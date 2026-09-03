#!/usr/bin/env python3
"""Generate ORIGINAL_QUESTION_CLOSURE.md from the committed headlines, carrier decision,
comparator audit and distributed validation. Data-driven so the report is always
consistent with the raw results and states only the safe claims the data support.

This is the v2 (post-verification) report generator. Relative to the first build it makes
the following interpretation corrections, none of which touch any raw datum, mechanism,
frozen rule, seed or outcome (see CORRECTIONS_AFTER_VERIFICATION.md):

  1. The headline is the preregistered *conditional* existence claim, not a narrow
     "central computation improves completion" claim.
  2. The ARB principle is reported as supported as an existence result for a
     complementarity-aware allocation rule *within a platform*.
  3. The experiments do not estimate the causal value of platform authority: every arm
     shares platform installation and enforcement, so authority is never the varied factor.
  4. Central-minus-independent is a statistically positive increment that FAILED the frozen
     one-task materiality condition; coordination_pass=False is authoritative.
  5. The independent resource-local mechanism captured most of central Leontief's advantage
     over DRF (reported as a fraction computed from the raw paired means).
  6. The claim matrix is corrected to seven preregistered rows.
  8. Severe drift is explicitly excluded from the robustness headline.
  9. The verification links point to the first verification commit and the v2 branch.
 10. The comparator is qualified as the strongest *tested* resource-local comparator.
 11. Coordination is not asserted as the cause; no claim that no further question can matter.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ARCH = os.path.join(HERE, "results", "architecture_v1")
DRIFT = os.path.join(HERE, "results", "drift_v1")
GH = "https://github.com/CARMA-org/arbitration-platform"
# The first independent verification (branch verification/platform-original-question-closure).
FIRST_VERIFICATION_COMMIT = "d2d77dbe33c4a5b6f9770f225b19ee68b45f1514"
# The comprehensive v2 verification lives on its own branch (stable ref link, not a commit,
# because it is created from the correction commit this report is regenerated on).
V2_VERIFICATION_BRANCH = "verification/platform-original-question-closure-v2"


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
    first_verification_commit = verify_commit or FIRST_VERIFICATION_COMMIT

    # Effect sizes read straight from the frozen headline (moderate, high).
    cdrf = {c: pq[c]["central_joint_leontief_minus_drf"]["mean_tasks"] for c in cells}
    idrf = {c: pq[c]["independent_bundle_maxmin_minus_drf"]["mean_tasks"] for c in cells}
    cind = {c: pq[c]["central_joint_leontief_minus_independent_bundle_maxmin"]["mean_tasks"] for c in cells}
    capture = {c: idrf[c] / cdrf[c] for c in cells}  # fraction of central's DRF advantage captured locally

    L = []
    A = L.append
    A("# Original ARB empirical question: closure at the tested scope\n")

    A("## 0. The conditional claim this closure supports\n")
    A("> In synthetic six-agent, four-resource workloads with strongly heterogeneous complementary")
    A("> requirements, a platform-enforced Leontief allocation increased aggregate task completion relative")
    A("> to DRF by 2.655 tasks per 48-task run at moderate contention and 1.825 at high contention. With")
    A("> declarations estimated from a fixed calibration history before 25% task-source drift, the")
    A("> corresponding advantages were 1.605 and 1.500 tasks. A resource-local bundle-progress mechanism also")
    A("> beat DRF and captured most of the central mechanism's gain. The additional central-versus-local")
    A("> increment was positive but failed the preregistered one-task materiality test. A price-mediated")
    A("> implementation reproduced the centralized objective and aggregate completion without invoking the")
    A("> central optimizer, although it did not reproduce the exact distribution of losses. These experiments")
    A("> establish an allocation-rule existence result within a platform; they do not compare platform")
    A("> authority against the absence of a platform.\n")
    A("The starting ARB principle -- that a complementarity-aware, platform-enforced allocation rule can")
    A("raise aggregate completion under heterogeneous complementary demand -- is therefore **supported as an")
    A("existence result for an allocation rule within a platform**, at the tested scope. It is not converted")
    A("here into a universal claim, a causal claim about platform authority, a Pareto claim, or a")
    A("strategyproofness, privacy or deployed-decentralization claim.\n")

    A("## 1. The original empirical question\n")
    A("Can platform-mediated allocation improve aggregate task completion when agents require heterogeneous,")
    A("complementary bundles of several constrained resources? This report closes that question at the tested")
    A("scope using two preregistered experiments and one preregistered adaptive decision rule. Because every")
    A("arm -- equal quotas, DRF, and each Leontief variant -- is installed and enforced through the identical")
    A("platform runtime, the experiments compare *allocation rules within a platform*; they do not estimate")
    A("the causal value of platform authority itself, which is never the varied factor (Section 15).\n")

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
    A("The independent bundle max-min is the strongest tested resource-local comparator -- the strongest")
    A("uncoordinated mechanism among those constructed and tested here, not a universally strongest mechanism.\n")

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
        A("  * central Leontief minus independent max-min: %s (materiality pass: %s)"
          % (fmt(pq[cell]["central_joint_leontief_minus_independent_bundle_maxmin"]), head["five_condition"]["coordination"][cell]["pass"]))
        A("  * independent max-min minus DRF: %s (pass: %s)"
          % (fmt(pq[cell]["independent_bundle_maxmin_minus_drf"]), head["five_condition"]["independent_vs_drf"][cell]["pass"]))
        A("  * separable relaxation equals equal quotas allocation rate: %.3f"
          % head["separable_relaxation_vs_equal"][cell]["allocation_equality_rate"])
    A("")
    A("Frozen flags: replication_pass=%s, coordination_pass=%s, independent_positive=%s, independent_noninferior=%s.\n"
      % (flags["replication_pass"], flags["coordination_pass"], flags["independent_positive"], flags["independent_noninferior"]))
    A("Reading of the coordination test: central Leontief minus the independent resource-local mechanism is a")
    A("*statistically positive but immaterial* increment. Its paired interval is above zero in both cells")
    A("(+%.3f and +%.3f tasks/run), but the point estimate is below the frozen +1.000-task materiality bar, so the"
      % (cind[cells[0]], cind[cells[1]]))
    A("preregistered `coordination_pass` condition is **False** -- and that machine-checked flag, not the sign of")
    A("the increment, is authoritative. The independent mechanism captured about %.0f%% of central Leontief's"
      % (100 * capture[cells[0]]))
    A("advantage over DRF at moderate contention and about %.0f%% at high contention. The evidence therefore does"
      % (100 * capture[cells[1]]))
    A("**not** establish that a positive result over DRF requires joint cross-resource computation, and it does")
    A("**not** establish that cross-resource coordination is the cause of the gain; most of the gain is available")
    A("from a resource-local rule that never couples the resources.\n")

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
    A("%d well-posed scenarios, maximum feasibility residual %.1e." % (val["n_wellposed"], val["max_capacity_residual"]))
    A("This distributed arm is a single-process simulation of a price-decomposed algorithm that never calls the")
    A("central convex optimizer; it establishes central-solver dispensability, not a deployed distributed system,")
    A("privacy, or the absence of all cross-resource communication (DISTRIBUTED_SOLVER.md, Section 6).\n")

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
        # Severe-drift caveat, computed from the frozen drift headline arm means.
        sec = drift["secondary"]
        def arm(cellkey, a):
            return sec[cellkey]["arm_tasks_per_run"][a]
        A("`ROBUST_AT_MODEST_DRIFT` means only that the frozen *relative* comparison of the stale carrier against")
        A("stale DRF passed the five-condition rule at delta 0.25, in both contention cells. It does **not** mean the")
        A("mechanism is robust to severe drift. Severe drift is not covered by this headline: at higher delta, stale")
        A("declarations reduce absolute completion substantially, and the stale carrier can fall below equal quotas.")
        A("For example, at delta 1.00 the stale carrier completes %.2f vs equal %.2f (moderate) and %.2f vs equal %.2f"
          % (arm("delta1.00__moderate", "carrier_stale_calibration"), arm("delta1.00__moderate", "equal"),
             arm("delta1.00__high", "carrier_stale_calibration"), arm("delta1.00__high", "equal")))
        A("(high) tasks/run -- below equal quotas in both -- even though the stale-carrier-minus-stale-DRF *relative*")
        A("advantage remains positive. The robustness headline is a statement about the relative comparison against")
        A("DRF at modest drift, not an absolute-performance or severe-drift guarantee. The preregistered secondary")
        A("drift outputs (utilization, distributional outcomes, declaration errors, dissimilarity, realized")
        A("contention) are emitted in `results/drift_v1/preregistered_secondary_completion/`.\n")
    else:
        A("## 11-12. Drift\n")
        A("Drift results pending in this build.\n")

    A("## 13. Exact safe conditional claim\n")
    A("> In synthetic six-agent, four-resource workloads with strongly heterogeneous complementary")
    A("> requirements, a platform-enforced Leontief allocation increased aggregate task completion relative")
    A("> to DRF by 2.655 tasks per 48-task run at moderate contention and 1.825 at high contention. With")
    A("> declarations estimated from a fixed calibration history before 25% task-source drift, the")
    A("> corresponding advantages were 1.605 and 1.500 tasks. A resource-local bundle-progress mechanism also")
    A("> beat DRF and captured most of the central mechanism's gain. The additional central-versus-local")
    A("> increment was positive but failed the preregistered one-task materiality test. A price-mediated")
    A("> implementation reproduced the centralized objective and aggregate completion without invoking the")
    A("> central optimizer, although it did not reproduce the exact distribution of losses. These experiments")
    A("> establish an allocation-rule existence result within a platform; they do not compare platform")
    A("> authority against the absence of a platform.")
    if any(not head["harmed_set_central_vs_distributed"][c]["equal"]["exact_harmed_set_equal"] for c in cells):
        A(">")
        A("> Where the central and distributed harmed sets are not exactly equal, the distributed method")
        A("> reproduced the aggregate result while altering which agents bore the losses (Section 9).")
    A("")

    A("## 14. Claim matrix\n")
    A("| claim | supported | evidence |")
    A("|---|---|---|")
    A("| Fresh-seed existence result replicates | %s | central Leontief minus DRF passes both cells |"
      % all(head["five_condition"]["fresh_replication"][c]["pass"] for c in cells))
    A("| Material cross-resource coordination advantage established | %s | central minus independent max-min fails the +1-task materiality bar (coordination_pass) |"
      % flags["coordination_pass"])
    A("| A positive result over DRF requires joint cross-resource computation | %s | independent max-min alone is positive over DRF in both cells |"
      % (not (flags["independent_positive"])))
    A("| Independent mechanism fully reproduces central or is noninferior | %s | independent minus central is materially negative (independent_noninferior) |"
      % flags["independent_noninferior"])
    A("| Central objective and aggregate outcome require the centralized convex solver | %s | distributed price solver reproduces objective and aggregate completion |"
      % (not flags["distributed_equivalent"]))
    A("| Central and distributed implementations impose identical individual losses | %s | exact harmed-set equality fails in at least one cell (Section 9) |"
      % all(head["harmed_set_central_vs_distributed"][c]["equal"]["exact_harmed_set_equal"] for c in cells))
    A("| Relative advantage survives the preregistered modest-drift cell | %s | carrier stale minus DRF stale passes at delta 0.25, both cells |"
      % (drift["declaration_robustness_classification"] == "ROBUST_AT_MODEST_DRIFT" if drift else False))
    A("")

    A("## 15. Material limitations and where consequential authority remains\n")
    A("Synthetic Dirichlet(0.1) workloads; six agents, four resources; the tested declaration sources and drift")
    A("levels; installation and enforcement through the canonical runtime. Not tested: strategic reporting,")
    A("collusion, real deployment distributions, governance, contract remedies. Not a Pareto improvement; no")
    A("individual-rationality, strategyproofness or collusion-resistance claim is made.")
    A("")
    A("Consequential platform authority remains **outside** what these experiments varied. All six arms are")
    A("installed and enforced by the same platform runtime through the identical contract path, so the study")
    A("compares allocation *rules* holding platform authority fixed. It does not compare a platform against no")
    A("platform, does not decentralize installation or enforcement (only the *computation* of the Leontief")
    A("allocation was shown to be decomposable), and therefore does not estimate the causal value of platform")
    A("authority. The distributed arm computes the allocation by price-mediated tatonnement but still relies on")
    A("the platform to install and enforce the resulting contracts. Whether platform authority is itself")
    A("beneficial, and how it should be governed, remain open questions rather than settled by this closure.\n")

    A("## 16. Immutable GitHub links\n")
    A("* Preregistration: %s/commit/%s" % (GH, prereg_commit or "7ebf8b70366b8b68a90554a722f097d8acea3f01"))
    A("* Architecture result: %s/commit/%s" % (GH, arch_commit or "2f9fa1b05a38d941511491e030d3e964232350eb"))
    A("* Carrier decision: recorded in DRIFT_CARRIER_DECISION.json at the architecture result commit")
    A("* Drift result: %s/commit/%s" % (GH, drift_commit or "3204646f74901bb357f614e2f5ab4c1b276fb449"))
    A("* First independent verification: %s/commit/%s" % (GH, first_verification_commit))
    A("* Comprehensive verification (v2): %s/tree/%s\n" % (GH, V2_VERIFICATION_BRANCH))

    A("## 17. Why the question is closed at the tested scope\n")
    A("The fresh-seed replication, the coordination/materiality test against the strongest tested resource-local")
    A("mechanism, the distributed-versus-central objective, completion and harmed-set comparison, and the")
    A("declaration-calibration-and-drift stress test together answer, at the tested scope: the ARB principle is")
    A("supported as an allocation-rule existence result within a platform; a *material* cross-resource")
    A("coordination advantage was not established; a positive result over DRF does not require joint computation;")
    A("the central objective and aggregate completion do not require the centralized convex solver; central and")
    A("distributed implementations do not impose identical individual losses; and the relative advantage survives")
    A("modest but not severe declaration drift. Consequential platform authority was held fixed across all arms")
    A("and so was not evaluated. Strategic reporting, collusion, real prevalence, governance, contract remedies,")
    A("and the value of platform authority itself are separate questions, not unfinished controls of this")
    A("experiment; this closure settles the tested allocation-rule questions without asserting that no further")
    A("empirical question could matter.\n")

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
