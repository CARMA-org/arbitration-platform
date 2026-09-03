# Audit note — how the final verification extends and supersedes v2

`verify_oq_final.py` is the authoritative verifier. It was written independently and does not
share code with `verify_oq_v2.py`. The v2 verifier, its tests and its reports remain in the
tree and, more importantly, their **exact original blobs remain recoverable at commit
`c678a0a96aba563ceff52e4d6b889fb90db316ec`** (branch tip `verification/original-question-v2`
tag) and in the pre-consolidation recovery bundle. Nothing about the v2 checkpoint is rewritten.

The v2 verifier reported a "65/65" pass label. That label was accurate for the checks v2
actually implemented, but several of those checks were narrower than their names implied, one
trusted a manifest's own boolean, and the code contained dead/misleading fragments. The final
verifier closes every gap below and adds exhaustive coverage; it reports **94/94** and its
own test suite includes a code-quality self-audit.

## v2 gaps closed by the final verifier

### A. Secondary supplement — representative → exhaustive
* **v2:** `verify_secondary_supplement` reconstructed only a *representative* set (3 cells ×
  2 arms, plus one carrier-vs-matched-DRF block) and did not compare JSON↔CSV, key sets, row
  count or duplicates. The docstring nonetheless described the supplement as fully verified.
* **final:** reconstructs **all 10 cells × 9 arms = 90 entries**, **every** emitted numeric
  field (run means, `vs_equal` and `vs_matched_drf` distributional blocks, scenario metrics),
  checks the **exact** CSV header, the 90-row count, duplicate-key absence, and **direct
  JSON↔CSV agreement**. Mutation tests cover a non-representative cell (delta 0.75), an arm not
  in v2's representative set (`drf_execution_queue_oracle`), a CSV-only value, a JSON-only
  value, a header change and a duplicated row key — each fails verification.

### B. Exact schemas and structural joins (new)
* **v2:** checked row counts, run-key uniqueness and "all arms present", but not exact headers,
  per-table duplicate logical keys, agent-join completeness, orphan absence, or the
  declarations join.
* **final:** exact ordered headers for all 11 raw tables; unique logical keys per table; one
  scenario per (cell,seed); every arm exactly once; all six agents joined with no orphans; the
  declarations table complete (4 sources × 6 agents) and joined to scenarios.

### C. Workload-hash & CRN invariants — misleading check corrected
* **v2:** `workload_hash_unique_per_seed` only checked that a scenario row existed for each
  (cell,seed) — it did **not** verify uniqueness or reuse; `workload_hash_count` trusted
  `summary["disjointness"]["n_workload_hashes"]`.
* **final:** verifies architecture workload reuse across contention (200 unique across 400)
  from raw; verifies the **drift** workload hash is unique per physical scenario and derives
  the **correct count of 2,000** (not 1,000 — see note below); checks capacity invariance
  across delta and the common-random-number nesting invariants (drift-source monotone in delta,
  0 at delta 0, 48 at delta 1, changed-identities ≤ drift-source draws).

  **Documented correction to an expected count.** An earlier expectation stated 1,000 unique
  drift workload hashes across 2,000 scenario rows (i.e. reuse across contention for the same
  (delta,seed)). The frozen data show **2,000** unique hashes: the drift design scopes the
  workload hash to the physical scenario (cell,seed) and reuses it across the **nine
  declaration-source arms**, not across contention (consistent with `SCHEMA.md`, which states
  the drift reuse dimension is "across declaration sources within a physical scenario"). The
  *physical task composition* **is** reused across contention for the same (delta,seed), and
  the verifier confirms that separately. Per the instruction to derive and document the correct
  count rather than force a stated number, the verifier asserts 2,000.

### D. Seed disjointness — formula → actual committed sets
* **v2:** regenerated formulas only (`canonical_seed_universe`).
* **final:** additionally **loads the actual committed seed sets** from the canonical
  evaluation, the heterogeneity pilot, the heterogeneity confirmatory data, and the
  architecture and drift raw data, and reports exact counts and exact overlaps (all zero),
  **and** cross-checks the derivation formula and the committed seed manifest.

### E. Immutable-file verification — trusted boolean → independent bytes
* **v2:** `correction_manifest_invariance` set a check equal to
  `CORRECTION_MANIFEST.json["invariants"]["original_bytes_match_base"]` — it **trusted the
  manifest's own boolean**.
* **final:** independently hashes the working-tree file and the candidate git blob and compares
  both to the committed anchor blob content at `1e2e1d9` (raw/results/manifests/carrier) and
  `7ebf8b7` (protocol/configs/seeds/drivers/oqlib); then it re-confirms the manifest's recorded
  `original_raw`/`original_manifest` hashes against `1e2e1d9`. A mutation test proves a changed
  immutable blob fails.

### F. Raw-derived decisions — headline flags → recomputed flags
* **v2:** fed `head["flags"]` into the carrier rule.
* **final:** recomputes every carrier flag (`replication_pass`, `coordination_pass`,
  `independent_positive`, `independent_noninferior`, `distributed_equivalent`) from the raw
  five-condition components and equivalence tests, compares them to **both** the headline and
  the carrier decision, re-evaluates the carrier rule from those raw-derived flags, and
  re-derives the drift classification via the **exact frozen tree** (not "not-all-pass").

### G/H. Architecture & drift completeness
* Both retain v2's full 256-subset enumeration over all 14,400 and 108,000 agent records and
  add per-field mean checks, zero-event reconstruction from raw, and the raw-derived
  flag/decision chain above.

### I. Code-quality defects removed / not reproduced
The v2 verifier contained a dead capacity loop (`cap = json.loads(...) if False else None` at
v2 line 761), the misleading check names noted in C/E, and checks that trusted derived
summaries. The final verifier contains **none** of these. Its test suite asserts, by AST scan
of `verify_oq_final.py`, that there are: no duplicate dict-literal keys, no dead
`if True/if False` constant conditionals, no duplicate `checks["…"]` assignment within any
function, globally unique check keys, all-boolean check values, and **no import of experiment
code**. (No duplicate `harm_indicator_agreement` key or duplicate `checks["scenarios_rowcount"]`
assignment exists in the current tree; the self-audit test guards against reintroducing that
class of defect.)

## Relationship to full reproduction
Neither v2 nor final re-executes the confirmatory runs. Full clean-clone reproduction of the
experiments, analyses, carrier selection and manifests is a separate step (see
`REPRODUCIBILITY.md` and the CI matrix). The final report sets `reran_confirmatory_runs: false`
explicitly to avoid any implication otherwise.
