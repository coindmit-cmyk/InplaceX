#!/usr/bin/env python3
"""Canonical multi-project AiStudio automation controller."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Any

import project_registry
import entry_preflight
import automation_authority_guard
import claim_next_task
import fast_track_gate
import model_resource_router
import runner_readiness_report
from action_report import build_report as build_action_report
from action_report import validate_report as validate_action_report

ROLE_MODES = {"all", "architect", "dispatcher", "workers", "integrator", "finalizer", "model_limit_retries", "release_locks", "pr_intake", "result_handoff", "full_intake"}
MODES = {"plan", "full", "project", "role", "one-task", "fast-track", "worktrees", "status"}


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def parse_json_object(text: str) -> dict[str, Any] | None:
    try:
        data = json.loads(str(text or "").strip())
    except (TypeError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


def child_result_state(results: list[dict[str, Any]], returncode: int) -> str:
    states: list[str] = []
    for result in results:
        if result.get("state") == "blocked":
            return "blocked"
        parsed = result.get("parsed_json")
        if not isinstance(parsed, dict):
            continue
        state = str(parsed.get("state") or "").strip()
        if state:
            states.append(state)
        if state == "blocked" or parsed.get("blocked_by_child") or int(parsed.get("failed_count") or 0) > 0:
            return "blocked"
        for nested in parsed.get("results") or []:
            nested_parsed = nested.get("parsed_json") if isinstance(nested, dict) else None
            if isinstance(nested_parsed, dict) and (
                str(nested_parsed.get("state") or "") == "blocked"
                or nested_parsed.get("blocked_by_child")
                or int(nested_parsed.get("failed_count") or 0) > 0
            ):
                return "blocked"
    if returncode != 0:
        return "failed"
    if states and all(state == "no_op" for state in states):
        return "no_op"
    return "succeeded"


def normalize_base(project: dict[str, Any]) -> tuple[str, str]:
    base_branch = str(project.get("base_branch") or "develop").strip()
    if base_branch.startswith("origin/"):
        return base_branch, base_branch.removeprefix("origin/")
    return f"origin/{base_branch}", base_branch


def normalize_ref(value: Any, default_branch: str = "develop") -> tuple[str, str]:
    ref = str(value or default_branch).strip() or default_branch
    if ref.startswith("origin/"):
        return ref, ref.removeprefix("origin/")
    return f"origin/{ref}", ref


def project_ref_model(project: dict[str, Any]) -> dict[str, str]:
    code_ref, code_branch = normalize_ref(project.get("code_base_ref") or project.get("base_ref") or project.get("base_branch") or "develop")
    state_ref, state_branch = normalize_ref(project.get("state_ref") or code_ref)
    push_ref = str(project.get("push_ref") or state_branch).strip() or state_branch
    return {
        "code_base_ref": code_ref,
        "code_base_branch": code_branch,
        "state_ref": state_ref,
        "state_branch": state_branch,
        "push_ref": push_ref,
    }


def project_command_root(project: dict[str, Any]) -> Path:
    return Path(str(project.get("automation_path") or project.get("local_path") or "")).expanduser()


def is_git_worktree(path: Path) -> bool:
    if not path.exists():
        return False
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "--is-inside-work-tree"],
            cwd=path,
            text=True,
            capture_output=True,
            check=False,
            timeout=10,
        )
    except Exception:
        return False
    return proc.returncode == 0 and proc.stdout.strip().lower() == "true"


def command_root_diagnostics(projects: list[dict[str, Any]]) -> list[dict[str, Any]]:
    diagnostics: list[dict[str, Any]] = []
    for project in projects:
        root = project_command_root(project)
        git_expected = bool(str(project.get("base_ref") or project.get("base_branch") or "").strip())
        automation_path = str(project.get("automation_path") or "").strip()
        exists = root.exists()
        is_git = is_git_worktree(root) if exists else False
        blockers: list[str] = []
        recommendation = ""
        if not str(root):
            blockers.append("command_root_missing")
        elif not exists:
            blockers.append("command_root_path_missing")
        elif git_expected and not is_git:
            blockers.append("command_root_not_git_worktree")
            if not automation_path:
                recommendation = "Configure automation_path to a git worktree for this project; artifact local_path cannot run worker/integrator/finalizer automation."
            else:
                recommendation = "Repair or replace automation_path with a valid git worktree before running write-capable automation."
        diagnostics.append({
            "project_id": project.get("project_id"),
            "local_path": project.get("local_path"),
            "automation_path": project.get("automation_path"),
            "command_root": str(root),
            "exists": exists,
            "git_expected": git_expected,
            "is_git_worktree": is_git,
            "blockers": blockers,
            "requires_github_access": bool(blockers and git_expected),
            "recommendation": recommendation,
        })
    return diagnostics


def command_root_preflight_blockers(args: argparse.Namespace, diagnostics: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not args.apply:
        return []
    if args.mode == "worktrees":
        return []
    if args.mode == "role" and args.role == "release_locks":
        return []
    blocked: list[dict[str, Any]] = []
    for diagnostic in diagnostics:
        blockers = diagnostic.get("blockers")
        if isinstance(blockers, list) and blockers:
            blocked.append(diagnostic)
    return blocked


def load_projects(registry_path: Path, project_id: str | None = None) -> list[dict[str, Any]]:
    projects, _warnings = project_registry.load_projects(registry_path, project_id)
    return projects


def manual_command(
    project: dict[str, Any],
    runtime_root: Path,
    mode: str,
    apply: bool,
    max_total_workers: int = 1,
    max_tasks_per_lane: int = 1,
    model_limit_retry_limit: int = 5,
) -> list[str]:
    refs = project_ref_model(project)
    command = [sys.executable, str(script_path("run_manual_automation.py")), "--runtime-root", str(runtime_root), "--project-id", str(project["project_id"]), "--project-root", str(project_command_root(project)), "--base-ref", refs["code_base_ref"], "--base-branch", refs["push_ref"], "--mode", mode, "--json"]
    if mode in {"all", "workers"}:
        command.extend(["--max-total-workers", str(max(0, int(max_total_workers)))])
    if mode == "workers":
        command.extend(["--max-tasks-per-lane", str(max(0, int(max_tasks_per_lane)))])
    if mode == "model_limit_retries":
        command.extend(["--model-limit-retry-limit", str(max(0, int(model_limit_retry_limit)))])
    if apply:
        command.append("--apply")
    return command


def one_task_command(project: dict[str, Any], runtime_root: Path, task_id: str, worker_id: str, apply: bool) -> list[str]:
    refs = project_ref_model(project)
    project_root = project_command_root(project)
    command = [sys.executable, str(script_path("run_worker_cycle.py")), "--project-root", str(project_root), "--base-ref", refs["code_base_ref"], "--push-ref", refs["push_ref"], "--worker-base-ref", refs["code_base_ref"], "--worker-context-ref", refs["state_ref"], "--worker-id", worker_id, "--task-id", task_id, "--machine-id", "aistudio-controller", "--runtime-root", str(runtime_root), "--json"]
    queue_path = project_root / str(project.get("task_queue_path") or "AiStudio/Task_manager/task_queue.json")
    if queue_path.is_file():
        queue = load_json(queue_path)
        task = next(
            (item for item in queue.get("tasks", []) if isinstance(item, dict) and str(item.get("id") or "") == task_id),
            None,
        )
        if task is not None:
            route = model_resource_router.route(project_root, runtime_root, "worker", task)
            if route.get("status") == "selected":
                command.extend(["--model", str(route["model"]), "--reasoning-effort", str(route["reasoning_effort"])])
            elif route.get("reason_code") in {"missing_task_complexity", "unknown_task_complexity"}:
                command = [
                    sys.executable,
                    str(script_path("claim_next_task.py")),
                    "--project-root", str(project_root),
                    "--base-ref", refs["state_ref"],
                    "--push-ref", refs["push_ref"],
                    "--worker-id", worker_id,
                    "--task-id", task_id,
                    "--machine-id", "aistudio-controller",
                    "--runtime-root", str(runtime_root),
                    "--json",
                ]
            else:
                command = [
                    sys.executable,
                    str(script_path("model_resource_router.py")),
                    "--project-root", str(project_root),
                    "--runtime-root", str(runtime_root),
                    "--role", "worker",
                    "--task-json", json.dumps(task, ensure_ascii=False, separators=(",", ":")),
                ]
    if not apply:
        command.append("--dry-run")
    return command


def fast_track_decision(project: dict[str, Any], task_id: str, worker_id: str) -> dict[str, Any]:
    project_root = project_command_root(project)
    queue_path = project_root / str(project.get("task_queue_path") or "AiStudio/Task_manager/task_queue.json")
    locks_path = project_root / str(project.get("agent_locks_path") or "AiStudio/Task_manager/agent_locks.json")
    queue = load_json(queue_path) if queue_path.is_file() else {"tasks": []}
    locks = load_json(locks_path) if locks_path.is_file() else {"locks": []}
    profile = claim_next_task.load_profile(project_root, worker_id)
    return fast_track_gate.evaluate(task_id, queue, locks, worker_id, profile)


def worktree_provision_command(args: argparse.Namespace) -> list[str]:
    command = [
        sys.executable,
        str(script_path("automation_worktree_provisioner.py")),
        "--registry",
        str(Path(args.registry).expanduser()),
        "--worktree-root",
        str(Path(args.worktree_root).expanduser()),
        "--json",
    ]
    if args.project_id:
        command.extend(["--project-id", args.project_id])
    if args.apply:
        command.append("--apply")
    if args.no_remote_check:
        command.append("--no-remote-check")
    return command


def worker_lock_preflight(project: dict[str, Any]) -> dict[str, Any]:
    refs = project_ref_model(project)
    cfg = {
        **project,
        "local_path": str(project_command_root(project)),
        "base_ref": refs["code_base_ref"],
        "base_branch": refs["code_base_branch"],
        "task_queue_github_ref": project.get("task_queue_github_ref") or refs["state_ref"],
        "agent_locks_git_ref": project.get("agent_locks_git_ref") or refs["state_ref"],
    }
    return runner_readiness_report.worker_lock_preflight(cfg, {})


def worker_execution_requested(args: argparse.Namespace) -> bool:
    if args.mode in {"full", "project", "fast-track"}:
        return True
    return args.mode == "role" and args.role in {"all", "workers"}


def entry_preflight_required(args: argparse.Namespace) -> bool:
    if not args.apply:
        return False
    if args.mode in {"status", "worktrees"}:
        return False
    if args.mode == "role" and args.role == "release_locks":
        return False
    return True


def entry_preflight_blockers(args: argparse.Namespace, projects: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not entry_preflight_required(args):
        return []
    blockers: list[dict[str, Any]] = []
    for project in projects:
        root = project_command_root(project)
        refs = project_ref_model(project)
        report = entry_preflight.run_entry_preflight(
            root,
            base_ref=refs["code_base_ref"],
            local_ref="HEAD",
            fetch=True,
            auto_ff=True,
            version_file=str(project.get("version_file") or "PROJECT_VERSION.json"),
            branch_role=str(project.get("task_manager_branch_role") or "") or None,
            # Fleet authority is evaluated once by the controller.  Passing
            # dry_run here avoids duplicating that global gate in every
            # project result while retaining the project-local checks.
            mode="dry_run",
        )
        if not report.get("ok"):
            blockers.append({
                "project_id": project.get("project_id"),
                "command_root": str(root),
                "base_ref": refs["code_base_ref"],
                "state_ref": refs["state_ref"],
                "report": report,
            })
    return blockers


def fleet_authority_preflight(args: argparse.Namespace) -> dict[str, Any] | None:
    """Evaluate the single shared writer-authority gate before fleet work."""
    if not entry_preflight_required(args):
        return None
    if not args.host_id:
        return {
            "schema_version": "1.0",
            "ok": False,
            "mode": "apply",
            "host_id": None,
            "host_role": "unknown",
            "canonical_writer_host": "",
            "apply_allowed": False,
            "mutations_performed": False,
            "errors": [{
                "code": "authority_inputs_required",
                "message": "--host-id is required for apply-capable automation",
            }],
        }
    return automation_authority_guard.evaluate_registry_authority(
        Path(args.registry).expanduser(),
        host_id=args.host_id,
        mode="apply",
    )


def fleet_execution_requested(args: argparse.Namespace, projects: list[dict[str, Any]]) -> bool:
    """Return whether this invocation can continue with another project."""
    return bool(args.apply and len(projects) > 1 and args.mode in {"full", "role"})


def isolate_project_preflight_failures(
    plan: list[dict[str, Any]],
    *,
    command_root_blockers: list[dict[str, Any]],
    runner_readiness_blockers: list[dict[str, Any]],
    entry_preflight_blockers: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Remove blocked projects from a fleet plan and retain explainable results."""
    blockers_by_project: dict[str, list[dict[str, Any]]] = {}
    for stage, blockers in (
        ("command_root", command_root_blockers),
        ("runner_readiness", runner_readiness_blockers),
        ("entry_preflight", entry_preflight_blockers),
    ):
        for blocker in blockers:
            project_id = str(blocker.get("project_id") or "")
            if not project_id:
                continue
            blockers_by_project.setdefault(project_id, []).append({"stage": stage, "report": blocker})

    runnable: list[dict[str, Any]] = []
    isolated: list[dict[str, Any]] = []
    for item in plan:
        project_id = str(item.get("project_id") or "")
        project_blockers = blockers_by_project.get(project_id, [])
        if not project_blockers:
            runnable.append(item)
            continue
        isolated.append({
            "project_id": project_id,
            "mode": item.get("mode"),
            "state": "blocked",
            "isolated": True,
            "returncode": 2,
            "preflight_blockers": project_blockers,
        })
    return runnable, isolated


