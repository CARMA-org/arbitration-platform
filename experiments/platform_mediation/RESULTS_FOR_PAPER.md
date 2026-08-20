# Platform-Mediated Utility Alignment: Results

## Question

A platform mediates a bounded pool of complementary resources among competing
agents. Each agent runs bundle-structured tasks: a task completes only when every
mandatory step is afforded, and mandatory steps for one task jointly require
several resources. The question is whether the utility representation an agent
declares to the joint optimizer changes how much work actually completes, and in
particular whether a representation that encodes complementary requirements
(Leontief or Cobb–Douglas) aligns joint optimization with completed work better
than a linear representation that treats resources as substitutes.

## Design

Six agents, eight tasks each, four archetypes (research, code review, document
processing, monitoring), across four workload regimes (identical, nearly
specialized, broad heterogeneous, complementary archetypes) and two contention
levels (mandatory demand exceeds supply by 1.3 and by 1.9). Capacities are sized
from the aggregate mandatory workload; the realized allocations never exceed
capacity or bounds. Seven policies see identical agents, tasks, priorities, and
bounds within each cell: equal quotas; Dominant Resource Fairness on the
mandatory demand bundle; a separable water-filling family whose exponent
(gamma = 2.0) is chosen on calibration seeds only; and joint weighted
proportional fairness under linear, Cobb–Douglas, CES (rho = 0.5), and Leontief
utilities. Linear, Cobb–Douglas, and CES use normalized resource-footprint
weights; Leontief uses the mandatory bundle proportions as its requirement
vector. Analysis uses 100 disjoint paired test seeds per cell (5600 evaluated
runs) with 95% bootstrap confidence intervals on seed-level paired differences.

## Headline result

Mean task completion rate by policy, pooled over all cells:

| Policy | Completion |
|--------|-----------|
| equal quotas | 0.594 |
| DRF | 0.555 |
| separable (gamma=2) | 0.443 |
| joint, linear | 0.143 |
| joint, CES (rho=0.5) | 0.569 |
| joint, Cobb–Douglas | 0.661 |
| joint, Leontief | 0.668 |

Paired seed-level completion differences (mean, 95% CI, 800 paired observations):

- Cobb–Douglas − linear: +0.518 [+0.496, +0.538]
- Leontief − linear: +0.525 [+0.502, +0.545]
- CES − linear: +0.426 [+0.407, +0.442]
- Cobb–Douglas − equal: +0.067 [+0.062, +0.073]
- Leontief − equal: +0.074 [+0.068, +0.080]
- Cobb–Douglas − DRF: +0.107 [+0.100, +0.113]
- Leontief − DRF: +0.113 [+0.106, +0.120]
- CES − equal: −0.025 [−0.028, −0.022]
- CES − DRF: +0.014 [+0.010, +0.017]

Complementarity-aware joint utilities (Cobb–Douglas, Leontief) complete more work
than linear joint utility by roughly half of all tasks, and they beat both equal
quotas and DRF with intervals that exclude zero. CES with rho = 0.5 is
intermediate: it clearly beats linear and roughly ties DRF, but it does not beat
equal quotas.

## Where the effect lives, and where it does not

Mean completion by regime (pooled over contention):

| Regime | equal | DRF | joint linear | joint CD | joint CES | joint Leontief |
|--------|-------|-----|--------------|----------|-----------|----------------|
| identical | 0.625 | 0.625 | 0.572 | 0.581 | 0.581 | 0.581 |
| nearly specialized | 0.583 | 0.531 | 0.000 | 0.688 | 0.564 | 0.698 |
| broad heterogeneous | 0.583 | 0.531 | 0.000 | 0.688 | 0.564 | 0.696 |
| complementary | 0.583 | 0.531 | 0.000 | 0.688 | 0.566 | 0.696 |

The linear joint policy collapses to zero completion in every regime with
resource heterogeneity. The mechanism is direct: with a linear (substitute)
utility, the optimizer concentrates each agent's allocation on its single
highest-weight resource and gives near-zero on the complementary resources the
same tasks also require, so no mandatory bundle is affordable. Resource
utilization confirms this: the linear joint policy charges only 23% of its own
allocation, while Cobb–Douglas and Leontief charge 91% and 93%. Complementarity-
aware utilities keep the allocation balanced across each agent's bundle, which is
exactly what bundle-structured tasks need.

