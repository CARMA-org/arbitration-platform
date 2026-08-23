import os
import subprocess
import sys

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
_saved_path = list(sys.path)
sys.path.insert(0, os.path.join(ROOT, "experiments", "platform_mediation"))
import make_bundle as mb
sys.path[:] = _saved_path


def _git(repo, *args):
    subprocess.check_call(["git", "-C", repo, *args],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


@pytest.fixture
def repo(tmp_path, monkeypatch):
    r = str(tmp_path / "repo")
    os.makedirs(r)
    _git(r, "init", "-q")
    _git(r, "config", "user.email", "t@example.com")
    _git(r, "config", "user.name", "t")
    md = os.path.join(r, "experiments", "platform_mediation")
    os.makedirs(md)
    with open(os.path.join(md, "EXPERIMENT_MANIFEST.json"), "w") as f:
        f.write('{"source_commit": "deadbeefcafe"}')
    with open(os.path.join(r, "src.txt"), "w") as f:
        f.write("hello")
    _git(r, "add", "-A")
    _git(r, "commit", "-q", "-m", "source commit")
    monkeypatch.setattr(mb, "ROOT", r)
    return r


def _add_bundle_only_commit(repo):
    with open(os.path.join(repo, mb.BUNDLE_ZIP), "wb") as f:
        f.write(b"zipbytes")
    with open(os.path.join(repo, mb.BUNDLE_SHA), "w") as f:
        f.write("shabytes\n")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", "Replace platform evaluation results bundle")


def test_ordinary_source_commit_resolves_to_itself(repo):
    with open(os.path.join(repo, "src.txt"), "w") as f:
        f.write("changed")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", "source change")
    assert mb.resolve_snapshot(None) == mb.rev_parse("HEAD")


def test_bundle_only_commit_resolves_to_parent(repo):
    parent = mb.rev_parse("HEAD")
    _add_bundle_only_commit(repo)
    assert mb.rev_parse("HEAD") != parent
    assert mb.resolve_snapshot(None) == parent


def test_explicit_snapshot_override(repo):
    head = mb.rev_parse("HEAD")
    _add_bundle_only_commit(repo)
    assert mb.resolve_snapshot(head) == head


def test_invalid_snapshot_refused(repo):
    with pytest.raises(SystemExit):
        mb.resolve_snapshot("no-such-ref-zzz")


def test_two_builds_same_snapshot_identical_bytes(repo):
    snap = mb.resolve_snapshot(None)
    z1, _, d1, _ = mb.build(snap, "resultscommit")
    b1 = open(z1, "rb").read()
    z2, _, d2, _ = mb.build(snap, "resultscommit")
    b2 = open(z2, "rb").read()
    assert b1 == b2
    assert d1 == d2


def test_bundle_commit_reproduces_parent_snapshot_bytes(repo):
    source_snap = mb.resolve_snapshot(None)
    z1, _, d1, _ = mb.build(source_snap, "resultscommit")
    b1 = open(z1, "rb").read()
    _add_bundle_only_commit(repo)
    resolved = mb.resolve_snapshot(None)
    assert resolved == source_snap
    z2, _, d2, _ = mb.build(resolved, "resultscommit")
    b2 = open(z2, "rb").read()
    assert b1 == b2


def test_repository_commit_txt_is_source_snapshot(repo):
    source_snap = mb.resolve_snapshot(None)
    _add_bundle_only_commit(repo)
    resolved = mb.resolve_snapshot(None)
    import zipfile
    zpath, _, _, _ = mb.build(resolved, "resultscommit")
    with zipfile.ZipFile(zpath) as zf:
        repo_commit = zf.read("REPOSITORY_COMMIT.txt").decode().strip()
    assert repo_commit == source_snap


def test_excluded_zip_cannot_be_nested():
    assert mb.excluded("platform_evaluation_results_bundle.zip")
    assert mb.excluded("foo/bar.zip")
    assert mb.excluded("platform_evaluation_results_bundle.sha256")
    assert not mb.excluded("src/main/java/Foo.java")
