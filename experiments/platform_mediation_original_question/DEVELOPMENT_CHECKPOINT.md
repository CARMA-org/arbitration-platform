# Development checkpoint: original-question closure

This is an incomplete development checkpoint. It is not a preregistration, and it is
not a result. It exists to preserve untracked development-stage work in a durable and
inspectable form. No part of this checkpoint may be presented as confirmatory
evidence.

## Status

- This is an incomplete development checkpoint, not a preregistration.
- No confirmatory seed has been generated and no confirmatory outcome has been
  generated. There is no `results/` directory.
- No primary scientific conclusion has been reached.
- Development-seed technical checks were run (module import checks and a structural
  seed-namespace disjointness check). See `dev_checks.log`.
- Development-seed effect estimates were viewed during engineering de-risking and are
  disclosed below. They are exploratory engineering observations. They are not
  confirmatory evidence and must not be cited as if they were.

## What was built and checkpointed

Development-stage Python under `oqlib/`, reusing the validated heterogeneity and
canonical infrastructure without modifying any existing file:

- `distributed.py` — distributed price-mediated (tatonnement) solver for the canonical
  Leontief weighted-proportional-fairness objective. Resource owners hold local prices,
  agents solve local subproblems, owners update prices from local demand over capacity.
  It does not call the central solver.
- `maxmin.py` — independent-resource weighted progressive-filling comparator. Each
  resource is allocated on its own from that resource's demands, weights, floors, and
  upper bounds, with no cross-resource coordination.
- `driftgen.py` — drift generation with common random numbers, separating drift-source
  draws from changed task identities, with independent Dirichlet drift targets.
- `declarations.py` — stale, refreshed, latent-oracle, and execution-oracle declaration
  construction, baseline-latent physical capacity, and policy-independent no-leakage
  bounds.
- `central.py` — reduced central Leontief reference used as an objective reference.
- `seeds_oq.py` — confirmatory and development seed namespaces and a disjointness check
  against the canonical, pilot, and heterogeneity-confirmatory seeds.
- `__init__.py` — import path setup only.

## Development-seed observations already viewed (exploratory only)

During engineering de-risking, the following were computed on development-only seed
namespaces (`.../development`). They were used to check mathematical correctness and
numerical scale of the new solvers, not to select an algorithm, tolerance, threshold,
or cell by task-completion outcome. They are exploratory and cannot support any
scientific claim.

- The Leontief reduction reproduced the canonical solver objective exactly on the
  sampled development scenarios.
- The distributed solver reached a relative objective gap from the central objective at
  most about 5.8e-6 on the sampled development scenarios, with continuous capacity and
  bound residuals at most about 1.1e-13.
- Distributed minus central task completion had a paired development-seed mean of about
  +0.0125 tasks per 48-task run on the sampled scenarios.
- Central Leontief minus independent-resource max-min had a development-seed mean of
  about +3.16 tasks per run on the sampled scenarios.
- Central Leontief minus DRF had a development-seed mean of about +2.39 tasks per run on
  the sampled scenarios.

These numbers are development-seed engineering observations. They are not confirmatory,
they carry no interval or decision, and they must not be reported as evidence about the
original question.

## What remains unfinished

- The declaration-drift experiment driver and the architecture experiment driver.
- The complete test suite.
- The analysis code, including the paired bootstrap, the frozen decision rules, and the
  classification fields.
- The public protocol and the frozen machine-readable configurations.
- The artifact manifests.
- Confirmatory execution.
- Independent verification with a clean full reproduction.
- Any promotion.

## Constraints for any future continuation

- Confirmatory seeds must remain disjoint from the canonical evaluation seeds, the
  heterogeneity pilot development seeds, and the heterogeneity confirmatory seeds. The
  confirmatory namespaces in `seeds_oq.py` already satisfy this and the disjointness is
  checked structurally.
- Any future protocol must be committed and publicly pushed before any confirmatory
  seed or outcome is generated. The public push must precede confirmatory execution.
- Development-seed effect sizes must not be used to choose the algorithm, tolerances,
  drift levels, primary cells, or thresholds.
