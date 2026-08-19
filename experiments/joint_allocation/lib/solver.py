import os
import sys

_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
_SCRIPTS = os.path.join(_ROOT, "scripts")
if _SCRIPTS not in sys.path:
    sys.path.insert(0, _SCRIPTS)

import joint_solver  # noqa: E402


def solve(instance, utility_configs):
    data = {
        "n_agents": instance.n,
        "n_resources": instance.m,
        "preferences": instance.W.tolist(),
        "priority_weights": instance.c.tolist(),
        "capacities": instance.Q.tolist(),
        "minimums": instance.mins.tolist(),
        "ideals": instance.ideals.tolist(),
        "utility_configs": utility_configs,
    }
    return joint_solver.solve_joint_allocation(data)


def eval_utility(cfg, w, a):
    return joint_solver.eval_utility_np(cfg, w, a)
