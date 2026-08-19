import json
import subprocess
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OLD_SOLVER = os.path.join(HERE, "joint_solver_ORIGINAL.py")

BASE_INSTANCE = {
    "n_agents": 2,
    "n_resources": 2,
    "preferences": [[0.9, 0.1], [0.1, 0.9]],
    "priority_weights": [1.0, 1.0],
    "capacities": [100, 100],
    "minimums": [[1, 1], [1, 1]],
    "ideals": [[100, 100], [100, 100]],
    "resource_names": ["R0", "R1"],
}

ADVERTISED = [
    ("LINEAR", {"type": "LINEAR"}),
    ("SQRT", {"type": "SQRT"}),
    ("LOG", {"type": "LOG"}),
    ("COBB_DOUGLAS", {"type": "COBB_DOUGLAS"}),
    ("CES_rho_0.5", {"type": "CES", "rho": 0.5}),
    ("CES_rho_-1", {"type": "CES", "rho": -1.0}),
    ("LEONTIEF", {"type": "LEONTIEF"}),
    ("THRESHOLD", {"type": "THRESHOLD", "threshold": 50.0, "sharpness": 1.0}),
    ("SATIATION", {"type": "SATIATION", "max_utility": 100.0, "saturation_param": 10.0}),
    ("NESTED_CES", {"type": "NESTED_CES", "nests": [{"R0": 0.5, "R1": 0.5}],
                    "nest_rhos": [0.5], "nest_weights": [1.0], "outer_rho": 0.5}),
    ("SOFTPLUS_LOSS_AVERSION", {"type": "SOFTPLUS_LOSS_AVERSION",
                                "weights": {"R0": 0.9, "R1": 0.1},
                                "reference_points": {"R0": 10.0, "R1": 10.0},
                                "lambda": 2.0, "tau": 1.0}),
    ("ASYMMETRIC_LOG_LOSS_AVERSION", {"type": "ASYMMETRIC_LOG_LOSS_AVERSION",
                                      "weights": {"R0": 0.9, "R1": 0.1},
                                      "reference_points": {"R0": 10.0, "R1": 10.0},
                                      "lambda": 2.0, "kappa": 10.0}),
]


def run_old_solver(payload):
    proc = subprocess.run(
        [sys.executable, OLD_SOLVER],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
    )
    return proc


def capacity_utilization(allocations, capacities):
    if allocations is None:
        return None
    n = len(allocations)
    m = len(capacities)
    util = []
    for j in range(m):
        col = sum(allocations[i][j] for i in range(n))
        util.append(col / capacities[j] if capacities[j] else None)
    return util


def main():
    records = []
    for name, cfg in ADVERTISED:
        payload = dict(BASE_INSTANCE)
        payload["utility_configs"] = [cfg, cfg]
        proc = run_old_solver(payload)
        rec = {
            "requested_model": name,
            "requested_config": cfg,
            "exit_code": proc.returncode,
        }
        parsed = None
        if proc.stdout.strip():
            try:
                parsed = json.loads(proc.stdout)
            except json.JSONDecodeError:
                rec["stdout_unparseable"] = proc.stdout[:500]
        if parsed is not None:
            rec["returned_status"] = parsed.get("status")
            rec["returned_allocation"] = parsed.get("allocations")
            rec["actual_model_used"] = parsed.get("utility_types")
            rec["returned_objective"] = parsed.get("objective")
            rec["returned_utilities"] = parsed.get("utilities")
            rec["capacity_utilization"] = capacity_utilization(
                parsed.get("allocations"), BASE_INSTANCE["capacities"])
        if proc.stderr.strip():
            rec["stderr"] = proc.stderr[:1000]
        records.append(rec)

    with open(os.path.join(HERE, "solver_reproduction.json"), "w") as f:
        json.dump(records, f, indent=2)

    for r in records:
        print(f"{r['requested_model']:32s} status={r.get('returned_status')!s:22s} "
              f"actual_model={r.get('actual_model_used')} "
              f"cap_util={r.get('capacity_utilization')}")


if __name__ == "__main__":
    main()
