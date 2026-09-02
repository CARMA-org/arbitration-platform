# Raw data schema

All raw tables are CSV with a header row, written in a single canonical sort order (by
their unique key) so the committed data is byte-identical regardless of execution or
resumption order.

## Architecture (`results/architecture_v1/raw/`)

### `scenarios.csv` — one row per (cell, seed)
`cell, regime, concentration, contention, contention_ratio, seed, task_workload_hash,
scenario_hash, declaration_source, latent_probs_by_agent, realized_task_counts_by_agent,
unique_archetypes_per_agent, frac_agents_all_four_archetypes, task_entropy_mean,
task_mixture_tv_mean_pairwise, resource_demand_tv_mean_pairwise,
resource_centroid_distance_mean, aggregate_mandatory_demand, capacity_by_resource,
realized_contention_by_resource, active_resource_count`

### `runs.csv` — one row per (cell, seed, arm)
`cell, regime, contention, seed, arm, solver_status, feasible, fallback_used,
queue_order_completion_mean, queue_order_tasks_per_run, best_subset_completion_mean,
best_subset_tasks_per_run, frac_zero_qo, frac_zero_bs, capacity_utilization,
unused_installed_total, capacity_violation, bound_violation, alloc_latency_ms`

### `agents.csv` — one row per (cell, seed, arm, agent)
`cell, regime, contention, seed, arm, agent, archetype, queue_order_completion,
best_subset_completion, best_subset_count, mandatory_failures, allocated, charged, unused,
min_bound, upper_bound`

### `distributed.csv` — one row per (cell, seed)
`cell, contention, seed, central_status, central_objective, distributed_objective,
rel_obj_gap, distributed_converged, iterations, message_count, runtime_ms,
capacity_residual, bound_residual, primal_residual, dual_residual, cont_alloc_l1_norm,
cont_alloc_linf, installed_alloc_l1_norm, installed_alloc_linf,
installed_outcome_disagreements, technically_valid`

### `infeasible.csv` — one row per infeasible (cell, seed, arm)
`cell, seed, arm, solver_status, failure_reason`

Arms: `equal, drf, central_joint_leontief, independent_bundle_maxmin,
separable_leontief_relaxation, distributed_price_leontief`.

## Drift (`results/drift_v1/raw/`)

### `scenarios.csv` — one row per (cell, seed), cell = `delta<d>__<contention>`
`cell, delta, contention, contention_ratio, seed, task_workload_hash, capacity_by_resource,
realized_contention_by_resource, active_resource_count, drift_source_total,
changed_identities_total, task_mixture_tv_from_baseline_mean, mand_demand_tv_mean_pairwise,
task_entropy_mean, cross_agent_dissimilarity, staleness_error_mean, calibration_error_mean,
latent_oracle_error_mean`

### `runs.csv` — one row per (cell, seed, arm)
`cell, delta, contention, seed, arm, policy_kind, declaration_source, solver_status,
feasible, fallback_used, queue_order_completion_mean, queue_order_tasks_per_run,
best_subset_completion_mean, best_subset_tasks_per_run, frac_zero_qo, frac_zero_bs,
capacity_utilization, unused_installed_total, capacity_violation, bound_violation,
alloc_latency_ms`

### `agents.csv` — one row per (cell, seed, arm, agent)
`cell, delta, contention, seed, arm, policy_kind, declaration_source, agent,
queue_order_completion, best_subset_completion, best_subset_count, mandatory_failures,
allocated, declared_demand, realized_demand`

### `declarations.csv` — one row per (delta, contention, seed, agent, source)
`delta, contention, seed, agent, source, declared_demand, staleness_error,
calibration_error, latent_oracle_error`

### `distributed.csv` — present only if the carrier is the distributed price solver
`cell, delta, contention, seed, declaration_source, central_status, central_objective,
distributed_objective, rel_obj_gap, distributed_converged, iterations, capacity_residual,
bound_residual, installed_alloc_l1_norm, installed_outcome_disagreements, technically_valid`

### `infeasible.csv`
`cell, seed, arm, solver_status, failure_reason`

Arms: `equal`, and `drf_<source>` and `carrier_<source>` for each of the four declaration
sources (`stale_calibration, refreshed_calibration, latent_distribution_oracle,
execution_queue_oracle`); plus `central_ref_<source>` if the carrier is the distributed
price solver.

## Units and conventions

Completion means are per-agent fractions of 8 tasks; `*_tasks_per_run` multiplies by the
6 agents and 8 tasks to give tasks per 48-task run. Differences are reported in tasks per
48-task run. `scenario_hash` is identical across arms within a (cell, seed);
`task_workload_hash` is identical across contention within a workload (architecture) and
across declaration sources within a physical scenario (drift).
