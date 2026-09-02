#!/usr/bin/env bash
# Full clean-clone reproduction for verification. Clones the repository with
# --no-hardlinks into a fresh directory, checks out the exact final experimental source
# revision, rebuilds the Java classes and solver classpath, reruns the architecture and
# drift experiments, their analyses, the carrier selection and the manifests, and
# compares every non-timing field to the committed results.
#
# Usage: clean_clone_reproduce.sh <final_experimental_head> <solver_python_abs_path>
set -euo pipefail

HEAD_REV="$1"
SOLVER="$2"
ORIG="$(cd "$(dirname "$0")/../.." && pwd)"
CLONE="$(mktemp -d)/arb_clone"
OQ="experiments/platform_mediation_original_question"

echo "cloning $ORIG -> $CLONE (--no-hardlinks)"
git clone --no-hardlinks "$ORIG" "$CLONE"
cd "$CLONE"
git checkout -q "$HEAD_REV"

echo "building Java classes and solver classpath"
mvn -q -DskipTests package
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt

export SOLVER_PYTHON="$SOLVER"
export OQ_PREREG_COMMIT="$(git rev-list --max-parents=0 HEAD >/dev/null 2>&1; echo reproduce)"
cd "$CLONE/$OQ"
echo "rerunning architecture"
"$SOLVER" run_architecture.py --solver-python "$SOLVER"
"$SOLVER" make_oq_analysis.py architecture
"$SOLVER" select_drift_carrier.py
echo "rerunning drift"
"$SOLVER" run_declaration_drift.py --solver-python "$SOLVER"
"$SOLVER" make_oq_analysis.py drift
"$SOLVER" make_oq_manifest.py architecture
"$SOLVER" make_oq_manifest.py drift

echo "comparing to committed results"
"$SOLVER" "$ORIG/$OQ/compare_reproduction.py" "$ORIG/$OQ" "$CLONE/$OQ"
echo "CLONE_DIR=$CLONE"
