#!/usr/bin/env python3
"""Read-only Workspace Doctor and Project Health Score."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict

import project_registry
import project_version_gate
from action_report import build_report as build_action_report
from action_report import validate_report as validate_action_report


CHECKOUT_ROLES = ("develop", "codex", "release")
DEFAULT_TASK_MANAGER = "AiStudio/Task_manager"


def safe_timestamp(path: Path) -> str:
    try:
        return datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc).isoformat(timespec="seconds")
    except OSError:
        return ""


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def run_git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(["git", *args], cwd=repo, text=True, capture_output=True, check=False, timeout=20)
    except (OSError, subprocess.SubprocessError):
        return None


def is_git_worktree(path: Path) -> bool:
    proc = run_git(path, ["rev-parse", "--is-inside-work-tree"]) if path.exists() else None
    return bool(proc and proc.returncode == 0 and proc.stdout.strip().lower() == "true")


def git_head(path: Path) -> str:
    proc = run_git(path, ["rev-parse", "HEAD"]) if path.exists() else None
    return proc.stdout.strip() if proc and proc.returncode == 0 else ""


def git_branch(path: Path) -> str:
    proc = run_git(path, ["branch", "--show-current"]) if path.exists() else None
    return proc.stdout.strip() if proc and proc.returncode == 0 else ""


def git_dirty(path: Path) -> list[str]:
    proc = run_git(path, ["status", "--porcelain"]) if path.exists() else None
    return [line for line in (proc.stdout if proc and proc.returncode == 0 else "").splitlines() if line.strip()]


def git_remotes(path: Path) -> list[dict[str, str]]:
    proc = run_git(path, ["remote", "-v"]) if path.exists() else None
    if not proc or proc.returncode != 0:
        return []

    remotes: dict[str, dict[str, str]] = {}
    for line in (proc.stdout or "").splitlines():
        parts = line.split()
        if len(parts) < 3:
            continue
        name, url, direction = parts[0], parts[1], parts[2].strip("()")
        remotes.setdefault(name, {})
        remotes[name][direction] = url

    items: list[dict[str, str]] = []
    for name in sorted(remotes):
        item = {"name": name}
        if "fetch" in remotes[name]:
            item["fetch"] = remotes[name]["fetch"]
        if "push" in remotes[name]:
            item["push"] = remotes[name]["push"]
        items.append(item)
    return items


def git_worktree_list(path: Path) -> list[dict[str, str]]:
    proc = run_git(path, ["worktree", "list"]) if path.exists() else None
    if not proc or proc.returncode != 0:
        return []

    worktrees: list[dict[str, str]] = []
    for line in (proc.stdout or "").splitlines():
        parts = line.split(maxsplit=2)
        if not parts:
            continue
        item: dict[str, str] = {"path": parts[0]}
        if len(parts) >= 2:
            item["head"] = parts[1]
        if len(parts) >= 3:
            item["annotation"] = parts[2].strip()
        worktrees.append(item)
    return worktrees


def git_worktree_entry(path: Path, worktrees: list[dict[str, str]]) -> dict[str, str]:
    try:
        target = path.resolve()
    except OSError:
        target = path
    for item in worktrees:
        try:
            if Path(item.get("path", "")).resolve() == target:
                return item
        except OSError:
            if item.get("path") == str(target):
                return item
    return {}


def repository_fingerprint(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"path": str(path), "exists": False, "is_git_worktree": False}
    remotes = git_remotes(path)
    remote_urls = sorted({
        candidate
        for remote in remotes
        for candidate in (remote.get("fetch") or remote.get("push"),)
        if candidate
    })
    return {
        "path": str(path),
        "exists": True,
        "is_git_worktree": is_git_worktree(path),
        "branch": git_branch(path),
        "head": git_head(path),
        "remote_urls": remote_urls,
    }


def compute_dir_bytes(path: Path) -> int:
    if not path.exists() or not path.is_dir():
        return 0
    total = 0
    try:
        entries = list(path.iterdir())
    except OSError:
        return 0

    for item in entries:
        try:
            if item.is_symlink():
                continue
            if item.is_file():
                total += item.stat().st_size
            elif item.is_dir():
                total += compute_dir_bytes(item)
        except OSError:
            continue
    return total


def is_markdown_link_broken(document_path: Path, target: str) -> bool:
    stripped = target.split("#", 1)[0].strip()
    if not stripped:
        return False
    lowered = stripped.lower()
    if lowered.startswith(("http://", "https://", "mailto:")) or stripped.startswith("#"):
        return False
    candidate = (document_path.parent / stripped).resolve()
    return not candidate.exists()


def scan_markdown_orphans(document_path: Path) -> list[str]:
    if not document_path.is_file():
        return []
    try:
        content = document_path.read_text(encoding="utf-8")
    except OSError:
        return []

    links: list[str] = []
    for match in re.finditer(r"\[[^\]]+\]\(([^)]+)\)", content):
        target = match.group(1).strip()
        if is_markdown_link_broken(document_path, target):
            links.append(target)
    return sorted(set(links))


def issue(code: str, points: int, evidence: Any, next_owner: str, next_action: str, severity: str = "warning") -> dict[str, Any]:
    return {
        "code": code,
        "severity": severity,
        "points": max(0, int(points)),
        "evidence": evidence,
        "next_owner": next_owner,
        "next_action": next_action,
    }


def classify_sibling(
    project_id: str,
    workspace_root: Path,
    sibling: Path,
    checkout_fingerprints: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    name = sibling.name
    normalized = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    pid = re.sub(r"[^a-z0-9]+", "-", project_id.lower()).strip("-")
    confidence = 0.0
    reason = "name_unrelated"
    if pid and normalized.startswith(pid) and sibling != workspace_root:
        confidence = 0.85
        reason = "project_id_prefix"
    elif any(token in normalized for token in ("old", "backup", "copy", "test", "worker", "build")):
        confidence = 0.55
        reason = "legacy_suffix"

    has_git_marker = (sibling / ".git").exists()
    if confidence < 0.5 and not has_git_marker:
        return {
            "path": str(sibling),
            "name": name,
            "classification": "unrelated_or_unknown",
            "confidence": confidence,
            "reason": reason,
            "size_bytes": 0,
            "modified_at": safe_timestamp(sibling),
            "repository_fingerprint": {"is_git_worktree": False},
            "duplicate_checkout_roles": [],
        }

    fp = repository_fingerprint(sibling)
    sibling_remotes = set(fp.get("remote_urls") or [])
    duplicate_roles: list[str] = []
    for role, source_fp in checkout_fingerprints.items():
        if not source_fp.get("is_git_worktree"):
            continue
        source_remotes = set(source_fp.get("remote_urls") or [])
        if source_remotes and set(source_remotes) == sibling_remotes and (
            source_fp.get("head") == fp.get("head")
            or source_fp.get("branch") == fp.get("branch")
            or not source_fp.get("head")
            or not fp.get("head")
        ):
            duplicate_roles.append(role)

    return {
        "path": str(sibling),
        "name": name,
        "classification": "sibling_candidate" if confidence >= 0.5 else "unrelated_or_unknown",
        "confidence": confidence,
        "reason": reason,
        "size_bytes": compute_dir_bytes(sibling),
        "modified_at": safe_timestamp(sibling),
        "repository_fingerprint": fp,
        "duplicate_checkout_roles": duplicate_roles,
    }


def build_classification_packet(
    project_id: str,
    sibling_candidates: list[dict[str, Any]],
    duplicate_signals: list[dict[str, Any]],
    navigation_orphans: dict[str, Any],
) -> list[dict[str, Any]]:
    packet: list[dict[str, Any]] = []

    for sibling in sibling_candidates:
        packet.append({
            "project_id": project_id,
            "kind": "sibling_candidate",
            "path_or_identifier": sibling.get("path", ""),
            "current_classification": sibling.get("classification", "unclassified"),
            "confidence": sibling.get("confidence", 0.0),
            "evidence": {
                "name": sibling.get("name"),
                "reason": sibling.get("reason"),
                "size_bytes": sibling.get("size_bytes"),
                "modified_at": sibling.get("modified_at"),
            },
            "next_owner": "workspace-doctor",
            "next_action": "Review sibling evidence and decide archive/archive restore/canonicalization action.",
            "automatic_move_allowed": False,
        })

    for signal in duplicate_signals:
        packet.append({
            "project_id": project_id,
            "kind": "duplicate_repository_signal",
            "path_or_identifier": signal.get("sibling", ""),
            "current_classification": "duplicate_repository_detected",
            "confidence": 0.9,
            "evidence": {
                "duplicate_of_roles": signal.get("duplicate_of_roles", []),
                "remote_count": len(
                    (signal.get("repository_fingerprint") or {}).get("remote_urls", [])
                ),
            },
            "next_owner": "workspace-doctor",
            "next_action": "Resolve duplicate repository signals before any cleanup or archive action.",
            "automatic_move_allowed": False,
        })

    for target in navigation_orphans.get("project_index_broken_links", []) or []:
        packet.append({
            "project_id": project_id,
            "kind": "orphaned_project_index_link",
            "path_or_identifier": str(target),
            "current_classification": "navigation_orphan",
            "confidence": 1.0,
            "evidence": {
                "source": "PROJECT_INDEX.md",
            },
            "next_owner": "architect",
            "next_action": "Remove or repair the broken index reference in PROJECT_INDEX.md.",
            "automatic_move_allowed": False,
        })

    for target in navigation_orphans.get("documentation_manifest_orphan_documents", []) or []:
        packet.append({
            "project_id": project_id,
            "kind": "orphaned_manifest_document",
            "path_or_identifier": str(target),
            "current_classification": "manifest_orphan",
            "confidence": 1.0,
            "evidence": {
                "source": "DOCUMENTATION_MANIFEST.json",
            },
            "next_owner": "architect",
            "next_action": "Drop orphaned document entry or add missing doc file.",
            "automatic_move_allowed": False,
        })

    # Keep report compact by defaulting to top-N highest-confidence items
    packet.sort(key=lambda item: float(item.get("confidence") or 0.0), reverse=True)
    return packet[:24]


def scan_checkouts(project: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    checkouts = project.get("checkouts") if isinstance(project.get("checkouts"), dict) else {}
    branches = project.get("branches") if isinstance(project.get("branches"), dict) else {}
    reports: list[dict[str, Any]] = []
    deductions: list[dict[str, Any]] = []
    workspace_root = str(project.get("workspace_root") or "")

    for role in CHECKOUT_ROLES:
        raw_path = str(checkouts.get(role) or "")
        path = Path(raw_path).expanduser() if raw_path else None
        expected_branch = str(branches.get(role) or "").removeprefix("origin/")
        worktrees = git_worktree_list(path) if path is not None else []
        fingerprint = repository_fingerprint(path) if path is not None else {"path": "", "exists": False, "is_git_worktree": False}
        is_worktree = fingerprint.get("is_git_worktree", False)
        item = {
            "role": role,
            "path": str(path) if path is not None else "",
            "exists": path.exists() if path is not None else False,
            "inside_workspace": project_registry.path_is_under(str(path), workspace_root) if workspace_root and path is not None else None,
            "is_git_worktree": is_worktree,
            "branch": fingerprint.get("branch", ""),
            "head": fingerprint.get("head", ""),
            "dirty_entries": git_dirty(path) if path is not None and path.exists() and is_worktree else [],
            "git_remotes": git_remotes(path) if path is not None and path.exists() else [],
            "worktrees": worktrees,
            "worktree_entry": git_worktree_entry(path, worktrees) if path is not None else {},
            "repository_fingerprint": fingerprint,
        }
        if not item["path"]:
            deductions.append(issue(f"{role}_checkout_missing_path", 10, item, "architect", f"Configure {role} checkout in Registry v2."))
        elif not item["exists"]:
            deductions.append(issue(f"{role}_checkout_missing", 10, item, "workspace-doctor", f"Provision the {role} checkout."))
        elif item["inside_workspace"] is False:
            deductions.append(issue(f"{role}_checkout_escapes_workspace", 25, item, "architect", "Fix Registry path containment before automation writes."))
        elif not item["is_git_worktree"]:
            deductions.append(issue(f"{role}_checkout_not_git", 15, item, "workspace-doctor", f"Replace or adopt {role} as a git worktree."))
        elif expected_branch and item["branch"] and item["branch"] != expected_branch:
            deductions.append(issue(f"{role}_branch_mismatch", 8, item, "integrator", f"Switch or recreate {role} checkout on {expected_branch}."))
        if item["dirty_entries"]:
            deductions.append(issue(f"{role}_checkout_dirty", 8, item["dirty_entries"], "integrator", f"Preserve and classify dirty changes in {role}."))
        reports.append(item)

    return reports, deductions


def scan_docs_and_state(root: Path, project: Dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    deductions: list[dict[str, Any]] = []
    version = project_version_gate.validate_version(root, version_file=str(project.get("version_file") or "PROJECT_VERSION.json"), expected_branch_role=None, require=False)
    project_index = root / str(project.get("project_index") or "PROJECT_INDEX.md")
    documentation_manifest = root / str(project.get("documentation_manifest") or "DOCUMENTATION_MANIFEST.json")
    task_manager = root / DEFAULT_TASK_MANAGER

    if not version.get("ok"):
        deductions.append(issue("project_version_invalid", 10, version.get("errors"), "architect", "Repair PROJECT_VERSION.json."))
    elif not version.get("exists"):
        deductions.append(issue("project_version_missing", 5, version.get("warnings"), "architect", "Add PROJECT_VERSION.json before strict gates."))

    if not project_index.is_file():
        deductions.append(issue("project_index_missing", 5, str(project_index), "architect", "Create PROJECT_INDEX.md."))

    if not documentation_manifest.is_file():
        deductions.append(issue("documentation_manifest_missing", 5, str(documentation_manifest), "architect", "Create DOCUMENTATION_MANIFEST.json."))

    if not task_manager.is_dir():
        deductions.append(issue("task_manager_missing", 10, str(task_manager), "dispatcher", "Install or migrate AiStudio/Task_manager state."))
    else:
        for name in ("task_queue.json", "agent_locks.json", "owner_directives.json"):
            if not (task_manager / name).is_file():
                deductions.append(issue(f"{name.removesuffix('.json')}_missing", 4, str(task_manager / name), "dispatcher", f"Restore {name}."))

    project_index_orphans = scan_markdown_orphans(project_index)
    if project_index_orphans:
        deductions.append(issue("project_index_broken_links", 4, project_index_orphans, "workspace-doctor", "Fix missing docs links in PROJECT_INDEX.md."))

    manifest_orphans: list[str] = []
    manifest_resolved_replacements: list[dict[str, str]] = []
    if documentation_manifest.is_file():
        manifest_payload = load_json(documentation_manifest)
        docs = manifest_payload.get("documents") if isinstance(manifest_payload, dict) else None
        if isinstance(docs, list):
            for entry in docs:
                rel = entry if isinstance(entry, str) else None
                if isinstance(entry, dict):
                    rel = entry.get("path")
                if isinstance(rel, str) and rel and not (root / rel).exists():
                    status = str(entry.get("status") or "").strip().lower() if isinstance(entry, dict) else ""
                    replacement = str(entry.get("replaced_by") or "").strip() if isinstance(entry, dict) else ""
                    if status in {"legacy", "deprecated"} and replacement and (root / replacement).exists():
                        manifest_resolved_replacements.append({"path": rel, "replaced_by": replacement, "status": status})
                    else:
                        manifest_orphans.append(rel)
        elif manifest_payload is not None:
            deductions.append(issue("documentation_manifest_invalid", 3, str(documentation_manifest), "architect", "Fix DOCUMENTATION_MANIFEST.json: expected documents array."))

    if manifest_orphans:
        deductions.append(issue("documentation_manifest_orphan_documents", 4, manifest_orphans, "workspace-doctor", "Fix dangling DOCUMENTATION_MANIFEST.json entries."))

    return {
        "project_version": version,
        "project_index_exists": project_index.is_file(),
        "documentation_manifest_exists": documentation_manifest.is_file(),
        "task_manager_exists": task_manager.is_dir(),
        "navigation_orphans": {
            "project_index_broken_links": project_index_orphans,
            "documentation_manifest_orphan_documents": manifest_orphans,
            "documentation_manifest_resolved_replacements": manifest_resolved_replacements,
        },
    }, deductions


def scan_project(project: dict[str, Any], devops_root: Path | None = None) -> dict[str, Any]:
    pid = str(project.get("project_id") or project.get("name") or "unknown")
    workspace_root = Path(str(project.get("workspace_root") or project.get("local_path") or "")).expanduser()
    command_root = Path(str(project.get("automation_path") or project.get("local_path") or "")).expanduser()
    deductions: list[dict[str, Any]] = []

    warnings = project_registry.project_path_warnings(project)
    for warning in warnings:
        deductions.append(issue("registry_path_warning", 15, warning, "architect", "Repair Registry v2 paths."))

    if not workspace_root.exists():
        deductions.append(issue("workspace_root_missing", 15, str(workspace_root), "workspace-doctor", "Provision the registered workspace root."))
    elif not workspace_root.is_dir():
        deductions.append(issue("workspace_root_not_directory", 25, str(workspace_root), "workspace-doctor", "Replace invalid workspace root with a directory."))

    if command_root.exists() and not is_git_worktree(command_root):
        deductions.append(issue("command_root_not_git", 15, str(command_root), "dispatcher", "Configure automation_path to a git worktree."))

    checkouts, checkout_deductions = scan_checkouts(project)
    deductions.extend(checkout_deductions)
    docs_state, docs_deductions = scan_docs_and_state(command_root, project) if command_root.exists() else ({}, [])
    deductions.extend(docs_deductions)

    checkout_fingerprints = {
        item["role"]: dict(item.get("repository_fingerprint") or {})
        for item in checkouts
        if isinstance(item.get("repository_fingerprint"), dict)
    }

    explicit_workspace_root = project.get("workspace_root")
    sibling_root = devops_root or (workspace_root.parent if explicit_workspace_root else None)
    siblings: list[dict[str, Any]] = []
    duplicate_signals: list[dict[str, Any]] = []
    if sibling_root is not None and sibling_root.exists() and sibling_root.is_dir():
        for sibling in sorted(sibling_root.iterdir(), key=lambda path: path.name.lower()):
            if sibling == workspace_root or not sibling.is_dir():
                continue
            classified = classify_sibling(pid, workspace_root, sibling, checkout_fingerprints)
            if classified["classification"] == "sibling_candidate":
                siblings.append(classified)
            if classified["duplicate_checkout_roles"]:
                duplicate_signals.append({
                    "sibling": str(sibling),
                    "duplicate_of_roles": classified["duplicate_checkout_roles"],
                    "repository_fingerprint": classified["repository_fingerprint"],
                })

        if siblings:
            deductions.append(issue("legacy_sibling_folders", 8, siblings, "workspace-doctor", "Review sibling candidates and create cleanup/archive plan."))
        if duplicate_signals:
            deductions.append(issue("duplicate_repository_signals", 12, duplicate_signals, "workspace-doctor", "Review duplicate repository fingerprints and route cleanup."))

    agent_classification_packet = build_classification_packet(
        pid,
        sibling_candidates=siblings,
        duplicate_signals=duplicate_signals,
        navigation_orphans=(docs_state.get("navigation_orphans", {}) if isinstance(docs_state, dict) else {}),
    )

    score = max(0, 100 - sum(int(item.get("points") or 0) for item in deductions))
    threshold = int(project.get("health_threshold") or 85)
    return {
        "project_id": pid,
        "workspace_root": str(workspace_root),
        "command_root": str(command_root),
        "health_score": score,
        "health_threshold": threshold,
        "status": "healthy" if score >= threshold else "attention",
        "quarantine_mode": project.get("quarantine_mode") or "advisory",
        "hard_block": False,
        "deductions": deductions,
        "sibling_candidates": siblings,
        "duplicate_repository_signals": duplicate_signals,
        "agent_classification_packet": agent_classification_packet,
        "checkouts": checkouts,
        "docs_state": docs_state,
    }


def build_report(registry_path: Path, *, devops_root: Path | None = None, project_id: str | None = None) -> dict[str, Any]:
    projects, registry_warnings = project_registry.load_projects(registry_path, project_id=project_id, include_disabled=False)
    reports = [scan_project(project, devops_root=devops_root) for project in projects]
    agent_classification_packet = [item for report in reports for item in (report.get("agent_classification_packet", []) or [])]
    return {
        "schema_version": "1.0",
        "mode": "workspace_doctor",
        "registry": str(registry_path),
        "devops_root": str(devops_root) if devops_root else None,
        "project_count": len(reports),
        "attention_count": sum(1 for item in reports if item["status"] != "healthy"),
        "average_health_score": round(sum(item["health_score"] for item in reports) / len(reports), 2) if reports else None,
        "registry_warnings": registry_warnings,
        "agent_classification_packet": agent_classification_packet,
        "projects": reports,
    }


def build_action_payload(
    report: dict[str, Any],
    *,
    registry_path: Path,
    devops_root: Path | None = None,
    project_id: str | None = None,
    started_at: str | None = None,
) -> dict[str, Any]:
    attention_count = int(report.get("attention_count") or 0)
    result = "no_op" if attention_count == 0 else "blocked"
    projects = [item for item in report.get("projects", []) if isinstance(item, dict)]
    affected_paths = sorted({
        path
        for project in projects
        for path in (project.get("workspace_root"), project.get("command_root"))
        if isinstance(path, str) and path
    })
    failed = [
        {
            "action": "health_attention",
            "project_id": project.get("project_id"),
            "status": project.get("status"),
            "health_score": project.get("health_score"),
            "deduction_count": len(project.get("deductions", []) if isinstance(project.get("deductions"), list) else []),
        }
        for project in projects
        if project.get("status") != "healthy"
    ]
    validation = {
        "ok": attention_count == 0,
        "attention_count": attention_count,
        "project_count": int(report.get("project_count") or 0),
        "registry_warning_count": len(report.get("registry_warnings", []) if isinstance(report.get("registry_warnings"), list) else []),
    }
    next_owner = "workspace-doctor" if attention_count else "dispatcher"
    next_action = (
        "Review deductions and route cleanup/rebuild/registry repair actions."
        if attention_count
        else "No workspace doctor action required."
    )
    return build_action_report(
        action_id="workspace-doctor.scan",
        action_type="workspace.doctor.scan",
        project_id=project_id or ("multiple" if len(projects) != 1 else str(projects[0].get("project_id") or "unknown")),
        actor="workspace-doctor-cli",
        source="scripts/agent_control/project_doctor.py",
        mode="dry_run",
        result=result,
        next_owner=next_owner,
        next_action=next_action,
        started_at=started_at,
        input_refs=[
            "project_doctor.py",
            f"registry={registry_path}",
            f"project_id_filter={project_id or 'all'}",
            *([f"devops_root={devops_root}"] if devops_root else []),
        ],
        before_state={
            "registry": str(registry_path),
            "devops_root": str(devops_root) if devops_root else None,
            "project_id_filter": project_id,
        },
        after_state={
            "mode": report.get("mode"),
            "project_count": report.get("project_count"),
            "attention_count": attention_count,
            "average_health_score": report.get("average_health_score"),
        },
        actions_planned=[
            {"action": "scan_workspace_health", "project_id": project.get("project_id")}
            for project in projects
        ],
        actions_executed=[
            {"action": "scan_workspace_health", "project_id": project.get("project_id"), "status": project.get("status")}
            for project in projects
        ],
        actions_failed=failed,
        affected_paths=affected_paths,
        validation=validation,
        artifacts=[],
        rollback={"required": False, "reason": "Workspace Doctor is read-only."},
        residual_risks=[
            f"{item.get('project_id')}: {item.get('deduction_count')} deductions"
            for item in failed
        ],
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--devops-root", type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--action-report-output", type=Path, help="Path to write Universal Action Report JSON.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    registry_path = args.registry.expanduser()
    devops_root = args.devops_root.expanduser() if args.devops_root else None
    report = build_report(registry_path, devops_root=devops_root, project_id=args.project_id)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        for project in report["projects"]:
            print(f"{project['project_id']}: {project['health_score']} ({project['status']})")
    if args.action_report_output:
        payload = build_action_payload(report, registry_path=registry_path, devops_root=devops_root, project_id=args.project_id)
        validation = validate_action_report(payload)
        if not validation["ok"]:
            raise SystemExit(f"action report validation failed: {validation['errors']}")
        write_json_atomic(args.action_report_output.expanduser(), payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
