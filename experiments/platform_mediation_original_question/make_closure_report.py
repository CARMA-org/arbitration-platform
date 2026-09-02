#!/usr/bin/env python3
"""Generate ORIGINAL_QUESTION_CLOSURE.md from the committed headlines, carrier decision,
comparator audit and distributed validation. Data-driven so the report is always
consistent with the raw results and states only the safe claims the data support.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ARCH = os.path.join(HERE, "results", "architecture_v1")
DRIFT = os.path.join(HERE, "results", "drift_v1")
GH = "https://github.com/CARMA-org/arbitration-platform"


def j(p):
    with open(p) as f:
        return json.load(f)


def fmt(st):
    return "%+.3f tasks/run (95%% CI [%+.3f, %+.3f])" % (st["mean_tasks"], st["ci_lo_tasks"], st["ci_hi_tasks"])


def main(prereg_commit="", arch_commit="", drift_commit="", verify_commit=""):
    head = j(os.path.join(ARCH, "architecture_headline.json"))
    dec = j(os.path.join(HERE, "DRIFT_CARRIER_DECISION.json"))
    audit = j(os.path.join(HERE, "comparator_audit.json"))
    val = j(os.path.join(HERE, "distributed_validation.json"))
    cells = head["co_primary_cells"]
    pq = head["paired_qo"]
    de = head["distributed_equivalence"]
    flags = head["flags"]
    drift = j(os.path.join(DRIFT, "drift_headline.json")) if os.path.exists(os.path.join(DRIFT, "drift_headline.json")) else None

    L = []
    A = L.append
    A("# Original ARB empirical question: closure at the tested scope\n")
    A("## 1. The original empirical question\n")
    A("Can platform-mediated allocation improve aggregate task completion when agents require heterogeneous,")
    A("complementary bundles of several constrained resources? This report closes that question at the tested")
    A("scope using two preregistered experiments and one preregistered adaptive decision rule.\n")

    A("## 2. The verified prior result\n")
    A("The prior heterogeneity experiment (experimental commit `073a5d6`, independently verified at `473d707`)")
    A("found, under exact pending-queue declarations with six agents, eight tasks, four resources and Dirichlet(0.1)")
    A("workloads, that joint Leontief minus DRF was +2.595 tasks per 48-task run (95% CI [2.295, 2.890]) at moderate")
    A("contention and +1.770 ([1.505, 2.045]) at high contention, both passing a frozen five-condition rule, with a")
    A("mean gain over equal quotas, about 4.9% and 7.0% of agents worse than equal quotas (not a Pareto")
    A("improvement), and a generally-increasing-but-not-strictly-monotone heterogeneity response.\n")

    A("## 3. Comparator audit\n")
    r = audit["randomized"]
    A("Four concepts were distinguished (COMPARATOR_AUDIT.md): equal quotas, DRF (dominant-resource-share coupling),")
    A("independent bundle max-min (resource-local weighted progressive filling of bundle progress, keeping the")
    A("declared complementarity coefficient), and the separable weighted-log Leontief relaxation. Over %d randomized"
      % r["n_scenarios"])
    A("development scenarios the independent bundle max-min differed from DRF in %d of %d (normalized L1 mean %.4f,"
      % (r["maxmin_differs_from_drf_count"], r["n_scenarios"], r["alloc_l1_norm_mean"]))
    A("L-infinity up to %d units), and the separable relaxation equalled equal quotas in %d of %d."
      % (r["alloc_linf_max"], r["relaxation_equals_equal_count"], r["n_scenarios"]))
    A("The independent bundle max-min is therefore the strongest tested uncoordinated resource-local comparator.\n")

    A("## 4. Why the separable relaxation collapses to equal quotas\n")
    A("Dropping the cross-resource utility consensus leaves each resource owner maximizing sum_i w_i log(x_ir).")
    A("Substituting u_ir = x_ir / a_ir gives sum_i w_i log(x_ir) minus a constant, so the coefficient magnitude a_ir")
    A("cancels and, under equal weights with inactive special bounds, the owner allocates an equal share to the")
    A("participating agents. Unequal weights or active bounds break the collapse (shown in the audit).\n")

    A("## 5. Architecture design\n")
    A("Six arms per scenario (equal, DRF, central joint Leontief, independent bundle max-min, separable Leontief")
    A("relaxation, distributed price Leontief), fresh Dirichlet(0.1) workloads under the confirmed scenario and")
    A("capacity construction, exact pending declarations, unit floors, moderate and high contention, 200 paired")
    A("seeds per cell, all enforced through the canonical Java runtime. Queue-order completion is primary; exact")
    A("best-subset completion (all 256 subsets) is a robustness outcome.\n")

    A("## 6. Architecture results (queue-order, tasks per 48-task run)\n")
    for cell in cells:
        cp = head["cell_policy"][cell]
        A("* **%s**: equal %.2f, DRF %.2f, central Leontief %.2f, independent max-min %.2f, separable relaxation %.2f,"
          % (cell, cp["equal"]["qo_tasks_per_run"], cp["drf"]["qo_tasks_per_run"],
             cp["central_joint_leontief"]["qo_tasks_per_run"], cp["independent_bundle_maxmin"]["qo_tasks_per_run"],
             cp["separable_leontief_relaxation"]["qo_tasks_per_run"]))
        A("  distributed price Leontief %.2f." % cp["distributed_price_leontief"]["qo_tasks_per_run"])
        A("  * central Leontief minus DRF: %s (five-condition pass: %s)"
          % (fmt(pq[cell]["central_joint_leontief_minus_drf"]), head["five_condition"]["fresh_replication"][cell]["pass"]))
        A("  * central Leontief minus independent max-min: %s (pass: %s)"
          % (fmt(pq[cell]["central_joint_leontief_minus_independent_bundle_maxmin"]), head["five_condition"]["coordination"][cell]["pass"]))
        A("  * independent max-min minus DRF: %s (pass: %s)"
          % (fmt(pq[cell]["independent_bundle_maxmin_minus_drf"]), head["five_condition"]["independent_vs_drf"][cell]["pass"]))
        A("  * separable relaxation equals equal quotas allocation rate: %.3f"
          % head["separable_relaxation_vs_equal"][cell]["allocation_equality_rate"])
    A("")
    A("Frozen flags: replication_pass=%s, coordination_pass=%s, independent_positive=%s, independent_noninferior=%s.\n"
      % (flags["replication_pass"], flags["coordination_pass"], flags["independent_positive"], flags["independent_noninferior"]))

    A("## 7. Distributed objective and allocation comparison\n")
    g = de["gap_summary"]
    A("Distributed classification: **%s**. Relative objective gap versus the central solver: mean %.2e, median %.2e,"
      % (de["classification"], g["mean"], g["median"]))
    A("95th percentile %.2e, maximum %.2e; %.1f%% of scenarios at most 1e-4. Maximum continuous feasibility residual"
      % (g["p95"], g["max"], 100 * g["frac_le_1e-4"]))
    A("%.1e; nonconvergences %d. Installed allocation L1 distance mean %.4f, L-infinity max %.0f; installed task-outcome"
      % (g["max_feasibility_residual"], g["nonconvergence_count"], g["installed_alloc_l1_mean"], g["installed_alloc_linf_max"]))
    A("disagreements total %d. Development validation (DISTRIBUTED_SOLVER.md): maximum relative objective gap %.2e over"
      % (g["installed_outcome_disagreements_total"], val["objective_gap"]["max"]))
    A("%d well-posed scenarios, maximum feasibility residual %.1e.\n" % (val["n_wellposed"], val["max_capacity_residual"]))

    A("## 8. Distribution of gains and losses\n")
    for cell in cells:
        dn = head["distributional"][cell]
        A("* **%s**, central Leontief vs equal quotas: %.1f%% of agents harmed (mean loss %.3f tasks), %.1f%% better"
          % (cell, 100 * dn["central_joint_leontief"]["vs_equal"]["frac_harmed"],
             dn["central_joint_leontief"]["vs_equal"]["mean_loss_harmed"],
             100 * dn["central_joint_leontief"]["vs_equal"]["frac_better"]))
        A("  (mean gain %.3f); zero-completion fraction %.3f. Not a Pareto improvement."
          % (dn["central_joint_leontief"]["vs_equal"]["mean_gain_better"], dn["central_joint_leontief"]["vs_equal"]["frac_zero"]))
    A("")

    A("## 9. Harmed-set comparison (central vs distributed Leontief)\n")
    for cell in cells:
        hs = head["harmed_set_central_vs_distributed"][cell]["equal"]
        A("* **%s** (harmed relative to equal quotas): harm-indicator agreement %.3f, harmed-set Jaccard %.3f,"
          % (cell, hs["harm_indicator_agreement"], hs["harmed_set_jaccard"]))
        A("  per-agent completion equality %.3f, exact harmed-set equal: %s, max per-agent completion difference %.3f tasks."
          % (hs["per_agent_completion_equality"], hs["exact_harmed_set_equal"], hs["max_abs_per_agent_diff_tasks"]))
    A("")

    A("## 10. Selected drift carrier\n")
    A("The frozen adaptive rule selected **%s** (branch %d). %s\n" % (dec["selected_carrier"], dec["branch"], dec["interpretation"]))

    if drift:
        A("## 11. Drift design and 12. Drift results\n")
        A("The carrier was retested with declarations from stale calibration, refreshed calibration, the latent")
        A("distribution oracle and the exact execution-queue oracle, over delta in {0, .25, .5, .75, 1} with common")
        A("random numbers, frozen capacity from baseline latent demand, and policy/declaration-independent bounds.")
        A("Co-primary decision (carrier stale minus DRF stale at delta 0.25):")
        for cell, d in drift["co_primary_decision"].items():
            A("* %s: %+.3f tasks/run (95%% CI [%+.3f, %+.3f]), pass=%s"
              % (cell, d["mean_tasks"], d["ci_lo_tasks"], d["ci_hi_tasks"], d["pass"]))
        A("Declaration-robustness classification: **%s**.\n" % drift["declaration_robustness_classification"])
    else:
        A("## 11-12. Drift\n")
        A("Drift results pending in this build.\n")

    A("## 13. Exact safe central claim\n")
    if de["classification"] == "OBJECTIVE_AND_OUTCOME_EQUIVALENT":
        A("> The tested coordinated Leontief outcome did not require centralized computation. Resource-price")
        A("> coordination reproduced its continuous objective and aggregate completion. Installation and enforcement")
        A("> nevertheless remained platform-controlled.")
        if any(not head["harmed_set_central_vs_distributed"][c]["equal"]["exact_harmed_set_equal"] for c in cells):
            A("> The distributed method reproduced the aggregate result; where the harmed sets are not exactly equal,")
            A("> it altered which agents bore the losses (Section 9).")
    A("")

    A("## 14. Claim matrix\n")
    A("| claim | supported | evidence |")
    A("|---|---|---|")
    A("| Prior existence result replicates on fresh seeds | %s | central Leontief minus DRF, both cells |"
      % all(head["five_condition"]["fresh_replication"][c]["pass"] for c in cells))
    A("| Cross-resource coordination beats the strongest uncoordinated resource-local mechanism | %s | central minus independent max-min |"
      % flags["coordination_pass"])
    A("| Positive result does not require joint computation | %s | independent max-min positive and noninferior |"
      % (flags["independent_positive"] and flags["independent_noninferior"]))
    A("| Joint outcome does not require centralized computation | %s | distributed equivalence |"
      % flags["distributed_equivalent"])
    A("")

    A("## 15. Material limitations\n")
    A("Synthetic Dirichlet(0.1) workloads; six agents, four resources; the tested declaration sources and drift")
    A("levels; installation and enforcement through the canonical runtime. Not tested: strategic reporting,")
    A("collusion, real deployment distributions, governance, contract remedies. Not a Pareto improvement; no")
    A("individual-rationality, strategyproofness or collusion-resistance claim is made.\n")

    A("## 16. Immutable GitHub links\n")
    A("* Preregistration: %s/commit/%s" % (GH, prereg_commit or "<prereg>"))
    A("* Architecture result: %s/commit/%s" % (GH, arch_commit or "<arch>"))
    A("* Carrier decision: recorded in DRIFT_CARRIER_DECISION.json at the architecture result commit")
    A("* Drift result: %s/commit/%s" % (GH, drift_commit or "<drift>"))
    A("* Verification: %s/commit/%s\n" % (GH, verify_commit or "<verify>"))

    A("## 17. Why the question is now closed at the tested scope\n")
    A("The fresh-seed replication, the coordination test against the strongest tested uncoordinated resource-local")
    A("mechanism, the distributed-versus-central objective, completion and harmed-set comparison, and the")
    A("declaration-calibration-and-drift stress test together answer, at the tested scope, whether the ARB")
    A("principle holds, whether coordination is the cause, whether centralized computation is required, whether")
    A("central and distributed implementations harm the same agents, and where consequential authority remains.")
    A("Strategic reporting, collusion, real prevalence, governance and contract remedies are separate questions,")
    A("not unfinished controls of this experiment.\n")

    with open(os.path.join(HERE, "ORIGINAL_QUESTION_CLOSURE.md"), "w") as f:
        f.write("\n".join(L) + "\n")
    print("wrote ORIGINAL_QUESTION_CLOSURE.md")


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    for a in ("prereg", "arch", "drift", "verify"):
        ap.add_argument("--%s-commit" % a, default="")
    args = ap.parse_args()
    main(args.prereg_commit, args.arch_commit, args.drift_commit, args.verify_commit)
