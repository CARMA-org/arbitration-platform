import numpy as np
import pandas as pd

SUMMARY_METRICS = [
    "objective_regret", "mean_norm_util", "min_norm_util", "median_norm_util",
    "frac_worse_than_equal", "frac_worse_than_strongest_sep",
    "worst_indiv_change_vs_equal", "p5_indiv_change_vs_equal",
    "capacity_utilization", "frac_cells_at_lower", "frac_cells_interior",
    "frac_cells_at_upper",
]


def summarize(series):
    v = np.asarray(series, dtype=float)
    v = v[np.isfinite(v)]
    if v.size == 0:
        return dict(median=np.nan, iqr_lo=np.nan, iqr_hi=np.nan, p5=np.nan, p95=np.nan, mean=np.nan, n=0)
    return dict(
        median=float(np.median(v)), iqr_lo=float(np.percentile(v, 25)),
        iqr_hi=float(np.percentile(v, 75)), p5=float(np.percentile(v, 5)),
        p95=float(np.percentile(v, 95)), mean=float(np.mean(v)), n=int(v.size))


def aggregate(raw_csv, group_cols=("cell", "rule")):
    df = pd.read_csv(raw_csv)
    records = []
    for keys, grp in df.groupby(list(group_cols)):
        if not isinstance(keys, tuple):
            keys = (keys,)
        base = dict(zip(group_cols, keys))
        for metric in SUMMARY_METRICS:
            if metric not in grp:
                continue
            s = summarize(grp[metric])
            rec = dict(base)
            rec["metric"] = metric
            rec.update(s)
            records.append(rec)
    return pd.DataFrame(records)
