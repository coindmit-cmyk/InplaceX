#!/usr/bin/env python3
"""Consume queued AiStudio Command Bus commands through automation_controller.py."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import command_bus

CONTROLLER_BLOCKING_ERRORS = {
    "command_root_preflight_failed",
    "runner_readiness_preflight_failed",
    "worker_lock_preflight_failed",
}


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def controller_command(command: dict[str, Any], registry: Path, runtime_root: Path) -> list[str]:
    cmd = [sys.executable, str(script_path("automation_controller.py")), "--registry", str(registry), "--runtime-root", str(runtime_root), "--mode", str(command.get("mode") or "project"), "--run-id", str(command.get("command_id")), "--json"]
    if command.get("project_id"):
        cmd.extend(["--project-id", str(command["project_id"])])
    if command.get("role"):
        cmd.extend(["--role", str(command["role"])])
    if command.get("task_id"):
        cmd.extend(["--task-id", str(command["task_id"])])
    if command.get("worker_id"):
        cmd.extend(["--worker-id", str(command["worker_id"])])
    if command.get("worktree_root"):
        cmd.extend(["--worktree-root", str(command["worktree_root"])])
    if command.get("no_remote_check"):
        cmd.append("--no-remote-check")
    if command.get("max_total_workers") is not None:
        cmd.extend(["--max-total-workers", str(command["max_total_workers"])])
    if command.get("max_tasks_per_lane") is not None:
        cmd.extend(["--max-tasks-per-lane", str(command["max_tasks_per_lane"])])
    if command.get("model_limit_retry_limit") is not None:
        cmd.extend(["--model-limit-retry-limit", str(command["model_limit_retry_limit"])])
    if command.get("apply"):
        cmd.append("--apply")
    return cmd


def parse_json_object(text: str) -> dict[str, Any] | None:
    try:
        data = json.loads(str(text or "").strip())
    except (TypeError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


def structured_blocker_report(data: dict[str, Any] | None) -> bool:
    if not isinstance(data, dict):
        return False
    if str(data.get("state") or "") == "blocked":
        return True
    if str(data.get("error") or "") in CONTROLLER_BLOCKING_ERRORS:
        return True
    if data.get("error"):
        return False
    if int(data.get("failed_count") or 0) > 0:
        return True
    for result in data.get("results") or []:
        if not isinstance(result, dict):
            continue
        parsed = result.get("parsed_json")
        if isinstance(parsed, dict) and int(parsed.get("failed_count") or 0) > 0:
            return True
    return False


def command_state(returncode: int, parsed_stdout: dict[str, Any] | None) -> str:
    if returncode == 0 and isinstance(parsed_stdout, dict) and str(parsed_stdout.get("state") or "") == "no_op":
        return "no_op"
    if returncode == 0:
        return "blocked" if structured_blocker_report(parsed_stdout) else "succeeded"
    return "blocked" if structured_blocker_report(parsed_stdout) else "failed"


def consume_one(runtime_root: Path, registry: Path, lease_owner: str) -> dict[str, Any]:
    command = command_bus.claim_next(runtime_root, lease_owner)
    if not command:
        return {"consumed": False, "reason": "no_queued_command"}
    command_id = str(command["command_id"])
    cmd = controller_command(command, registry, runtime_root)
    command_bus.update_command(runtime_root, command_id, {"state": "running", "controller_command": cmd, "started_at": command_bus.now_utc()})
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, check=False)
    except Exception as exc:
        result = {
            "state": "failed",
            "returncode": 1,
            "error": f"consumer_subprocess_error: {exc}",
            "finished_at": command_bus.now_utc(),
        }
        command_bus.update_command(runtime_root, command_id, result)
        return {"consumed": True, "command_id": command_id, **result}
    parsed_stdout = parse_json_object(proc.stdout)
    state = command_state(proc.returncode, parsed_stdout)
    result = {
        "state": state,
        "returncode": proc.returncode,
        "stdout": proc.stdout[-12000:],
        "stderr": proc.stderr[-12000:],
        "finished_at": command_bus.now_utc(),
    }
    if parsed_stdout is not None:
        result["parsed_json"] = parsed_stdout
    command_bus.update_command(runtime_root, command_id, result)
    return {"consumed": True, "command_id": command_id, **result}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--registry", required=True)
    parser.add_argument("--lease-owner", default="command-consumer")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = consume_one(Path(args.runtime_root).expanduser(), Path(args.registry).expanduser(), args.lease_owner)
    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else result)
    return 0 if not result.get("consumed") or result.get("returncode", 0) == 0 else int(result.get("returncode") or 1)


if __name__ == "__main__":
    raise SystemExit(main())
