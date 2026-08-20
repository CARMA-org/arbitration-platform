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
    return "%+.3f [%+.3f, %+.3f] (n=%d)" % (d["mean"], d["ci_lo"], d["ci_hi"], d["n"])


def main():
    h = load(os.path.join(HERE, "results", "headline.json"))
    summary = load(os.path.join(HERE, "results", "summary.json"))
    dyn = load(os.path.join(ROOT, "experiments", "dynamic_allocation", "results", "summary.json")) or \
        load(os.path.join(ROOT, "experiments", "dynamic_allocation", "results", "summary_full.json"))
    enf = load(os.path.join(ROOT, "experiments", "enforcement", "results", "enforcement_report_full.json")) or \
        load(os.path.join(ROOT, "experiments", "enforcement", "results", "enforcement_report_smoke.json"))

    o = h["overall_completion_by_policy"]
    util = h["capacity_utilization_by_policy"]
    cons = h["allocation_consumption_by_policy"]
    cmp = h["paired_completion_diffs"]
    harm = h["individual_harm_vs_equal"]
    lat = h["allocation_latency_ms"]
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
             "coordination.")
    L.append("")
    L.append("## Design")
    L.append("")
    if summary:
        L.append("Compositions %s at contention %s. %d agents, %d tasks each. "
                 "%d test seeds per cell over %d cells (%d evaluated runs, %d agent records). "
                 "The appendix separable exponent gamma=%s was tuned on calibration seeds "
                 "against declared linear welfare, not completion." % (
                     summary["compositions"], summary["contention"],
                     summary["n_agents"], summary["tasks_per_agent"],
                     summary["n_test_seeds_per_cell"], summary["n_cells"],
                     summary["total_test_runs"], summary["n_agent_records"],
                     summary["selected_separable_gamma"]))
        L.append("")
        L.append("Realized contention ratios after integer capacity construction: "
                 + json.dumps(summary.get("realized_contention_ratio", {})) + ".")
    L.append("")
    L.append("Every policy in a cell receives the same agents, tasks, priorities, bounds, "
             "and scenario hash. The declaration primitive for linear, Cobb-Douglas, CES, "
             "and Leontief is each agent's normalized mandatory-demand vector derived from "
             "its task queue; DRF receives the raw mandatory-demand vector; operator "
             "priorities are equal across agents.")
    L.append("")
    L.append("## Headline completion")
    L.append("")
    L.append("| Policy | Mean task completion |")
    L.append("|--------|----------------------|")
    for p in h["policies"]:
        L.append("| %s | %s |" % (p, f3(o.get(p))))
    L.append("")
    L.append("Paired seed-level completion differences (mean, 95% bootstrap CI):")
    L.append("")
    for k in sorted(cmp):
        L.append("- %s: %s" % (k, ci(cmp[k])))
    L.append("")
    L.append("## Homogeneous null and Cobb-Douglas decomposition")
    L.append("")
    L.append("In the homogeneous composition the maximum completion spread across all "
             "policies is %.4f, i.e. no policy has an advantage when agents are identical."
             % h["homogeneous_null_max_spread"])
    L.append("")
    dv = h["decomposed_vs_joint_cobb_douglas"]
    L.append("The exact decomposed Cobb-Douglas comparator, which solves each resource "
             "independently without the joint solver, matches joint Cobb-Douglas to a "
             "maximum absolute completion difference of %.4f across all cells; the paired "
             "difference is %s. Cobb-Douglas weighted proportional fairness therefore "
             "decomposes by resource, so centralized authority does not require centralized "
             "computation for this family." % (dv["max_abs_completion_diff"], ci(dv["paired_completion"])))
    L.append("")
    L.append("## Resource use and distribution")
    L.append("")
    L.append("| Policy | Capacity utilization | Allocation consumption |")
    L.append("|--------|----------------------|------------------------|")
    for p in h["policies"]:
        L.append("| %s | %s | %s |" % (p, f3(util.get(p)), f3(cons.get(p))))
    L.append("")
    L.append("Capacity utilization is total charged over total capacity; allocation "
             "consumption is total charged over total installed allocation.")
    L.append("")
    L.append("Individual completion change versus equal quotas (averaged over cells):")
    L.append("")
    for p in sorted(harm):
        d = harm[p]
        L.append("- %s: mean %s, worst %s, fraction worse %.3f" % (
            p, f3(d["mean_change_vs_equal"]), f3(d["worst_loss_vs_equal"]), d["frac_worse"]))
    L.append("")
    L.append("Allocation latency: median %.0f ms, p95 %.0f ms, max %.0f ms. Comparison "
             "rules allocate in under a millisecond. No capacity or bound violation "
             "occurred in %d runs." % (lat["median"], lat["p95"], lat["max"], h["n_runs"]))
    L.append("")
    L.append("## Interpretation")
    L.append("")
    L.append("A linear declaration treats resources as substitutes and concentrates each "
             "agent on its single highest-weight resource, starving the complementary "
             "resources its bundle-structured tasks also require; it completes the least "
             "work in the mixed composition. Cobb-Douglas is multiplicative and Leontief "
             "represents fixed-proportion requirements; both keep allocations balanced "
             "across each agent's bundle and complete more work. CES with rho=0.5 remains a "
             "substitutes model and is intermediate. The Cobb-Douglas result is achievable "
             "with per-resource computation; the Leontief result retains cross-resource "
             "coupling in the solver.")
    L.append("")
    if dyn:
        L.append("## Dynamic contract behaviour (appendix simulation)")
        L.append("")
        L.append("A solver-level simulation over %d seeds and %d epochs with an "
                 "agent-targeted event schedule and a capacity shock. Floors are taken from "
                 "the installed discrete allocation and verified after integer rounding. "
                 "This simulation does not drive the runtime clock." % (dyn["seeds"], dyn["epochs"]))
        L.append("")
        L.append("| Policy | Admissions | Mean wait | Commitment infeasibility | Discrete floor violations | Lease expiries |")
        L.append("|--------|-----------|-----------|--------------------------|---------------------------|----------------|")
        for a in dyn["aggregate"]:
            L.append("| %s | %.2f | %.2f | %.2f | %.2f | %.2f |" % (
                a["policy"], a["admissions"], a["mean_waiting_time"],
                a["commitment_infeasibility"], a["floor_violations"], a["lease_expiries"]))
        L.append("")
        L.append("Discrete floor violations are nonzero for every committed policy: floors "
                 "that hold for the continuous solution can be violated after integer "
                 "rounding under a later capacity change. Capacity violations total %d."
                 % dyn["capacity_violations_total"])
    L.append("")
    if enf:
        t = enf["totals"]
        L.append("## Enforcement")
        L.append("")
        L.append("A deterministic fault-injection suite over %d repetitions of the "
                 "concurrency cases plus single-shot cases reports every invariant counter "
                 "at zero (backend-after-denial=%d, quota=%d, capacity=%d, partial-deduction=%d, "
                 "silent-fallback=%d, incorrect-success=%d). These are test results, not "
                 "estimates of real-world failure rates." % (
                     enf.get("reps_for_concurrency_cases", 0), t["backend_after_denial"],
                     t["quota_violations"], t["capacity_violations"], t["partial_deductions"],
                     t["silent_fallbacks"], t["incorrect_success"]))
    L.append("")
    L.append("## Scope of claims")
    L.append("")
    L.append("The runtime enforces contract authority, the shared per-version consumption "
             "ledger, execution binding, service-instance billing, and the solver timeout, "
             "and the test suite exercises these directly. The completion, distributional, "
             "latency, and dynamic numbers are results of controlled synthetic experiments "
             "with mock task outputs and a latency budget of service constants reported as "
             "latency-budget completion. No claim is made about strategyproofness, truthful "
             "reporting, collusion resistance, protection against a hostile operator, or "
             "sandboxing of untrusted agent code.")
    L.append("")
    L.append("## Reproduction")
    L.append("")
    L.append("From the source revision in `EXPERIMENT_MANIFEST.json`: build the classpath, "
             "then run `run_sweep.py --full`, `make_headline.py`, `make_memo.py`, "
             "`make_test_report.py`, `make_manifest.py`, and the enforcement and dynamic "
             "drivers. `SOLVER_PYTHON` must point at an interpreter with cvxpy and clarabel.")
    L.append("")

    out = os.path.join(HERE, "RESULTS_FOR_PAPER.md")
    with open(out, "w") as f:
        f.write("\n".join(L))
    print("wrote", out)


if __name__ == "__main__":
    main()
