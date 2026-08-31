"""Workload-heterogeneity pilot library.

Importing this package makes the canonical platform-mediation library importable
as ``lib`` (and the repository root importable for ``scripts``). The pilot reuses
the canonical archetypes, seed machinery, capacity-preserving rounding, and the
Java-runtime job runner without modifying any canonical file.
"""
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_CANON = os.path.abspath(os.path.join(_HERE, "..", "..", "platform_mediation"))
_ROOT = os.path.abspath(os.path.join(_HERE, "..", "..", ".."))

for _p in (_CANON, _ROOT):
    if _p not in sys.path:
        sys.path.insert(0, _p)
