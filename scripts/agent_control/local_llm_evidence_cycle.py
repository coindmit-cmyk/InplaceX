#!/usr/bin/env python3
"""Validate local LLM responses and record promotion evidence.

This postflight step does not execute a model or apply model-proposed patches.
It turns response files into quality-gate evidence, updates task queue LLM state
when requested, and increments per-kind policy evidence at most once per
response file.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import local_llm_quality_gate
from project_paths import task_file, task_manager_dir, task_reports_dir


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def task_kind(task: dict[str, Any]) -> str:
    for field in ("llm_task_kind", "task_kind", "type", "area"):
        value = str(task.get(field) or "").strip()
        if value:
            return value
    return "unknown"


def display_path(path: Path, project_root: Path) -> str:
    try:
        return path.relative_to(project_root).as_posix()
    except ValueError:
        return str(path)


def default_response_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "llm_parallel_debug" / "responses"


def default_report_path(project_root: Path) -> Path:
    return task_reports_dir(project_root) / "local_llm_evidence_report.json"


def task_id_from_response_path(path: Path) -> str:
    name = path.name
    for suffix in (".response.json", ".llm.json", ".json"):
        if name.lower().endswith(suffix):
            return name[: -len(suffix)]
    return path.stem


def collect_response_paths(paths: list[str], response_dir: Path) -> list[Path]:
    result: list[Path] = []
    seen: set[Path] = set()
    for raw in paths:
        path = Path(raw).resolve()
        if path.is_file() and path not in seen:
            result.append(path)
            seen.add(path)
    if response_dir.exists():
        for path in sorted(response_dir.glob("*.json")):
            resolved = path.resolve()
            if resolved not in seen:
                result.append(resolved)
                seen.add(resolved)
    return result


def find_task_index(queue: dict[str, Any], task_id_value: str) -> int | None:
    needle = task_id_value.upper()
    for index, task in enumerate(queue.get("tasks") or []):
        if isinstance(task, dict) and task_id(task).upper() == needle:
            return index
    return None


def evidence_history(task: dict[str, Any]) -> list[dict[str, Any]]:
    history = task.get("llm_evidence_history")
    return history if isinstance(history, list) else []


def already_recorded(task: dict[str, Any], response_relpath: str) -> bool:
    return any(isinstance(item, dict) and item.get("response_path") == response_relpath for item in evidence_history(task))


def ensure_kind_evidence(policy: dict[str, Any], kind: str) -> dict[str, Any] | None:
    kinds = policy.get("task_kinds")
    if not isinstance(kinds, dict) or not isinstance(kinds.get(kind), dict):
        return None
    item = kinds[kind]
    evidence = item.get("evidence")
    if not isinstance(evidence, dict):
        evidence = {}
        item["evidence"] = evidence
    return evidence


def increment_evidence(policy: dict[str, Any], kind: str, ok: bool, checked_at: str) -> bool:
    evidence = ensure_kind_evidence(policy, kind)
    if evidence is None:
        return False
    evidence["attempts"] = int(evidence.get("attempts") or 0) + 1
    if ok:
        evidence["successes"] = int(evidence.get("successes") or 0) + 1
    else:
        evidence["failures"] = int(evidence.get("failures") or 0) + 1
    evidence["last_evaluated_at"] = checked_at
    return True


def summarize_validation(validation: dict[str, Any]) -> dict[str, Any]:
    return {
        "ok": bool(validation.get("ok")),
        "classification": validation.get("classification"),
        "verdict": validation.get("verdict"),
        "changed_paths": validation.get("changed_paths") or [],
        "errors": validation.get("errors") or [],
        "warnings": validation.get("warnings") or [],
    }


def apply_triage_route(task: dict[str, Any], validation: dict[str, Any], checked_at: str) -> bool:
    if task.get("llm_triage_only") is not True:
        return False
    if str(task.get("llm_triage_route_on_pass") or "") != "dispatcher_repair":
        return False
    if not validation.get("ok") or validation.get("verdict") not in {"blocked", "needs_worker_fix"}:
        return False
    previous = str(task.get("status") or "")
    task["status"] = "needs_dispatcher_repair"
    task["worker_ready"] = False
    task["dispatcher_decision"] = "needs_dispatcher_repair"
    task["packet_status"] = "needs_dispatcher_repair"
    task["normalization_status"] = "needs_dispatcher_repair"
    task["owner"] = "dispatcher"
    task["next_owner"] = "Dispatcher"
    task["next_role"] = "auto_dispatcher"
    task["lock"] = "free"
    task["not_worker_ready_reason"] = "local LLM triage found a validated remediation blocker"
    task["dispatcher_decision_reason"] = task["not_worker_ready_reason"]
    task["repair_request"] = "Resolve the validated local LLM triage blocker before worker claim."
    task["missing_packet_fields"] = ["dispatcher_blocker_resolution"]
    task["repair_owner"] = "dispatcher"
    task["next_action"] = (
        "resolve the local LLM triage blocker, rerun dispatcher_packet_repair.py, "
        "then rerun task queue readiness validation"
    )
    task["dispatcher_next_review_at"] = checked_at
    task["llm_triage_routed_at"] = checked_at
    task["llm_triage_routed_by"] = "local_llm_evidence_cycle"
    history = task.get("status_history") if isinstance(task.get("status_history"), list) else []
    history.append({
        "at": checked_at,
        "by": "local_llm_evidence_cycle",
        "from": previous,
        "to": "needs_dispatcher_repair",
        "reason": "validated_local_llm_triage",
        "verdict": validation.get("verdict"),
        "next_owner": "Dispatcher",
    })
    task["status_history"] = history
    return True


def process_response(
    project_root: Path,
    queue: dict[str, Any],
    policy: dict[str, Any],
    response_path: Path,
    checked_at: str,
    *,
    apply: bool,
) -> dict[str, Any]:
    response_relpath = display_path(response_path, project_root)
    inferred_task_id = task_id_from_response_path(response_path)
    index = find_task_index(queue, inferred_task_id)
    if index is None:
        return {
            "response_path": response_relpath,
            "task_id": inferred_task_id,
            "status": "skipped",
            "reason": "task not found in queue",
            "policy_evidence_recorded": False,
        }

    task = queue["tasks"][index]
    if already_recorded(task, response_relpath):
        return {
            "response_path": response_relpath,
            "task_id": task_id(task),
            "status": "already_recorded",
            "reason": "response path is already present in llm_evidence_history",
            "policy_evidence_recorded": False,
        }

    parsed, raw_output = local_llm_quality_gate.parse_response(response_path)
    validation = local_llm_quality_gate.validate(task, parsed, raw_output, project_root=project_root)
    kind = task_kind(task)
    ok = bool(validation.get("ok"))
    policy_recorded = False
    if apply:
        policy_recorded = increment_evidence(policy, kind, ok, checked_at)
        record = {
            "checked_at": checked_at,
            "response_path": response_relpath,
            "task_kind": kind,
            "ok": ok,
            "classification": validation.get("classification"),
            "verdict": validation.get("verdict"),
            "errors": validation.get("errors") or [],
            "warnings": validation.get("warnings") or [],
        }
        history = evidence_history(task)
        history.append(record)
        task["llm_evidence_history"] = history
        task["llm_last_quality_gate"] = summarize_validation(validation)
        task["llm_last_response_path"] = response_relpath
        task["llm_finished_at"] = checked_at
        task["llm_queue_state"] = "completed" if ok else "failed_quality_gate"
        task["llm_queue_reason"] = "local LLM response passed quality gate" if ok else "local LLM response failed quality gate"
        triage_routed = apply_triage_route(task, validation, checked_at)
        pre_worker_decision = None
        if task.get("llm_triage_only") is True and task.get("llm_triage_route_on_pass") == "dispatcher_repair":
            if triage_routed:
                pre_worker_decision = "dispatcher_repair"
                pre_worker_reason = "validated local LLM triage found a remediation blocker"
            elif ok:
                pre_worker_decision = "codex_fallback_no_blocker"
                pre_worker_reason = "validated local LLM triage did not produce a Dispatcher blocker"
            else:
                pre_worker_decision = "codex_fallback_quality_gate"
                pre_worker_reason = "local LLM response failed the quality gate"
            task["llm_pre_worker_decision"] = pre_worker_decision
            task["llm_pre_worker_decided_at"] = checked_at
            task["llm_pre_worker_reason"] = pre_worker_reason
        if task.get("llm_claimed_by") is not None:
            task["llm_claimed_by"] = None
        if task.get("llm_started_at") is not None:
            task["llm_started_at"] = None

    else:
        triage_routed = False
        pre_worker_decision = None

    return {
        "response_path": response_relpath,
        "task_id": task_id(task),
        "task_kind": kind,
        "status": "recorded" if apply else "planned",
        "quality_gate": summarize_validation(validation),
        "policy_evidence_recorded": policy_recorded,
        "triage_routed": triage_routed,
        "pre_worker_decision": pre_worker_decision,
        "next_owner": "dispatcher" if triage_routed or not ok else "codex-comparison",
    }


def run_cycle(
    project_root: Path,
    *,
    queue_path: Path,
    policy_path: Path,
    response_dir: Path,
    response_paths: list[str],
    report_path: Path | None,
    apply: bool,
    task_id_filter: str | None = None,
) -> dict[str, Any]:
    queue = load_json(queue_path)
    policy = load_json(policy_path)
    checked_at = utc_now()
    responses = collect_response_paths(response_paths, response_dir)
    if task_id_filter:
        responses = [response for response in responses if task_id_from_response_path(response).upper() == task_id_filter.upper()]
    items = [
        process_response(project_root, queue, policy, response, checked_at, apply=apply)
        for response in responses
    ]
    counts: dict[str, int] = {}
    for item in items:
        status = str(item.get("status") or "unknown")
        counts[status] = counts.get(status, 0) + 1
        quality = item.get("quality_gate")
        if isinstance(quality, dict):
            key = "quality_gate_passed" if quality.get("ok") else "quality_gate_failed"
            counts[key] = counts.get(key, 0) + 1
        if item.get("triage_routed"):
            counts["triage_routed"] = counts.get("triage_routed", 0) + 1

    if apply:
        write_json(queue_path, queue)
        write_json(policy_path, policy)

    report = {
        "schema_version": 1,
        "source": "local_llm_evidence_cycle.py",
        "checked_at": checked_at,
        "project_root": str(project_root),
        "queue": str(queue_path),
        "policy": str(policy_path),
        "response_dir": str(response_dir),
        "apply": apply,
        "task_id_filter": task_id_filter,
        "counts": counts,
        "items": items,
        "next_owner": "dispatcher" if counts.get("triage_routed") else (
            "remote-automation-host" if counts.get("recorded") else "dispatcher"
        ),
    }
    if report_path:
        write_json(report_path, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate local LLM responses and record policy evidence.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue", help="Defaults to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--policy", help="Defaults to AiStudio/Task_manager/local_llm_dispatch_policy.json.")
    parser.add_argument("--response-dir", help="Defaults to AiStudio/Task_manager/llm_parallel_debug/responses.")
    parser.add_argument("--response", action="append", default=[], help="Specific response file. Can be repeated.")
    parser.add_argument("--output", help="Defaults to AiStudio/Task_manager/reports/local_llm_evidence_report.json when --write-report is used.")
    parser.add_argument("--apply", action="store_true", help="Write queue and policy evidence updates.")
    parser.add_argument("--write-report", action="store_true")
    parser.add_argument("--task-id", help="Limit evidence processing to one exact task id.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    policy_path = Path(args.policy).resolve() if args.policy else task_file(project_root, "local_llm_dispatch_policy.json")
    response_dir = Path(args.response_dir).resolve() if args.response_dir else default_response_dir(project_root)
    report_path = None
    if args.write_report or args.output:
        report_path = Path(args.output).resolve() if args.output else default_report_path(project_root)
    report = run_cycle(
        project_root,
        queue_path=queue_path,
        policy_path=policy_path,
        response_dir=response_dir,
        response_paths=args.response,
        report_path=report_path,
        apply=args.apply,
        task_id_filter=args.task_id,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"responses: {sum(report['counts'].values())}")
        print(f"recorded: {report['counts'].get('recorded', 0)}")
        print(f"already_recorded: {report['counts'].get('already_recorded', 0)}")
        print(f"quality_gate_passed: {report['counts'].get('quality_gate_passed', 0)}")
        print(f"quality_gate_failed: {report['counts'].get('quality_gate_failed', 0)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
