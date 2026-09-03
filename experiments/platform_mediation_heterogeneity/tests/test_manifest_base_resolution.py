"""The heterogeneity provenance-manifest generators must resolve the canonical-evaluation
base commit WITHOUT depending on the ``platform-evaluation`` branch, which is deleted after
consolidation. These tests assert branch-independence, override precedence, and a graceful
no-branch fallback.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
EXP = os.path.abspath(os.path.join(HERE, ".."))
if EXP not in sys.path:
    sys.path.insert(0, EXP)

import make_confirmatory_manifest as C  # noqa: E402
import make_pilot_manifest as P  # noqa: E402

IMMUTABLE_BASE = "bfab534bba977d5f7c40b0407b83036b38dfbf4a"


def test_default_uses_immutable_base_not_branch():
    # The immutable base commit is reachable in main's history, so the resolver returns it
    # directly and never needs the (soon-deleted) platform-evaluation branch.
    assert C.resolve_canonical_base() == IMMUTABLE_BASE
    assert P.resolve_canonical_base() == IMMUTABLE_BASE
    assert C.CANONICAL_BASE_DEFAULT == P.CANONICAL_BASE_DEFAULT == IMMUTABLE_BASE


def test_explicit_and_env_override(monkeypatch):
    assert C.resolve_canonical_base("1234abcd") == "1234abcd"
    monkeypatch.setenv("CANONICAL_BASE_COMMIT", "feedface")
    assert C.resolve_canonical_base() == "feedface"
    assert P.resolve_canonical_base() == "feedface"


def test_operates_without_branch(monkeypatch):
    """Simulate a fresh clone where neither the immutable default commit nor the branch is
    reachable: the resolver must not crash and must return a usable string."""
    for mod in (C, P):
        monkeypatch.setattr(mod, "CANONICAL_BASE_DEFAULT", "0" * 40)
        # make every git call (default-commit check AND branch lookup) fail, as in a repo
        # without that commit or branch
        monkeypatch.setattr(mod, "run", lambda *a, **k: None)
        got = mod.resolve_canonical_base()
        assert got == "0" * 40  # falls back to the recorded default string, no exception


def test_branch_used_only_as_last_resort(monkeypatch):
    """If the immutable default is absent but the branch resolves, use the branch."""
    calls = {"branch": "abc123branch"}

    def fake_run(cmd, **k):
        if "--verify" in cmd:
            return None  # default commit not present
        if "origin/platform-evaluation" in cmd:
            return calls["branch"]
        return None
    monkeypatch.setattr(C, "CANONICAL_BASE_DEFAULT", "1" * 40)
    monkeypatch.setattr(C, "run", fake_run)
    assert C.resolve_canonical_base() == "abc123branch"
