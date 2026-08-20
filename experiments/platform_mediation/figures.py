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

# Okabe-Ito colourblind-safe palette.
COLOR = {"equal": "#009E73", "drf": "#CC79A7", "separable": "#0072B2", "joint": "#D55E00"}
POLICY_ORDER = ["equal", "drf", "separable", "joint"]
CELL_ORDER = ["identical", "nearly_specialized", "broad_heterogeneous", "complementary"]


def load_runs(mode):
    path = os.path.join(HERE, "results", "raw", "runs.csv")
    rows = list(csv.DictReader(open(path)))
    return rows


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


def fig_completion_and_welfare(rows):
    cells = cells_sorted(rows)
    comp = cell_policy_mean(rows, "completion_mean")
    welf = cell_policy_mean(rows, "declared_welfare")
    x = np.arange(len(cells))
    w = 0.2
    fig, ax = plt.subplots(1, 2, figsize=(13, 5))
    for k, pol in enumerate(POLICY_ORDER):
        ax[0].bar(x + (k - 1.5) * w, [comp.get((c, pol), 0) for c in cells], w,
                  label=pol, color=COLOR[pol])
        ax[1].bar(x + (k - 1.5) * w, [welf.get((c, pol), 0) for c in cells], w,
                  label=pol, color=COLOR[pol])
    for a, title, ylab in ((ax[0], "Task completion rate", "mean completion"),
                           (ax[1], "Declared weighted-log welfare", "declared welfare")):
        a.set_title(title)
        a.set_ylabel(ylab)
        a.set_xticks(x)
        a.set_xticklabels([c.replace("__", "\n") for c in cells], rotation=30, ha="right", fontsize=7)
        a.grid(axis="y", alpha=0.3)
    ax[0].legend(fontsize=8, ncol=2)
    fig.suptitle("Joint WPF maximises declared welfare but not task completion", fontsize=12)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, "fig1_completion_vs_welfare.png"), dpi=150)
    plt.close(fig)


def fig_tradeoff(paired):
    # joint - separable: declared welfare (x) vs completion (y), with 95% CIs.
    byc = {}
    for r in paired:
        if r["comparison"] != "joint_minus_separable":
            continue
        byc.setdefault(r["cell"], {})[r["metric"]] = r
    fig, ax = plt.subplots(figsize=(7.5, 6))
    for cell, d in byc.items():
        if "declared_welfare" not in d or "completion_mean" not in d:
            continue
        x = float(d["declared_welfare"]["mean_diff"])
        y = float(d["completion_mean"]["mean_diff"])
        xerr = [[x - float(d["declared_welfare"]["ci_lo"])],
                [float(d["declared_welfare"]["ci_hi"]) - x]]
        yerr = [[y - float(d["completion_mean"]["ci_lo"])],
                [float(d["completion_mean"]["ci_hi"]) - y]]
        ax.errorbar(x, y, xerr=xerr, yerr=yerr, fmt="o", color="#D55E00", capsize=3)
        ax.annotate(cell.replace("__", "/"), (x, y), fontsize=7,
                    xytext=(4, 4), textcoords="offset points")
    ax.axhline(0, color="grey", lw=0.8)
    ax.axvline(0, color="grey", lw=0.8)
    ax.set_xlabel("declared welfare: joint - separable (95% CI)")
    ax.set_ylabel("task completion: joint - separable (95% CI)")
    ax.set_title("Welfare gains do not translate into task-completion gains")
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, "fig2_welfare_vs_completion_tradeoff.png"), dpi=150)
    plt.close(fig)


def fig_individual(indiv):
    cells = sorted({r["cell"] for r in indiv})
    def key(c):
        reg = c.rsplit("__", 1)[0]
        return (CELL_ORDER.index(reg) if reg in CELL_ORDER else 99, c.rsplit("__", 1)[1])
    cells = sorted(cells, key=key)
    policies = ["joint", "separable", "drf"]
    idx = {(r["cell"], r["policy"]): r for r in indiv}
    x = np.arange(len(cells))
    w = 0.25
    fig, ax = plt.subplots(figsize=(12, 5))
    colors = {"joint": "#D55E00", "separable": "#0072B2", "drf": "#CC79A7"}
    for k, pol in enumerate(policies):
        vals = [float(idx[(c, pol)]["worst_agent_loss_vs_equal"]) if (c, pol) in idx else 0
                for c in cells]
        ax.bar(x + (k - 1) * w, vals, w, label=pol + " (worst agent)", color=colors[pol])
    ax.set_xticks(x)
    ax.set_xticklabels([c.replace("__", "\n") for c in cells], rotation=30, ha="right", fontsize=7)
    ax.set_ylabel("worst per-agent completion change vs equal quotas")
    ax.axhline(0, color="grey", lw=0.8)
    ax.set_title("Individual-agent harm relative to equal quotas (more negative = worse)")
    ax.legend(fontsize=8)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, "fig3_individual_loss.png"), dpi=150)
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="full")
    args = ap.parse_args()
    os.makedirs(FIG, exist_ok=True)
    rows = load_runs(args.mode)
    fig_completion_and_welfare(rows)
    fig_tradeoff(load_paired())
    fig_individual(load_individual())
    print("wrote 3 figures to", FIG)


if __name__ == "__main__":
    main()
