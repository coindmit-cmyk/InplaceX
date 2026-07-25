#!/usr/bin/env python3
"""Prepare local-LLM-ready task prompts from the canonical task queue.

This script is a planning bridge only. It does not call a model, claim tasks,
apply patches, or mark worker results. It combines the dispatcher tagger, packet
planner, and prompt builder into one repeatable remote-host preparation step.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

import llm_dispatch_tagger
import local_llm_packet_planner
import local_llm_prompt_builder
from project_paths import task_file, task_manager_dir, task_reports_dir


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_name(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "-", value.strip())
    return cleaned.strip("-") or "task"


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def find_task(queue: dict[str, Any], task_id_value: str) -> dict[str, Any] | None:
    needle = task_id_value.upper()
    for task in queue.get("tasks") or []:
        if isinstance(task, dict) and task_id(task).upper() == needle:
            return task
    return None


def policy_packet_mode(policy: dict[str, Any]) -> str:
    context = policy.get("context") if isinstance(policy.get("context"), dict) else {}
    if context.get("packet_mode") == "full_worker_packet":
        return "full"
    return "minimal"


def default_prompt_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "llm_parallel_debug" / "prompts"


def default_report_path(project_root: Path) -> Path:
    return task_reports_dir(project_root) / "local_llm_planning_report.json"


def display_path(path: Path, project_root: Path) -> str:
    try:
        return path.relative_to(project_root).as_posix()
    except ValueError:
        return str(path)


def build_prompt_record(
    project_root: Path,
    queue: dict[str, Any],
    policy: dict[str, Any],
    item: dict[str, Any],
    prompt_dir: Path,
    *,
    max_doc_chars: int,
    write_prompts: bool,
) -> dict[str, Any]:
    tid = str(item["task_id"])
    task = find_task(queue, tid)
    if task is None:
        return {
            "task_id": tid,
            "status": "skipped",
            "reason": "task disappeared from tagged queue",
            "next_owner": "dispatcher",
        }

    prompt_settings = item.get("recommended_prompt") if isinstance(item.get("recommended_prompt"), dict) else {}
    recommended_task_mode = str(prompt_settings.get("task_mode") or "")
    task_mode = recommended_task_mode if task.get("llm_triage_only") is True and recommended_task_mode in {"full", "minimal"} else policy_packet_mode(policy)
    doc_mode = str(prompt_settings.get("doc_mode") or "excerpt")
    prompt_path = prompt_dir / f"{safe_name(tid)}.prompt.json"
    prompt = local_llm_prompt_builder.build_prompt(
        project_root,
        task,
        max_doc_chars=max_doc_chars,
        doc_mode=doc_mode,
        task_mode=task_mode,
    )
    if write_prompts:
        prompt_path.parent.mkdir(parents=True, exist_ok=True)
        prompt_path.write_text(prompt + "\n", encoding="utf-8")

    return {
        "task_id": tid,
        "status": "prompt_written" if write_prompts else "prompt_planned",
        "prompt_path": display_path(prompt_path, project_root),
        "task_mode": task_mode,
        "doc_mode": doc_mode,
        "llm_execution_mode": task.get("llm_execution_mode"),
        "llm_queue_key": task.get("llm_queue_key"),
        "next_owner": "remote-automation-host",
    }


def run_cycle(
    project_root: Path,
    *,
    queue_path: Path,
    policy_path: Path,
    report_path: Path | None,
    prompt_dir: Path,
    apply_tags: bool,
    write_prompts: bool,
    candidate_limit: int,
    max_doc_chars: int,
    task_id_filter: str | None = None,
) -> dict[str, Any]:
    queue = load_json(queue_path)
    policy = load_json(policy_path)
    tagged_queue, tag_report = llm_dispatch_tagger.process_queue(queue, policy, policy_path)
    if apply_tags:
        write_json(queue_path, tagged_queue)

    packet_plan = local_llm_packet_planner.build_plan(project_root, tagged_queue, candidate_limit)
    ready_items = []
    waiting_items = []
    dispatcher_items = []
    for item in packet_plan["items"]:
        if task_id_filter and str(item.get("task_id") or "").upper() != task_id_filter.upper():
            continue
        task = find_task(tagged_queue, str(item["task_id"]))
        queue_state = task.get("llm_queue_state") if isinstance(task, dict) else None
        item["llm_queue_state"] = queue_state
        item["llm_queue_key"] = task.get("llm_queue_key") if isinstance(task, dict) else None
        if item["classification"] == "llm_prompt_ready" and queue_state == "ready":
            ready_items.append(item)
        elif item["classification"] == "llm_prompt_ready":
            waiting_items.append(item)
        else:
            dispatcher_items.append(item)

    prompt_records = [
        build_prompt_record(
            project_root,
            tagged_queue,
            policy,
            item,
            prompt_dir,
            max_doc_chars=max_doc_chars,
            write_prompts=write_prompts,
        )
        for item in ready_items
    ]

    report = {
        "schema_version": 1,
        "source": "local_llm_planning_cycle.py",
        "project_root": str(project_root),
        "queue": str(queue_path),
        "policy": str(policy_path),
        "apply_tags": apply_tags,
        "write_prompts": write_prompts,
        "prompt_dir": str(prompt_dir),
        "task_id_filter": task_id_filter,
        "tag_report": tag_report,
        "packet_plan_counts": packet_plan["counts"],
        "counts": {
            "prompt_ready_now": len(ready_items),
            "prompt_ready_waiting": len(waiting_items),
            "needs_dispatcher": len(dispatcher_items),
            "prompts": len(prompt_records),
        },
        "prompts": prompt_records,
        "waiting_items": waiting_items,
        "dispatcher_items": dispatcher_items,
        "next_owner": "remote-automation-host" if prompt_records else "dispatcher",
    }
    if report_path:
        write_json(report_path, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare local LLM prompt plans from Worker Packet v2 tasks.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue", help="Defaults to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--policy", help="Defaults to AiStudio/Task_manager/local_llm_dispatch_policy.json.")
    parser.add_argument("--output", help="Defaults to AiStudio/Task_manager/reports/local_llm_planning_report.json when --write-report is used.")
    parser.add_argument("--prompt-dir", help="Defaults to AiStudio/Task_manager/llm_parallel_debug/prompts.")
    parser.add_argument("--apply", action="store_true", help="Write updated LLM tags and queue state back to task_queue.json.")
    parser.add_argument("--write-prompts", action="store_true", help="Write prompt JSON files for ready local LLM tasks.")
    parser.add_argument("--write-report", action="store_true", help="Write the planning report to the default or --output path.")
    parser.add_argument("--candidate-limit", type=int, default=40)
    parser.add_argument("--max-doc-chars", type=int, default=local_llm_prompt_builder.DEFAULT_MAX_DOC_CHARS)
    parser.add_argument("--task-id", help="Limit prompt planning to one exact task id after queue tagging.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    policy_path = Path(args.policy).resolve() if args.policy else task_file(project_root, "local_llm_dispatch_policy.json")
    prompt_dir = Path(args.prompt_dir).resolve() if args.prompt_dir else default_prompt_dir(project_root)
    report_path = None
    if args.write_report or args.output:
        report_path = Path(args.output).resolve() if args.output else default_report_path(project_root)

    report = run_cycle(
        project_root,
        queue_path=queue_path,
        policy_path=policy_path,
        report_path=report_path,
        prompt_dir=prompt_dir,
        apply_tags=args.apply,
        write_prompts=args.write_prompts,
        candidate_limit=args.candidate_limit,
        max_doc_chars=args.max_doc_chars,
        task_id_filter=args.task_id,
    )

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"queue: {queue_path}")
        print(f"policy: {policy_path}")
        print(f"apply_tags: {args.apply}")
        print(f"write_prompts: {args.write_prompts}")
        print(f"prompt_ready_now: {report['counts']['prompt_ready_now']}")
        print(f"prompt_ready_waiting: {report['counts']['prompt_ready_waiting']}")
        print(f"needs_dispatcher: {report['counts']['needs_dispatcher']}")
        if report_path:
            print(f"report: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
