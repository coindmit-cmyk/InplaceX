#!/usr/bin/env python3
"""Run the staged legacy queue migration preparation pipeline."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import dispatcher_packet_repair
import normalize_task_packets
import workspace_dispatcher_repair_applier
import workspace_dispatcher_repair_planner
import workspace_legacy_queue_normalizer


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def issue_counts(queue: dict[str, Any]) -> dict[str, int]:
    issues = workspace_legacy_queue_normalizer.validation_issues(queue)
    return {
        "errors": sum(1 for item in issues if item["severity"] == "error"),
        "warnings": sum(1 for item in issues if item["severity"] == "warning"),
    }


def build_pipeline(
    queue_path: Path,
    output_dir: Path,
    prefix: str,
    *,
    apply_dispatcher_repairs: bool = False,
    apply_to_source: bool = False,
    locks_path: Path | None = None,
    context_refs: list[str] | None = None,
    verified_by: str = "dispatcher",
) -> dict[str, Any]:
    context_refs = context_refs or []
    output_dir.mkdir(parents=True, exist_ok=True)
    normalized_path = output_dir / f"{prefix}.normalized.json"
    packet_v2_path = output_dir / f"{prefix}.packet-v2.json"
    repair_plan_path = output_dir / f"{prefix}.dispatcher-repair-plan.json"
    dispatcher_repaired_path = output_dir / f"{prefix}.dispatcher-repaired.json"
    summary_path = output_dir / f"{prefix}.summary.json"

    source_before_counts = issue_counts(workspace_legacy_queue_normalizer.load_json(queue_path))
    normalization = workspace_legacy_queue_normalizer.build_report(queue_path, output_path=normalized_path)
    normalized_queue = workspace_legacy_queue_normalizer.load_json(normalized_path)
    locks = (
        workspace_legacy_queue_normalizer.load_json(locks_path)
        if locks_path is not None and locks_path.exists()
        else None
    )
    dependency_queue, dependency_actions = normalize_task_packets.reconcile_queue_dependencies(
        normalized_queue,
        normalize_task_packets.active_lock_task_ids(locks),
        normalize_task_packets.utc_now(),
    )
    if dependency_queue != normalized_queue:
        write_json_atomic(normalized_path, dependency_queue)
        normalized_queue = dependency_queue
    packet_v2_queue, packet_report = dispatcher_packet_repair.process_queue(normalized_queue)
    dispatcher_packet_repair.write_json(packet_v2_path, packet_v2_queue)
    repair_plan = workspace_dispatcher_repair_planner.build_plan(packet_v2_path)
    workspace_dispatcher_repair_planner.write_json_atomic(repair_plan_path, repair_plan)
    dispatcher_repair = None
    final_queue_path = packet_v2_path
    final_counts = issue_counts(packet_v2_queue)
    if apply_dispatcher_repairs and repair_plan["repair_count"]:
        dispatcher_repair = workspace_dispatcher_repair_applier.build_report(
            packet_v2_path,
            repair_plan_path,
            output_path=dispatcher_repaired_path,
            context_refs=context_refs,
            verified_by=verified_by,
        )
        final_queue_path = dispatcher_repaired_path
        final_counts = dispatcher_repair["after_validation"]

    source_apply = {
        "requested": apply_to_source,
        "applied": False,
        "reason": "not_requested" if not apply_to_source else "no_safe_changes",
    }
    changed = (
        int(normalization["change_count"] or 0) > 0
        or bool(dependency_actions)
        or int(packet_report["repaired_count"] or 0) > 0
        or int(packet_report["cleaned_count"] or 0) > 0
        or bool(dispatcher_repair and int(dispatcher_repair["packet_repair"]["repaired_count"] or 0) > 0)
    )
    if apply_to_source:
        if final_counts["errors"] > source_before_counts["errors"]:
            source_apply["reason"] = "validation_errors_would_increase"
        elif not changed:
            source_apply["reason"] = "no_changes"
        else:
            final_queue = workspace_legacy_queue_normalizer.load_json(final_queue_path)
            write_json_atomic(queue_path, final_queue)
            source_apply.update({
                "applied": True,
                "reason": "applied_final_queue_to_source",
                "final_queue": str(final_queue_path),
            })

    summary = {
        "schema_version": "1.0",
        "mode": "workspace_queue_migration_pipeline",
        "source_queue": str(queue_path),
        "mutates_source_queue": apply_to_source,
        "artifacts": {
            "normalized_queue": str(normalized_path),
            "packet_v2_queue": str(packet_v2_path),
            "dispatcher_repair_plan": str(repair_plan_path),
            "dispatcher_repaired_queue": str(dispatcher_repaired_path) if dispatcher_repair else None,
            "final_queue": str(final_queue_path),
            "summary": str(summary_path),
        },
        "normalization": {
            "change_count": normalization["change_count"],
            "before": normalization["before"],
            "after": normalization["after"],
        },
        "dependency_reconciliation": {
            "change_count": len(dependency_actions),
            "actions": dependency_actions,
            "locks": str(locks_path) if locks_path is not None else None,
        },
        "packet_repair": {
            "repaired_count": packet_report["repaired_count"],
            "cleaned_count": packet_report["cleaned_count"],
            "needs_dispatcher_repair_count": packet_report["needs_dispatcher_repair_count"],
            "skipped_count": packet_report["skipped_count"],
        },
        "dispatcher_repair_plan": {
            "repair_count": repair_plan["repair_count"],
            "action_counts": repair_plan["action_counts"],
        },
        "dispatcher_repair": dispatcher_repair,
        "final_validation": final_counts,
        "source_validation_before": source_before_counts,
        "source_apply": source_apply,
    }
    write_json_atomic(summary_path, summary)
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--prefix", default="task_queue")
    parser.add_argument("--apply-dispatcher-repairs", action="store_true")
    parser.add_argument("--apply-to-source", action="store_true", help="Atomically replace --queue with the final queue when validation does not regress.")
    parser.add_argument("--locks", type=Path, help="Canonical lock file used to preserve actively leased dependency rows.")
    parser.add_argument("--context-ref", action="append", default=[])
    parser.add_argument("--verified-by", default="dispatcher")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    summary = build_pipeline(
        args.queue.expanduser(),
        args.output_dir.expanduser(),
        args.prefix,
        apply_dispatcher_repairs=args.apply_dispatcher_repairs,
        apply_to_source=args.apply_to_source,
        locks_path=args.locks.expanduser() if args.locks else None,
        context_refs=args.context_ref,
        verified_by=args.verified_by,
    )
    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print(f"normalization_changes={summary['normalization']['change_count']}")
        print(f"packet_repaired={summary['packet_repair']['repaired_count']}")
        print(f"needs_dispatcher_repair={summary['packet_repair']['needs_dispatcher_repair_count']}")
        print(f"final_errors={summary['final_validation']['errors']}")
        print(f"final_warnings={summary['final_validation']['warnings']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
