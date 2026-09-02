"""Job construction helpers shared by the architecture and drift drivers.

Every arm is enforced through the canonical Java runtime. Native policies (``equal``,
``drf``, ``joint_leontief``) are computed inside the harness from the agent
declarations; the resource-local and distributed mechanisms are computed in Python and
installed verbatim through the harness ``preinstalledAllocation`` field, which routes
them through the identical ``installContracts`` + execution path.
"""
import numpy as np

from lib import scenario as canon
from lib.archetypes import RESOURCES

# The canonical joint solver receives priority_weights = PriorityEconomy.BASE_WEIGHT
# (currency burn is zero), so the central Leontief reference and the distributed solver
# use the same constant weight; with equal weights the allocation is weight-invariant.
BASE_WEIGHT = 10.0


def scenario_arrays(sc):
    """Extract (R, Q, mn, up, c) float arrays from a scenario built by
    ``pilot_scenario.build_scenario`` (or the drift scenario builder). R is the declared
    Leontief requirement matrix, mn/up the integer floors/upper bounds, Q the capacities,
    c the equal priority weights."""
    ags = sc["agents"]
    n = len(ags)
    m = len(RESOURCES)
    R = np.zeros((n, m))
    mn = np.zeros((n, m))
    up = np.zeros((n, m))
    for i, a in enumerate(ags):
        for j, r in enumerate(RESOURCES):
            R[i, j] = a["leontief_req"][r]
            mn[i, j] = a["min"][r]
            up[i, j] = a["upper"][r]
    Q = np.array([sc["capacities"][r] for r in RESOURCES], float)
    c = np.full(n, BASE_WEIGHT)
    return R, Q, mn, up, c


def make_native_job(sc, cell, seed, policy, solver_python, execute=True):
    """A job for a policy computed inside the harness (equal, drf, joint_leontief)."""
    return canon.make_job(sc, cell, seed, policy, solver_python, execute)


def make_preinstalled_job(sc, cell, seed, policy, alloc, solver_python, execute=True):
    """A job whose integer allocation was computed in Python and is installed verbatim
    through the canonical runtime contract path. ``alloc`` is a list (per agent, in agent
    order) of resource->int dicts."""
    job = canon.make_job(sc, cell, seed, policy, solver_python, execute)
    n = len(sc["agents"])
    job["preinstalledAllocation"] = [
        {r: int(alloc[i][r]) for r in RESOURCES} for i in range(n)]
    return job
