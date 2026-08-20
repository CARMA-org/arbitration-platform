# Platform Mediation — Results

This reports what the ARB codebase supports as evidence for a paper about
platform-mediated coordination for AI agents. It states the exact configurations
and denominators, the results under every prespecified policy and cell, which
findings support platform mediation, the null and negative findings, the
individual-agent harms, the implementation limits, and which claims remain
unsupported. It does not force a positive conclusion.

All allocations are installed and executed through the canonical Java runtime
(`AgentRuntime` + `ExecutionContext`); the joint policy is produced by
`ConvexJointArbitrator` (convex solve via `scripts/joint_solver.py`). Task
completion and quality are defined by the task specifications, not by the
weighted-log objective, and the allocator never reads test-task outcomes.

## Headline finding

The platform reliably converts agent resource declarations and operator policy
into enforceable multi-resource contracts and behaves predictably under arrivals,
capacity changes, and attempted quota violations (all enforcement invariants zero;
zero capacity or bound violations across every sweep). **But the specific joint
weighted-Nash (weighted proportional fairness, linear utility) allocation policy
does not improve actual task outcomes under contention — it reduces them relative
to equal quotas and DRF.** Joint allocation maximises the declared weighted-log
welfare in every cell while completing the fewest tasks. This is a negative result
for the claim that the Nash allocator improves task outcomes, and it is stated
prominently below.

The evidence therefore supports a platform-mediation paper about the mechanism
(enforceable contracts, atomic multi-resource enforcement, predictable dynamic
behaviour) and about the objective–outcome misalignment, but **does not** support a
claim that weighted-Nash allocation improves task outcomes.

## 1. Platform-mediation sweep

### Configuration and denominators

- Regimes (workload profiles): `identical`, `nearly_specialized`,
  `broad_heterogeneous`, `complementary`.
- Contention levels: `moderate` (demand/supply = 1.3), `high` (1.9), applied to
  mandatory-step demand.
- Cells: 4 regimes x 2 contention = **8 cells**.
- Full mode: **30 calibration seeds** and **100 paired test seeds per cell**
  (disjoint, deterministic hashed seeds). 6 agents, 8 tasks each. **3200 test
  runs** (8 cells x 100 seeds x 4 policies). Every policy receives the same agents,
  tasks, capacities, priorities, backend behaviour, and seed.
- Policies: `equal` quotas, `drf` (dominant resource fairness on fixed bundles),
  `separable` (held-out tuned water-filling family), `joint` (WPF through
  `ConvexJointArbitrator`).
- Separable exponent selected only on calibration seeds by pooled declared-welfare
  regret vs joint; one exponent used on all test seeds: **gamma* = 4.0**.
- Deterministic mock backend, simulated latency disabled. Per-agent upper bounds
  are 0.55 x capacity on used resources (not equal to total capacity); admitted
  minimums are 1 on used resources. Priorities are exogenous tiers {1, 2, 4}
  labelled as operator policy inputs.

### Results — task outcomes vs declared welfare

Mean over 100 test seeds. Completion, quality, and priority-weighted SLO are task
outcomes; declared welfare is the allocator's own objective (reported for contrast,
never as task performance).

| cell | policy | completion | quality | utilization | pri-wtd SLO | declared welfare |
|---|---|---|---|---|---|---|
| identical / moderate | equal | 0.625 | 0.597 | 0.972 | 0.104 | 376.82 |
| identical / moderate | drf | 0.625 | 0.597 | 0.972 | 0.104 | 376.80 |
| identical / moderate | separable | 0.575 | 0.564 | 0.946 | 0.024 | 377.11 |
| identical / moderate | joint | 0.569 | 0.559 | 0.942 | 0.017 | 377.11 |
| broad_heterogeneous / moderate | equal | 0.438 | 0.374 | 0.786 | 0.166 | 332.40 |
| broad_heterogeneous / moderate | drf | 0.375 | 0.337 | 0.707 | 0.104 | 330.30 |
| broad_heterogeneous / moderate | separable | 0.213 | 0.179 | 0.426 | 0.086 | 338.05 |
| broad_heterogeneous / moderate | joint | 0.007 | 0.006 | 0.031 | 0.003 | 343.58 |
| nearly_specialized / high | equal | 0.292 | 0.248 | 0.775 | 0.125 | 322.63 |
| nearly_specialized / high | drf | 0.250 | 0.223 | 0.690 | 0.084 | 321.82 |
| nearly_specialized / high | separable | 0.050 | 0.047 | 0.221 | 0.009 | 339.25 |
| nearly_specialized / high | joint | 0.000 | 0.000 | 0.102 | 0.000 | 340.07 |
| complementary / moderate | equal | 0.438 | 0.373 | 0.786 | 0.167 | 346.51 |
| complementary / moderate | joint | 0.000 | 0.000 | 0.139 | 0.000 | 356.53 |