def runner_readiness_preflight(project: dict[str, Any]) -> dict[str, Any]:
    refs = project_ref_model(project)
    cfg = {
        **project,
        "local_path": str(project_command_root(project)),
        "base_ref": refs["code_base_ref"],
        "base_branch": refs["code_base_branch"],
        "task_queue_github_ref": project.get("task_queue_github_ref") or refs["state_ref"],
        "agent_locks_git_ref": project.get("agent_locks_git_ref") or refs["state_ref"],
    }
    codex_bin = runner_readiness_report.project_codex_bin(cfg)
    codex_readiness = runner_readiness_report.codex_host_readiness.codex_host_readiness(codex_bin).to_dict()
    return runner_readiness_report.analyze_project(cfg, {}, codex_readiness)


def compact_runner_readiness(readiness: dict[str, Any]) -> dict[str, Any]:
    return {
        "project_id": readiness.get("project_id"),
        "ready": readiness.get("ready"),
        "blockers": readiness.get("blockers") or [],
        "candidate_task_count": int(readiness.get("candidate_task_count") or 0),
        "worker_ready_candidate_count": int(readiness.get("worker_ready_candidate_count") or 0),
        "codex_host_readiness": readiness.get("codex_host_readiness"),
        "base_ref_status": readiness.get("base_ref_status"),
    }


