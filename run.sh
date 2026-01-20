#!/bin/bash

# Arbitration Platform Runner
# Usage:
#   ./run.sh              Run all demos (complete demonstration)
#   ./run.sh --quick      Run only main validation scenarios
#   ./run.sh --agents     Run only realistic agent demo
#   ./run.sh --safety     Run only safety monitoring demos
#   ./run.sh --services   Run only service composition demo

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src/main/java"
OUT_DIR="$SCRIPT_DIR/out"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_header() {
    echo ""
    echo -e "${BLUE}════════════════════════════════════════════════════════════════════════${NC}"
    echo -e "${YELLOW}  $1${NC}"
    echo -e "${BLUE}════════════════════════════════════════════════════════════════════════${NC}"
    echo ""
}

# Check for Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}Error: Java not found. Please install Java 21+.${NC}"
    exit 1
fi

# Check for javac
if ! command -v javac &> /dev/null; then
    echo -e "${RED}Error: javac not found. Please install JDK 21+.${NC}"
    echo "On Mac: brew install openjdk@21"
    exit 1
fi

# Clean and create output directory
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Find all Java files
echo -e "${GREEN}Compiling...${NC}"
find "$SRC_DIR" -name "*.java" -print0 | xargs -0 javac -d "$OUT_DIR" 2>&1 | grep -v "^Note:" || true

# Parse arguments
MODE="${1:-all}"

run_demo() {
    local class=$1
    local name=$2
    print_header "$name"
    java -cp "$OUT_DIR" "$class"
}

case "$MODE" in
    --quick)
        echo -e "${GREEN}Running quick validation...${NC}"
        run_demo "org.carma.arbitration.Demo" "CORE ARBITRATION MECHANISM"
        ;;
    --agents)
        echo -e "${GREEN}Running realistic agent demo...${NC}"
        run_demo "org.carma.arbitration.demo.RealisticAgentDemo" "REALISTIC AGENT DEMONSTRATION"
        ;;
    --services)
        echo -e "${GREEN}Running service composition demo...${NC}"
        run_demo "org.carma.arbitration.ServiceDemo" "AI SERVICE INTEGRATION"
        ;;
    --safety)
        echo -e "${GREEN}Running safety demos...${NC}"
        run_demo "org.carma.arbitration.safety.ServiceCompositionAnalyzer" "SERVICE COMPOSITION SAFETY"
        run_demo "org.carma.arbitration.safety.ConfigurationValidator" "CONFIGURATION VALIDATION"
        ;;
    --nonlinear)
        echo -e "${GREEN}Running nonlinear utility demo...${NC}"
        run_demo "org.carma.arbitration.NonlinearUtilityDemo" "NONLINEAR UTILITY FUNCTIONS"
        ;;
    all|*)
        echo -e "${GREEN}Running complete demonstration suite...${NC}"
        echo ""

        # Demo 1: Core Arbitration
        run_demo "org.carma.arbitration.Demo" "DEMO 1: CORE ARBITRATION MECHANISM"

        # Demo 2: Nonlinear Utilities
        run_demo "org.carma.arbitration.NonlinearUtilityDemo" "DEMO 2: NONLINEAR UTILITY FUNCTIONS"

        # Demo 3: Service Integration
        run_demo "org.carma.arbitration.ServiceDemo" "DEMO 3: AI SERVICE INTEGRATION"

        # Demo 4: Realistic Agents (the key milestone 3 demo)
        run_demo "org.carma.arbitration.demo.RealisticAgentDemo" "DEMO 4: REALISTIC AGENT FRAMEWORK"

        # Demo 5: Service Composition Safety
        run_demo "org.carma.arbitration.safety.ServiceCompositionAnalyzer" "DEMO 5: SERVICE COMPOSITION SAFETY ANALYSIS"

        # Demo 6: Configuration Validation
        run_demo "org.carma.arbitration.safety.ConfigurationValidator" "DEMO 6: CONFIGURATION VALIDATION"

        # Final summary
        print_header "ALL DEMONSTRATIONS COMPLETE"
        echo -e "${GREEN}✓ Demo 1: Core Arbitration Mechanism - Weighted Proportional Fairness${NC}"
        echo -e "${GREEN}✓ Demo 2: Nonlinear Utility Functions - 11 utility types${NC}"
        echo -e "${GREEN}✓ Demo 3: AI Service Integration - Service compositions${NC}"
        echo -e "${GREEN}✓ Demo 4: Realistic Agent Framework - 6 implementable agents${NC}"
        echo -e "${GREEN}✓ Demo 5: Service Composition Safety - Depth limits, pattern detection${NC}"
        echo -e "${GREEN}✓ Demo 6: Configuration Validation - Load-time safety checks${NC}"
        echo ""
        echo -e "${YELLOW}Key Milestone 3 deliverables demonstrated:${NC}"
        echo "  • Realistic, implementable agents (not just scenarios)"
        echo "  • NewsSearchAgent example (low autonomy, narrow scope)"
        echo "  • AGI emergence monitoring (A+G+I conjunction detection)"
        echo "  • Service composition safety analysis"
        echo "  • Configuration validation at load time"
        echo ""
        echo -e "${YELLOW}To integrate REAL LLMs:${NC}"
        echo "  1. Set OPENAI_API_KEY or ANTHROPIC_API_KEY environment variable"
        echo "  2. Use LLMServiceBackend instead of MockServiceBackend"
        echo "  3. See LLMServiceBackend.java for integration example"
        echo ""
        ;;
esac
