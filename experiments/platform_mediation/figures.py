#!/usr/bin/env python3
"""Publication figures for the platform-mediation sweep."""
import argparse
import csv
import os
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
FIG = os.path.join(HERE, "figures")

COLOR = {
    "equal": "#009E73", "drf": "#CC79A7", "decomposed_cobb_douglas": "#0072B2",
    "joint_linear": "#D55E00", "joint_cobb_douglas": "#E69F00",
    "joint_ces": "#56B4E9", "joint_leontief": "#000000",
}
POLICY_ORDER = ["equal", "drf", "decomposed_cobb_douglas", "joint_linear",
                "joint_cobb_douglas", "joint_ces", "joint_leontief"]
CELL_ORDER = ["homogeneous", "mixed_bundle"]


def load_runs():
    path = os.path.join(HERE, "results", "raw", "runs.csv")
    return list(csv.DictReader(open(path)))


def load_paired():
    return list(csv.DictReader(open(os.path.join(HERE, "tables", "paired_differences.csv"))))


def load_individual():
    return list(csv.DictReader(open(os.path.join(HERE, "tables", "individual_loss.csv"))))


def cell_policy_mean(rows, metric):
    agg = defaultdict(list)
    for r in rows:
        agg[(r["cell"], r["policy"])].append(float(r[metric]))
    return {k: float(np.mean(v)) for k, v in agg.items()}


def cells_sorted(rows):
    cells = sorted({r["cell"] for r in rows})

    def key(c):
        reg = c.rsplit("__", 1)[0]
        con = c.rsplit("__", 1)[1]
        return (CELL_ORDER.index(reg) if reg in CELL_ORDER else 99, con)

    return sorted(cells, key=key)


def short(cell):
    reg, con = cell.rsplit("__", 1)
    abbr = {"homogeneous": "homo", "mixed_bundle": "mixed"}
    return "%s/%s" % (abbr.get(reg, reg), con[0])


def fig_completion(rows):
    comp = cell_policy_mean(rows, "completion_mean")
    cells = cells_sorted(rows)
    x = np.arange(len(cells))
    w = 0.115
    fig, ax = plt.subplots(figsize=(12, 5))
    for k, pol in enumerate(POLICY_ORDER):
        vals = [comp.get((c, pol), 0.0) for c in cells]
        ax.bar(x + (k - 3) * w, vals, w, label=pol, color=COLOR.get(pol, "#888"))
    ax.set_xticks(x)
    ax.set_xticklabels([short(c) for c in cells], rotation=0)
    ax.set_ylabel("mean task completion rate")
    ax.set_title("Task completion by policy and cell")
    ax.legend(ncol=4, fontsize=8, loc="upper center", bbox_to_anchor=(0.5, -0.08))
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, "fig1_completion_by_policy.png"), dpi=150, bbox_inches="tight")
    plt.close(fig)


def fig_nonlinear_vs_linear(paired):
    nonlin = ["joint_cobb_douglas", "joint_ces", "joint_leontief"]
    cells = sorted({r["cell"] for r in paired})
    cells = sorted(cells, key=lambda c: (CELL_ORDER.index(c.rsplit("__", 1)[0])
                                         if c.rsplit("__", 1)[0] in CELL_ORDER else 99,
                                         c.rsplit("__", 1)[1]))
    idx = {(r["cell"], r["comparison"], r["metric"]): r for r in paired}
    fig, ax = plt.subplots(figsize=(12, 5))
    x = np.arange(len(cells))
    w = 0.25
    for k, pol in enumerate(nonlin):
        comp = pol + "_minus_joint_linear"
        means, los, his = [], [], []
        for c in cells:
            r = idx.get((c, comp, "completion_mean"))
            m = float(r["mean_diff"]) if r else 0.0
            lo = float(r["ci_lo"]) if r else 0.0
            hi = float(r["ci_hi"]) if r else 0.0
            means.append(m)
            los.append(m - lo)
            his.append(hi - m)
        ax.bar(x + (k - 1) * w, means, w, yerr=[los, his], capsize=2,
               label=pol, color=COLOR.get(pol, "#888"))
    ax.axhline(0, color="k", linewidth=0.8)
    ax.set_xticks(x)
    ax.set_xticklabels([short(c) for c in cells])
    ax.set_ylabel("completion: nonlinear joint - linear joint (95% CI)")
    ax.set_title("Complementarity-aware utility vs linear utility")
    ax.legend(fontsize=9)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, "fig2_nonlinear_vs_linear.png"), dpi=150, bbox_inches="tight")
    plt.close(fig)


def fig_individual(indiv):
    cells = sorted({r["cell"] for r in indiv},
                   key=lambda c: (CELL_ORDER.index(c.rsplit("__", 1)[0])
                                  if c.rsplit("__", 1)[0] in CELL_ORDER else 99,
                                  c.rsplit("__", 1)[1]))
    policies = ["joint_linear", "joint_cobb_douglas", "joint_ces", "joint_leontief"]
    idx = {(r["cell"], r["policy"]): r for r in indiv}
    fig, ax = plt.subplots(figsize=(12, 5))
    x = np.arange(len(cells))
    w = 0.2
    for k, pol in enumerate(policies):
        vals = [float(idx[(c, pol)]["worst_agent_loss_vs_equal"]) if (c, pol) in idx else 0
                for c in cells]
        ax.bar(x + (k - 1.5) * w, vals, w, label=pol, color=COLOR.get(pol, "#888"))
    ax.axhline(0, color="k", linewidth=0.8)
    ax.set_xticks(x)
    ax.set_xticklabels([short(c) for c in cells])
    ax.set_ylabel("worst per-agent completion change vs equal quotas")
    ax.set_title("Individual-agent harm relative to equal quotas (more negative = worse)")
    ax.legend(fontsize=9)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, "fig3_individual_loss.png"), dpi=150, bbox_inches="tight")
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="full")
    ap.parse_args()
    os.makedirs(FIG, exist_ok=True)
    rows = load_runs()
    fig_completion(rows)
    fig_nonlinear_vs_linear(load_paired())
    fig_individual(load_individual())
    print("wrote figures to", FIG)


if __name__ == "__main__":
    main()
