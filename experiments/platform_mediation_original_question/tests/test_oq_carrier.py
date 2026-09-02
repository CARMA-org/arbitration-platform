"""Carrier-selection determinism, every adaptive branch, and no manual override."""
import itertools

import select_drift_carrier as SDC


def test_decide_is_deterministic():
    for args in itertools.product([True, False], repeat=5):
        a = SDC.decide(*args)
        b = SDC.decide(*args)
        assert a == b


def test_branch_1_independent_positive_and_noninferior():
    carrier, branch, _ = SDC.decide(replication_pass=True, coordination_pass=False,
                                    independent_positive=True, independent_noninferior=True,
                                    distributed_equivalent=False)
    assert carrier == "independent_bundle_maxmin" and branch == 1


def test_branch_2_coordination_distributed_equivalent():
    carrier, branch, _ = SDC.decide(True, True, False, False, True)
    assert carrier == "distributed_price_leontief" and branch == 2


def test_branch_2_coordination_not_distributed_equivalent():
    carrier, branch, _ = SDC.decide(True, True, False, False, False)
    assert carrier == "central_joint_leontief" and branch == 2


def test_branch_3_replication_noninferior_selects_independent():
    carrier, branch, _ = SDC.decide(True, False, False, True, False)
    assert carrier == "independent_bundle_maxmin" and branch == 3


def test_branch_3_replication_not_noninferior_selects_central():
    carrier, branch, _ = SDC.decide(True, False, False, False, False)
    assert carrier == "central_joint_leontief" and branch == 3


def test_branch_4_diagnostic():
    carrier, branch, _ = SDC.decide(False, False, False, False, False)
    assert carrier == "central_joint_leontief_diagnostic" and branch == 4


def test_priority_independent_over_coordination():
    # When both independent (positive+noninferior) and coordination hold, branch 1 wins.
    carrier, branch, _ = SDC.decide(True, True, True, True, True)
    assert branch == 1 and carrier == "independent_bundle_maxmin"


def test_no_override_carrier_is_pure_function_of_conditions():
    seen = {}
    for args in itertools.product([True, False], repeat=5):
        seen[args] = SDC.decide(*args)[0]
    # the mapping is total and every output is one of the five preregistered carriers
    valid = {"independent_bundle_maxmin", "distributed_price_leontief", "central_joint_leontief",
             "central_joint_leontief_diagnostic"}
    assert set(seen.values()) <= valid
