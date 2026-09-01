# Independent verification — heterogeneity confirmatory experiment

## Overall status

**VERIFIED.**

All seven VERIFIED preconditions hold: (1) verifiable preregistration chronology;
(2) no outcome-relevant post-preregistration change; (3) exact independent
reconstruction of the frozen decision; (4) exact independent local-optimization
reconstruction; (5) valid manifests and provenance; (6) passing relevant tests;
(7) a successful full clean rerun whose every outcome-relevant field is byte-identical
(only the nondeterministic wall-clock latency column differs).

The frozen confirmatory rule passed in both co-primary cells under independent
reconstruction, and a full clean rerun in a fresh clone reproduced the raw data
exactly except for the allocation-latency timing column. One reporting nuance was
found and is recorded below: the response is not monotone at high contention
(the committed reports say "rises roughly monotonically", which remains defensible;
a flat "monotone" description is not).

## Exact repository state

- initial worktree: CLEAN (only an untracked `verification/` directory was added by this audit)
- branch: `platform-heterogeneity-pilot`
- final experimental HEAD: `073a5d6f0c1196f60ac8029bc4ee0971db8f099b`
- canonical base: `bfab534bba977d5f7c40b0407b83036b38dfbf4a` (== `origin/platform-evaluation`)
- `origin/main`: `91d8ad77d2b549933b46d566e5776b67b87628ff` (unchanged)
- canonical bundle sha256: `1324889bde926fa9624cc499f8f0728d716217f8671c6f97296a9a08988ed484` (matches expected)
- all reported short hashes resolve uniquely; commit chain is linear:
  `bfab534 -> a64d09c -> 816a0b7 -> 11fdccd -> 0caa180 (prereg) -> c76b4c9 (fix) -> 073a5d6 (HEAD)`
- every commit authored and committed by `Avyay <avyaymc@gmail.com>`
- 71 files changed `bfab534..073a5d6`; 0 outside `experiments/platform_mediation_heterogeneity/`

## Provenance decision

Chronology is verifiable and there is no outcome-relevant post-preregistration change.

- `bfab534` is an ancestor of HEAD.
- The confirmatory raw files (`runs.csv`, `agents.csv`, `scenarios.csv`, `summary.json`,
  `confirmatory_headline.json`) FIRST appear only at `073a5d6`; there are 0 such files at
  the preregistration commit `0caa180` and at the defect-fix commit `c76b4c9`.
- The committed run log shows the confirmatory sweep started 14 seconds after the
  preregistration commit and ran for ~7053 s; the preregistration commit itself is not
  amended (author time equals committer time).
- Between `0caa180` and `073a5d6` only three files are modified: `CHANGELOG.md` (prose),
  the top-level `EXPERIMENT_MANIFEST.json` (SHA index), and `make_confirmatory_analysis.py`.
  Every outcome-relevant file is byte-identical (git object id equal) across prereg -> HEAD:
  `config/confirmatory_v1.json`, `run_confirmatory.py`, `pilotlib/workload.py`,
  `pilotlib/pilot_scenario.py`, `pilotlib/floors.py`, `pilotlib/local_opt.py`,
  `pilotlib/measures.py`, the canonical `lib/seeds.py`, the whole canonical `lib/`, and all
  of `src/` (Java runtime and policies).
- The defect fix `c76b4c9` is exactly a two-line import reorder in
  `make_confirmatory_analysis.py`: it moves `from pilotlib import pilot_analysis` (which puts
  the canonical library on `sys.path`) before `from lib.analysis import cell_bootstrap`.
  Run as a script the file previously failed at import (`ModuleNotFoundError: lib`) and had
  never executed; the fix enables execution and changes nothing computed. `cell_bootstrap`
  is the same function either way and seeds a fresh generator from a hash of
  `(name, boot_seed)`, independent of import order and of global random state. The success
  rule, bootstrap seed, comparison set, and outcome definitions in that file are unchanged.
  Assessment: OUTCOME-NEUTRAL.

## Frozen rule (extracted verbatim from the preregistration commit 0caa180)

Primary policy `joint_leontief`; co-primary cells `dirichlet_0.1__moderate` and
`dirichlet_0.1__high`; comparator DRF; metric queue-order completion; bootstrap seed
20260901; 10,000 resamples; 200 paired seeds. "Leontief minus equal" is a preregistered
SECONDARY comparison, not part of the primary rule.

