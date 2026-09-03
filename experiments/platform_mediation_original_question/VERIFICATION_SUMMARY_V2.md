# Independent verification v2 — summary

Branch `verification/platform-original-question-closure-v2`, created from the correction
commit on `correction/original-question-closure-v2` (which is itself created from the final
experimental head `1e2e1d968e9204a44567c3571c0d75f5900319cc` with additive commits only).

The v2 verifier (`verify_oq_v2.py`) uses only the Python standard library and NumPy. It
imports **none** of the experiment's drivers, analysis, carrier-selection code, manifest
helpers, local task optimizers, or allocator implementations. It reconstructs every reported
quantity directly from the frozen raw CSVs with its own seed derivation, paired percentile
bootstrap, exact best-subset enumeration, five-condition rule, equivalence/noninferiority
classifications, harmed-set and distributional statistics, adaptive carrier rule, and
manifest hashing.

## Verification status: VERIFIED

`VERIFICATION_REPORT_V2.json`: **65 / 65 checks pass** across six sections.

| section | checks | what is reconstructed and compared |
|---|---:|---|
| git | 5 | commit chronology (prereg → arch → drift → head), preregistration ancestry, and byte-identity of every outcome-relevant source/config/protocol file between preregistration `7ebf8b7` and head `1e2e1d9` |
| manifests | 4 | every architecture, drift, and correction-manifest artifact hash; correction-manifest invariance flag |
| architecture | 30 | schema, row counts, unique keys, all six arms per seed, complete pairing, workload identity; confirmatory seed derivation and disjointness from drift, prior-heterogeneity, canonical, and development namespaces; every arm mean; every paired queue-order and best-subset mean **and CI**; every component of each five-condition decision; noninferiority and central–independent equivalence; separable-relaxation structural result; every distributed objective/feasibility/convergence/allocation-gap/aggregate-outcome statistic; every distributional statistic; every harmed-set statistic; the adaptive carrier rule; exact best-subset by enumerating all 256 subsets of all 14,400 agent records; allocation feasibility (installed sums vs capacities, floors and upper bounds); summary aggregations and zero-event claims |
| drift | 21 | schema, row counts, unique keys, all nine arms per seed; seed derivation and disjointness; the co-primary comparison mean **and CI** in both cells and both independently-seeded appearances of it; every secondary mean **and CI** (per-source carrier−DRF, stale−refreshed, difference-in-differences, best-subset, arm means); classification; all drift metrics; exact best-subset over all 108,000 agent records via uniquely-recovered archetype compositions; allocation feasibility; summary and zero-event claims |
| secondary supplement | 3 | presence, shape (10 cells × 9 arms), and independent reconstruction of representative distributional cells in the preregistered secondary drift completion |
| distributed | 2 | the distributed solver invokes no central optimization routine (AST import/call-graph inspection); it is a single-process simulation with a global feasibility scale |

Exact best-subset verification does not merely range-check the recorded count: for every
agent record the task multiset is reconstructed (from the scenario rows for architecture, and
from a uniquely-recovered nonnegative archetype composition of the realized mandatory demand
for drift), all 256 subsets are enumerated, and the resulting maximum feasible subset size is
compared to the recorded per-agent count, its completion fraction, and the aggregated
per-run field. Every one of the 14,400 architecture and 108,000 drift records matches.

The drift CI comparison compares interval **endpoints**, not only means and pass flags. The
co-primary interval and its secondary copy use different hash-derived bootstrap streams; both
are reconstructed and matched to their frozen counterparts (the co-primary interval is
authoritative; the 0.005 endpoint differences are reproduced exactly).

## Distributed solver, reported separately

The distributed arm imports only the objective **evaluator** `leontief_objective` (used to
score its own allocation for the gap statistic); it does not import cvxpy, the canonical
joint solver, or the central **solver** routine `reduced_central_leontief`, and it calls no
solver. It is therefore established to invoke no central optimization routine. Separately,
and reported as a characteristic rather than a defect: it is a **single-process simulation**
that holds global arrays in one process and uses a **global scalar feasibility repair**. It
establishes central-solver dispensability and algorithmic decomposability, not a deployed
distributed system, privacy, or the absence of all cross-resource communication.

## The first verification overstated its coverage

Stated directly: the first verification (`verify_oq.py`, branch
`verification/platform-original-question-closure`, commit
`d2d77dbe33c4a5b6f9770f225b19ee68b45f1514`) provided useful primary reconstructions and a
genuinely useful full clean-clone rerun with exact equality of all non-timing raw fields.
But it **overstated its coverage**: its summary claimed it reconstructed "every reported
quantity … using its own … best-subset enumeration [and] harmed-set statistics," whereas in
fact it

* did **not** enumerate best subsets — it only range-checked that recorded counts lie in
  `[0, 8]` on 40 records;
* did **not** reconstruct the harmed-set or distributional statistics at all;
* compared only three architecture paired comparisons (means and CIs) and the drift
  co-primary **means** (not CI endpoints, not the secondary comparisons, not the drift
  metrics, not the distributed gap-summary fields, not the arm means);
* did **not** compare all generated analyses.

This v2 verification closes those gaps: it reconstructs and compares every claimed quantity,
enumerates all 256 subsets for all 122,400 agent records, and compares all generated
analyses including the newly-emitted preregistered secondary supplement.

## Reproduction evidence

`compare_reproduction_v2.py` reran ONLY the deterministic analyses (architecture and drift
analyses, carrier selection, the preregistered secondary drift completion, and the manifests)
over a temporary copy of the frozen raw data and compared the regenerated headline JSON,
result tables, summaries, carrier decision, secondary supplement, and manifests to the
committed frozen outputs. All match, with explicitly documented exclusions for
environment-dependent serialization (`drift_headline.json` is compared semantically because
its `arm_tasks_per_run` block iterates a Python `set` whose order is `PYTHONHASHSEED`-
dependent; manifest `generated_utc`/`source_commit` and the regenerated `EXPERIMENT_MANIFEST`
artifact entries are excluded). A preserved clean clone (`git clone --no-hardlinks` at
`1e2e1d9`) from the first verification was still present and its raw tables were additionally
diffed: every non-timing field of all eleven architecture and drift raw tables is identical.
See `CLEAN_REPRODUCTION_V2.txt`. This is not a second full clean experiment rerun (none was
performed); it is the preserved exact-raw reproduction evidence plus a fresh deterministic
analysis reconstruction.

## Scope note

Scientific hypotheses failing does not make verification fail. This verification confirms
that the reported statistics, decisions, classifications, carrier selection, corrected
report, and secondary supplement follow from the committed raw data, that no outcome-relevant
input changed between preregistration and the experimental head, and that the deterministic
analyses reproduce exactly.
