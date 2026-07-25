#!/usr/bin/env python3
"""Classify rebuild and integration outputs into deterministic next actions.

The classifier is read-only. It turns scattered pre-integrator, clean rebuild
and handoff statuses into items that always contain next_owner, reason and
next_event. Missing task identity is treated as recoverable metadata when the
payload has enough evidence and does not touch high-risk paths.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

from _rebuild_common import load_json, normalize_path, unique, utc_now, write_json
from process_log import append_log
from project_paths import task_manager_dir


LOW_RISK_DOC_PREFIXES = (
    "docs/",
    ".agent/",
    "old/",
)
COORDINATION_PREFIXES = (
    "docs/plans/",
    "docs/automation/",
    "docs/agent-updates/",
    ".agent/",
    "agent-worktrees/",
    "old/agent-updates/",
    "scripts/agent_control/",
    "schemas/agent-control/",
)
HIGH_RISK_PREFIXES = (
    ".github/workflows/",
    "deploy/",
    "infra/",
    "migrations/",
    "security/",
)
HIGH_RISK_KEYWORDS = (
    "auth",
    "credential",
    "payment",
    "permission",
    "production",
    "secret",
    "token",
)

EVENT_BY_DECISION = {
    "ready_candidate": "integration_requested",
    "integrator_identity_recovery": "integration_requested",
    "integrator_module_batch": "integration_requested",
    "ready_to_finalize": "finalization_requested",
    "cleanup_candidate": "cleanup_requested",
    "cleanup_consumed": "cleanup_requested",
    "crb_auto_task": "crb_task_created",
    "dispatcher_rebuild": "dispatcher_rebuild_requested",
    "provisional_crb": "provisional_crb_requested",
    "llm_advisory_classify": "llm_advisory_requested",
    "needs_worker_fix": "worker_fix_requested",
    "needs_rework": "integration_rework_requested",
    "needs_human": "needs_human_created",
}

OWNER_BY_DECISION = {
    "ready_candidate": "auto-integrator",
    "integrator_identity_recovery": "auto-integrator",
    "integrator_module_batch": "auto-integrator",
    "ready_to_finalize": "auto-finalizer",
    "cleanup_candidate": "cleanup_script",
    "cleanup_consumed": "cleanup_script",
    "crb_auto_task": "auto-dispatcher",
    "dispatcher_rebuild": "auto-dispatcher",
    "provisional_crb": "auto-dispatcher",
    "llm_advisory_classify": "auto-dispatcher",
    "needs_worker_fix": "worker",
    "needs_rework": "auto-integrator",
    "needs_human": "human",
}


def task_ids(item: dict[str, Any]) -> list[str]:
    values = item.get("task_ids") or item.get("known_task_ids") or []
    if item.get("task_id"):
        values = [item.get("task_id"), *values]
    return [str(value).strip() for value in values if str(value or "").strip()]


def changed_paths(item: dict[str, Any]) -> list[str]:
    values = (
        item.get("integration_changed_paths")
        or item.get("changed_paths")
        or item.get("paths")
        or []
    )
    return [normalize_path(path) for path in values if normalize_path(path)]


def module_scopes(paths: list[str]) -> list[str]:
    scopes: list[str] = []
    for path in paths:
        if path.startswith("docs/"):
            scopes.append("docs")
        elif "/" in path:
            scopes.append(path.split("/", 1)[0])
        elif path:
            scopes.append(path)
    return sorted(set(scopes))


def is_docs_only(paths: list[str]) -> bool:
    return bool(paths) and all(path.startswith(LOW_RISK_DOC_PREFIXES) or path in {"CHANGELOG.md", "README.md"} for path in paths)


def is_coordination_only(paths: list[str]) -> bool:
    return bool(paths) and all(path.startswith(COORDINATION_PREFIXES) for path in paths)


def risk_class(paths: list[str]) -> str:
    if not paths:
        return "low"
    lowered = " ".join(paths).lower()
    if any(path.lower() == ".env" or path.lower().startswith(".env.") for path in paths):
        return "high"
    if any(path.lower().startswith(HIGH_RISK_PREFIXES) for path in paths):
        return "high"
    if any(word in lowered for word in HIGH_RISK_KEYWORDS):
        return "high"
    if is_docs_only(paths):
        return "low"
    return "medium"


def has_integration_evidence(item: dict[str, Any], paths: list[str]) -> bool:
    if paths:
        return True
    for key in ("branch", "source_branch", "pr", "worker_report", "report_path", "commit", "head_sha"):
        if item.get(key):
            return True
    source = item.get("source") if isinstance(item.get("source"), dict) else {}
    return any(source.get(key) for key in ("branch", "source_branch", "pr", "worker_report", "report_path", "commit", "head_sha"))


def is_bounded_payload(paths: list[str], scopes: list[str]) -> bool:
    return bool(paths) and len(paths) <= 5 and len(scopes) <= 1


def is_integrator_recoverable(item: dict[str, Any], paths: list[str], risk: str) -> bool:
    return risk != "high" and has_integration_evidence(item, paths)


def metadata_only_dispatcher_reason(reason: str) -> bool:
    text = reason.lower()
    metadata_markers = (
        "branch missing claimed task_id",
        "branch missing task_id",
        "missing task identity",
        "missing task_id",
        "ambiguous task identity",
        "multiple task_ids",
    )
    return any(marker in text for marker in metadata_markers)


def not_product_payload(item: dict[str, Any], paths: list[str]) -> bool:
    status = str(item.get("code_payload_status") or item.get("readiness_blocker_type") or "")
    return not paths and status in {"not_code_payload", "coordination_only"}


def source_status(item: dict[str, Any]) -> str:
    return str(
        item.get("decision")
        or item.get("classification")
        or item.get("rebuild_route")
        or item.get("status")
        or item.get("route")
        or ""
    )


def classify_item(item: dict[str, Any]) -> dict[str, Any]:
    status = source_status(item)
    paths = changed_paths(item)
    ids = task_ids(item)
    risk = str(item.get("risk_class") or risk_class(paths))
    scopes = module_scopes(paths)
    reason = ""

    if status in {"ready_to_finalize", "finalization_ready", "integration_handoff_ready"}:
        decision = "ready_to_finalize"
        reason = "handoff or task status is ready for Auto Finalizer"
    elif status == "ready_candidate" and ids:
        decision = "ready_candidate"
        reason = "identity-valid candidate can enter Integrator batch"
    elif status == "ready_candidate":
        if is_integrator_recoverable(item, paths, risk):
            decision = "integrator_identity_recovery"
            reason = "ready-like source has no task_id, but Integrator can recover or assign provisional identity before batching"
        else:
            decision = "dispatcher_rebuild"
            reason = "ready-like source has no task_id and no enough evidence for Integrator metadata recovery"
    elif status in {"coordination_only", "cleanup_candidate", "duplicate", "stale"}:
        decision = "cleanup_candidate"
        reason = f"{status or 'candidate'} is not product payload for current package"
    elif not_product_payload(item, paths):
        decision = "cleanup_candidate"
        reason = "source is explicitly marked as not product payload"
    elif status in {"auto_clean_rebuild_small", "auto_clean_rebuild_medium", "auto_clean_rebuild_large", "auto_clean_rebuild_possible"}:
        decision = "crb_auto_task"
        reason = f"{status} can be promoted through clean_rebuild_queue_bridge"
    elif status in {"needs_human", "needs_human_rebuild"} or risk == "high":
        decision = "needs_human"
        reason = item.get("reason") or "high-risk or explicitly human-routed item"
    elif status in {"needs_worker_fix"}:
        decision = "needs_worker_fix"
        reason = item.get("reason") or "worker result needs correction before integration"
    elif status in {"needs_rework", "integration_rework_requested"}:
        decision = "needs_rework"
        reason = item.get("reason") or "Integrator repair/rework required"
    elif status in {"needs_dispatcher", "needs_dispatcher_rebuild"} and metadata_only_dispatcher_reason(str(item.get("reason") or "")) and is_integrator_recoverable(item, paths, risk):
        if len(scopes) > 1 or len(ids) > 1:
            decision = "integrator_module_batch"
            reason = "metadata-only Dispatcher route has safe evidence; Integrator resolves identity while building a module-aware batch"
        else:
            decision = "integrator_identity_recovery"
            reason = "metadata-only Dispatcher route has safe evidence; Integrator resolves identity instead of bouncing the item"
    elif status in {"needs_clean_rebuild", "needs_rebuild", "needs_dispatcher_rebuild"}:
        if len(ids) == 1 and is_bounded_payload(paths, scopes) and risk != "high":
            decision = "crb_auto_task"
            reason = "single-task bounded clean rebuild can become CRB work"
        elif is_integrator_recoverable(item, paths, risk) and not ids and len(scopes) <= 1:
            decision = "integrator_identity_recovery"
            reason = "clean rebuild source has no task_id, but has enough safe evidence for Integrator identity recovery"
        elif is_integrator_recoverable(item, paths, risk):
            decision = "integrator_module_batch"
            reason = "clean rebuild source is safe enough for Integrator to rebuild/split as a module batch"
        else:
            decision = "dispatcher_rebuild"
            reason = item.get("reason") or "clean rebuild lacks enough safe evidence for Integrator recovery"
    elif not ids and is_coordination_only(paths):
        decision = "cleanup_consumed"
        reason = "no task_id and coordination-only paths; route to cleanup/consumed, not Integrator"
    elif not ids and is_docs_only(paths):
        decision = "provisional_crb"
        reason = "no task_id but low-risk docs-only payload can become provisional CRB"
    elif not ids and is_integrator_recoverable(item, paths, risk) and len(scopes) <= 1:
        decision = "integrator_identity_recovery"
        reason = "no task_id with safe bounded product payload; Integrator performs metadata recovery before integration"
    elif not ids and is_integrator_recoverable(item, paths, risk):
        decision = "integrator_module_batch"
        reason = "no task_id with safe broad product payload; Integrator splits/coalesces module batches instead of bouncing to Dispatcher"
    elif not ids:
        decision = "dispatcher_rebuild"
        reason = "no task_id and insufficient integration evidence; Dispatcher must rebuild identity"
    elif paths and risk != "high" and len(scopes) > 1:
        decision = "integrator_module_batch"
        reason = "safe multi-module package can go to Integrator for module-aware batching; size alone is not a blocker"
    else:
        decision = "dispatcher_rebuild"
        reason = item.get("reason") or "unresolved status requires Dispatcher routing"

    next_event = EVENT_BY_DECISION[decision]
    next_owner = OWNER_BY_DECISION[decision]
    return {
        "source": item,
        "source_status": status,
        "decision": decision,
        "next_owner": next_owner,
        "reason": reason,
        "next_event": next_event,
        "task_ids": ids,
        "branch": item.get("branch") or item.get("source_branch"),
        "pr": item.get("pr"),
        "changed_paths": paths,
        "path_count": len(paths),
        "module_scopes": scopes,
        "risk_class": risk,
        "identity_valid": item.get("identity_valid"),
    }


def items_from_handoff(handoff: dict[str, Any]) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    route_fields = {
        "ready_to_finalize": "ready_to_finalize",
        "needs_dispatcher": "needs_dispatcher_rebuild",
        "needs_worker_fix": "needs_worker_fix",
        "needs_rework": "needs_rework",
        "needs_human": "needs_human",
        "cleanup_candidates": "cleanup_candidate",
    }
    dispositions = handoff.get("branch_dispositions") or []
    by_task: dict[str, dict[str, Any]] = {}
    by_branch: dict[str, dict[str, Any]] = {}
    for disposition in dispositions if isinstance(dispositions, list) else []:
        if not isinstance(disposition, dict):
            continue
        if disposition.get("task_id"):
            by_task[str(disposition["task_id"])] = disposition
        if disposition.get("branch"):
            by_branch[str(disposition["branch"])] = disposition
    for field, status in route_fields.items():
        for raw in handoff.get(field) or []:
            if isinstance(raw, dict):
                item = dict(raw)
            else:
                item = {"task_id": raw}
            lookup = by_task.get(str(item.get("task_id") or "")) or by_branch.get(str(item.get("branch") or "")) or {}
            merged = {**lookup, **item}
            merged["classification"] = status
            items.append(merged)
    return items


def collect_items(
    *,
    input_data: dict[str, Any] | None = None,
    readiness: dict[str, Any] | None = None,
    clean_rebuild: dict[str, Any] | None = None,
    handoff: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for data in (input_data, readiness):
        if isinstance(data, dict):
            result.extend(item for item in data.get("items", data.get("candidates", [])) if isinstance(item, dict))
    if isinstance(clean_rebuild, dict):
        result.extend(item for item in clean_rebuild.get("items", []) if isinstance(item, dict) and item.get("rebuild_route") != "not_clean_rebuild")
    if isinstance(handoff, dict):
        result.extend(items_from_handoff(handoff))
    return unique(result)


def build_report(
    project_root: Path,
    *,
    input_data: dict[str, Any] | None = None,
    readiness: dict[str, Any] | None = None,
    clean_rebuild: dict[str, Any] | None = None,
    handoff: dict[str, Any] | None = None,
) -> dict[str, Any]:
    items = [classify_item(item) for item in collect_items(input_data=input_data, readiness=readiness, clean_rebuild=clean_rebuild, handoff=handoff)]
    missing_contract = [item for item in items if not item.get("next_owner") or not item.get("reason") or not item.get("next_event")]
    counts = Counter(str(item.get("decision") or "unknown") for item in items)
    owner_counts = Counter(str(item.get("next_owner") or "unknown") for item in items)
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "source": "rebuild_decision_classifier.py",
        "policy": "missing task_id is recoverable metadata when safe evidence exists; package size alone does not block Integrator",
        "item_count": len(items),
        "counts": dict(counts),
        "owner_counts": dict(owner_counts),
        "contract_valid": not missing_contract,
        "contract_errors": missing_contract,
        "items": items,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--input")
    parser.add_argument("--readiness")
    parser.add_argument("--clean-rebuild-plan")
    parser.add_argument("--handoff")
    parser.add_argument("--output")
    parser.add_argument("--apply", action="store_true", help="Write the default report and process log. Without this, only --output writes a file.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    readiness_path = Path(args.readiness).resolve() if args.readiness else plans / "pr_readiness_report.identity_filtered.json"
    if not readiness_path.exists():
        readiness_path = plans / "pr_readiness_report.json"
    clean_path = Path(args.clean_rebuild_plan).resolve() if args.clean_rebuild_plan else plans / "clean_rebuild_plan.json"
    handoff_path = Path(args.handoff).resolve() if args.handoff else plans / "integration_handoff.json"
    output_path = Path(args.output).resolve() if args.output else (plans / "rebuild_decision_report.json" if args.apply else None)

    report = build_report(
        project_root,
        input_data=load_json(args.input, {}) if args.input else None,
        readiness=load_json(readiness_path, {}),
        clean_rebuild=load_json(clean_path, {}),
        handoff=load_json(handoff_path, {}),
    )
    if output_path:
        write_json(output_path, report)
    if args.apply:
        append_log(project_root, "router", "rebuild_decisions_classified", severity="info", counts=report["counts"], contract_valid=report["contract_valid"])
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"items: {report['item_count']}")
        print(f"counts: {report['counts']}")
        print(f"written: {output_path or '-'}")
    return 0 if report["contract_valid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