| # | frozen condition | threshold | comparator | reconstructed value (mod / high) | pass | evidence |
|---|---|---|---|---|---|---|
| 1 | Leontief mean completion higher than DRF | > 0 | DRF | 0.7750 > 0.7209 / 0.5452 > 0.5083 | yes / yes | reconstructed_primary.json |
| 2 | paired 95% bootstrap interval (Leo-DRF) strictly above zero | ci_lo > 0 | DRF | ci_lo frac 0.04781 / 0.03135 | yes / yes | reconstructed_primary.json |
| 3 | point estimate at least one task per 48-task run | >= 1.0 tasks/run | DRF | +2.595 / +1.770 tasks/run | yes / yes | reconstructed_primary.json |
| 4 | no increase in observed zero-completion vs DRF | leo <= drf | DRF | 0.0000 <= 0.0008 / 0.0000 <= 0.0042 | yes / yes | reconstructed_distribution.csv |
| 5 | capacity, bound, fallback, infeasibility all zero | all 0 | — | 0 / 0 / 0 / 0 | yes | summary.json + reconstruction |

The committed decision document applies the rule exactly: it uses the interval bound for
condition 2 and the point estimate for condition 3, and scopes the primary rule to DRF.

## Independently reconstructed primary results

Reconstructed from the committed raw with a newly written script that imports none of the
project's analysis, bootstrap, seed, manifest, or local-optimization code
(`independent_reconstruction.py`). The `derive_seed` and `cell_bootstrap` procedures were
reimplemented from their documented definitions, so the committed intervals are reproduced
rather than trusted.

- `dirichlet_0.1__moderate`: Leontief 0.7750, DRF 0.7209. Leontief - DRF = **+2.595 tasks/run**;
  95% interval **[2.295, 2.890] tasks/run** (fraction [0.04781, 0.06021]); paired-t SE 0.154 t.
- `dirichlet_0.1__high`: Leontief 0.5452, DRF 0.5083. Leontief - DRF = **+1.770 tasks/run**;
  95% interval **[1.505, 2.045] tasks/run** (fraction [0.03135, 0.04260]); paired-t SE 0.142 t.
  (This is the interval that was corrupted in the pasted summary; it is recovered here.)

Reconstructed decision: both co-primary cells pass all five conditions -> confirmed = True.
These match the committed headline exactly. 30/30 structural checks pass: counts
(19,600 runs, 117,600 agents, 2,800 scenarios), unique keys, six agents / eight tasks per
run, seven policies per scenario, 200 unique seeds per cell, scenario/workload-hash equality
across policies, seed and workload-hash disjointness from canonical and pilot (all overlaps
0; confirmatory seeds equal `derive_seed("heterogeneity_confirmatory_v1","test",i)`),
no redraws/exclusions, no infeasible/fallback/violation, solver statuses optimal (29/11,200
optimal_inaccurate — a valid feasible cvxpy status), capacity and charged-resource
conservation, run completion equal to the mean of agent completions, and the exact
completion-to-tasks x48 conversion.

Note on the seed namespace: the confirmatory workload seeds are `derive_seed(...)` 32-bit
integers, not literally 20260901; 20260901 is the (separate) frozen bootstrap seed.

## Equal-quota comparison (secondary, not primary)

Leontief minus equal is a preregistered SECONDARY comparison. Reconstructed means: Leontief
completes more than equal on average in both co-primary cells (+3.415 tasks/run moderate,
+3.450 tasks/run high). This is an aggregate mean advantage, not a per-agent one and not part
of the primary rule (see distributional results). The phrase "beats DRF and equal" mixes a
primary (DRF) result with a secondary (equal) result and should be labelled accordingly.

## Local-optimization verification

`pilotlib/local_opt.py` enumerates all 256 subsets of an agent's eight tasks, treats a subset
as feasible when its summed MANDATORY footprint is within the installed allocation on every
resource, and selects by (max count, then base quality, then min consumption, then
lexicographic indices). `locally_optimized_completion = count / 8` depends only on the maximum
feasible count. In `run_confirmatory.py` the subset table is built once per scenario-agent from
the agent's own task footprints (policy-independent) and evaluated against each policy's
installed allocation; `select_from_table` sees only `(table, allocation)` — no policy label and
no queue-order outcome. It changes no allocation and is computed offline (not through the Java
runtime).

Independent record-by-record check (`verify_local_opt.py`): mandatory footprints transcribed
from the documented service/archetype definitions reproduce every scenario's
`aggregate_mandatory_demand` (0/2,800 mismatches); an independent 256-subset enumeration
reproduces the committed `locally_optimized_count` for **all 117,600 agent records** (0 count
mismatches, 0 completion mismatches). The committed optimizer is therefore exact and applied
symmetrically to every policy, using only oracle information (the agent's own task footprints
and its installed allocation).

Locally-optimized Leontief minus DRF (independent): +2.555 tasks/run [2.255, 2.850]
(moderate) and +1.895 tasks/run [1.625, 2.165] (high); both intervals are above zero. The
advantage is not eliminated by exact offline best-subset selection under the same installed
allocation.

## Response-curve correction

Reconstructed Leontief-minus-DRF queue-order differences (tasks/run), in increasing
concentration/dissimilarity order (lower Dirichlet alpha = more concentration = higher realized
resource-demand dissimilarity; realized resource-demand TV confirmed monotone in this order):

