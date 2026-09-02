#!/usr/bin/env python3
"""Frozen adaptive selection of the drift-experiment carrier.

Reads the completed architecture analysis (derived only from the committed architecture
raw data) and applies the preregistered priority rule with no manual override. Writes
DRIFT_CARRIER_DECISION.json recording every input estimate and interval, every condition
and its pass/fail, the selected carrier and its interpretation, the architecture
raw-data hashes, the public preregistration protocol hash, and this script's own hash.

Priority (preregistered):
  1. independent_positive AND independent_noninferior -> independent_bundle_maxmin
     (the positive effect does not require joint cross-resource computation).
  2. else if coordination_pass:
       distributed_price_leontief if distributed_equivalent, else central_joint_leontief.
  3. else if replication_pass (central did not beat the independent mechanism):
       independent_bundle_maxmin if independent_noninferior, else central_joint_leontief.
  4. else central_joint_leontief_diagnostic (no material positive architecture result).
"""
import hashlib
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ARCH = os.environ.get("OQ_ARCH_DIR", os.path.join(HERE, "results", "architecture_v1"))


def sha256_file(path):
    if not os.path.exists(path):
        return None
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def decide(replication_pass, coordination_pass, independent_positive,
           independent_noninferior, distributed_equivalent):
    """Pure preregistered priority rule. Returns (carrier, branch, interpretation).
    No manual override is possible: the output is a deterministic function of the five
    boolean conditions, which are computed only from the architecture raw data."""
    if independent_positive and independent_noninferior:
        return ("independent_bundle_maxmin", 1,
                "The positive effect does not require joint cross-resource computation: a resource-local "
                "mechanism using the same fixed-proportion declarations is positive against DRF and "
                "noninferior to central Leontief.")
    if coordination_pass:
        if distributed_equivalent:
            return ("distributed_price_leontief", 2,
                    "Cross-resource coordination adds value and the distributed price solver reproduces the "
                    "central objective and outcomes; the joint outcome need not be centrally computed.")
        return ("central_joint_leontief", 2,
                "Cross-resource coordination adds value; the distributed method was not established as "
                "objective-and-outcome equivalent, so the central mechanism carries the drift test.")
    if replication_pass:
        if independent_noninferior:
            return ("independent_bundle_maxmin", 3,
                    "The prior existence result replicates but central Leontief does not beat the independent "
                    "mechanism, which is noninferior; the independent mechanism carries the drift test.")
        return ("central_joint_leontief", 3,
                "The prior existence result replicates; central Leontief carries the drift test.")
    return ("central_joint_leontief_diagnostic", 4,
            "The architecture experiment did not identify a mechanism carrying a material positive result. The "
            "drift experiment diagnostically tests whether the previously verified exact-information result is "
            "sensitive to calibration and drift; no new positive architecture claim is made.")


def main():
    headline = json.load(open(os.path.join(ARCH, "architecture_headline.json")))
    flags = headline["flags"]
    pq = headline["paired_qo"]
    cells = headline["co_primary_cells"]

    replication_pass = bool(flags["replication_pass"])
    coordination_pass = bool(flags["coordination_pass"])
    independent_positive = bool(flags["independent_positive"])
    independent_noninferior = bool(flags["independent_noninferior"])
    distributed_equivalent = bool(flags["distributed_equivalent"])

    carrier, branch, interpretation = decide(
        replication_pass, coordination_pass, independent_positive,
        independent_noninferior, distributed_equivalent)

    def cmp_block(key):
        return {cell: {k: pq[cell][key][k] for k in ("mean_tasks", "ci_lo_tasks", "ci_hi_tasks")} for cell in cells}

    decision = {
        "selected_carrier": carrier,
        "interpretation": interpretation,
        "branch": branch,
        "conditions": {
            "replication_pass": replication_pass,
            "coordination_pass": coordination_pass,
            "independent_positive": independent_positive,
            "independent_noninferior": independent_noninferior,
            "distributed_equivalent": distributed_equivalent,
        },
        "inputs": {
            "central_joint_leontief_minus_drf": cmp_block("central_joint_leontief_minus_drf"),
            "central_joint_leontief_minus_independent_bundle_maxmin": cmp_block("central_joint_leontief_minus_independent_bundle_maxmin"),
            "independent_bundle_maxmin_minus_drf": cmp_block("independent_bundle_maxmin_minus_drf"),
            "independent_bundle_maxmin_minus_central_joint_leontief": cmp_block("independent_bundle_maxmin_minus_central_joint_leontief"),
            "five_condition_fresh_replication": {c: headline["five_condition"]["fresh_replication"][c]["pass"] for c in cells},
            "five_condition_coordination": {c: headline["five_condition"]["coordination"][c]["pass"] for c in cells},
            "five_condition_independent_vs_drf": {c: headline["five_condition"]["independent_vs_drf"][c]["pass"] for c in cells},
            "distributed_classification": headline["distributed_equivalence"]["classification"],
            "indep_noninferior_by_cell": flags["indep_noninferior_by_cell"],
        },
        "architecture_raw_hashes": {
            name: sha256_file(os.path.join(ARCH, "raw", name + ".csv"))
            for name in ("scenarios", "runs", "agents", "distributed", "infeasible")
        },
        "architecture_headline_sha256": sha256_file(os.path.join(ARCH, "architecture_headline.json")),
        "public_preregistration_protocol_sha256": sha256_file(
            os.path.join(HERE, "ORIGINAL_QUESTION_CLOSURE_PROTOCOL.md")),
        "public_preregistration_commit": os.environ.get("OQ_PREREG_COMMIT", ""),
        "script_sha256": sha256_file(os.path.abspath(__file__)),
    }
    with open(os.path.join(HERE, "DRIFT_CARRIER_DECISION.json"), "w") as f:
        json.dump(decision, f, indent=2)
    print("selected carrier: %s (branch %d)" % (carrier, branch))
    print("  conditions:", decision["conditions"])
    return decision


if __name__ == "__main__":
    main()
