#!/usr/bin/env python3
"""Classify old integration candidates for safe salvage routing."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_manager_dir


TASK_TOKEN_RE = re.compile(
    r"\b(?:AUTO|BUY|CIE|COM|COM-CIE|COM-CX|COM-IHB|COM-MPI|COM-WHE|COM-XQA|CX|CXE|DEL|DELQ|DIRQ|FAM|FRI|IHB|MPI|MVP|MVPQ|NW|P0|P1|PAY|PROD|R4S|WHE|XCOM)-\d+(?:\.\d+)*\b",
    re.IGNORECASE,
)
SERVICE_TOKEN_RE = re.compile(r"\b(?:WORKER|5|53|55|5\.3|5\.5|MAX|MINI|P0|P1)-?\d*\b", re.IGNORECASE)

COORDINATION_PREFIXES = (
    ".agent/",
    "AiStudio/Task_manager/",
    "docs/agent-updates/",
    "docs/automation/",
    "docs/plans/",
    "docs/reports/",
    "old/agent-runs/",
    "schemas/agent-control/",
    "scripts/agent_control/",
)
COORDINATION_EXACT = {
    "AGENTS.md",
    "CHANGELOG.md",
    "README.md",
}
NOISY_PREFIXES = (
    ".agent/upstream/",
    "AiStudio/Agent/",
    ".worktrees/",
    "agent-worktrees/",
    "old/",
)
PRODUCT_DOC_PREFIXES = (
    "docs/",
)
PRODUCT_DOC_ALLOWLIST = (
    "docs/commerce_operating_model.md",
    "docs/plans/tasks/",
)
MAX_CLEAN_PATHS = 25


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_branch(value: Any) -> str:
    text = str(value or "").strip()
    for prefix in ("refs/remotes/origin/", "refs/heads/", "origin/"):
        if text.startswith(prefix):
            return text.removeprefix(prefix)
    return text


def normalize_path(path: Any) -> str:
    return str(path or "").replace("\\", "/").strip()


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def queue_task_ids(queue: dict[str, Any]) -> set[str]:
    return {
        task_id(task).upper()
        for task in queue.get("tasks") or []
        if isinstance(task, dict) and task_id(task)
    }


def candidate_index(report: dict[str, Any], key: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for item in report.get(key) or report.get("items") or []:
        if not isinstance(item, dict):
            continue
        branch = normalize_branch(item.get("branch") or item.get("normalized_branch"))
        if branch:
            result[branch] = item
    return result


def git_name_only(project_root: Path, left: str, right: str) -> list[str]:
    if not left or not right:
        return []
    try:
        result = subprocess.run(
            ["git", "-C", str(project_root), "diff", "--name-only", left, right],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return []
    return [normalize_path(path) for path in result.stdout.splitlines() if normalize_path(path)]


def audit_index(audit: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for item in audit.get("items") or []:
        if not isinstance(item, dict) or item.get("kind") != "integration_candidate":
            continue
        branch = normalize_branch(item.get("branch"))
        if branch:
            result[branch] = item
    return result


def changed_paths(candidate: dict[str, Any]) -> list[str]:
    paths = candidate.get("integration_changed_paths")
    if not isinstance(paths, list) or not paths:
        paths = candidate.get("changed_paths")
    return [normalize_path(path) for path in paths or [] if normalize_path(path)]


def is_noisy_path(path: str) -> bool:
    return path.startswith(NOISY_PREFIXES)


def is_coordination_path(path: str) -> bool:
    if path in COORDINATION_EXACT:
        return True
    return path.startswith(COORDINATION_PREFIXES)


def is_product_doc(path: str) -> bool:
    return path.startswith(PRODUCT_DOC_ALLOWLIST) or (
        path.startswith(PRODUCT_DOC_PREFIXES) and not is_coordination_path(path)
    )


def product_paths(paths: list[str]) -> list[str]:
    return [
        path
        for path in paths
        if not is_noisy_path(path) and (not is_coordination_path(path) or is_product_doc(path))
    ]


def noisy_paths(paths: list[str]) -> list[str]:
    return [path for path in paths if is_noisy_path(path)]


def infer_task_tokens(branch: str, paths: list[str], known_ids: set[str]) -> list[str]:
    haystack = " ".join([branch, *paths]).upper().replace("_", "-")
    tokens = {match.group(0).upper() for match in TASK_TOKEN_RE.finditer(haystack)}
    tokens = {token for token in tokens if not SERVICE_TOKEN_RE.fullmatch(token)}
    known = sorted(tokens & known_ids)
    if known:
        return known
    return sorted(tokens)


def sample(values: list[str], limit: int = 12) -> list[str]:
    return values[:limit]


def classify_candidate(
    project_root: Path,
    candidate: dict[str, Any],
    readiness_item: dict[str, Any] | None,
    audit_item: dict[str, Any] | None,
    known_ids: set[str],
    base_ref: str,
) -> dict[str, Any]:
    branch = normalize_branch(candidate.get("branch") or candidate.get("normalized_branch"))
    ahead_paths = git_name_only(project_root, str(candidate.get("merge_base_sha") or ""), str(candidate.get("head_sha") or ""))
    current_base_paths = git_name_only(project_root, base_ref, str(candidate.get("head_sha") or ""))
    paths = ahead_paths or changed_paths(candidate)
    product = product_paths(paths)
    current_product = product_paths(current_base_paths)
    noisy = noisy_paths(paths)
    explicit_ids = [str(value).upper() for value in candidate.get("task_ids") or [] if value]
    readiness_ids = [str(value).upper() for value in (readiness_item or {}).get("task_ids") or [] if value]
    audit_ids = [str(value).upper() for value in (audit_item or {}).get("inferred_task_ids") or [] if value]
    inferred = sorted(set(explicit_ids + readiness_ids + audit_ids + infer_task_tokens(branch, product or paths, known_ids)))
    known_inferred = [value for value in inferred if value in known_ids]
    source_classification = str((readiness_item or {}).get("classification") or "")
    authoritative_coordination_only = (
        source_classification == "coordination_only"
        and bool((readiness_item or {}).get("identity_valid"))
        and not changed_paths(readiness_item or {})
    )
    if authoritative_coordination_only and readiness_ids:
        inferred = sorted(set(readiness_ids))
        known_inferred = [value for value in inferred if value in known_ids]

    issues: list[str] = []
    if noisy:
        issues.append("branch contains nested/upstream/worktree payload")
    if candidate.get("path_list_truncated"):
        issues.append("changed path list is truncated")
    if len(product) > MAX_CLEAN_PATHS:
        issues.append("too many product paths for direct salvage")

    behind_base = int(candidate.get("behind_base") or 0)

    if authoritative_coordination_only:
        classification = "cleanup_candidate"
        next_owner = "cleanup"
        reason = "identity-valid readiness classified the branch as coordination-only"
        product = []
        current_product = []
    elif not product:
        classification = "cleanup_candidate"
        next_owner = "cleanup"
        reason = "no product payload after filtering coordination/noisy paths"
    elif ahead_paths and not current_product:
        classification = "cleanup_candidate"
        next_owner = "cleanup"
        reason = "product payload is already present in current base"
    elif behind_base > 0:
        classification = "needs_clean_rebuild"
        next_owner = "auto-dispatcher"
        reason = "branch is behind current base; requires rebase or clean package rebuild before merge"
    elif issues:
        classification = "needs_clean_rebuild"
        next_owner = "auto-dispatcher"
        reason = "; ".join(issues)
    elif len(known_inferred) == 1 and readiness_item and readiness_item.get("identity_valid"):
        classification = "salvage_ready"
        next_owner = "pre_integrator"
        reason = "single identity-valid candidate with product payload"
    elif len(known_inferred) == 1:
        classification = "needs_worker_fix"
        next_owner = "worker"
        reason = "product payload exists but worker report or identity evidence is incomplete"
    elif len(inferred) == 1:
        classification = "needs_task_packet_for_existing_diff"
        next_owner = "auto-dispatcher"
        reason = "product payload has a recognizable task token but no queue packet"
    elif len(inferred) > 1:
        classification = "needs_dispatcher"
        next_owner = "auto-dispatcher"
        reason = "product payload maps to multiple task-like tokens"
    else:
        classification = "needs_human"
        next_owner = "human"
        reason = "product payload has no reliable task identity"

    return {
        "branch": candidate.get("branch"),
        "normalized_branch": branch,
        "classification": classification,
        "next_owner": next_owner,
        "reason": reason,
        "task_ids": known_inferred or inferred,
        "known_task_ids": known_inferred,
        "canonical_target_id": f"task:{known_inferred[0]}" if len(known_inferred) == 1 else None,
        "risk_class": "medium" if product else "low",
        "changed_paths": product,
        "ahead_changed_paths_sample": sample(ahead_paths),
        "current_base_changed_paths_sample": sample(current_base_paths),
        "product_path_count": len(product),
        "current_product_path_count": len(current_product),
        "coordination_or_noise_path_count": max(0, len(paths) - len(product)),
        "noisy_path_count": len(noisy),
        "product_paths_sample": sample(product),
        "noisy_paths_sample": sample(noisy),
        "source_classification": source_classification,
        "identity_valid": bool((readiness_item or {}).get("identity_valid")),
        "identity_issues": (readiness_item or {}).get("identity_issues") or (audit_item or {}).get("issues") or [],
        "head_sha": candidate.get("head_sha"),
        "ahead_of_base": candidate.get("ahead_of_base"),
        "behind_base": candidate.get("behind_base"),
        "path_source": "merge_base_to_head" if ahead_paths else "base_to_head_snapshot",
    }


def build_report(project_root: Path, preflight: dict[str, Any], readiness: dict[str, Any], audit: dict[str, Any], queue: dict[str, Any]) -> dict[str, Any]:
    readiness_by_branch = candidate_index(readiness, "items")
    audit_by_branch = audit_index(audit)
    known_ids = queue_task_ids(queue)
    base_ref = str(preflight.get("base_branch") or "origin/develop")
    candidates = [item for item in preflight.get("candidates") or [] if isinstance(item, dict)]
    items = [
        classify_candidate(
            project_root,
            candidate,
            readiness_by_branch.get(normalize_branch(candidate.get("branch"))),
            audit_by_branch.get(normalize_branch(candidate.get("branch"))),
            known_ids,
            base_ref,
        )
        for candidate in candidates
    ]
    counts = Counter(str(item["classification"]) for item in items)
    owner_counts = Counter(str(item["next_owner"]) for item in items)
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "source": "candidate_salvage_audit.py",
        "policy": "old candidates are classified before Integrator; no product code mutation",
        "candidate_count": len(items),
        "counts": dict(counts),
        "next_owner_counts": dict(owner_counts),
        "items": items,
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Candidate Salvage Audit",
        "",
        f"- Generated: `{report.get('created_at')}`",
        f"- Candidates: `{report.get('candidate_count')}`",
        f"- Counts: `{json.dumps(report.get('counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        f"- Next owners: `{json.dumps(report.get('next_owner_counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        "",
        "| Classification | Owner | Branch | Tasks | Product paths | Reason |",
        "| --- | --- | --- | --- | ---: | --- |",
    ]
    for item in report.get("items") or []:
        tasks = ", ".join(item.get("task_ids") or []) or "-"
        reason = str(item.get("reason") or "").replace("|", "/")
        lines.append(
            f"| `{item.get('classification')}` | `{item.get('next_owner')}` | "
            f"`{item.get('branch')}` | `{tasks}` | {item.get('product_path_count') or 0} | {reason} |"
        )
    lines.append("")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--preflight")
    parser.add_argument("--readiness")
    parser.add_argument("--audit")
    parser.add_argument("--queue")
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    preflight_path = Path(args.preflight).resolve() if args.preflight else plans / "integrator_preflight.json"
    readiness_path = Path(args.readiness).resolve() if args.readiness else plans / "pr_readiness_report.identity_filtered.json"
    audit_path = Path(args.audit).resolve() if args.audit else plans / "task_identity_audit.json"
    queue_path = Path(args.queue).resolve() if args.queue else plans / "task_queue.json"
    output_path = Path(args.output).resolve() if args.output else plans / "candidate_salvage_audit.json"
    report_path = Path(args.report).resolve() if args.report else plans / "reports" / f"CANDIDATE_SALVAGE_AUDIT_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"

    report = build_report(
        project_root,
        load_json(preflight_path),
        load_json(readiness_path),
        load_json(audit_path),
        load_json(queue_path),
    )
    write_json(output_path, report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(report), encoding="utf-8")
    append_log(project_root, "pre-integrator", "candidate_salvage_audit", severity="info", counts=report["counts"])

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"candidates: {report['candidate_count']}")
        print(f"counts: {report['counts']}")
        print(f"written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
