"""Central Leontief continuous reference: the canonical ``scripts/joint_solver.py``
called in-process for LEONTIEF agents. This is the same solver the ``joint_leontief``
architecture arm invokes through the Java runtime, so the distributed price solver is
compared against the actual central solver's continuous objective and allocation.

This module is used only by the drivers and the analysis to obtain the central
objective. The distributed solver in ``distributed.py`` never imports it: the
distributed computation calls no central solver.
"""
import importlib.util
import os

import numpy as np

_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
_SPEC = importlib.util.spec_from_file_location(
    "oq_joint_solver", os.path.join(_ROOT, "scripts", "joint_solver.py"))
_JS = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_JS)


def central_leontief_reference(R, Q, mn, up, c):
    """Return the canonical joint solver result for the LEONTIEF weighted-proportional-
    fairness problem. Result dict includes ``status``, ``objective_value`` and
    ``allocations`` (n x m continuous)."""
    R = np.asarray(R, float)
    n, m = R.shape
    data = {
        "n_agents": int(n), "n_resources": int(m),
        "preferences": R.tolist(),
        "priority_weights": list(map(float, c)),
        "capacities": list(map(float, Q)),
        "minimums": np.asarray(mn, float).tolist(),
        "ideals": np.asarray(up, float).tolist(),
        "utility_configs": [{"type": "LEONTIEF", "requirements": R[i].tolist()} for i in range(n)],
    }
    return _JS.solve_joint_allocation(data)
