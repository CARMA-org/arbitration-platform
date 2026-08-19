import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
for p in (os.path.join(ROOT, "scripts"),
          os.path.join(ROOT, "experiments", "joint_allocation")):
    if p not in sys.path:
        sys.path.insert(0, p)
