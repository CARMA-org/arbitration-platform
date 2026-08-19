#!/usr/bin/env python3
"""
Joint multi-resource allocation solver.

Objective:
    maximize  sum_i c_i * log(Phi_i(a_i))
subject to:
    sum_i a_ij <= Q_j        for each resource j
    a_ij >= min_ij           for each agent i, resource j
    a_ij <= ideal_ij         for each agent i, resource j

Supported utility families Phi_i (see docs/MODEL_SUPPORT.md):
    LINEAR        Phi = sum_j beta_j a_j
    COBB_DOUGLAS  Phi = prod_j a_j^beta_j   with sum_j beta_j = 1
    CES           Phi = (sum_j beta_j a_j^rho)^(1/rho),  rho < 1, rho != 0
                  rho == 1 handled as LINEAR, rho -> 0 handled as COBB_DOUGLAS
    LEONTIEF      Phi = min_j a_j / r_j,    r_j > 0

Every other historical type name is reported as unsupported_model and is never
silently replaced by a linear surrogate.

Usage (stdin JSON -> stdout JSON):
    echo '{"n_agents":2,...}' | python3 joint_solver.py
"""

import sys
import json
import numpy as np

try:
    import cvxpy as cp
    CVXPY_AVAILABLE = True
except ImportError:
    CVXPY_AVAILABLE = False

SUPPORTED = {"LINEAR", "COBB_DOUGLAS", "CES", "LEONTIEF"}
UNSUPPORTED = {
    "SQRT", "LOG", "THRESHOLD", "SATIATION", "NESTED_CES",
    "SOFTPLUS_LOSS_AVERSION", "ASYMMETRIC_LOG_LOSS_AVERSION",
}
POS_FLOOR = 1e-9


class ValidationError(Exception):
    pass


class UnsupportedModel(Exception):
    pass


class ModelConstructionError(Exception):
    pass


def _normalize_configs(data, n):
    cfgs = data.get("utility_configs", None)
    if cfgs is None:
        cfgs = [{"type": "LINEAR"}] * n
    elif isinstance(cfgs, dict):
        cfgs = [cfgs] * n
    if len(cfgs) != n:
        raise ValidationError(
            f"utility_configs has {len(cfgs)} entries but n_agents={n}")
    out = []
    for c in cfgs:
        out.append({"type": "LINEAR"} if c is None else dict(c))
    return out


def _canonical_type(cfg):
    t = cfg.get("type", "LINEAR")
    if t == "CES":
        rho = float(cfg.get("rho", 0.5))
        if abs(rho - 1.0) < 1e-12:
            return "CES(rho=1)->LINEAR"
        if abs(rho) < 1e-12:
            return "CES(rho=0)->COBB_DOUGLAS"
        return f"CES(rho={rho})"
    return t


