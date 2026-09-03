# Platform-Mediated Multi-Agent Resource Arbitration

A research prototype and evaluation suite in which a platform mediates access to a shared,
bounded resource pool among competing agents. Agents declare resource demands and a utility
representation; an operator selects an allocation policy; the platform computes an allocation,
installs it as versioned multi-resource contracts, and enforces those contracts across the
allocation epoch, including concurrent and repeated execution. The prototype is used to study
**how the allocation rule and the structure of resource requirements affect the amount of
completed bundle-structured work.**

> **Repository state.** `main` is the current, consolidated, independently verified research
> state. The historical `v0.9` tag marks the earlier prototype snapshot and is preserved
> unchanged; this README no longer presents the repository *as* v0.9. No software license is
> declared (see [License status](#license-status)).

## What this repository contains

The engineering prototype (Java runtime + Python convex solver) plus a sequence of
preregistered synthetic experiments and an independent verification of the most recent one:

| Component | Where | What it establishes |
|---|---|---|
| **Engineering prototype** | `src/`, `scripts/joint_solver.py` | Runtime that installs and enforces versioned multi-resource contracts; a convex joint allocator for linear, Cobb–Douglas, CES and Leontief utilities. |
| **Canonical evaluation** | [`experiments/platform_mediation/`](experiments/platform_mediation/RESULTS_FOR_PAPER.md) | The primary sweep comparing utility representations and operational contract behaviour, including null and negative results and individual-agent harms. |
| **Heterogeneity experiment** | [`experiments/platform_mediation_heterogeneity/`](experiments/platform_mediation_heterogeneity/) | Confirmed a conditional existence result: joint Leontief beats DRF under strongly heterogeneous complementary requirements (verified). |
| **Architecture + declaration-drift closure** | [`experiments/platform_mediation_original_question/`](experiments/platform_mediation_original_question/ORIGINAL_QUESTION_CLOSURE.md) | Preregistered "original-question" closure: does the result need cross-resource coordination, centralized computation, or hold under declaration drift? |
| **Independent verification** | [`.../VERIFICATION_SUMMARY_FINAL.md`](experiments/platform_mediation_original_question/VERIFICATION_SUMMARY_FINAL.md) | A stdlib+NumPy verifier that re-derives every reported closure quantity from the committed raw data (94/94 checks). |

Immutable GitHub commits for the closure: preregistration
[`7ebf8b7`](https://github.com/CARMA-org/arbitration-platform/commit/7ebf8b70366b8b68a90554a722f097d8acea3f01),
results/report [`1e2e1d9`](https://github.com/CARMA-org/arbitration-platform/commit/1e2e1d968e9204a44567c3571c0d75f5900319cc),
public-interpretation correction [`601ca56`](https://github.com/CARMA-org/arbitration-platform/commit/601ca56752d16fe5b9364590f95ef5335331e9b5),
comprehensive v2 verification [`c678a0a`](https://github.com/CARMA-org/arbitration-platform/commit/c678a0a96aba563ceff52e4d6b889fb90db316ec).

## Central conclusion (short)

**The tested allocation-rule principle works under identified synthetic conditions, but the
experiments do not show that centralized joint computation or platform authority is
necessary.** A strong resource-local mechanism captured most of the benefit, a price-mediated
implementation reproduced the aggregate central outcome, and modest declaration drift preserved
a *relative* advantage over DRF while severe drift exposed absolute fragility.

In more detail, at the tested and preregistered scope (synthetic six-agent, four-resource,
Dirichlet(0.1) heterogeneous workloads):

- **Existence result holds.** Central joint Leontief beats DRF by **+2.655** tasks per 48-task
  run (95% CI [2.360, 2.950]) at moderate contention and **+1.825** ([1.545, 2.100]) at high.
- **Coordination is not shown to be necessary.** A resource-local independent bundle max-min
  also beats DRF (**+1.970** / **+1.470**), capturing ≈74% / ≈81% of central's advantage.
  Central − independent is positive (+0.685 / +0.355) but **fails** the frozen +1.000-task
  materiality bar, so `coordination_pass = False`.
- **Central computation is not required.** A price-mediated distributed method reproduced the
  centralized continuous objective and aggregate completion within the frozen equivalence
  criteria — though it did **not** reproduce exactly which agents bore losses.
- **Modest drift is survived, severe drift is not.** With stale declarations estimated before
  25% task-source drift the selected carrier still beat stale DRF (`ROBUST_AT_MODEST_DRIFT`, a
  *relative* advantage). At full drift (δ=1.0) stale central completion falls **below** equal
  quotas (23.53 vs 26.73 moderate; 17.65 vs 18.95 high).
- **Authority was held fixed, not tested.** All arms shared the same platform installation and
  enforcement; the experiments varied allocation/computation rules, not the presence or locus
  of authority, and do **not** estimate the causal value of platform authority. No universal
  claim and no strict-monotonicity claim is made.

## Supported scope

- Bounded multi-resource pool (compute, memory, API credits, dataset units).
- Per-agent utility declarations: linear, Cobb–Douglas, CES, and Leontief.
- Joint allocation over the full agent-by-resource matrix via a convex program
  (Python/cvxpy/Clarabel), plus comparison rules: equal quotas, standard Dominant Resource
  Fairness on the mandatory-demand vector, an exact decomposed Cobb–Douglas comparator, and (in
  the closure experiment) an independent resource-local bundle max-min and a price-mediated
  Leontief solver.
- Versioned allocation contracts with a shared per-contract consumption ledger, enforced by the
  runtime under a monotonic epoch and an injectable clock.
- Controlled synthetic experiments comparing allocation rules and operational contract
  behaviour under heterogeneity, dynamics, declaration drift, and fault injection.

## Architecture

- `model/` — resource types, `AIService`, `ServiceRegistry`, `Agent`, `UtilityDeclaration`,
  `AllocationContract`, `ConsumptionLedger`, `AllocationSnapshot`, `ServiceHandle`.
- `agent/RealisticAgentFramework` — the `AgentRuntime` and `ExecutionContext`. The runtime holds
  one immutable `AllocationSnapshot`, published by a single atomic reference replacement under an
  installation lock. Each execution context is bound to an agent, an allocation id, a contract
  version, and the shared ledger for that version; every service call is gated on the active
  contract.
- `mechanism/ConvexJointArbitrator` — builds the solver input, runs `scripts/joint_solver.py`
  under a hard timeout draining stdout and stderr, and applies capacity-preserving integer
  rounding. It fails closed unless fallback is explicitly enabled.
- `scripts/joint_solver.py` — the convex solver for the four utility families.
- `experiment/` — the `PlatformMediationHarness`, `TaskAgent`, and `EnforcementFaultInjection`
  entry points used by the experiments.

## Installation

Requirements: Java 21+, Maven 3.6+, and Python 3.10+ with `cvxpy`, `clarabel`, `numpy`, `scipy`,
`pandas` for the solver and experiments. Pinned versions are listed in
[`docs/REPRODUCIBILITY.md`](docs/REPRODUCIBILITY.md).

```bash
mvn -q compile
python3 -m venv .venv && . .venv/bin/activate
pip install -r experiments/joint_allocation/requirements.txt
```

## Canonical demo

```bash
mvn -q exec:java -Dexec.mainClass=org.carma.arbitration.demo.IntegratedArbitrationDemo
```

This registers agents, runs joint arbitration through the runtime, installs versioned contracts,
and executes each agent through the enforced path.

## Tests

```bash
export SOLVER_PYTHON="$PWD/.venv/bin/python3"   # a Python with cvxpy/clarabel
mvn -o test                                     # Java suite
$SOLVER_PYTHON -m pytest tests/python -q         # Python solver suite
# experiment + verification suites (no solver needed for the verifiers):
$SOLVER_PYTHON -m pytest experiments/platform_mediation_original_question/tests -q
$SOLVER_PYTHON experiments/platform_mediation_original_question/verify_oq_final.py
```

The solver-dependent Java tests require `SOLVER_PYTHON` to point at an interpreter with
cvxpy/clarabel installed; otherwise they are skipped. Continuous integration
([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs the Java build, the Python solver
suite, every experiment test suite, the comprehensive closure verifier, and the manifest and
generated-document consistency checks on `main` and pull requests into `main`.

## Reproduction vs. raw-data verification

Two distinct things are available and should not be conflated:

- **Full experiment reproduction** re-executes the runs from a clean clone (see
  [`docs/REPRODUCIBILITY.md`](docs/REPRODUCIBILITY.md) and each experiment's `REPRODUCIBILITY.md`).
  The closure experiments comprise 20,400 confirmatory policy runs.
- **Raw-data verification** re-derives every reported statistic from the *committed* raw CSVs
  without re-running anything, using an independent stdlib+NumPy verifier
  (`experiments/platform_mediation_original_question/verify_oq_final.py`). CI runs this on every
  push; it does **not** re-execute the confirmatory runs.

```bash
# canonical evaluation reproduction (run the primary sweep alone for clean latency)
mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt
cd experiments/platform_mediation && python3 run_sweep.py --full
```

See [`docs/EXPERIMENTS.md`](docs/EXPERIMENTS.md) for the design and
[`docs/PLATFORM_EVALUATION.md`](docs/PLATFORM_EVALUATION.md) for the canonical mediation path.

## Supported utility families

| Family | Declaration | Solver term |
|--------|-------------|-------------|
| Linear | normalized resource weights | `Σ_j β_j a_j` |
| Cobb–Douglas | normalized weights summing to 1 | `Π_j a_j^{β_j}` |
| CES | weights and elasticity `ρ ≤ 1`, `ρ ≠ 0` | `(Σ_j β_j a_j^{ρ})^{1/ρ}` |
| Leontief | requirement vector `r_j ≥ 0`, at least one positive | `min_{j: r_j>0} a_j / r_j` |

Unsupported family names are rejected at validation rather than replaced by a linear surrogate.
See [`docs/MODEL_SUPPORT.md`](docs/MODEL_SUPPORT.md). Linear and CES (`ρ = 0.5`) treat resources
as substitutes. Under weighted proportional fairness the Cobb–Douglas objective separates by
resource, so a decomposed per-resource comparator matches the joint continuous solution up to
solver tolerance; the installed integer allocations can still differ by a unit because rounding
is applied independently. Leontief retains genuine cross-resource coupling in the joint solver.

## Known limitations and the open authority question

See [`docs/KNOWN_LIMITATIONS.md`](docs/KNOWN_LIMITATIONS.md). In brief: task execution is
synthetic and reported latency is allocation-computation time, not task performance; the
enforcement invariants are properties observed in deterministic tests, not estimates of
real-world failure rates; the runtime enforces contracts against agents that call the provided
execution path and does not model strategic reporting, collusion, a hostile operator, or
sandboxing of untrusted agent code. Crucially, **every experimental arm holds platform
installation and enforcement authority fixed** — the experiments compare allocation *rules*, so
whether platform authority is itself beneficial, and how it should be governed, remain open
questions rather than results of this work.

## License status

No license is currently declared. Until a `LICENSE` file is added, no license is granted. The
`v0.9` tag and this state are both unlicensed.

## References

- Kelly, Maulloo, Tan. Rate control for communication networks (1998).
- Ghodsi et al. Dominant Resource Fairness (2011).
- Parkes. Iterative Combinatorial Auctions (2001).
- Goulart, Chen. Clarabel: an interior-point solver for conic programs (2023).
