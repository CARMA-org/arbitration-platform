#!/bin/bash

# Arbitration Platform Runner
# Usage:
#   ./run.sh              Run validation scenarios
#   ./run.sh --full       Run validation + asymptotic test
#   ./run.sh --asymptotic Run only asymptotic test

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src/main/java"
OUT_DIR="$SCRIPT_DIR/out"

# Check for Java
if ! command -v java &> /dev/null; then
    echo "Error: Java not found. Please install Java 21+."
    exit 1
fi

# Check for javac
if ! command -v javac &> /dev/null; then
    echo "Error: javac not found. Please install JDK 21+."
    echo "On Mac: brew install openjdk@21"
    exit 1
fi

# Clean and create output directory
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Find all Java files
echo "Compiling..."
find "$SRC_DIR" -name "*.java" -print0 | xargs -0 javac -d "$OUT_DIR"

echo "Running..."
echo ""
java -cp "$OUT_DIR" org.carma.arbitration.Demo "$@"
