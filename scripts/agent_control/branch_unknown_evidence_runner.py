#!/usr/bin/env python3
"""Resolve one bounded unknown branch cohort with existing lifecycle tools."""

from __future__ import annotations

import argparse
import json
import platform
import re
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Any

from branch_recovery_remote_runner import exclusive_lock, load_json, write_json

try:
    import resource
except ImportError:  # pragma: no cover - Windows development host
    resource = None

TARGET_CLASSIFICATION = "unknown_needs_review"
TARGET_REASON = "merged branch historical value is unknown because changed-path evidence was not collected"
TERMINAL_CLASSES = {"merged_safe_delete", "archive_candidate", "cleanup_candidate"}


class RunnerError(RuntimeError):
    pass


def target_inventory(
    payload: dict[str, Any],
    *,
    list_key: str = "retained_ambiguous",
    classification: str = TARGET_CLASSIFICATION,
    reason: str = TARGET_REASON,
) -> dict[str, str]:
    rows = payload.get(list_key)
    if not isinstance(rows, list):
        raise RunnerError(f"inventory does not contain {list_key}")
    result: dict[str, str] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        if row.get("classification") != classification or row.get("reason") != reason:
            continue
        branch, sha = str(row.get("branch") or ""), str(row.get("sha") or "")
        if not branch or re.fullmatch(r"[0-9a-f]{40}", sha) is None:
            raise RunnerError("target inventory contains an invalid branch or SHA")
        if branch in result and result[branch] != sha:
            raise RunnerError(f"target inventory has conflicting SHAs for {branch}")
        result[branch] = sha
    return result


def run_json(command: list[str], *, cwd: Path, timeout: int) -> dict[str, Any]:
    proc = subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=timeout)
    try:
        payload = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RunnerError(f"{Path(command[0]).name} returned invalid JSON") from exc
    if proc.returncode != 0 or not payload.get("ok"):
        raise RunnerError(
            f"{Path(command[0]).name} failed: "
            f"{str(payload.get('error') or proc.stderr or 'unknown error')[:500]}"
        )
    return payload


def scan(args: argparse.Namespace) -> dict[str, Any]:
    output = args.evidence_root / "deep-scan.json"
    command = [
        sys.executable,
        str(args.source_root / "scripts/agent_control/branch_lifecycle_scanner.py"),
        "--project-root", str(args.source_root),
        "--task-project-root", str(args.source_root),
        "--stale-days", "0",
        "--protection-mode", args.protection_mode,
        "--max-task-count", "0",
        "--evidence-cache", str(args.evidence_cache),
        "--fetch",
        "--deep-metrics",
        "--output", str(output),
        "--json",
    ]
    for host in args.expected_codex_host:
        command += ["--expected-codex-host", host]
    if args.codex_activity_dir:
        command += ["--codex-activity-dir", str(args.codex_activity_dir)]
    run_json(command, cwd=args.source_root, timeout=args.command_timeout_seconds)
    payload = load_json(output)
    if not isinstance(payload, dict) or not payload.get("ok"):
        raise RunnerError("deep lifecycle scan evidence is missing")
    return payload


def retirement(
    args: argparse.Namespace,
    branches: list[str],
    batch_number: int,
    *,
    apply: bool,
) -> dict[str, Any]:
    command = [
        sys.executable,
        str(args.source_root / "scripts/agent_control/worktree_retirement.py"),
        "--project-root", str(args.source_root),
        "--base-ref", "origin/develop",
        "--base-ref", "origin/release/main",
        "--min-age-days", "0",
        "--protection-mode", args.protection_mode,
        "--archive-root", args.archive_root,
        "--max-count", str(len(branches)),
        "--delete-local-branch",
        "--delete-remote-branch",
        "--lifecycle-evidence", str(args.evidence_root / "deep-scan.json"),
        "--json",
    ]
    for branch in branches:
        command += ["--branch", branch]
    if apply:
        command += ["--apply"]
    proc = subprocess.run(
        command,
        cwd=args.source_root,
        text=True,
        capture_output=True,
        timeout=args.command_timeout_seconds,
    )
    try:
        payload = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RunnerError(f"retirement batch {batch_number} returned invalid JSON") from exc
    suffix = "apply" if apply else "plan"
    write_json(args.evidence_root / f"batch-{batch_number:03d}-{suffix}.json", payload)
    if not apply and (proc.returncode != 0 or not payload.get("ok")):
        raise RunnerError(f"retirement batch {batch_number} plan failed")
    return payload