- moderate: homogeneous +0.025, iid_uniform +0.045, dir3.0 +0.195, dir1.0 +0.790,
  dir0.3 +1.655, dir0.1 +2.595, dir0.03 +3.155 — STRICTLY MONOTONE.
- high: homogeneous +0.040, iid_uniform +0.085, dir3.0 **+0.370**, dir1.0 **+0.330**,
  dir0.3 +1.190, dir0.1 +1.770, dir0.03 +1.795 — NOT MONOTONE (a decrease from
  Dirichlet alpha 3.0 to alpha 1.0, i.e. as concentration increases).

Correction: the response is strictly monotone at moderate contention but NOT monotone at high
contention (one violation: dir3.0 +0.370 -> dir1.0 +0.330). "Monotone with dissimilarity" is
not accurate; "rises roughly monotonically / generally increasing with dissimilarity" is
accurate, and is the wording the committed reports actually use. At the current design's
dissimilarity (iid_uniform) the interval includes zero at both contention levels.

## Distributional results (co-primary, independent)

Denominator for zero-completion and worse-than-equal fractions is 1,200 agents (200 seeds x 6).

- dir0.1 moderate: equal mean 0.7039 (min 0.250); DRF 0.7209 (min 0.000, frac_zero 0.00083,
  frac_worse_than_equal 0.218, worst change vs equal -0.625); Leontief 0.7750 (min 0.250,
  frac_zero 0.000, frac_worse_than_equal 0.049, worst change vs equal -0.375); CES 0.6722
  (min 0.000, frac_zero 0.0033, frac_worse 0.263, mean change vs equal negative).
- dir0.1 high: equal 0.4733 (min 0.125); DRF 0.5083 (min 0.000, frac_zero 0.00417,
  frac_worse 0.188, worst -0.500); Leontief 0.5452 (min 0.125, frac_zero 0.000,
  frac_worse 0.070, worst -0.250); CES 0.4553 (min 0.000, frac_zero 0.0325, frac_worse 0.320).

Reading: relative to DRF, Leontief has a higher mean, a higher observed minimum, no increase in
zero-completion, and fewer agents worse than equal. Relative to EQUAL, Leontief is not a
uniform improvement: about 4.9% (moderate) and 7.0% (high) of agents complete fewer tasks than
under equal, with a worst observed individual change of -0.375 and -0.250. These are
finite-sample observations at 200 seeds, not guarantees. Cobb-Douglas tracks these patterns;
CES is worse than DRF under concentration.

## Reproduction results (clean clone at 073a5d6)

Environment: Python 3.12.12; Java 21.0.1 on PATH (Maven toolchain Java 24.0.1); Maven 3.9.10;
cvxpy 1.5.3, clarabel 0.9.0, numpy 1.26.4, scipy 1.13.1, pandas 2.2.2; macOS Darwin 24.6.0 arm64.

Tests (all in the clone):
- pilot + confirmatory + local-opt tests: 67 passed, 0 failed, 0 errored, 0 skipped
- canonical Python suite: 86 passed (2 benign solver-accuracy warnings)
- Java suite (`mvn -o test`): 63 run, 0 failures, 0 errors, 0 skipped
- claim scan (whole repo): passed
- canonical consistency (`check_consistency.py --with-manifest`): passed
- pilot audit (`audit_pilot.py`): all 21 checks passed
- both manifests verify: top-level 55/55, confirmatory 19/19
- `git diff --check`: clean

Full confirmatory rerun in the clone (19,600 runs, 8,928 s; 0 infeasible / capacity / bound /
fallback; disjointness overlaps all 0):
- `raw/agents.csv`, `raw/scenarios.csv`, `summary.json`: BYTE-IDENTICAL to the committed data.
- `raw/runs.csv`: differs ONLY in `alloc_latency_ms` (11,066 rows); 0 rows differ in any other
  column; 0 solver-status differences.
- Exact equality holds for seeds, scenarios, workload hashes, allocations, task outcomes, agent
  outcomes, solver statuses, violations, primary statistics, and the pass/fail decision. The
  solver's continuous output was byte-identical (agents.csv identical), so the
  floating-point-vs-integer distinction did not even arise. Only wall-clock latency differs.

## Claims audit

1. "The frozen confirmatory rule passed in both co-primary cells." — SUPPORTED AS WRITTEN.
2. "Joint Leontief completed more tasks than DRF by more than one task per run in both
   co-primary cells." — SUPPORTED AS WRITTEN (+2.595 and +1.770; both interval lower bounds
   also exceed one task/run).
3. "Joint Leontief also completed more tasks than equal quotas." — SUPPORTED ONLY WITH NARROWER
   WORDING: on average, and as a preregistered SECONDARY (not primary) comparison; it is not a
   per-agent statement (about 5-7% of agents complete fewer tasks than under equal).
