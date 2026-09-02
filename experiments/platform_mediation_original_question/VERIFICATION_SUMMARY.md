# Independent verification summary

Verification branch `verification/platform-original-question-closure`, created from the
final experimental result commit. The independent verifier (`verify_oq.py`) imports none
of the experiment's analysis, decision, bootstrap, manifest, local-task-optimizer or
carrier-selection modules; it reconstructs every reported quantity from the committed raw
data using its own bootstrap, best-subset enumeration, five-condition rule, harmed-set
statistics and adaptive carrier rule.

## Verification status: VERIFIED

## Independent reconstruction (verify_oq.py -> VERIFICATION_REPORT.json)

Architecture — all checks pass:
- Row counts: 2400 runs, 14400 agents, 400 scenarios, 400 distributed rows.
- Confirmatory scenario seeds equal the architecture confirmatory namespace; unique keys;
  all six arms present for every (cell, seed).
- Every paired queue-order comparison mean and 95% percentile interval matches the
  committed headline (independent bootstrap, seed 20260902, 20000 resamples, scenario
  seed as resampling unit, raw-CSV canonical order).
- Every frozen flag reconstructs: replication_pass True, coordination_pass False,
  independent_positive True, independent_noninferior False, distributed_equivalent True.
- The adaptive carrier rule reconstructs to `central_joint_leontief` (branch 3), matching
  the committed decision.
- Separable relaxation equals equal quotas allocation rate 1.000 in both cells.
- Zero infeasibility, fallback, capacity and bound events.

Drift — all checks pass:
- Confirmatory scenario seeds equal the declaration-drift confirmatory namespace.
- Co-primary decision (carrier stale minus DRF stale at delta 0.25) reconstructs to
  +1.605 [1.315, 1.895] moderate and +1.500 [1.230, 1.775] high, both passing; the
  ROBUST_AT_MODEST_DRIFT classification reconstructs.
- Zero infeasibility, fallback, capacity and bound events.

Distributed source inspection: the distributed solver imports no central solver and calls
no central-solve function (AST inspection).

## Test and check counts

| suite / check | result |
|---|---|
| New experiment tests (`experiments/platform_mediation_original_question/tests`) | 50 passed, 0 failed, 0 errored, 0 skipped |
| Existing heterogeneity tests | 67 passed, 0 failed, 0 errored, 0 skipped |
| Canonical Python tests (`tests/python`) | 86 passed, 0 failed, 0 errored, 0 skipped |
| Java tests (`mvn test`, SOLVER_PYTHON set) | 66 passed, 0 failed, 0 errored, 0 skipped |
| Whole-repository claim scan | pass |
| Canonical consistency with manifest | pass (2800 runs reconstructed) |
| Frozen canonical bundle reproduction | pass (`platform_evaluation_results_bundle.zip: OK`, sha256 matches) |
| Architecture manifest verification | pass (all hashes match) |
| Drift manifest verification | pass (all hashes match) |
| `git diff --check` | clean |

## Full clean-clone reproduction

`git clone --no-hardlinks` of the repository, checked out at the final experimental source
revision `1e2e1d968e9204a44567c3571c0d75f5900319cc`, Java rebuilt, then the architecture
experiment, its analysis, the carrier selection, the drift experiment, its analysis and
the manifests rerun with `clean_clone_reproduce.sh` and compared with
`compare_reproduction.py`.

Result: **all non-timing fields identical** (see CLEAN_REPRODUCTION.txt). The clone
reran 2400 architecture and 18000 drift policy runs with zero infeasibility, fallback,
capacity and bound events, reproduced the carrier selection (`central_joint_leontief`)
and the drift classification (`ROBUST_AT_MODEST_DRIFT`) with the co-primary decision
+1.605 [1.315, 1.895] moderate and +1.500 [1.230, 1.775] high, and every raw-table field
matched the committed data.

Timing and machine-dependent latency fields (`runtime_ms`, `alloc_latency_ms`) are
excluded from the comparison; every other field of every raw table matched exactly.

## Scope note

Scientific hypotheses failing does not make verification fail. This verification confirms
that the reported statistics, decisions, classifications and carrier selection follow from
the committed raw data and reproduce exactly on a clean clone.
