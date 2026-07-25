#!/usr/bin/env python3
"""Finalize tasks whose reviewed integration PR is already merged."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_file, task_manager_dir


ELIGIBLE_STATUSES = {"agent_done", "integration_requested", "needs_human", "review"}
ELIGIBLE_INTEGRATION_STATUSES = {"finalizer_ready", "manual_pr_ready", "needs_integrator_review", "pending"}
PASSING_CHECK_CONCLUSIONS = {"NEUTRAL", "SKIPPED", "SUCCESS"}
PASSING_CONTEXT_STATES = {"EXPECTED", "SUCCESS"}
STRONG_RISK_CLASSES = {"critical", "high"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=str(cwd),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def git_text(project_root: Path, args: list[str]) -> str:
    proc = run(["git", *args], project_root)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or proc.stdout.strip() or f"git {' '.join(args)} failed")
    return proc.stdout.strip()


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"expected JSON object: {path}")
    return data


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def exact_token(text: str, value: str) -> bool:
    if not value:
        return False
    pattern = rf"(?<![A-Za-z0-9_.-]){re.escape(value)}(?![A-Za-z0-9_.-])"
    return re.search(pattern, text, flags=re.IGNORECASE) is not None


def remote_repo(project_root: Path) -> str:
    remote = git_text(project_root, ["config", "--get", "remote.origin.url"])
    value = remote.removesuffix(".git").replace("\\", "/")
    if value.startswith("git@github.com:"):
        return value.split(":", 1)[1]
    marker = "github.com/"
    if marker in value:
        return value.split(marker, 1)[1]
    raise RuntimeError("origin is not a supported GitHub repository URL; pass --repo")


def collect_prs(
    project_root: Path,
    *,
    repo: str,
    base_branch: str,
    limit: int,
    snapshot: Path | None,
) -> list[dict[str, Any]]:
    if snapshot is not None:
        data = json.loads(snapshot.read_text(encoding="utf-8"))
    else:
        fields = (
            "number,url,state,isDraft,baseRefName,headRefName,headRefOid,"
            "mergeCommit,mergedAt,body,statusCheckRollup"
        )
        proc = run(
            [
                "gh",
                "pr",
                "list",
                "--repo",
                repo,
                "--state",
                "merged",
                "--base",
                base_branch,
                "--limit",
                str(limit),
                "--json",
                fields,
            ],
            project_root,
        )
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.strip() or proc.stdout.strip() or "gh pr list failed")
        data = json.loads(proc.stdout)
    if not isinstance(data, list):
        raise ValueError("merged PR snapshot must be a JSON array")
    return [row for row in data if isinstance(row, dict)]


def eligible_task(task: dict[str, Any]) -> bool:
    return (
        str(task.get("status") or "").lower() in ELIGIBLE_STATUSES
        and str(task.get("integration_status") or "").lower() in ELIGIBLE_INTEGRATION_STATUSES
        and bool(str(task.get("worker_result_commit") or "").strip())
    )


def check_rollup_issues(rollup: Any) -> list[str]:
    if not isinstance(rollup, list) or not rollup:
        return ["integration PR has no CI check evidence"]
    issues: list[str] = []
    for item in rollup:
        if not isinstance(item, dict):
            issues.append("integration PR contains malformed CI check evidence")
            continue
        kind = str(item.get("__typename") or "")
        name = str(item.get("name") or item.get("context") or "unnamed check")
        if kind == "CheckRun" or "conclusion" in item:
            status = str(item.get("status") or "").upper()
            conclusion = str(item.get("conclusion") or "").upper()
            if status and status != "COMPLETED":
                issues.append(f"CI check is not complete: {name}")
            elif conclusion not in PASSING_CHECK_CONCLUSIONS:
                issues.append(f"CI check did not pass: {name} ({conclusion or 'unknown'})")
        else:
            state = str(item.get("state") or "").upper()
            if state not in PASSING_CONTEXT_STATES:
                issues.append(f"CI status did not pass: {name} ({state or 'unknown'})")
    return issues


def manifest_paths(body: str) -> list[str]:
    return sorted(
        {
            value.replace("\\", "/")
            for value in re.findall(r"`([^`]+\.json)`", body)
            if value.replace("\\", "/").startswith("docs/reports/integration/")
        }
    )


def read_manifest(project_root: Path, merge_commit: str, path: str) -> dict[str, Any] | None:
    proc = run(["git", "show", f"{merge_commit}:{path}"], project_root)
    if proc.returncode != 0:
        return None
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return None
    return data if isinstance(data, dict) else None


def manifest_check_passed(manifest: dict[str, Any], needle: str) -> bool:
    for check in manifest.get("checks") or []:
        if not isinstance(check, dict):
            continue
        if needle not in str(check.get("name") or "").lower():
            continue
        result = str(check.get("result") or "").lower()
        return "pass" in result or "preservation_ok" in result or result.startswith("ok")
    return False


def strong_review_issues(
    project_root: Path,
    task: dict[str, Any],
    pr: dict[str, Any],
    merge_commit: str,
) -> tuple[list[str], str | None]:
    strong_review = bool(task.get("requires_strong_review")) or str(task.get("source_risk_class") or "").lower() in STRONG_RISK_CLASSES
    body = str(pr.get("body") or "")
    paths = manifest_paths(body)
    if not paths:
        if strong_review:
            return ["strong review task has no integration manifest reference"], None
        return [], None
    worker_sha = str(task.get("worker_result_commit") or "").strip().lower()
    source_sha = str(
        task.get("source_head_sha")
        or task.get("clean_rebuild_source_head_sha")
        or task.get("repository_hygiene_head_sha")
        or ""
    ).strip().lower()
    issues: list[str] = []
    for path in paths:
        manifest = read_manifest(project_root, merge_commit, path)
        if manifest is None:
            issues.append(f"integration manifest is missing or invalid at merge commit: {path}")
            continue
        if str(manifest.get("mode") or "").lower() != "manual":
            issues.append(f"integration manifest is not ManualIntegrationMode: {path}")
            continue
        if str(manifest.get("working_branch") or "") != str(pr.get("headRefName") or ""):
            issues.append(f"integration manifest branch does not match PR head: {path}")
            continue
        if manifest.get("missing_surfaces") not in (None, []):
            issues.append(f"integration manifest still has missing surfaces: {path}")
            continue
        reality = manifest.get("project_reality_map") if isinstance(manifest.get("project_reality_map"), dict) else {}
        if reality.get("blocking_gaps") not in (None, []):
            issues.append(f"integration manifest still has blocking reality-map gaps: {path}")
            continue
        if str(manifest.get("final_status") or "") not in {
            "finalizer_ready",
            "manual_integration_done",
            "manual_pr_ready",
        }:
            issues.append(f"integration manifest is not finalizer-eligible: {path}")
            continue
        commits = {
            str(value).strip().lower()
            for value in ((manifest.get("evidence") or {}).get("commits") or [])
            if str(value).strip()
        }
        if worker_sha and worker_sha not in commits:
            issues.append(f"integration manifest does not bind the exact Worker result SHA: {path}")
            continue
        if source_sha and source_sha not in commits:
            issues.append(f"integration manifest does not bind the exact source SHA: {path}")
            continue
        if not manifest_check_passed(manifest, "capability preservation"):
            issues.append(f"integration manifest lacks passing capability-preservation evidence: {path}")
            continue
        if not manifest_check_passed(manifest, "integration protection"):
            issues.append(f"integration manifest lacks passing Integration Protection evidence: {path}")
            continue
        return [], path
    return issues or ["no acceptable strong-review integration manifest found"], None


def merge_commit_oid(pr: dict[str, Any]) -> str:
    value = pr.get("mergeCommit")
    if isinstance(value, dict):
        return str(value.get("oid") or "").strip()
    return str(value or "").strip()


def validate_candidate(
    project_root: Path,
    task: dict[str, Any],
    pr: dict[str, Any],
    *,
    base_branch: str,
    base_ref: str,
) -> dict[str, Any]:
    current_id = task_id(task)
    body = str(pr.get("body") or "")
    worker_sha = str(task.get("worker_result_commit") or "").strip()
    source_sha = str(
        task.get("source_head_sha")
        or task.get("clean_rebuild_source_head_sha")
        or task.get("repository_hygiene_head_sha")
        or ""
    ).strip()
    head_sha = str(pr.get("headRefOid") or "").strip()
    merge_commit = merge_commit_oid(pr)
    issues: list[str] = []
    if str(pr.get("state") or "").upper() != "MERGED" or not pr.get("mergedAt"):
        issues.append("integration PR is not merged")
    if bool(pr.get("isDraft")):
        issues.append("integration PR is still draft")
    if str(pr.get("baseRefName") or "") != base_branch:
        issues.append("integration PR targets a different base branch")
    if not exact_token(body, current_id):
        issues.append("integration PR body does not bind the exact task ID")
    if len(worker_sha) != 40:
        issues.append("task lacks an exact 40-character Worker result SHA")
    if worker_sha and worker_sha.lower() not in body.lower():
        issues.append("integration PR body does not bind the exact Worker result SHA")
    if source_sha and len(source_sha) != 40:
        issues.append("task source SHA is not an exact 40-character commit")
    if source_sha and source_sha.lower() not in body.lower():
        issues.append("integration PR body does not bind the exact source SHA")
    if len(head_sha) != 40 or len(merge_commit) != 40:
        issues.append("integration PR lacks exact head or merge SHA")
    issues.extend(check_rollup_issues(pr.get("statusCheckRollup")))
    if merge_commit:
        ancestry = run(["git", "merge-base", "--is-ancestor", merge_commit, base_ref], project_root)
        if ancestry.returncode != 0:
            issues.append(f"integration merge commit is not present on {base_ref}")
    manifest_issues, manifest_path = strong_review_issues(project_root, task, pr, merge_commit)
    issues.extend(manifest_issues)
    return {
        "task_id": current_id,
        "pr_number": pr.get("number"),
        "pr_url": pr.get("url"),
        "head_branch": pr.get("headRefName"),
        "head_sha": head_sha,
        "merge_commit": merge_commit,
        "merged_at": pr.get("mergedAt"),
        "manifest": manifest_path,
        "ok": not issues,
        "issues": issues,
    }


def event_id(kind: str, current_id: str, merge_commit: str) -> str:
    digest = hashlib.sha256(f"{kind}|{current_id}|{merge_commit}".encode("utf-8")).hexdigest()[:20]
    return f"{kind}-accepted-pr-{digest}"


def existing_event_ids(path: Path) -> set[str]:
    if not path.exists():
        return set()
    result: set[str] = set()
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict) and row.get("event_id"):
            result.add(str(row["event_id"]))
    return result


def append_events(path: Path, project: str, candidate: dict[str, Any], now: str) -> list[str]:
    known = existing_event_ids(path)
    current_id = str(candidate["task_id"])
    merge_commit = str(candidate["merge_commit"])
    payload = {
        "integration_pr": candidate.get("pr_number"),
        "integration_pr_url": candidate.get("pr_url"),
        "integration_head_sha": candidate.get("head_sha"),
        "merge_commit": merge_commit,
        "manifest": candidate.get("manifest"),
        "mode": "accepted_manual_integration_pr",
    }
    appended: list[str] = []
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for kind, reason in (
            ("integration_recorded", "accepted integration PR exact evidence verified"),
            ("finalization_recorded", "accepted integration PR is merged with passing CI and review evidence"),
        ):
            current_event_id = event_id(kind, current_id, merge_commit)
            if current_event_id in known:
                continue
            row = {
                "schema_version": 1,
                "event_id": current_event_id,
                "created_at": now,
                "project": project,
                "event": kind,
                "role": "auto-finalizer",
                "task_id": current_id,
                "canonical_target_id": f"task:{current_id}",
                "severity": "info",
                "reason": reason,
                "payload": payload,
            }
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
            appended.append(current_event_id)
            known.add(current_event_id)
    return appended


def apply_candidate(
    task: dict[str, Any],
    candidate: dict[str, Any],
    *,
    now: str,
) -> None:
    before = {
        "status": task.get("status"),
        "integration_status": task.get("integration_status"),
        "finalization_status": task.get("finalization_status"),
    }
    task.update(
        {
            "status": "done",
            "integration_status": "finalized",
            "finalization_status": "done_recorded",
            "dispatcher_decision": "done",
            "worker_ready": False,
            "lock": "free",
            "next_owner": "none",
            "next_role": "none",
            "integration_pr": candidate.get("pr_number"),
            "integration_pr_url": candidate.get("pr_url"),
            "integration_pr_head_sha": candidate.get("head_sha"),
            "merge_commit": candidate.get("merge_commit"),
            "finalizer_merge_commit": candidate.get("merge_commit"),
            "finalized_at": now,
            "finalized_by": "accepted_integration_reconciler",
            "status_reason": "merged Manual Integrator PR verified against exact task, Worker, source, CI and review evidence",
            "accepted_integration_evidence": {
                "pr_number": candidate.get("pr_number"),
                "pr_url": candidate.get("pr_url"),
                "head_branch": candidate.get("head_branch"),
                "head_sha": candidate.get("head_sha"),
                "merge_commit": candidate.get("merge_commit"),
                "merged_at": candidate.get("merged_at"),
                "manifest": candidate.get("manifest"),
            },
        }
    )
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append(
        {
            "at": now,
            "by": "accepted_integration_reconciler",
            "from": before,
            "to": {
                "status": "done",
                "integration_status": "finalized",
                "finalization_status": "done_recorded",
            },
            "event": "finalization_recorded",
            "reason": "accepted integration PR exact evidence verified",
            "integration_pr": candidate.get("pr_number"),
            "merge_commit": candidate.get("merge_commit"),
        }
    )
    task["status_history"] = history


def release_lock(locks: dict[str, Any], current_id: str, now: str) -> bool:
    changed = False
    for lock in locks.get("locks") or []:
        if not isinstance(lock, dict) or str(lock.get("task_id") or "") != current_id:
            continue
        if str(lock.get("state") or "") != "released":
            lock["state"] = "released"
            lock["released_at"] = now
            lock["released_by"] = "accepted_integration_reconciler"
            lock["notes"] = "accepted integration PR finalized"
            changed = True
    if changed:
        locks["updated_at"] = now
    return changed


def reconcile(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve()
    queue_path = task_file(project_root, "task_queue.json")
    locks_path = task_file(project_root, "agent_locks.json")
    events_path = task_file(project_root, "agent_events.jsonl")
    queue = load_json(queue_path)
    locks = load_json(locks_path) if locks_path.exists() else {"locks": []}
    repo = args.repo or remote_repo(project_root)
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    eligible = [task for task in tasks if isinstance(task, dict) and eligible_task(task)]
    if args.task_id:
        eligible = [task for task in eligible if task_id(task) == args.task_id]
    now = utc_now()
    output = Path(args.output).resolve() if args.output else task_manager_dir(project_root) / "reports" / "ACCEPTED_INTEGRATION_RECONCILER.json"
    if not eligible:
        report = {
            "schema_version": 1,
            "generated_at": now,
            "project_root": str(project_root),
            "repo": repo,
            "base_branch": args.base_branch,
            "base_ref": args.base_ref,
            "apply": bool(args.apply),
            "eligible_task_count": 0,
            "merged_pr_count": 0,
            "ready_count": 0,
            "blocked_count": 0,
            "unmatched_count": 0,
            "ready": [],
            "blocked": [],
            "unmatched_task_ids": [],
            "applied_count": 0,
            "applied": [],
            "status": "no_candidates",
        }
        write_json(output, report)
        report["output"] = str(output)
        return report
    if args.fetch:
        proc = run(["git", "fetch", "origin", "--prune"], project_root)
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.strip() or proc.stdout.strip() or "git fetch failed")
    base_ref = args.base_ref
    git_text(project_root, ["rev-parse", "--verify", base_ref])
    snapshot = Path(args.pr_list_json).resolve() if args.pr_list_json else None
    prs = collect_prs(
        project_root,
        repo=repo,
        base_branch=args.base_branch,
        limit=args.max_prs,
        snapshot=snapshot,
    )
    if args.pr_number:
        prs = [row for row in prs if int(row.get("number") or 0) == args.pr_number]

    ready: list[tuple[dict[str, Any], dict[str, Any]]] = []
    blocked: list[dict[str, Any]] = []
    unmatched: list[str] = []
    for task in eligible:
        current_id = task_id(task)
        matching_prs = [pr for pr in prs if exact_token(str(pr.get("body") or ""), current_id)]
        if not matching_prs:
            unmatched.append(current_id)
            continue
        evaluated = [
            validate_candidate(project_root, task, pr, base_branch=args.base_branch, base_ref=base_ref)
            for pr in matching_prs
        ]
        valid = [candidate for candidate in evaluated if candidate.get("ok")]
        if len(valid) == 1:
            ready.append((task, valid[0]))
        elif len(valid) > 1:
            blocked.append(
                {
                    "task_id": current_id,
                    "reason": "multiple merged integration PRs satisfy the exact evidence contract",
                    "candidates": valid,
                }
            )
        else:
            blocked.append(
                {
                    "task_id": current_id,
                    "reason": "merged PR evidence did not pass the accepted-integration gate",
                    "candidates": evaluated,
                }
            )

    ready = ready[: max(0, args.max_items)]
    applied: list[dict[str, Any]] = []
    if args.apply:
        for task, candidate in ready:
            apply_candidate(task, candidate, now=now)
            event_ids = append_events(events_path, project_root.name, candidate, now)
            lock_released = release_lock(locks, str(candidate["task_id"]), now)
            applied.append({**candidate, "event_ids": event_ids, "lock_released": lock_released})
        if applied:
            queue["updated_at"] = now
            write_json(queue_path, queue)
            if locks_path.exists() or any(item.get("lock_released") for item in applied):
                write_json(locks_path, locks)

    report = {
        "schema_version": 1,
        "generated_at": now,
        "project_root": str(project_root),
        "repo": repo,
        "base_branch": args.base_branch,
        "base_ref": base_ref,
        "apply": bool(args.apply),
        "eligible_task_count": len(eligible),
        "merged_pr_count": len(prs),
        "ready_count": len(ready),
        "blocked_count": len(blocked),
        "unmatched_count": len(unmatched),
        "ready": [candidate for _, candidate in ready],
        "blocked": blocked,
        "unmatched_task_ids": unmatched,
        "applied_count": len(applied),
        "applied": applied,
        "status": "reconciled" if applied else "ready" if ready else "no_candidates",
    }
    write_json(output, report)
    report["output"] = str(output)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--repo")
    parser.add_argument("--base-branch", default="develop")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--pr-list-json", help="Deterministic merged-PR snapshot for tests or controlled runs.")
    parser.add_argument("--max-prs", type=int, default=100)
    parser.add_argument("--max-items", type=int, default=5)
    parser.add_argument("--task-id")
    parser.add_argument("--pr-number", type=int)
    parser.add_argument("--output")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report = reconcile(args)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"status: {report['status']}")
        print(f"ready: {report['ready_count']}")
        print(f"applied: {report['applied_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
