import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))


def java_math_round(v):
    return int(math.floor(v + 0.5))


def naive_cellwise_round(continuous):
    return [[java_math_round(v) for v in row] for row in continuous]


def column_sums(matrix, n_cols):
    return [sum(row[j] for row in matrix) for j in range(n_cols)]


def demo_rounding_violation():
    capacities = [100, 100]
    continuous = [
        [50.5, 25.4],
        [24.5, 49.5],
        [25.0, 25.1],
    ]
    rounded = naive_cellwise_round(continuous)
    cont_cols = column_sums(continuous, 2)
    round_cols = column_sums(rounded, 2)
    violations = [round_cols[j] > capacities[j] for j in range(2)]
    return {
        "capacities": capacities,
        "continuous_allocation": continuous,
        "continuous_column_sums": cont_cols,
        "naive_rounded_allocation": rounded,
        "rounded_column_sums": round_cols,
        "capacity_violation_per_column": violations,
        "note": "Java ConvexJointArbitrator.parseResult uses Math.round per cell "
                "(line ~355). Independent cellwise rounding of a feasible continuous "
                "allocation can push a column sum above capacity.",
    }


def demo_negative_consumption():
    allocated = 100
    consumed_before = 40
    remaining = allocated - consumed_before
    request = -30
    if request <= remaining:
        consumed_after = consumed_before + request
        returned = True
    else:
        consumed_after = allocated
        returned = False
    return {
        "allocated": allocated,
        "consumed_before": consumed_before,
        "requested_amount": request,
        "consumed_after": consumed_after,
        "method_returned": returned,
        "note": "RealisticAgentFramework.tryConsumeResource (lines ~694-711): a "
                "negative amount satisfies 'amount <= remaining' and is added to "
                "consumed, so recorded consumption DECREASES from 40 to 10.",
    }


def main():
    out = {
        "rounding": demo_rounding_violation(),
        "negative_consumption": demo_negative_consumption(),
    }
    with open(os.path.join(HERE, "rounding_and_consumption.json"), "w") as f:
        json.dump(out, f, indent=2)
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
