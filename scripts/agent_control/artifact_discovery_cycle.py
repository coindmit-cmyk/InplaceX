#!/usr/bin/env python3
"""Run the safe Artifact Discovery automation cycle.

This command keeps scanner/classifier/router/report generation read-only and
uses the normalizer for any queue-visible Task Manager rows.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path
from typing import Any

import artifact_discovery_classifier
import artifact_discovery_normalizer
import artifact_discovery_report_builder
import artifact_discovery_router
import artifact_discovery_scanner


def utc_stamp() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def batch_id() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d")


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run_cycle(
    *,
    project_root: Path,
    output_dir: Path,
    apply_normalized: bool,
    worker_ready_first_safe: bool,
    semantic_mode: str,
    local_llm_timeout: int,
) -> dict[str, Any]:
    stamp = utc_stamp()
    output_dir.mkdir(parents=True, exist_ok=True)
    scan_path = output_dir / f"ARTIFACT_DISCOVERY_CYCLE_{stamp}.scan.json"
    classified_path = output_dir / f"ARTIFACT_DISCOVERY_CYCLE_{stamp}.classified.json"
    routed_path = output_dir / f"ARTIFACT_DISCOVERY_CYCLE_{stamp}.routed.json"
    report_path = output_dir / f"ARTIFACT_DISCOVERY_CYCLE_{stamp}.md"
    normalized_path = output_dir / f"ARTIFACT_DISCOVERY_CYCLE_{stamp}.normalized.json"

    scan = artifact_discovery_scanner.scan(
        project_root,
        semantic_mode=semantic_mode,
        local_llm_timeout=local_llm_timeout,
    )
    write_json(scan_path, scan)
    classified = artifact_discovery_classifier.classify_report(scan)
    write_json(classified_path, classified)
    routed = artifact_discovery_router.route_report(classified, project_root=project_root)
    write_json(routed_path, routed)
    report_path.write_text(artifact_discovery_report_builder.markdown(routed), encoding="utf-8")
    try:
        routed_rel = routed_path.relative_to(project_root)
    except ValueError:
        routed_rel = routed_path
    normalized = artifact_discovery_normalizer.normalize_report(
        routed,
        routed_path=routed_rel,
        batch_id=batch_id(),
        worker_ready_first_safe=worker_ready_first_safe,
    )
    if apply_normalized:
        normalized["apply_result"] = artifact_discovery_normalizer.apply_rows(
            project_root / "AiStudio" / "Task_manager" / "task_queue.json",
            normalized.get("rows") or [],
            project_root / "AiStudio" / "Task_manager" / "task_history.json",
        )
    write_json(normalized_path, normalized)
    return {
        "ok": True,
        "project_root": str(project_root),
        "output_dir": str(output_dir),
        "artifacts": {
            "scan": str(scan_path),
            "classified": str(classified_path),
            "routed": str(routed_path),
            "markdown": str(report_path),
            "normalized": str(normalized_path),
        },
        "summary": {
            "inventory": scan.get("summary", {}).get("inventory_count", 0),
            "findings": routed.get("summary", {}).get("finding_count", 0),
            "blocking": routed.get("summary", {}).get("blocking_count", 0),
            "by_category": routed.get("summary", {}).get("by_category", {}),
            "by_severity": routed.get("summary", {}).get("by_severity", {}),
            "by_owner": routed.get("summary", {}).get("by_owner", {}),
            "by_disposition": routed.get("summary", {}).get("by_disposition", {}),
            "by_semantic_kind": routed.get("summary", {}).get("by_semantic_kind", {}),
            "by_implementation_status": routed.get("summary", {}).get("by_implementation_status", {}),
            "by_integration_status": routed.get("summary", {}).get("by_integration_status", {}),
            "resolution_counts": routed.get("summary", {}).get("resolution_counts", {}),
            "raw_task_candidates": routed.get("summary", {}).get("task_candidate_count", 0),
            "normalized_rows": normalized.get("summary", {}).get("normalized_rows", 0),
            "worker_ready_rows": normalized.get("summary", {}).get("worker_ready_rows", 0),
            "apply_normalized": bool(apply_normalized),
        },
        "apply_result": normalized.get("apply_result"),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--output-dir", default="docs/reports/discovery")
    parser.add_argument("--apply-normalized", action="store_true")
    parser.add_argument("--worker-ready-first-safe", action="store_true")
    parser.add_argument("--semantic-mode", choices=["deterministic", "local-llm"], default="deterministic")
    parser.add_argument("--local-llm-timeout", type=int, default=60)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    project_root = Path(args.project_root).resolve()
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = project_root / output_dir
    report = run_cycle(
        project_root=project_root,
        output_dir=output_dir,
        apply_normalized=bool(args.apply_normalized),
        worker_ready_first_safe=bool(args.worker_ready_first_safe),
        semantic_mode=args.semantic_mode,
        local_llm_timeout=args.local_llm_timeout,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else report["artifacts"]["markdown"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