4. "The effect survived exact local task selection." — SUPPORTED AS WRITTEN, with the note that
   the check is an exact OFFLINE best-subset selection under the same installed allocation
   (+2.555 and +1.895, intervals above zero).
5. "The response was monotone with heterogeneity." — CONTRADICTED. Narrower accurate statement:
   strictly monotone at moderate contention; generally increasing but NOT monotone at high
   contention (a decrease from Dirichlet alpha 3.0 to alpha 1.0).
6. "The result is a property of allocation rather than task ordering." — SUPPORTED ONLY WITH
   NARROWER WORDING: the advantage is not eliminated by an exact offline best-subset selection
   under the same installed allocation, so it is not merely an artifact of the generated task
   order; the local-selection control is offline, not runtime-executed.
7. "The result supports a general advantage for Leontief." — CONTRADICTED. Narrower: a
   CONDITIONAL advantage that appears only at high realized resource-demand dissimilarity and is
   absent (interval includes zero) at the current low-heterogeneity iid_uniform design.
8. "The result supports centralized rather than decentralized allocation." — UNSUPPORTED. Equal
   and DRF are also platform-computed; the experiment compares allocation objectives under one
   authority, not authority structure.
9. "No individual agent was made worse off." — CONTRADICTED. Relative to equal, about 4.9%
   (moderate) and 7.0% (high) of agents complete fewer tasks; worst observed individual change
   -0.375 and -0.250. Narrower: relative to DRF, Leontief did not lower the observed minimum in
   these samples.
10. "No observed zero-completion increase occurred relative to DRF." — SUPPORTED ONLY WITH
    NARROWER WORDING: in these 200-seed samples the observed zero-completion fraction under
    Leontief (0.000) did not exceed DRF's (0.0008 moderate, 0.0042 high); this is an observed
    finite-sample count, not a guarantee.

## Exact defensible claim

In the tested synthetic workloads, when agents' mandatory resource profiles are sufficiently
dissimilar (Dirichlet alpha = 0.1, realized resource-demand total variation ~0.117), a
complementarity-aware Leontief weighted-proportional-fairness allocation completed more tasks
than DRF, by +2.595 tasks per 48-task run (95% interval [2.295, 2.890]) at moderate contention
and +1.770 (95% interval [1.505, 2.045]) at high contention, under the platform's
exact-pending-queue (oracle) declaration condition and the canonical runtime contract path. The
result passed a preregistered rule in both cells, reproduced byte-for-byte in a clean rerun, and
was not eliminated by an exact offline best-subset task-selection control. As a secondary
comparison Leontief's mean also exceeded equal quotas, though a minority of agents (about 5-7%)
completed fewer tasks than under equal.

## Every discrepancy found

- The pasted summary's flat "monotone with dissimilarity" is not accurate (not monotone at high
  contention). The committed reports use "rises roughly monotonically", which is defensible.
- The pasted headline phrasing "more tasks than DRF (and equal)" places a secondary (equal)
  comparison alongside the primary (DRF) one; equal is preregistered as secondary.
- The pasted high-contention interval was corrupted; the correct interval is [1.505, 2.045]
  tasks/run (fraction [0.03135, 0.04260]).
- `alloc_latency_ms` is nondeterministic across runs (expected timing field); it is the only
  raw field that changed on rerun.
- 29 of 11,200 joint solves report `optimal_inaccurate` — a valid feasible cvxpy status, also
  present in the canonical evaluation; it did not affect determinism (rerun byte-identical).
- No defect was repaired during this verification; all discrepancies are reporting/wording
  nuances, not primary-result failures.

## Material limitations of the verified result

Synthetic task execution; conditional on high realized heterogeneity (null at the current
design); oracle exact-pending-queue declarations (no elicitation, staleness, strategic reporting,
or realistic task arrival tested); a single random-seed platform; the Leontief-vs-equal
comparison is a mean advantage with a distributional trade-off, not a per-agent guarantee; the
result does not speak to authority structure, universal Leontief superiority, individual
guarantees, or collusion resistance, and the reports do not claim these.

## Files created (all under verification/)

INDEPENDENT_VERIFICATION.md, provenance.txt, independent_reconstruction.py, verify_local_opt.py,
reconstructed_primary.json, reconstructed_curve.csv, reconstructed_distribution.csv,
local_opt_record_comparison.csv, reproduction_comparison.json, test_log.txt, environment.txt,
diff_bfab534_to_head.txt, original_confirmatory_sha.txt, SHA256SUMS.

## Git status

Original worktree remained clean throughout (only the untracked `verification/` directory was
added by this audit; 0 tracked-file modifications). The full rerun was performed in a separate
`git clone --no-hardlinks` clone; the original committed results were never overwritten.
