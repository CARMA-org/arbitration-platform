#!/usr/bin/env python3
"""
Joint Multi-Resource Allocation Solver with Nonlinear Utility Support

Solves the joint allocation problem:
    maximize: Σᵢ cᵢ · log(Φᵢ(A))

where Φᵢ is the utility function which can be:
    - LINEAR:       Φ = Σⱼ wⱼ·aⱼ
    - SQRT:         Φ = (Σⱼ wⱼ·√aⱼ)²
    - LOG:          Φ = Σⱼ wⱼ·log(1+aⱼ)
    - COBB_DOUGLAS: Φ = Π aⱼ^wⱼ
    - CES:          Φ = (Σⱼ wⱼ·aⱼ^ρ)^(1/ρ)

subject to:
    Σᵢ aᵢⱼ ≤ Qⱼ           ∀j (resource capacity)
    aᵢⱼ ≥ minᵢⱼ           ∀i,j (minimum requirements)
    aᵢⱼ ≤ idealᵢⱼ         ∀i,j (maximum requests)

For strictly concave utilities (all except Leontief), the problem is convex
and can be solved exactly using interior-point methods.

Usage:
    echo '{"n_agents": 3, "n_resources": 2, ...}' | python3 joint_solver.py
    
Requirements:
    pip install cvxpy clarabel numpy

Author: CARMA Arbitration Platform
"""

import sys
import json
import numpy as np

try:
    import cvxpy as cp
    CVXPY_AVAILABLE = True
except ImportError:
    CVXPY_AVAILABLE = False


# ============================================================================
# Utility Function Implementations
# ============================================================================

def compute_linear_utility(W, A, agent_idx):
    """Linear utility: Φ = Σⱼ wⱼ·aⱼ"""
    return cp.sum(cp.multiply(W[agent_idx, :], A[agent_idx, :]))


def compute_sqrt_utility(W, A, agent_idx, epsilon=1e-6):
    """Square root utility: Φ = (Σⱼ wⱼ·√aⱼ)²"""
    sqrt_terms = cp.sqrt(A[agent_idx, :] + epsilon)
    weighted_sum = cp.sum(cp.multiply(W[agent_idx, :], sqrt_terms))
    return cp.square(weighted_sum)


def compute_log_utility(W, A, agent_idx, epsilon=1e-6):
    """Logarithmic utility: Φ = Σⱼ wⱼ·log(1+aⱼ)"""
    log_terms = cp.log(1 + A[agent_idx, :] + epsilon)
    return cp.sum(cp.multiply(W[agent_idx, :], log_terms))


def compute_cobb_douglas_utility(W, A, agent_idx, epsilon=1e-6):
    """
    Cobb-Douglas utility: Φ = Π aⱼ^wⱼ
    
    We use log transformation: log(Φ) = Σⱼ wⱼ·log(aⱼ)
    Then Φ = exp(Σⱼ wⱼ·log(aⱼ))
    """
    log_terms = cp.log(A[agent_idx, :] + epsilon)
    weighted_log_sum = cp.sum(cp.multiply(W[agent_idx, :], log_terms))
    return cp.exp(weighted_log_sum)


def compute_ces_utility(W, A, agent_idx, rho, epsilon=1e-6):
    """
    CES utility: Φ = (Σⱼ wⱼ·aⱼ^ρ)^(1/ρ)
    
    Special cases:
        ρ → 1: Linear
        ρ = 0.5: Square root-like
        ρ → 0: Cobb-Douglas
        ρ → -∞: Leontief
    """
    if abs(rho - 1.0) < 0.01:
        # Linear case
        return compute_linear_utility(W, A, agent_idx)
    
    if abs(rho) < 0.01:
        # Cobb-Douglas case
        return compute_cobb_douglas_utility(W, A, agent_idx, epsilon)
    
    if rho < -10:
        # Leontief approximation - use geometric mean as smooth approximation
        # This won't be exactly Leontief but avoids non-differentiability
        return compute_cobb_douglas_utility(W, A, agent_idx, epsilon)
    
    # General CES case
    power_terms = cp.power(A[agent_idx, :] + epsilon, rho)
    weighted_sum = cp.sum(cp.multiply(W[agent_idx, :], power_terms))
    return cp.power(weighted_sum, 1.0 / rho)


