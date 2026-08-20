#!/usr/bin/env python3
"""A solver stand-in that stalls briefly and then reports a solver timeout error.

Emulates a solver that exceeds its own time budget and gives up rather than
returning an allocation, so the arbitrator must fail closed.
"""
import json
import sys
import time

sys.stdin.read()
time.sleep(1.0)
print(json.dumps({
    "status": "solver_error", "requested_utility": "LINEAR", "solved_utility": None,
    "solver": None, "objective_value": None, "allocations": None, "utilities": None,
    "warnings": [], "error_type": "Timeout", "error_message": "solver exceeded time budget",
}))
