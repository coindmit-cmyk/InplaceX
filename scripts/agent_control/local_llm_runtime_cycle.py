#!/usr/bin/env python3
"""Run the local LLM task/idle lane without applying model patches.

The runtime cycle sits between planning and evidence:

1. planning writes prompt files for ready Worker Packet v2 tasks;
2. runtime calls the configured local model and writes response files;
3. evidence validates those response files and updates policy counters.

When no task prompt is ready, the runtime can create an advisory idle-learning
prompt. Idle output is never task evidence and never counts toward llm_only
promotion.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_file, task_manager_dir, task_reports_dir


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def default_prompt_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "llm_parallel_debug" / "prompts"


def default_response_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "llm_parallel_debug" / "responses"


def default_idle_prompt_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "llm_idle_learning" / "prompts"


def default_idle_response_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "llm_idle_learning" / "responses"


def default_report_path(project_root: Path) -> Path:
    return task_reports_dir(project_root) / "local_llm_runtime_report.json"


def display_path(path: Path, project_root: Path) -> str:
    try:
        return path.relative_to(project_root).as_posix()
    except ValueError:
        return str(path)


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def task_id_from_prompt(prompt_path: Path) -> str:
    data = load_json(prompt_path)
    contract = data.get("contract_summary") if isinstance(data.get("contract_summary"), dict) else {}
    task = data.get("task") if isinstance(data.get("task"), dict) else {}
    for value in (
        contract.get("task_id"),
        task.get("id"),
        task.get("task_id"),
        task.get("canonical_task_id"),
        prompt_path.name.removesuffix(".prompt.json"),
    ):
        text = str(value or "").strip()
        if text:
            return text
    return prompt_path.stem


def response_path_for(prompt_path: Path, response_dir: Path) -> Path:
    name = prompt_path.name
    if name.endswith(".prompt.json"):
        return response_dir / f"{name[:-len('.prompt.json')]}.response.json"
    return response_dir / f"{prompt_path.stem}.response.json"


def collect_ready_prompts(prompt_dir: Path, response_dir: Path) -> list[Path]:
    if not prompt_dir.exists():
        return []
    prompts = []
    for prompt in sorted(prompt_dir.glob("*.prompt.json")):
        if not response_path_for(prompt, response_dir).exists():
            prompts.append(prompt)
    return prompts


def find_task(queue: dict[str, Any], task_id: str) -> dict[str, Any] | None:
    needle = task_id.upper()
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        current = str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").upper()
        if current == needle:
            return task
    return None


def call_adapter(
    adapter_path: Path,
    prompt_path: Path,
    *,
    response_format_json: bool,
    timeout_sec: int | None,
) -> tuple[int, str, str, list[str]]:
    cmd = [
        sys.executable,
        str(adapter_path),
        "--prompt-file",
        str(prompt_path),
        "--json",
    ]
    if response_format_json:
        cmd.append("--response-format-json")
    if timeout_sec:
        cmd.extend(["--timeout", str(timeout_sec)])
    proc = subprocess.run(cmd, text=True, capture_output=True)
    return proc.returncode, proc.stdout, proc.stderr, cmd


def idle_learning_policy(policy: dict[str, Any]) -> dict[str, Any]:
    raw = policy.get("idle_learning")
    if not isinstance(raw, dict):
        raw = {}
    return {
        "enabled": bool(raw.get("enabled", True)),
        "mode": str(raw.get("mode") or "advisory_only"),
        "roles": raw.get("roles") if isinstance(raw.get("roles"), list) and raw.get("roles") else ["dispatcher_research"],
        "max_prompts_per_cycle": int(raw.get("max_prompts_per_cycle") or 1),
        "topics": raw.get("topics") if isinstance(raw.get("topics"), list) and raw.get("topics") else [
            "Find smaller Worker Packet v2 candidates that local LLM can safely help with.",
            "Suggest dispatcher splits that reduce broad task scope without changing code.",
            "Review recent failed quality gates and propose prompt-contract improvements.",
        ],
    }


def build_idle_prompt(policy: dict[str, Any], role: str, topic: str, created_at: str) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "created_at": created_at,
        "mode": "idle_learning",
        "role": role,
        "topic": topic,
        "hard_rules": [
            "Advisory only: do not claim tasks, edit files, invent test results, or mark work done.",
            "Use only repository state supplied in the prompt and policy.",
            "Prefer concrete dispatcher/architect suggestions with exact files or task ids when known.",
            "Return raw JSON only.",
        ],
        "policy_excerpt": {
            "promotion_threshold": policy.get("promotion_threshold"),
            "queue": policy.get("queue"),
            "granularity": policy.get("granularity"),
            "task_kinds": policy.get("task_kinds"),
        },
        "response_schema": {
            "schema_version": 1,
            "role": role,
            "topic": topic,
            "findings": ["short evidence-backed observations"],
            "recommendations": ["safe next steps for Dispatcher/Codex"],
            "candidate_task_ids": ["optional existing task ids"],
            "risk_notes": ["why this should stay advisory or needs human/Codex"],
            "confidence": 0.0,
        },
    }


def run_cycle(
    project_root: Path,
    *,
    queue_path: Path,
    policy_path: Path,
    prompt_dir: Path,
    response_dir: Path,
    idle_prompt_dir: Path,
    idle_response_dir: Path,
    report_path: Path | None,
    adapter_path: Path,
    apply: bool,
    execute_model: bool,
    execute_idle: bool,
    max_task_prompts: int,
    timeout_sec: int | None = None,
    task_id_filter: str | None = None,
    allow_idle: bool = True,
    pre_worker: bool = False,
) -> dict[str, Any]:
    checked_at = utc_now()
    queue = load_json(queue_path)
    policy = load_json(policy_path)
    ready_prompts = collect_ready_prompts(prompt_dir, response_dir)
    if task_id_filter:
        ready_prompts = [prompt for prompt in ready_prompts if task_id_from_prompt(prompt).upper() == task_id_filter.upper()]
    ready_prompts = ready_prompts[:max_task_prompts]
    task_items: list[dict[str, Any]] = []

    for prompt in ready_prompts:
        tid = task_id_from_prompt(prompt)
        response = response_path_for(prompt, response_dir)
        item: dict[str, Any] = {
            "task_id": tid,
            "prompt_path": display_path(prompt, project_root),
            "response_path": display_path(response, project_root),
            "status": "planned",
            "next_owner": "local_llm_evidence_cycle",
        }
        task = find_task(queue, tid)
        if apply and task is not None:
            task["llm_queue_state"] = "in_progress"
            task["llm_claimed_by"] = "local_llm_runtime_cycle"
            task["llm_started_at"] = checked_at
            if pre_worker:
                task["llm_pre_worker_attempts"] = int(task.get("llm_pre_worker_attempts") or 0) + 1
                task["llm_pre_worker_last_attempt_at"] = checked_at
        if apply and execute_model:
            code, stdout, stderr, cmd = call_adapter(
                adapter_path,
                prompt,
                response_format_json=True,
                timeout_sec=timeout_sec,
            )
            item["command"] = cmd
            item["exit_code"] = code
            if code == 0:
                response.parent.mkdir(parents=True, exist_ok=True)
                response.write_text(stdout, encoding="utf-8")
                item["status"] = "response_written"
                if task is not None:
                    task["llm_queue_state"] = "response_ready"
                    task["llm_queue_reason"] = "local LLM response written; awaiting evidence cycle"
            else:
                item["status"] = "backend_unavailable"
                item["stderr"] = stderr
                if task is not None:
                    task["llm_queue_state"] = "ready"
                    task["llm_claimed_by"] = None
                    task["llm_started_at"] = None
                    task["llm_queue_reason"] = "local LLM backend unavailable; task left queued"
                    if pre_worker:
                        settings = policy.get("pre_worker") if isinstance(policy.get("pre_worker"), dict) else {}
                        maximum = max(1, int(settings.get("max_attempts_per_task") or 1))
                        if int(task.get("llm_pre_worker_attempts") or 0) >= maximum:
                            task["llm_pre_worker_decision"] = "codex_fallback_backend"
                            task["llm_pre_worker_decided_at"] = checked_at
                            task["llm_pre_worker_reason"] = "local LLM backend unavailable after bounded attempts"
        task_items.append(item)

    idle_items: list[dict[str, Any]] = []
    idle_policy = idle_learning_policy(policy)
    if not ready_prompts and idle_policy["enabled"] and allow_idle:
        max_idle = max(0, min(int(idle_policy["max_prompts_per_cycle"]), 1))
        for index in range(max_idle):
            role = str(idle_policy["roles"][index % len(idle_policy["roles"])])
            topic = str(idle_policy["topics"][index % len(idle_policy["topics"])])
            safe_stamp = checked_at.replace(":", "").replace("-", "")
            prompt = idle_prompt_dir / f"{safe_stamp}-{role}.prompt.json"
            response = idle_response_dir / f"{safe_stamp}-{role}.response.json"
            item = {
                "role": role,
                "topic": topic,
                "prompt_path": display_path(prompt, project_root),
                "response_path": display_path(response, project_root),
                "status": "planned",
                "next_owner": "dispatcher",
            }
            if apply:
                write_json(prompt, build_idle_prompt(policy, role, topic, checked_at))
                item["status"] = "prompt_written"
            if apply and execute_idle:
                code, stdout, stderr, cmd = call_adapter(
                    adapter_path,
                    prompt,
                    response_format_json=True,
                    timeout_sec=timeout_sec,
                )
                item["command"] = cmd
                item["exit_code"] = code
                if code == 0:
                    response.parent.mkdir(parents=True, exist_ok=True)
                    response.write_text(stdout, encoding="utf-8")
                    item["status"] = "response_written"
                else:
                    item["status"] = "backend_unavailable"
                    item["stderr"] = stderr
            idle_items.append(item)

    if apply:
        queue["updated_at"] = checked_at
        write_json(queue_path, queue)

    counts = {
        "task_prompts": len(task_items),
        "task_responses": sum(1 for item in task_items if item.get("status") == "response_written"),
        "idle_prompts": len(idle_items),
        "idle_responses": sum(1 for item in idle_items if item.get("status") == "response_written"),
        "backend_unavailable": sum(1 for item in task_items + idle_items if item.get("status") == "backend_unavailable"),
    }
    report = {
        "schema_version": 1,
        "source": "local_llm_runtime_cycle.py",
        "checked_at": checked_at,
        "project_root": str(project_root),
        "queue": str(queue_path),
        "policy": str(policy_path),
        "apply": apply,
        "execute_model": execute_model,
        "execute_idle": execute_idle,
        "pre_worker": pre_worker,
        "task_id_filter": task_id_filter,
        "counts": counts,
        "tasks": task_items,
        "idle_learning": idle_items,
        "next_owner": "local_llm_evidence_cycle" if counts["task_responses"] else "dispatcher",
    }
    if report_path:
        write_json(report_path, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Run local LLM task prompts or idle advisory learning.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue", help="Defaults to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--policy", help="Defaults to AiStudio/Task_manager/local_llm_dispatch_policy.json.")
    parser.add_argument("--prompt-dir", help="Defaults to AiStudio/Task_manager/llm_parallel_debug/prompts.")
    parser.add_argument("--response-dir", help="Defaults to AiStudio/Task_manager/llm_parallel_debug/responses.")
    parser.add_argument("--idle-prompt-dir", help="Defaults to AiStudio/Task_manager/llm_idle_learning/prompts.")
    parser.add_argument("--idle-response-dir", help="Defaults to AiStudio/Task_manager/llm_idle_learning/responses.")
    parser.add_argument("--output", help="Defaults to AiStudio/Task_manager/reports/local_llm_runtime_report.json when --write-report is used.")
    parser.add_argument("--adapter", help="Defaults to scripts/agent_control/local_llm_adapter.py.")
    parser.add_argument("--apply", action="store_true", help="Write queue state, response files and idle prompts.")
    parser.add_argument("--execute-model", action="store_true", help="Call the local LLM for ready task prompts.")
    parser.add_argument("--execute-idle", action="store_true", help="Call the local LLM for idle advisory prompts.")
    parser.add_argument("--write-report", action="store_true")
    parser.add_argument("--max-task-prompts", type=int, default=1)
    parser.add_argument("--timeout", type=int)
    parser.add_argument("--task-id", help="Limit execution to one exact task id.")
    parser.add_argument("--skip-idle", action="store_true", help="Do not create or execute idle-learning prompts when no task prompt is ready.")
    parser.add_argument("--pre-worker", action="store_true", help="Record bounded pre-worker attempts and Codex fallback on backend failure.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    report_path = None
    if args.write_report or args.output:
        report_path = Path(args.output).resolve() if args.output else default_report_path(project_root)
    report = run_cycle(
        project_root,
        queue_path=Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json"),
        policy_path=Path(args.policy).resolve() if args.policy else task_file(project_root, "local_llm_dispatch_policy.json"),
        prompt_dir=Path(args.prompt_dir).resolve() if args.prompt_dir else default_prompt_dir(project_root),
        response_dir=Path(args.response_dir).resolve() if args.response_dir else default_response_dir(project_root),
        idle_prompt_dir=Path(args.idle_prompt_dir).resolve() if args.idle_prompt_dir else default_idle_prompt_dir(project_root),
        idle_response_dir=Path(args.idle_response_dir).resolve() if args.idle_response_dir else default_idle_response_dir(project_root),
        report_path=report_path,
        adapter_path=Path(args.adapter).resolve() if args.adapter else script_path("local_llm_adapter.py"),
        apply=args.apply,
        execute_model=args.execute_model,
        execute_idle=args.execute_idle,
        max_task_prompts=args.max_task_prompts,
        timeout_sec=args.timeout,
        task_id_filter=args.task_id,
        allow_idle=not args.skip_idle,
        pre_worker=args.pre_worker,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"task_prompts: {report['counts']['task_prompts']}")
        print(f"task_responses: {report['counts']['task_responses']}")
        print(f"idle_prompts: {report['counts']['idle_prompts']}")
        print(f"idle_responses: {report['counts']['idle_responses']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
