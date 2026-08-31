import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PILOT = os.path.abspath(os.path.join(HERE, ".."))
if PILOT not in sys.path:
    sys.path.insert(0, PILOT)

import pilotlib  # noqa: F401,E402  importing sets up the canonical `lib` path

import pytest  # noqa: E402


def _solver_available():
    sp = os.environ.get("SOLVER_PYTHON")
    if not sp or not os.path.exists(sp):
        return None
    root = pilotlib._ROOT
    if not os.path.exists(os.path.join(root, "cp.txt")):
        return None
    if not os.path.exists(os.path.join(root, "target", "classes",
                                        "org", "carma", "arbitration",
                                        "experiment", "PlatformMediationHarness.class")):
        return None
    return sp


@pytest.fixture(scope="module")
def live_workload_sweep(tmp_path_factory):
    """Run a small live workload sweep through the canonical Java harness into a
    temporary directory. Skips when the solver/classpath are unavailable."""
    sp = _solver_available()
    if sp is None:
        pytest.skip("SOLVER_PYTHON / cp.txt / compiled harness not available")

    import run_pilot

    tmp = tmp_path_factory.mktemp("pilot_live")
    raw = os.path.join(tmp, "raw")
    os.makedirs(raw, exist_ok=True)

    cfg = run_pilot.load_config()
    cfg = dict(cfg)
    cfg["n_dev_seeds"] = 2
    cfg["workload_regimes"] = [r for r in cfg["workload_regimes"]
                               if r["name"] in ("iid_uniform", "dirichlet_0.03")]

    saved = (run_pilot.RESULTS, run_pilot.RAW, run_pilot.LOGS)
    run_pilot.RESULTS = str(tmp)
    run_pilot.RAW = raw
    run_pilot.LOGS = str(tmp)
    try:
        summary = run_pilot.run_one_sweep(cfg, "workload", sp, lambda m: None)
    finally:
        run_pilot.RESULTS, run_pilot.RAW, run_pilot.LOGS = saved
    return {"dir": str(tmp), "raw": raw, "cfg": cfg, "summary": summary}


@pytest.fixture(scope="module")
def solver_python():
    sp = _solver_available()
    if sp is None:
        import pytest as _p
        _p.skip("SOLVER_PYTHON / cp.txt / compiled harness not available")
    return sp
