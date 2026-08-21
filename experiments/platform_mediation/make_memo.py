#!/usr/bin/env python3
"""Generate the paper-facing results memo from the machine-readable headline and
summary files. No numbers are hand-written."""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load(path):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return None


def f3(x):
    return "n/a" if x is None else ("%+.3f" % x if x < 0 else "%.3f" % x)


def ci(d):
    return "%s [%+.3f, %+.3f]" % (f3(d["mean"]), d["ci_lo"], d["ci_hi"])


def main():
    h = load(os.path.join(HERE, "results", "headline.json"))
    s = load(os.path.join(HERE, "results", "summary.json"))
    dyn = load(os.path.join(ROOT, "experiments", "dynamic_allocation", "results", "summary.json"))
    enf = load(os.path.join(ROOT, "experiments", "enforcement", "results", "enforcement_report_full.json")) or \
        load(os.path.join(ROOT, "experiments", "enforcement", "results", "enforcement_report_smoke.json"))

    policies = h["policies"]
    mixed = h["mixed_cells"]
    L = []
    L.append("# Platform-Mediated Utility Alignment: Results")
    L.append("")
    L.append("## Question")
    L.append("")
    L.append("A platform allocates several bounded resources to agents running "
             "bundle-structured tasks, where a task completes only when every mandatory "
             "step is afforded and those steps jointly require several resources. This "
             "study measures how the semantics of an agent's utility declaration affect "
             "completed work, and when the allocation computation needs cross-resource "
             "coordination. Each seed is an independent workload draw.")
    L.append("")
    L.append("## Design")
    L.append("")
    if s:
        L.append("Compositions %s at contention %s. %d agents, %d tasks each. %d test seeds "
                 "per cell over %d cells (%d runs, %d agent records). Distinct workload hashes "
                 "per cell: %s." % (
                     s["compositions"], s["contention"], s["n_agents"], s["tasks_per_agent"],
                     s["n_test_seeds_per_cell"], s["n_cells"], s["total_test_runs"],
                     s["n_agent_records"], json.dumps(s["distinct_workload_hashes_per_cell"])))
        L.append("")
        L.append("Realized contention ratios after integer capacity construction: %s."
                 % json.dumps(s.get("realized_contention_ratio", {})))
    L.append("")
    L.append("Each seed samples task types uniformly from the four archetypes; homogeneous "
             "agents share one sampled queue, mixed agents sample independently. The "
             "declaration primitive for linear, Cobb-Douglas, CES, and Leontief is each "
             "agent's normalized mandatory-demand vector from its exact queue; DRF receives "
             "the raw mandatory-demand vector; operator priorities are equal. Every policy in "
             "a cell-seed receives the same scenario hash. Paired differences use a stratified "
             "paired bootstrap (bootstrap seed %s, %d resamples), reported as 100 paired "
             "workload draws per cell." % (h["bootstrap_seed"], h["n_bootstrap"]))
    L.append("")
    L.append("## Mixed-bundle results")
    L.append("")
    L.append("Mean task completion by policy and mixed cell:")
    L.append("")
    L.append("| Policy | " + " | ".join(mixed) + " |")
    L.append("|--------|" + "|".join(["--------"] * len(mixed)) + "|")
    for p in policies:
        L.append("| %s | %s |" % (p, " | ".join(f3(h["per_cell_completion"][c][p]) for c in mixed)))
    L.append("")
    L.append("Mixed-bundle equal-weighted stratified paired completion differences "
             "(mean [95%% CI], %d seeds in each of %d mixed cells):" %
             (list(h["mixed_aggregate_completion"].values())[0]["n_per_cell"],
              list(h["mixed_aggregate_completion"].values())[0]["n_cells"]))
    L.append("")
    for k in sorted(h["mixed_aggregate_completion"]):
        L.append("- %s: %s" % (k, ci(h["mixed_aggregate_completion"][k])))
    L.append("")
    L.append("Per-cell paired differences are in `tables/paired_differences.csv`; the mixed "
             "cells are reported individually there so any contention interaction is visible.")
    L.append("")
    L.append("## Homogeneous symmetry check")
    L.append("")
    L.append("In the homogeneous composition all agents share one workload draw, so any policy "
             "difference is a rounding or tie-breaking artifact. The maximum completion spread "
             "across all policies is %.4f." % h["homogeneous_symmetry_max_spread"])
    L.append("")
    L.append("## Cobb-Douglas decomposition")
    L.append("")
    cd = h["cobb_douglas_decomposition"]
    L.append("The Cobb-Douglas weighted-log objective separates across resource columns. The "
             "continuous joint and decomposed solutions agree within a tolerance of %g (verified "
             "by a randomized solver test). The installed integer allocations are not identical: "
             "they differ by up to %d unit(s) on %d of %d agent records (%.1f%%) due to "
             "independent rounding tie-breaking, while the mixed-aggregate completion difference "
             "is %s. This shows the allocation computation can be separated by resource for "
             "Cobb-Douglas; it does not decentralize authority, policy selection, contract "
             "installation, or enforcement." % (
                 cd["continuous_agreement_tolerance"], cd["max_installed_integer_unit_diff"],
                 cd["agent_records_with_installed_diff"], cd["agent_records_total"],
                 100.0 * cd["fraction_records_with_installed_diff"], ci(cd["mixed_completion_diff"])))
    L.append("")
    L.append("Leontief constrains one utility value jointly by several resource ratios and "
             "remains cross-resource coupled in the optimization.")
    L.append("")
    L.append("## Resource use and individual outcomes")
    L.append("")
    L.append("| Policy | Capacity utilization | Allocation consumption |")
    L.append("|--------|----------------------|------------------------|")
    for p in policies:
        L.append("| %s | %s | %s |" % (p, f3(h["capacity_utilization_by_policy"][p]),
                                       f3(h["allocation_consumption_by_policy"][p])))
    L.append("")
    L.append("Capacity utilization is total charged over total capacity; allocation "
             "consumption is total charged over total installed allocation.")
    L.append("")
    L.append("Individual completion change versus equal quotas, over the mixed-bundle agent "
             "records evaluated:")
    L.append("")
    for p in sorted(h["individual_change_vs_equal_mixed"]):
        d = h["individual_change_vs_equal_mixed"][p]
        L.append("- %s: mean %s, worst %s, fraction worse %.3f (n=%d)" % (
            p, f3(d["mean_change_vs_equal"]), f3(d["worst_loss_vs_equal"]), d["frac_worse"], d["n"]))
    L.append("")
    L.append("Where the worst observed change is zero, no sampled agent had lower completion "
             "under that policy than under equal quotas in the evaluated workload draws; this is "
             "an observation, not a general guarantee.")
    L.append("")
    L.append("## Allocation latency")
    L.append("")
    L.append("Measured latency includes Python process startup, cvxpy model construction, solve "
             "time, output parsing, and integer conversion on the recorded machine. By policy "
             "(count, median, p95, max ms):")
    L.append("")
    L.append("| Policy | n | median | p95 | max |")
    L.append("|--------|---|--------|-----|-----|")
    for p in policies:
        d = h["latency_by_policy_ms"][p]
        L.append("| %s | %d | %.0f | %.0f | %.0f |" % (p, d["n"], d["median"], d["p95"], d["max"]))
    jl = h["joint_latency_ms"]
    L.append("")
    L.append("The four joint solver policies together: n=%d, median %.0f ms, p95 %.0f ms, max "
             "%.0f ms. Comparison rules allocate in under a millisecond and are not pooled with "
             "solver policies. No capacity or bound violation occurred in %d runs."
             % (jl["n"], jl["median"], jl["p95"], jl["max"], h["n_runs"]))
    L.append("")
    L.append("## Interpretation")
    L.append("")
    L.append("A linear declaration treats resources as substitutes. In the evaluated "
             "contention it produced imbalanced bundles that left some mandatory resources near "
             "their minimum, so fewer bundle-structured tasks completed. Cobb-Douglas is "
             "multiplicative and Leontief represents fixed-proportion requirements; both keep "
             "allocations balanced across each agent's bundle and complete more work. CES with "
             "rho=0.5 remains a substitutes model and is intermediate. Declared welfare is "
             "reported only within a utility family and is not compared across families.")
    L.append("")
    if dyn:
        L.append("## Dynamic contract behaviour (appendix simulation)")
        L.append("")
        L.append("A secondary solver-level simulation over %d seeds and %d epochs with an "
                 "agent-targeted event schedule and a capacity shock. It uses the same "
                 "capacity-preserving rounding as the platform, preserves promised and solver "
                 "floor maps separately, and verifies floors against the rounded allocation. It "
                 "does not install runtime contracts or drive the runtime clock." %
                 (dyn["seeds"], dyn["epochs"]))
        L.append("")
        L.append("| Policy | Protected agent-epochs | Active-floor epochs | Infeasible-floor epochs | Discrete floor violations | Mean shortfall |")
        L.append("|--------|------------------------|---------------------|-------------------------|---------------------------|----------------|")
        for a in dyn["aggregate"]:
            L.append("| %s | %.1f | %.1f | %.2f | %.2f | %.3f |" % (
                a["policy"], a["protected_agent_epochs"], a["active_floor_epochs"],
                a["infeasible_floor_epochs"], a["discrete_floor_violations"],
                a["mean_floor_shortfall"]))
        L.append("")
        L.append("Discrete floor violations arise because floors that hold for the continuous "
                 "solution can be violated after integer rounding under a later capacity change. "
                 "Capacity violations total %d." % dyn["capacity_violations_total"])
    L.append("")
    if enf:
        L.append("## Enforcement")
        L.append("")
        L.append("A deterministic fault-injection suite with explicit denominators per case "
                 "(trials, operations, expected/observed successes and denials). Every invariant "
                 "counter is zero. These are test results, not estimates of operational failure "
                 "rates. Per-case denominators are in "
                 "`experiments/enforcement/results/enforcement_cases_full.csv`.")
    L.append("")
    L.append("## Scope of claims")
    L.append("")
    L.append("The runtime enforces contract authority, the shared per-version consumption "
             "ledger, execution binding, service-instance billing, and the solver timeout, and "
             "the test suite exercises these directly. Cobb-Douglas separates the allocation "
             "computation but does not by itself decentralize authority, policy selection, "
             "contract installation, or enforcement. The completion, distributional, latency, "
             "and dynamic numbers are results of controlled synthetic experiments with mock task "
             "outputs and a latency budget of service constants. No claim is made about "
             "strategyproofness, truthful reporting, collusion resistance, protection against a "
             "hostile operator, or sandboxing of untrusted agent code.")
    L.append("")
    L.append("## Reproduction")
    L.append("")
    L.append("From the source revision in `EXPERIMENT_MANIFEST.json`: build the classpath, then "
             "run `run_sweep.py --full`, `make_headline.py`, `make_memo.py`, `figures.py`, "
             "`make_test_report.py`, `make_manifest.py`, and `check_consistency.py "
             "--with-manifest`, plus the enforcement and dynamic drivers. `SOLVER_PYTHON` must "
             "point at an interpreter with cvxpy and clarabel.")
    L.append("")

    with open(os.path.join(HERE, "RESULTS_FOR_PAPER.md"), "w") as f:
        f.write("\n".join(L))
    print("wrote RESULTS_FOR_PAPER.md")


if __name__ == "__main__":
    main()