def get_utility_for_agent(W, A, agent_idx, utility_config, epsilon=1e-6):
    """
    Get the utility expression for a specific agent based on their utility config.
    """
    if utility_config is None:
        return compute_linear_utility(W, A, agent_idx)
    
    util_type = utility_config.get('type', 'LINEAR')
    
    if util_type == 'LINEAR':
        return compute_linear_utility(W, A, agent_idx)
    elif util_type == 'SQRT':
        return compute_sqrt_utility(W, A, agent_idx, epsilon)
    elif util_type == 'LOG':
        return compute_log_utility(W, A, agent_idx, epsilon)
    elif util_type == 'COBB_DOUGLAS':
        return compute_cobb_douglas_utility(W, A, agent_idx, epsilon)
    elif util_type == 'CES':
        rho = utility_config.get('rho', 0.5)
        return compute_ces_utility(W, A, agent_idx, rho, epsilon)
    elif util_type == 'LEONTIEF':
        # Approximate with low-rho CES
        return compute_ces_utility(W, A, agent_idx, -5.0, epsilon)
    else:
        return compute_linear_utility(W, A, agent_idx)


# ============================================================================
# Numpy Utility Evaluation (for result reporting)
# ============================================================================

def eval_linear_utility_np(w, a):
    """Evaluate linear utility with numpy."""
    return np.sum(w * a)


def eval_sqrt_utility_np(w, a, epsilon=1e-6):
    """Evaluate sqrt utility with numpy."""
    sqrt_terms = np.sqrt(np.maximum(a, epsilon))
    return np.sum(w * sqrt_terms) ** 2


def eval_log_utility_np(w, a, epsilon=1e-6):
    """Evaluate log utility with numpy."""
    return np.sum(w * np.log(1 + a + epsilon))


def eval_cobb_douglas_utility_np(w, a, epsilon=1e-6):
    """Evaluate Cobb-Douglas utility with numpy."""
    return np.prod(np.maximum(a, epsilon) ** w)


def eval_ces_utility_np(w, a, rho, epsilon=1e-6):
    """Evaluate CES utility with numpy."""
    if abs(rho - 1.0) < 0.01:
        return eval_linear_utility_np(w, a)
    if abs(rho) < 0.01:
        return eval_cobb_douglas_utility_np(w, a, epsilon)
    power_terms = np.maximum(a, epsilon) ** rho
    return np.sum(w * power_terms) ** (1.0 / rho)


def eval_utility_np(w, a, util_config):
    """Evaluate utility with numpy based on config."""
    if util_config is None:
        return eval_linear_utility_np(w, a)
    
    util_type = util_config.get('type', 'LINEAR')
    
    if util_type == 'LINEAR':
        return eval_linear_utility_np(w, a)
    elif util_type == 'SQRT':
        return eval_sqrt_utility_np(w, a)
    elif util_type == 'LOG':
        return eval_log_utility_np(w, a)
    elif util_type == 'COBB_DOUGLAS':
        return eval_cobb_douglas_utility_np(w, a)
    elif util_type == 'CES':
        rho = util_config.get('rho', 0.5)
        return eval_ces_utility_np(w, a, rho)
    elif util_type == 'LEONTIEF':
        # True Leontief
        mask = w > 1e-8
        if not np.any(mask):
            return 0.0
        return np.min(a[mask] / w[mask])
    else:
        return eval_linear_utility_np(w, a)


# ============================================================================
# Main Solver
# ============================================================================