def validate_inputs(data):
    for key in ("n_agents", "n_resources", "preferences", "priority_weights",
                "capacities", "minimums", "ideals"):
        if key not in data:
            raise ValidationError(f"missing required field: {key}")

    n = int(data["n_agents"])
    m = int(data["n_resources"])
    if n <= 0 or m <= 0:
        raise ValidationError("n_agents and n_resources must be positive")

    W = np.asarray(data["preferences"], dtype=float)
    c = np.asarray(data["priority_weights"], dtype=float)
    Q = np.asarray(data["capacities"], dtype=float)
    mins = np.asarray(data["minimums"], dtype=float)
    ideals = np.asarray(data["ideals"], dtype=float)

    if W.shape != (n, m):
        raise ValidationError(f"preferences shape {W.shape} != ({n},{m})")
    if c.shape != (n,):
        raise ValidationError(f"priority_weights shape {c.shape} != ({n},)")
    if Q.shape != (m,):
        raise ValidationError(f"capacities shape {Q.shape} != ({m},)")
    if mins.shape != (n, m):
        raise ValidationError(f"minimums shape {mins.shape} != ({n},{m})")
    if ideals.shape != (n, m):
        raise ValidationError(f"ideals shape {ideals.shape} != ({n},{m})")

    for name, arr in (("preferences", W), ("priority_weights", c),
                      ("capacities", Q), ("minimums", mins), ("ideals", ideals)):
        if not np.all(np.isfinite(arr)):
            raise ValidationError(f"{name} contains non-finite values")

    if np.any(W < 0):
        raise ValidationError("preferences must be nonnegative")
    if np.any(Q < 0):
        raise ValidationError("capacities must be nonnegative")
    if np.any(mins < 0):
        raise ValidationError("minimums must be nonnegative")
    if np.any(ideals < 0):
        raise ValidationError("ideals must be nonnegative")
    if np.any(c <= 0):
        raise ValidationError("priority_weights must be strictly positive")
    if np.any(mins > ideals + 1e-12):
        raise ValidationError("minimums exceed ideals for some cell")

    min_totals = mins.sum(axis=0)
    for j in range(m):
        if min_totals[j] > Q[j] + 1e-9:
            raise ValidationError(
                f"resource {j}: sum of minimums {min_totals[j]} exceeds capacity {Q[j]}")

    cfgs = _normalize_configs(data, n)
    for i, cfg in enumerate(cfgs):
        t = cfg.get("type", "LINEAR")
        if t in UNSUPPORTED:
            raise UnsupportedModel(
                f"agent {i}: utility type {t} is not supported (see docs/MODEL_SUPPORT.md)")
        if t not in SUPPORTED:
            raise UnsupportedModel(f"agent {i}: unknown utility type {t}")
        if t == "COBB_DOUGLAS":
            beta = W[i, :]
            if abs(beta.sum() - 1.0) > 1e-6:
                raise ValidationError(
                    f"agent {i}: COBB_DOUGLAS weights must sum to 1 (got {beta.sum()})")
            if np.any((beta > 0) & (ideals[i, :] <= 0)):
                raise ValidationError(
                    f"agent {i}: COBB_DOUGLAS needs a strictly positive allocation "
                    f"on every positively-weighted resource")
        if t == "CES":
            rho = float(cfg.get("rho", 0.5))
            if rho > 1.0 + 1e-12:
                raise ValidationError(f"agent {i}: CES requires rho <= 1 (got {rho})")
            if rho < 0 and np.any((W[i, :] > 0) & (ideals[i, :] <= 0)):
                raise ValidationError(
                    f"agent {i}: CES with rho<0 needs strictly positive allocations")
        if t == "LEONTIEF":
            req = cfg.get("requirements", None)
            if req is None:
                raise ValidationError(
                    f"agent {i}: LEONTIEF requires an explicit 'requirements' vector r_j>0")
            r = np.asarray(req, dtype=float)
            if r.shape != (m,):
                raise ValidationError(f"agent {i}: LEONTIEF requirements shape {r.shape} != ({m},)")
            if np.any(r <= 0) or not np.all(np.isfinite(r)):
                raise ValidationError(f"agent {i}: LEONTIEF requirements must be finite and > 0")

    return n, m, W, c, Q, mins, ideals, cfgs


def _agent_log_utility_expr(cfg, W, A, i, aux_vars, constraints, meta):
    """Return a concave CVXPY expression for log(Phi_i(a_i))."""
    t = cfg.get("type", "LINEAR")
    row = A[i, :]
    beta = W[i, :]

    if t == "LINEAR":
        return cp.log(beta @ row)

    if t == "COBB_DOUGLAS":
        return beta @ cp.log(row)

    if t == "CES":
        rho = float(cfg.get("rho", 0.5))
        if abs(rho - 1.0) < 1e-12:
            return cp.log(beta @ row)
        if abs(rho) < 1e-12:
            return beta @ cp.log(row)
        scale = np.power(np.maximum(beta, 0.0), 1.0 / rho)
        phi = cp.pnorm(cp.multiply(scale, row), rho)
        try:
            meta.setdefault("ces_pnorm_p", {})[str(i)] = str(getattr(phi, "p", rho))
        except Exception:
            pass
        return cp.log(phi)

    if t == "LEONTIEF":
        r = np.asarray(cfg["requirements"], dtype=float)
        u = cp.Variable(nonneg=True)
        aux_vars.append(u)
        for j in range(len(r)):
            constraints.append(u <= row[j] / r[j])
        return cp.log(u)

    raise UnsupportedModel(f"utility type {t} is not supported")


def eval_utility_np(cfg, w, a):
    """Evaluate Phi_i for a concrete allocation (supported families only)."""
    t = cfg.get("type", "LINEAR")
    a = np.asarray(a, dtype=float)
    w = np.asarray(w, dtype=float)
    if t == "LINEAR":
        return float(np.sum(w * a))
    if t == "COBB_DOUGLAS":
        return float(np.prod(np.power(np.maximum(a, POS_FLOOR), w)))
    if t == "CES":
        rho = float(cfg.get("rho", 0.5))
        if abs(rho - 1.0) < 1e-12:
            return float(np.sum(w * a))
        if abs(rho) < 1e-12:
            return float(np.prod(np.power(np.maximum(a, POS_FLOOR), w)))
        aa = np.maximum(a, POS_FLOOR) if rho < 0 else np.maximum(a, 0.0)
        s = np.sum(w * np.power(aa, rho))
        return float(np.power(max(s, POS_FLOOR), 1.0 / rho))
    if t == "LEONTIEF":
        r = np.asarray(cfg["requirements"], dtype=float)
        return float(np.min(a / r))
    raise UnsupportedModel(f"utility type {t} is not supported")


