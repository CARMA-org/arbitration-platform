import hashlib
import json
import os
import platform
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def hash_dir(rel):
    d = os.path.join(HERE, rel)
    out = {}
    if not os.path.isdir(d):
        return out
    for name in sorted(os.listdir(d)):
        p = os.path.join(d, name)
        if os.path.isfile(p):
            out[f"experiments/joint_allocation/{rel}/{name}"] = sha256(p)
    return out


def versions():
    import cvxpy, numpy, scipy, clarabel
    return {"python": sys.version.split()[0], "cvxpy": cvxpy.__version__,
            "numpy": numpy.__version__, "scipy": scipy.__version__,
            "clarabel": clarabel.__version__, "platform": platform.platform(),
            "machine": platform.machine()}


def main():
    manifest = {
        "audited_baseline_commit": "513e1e9217c0965df35cb3c15fbc701c76346b8c",
        "versions": versions(),
        "seed_derivation": "SHA-256 of pipe-joined string labels, truncated to 32 bits "
                           "(experiments/joint_allocation/lib/seeds.py). Train and test "
                           "seed sets are disjoint by construction.",
        "configurations": {
            "rounding_comparison": {"n_agents": 6, "n_resources": 3, "cap": 100,
                                    "lb": 1, "ub": 100,
                                    "dirichlet_alphas": [0.1, 0.3, 1.0, 3.0, 10.0],
                                    "per_alpha": 200},
            "experiment1": {"n_agents": 6, "n_resources": 3, "cap": 100, "lb": 1, "ub": 100,
                            "dirichlet_alphas": [0.1, 0.3, 1.0, 3.0, 10.0],
                            "n_train_per_cell": 30, "n_test_per_cell": 100},
            "experiment2": {"n_agents": 8, "n_resources": 4, "cap": 100, "lb": 1, "ub": 100,
                            "breadths": [1.3, 2.0, 3.0, 3.8],
                            "lambdas": [0.0, 0.25, 0.5, 0.75, 1.0],
                            "n_train_per_cell": 40, "n_test_per_cell": 100},
            "experiment3": {"n_agents": 8, "n_resources": 4, "cap": 100, "lb": 1, "ub": 100,
                            "breadths": [1.5, 3.0, 3.8], "lambdas": [0.25, 0.75, 1.0],
                            "families": ["COBB_DOUGLAS", "CES_0.5", "LINEAR", "LEONTIEF"],
                            "omitted": ["CES_-1"],
                            "n_train_per_cell": 20, "n_test_per_cell": 50},
            "experiment4": {"n_agents": 8, "n_resources": 4, "cap": 100,
                            "representative_cell": {"breadth": 3.0, "lambda": 0.5},
                            "h_over_q": [1.0, 0.5, 0.25], "l_over_q": [0.0, 0.01, 0.05],
                            "priority_lognormal_s": [0.0, 0.5, 1.0],
                            "n_train_per_cell": 20, "n_test_per_cell": 60},
            "experiment5": {"n_incumbents": 6, "n_resources": 4, "cap": 100,
                            "representative_cell": {"breadth": 3.0, "lambda": 0.5},
                            "events": ["drift", "arrival", "capacity"], "n_seeds": 100},
        },
        "gamma_grid": [0.0, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0],
        "commands": {
            "install": "pip install -r experiments/joint_allocation/requirements.txt",
            "python_tests": "python -m pytest tests/python -q",
            "java_tests": "SOLVER_PYTHON=<python-with-cvxpy> mvn test",
            "smoke": "cd experiments/joint_allocation && python run_all.py --smoke",
            "full": "cd experiments/joint_allocation && python run_all.py",
        },
        "hashes": {},
    }
    manifest["hashes"].update(hash_dir("tables"))
    for name in sorted(os.listdir(os.path.join(HERE, "results"))):
        if name.endswith(".json"):
            p = os.path.join(HERE, "results", name)
            manifest["hashes"][f"experiments/joint_allocation/results/{name}"] = sha256(p)

    with open(os.path.join(ROOT, "EXPERIMENT_MANIFEST.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print("wrote EXPERIMENT_MANIFEST.json with", len(manifest["hashes"]), "hashes")


if __name__ == "__main__":
    main()