Overall mean completion across cells: **equal 0.398, drf 0.359, separable 0.222,
joint 0.120.** Full per-cell numbers are in `tables/cell_policy_means.csv`.

### Paired differences (95% bootstrap CIs), joint minus equal

| cell | completion diff | declared-welfare diff |
|---|---|---|
| identical / moderate | -0.056 [-0.059, -0.052] | +0.293 [+0.267, +0.318] |
| identical / high | -0.002 [-0.004, -0.001] | +0.294 [+0.269, +0.318] |
| nearly_specialized / moderate | -0.438 [-0.438, -0.438] | +17.564 [+17.363, +17.771] |
| nearly_specialized / high | -0.292 [-0.292, -0.292] | +17.446 [+17.245, +17.648] |
| broad_heterogeneous / moderate | -0.430 [-0.433, -0.427] | +11.189 [+10.671, +11.677] |
| broad_heterogeneous / high | -0.285 [-0.287, -0.282] | +10.760 [+10.347, +11.177] |
| complementary / moderate | -0.438 [-0.438, -0.438] | +10.028 [+9.911, +10.153] |
| complementary / high | -0.292 [-0.292, -0.292] | +9.883 [+9.740, +10.018] |

In every non-identical cell the completion CI is strictly negative and the declared
welfare CI is strictly positive: joint allocation raises the declared objective and
lowers task completion at the same time. Joint minus separable shows the same sign
pattern (see `tables/paired_differences.csv`), even though the separable exponent
was tuned to approximate joint's declared welfare.

### Individual-agent harm (vs equal quotas)

| cell | mean completion change | worst agent | fraction of agents worse |
|---|---|---|---|
| identical / moderate | -0.056 | -0.125 | 0.45 |
| nearly_specialized / high | -0.292 | -0.500 | 1.00 |
| broad_heterogeneous / moderate | -0.430 | -0.750 | 1.00 |
| complementary / moderate | -0.438 | -0.750 | 1.00 |

Under joint allocation almost every agent completes fewer tasks than under equal
quotas in the heterogeneous, specialized, and complementary regimes; the worst
individual loses up to 0.75 of its completion. Full detail:
`tables/individual_loss.csv`.

### Why (mechanism)

With linear declared utilities, weighted-Nash welfare gives each agent resources
concentrated on its comparative-advantage resource (the Fisher-market property).
The tasks, however, need complementary bundles — every mandatory step charges a
multi-resource vector — so an agent that receives a single resource in abundance
but little of the others cannot complete even one task and is denied on its first
step. Equal quotas hand each agent a balanced bundle and therefore complete more
whole tasks. The `identical` regime, where no comparative advantage exists, shows
essentially no difference between policies, as expected.

### What supports platform mediation here

- The mechanism itself: declarations + operator priorities are converted into
  installed, versioned, conservation-checked contracts and enforced during
  multi-resource execution. Zero capacity or bound violations across all 3200 runs.
- Allocation latency is small (median joint solve well under a second; comparison
  policies effectively instantaneous — see `runs.csv`).

### Null and negative findings (stated prominently)

- **Negative:** joint weighted-Nash allocation reduces task completion, quality,
  and priority-weighted SLO relative to equal quotas and DRF in every contended,
  heterogeneous cell, while increasing declared welfare.
- **Null:** in the `identical` regime there is no meaningful task-outcome
  difference between policies.
- Declared welfare must not be presented as task performance; the two move in
  opposite directions here.

## 2. Enforcement fault injection

The canonical runtime was exercised with negative resource requests, repeated and
concurrent over-quota calls (100 deterministic repetitions for concurrency-sensitive
cases), duplicate requests, stale allocation replay, invalid and cyclic service
compositions, unsupported utility requests, malformed solver output, a slow/timeout
solver, oversubscribed minimums, and one exhausted resource inside an otherwise
affordable call.

Reported invariants — backend invocations after denial, agent-quota violations,
aggregate-capacity violations, partial deductions after denial, silent fallbacks,
incorrect success statuses — are **all zero** across all 12 cases. No repair was
required (initial = final = zero). Machine-readable report:
`experiments/enforcement/results/enforcement_report_full.json`.

These checks concern mechanical enforcement only. They are **not** evidence of
strategyproofness, truthful reporting, collusion resistance, or protection against
a malicious platform operator, and are not described as such.

## 3. Dynamic allocation

### Configuration

