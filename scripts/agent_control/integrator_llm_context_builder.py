#!/usr/bin/env python3
"""Build a compact redacted context for advisory-only Integrator LLM help."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_file


SECRET_PATTERNS = (
    re.compile(r"(?i)(api[_-]?key|access[_-]?token|auth[_-]?token|token|password|passwd|secret)\s*[:=]\s*[^,\s\"']+"),
    re.compile(r"(?i)(bearer|token)\s+[a-z0-9._\-]{16,}"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.DOTALL),
)
SENSITIVE_KEYS = {
    "api_key",
    "apikey",
    "access_token",
    "auth_token",
    "authorization",
    "credential",
    "password",
    "secret",
    "token",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path | None) -> Any:
    if not path or not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def stable_hash(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def redacted_value(value: Any, stats: Counter[str]) -> Any:
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, child in value.items():
            normalized = str(key).lower().replace("-", "_")
            if normalized in SENSITIVE_KEYS or normalized.endswith("_token") or normalized.endswith("_secret"):
                stats["sensitive_keys"] += 1
                result[key] = "[REDACTED]"
            else:
                result[key] = redacted_value(child, stats)
        return result
    if isinstance(value, list):
        return [redacted_value(item, stats) for item in value]
    if isinstance(value, str):
        text = value
        for pattern in SECRET_PATTERNS:
            text, count = pattern.subn("[REDACTED]", text)
            stats["pattern_matches"] += count
        return text
    return value


def compact_paths(paths: Any, limit: int) -> list[str]:
    if not isinstance(paths, list):
        return []
    return [str(path) for path in paths[:limit] if path]


def compact_candidate(candidate: dict[str, Any], max_paths: int) -> dict[str, Any]:
    return {
        "branch": candidate.get("branch"),
        "normalized_branch": candidate.get("normalized_branch"),
        "pr": candidate.get("pr"),
        "head_sha": candidate.get("head_sha"),
        "task_ids": [str(item) for item in candidate.get("task_ids") or [] if item],
        "classification": candidate.get("classification"),
        "risk_class": candidate.get("risk_class"),
        "reason": candidate.get("reason"),
        "next_owner": candidate.get("next_owner"),
        "ahead_of_base": candidate.get("ahead_of_base"),
        "behind_base": candidate.get("behind_base"),
        "check_state": candidate.get("check_state"),
        "changed_paths": compact_paths(candidate.get("changed_paths") or candidate.get("integration_changed_paths"), max_paths),
        "coordination_changed_paths": compact_paths(candidate.get("coordination_changed_paths"), max_paths),
        "evidence": compact_paths(candidate.get("evidence"), 10),
        "warnings": compact_paths(candidate.get("warnings"), 10),
    }


def compact_batch_item(item: dict[str, Any], max_paths: int) -> dict[str, Any]:
    return {
        "branch": item.get("branch"),
        "task_ids": [str(value) for value in item.get("task_ids") or [] if value],
        "classification": item.get("classification"),
        "risk_class": item.get("risk_class"),
        "route": item.get("route"),
        "reason": item.get("reason"),
        "next_owner": item.get("next_owner"),
        "changed_paths": compact_paths(item.get("changed_paths"), max_paths),
        "migration_sensitive": item.get("migration_sensitive"),
        "migration_compatibility_policy": item.get("migration_compatibility_policy"),
        "integrator_must_adapt_migrations": item.get("integrator_must_adapt_migrations"),
    }


def load_default(path: Path, explicit: str | None, default_name: str) -> Any:
    candidate = Path(explicit).resolve() if explicit else task_file(path, default_name)
    return load_json(candidate)


def build_context(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve()
    preflight = load_default(project_root, args.preflight, "integrator_preflight.json") or {}
    readiness = load_default(project_root, args.readiness, "pr_readiness_report.json") or {}
    batch = load_default(project_root, args.batch, "integration_batch.json") or {}
    repair = load_default(project_root, args.pre_integrator_repair, "pre_integrator_repair.json") or {}
    handoff_validation = load_json(Path(args.handoff_validation).resolve()) if args.handoff_validation else None

    readiness_items = [item for item in readiness.get("items") or [] if isinstance(item, dict)]
    preflight_candidates = [item for item in preflight.get("candidates") or [] if isinstance(item, dict)]
    source_candidates = readiness_items or preflight_candidates
    candidates = [compact_candidate(item, args.max_paths) for item in source_candidates[: args.max_candidates]]

    included = [compact_batch_item(item, args.max_paths) for item in (batch.get("included") or [])[: args.max_batch_items] if isinstance(item, dict)]
    excluded = [compact_batch_item(item, args.max_paths) for item in (batch.get("excluded") or [])[: args.max_batch_items] if isinstance(item, dict)]

    route_counts = Counter(str(item.get("route") or item.get("classification") or "unknown") for item in batch.get("excluded") or [] if isinstance(item, dict))
    classification_counts = readiness.get("counts") or {}

    context: dict[str, Any] = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project": args.project or project_root.name,
        "project_root_name": project_root.name,
        "base_branch": args.base or preflight.get("base_branch") or readiness.get("base_branch") or "develop",
        "base_sha": preflight.get("base_sha") or readiness.get("base_sha"),
        "source_files": {
            "preflight": "AiStudio/Task_manager/integrator_preflight.json",
            "readiness": "AiStudio/Task_manager/pr_readiness_report.json",
            "integration_batch": "AiStudio/Task_manager/integration_batch.json",
            "pre_integrator_repair": "AiStudio/Task_manager/pre_integrator_repair.json",
        },
        "summary": {
            "preflight_candidate_count": repair.get("preflight_candidate_count") or preflight.get("candidate_count") or len(preflight_candidates),
            "readiness_counts": classification_counts,
            "batch_included_count": batch.get("included_count", len(batch.get("included") or [])),
            "batch_excluded_count": batch.get("excluded_count", len(batch.get("excluded") or [])),
            "excluded_route_counts": dict(route_counts),
            "handoff_ready": repair.get("handoff_ready"),
            "dirty_worktree": preflight.get("dirty_worktree", False),
        },
        "candidates": candidates,
        "batch": {
            "batch_id": batch.get("batch_id"),
            "included": included,
            "excluded": excluded,
        },
        "path_conflicts": [item for item in (preflight.get("path_conflicts") or [])[: args.max_conflicts] if isinstance(item, dict)],
        "blockers": [item for item in (preflight.get("blockers") or [])[: args.max_blockers] if isinstance(item, dict)],
        "handoff_validation": handoff_validation,
        "advisory_questions": [
            "Explain the most important blockers and their next owners.",
            "Suggest safe small batch groups only when supported by context.",
            "Explain why excluded items should not enter the current finalizer package.",
            "Explain handoff validator warnings without proposing direct mutation.",
        ],
        "advisory_only_boundary": {
            "llm_may": [
                "explain blockers",
                "explain path conflicts",
                "suggest safe batch groups",
                "draft rejection_detail text",
                "explain validator warnings",
            ],
            "llm_must_not": [
                "merge",
                "push",
                "delete branch",
                "close PR",
                "release lock",
                "mark task done",
                "edit queue",
                "edit locks",
                "edit events",
                "edit handoff",
            ],
        },
    }
    redaction_stats: Counter[str] = Counter()
    redacted = redacted_value(context, redaction_stats)
    redacted["source_context_hash"] = stable_hash(redacted)
    redacted["redaction"] = {
        "redaction_count": int(sum(redaction_stats.values())),
        "stats": dict(redaction_stats),
        "full_file_contents_included": False,
        "full_diffs_included": False,
    }
    return redacted


def main() -> int:
    parser = argparse.ArgumentParser(description="Build redacted compact context for Integrator local LLM advice.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--output", help="Defaults to <project-root>/AiStudio/Task_manager/integrator_llm_context.json")
    parser.add_argument("--preflight")
    parser.add_argument("--readiness")
    parser.add_argument("--batch")
    parser.add_argument("--pre-integrator-repair")
    parser.add_argument("--handoff-validation")
    parser.add_argument("--project")
    parser.add_argument("--base")
    parser.add_argument("--max-candidates", type=int, default=80)
    parser.add_argument("--max-batch-items", type=int, default=80)
    parser.add_argument("--max-paths", type=int, default=20)
    parser.add_argument("--max-conflicts", type=int, default=40)
    parser.add_argument("--max-blockers", type=int, default=40)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    context = build_context(args)
    output = Path(args.output).resolve() if args.output else task_file(project_root, "integrator_llm_context.json")
    write_json(output, context)
    if args.json:
        print(json.dumps(context, ensure_ascii=False, indent=2))
    else:
        print(f"candidates: {len(context['candidates'])}")
        print(f"batch_included: {context['summary']['batch_included_count']}")
        print(f"batch_excluded: {context['summary']['batch_excluded_count']}")
        print(f"context_hash: {context['source_context_hash']}")
        print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
