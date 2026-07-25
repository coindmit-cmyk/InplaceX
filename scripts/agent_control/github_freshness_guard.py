#!/usr/bin/env python3
"""Fail when a local checkout is stale relative to the GitHub base ref."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any


def run_git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-c", "core.longpaths=true", *args],
        cwd=str(repo),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        encoding="utf-8",
        errors="replace",
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


def current_branch(repo: Path) -> str | None:
    proc = run_git(repo, ["branch", "--show-current"])
    if proc.returncode != 0:
        return None
    return proc.stdout.strip() or None


def dirty_paths(repo: Path) -> list[str]:
    proc = run_git(repo, ["status", "--porcelain"])
    if proc.returncode != 0:
        return []
    return [line for line in proc.stdout.splitlines() if line.strip()]


def check_freshness(
    repo: Path,
    base_ref: str,
    local_ref: str,
    remote: str,
    fetch: bool,
    auto_ff: bool = False,
) -> dict[str, Any]:
    repo = repo.resolve()
    errors: list[dict[str, str]] = []
    warnings: list[str] = []
    fetch_error: str | None = None

    if fetch:
        fetch_proc = run_git(repo, ["fetch", "--prune", remote])
        if fetch_proc.returncode != 0:
            fetch_error = (fetch_proc.stderr or fetch_proc.stdout).strip()
            errors.append(
                {
                    "code": "fetch_failed",
                    "message": f"could not refresh remote refs from {remote}",
                }
            )

    local_sha = rev_parse(repo, local_ref)
    base_sha = rev_parse(repo, base_ref)
    dirty = dirty_paths(repo)

    if not local_sha:
        errors.append({"code": "missing_local_ref", "message": f"missing local ref: {local_ref}"})
    if not base_sha:
        errors.append({"code": "missing_base_ref", "message": f"missing base ref: {base_ref}"})

    if errors:
        return {
            "ok": False,
            "project_root": str(repo),
            "current_branch": current_branch(repo),
            "local_ref": local_ref,
            "base_ref": base_ref,
            "remote": remote,
            "fetched": fetch and fetch_error is None,
            "fetch_error": fetch_error,
            "local_sha": local_sha,
            "base_sha": base_sha,
            "dirty": bool(dirty),
            "dirty_entries": dirty,
            "errors": errors,
            "warnings": warnings,
        }

    local_contains_base = ancestor(repo, base_ref, local_ref)
    base_contains_local = ancestor(repo, local_ref, base_ref)
    counts = ahead_count(repo, local_ref, base_ref)
    local_only = counts[0] if counts else None
    base_only = counts[1] if counts else None

    fast_forward: dict[str, Any] | None = None
    if auto_ff and not dirty and not local_contains_base and base_contains_local:
        merge_proc = run_git(repo, ["merge", "--ff-only", base_ref])
        fast_forward = {
            "attempted": True,
            "returncode": merge_proc.returncode,
            "stdout": merge_proc.stdout[-2000:],
            "stderr": merge_proc.stderr[-2000:],
        }
        if merge_proc.returncode == 0:
            local_sha = rev_parse(repo, local_ref)
            base_sha = rev_parse(repo, base_ref)
            local_contains_base = ancestor(repo, base_ref, local_ref)
            base_contains_local = ancestor(repo, local_ref, base_ref)
            counts = ahead_count(repo, local_ref, base_ref)
            local_only = counts[0] if counts else None
            base_only = counts[1] if counts else None
        else:
            errors.append({"code": "auto_fast_forward_failed", "message": f"could not fast-forward {local_ref} to {base_ref}"})

    if not local_contains_base:
        message = (
            f"local checkout {local_ref} is behind {base_ref}; fetch and update from GitHub "
            "before reading local files as source of truth"
        )
        if dirty:
            message += "; preserve local changes before merge/rebase"
        errors.append({"code": "local_checkout_behind_remote", "message": message})

    if dirty:
        warnings.append("local worktree has uncommitted changes; protect them before updating from base")

    return {
        "ok": not errors,
        "project_root": str(repo),
        "current_branch": current_branch(repo),
        "local_ref": local_ref,
        "base_ref": base_ref,
        "remote": remote,
        "fetched": fetch and fetch_error is None,
        "fetch_error": fetch_error,
        "local_sha": local_sha,
        "base_sha": base_sha,
        "local_contains_base": local_contains_base,
        "base_contains_local": base_contains_local,
        "local_only_commits": local_only,
        "base_only_commits": base_only,
        "fast_forward": fast_forward or {"attempted": False},
        "dirty": bool(dirty),
        "dirty_entries": dirty,
        "errors": errors,
        "warnings": warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate that local files are fresh relative to GitHub.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--local-ref", default="HEAD")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--fetch", action="store_true", help="Refresh remote refs before checking freshness.")
    parser.add_argument("--auto-ff", action="store_true", help="Fast-forward a clean checkout when it is only behind the base ref.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report = check_freshness(
        Path(args.project_root),
        base_ref=args.base_ref,
        local_ref=args.local_ref,
        remote=args.remote,
        fetch=args.fetch,
        auto_ff=args.auto_ff,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    elif report["ok"]:
        print(f"ok: {args.local_ref} contains {args.base_ref}")
    else:
        print("; ".join(error["message"] for error in report["errors"]))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