def worker_readiness_preflight_blockers(args: argparse.Namespace, projects: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not args.apply or not worker_execution_requested(args):
        return []
    blockers: list[dict[str, Any]] = []
    for project in projects:
        readiness = runner_readiness_preflight(project)
        project_blockers = {str(blocker) for blocker in readiness.get("blockers") or []}
        if project_blockers:
            blockers.append(compact_runner_readiness(readiness))
    return blockers


def build_plan(args: argparse.Namespace, projects: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if args.mode == "status":
        return []
    if args.mode == "worktrees":
        return [{"project_id": args.project_id, "mode": "worktrees", "command": worktree_provision_command(args)}]
    if args.mode in {"one-task", "fast-track"}:
        if not args.project_id or len(projects) != 1:
            raise ValueError(f"{args.mode} mode requires exactly one --project-id")
        if not args.task_id:
            raise ValueError(f"{args.mode} mode requires --task-id")
        decision = fast_track_decision(projects[0], args.task_id, args.worker_id) if args.mode == "fast-track" else None
        command = (
            one_task_command(projects[0], Path(args.runtime_root).expanduser(), args.task_id, args.worker_id, args.apply)
            if decision is None or decision["eligible"]
            else None
        )
        item = {"project_id": projects[0]["project_id"], "mode": args.mode, "command": command}
        if decision is not None:
            item["fast_track"] = decision
        return [item]
    role_mode = args.role if args.mode == "role" else "all"
    if role_mode not in ROLE_MODES:
        raise ValueError(f"unsupported role mode: {role_mode}")
    if args.mode == "project" and len(projects) != 1:
        raise ValueError("project mode requires --project-id")
    return [
        {
            "project_id": project["project_id"],
            "mode": role_mode,
            "command": manual_command(
                project,
                Path(args.runtime_root).expanduser(),
                role_mode,
                args.apply,
                args.max_total_workers,
                args.max_tasks_per_lane,
                args.model_limit_retry_limit,
            ),
        }
        for project in projects
    ]


def status_payload(runtime_root: Path) -> dict[str, Any]:
    root = runtime_root / "automation-controller"
    reports = sorted(root.glob("*.json"), key=lambda path: path.stat().st_mtime, reverse=True) if root.exists() else []
    latest = load_json(reports[0]) if reports else None
    return {"schema_version": "1.0", "state": "status", "latest_report": latest, "report_count": len(reports)}


def controller_result(payload: dict[str, Any]) -> str:
    state = str(payload.get("state") or "")
    if state in {"succeeded", "no_op", "planned"}:
        return "succeeded"
    if state in {"rejected", "blocked"}:
        return "blocked"
    if state == "failed":
        return "failed"
    return "failed" if int(payload.get("returncode") or 0) else "succeeded"


def build_controller_action_report(payload: dict[str, Any], report_path: Path) -> dict[str, Any]:
    result = controller_result(payload)
    is_problem = result in {"blocked", "failed"}
    next_owner = "dispatcher" if is_problem else "controller"
    next_action = str(payload.get("error") or "")
    if not next_action:
        next_action = "Review controller blockers and rerun." if is_problem else "No controller action required."
    planned = [
        {"action": "run_child_command", "project_id": item.get("project_id"), "mode": item.get("mode"), "command": item.get("command")}
        for item in payload.get("planned_commands", [])
        if isinstance(item, dict)
    ]
    results = [item for item in payload.get("results", []) if isinstance(item, dict)]
    executed = [
        {"action": "run_child_command", "project_id": item.get("project_id"), "mode": item.get("mode"), "returncode": item.get("returncode")}
        for item in results
    ]
    failed = [item for item in executed if int(item.get("returncode") or 0) != 0]
    blockers = []
    for key in ("command_root_blockers", "runner_readiness_blockers", "entry_preflight_blockers", "isolated_projects"):
        value = payload.get(key)
        if isinstance(value, list):
            blockers.extend(value)
    if isinstance(payload.get("preflight"), dict) and payload.get("preflight", {}).get("ok") is False:
        blockers.append(payload.get("preflight"))
    return build_action_report(
        action_id=f"automation-controller.{payload.get('run_id') or 'run'}",
        action_type="automation.controller",
        project_id=str(payload.get("project_id") or "multiple"),
        actor="automation-controller-cli",
        source="scripts/agent_control/automation_controller.py",
        mode="apply" if payload.get("apply") else "dry_run",
        result=result,
        next_owner=next_owner,
        next_action=next_action,
        started_at=str(payload.get("started_at") or now_utc()),
        finished_at=str(payload.get("finished_at") or payload.get("updated_at") or now_utc()),
        input_refs=[
            f"mode={payload.get('mode')}",
            f"role={payload.get('role')}",
            f"task_id={payload.get('task_id') or ''}",
            f"worker_id={payload.get('worker_id') or ''}",
        ],
        before_state={
            "mode": payload.get("mode"),
            "role": payload.get("role"),
            "apply": payload.get("apply"),
            "project_count": payload.get("project_count"),
        },
        after_state={
            "state": payload.get("state"),
            "returncode": payload.get("returncode"),
            "error": payload.get("error"),
            "result_count": len(results),
        },
        actions_planned=planned,
        actions_executed=executed,
        actions_failed=failed,
        affected_paths=[str(report_path)],
        validation={"ok": not is_problem, "blocker_count": len(blockers), "failed_child_count": len(failed)},
        artifacts=[str(report_path)],
        rollback={"required": False, "reason": "Automation controller report emission is read-only."},
        residual_risks=[str(payload.get("error"))] if payload.get("error") else [],
    )


def write_controller_outputs(args: argparse.Namespace, report_path: Path, payload: dict[str, Any]) -> None:
    write_json(report_path, payload)
    action_report_output = getattr(args, "action_report_output", None)
    if action_report_output:
        action_payload = build_controller_action_report(payload, report_path)
        validation = validate_action_report(action_payload)
        if not validation["ok"]:
            raise SystemExit(f"action report validation failed: {validation['errors']}")
        write_json(Path(action_report_output).expanduser(), action_payload)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, help="Project registry JSON path.")
    parser.add_argument("--host-id", help="Registered host id required for apply-capable automation.")
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--mode", default="plan", choices=sorted(MODES))
    parser.add_argument("--project-id")
    parser.add_argument("--role", default="all", choices=sorted(ROLE_MODES))
    parser.add_argument("--task-id")
    parser.add_argument("--worker-id", default="auto-worker-5.3-mini")
    parser.add_argument("--max-total-workers", type=int, default=1, help="Maximum worker lanes for role/all dashboard commands.")
    parser.add_argument("--max-tasks-per-lane", type=int, default=1, help="Maximum tasks per worker lane for role=workers dashboard commands.")
    parser.add_argument("--model-limit-retry-limit", type=int, default=5, help="Maximum blocked_model_limit tasks to authorize for retry.")
    parser.add_argument("--worktree-root", default="runtime/agent-control/automation-worktrees")
    parser.add_argument("--no-remote-check", action="store_true")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--action-report-output", type=Path, help="Path to write Universal Action Report JSON.")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    runtime_root = Path(args.runtime_root).expanduser()
    if args.mode == "status":
        payload = status_payload(runtime_root)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else f"reports: {payload['report_count']}")
        return 0
    started_at = now_utc()
    run_id = args.run_id or f"controller-{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    report_path = runtime_root / "automation-controller" / f"{run_id}.json"
    try:
        projects = load_projects(Path(args.registry).expanduser(), args.project_id)
        plan = build_plan(args, projects)
        root_diagnostics = command_root_diagnostics(projects)
    except Exception as exc:
        payload = {"schema_version": "1.0", "run_id": run_id, "state": "rejected", "mode": args.mode, "apply": bool(args.apply), "started_at": started_at, "finished_at": now_utc(), "error": str(exc)}
        write_controller_outputs(args, report_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2
    fast_track_fallbacks = [
        item["fast_track"]
        for item in plan
        if item.get("mode") == "fast-track"
        and isinstance(item.get("fast_track"), dict)
        and not item["fast_track"].get("eligible")
    ]
    if fast_track_fallbacks:
        finished_at = now_utc()
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "state": "no_op",
            "mode": args.mode,
            "project_id": args.project_id,
            "task_id": args.task_id,
            "worker_id": args.worker_id,
            "apply": bool(args.apply),
            "started_at": started_at,
            "updated_at": finished_at,
            "finished_at": finished_at,
            "project_count": len(projects),
            "planned_commands": plan,
            "results": [],
            "route": "standard_lifecycle",
            "fast_track": fast_track_fallbacks[0],
            "returncode": 0,
            "command_root_diagnostics": root_diagnostics,
        }
        write_controller_outputs(args, report_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else f"standard lifecycle: {run_id}")
        return 0
    root_blockers = command_root_preflight_blockers(args, root_diagnostics)
    isolate_preflight_failures = fleet_execution_requested(args, projects)
    if root_blockers and not isolate_preflight_failures:
        finished_at = now_utc()
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "state": "rejected",
            "mode": args.mode,
            "role": args.role,
            "project_id": args.project_id,
            "task_id": args.task_id,
            "worker_id": args.worker_id,
            "apply": bool(args.apply),
            "model_limit_retry_limit": max(0, int(args.model_limit_retry_limit)),
            "started_at": started_at,
            "updated_at": finished_at,
            "finished_at": finished_at,
            "project_count": len(projects),
            "planned_commands": plan,
            "results": [],
            "error": "command_root_preflight_failed",
            "command_root_blockers": root_blockers,
            "command_root_diagnostics": root_diagnostics,
        }
        write_controller_outputs(args, report_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2
    worker_readiness_blockers = worker_readiness_preflight_blockers(args, projects)
    if worker_readiness_blockers and not isolate_preflight_failures:
        finished_at = now_utc()
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "state": "rejected",
            "mode": args.mode,
            "role": args.role,
            "project_id": args.project_id,
            "task_id": args.task_id,
            "worker_id": args.worker_id,
            "apply": bool(args.apply),
            "model_limit_retry_limit": max(0, int(args.model_limit_retry_limit)),
            "started_at": started_at,
            "updated_at": finished_at,
            "finished_at": finished_at,
            "project_count": len(projects),
            "planned_commands": plan,
            "results": [],
            "error": "runner_readiness_preflight_failed",
            "runner_readiness_blockers": worker_readiness_blockers,
            "command_root_diagnostics": root_diagnostics,
        }
        write_controller_outputs(args, report_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2
    preflight = None
    if args.apply and args.mode in {"one-task", "fast-track"}:
        preflight = worker_lock_preflight(projects[0])
        if not preflight.get("ok"):
            finished_at = now_utc()
            payload = {
                "schema_version": "1.0",
                "run_id": run_id,
                "state": "rejected",
                "mode": args.mode,
                "role": args.role,
                "project_id": args.project_id,
                "task_id": args.task_id,
                "worker_id": args.worker_id,
                "apply": bool(args.apply),
                "model_limit_retry_limit": max(0, int(args.model_limit_retry_limit)),
                "started_at": started_at,
                "updated_at": finished_at,
                "finished_at": finished_at,
                "project_count": len(projects),
                "planned_commands": plan,
                "results": [],
                "error": "worker_lock_preflight_failed",
                "preflight": preflight,
                "command_root_diagnostics": root_diagnostics,
            }
            write_controller_outputs(args, report_path, payload)
            print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
            return 2
    authority = fleet_authority_preflight(args)
    if authority is not None and not authority.get("ok"):
        finished_at = now_utc()
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "state": "rejected",
            "mode": args.mode,
            "role": args.role,
            "project_id": args.project_id,
            "task_id": args.task_id,
            "worker_id": args.worker_id,
            "host_id": args.host_id,
            "apply": bool(args.apply),
            "model_limit_retry_limit": max(0, int(args.model_limit_retry_limit)),
            "started_at": started_at,
            "updated_at": finished_at,
            "finished_at": finished_at,
            "project_count": len(projects),
            "planned_commands": plan,
            "results": [],
            "error": "fleet_authority_preflight_failed",
            "fleet_authority": authority,
            "command_root_diagnostics": root_diagnostics,
        }
        write_controller_outputs(args, report_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2
    entry_blockers = entry_preflight_blockers(args, projects)
    if entry_blockers and not isolate_preflight_failures:
        finished_at = now_utc()
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "state": "rejected",
            "mode": args.mode,
            "role": args.role,
            "project_id": args.project_id,
            "task_id": args.task_id,
            "worker_id": args.worker_id,
            "apply": bool(args.apply),
            "model_limit_retry_limit": max(0, int(args.model_limit_retry_limit)),
            "started_at": started_at,
            "updated_at": finished_at,
            "finished_at": finished_at,
            "project_count": len(projects),
            "planned_commands": plan,
            "results": [],
            "error": "entry_preflight_failed",
            "entry_preflight_blockers": entry_blockers,
            "preflight": preflight,
            "command_root_diagnostics": root_diagnostics,
        }
        write_controller_outputs(args, report_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2
    runnable_plan, isolated_projects = (
        isolate_project_preflight_failures(
            plan,
            command_root_blockers=root_blockers,
            runner_readiness_blockers=worker_readiness_blockers,
            entry_preflight_blockers=entry_blockers,
        )
        if isolate_preflight_failures
        else (plan, [])
    )
    payload = {"schema_version": "1.0", "run_id": run_id, "state": "planned" if not args.apply else "running", "mode": args.mode, "role": args.role, "project_id": args.project_id, "task_id": args.task_id, "worker_id": args.worker_id, "host_id": args.host_id, "apply": bool(args.apply), "model_limit_retry_limit": max(0, int(args.model_limit_retry_limit)), "started_at": started_at, "updated_at": started_at, "project_count": len(projects), "runnable_project_count": len(runnable_plan), "planned_commands": plan, "results": [], "isolated_projects": isolated_projects, "preflight": preflight, "fleet_authority": authority, "command_root_diagnostics": root_diagnostics, "command_root_blockers": root_blockers, "runner_readiness_blockers": worker_readiness_blockers, "entry_preflight_blockers": entry_blockers}
    write_controller_outputs(args, report_path, payload)
    if not args.apply and args.mode != "worktrees":
        finished_at = now_utc()
        payload.update({"state": "succeeded", "updated_at": finished_at, "finished_at": finished_at, "returncode": 0})
        write_controller_outputs(args, report_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else f"planned: {run_id}")
        return 0
    returncode = 2 if isolated_projects else 0
    results: list[dict[str, Any]] = list(isolated_projects)
    for item in runnable_plan:
        proc = subprocess.run(item["command"], text=True, capture_output=True, check=False)
        result = {"project_id": item["project_id"], "mode": item["mode"], "returncode": proc.returncode, "stdout": proc.stdout[-12000:], "stderr": proc.stderr[-12000:]}
        parsed_stdout = parse_json_object(proc.stdout)
        if parsed_stdout is not None:
            result["parsed_json"] = parsed_stdout
        results.append(result)
        if proc.returncode != 0:
            returncode = returncode or proc.returncode
    finished_at = now_utc()
    payload.update({"state": child_result_state(results, returncode), "updated_at": finished_at, "finished_at": finished_at, "returncode": returncode, "results": results})
    if isolated_projects:
        payload["error"] = "project_preflight_failed"
    write_controller_outputs(args, report_path, payload)
    print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else f"{payload['state']}: {run_id}")
    return returncode


if __name__ == "__main__":
    raise SystemExit(main())
