#!/usr/bin/env python3
"""Verify a checkout against the selected remote's currently published develop."""
import argparse
import json
import subprocess
import sys


def git(repo, *args, check=True):
    result = subprocess.run(["git", *args], cwd=repo, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, shell=False)
    if check and result.returncode:
        raise RuntimeError("git command failed")
    return result


def output(repo, *args):
    return git(repo, *args).stdout.strip()


def verify(repo, requested_remote, fetch):
    branch = output(repo, "symbolic-ref", "--quiet", "--short", "HEAD") if git(
        repo, "symbolic-ref", "--quiet", "--short", "HEAD", check=False).returncode == 0 else "(detached)"
    tracking_remote = output(repo, "config", "--get", "branch.develop.remote") if git(
        repo, "config", "--get", "branch.develop.remote", check=False).returncode == 0 else ""
    tracking_merge = output(repo, "config", "--get", "branch.develop.merge") if git(
        repo, "config", "--get", "branch.develop.merge", check=False).returncode == 0 else ""
    remote = requested_remote or tracking_remote
    if not remote:
        raise RuntimeError("cannot identify the configured develop authority")
    if remote.startswith("-"):
        raise RuntimeError("unsafe remote name")
    if remote not in output(repo, "remote").splitlines():
        raise RuntimeError("unknown configured remote")
    if tracking_remote != remote or tracking_merge != "refs/heads/develop":
        raise RuntimeError("develop tracking configuration is missing or inconsistent")

    local_develop = output(repo, "rev-parse", "--verify", "refs/heads/develop^{commit}")
    head = output(repo, "rev-parse", "--verify", "HEAD^{commit}")
    if not local_develop:
        raise RuntimeError("local develop is missing")
    if fetch:
        fetched = git(repo, "fetch", "--no-tags", "--no-write-fetch-head", remote,
                      "+refs/heads/develop:refs/remotes/%s/develop" % remote, check=False)
        if fetched.returncode:
            raise RuntimeError("fetch failed")

    advertised = git(repo, "ls-remote", "--exit-code", "--heads", remote,
                     "refs/heads/develop", check=False)
    if advertised.returncode:
        raise RuntimeError("remote unavailable or develop is not advertised")
    lines = advertised.stdout.splitlines()
    refs = [line.split() for line in lines if len(line.split()) == 2 and line.split()[1] == "refs/heads/develop"]
    if len(refs) != 1 or len(refs[0][0]) != 40:
        raise RuntimeError("remote develop advertisement is invalid")
    published = refs[0][0].lower()
    tracking_ref = "refs/remotes/%s/develop" % remote
    tracking = git(repo, "rev-parse", "--verify", tracking_ref + "^{commit}", check=False)
    tracked = tracking.stdout.strip().lower() if tracking.returncode == 0 else ""
    if fetch:
        if tracked != published:
            raise RuntimeError("remote develop moved during fetch or tracking ref differs")
    elif tracked != published:
        raise RuntimeError("tracking ref does not match freshly published develop; rerun with --fetch")

    def counts(revision):
        result = output(repo, "rev-list", "--left-right", "--count", revision + "..." + published)
        left, right = result.split()
        return {"ahead": int(left), "behind": int(right)}

    is_ancestor = git(repo, "merge-base", "--is-ancestor", published, "HEAD", check=False).returncode == 0
    status = git(repo, "status", "--porcelain", "--untracked-files=normal").stdout
    tracked_dirty = False
    untracked = False
    for line in status.splitlines():
        if line.startswith("??"):
            untracked = True
        else:
            tracked_dirty = True
    local_counts = counts("refs/heads/develop")
    head_counts = counts("HEAD")
    current = local_develop == published and is_ancestor
    reason = "current" if current else "local develop or HEAD is not based on published develop"
    return {"status": "current" if current else "stale", "reason": reason,
            "remote": remote, "branch": branch, "local_develop": local_develop,
            "published_develop": published, "head": head,
            "develop_ahead": local_counts["ahead"], "develop_behind": local_counts["behind"],
            "head_ahead": head_counts["ahead"], "head_behind": head_counts["behind"],
            "network_verified": True, "tracked_changes": tracked_dirty,
            "untracked_changes": untracked, "dirty": tracked_dirty or untracked,
            "commit_only_reproducible": not (tracked_dirty or untracked)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=".")
    parser.add_argument("--remote")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = verify(args.repo, args.remote, args.fetch)
    except (RuntimeError, OSError, ValueError) as exc:
        result = {"status": "error", "reason": str(exc), "network_verified": False}
        if args.json:
            print(json.dumps(result, sort_keys=True))
        else:
            print("develop preflight failed: " + result["reason"])
        return 1
    if args.json:
        print(json.dumps(result, sort_keys=True))
    else:
        print("develop preflight %s: %s; develop %s, HEAD %s; dirty=%s (tracked=%s, untracked=%s)" %
              (result["status"], result["remote"], result["published_develop"], result["head"],
               result["dirty"], result["tracked_changes"], result["untracked_changes"]))
    return 0 if result["status"] == "current" else 1


if __name__ == "__main__":
    sys.exit(main())
