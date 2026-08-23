#!/usr/bin/env python3
import os
import sys

DEFAULT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

FORBIDDEN = [
    "PASS: All 11 utility types",
    "All 11 utility types working correctly",
    "outside option = minimum request",
    "Joint optimization achieves global Pareto optimality",
    "maximum Pareto optimality",
    "Estimated optimization speedup",
    "Estimated optimality loss",
    "OptLoss",
    "getEstimatedOptimalityLoss",
    "getPerformanceImprovementFactor",
]

FORBIDDEN_LOWER = [
    "golden ratio",
    "golden-ratio",
    "38.2% conjecture",
]

README_CANON_LOWER = ["golden ratio", "golden-ratio", "38.2%"]

SCAN_EXTS = (".java", ".py", ".md", ".json", ".txt", ".yml", ".yaml")
SKIP_DIRS = {".git", "target", "__pycache__", "experiments_venv_tmp", "node_modules"}
SELF = os.path.basename(__file__)


def walk_files(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn.endswith(SCAN_EXTS):
                yield os.path.join(dirpath, fn)


def scan_text(root):
    issues = []
    for path in walk_files(root):
        rel = os.path.relpath(path, root)
        base = os.path.basename(path)
        if base in (SELF, "check_consistency.py"):
            continue
        try:
            text = open(path, encoding="utf-8", errors="ignore").read()
        except OSError:
            continue
        lower = text.lower()
        for phrase in FORBIDDEN:
            if phrase in text:
                issues.append("%s: forbidden phrase '%s'" % (rel, phrase))
        for phrase in FORBIDDEN_LOWER:
            if phrase in lower:
                issues.append("%s: forbidden phrase '%s'" % (rel, phrase))
        if base == "README.md":
            for phrase in README_CANON_LOWER:
                if phrase in lower:
                    issues.append("README golden-ratio/38.2%% reference: '%s'" % phrase)
    return issues


def scan_demo(root):
    issues = []
    demo = os.path.join(root, "src", "main", "java", "org", "carma", "arbitration", "Demo.java")
    if not os.path.exists(demo):
        return ["Demo.java missing"]
    text = open(demo).read()
    for fam in ("LINEAR", "COBB_DOUGLAS", "CES", "LEONTIEF"):
        if fam not in text:
            issues.append("Demo.java does not mention solver family %s" % fam)
    if "rejected by the canonical solver" not in text and "rejects any other family" not in text:
        issues.append("Demo.java does not describe unsupported families as rejected")
    if "collusion resistance" in text.lower() and "not a collusion" not in text.lower():
        issues.append("Demo.java presents collusion resistance without a negation")
    if "individual rationality" in text.lower() and "not individual rationality" not in text.lower():
        issues.append("Demo.java presents individual rationality without a negation")
    return issues


def scan_grouping(root):
    issues = []
    demo = os.path.join(root, "src", "main", "java", "org", "carma", "arbitration", "GroupingPolicyDemo.py")
    demo_java = os.path.join(root, "src", "main", "java", "org", "carma", "arbitration", "GroupingPolicyDemo.java")
    path = demo_java if os.path.exists(demo_java) else demo
    if os.path.exists(path):
        text = open(path).read()
        if "measured speedup" in text.lower():
            issues.append("GroupingPolicyDemo claims measured speedup")
        if "proxy" not in text.lower():
            issues.append("GroupingPolicyDemo grouping output does not say proxy")
    return issues


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ROOT
    issues = scan_text(root) + scan_demo(root) + scan_grouping(root)
    if issues:
        print("CLAIM SCAN FAILED (%d issues) in %s:" % (len(issues), root))
        for i in issues:
            print("  - " + i)
        sys.exit(1)
    print("claim scan passed: no stale claims in %s" % root)


if __name__ == "__main__":
    main()
