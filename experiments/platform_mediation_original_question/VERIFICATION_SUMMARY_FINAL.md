# Final independent verification — original-question closure

**Verifier:** `verify_oq_final.py` (authoritative; supersedes `verify_oq_v2.py`).
**Machine report:** `VERIFICATION_REPORT_FINAL.json`.
**Status:** `VERIFIED` — **94 / 94** checks pass on the committed data.

This verifier imports **no** experiment code (no drivers, allocators/optimizers, analysis
generator, carrier selector, secondary-completion generator, or manifest generators). It uses
only the Python standard library and NumPy, and reconstructs every reported quantity directly
from the frozen raw CSVs with its own seed derivation, paired percentile bootstrap, exact
256-subset enumeration, five-condition rule, equivalence/noninferiority tests, harmed-set and
distributional statistics, adaptive carrier rule, and drift classification tree.

## What this pass is — and is not

This pass **re-derives results from committed raw data**. It does **not** re-execute the
20,400 confirmatory policy runs (2,400 architecture + 18,000 drift). End-to-end reproduction
from a clean clone (re-running the experiments, analyses, carrier selection and manifests) is
a **separate** step, documented in `REPRODUCIBILITY.md` and enforced in CI, and is not what
this verifier does.

Evidence is reported in five distinct kinds (see `by_category` in the JSON report):

| Category | Meaning | Checks |
|---|---|---|
| `git_provenance` | commit ancestry + **independent** immutable-blob byte identity | 13 |
| `git_provenance_and_regeneration` | experiment + correction manifest artifact hashes recomputed | 4 |
| `raw_derived_structure` | exact schemas, keys, joins, workload/CRN invariants | 22 |
| `raw_derived_and_formula` | seed disjointness from **actual committed** seed sets + formula | 10 |
| `raw_derived` | every architecture/drift/supplement statistic re-derived from raw | 43 |
| `static_source_inspection` | distributed module invokes no central solver | 2 |

## Coverage (explicit; not a single aggregate number)

* **14,400** architecture agent records — best-subset reconstructed by enumerating **all 256
  subsets** of each 8-task queue; task counts read from the scenario rows.
* **108,000** drift agent records — best-subset reconstructed by enumerating **all 256
  subsets**; each task composition **independently recovered** from the agent's realized
  mandatory demand by exact 4×4 integer solve (uniqueness asserted for every record).
* **90** preregistered-secondary supplement entries (10 drift cells × 9 arms) — every JSON
  field reconstructed from raw, every CSV cell reconstructed from raw, and JSON↔CSV agreement
  checked directly; exact header, 90-row count and duplicate-key absence enforced.
* **200** architecture + **200** drift confirmatory seeds — loaded from the committed raw data
  and compared for **actual** overlap against the committed canonical (400), pilot (30) and
  heterogeneity-confirmatory (200) seed sets (all overlaps **0**), then cross-checked against
  the derivation formula and the committed seed manifest.
* **22** preregistration-frozen files and **21** result-frozen files — byte identity verified
  independently against commits `7ebf8b7` (preregistration) and `1e2e1d9` (result head).

## Check inventory

### Provenance & immutability (`git_provenance`, 13)
Commit chronology `prereg → arch → drift → result → correction → v2`; preregistration and v2
are ancestors of the candidate; **every** preregistration-frozen file and **every**
result-frozen file is byte-identical to its anchor (independently hashed working tree **and**
candidate blob, not trusting any manifest boolean); `CORRECTION_MANIFEST` base is the result
head; its recorded `original_raw`/`original_manifest` hashes are independently reconfirmed
against `1e2e1d9`; the carrier decision's preregistration commit and protocol hash match.

### Manifests (`git_provenance_and_regeneration`, 4)
Architecture, drift and correction manifest artifact SHA-256 values recomputed from disk;
correction manifest artifact count = 24.

### Seed disjointness (`raw_derived_and_formula`, 10)
Actual overlaps arch/drift × canonical/pilot/heterogeneity all zero; arch and drift each 200
unique; prior sets non-empty; actual seeds equal the derivation formula and the committed seed
manifest lists; confirmatory disjoint from development and canonical formulas.

### Schemas & joins (`raw_derived_structure`, 10)
Exact ordered headers for all 11 raw tables; exact row counts; unique logical keys (no
duplicate rows); one scenario per (cell,seed); every arm exactly once per scenario; all six
agents join to each run with no orphans; declarations complete (4 sources × 6 agents) and
joined to scenarios; exact cell sets.

