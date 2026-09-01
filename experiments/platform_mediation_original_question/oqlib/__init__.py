"""Original-question-closure library.

Importing this package makes the heterogeneity pilot library (``pilotlib``) and the
canonical platform-mediation library (``lib``) importable, and puts the repository
root on the path for ``scripts``. It reuses the validated archetypes, seed
machinery, capacity-preserving rounding, dissimilarity measures, and the Java-runtime
job runner without modifying any existing file.
"""
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_HET = os.path.abspath(os.path.join(_HERE, "..", "..", "platform_mediation_heterogeneity"))
_CANON = os.path.abspath(os.path.join(_HERE, "..", "..", "platform_mediation"))
_ROOT = os.path.abspath(os.path.join(_HERE, "..", "..", ".."))

for _p in (_HET, _CANON, _ROOT):
    if _p not in sys.path:
        sys.path.insert(0, _p)
