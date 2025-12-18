#!/usr/bin/env python3
"""
Joint Optimization Solver for Arbitration Platform

This script solves the multi-resource weighted proportional fairness problem:

    maximize: Σᵢ cᵢ · log(Σⱼ wᵢⱼ · aᵢⱼ)
    
    subject to:
        Σᵢ aᵢⱼ ≤ Qⱼ           ∀j  (resource capacity)
        aᵢⱼ ≥ minᵢⱼ           ∀i,j (minimum requirements)  
        aᵢⱼ ≤ idealᵢⱼ         ∀i,j (maximum requests)
        aᵢⱼ ≥ 0               ∀i,j (non-negativity)

Where:
    aᵢⱼ = allocation of resource j to agent i
    wᵢⱼ = agent i's preference weight for resource j (Σⱼ wᵢⱼ = 1)
    cᵢ = agent i's priority weight (BaseWeight + CurrencyBurned)
    Qⱼ = total capacity of resource j

Usage:
    echo '{"n_agents": 3, "n_resources": 2, ...}' | python3 joint_solver.py
    
    Or:
    python3 joint_solver.py < input.json > output.json

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

def solve_joint_allocation(data):
    """
    Solve the joint multi-resource allocation problem.
    """
    n = data['n_agents']
    m = data['n_resources']
    
    # Extract matrices
    W = np.array(data['preferences'])           # n x m preference weights
    c = np.array(data['priority_weights'])      # n priority weights
    Q = np.array(data['capacities'])            # m capacities
    mins = np.array(data['minimums'])           # n x m minimums
    ideals = np.array(data['ideals'])           # n x m ideals
    
    # Validate inputs
    assert W.shape == (n, m), f"Preferences shape mismatch: {W.shape} vs ({n}, {m})"
    assert c.shape == (n,), f"Priority weights shape mismatch: {c.shape} vs ({n},)"
    assert Q.shape == (m,), f"Capacities shape mismatch: {Q.shape} vs ({m},)"
    assert mins.shape == (n, m), f"Minimums shape mismatch: {mins.shape} vs ({n}, {m})"
    assert ideals.shape == (n, m), f"Ideals shape mismatch: {ideals.shape} vs ({n}, {m})"
    
    # Check feasibility: sum of minimums must not exceed capacity
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
    
    # FIX: Ensure minimums are not greater than ideals
    mins = np.minimum(mins, ideals)
    
    # FIX: Ensure ideals don't exceed capacity per resource
    for j in range(m):
        if np.sum(mins[:, j]) > Q[j]:
            # Scale down minimums proportionally if they exceed capacity
            scale = Q[j] / np.sum(mins[:, j])
            mins[:, j] = mins[:, j] * scale
    
    # Decision variables
    A = cp.Variable((n, m), nonneg=True)
    
    # Utility variable (explicit for DCP compliance)
    u = cp.Variable(n, nonneg=True)
    
    # Small epsilon to ensure strict positivity
    epsilon = 1e-6
    
    # FIX: Compute minimum achievable utility for each agent
    # This ensures the log constraint is feasible
    min_utilities = np.array([
        max(epsilon, np.sum(W[i, :] * mins[i, :]))
        for i in range(n)
    ])
    
    # Objective: maximize Σᵢ cᵢ · log(uᵢ)
    objective = cp.Maximize(c @ cp.log(u))
    
    # Constraints
    constraints = [
        # Link u to actual utility: u = Σⱼ wᵢⱼ · aᵢⱼ
        u == cp.sum(cp.multiply(W, A), axis=1),
        # FIX: Ensure utility is at least the minimum achievable utility
        u >= min_utilities,
        # Resource capacity: Σᵢ aᵢⱼ ≤ Qⱼ
        cp.sum(A, axis=0) <= Q,
        # Minimum requirements
        A >= mins,
        # Maximum requests (ideals)
        A <= ideals,
    ]
    
    # Solve
    problem = cp.Problem(objective, constraints)
    
    # Try solvers in order
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
    
    # Check solution status
    if problem.status not in [cp.OPTIMAL, cp.OPTIMAL_INACCURATE]:
        # Return fallback solution with minimums
        return {
            "status": "infeasible",
            "error": solve_error or f"Problem status: {problem.status}",
            "allocations": mins.tolist(),
            "objective": float('-inf'),
            "solver": used_solver or "none"
        }
    
    # Extract solution
    allocations = A.value
    utilities = u.value
    
    # Ensure allocations are non-negative (numerical precision)
    allocations = np.maximum(allocations, 0)
    
    # Calculate welfare
    welfare = np.sum(c * np.log(np.maximum(utilities, epsilon)))
    
    return {
        "status": "optimal",
        "solver": used_solver,
        "allocations": allocations.tolist(),
        "objective": float(problem.value),
        "utilities": utilities.tolist(),
        "welfare": float(welfare)
    }


def solve_sequential_fallback(data):
    """
    Fallback solver when cvxpy is not available.
    Solves each resource independently using water-filling.
    
    WARNING: This achieves LOCAL Pareto optimality only!
    """
    n = data['n_agents']
    m = data['n_resources']
    
    W = np.array(data['preferences'])
    c = np.array(data['priority_weights'])
    Q = np.array(data['capacities'])
    mins = np.array(data['minimums'])
    ideals = np.array(data['ideals'])
    
    allocations = np.zeros((n, m))
    
    # Solve each resource independently
    for j in range(m):
        capacity = Q[j]
        agent_mins = mins[:, j]
        agent_ideals = ideals[:, j]
        weights = c  # Use priority weights directly
        
        alloc = water_filling(weights, agent_mins, agent_ideals, capacity)
        allocations[:, j] = alloc
    
    # Calculate utilities and welfare
    utilities = np.sum(W * allocations, axis=1)
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
    """
    Water-filling algorithm for single-resource allocation.
    """
    n = len(weights)
    alloc = mins.copy().astype(float)
    remaining = capacity - np.sum(mins)
    
    if remaining <= 0:
        return alloc
    
    frozen = np.zeros(n, dtype=bool)
    
    for _ in range(100):  # Max iterations
        active = ~frozen & (alloc < ideals)
        if not np.any(active):
            break
        
        active_weight = np.sum(weights[active])
        if active_weight < 1e-9:
            # Distribute equally among active agents
            equal_share = remaining / np.sum(active)
            for i in np.where(active)[0]:
                slack = ideals[i] - alloc[i]
                alloc[i] += min(equal_share, slack)
            break
        
        # Find bottleneck agent
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
    
    # Ensure bounds
    alloc = np.maximum(mins, np.minimum(ideals, alloc))
    return alloc


def main():
    """Main entry point - reads JSON from stdin, writes result to stdout."""
    try:
        # Read input
        input_data = sys.stdin.read()
        if not input_data.strip():
            print(json.dumps({"error": "No input provided"}), file=sys.stderr)
            sys.exit(1)
        
        data = json.loads(input_data)
        
        # Solve
        if CVXPY_AVAILABLE:
            result = solve_joint_allocation(data)
        else:
            result = solve_sequential_fallback(data)
            result["warning"] = "cvxpy not available - using sequential fallback"
        
        # Output result
        print(json.dumps(result))
        
    except json.JSONDecodeError as e:
        print(json.dumps({"error": f"Invalid JSON: {str(e)}"}), file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(json.dumps({"error": str(e), "type": type(e).__name__}), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