In the identical regime there is no complementarity to represent and no
cross-agent heterogeneity to exploit; here joint optimization offers no
advantage and equal quotas is marginally best. This is a genuine null and is
reported as such.

This is therefore not a failure of Nash welfare. It is the failure of an
incompatible utility representation: a linear declaration misdescribes tasks that
need bundles, and the optimizer faithfully maximizes the wrong thing.

## Distributional effects

Relative to equal quotas, averaged over cells: the linear joint policy makes
85.5% of agents worse off with a worst per-agent loss of −1.000 (full
starvation); Cobb–Douglas harms 10.8% of agents (worst −0.125); Leontief harms
10.0% (worst −0.125); CES harms 23.0% (worst −0.500). The complementarity-aware
joint policies are close to Pareto improvements over equal quotas, while the
linear policy is broadly harmful. Optional refinement rates follow the same
ordering (Leontief 0.32, Cobb–Douglas 0.30, CES 0.29, equal 0.17, DRF 0.17,
linear 0.09), because balanced allocations leave usable slack after mandatory
work.

## Allocation cost

The joint policies call the convex solver; allocation latency has median 622 ms,
95th percentile 705 ms, and maximum 1207 ms. The comparison rules are arithmetic
and allocate in under a millisecond. The installed allocations are integers; no
capacity or bound violation occurred in any of the 5600 runs. Declared welfare is
reported only within a utility family and is not compared across families, whose
objective scales are not commensurable.

## Secondary result: dynamic contract behaviour

A separate experiment (100 seeds, 100 epochs, four commitment policies over a
prebuilt event schedule with a capacity shock) measures operational outcomes
rather than throughput. Unrestricted reoptimization makes no commitments and so
never reports infeasibility, but imposes the largest incumbent utility loss
(worst −0.79). Permanent accepted-utility floors, taken from the installed
discrete allocation, incur the most admission waiting (mean 3.25 epochs) and the
most commitment infeasibility under the capacity shock (15.47 per run), which the
policy reports rather than silently discards. Time-limited leases reduce both
(waiting 0.39, infeasibility 1.20). Adding a proportional-shortfall rule to
leases further lowers reported infeasibility (0.94) by scaling protected floors
when a shock makes them jointly infeasible. No capacity violation occurred.

## Enforcement

A deterministic fault-injection suite exercises the runtime and solver over 100
repetitions of the concurrency cases plus single-shot cases: negative bundles,
repeated and concurrent over-quota calls, stale-context and duplicate calls,
invalid and cyclic compositions, malformed solver output, a genuinely hung solver
process terminated by the Java timeout, oversubscribed minimums, one-resource-
short calls, and unsupported utility declarations. Every invariant counter
(backend-after-denial, quota violations, capacity violations, partial deductions,
silent fallbacks, incorrect successes) is zero. These are results of deterministic
tests, not estimates of real-world failure rates.

## Scope of claims

- Properties of the continuous convex formulation (concavity of the assembled
  objective, capacity and bound feasibility) are mathematical.
- Contract authority, the shared per-version consumption ledger, execution
  binding, and the solver timeout are properties the runtime enforces and that
  the test suite exercises directly.
- The completion, distributional, latency, and dynamic numbers above are results
  of controlled synthetic experiments with the stated task and latency models;
  latency is a budget of service constants, reported as latency-budget completion,
  not observed service-level attainment, and task outputs are mock.
- No claim is made about strategyproofness, truthful reporting, collusion
  resistance, protection against a hostile operator, or sandboxing of untrusted
  agent code; none is tested.

## Reproduction

From the source revision recorded in `EXPERIMENT_MANIFEST.json`:
`mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt`, then in
`experiments/platform_mediation` run `python3 run_sweep.py --full`, then
`figures.py`, `recompute_headline.py`, `make_test_report.py`, and
`make_manifest.py`. The dynamic and enforcement experiments run from their own
directories. `SOLVER_PYTHON` must point at an interpreter with cvxpy and clarabel.
