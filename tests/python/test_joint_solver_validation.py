import pytest

import joint_solver as js


def _data(util_type):
    return {
        "n_agents": 2, "n_resources": 2,
        "preferences": [[0.6, 0.4], [0.4, 0.6]],
        "priority_weights": [1.0, 1.0],
        "capacities": [100, 100],
        "minimums": [[1, 1], [1, 1]],
        "ideals": [[100, 100], [100, 100]],
        "utility_configs": [{"type": util_type}, {"type": "LINEAR"}],
    }


def test_unsupported_model_with_cvxpy():
    if not js.CVXPY_AVAILABLE:
        pytest.skip("cvxpy required")
    res = js.solve_joint_allocation(_data("SOFTPLUS_LOSS_AVERSION"))
    assert res["status"] == "unsupported_model"
    assert res["allocations"] is None


def test_unsupported_model_without_cvxpy(monkeypatch):
    monkeypatch.setattr(js, "CVXPY_AVAILABLE", False)
    res = js.solve_joint_allocation(_data("SOFTPLUS_LOSS_AVERSION"))
    assert res["status"] == "unsupported_model"
    assert res["allocations"] is None


def test_supported_model_without_cvxpy(monkeypatch):
    monkeypatch.setattr(js, "CVXPY_AVAILABLE", False)
    res = js.solve_joint_allocation(_data("LINEAR"))
    assert res["status"] == "solver_error"
    assert res["allocations"] is None


def test_supported_model_with_cvxpy():
    if not js.CVXPY_AVAILABLE:
        pytest.skip("cvxpy required")
    res = js.solve_joint_allocation(_data("LINEAR"))
    assert res["status"] in ("optimal", "optimal_inaccurate")
    assert res["allocations"] is not None


def test_generic_solver_error_is_not_unsupported_model(monkeypatch):
    monkeypatch.setattr(js, "CVXPY_AVAILABLE", False)
    res = js.solve_joint_allocation(_data("LINEAR"))
    assert res["status"] != "unsupported_model"