Repeated allocation epochs with prespecified events: agent arrivals, departures,
preference changes, lease expirations, a 30% capacity loss at 30% of the horizon,
and a capacity restoration at 60%. Four commitment policies over a shared event
schedule: full reoptimization with no protected status quo (`reoptimize`),
permanent accepted-utility floors (`permanent_floors`), time-limited leases
(`leases`, lease length 10 epochs), and leases plus a proportional-shortfall rule
when a capacity loss makes floors infeasible (`leases_shortfall`). Floors are lower
bounds on declared LINEAR utility (the supported representation), added to the same
convex joint solver. Task outcomes are measured through the canonical runtime.
Full mode: **100 paired seeds, 100 epochs per seed**; artifacts in
`experiments/dynamic_allocation/`.

### Results (mean over 100 seeds)

| policy | completion | entrant waiting (epochs) | commitment infeasibility (epochs) | churn frac | binding commitments | worst incumbent loss | capacity violations |
|---|---|---|---|---|---|---|---|
| reoptimize | 0.142 | 0.00 | 0 | 0.092 | 0.00 | -0.588 | 0 |
| permanent_floors | 0.157 | 3.25 | 15.47 | 0.083 | 0.78 | -0.654 | 0 |
| leases | 0.146 | 0.39 | 1.20 | 0.095 | 1.52 | -0.630 | 0 |
| leases_shortfall | 0.147 | 0.35 | 0.94 | 0.096 | 1.51 | -0.642 | 0 |

Paired differences vs `reoptimize` (95% bootstrap CIs):

| metric | permanent_floors | leases | leases_shortfall |
|---|---|---|---|
| completion | +0.016 [+0.010,+0.022] | +0.005 [+0.002,+0.007] | +0.005 [+0.003,+0.008] |
| entrant waiting | +3.25 [+2.55,+3.95] | +0.39 [+0.28,+0.50] | +0.35 [+0.26,+0.46] |
| commitment infeasibility | +15.47 [+13.60,+17.28] | +1.20 [+0.81,+1.61] | +0.94 [+0.62,+1.29] |
| churn frac | -0.009 [-0.011,-0.007] | +0.003 [+0.002,+0.005] | +0.004 [+0.003,+0.006] |
| worst incumbent loss | -0.066 [-0.114,-0.018] | -0.042 [-0.080,-0.004] | -0.054 [-0.094,-0.016] |

### Interpretation (does a lease help?)

Leases **do** occupy a useful middle ground, but only on the operational metrics,
not on task throughput. Relative to permanent floors, leases cut entrant waiting
about 8x (0.39 vs 3.25 epochs) and commitment infeasibility about 13x (1.20 vs
15.47 epochs), while keeping most of permanent floors' small completion advantage
over free reoptimization. Adding the proportional-shortfall rule reduces
commitment infeasibility further (0.94 epochs). Leases do **not** dominate: they
slightly increase allocation churn and worst-incumbent loss relative to free
reoptimization, and the task-completion spread across all four policies is small
(0.142–0.157). Permanent floors buy the lowest churn and slightly higher completion
but are the most brittle under a capacity shock (longest waiting, most infeasible
commitments, worst incumbent loss). Zero capacity violations under every policy.

So the answer to the middle-ground question is a qualified yes: leases trade a
little churn for large reductions in entrant waiting and commitment infeasibility
under capacity shocks, but they are not a task-throughput win. This is reported
without assuming leases help; the null on task completion is stated alongside the
operational gains.

## 4. Implementation limits and unsupported claims

- The canonical joint policy uses **linear** declared utilities. The solver also
  supports `COBB_DOUGLAS`, `CES`, and `LEONTIEF`, which model bundle
  complementarity and would likely align better with bundle-structured tasks, but
  the runtime declaration path only emits linear utilities; those families were not
  wired to the runtime and are not evaluated as the canonical policy here.
- Task completion, quality, and SLO are defined by a deterministic task model over
  the existing mock service backend. They are a controlled proxy for real workloads,
  not measurements of a deployed system.
- Commitment floors are implemented and evaluated only for the linear utility
  representation; no claim is made that they apply to other utility models.
- No strategic-currency, urgency-revelation, equilibrium, or coalition claims are
  made; priority variation is exogenous operator policy, not endogenous burning.
- Enforcement results are mechanical invariants, not incentive or security
  properties.

## Bottom line

ARB supports a paper about **platform-mediated coordination** — enforceable
multi-resource contracts from declarations and operator policy, with predictable
behaviour under contention, arrivals, capacity changes, and attempted quota
violations — and a substantive **negative** result that the weighted-Nash
allocation objective is misaligned with bundle-structured task outcomes. It does
**not** support the narrower claim that the weighted-Nash allocator improves task
outcomes.
