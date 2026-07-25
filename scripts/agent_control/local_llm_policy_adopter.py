#!/usr/bin/env python3
"""Adopt proven local-LLM pre-worker policy without replacing project evidence."""

from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
from typing import Any

from project_paths import task_file


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return data if isinstance(data, dict) else {}


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def evidence_allows_adoption(evidence: dict[str, Any]) -> tuple[bool, list[str]]:
    holdout = evidence.get("holdout") if isinstance(evidence.get("holdout"), dict) else {}
    reasons: list[str] = []
    if evidence.get("status") != "holdout_100_of_100_passed":
        reasons.append("evidence status is not holdout_100_of_100_passed")
    if int(holdout.get("selected") or 0) < 100:
        reasons.append("holdout selected count is below 100")
    if int(holdout.get("passed") or 0) != int(holdout.get("selected") or 0):
        reasons.append("holdout is not 100 percent passed")
    if int(holdout.get("failed") or 0) != 0:
        reasons.append("holdout has failures")
    if int(holdout.get("unique_task_ids") or 0) != int(holdout.get("selected") or 0):
        reasons.append("holdout task ids are not unique")
    if int(holdout.get("unique_contract_fingerprints") or 0) != int(holdout.get("selected") or 0):
        reasons.append("holdout contract fingerprints are not unique")
    if holdout.get("queue_mutated") is not False:
        reasons.append("holdout did not prove queue immutability")
    if holdout.get("promotion_ready") is not True:
        reasons.append("holdout promotion_ready is not true")
    return not reasons, reasons


def merge_policy(existing: dict[str, Any], source: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    updated = deepcopy(existing) if existing else deepcopy(source)
    source_pre_worker = source.get("pre_worker") if isinstance(source.get("pre_worker"), dict) else {}
    if source_pre_worker.get("enabled") is not True:
        raise ValueError("source policy pre_worker.enabled must be true")
    kinds = [str(value) for value in source_pre_worker.get("task_kinds") or [] if str(value).strip()]
    if not kinds:
        raise ValueError("source policy pre_worker.task_kinds must not be empty")
    updated["schema_version"] = source.get("schema_version", updated.get("schema_version", 1))
    updated["pre_worker"] = deepcopy(source_pre_worker)
    updated_kinds = updated.setdefault("task_kinds", {})
    source_kinds = source.get("task_kinds") if isinstance(source.get("task_kinds"), dict) else {}
    adopted: list[str] = []
    for kind in kinds:
        source_kind = source_kinds.get(kind)
        if not isinstance(source_kind, dict):
            raise ValueError(f"source policy missing task kind {kind}")
        previous = updated_kinds.get(kind) if isinstance(updated_kinds.get(kind), dict) else {}
        previous_evidence = deepcopy(previous.get("evidence")) if isinstance(previous.get("evidence"), dict) else None
        merged_kind = deepcopy(source_kind)
        if previous_evidence is not None:
            merged_kind["evidence"] = previous_evidence
        updated_kinds[kind] = merged_kind
        adopted.append(kind)
    return updated, adopted


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--source-policy")
    parser.add_argument("--evidence")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    repository_root = Path(__file__).resolve().parents[2]
    source_path = Path(args.source_policy).resolve() if args.source_policy else repository_root / "templates" / "AiStudio" / "Task_manager" / "local_llm_dispatch_policy.json"
    evidence_path = Path(args.evidence).resolve() if args.evidence else repository_root / "docs" / "reports" / "integration" / "LOCAL_LLM_REMEDIATION_CANARY_20260712.json"
    target_path = task_file(project_root, "local_llm_dispatch_policy.json")
    evidence = load_json(evidence_path)
    allowed, blockers = evidence_allows_adoption(evidence)
    if not allowed:
        report = {
            "status": "blocked",
            "apply": args.apply,
            "project_root": str(project_root),
            "target_policy": str(target_path),
            "source_policy": str(source_path),
            "evidence": str(evidence_path),
            "blockers": blockers,
        }
        print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else "; ".join(blockers))
        return 2
    try:
        updated, adopted = merge_policy(load_json(target_path), load_json(source_path))
    except ValueError as exc:
        print(json.dumps({"status": "blocked", "blockers": [str(exc)]}, ensure_ascii=False, indent=2) if args.json else str(exc))
        return 2
    changed = updated != load_json(target_path)
    if args.apply and changed:
        write_json(target_path, updated)
    report = {
        "status": "adopted" if args.apply and changed else "already_current" if args.apply else "planned",
        "apply": args.apply,
        "changed": changed,
        "project_root": str(project_root),
        "target_policy": str(target_path),
        "source_policy": str(source_path),
        "evidence": str(evidence_path),
        "adopted_task_kinds": adopted,
        "preserved_project_evidence": True,
        "next_owner": "remote-automation-host" if args.apply else "agent-update-manager",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else report["status"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
