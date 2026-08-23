#!/usr/bin/env python3
import os
import re
import sys

DEFAULT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

FORBIDDEN = [
    "PASS: All 11 utility types",
    "All 11 utility types working correctly",
    "all 11 utility types",
    "outside option = minimum request",
    "Joint optimization achieves global Pareto optimality",
    "maximum Pareto optimality",
    "Estimated optimization speedup",
    "Estimated optimality loss",
    "OptLoss",
    "getEstimatedOptimalityLoss",
    "getPerformanceImprovementFactor",
    "PARETO PROPERTY VERIFICATION",
    "Per-Round Pareto Optimality",
    "Rounds verified Pareto optimal",
    "verified Pareto optimal",
    "PARETO OPTIMALITY HOLDS",
    "ALL AGENTS BENEFIT",
    "SACRIFICE WORKS",
    "achieved Pareto optimal",
    "Aggressive burning is optimal",
    "Optimal strategy depends on time horizon",
    "Infinite horizon: Conservative",
    "Pareto-Optimized",
    "faster computation at cost of optimality",
    "complexity reduction when splitting",
    "trade-off between performance and optimality",
    "at cost of optimality",
    "| Optimality |",
]

FORBIDDEN_CASE = [
    "splitBySpectral",
    "approximateFiedlerVector",
    "SPECTRAL",
    "Fiedler",
    "ParetoVerifier",
    "ParetoAnalysisSimulation",
    "LongitudinalParetoDemo",
    "hasNoPairwiseUnitParetoImprovement",
    "isParetoOptimal",
    "getParetoOptimalityRate",
    "paretoOptimalityRate",
    "paretoOptimalityChecks",
]

FORBIDDEN_LOWER = [
    "golden ratio",
    "golden-ratio",
    "38.2% conjecture",
]

README_CANON_LOWER = ["golden ratio", "golden-ratio", "38.2%"]

WITHIN_OPT_RE = re.compile(r"within\s*1\s*[-‐-―]\s*3\s*%\s*of\s*optimal", re.I)
RETENTION_RE = re.compile(r"~\s*\d{1,3}\s*%")
ENUM_SPECTRAL_RE = re.compile(r"^\s*SPECTRAL\s*,?\s*$")
FALLBACK_RE = re.compile(r"fall[s]?\s*back\s*to\s*sequential|fallback\s*to\s*sequential", re.I)
FALLBACK_OK = ("explicit", "fails closed", "only when", "not automatic", "unless", "must be enabled")
FALLBACK_TRIGGER = ("error", "fail", "without", "missing", "depend", "unavailable", "timeout")

CLAIM_TOKENS = [
    "pareto optimal",
    "globally pareto",
    "global pareto",
    "local pareto optimality",
    "optimality loss",
    "speedup",
    "optimal strategy",
    "aggressive burning",
]

NEG_MARKERS = (
    "not", "n't", "never", "without", "cannot", "rather than", "fails closed",
    "only when", "explicitly enabled", "unless", "no longer", "neither", " no ",
    "is not", "are not", "does not", "do not",
)
NEG_WINDOW = 170

SCAN_EXTS = (".java", ".py", ".md", ".json", ".txt", ".yml", ".yaml",
             ".xml", ".properties", ".sh")
SKIP_DIRS = {".git", "target", "__pycache__", "experiments_venv_tmp",
             "node_modules", ".venv", "venv"}
SELF = os.path.basename(__file__)
SKIP_FILES = {SELF, "check_consistency.py"}


def negated_before(lower_text, pos):
    window = lower_text[max(0, pos - NEG_WINDOW):pos]
    return any(m in window for m in NEG_MARKERS)


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
        if base in SKIP_FILES:
            continue
        try:
            text = open(path, encoding="utf-8", errors="ignore").read()
        except OSError:
            continue
        lower = text.lower()
        for phrase in FORBIDDEN:
            if phrase.lower() in lower:
                issues.append("%s: forbidden phrase '%s'" % (rel, phrase))
        for phrase in FORBIDDEN_CASE:
            if phrase in text:
                issues.append("%s: forbidden symbol '%s'" % (rel, phrase))
        for phrase in FORBIDDEN_LOWER:
            if phrase in lower:
                issues.append("%s: forbidden phrase '%s'" % (rel, phrase))
        if base == "README.md":
            for phrase in README_CANON_LOWER:
                if phrase in lower:
                    issues.append("README golden-ratio/38.2%% reference: '%s'" % phrase)
        for ln in text.splitlines():
            low = ln.lower()
            if WITHIN_OPT_RE.search(ln):
                issues.append("%s: '1-3%% of optimal' accuracy claim: %s" % (rel, ln.strip()[:80]))
            if RETENTION_RE.search(ln) and ("optimal" in low or "retention" in low or "retain" in low):
                issues.append("%s: numeric grouping-retention claim: %s" % (rel, ln.strip()[:80]))
            if base.endswith(".java") and ENUM_SPECTRAL_RE.match(ln):
                issues.append("%s: SPECTRAL enum constant present" % rel)
            if (FALLBACK_RE.search(ln) and any(t in low for t in FALLBACK_TRIGGER)
                    and not any(k in low for k in FALLBACK_OK)):
                issues.append("%s: automatic sequential-fallback claim: %s" % (rel, ln.strip()[:80]))
        for tok in CLAIM_TOKENS:
            start = 0
            while True:
                pos = lower.find(tok, start)
                if pos < 0:
                    break
                if not negated_before(lower, pos):
                    snippet = text[max(0, pos - 10):pos + len(tok) + 30].replace("\n", " ")
                    issues.append("%s: unsupported claim token '%s': ...%s..." % (rel, tok, snippet.strip()))
                start = pos + len(tok)
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
    mech = os.path.join(root, "src", "main", "java", "org", "carma", "arbitration", "mechanism")
    demo = os.path.join(root, "src", "main", "java", "org", "carma", "arbitration", "GroupingPolicyDemo.java")
    if os.path.exists(demo):
        text = open(demo).read()
        if "measured speedup" in text.lower():
            issues.append("GroupingPolicyDemo claims measured speedup")
        if "proxy" not in text.lower():
            issues.append("GroupingPolicyDemo grouping output does not say proxy")
    for fn in ("GroupingPolicy.java", "GroupingSplitter.java"):
        p = os.path.join(mech, fn)
        if os.path.exists(p) and "SPECTRAL" in open(p).read():
            issues.append("%s still references SPECTRAL" % fn)
    return issues


def scan_root(root):
    return scan_text(root) + scan_demo(root) + scan_grouping(root)


def main():
    roots = sys.argv[1:] or [DEFAULT_ROOT]
    failed = False
    for root in roots:
        root = os.path.abspath(root)
        issues = scan_root(root)
        if issues:
            failed = True
            print("CLAIM SCAN FAILED (%d issues) in %s:" % (len(issues), root))
            for i in issues:
                print("  - " + i)
        else:
            print("claim scan passed: no stale claims in %s" % root)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