def solve_joint_allocation(data):
    """
    Solve the joint multi-resource allocation problem with nonlinear utilities.
    """
    n = data['n_agents']
    m = data['n_resources']
    
    # Extract matrices
    W = np.array(data['preferences'])           # n x m preference weights
    c = np.array(data['priority_weights'])      # n priority weights
    Q = np.array(data['capacities'])            # m capacities
    mins = np.array(data['minimums'])           # n x m minimums
    ideals = np.array(data['ideals'])           # n x m ideals
    
    # Get utility configurations (one per agent, or None for all linear)
    utility_configs = data.get('utility_configs', None)
    if utility_configs is None:
        utility_configs = [None] * n
    elif isinstance(utility_configs, dict):
        # Single config for all agents
        utility_configs = [utility_configs] * n
    
    # Validate inputs
    assert W.shape == (n, m), f"Preferences shape mismatch: {W.shape} vs ({n}, {m})"
    assert c.shape == (n,), f"Priority weights shape mismatch"
    assert Q.shape == (m,), f"Capacities shape mismatch"
    assert mins.shape == (n, m), f"Minimums shape mismatch"
    assert ideals.shape == (n, m), f"Ideals shape mismatch"
    
    # Check feasibility
    min_totals = np.sum(mins, axis=0)
    for j in range(m):
        if min_totals[j] > Q[j]:
            return {
                "status": "infeasible",
                "error": f"Resource {j}: sum of minimums ({min_totals[j]}) exceeds capacity ({Q[j]})",
                "allocations": mins.tolist(),
                "objective": float('-inf'),
                "solver": "none"
            }
    
    # Ensure bounds are consistent
    mins = np.minimum(mins, ideals)
    
    # Decision variables
    A = cp.Variable((n, m), nonneg=True)
    
    # Small epsilon for numerical stability
    epsilon = 1e-6
    
    # Build utility variables for each agent
    utilities = []
    for i in range(n):
        u_i = get_utility_for_agent(W, A, i, utility_configs[i], epsilon)
        utilities.append(u_i)
    
    # Compute minimum achievable utility for each agent
    min_utilities = []
    for i in range(n):
        min_u = eval_utility_np(W[i, :], mins[i, :], utility_configs[i])
        min_utilities.append(max(epsilon, min_u))
    min_utilities = np.array(min_utilities)
    
    # Objective: maximize Σᵢ cᵢ · log(Φᵢ)
    # For DCP compliance, we use auxiliary variables
    u = cp.Variable(n, nonneg=True)
    
    objective = cp.Maximize(c @ cp.log(u))
    
    # Constraints
    constraints = [
        # Resource capacity
        cp.sum(A, axis=0) <= Q,
        # Minimum requirements
        A >= mins,
        # Maximum requests
        A <= ideals,
        # Minimum utility
        u >= min_utilities,
    ]
    
    # Link u to actual utility - depends on utility type
    # For linear utilities, this is straightforward
    # For nonlinear, we need to be careful about DCP compliance
    
    # Check if all utilities are linear
    all_linear = all(
        (cfg is None or cfg.get('type', 'LINEAR') == 'LINEAR')
        for cfg in utility_configs
    )
    
    if all_linear:
        # Simple linear case - direct constraint
        constraints.append(u == cp.sum(cp.multiply(W, A), axis=1))
    else:
        # For nonlinear utilities, we need individual constraints
        # and may need to use different formulations
        for i in range(n):
            cfg = utility_configs[i]
            if cfg is None or cfg.get('type', 'LINEAR') == 'LINEAR':
                constraints.append(u[i] == cp.sum(cp.multiply(W[i, :], A[i, :])))
            elif cfg.get('type') == 'SQRT':
                # u[i] <= (Σⱼ wⱼ·√aⱼ)²
                # This is concave in A, so u[i] <= f(A) is DCP-compliant
                sqrt_sum = cp.sum(cp.multiply(W[i, :], cp.sqrt(A[i, :] + epsilon)))
                constraints.append(u[i] <= cp.square(sqrt_sum))
            elif cfg.get('type') == 'LOG':
                # u[i] <= Σⱼ wⱼ·log(1+aⱼ)
                log_sum = cp.sum(cp.multiply(W[i, :], cp.log(1 + A[i, :] + epsilon)))
                constraints.append(u[i] <= log_sum)
            elif cfg.get('type') == 'COBB_DOUGLAS':
                # u[i] <= Π aⱼ^wⱼ = exp(Σⱼ wⱼ·log(aⱼ))
                # Using log transformation
                log_prod = cp.sum(cp.multiply(W[i, :], cp.log(A[i, :] + epsilon)))
                constraints.append(u[i] <= cp.exp(log_prod))
            elif cfg.get('type') == 'CES':
                rho = cfg.get('rho', 0.5)
                if rho > 0 and rho < 1:
                    # Concave power
                    power_sum = cp.sum(cp.multiply(W[i, :], cp.power(A[i, :] + epsilon, rho)))
                    constraints.append(u[i] <= cp.power(power_sum, 1.0 / rho))
                else:
                    # Fall back to linear approximation at current point
                    constraints.append(u[i] == cp.sum(cp.multiply(W[i, :], A[i, :])))
            else:
                # Default to linear
                constraints.append(u[i] == cp.sum(cp.multiply(W[i, :], A[i, :])))
    
    # Solve
    problem = cp.Problem(objective, constraints)
    
    solvers_to_try = ['CLARABEL', 'ECOS', 'SCS']
    used_solver = None
    solve_error = None
    
    for solver_name in solvers_to_try:
        try:
            solver = getattr(cp, solver_name, None)
            if solver is None:
                continue
            problem.solve(solver=solver, verbose=False)
            if problem.status in [cp.OPTIMAL, cp.OPTIMAL_INACCURATE]:
                used_solver = solver_name
                break
            else:
                solve_error = f"{solver_name}: status={problem.status}"
        except cp.error.SolverError as e:
            solve_error = f"{solver_name}: {str(e)}"
            continue
        except Exception as e:
            solve_error = f"{solver_name}: {str(e)}"
            continue
    
    if problem.status not in [cp.OPTIMAL, cp.OPTIMAL_INACCURATE]:
        return {
            "status": "infeasible",
            "error": solve_error or f"Problem status: {problem.status}",
            "allocations": mins.tolist(),
            "objective": float('-inf'),
            "solver": used_solver or "none"
        }
    
    # Extract solution
    allocations = A.value
    utilities_val = u.value
    
    # Ensure allocations are non-negative
    allocations = np.maximum(allocations, 0)
    
    # Calculate actual utilities using numpy
    actual_utilities = []
    for i in range(n):
        u_i = eval_utility_np(W[i, :], allocations[i, :], utility_configs[i])
        actual_utilities.append(u_i)
    actual_utilities = np.array(actual_utilities)
    
    # Calculate welfare
    welfare = np.sum(c * np.log(np.maximum(actual_utilities, epsilon)))
    
    return {
        "status": "optimal",
        "solver": used_solver,
        "allocations": allocations.tolist(),
        "objective": float(problem.value),
        "utilities": actual_utilities.tolist(),
        "welfare": float(welfare),
        "utility_types": [
            (cfg.get('type', 'LINEAR') if cfg else 'LINEAR')
            for cfg in utility_configs
        ]
    }