def solve_joint_allocation(data):
    if not CVXPY_AVAILABLE:
        return {
            "status": "solver_error", "requested_utility": None, "solved_utility": None,
            "solver": None, "objective_value": None, "allocations": None, "utilities": None,
            "warnings": [], "error_type": "MissingDependency",
            "error_message": "cvxpy is not installed",
        }

    warnings = []
    meta = {}
    try:
        n, m, W, c, Q, mins, ideals, cfgs = validate_inputs(data)
    except ValidationError as e:
        return _err("validation_error", "ValidationError", str(e), data)
    except UnsupportedModel as e:
        return _err("unsupported_model", "UnsupportedModel", str(e), data)

    requested = [_canonical_type(cfg) for cfg in cfgs]

    A = cp.Variable((n, m), nonneg=True)
    constraints = [cp.sum(A, axis=0) <= Q, A >= mins, A <= ideals]
    aux_vars = []
    obj_terms = []
    try:
        for i in range(n):
            log_u = _agent_log_utility_expr(cfgs[i], W, A, i, aux_vars, constraints, meta)
            obj_terms.append(c[i] * log_u)
    except UnsupportedModel as e:
        return _err("unsupported_model", "UnsupportedModel", str(e), data)
    except Exception as e:
        return _err("model_error", type(e).__name__, str(e), data)

    problem = cp.Problem(cp.Maximize(cp.sum(obj_terms)), constraints)

    if not problem.is_dcp():
        return _err("model_error", "NotDCP",
                    "assembled problem is not DCP; refusing to solve a different model", data)

    solve_err = None
    used_solver = None
    for solver_name in ("CLARABEL", "ECOS", "SCS"):
        solver = getattr(cp, solver_name, None)
        if solver is None:
            continue
        try:
            problem.solve(solver=solver, verbose=False)
        except Exception as e:
            solve_err = f"{solver_name}: {e}"
            continue
        if problem.status in (cp.OPTIMAL, cp.OPTIMAL_INACCURATE):
            used_solver = solver_name
            break
        solve_err = f"{solver_name}: status={problem.status}"

    if problem.status == cp.INFEASIBLE:
        return _err("infeasible", None, solve_err or "problem is infeasible", data)
    if problem.status == cp.UNBOUNDED:
        return _err("unbounded", None, solve_err or "problem is unbounded", data)
    if problem.status not in (cp.OPTIMAL, cp.OPTIMAL_INACCURATE):
        return _err("solver_error", "SolverError", solve_err or f"status={problem.status}", data)

    if problem.status == cp.OPTIMAL_INACCURATE:
        warnings.append("solver reported optimal_inaccurate")

    allocations = np.maximum(A.value, 0.0)
    utilities = [eval_utility_np(cfgs[i], W[i, :], allocations[i, :]) for i in range(n)]
    welfare = float(np.sum(c * np.log(np.maximum(utilities, POS_FLOOR))))

    return {
        "status": "optimal_inaccurate" if problem.status == cp.OPTIMAL_INACCURATE else "optimal",
        "requested_utility": requested if len(set(requested)) > 1 else requested[0],
        "solved_utility": requested if len(set(requested)) > 1 else requested[0],
        "solver": used_solver,
        "objective_value": float(problem.value),
        "allocations": allocations.tolist(),
        "utilities": utilities,
        "welfare": welfare,
        "warnings": warnings,
        "error_type": None,
        "error_message": None,
        "meta": meta,
    }


def _err(status, error_type, msg, data):
    n = data.get("n_agents")
    try:
        cfgs = _normalize_configs(data, int(n)) if n else []
        requested = [_canonical_type(cfg) for cfg in cfgs]
        req = requested if len(set(requested)) > 1 else (requested[0] if requested else None)
    except Exception:
        req = None
    return {
        "status": status, "requested_utility": req, "solved_utility": None,
        "solver": None, "objective_value": None, "allocations": None, "utilities": None,
        "warnings": [], "error_type": error_type, "error_message": msg, "meta": {},
    }


def main():
    try:
        raw = sys.stdin.read()
        if not raw.strip():
            print(json.dumps(_err("validation_error", "EmptyInput", "no input provided", {})))
            sys.exit(0)
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        print(json.dumps(_err("validation_error", "JSONDecodeError", str(e), {})))
        sys.exit(0)
    result = solve_joint_allocation(data)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
