#!/usr/bin/env python3
"""Independent record-by-record audit of locally_optimized_completion.

Reimplements exact 256-subset enumeration with mandatory footprints transcribed
from the documented service/archetype definitions (NOT imported from the project),
and compares the independently computed maximum-feasible mandatory task count to the
committed ``locally_optimized_count`` for every one of the 117,600 agent records.

Only the maximum feasible COUNT is compared (locally_optimized_completion = count/8);
the project's tie-break (quality, consumption, lexicographic) selects which subset,
not the count, so it does not affect the completion value.
"""
import csv
import itertools
import json
import os
import sys

import numpy as np

RESOURCES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]

# Mandatory resource footprints per archetype, transcribed from the documented
# SERVICE_FOOTPRINT + ARCHETYPES mandatory steps (archetypes.py / ServiceType.java):
#   research      = KNOWLEDGE_RETRIEVAL + REASONING + TEXT_GENERATION
#   code_review   = CODE_ANALYSIS
#   doc_processing= OCR + DATA_EXTRACTION + TEXT_SUMMARIZATION
#   monitoring    = KNOWLEDGE_RETRIEVAL + TEXT_CLASSIFICATION
MANDATORY = {
    "research":       {"COMPUTE": 34, "MEMORY": 29, "API_CREDITS": 17, "DATASET": 8},
    "code_review":    {"COMPUTE": 10, "MEMORY": 8,  "API_CREDITS": 6,  "DATASET": 0},
    "doc_processing": {"COMPUTE": 22, "MEMORY": 16, "API_CREDITS": 10, "DATASET": 0},
    "monitoring":     {"COMPUTE": 8,  "MEMORY": 9,  "API_CREDITS": 1,  "DATASET": 8},
}
ARCH = list(MANDATORY.keys())
MAND_VEC = {a: np.array([MANDATORY[a][r] for r in RESOURCES], dtype=np.int64) for a in MANDATORY}
# 256 x 8 subset-membership matrix
MASKS = np.array([[(m >> i) & 1 for i in range(8)] for m in range(256)], dtype=np.int64)
MASK_COUNT = MASKS.sum(axis=1)


def load_csv(p):
    with open(p) as f:
        return list(csv.DictReader(f))


def agent_footprint_matrix(task_counts_for_agent):
    """8x4 mandatory footprint matrix for an agent from its archetype multiset."""
    rows = []
    for arch, c in task_counts_for_agent.items():
        rows.extend([MAND_VEC[arch]] * int(c))
    assert len(rows) == 8, "agent does not have 8 tasks: %d" % len(rows)
    return np.array(rows, dtype=np.int64)  # 8 x 4


def max_feasible_count(F, alloc):
    """Independent exact enumeration: max number of tasks whose summed mandatory
    footprint fits within the installed allocation on every resource."""
    subset_sums = MASKS @ F               # 256 x 4
    feasible = np.all(subset_sums <= alloc, axis=1)   # 256
    return int(MASK_COUNT[feasible].max())            # empty set always feasible -> >=0


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    out = sys.argv[2] if len(sys.argv) > 2 else "."
    conf = os.path.join(root, "experiments", "platform_mediation_heterogeneity",
                        "results", "confirmatory_v1")
    scen = load_csv(os.path.join(conf, "raw", "scenarios.csv"))
    agents = load_csv(os.path.join(conf, "raw", "agents.csv"))

    # cross-check transcribed footprints vs committed aggregate mandatory demand
    ftp_bad = 0
    for s in scen:
        counts = json.loads(s["realized_task_counts_by_agent"])
        agg = np.zeros(4, dtype=np.int64)
        for a_counts in counts:
            for arch, c in a_counts.items():
                agg += MAND_VEC[arch] * int(c)
        committed = json.loads(s["aggregate_mandatory_demand"])
        if any(int(agg[i]) != int(committed[RESOURCES[i]]) for i in range(4)):
            ftp_bad += 1
    print("footprint cross-check vs aggregate_mandatory_demand: %d/%d scenarios mismatch"
          % (ftp_bad, len(scen)))

    # precompute subset footprint sums per (cell,seed,agent_idx) once (policy-independent)
    F_by = {}
    for s in scen:
        counts = json.loads(s["realized_task_counts_by_agent"])
        for i in range(6):
            F_by[(s["cell"], s["seed"], i)] = agent_footprint_matrix(counts[i])

    mism = []
    n = 0
    compl_mism = 0
    for a in agents:
        i = int(a["agent"][1:])
        F = F_by[(a["cell"], a["seed"], i)]
        alloc = json.loads(a["allocated"])
        av = np.array([alloc[r] for r in RESOURCES], dtype=np.int64)
        indep_count = max_feasible_count(F, av)
        committed_count = int(a["locally_optimized_count"])
        committed_compl = float(a["locally_optimized_completion"])
        n += 1
        if indep_count != committed_count:
            mism.append({"cell": a["cell"], "seed": a["seed"], "policy": a["policy"],
                         "agent": a["agent"], "independent_count": indep_count,
                         "committed_count": committed_count,
                         "committed_completion": committed_compl})
        if abs(indep_count / 8.0 - committed_compl) > 1e-9:
            compl_mism += 1

    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "local_opt_record_comparison.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["records_compared", "count_mismatches", "completion_mismatches",
                    "footprint_scenario_mismatches"])
        w.writerow([n, len(mism), compl_mism, ftp_bad])
        if mism:
            w.writerow([])
            w.writerow(["cell", "seed", "policy", "agent", "independent_count",
                        "committed_count", "committed_completion"])
            for m in mism[:1000]:
                w.writerow([m["cell"], m["seed"], m["policy"], m["agent"],
                            m["independent_count"], m["committed_count"], m["committed_completion"]])

    print("records compared: %d" % n)
    print("count mismatches (independent vs committed): %d" % len(mism))
    print("completion mismatches (count/8 vs committed completion): %d" % compl_mism)
    print("RESULT: %s" % ("EXACT MATCH — committed local optimizer is exact and symmetric"
                          if len(mism) == 0 and compl_mism == 0 and ftp_bad == 0 else "MISMATCH — see CSV"))
    return 0 if (len(mism) == 0 and compl_mism == 0 and ftp_bad == 0) else 1


if __name__ == "__main__":
    sys.exit(main())
