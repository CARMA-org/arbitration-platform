import json
import os
import stat
import sys

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "experiments", "enforcement"))
import run_enforcement as enf


def _shim(tmp_path, body):
    p = tmp_path / "shim.sh"
    p.write_text("#!/bin/sh\n" + body)
    p.chmod(p.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    return str(p)


def test_run_solver_uses_supplied_interpreter(tmp_path):
    marker = json.dumps({"status": "unsupported_model", "allocations": None})
    shim = _shim(tmp_path, "cat >/dev/null; echo '%s'\n" % marker)
    res = enf.run_solver(shim, {"n_agents": 1})
    assert res["status"] == "unsupported_model"


def test_run_solver_nonjson_is_harness_error(tmp_path):
    shim = _shim(tmp_path, "cat >/dev/null; echo not-json\n")
    with pytest.raises(RuntimeError):
        enf.run_solver(shim, {"n_agents": 1})


def test_run_solver_multiple_json_lines_is_harness_error(tmp_path):
    shim = _shim(tmp_path, "cat >/dev/null; echo '{}'; echo '{}'\n")
    with pytest.raises(RuntimeError):
        enf.run_solver(shim, {"n_agents": 1})


def test_run_solver_nonzero_exit_is_harness_error(tmp_path):
    shim = _shim(tmp_path, "cat >/dev/null; echo '{}'; exit 3\n")
    with pytest.raises(RuntimeError):
        enf.run_solver(shim, {"n_agents": 1})


def test_unsupported_utility_check_rejects_via_subprocess():
    case = enf.unsupported_utility_check(sys.executable)
    assert case["status"] == "unsupported_model"
    assert case["silent_fallbacks"] == 0
    assert case["incorrect_success"] == 0


def test_unsupported_check_flags_generic_solver_error(tmp_path):
    err = json.dumps({"status": "solver_error", "allocations": None})
    shim = _shim(tmp_path, "cat >/dev/null; echo '%s'\n" % err)
    case = enf.unsupported_utility_check(shim)
    assert case["silent_fallbacks"] == 1
    assert case["incorrect_success"] == 0
