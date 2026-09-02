#!/usr/bin/env bash
# Assemble the original-question-closure verification bundle outside the repo worktree.
# Produces arb_original_question_closure_bundle.zip and .sha256 in a staging directory,
# then reports the absolute path, byte size and SHA-256. The zip and git bundle are never
# tracked in the repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OQ="experiments/platform_mediation_original_question"
STAGE="$(mktemp -d)"
BUNDLE_DIR="$STAGE/arb_original_question_closure_bundle"
mkdir -p "$BUNDLE_DIR"

cd "$ROOT"

# 1. Git bundle with the experimental and verification branches.
git bundle create "$BUNDLE_DIR/original_question_closure.gitbundle" \
  platform-original-question-closure verification/platform-original-question-closure

# 2. Preregistration, configs, source, tests, audit, distributed derivation.
mkdir -p "$BUNDLE_DIR/source"
cp -R "$OQ/oqlib" "$OQ/config" "$OQ/tests" "$BUNDLE_DIR/source/"
cp "$OQ"/*.py "$OQ"/*.md "$OQ"/*.json "$BUNDLE_DIR/source/" 2>/dev/null || true

# 3. Raw data, carrier decision, manifests, reports.
mkdir -p "$BUNDLE_DIR/results"
cp -R "$OQ/results/architecture_v1" "$BUNDLE_DIR/results/" 2>/dev/null || true
cp -R "$OQ/results/drift_v1" "$BUNDLE_DIR/results/" 2>/dev/null || true
# drop the resumable partial caches from the bundle copy
rm -rf "$BUNDLE_DIR/results/architecture_v1/_partial" "$BUNDLE_DIR/results/drift_v1/_partial" 2>/dev/null || true

# 4. Branch and commit inventory.
{
  echo "# Branch and commit inventory";
  echo;
  for b in platform-original-question-closure verification/platform-original-question-closure \
           platform-evaluation main; do
    echo "$b local:  $(git rev-parse "$b" 2>/dev/null || echo missing)";
    echo "$b remote: $(git ls-remote origin "refs/heads/$b" 2>/dev/null | awk '{print $1}')";
  done;
} > "$BUNDLE_DIR/BRANCH_INVENTORY.txt"

# 5. Zip and hash.
OUTZIP="$STAGE/arb_original_question_closure_bundle.zip"
( cd "$STAGE" && zip -qr "$OUTZIP" "arb_original_question_closure_bundle" )
( cd "$STAGE" && shasum -a 256 "arb_original_question_closure_bundle.zip" > "arb_original_question_closure_bundle.zip.sha256" )

# 6. Copy to a durable path in the repository's parent directory (not tracked).
DEST="$(cd "$ROOT/.." && pwd)"
cp "$OUTZIP" "$DEST/"
cp "$STAGE/arb_original_question_closure_bundle.zip.sha256" "$DEST/"

echo "BUNDLE_PATH=$DEST/arb_original_question_closure_bundle.zip"
echo "BUNDLE_BYTES=$(wc -c < "$DEST/arb_original_question_closure_bundle.zip" | tr -d ' ')"
echo "BUNDLE_SHA256=$(shasum -a 256 "$DEST/arb_original_question_closure_bundle.zip" | awk '{print $1}')"
