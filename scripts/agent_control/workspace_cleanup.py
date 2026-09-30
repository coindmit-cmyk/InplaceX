#!/usr/bin/env python3
"""Archive-only workspace cleanup and restore planner."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections.abc import Iterable
from datetime import datetime, timezone
import shutil
import subprocess
from pathlib import Path
from typing import Any

import project_doctor
import project_registry
from action_report import build_report as build_action_report, validate_report as validate_action_report


def run_git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(["git", *args], cwd=repo, text=True, capture_output=True, check=False, timeout=20)
    except (OSError, subprocess.SubprocessError):
        return None


def git_summary(path: Path) -> dict[str, Any]:
    inside = run_git(path, ["rev-parse", "--is-inside-work-tree"]) if path.exists() else None
    is_worktree = bool(inside and inside.returncode == 0 and inside.stdout.strip().lower() == "true")
    if not is_worktree:
        return {"is_git_worktree": False, "branch": "", "head": "", "dirty_entry_count": 0, "dirty_entries_sample": []}
    branch = run_git(path, ["branch", "--show-current"])
    head = run_git(path, ["rev-parse", "HEAD"])
    dirty = run_git(path, ["status", "--porcelain"])
    dirty_entries = [line for line in (dirty.stdout if dirty and dirty.returncode == 0 else "").splitlines() if line.strip()]
    return {
        "is_git_worktree": True,
        "branch": branch.stdout.strip() if branch and branch.returncode == 0 else "",
        "head": head.stdout.strip() if head and head.returncode == 0 else "",
        "dirty_entry_count": len(dirty_entries),
        "dirty_entries_sample": dirty_entries[:20],
    }


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def file_timestamp_utc(path: Path, *, field: str) -> str | None:
    try:
        stat = path.stat()
    except OSError:
        return None
    raw = getattr(stat, field, None)
    if raw is None:
        return None
    try:
        return datetime.fromtimestamp(raw, tz=timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    except OSError:
        return None


def directory_inventory(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"size": 0, "sha256": None}
    if path.is_file():
        return {"size": path.stat().st_size, "sha256": sha256_file(path)}
    files = file_manifest(path)
    total = sum(item.get("size", 0) for item in files if isinstance(item.get("size"), int))
    h = hashlib.sha256()
    for item in files:
        h.update(f"{item.get('path')}\0{item.get('size')}\0{item.get('sha256')}\n".encode("utf-8"))
    return {
        "inventory_size": total,
        "inventory_hash": h.hexdigest(),
    }


def _collect_targets(base: Path) -> list[Path]:
    if not base.exists() or not base.is_dir():
        return []
    try:
        return sorted(base.iterdir(), key=lambda path: path.name.lower())
    except OSError:
        return []


def build_move(
    *,
    project_id: str,
    source: Path,
    target: Path,
    classification: str,
    reason: str,
    confidence: float | None = None,
    apply_allowed: bool = False,
) -> dict[str, Any]:
    git = git_summary(source)
    inventory = directory_inventory(source)
    return {
        "source": str(source),
        "target": str(target),
        "classification": classification,
        "project_id": project_id,
        "confidence": confidence,
        "size": inventory.get("size"),
        "sha256": inventory.get("sha256"),
        "inventory_size": inventory.get("inventory_size"),
        "inventory_hash": inventory.get("inventory_hash"),
        "source_created_at": file_timestamp_utc(source, field="st_ctime"),
        "source_modified_at": file_timestamp_utc(source, field="st_mtime"),
        "reason": reason,
        "approved_by": None,
        "restore_procedure": (
            "Run python scripts/agent_control/workspace_cleanup.py "
            "--restore-manifest <manifest> --restore-root <staging-target> --apply"
        ),
        "retention_metadata": move_retention_metadata(),
        "mode": "archive",
        "source_git": git,
        "requires_manual_git_archive": bool(git.get("is_git_worktree")),
        "apply_allowed": bool(apply_allowed),
    }


def build_category_counter() -> dict[str, dict[str, int]]:
    return {
        "sibling_folders": {"candidate_count": 0, "apply_count": 0},
        "stale_worktrees": {"candidate_count": 0, "apply_count": 0},
        "duplicate_checkout_hints": {"candidate_count": 0, "apply_count": 0},
        "runtime_log_cache": {"candidate_count": 0, "apply_count": 0},
        "build_import_export": {"candidate_count": 0, "apply_count": 0},
        "legacy_task_manager_paths": {"candidate_count": 0, "apply_count": 0},
        "obsolete_generated_reports": {"candidate_count": 0, "apply_count": 0},
        "old_docs": {"candidate_count": 0, "apply_count": 0},
    }


def _append_move(
    *,
    moves: list[dict[str, Any]],
    category_summary: dict[str, dict[str, int]],
    category: str,
    move: dict[str, Any],
    seen: set[str],
) -> None:
    source = move.get("source")
    if not isinstance(source, str):
        return
    key = source
    if key in seen:
        return
    seen.add(key)
    moves.append(move)
    category_summary.setdefault(category, {"candidate_count": 0, "apply_count": 0})
    category_summary[category]["candidate_count"] += 1
    if move.get("apply_allowed"):
        category_summary[category]["apply_count"] += 1


def collect_runtime_candidates(project_root: Path) -> list[Path]:
    candidates: list[Path] = []
    runtime_root = project_root / "runtime"
    if not runtime_root.exists():
        return candidates
    for relative in ("runs", "logs", "cache", "command-state"):
        candidate = runtime_root / relative
        if candidate.exists() and any(candidate.iterdir()):
            candidates.append(candidate)
    if runtime_root.exists() and any(runtime_root.iterdir()) and (runtime_root / "runs").exists():
        return candidates
    return candidates


def collect_build_import_export_candidates(project_root: Path) -> list[Path]:
    candidates: list[Path] = []
    for relative in ("builds", "imports", "exports"):
        candidate = project_root / "temp" / relative
        if candidate.exists() and any(candidate.iterdir()):
            candidates.append(candidate)
    return candidates


def collect_stale_worktree_candidates(project_root: Path) -> list[Path]:
    candidates: list[Path] = []
    candidate_root = project_root / "temp" / "worktrees"
    for candidate in _collect_targets(candidate_root):
        if candidate.is_dir() and any(candidate.iterdir()):
            candidates.append(candidate)
    return candidates


def collect_legacy_task_manager_candidates(project_root: Path) -> list[Path]:
    candidates: list[Path] = []
    tokens = ("task_manager", "taskmanager", "task-manager")
    for candidate in _collect_targets(project_root):
        lowered = candidate.name.lower()
        if candidate.is_dir() and any(token in lowered for token in tokens) and lowered != "task_manager":
            candidates.append(candidate)
    return candidates


def collect_obsolete_report_or_doc_candidates(project_root: Path) -> tuple[list[Path], list[Path]]:
    reports = project_root / "docs" / "reports"
    docs = project_root / "docs"
    report_candidates: list[Path] = []
    doc_candidates: list[Path] = []
    report_tokens = ("generated", "legacy", "obsolete", "deprecated", "old")
    doc_tokens = ("legacy", "deprecated", "obsolete", "old")
    for candidate in _collect_targets(reports):
        lowered = candidate.name.lower()
        if candidate.name.startswith("."):
            continue
        if any(token in lowered for token in report_tokens):
            report_candidates.append(candidate)
    for candidate in _collect_targets(docs):
        if candidate == reports:
            continue
        lowered = candidate.name.lower()
        if candidate.name.startswith("."):
            continue
        if any(token in lowered for token in doc_tokens):
            doc_candidates.append(candidate)
    return report_candidates, doc_candidates


def move_retention_metadata() -> dict[str, Any]:
    return {
        "retention_policy": "manual_review_required",
        "delete_scheduled": False,
        "deletion_requires_owner_approval": True,
        "delete_after_days": None,
    }


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def file_manifest(root: Path) -> list[dict[str, Any]]:
    files: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*"), key=lambda p: p.as_posix()):
        if path.is_file():
            files.append({
                "path": path.relative_to(root).as_posix(),
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            })
    return files


def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _collect_action_paths(report: dict[str, Any], operation: str) -> tuple[list[str], list[str]]:
    affected: set[str] = set()
    artifacts: set[str] = set()
    if operation == "restore":
        for field in ("restore_root", "archive_root"):
            value = report.get(field)
            if value:
                affected.add(str(value))
        if report.get("restore_root"):
            artifacts.add(str(report.get("restore_root")))
        return sorted(affected), sorted(artifacts)
    for project in report.get("projects") or []:
        if not isinstance(project, dict):
            continue
        for move in project.get("moves") or []:
            if not isinstance(move, dict):
                continue
            if move.get("source"):
                affected.add(str(move["source"]))
            if move.get("target"):
                affected.add(str(move["target"]))
            if move.get("state") == "archived":
                artifacts.add(str(move.get("target")))
    return sorted(affected), sorted(artifacts)


def _infer_project_id(report: dict[str, Any], project_id: str | None) -> str:
    if project_id:
        return project_id
    projects = [str(item.get("project_id") or "") for item in report.get("projects") or [] if isinstance(item, dict)]
    return projects[0] if projects else "workspace-cleanup"


def _build_default_next_action(blockers: list[str], operation: str) -> tuple[str, str]:
    if not blockers:
        return "none", "No follow-up required for workspace cleanup."
    reason = blockers[0] or "cleanup blocked"
    return (
        "owner",
        (
            f"Resolve cleanup blocker and rerun workspace_cleanup in {operation} mode: {reason}"
            if operation == "restore"
            else f"Resolve blocked cleanup actions and rerun with --apply: {reason}"
        ),
    )


def _collect_blockers(report: dict[str, Any], operation: str) -> list[str]:
    if operation == "restore":
        if report.get("ok") is False and report.get("reason"):
            return [str(report.get("reason"))]
        return []
    blocked: list[str] = []
    for project in report.get("projects") or []:
        if not isinstance(project, dict):
            continue
        for move in project.get("moves") or []:
            if move.get("state") == "blocked":
                source = str(move.get("source") or "unknown source")
                reason = str(move.get("reason") or "blocked")
                blocked.append(f"{source}: {reason}")
    return blocked


def _build_action_payload(
    *,
    operation: str,
    args: argparse.Namespace,
    report: dict[str, Any],
    started_at: str | None = None,
) -> dict[str, Any]:
    apply_requested = bool(getattr(args, "apply", False))
    cleanup_failed = int(report.get("failed_count") or 0) if operation != "restore" else 0
    is_blocked = bool(report.get("ok") is False) or (operation == "apply" and cleanup_failed > 0)
    result = "blocked" if is_blocked else ("no_op" if int(report.get("move_count") or report.get("file_count") or 0) == 0 else "succeeded")
    blockers = _collect_blockers(report, operation if operation != "apply" else "apply")
    next_owner, next_action = _build_default_next_action(blockers, operation)
    affected_paths, artifacts = _collect_action_paths(report, operation)

    planned: list[dict[str, Any]] = []
    executed: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    failed: list[dict[str, Any]] = []
    if operation == "restore":
        planned.append({
            "action": "restore_manifest",
            "manifest_path": str(args.restore_manifest),
            "restore_root": str(args.restore_root),
            "apply": apply_requested,
        })
        if report.get("ok"):
            if apply_requested:
                executed.append({
                    "action": "restore_manifest",
                    "result": "restored",
                    "file_count": int(report.get("file_count") or 0),
                })
            else:
                skipped.append({"action": "restore_manifest", "result": "dry_run"})
        else:
            failed.append({
                "action": "restore_manifest",
                "result": report.get("reason"),
            })
    else:
        for project in report.get("projects") or []:
            if not isinstance(project, dict):
                continue
            planned_count = len(project.get("moves") or [])
            project_id = project.get("project_id") or "unknown"
            planned.append({"action": "cleanup_project_plan", "project_id": project_id, "planned_moves": planned_count})
            for move in project.get("moves") or []:
                state = str(move.get("state") or "unknown")
                if state == "archived":
                    executed.append({"action": "archive", "project_id": project_id, "source": move.get("source"), "target": move.get("target")})
                elif state == "planned":
                    skipped.append({"action": "archive_planned", "project_id": project_id, "source": move.get("source"), "target": move.get("target")})
                elif state in {"blocked", "skipped_missing", "blocked_manual"}:
                    failed.append({"action": "archive", "project_id": project_id, "state": state, "source": move.get("source"), "target": move.get("target")})
                else:
                    skipped.append({"action": "archive_skipped", "project_id": project_id, "state": state, "source": move.get("source"), "target": move.get("target")})

    before_state = {
        "command": "workspace_cleanup",
        "operation": operation,
        "registry": str(args.registry) if args.registry else None,
        "project_id_filter": args.project_id,
        "devops_root": str(args.devops_root) if args.devops_root else None,
        "restore_root": str(args.restore_root) if args.restore_root else None,
    }
    after_state = {
        "mode": report.get("mode"),
        "apply": bool(report.get("apply")),
        "failed_count": cleanup_failed if operation != "restore" else None,
        "project_count": int(report.get("project_count") or 0),
        "move_count": int(report.get("move_count") or 0),
        "file_count": int(report.get("file_count") or 0),
        "registry_warnings": report.get("registry_warnings", []),
        "ok": bool(report.get("ok", True)),
    }
    report_payload = build_action_report(
        action_id=f"workspace-cleanup.{operation}",
        action_type="workspace.cleanup",
        project_id=_infer_project_id(report, args.project_id),
        actor="workspace-cleanup-cli",
        mode="apply" if (operation == "apply" and apply_requested) or (operation == "restore" and apply_requested) else "dry_run",
        result=result,
        next_owner=next_owner,
        next_action=next_action,
        started_at=started_at or now_utc(),
        input_refs=[
            "workspace_cleanup.py",
            f"operation={operation}",
            "project_id_filter={}".format(args.project_id or "all"),
            *([f"registry={args.registry}"] if args.registry else []),
            *([f"devops_root={args.devops_root}"] if args.devops_root else []),
            *([f"restore_root={args.restore_root}"] if args.restore_root else []),
        ],
        before_state=before_state,
        after_state=after_state,
        actions_planned=planned,
        actions_executed=executed,
        actions_skipped=skipped,
        actions_failed=failed,
        affected_paths=affected_paths,
        validation={"ok": not is_blocked, "blocked_count": len(blockers), "failed_count": cleanup_failed},
        artifacts=artifacts,
        rollback={},
        residual_risks=blockers,
    )
    return report_payload


def plan_cleanup(registry_path: Path, *, project_id: str | None = None, devops_root: Path | None = None) -> dict[str, Any]:
    projects, registry_warnings = project_registry.load_projects(registry_path, project_id=project_id)
    items: list[dict[str, Any]] = []
    for project in projects:
        doctor = project_doctor.scan_project(project, devops_root=devops_root)
        archive_root = Path(str(project.get("workspace_root") or project.get("local_path") or "")).expanduser() / "archive" / "cleanup"
        moves: list[dict[str, Any]] = []
        category_summary = build_category_counter()
        seen: set[str] = set()
        workspace_root = Path(str(project.get("workspace_root") or project.get("local_path") or "")).expanduser()

        for sibling in doctor.get("sibling_candidates") or []:
            if not isinstance(sibling, dict) or float(sibling.get("confidence") or 0) < 0.8:
                continue
            source = Path(str(sibling.get("path") or ""))
            target = archive_root / "legacy-workspaces" / source.name
            move = build_move(
                project_id=str(project.get("project_id") or ""),
                source=source,
                target=target,
                classification=str(sibling.get("classification") or "sibling_candidate"),
                reason=sibling.get("reason") or "high_confidence_sibling",
                confidence=float(sibling.get("confidence") or 0.0),
                apply_allowed=True,
            )
            _append_move(moves=moves, category_summary=category_summary, category="sibling_folders", move=move, seen=seen)

        duplicate_signals = doctor.get("duplicate_repository_signals") or []
        for signal in duplicate_signals:
            sibling_path = str(signal.get("sibling") or "")
            if not sibling_path:
                continue
            source = Path(sibling_path)
            target = archive_root / "duplicate-checkouts" / source.name
            move = build_move(
                project_id=str(project.get("project_id") or ""),
                source=source,
                target=target,
                classification="duplicate_checkout_hint",
                reason="duplicate_checkout_hint",
                confidence=0.9,
                apply_allowed=False,
            )
            _append_move(
                moves=moves,
                category_summary=category_summary,
                category="duplicate_checkout_hints",
                move=move,
                seen=seen,
            )

        for candidate in collect_stale_worktree_candidates(workspace_root):
            source = candidate
            target = archive_root / "stale-worktrees" / source.name
            _append_move(
                moves=moves,
                category_summary=category_summary,
                category="stale_worktrees",
                move=build_move(
                    project_id=str(project.get("project_id") or ""),
                    source=source,
                    target=target,
                    classification="stale_worktree",
                    reason="stale_worktree_candidate",
                    confidence=None,
                    apply_allowed=False,
                ),
                seen=seen,
            )

        for candidate in collect_runtime_candidates(workspace_root):
            source = candidate
            target = archive_root / "old-runtime" / source.name
            _append_move(
                moves=moves,
                category_summary=category_summary,
                category="runtime_log_cache",
                move=build_move(
                    project_id=str(project.get("project_id") or ""),
                    source=source,
                    target=target,
                    classification="runtime_log_cache",
                    reason="runtime_artifact_candidate",
                    confidence=None,
                    apply_allowed=False,
                ),
                seen=seen,
            )

        for candidate in collect_build_import_export_candidates(workspace_root):
            source = candidate
            target = archive_root / "old-build-import-export" / source.name
            _append_move(
                moves=moves,
                category_summary=category_summary,
                category="build_import_export",
                move=build_move(
                    project_id=str(project.get("project_id") or ""),
                    source=source,
                    target=target,
                    classification="build_import_export_candidate",
                    reason="old_build_import_export_candidate",
                    confidence=None,
                    apply_allowed=False,
                ),
                seen=seen,
            )

        for candidate in collect_legacy_task_manager_candidates(workspace_root):
            source = candidate
            target = archive_root / "legacy-task-manager" / source.name
            _append_move(
                moves=moves,
                category_summary=category_summary,
                category="legacy_task_manager_paths",
                move=build_move(
                    project_id=str(project.get("project_id") or ""),
                    source=source,
                    target=target,
                    classification="legacy_task_manager_path",
                    reason="legacy_task_manager_path",
                    confidence=None,
                    apply_allowed=False,
                ),
                seen=seen,
            )

        obsolete_reports, old_docs = collect_obsolete_report_or_doc_candidates(workspace_root)
        for candidate in obsolete_reports:
            source = candidate
            target = archive_root / "obsolete-reports" / source.name
            _append_move(
                moves=moves,
                category_summary=category_summary,
                category="obsolete_generated_reports",
                move=build_move(
                    project_id=str(project.get("project_id") or ""),
                    source=source,
                    target=target,
                    classification="obsolete_generated_report",
                    reason="obsolete_generated_report_candidate",
                    confidence=None,
                    apply_allowed=False,
                ),
                seen=seen,
            )

        for candidate in old_docs:
            source = candidate
            target = archive_root / "old-docs" / source.name
            _append_move(
                moves=moves,
                category_summary=category_summary,
                category="old_docs",
                move=build_move(
                    project_id=str(project.get("project_id") or ""),
                    source=source,
                    target=target,
                    classification="old_doc",
                    reason="old_doc_candidate",
                    confidence=None,
                    apply_allowed=False,
                ),
                seen=seen,
            )

        items.append({
            "project_id": project.get("project_id"),
            "archive_root": str(archive_root),
            "move_count": len(moves),
            "moves": moves,
            "category_summary": category_summary,
            "delete_enabled": False,
            "doctor_health_score": doctor.get("health_score"),
        })
    return {
        "schema_version": "1.0",
        "mode": "workspace_cleanup_plan",
        "registry": str(registry_path),
        "archive_only": True,
        "automatic_delete_enabled": False,
        "project_count": len(items),
        "move_count": sum(item["move_count"] for item in items),
        "registry_warnings": registry_warnings,
        "projects": items,
    }


def apply_cleanup_plan(plan: dict[str, Any], *, apply: bool = False) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    for project in plan.get("projects") or []:
        if not isinstance(project, dict):
            continue
        project_result = {"project_id": project.get("project_id"), "moves": [], "manifest": None}
        for move in project.get("moves") or []:
            source = Path(str(move.get("source") or "")).expanduser()
            target = Path(str(move.get("target") or "")).expanduser()
            if not isinstance(move, dict):
                continue
            item = dict(move)
            item["state"] = "planned"
            if not source.exists():
                item.update({"state": "skipped_missing"})
            elif target.exists():
                item.update({"state": "blocked", "reason": "archive_target_exists"})
            elif not move.get("apply_allowed", False):
                item.update({"state": "planned", "reason": "apply_not_allowed_for_category"})
            elif (move.get("source_git") or {}).get("is_git_worktree"):
                item.update({"state": "blocked", "reason": "git_worktree_requires_manual_archive"})
            elif apply:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(source), str(target))
                item.update({"state": "archived"})
            project_result["moves"].append(item)
        if apply and any(item["state"] == "archived" for item in project_result["moves"]):
            archive_root = Path(str(project.get("archive_root") or "")).expanduser()
            manifest = {
                "schema_version": "1.0",
                "project_id": project.get("project_id"),
                "archive_root": str(archive_root),
                "moves": project_result["moves"],
                "files": file_manifest(archive_root) if archive_root.exists() else [],
                "restore_supported": True,
                "delete_enabled": False,
            }
            manifest_path = archive_root / "cleanup_manifest.json"
            write_json_atomic(manifest_path, manifest)
            project_result["manifest"] = str(manifest_path)
        results.append(project_result)
    return {
        "schema_version": "1.0",
        "mode": "workspace_cleanup_apply",
        "apply": bool(apply),
        "archive_only": True,
        "failed_count": sum(1 for project in results for item in project["moves"] if item["state"] == "blocked"),
        "projects": results,
    }


def restore_manifest(manifest_path: Path, restore_root: Path, *, apply: bool = False) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if restore_root.exists() and any(restore_root.iterdir()):
        return {"ok": False, "reason": "restore_target_not_empty", "restore_root": str(restore_root)}
    archive_root = Path(str(manifest.get("archive_root") or "")).expanduser()
    files = manifest.get("files") if isinstance(manifest.get("files"), list) else []
    checks: list[dict[str, Any]] = []
    for item in files:
        if not isinstance(item, dict):
            continue
        source = archive_root / str(item.get("path") or "")
        ok = source.is_file() and sha256_file(source) == item.get("sha256")
        checks.append({"path": item.get("path"), "ok": ok})
    if not all(item["ok"] for item in checks):
        return {"ok": False, "reason": "archive_checksum_mismatch", "checks": checks}
    if apply:
        for item in files:
            source = archive_root / str(item.get("path") or "")
            target = restore_root / str(item.get("path") or "")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    return {"ok": True, "apply": bool(apply), "restore_root": str(restore_root), "file_count": len(files), "checks": checks}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--devops-root", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--restore-manifest", type=Path)
    parser.add_argument("--restore-root", type=Path)
    parser.add_argument("--action-report-output", type=Path, help="Path to write Universal Action Report JSON.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    started_at = now_utc()
    action_report_payload: dict[str, Any] | None = None
    if args.restore_manifest:
        if not args.restore_root:
            raise SystemExit("--restore-root is required with --restore-manifest")
        report = restore_manifest(args.restore_manifest.expanduser(), args.restore_root.expanduser(), apply=args.apply)
        action_report_payload = _build_action_payload(operation="restore", args=args, report=report, started_at=started_at)
    else:
        if not args.registry:
            raise SystemExit("--registry is required")
        plan = plan_cleanup(
            args.registry.expanduser(),
            project_id=args.project_id,
            devops_root=args.devops_root.expanduser() if args.devops_root else None,
        )
        if args.apply:
            report = apply_cleanup_plan(plan, apply=True)
            action_report_payload = _build_action_payload(operation="apply", args=args, report=report, started_at=started_at)
        else:
            report = plan
            action_report_payload = _build_action_payload(operation="plan", args=args, report=report, started_at=started_at)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"{report.get('mode')}: {report.get('move_count', report.get('file_count', 0))}")
    if args.action_report_output:
        if action_report_payload is None:
            raise SystemExit("unable to build workspace cleanup action report")
        validation = validate_action_report(action_report_payload)
        if not validation["ok"]:
            raise SystemExit(f"action report validation failed: {validation['errors']}")
        write_json_atomic(args.action_report_output.expanduser(), action_report_payload)
    return 0 if report.get("ok", True) is not False and int(report.get("failed_count") or 0) == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