def solve_sequential_fallback(data):
    """
    Fallback solver when cvxpy is not available.
    Solves each resource independently using water-filling.
    Only supports linear utilities.
    """
    n = data['n_agents']
    m = data['n_resources']
    
    W = np.array(data['preferences'])
    c = np.array(data['priority_weights'])
    Q = np.array(data['capacities'])
    mins = np.array(data['minimums'])
    ideals = np.array(data['ideals'])
    
    utility_configs = data.get('utility_configs', None)
    
    allocations = np.zeros((n, m))
    
    for j in range(m):
        capacity = Q[j]
        agent_mins = mins[:, j]
        agent_ideals = ideals[:, j]
        weights = c
        
        alloc = water_filling(weights, agent_mins, agent_ideals, capacity)
        allocations[:, j] = alloc
    
    # Calculate utilities
    utilities = []
    for i in range(n):
        cfg = utility_configs[i] if utility_configs else None
        u_i = eval_utility_np(W[i, :], allocations[i, :], cfg)
        utilities.append(u_i)
    utilities = np.array(utilities)
    
    epsilon = 1e-8
    welfare = np.sum(c * np.log(utilities + epsilon))
    
    return {
        "status": "sequential_fallback",
        "solver": "water_filling",
        "allocations": allocations.tolist(),
        "objective": float(welfare),
        "utilities": utilities.tolist(),
        "welfare": float(welfare),
        "warning": "Using sequential optimization - LOCAL Pareto only"
    }


def water_filling(weights, mins, ideals, capacity):
    """Water-filling algorithm for single-resource allocation."""
    n = len(weights)
    alloc = mins.copy().astype(float)
    remaining = capacity - np.sum(mins)
    
    if remaining <= 0:
        return alloc
    
    frozen = np.zeros(n, dtype=bool)
    
    for _ in range(100):
        active = ~frozen & (alloc < ideals)
        if not np.any(active):
            break
        
        active_weight = np.sum(weights[active])
        if active_weight < 1e-9:
            equal_share = remaining / np.sum(active)
            for i in np.where(active)[0]:
                slack = ideals[i] - alloc[i]
                alloc[i] += min(equal_share, slack)
            break
        
        bottleneck_idx = -1
        min_fill_ratio = float('inf')
        
        for i in np.where(active)[0]:
            slack = ideals[i] - alloc[i]
            share = (weights[i] / active_weight) * remaining
            if share > slack:
                fill_ratio = slack / share
                if fill_ratio < min_fill_ratio:
                    min_fill_ratio = fill_ratio
                    bottleneck_idx = i
        
        if bottleneck_idx >= 0 and min_fill_ratio < 1.0:
            to_distribute = remaining * min_fill_ratio
            for i in np.where(active)[0]:
                share = (weights[i] / active_weight) * to_distribute
                alloc[i] += share
            remaining -= to_distribute
            alloc[bottleneck_idx] = ideals[bottleneck_idx]
            frozen[bottleneck_idx] = True
        else:
            for i in np.where(active)[0]:
                share = (weights[i] / active_weight) * remaining
                alloc[i] += share
            remaining = 0
            break
    
    alloc = np.maximum(mins, np.minimum(ideals, alloc))
    return alloc


def main():
    """Main entry point."""
    try:
        input_data = sys.stdin.read()
        if not input_data.strip():
            print(json.dumps({"error": "No input provided"}), file=sys.stderr)
            sys.exit(1)
        
        data = json.loads(input_data)
        
        if CVXPY_AVAILABLE:
            result = solve_joint_allocation(data)
        else:
            result = solve_sequential_fallback(data)
            result["warning"] = "cvxpy not available - using sequential fallback"
        
        print(json.dumps(result))
        
    except json.JSONDecodeError as e:
        print(json.dumps({"error": f"Invalid JSON: {str(e)}"}), file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(json.dumps({"error": str(e), "type": type(e).__name__}), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()