#!/usr/bin/env python3
"""Build a dashboard snapshot on a worker host and optionally upload it.

The VPS dashboard can run in mirror mode from this uploaded latest.json file.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import shlex
import sqlite3
import subprocess
import time
from pathlib import Path

import collect_remote_automation_status
import runner_readiness_report
from remote_dashboard_stub import (
    CODEX_LIMIT_STALE_MINUTES_DEFAULT,
    DASHBOARD_SNAPSHOT_RETENTION_DEFAULT,
    PROJECT_SNAPSHOT_RETENTION_DEFAULT,
    SYSTEM_RESOURCE_ACTIVITY_WINDOW_MINUTES_DEFAULT,
    SYSTEM_RESOURCE_SAMPLE_INTERVAL_SECONDS_DEFAULT,
    SYSTEM_RESOURCE_SAMPLE_RETENTION_HOURS_DEFAULT,
    augment_added_counts_from_history,
    augment_system_resource_load,
    build_snapshot,
    clean_old_system_resource_samples,
    init_db,
    record_system_resource_sample,
    store_snapshot,
    utc_now,
)


DASHBOARD_TRANSIENT_PATTERNS = (
    "analytics.sqlite.precompact-*",
    "analytics.sqlite.*.tmp",
    "latest.json.*.tmp",
)
DASHBOARD_TRANSIENT_MAX_AGE_SECONDS = 6 * 60 * 60
DASHBOARD_TRANSIENT_MAX_BYTES = 512 * 1024 * 1024


def _fresh(path: Path, max_age_seconds: int) -> bool:
    if max_age_seconds <= 0:
        return False
    try:
        return time.time() - path.stat().st_mtime <= max_age_seconds
    except OSError:
        return False


def cleanup_dashboard_transient_files(
    db_path: Path,
    output_path: Path | None = None,
    *,
    max_age_seconds: int = DASHBOARD_TRANSIENT_MAX_AGE_SECONDS,
    max_bytes: int = DASHBOARD_TRANSIENT_MAX_BYTES,
) -> dict:
    dashboard_dir = db_path.expanduser().parent
    patterns = set(DASHBOARD_TRANSIENT_PATTERNS)
    if output_path is not None:
        patterns.add(f"{output_path.expanduser().name}.*.tmp")
    now = time.time()
    removed: list[str] = []
    kept: list[str] = []
    errors: list[dict[str, str]] = []
    if not dashboard_dir.exists():
        return {"removed": removed, "kept": kept, "errors": errors}
    for pattern in sorted(patterns):
        for path in dashboard_dir.glob(pattern):
            if path == db_path or not path.is_file():
                continue
            try:
                stat = path.stat()
                too_old = max_age_seconds >= 0 and now - stat.st_mtime > max_age_seconds
                too_large = max_bytes >= 0 and stat.st_size > max_bytes
                if too_old or too_large:
                    path.unlink()
                    removed.append(str(path))
                else:
                    kept.append(str(path))
            except OSError as exc:
                errors.append({"path": str(path), "error": str(exc)})
    return {"removed": removed, "kept": kept, "errors": errors}


def refresh_runner_readiness(runtime_root: Path, registry_path: Path | None, *, max_age_seconds: int = 300) -> dict:
    status_path = runtime_root / "runner-readiness-refresh" / "latest.json"
    status_path.parent.mkdir(parents=True, exist_ok=True)
    output_path = runtime_root / "runner-readiness.json"
    if _fresh(status_path, max_age_seconds) and _fresh(output_path, max_age_seconds):
        try:
            status = json.loads(status_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            status = {}
        if isinstance(status, dict):
            status["cached"] = True
            return status
    status = {
        "generated_at": utc_now(),
        "ok": False,
        "output": str(output_path),
        "registry": str(registry_path) if registry_path is not None else None,
    }
    if registry_path is None:
        status["reason"] = "registry_missing"
        status_path.write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return status

    stdout = io.StringIO()
    try:
        with contextlib.redirect_stdout(stdout):
            returncode = runner_readiness_report.main([
                "--runtime-root",
                str(runtime_root),
                "--registry",
                str(registry_path),
                "--output",
                str(output_path),
            ])
    except Exception as exc:
        status["reason"] = "runner_readiness_exception"
        status["error"] = str(exc)
    else:
        status["returncode"] = returncode
        status["stdout"] = stdout.getvalue().strip()
        status["ok"] = returncode == 0
        if returncode != 0:
            status["reason"] = "runner_readiness_failed"

    status_path.write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return status


def build_local_snapshot(
    *,
    runtime_root: Path,
    registry_path: Path | None,
    db_path: Path,
    automation_worktree_root: Path | None,
    codex_limit_max_age_minutes: int,
    resource_activity_window_minutes: int,
    resource_sample_interval_seconds: int,
    resource_sample_retention_hours: int,
    history_enabled: bool = False,
    dashboard_snapshot_retention: int = DASHBOARD_SNAPSHOT_RETENTION_DEFAULT,
    project_snapshot_retention: int = PROJECT_SNAPSHOT_RETENTION_DEFAULT,
) -> dict:
    cleanup_dashboard_transient_files(db_path)
    refresh_runner_readiness(runtime_root, registry_path, max_age_seconds=0)
    status_path = runtime_root / "automation-status" / "latest.json"
    status_path.parent.mkdir(parents=True, exist_ok=True)
    automation_status = collect_remote_automation_status.collect()
    status_path.write_text(json.dumps(automation_status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    snapshot = build_snapshot(
        runtime_root,
        registry_path,
        resource_activity_window_minutes=resource_activity_window_minutes,
        codex_limit_max_age_minutes=codex_limit_max_age_minutes,
        automation_worktree_root=automation_worktree_root,
    )
    if history_enabled:
        augment_added_counts_from_history(db_path, snapshot)
        resource_activity = snapshot.get("summary", {}).get("resource_activity", {})
        if resource_activity.get("is_active"):
            record_system_resource_sample(
                db_path,
                snapshot.get("summary", {}).get("resource_load", {}),
                sample_interval_seconds=resource_sample_interval_seconds,
                retention_hours=resource_sample_retention_hours,
            )
        else:
            init_db(db_path)
            with sqlite3.connect(db_path) as conn:
                clean_old_system_resource_samples(conn, resource_sample_retention_hours)
        store_snapshot(
            db_path,
            snapshot,
            dashboard_retention=dashboard_snapshot_retention,
            project_retention=project_snapshot_retention,
        )
        augment_system_resource_load(db_path, snapshot, retention_hours=resource_sample_retention_hours)
    return snapshot


def write_json_atomic(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + f".{os.getpid()}.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def split_remote_scp_target(target: str) -> tuple[str, str] | None:
    if ":" not in target:
        return None
    host, remote_path = target.split(":", 1)
    if not host or not remote_path:
        return None
    return host, remote_path


def run_scp(source: Path, target: str, identity_file: Path | None) -> None:
    command = ["scp", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes"]
    if identity_file is not None:
        command.extend(["-i", str(identity_file)])
    command.extend([str(source), target])
    subprocess.run(command, check=True)


def run_scp_atomic(source: Path, target: str, identity_file: Path | None) -> None:
    remote = split_remote_scp_target(target)
    if remote is None:
        run_scp(source, target, identity_file)
        return
    host, remote_path = remote
    tmp_remote_path = f"{remote_path}.{os.getpid()}.tmp"
    run_scp(source, f"{host}:{tmp_remote_path}", identity_file)
    command = ["ssh", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes"]
    if identity_file is not None:
        command.extend(["-i", str(identity_file)])
    command.extend([host, f"mv -f -- {shlex.quote(tmp_remote_path)} {shlex.quote(remote_path)}"])
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Publish an Agent Control dashboard snapshot")
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--registry", default="runtime/agent-control/projects.local.json")
    parser.add_argument("--automation-worktree-root", default="")
    parser.add_argument("--db", default="")
    parser.add_argument("--output", default="")
    parser.add_argument("--scp-target", default="", help="Optional scp target, e.g. dashboard@host:/srv/agent-dashboard/latest.json")
    parser.add_argument("--scp-identity", default="", help="Optional SSH private key for scp upload.")
    parser.add_argument("--codex-limit-max-age-minutes", type=int, default=CODEX_LIMIT_STALE_MINUTES_DEFAULT)
    parser.add_argument("--resource-sample-retention-hours", type=int, default=SYSTEM_RESOURCE_SAMPLE_RETENTION_HOURS_DEFAULT)
    parser.add_argument("--dashboard-snapshot-retention", type=int, default=DASHBOARD_SNAPSHOT_RETENTION_DEFAULT)
    parser.add_argument("--project-snapshot-retention", type=int, default=PROJECT_SNAPSHOT_RETENTION_DEFAULT)
    parser.add_argument("--resource-sample-interval-seconds", type=int, default=SYSTEM_RESOURCE_SAMPLE_INTERVAL_SECONDS_DEFAULT)
    parser.add_argument("--resource-activity-window-minutes", type=int, default=SYSTEM_RESOURCE_ACTIVITY_WINDOW_MINUTES_DEFAULT)
    parser.add_argument(
        "--enable-history",
        action="store_true",
        help="Opt in to SQLite snapshot and resource history. Disabled by default.",
    )
    args = parser.parse_args()

    runtime_root = Path(args.runtime_root).expanduser()
    registry_path = Path(args.registry).expanduser() if args.registry else None
    automation_worktree_root = Path(args.automation_worktree_root).expanduser() if args.automation_worktree_root else None
    db_path = Path(args.db).expanduser() if args.db else runtime_root / "dashboard" / "analytics.sqlite"
    output = Path(args.output).expanduser() if args.output else runtime_root / "dashboard" / "latest.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    cleanup_dashboard_transient_files(db_path, output)

    snapshot = build_local_snapshot(
        runtime_root=runtime_root,
        registry_path=registry_path,
        db_path=db_path,
        automation_worktree_root=automation_worktree_root,
        codex_limit_max_age_minutes=max(0, args.codex_limit_max_age_minutes),
        resource_activity_window_minutes=max(0, args.resource_activity_window_minutes),
        resource_sample_interval_seconds=max(0, args.resource_sample_interval_seconds),
        resource_sample_retention_hours=max(0, args.resource_sample_retention_hours),
        history_enabled=args.enable_history,
        dashboard_snapshot_retention=max(0, args.dashboard_snapshot_retention),
        project_snapshot_retention=max(0, args.project_snapshot_retention),
    )
    write_json_atomic(output, snapshot)

    if args.scp_target:
        identity_file = Path(args.scp_identity).expanduser() if args.scp_identity else None
        run_scp_atomic(output, args.scp_target, identity_file)

    print(json.dumps({
        "output": str(output),
        "scp_target": args.scp_target or None,
        "scp_identity": args.scp_identity or None,
        "projects": len(snapshot.get("projects", [])),
        "generated_at": snapshot.get("generated_at"),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