### Workload-hash & common-random-number invariants (`raw_derived_structure`, 12)
Architecture workload reused across contention per seed (200 unique across 400 rows);
architecture composition reused across contention. **Drift workload hash is unique per
physical scenario (2,000 across 2,000 rows)** and shared across the 9 declaration-source arms
(the drift reuse dimension is declaration source, not contention — see the audit note); drift
**physical composition** is reused across contention per (delta,seed); capacity invariant
across delta per (contention,seed); CRN nesting: drift-source count monotone in delta, zero at
delta 0, full (48) at delta 1, and changed-identities ≤ drift-source draws for every scenario.

### Architecture (`raw_derived`, 21)
Independent re-validation of the mandatory-footprint table against every scenario's aggregate
demand; every arm mean (queue-order/best-subset tasks-per-run, completion means, zero-fraction,
utilization); every paired mean and CI (queue-order and best-subset); zero-event totals from
raw; every five-condition component; per-cell noninferiority and equivalence; separable-
relaxation structural equalities; every distributed gap-summary field; distributed outcome
stats and classification from raw; every distributional statistic (each arm vs equal and drf);
every harmed-set statistic (central vs distributed vs equal and drf); **raw-derived carrier
flags** matched to the headline and to the carrier decision; the adaptive carrier rule
re-evaluated from raw flags; best-subset for all 14,400 records; direct allocation feasibility;
summary record counts.

### Drift (`raw_derived`, 12)
Carrier is central/native/non-distributed; zero-event totals from raw; co-primary mean, CI and
five-condition; **both independently-seeded appearances** of the primary comparison; every
secondary mean and CI (per-source carrier−DRF, stale−refreshed for carrier and DRF,
difference-in-differences, best-subset, and every arm mean) across all 10 cells; the
**classification via the exact frozen tree**; all drift metrics; best-subset for all 108,000
records; unique composition recovery; direct allocation feasibility; summary counts.

### Preregistered-secondary supplement (`raw_derived`, 10)
Present; exact cells/arms; exact CSV header; 90-row count; no duplicate keys; every JSON
cell/arm field vs raw; scenario metrics per cell; every CSV cell vs raw; JSON↔CSV agreement;
90 JSON entries.

### Distributed inspection (`static_source_inspection`, 2)
`oqlib/distributed.py` invokes no central optimization solver (no cvxpy, no joint-solver
module, no central-solver import or call); it imports only the objective *evaluator*
`leontief_objective` from `.central` to score its own allocation.

## The safe scientific conclusion

The allocation-rule core of the original ARB idea is supported **as a conditional existence
result inside a platform**, at the tested and preregistered scope (synthetic six-agent,
four-resource, Dirichlet(0.1) heterogeneous workloads):

* Central joint Leontief beats DRF by **+2.655** tasks/run (95% CI [2.360, 2.950]) at moderate
  and **+1.825** ([1.545, 2.100]) at high contention.
* The resource-local **independent bundle max-min also beats DRF** (**+1.970** [1.650, 2.285]
  moderate; **+1.470** [1.185, 1.755] high), capturing ≈74% and ≈81% of central's advantage.
* Central − independent is positive (**+0.685** [0.495, 0.880]; **+0.355** [0.150, 0.560]) but
  **fails** the frozen +1.000-task materiality threshold, so `coordination_pass = False`. These
  experiments therefore **do not** establish that the positive result requires centralized
  joint cross-resource computation.
* The price-mediated distributed method reproduced the centralized continuous objective and
  aggregate completion within the frozen equivalence criteria, but did **not** reproduce
  exactly which agents bore losses.
* Under stale declarations estimated before 25% task-source drift, the selected central carrier
  beats stale DRF (**+1.605** [1.315, 1.895] moderate; **+1.500** [1.230, 1.775] high):
  `ROBUST_AT_MODEST_DRIFT`, a **relative** advantage over stale DRF at delta 0.25 — not an
  absolute-performance or severe-drift guarantee. At delta 1.00 stale central completion falls
  **below** equal quotas (23.53 vs 26.73 moderate; 17.65 vs 18.95 high).
* All arms held platform installation and enforcement authority fixed; the experiments varied
  allocation/computation rules, **not** the presence or locus of authority, and do not
  estimate the causal value of platform authority. No universal claim and no strict-monotonicity
  claim is made.
