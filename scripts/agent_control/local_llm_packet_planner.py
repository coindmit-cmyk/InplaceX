#!/usr/bin/env python3
"""Plan minimal local-LLM packet prompts for Dispatcher follow-up."""

from __future__ import annotations

import argparse
import fnmatch
import json
from pathlib import Path
from typing import Any

from project_paths import task_file


NOISY_DOC_PATTERNS = (
    "task_queue.json",
    "agent_events.jsonl",
    "MVP_STATUS.md",
    "reports/workers/",
    "llm_parallel_debug/",
)


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def as_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item) for item in value if str(item)]
    if isinstance(value, str) and value:
        return [value]
    return []


def match_any(path: str, patterns: list[str]) -> bool:
    normalized = path.replace("\\", "/")
    for raw in patterns:
        pattern = raw.replace("\\", "/")
        if fnmatch.fnmatch(normalized, pattern) or normalized == pattern.rstrip("/"):
            return True
        if pattern.endswith("/**") and normalized.startswith(pattern[:-3]):
            return True
    return False


def existing_candidate_paths(project_root: Path, allowed_paths: list[str], forbidden_paths: list[str], limit: int) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for raw in allowed_paths:
        pattern = raw.replace("\\", "/")
        if pattern.endswith("/**"):
            matches = (project_root / pattern[:-3]).rglob("*")
        elif any(ch in pattern for ch in "*?["):
            matches = project_root.glob(pattern)
        else:
            matches = [project_root / pattern]
        for path in matches:
            if not path.is_file():
                continue
            relative = path.relative_to(project_root).as_posix()
            if match_any(relative, forbidden_paths):
                continue
            if relative not in seen:
                result.append(relative)
                seen.add(relative)
            if len(result) >= limit:
                return result
    return result


def doc_paths(task: dict[str, Any]) -> list[str]:
    paths: list[str] = []
    for ref in task.get("doc_refs") or []:
        if isinstance(ref, dict) and ref.get("path"):
            paths.append(str(ref["path"]))
        elif isinstance(ref, str):
            paths.append(ref)
    for ref in task.get("context_docs") or []:
        if isinstance(ref, str) and ref not in paths:
            paths.append(ref)
    return paths


def noisy_docs(paths: list[str]) -> list[str]:
    noisy: list[str] = []
    for path in paths:
        normalized = path.replace("\\", "/")
        if any(pattern in normalized for pattern in NOISY_DOC_PATTERNS):
            noisy.append(path)
    return noisy


def classify_task(project_root: Path, task: dict[str, Any], candidate_limit: int) -> dict[str, Any]:
    tid = task_id(task)
    input_refs = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    allowed_paths = as_list(input_refs.get("allowed_paths")) or as_list(task.get("allowed_paths"))
    forbidden_paths = as_list(input_refs.get("forbidden_paths")) or as_list(task.get("forbidden_paths"))
    candidates = existing_candidate_paths(project_root, allowed_paths, forbidden_paths, candidate_limit)
    docs = doc_paths(task)
    noisy = noisy_docs(docs)
    reasons: list[str] = []

    if not task.get("llm_candidate"):
        reasons.append("task is not tagged llm_candidate")
    if str(task.get("llm_execution_mode") or "") not in {"parallel_debug", "llm_only"}:
        reasons.append("task execution mode is not local-LLM enabled")
    if int(task.get("packet_schema_version") or 1) < 2:
        reasons.append("task is not Worker Packet v2")
    if not allowed_paths:
        reasons.append("missing allowed_paths")
    if not candidates:
        reasons.append("no existing candidate path under allowed_paths")
    triage_only = task.get("llm_triage_only") is True
    if len(candidates) > 1 and not triage_only:
        reasons.append("multiple candidate paths; Dispatcher should choose one explicit target")
    if noisy and not triage_only:
        reasons.append("noisy doc refs should use metadata mode or be removed")

    if not reasons:
        classification = "llm_prompt_ready"
    elif candidates and all(reason in {"multiple candidate paths; Dispatcher should choose one explicit target", "noisy doc refs should use metadata mode or be removed"} for reason in reasons):
        classification = "needs_dispatcher_repack"
    else:
        classification = "not_llm_prompt_ready"

    return {
        "task_id": tid,
        "title": task.get("title"),
        "type": task.get("type"),
        "llm_candidate": bool(task.get("llm_candidate")),
        "llm_execution_mode": task.get("llm_execution_mode"),
        "llm_triage_only": triage_only,
        "classification": classification,
        "reasons": reasons,
        "allowed_paths": allowed_paths,
        "existing_candidate_paths": candidates,
        "recommended_target_path": candidates[0] if len(candidates) == 1 else None,
        "noisy_doc_refs": noisy,
        "recommended_prompt": {
            "task_mode": "minimal",
            "doc_mode": "metadata" if noisy else "excerpt",
            "response_format_json": True,
            "quality_gate_required": True,
        },
        "next_owner": "dispatcher" if classification != "llm_prompt_ready" else "remote-automation-host",
    }


def build_plan(project_root: Path, queue: dict[str, Any], candidate_limit: int) -> dict[str, Any]:
    items = [
        classify_task(project_root, task, candidate_limit)
        for task in queue.get("tasks") or []
        if isinstance(task, dict) and (task.get("llm_candidate") or task.get("llm_parallel_required"))
    ]
    counts: dict[str, int] = {}
    for item in items:
        counts[item["classification"]] = counts.get(item["classification"], 0) + 1
    return {
        "schema_version": 1,
        "source": "local_llm_packet_planner.py",
        "counts": counts,
        "items": items,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Plan minimal prompt packets for local LLM candidates.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue", help="Defaults to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--output", help="Plan output path. Prints JSON when omitted.")
    parser.add_argument("--candidate-limit", type=int, default=40)
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    plan = build_plan(project_root, load_json(queue_path), args.candidate_limit)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
