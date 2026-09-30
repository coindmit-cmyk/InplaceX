#!/usr/bin/env python3
"""Capture exact recovery refs in durable ledgers before bounded retirement."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from branch_recovery_remote_runner import exclusive_lock, load_json, write_json
import worktree_retirement

try:
    import resource
except ImportError:  # pragma: no cover - Windows development host
    resource = None

TARGET_CLASSES = {"dirty_worker_candidate", "integration_recovery_candidate"}
TERMINAL_CLASSES = {"merged_safe_delete", "archive_candidate", "cleanup_candidate"}
DEFAULT_BASE_BRANCH = "develop"
DEFAULT_RELEASE_BRANCH = "release/main"


class RunnerError(RuntimeError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def recovery_inventory(payload: dict[str, Any], list_key: str) -> dict[str, dict[str, str]]:
    rows = payload.get(list_key)
    if not isinstance(rows, list):
        raise RunnerError(f"inventory does not contain {list_key}")
    result: dict[str, dict[str, str]] = {}
    for row in rows:
        if not isinstance(row, dict) or row.get("classification") not in TARGET_CLASSES:
            continue
        branch = str(row.get("branch") or "")
        sha = str(row.get("sha") or "")
        reason = str(row.get("reason") or "")
        classification = str(row.get("classification") or "")
        if not branch or not reason or len(sha) != 40 or any(ch not in "0123456789abcdef" for ch in sha):
            raise RunnerError("recovery inventory contains an invalid exact row")
        current = result.get(branch)
        exact = {"branch": branch, "sha": sha, "classification": classification, "reason": reason}
        if current and current != exact:
            raise RunnerError(f"recovery inventory conflicts for {branch}")
        result[branch] = exact
    return result


def run_scanner(args: argparse.Namespace, output_name: str = "deep-scan.json") -> dict[str, Any]:
    output = args.evidence_root / output_name
    command = [
        sys.executable,
        str(args.tool_root / "scripts/agent_control/branch_lifecycle_scanner.py"),
        "--project-root", str(args.source_root),
        "--task-project-root", str(args.source_root),
        "--base", args.base_branch,
        "--release-base", args.release_branch,
        "--stale-days", "0",
        "--protection-mode", "canonical-only",
        "--max-task-count", "0",
        "--evidence-cache", str(args.evidence_cache),
        "--fetch",
        "--deep-metrics",
        "--include-branch-report",
        "--output", str(output),
        "--json",
    ]
    proc = subprocess.run(
        command,
        cwd=args.source_root,
        text=True,
        capture_output=True,
        timeout=args.command_timeout_seconds,
    )
    try:
        summary = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RunnerError("lifecycle scanner returned invalid JSON") from exc
    payload = load_json(output)
    if proc.returncode != 0 or not summary.get("ok") or not isinstance(payload, dict) or not payload.get("ok"):
        raise RunnerError("deep lifecycle scan failed")
    return payload


def logical_branch_rows(report: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    rows = report.get("branches") if isinstance(report, dict) else None
    for row in rows or []:
        if not isinstance(row, dict) or not row.get("name"):
            continue
        name = str(row["name"])
        if name not in result or row.get("ref_kind") == "remote":
            result[name] = row
    return result


def persistent_branches(args: argparse.Namespace) -> set[str]:
    base_branch = str(getattr(args, "base_branch", DEFAULT_BASE_BRANCH))
    release_branch = str(getattr(args, "release_branch", DEFAULT_RELEASE_BRANCH))
    return {
        str(branch)
        for branch in [
            base_branch,
            release_branch,
            *(getattr(args, "persistent_branch", []) or []),
        ]
        if branch
    }


def prevention_groups(
    rows: dict[str, dict[str, Any]],
    persistent: set[str] | None = None,
) -> dict[str, list[dict[str, Any]]]:
    persistent = persistent or {DEFAULT_BASE_BRANCH, DEFAULT_RELEASE_BRANCH}
    groups: dict[str, list[dict[str, Any]]] = {
        "persistent": [],
        "active": [],
        "terminal": [],
        "recovery": [],
        "retained": [],
    }
    for branch in sorted(rows):
        row = rows[branch]
        classification = str(row.get("classification") or "unknown_needs_review")
        if branch in persistent:
            groups["persistent"].append(row)
        elif classification == "keep_active":
            groups["active"].append(row)
        elif classification in TERMINAL_CLASSES:
            groups["terminal"].append(row)
        elif classification in TARGET_CLASSES:
            groups["recovery"].append(row)
        else:
            groups["retained"].append(row)
    return groups


def exact_row(row: dict[str, Any]) -> dict[str, str]:
    return {
        "branch": str(row.get("name") or row.get("branch") or ""),
        "sha": str(row.get("sha") or ""),
        "classification": str(row.get("classification") or ""),
        "reason": str(row.get("reason") or ""),
    }


def validate_exact_row(row: dict[str, str]) -> None:
    if (
        not row["branch"]
        or len(row["sha"]) != 40
        or any(ch not in "0123456789abcdef" for ch in row["sha"])
        or not row["classification"]
        or not row["reason"]
    ):
        raise RunnerError("prevention scan contains an incomplete exact row")


def live_remote_heads(args: argparse.Namespace) -> dict[str, str]:
    proc = subprocess.run(
        ["git", "ls-remote", "--heads", "origin"],
        cwd=args.source_root,
        text=True,
        capture_output=True,
        timeout=min(args.command_timeout_seconds, max(1, args.max_wall_seconds)),
    )
    if proc.returncode != 0:
        raise RunnerError("live remote head reconciliation failed")
    result: dict[str, str] = {}
    for line in proc.stdout.splitlines():
        fields = line.split()
        if len(fields) != 2 or not fields[1].startswith("refs/heads/"):
            continue
        branch = fields[1].removeprefix("refs/heads/")
        sha = fields[0]
        if branch in result and result[branch] != sha:
            raise RunnerError(f"conflicting remote head for {branch}")
        result[branch] = sha
    return result


def live_scanner_rows(
    scanner_rows: dict[str, dict[str, Any]],
    remote_heads: dict[str, str],
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for branch, sha in remote_heads.items():
        row = scanner_rows.get(branch)
        if row and str(row.get("sha") or "") == sha:
            result[branch] = row
        else:
            result[branch] = {
                "name": branch,
                "sha": sha,
                "classification": "unknown_needs_review",
                "reason": "missing exact live scanner evidence",
                "ref_kind": "remote",
            }
    return result


def usage_delta(
    before: Any,
    after: Any,
) -> tuple[float | None, dict[str, int] | None]:
    if before is None or after is None:
        return None, None
    cpu_seconds = round(after.ru_utime + after.ru_stime - before.ru_utime - before.ru_stime, 3)
    io_blocks = {
        "read": max(0, after.ru_inblock - before.ru_inblock),
        "write": max(0, after.ru_oublock - before.ru_oublock),
    }
    return cpu_seconds, io_blocks


def check_wall_time(started: float, args: argparse.Namespace) -> None:
    if time.monotonic() - started >= args.max_wall_seconds:
        raise RunnerError("maximum wall time reached")


def row_id(row: dict[str, Any]) -> str:
    value = "|".join(str(row[key]) for key in ("branch", "sha", "classification", "reason"))
    return "recovery:" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:24]


def evidence_row(
    inventory: dict[str, str],
    current: dict[str, Any],
    *,
    archive_root: str,
    repository: str,
    date: str,
) -> dict[str, Any]:
    branch = inventory["branch"]
    sha = inventory["sha"]
    stem = f"{worktree_retirement.safe_name(branch)}-{sha[:12]}"
    relative_dir = worktree_retirement.archive_relative_dir(archive_root, repository, date)
    bundle = f"{archive_root.rstrip('/')}/{relative_dir}/{stem}.bundle"
    return {
        **inventory,
        "row_id": row_id(inventory),
        "recommended_next_action": str(current.get("recommended_action") or "owner_review"),
        "evidence": {
            "merged_into_develop": bool(current.get("merged_into_develop")),
            "merged_into_release_main": bool(current.get("merged_into_release_main")),
            "ahead_develop": current.get("ahead_develop"),
            "behind_develop": current.get("behind_develop"),
            "ahead_release_main": current.get("ahead_release_main"),
            "behind_release_main": current.get("behind_release_main"),
            "changed_paths_count": int(current.get("changed_paths_count") or 0),
            "changed_paths_collected": bool(current.get("changed_paths_collected")),
            "historical_value": current.get("historical_value"),
            "capability_state": current.get("capability_state"),
            "integration_evidence": current.get("integration_evidence"),
        },
        "restore": {
            "bundle": bundle,
            "manifest": bundle.removesuffix(".bundle") + ".json",
            "verify": f"git bundle list-heads {bundle}",
            "restore_ref": f"git fetch {bundle} {sha}:refs/heads/recovered/{worktree_retirement.safe_name(branch)}",
        },
    }


def ledger_identity(rows: list[dict[str, Any]]) -> str:
    value = "\n".join(f"{row['branch']}|{row['sha']}|{row['row_id']}" for row in rows)
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:20]


def write_ledger(
    args: argparse.Namespace,
    rows: list[dict[str, Any]],
    *,
    cohort_id: str,
    batch_number: int,
) -> Path:
    batch_id = ledger_identity(rows)
    filename = f"{cohort_id}-batch-{batch_number:03d}-{batch_id}.json"
    if args.apply:
        path = (
            Path(args.archive_root).expanduser().resolve()
            / "recovery-ledgers"
            / args.repository
            / filename
        )
    else:
        path = args.evidence_root / "ledger-candidates" / filename
    payload = {
        "schema_version": "1.0",
        "ledger_id": f"{cohort_id}:{batch_id}",
        "generated_at": utc_now(),
        "repository": args.repository,
        "source_inventory": str(args.inventory or (args.evidence_root / "deep-scan.json")),
        "contract": {
            "purpose": "archive-first recovery capture before exact-SHA source ref retirement",
            "model_tokens": 0,
            "required_follow_up": {
                "dirty_worker_candidate": "inspect captured work and rebuild from a clean canonical base",
                "integration_recovery_candidate": "inspect unique commits and reconcile valuable work into develop",
            },
        },
        "rows": rows,
    }
    write_json(path, payload)
    digest = worktree_retirement.sha256(path)
    sidecar = worktree_retirement.recovery_ledger_sidecar(path)
    sidecar.write_text(f"{digest}  {path.name}\n", encoding="utf-8")
    verified = worktree_retirement.load_recovery_capture_rows(path)
    if len(verified) != len(rows):
        raise RunnerError("recovery ledger verification count mismatch")
    return path


def retirement(
    args: argparse.Namespace,
    branches: list[str],
    ledger: Path | None,
    batch_number: int,
    *,
    apply: bool,
    lifecycle_evidence: Path | None = None,
) -> dict[str, Any]:
    command = [
        sys.executable,
        str(args.tool_root / "scripts/agent_control/worktree_retirement.py"),
        "--project-root", str(args.source_root),
        "--base-ref", f"origin/{args.base_branch}",
        "--base-ref", f"origin/{args.release_branch}",
        "--min-age-days", "0",
        "--protection-mode", "canonical-only",
        "--archive-root", args.archive_root,
        "--max-count", str(len(branches)),
        "--delete-local-branch",
        "--delete-remote-branch",
        "--json",
    ]
    if ledger:
        command += ["--recovery-ledger", str(ledger)]
    if lifecycle_evidence:
        command += ["--lifecycle-evidence", str(lifecycle_evidence)]
    for branch in branches:
        command += ["--branch", branch]
    if apply:
        command.append("--apply")
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
        raise RunnerError(f"retirement batch {batch_number} {suffix} failed")
    return payload


def execute(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    usage_before = resource.getrusage(resource.RUSAGE_SELF) if resource else None
    payload = load_json(args.inventory)
    if not isinstance(payload, dict):
        raise RunnerError("source reconciliation inventory is missing")
    targets = recovery_inventory(payload, args.inventory_list_key)
    if len(targets) != args.expected_target_count:
        raise RunnerError(f"target count mismatch: expected {args.expected_target_count}, got {len(targets)}")

    state_path = args.evidence_root / "state.json"
    state = load_json(state_path, {"schema_version": "1.0", "completed": {}})
    completed = state.get("completed")
    if not isinstance(completed, dict):
        raise RunnerError("continuation state is invalid")
    selected = [
        name
        for name in sorted(targets)
        if completed.get(name) != targets[name]["sha"]
    ][: args.max_branches]

    scan = run_scanner(args)
    branch_rows = logical_branch_rows(scan.get("branch_report") or {})
    confirmed: list[dict[str, Any]] = []
    retained: list[dict[str, str]] = []
    date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    for branch in selected:
        baseline = targets[branch]
        current = branch_rows.get(branch)
        if (
            not current
            or current.get("sha") != baseline["sha"]
            or current.get("classification") != baseline["classification"]
            or current.get("reason") != baseline["reason"]
        ):
            retained.append({
                **baseline,
                "current_sha": str((current or {}).get("sha") or ""),
                "current_classification": str((current or {}).get("classification") or "missing"),
                "current_reason": str((current or {}).get("reason") or "missing live evidence"),
            })
            continue
        confirmed.append(
            evidence_row(
                baseline,
                current,
                archive_root=args.archive_root,
                repository=args.repository,
                date=date,
            )
        )

    cohort_id = "branch-recovery-" + hashlib.sha256(
        "\n".join(f"{targets[name]['branch']}|{targets[name]['sha']}" for name in sorted(targets)).encode("utf-8")
    ).hexdigest()[:20]
    batches = [confirmed[i : i + args.batch_size] for i in range(0, len(confirmed), args.batch_size)]
    operations = {
        "deep_scans": 1,
        "ledgers_written": 0,
        "retirement_plans": 0,
        "retirement_applies": 0,
    }
    retired = 0
    ledger_index: list[dict[str, Any]] = []
    for number, rows in enumerate(batches, start=1):
        ledger = write_ledger(args, rows, cohort_id=cohort_id, batch_number=number)
        operations["ledgers_written"] += 1
        ledger_index.append({
            "batch": number,
            "path": str(ledger),
            "sha256": worktree_retirement.sha256(ledger),
            "row_count": len(rows),
        })
        branches = [row["branch"] for row in rows]
        plan = retirement(args, branches, ledger, number, apply=False)
        operations["retirement_plans"] += 1
        plan_rows = (plan.get("plan") or {}).get("worktrees") or []
        eligible = [str(row.get("branch")) for row in plan_rows if row.get("eligible")]
        for row in plan_rows:
            if not row.get("eligible"):
                retained.append({
                    **targets[str(row.get("branch"))],
                    "current_sha": str(row.get("sha") or ""),
                    "current_classification": targets[str(row.get("branch"))]["classification"],
                    "current_reason": ",".join(str(reason) for reason in row.get("reasons") or []),
                })
        if not args.apply or not eligible:
            continue
        refreshed = run_scanner(args, f"deep-scan-batch-{number:03d}.json")
        operations["deep_scans"] += 1
        refreshed_rows = logical_branch_rows(refreshed.get("branch_report") or {})
        rechecked: list[str] = []
        for branch in eligible:
            baseline = targets[branch]
            current = refreshed_rows.get(branch)
            if (
                current
                and current.get("sha") == baseline["sha"]
                and current.get("classification") == baseline["classification"]
                and current.get("reason") == baseline["reason"]
            ):
                rechecked.append(branch)
            else:
                retained.append({
                    **baseline,
                    "current_sha": str((current or {}).get("sha") or ""),
                    "current_classification": str((current or {}).get("classification") or "missing"),
                    "current_reason": str((current or {}).get("reason") or "batch liveness recheck failed"),
                })
        eligible = rechecked
        if not eligible:
            continue
        result = retirement(args, eligible, ledger, number, apply=True)
        operations["retirement_applies"] += 1
        results = result.get("results") or []
        for row in results:
            branch = str(row.get("branch") or "")
            if row.get("status") == "retired":
                archive = row.get("archive") or {}
                recovery = archive.get("recovery_ledger") or {}
                if (
                    row.get("sha") != targets.get(branch, {}).get("sha")
                    or archive.get("verified") is not True
                    or recovery.get("ledger_sha256") != worktree_retirement.sha256(ledger)
                ):
                    raise RunnerError(f"retirement evidence mismatch for {branch}")
                completed[branch] = targets[branch]["sha"]
                retired += 1
        state["completed"] = completed
        write_json(state_path, state)
        if len(results) != len(eligible) or any(row.get("status") != "retired" for row in results):
            raise RunnerError(f"retirement batch {number} did not complete")

    write_json(args.evidence_root / "recovery-inventory.json", confirmed)
    write_json(args.evidence_root / "retained.json", retained)
    write_json(args.evidence_root / "ledger-index.json", ledger_index)
    usage_after = resource.getrusage(resource.RUSAGE_SELF) if resource else None
    cpu_seconds = None
    io_blocks = None
    if usage_before is not None and usage_after is not None:
        cpu_seconds = round(
            usage_after.ru_utime + usage_after.ru_stime - usage_before.ru_utime - usage_before.ru_stime,
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
        "selected": len(selected),
        "confirmed": len(confirmed),
        "confirmed_by_classification": dict(Counter(row["classification"] for row in confirmed)),
        "retained": len(retained),
        "retired_this_run": retired,
        "resolved_total": len(completed),
        "deferred": max(0, len(targets) - len(completed) - len(retained)),
        "batch_size": args.batch_size,
        "batch_count": len(batches),
        "model_tokens": 0,
        "platform": platform.system().lower(),
        "ledger_index": str(args.evidence_root / "ledger-index.json"),
    }
    write_json(args.evidence_root / "resource-report.json", report)
    return report


def execute_prevention(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    usage_before = resource.getrusage(resource.RUSAGE_SELF) if resource else None
    initial_remote_heads = live_remote_heads(args)
    if len(initial_remote_heads) > args.max_scan_refs:
        raise RunnerError(f"live ref count exceeds hard cap: {len(initial_remote_heads)} > {args.max_scan_refs}")
    initial_scan = run_scanner(args)
    branch_rows = live_scanner_rows(
        logical_branch_rows(initial_scan.get("branch_report") or {}),
        initial_remote_heads,
    )
    persistent = persistent_branches(args)
    initial_groups = prevention_groups(branch_rows, persistent)
    candidates = [
        exact_row(row)
        for group in ("terminal", "recovery")
        for row in initial_groups[group]
    ]
    for row in candidates:
        validate_exact_row(row)
    selected = candidates[: args.max_branches]
    deferred = candidates[args.max_branches :]
    batches: list[list[dict[str, str]]] = []
    for classification_set in (TERMINAL_CLASSES, TARGET_CLASSES):
        rows = [row for row in selected if row["classification"] in classification_set]
        batches.extend(rows[index : index + args.batch_size] for index in range(0, len(rows), args.batch_size))

    operations = {
        "deep_scans": 1,
        "git_remote_queries": 1,
        "ledgers_written": 0,
        "retirement_plans": 0,
        "retirement_applies": 0,
    }
    retained: list[dict[str, str]] = []
    retired = 0
    ledger_index: list[dict[str, Any]] = []
    latest_rows = branch_rows
    date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    cohort_id = "branch-prevention-" + hashlib.sha256(
        "\n".join(f"{row['branch']}|{row['sha']}" for row in candidates).encode("utf-8")
    ).hexdigest()[:20]

    for number, baseline_rows in enumerate(batches, start=1):
        check_wall_time(started, args)
        lifecycle_path = args.evidence_root / "deep-scan.json"
        if args.apply:
            refreshed_remote_heads = live_remote_heads(args)
            operations["git_remote_queries"] += 1
            if len(refreshed_remote_heads) > args.max_scan_refs:
                raise RunnerError("batch liveness scan exceeds hard ref cap")
            if args.batch_recheck_mode == "deep":
                refreshed = run_scanner(args, f"deep-scan-batch-{number:03d}.json")
                operations["deep_scans"] += 1
                latest_rows = live_scanner_rows(
                    logical_branch_rows(refreshed.get("branch_report") or {}),
                    refreshed_remote_heads,
                )
                lifecycle_path = args.evidence_root / f"deep-scan-batch-{number:03d}.json"
            else:
                latest_rows = live_scanner_rows(latest_rows, refreshed_remote_heads)

        confirmed: list[dict[str, str]] = []
        for baseline in baseline_rows:
            current = latest_rows.get(baseline["branch"])
            current_exact = exact_row(current or {})
            if current and current_exact == baseline:
                confirmed.append(baseline)
            else:
                retained.append({
                    **baseline,
                    "current_sha": current_exact["sha"],
                    "current_classification": current_exact["classification"] or "missing",
                    "current_reason": current_exact["reason"] or "batch liveness recheck failed",
                })
        if not confirmed:
            continue

        recovery_batch = all(row["classification"] in TARGET_CLASSES for row in confirmed)
        ledger: Path | None = None
        if recovery_batch:
            recovery_rows = [
                evidence_row(
                    row,
                    latest_rows[row["branch"]],
                    archive_root=args.archive_root,
                    repository=args.repository,
                    date=date,
                )
                for row in confirmed
            ]
            ledger = write_ledger(args, recovery_rows, cohort_id=cohort_id, batch_number=number)
            operations["ledgers_written"] += 1
            ledger_index.append({
                "batch": number,
                "path": str(ledger),
                "sha256": worktree_retirement.sha256(ledger),
                "row_count": len(recovery_rows),
            })

        branches = [row["branch"] for row in confirmed]
        plan = retirement(
            args,
            branches,
            ledger,
            number,
            apply=False,
            lifecycle_evidence=None if recovery_batch else lifecycle_path,
        )
        operations["retirement_plans"] += 1
        plan_rows = (plan.get("plan") or {}).get("worktrees") or []
        eligible: list[str] = []
        by_branch = {row["branch"]: row for row in confirmed}
        for row in plan_rows:
            branch = str(row.get("branch") or "")
            if row.get("eligible") and branch in by_branch:
                eligible.append(branch)
            elif branch in by_branch:
                retained.append({
                    **by_branch[branch],
                    "current_sha": str(row.get("sha") or ""),
                    "current_classification": by_branch[branch]["classification"],
                    "current_reason": ",".join(str(reason) for reason in row.get("reasons") or []),
                })
        if not args.apply or not eligible:
            continue

        check_wall_time(started, args)
        result = retirement(
            args,
            eligible,
            ledger,
            number,
            apply=True,
            lifecycle_evidence=None if recovery_batch else lifecycle_path,
        )
        operations["retirement_applies"] += 1
        results = result.get("results") or []
        for row in results:
            branch = str(row.get("branch") or "")
            baseline = by_branch.get(branch) or {}
            archive = row.get("archive") or {}
            if (
                row.get("status") != "retired"
                or row.get("sha") != baseline.get("sha")
                or archive.get("verified") is not True
            ):
                raise RunnerError(f"retirement evidence mismatch for {branch or 'unknown'}")
            if recovery_batch:
                recovery = archive.get("recovery_ledger") or {}
                if not ledger or recovery.get("ledger_sha256") != worktree_retirement.sha256(ledger):
                    raise RunnerError(f"recovery ledger evidence mismatch for {branch}")
            retired += 1
        if len(results) != len(eligible):
            raise RunnerError(f"retirement batch {number} did not complete")

    check_wall_time(started, args)
    remote_heads = live_remote_heads(args)
    operations["git_remote_queries"] += 1
    if len(remote_heads) > args.max_scan_refs:
        raise RunnerError("final remote reconciliation exceeds hard ref cap")
    final_rows: list[dict[str, str]] = []
    final_counts = Counter()
    for branch, sha in sorted(remote_heads.items()):
        known = latest_rows.get(branch)
        known_exact = exact_row(known or {})
        if branch in persistent:
            category = "persistent"
            reason = "canonical persistent branch"
        elif known and known_exact["sha"] == sha and known_exact["classification"] == "keep_active":
            category = "active"
            reason = known_exact["reason"]
        elif known and known_exact["sha"] == sha and known_exact["classification"] in TERMINAL_CLASSES:
            category = "terminal_remaining"
            reason = known_exact["reason"]
        elif known and known_exact["sha"] == sha and known_exact["classification"] in TARGET_CLASSES:
            category = "recovery_remaining"
            reason = known_exact["reason"]
        else:
            category = "retained"
            reason = known_exact["reason"] if known and known_exact["sha"] == sha else "missing or changed final evidence"
        final_counts[category] += 1
        final_rows.append({"branch": branch, "sha": sha, "category": category, "reason": reason})
    reconciliation = {
        "schema_version": "1.0",
        "generated_at": utc_now(),
        "total_heads": len(remote_heads),
        "counts": dict(final_counts),
        "rows": final_rows,
    }
    write_json(args.evidence_root / "final-reconciliation.json", reconciliation)
    write_json(args.evidence_root / "retained.json", retained)
    write_json(args.evidence_root / "deferred.json", deferred)
    write_json(args.evidence_root / "ledger-index.json", ledger_index)
    usage_after = resource.getrusage(resource.RUSAGE_SELF) if resource else None
    cpu_seconds, io_blocks = usage_delta(usage_before, usage_after)
    report = {
        "schema_version": "1.0",
        "status": "completed",
        "mode": "apply" if args.apply else "dry_run",
        "purpose": "branch_lifecycle_prevention",
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "cpu_seconds": cpu_seconds,
        "io_blocks": io_blocks,
        "operations": operations,
        "initial_counts": {key: len(value) for key, value in initial_groups.items()},
        "selected": len(selected),
        "retained_during_run": len(retained),
        "retired_this_run": retired,
        "deferred": len(deferred),
        "final_total_heads": len(remote_heads),
        "final_counts": dict(final_counts),
        "batch_size": args.batch_size,
        "batch_count": len(batches),
        "max_branches": args.max_branches,
        "max_scan_refs": args.max_scan_refs,
        "max_wall_seconds": args.max_wall_seconds,
        "batch_recheck_mode": getattr(args, "batch_recheck_mode", "deep"),
        "model_tokens": 0,
        "platform": platform.system().lower(),
        "reconciliation": str(args.evidence_root / "final-reconciliation.json"),
        "ledger_index": str(args.evidence_root / "ledger-index.json"),
    }
    write_json(args.evidence_root / "resource-report.json", report)
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--tool-root", type=Path)
    parser.add_argument("--base-branch", default=DEFAULT_BASE_BRANCH)
    parser.add_argument("--release-branch", default=DEFAULT_RELEASE_BRANCH)
    parser.add_argument("--persistent-branch", action="append", default=[])
    parser.add_argument("--inventory", type=Path)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--evidence-cache", type=Path, required=True)
    parser.add_argument("--inventory-list-key", default="retained")
    parser.add_argument("--expected-target-count", type=int)
    parser.add_argument("--repository", default="ai-project-agent")
    parser.add_argument("--archive-root", default="/srv/aistudio-hdd/AiStudioData/archive/git-branches")
    parser.add_argument("--batch-size", type=int, default=25)
    parser.add_argument("--max-branches", type=int, default=25)
    parser.add_argument("--max-scan-refs", type=int, default=500)
    parser.add_argument("--max-wall-seconds", type=int, default=1200)
    parser.add_argument("--command-timeout-seconds", type=int, default=900)
    parser.add_argument("--batch-recheck-mode", choices=("deep", "live-ref"), default="deep")
    parser.add_argument("--prevention", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--yes", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if args.apply and not args.yes:
        parser.error("--apply requires --yes")
    if args.batch_size < 1 or args.batch_size > 25:
        parser.error("--batch-size must be between 1 and 25")
    if not args.prevention and (not args.inventory or not args.expected_target_count):
        parser.error("capture mode requires --inventory and --expected-target-count")
    if args.max_branches < 1 or (args.expected_target_count is not None and args.expected_target_count < 1):
        parser.error("branch bounds must be positive")
    if args.max_scan_refs < 1 or args.max_wall_seconds < 1:
        parser.error("scan and wall-time bounds must be positive")
    if args.command_timeout_seconds > args.max_wall_seconds:
        parser.error("--command-timeout-seconds cannot exceed --max-wall-seconds")
    return args


def main() -> int:
    args = parse_args()
    args.source_root = args.source_root.resolve()
    args.tool_root = (args.tool_root or args.source_root).resolve()
    if args.inventory:
        args.inventory = args.inventory.resolve()
    args.evidence_root = args.evidence_root.resolve()
    args.evidence_cache = args.evidence_cache.resolve()
    base_evidence_root = args.evidence_root
    base_evidence_root.mkdir(parents=True, exist_ok=True)
    try:
        with exclusive_lock(base_evidence_root / "runner.lock"):
            if args.prevention:
                run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
                args.evidence_root = base_evidence_root / "runs" / run_id
                args.evidence_root.mkdir(parents=True, exist_ok=False)
                payload = execute_prevention(args)
                payload["evidence_root"] = str(args.evidence_root)
                write_json(base_evidence_root / "latest.json", payload)
            else:
                payload = execute(args)
    except (OSError, ValueError, subprocess.SubprocessError, RunnerError) as exc:
        payload = {"schema_version": "1.0", "status": "failed", "error": str(exc), "model_tokens": 0}
        write_json(args.evidence_root / "failure-report.json", payload)
        if args.prevention:
            write_json(base_evidence_root / "latest.json", payload)
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if args.json else json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if payload.get("status") == "completed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
