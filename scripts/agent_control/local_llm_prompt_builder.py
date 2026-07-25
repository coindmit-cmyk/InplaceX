#!/usr/bin/env python3
"""Build strict local-LLM prompts from Worker Packet v2 tasks."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from project_paths import task_file


DEFAULT_MAX_DOC_CHARS = 8000
DEFAULT_MAX_CANDIDATE_PATHS = 80
RESPONSE_SCHEMA = {
    "schema_version": 1,
    "task_id": "exact task id from the worker packet",
    "verdict": "patch_proposed | already_satisfied | needs_worker_fix | blocked",
    "changed_paths": ["paths the LLM proposes to edit; empty when no patch is proposed"],
    "evidence": ["specific evidence string, or for triage a fact object such as {source_report_exists: false}"],
    "violations": ["contract violations or blockers discovered by the LLM"],
    "confidence": 0.0,
    "comparison_notes": "short note for Codex-vs-LLM review",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def find_task(queue: dict[str, Any], task_id_value: str) -> dict[str, Any]:
    for task in queue.get("tasks") or []:
        if isinstance(task, dict) and task_id(task).upper() == task_id_value.upper():
            return task
    raise SystemExit(f"task not found: {task_id_value}")


def doc_ref_paths(task: dict[str, Any]) -> list[str]:
    paths: list[str] = []
    for ref in task.get("doc_refs") or []:
        if isinstance(ref, dict) and ref.get("path"):
            paths.append(str(ref["path"]))
        elif isinstance(ref, str) and ref:
            paths.append(ref)
    for ref in task.get("context_docs") or []:
        if isinstance(ref, str) and ref and ref not in paths:
            paths.append(ref)
    return paths


def read_doc(project_root: Path, path_value: str, max_chars: int, *, doc_mode: str) -> dict[str, Any]:
    path = project_root / path_value
    if not path.exists() or not path.is_file():
        return {"path": path_value, "exists": False, "content": ""}
    if doc_mode == "metadata":
        return {"path": path_value, "exists": True, "content": ""}
    text = path.read_text(encoding="utf-8", errors="replace")
    truncated = len(text) > max_chars
    if truncated:
        text = text[:max_chars] + "\n[TRUNCATED]\n"
    return {"path": path_value, "exists": True, "truncated": truncated, "content": text}


def candidate_paths(project_root: Path, allowed_paths: list[Any], forbidden_paths: list[Any], limit: int) -> list[str]:
    found: list[str] = []
    seen: set[str] = set()
    forbidden = [str(path).replace("\\", "/") for path in forbidden_paths]
    for raw in allowed_paths:
        pattern = str(raw).replace("\\", "/")
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
            if any(relative == item or relative.startswith(item[:-2]) for item in forbidden if item.endswith("/**")):
                continue
            if relative not in seen:
                found.append(relative)
                seen.add(relative)
            if len(found) >= limit:
                return found
    return found


def minimal_task(task: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "id",
        "task_id",
        "canonical_task_id",
        "title",
        "type",
        "priority",
        "complexity",
        "worker_instructions",
        "traceability",
        "allowed_paths",
        "forbidden_paths",
        "acceptance_criteria",
        "checks",
        "doc_refs",
        "input_refs",
        "script_actions",
    )
    return {key: task[key] for key in keys if key in task}


def build_prompt(project_root: Path, task: dict[str, Any], *, max_doc_chars: int, doc_mode: str, task_mode: str) -> str:
    tid = task_id(task)
    allowed_paths = (task.get("input_refs") or {}).get("allowed_paths") or task.get("allowed_paths") or []
    forbidden_paths = (task.get("input_refs") or {}).get("forbidden_paths") or task.get("forbidden_paths") or []
    docs = [read_doc(project_root, path, max_doc_chars, doc_mode=doc_mode) for path in doc_ref_paths(task)]
    input_refs = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    source_report = str(input_refs.get("source_report") or "").strip()
    source_report_path = Path(source_report) if source_report else None
    if source_report_path is not None and not source_report_path.is_absolute():
        source_report_path = project_root / source_report_path
    payload = {
        "contract_summary": {
            "task_id": tid,
            "allowed_paths": allowed_paths,
            "forbidden_paths": forbidden_paths,
            "existing_candidate_paths": candidate_paths(project_root, allowed_paths, forbidden_paths, DEFAULT_MAX_CANDIDATE_PATHS),
            "path_rule": "Every changed_paths item must match allowed_paths and must not match forbidden_paths. If no valid path is clear, return needs_worker_fix with changed_paths=[].",
            "json_rule": "Return raw JSON only. Do not wrap it in Markdown fences.",
            "source_report": source_report or None,
            "source_report_exists": source_report_path.is_file() if source_report_path is not None else None,
        },
        "task": minimal_task(task) if task_mode == "minimal" else task,
        "doc_refs": docs,
        "response_schema": RESPONSE_SCHEMA,
        "hard_rules": [
            "Return exactly one raw JSON object. No Markdown fences. No prose outside JSON.",
            f"task_id must equal {tid!r}. Do not use placeholders.",
            "changed_paths must be empty or match contract_summary.allowed_paths.",
            "Do not propose edits to forbidden_paths.",
            "Do not invent test results, files, APIs or repository state.",
            "Do not use paths just because they appear in docs. Only contract_summary.allowed_paths permits edits.",
            "Fields named worker_report_path or report paths are evidence outputs, not patch targets, unless they also appear in contract_summary.allowed_paths.",
            "If you cannot satisfy the task from supplied context, use verdict needs_worker_fix or blocked.",
            "For parallel_debug, prefer a small, auditable result over broad implementation ideas.",
            "For triage-only tasks, cite contract_summary source facts exactly. Do not replace a missing source-report fact with guesses about allowed or forbidden paths.",
            "For triage-only tasks with a source report, include source_report_exists as an exact string fact or a boolean field in an evidence object.",
            "Triage-only tasks must not propose file changes. Use changed_paths=[] and do not use patch_proposed.",
        ],
        "failure_examples": [
            {"reason": "wrong task id", "bad": {"task_id": "12345"}},
            {"reason": "generic evidence", "bad": {"evidence": ["README looks comprehensive"]}},
            {"reason": "broad scope", "bad": {"changed_paths": ["control/myvpn_control/api.py", "services/accounts.py"]}},
        ],
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build a strict local LLM prompt from a Worker Packet v2 task.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--queue", help="Defaults to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--output", help="Prompt output path. Prints to stdout when omitted.")
    parser.add_argument("--max-doc-chars", type=int, default=DEFAULT_MAX_DOC_CHARS)
    parser.add_argument("--doc-mode", choices=("excerpt", "metadata"), default="excerpt")
    parser.add_argument("--task-mode", choices=("full", "minimal"), default="full")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    task = find_task(load_json(queue_path), args.task_id)
    prompt = build_prompt(project_root, task, max_doc_chars=args.max_doc_chars, doc_mode=args.doc_mode, task_mode=args.task_mode)

    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(prompt + "\n", encoding="utf-8")
    else:
        print(prompt)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
