#!/usr/bin/env python3
"""Guard AiStudio release promotion against skipping develop."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any


RELEASE_METADATA_PATHS = {
    ".agent/agent_version.json",
    "AiStudio/Project_state/indexes/current_summary.md",
    "CHANGELOG.md",
    "PROJECT_VERSION.json",
    "VERSION",
    "templates/.agent/agent_version.json",
}


def run_git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=str(repo),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def rev_parse(repo: Path, ref: str) -> str | None:
    proc = run_git(repo, ["rev-parse", "--verify", ref])
    if proc.returncode != 0:
        return None
    return proc.stdout.strip()


def ancestor(repo: Path, older: str, newer: str) -> bool:
    return run_git(repo, ["merge-base", "--is-ancestor", older, newer]).returncode == 0


def ahead_count(repo: Path, left: str, right: str) -> tuple[int, int] | None:
    proc = run_git(repo, ["rev-list", "--left-right", "--count", f"{left}...{right}"])
    if proc.returncode != 0:
        return None
    parts = proc.stdout.strip().split()
    if len(parts) != 2:
        return None
    return int(parts[0]), int(parts[1])


def same_tree(repo: Path, left: str, right: str) -> bool:
    return run_git(repo, ["diff", "--quiet", left, right]).returncode == 0


def cherry_equivalence(repo: Path, upstream: str, head: str) -> dict[str, Any]:
    proc = run_git(repo, ["cherry", upstream, head])
    result: dict[str, Any] = {
        "available": proc.returncode == 0,
        "patch_equivalent_commits": [],
        "non_equivalent_commits": [],
        "error": proc.stderr.strip() if proc.returncode != 0 else None,
    }
    if proc.returncode != 0:
        return result
    for line in proc.stdout.splitlines():
        marker, _, sha = line.strip().partition(" ")
        if not sha:
            continue
        if marker == "-":
            result["patch_equivalent_commits"].append(sha)
        elif marker == "+":
            result["non_equivalent_commits"].append(sha)
    return result


def commit_changed_paths(repo: Path, commit: str) -> set[str] | None:
    proc = run_git(
        repo,
        ["diff-tree", "--root", "--no-commit-id", "--name-only", "-r", "-m", commit],
    )
    if proc.returncode != 0:
        return None
    return {line.strip() for line in proc.stdout.splitlines() if line.strip()}


def check(repo: Path, develop_ref: str, release_ref: str, allow_hotfix: bool) -> dict[str, Any]:
    develop_sha = rev_parse(repo, develop_ref)
    release_sha = rev_parse(repo, release_ref)
    errors: list[str] = []
    warnings: list[str] = []
    if not develop_sha:
        errors.append(f"missing develop ref: {develop_ref}")
    if not release_sha:
        errors.append(f"missing release ref: {release_ref}")
    if errors:
        return {
            "ok": False,
            "develop_ref": develop_ref,
            "release_ref": release_ref,
            "develop_sha": develop_sha,
            "release_sha": release_sha,
            "errors": errors,
            "warnings": warnings,
        }

    release_contains_develop = ancestor(repo, develop_ref, release_ref)
    develop_contains_release = ancestor(repo, release_ref, develop_ref)
    counts = ahead_count(repo, develop_ref, release_ref)
    develop_only = counts[0] if counts else None
    release_only = counts[1] if counts else None
    release_tree_matches_develop = same_tree(repo, develop_ref, release_ref)
    release_cherry = cherry_equivalence(repo, develop_ref, release_ref)
    release_non_equivalent = len(release_cherry["non_equivalent_commits"]) if release_cherry["available"] else release_only
    release_patch_equivalent = len(release_cherry["patch_equivalent_commits"]) if release_cherry["available"] else 0
    release_metadata_commits: list[str] = []
    release_non_metadata_commits: list[str] = []
    if release_cherry["available"]:
        for commit in release_cherry["non_equivalent_commits"]:
            changed_paths = commit_changed_paths(repo, commit)
            if changed_paths and changed_paths.issubset(RELEASE_METADATA_PATHS):
                release_metadata_commits.append(commit)
            else:
                release_non_metadata_commits.append(commit)

    if not release_contains_develop:
        errors.append("release ref does not contain develop; promote from develop first")
    if release_only and not develop_contains_release and release_tree_matches_develop:
        warnings.append("release has merge-only commits not in develop, but the tree matches develop")
    elif (
        release_only
        and not develop_contains_release
        and release_contains_develop
        and release_non_equivalent
        and not release_non_metadata_commits
    ):
        warnings.append("release differs from develop only by approved release metadata")
    elif release_only and not develop_contains_release and release_non_equivalent:
        message = "release has commits that are not in develop; backport or merge them to develop"
        if allow_hotfix:
            warnings.append(message)
        else:
            errors.append(message)
    elif release_only and not develop_contains_release and release_patch_equivalent:
        warnings.append("release has commits with patch-equivalent changes already present in develop")

    return {
        "ok": not errors,
        "develop_ref": develop_ref,
        "release_ref": release_ref,
        "develop_sha": develop_sha,
        "release_sha": release_sha,
        "release_contains_develop": release_contains_develop,
        "develop_contains_release": develop_contains_release,
        "develop_only_commits": develop_only,
        "release_only_commits": release_only,
        "release_only_patch_equivalent_commits": release_patch_equivalent,
        "release_only_non_equivalent_commits": release_non_equivalent,
        "release_only_metadata_commits": len(release_metadata_commits),
        "release_only_non_metadata_commits": len(release_non_metadata_commits),
        "release_tree_matches_develop": release_tree_matches_develop,
        "errors": errors,
        "warnings": warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate AiStudio develop -> release/main promotion order.")
    parser.add_argument("--repo", default=".")
    parser.add_argument("--develop-ref", default="origin/develop")
    parser.add_argument("--release-ref", default="origin/release/main")
    parser.add_argument("--allow-hotfix", action="store_true", help="Warn instead of failing when release has emergency-only commits not yet backported.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report = check(Path(args.repo).resolve(), args.develop_ref, args.release_ref, args.allow_hotfix)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print("ok" if report["ok"] else "; ".join(report["errors"]))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
