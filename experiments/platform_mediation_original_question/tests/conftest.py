import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
EXP = os.path.abspath(os.path.join(HERE, ".."))
if EXP not in sys.path:
    sys.path.insert(0, EXP)

import oqlib  # noqa: E402,F401  (puts pilotlib, lib, scripts, repo root on sys.path)


def solver_available():
    """True if the Java harness classpath and a cvxpy solver-python are available."""
    root = os.path.abspath(os.path.join(EXP, "..", ".."))
    cp = os.path.join(root, "cp.txt")
    classes = os.path.join(root, "target", "classes")
    sp = os.environ.get("SOLVER_PYTHON")
    return os.path.exists(cp) and os.path.isdir(classes) and bool(sp)
