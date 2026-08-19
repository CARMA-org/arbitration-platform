import csv
import os
import time
import numpy as np

from . import rules, metrics

RESULTS = os.path.join(os.path.dirname(__file__), "..", "results")
RAW = os.path.join(RESULTS, "raw")

RAW_FIELDS = [
    "experiment", "cell", "seed", "rule", "gamma",
    "achieved_breadth", "cosine_dissimilarity",
    "objective", "objective_regret",
    "mean_norm_util", "min_norm_util", "median_norm_util",
    "frac_worse_than_equal", "frac_worse_than_strongest_sep",
    "worst_indiv_change_vs_equal", "p5_indiv_change_vs_equal",
    "capacity_utilization", "frac_cells_at_lower", "frac_cells_interior",
    "frac_cells_at_upper", "solve_time_s",
]


def solve_joint(instance, cfgs):
    t0 = time.perf_counter()
    A, res = rules.joint_nash(instance, cfgs)
    dt = time.perf_counter() - t0
    return A, res, dt


def tune_global_gamma(cells, instance_factory):
    """Pick one gamma minimizing mean objective regret over pooled train seeds.

    instance_factory(cell, seed) -> (instance, utility_configs, meta).
    """
    sums = {g: 0.0 for g in rules.GAMMA_GRID}
    count = 0
    for cell in cells:
        for seed in cell["train_seeds"]:
            inst, cfgs, meta = instance_factory(cell, seed)
            A_joint, res, _ = solve_joint(inst, cfgs)
            if A_joint is None:
                continue
            joint_obj = metrics.weighted_log_objective(inst, cfgs, A_joint)
            if not np.isfinite(joint_obj):
                continue
            for g in rules.GAMMA_GRID:
                A = rules.waterfill_gamma(inst, g)
                sums[g] += joint_obj - metrics.weighted_log_objective(inst, cfgs, A)
            count += 1
    mean_regret = {g: (sums[g] / count if count else float("inf")) for g in rules.GAMMA_GRID}
    best = min(mean_regret, key=mean_regret.get)
    return best, mean_regret, count


def evaluate(name, cells, instance_factory, tuned_gamma, out_csv):
    os.makedirs(RAW, exist_ok=True)
    path = os.path.join(RAW, out_csv)
    rows = []
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=RAW_FIELDS)
        writer.writeheader()
        for cell in cells:
            for seed in cell["test_seeds"]:
                inst, cfgs, meta = instance_factory(cell, seed)
                A_joint, res, dt = solve_joint(inst, cfgs)
                if A_joint is None:
                    continue
                joint_obj = metrics.weighted_log_objective(inst, cfgs, A_joint)
                if not np.isfinite(joint_obj):
                    continue

                equal_A = rules.equal_shares(inst)
                equal_norm = metrics.normalized_utilities(inst, cfgs, equal_A)
                tuned_A = rules.waterfill_gamma(inst, tuned_gamma)
                strongest_norm = metrics.normalized_utilities(inst, cfgs, tuned_A)

                gamma_allocs = {g: rules.waterfill_gamma(inst, g) for g in rules.GAMMA_GRID}
                gamma_obj = {g: metrics.weighted_log_objective(inst, cfgs, gamma_allocs[g])
                             for g in rules.GAMMA_GRID}
                best_g = max(gamma_obj, key=gamma_obj.get)

                emit = {
                    "joint": (A_joint, None, dt),
                    "equal": (equal_A, None, 0.0),
                    "proportional_gamma1": (gamma_allocs[1.0], 1.0, 0.0),
                    f"tuned_gamma{tuned_gamma}": (tuned_A, tuned_gamma, 0.0),
                    "oracle_envelope": (gamma_allocs[best_g], best_g, 0.0),
                }
                for rule_name, (A, gamma, solve_t) in emit.items():
                    m = metrics.rule_metrics(inst, cfgs, A, equal_norm, strongest_norm)
                    row = {
                        "experiment": name, "cell": cell["label"], "seed": seed,
                        "rule": rule_name, "gamma": gamma,
                        "achieved_breadth": meta.get("achieved_breadth"),
                        "cosine_dissimilarity": meta.get("cosine_dissimilarity"),
                        "objective_regret": joint_obj - m["objective"],
                        "solve_time_s": solve_t,
                    }
                    row.update({k: m[k] for k in (
                        "objective", "mean_norm_util", "min_norm_util", "median_norm_util",
                        "frac_worse_than_equal", "frac_worse_than_strongest_sep",
                        "worst_indiv_change_vs_equal", "p5_indiv_change_vs_equal",
                        "capacity_utilization", "frac_cells_at_lower",
                        "frac_cells_interior", "frac_cells_at_upper")})
                    writer.writerow(row)
                    rows.append(row)
    return path, rows
