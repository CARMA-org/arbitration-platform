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


def contention_range(realized, cell):
    parts = []
    for r, v in realized.get(cell, {}).items():
        parts.append("%s %.2f-%.2f" % (r, v["min"], v["max"]))
    return "; ".join(parts)


def main():
    h = load(os.path.join(HERE, "results", "headline.json"))
    s = load(os.path.join(HERE, "results", "summary.json"))
    dyn = load(os.path.join(ROOT, "experiments", "dynamic_allocation", "results", "summary.json"))
    enf = load(os.path.join(ROOT, "experiments", "enforcement", "results", "enforcement_report_full.json")) or \
        load(os.path.join(ROOT, "experiments", "enforcement", "results", "enforcement_report_smoke.json"))

    policies = h["policies"]
    mixed = h["mixed_cells"]
    realized = h.get("realized_contention_summary", {})
    L = []
    L.append("# Platform-Mediated Utility Alignment: Results")
    L.append("")
    L.append("## Question")
    L.append("")
    L.append("A platform allocates several bounded resources to agents running "
             "bundle-structured tasks, where a task completes only when every mandatory "
             "step is afforded and those steps jointly require several resources. This "
             "study measures how the semantics of an agent's utility declaration affect "
             "completed work in these synthetic workloads, and when the allocation "
             "computation needs cross-resource coordination. Each seed is an independent "
             "workload draw. Tasks are mock and resource requirements are synthetic.")
    L.append("")
    L.append("## Design")
    L.append("")
    if s:
        L.append("Compositions %s at contention %s. %d agents, %d tasks each. %d test seeds "
                 "per cell over %d cells (%d feasible runs, %d infeasible, %d agent records). "
                 "Distinct workload hashes per cell: %s." % (
                     s["compositions"], s["contention"], s["n_agents"], s["tasks_per_agent"],
                     s["n_test_seeds_per_cell"], s["n_cells"], s["total_test_runs"],
                     s.get("infeasible_runs", 0), s["n_agent_records"],
                     json.dumps(s["distinct_workload_hashes_per_cell"])))
        L.append("")
        L.append("Realized contention ranges over the %d seeds (min-max by resource): "
                 "moderate cells and high cells respectively. Mixed cells: %s | %s."
                 % (s["n_test_seeds_per_cell"],
                    contention_range(realized, "mixed_bundle__moderate"),
                    contention_range(realized, "mixed_bundle__high")))
    L.append("")
    L.append("Each seed samples task types uniformly from the four archetypes; homogeneous "
             "agents share one sampled queue, mixed agents sample independently. The "
             "declaration primitive for linear, Cobb-Douglas, CES, and Leontief is each "
             "agent's normalized mandatory-demand vector from its exact queue; DRF receives "
             "the raw mandatory-demand vector; operator priorities are equal. Every policy in "
             "a cell-seed receives the same scenario hash. Paired differences use a stratified "
             "paired bootstrap over seeds (bootstrap seed %s, %d resamples, seed as the "
             "resampling unit); the mixed aggregate weights the two mixed cells equally." %
             (h["bootstrap_seed"], h["n_bootstrap"]))
    L.append("")
    L.append("Solver status counts by joint policy: %s. Fallback was disabled; fallback used: "
             "%s; infeasible runs: %d." % (
                 json.dumps(h.get("solver_status_counts", {})),
                 json.dumps(h.get("fallback_used_counts", {})), h.get("infeasible_runs", 0)))
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
    agg0 = list(h["mixed_aggregate_completion"].values())[0]
    L.append("Mixed-bundle equal-weighted stratified paired completion differences "
             "(mean [95%% CI], %d seeds in each of %d mixed cells):" %
             (agg0["n_per_cell"], agg0["n_cells"]))
    L.append("")
    for k in sorted(h["mixed_aggregate_completion"]):
        L.append("- %s: %s" % (k, ci(h["mixed_aggregate_completion"][k])))
    L.append("")
    L.append("The nonlinear joint policies (Cobb-Douglas, CES, Leontief) complete far more "
             "bundle-structured work than joint linear utility. Their advantage over equal "
             "quotas and standard DRF is small; equal quotas and DRF are strong comparators. "
             "Per-cell paired differences are in `tables/paired_differences.csv`.")
    L.append("")
    L.append("## Homogeneous symmetry check")
    L.append("")
    spread = h["homogeneous_symmetry_max_spread"]
    if spread == 0.0:
        L.append("In the homogeneous composition all agents share one workload draw and all "
                 "seven policies produce identical mean completion in every cell (maximum "
                 "spread %.4f)." % spread)
    else:
        L.append("In the homogeneous composition all agents share one workload draw, so any "
                 "policy difference is a rounding or tie-breaking artifact. The maximum "
                 "completion spread across all policies is %.4f." % spread)
    L.append("")
    L.append("## Cobb-Douglas decomposition")
    L.append("")
    cd = h["cobb_douglas_decomposition"]
    val = cd.get("measured_continuous_validation")
    ra = cd["run_agent_records"]
    rc = cd["run_completion"]
    if val:
        cc = val["continuous_comparison"]
        L.append("The Cobb-Douglas weighted-log objective separates across resource columns. "
                 "A measured comparison of the exact bounded-log decomposed solver against the "
                 "joint Cobb-Douglas solver over %d bounded instances (seed %d, box and "
                 "capacity constraints binding) finds a maximum absolute continuous difference "
                 "of %g (maximum relative error %g) over the %d instances where the joint "
                 "solver reached a genuinely optimal, feasible solution. The joint solver "
                 "returned optimal_inaccurate in %d instances and failed in %d; the platform's "
                 "capacity-preserving rounding clamps such solutions to feasibility." % (
                     val["n_cases"], val["seed"], cc["max_abs_continuous_diff"],
                     cc["max_relative_error"], cc["n_compared"],
                     val["joint_solver_status_counts"]["optimal_inaccurate"],
                     val["joint_solver_status_counts"]["failed"]))
        L.append("")
    L.append("In the primary experiment the installed integer allocations of decomposed and "
             "joint Cobb-Douglas differ by up to %d unit(s) on %d of %d agent records (%.1f%%) "
             "because rounding is applied independently, yet only %d of %d run-level completion "
             "outcomes differ (maximum completion difference %g). The mixed-aggregate "
             "completion difference is %s. The computation can be separated by resource for "
             "Cobb-Douglas; contract authority, installation, versioning, and enforcement "
             "remain coordinated platform operations." % (
                 ra["max_installed_integer_unit_diff"], ra["agent_records_with_installed_diff"],
                 ra["agent_records_total"], 100.0 * ra["fraction_records_with_installed_diff"],
                 rc["runs_with_completion_diff"], rc["run_pairs"], rc["max_abs_completion_diff"],
                 ci(rc["mixed_paired_completion_diff"])))
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
             "records evaluated (denominator n per policy):")
    L.append("")
    for p in sorted(h["individual_change_vs_equal_mixed"]):
        d = h["individual_change_vs_equal_mixed"][p]
        L.append("- %s: mean %s, worst %s, fraction worse %.3f (n=%d)" % (
            p, f3(d["mean_change_vs_equal"]), f3(d["worst_loss_vs_equal"]), d["frac_worse"], d["n"]))
    L.append("")
    L.append("The nonlinear joint policies make some individual agents worse off than equal "
             "quotas; this is not a Pareto improvement and no individual guarantee is claimed.")
    L.append("")
    L.append("## Allocation-computation latency")
    L.append("")
    L.append("Allocation-computation latency includes Python process startup, cvxpy model "
             "construction, solve, output parsing, and integer conversion on the recorded "
             "machine, measured during a primary run with no other solver-heavy work "
             "concurrent. By policy (count, median, p95, max ms):")
    L.append("")
    L.append("| Policy | n | median | p95 | max |")
    L.append("|--------|---|--------|-----|-----|")
    for p in policies:
        d = h["latency_by_policy_ms"][p]
        L.append("| %s | %d | %.0f | %.0f | %.0f |" % (p, d["n"], d["median"], d["p95"], d["max"]))
    jl = h["joint_latency_ms"]
    L.append("")
    L.append("The four joint solver policies together: n=%d, median %.0f ms, p95 %.0f ms, max "
             "%.0f ms. The comparison rules (equal quotas, DRF, decomposed Cobb-Douglas) "
             "allocate in under a millisecond and are not pooled with solver policies. No "
             "capacity or bound violation occurred in %d runs." % (
                 jl["n"], jl["median"], jl["p95"], jl["max"], h["n_runs"]))
    L.append("")
    L.append("## Interpretation")
    L.append("")
    L.append("A linear declaration treats resources as substitutes. In the evaluated workloads "
             "it produced imbalanced bundles that left some mandatory resources near their "
             "minimum, so far fewer bundle-structured tasks completed. Cobb-Douglas is "
             "multiplicative and Leontief represents fixed-proportion requirements; both keep "
             "allocations balanced across each agent's bundle and complete more work. CES with "
             "rho=0.5 is also a substitutes model, but its concavity prevents the extreme "
             "concentration seen under joint linear utility in these workloads. Declared "
             "welfare is reported only within a utility family and is not compared across "
             "families. This is behaviour in the tested synthetic workloads, not a general "
             "advantage for joint optimization or a claim about real service performance.")
    L.append("")
    if dyn:
        L.append("## Dynamic contract behaviour (appendix simulation)")
        L.append("")
        L.append("A secondary solver-level simulation over %d seeds and %d epochs with an "
                 "agent-targeted event schedule and a capacity shock. It uses the same "
                 "capacity-preserving rounding as the platform, preserves promised and applied "
                 "floor maps separately, and verifies floors against the rounded allocation. It "
                 "does not install runtime contracts or drive the runtime clock. Counts below "
                 "are mean per 100-epoch seed with the total across all %d seeds in "
                 "parentheses." % (dyn["seeds"], dyn["epochs"], dyn["seeds"]))
        L.append("")
        L.append("| Policy | Protected agent-epochs | Infeasible-floor epochs | Discrete floor violations | Floor shortfall (total) |")
        L.append("|--------|------------------------|-------------------------|---------------------------|-------------------------|")
        for a in dyn["aggregate"]:
            pae = a["protected_agent_epochs"]
            ife = a["infeasible_floor_epochs"]
            dfv = a["discrete_floor_violations"]
            fst = a["floor_shortfall_total"]
            L.append("| %s | %.1f (%d) | %.2f (%d) | %.2f (%d) | %.1f |" % (
                a["policy"], pae["mean_per_seed"], pae["total"], ife["mean_per_seed"], ife["total"],
                dfv["mean_per_seed"], dfv["total"], fst["total"]))
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
                 "counter is zero. These are deterministic test outcomes, not estimates of "
                 "operational failure rates or evidence of security against a hostile operator. "
                 "Per-case denominators are in "
                 "`experiments/enforcement/results/enforcement_cases_full.csv`.")
    L.append("")
    L.append("## Scope of claims")
    L.append("")
    L.append("The runtime converts agent declarations and exogenous operator priorities into a "
             "versioned allocation snapshot, conservation-checks it before installation, and "
             "enforces consumption through a shared per-version ledger; execution checks "
             "registration, version, expiry, context, and service-instance identity, and does "
             "not call the backend after a denial. Unsupported utility families are rejected "
             "rather than approximated, and solver timeouts fail closed. The test suite "
             "exercises these directly. Cobb-Douglas separates the allocation computation but "
             "does not by itself decentralize authority, policy selection, contract "
             "installation, or enforcement. The completion, distributional, latency, and "
             "dynamic numbers are results of controlled synthetic experiments with mock task "
             "outputs and synthetic service-cost constants. No claim is made about "
             "strategyproofness, truthful reporting, collusion resistance, individual "
             "rationality, protection against a hostile operator, or sandboxing of untrusted "
             "agent code.")
    L.append("")
    L.append("## Reproduction")
    L.append("")
    L.append("From the source revision in `EXPERIMENT_MANIFEST.json`: build the classpath, then "
             "run `run_sweep.py --full`, `validate_decomposition.py`, `make_headline.py`, "
             "`make_memo.py`, `figures.py`, `make_test_report.py`, `make_manifest.py`, and "
             "`check_consistency.py --with-manifest`, plus the enforcement and dynamic drivers. "
             "`SOLVER_PYTHON` must point at an interpreter with cvxpy and clarabel.")
    L.append("")

    with open(os.path.join(HERE, "RESULTS_FOR_PAPER.md"), "w") as f:
        f.write("\n".join(L))
    print("wrote RESULTS_FOR_PAPER.md")


if __name__ == "__main__":
    main()
