# Joint allocation experiment harness

Self-contained harness comparing allocation rules under the weighted-log objective.
See `../../docs/EXPERIMENTS.md` for the full description and `../../docs/REPRODUCIBILITY.md`
for environment and commands.

## Quick start

```bash
pip install -r requirements.txt
python run_all.py --smoke      # fast pass (small seed counts)
python run_all.py              # full pass
python make_manifest.py        # regenerate ../../EXPERIMENT_MANIFEST.json
```

## Layout

- `lib/` — solver wrapper, instance generators, allocation rules, metrics, rounding, seeds.
- `run_experiment{1..5}.py` — per-experiment drivers (`--smoke` for a fast pass).
- `run_rounding_comparison.py` — old vs new rounding over 1000 linear instances.
- `run_all.py` — runs everything plus figures.
- `figures.py` — Experiment 1 regret curve and Experiment 2 heatmap.
- `tables/` — aggregated CSV tables (committed).
- `results/` — summary JSON (committed) and `raw/` per-instance CSV (gitignored).
- `figures/` — generated PNGs.

Randomness is derived deterministically in `lib/seeds.py`; training and test seeds are
disjoint. The comparator exponent `gamma` is tuned on training seeds and evaluated on
test seeds only.
