# Corrections after verification (v2)

This document records every correction applied on branch
`correction/original-question-closure-v2`, created from the final experimental head
`1e2e1d968e9204a44567c3571c0d75f5900319cc` using **additive commits only**.

## 0. What did NOT change (invariants)

No raw datum, experimental mechanism, frozen rule, seed, or outcome was changed. All of the
following are byte-identical to `1e2e1d9`:

- every raw CSV under `results/architecture_v1/raw/` and `results/drift_v1/raw/`;
- every headline JSON (`architecture_headline.json`, `drift_headline.json`) and result
  table (`tables/*.csv`);
- `results/architecture_v1/summary.json` and `results/drift_v1/summary.json`;
- `DRIFT_CARRIER_DECISION.json`, the two `EXPERIMENT_MANIFEST.json` files, and the frozen
  configs `config/architecture_v1.json`, `config/drift_v1.json`, the seed manifest
  `CONFIRMATORY_SEED_MANIFEST.json`, and the preregistration protocol
  `ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md`;
- everything in `oqlib/`, the experiment drivers, `make_oq_analysis.py`, `select_drift_carrier.py`,
  `make_oq_manifest.py`, `comparator_audit.json`, and `distributed_validation.json`.

Independent reconstruction directly from the frozen raw CSVs reproduces every headline
mean, every 95% percentile-bootstrap CI endpoint, every five-condition flag, the carrier
selection, the distributed-equivalence classification, all harmed-set and distributional
statistics, and the exact best-subset counts for all 14,400 architecture and 108,000 drift
agent records. The corrections below are therefore about **public interpretation and omitted
reporting**, not about the data or the experiment.

## 1. Documentation defects corrected (no experimental content)

These are defects of wording, emphasis, or an omitted secondary output. None touches a raw
datum, a mechanism, a frozen rule, a seed, or an outcome.

### 1.1 `ORIGINAL_QUESTION_CLOSURE.md` (regenerated from a corrected `make_closure_report.py`)

1. **Headline is the preregistered conditional claim.** The narrow central claim is replaced
   by the exact conditional existence claim (report Sections 0 and 13).
2. **ARB principle framed as an existence result within a platform** (Sections 0, 17), not a
   universal claim.
3. **No causal claim about platform authority.** Because all six arms share platform
   installation and enforcement, the experiments compare allocation *rules* with authority
   held fixed and do not estimate the causal value of platform authority (Sections 1, 15).
4. **Coordination increment described honestly.** Central-minus-independent is a
   statistically positive but immaterial increment (+0.685 / +0.355 tasks/run), below the
   frozen +1.000-task materiality bar, so `coordination_pass=False`; that machine-checked
   flag, not the sign of the increment, is authoritative (Section 6).
5. **Local mechanism captured most of the gain.** The independent resource-local mechanism
   captured about 74% (moderate) and 81% (high) of central Leontief's advantage over DRF
   (Section 6).
6. **Claim matrix corrected** to the seven preregistered rows with the correct verdicts
   (Section 14): fresh-seed existence replicates = True; material cross-resource coordination
   advantage established = False; a positive result over DRF requires joint computation =
   False; independent fully reproduces / is noninferior = False; central objective requires
   the central solver = False; central and distributed impose identical losses = False;
   relative advantage survives modest drift = True.
7. **Fresh harmed-agent fractions** are 4.4% (moderate) and 6.7% (high) versus equal quotas
   (Section 8) -- as already recorded in the frozen headline; stated plainly.
8. **Severe drift is excluded from the robustness headline.** `ROBUST_AT_MODEST_DRIFT` means
   only that the frozen *relative* stale-carrier-minus-stale-DRF comparison passed at delta
   0.25. At higher drift, stale declarations reduce absolute completion substantially and the
   stale carrier can fall below equal quotas (e.g. delta 1.00: carrier 23.53 vs equal 26.73
   moderate; 17.65 vs equal 18.95 high) (Section 11-12).
9. **Verification links** now point to the first verification commit
   `d2d77dbe33c4a5b6f9770f225b19ee68b45f1514` and the comprehensive v2 verification commit
   `c678a0a96aba563ceff52e4d6b889fb90db316ec` (Section 16, immutable commit URLs), replacing
   the `<verify>` placeholder.
10. **Comparator qualified** as "the strongest tested resource-local comparator," never
    universally strongest (Section 3).
11. **No causal-coordination claim and no finality overreach.** The report does not assert
    that coordination caused the result, and it does not assert that no further empirical
    question could matter (Sections 6, 17).

### 1.2 `COMPARATOR_AUDIT.md` (prose correction only; comparator selection unchanged)

The interpretive sentence in Section 3.2 previously read that "the second and third
constructed examples" diverge. That is a prose error: by the audit's own `differ` flags, the
**first and third** constructed symmetric examples coincide and **only the second**
(asymmetric) example diverges. The sentence is corrected in both `COMPARATOR_AUDIT.md` and
its generator `make_comparator_audit.py` (now data-driven from the `differ` flags so it
cannot drift again). The 120-of-120 randomized distinctness result is preserved unchanged;
the comparator selection (independent bundle max-min as the primary resource-local
comparator) is unchanged.

### 1.3 `DISTRIBUTED_SOLVER.md` (added scope correction; `oqlib/distributed.py` unchanged)

A new Section 6 clarifies that the `distributed_price_leontief` arm is a **single-process
simulation** of a price-decomposed algorithm. It does not call the central convex optimizer,
but it holds global arrays in one process and uses a **global scalar feasibility repair**. It
establishes algorithmic decomposability and central-solver dispensability -- not a deployed
distributed system, not privacy, and not the absence of all cross-resource communication.

## 2. Omitted preregistered secondary reporting, now emitted (analysis-only)

The frozen protocol (Section 6) promised drift distributional outcomes, utilization, realized
contention, and dissimilarity, but `drift_headline.json` omitted several of them. They were
recorded in the raw data or are deterministically derivable from it under the preregistered
analysis, but were not emitted into the original public summary; they are now emitted as a
**post-result completion of preregistered secondary reporting**, reading only the frozen raw
files, with no discretionary threshold or arm selection, by `complete_drift_secondary.py` into
`results/drift_v1/preregistered_secondary_completion/` (JSON + CSV + tests). The existing
`drift_headline.json` and `drift_response.csv` are **not** altered.

Note on two bootstrap streams for one comparison: the co-primary stale-carrier-minus-stale-DRF
interval and the secondary copy of the same comparison use different hash-derived bootstrap
streams (different `name` strings feed the frozen RNG), which is why some interval endpoints
differ by 0.005 (e.g. moderate co-primary `[1.315, 1.895]` vs secondary `[1.310, 1.895]`).
**The co-primary interval is authoritative.** Neither frozen output is silently replaced.

## 3. Correction manifest

`CORRECTION_MANIFEST.json` records SHA-256 hashes for all original raw files, the original
experiment manifests, the corrected documents and scripts, the new tests, and the new
supplemental outputs, together with the invariance assertion of Section 0.

## 4. Distinction summary

| defect | kind | fix |
|---|---|---|
| Narrow/overclaimed public interpretation | documentation | regenerated report from corrected generator |
| "second and third diverge" | documentation (prose) | corrected sentence + data-driven generator |
| Distributed arm overread as deployed/private | documentation | added scope section |
| Preregistered drift secondaries omitted | omitted reporting | new analysis-only script + outputs + tests |
| Verification coverage overstated (v1) | verification scope | comprehensive v2 verifier (separate branch) |

None of these is an experimental defect: the mechanisms, frozen rules, seeds, raw data and
outcomes are exactly as at `1e2e1d9`.
