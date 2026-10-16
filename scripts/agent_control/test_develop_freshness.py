"""Integration tests for develop_freshness using only local Git repositories."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("develop_freshness.py")


def git(path, *args, check=True):
    result = subprocess.run(["git", *args], cwd=path, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, shell=False)
    if check and result.returncode:
        raise AssertionError("git %r failed: %s" % (args, result.stderr))
    return result


class FreshnessIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bare = self.root / "origin.git"
        git(self.root, "init", "--bare", str(self.bare))
        self.repo = self.root / "work"
        git(self.root, "clone", str(self.bare), str(self.repo))
        git(self.repo, "config", "user.name", "Fixture User")
        git(self.repo, "config", "user.email", "fixture@example.invalid")
        (self.repo / "tracked.txt").write_text("initial\n", encoding="utf-8")
        git(self.repo, "add", "tracked.txt")
        git(self.repo, "commit", "-m", "initial")
        git(self.repo, "branch", "-M", "develop")
        git(self.repo, "push", "-u", "origin", "develop")
        git(self.repo, "remote", "set-head", "origin", "develop")
        self.commit("initial published")
        git(self.repo, "push", "origin", "develop")

    def tearDown(self):
        self.temp.cleanup()

    def commit(self, message):
        with (self.repo / "tracked.txt").open("a", encoding="utf-8") as stream:
            stream.write(message + "\n")
        git(self.repo, "add", "tracked.txt")
        git(self.repo, "commit", "-m", message)

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), "--repo", str(self.repo),
                               "--remote", "origin", "--json", *args],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, shell=False)

    def result(self, completed):
        return json.loads(completed.stdout)

    def test_fresh_develop_passes_and_reports_network_verification(self):
        completed = self.run_cli()
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        data = self.result(completed)
        self.assertEqual(data["status"], "current")
        self.assertTrue(data["network_verified"])
        self.assertFalse(data["dirty"])

    def test_dirty_checkout_is_allowed_reported_and_preserved(self):
        (self.repo / "tracked.txt").write_text("wip\n", encoding="utf-8")
        (self.repo / "untracked.txt").write_text("untracked\n", encoding="utf-8")
        before_head = git(self.repo, "rev-parse", "HEAD").stdout.strip()
        completed = self.run_cli()
        data = self.result(completed)
        self.assertEqual(completed.returncode, 0)
        self.assertTrue(data["tracked_changes"])
        self.assertTrue(data["untracked_changes"])
        self.assertFalse(data["commit_only_reproducible"])
        self.assertEqual(git(self.repo, "rev-parse", "HEAD").stdout.strip(), before_head)
        self.assertEqual((self.repo / "tracked.txt").read_text(encoding="utf-8"), "wip\n")
        self.assertTrue((self.repo / "untracked.txt").exists())

    def test_stale_local_develop_fails_after_remote_advances(self):
        publisher = self.root / "publisher"
        git(self.root, "clone", str(self.bare), str(publisher))
        git(publisher, "config", "user.name", "Fixture User")
        git(publisher, "config", "user.email", "fixture@example.invalid")
        git(publisher, "checkout", "develop")
        (publisher / "tracked.txt").write_text("remote advances\n", encoding="utf-8")
        git(publisher, "commit", "-am", "remote advances")
        git(publisher, "push", "origin", "develop")
        # Refresh only the tracking ref; leave this worktree's local develop stale.
        git(self.repo, "fetch", "origin", "develop")
        completed = self.run_cli()
        self.assertNotEqual(completed.returncode, 0)
        data = self.result(completed)
        self.assertEqual(data["status"], "stale")
        self.assertGreater(data["develop_behind"], 0)

    def test_ahead_and_diverged_local_develop_fail(self):
        self.commit("local only")
        completed = self.run_cli()
        self.assertNotEqual(completed.returncode, 0)
        self.assertGreater(self.result(completed)["develop_ahead"], 0)
        git(self.repo, "reset", "--hard", "origin/develop")
        self.commit("local side")
        other = self.root / "other"
        git(self.root, "clone", str(self.bare), str(other))
        git(other, "config", "user.name", "Fixture User")
        git(other, "config", "user.email", "fixture@example.invalid")
        git(other, "checkout", "develop")
        (other / "tracked.txt").write_text("remote side\n", encoding="utf-8")
        git(other, "commit", "-am", "remote side")
        git(other, "push", "origin", "develop")
        git(self.repo, "fetch", "origin", "develop")
        completed = self.run_cli()
        self.assertNotEqual(completed.returncode, 0)
        self.assertGreater(self.result(completed)["develop_ahead"], 0)
        self.assertGreater(self.result(completed)["develop_behind"], 0)

    def test_missing_remote_develop_fails_even_with_old_tracking_ref(self):
        git(self.repo, "push", "origin", ":develop")
        completed = self.run_cli()
        self.assertNotEqual(completed.returncode, 0)
        self.assertEqual(self.result(completed)["status"], "error")

    def test_obsolete_feature_head_fails_and_fresh_pr_head_passes(self):
        old = git(self.repo, "rev-parse", "HEAD").stdout.strip()
        self.commit("published advance")
        git(self.repo, "push", "origin", "develop")
        git(self.repo, "fetch", "origin", "develop")
        git(self.repo, "checkout", "-b", "feature/old", old)
        completed = self.run_cli()
        self.assertNotEqual(completed.returncode, 0)
        git(self.repo, "checkout", "-b", "feature/fresh", "develop")
        completed = self.run_cli()
        self.assertEqual(completed.returncode, 0, completed.stdout)
        self.assertEqual(self.result(completed)["status"], "current")

    def test_unknown_and_unsafe_remote_fail_without_exposing_url(self):
        completed = self.run_cli("--remote", "missing")
        self.assertNotEqual(completed.returncode, 0)
        self.assertEqual(self.result(completed)["status"], "error")
        completed = self.run_cli("--remote", "--upload-pack=bad")
        self.assertNotEqual(completed.returncode, 0)
        self.assertNotIn(str(self.bare), completed.stdout)

    def test_network_failure_fails_closed(self):
        git(self.repo, "remote", "set-url", "origin", str(self.root / "does-not-exist.git"))
        completed = self.run_cli()
        self.assertNotEqual(completed.returncode, 0)
        data = self.result(completed)
        self.assertEqual(data["status"], "error")
        self.assertFalse(data["network_verified"])
        self.assertNotIn("does-not-exist", completed.stdout)

    def test_fetch_updates_only_develop_tracking_ref_and_preserves_wip(self):
        # Publish a new commit from a separate clone so local develop remains old.
        other = self.root / "publisher"
        git(self.root, "clone", str(self.bare), str(other))
        git(other, "config", "user.name", "Fixture User")
        git(other, "config", "user.email", "fixture@example.invalid")
        git(other, "checkout", "develop")
        (other / "tracked.txt").write_text("published\n", encoding="utf-8")
        git(other, "commit", "-am", "publish")
        git(other, "push", "origin", "develop")
        git(self.repo, "update-ref", "refs/remotes/origin/other", "HEAD")
        (self.repo / "tracked.txt").write_text("local wip\n", encoding="utf-8")
        (self.repo / "new.txt").write_text("untracked\n", encoding="utf-8")
        git(self.repo, "add", "tracked.txt")
        head = git(self.repo, "rev-parse", "HEAD").stdout.strip()
        unrelated = git(self.repo, "rev-parse", "refs/remotes/origin/other").stdout.strip()
        git(self.repo, "stash", "push", "-m", "fixture stash")
        stash = git(self.repo, "rev-parse", "refs/stash").stdout.strip()
        # Restore WIP after recording the stash while keeping the entry for preservation checks.
        git(self.repo, "stash", "apply")
        index = git(self.repo, "write-tree").stdout.strip()
        completed = self.run_cli("--fetch")
        self.assertNotEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertEqual(self.result(completed)["status"], "stale")
        self.assertEqual(git(self.repo, "rev-parse", "HEAD").stdout.strip(), head)
        self.assertEqual(git(self.repo, "write-tree").stdout.strip(), index)
        self.assertEqual(git(self.repo, "rev-parse", "refs/remotes/origin/other").stdout.strip(), unrelated)
        self.assertEqual(git(self.repo, "rev-parse", "refs/stash").stdout.strip(), stash)
        self.assertEqual((self.repo / "tracked.txt").read_text(encoding="utf-8"), "local wip\n")
        self.assertEqual((self.repo / "new.txt").read_text(encoding="utf-8"), "untracked\n")
        self.assertEqual(git(self.repo, "rev-parse", "refs/remotes/origin/develop").stdout.strip(),
                         git(other, "rev-parse", "develop").stdout.strip())


if __name__ == "__main__":
    unittest.main()
