#!/usr/bin/env python3
"""Validate local LLM worker-packet responses before they become evidence."""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
from pathlib import Path
from typing import Any

from project_paths import task_file


ALLOWED_VERDICTS = {"patch_proposed", "already_satisfied", "needs_worker_fix", "blocked"}
PLACEHOLDER_TASK_IDS = {"12345", "TASK_ID", "[INSERT TASK ID]", "INSERT_TASK_ID"}
GENERIC_EVIDENCE_PATTERNS = (
    "readme looks comprehensive",
    "documentation is comprehensive",
    "aligns with best practices",
    "overall functionality and security",
    "task is not present in the provided context",
    "does not exist in the supplied packet",
)


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def find_task(queue: dict[str, Any], task_id_value: str) -> dict[str, Any]:
    for task in queue.get("tasks") or []:
        if isinstance(task, dict) and task_id(task).upper() == task_id_value.upper():
            return task
    raise SystemExit(f"task not found: {task_id_value}")


def response_text(data: Any) -> str:
    if isinstance(data, dict):
        for key in ("output", "response", "content"):
            if isinstance(data.get(key), str):
                return data[key]
    if isinstance(data, str):
        return data
    return json.dumps(data, ensure_ascii=False)


def parse_response(path: Path) -> tuple[dict[str, Any] | None, str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    try:
        wrapper = json.loads(text)
    except json.JSONDecodeError:
        wrapper = text
    output = response_text(wrapper).strip()
    candidate = output
    if candidate.startswith("```"):
        candidate = re.sub(r"^```(?:json)?\s*", "", candidate, flags=re.IGNORECASE)
        candidate = re.sub(r"\s*```$", "", candidate)
    try:
        parsed = json.loads(candidate)
    except json.JSONDecodeError:
        return None, output
    return parsed if isinstance(parsed, dict) else None, output


def list_of_strings(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


def structured_boolean_facts(value: Any, key: str) -> list[bool]:
    facts: list[bool] = []
    if isinstance(value, dict):
        if isinstance(value.get(key), bool):
            facts.append(value[key])
        for nested in value.values():
            facts.extend(structured_boolean_facts(nested, key))
    elif isinstance(value, list):
        for nested in value:
            facts.extend(structured_boolean_facts(nested, key))
    return facts


def match_any(path: str, patterns: list[str]) -> bool:
    normalized = path.replace("\\", "/")
    for raw in patterns:
        pattern = str(raw).replace("\\", "/")
        if fnmatch.fnmatch(normalized, pattern) or normalized == pattern.rstrip("/"):
            return True
        if pattern.endswith("/**") and normalized.startswith(pattern[:-3]):
            return True
    return False


def validate(
    task: dict[str, Any],
    parsed: dict[str, Any] | None,
    raw_output: str,
    *,
    project_root: Path | None = None,
) -> dict[str, Any]:
    tid = task_id(task)
    allowed_paths = [str(path) for path in (task.get("input_refs") or {}).get("allowed_paths") or task.get("allowed_paths") or []]
    forbidden_paths = [str(path) for path in (task.get("input_refs") or {}).get("forbidden_paths") or task.get("forbidden_paths") or []]
    errors: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []

    def issue(target: list[dict[str, str]], code: str, message: str) -> None:
        target.append({"code": code, "message": message})

    if parsed is None:
        issue(errors, "response_not_json", "response must be a single JSON object")
        output_lower = raw_output.lower()
        for placeholder in PLACEHOLDER_TASK_IDS:
            if placeholder.lower() in output_lower:
                issue(errors, "placeholder_task_id", f"response contains placeholder task id {placeholder!r}")
        if raw_output.lstrip().startswith("```"):
            issue(errors, "markdown_fence", "response must not be wrapped in Markdown fences")
        return {
            "ok": False,
            "classification": "failed_quality_gate",
            "task_id": tid,
            "errors": errors,
            "warnings": warnings,
            "parsed": None,
        }
    if raw_output.lstrip().startswith("```"):
        issue(errors, "markdown_fence", "response must not be wrapped in Markdown fences")

    got_task_id = str(parsed.get("task_id") or "").strip()
    if not got_task_id:
        issue(errors, "missing_task_id", "response missing task_id")
    elif got_task_id.upper() != tid.upper():
        issue(errors, "task_id_mismatch", f"response task_id={got_task_id!r} does not match {tid!r}")
    if got_task_id.upper() in PLACEHOLDER_TASK_IDS:
        issue(errors, "placeholder_task_id", f"response uses placeholder task_id={got_task_id!r}")

    verdict = str(parsed.get("verdict") or "").strip()
    if verdict not in ALLOWED_VERDICTS:
        issue(errors, "invalid_verdict", f"verdict must be one of {sorted(ALLOWED_VERDICTS)}")

    changed_paths = list_of_strings(parsed.get("changed_paths"))
    outside_allowed = [path for path in changed_paths if allowed_paths and not match_any(path, allowed_paths)]
    forbidden_hits = [path for path in changed_paths if match_any(path, forbidden_paths)]
    if outside_allowed:
        issue(errors, "changed_paths_outside_allowed", ", ".join(outside_allowed[:10]))
    if forbidden_hits:
        issue(errors, "changed_paths_forbidden", ", ".join(forbidden_hits[:10]))
    if verdict == "patch_proposed" and not changed_paths:
        issue(errors, "patch_without_changed_paths", "patch_proposed requires changed_paths")
    if verdict != "patch_proposed" and changed_paths:
        issue(warnings, "non_patch_has_changed_paths", "non-patch verdict should usually leave changed_paths empty")

    evidence = list_of_strings(parsed.get("evidence"))
    if not evidence:
        issue(errors, "missing_evidence", "response missing evidence")
    evidence_text = " ".join(evidence).lower()
    violations_text = " ".join(list_of_strings(parsed.get("violations"))).lower()
    for pattern in GENERIC_EVIDENCE_PATTERNS:
        if pattern in evidence_text:
            issue(errors, "generic_evidence", f"evidence contains generic phrase: {pattern}")
            break
    if re.search(r"\b(test|pytest|check|validated|passed)\b", evidence_text) and "worker_check_evidence" not in task:
        issue(warnings, "possible_invented_check", "evidence mentions checks; confirm they come from supplied context")

    input_refs = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    source_report = str(input_refs.get("source_report") or "").strip()
    if task.get("llm_triage_only") is True:
        if verdict == "patch_proposed" or changed_paths:
            issue(errors, "triage_proposed_changes", "triage-only responses must use changed_paths=[] and must not propose a patch")
    if task.get("llm_triage_only") is True and project_root is not None and source_report:
        source_path = Path(source_report)
        if not source_path.is_absolute():
            source_path = project_root / source_path
        source_exists = source_path.is_file()
        combined = f"{evidence_text} {violations_text}"
        structured_facts = structured_boolean_facts(parsed.get("evidence"), "source_report_exists")
        structured_facts.extend(structured_boolean_facts(parsed.get("violations"), "source_report_exists"))
        true_fact = True in structured_facts or "source_report_exists: true" in combined or "source_report_exists=true" in combined
        false_fact = False in structured_facts or "source_report_exists: false" in combined or "source_report_exists=false" in combined
        if source_exists:
            if false_fact or not true_fact:
                issue(
                    errors,
                    "source_report_fact_mismatch",
                    f"triage evidence must state source_report_exists: true for {source_report}",
                )
        else:
            missing_terms = ("missing", "not found", "does not exist", "absent", "unavailable", "не найден", "отсутств")
            source_name = Path(source_report).name.lower()
            identifies_source = (
                source_name in combined
                or false_fact
                or "source report" in combined
            )
            if true_fact or not identifies_source or not (false_fact or any(term in combined for term in missing_terms)):
                issue(
                    errors,
                    "missing_source_report_not_acknowledged",
                    f"triage evidence must identify missing source report {source_report}",
                )

    confidence = parsed.get("confidence")
    if not isinstance(confidence, (int, float)) or not 0 <= float(confidence) <= 1:
        issue(errors, "invalid_confidence", "confidence must be a number from 0 to 1")

    classification = "passed_quality_gate" if not errors else "failed_quality_gate"
    return {
        "ok": not errors,
        "classification": classification,
        "task_id": tid,
        "verdict": verdict or None,
        "changed_paths": changed_paths,
        "errors": errors,
        "warnings": warnings,
        "parsed": parsed,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate local LLM response quality for a Worker Packet task.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--response", required=True)
    parser.add_argument("--queue", help="Defaults to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    task = find_task(load_json(queue_path), args.task_id)
    parsed, raw_output = parse_response(Path(args.response).resolve())
    report = validate(task, parsed, raw_output, project_root=project_root)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print("ok" if report["ok"] else "; ".join(item["code"] for item in report["errors"]))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
