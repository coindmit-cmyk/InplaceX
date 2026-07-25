#!/usr/bin/env python3
"""Dry-run classifier for dirty and unpushed project changes."""

from __future__ import annotations

import argparse
import json
import subprocess
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import project_registry
import workspace_preservation_plan


def run_git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
        timeout=60,
    )


def porcelain_path(line: str) -> str:
    path = line[3:].strip()
    if " -> " in path:
        path = path.rsplit(" -> ", 1)[1]
    return path.strip('"').replace("\\", "/")


def parse_name_status(line: str) -> tuple[str, str] | None:
    parts = line.split("\t")
    if len(parts) < 2:
        return None
    status = parts[0]
    path = parts[-1].strip('"').replace("\\", "/")
    return status, path


def classify_path(path: str) -> tuple[str, str]:
    normalized = path.strip().replace("\\", "/").lstrip("/")
    lower = normalized.lower()
    if lower.startswith("archive/cleanup/preservation/"):
        return "preservation_artifact", "workspace-doctor"
    if lower.startswith((".env", "secrets/")) or "secret" in lower:
        return "secret_config", "owner"
    if lower.startswith(("aistudio/task_manager/", "docs/plans/", ".agent/")):
        return "task_or_agent_state", "dispatcher"
    if lower.startswith(("docs/", "readme", "agents.md", "changelog")):
        return "documentation", "architect"
    if lower.startswith(("apps/", "templates/", "static/", "eshop/", "tests/")) or "/migrations/" in lower:
        return "product_code", "integrator"
    if lower.startswith(("archive/", "runtime/", "temp/", "old/", "backups/")):
        return "runtime_archive", "workspace-doctor"
    if lower.startswith(("scripts/", "schemas/", "agent-core/", "templates/agent-control/")):
        return "automation_contract", "architect"
    return "unknown", "integrator"


def dirty_changes(repo: Path) -> list[dict[str, Any]]:
    proc = run_git(repo, ["status", "--porcelain", "--untracked-files=all"])
    changes: list[dict[str, Any]] = []
    for line in (proc.stdout if proc.returncode == 0 else "").splitlines():
        if not line.strip():
            continue
        path = porcelain_path(line)
        category, owner = classify_path(path)
        changes.append({"source": "dirty", "status": line[:2], "path": path, "category": category, "owner": owner})
    return changes


def unpushed_changes(repo: Path, base_ref: str) -> tuple[list[str], list[dict[str, Any]]]:
    if not base_ref:
        return [], []
    commits = run_git(repo, ["log", "--format=%H", f"{base_ref}..HEAD"])
    commit_ids = [line.strip() for line in (commits.stdout if commits.returncode == 0 else "").splitlines() if line.strip()]
    diff = run_git(repo, ["diff", "--name-status", f"{base_ref}..HEAD"])
    changes: list[dict[str, Any]] = []
    for line in (diff.stdout if diff.returncode == 0 else "").splitlines():
        parsed = parse_name_status(line)
        if not parsed:
            continue
        status, path = parsed
        category, owner = classify_path(path)
        changes.append({"source": "unpushed", "status": status, "path": path, "category": category, "owner": owner})
    return commit_ids, changes


def summarize(changes: list[dict[str, Any]]) -> dict[str, Any]:
    non_actionable = {"runtime_archive", "preservation_artifact"}
    by_category = Counter(str(item.get("category") or "unknown") for item in changes)
    by_owner = Counter(str(item.get("owner") or "integrator") for item in changes)
    by_category_source: dict[str, Counter[str]] = defaultdict(Counter)
    migration_sensitive_categories: set[str] = set()
    examples: dict[str, list[str]] = defaultdict(list)
    for item in changes:
        category = str(item.get("category") or "unknown")
        source = str(item.get("source") or "unknown")
        path = str(item.get("path") or "")
        by_category_source[category][source] += 1
        if "/migrations/" in f"/{path.replace('\\', '/')}":
            migration_sensitive_categories.add(category)
        if len(examples[category]) < 10:
            examples[category].append(path)
    return {
        "total_change_paths": len(changes),
        "actionable_change_paths": sum(1 for item in changes if str(item.get("category") or "unknown") not in non_actionable),
        "non_actionable_categories": sorted(non_actionable),
        "by_category": dict(sorted(by_category.items())),
        "by_owner": dict(sorted(by_owner.items())),
        "by_category_source": {
            category: dict(sorted(counter.items()))
            for category, counter in sorted(by_category_source.items())
        },
        "migration_sensitive_categories": sorted(migration_sensitive_categories),
        "examples": dict(sorted(examples.items())),
    }


def classify_project(project: dict[str, Any], preservation: dict[str, Any]) -> dict[str, Any]:
    repo = Path(str(project.get("automation_path") or project.get("local_path") or "")).expanduser()
    base_ref = str(project.get("code_base_ref") or project.get("base_ref") or "origin/develop")
    dirty = dirty_changes(repo) if repo.exists() else []
    commit_ids, unpushed = unpushed_changes(repo, base_ref) if repo.exists() else ([], [])
    changes = dirty + unpushed
    summary = summarize(changes)
    preservation_evidence = preservation.get("preservation_evidence") if isinstance(preservation, dict) else {}
    return {
        "project_id": project.get("project_id"),
        "root": str(repo),
        "base_ref": base_ref,
        "dirty_change_count": len(dirty),
        "unpushed_commit_count": len(commit_ids),
        "unpushed_change_count": len(unpushed),
        "preservation_captured": bool((preservation_evidence or {}).get("captured")),
        "summary": summary,
        "changes_sample": changes[:100],
        "next_actions": [
            "Review product_code and unknown paths with Integrator before rebuild.",
            "Route task_or_agent_state paths to Dispatcher/Architect before migration.",
            "Keep secret_config paths out of tracked reports and shared task packets.",
        ],
    }


def build_report(registry_path: Path, *, project_id: str | None = None, devops_root: Path | None = None) -> dict[str, Any]:
    projects, registry_warnings = project_registry.load_projects(registry_path, project_id=project_id)
    preservation = workspace_preservation_plan.build_report(registry_path, project_id=project_id, devops_root=devops_root)
    preservation_by_project = {
        str(item.get("project_id") or ""): item
        for item in preservation.get("projects") or []
        if isinstance(item, dict)
    }
    items = [
        classify_project(project, preservation_by_project.get(str(project.get("project_id") or ""), {}))
        for project in projects
    ]
    return {
        "schema_version": "1.0",
        "mode": "workspace_change_classifier",
        "registry": str(registry_path),
        "devops_root": str(devops_root) if devops_root else None,
        "project_count": len(items),
        "registry_warnings": registry_warnings,
        "projects": items,
        "mutates_state": False,
    }


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--devops-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = build_report(args.registry.expanduser(), project_id=args.project_id, devops_root=args.devops_root.expanduser() if args.devops_root else None)
    if args.output:
        write_json_atomic(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"classified projects={report['project_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