def execute(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    usage_before = resource.getrusage(resource.RUSAGE_SELF) if resource else None
    inventory = load_json(args.inventory)
    if not isinstance(inventory, dict):
        raise RunnerError("source reconciliation inventory is missing")
    targets = target_inventory(
        inventory,
        list_key=args.inventory_list_key,
        classification=args.target_classification,
        reason=args.target_reason,
    )
    if not targets:
        raise RunnerError("source inventory contains no matching cohort")
    if args.expected_target_count is not None and len(targets) != args.expected_target_count:
        raise RunnerError(
            f"target inventory count mismatch: expected {args.expected_target_count}, got {len(targets)}"
        )
    state_path = args.evidence_root / "state.json"
    state = load_json(state_path, {"schema_version": "1.0", "completed": {}})
    completed = state.get("completed")
    if not isinstance(completed, dict):
        raise RunnerError("invalid continuation state")
    pending = sorted(branch for branch, sha in targets.items() if completed.get(branch) != sha)
    selected = pending[: args.max_branches]

    scan_payload = scan(args)
    decisions = {
        str(row.get("branch") or ""): row
        for row in (scan_payload.get("scanner") or {}).get("decisions") or []
        if isinstance(row, dict)
    }
    terminal: list[str] = []
    retained: list[dict[str, str]] = []
    for branch in selected:
        row = decisions.get(branch)
        if not row or row.get("sha") != targets[branch]:
            raise RunnerError(f"deep evidence missing or SHA changed for {branch}")
        classification = str(row.get("source_classification") or "")
        if classification in TERMINAL_CLASSES:
            terminal.append(branch)
        else:
            retained.append({
                "branch": branch,
                "sha": targets[branch],
                "classification": classification,
                "reason": str(row.get("reason") or "deep evidence remains nonterminal"),
            })

    batches = [
        terminal[offset : offset + args.batch_size]
        for offset in range(0, len(terminal), args.batch_size)
    ]
    retired = 0
    operations = {"deep_scans": 1, "retirement_plans": 0, "retirement_applies": 0}
    for number, branches in enumerate(batches, start=1):
        plan = retirement(args, branches, number, apply=False)
        operations["retirement_plans"] += 1
        if int((plan.get("plan") or {}).get("eligible_count") or 0) != len(branches):
            raise RunnerError(f"retirement batch {number} is not fully eligible")
        if not args.apply:
            continue
        result = retirement(args, branches, number, apply=True)
        operations["retirement_applies"] += 1
        results = result.get("results") or []
        for row in results:
            if row.get("status") == "retired":
                branch = str(row.get("branch") or "")
                if row.get("sha") != targets.get(branch) or (row.get("archive") or {}).get("verified") is not True:
                    raise RunnerError(f"retirement evidence mismatch for {branch}")
                completed[branch] = targets[branch]
                retired += 1
        state["completed"] = completed
        write_json(state_path, state)
        if len(results) != len(branches) or any(row.get("status") != "retired" for row in results):
            raise RunnerError(f"retirement batch {number} did not complete")

    usage_after = resource.getrusage(resource.RUSAGE_SELF) if resource else None
    cpu_seconds = None
    io_blocks = None
    if usage_before is not None and usage_after is not None:
        cpu_seconds = round(
            (usage_after.ru_utime + usage_after.ru_stime)
            - (usage_before.ru_utime + usage_before.ru_stime),
            3,
        )
        io_blocks = {
            "read": max(0, usage_after.ru_inblock - usage_before.ru_inblock),
            "write": max(0, usage_after.ru_oublock - usage_before.ru_oublock),
        }
    report = {
        "schema_version": "1.0",
        "status": "completed",
        "mode": "apply" if args.apply else "dry_run",
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "cpu_seconds": cpu_seconds,
        "io_blocks": io_blocks,
        "operations": operations,
        "target_total": len(targets),
        "target_classification": args.target_classification,
        "target_reason": args.target_reason,
        "inventory_list_key": args.inventory_list_key,
        "protection_mode": args.protection_mode,
        "selected": len(selected),
        "terminal_by_classification": dict(Counter(
            str(decisions[name].get("source_classification") or "") for name in terminal
        )),
        "retained": len(retained),
        "retired_this_run": retired,
        "resolved_total": len(completed),
        "deferred": max(0, len(targets) - len(completed) - len(retained)),
        "batch_size": args.batch_size,
        "batch_count": len(batches),
        "model_tokens": 0,
        "platform": platform.system().lower(),
        "retained_evidence": str(args.evidence_root / "retained.json"),
    }
    write_json(args.evidence_root / "retained.json", retained)
    write_json(args.evidence_root / "resource-report.json", report)
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--evidence-cache", type=Path, required=True)
    parser.add_argument("--inventory-list-key", default="retained_ambiguous")
    parser.add_argument("--target-classification", default=TARGET_CLASSIFICATION)
    parser.add_argument("--target-reason", default=TARGET_REASON)
    parser.add_argument("--expected-target-count", type=int)
    parser.add_argument(
        "--protection-mode",
        choices=("default", "canonical-only"),
        default="default",
    )
    parser.add_argument("--archive-root", default="/srv/aistudio-hdd/AiStudioData/archive/git-branches")
    parser.add_argument("--codex-activity-dir", type=Path)
    parser.add_argument("--expected-codex-host", action="append", default=[])
    parser.add_argument("--batch-size", type=int, default=25)
    parser.add_argument("--max-branches", type=int, default=50)
    parser.add_argument("--command-timeout-seconds", type=int, default=900)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--yes", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if args.apply and not args.yes:
        parser.error("--apply requires --yes")
    if args.batch_size < 1 or args.max_branches < 1 or args.batch_size > args.max_branches:
        parser.error("invalid batch/max branch bounds")
    if args.expected_target_count is not None and args.expected_target_count < 1:
        parser.error("--expected-target-count must be positive")
    return args


def main() -> int:
    args = parse_args()
    args.source_root = args.source_root.resolve()
    args.inventory = args.inventory.resolve()
    args.evidence_root = args.evidence_root.resolve()
    args.evidence_cache = args.evidence_cache.resolve()
    args.evidence_root.mkdir(parents=True, exist_ok=True)
    try:
        with exclusive_lock(args.evidence_root / "runner.lock"):
            payload = execute(args)
    except (OSError, subprocess.SubprocessError, RunnerError) as exc:
        payload = {"schema_version": "1.0", "status": "failed", "error": str(exc), "model_tokens": 0}
        write_json(args.evidence_root / "failure-report.json", payload)
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if args.json else json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if payload.get("status") == "completed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
