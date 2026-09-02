# Distributed price-mediated Leontief solver: derivation and frozen parameters

This document derives the distributed price solver used by the `distributed_price_leontief`
arm, states every numerical parameter unambiguously, and reports the development
validation against the canonical central solver. The distributed solver never calls the
central solver (verified by source inspection and a test).

## 1. Central problem

For LEONTIEF agents the canonical central problem solved by `scripts/joint_solver.py` is

```
maximize   sum_i c_i * log(u_i)
subject to u_i <= a_ij / r_ij            for every r_ij > 0
           mins_ij <= a_ij <= ideals_ij
           sum_i a_ij <= Q_j
```

with equal priority weights `c_i = 10` (the canonical `PriorityEconomy` base weight; the
allocation is invariant to the common weight). The reduced form substitutes
`a_ij = u_i r_ij` and matches the joint solver's objective to 2.5e-9 on the architecture
scenarios; the distributed solver targets this same objective value.

## 2. Distributed algorithm

Each resource owner `j` holds a single price `lambda_j` for its own resource. Each agent
solves a local subproblem using only its own Leontief coefficients `r_i` and the prices
it receives, and reports only its per-resource demand. Each owner updates its own price
from the ratio of its local demand to its own capacity. No component collects the full
set of declarations or the queue.

### 2.1 Agent subproblem (exact primal recovery)

By dual decomposition on the capacity constraints, given prices `lambda`, agent `i`
maximizes over its utility level `u`:

```
g_i(u) = c_i log u - sum_{j: r_ij>0} lambda_j * max(mins_ij, u * r_ij),   0 <= u <= ub_i,
ub_i = min_{j: r_ij>0} ideals_ij / r_ij
```

because the cost-minimizing installed quantity is `a_ij = max(mins_ij, u r_ij)` (a floored
resource held at its floor, a resource above its floor held in fixed proportion). `g_i` is
concave with kinks at the breakpoints `u = mins_ij / r_ij`; its maximizer is one of the
breakpoints, an interior stationary point `c_i / sum_active(lambda_j r_ij)`, or the upper
bound `ub_i`. The implementation evaluates `g_i` at all of these candidates and returns
the best, which is exact (verified against a brute-force 1-D search: maximum objective
shortfall 7e-15 over 3000 randomized agents). A vectorized fast path handles the common
case where no floor binds and the upper bound is slack.

### 2.2 Owner update

The owner of resource `j` sees only its local demand `d_j = sum_i a_ij` and its own
capacity `Q_j`, and updates

```
lambda_j <- clip( lambda_j * (d_j / Q_j) ** ETA , LAMBDA_FLOOR , LAMBDA_MAX ).
```

Demand is bounded by the agents' upper bounds and the price is bounded by `LAMBDA_MAX`, so
the multiplicative update cannot overflow. Raising any price lowers every agent's utility
and therefore every demand; at sufficiently high prices every agent sits on its floors,
whose total `sum_i mins_ij <= Q_j` is feasible, so a feasible price vector exists and the
iteration converges to it.

### 2.3 Feasibility repair and slack fill

After the price iteration, if any resource is over capacity by more than `REPAIR_TOL`, the
utility levels are scaled by a single global factor `s in [0, 1]` (the largest that makes
the continuous allocation feasible), found by bisection; `s = 0` leaves every resource at
its floors (feasible) and `s = 1` is the unrepaired allocation, so a unique largest
feasible `s` exists. A converged tatonnement leaves only a negligible overshoot, so `s` is
at or very near 1. Finally each owner distributes leftover local capacity to agents below
their upper bound weighted by their requirement, which cannot change any agent's smallest
ratio and so leaves the objective unchanged while using otherwise idle capacity.

## 3. Frozen numerical parameters

| parameter | value | meaning |
|---|---|---|
| `c_i` | `10.0` | equal priority weight (canonical base weight) |
| `ETA` | `0.5` | price-update exponent |
| `ITERS` | `8000` | tatonnement iteration cap |
| `STOP_TOL` | `1e-12` | early stop when max price relative change is below this |
| `LAMBDA_INIT` | `1.0` | initial price on every resource |
| `LAMBDA_FLOOR` | `1e-12` | lowest price |
| `LAMBDA_MAX` | `1e12` | highest price (bounds the multiplicative update against overflow) |
| `SCALE_BISECT` | `100` | bisection steps for the global feasibility repair |
| `REPAIR_TOL` | `1e-9` | feasibility-repair target slack |
| `FEAS_TOL` | `1e-7` | continuous feasibility tolerance for the convergence flag |

Initialization is `lambda = LAMBDA_INIT` on every resource. Primal recovery is the exact
candidate-evaluation of section 2.1. The only feasibility repair is the single global
scale bisection of section 2.3.

## 4. Development validation

`validate_distributed.py` compares the distributed continuous objective to the canonical
central solver over 575 well-posed development scenarios spanning natural Dirichlet(0.1),
symmetric, tie/kink, tiny/zero-coefficient, active-floor, active-upper-bound,
near-degenerate and highly-heterogeneous cases (both contention levels). Results
(`distributed_validation.json`):

- Relative objective gap: mean 1.7e-9, p95 3.6e-9, maximum 3.65e-5; 100% of scenarios at
  most 1e-4 and at most 1e-3.
- Maximum continuous capacity residual 1e-9; maximum bound residual 0.
- Maximum gap by case type: natural 3.7e-5, symmetric 3.2e-9, tie/kink 4.3e-9, tiny/zero
  5.2e-9, active floor 1.1e-7, active bound 5.2e-9, near-degenerate 4.4e-9,
  heterogeneous 6.6e-9.
- Mean tatonnement iterations 1841 (early stop); zero nonconvergences.

## 5. Enumerated failure mode

The one regime the method does not reproduce is genuinely ill-posed: when floors alone
saturate a resource that another agent also requires, that agent's achievable Leontief
utility is zero and the central objective is unbounded below; there the canonical central
solver itself returns a floor-violating, inaccurate solution, and the relative objective
gap is undefined. This regime does not occur in the confirmatory experiments, where
capacities are total demand divided by 1.3-1.9 and the unit floors never bind (0 of 2626
binding cells on the natural Dirichlet(0.1) architecture scenarios). Such instances are
counted and excluded from the equivalence statistics rather than reported as gaps.
