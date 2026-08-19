import argparse
import json
import os
import numpy as np
import cvxpy as cp

from lib.generators import gen_breadth_controlled
from lib.seeds import derive_seed

HERE = os.path.dirname(os.path.abspath(__file__))
N_INCUMBENT, N_RES = 6, 4
CAP, LB, UB = 100.0, 1.0, 100.0
B_REP, LAM_REP = 3.0, 0.5


def solve_linear_nash(W, c, Q, mins, ideals, floor_W=None, floor_vals=None):
    n, m = W.shape
    A = cp.Variable((n, m), nonneg=True)
    obj = cp.Maximize(cp.sum([c[i] * cp.log(W[i] @ A[i]) for i in range(n)]))
    cons = [cp.sum(A, axis=0) <= Q, A >= mins, A <= ideals]
    if floor_W is not None:
        for i in range(len(floor_W)):
            cons.append(floor_W[i] @ A[i] >= floor_vals[i] - 1e-6)
    prob = cp.Problem(obj, cons)
    try:
        prob.solve(solver=cp.CLARABEL)
    except cp.error.SolverError:
        return None, "solver_error"
    if prob.status not in (cp.OPTIMAL, cp.OPTIMAL_INACCURATE):
        return None, prob.status
    return np.maximum(A.value, 0.0), "optimal"


def base_instance(seed):
    rng = np.random.default_rng(seed)
    inst, _, _ = gen_breadth_controlled(rng, N_INCUMBENT, N_RES, B_REP, LAM_REP, CAP, LB, UB)
    return inst


def apply_event(event, inst, seed):
    W, c = inst.W.copy(), inst.c.copy()
    Q = inst.Q.copy()
    mins = inst.mins.copy()
    ideals = inst.ideals.copy()
    if event == "drift":
        rng = np.random.default_rng(derive_seed("exp5_drift", seed))
        noise = rng.uniform(0.7, 1.3, size=W.shape)
        W = W * noise
        W = W / W.sum(axis=1, keepdims=True)
    elif event == "arrival":
        rng = np.random.default_rng(derive_seed("exp5_arrival", seed))
        w_new = rng.uniform(0.2, 1.0, size=N_RES)
        w_new = w_new / w_new.sum()
        W = np.vstack([W, w_new])
        c = np.append(c, 1.0)
        mins = np.vstack([mins, np.full(N_RES, LB)])
        ideals = np.vstack([ideals, np.full(N_RES, UB)])
    elif event == "capacity":
        Q = Q * 0.7
    return W, c, Q, mins, ideals


def run(event, seeds):
    recs = []
    for seed in seeds:
        inst = base_instance(seed)
        A0, st0 = solve_linear_nash(inst.W, inst.c, inst.Q, inst.mins, inst.ideals)
        if A0 is None:
            continue
        u0_old = np.array([inst.W[i] @ A0[i] for i in range(N_INCUMBENT)])

        W1, c1, Q1, mins1, ideals1 = apply_event(event, inst, seed)
        n1 = W1.shape[0]

        A_un, st_un = solve_linear_nash(W1, c1, Q1, mins1, ideals1)
        floor_W = inst.W
        A_cm, st_cm = solve_linear_nash(W1, c1, Q1, mins1, ideals1,
                                        floor_W=floor_W, floor_vals=u0_old)

        rec = {"event": event, "seed": int(seed),
               "unrestricted_status": st_un, "commitment_status": st_cm,
               "commitment_feasible": A_cm is not None}
        if A_un is not None:
            obj_un = sum(c1[i] * np.log(max(W1[i] @ A_un[i], 1e-9)) for i in range(n1))
            rec["objective_unrestricted"] = float(obj_un)
        if A_cm is not None and A_un is not None:
            obj_cm = sum(c1[i] * np.log(max(W1[i] @ A_cm[i], 1e-9)) for i in range(n1))
            rec["objective_commitment"] = float(obj_cm)
            rec["objective_cost_of_commitment"] = float(rec["objective_unrestricted"] - obj_cm)
            u_new_old_repr = np.array([inst.W[i] @ A_cm[i] for i in range(N_INCUMBENT)])
            rec["frac_floors_binding"] = float(np.mean(
                np.abs(u_new_old_repr - u0_old) < 1e-3))
            # individual losses under old utility representation, unrestricted reopt
            u_un_old_repr = np.array([inst.W[i] @ A_un[i] for i in range(N_INCUMBENT)])
            losses = (u_un_old_repr - u0_old) / np.maximum(u0_old, 1e-9)
            rec["worst_incumbent_loss_old_repr_unrestricted"] = float(losses.min())
            rec["allocation_movement_l1"] = float(np.sum(np.abs(A_cm[:N_INCUMBENT] - A0)))
        if event == "arrival" and A_un is not None:
            rec["entrant_admitted_unrestricted"] = bool(A_un[-1].sum() > mins1[-1].sum() + 1e-6)
            if A_cm is not None:
                rec["entrant_admitted_commitment"] = bool(A_cm[-1].sum() > mins1[-1].sum() + 1e-6)
        recs.append(rec)
    return recs


def summarize(event, recs):
    feasible = [r for r in recs if r["commitment_feasible"]]
    out = {"event": event, "n_seeds": len(recs),
           "commitment_feasibility_rate": len(feasible) / len(recs) if recs else None}
    costs = [r["objective_cost_of_commitment"] for r in recs if "objective_cost_of_commitment" in r]
    if costs:
        out["median_objective_cost_of_commitment"] = float(np.median(costs))
        out["p95_objective_cost_of_commitment"] = float(np.percentile(costs, 95))
    binding = [r["frac_floors_binding"] for r in recs if "frac_floors_binding" in r]
    if binding:
        out["median_frac_floors_binding"] = float(np.median(binding))
    worst = [r["worst_incumbent_loss_old_repr_unrestricted"] for r in recs
             if "worst_incumbent_loss_old_repr_unrestricted" in r]
    if worst:
        out["median_worst_incumbent_loss_unrestricted"] = float(np.median(worst))
    if event == "arrival":
        adm = [r.get("entrant_admitted_unrestricted") for r in recs if "entrant_admitted_unrestricted" in r]
        out["entrant_admission_rate_unrestricted"] = float(np.mean(adm)) if adm else None
    return out


def main():
    import csv
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    args = ap.parse_args()
    n = 20 if args.smoke else 100
    tag = "smoke" if args.smoke else "full"
    seeds = [derive_seed("exp5", i) for i in range(n)]

    all_recs = []
    summaries = []
    for event in ("drift", "arrival", "capacity"):
        recs = run(event, seeds)
        all_recs.extend(recs)
        summaries.append(summarize(event, recs))

    os.makedirs(os.path.join(HERE, "results", "raw"), exist_ok=True)
    raw_path = os.path.join(HERE, "results", "raw", f"experiment5_{tag}.csv")
    keys = sorted({k for r in all_recs for k in r})
    with open(raw_path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        for r in all_recs:
            w.writerow(r)

    out = {"experiment": "experiment5", "tag": tag, "n_seeds": n, "summaries": summaries,
           "raw_csv": os.path.relpath(raw_path, HERE)}
    with open(os.path.join(HERE, "results", f"experiment5_{tag}_summary.json"), "w") as f:
        json.dump(out, f, indent=2)
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
