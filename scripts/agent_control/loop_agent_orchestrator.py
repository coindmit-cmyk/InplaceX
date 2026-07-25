#!/usr/bin/env python3
"""Run the next rebuild/integration automation step from status and events.

This is a thin deterministic loop over existing scripts. It is dry-run by
default. With --apply it executes one selected next step unless --watch is used.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from _rebuild_common import load_json, utc_now, write_json
from event_driven_scheduler import pending_finalizer_events, pending_integrator_events, read_events, write_events
from process_log import append_log
from project_paths import task_manager_dir


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def run(cmd: list[str]) -> dict[str, Any]:
    proc = subprocess.run(cmd, text=True, capture_output=True, check=False)
    return {"command": cmd, "exit_code": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr}


def parse_stdout_json(stdout: str) -> dict[str, Any] | None:
    text = stdout.strip()
    if not text:
        return None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def finalizer_noop_result(results: list[dict[str, Any]]) -> dict[str, Any] | None:
    for result in results:
        command = result.get("command") if isinstance(result.get("command"), list) else []
        if len(command) <= 1 or not str(command[1]).endswith("auto_finalizer_merge.py"):
            continue
        payload = parse_stdout_json(str(result.get("stdout") or ""))
        if not isinstance(payload, dict):
            continue
        decision = str(payload.get("decision") or "")
        if decision in {"no_handoff", "already_finalized"}:
            return {"decision": decision, "payload": payload, "command": command}
    return None


def event_names(project_root: Path) -> set[str]:
    events = pending_events(project_root)
    return {str(event.get("event") or "") for event in events if not event.get("consumed_by")}


def pending_events(project_root: Path) -> list[dict[str, Any]]:
    return [
        event
        for event in read_events(task_manager_dir(project_root) / "agent_events.jsonl")
        if not event.get("consumed_by")
    ]


def consume_events_by_name(project_root: Path, names: set[str], role: str) -> int:
    path = task_manager_dir(project_root) / "agent_events.jsonl"
    events = read_events(path)
    now = utc_now()
    count = 0
    for event in events:
        if event.get("consumed_by"):
            continue
        if str(event.get("event") or "") not in names:
            continue
        event["consumed_by"] = role
        event["consumed_at"] = now
        count += 1
    if count:
        write_events(path, events)
    return count


def build_status(project_root: Path) -> dict[str, Any]:
    proc = run([
        sys.executable,
        str(script_path("compact_status_builder.py")),
        "--project-root",
        str(project_root),
        "--json",
    ])
    if proc["exit_code"] != 0:
        raise RuntimeError(json.dumps(proc, ensure_ascii=False, indent=2))
    return json.loads(proc["stdout"])


def decide(project_root: Path, status: dict[str, Any]) -> dict[str, Any]:
    counts = status.get("counts") or {}
    events = read_events(task_manager_dir(project_root) / "agent_events.jsonl")
    pending = [event for event in events if not event.get("consumed_by")]
    names = {str(event.get("event") or "") for event in pending}
    finalizer = pending_finalizer_events(pending, events)
    integrator = pending_integrator_events(project_root, pending)
    if {"dispatcher_rebuild_requested", "provisional_crb_requested", "llm_advisory_requested"} & names:
        return {"run_class": "dispatcher_rebuild", "reason": "rebuild decisions need Dispatcher plan"}
    if "crb_task_created" in names:
        return {"run_class": "clean_rebuild_promotion", "reason": "auto clean rebuild tasks can be promoted"}
    if counts.get("ready_candidate") or integrator:
        return {"run_class": "integration_run", "reason": "ready candidates or integration event present"}
    if counts.get("worker_ready") or "worker_ready_available" in names:
        return {"run_class": "worker_run", "reason": "worker-ready tasks or event present"}
    if counts.get("ready_to_finalize") or finalizer:
        return {"run_class": "finalizer_gate", "reason": "handoff/finalization route present"}
    if counts.get("cleanup_candidate") or counts.get("cleanup_candidates") or "cleanup_requested" in names:
        return {"run_class": "cleanup_plan", "reason": "cleanup candidates present"}
    if counts.get("needs_human") or "needs_human_created" in names:
        return {"run_class": "human_queue", "reason": "human-routed items are pending"}
    return {"run_class": "idle", "reason": "no automatic action detected"}


def commands_for(args: argparse.Namespace, run_class: str) -> list[list[str]]:
    project_root = str(Path(args.project_root).resolve())
    base_ref = args.base_ref
    if run_class == "dispatcher_rebuild":
        return [
            [sys.executable, str(script_path("dispatcher_rebuild_planner.py")), "--project-root", project_root, "--json"],
            [sys.executable, str(script_path("provisional_crb_task_builder.py")), "--project-root", project_root, "--max-items", str(args.max_provisional), "--json"],
            [sys.executable, str(script_path("route_rebuild_and_integration_results.py")), "--project-root", project_root, "--json"],
        ]
    if run_class == "clean_rebuild_promotion":
        return [[sys.executable, str(script_path("clean_rebuild_queue_bridge.py")), "--project-root", project_root, "--max-items", str(args.max_crb_promotions), "--json"]]
    if run_class == "worker_run":
        cmd = [
            sys.executable,
            str(script_path("worker_pool_manager.py")),
            "--project-root",
            project_root,
            "--base-ref",
            base_ref,
            "--machine-id",
            args.machine_id,
            "--runtime-root",
            args.runtime_root,
            "--json",
        ]
        if args.fetch:
            cmd.append("--fetch")
        return [cmd]
    if run_class == "integration_run":
        cmd = [
            sys.executable,
            str(script_path("pre_integrator_repair.py")),
            "--project-root",
            project_root,
            "--base-ref",
            base_ref,
            "--json",
        ]
        if args.fetch:
            cmd.append("--fetch")
        cmd.append("--emit-events")
        package_cmd = [
            sys.executable,
            str(script_path("build_integration_package.py")),
            "--project-root",
            project_root,
            "--base-ref",
            base_ref,
            "--finalizer-base-branch",
            args.finalizer_base_branch,
            "--max-items",
            str(args.max_integration_items),
            "--json",
        ]
        if args.fetch:
            package_cmd.append("--fetch")
        if args.push_package:
            package_cmd.append("--push")
        return [cmd, package_cmd]
    if run_class == "finalizer_gate":
        cmd = [
            sys.executable,
            str(script_path("auto_finalizer_merge.py")),
            "--project-root",
            project_root,
            "--handoff",
            str(task_manager_dir(Path(project_root)) / "integration_handoff.json"),
            "--base-branch",
            args.finalizer_base_branch,
            "--json",
        ]
        if args.fetch:
            cmd.append("--fetch")
        return [cmd]
    if run_class == "cleanup_plan":
        cmd = [
            sys.executable,
            str(script_path("cleanup_merged_branches.py")),
            "--project-root",
            project_root,
            "--base",
            base_ref,
            "--json",
        ]
        if args.fetch:
            cmd.append("--fetch")
        return [cmd]
    return []


def apply_flags(commands: list[list[str]], run_class: str, apply: bool) -> list[list[str]]:
    if not apply:
        return commands
    result: list[list[str]] = []
    for cmd in commands:
        name = Path(cmd[1]).name if len(cmd) > 1 else ""
        updated = list(cmd)
        if name in {
            "provisional_crb_task_builder.py",
            "route_rebuild_and_integration_results.py",
            "clean_rebuild_queue_bridge.py",
            "worker_pool_manager.py",
            "build_integration_package.py",
            "auto_finalizer_merge.py",
            "cleanup_merged_branches.py",
        }:
            updated.append("--apply")
        if name == "pre_integrator_repair.py":
            # --emit-events is already included; this script writes reports as part of its deterministic scan.
            pass
        result.append(updated)
    return result


def run_once(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve()
    status = build_status(project_root)
    decision = decide(project_root, status)
    commands = apply_flags(commands_for(args, decision["run_class"]), decision["run_class"], args.apply)
    executions: list[dict[str, Any]] = []
    consumed_events = 0
    if args.apply:
        for command in commands:
            result = run(command)
            executions.append(result)
            if result["exit_code"] != 0:
                break
    no_op = finalizer_noop_result(executions) if decision["run_class"] == "finalizer_gate" and executions and all(item.get("exit_code") == 0 for item in executions) else None
    if args.apply:
        if executions and all(item.get("exit_code") == 0 for item in executions):
            if decision["run_class"] == "dispatcher_rebuild":
                consumed_events = consume_events_by_name(
                    project_root,
                    {"dispatcher_rebuild_requested", "provisional_crb_requested", "llm_advisory_requested"},
                    "loop_agent_orchestrator",
                )
            elif decision["run_class"] == "clean_rebuild_promotion":
                consumed_events = consume_events_by_name(project_root, {"crb_task_created"}, "loop_agent_orchestrator")
    report = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "apply": bool(args.apply),
        "decision": decision,
        "commands": commands,
        "executions": executions,
        "consumed_events": consumed_events,
        "status": status,
    }
    if no_op is not None:
        report["state"] = "no_op"
        report["no_op"] = no_op
    elif executions:
        report["state"] = "succeeded" if all(item.get("exit_code") == 0 for item in executions) else "failed"
    else:
        report["state"] = "idle" if decision["run_class"] == "idle" else "planned"
    output = task_manager_dir(project_root) / "loop_agent_orchestrator.json"
    report["output"] = None
    if args.apply:
        write_json(output, report)
        if no_op is not None:
            append_log(project_root, "orchestrator", "loop_agent_noop", severity="info", run_class=decision["run_class"], reason=no_op.get("decision"), apply=args.apply)
        else:
            append_log(project_root, "orchestrator", "loop_agent_decision", severity="info", run_class=decision["run_class"], apply=args.apply)
        report["output"] = str(output)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--finalizer-base-branch", default="develop")
    parser.add_argument("--machine-id", default="aistudio")
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--max-provisional", type=int, default=10)
    parser.add_argument("--max-crb-promotions", type=int, default=10)
    parser.add_argument("--max-integration-items", type=int, default=10)
    parser.add_argument("--push-package", action="store_true")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--watch", action="store_true")
    parser.add_argument("--interval", type=int, default=1800)
    parser.add_argument("--max-cycles", type=int, default=1)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    reports: list[dict[str, Any]] = []
    cycles = args.max_cycles if args.watch else 1
    for index in range(cycles):
        reports.append(run_once(args))
        if not args.watch or index == cycles - 1:
            break
        time.sleep(args.interval)

    result = reports[-1] if len(reports) == 1 else {"cycles": len(reports), "reports": reports}
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        last = reports[-1]
        print(f"run_class: {last['decision']['run_class']}")
        print(f"reason: {last['decision']['reason']}")
    failed = [
        execution
        for report in reports
        for execution in report.get("executions", [])
        if execution.get("exit_code") not in {0, None}
    ]
    return int(failed[0]["exit_code"]) if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
