#!/usr/bin/env python3
"""Collect worker-done evidence and emit integration_requested events."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file


READY_STATUSES = {"agent_done", "review", "integration_ready", "integration_requested"}
CURRENT_WORKER_BRANCH_PREFIXES = (
    "refs/remotes/origin/AiStudio/Agent/worker/",
    "refs/heads/AiStudio/Agent/worker/",
)
LEGACY_WORKER_BRANCH_PREFIXES = (
    "refs/remotes/origin/remote/",
    "refs/heads/remote/",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def read_events(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    events: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            events.append(event)
    return events


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, text=True, capture_output=True, check=False)


def run_json(cmd: list[str]) -> dict[str, Any]:
    proc = subprocess.run(cmd, text=True, capture_output=True, check=False)
    if not proc.stdout.strip():
        return {"exit_code": proc.returncode, "stderr": proc.stderr}
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        data = {"raw_stdout": proc.stdout, "stderr": proc.stderr}
    data["exit_code"] = proc.returncode
    return data


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def has_integration_evidence(task: dict[str, Any]) -> bool:
    if task.get("branch") or task.get("github_branch") or task.get("worker_branch") or task.get("synced_from_worker_branch"):
        return True
    if task.get("pr") or task.get("github_pr") or task.get("pull_request"):
        return True
    if task.get("worker_result_commit") or task.get("head_sha"):
        return True
    commits = task.get("commits")
    if isinstance(commits, list) and commits:
        return True
    for field in ("worker_report", "last_agent_report", "integration_report"):
        if task.get(field):
            return True
    return False


def task_changed_paths(task: dict[str, Any]) -> list[str]:
    for field in ("integration_changed_paths", "changed_paths", "worker_changed_paths"):
        value = task.get(field)
        if isinstance(value, list):
            return [str(item) for item in value if str(item or "").strip()]
    return []


def task_branch_ref(task: dict[str, Any]) -> str | None:
    for field in ("branch", "github_branch", "worker_branch", "synced_from_worker_branch"):
        value = str(task.get(field) or "").strip()
        if value:
            return value
    commit = str(task.get("worker_result_commit") or task.get("head_sha") or "").strip()
    return commit or None


def is_migration_sensitive_path(path: str) -> bool:
    normalized = str(path or "").replace("\\", "/").lower()
    parts = [part for part in normalized.split("/") if part]
    return "migrations" in parts or normalized.endswith((".sql", ".ddl"))


def task_migration_policy(task: dict[str, Any]) -> dict[str, Any] | None:
    policy = task.get("migration_compatibility_policy")
    return dict(policy) if isinstance(policy, dict) and policy else None


def migration_policy_code_refs(policy: dict[str, Any] | None) -> list[str]:
    if not isinstance(policy, dict):
        return []
    refs = policy.get("code_refs") or []
    if not isinstance(refs, list):
        return []
    return [str(path) for path in refs if str(path or "").strip()]


def task_is_migration_sensitive(task: dict[str, Any], changed_paths: list[str], policy: dict[str, Any] | None) -> bool:
    if task.get("migration_sensitive") is True:
        return True
    inventory = task.get("context_inventory") if isinstance(task.get("context_inventory"), dict) else {}
    input_refs = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    paths = [
        *changed_paths,
        *[str(path) for path in task.get("allowed_paths") or []],
        *[str(path) for path in task.get("code_refs") or []],
        *[str(path) for path in task.get("integration_changed_paths") or []],
        *[str(path) for path in inventory.get("code_refs") or []],
        *[str(path) for path in input_refs.get("allowed_paths") or []],
        *[str(path) for path in input_refs.get("changed_paths") or []],
        *migration_policy_code_refs(policy),
    ]
    checks = [
        *[str(item) for item in task.get("checks") or []],
        *[str(item) for item in task.get("script_actions") or []],
        *[str(item) for item in task.get("regression_guards") or []],
    ]
    check_text = " ".join(checks).replace("\\", "/").lower()
    return (
        "makemigrations" in check_text
        or "migrate --plan" in check_text
        or "migrate --check" in check_text
        or any(is_migration_sensitive_path(path) for path in paths)
    )


def candidates(queue: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for task in queue.get("tasks", []) if isinstance(queue.get("tasks"), list) else []:
        if not isinstance(task, dict):
            continue
        if str(task.get("status") or "") not in READY_STATUSES:
            continue
        current_task_id = task_id(task)
        if not current_task_id:
            continue
        if not has_integration_evidence(task):
            continue
        changed_paths = task_changed_paths(task)
        migration_policy = task_migration_policy(task)
        migration_sensitive = task_is_migration_sensitive(task, changed_paths, migration_policy)
        result.append({
            "task_id": current_task_id,
            "title": task.get("title"),
            "status": task.get("status"),
            "integration_status": task.get("integration_status"),
            "worker_check_evidence": task.get("worker_check_evidence"),
            "integrator_must_run_checks": str(task.get("integration_status") or "") == "pending_checks",
            "branch": task_branch_ref(task),
            "head_sha": task.get("worker_result_commit") or task.get("head_sha"),
            "pr": task.get("pr") or task.get("github_pr") or task.get("pull_request"),
            "changed_paths": changed_paths,
            "migration_sensitive": migration_sensitive,
            "migration_compatibility_policy": migration_policy,
            "integrator_must_adapt_migrations": bool(migration_sensitive and migration_policy),
            "checks": task.get("checks", []),
        })
    return result


def existing_integration_event_keys(project_root: Path) -> set[tuple[str, str]]:
    events_path = task_file(project_root, "agent_events.jsonl")
    keys: set[tuple[str, str]] = set()
    for event in read_events(events_path):
        if event.get("event") != "integration_requested":
            continue
        payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
        if not payload.get("changed_paths") and not payload.get("integration_changed_paths"):
            continue
        task = str(event.get("task_id") or "").strip()
        branch = str(event.get("branch") or "").strip()
        if task:
            keys.add((task, branch))
    return keys


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run worker-to-integrator bridge.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--base-ref", help="Base ref for worker result changed-path evidence.")
    parser.add_argument("--sync-worker-results", action="store_true", help="Explicitly sync worker branch statuses before collecting candidates.")
    parser.add_argument("--no-sync-worker-results", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--branch-prefix", action="append", default=[], help="Worker branch ref prefix to sync. Can be repeated.")
    parser.add_argument("--include-legacy-worker-branches", action="store_true", help="Also sync legacy remote/* worker branches during explicit recovery imports.")
    parser.add_argument("--fetch", action="store_true", help="Fetch remote refs before worker status sync.")
    parser.add_argument("--apply", action="store_true", help="Write integration candidate artifacts, events and process logs. Default is dry-run.")
    parser.add_argument("--emit-events", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    project_root = Path(args.project_root).resolve()
    sync_report: dict[str, Any] | None = None
    if args.sync_worker_results and not args.no_sync_worker_results:
        prefixes = list(args.branch_prefix or CURRENT_WORKER_BRANCH_PREFIXES)
        if args.include_legacy_worker_branches:
            prefixes.extend(LEGACY_WORKER_BRANCH_PREFIXES)
        resolved_prefixes = list(dict.fromkeys(prefixes))
        if args.apply:
            sync_cmd = [
                sys.executable,
                str(script_path("sync_worker_results.py")),
                "--project-root",
                str(project_root),
                "--apply",
                "--json",
            ]
            if args.base_ref:
                sync_cmd.extend(["--base-ref", args.base_ref])
            if args.fetch:
                sync_cmd.append("--fetch")
            for prefix in resolved_prefixes:
                sync_cmd.extend(["--branch-prefix", prefix])
            sync_report = run_json(sync_cmd)
        else:
            sync_report = {"dry_run": True, "skipped_apply": True, "branch_prefixes": resolved_prefixes}

    queue = load_json(task_file(project_root, "task_queue.json"))
    items = candidates(queue)
    output = task_file(project_root, "integration_candidates.json")
    output_written = None
    if args.apply:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps({"generated_at": utc_now(), "candidates": items}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        output_written = str(output)

    emitted_count = 0
    skipped_existing_event_count = 0
    if args.apply and args.emit_events:
        existing_events = existing_integration_event_keys(project_root)
        for item in items:
            key = (str(item["task_id"]), str(item.get("branch") or ""))
            if key in existing_events:
                skipped_existing_event_count += 1
                continue
            cmd = [
                sys.executable,
                str(script_path("emit_agent_event.py")),
                "--project-root",
                str(project_root),
                "--event",
                "integration_requested",
                "--role",
                "worker_integrator_bridge",
                "--next-role",
                "auto-integrator",
                "--task-id",
                str(item["task_id"]),
                "--payload-json",
                json.dumps(item, ensure_ascii=False),
            ]
            if item.get("branch"):
                cmd.extend(["--branch", str(item["branch"])])
            if item.get("pr"):
                try:
                    cmd.extend(["--pr", str(int(item["pr"]))])
                except (TypeError, ValueError):
                    pass
            run(cmd)
            existing_events.add(key)
            emitted_count += 1

    if args.apply:
        append_log(project_root, "integrator", "worker_integrator_bridge", severity="info", candidate_count=len(items))
    report = {
        "sync_report": sync_report,
        "candidate_count": len(items),
        "emitted_count": emitted_count,
        "skipped_existing_event_count": skipped_existing_event_count,
        "output": output_written,
        "dry_run": not args.apply,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else f"integration candidates: {len(items)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
