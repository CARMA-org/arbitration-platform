import argparse
import os
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "results", "raw")
FIG = os.path.join(HERE, "figures")


def _tag(smoke):
    return "smoke" if smoke else "full"


def fig_exp1(tag):
    path = os.path.join(RAW, f"experiment1_{tag}.csv")
    if not os.path.exists(path):
        return
    df = pd.read_csv(path)
    df["alpha"] = df["cell"].str.replace("alpha=", "").astype(float)
    rules = ["proportional_gamma1", "oracle_envelope", "joint"]
    tuned = [r for r in df["rule"].unique() if r.startswith("tuned_gamma")]
    rules = ["proportional_gamma1"] + tuned + ["oracle_envelope"]
    plt.figure(figsize=(7, 4.5))
    for rule in rules:
        sub = df[df["rule"] == rule].groupby("alpha")["objective_regret"].median()
        plt.plot(sub.index, sub.values, marker="o", label=rule)
    plt.xscale("log")
    plt.xlabel("Dirichlet concentration alpha")
    plt.ylabel("median objective regret vs joint (weighted log)")
    plt.title(f"Experiment 1: separable-rule regret ({tag})")
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(FIG, f"experiment1_regret_{tag}.png"), dpi=120)
    plt.close()


def fig_exp2_heatmap(tag):
    path = os.path.join(RAW, f"experiment2_{tag}.csv")
    if not os.path.exists(path):
        return
    df = pd.read_csv(path)
    tuned = [r for r in df["rule"].unique() if r.startswith("tuned_gamma")][0]
    sub = df[df["rule"] == tuned].copy()
    sub["B"] = sub["cell"].str.extract(r"B=([\d.]+)").astype(float)
    sub["lam"] = sub["cell"].str.extract(r"lam=([\d.]+)").astype(float)
    piv = sub.groupby(["B", "lam"])["objective_regret"].median().reset_index()
    Bs = sorted(piv["B"].unique())
    lams = sorted(piv["lam"].unique())
    grid = np.full((len(Bs), len(lams)), np.nan)
    for _, row in piv.iterrows():
        grid[Bs.index(row["B"]), lams.index(row["lam"])] = row["objective_regret"]
    plt.figure(figsize=(6.5, 5))
    im = plt.imshow(grid, aspect="auto", origin="lower", cmap="viridis")
    plt.colorbar(im, label="median objective regret vs joint")
    plt.xticks(range(len(lams)), lams)
    plt.yticks(range(len(Bs)), Bs)
    plt.xlabel("asymmetry lambda")
    plt.ylabel("breadth B")
    plt.title(f"Experiment 2: tuned separable regret ({tag})")
    for i in range(len(Bs)):
        for j in range(len(lams)):
            if np.isfinite(grid[i, j]):
                plt.text(j, i, f"{grid[i, j]:.2f}", ha="center", va="center",
                         color="white", fontsize=8)
    plt.tight_layout()
    plt.savefig(os.path.join(FIG, f"experiment2_heatmap_{tag}.png"), dpi=120)
    plt.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true")
    args = ap.parse_args()
    tag = _tag(args.smoke)
    os.makedirs(FIG, exist_ok=True)
    fig_exp1(tag)
    fig_exp2_heatmap(tag)
    print(f"figures written to {FIG}")


if __name__ == "__main__":
    main()
