#!/usr/bin/env python3
"""Normalize Artifact Discovery routes into scoped Task Manager rows.

The router exposes raw task candidates, which can be numerous. This normalizer
groups those findings into Dispatcher-owned follow-up rows and, optionally,
promotes only the first safe Project Map backfill into a full Worker Packet v2.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import sys
from copy import deepcopy
from pathlib import Path
from typing import Any

from dispatcher_packet_repair import apply_v2_packet
from project_paths import task_file


DEFAULT_FORBIDDEN_PATHS = [
    ".git/**",
    ".env",
    ".env.*",
    "**/__pycache__/**",
    "**/.pytest_cache/**",
    "node_modules/**",
    "secrets/**",
    "AiStudio/Task_manager/**",
    "scripts/**",
    "docs/agent/discovery/**",
]
GROUPS = [
    {
        "suffix": "PROJECT-MAP-BATCH",
        "category": "missing_project_map_coverage",
        "owner": "Dispatcher",
        "title": "ADL follow-up: batch Project Map coverage findings",
        "task_type": "reality_map_backfill_batch",
        "purpose": "Batch missing Project Map coverage findings before worker execution.",
    },
    {
        "suffix": "INDEX-LINKS",
        "category": "missing_index_link",
        "owner": "Dispatcher",
        "title": "ADL follow-up: triage missing index links",
        "task_type": "integration_surface_triage",
        "purpose": "Triage missing index links and build scoped Integrator/Worker packets.",
    },
    {
        "suffix": "SCRIPT-CATALOG",
        "category": "missing_script_catalog_entry",
        "owner": "Dispatcher",
        "title": "ADL follow-up: triage missing script catalog entries",
        "task_type": "automation_surface_triage",
        "purpose": "Triage script catalog gaps and build scoped follow-up packets.",
    },
    {
        "suffix": "SCHEMA-TEMPLATE-PAIRS",
        "category": "missing_validator_template_pair",
        "owner": "Dispatcher",
        "title": "ADL follow-up: triage schema/template pair gaps",
        "task_type": "schema_template_triage",
        "purpose": "Triage schema/template pair findings before implementation.",
    },
    {
        "suffix": "CLEANUP-CANDIDATES",
        "category": "cleanup_candidate",
        "owner": "Dispatcher",
        "title": "ADL follow-up: triage cleanup candidates",
        "task_type": "cleanup_candidate_triage",
        "purpose": "Review cleanup candidates without automatic deletion.",
    },
    {
        "suffix": "SENSITIVE-RISK",
        "category": "possible_secret_pattern",
        "owner": "Human",
        "title": "ADL follow-up: route sensitive-risk findings",
        "task_type": "security_review_triage",
        "purpose": "Route sensitive-risk findings without exposing raw secret-like material.",
    },
]
NORMALIZER_VERSION = "1.0-release"
COMMON_ROW_REQUIRED_FIELDS = [
    "id",
    "canonical_task_id",
    "title",
    "status",
    "worker_ready",
    "packet_status",
    "normalization_status",
    "dispatcher_decision",
    "next_owner",
    "source_file",
    "source_finding_category",
    "created_at",
    "created_by",
    "provenance",
    "context_docs",
    "allowed_paths",
    "forbidden_paths",
    "acceptance_criteria",
    "checks",
]
WORKER_PACKET_REQUIRED_FIELDS = [
    "packet_schema_version",
    "doc_refs",
    "input_refs",
    "output_contract",
    "script_actions",
    "source_finding_id",
    "artifact_disposition",
    "semantic_kind",
    "implementation_status",
    "integration_status",
]


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def today_id() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d")


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def stable_hash(data: dict[str, Any]) -> str:
    payload = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def safe_slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-")[:80] or "artifact"


def findings(report: dict[str, Any]) -> list[dict[str, Any]]:
    return [item for item in report.get("findings") or [] if isinstance(item, dict)]


def finding_category(finding: dict[str, Any]) -> str:
    return str(finding.get("category") or "").strip()


def finding_path(finding: dict[str, Any]) -> str:
    return str(finding.get("path") or "").replace("\\", "/").strip()


def finding_flags(finding: dict[str, Any]) -> list[str]:
    return [str(flag) for flag in finding.get("artifact_flags") or [] if str(flag)]


def finding_disposition(finding: dict[str, Any]) -> str:
    return str(finding.get("artifact_disposition") or "").strip()


def finding_semantic_kind(finding: dict[str, Any]) -> str:
    return str(finding.get("semantic_kind") or "").strip()


def finding_implementation_status(finding: dict[str, Any]) -> str:
    return str(finding.get("implementation_status") or "").strip()


def finding_integration_status(finding: dict[str, Any]) -> str:
    return str(finding.get("integration_status") or "").strip()


def group_flag_summary(items: list[dict[str, Any]]) -> list[str]:
    flags: set[str] = set()
    for item in items:
        flags.update(finding_flags(item))
    return sorted(flags)


def group_disposition_summary(items: list[dict[str, Any]]) -> list[str]:
    return sorted({finding_disposition(item) for item in items if finding_disposition(item)})


def group_semantic_kind_summary(items: list[dict[str, Any]]) -> list[str]:
    return sorted({finding_semantic_kind(item) for item in items if finding_semantic_kind(item)})


def group_implementation_status_summary(items: list[dict[str, Any]]) -> list[str]:
    return sorted({finding_implementation_status(item) for item in items if finding_implementation_status(item)})


def group_integration_status_summary(items: list[dict[str, Any]]) -> list[str]:
    return sorted({finding_integration_status(item) for item in items if finding_integration_status(item)})


def sample_artifacts(items: list[dict[str, Any]], limit: int = 20) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    for item in items:
        path = finding_path(item)
        if not path:
            continue
        samples.append({
            "path": path,
            "artifact_flags": finding_flags(item),
            "artifact_disposition": finding_disposition(item),
            "semantic_kind": finding_semantic_kind(item),
            "implementation_status": finding_implementation_status(item),
            "integration_status": finding_integration_status(item),
            "integration_gaps": item.get("integration_gaps") or [],
        })
        if len(samples) >= limit:
            break
    return samples


def safe_first_worker_finding(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    candidates = [
        item for item in items
        if finding_category(item) == "missing_project_map_coverage"
        and item.get("auto_task_allowed", True) is True
        and finding_path(item)
        and not finding_path(item).startswith(("AiStudio/Task_manager/", "docs/reports/discovery/"))
    ]
    return sorted(candidates, key=lambda item: finding_path(item))[0] if candidates else None


def base_provenance(routed_path: Path, generated_at: str, summary: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        {
            "source_type": "artifact_discovery_routed_report",
            "source_file": routed_path.as_posix(),
            "source_item_id": "artifact_discovery_normalized_followups",
            "captured_at": generated_at,
            "summary": (
                f"Artifact Discovery produced {summary.get('finding_count', 0)} findings "
                f"and {summary.get('task_candidate_count', 0)} raw task candidates; "
                "normalizer grouped them into scoped follow-up rows."
            ),
        }
    ]


def group_row(
    *,
    batch_id: str,
    index: int,
    group: dict[str, str],
    items: list[dict[str, Any]],
    routed_path: Path,
    report: dict[str, Any],
    generated_at: str,
) -> dict[str, Any] | None:
    category_items = [item for item in items if finding_category(item) == group["category"]]
    if not category_items:
        return None
    sample_paths = [finding_path(item) for item in category_items[:20] if finding_path(item)]
    sample_items = sample_artifacts(category_items)
    owner = group["owner"]
    decision = "needs_human" if owner == "Human" else "needs_dispatcher_review"
    status = "needs_human" if owner == "Human" else "planned"
    return {
        "id": f"ADL-FOLLOWUP-{batch_id}-{index:03d}-{group['suffix']}",
        "canonical_task_id": f"ADL-FOLLOWUP-{batch_id}-{index:03d}-{group['suffix']}",
        "title": group["title"],
        "status": status,
        "worker_ready": False,
        "packet_status": decision,
        "normalization_status": decision,
        "dispatcher_decision": decision,
        "dispatcher_decision_reason": group["purpose"],
        "not_worker_ready_reason": "ADL normalized group needs Dispatcher scoping into a narrow Worker Packet v2 before execution.",
        "next_owner": owner,
        "priority": "P2",
        "complexity": "M",
        "type": group["task_type"],
        "source_file": routed_path.as_posix(),
        "source_summary": group["purpose"],
        "source_finding_category": group["category"],
        "finding_count": len(category_items),
        "sample_paths": sample_paths,
        "sample_artifacts": sample_items,
        "artifact_flags": group_flag_summary(category_items),
        "artifact_dispositions": group_disposition_summary(category_items),
        "semantic_kinds": group_semantic_kind_summary(category_items),
        "implementation_statuses": group_implementation_status_summary(category_items),
        "integration_statuses": group_integration_status_summary(category_items),
        "created_at": generated_at,
        "created_by": "scripts/agent_control/artifact_discovery_normalizer.py",
        "provenance": base_provenance(routed_path, generated_at, report.get("summary") or {}),
        "context_docs": [
            "docs/agent/discovery/README.md",
            "docs/agent/discovery/REPORTING.md",
            routed_path.as_posix(),
        ],
        "allowed_paths": [
            "AiStudio/Task_manager/task_queue.json",
            "docs/reports/dispatcher/**",
            "docs/reports/discovery/**",
        ],
        "forbidden_paths": DEFAULT_FORBIDDEN_PATHS,
        "acceptance_criteria": [
            "Dispatcher chooses a small path batch or explicit non-worker disposition.",
            "No raw Artifact Discovery candidate is marked worker_ready without Worker Packet v2 fields.",
            "Sensitive-risk findings remain Human/Doctor/security review work and do not expose raw values.",
        ],
        "checks": [
            "python -m json.tool AiStudio/Task_manager/task_queue.json",
            "python scripts/agent_control/validate_task_queue_readiness.py --queue AiStudio/Task_manager/task_queue.json --json",
            "git diff --check",
        ],
    }


def worker_row(
    *,
    batch_id: str,
    finding: dict[str, Any],
    routed_path: Path,
    generated_at: str,
    summary: dict[str, Any],
) -> dict[str, Any]:
    path = finding_path(finding)
    task_id = f"ADL-FOLLOWUP-{batch_id}-001-PROJECT-MAP-{safe_slug(path).upper()}"
    base = {
        "id": task_id,
        "canonical_task_id": task_id,
        "canonical_target_id": f"task:{task_id}",
        "title": f"ADL follow-up: map {path} in PROJECT_MAP.md",
        "status": "planned",
        "worker_ready": True,
        "packet_status": "worker_ready",
        "normalization_status": "worker_ready",
        "dispatcher_decision": "worker_ready",
        "dispatcher_decision_reason": "First safe scoped ADL Project Map finding normalized into Worker Packet v2.",
        "next_owner": "Worker",
        "priority": "P2",
        "complexity": "S",
        "type": "documentation_map_backfill",
        "recommended_agent": "auto-worker-5.3-mini",
        "eligible_worker_profiles": ["auto-worker-5.3-mini", "auto-worker-5.3"],
        "base_branch": "develop",
        "base_ref": "origin/develop",
        "source_file": routed_path.as_posix(),
        "source_summary": f"First safe scoped ADL finding: missing_project_map_coverage for {path}.",
        "source_finding_id": finding.get("id"),
        "source_finding_category": finding_category(finding),
        "artifact_flags": finding_flags(finding),
        "artifact_disposition": finding_disposition(finding),
        "semantic_kind": finding_semantic_kind(finding),
        "implementation_status": finding_implementation_status(finding),
        "implementation_evidence": finding.get("implementation_evidence") or [],
        "integration_status": finding_integration_status(finding),
        "integration_gaps": finding.get("integration_gaps") or [],
        "integration_evidence": finding.get("integration_evidence") or [],
        "created_at": generated_at,
        "created_by": "scripts/agent_control/artifact_discovery_normalizer.py",
        "provenance": base_provenance(routed_path, generated_at, summary),
        "requires_current_context_review": True,
        "current_context_verified_at": generated_at,
        "current_context_verified_by": "scripts/agent_control/artifact_discovery_normalizer.py",
        "current_context_reviewed_by": "scripts/agent_control/artifact_discovery_normalizer.py",
        "context_docs": [
            "PROJECT_MAP.md",
            "docs/agent/discovery/README.md",
            "docs/agent/discovery/REPORTING.md",
            routed_path.as_posix(),
        ],
        "allowed_paths": ["PROJECT_MAP.md", "docs/reports/workers/**"],
        "forbidden_paths": DEFAULT_FORBIDDEN_PATHS,
        "acceptance_criteria": [
            f"PROJECT_MAP.md explicitly records `{path}` under appropriate map coverage without broad remapping.",
            "The worker report records Artifact Discovery routed report evidence.",
            "No Artifact Discovery docs, scripts, generated reports or Task_manager state are modified by the worker.",
        ],
        "checks": [
            "python scripts/agent_control/artifact_discovery_scanner.py --project-root . --json",
            "python -m json.tool AiStudio/Task_manager/task_queue.json",
            "git diff --check",
        ],
        "doc_refs": [
            {"path": "PROJECT_MAP.md", "purpose": "target map file"},
            {"path": "docs/agent/discovery/README.md", "purpose": "ADL runtime boundary"},
            {"path": routed_path.as_posix(), "purpose": "routed ADL finding output"},
        ],
        "input_refs": {
            "base_ref": "origin/develop",
            "finding_id": finding.get("id"),
            "finding_category": finding_category(finding),
            "artifact_flags": finding_flags(finding),
            "artifact_disposition": finding_disposition(finding),
            "semantic_kind": finding_semantic_kind(finding),
            "implementation_status": finding_implementation_status(finding),
            "integration_status": finding_integration_status(finding),
            "integration_gaps": finding.get("integration_gaps") or [],
            "target_path": path,
            "allowed_paths": ["PROJECT_MAP.md", "docs/reports/workers/**"],
            "forbidden_paths": DEFAULT_FORBIDDEN_PATHS,
        },
        "output_contract": {
            "changed_paths_must_match_allowed_paths": True,
            "required_changed_paths": ["PROJECT_MAP.md"],
            "optional_report_paths": ["docs/reports/workers/**"],
            "runtime_automation_changed": False,
            "task_queue_mutation_allowed": False,
            "artifact_discovery_scripts_changed": False,
            "documentation_impact_required": True,
        },
        "script_actions": [
            "Read PROJECT_MAP.md, docs/agent/discovery/README.md and the routed ADL report before editing.",
            f"Update PROJECT_MAP.md only for `{path}` coverage, or write a no-op worker report if already covered.",
            "Run python scripts/agent_control/artifact_discovery_scanner.py --project-root . --json.",
            "Run python -m json.tool AiStudio/Task_manager/task_queue.json.",
            "Run git diff --check.",
        ],
    }
    return apply_v2_packet(base, generated_at)


def missing_fields(row: dict[str, Any], fields: list[str]) -> list[str]:
    missing: list[str] = []
    for field in fields:
        value = row.get(field)
        if value is None or value == "" or value == [] or value == {}:
            missing.append(field)
    return missing


def validate_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    worker_ready_ids: list[str] = []

    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            errors.append({"row": f"row[{index}]", "field": "<row>", "message": "row must be an object"})
            continue
        task_id = str(row.get("id") or "").strip()
        label = task_id or f"row[{index}]"
        if task_id in seen_ids:
            errors.append({"row": label, "field": "id", "message": "duplicate normalized row id"})
        if task_id:
            seen_ids.add(task_id)
        if not task_id.startswith("ADL-FOLLOWUP-"):
            errors.append({"row": label, "field": "id", "message": "normalized ADL rows must use ADL-FOLLOWUP-* ids"})
        if row.get("canonical_task_id") != task_id:
            errors.append({"row": label, "field": "canonical_task_id", "message": "canonical_task_id must match id"})
        if row.get("created_by") != "scripts/agent_control/artifact_discovery_normalizer.py":
            errors.append({"row": label, "field": "created_by", "message": "row must be owned by the ADL normalizer"})

        for field in missing_fields(row, COMMON_ROW_REQUIRED_FIELDS):
            errors.append({"row": label, "field": field, "message": "required normalized row field is missing"})

        if row.get("worker_ready") is True:
            worker_ready_ids.append(label)
            for field in missing_fields(row, WORKER_PACKET_REQUIRED_FIELDS):
                errors.append({"row": label, "field": field, "message": "worker_ready row is missing Worker Packet v2 field"})
            if row.get("packet_schema_version") != 2:
                errors.append({"row": label, "field": "packet_schema_version", "message": "worker_ready row must be Worker Packet v2"})
            output_contract = row.get("output_contract") if isinstance(row.get("output_contract"), dict) else {}
            if output_contract.get("task_queue_mutation_allowed") is not False:
                errors.append({"row": label, "field": "output_contract.task_queue_mutation_allowed", "message": "worker packet must not mutate Task_manager"})
            if output_contract.get("changed_paths_must_match_allowed_paths") is not True:
                errors.append({"row": label, "field": "output_contract.changed_paths_must_match_allowed_paths", "message": "worker packet must constrain changed paths"})
            if row.get("dispatcher_decision") != "worker_ready":
                errors.append({"row": label, "field": "dispatcher_decision", "message": "worker_ready row must have dispatcher_decision=worker_ready"})
        elif row.get("worker_ready") is False:
            if not row.get("not_worker_ready_reason"):
                errors.append({"row": label, "field": "not_worker_ready_reason", "message": "non-worker row must explain why it is not worker_ready"})
            if row.get("packet_status") == "worker_ready" or row.get("dispatcher_decision") == "worker_ready":
                errors.append({"row": label, "field": "dispatcher_decision", "message": "non-worker row cannot be marked worker_ready"})
            if row.get("next_owner") == "Worker":
                warnings.append({"row": label, "field": "next_owner", "message": "non-worker row should not be assigned directly to Worker"})
        else:
            errors.append({"row": label, "field": "worker_ready", "message": "worker_ready must be boolean"})

    if len(worker_ready_ids) > 1:
        errors.append({
            "row": ",".join(worker_ready_ids),
            "field": "worker_ready",
            "message": "normalizer may emit at most one worker_ready ADL packet per batch",
        })

    return {
        "ok": not errors,
        "normalizer_version": NORMALIZER_VERSION,
        "error_count": len(errors),
        "warning_count": len(warnings),
        "errors": errors,
        "warnings": warnings,
        "checked_rows": len(rows),
        "worker_ready_rows": len(worker_ready_ids),
    }


def normalize_report(
    report: dict[str, Any],
    *,
    routed_path: Path,
    batch_id: str,
    worker_ready_first_safe: bool,
) -> dict[str, Any]:
    generated_at = utc_now()
    items = findings(report)
    rows: list[dict[str, Any]] = []
    if worker_ready_first_safe:
        first = safe_first_worker_finding(items)
        if first:
            rows.append(worker_row(
                batch_id=batch_id,
                finding=first,
                routed_path=routed_path,
                generated_at=generated_at,
                summary=report.get("summary") or {},
            ))
    next_index = 2 if rows else 1
    for group in GROUPS:
        row = group_row(
            batch_id=batch_id,
            index=next_index,
            group=group,
            items=items,
            routed_path=routed_path,
            report=report,
            generated_at=generated_at,
        )
        if row:
            rows.append(row)
            next_index += 1
    release_gate = validate_rows(rows)
    return {
        "schema_version": "1.0",
        "normalizer_version": NORMALIZER_VERSION,
        "generated_at": generated_at,
        "mode": "artifact_discovery_normalized_followups",
        "source_report": routed_path.as_posix(),
        "source_report_hash": stable_hash(report),
        "summary": {
            "input_findings": len(items),
            "raw_task_candidates": len([item for item in report.get("task_candidates") or [] if isinstance(item, dict)]),
            "normalized_rows": len(rows),
            "worker_ready_rows": sum(1 for row in rows if row.get("worker_ready") is True),
            "dispatcher_owned_rows": sum(1 for row in rows if row.get("next_owner") == "Dispatcher"),
            "human_owned_rows": sum(1 for row in rows if row.get("next_owner") == "Human"),
            "release_ready": release_gate["ok"],
            "release_gate_errors": release_gate["error_count"],
            "release_gate_warnings": release_gate["warning_count"],
        },
        "release_gate": release_gate,
        "rows": rows,
    }


def existing_task_ids(queue: dict[str, Any], history: dict[str, Any] | None = None) -> set[str]:
    result: set[str] = set()
    for source in (queue, history or {}):
        for task in source.get("tasks") or []:
            if isinstance(task, dict) and str(task.get("id") or "").strip():
                result.add(str(task.get("id")).strip())
    return result


def existing_source_finding_ids(queue: dict[str, Any], history: dict[str, Any] | None = None) -> set[str]:
    result: set[str] = set()
    for source in (queue, history or {}):
        for task in source.get("tasks") or []:
            if isinstance(task, dict) and str(task.get("source_finding_id") or "").strip():
                result.add(str(task.get("source_finding_id")).strip())
    return result


def active_adl_worker_ready_exists(queue: dict[str, Any]) -> bool:
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or "")
        if (
            task_id.startswith("ADL-FOLLOWUP-")
            and task.get("worker_ready") is True
            and task.get("dispatcher_decision") == "worker_ready"
            and str(task.get("status") or "") in {"planned", "worker_ready", "needs_stronger_agent"}
        ):
            return True
    return False


def apply_rows(queue_path: Path, rows: list[dict[str, Any]], history_path: Path | None = None) -> dict[str, Any]:
    release_gate = validate_rows(rows)
    if not release_gate["ok"]:
        return {
            "ok": False,
            "queue": str(queue_path),
            "added": [],
            "skipped_existing": [],
            "release_gate": release_gate,
            "reason": "artifact_discovery_normalizer_release_gate_failed",
        }
    queue = load_json(queue_path)
    history = load_json(history_path) if history_path and history_path.exists() else None
    tasks = queue.setdefault("tasks", [])
    if not isinstance(tasks, list):
        raise ValueError("task_queue tasks must be an array")
    known = existing_task_ids(queue, history)
    known_findings = existing_source_finding_ids(queue, history)
    has_active_adl_worker_ready = active_adl_worker_ready_exists(queue)
    added: list[str] = []
    skipped: list[str] = []
    for row in rows:
        task_id = str(row.get("id") or "").strip()
        source_finding_id = str(row.get("source_finding_id") or "").strip()
        if row.get("worker_ready") is True and task_id.startswith("ADL-FOLLOWUP-") and has_active_adl_worker_ready:
            skipped.append(task_id)
            continue
        if not task_id or task_id in known or (source_finding_id and source_finding_id in known_findings):
            skipped.append(task_id)
            continue
        tasks.append(deepcopy(row))
        known.add(task_id)
        if row.get("worker_ready") is True and task_id.startswith("ADL-FOLLOWUP-"):
            has_active_adl_worker_ready = True
        if source_finding_id:
            known_findings.add(source_finding_id)
        added.append(task_id)
    if added:
        queue["updated_at"] = utc_now()
        write_json(queue_path, queue)
    return {"ok": True, "queue": str(queue_path), "added": added, "skipped_existing": skipped}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Routed Artifact Discovery JSON report.")
    parser.add_argument("--output", help="Write normalized follow-up rows JSON.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--queue", help="Task queue path. Defaults to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--history", help="Task history path used for duplicate detection.")
    parser.add_argument("--batch-id", default=today_id())
    parser.add_argument("--worker-ready-first-safe", action="store_true", help="Create one safe Worker Packet v2 row.")
    parser.add_argument("--apply", action="store_true", help="Append normalized rows to task_queue.json.")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    project_root = Path(args.project_root).resolve()
    routed_path = Path(args.input)
    if not routed_path.is_absolute():
        routed_path = (project_root / routed_path).resolve()
    try:
        routed_rel = routed_path.relative_to(project_root)
    except ValueError:
        routed_rel = routed_path
    normalized = normalize_report(
        load_json(routed_path),
        routed_path=routed_rel,
        batch_id=args.batch_id,
        worker_ready_first_safe=bool(args.worker_ready_first_safe),
    )
    if args.output:
        output = Path(args.output)
        if not output.is_absolute():
            output = project_root / output
        write_json(output, normalized)
    if args.apply:
        queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
        history_path = Path(args.history).resolve() if args.history else task_file(project_root, "task_history.json")
        normalized["apply_result"] = apply_rows(queue_path, normalized.get("rows") or [], history_path)
    if args.json or not args.output:
        print(json.dumps(normalized, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
