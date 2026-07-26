#!/usr/bin/env python3
"""Discover safe Project Input PRs and reconcile merged packages."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any

import input_lifecycle_controller as lifecycle


PACKAGE_PATH = re.compile(r"^AiStudio/Project_state/input/(?:GPT|Codex|Other/[^/]+)/(?P<package>PR-(?P<number>[1-9][0-9]*)-[a-z0-9][a-z0-9-]*)/")


def register_manifest(
    project_root: Path,
    manifest: dict[str, Any],
    package_path: str,
    merge_commit: str,
    *,
    apply: bool,
) -> dict[str, Any]:
    package_id = str(manifest["package_id"])
    registry = lifecycle.load_active(project_root)
    if lifecycle.find_record(registry, package_id):
        return {"package_id": package_id, "registered": False, "duplicate": True}
    if any(item.get("package_id") == package_id for item in lifecycle.history_records(project_root)):
        return {"package_id": package_id, "registered": False, "terminal": True}
    record = {
        "package_id": package_id,
        "source": manifest["source"],
        "state": "awaiting_analysis",
        "source_pr": manifest["source_pr"]["number"],
        "updated_at": lifecycle.utc_now(),
        "downstream_refs": sorted({f"package:{package_path}", f"merge_commit:{merge_commit}"}),
    }
    if apply:
        registry["records"].append(record)
        lifecycle.save_active(project_root, registry)
    return {"package_id": package_id, "registered": apply, "record": record}


def source_merge_commit(project_root: Path, manifest: dict[str, Any]) -> str:
    source_pr = manifest.get("source_pr") or {}
    repository = str(source_pr.get("repository") or "")
    number = source_pr.get("number")
    if repository and isinstance(number, int):
        result = subprocess.run(
            ["gh", "pr", "view", str(number), "--repo", repository, "--json", "mergeCommit,state"],
            cwd=project_root,
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode == 0:
            data = json.loads(result.stdout or "{}")
            commit = (data.get("mergeCommit") or {}).get("oid")
            if data.get("state") == "MERGED" and commit:
                return str(commit)
    return lifecycle.git_head(project_root)


def reconcile_merged_packages(project_root: Path, *, apply: bool) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for package_dir in lifecycle.package_dirs(project_root):
        try:
            manifest = lifecycle.load_manifest(package_dir, project_root)
            relative = package_dir.relative_to(project_root).as_posix()
            results.append(
                register_manifest(
                    project_root,
                    manifest,
                    relative,
                    source_merge_commit(project_root, manifest),
                    apply=apply,
                )
            )
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            results.append({"package_path": str(package_dir), "registered": False, "error": str(exc)})
    return results


def discover_open_prs(project_root: Path, repository: str) -> list[dict[str, Any]]:
    if not repository:
        return []
    result = subprocess.run(
        [
            "gh",
            "pr",
            "list",
            "--repo",
            repository,
            "--state",
            "open",
            "--base",
            "develop",
            "--json",
            "number,headRefName,isDraft,mergeable,files,labels,url",
        ],
        cwd=project_root,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return [{"eligible": False, "error": result.stderr.strip() or "gh pr list failed"}]
    discovered: list[dict[str, Any]] = []
    for pr in json.loads(result.stdout or "[]"):
        paths = [str(item.get("path") or "") for item in pr.get("files") or []]
        matches = [PACKAGE_PATH.match(path) for path in paths]
        roots = {match.group("package") for match in matches if match}
        labels = {str(item.get("name") or "") for item in pr.get("labels") or []}
        package_id = next(iter(roots), "") if len(roots) == 1 else ""
        eligible = (
            bool(package_id)
            and package_id.startswith(f"PR-{pr['number']}-")
            and any(path.endswith(f"{package_id}/manifest.json") for path in paths)
            and not pr.get("isDraft")
            and pr.get("mergeable") == "MERGEABLE"
            and "project-input" in labels
        )
        discovered.append({**pr, "package_id": package_id, "eligible": eligible})
    return discovered


def merge_eligible_pr(project_root: Path, repository: str, pr: dict[str, Any]) -> dict[str, Any]:
    result = subprocess.run(
        ["gh", "pr", "merge", str(pr["number"]), "--repo", repository, "--merge"],
        cwd=project_root,
        check=False,
        capture_output=True,
        text=True,
    )
    return {
        "number": pr["number"],
        "package_id": pr["package_id"],
        "merged": result.returncode == 0,
        "error": result.stderr.strip() if result.returncode else "",
        "branch_deleted": False,
    }


def register_from_develop(
    project_root: Path,
    repository: str,
    pr: dict[str, Any],
    *,
    apply: bool,
) -> dict[str, Any]:
    package_id = str(pr["package_id"])
    manifest_path = next(
        str(item.get("path"))
        for item in pr.get("files") or []
        if str(item.get("path") or "").endswith(f"{package_id}/manifest.json")
    )
    shown = subprocess.run(
        ["git", "show", f"origin/develop:{manifest_path}"],
        cwd=project_root,
        check=False,
        capture_output=True,
        text=True,
    )
    if shown.returncode != 0:
        return {"package_id": package_id, "registered": False, "error": "merged manifest missing from origin/develop"}
    manifest = json.loads(shown.stdout)
    if manifest.get("package_id") != package_id or (manifest.get("source_pr") or {}).get("number") != pr["number"]:
        return {"package_id": package_id, "registered": False, "error": "merged manifest identity mismatch"}
    authority = manifest.get("authority") or {}
    if any(authority.get(field) is not False for field in ("execution_authorized", "worker_ready", "merge_authorized")):
        return {"package_id": package_id, "registered": False, "error": "merged manifest grants authority"}
    view = subprocess.run(
        ["gh", "pr", "view", str(pr["number"]), "--repo", repository, "--json", "mergeCommit,state"],
        cwd=project_root,
        check=False,
        capture_output=True,
        text=True,
    )
    data = json.loads(view.stdout or "{}") if view.returncode == 0 else {}
    merge_commit = str((data.get("mergeCommit") or {}).get("oid") or "")
    if data.get("state") != "MERGED" or not merge_commit:
        return {"package_id": package_id, "registered": False, "error": "merge evidence missing"}
    return register_manifest(
        project_root,
        manifest,
        str(Path(manifest_path).parent).replace("\\", "/"),
        merge_commit,
        apply=apply,
    )


def collect(
    project_root: Path,
    *,
    apply: bool,
    discover_github: bool = False,
    repository: str = "",
) -> dict[str, Any]:
    github = discover_open_prs(project_root, repository) if discover_github else []
    merges = [merge_eligible_pr(project_root, repository, pr) for pr in github if apply and pr.get("eligible")]
    remote_registrations: list[dict[str, Any]] = []
    if any(item.get("merged") for item in merges):
        subprocess.run(["git", "fetch", "origin", "develop"], cwd=project_root, check=False, capture_output=True)
        merged_numbers = {item["number"] for item in merges if item.get("merged")}
        remote_registrations = [
            register_from_develop(project_root, repository, pr, apply=apply)
            for pr in github
            if pr.get("number") in merged_numbers
        ]
    return {
        "github": github,
        "merges": merges,
        "remote_registrations": remote_registrations,
        "reconciled": reconcile_merged_packages(project_root, apply=apply),
        "branches_deleted": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path("."))
    parser.add_argument("--repository", default="")
    parser.add_argument("--discover-github", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(
        json.dumps(
            collect(
                args.project_root.resolve(),
                apply=args.apply,
                discover_github=args.discover_github,
                repository=args.repository,
            ),
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
