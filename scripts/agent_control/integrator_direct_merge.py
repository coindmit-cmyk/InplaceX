#!/usr/bin/env python3
"""Apply an integration batch directly to develop without a Finalizer package hop."""

from __future__ import annotations

import argparse
import ast
import json
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_manager_dir
import documentation_impact_checker
import project_version_gate
from worker_result_contract_validator import DOCUMENTATION_IMPACT_VALUES, documentation_impact_from_report, task_requires_documentation_impact

TERMINAL_TASK_STATUSES = {"done", "postponed", "failed", "stale_or_superseded", "duplicate_linked"}
TERMINAL_INTEGRATION_STATUSES = {"finalized", "closed_no_diff", "closed_coordination_only", "blocked"}

DOCUMENTATION_IMPACT_BLOCKING = {"blocked_missing_docs"}
MAX_INTEGRATION_REPAIR_WORKER_RETRIES = 2

WORKER_PACKET_BASE_FIELDS = (
    "complexity",
    "priority",
    "type",
    "allowed_paths",
    "forbidden_paths",
    "acceptance_criteria",
    "checks",
)
WORKER_PACKET_V2_FIELDS = (
    "worker_instructions",
    "traceability",
    "context_inventory",
    "doc_refs",
    "input_refs",
    "output_contract",
    "script_actions",
    "existing_behavior",
    "preserve_contract",
    "regression_guards",
    "code_refs",
    "integration_notes",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def run(cmd: list[str], cwd: Path) -> dict[str, Any]:
    proc = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True, check=False)
    return {"command": cmd, "cwd": str(cwd), "exit_code": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr}


def run_with_input(cmd: list[str], cwd: Path, payload: bytes) -> dict[str, Any]:
    proc = subprocess.run(cmd, cwd=cwd, input=payload, capture_output=True, check=False)
    return {
        "command": cmd,
        "cwd": str(cwd),
        "exit_code": proc.returncode,
        "stdout": proc.stdout.decode("utf-8", errors="replace"),
        "stderr": proc.stderr.decode("utf-8", errors="replace"),
    }


def short(result: dict[str, Any]) -> str:
    return ((result.get("stderr") or "") + "\n" + (result.get("stdout") or "")).strip()[-2000:]


def has_packet_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict)):
        return bool(value)
    return True


def valid_source_head_sha(value: Any) -> str:
    candidate = str(value or "").strip().lower()
    return candidate if re.fullmatch(r"[0-9a-f]{40}", candidate) else ""


def normalized_target_branch(value: Any) -> str:
    branch = str(value or "").strip()
    for prefix in ("refs/remotes/origin/", "origin/", "refs/heads/"):
        if branch.startswith(prefix):
            return branch[len(prefix):]
    return branch


def integration_target_gate_issues(base_ref: str, task_row: dict[str, Any] | None) -> list[str]:
    if not isinstance(task_row, dict) or str(task_row.get("type") or "") != "repository_hygiene_integration":
        return []
    target = normalized_target_branch(task_row.get("base_branch"))
    expected = normalized_target_branch(base_ref)
    if not target:
        return ["repository hygiene target base is missing"]
    if not expected:
        return ["Integrator base is missing"]
    if target and expected and target != expected:
        return [f"repository hygiene target base {target!r} does not match Integrator base {expected!r}"]
    return []


def integration_repair_source_head_sha(
    task: dict[str, Any] | None,
    routed_metadata: dict[str, Any] | None = None,
) -> str:
    routed_metadata = routed_metadata if isinstance(routed_metadata, dict) else {}
    task = task if isinstance(task, dict) else {}
    routed_values = (
        routed_metadata.get("integration_repair_source_head_sha"),
        routed_metadata.get("source_head_sha"),
        routed_metadata.get("head_sha"),
        routed_metadata.get("worker_result_commit"),
    )
    task_values = [task.get("integration_repair_source_head_sha")]
    if str(task.get("type") or "") == "repository_hygiene_integration":
        task_values.extend([task.get("repository_hygiene_head_sha"), task.get("head_sha")])
    task_values.append(task.get("worker_result_commit"))
    for value in (*routed_values, *task_values):
        if source_head_sha := valid_source_head_sha(value):
            return source_head_sha
    return ""


def integration_repair_retry_count(task: dict[str, Any]) -> int:
    try:
        return max(0, int(task.get("integration_repair_retry_count") or 0))
    except (TypeError, ValueError):
        return MAX_INTEGRATION_REPAIR_WORKER_RETRIES


def is_repairable_integrator_failure_task(
    task: dict[str, Any] | None,
    routed_metadata: dict[str, Any] | None = None,
) -> bool:
    if not isinstance(task, dict):
        return False
    task_type = str(task.get("type") or "").strip()
    if task_type == "repository_hygiene_integration":
        return True
    if task_type != "design-handoff-intake" or not has_packet_value(task.get("source_item_id")):
        return False
    if integration_repair_retry_count(task) >= MAX_INTEGRATION_REPAIR_WORKER_RETRIES:
        return False
    if not integration_repair_source_head_sha(task, routed_metadata):
        return False
    try:
        if int(task.get("packet_schema_version") or 1) < 2:
            return False
    except (TypeError, ValueError):
        return False
    if any(
        not has_packet_value(task.get(field))
        for field in (*WORKER_PACKET_BASE_FIELDS, *WORKER_PACKET_V2_FIELDS)
    ):
        return False
    if has_packet_value(task.get("blocked_by")) or has_packet_value(task.get("split_into")):
        return False
    if not has_packet_value(task.get("eligible_worker_profiles")):
        return False
    if not any(
        has_packet_value(task.get(field))
        for field in ("context_docs", "source_file", "provenance")
    ):
        return False
    if task.get("requires_current_context_review") is True and not (
        has_packet_value(task.get("current_context_verified_at"))
        and (
            has_packet_value(task.get("current_context_verified_by"))
            or has_packet_value(task.get("current_context_reviewed_by"))
        )
    ):
        return False
    context_inventory = task.get("context_inventory")
    if not isinstance(context_inventory, dict) or any(
        not has_packet_value(context_inventory.get(field))
        for field in ("code_refs", "doc_refs", "task_refs")
    ):
        return False
    output_contract = task.get("output_contract")
    return (
        isinstance(output_contract, dict)
        and output_contract.get("changed_paths_must_match_allowed_paths") is True
        and output_contract.get("preserve_existing_behavior") is True
        and str(output_contract.get("task_state_on_blocker") or "") == "needs_worker_fix"
    )


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def append_event(path: Path, event: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")


def mark_events_consumed(path: Path, event_ids: list[str], role: str) -> int:
    wanted = {str(event_id) for event_id in event_ids if str(event_id or "").strip()}
    if not wanted or not path.exists():
        return 0
    changed = 0
    lines: list[str] = []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            lines.append(line)
            continue
        if isinstance(event, dict) and str(event.get("event_id") or "") in wanted and not event.get("consumed_by"):
            event["consumed_by"] = role
            event["consumed_at"] = utc_now()
            changed += 1
        lines.append(json.dumps(event, ensure_ascii=False, sort_keys=True) if isinstance(event, dict) else line)
    if changed:
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return changed


def slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-").lower() or "item"


def normalize_branch(value: Any) -> str:
    branch = str(value or "").strip()
    if branch.startswith("refs/remotes/origin/"):
        return "origin/" + branch.removeprefix("refs/remotes/origin/")
    if branch.startswith("refs/heads/"):
        return branch.removeprefix("refs/heads/")
    return branch


def resolve_branch_ref(branch: str, worktree: Path, results: list[dict[str, Any]]) -> str | None:
    """Return a checkoutable local or origin ref for a worker branch."""
    candidates = [branch]
    if branch and not branch.startswith("origin/"):
        candidates.append(f"origin/{branch}")
    for candidate in candidates:
        verify = run(["git", "rev-parse", "--verify", candidate], worktree)
        results.append(verify)
        if verify["exit_code"] == 0:
            return candidate
    return None


def paths_existing_at_ref(ref: str, paths: list[str], worktree: Path, results: list[dict[str, Any]]) -> tuple[list[str], list[str]]:
    existing: list[str] = []
    missing: list[str] = []
    for path in paths:
        listed = run(["git", "ls-tree", "-r", "--name-only", ref, "--", path], worktree)
        results.append(listed)
        matches = {line.strip() for line in str(listed.get("stdout") or "").splitlines() if line.strip()}
        if listed["exit_code"] == 0 and (path in matches or any(match.startswith(path.rstrip("/") + "/") for match in matches)):
            existing.append(path)
        else:
            missing.append(path)
    if missing:
        results.append({
            "command": ["integrator", "filter-missing-paths", ref],
            "cwd": str(worktree),
            "exit_code": 0,
            "stdout": json.dumps({"kept": existing, "missing": missing}, ensure_ascii=False),
            "stderr": "",
        })
    return existing, missing


def focused_validation_paths(worktree: Path, changed_paths: list[str]) -> list[str]:
    tests: set[str] = set()
    unpaired_script_stems: set[str] = set()
    for rel_path in changed_paths:
        normalized = str(rel_path or "").replace("\\", "/").strip()
        if normalized.startswith("tests/") and normalized.endswith(".py") and (worktree / normalized).is_file():
            tests.add(normalized)
        if normalized.startswith("scripts/agent_control/") and normalized.endswith(".py"):
            stem = Path(normalized).stem
            candidate = f"tests/test_{stem}.py"
            if (worktree / candidate).is_file():
                tests.add(candidate)
            else:
                unpaired_script_stems.add(stem)
    tests_root = worktree / "tests"
    if unpaired_script_stems and tests_root.is_dir():
        semantic_matches: dict[str, set[str]] = {
            stem: set() for stem in unpaired_script_stems
        }
        fallback_matches: dict[str, set[str]] = {
            stem: set() for stem in unpaired_script_stems
        }
        for candidate in tests_root.rglob("test_*.py"):
            text = read_text_file(candidate)
            rel_path = candidate.relative_to(worktree).as_posix()
            for stem in unpaired_script_stems:
                escaped = re.escape(stem)
                if re.search(rf"\b(?:from|import)\s+{escaped}\b", text) or re.search(
                    rf"spec_from_file_location\(\s*[\"']{escaped}[\"']",
                    text,
                ):
                    semantic_matches[stem].add(rel_path)
                elif re.search(rf"\b{escaped}\b", text):
                    fallback_matches[stem].add(rel_path)
        for stem in sorted(unpaired_script_stems):
            matches = semantic_matches[stem] or fallback_matches[stem]
            tests.update(sorted(matches)[:3])
    return sorted(tests)


def changed_line_numbers(diff_text: str) -> set[int]:
    lines: set[int] = set()
    for line in diff_text.splitlines():
        match = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
        if not match:
            continue
        start = int(match.group(1))
        count = int(match.group(2) or 1)
        lines.update(range(start, start + count))
    return lines


def test_nodes_for_changed_lines(rel_path: str, text: str, changed_lines: set[int]) -> list[str]:
    if not changed_lines:
        return [rel_path]
    source_lines = text.splitlines()
    behavior_lines = {
        line
        for line in changed_lines
        if 1 <= line <= len(source_lines)
        and source_lines[line - 1].strip()
        and not source_lines[line - 1].lstrip().startswith("#")
    }
    if not behavior_lines:
        return [rel_path]
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return [rel_path]

    nodes: list[tuple[int, int, str]] = []
    for item in tree.body:
        if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)) and item.name.startswith("test_"):
            start = min([item.lineno, *(decorator.lineno for decorator in item.decorator_list)])
            nodes.append((start, int(item.end_lineno or item.lineno), f"{rel_path}::{item.name}"))
        elif isinstance(item, ast.ClassDef):
            for child in item.body:
                if not isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)) or not child.name.startswith("test_"):
                    continue
                start = min([child.lineno, *(decorator.lineno for decorator in child.decorator_list)])
                nodes.append(
                    (
                        start,
                        int(child.end_lineno or child.lineno),
                        f"{rel_path}::{item.name}::{child.name}",
                    )
                )

    selected = {
        selector
        for start, end, selector in nodes
        if any(start <= line <= end for line in behavior_lines)
    }
    covered_lines = {
        line
        for start, end, _selector in nodes
        for line in behavior_lines
        if start <= line <= end
    }
    if covered_lines != behavior_lines or not selected:
        return [rel_path]
    return sorted(selected)


def focused_validation_targets(worktree: Path, validation_paths: list[str]) -> list[str]:
    targets: list[str] = []
    for rel_path in validation_paths:
        diff = run(["git", "diff", "--cached", "--unified=0", "--", rel_path], worktree)
        if diff["exit_code"] != 0 or not str(diff.get("stdout") or "").strip():
            targets.append(rel_path)
            continue
        targets.extend(
            test_nodes_for_changed_lines(
                rel_path,
                read_text_file(worktree / rel_path),
                changed_line_numbers(str(diff.get("stdout") or "")),
            )
        )
    return sorted(set(targets))


def required_focused_test_checks(task_row: dict[str, Any] | None) -> list[str]:
    if not isinstance(task_row, dict):
        return []
    checks = task_row.get("checks")
    if not isinstance(checks, list):
        return []
    return [
        str(check).strip()
        for check in checks
        if str(check or "").strip()
        and re.search(r"\b(?:test|tests|pytest|regression|matrix)\b", str(check), flags=re.IGNORECASE)
    ]


def uncovered_focused_test_paths(
    worktree: Path,
    changed_paths: list[str],
    validation_paths: list[str],
) -> list[str]:
    tests = {
        path: read_text_file(worktree / path)
        for path in validation_paths
        if path.startswith("tests/") and path.endswith(".py")
    }
    uncovered: list[str] = []
    for rel_path in changed_paths:
        normalized = normalize_path(rel_path)
        if not normalized.startswith("scripts/agent_control/") or not normalized.endswith(".py"):
            continue
        stem = Path(normalized).stem
        paired = f"tests/test_{stem}.py"
        if paired in tests:
            continue
        if any(re.search(rf"\b{re.escape(stem)}\b", text) for text in tests.values()):
            continue
        uncovered.append(normalized)
    return sorted(set(uncovered))


def source_pr_numbers(item: dict[str, Any], task_row: dict[str, Any] | None) -> list[int]:
    row = task_row or {}
    if str(row.get("type") or item.get("type") or "") != "repository_hygiene_integration":
        return []
    values = row.get("pr_numbers") or item.get("pr_numbers") or []
    if not isinstance(values, list):
        values = []
    leaf = row.get("github_pr") or item.get("github_pr")
    if leaf is not None:
        values = [*values, leaf]
    numbers: set[int] = set()
    for value in values:
        try:
            numbers.add(int(value))
        except (TypeError, ValueError):
            continue
    return sorted(number for number in numbers if number > 0)


def repository_hygiene_live_ci_gate(
    worktree: Path,
    base_ref: str,
    resolved_branch: str,
    task_row: dict[str, Any] | None,
    item: dict[str, Any],
) -> tuple[list[str], dict[str, Any], list[dict[str, Any]]]:
    row = task_row or {}
    if str(row.get("type") or item.get("type") or "") != "repository_hygiene_integration":
        return [], {}, []

    issues: list[str] = []
    commands: list[dict[str, Any]] = []
    expected_head = valid_source_head_sha(
        row.get("repository_hygiene_head_sha")
        or item.get("repository_hygiene_head_sha")
    )
    if not expected_head:
        return ["repository hygiene exact head SHA is missing"], {}, commands

    branch_head_result = run(["git", "rev-parse", resolved_branch], worktree)
    commands.append(branch_head_result)
    branch_head = valid_source_head_sha(branch_head_result.get("stdout"))
    if branch_head_result.get("exit_code") != 0 or not branch_head:
        issues.append("repository hygiene source branch head is unavailable")
    elif branch_head != expected_head:
        issues.append("repository hygiene source branch no longer matches the recorded exact head")

    numbers = source_pr_numbers(item, row)
    leaf_value = row.get("github_pr") or item.get("github_pr") or (numbers[-1] if numbers else None)
    try:
        leaf_pr = int(leaf_value or 0)
    except (TypeError, ValueError):
        leaf_pr = 0
    if leaf_pr <= 0:
        issues.append("repository hygiene source PR number is missing")
        return issues, {"expected_head": expected_head}, commands

    view = run(
        [
            "gh",
            "pr",
            "view",
            str(leaf_pr),
            "--json",
            "number,state,isDraft,baseRefName,headRefName,headRefOid,mergeStateStatus,statusCheckRollup",
        ],
        worktree,
    )
    commands.append(view)
    if view.get("exit_code") != 0:
        issues.append("repository hygiene live required-CI lookup failed")
        return issues, {"pr": leaf_pr, "expected_head": expected_head}, commands
    try:
        payload = json.loads(str(view.get("stdout") or "{}"))
    except (TypeError, ValueError, json.JSONDecodeError):
        issues.append("repository hygiene live required-CI payload is invalid")
        return issues, {"pr": leaf_pr, "expected_head": expected_head}, commands

    state = str(payload.get("state") or "").upper()
    head = valid_source_head_sha(payload.get("headRefOid"))
    target = normalized_target_branch(payload.get("baseRefName"))
    expected_target = normalized_target_branch(base_ref)
    checks = payload.get("statusCheckRollup")
    if not isinstance(checks, list):
        checks = []
    normalized_checks = [
        {
            "name": str(check.get("name") or check.get("context") or ""),
            "status": str(
                check.get("status")
                or ("COMPLETED" if check.get("state") else "")
            ).upper(),
            "conclusion": str(check.get("conclusion") or check.get("state") or "").upper(),
        }
        for check in checks
        if isinstance(check, dict)
    ]
    ci_green = bool(normalized_checks) and all(
        check["status"] == "COMPLETED" and check["conclusion"] == "SUCCESS"
        for check in normalized_checks
    )
    if state != "OPEN":
        issues.append(f"repository hygiene source PR is not open: {state or 'UNKNOWN'}")
    if payload.get("isDraft") is True:
        issues.append("repository hygiene source PR is draft")
    if head != expected_head:
        issues.append("repository hygiene live PR head does not match the recorded exact head")
    if target != expected_target:
        issues.append(
            f"repository hygiene live PR target {target!r} does not match Integrator base {expected_target!r}"
        )
    if not normalized_checks:
        issues.append("repository hygiene exact-head required CI is missing")
    elif not ci_green:
        issues.append("repository hygiene exact-head required CI is not complete and green")

    evidence = {
        "pr": leaf_pr,
        "state": state,
        "draft": payload.get("isDraft") is True,
        "target": target,
        "head": head,
        "expected_head": expected_head,
        "merge_state": str(payload.get("mergeStateStatus") or "").upper(),
        "ci_green": ci_green,
        "checks": normalized_checks,
    }
    return issues, evidence, commands


def source_pr_close_retry_rows(task_rows: dict[str, Any] | None, base_ref: str) -> dict[str, dict[str, Any]]:
    if not isinstance(task_rows, dict):
        return {}
    rows = task_rows.get("tasks")
    if not isinstance(rows, list):
        return {}
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        task_id = str(row.get("id") or row.get("task_id") or "").strip()
        if not task_id or str(row.get("type") or "") != "repository_hygiene_integration":
            continue
        integration_status = str(row.get("integration_status") or "")
        coordination_only = integration_status == "needs_coordination_source_pr_close"
        if integration_status not in {"needs_source_pr_close", "needs_coordination_source_pr_close"}:
            continue
        if str(row.get("next_owner") or "").strip().lower() not in {"integrator", "auto-integrator", "auto_integrator"}:
            continue
        if int(row.get("source_pr_close_retry_count") or 0) >= 3:
            continue
        merge_commit = str(row.get("merge_commit") or "").strip()
        if not source_pr_numbers(row, row):
            continue
        if coordination_only and not (
            row.get("classification_recheck") is True
            and str(row.get("source_pr_close_mode") or "") == "coordination_only"
            and not row.get("integration_changed_paths")
            and bool(row.get("coordination_changed_paths"))
            and re.fullmatch(r"[0-9a-fA-F]{40}", str(row.get("repository_hygiene_head_sha") or ""))
        ):
            continue
        if not coordination_only and not merge_commit:
            continue
        if integration_target_gate_issues(base_ref, row):
            continue
        result[task_id] = row
    return result


def close_source_pull_requests(
    worktree: Path,
    ready_commits: dict[str, str],
    ready_metadata: dict[str, dict[str, Any]],
    task_rows: dict[str, Any] | None,
    results: list[dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    reports: dict[str, dict[str, Any]] = {}
    for task_id, commit_sha in ready_commits.items():
        item = ready_metadata.get(task_id) or {}
        task_row = _load_task_row(task_rows, task_id)
        numbers = source_pr_numbers(item, task_row)
        if not numbers:
            continue
        closed: list[int] = []
        failures: list[dict[str, Any]] = []
        coordination_only = str(
            item.get("integration_status")
            or (task_row or {}).get("integration_status")
            or ""
        ) == "needs_coordination_source_pr_close"
        for number in numbers:
            comment = (
                "Closed by AiStudio auto-integrator after repeated exact-head "
                f"coordination-only classification against develop {commit_sha}; "
                "no product payload required integration."
                if coordination_only
                else f"Integrated by AiStudio auto-integrator as commit {commit_sha} on develop."
            )
            close = run(
                [
                    "gh",
                    "pr",
                    "close",
                    str(number),
                    "--comment",
                    comment,
                ],
                worktree,
            )
            results.append(close)
            if close["exit_code"] != 0:
                failures.append({"pr": number, "reason": short(close) or "gh pr close failed"})
                continue
            verify = run(["gh", "pr", "view", str(number), "--json", "state", "--jq", ".state"], worktree)
            results.append(verify)
            if verify["exit_code"] == 0 and str(verify.get("stdout") or "").strip().upper() == "CLOSED":
                closed.append(number)
            else:
                failures.append({"pr": number, "reason": short(verify) or "source PR did not report CLOSED"})
        reports[task_id] = {
            "status": "closed" if not failures and len(closed) == len(numbers) else "failed",
            "pr_numbers": numbers,
            "closed_pr_numbers": closed,
            "failures": failures,
            "checked_at": utc_now(),
        }
    return reports


def task_keys(item: dict[str, Any]) -> list[str]:
    values = [str(v) for v in item.get("task_ids") or [] if str(v or "").strip()]
    if values:
        return values
    task_id = str(item.get("task_id") or item.get("source_artifact_id") or "").strip()
    return [task_id] if task_id else []


def task_id_key(value: Any) -> str:
    return str(value or "").strip().lower()


def primary_key(item: dict[str, Any]) -> str:
    keys = task_keys(item)
    return keys[0] if keys else str(item.get("branch") or item.get("head_sha") or "unknown")


def normalize_path(value: Any) -> str:
    path = str(value or "").strip().replace("\\", "/")
    while len(path) >= 2 and path[0] == path[-1] and path[0] in {"'", '"'}:
        path = path[1:-1].strip()
    return path


def _normalize_task_id(value: Any) -> str:
    return str(value or "").strip()


def _load_task_rows(project_root: Path) -> dict[str, Any] | None:
    queue_path = task_manager_dir(project_root) / "task_queue.json"
    if not queue_path.exists():
        return None
    try:
        return json.loads(queue_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _load_task_row(task_rows: dict[str, Any] | None, task_id: str) -> dict[str, Any] | None:
    if not isinstance(task_rows, dict):
        return None
    tasks = task_rows.get("tasks")
    if not isinstance(tasks, list):
        return None
    normalized = _normalize_task_id(task_id).upper()
    for row in tasks:
        if not isinstance(row, dict):
            continue
        if _normalize_task_id(row.get("id")).upper() == normalized:
            return row
    return None


def _task_report_paths(item: dict[str, Any], task_row: dict[str, Any] | None) -> list[str]:
    candidates: list[str] = []
    sources = (item, task_row)
    for source in sources:
        if not isinstance(source, dict):
            continue
        for key in ("worker_report", "worker_report_path", "worker_result_report"):
            path = _normalize_task_id(source.get(key))
            if path:
                candidates.append(path)
        imported = source.get("imported_worker_reports")
        if isinstance(imported, list):
            for path in imported:
                path_value = _normalize_task_id(path)
                if path_value:
                    candidates.append(path_value)
    return list(dict.fromkeys(candidates))


def _documentation_version_gate_issues(task_root: Path, base_branch: str, task_row: dict[str, Any] | None, item: dict[str, Any]) -> list[str]:
    task_id = _normalize_task_id(primary_key(item))
    if not task_row or not task_id or not task_requires_documentation_impact(task_row):
        return []

    changed_paths = sorted({normalize_path(path) for path in (item.get("changed_paths") or []) if normalize_path(path)})
    loaded_report = False
    impact = ""
    invalid_impacts: list[str] = []
    for report_path in _task_report_paths(item, task_row):
        candidate = task_root / report_path
        if not candidate.exists():
            continue
        try:
            report_text = candidate.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            report_text = ""
        try:
            report_payload = json.loads(report_text)
        except json.JSONDecodeError:
            report_payload = None
        loaded_report = True
        current_impact = documentation_impact_from_report(report_payload, report_text)
        if not current_impact:
            continue
        if current_impact not in DOCUMENTATION_IMPACT_VALUES:
            invalid_impacts.append(current_impact)
            continue
        impact = current_impact
        break

    if not loaded_report:
        return ["documentation impact report missing for documentation-gated task"]

    if not impact:
        if invalid_impacts:
            return [f"documentation_impact invalid: {invalid_impacts[0]}"]
        return ["documentation_impact missing"]
    if impact in DOCUMENTATION_IMPACT_BLOCKING:
        return ["documentation_impact is blocked_missing_docs"]

    reasons: list[str] = []
    doc_report = documentation_impact_checker.build_report(task_root, changed_paths=changed_paths)
    if doc_report.get("release_blocking"):
        for item in doc_report.get("errors", []):
            reasons.append(f"{item.get('code')}: {item.get('message')}")

    version_report = project_version_gate.validate_version(
        task_root,
        expected_branch_role=_normalize_task_id(base_branch).removeprefix("origin/") or "develop",
        require=True,
    )
    for item in version_report.get("errors", []):
        reasons.append(f"{item.get('message')}")

    if version_report.get("version") and isinstance(version_report["version"], dict):
        version = version_report["version"]
        for field in ("project_index", "documentation_manifest"):
            rel = _normalize_task_id(version.get(field))
            if not rel:
                reasons.append(f"PROJECT_VERSION.json missing required field {field}")
                continue
            if not (task_root / rel).is_file():
                reasons.append(f"{field} file is missing: {rel}")

    return reasons


def item_paths(item: dict[str, Any]) -> list[str]:
    paths = item.get("changed_paths") or item.get("integration_changed_paths") or []
    return sorted({path for path in (normalize_path(p) for p in paths) if path})


def item_policy_code_refs(item: dict[str, Any]) -> list[str]:
    policy = item.get("migration_compatibility_policy")
    if not isinstance(policy, dict):
        return []
    refs = policy.get("code_refs") or []
    if not isinstance(refs, list):
        return []
    return sorted({path for path in (normalize_path(p) for p in refs) if path})


def is_migration_path(path: str) -> bool:
    normalized = normalize_path(path).lower()
    return "/migrations/" in f"/{normalized}" and normalized.endswith(".py") and not normalized.endswith("/__init__.py")


def item_is_migration_sensitive(item: dict[str, Any]) -> bool:
    if item.get("migration_sensitive") is True:
        return True
    return any(is_migration_path(path) for path in [*item_paths(item), *item_policy_code_refs(item)])







CODE_BEHAVIOR_EXTENSIONS = {
    ".py",
    ".js",
    ".jsx",
    ".ts",
    ".tsx",
    ".go",
    ".rs",
    ".java",
    ".kt",
    ".swift",
    ".cs",
    ".php",
    ".rb",
    ".sh",
    ".ps1",
}

LINE_PRESERVATION_PATHS = {
    ".cbmignore",
    ".dockerignore",
    ".gitignore",
}
UNION_CONFLICT_PATHS = {"CHANGELOG.md"}

GENERIC_METHOD_BEHAVIOR_PATTERN = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|static|final|open|override|abstract|suspend)\s+)*"
    r"\S[A-Za-z0-9_<>,.?\[\]\s]*\s+([A-Za-z_]\w*)\s*\("
)

BEHAVIOR_PATTERNS = (
    re.compile(r"^\s*(?:async\s+)?def\s+([A-Za-z_]\w*)\s*\("),
    re.compile(r"^\s*class\s+([A-Za-z_]\w*)\b"),
    re.compile(r"^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\("),
    re.compile(r"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?\("),
    re.compile(r"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?function\b"),
    GENERIC_METHOD_BEHAVIOR_PATTERN,
    re.compile(r"^\s*@(?:app|router|bp)\.(?:get|post|put|patch|delete|route)\(([^)]*)\)"),
    re.compile(r"^\s*(?:app|router)\.(?:get|post|put|patch|delete|use)\(([^,)]*)"),
    re.compile(r"\b(?:callback_data|command|text|url|endpoint|path)\s*=\s*[\"']([^\"']{2,120})[\"']"),
    re.compile(r"[\"'](/[^\"'\s]{1,120})[\"']"),
)

def is_code_behavior_path(path: str) -> bool:
    normalized = normalize_path(path)
    if not normalized:
        return False
    return Path(normalized).suffix.lower() in CODE_BEHAVIOR_EXTENSIONS


def is_line_preservation_path(path: str) -> bool:
    return normalize_path(path).lower() in LINE_PRESERVATION_PATHS


def line_contract_tokens(text: str) -> list[str]:
    return sorted(
        {
            line.strip()
            for line in text.replace("\r\n", "\n").replace("\r", "\n").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
    )


def read_text_file(path: Path) -> str:
    try:
        raw = path.read_bytes()
    except OSError:
        return ""
    if b"\0" in raw:
        return ""
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return ""


def behavior_patterns_for_path(rel_path: str | None) -> tuple[re.Pattern[str], ...]:
    if Path(str(rel_path or "")).suffix.lower() == ".py":
        return tuple(pattern for pattern in BEHAVIOR_PATTERNS if pattern is not GENERIC_METHOD_BEHAVIOR_PATTERN)
    return BEHAVIOR_PATTERNS


def behavior_tokens(text: str, rel_path: str | None = None) -> set[str]:
    tokens: set[str] = set()
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("//"):
            continue
        for pattern in behavior_patterns_for_path(rel_path):
            match = pattern.search(line)
            if not match:
                continue
            token = str(match.group(1)).strip().strip("\"'")
            if token and len(token) <= 140:
                tokens.add(token)
    return tokens


def behavior_block_pattern() -> re.Pattern[str]:
    return re.compile(r"^(?P<indent>\s*)(?P<header>(?:async\s+)?def\s+(?P<py>[A-Za-z_]\w*)\s*\(|class\s+(?P<class>[A-Za-z_]\w*)\b)")


def top_level_behavior_blocks(text: str) -> dict[str, str]:
    lines = text.splitlines()
    blocks: dict[str, str] = {}
    pattern = behavior_block_pattern()
    index = 0
    while index < len(lines):
        match = pattern.search(lines[index])
        if not match or match.group("indent"):
            index += 1
            continue
        name = match.group("py") or match.group("class")
        start = index
        while start > 0 and lines[start - 1].lstrip().startswith("@"):
            start -= 1
        end = index + 1
        while end < len(lines):
            next_match = pattern.search(lines[end])
            if next_match and not next_match.group("indent"):
                break
            end += 1
        block = "\n".join(lines[start:end]).strip()
        if name and block:
            blocks[name] = block + "\n"
        index = end
    return blocks


def behavior_snapshot(worktree: Path, paths: list[str]) -> dict[str, list[str]]:
    snapshot: dict[str, list[str]] = {}
    for rel_path in paths:
        if is_line_preservation_path(rel_path):
            tokens = line_contract_tokens(read_text_file(worktree / rel_path))
            if tokens:
                snapshot[rel_path] = tokens
            continue
        if not is_code_behavior_path(rel_path):
            continue
        tokens = sorted(behavior_tokens(read_text_file(worktree / rel_path), rel_path))
        if tokens:
            snapshot[rel_path] = tokens
    return snapshot


def text_at_ref(worktree: Path, ref: str, rel_path: str) -> str:
    result = run(["git", "show", f"{ref}:{rel_path}"], worktree)
    return str(result.get("stdout") or "") if result.get("exit_code") == 0 else ""


def apply_candidate_delta(
    worktree: Path,
    base_ref: str,
    candidate_ref: str,
    paths: list[str],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    commands: list[dict[str, Any]] = []
    merge_base = run(["git", "merge-base", base_ref, candidate_ref], worktree)
    commands.append(merge_base)
    ancestor = str(merge_base.get("stdout") or "").strip()
    if merge_base.get("exit_code") != 0 or not ancestor:
        return {"ok": False, "reason": "candidate_merge_base_missing"}, commands

    diff_cmd = ["git", "diff", "--binary", "--full-index", ancestor, candidate_ref, "--", *paths]
    diff_proc = subprocess.run(diff_cmd, cwd=worktree, capture_output=True, check=False)
    diff_result = {
        "command": diff_cmd,
        "cwd": str(worktree),
        "exit_code": diff_proc.returncode,
        "stdout": "",
        "stderr": diff_proc.stderr.decode("utf-8", errors="replace"),
        "patch_bytes": len(diff_proc.stdout),
    }
    commands.append(diff_result)
    if diff_proc.returncode != 0:
        return {"ok": False, "reason": "candidate_diff_failed"}, commands
    if not diff_proc.stdout:
        return {"ok": False, "reason": "candidate_diff_empty"}, commands

    apply_result = run_with_input(
        ["git", "apply", "--3way", "--index", "--whitespace=nowarn", "-"],
        worktree,
        diff_proc.stdout,
    )
    commands.append(apply_result)
    if apply_result.get("exit_code") != 0:
        conflict_resolution, resolution_commands = resolve_union_conflicts(worktree)
        commands.extend(resolution_commands)
        if conflict_resolution.get("ok") is True:
            return {
                "ok": True,
                "reason": "candidate_delta_applied_with_union_conflict_resolution",
                "merge_base": ancestor,
                "patch_bytes": len(diff_proc.stdout),
                "conflict_resolution": conflict_resolution,
            }, commands
        return {"ok": False, "reason": "candidate_three_way_apply_failed"}, commands
    return {
        "ok": True,
        "reason": "candidate_delta_applied",
        "merge_base": ancestor,
        "patch_bytes": len(diff_proc.stdout),
    }, commands


def resolve_union_conflicts(worktree: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    commands: list[dict[str, Any]] = []
    unmerged = run(["git", "diff", "--name-only", "--diff-filter=U"], worktree)
    commands.append(unmerged)
    conflict_paths = sorted(path for path in str(unmerged.get("stdout") or "").splitlines() if path)
    if (
        unmerged.get("exit_code") != 0
        or not conflict_paths
        or any(path not in UNION_CONFLICT_PATHS for path in conflict_paths)
    ):
        return {
            "ok": False,
            "reason": "union_conflict_scope_not_allowed",
            "conflict_paths": conflict_paths,
        }, commands

    resolved: list[str] = []
    with tempfile.TemporaryDirectory(prefix="integrator-union-") as temp_dir:
        temp_root = Path(temp_dir)
        for index, rel_path in enumerate(conflict_paths):
            stage_paths: dict[int, Path] = {}
            for stage in (1, 2, 3):
                proc = subprocess.run(
                    ["git", "show", f":{stage}:{rel_path}"],
                    cwd=worktree,
                    capture_output=True,
                    check=False,
                )
                result = {
                    "command": ["git", "show", f":{stage}:{rel_path}"],
                    "cwd": str(worktree),
                    "exit_code": proc.returncode,
                    "stdout": "",
                    "stderr": proc.stderr.decode("utf-8", errors="replace"),
                    "content_bytes": len(proc.stdout),
                }
                commands.append(result)
                if proc.returncode != 0:
                    return {
                        "ok": False,
                        "reason": "union_conflict_stage_missing",
                        "path": rel_path,
                        "stage": stage,
                    }, commands
                stage_path = temp_root / f"{index}-{stage}.txt"
                stage_path.write_bytes(proc.stdout)
                stage_paths[stage] = stage_path

            merge = subprocess.run(
                [
                    "git",
                    "merge-file",
                    "--union",
                    str(stage_paths[2]),
                    str(stage_paths[1]),
                    str(stage_paths[3]),
                ],
                cwd=worktree,
                capture_output=True,
                check=False,
            )
            commands.append(
                {
                    "command": ["git", "merge-file", "--union", rel_path],
                    "cwd": str(worktree),
                    "exit_code": merge.returncode,
                    "stdout": merge.stdout.decode("utf-8", errors="replace"),
                    "stderr": merge.stderr.decode("utf-8", errors="replace"),
                }
            )
            if merge.returncode != 0:
                return {
                    "ok": False,
                    "reason": "union_conflict_merge_failed",
                    "path": rel_path,
                }, commands
            (worktree / rel_path).write_bytes(stage_paths[2].read_bytes())
            add = run(["git", "add", "--", rel_path], worktree)
            commands.append(add)
            if add.get("exit_code") != 0:
                return {
                    "ok": False,
                    "reason": "union_conflict_stage_failed",
                    "path": rel_path,
                }, commands
            resolved.append(rel_path)

    remaining = run(["git", "diff", "--name-only", "--diff-filter=U"], worktree)
    commands.append(remaining)
    remaining_paths = sorted(path for path in str(remaining.get("stdout") or "").splitlines() if path)
    return {
        "ok": remaining.get("exit_code") == 0 and not remaining_paths,
        "reason": "union_conflicts_resolved" if not remaining_paths else "union_conflicts_remain",
        "resolved_paths": resolved,
        "remaining_paths": remaining_paths,
    }, commands


def test_behavior_name_tokens(name: str) -> set[str]:
    return {
        token
        for token in re.split(r"[^a-z0-9]+", name.lower())
        if token and token not in {"test", "tests", "when", "with", "and", "or", "the"}
    }


def replaced_test_behaviors(
    rel_path: str,
    missing: set[str],
    added: set[str],
) -> set[str]:
    normalized = normalize_path(rel_path).lower()
    if not (
        normalized.startswith(("tests/", "test/"))
        or Path(normalized).name.startswith("test_")
    ):
        return set()

    available = {name for name in added if name.startswith("test_")}
    replaced: set[str] = set()
    for old_name in sorted(name for name in missing if name.startswith("test_")):
        old_tokens = test_behavior_name_tokens(old_name)
        matches = sorted(
            (
                (len(old_tokens & test_behavior_name_tokens(new_name)), new_name)
                for new_name in available
            ),
            reverse=True,
        )
        if matches and matches[0][0] >= 2:
            replaced.add(old_name)
            available.remove(matches[0][1])
    return replaced


def detect_behavior_regression(before: dict[str, list[str]], after: dict[str, list[str]]) -> dict[str, Any]:
    lost: dict[str, list[str]] = {}
    after_tokens_global = {
        token
        for tokens in after.values()
        for token in tokens
    }
    for rel_path, tokens in before.items():
        before_tokens = set(tokens)
        after_tokens = set(after.get(rel_path, []))
        missing_tokens = before_tokens - after_tokens
        # Refactors may move a class or method to another changed file without
        # removing its behavior. The snapshot only contains candidate paths, so
        # a token found elsewhere in `after` is evidence of an in-scope move.
        missing_tokens -= after_tokens_global
        missing_tokens -= replaced_test_behaviors(
            rel_path,
            missing_tokens,
            after_tokens - before_tokens,
        )
        missing = sorted(missing_tokens)
        if missing:
            lost[rel_path] = missing[:25]
    return {
        "ok": not lost,
        "lost_behavior": lost,
        "lost_count": sum(len(values) for values in lost.values()),
    }



def attempt_behavior_preserving_merge(
    worktree: Path,
    paths: list[str],
    before_texts: dict[str, str],
    candidate_texts: dict[str, str],
    before_snapshot: dict[str, list[str]],
) -> dict[str, Any]:
    repaired: list[dict[str, Any]] = []
    for rel_path in paths:
        before_text = before_texts.get(rel_path, "")
        candidate_text = candidate_texts.get(rel_path, "")
        if not before_text or not candidate_text:
            continue
        if is_line_preservation_path(rel_path):
            before_lines = before_text.replace("\r\n", "\n").replace("\r", "\n").splitlines()
            existing = {line.strip() for line in before_lines if line.strip()}
            additions = [
                line.rstrip()
                for line in candidate_text.replace("\r\n", "\n").replace("\r", "\n").splitlines()
                if line.strip() and line.strip() not in existing
            ]
            merged_lines = [line.rstrip() for line in before_lines]
            if additions:
                if merged_lines and merged_lines[-1]:
                    merged_lines.append("")
                merged_lines.extend(additions)
            merged = "\n".join(merged_lines).rstrip() + "\n"
            (worktree / rel_path).write_text(merged, encoding="utf-8")
            repaired.append({"path": rel_path, "added_behavior": additions})
            continue
        existing_tokens = set(before_snapshot.get(rel_path, []))
        before_text = before_text.replace("\r\n", "\n").replace("\r", "\n")
        candidate_text = candidate_text.replace("\r\n", "\n").replace("\r", "\n")
        candidate_blocks = top_level_behavior_blocks(candidate_text)
        additions = {
            name: block
            for name, block in candidate_blocks.items()
            if name not in existing_tokens and block.strip() and block.strip() not in before_text
        }
        if not additions:
            continue
        merged = before_text.rstrip() + "\n\n" + "\n\n".join(block.rstrip() for block in additions.values()) + "\n"
        (worktree / rel_path).write_text(merged, encoding="utf-8")
        repaired.append({"path": rel_path, "added_behavior": sorted(additions)})
    return {"ok": bool(repaired), "repaired": repaired}


def repair_text_whitespace(worktree: Path, paths: list[str]) -> list[str]:
    repaired: list[str] = []
    for rel_path in paths:
        path = worktree / rel_path
        if not path.is_file():
            continue
        try:
            raw = path.read_bytes()
        except OSError:
            continue
        if b"\0" in raw:
            continue
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        normalized = text.replace("\r\n", "\n")
        lines = [line.rstrip(" \t") for line in normalized.split("\n")]
        while lines and lines[-1] == "":
            lines.pop()
        fixed = ("\n".join(lines) + "\n") if lines else ""
        if fixed != normalized:
            path.write_text(fixed, encoding="utf-8")
            repaired.append(rel_path)
    return repaired


def merge_limit(value: Any, fallback: int) -> int:
    limit = int(value or 0)
    return limit if limit > 0 else fallback


def is_direct_merge_candidate(item: dict[str, Any]) -> bool:
    if any(key.startswith("SRC-") for key in task_keys(item)):
        return False
    if item_is_migration_sensitive(item):
        return False
    classification = str(item.get("classification") or "ready_candidate")
    if classification == "ready_candidate":
        return True
    if classification != "needs_integrator_review":
        return False

    risk_class = str(item.get("risk_class") or "medium").lower()
    if risk_class in {"high", "critical"}:
        return False

    blocker_type = str(item.get("readiness_blocker_type") or "integrator_review")
    if blocker_type != "integrator_review":
        return False

    payload_status = str(item.get("code_payload_status") or "unverified_candidate")
    if payload_status != "unverified_candidate":
        return False

    reason = str(item.get("reason") or "").lower()
    hard_blockers = ("high-risk", "needs human", "conflict", "dirty branch", "missing branch")
    return not any(blocker in reason for blocker in hard_blockers)


def eligible_items(batch: dict[str, Any], already_finalized: set[str]) -> list[dict[str, Any]]:
    return [
        i for i in batch.get("included") or []
        if isinstance(i, dict)
        and primary_key(i) not in already_finalized
        and is_direct_merge_candidate(i)
    ]


def non_direct_merge_routes(batch: dict[str, Any], already_finalized: set[str]) -> list[dict[str, Any]]:
    routed: list[dict[str, Any]] = []
    for item in batch.get("included") or []:
        if not isinstance(item, dict):
            continue
        task_id = primary_key(item)
        if task_id.startswith("SRC-") or task_id in already_finalized or is_direct_merge_candidate(item):
            continue
        if item_is_migration_sensitive(item):
            routed.append({
                "task_id": task_id,
                "next_owner": "integrator",
                "reason": "migration-sensitive item requires integration package migration checks instead of direct merge",
            })
            continue
        classification = str(item.get("classification") or "")
        reason = str(item.get("reason") or "not eligible for direct merge")
        if classification == "needs_integrator_review":
            routed.append({"task_id": task_id, "next_owner": "human", "reason": f"manual integrator review required: {reason}"})
        else:
            routed.append({"task_id": task_id, "next_owner": "dispatcher", "reason": f"not a direct-merge candidate: {reason}"})
    return routed


def excluded_batch_routes(batch: dict[str, Any], already_finalized: set[str]) -> list[dict[str, Any]]:
    routed: list[dict[str, Any]] = []
    finalized = {task_id_key(value) for value in already_finalized}
    for exclusion_item in batch.get("excluded") or []:
        if not isinstance(exclusion_item, dict):
            continue
        item = exclusion_item.get("item")
        if not isinstance(item, dict):
            item = exclusion_item.get("event")
        if not isinstance(item, dict):
            continue
        route = str(exclusion_item.get("route") or "").strip()
        if route in {"", "cleanup_candidates", "duplicate", "deferred_next_batch", "excluded_from_package"}:
            continue
        task_ids = [task_id for task_id in task_keys(item) if task_id_key(task_id) not in finalized]
        if not task_ids:
            continue
        reason = str(exclusion_item.get("reason") or item.get("reason") or f"batch exclusion routed to {route}")
        if route == "needs_worker_fix":
            next_owner = "worker_pool"
        elif route in {"needs_human", "blocked"}:
            next_owner = "human"
        else:
            next_owner = "dispatcher"
        for task_id in task_ids:
            routed.append({"task_id": task_id, "next_owner": next_owner, "reason": reason, "route": route})
    return routed


def worker_bridge_items(plans: Path, already_finalized: set[str]) -> list[dict[str, Any]]:
    path = plans / "integration_candidates.json"
    if not path.exists():
        return []
    data = load_json(path)
    candidates = data.get("candidates") if isinstance(data, dict) else []
    return [
        item for item in candidates or []
        if isinstance(item, dict)
        and primary_key(item) not in already_finalized
        and is_direct_merge_candidate(item)
        and normalize_branch(item.get("branch"))
        and item_paths(item)
    ]


def finalization_recorded_ids(events_path: Path) -> set[str]:
    if not events_path.exists():
        return set()
    recorded: set[str] = set()
    for line in events_path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        event_name = str(event.get("event") or "")
        task_id = str(event.get("task_id") or event.get("canonical_target_id") or "").strip()
        if not task_id:
            continue
        if event_name in {"integration_invalidated", "finalization_invalidated"}:
            recorded.discard(task_id)
            if task_id.startswith("task:"):
                recorded.discard(task_id.removeprefix("task:"))
            else:
                recorded.discard(f"task:{task_id}")
        elif event_name == "finalization_recorded":
            recorded.add(task_id)
            if task_id.startswith("task:"):
                recorded.add(task_id.removeprefix("task:"))
        elif event_name == "integration_routed" and task_id.startswith("SRC-"):
            recorded.add(task_id)
    return recorded

def terminal_queue_ids(plans: Path) -> set[str]:
    queue_path = plans / "task_queue.json"
    if not queue_path.exists():
        return set()
    data = load_json(queue_path)
    tasks = data.get("tasks") if isinstance(data, dict) else None
    if not isinstance(tasks, list):
        return set()
    result: set[str] = set()
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        if not task_id:
            continue
        if str(task.get("status") or "") in TERMINAL_TASK_STATUSES or str(task.get("integration_status") or "") in TERMINAL_INTEGRATION_STATUSES:
            result.add(task_id)
    return result


def _current_worker_report(task: dict[str, Any], metadata: dict[str, Any]) -> str:
    for source in (task, metadata):
        for field in ("worker_changed_paths", "changed_paths"):
            paths = source.get(field)
            if not isinstance(paths, list):
                continue
            reports = sorted(
                {
                    normalize_path(path)
                    for path in paths
                    if normalize_path(path).startswith("docs/reports/workers/")
                    and Path(normalize_path(path)).name.startswith("WORKER_RESULT_")
                }
            )
            if reports:
                return reports[-1]
    return str(task.get("worker_report") or metadata.get("worker_report") or "").strip()


def update_task_queue(
    plans: Path,
    ready_commits: dict[str, str],
    routed: list[dict[str, Any]],
    ready_metadata: dict[str, dict[str, Any]] | None = None,
    changed_route_ids: set[str] | None = None,
    source_pr_closures: dict[str, dict[str, Any]] | None = None,
) -> bool:
    queue_path = plans / "task_queue.json"
    if not queue_path.exists():
        return False
    data = load_json(queue_path)
    tasks = data.get("tasks") if isinstance(data, dict) else None
    if not isinstance(tasks, list):
        return False
    now = utc_now()
    routed_by_id = {task_id_key(item.get("task_id")): item for item in routed}
    ready_metadata = ready_metadata or {}
    source_pr_closures = source_pr_closures or {}
    changed = False
    material_fields = [
        "status",
        "integration_status",
        "finalization_status",
        "lock",
        "worker_ready",
        "dispatcher_decision",
        "next_owner",
        "next_role",
        "requires_human_attention",
        "repair_request",
        "missing_packet_fields",
        "repair_owner",
        "next_action",
        "not_worker_ready_reason",
        "merge_commit",
        "branch",
        "github_branch",
        "synced_from_worker_branch",
        "worker_result_commit",
        "worker_report",
        "imported_worker_reports",
        "source_pr_close_status",
        "source_pr_closed_numbers",
        "source_pr_close_failures",
        "source_pr_close_retry_count",
        "integration_repair_kind",
        "integration_repair_source_head_sha",
        "integration_repair_retry_count",
        "integration_repair_retry_evidence",
        "integration_repair_validation_paths",
        "integration_repair_lost_behavior",
    ]
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or "")
        normalized_task_id = task_id_key(task_id)
        if task_id in ready_commits:
            before_material = {field: task.get(field) for field in material_fields}
            before = str(task.get("status") or "")
            task["lock"] = "free"
            task["worker_ready"] = False
            metadata = ready_metadata.get(task_id) or {}
            coordination_only_close = str(metadata.get("integration_status") or "") == "needs_coordination_source_pr_close"
            if coordination_only_close:
                task["source_pr_close_base_commit"] = ready_commits[task_id]
            else:
                task["merge_commit"] = ready_commits[task_id]
            source_closure = source_pr_closures.get(task_id)
            source_close_failed = bool(source_closure and source_closure.get("status") != "closed")
            if source_closure:
                task["source_pr_close_status"] = source_closure.get("status")
                task["source_pr_closed_numbers"] = source_closure.get("closed_pr_numbers") or []
                task["source_pr_close_failures"] = source_closure.get("failures") or []
                task["source_pr_close_checked_at"] = source_closure.get("checked_at") or now
            if source_close_failed:
                retry_count = int(task.get("source_pr_close_retry_count") or 0) + 1
                task["source_pr_close_retry_count"] = retry_count
                task["integration_status"] = (
                    "needs_coordination_source_pr_close"
                    if coordination_only_close
                    else "needs_source_pr_close"
                )
                task["finalization_status"] = "blocked_source_pr_open"
                if retry_count < 3:
                    task["status"] = "integration_requested"
                    task["dispatcher_decision"] = "needs_integrator_review"
                    task["next_owner"] = "Integrator"
                    task["next_role"] = "integrator_review"
                    task["requires_human_attention"] = False
                    task["next_action"] = "Auto Integrator must retry source PR closure without reapplying the integrated payload."
                else:
                    task["status"] = "needs_human"
                    task["dispatcher_decision"] = "needs_human"
                    task["next_owner"] = "human"
                    task["next_role"] = "human"
                    task["requires_human_attention"] = True
                    task["next_action"] = "Owner or Integrator must inspect the repeated GitHub source PR closure failure."
            else:
                task["status"] = "stale_or_superseded" if coordination_only_close else "done"
                task["integration_status"] = "closed_coordination_only" if coordination_only_close else "finalized"
                task["finalization_status"] = "recorded"
                task["dispatcher_decision"] = "stale_or_superseded" if coordination_only_close else "done"
                task["next_owner"] = "none"
                task["next_role"] = "none"
                task["requires_human_attention"] = False
                task["finalized_at"] = now
                task["finalized_by"] = "auto-integrator"
                task.pop("next_action", None)
            branch = normalize_branch(metadata.get("branch"))
            if branch:
                task["branch"] = branch
                task["github_branch"] = branch
                task["synced_from_worker_branch"] = branch
            worker_result_commit = str(metadata.get("head_sha") or metadata.get("worker_result_commit") or "").strip()
            if worker_result_commit:
                task["worker_result_commit"] = worker_result_commit
            worker_report = _current_worker_report(task, metadata)
            if worker_report:
                task["worker_report"] = worker_report
            imported_reports = metadata.get("imported_worker_reports")
            if isinstance(imported_reports, list) and imported_reports:
                existing_reports = task.get("imported_worker_reports")
                if not isinstance(existing_reports, list):
                    existing_reports = []
                task["imported_worker_reports"] = sorted({str(item) for item in [*existing_reports, *imported_reports] if str(item or "").strip()})
            reason = (
                "direct integration applied but source PR closure requires an automatic retry"
                if source_close_failed
                else "direct integrator merge to develop"
            )
        elif normalized_task_id in routed_by_id:
            if str(task.get("status") or "") in TERMINAL_TASK_STATUSES or str(task.get("integration_status") or "") in TERMINAL_INTEGRATION_STATUSES:
                continue
            before_material = {field: task.get(field) for field in material_fields}
            before = str(task.get("status") or "")
            routed_item = routed_by_id[normalized_task_id]
            next_owner = str(routed_item.get("next_owner") or "dispatcher").lower()
            route = str(routed_item.get("route") or "").lower()
            reason = str(routed_item.get("reason") or "integration routed to dispatcher")
            repairable_integrator_failure = route == "needs_worker_fix" and bool(
                routed_item.get("integration_repair_kind")
            )
            needs_packet_repair = (
                str(task.get("packet_status") or "") == "needs_dispatcher_repair"
                or str(task.get("normalization_status") or "") == "needs_dispatcher_repair"
                or str(task.get("dispatcher_decision") or "") == "needs_dispatcher_repair"
            )
            worker_route = next_owner in {"worker", "worker_pool", "auto_workers", "auto-workers"}
            required_ci_wait = route == "required_ci_wait"
            if required_ci_wait:
                task["status"] = "integration_requested"
                task["integration_status"] = "pending_required_ci"
                task["dispatcher_decision"] = "integration_ready"
                task["next_owner"] = "Integrator"
                task["next_role"] = "auto_integrator"
                task["requires_human_attention"] = False
                task["worker_ready"] = False
            elif repairable_integrator_failure:
                task["status"] = "needs_dispatcher_repair"
                task["integration_status"] = "needs_worker_fix"
                task["dispatcher_decision"] = "needs_dispatcher_repair"
                task["packet_status"] = "needs_dispatcher_repair"
                task["normalization_status"] = "needs_dispatcher_repair"
                task["next_owner"] = "Dispatcher"
                task["next_role"] = "auto_dispatcher"
                task["requires_human_attention"] = False
                task["worker_ready"] = False
                task["integration_repair_kind"] = routed_item.get("integration_repair_kind")
                task["integration_repair_validation_paths"] = routed_item.get("validation_paths") or []
                task["integration_repair_lost_behavior"] = routed_item.get("lost_behavior") or {}
                source_head_sha = integration_repair_source_head_sha(task, routed_item)
                if source_head_sha:
                    task["integration_repair_source_head_sha"] = source_head_sha
                if str(task.get("type") or "") == "design-handoff-intake":
                    retry_evidence = task.get("integration_repair_retry_evidence")
                    if not isinstance(retry_evidence, list):
                        retry_evidence = []
                    retry_evidence.append({
                        "at": now,
                        "reason": reason,
                        "branch": task.get("branch") or task.get("github_branch"),
                        "worker_result_commit": task.get("worker_result_commit"),
                        "source_head_sha": source_head_sha or None,
                        "validation_paths": routed_item.get("validation_paths") or [],
                        "lost_behavior": routed_item.get("lost_behavior") or {},
                    })
                    task["integration_repair_retry_evidence"] = retry_evidence
            elif next_owner in {"integrator", "human", "owner", "manual"}:
                task["status"] = "needs_human"
                task["integration_status"] = "needs_integrator_review"
                task["dispatcher_decision"] = "needs_integrator_review"
                task["next_owner"] = "human"
                task["next_role"] = "human"
                task["requires_human_attention"] = True
                task["worker_ready"] = False
            elif worker_route and not needs_packet_repair:
                task["status"] = "planned"
                task["integration_status"] = "returned_to_worker"
                task["dispatcher_decision"] = "worker_ready"
                task["next_owner"] = "worker_pool"
                task["next_role"] = "auto_workers"
                task["requires_human_attention"] = False
                task["worker_ready"] = True
            else:
                if worker_route and needs_packet_repair:
                    task["status"] = "needs_dispatcher_repair"
                    task["integration_status"] = "needs_dispatcher_repair"
                else:
                    task["integration_status"] = "needs_dispatcher"
                task["dispatcher_decision"] = "needs_dispatcher_repair"
                task["next_owner"] = "Dispatcher"
                task["next_role"] = "auto_dispatcher"
                task["requires_human_attention"] = False
                task["worker_ready"] = False
                task["not_worker_ready_reason"] = "Dispatcher repair is required before this integration route can return to worker execution."
            task["lock"] = "free"
            task["repair_request"] = reason
            manual_review = next_owner in {"integrator", "human", "owner", "manual"} and not required_ci_wait
            worker_fix = worker_route and not needs_packet_repair
            task["missing_packet_fields"] = (
                [] if required_ci_wait else
                ["repair_worker_packet"] if repairable_integrator_failure else
                ["behavior_preservation"] if manual_review else
                ["product_payload"] if worker_fix else
                (["branch", "changed_paths"] if "missing branch or changed_paths" in reason else ["integration_packet"])
            )
            task["repair_owner"] = (
                "Integrator" if required_ci_wait else
                "Dispatcher" if repairable_integrator_failure else
                "Integrator" if manual_review else
                "Worker" if worker_fix else
                "Dispatcher"
            )
            task["next_action"] = (
                "Auto Integrator must retry after the source PR exact-head required CI is complete and green." if required_ci_wait else
                "Dispatcher must create an idempotent Worker Packet v2 that rebuilds the PR intent on current develop and resolves the recorded Integrator blocker." if repairable_integrator_failure else
                "Integrator must manually preserve current target-branch behavior and add only useful candidate changes." if manual_review else
                "Worker must produce a product-code payload or explicitly close the task as coordination-only with evidence." if worker_fix else
                "Dispatcher must rebuild or update the worker packet with a valid source branch and changed_paths before integration."
            )
        else:
            continue
        after_material = {field: task.get(field) for field in material_fields}
        if after_material == before_material:
            continue
        if normalized_task_id in routed_by_id and changed_route_ids is not None:
            changed_route_ids.add(normalized_task_id)
        history = task.get("status_history")
        if not isinstance(history, list):
            history = []
        history.append(
            {
                "at": now,
                "by": "integrator_direct_merge",
                "from": before,
                "to": task.get("status"),
                "reason": reason,
                "event": (
                    "source_pr_close_required"
                    if task_id in ready_commits and source_pr_closures.get(task_id, {}).get("status") == "failed"
                    else "direct_merge_recorded" if task_id in ready_commits else "integration_routed"
                ),
                "next_owner": (
                    task.get("next_owner")
                    if task_id in ready_commits
                    else routed_by_id.get(normalized_task_id, {}).get("next_owner", "Dispatcher")
                ),
            }
        )
        task["status_history"] = history
        changed = True
    if changed:
        write_json(queue_path, data)
    return changed


def update_agent_locks(plans: Path, ready_task_ids: set[str]) -> bool:
    if not ready_task_ids:
        return False
    locks_path = plans / "agent_locks.json"
    if not locks_path.exists():
        return False
    data = load_json(locks_path)
    locks = data.get("locks") if isinstance(data, dict) else None
    if not isinstance(locks, list):
        return False
    now = utc_now()
    changed = False
    for lock in locks:
        if not isinstance(lock, dict):
            continue
        task_id = str(lock.get("task_id") or "")
        if task_id not in ready_task_ids:
            continue
        if str(lock.get("state") or "") == "released":
            continue
        lock["previous_state"] = lock.get("state")
        lock["state"] = "released"
        lock["released_at"] = now
        lock["released_by"] = "auto-integrator"
        lock["release_reason"] = "direct integration finalized"
        changed = True
    if changed:
        write_json(locks_path, data)
    return changed


def record_state_commit(
    worktree: Path,
    project_root: Path,
    batch: dict[str, Any],
    report: dict[str, Any],
    ready_commits: dict[str, str],
    ready_metadata: dict[str, dict[str, Any]],
    routed: list[dict[str, Any]],
    results: list[dict[str, Any]],
    consumed_event_ids: list[str] | None = None,
    source_pr_closures: dict[str, dict[str, Any]] | None = None,
    source_pr_close_retry_ids: set[str] | None = None,
) -> str | None:
    plans = task_manager_dir(worktree)
    events_path = plans / "agent_events.jsonl"
    consumed_count = mark_events_consumed(events_path, consumed_event_ids or [], "auto_integrator")
    source_pr_closures = source_pr_closures or {}
    source_pr_close_retry_ids = source_pr_close_retry_ids or set()
    for task_id, commit_sha in ready_commits.items():
        is_close_retry = task_id in source_pr_close_retry_ids
        if not is_close_retry:
            append_event(events_path, build_event(project_root.name, task_id, "integration_recorded", "auto-integrator", "direct integrator merge to develop", {"merge_commit": commit_sha, "mode": "direct_to_develop", "batch_id": batch.get("batch_id")}))
        source_closure = source_pr_closures.get(task_id)
        if not source_closure or source_closure.get("status") == "closed":
            reason = "source PR close retry succeeded" if is_close_retry else "direct integrator merge to develop"
            append_event(events_path, build_event(project_root.name, task_id, "finalization_recorded", "auto-integrator", reason, {"merge_commit": commit_sha, "mode": "source_pr_close_retry" if is_close_retry else "direct_to_develop", "batch_id": batch.get("batch_id")}))
        else:
            append_event(events_path, build_event(project_root.name, task_id, "source_pr_close_required", "auto-integrator", "integrated payload is present but source PR remains open", {"merge_commit": commit_sha, "mode": "source_pr_close_retry" if is_close_retry else "direct_to_develop", "batch_id": batch.get("batch_id"), "source_pr_closure": source_closure}))
    changed_route_ids: set[str] = set()
    queue_changed = update_task_queue(plans, ready_commits, routed, ready_metadata, changed_route_ids, source_pr_closures)
    for item in routed:
        if task_id_key(item["task_id"]) not in changed_route_ids:
            continue
        payload = {"next_owner": item["next_owner"], "mode": "direct_to_develop"}
        if item.get("lost_behavior"):
            payload["lost_behavior"] = item.get("lost_behavior")
            payload["lost_count"] = item.get("lost_count")
        append_event(events_path, build_event(project_root.name, item["task_id"], "integration_routed", "auto-integrator", item["reason"], payload))
    locks_changed = update_agent_locks(plans, set(ready_commits))
    state_changed = bool(ready_commits or changed_route_ids or consumed_count or queue_changed or locks_changed)
    report["state_changed"] = state_changed
    report["changed_route_ids"] = sorted(changed_route_ids)
    if not state_changed:
        return None
    write_json(plans / "integrator_direct_merge.json", report)
    state_paths = [
        "AiStudio/Task_manager/agent_events.jsonl",
        "AiStudio/Task_manager/task_queue.json",
        "AiStudio/Task_manager/agent_locks.json",
        "AiStudio/Task_manager/integrator_direct_merge.json",
    ]
    existing_state_paths = [path for path in state_paths if (worktree / path).exists()]
    add = run(["git", "add", "-A", "--", *existing_state_paths], worktree)
    results.append(add)
    status = run(["git", "status", "--porcelain", "--", *existing_state_paths], worktree)
    results.append(status)
    if add["exit_code"] != 0 or status["exit_code"] != 0 or not str(status.get("stdout") or "").strip():
        return None
    commit = run(["git", "commit", "-m", "chore(integrator): record direct merge state"], worktree)
    results.append(commit)
    if commit["exit_code"] != 0:
        return None
    sha = run(["git", "rev-parse", "HEAD"], worktree)
    results.append(sha)
    push = run(["git", "push", "origin", "HEAD:develop"], worktree)
    results.append(push)
    if push["exit_code"] != 0:
        return None
    state_sha = (sha.get("stdout") or "").strip() or None
    if state_sha:
        report["state_commit"] = state_sha
        write_json(plans / "integrator_direct_merge.json", report)
        correction_paths = ["AiStudio/Task_manager/integrator_direct_merge.json"]
        correction_add = run(["git", "add", "-A", "--", *correction_paths], worktree)
        correction_status = run(["git", "status", "--porcelain", "--", *correction_paths], worktree)
        if correction_add["exit_code"] == 0 and correction_status["exit_code"] == 0 and str(correction_status.get("stdout") or "").strip():
            correction_commit = run(["git", "commit", "-m", "chore(integrator): record direct merge state commit"], worktree)
            if correction_commit["exit_code"] == 0:
                run(["git", "push", "origin", "HEAD:develop"], worktree)
    return state_sha


def should_write_final_report_without_state_commit(report: dict[str, Any], state_commit: str | None) -> bool:
    if state_commit:
        return False
    return bool(report.get("state_changed", True) or report.get("status") == "no_ready_items")


def clean_worktree(project_root: Path, worktree: Path, results: list[dict[str, Any]]) -> None:
    if worktree.exists():
        results.append(run(["git", "worktree", "remove", "--force", str(worktree)], project_root))
    if worktree.exists():
        shutil.rmtree(worktree)
    results.append(run(["git", "worktree", "prune"], project_root))


def default_worktree_root(project_root: Path) -> Path:
    return project_root.parent / ".agent-worktrees" / project_root.name / "integrator"


def build_event(project: str, task_id: str, event: str, role: str, reason: str, payload: dict[str, Any]) -> dict[str, Any]:
    now = utc_now()
    return {
        "schema_version": 1,
        "event_id": f"{event}-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S%f')}-{slug(task_id)}",
        "created_at": now,
        "project": project,
        "event": event,
        "role": role,
        "task_id": task_id,
        "canonical_target_id": f"task:{task_id}" if task_id and not task_id.startswith("SRC-") else task_id,
        "severity": "info" if event in {"integration_recorded", "finalization_recorded"} else "warning",
        "reason": reason,
        "payload": payload,
    }


def direct_merge(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    batch_path = Path(args.batch).resolve() if args.batch else plans / "integration_batch.json"
    report_path = plans / "integrator_direct_merge.json"
    events_path = plans / "agent_events.jsonl"
    batch_exists = batch_path.exists()
    batch = load_json(batch_path) if batch_exists else {"included": []}
    already_finalized = finalization_recorded_ids(events_path) | terminal_queue_ids(plans)
    input_source = "integration_batch"
    included = eligible_items(batch, already_finalized)
    if not included and not batch_exists:
        included = worker_bridge_items(plans, already_finalized)
        if included:
            input_source = "integration_candidates"
    max_ready = merge_limit(args.max_items, len(included))
    run_stamp = stamp()
    worktree_root = Path(args.worktree_root).expanduser().resolve() if args.worktree_root else default_worktree_root(project_root)
    worktree = worktree_root / f"direct-{run_stamp.lower()}"
    results: list[dict[str, Any]] = []
    ready: list[str] = []
    ready_metadata: dict[str, dict[str, Any]] = {}
    routed: list[dict[str, Any]] = []
    commits: list[str] = []

    if args.fetch and args.apply:
        results.append(run(["git", "fetch", "origin", "--prune"], project_root))

    task_rows = _load_task_rows(project_root)
    source_close_retry_metadata = source_pr_close_retry_rows(task_rows, args.base_ref)
    coordination_close_ids = {
        task_id
        for task_id, row in source_close_retry_metadata.items()
        if str(row.get("integration_status") or "") == "needs_coordination_source_pr_close"
    }
    coordination_base_sha = ""
    if coordination_close_ids:
        base_lookup = run(["git", "rev-parse", "--verify", f"{args.base_ref}^{{commit}}"], project_root)
        results.append(base_lookup)
        if base_lookup["exit_code"] == 0:
            coordination_base_sha = str(base_lookup.get("stdout") or "").strip()
    source_close_retry_commits = {
        task_id: (
            coordination_base_sha
            if task_id in coordination_close_ids
            else str(row.get("merge_commit") or "").strip()
        )
        for task_id, row in source_close_retry_metadata.items()
        if task_id not in coordination_close_ids or re.fullmatch(r"[0-9a-fA-F]{40}", coordination_base_sha)
    }

    if not included and not source_close_retry_commits:
        if args.apply:
            mark_events_consumed(events_path, getattr(args, "consume_event_id", []), "auto_integrator")
        routed = non_direct_merge_routes(batch, already_finalized) if input_source == "integration_batch" else []
        if input_source == "integration_batch":
            routed.extend(excluded_batch_routes(batch, already_finalized))
        queue_changed = False
        changed_route_ids: set[str] = set()
        if routed and args.apply:
            queue_changed = update_task_queue(plans, {}, routed, changed_route_ids=changed_route_ids)
            if queue_changed:
                for item in routed:
                    if task_id_key(item["task_id"]) not in changed_route_ids:
                        continue
                    append_event(events_path, build_event(project_root.name, item["task_id"], "integration_routed", "auto-integrator", item["reason"], {"next_owner": item["next_owner"], "mode": "direct_to_develop"}))
        status = "routed_no_direct_merge_candidates" if routed else "no_candidates"
        report = {"schema_version": 1, "created_at": utc_now(), "status": status, "input_source": input_source, "batch_exists": batch_exists, "ready": [], "routed": routed, "queue_changed": queue_changed, "changed_route_ids": sorted(changed_route_ids), "command_results": results}
        if args.apply:
            write_json(report_path, report)
        return report

    if not args.apply:
        report = {
            "schema_version": 1,
            "created_at": utc_now(),
            "status": "dry_run",
            "input_source": input_source,
            "batch_exists": batch_exists,
            "selected_count": min(len(included), max_ready),
            "candidate_count": len(included),
            "source_pr_close_retry_count": len(source_close_retry_commits),
            "source_pr_close_retry_task_ids": sorted(source_close_retry_commits),
            "ready": [],
            "routed": [],
            "command_results": results,
        }
        return report

    clean_worktree(project_root, worktree, results)
    worktree.parent.mkdir(parents=True, exist_ok=True)
    results.append(run(["git", "worktree", "add", "-B", f"integrator/direct/{run_stamp}", str(worktree), args.base_ref], project_root))
    if results[-1]["exit_code"] != 0:
        report = {"schema_version": 1, "created_at": utc_now(), "status": "blocked", "reason": "worktree_add_failed", "ready": [], "routed": [], "command_results": results}
        write_json(report_path, report)
        return report

    base_sha = run(["git", "rev-parse", "HEAD"], worktree)
    results.append(base_sha)
    base = (base_sha.get("stdout") or "").strip()

    source_pr_closures = close_source_pull_requests(
        worktree,
        source_close_retry_commits,
        source_close_retry_metadata,
        task_rows,
        results,
    ) if source_close_retry_commits else {}

    for item in included:
        if len(ready) >= max_ready:
            break
        task_id = primary_key(item)
        branch = normalize_branch(item.get("branch"))
        paths = item_paths(item)
        if not branch or not paths:
            routed.append({"task_id": task_id, "next_owner": "dispatcher", "reason": "missing branch or changed_paths"})
            continue
        task_row = _load_task_row(task_rows, task_id) if task_id else None
        repair_source_head_sha = integration_repair_source_head_sha(task_row, item)
        repairable_worker_failure = is_repairable_integrator_failure_task(task_row, item)
        gate_issues = [
            *integration_target_gate_issues(args.base_ref, task_row),
            *_documentation_version_gate_issues(project_root, args.base_ref, task_row, item),
        ]
        if gate_issues:
            routed.append({
                "task_id": task_id,
                "next_owner": "integrator",
                "reason": "; ".join(gate_issues),
                "documentation_version_gate_issues": gate_issues,
            })
            continue
        resolved_branch = resolve_branch_ref(branch, worktree, results)
        if not resolved_branch:
            routed.append({"task_id": task_id, "next_owner": "dispatcher", "reason": "source branch not found"})
            continue
        live_ci_issues, live_ci_evidence, live_ci_commands = repository_hygiene_live_ci_gate(
            worktree,
            args.base_ref,
            resolved_branch,
            task_row,
            item,
        )
        results.extend(live_ci_commands)
        if live_ci_issues:
            routed.append({
                "task_id": task_id,
                "next_owner": "integrator",
                "route": "required_ci_wait",
                "reason": "; ".join(live_ci_issues),
                "required_ci_evidence": live_ci_evidence,
            })
            continue
        checkout_paths, missing_paths = paths_existing_at_ref(resolved_branch, paths, worktree, results)
        if not checkout_paths:
            next_owner = "dispatcher" if task_id.startswith("SRC-") else "worker"
            routed.append({
                "task_id": task_id,
                "next_owner": next_owner,
                "reason": "no changed_paths exist in source branch",
                "missing_changed_paths": missing_paths,
            })
            continue
        before_texts = {rel_path: read_text_file(worktree / rel_path) for rel_path in checkout_paths}
        behavior_before = behavior_snapshot(worktree, checkout_paths)
        candidate_texts = {
            rel_path: text_at_ref(worktree, resolved_branch, rel_path)
            for rel_path in checkout_paths
        }
        candidate_apply, candidate_apply_commands = apply_candidate_delta(
            worktree,
            args.base_ref,
            resolved_branch,
            checkout_paths,
        )
        results.extend(candidate_apply_commands)
        if not candidate_apply["ok"]:
            restore = run(["git", "restore", "--staged", "--worktree", "--", *checkout_paths], worktree)
            results.append(restore)
            next_owner = "dispatcher" if repairable_worker_failure else "integrator"
            routed.append({
                "task_id": task_id,
                "next_owner": next_owner,
                **({"route": "needs_worker_fix", "integration_repair_kind": "three_way_apply"} if repairable_worker_failure else {}),
                **({"integration_repair_source_head_sha": repair_source_head_sha} if repairable_worker_failure and repair_source_head_sha else {}),
                "reason": f"{candidate_apply['reason']}: task-scoped candidate delta could not be applied to current base",
            })
            continue
        behavior_after = behavior_snapshot(worktree, checkout_paths)
        behavior_report = detect_behavior_regression(behavior_before, behavior_after)
        if not behavior_report["ok"]:
            preserve_report = attempt_behavior_preserving_merge(worktree, checkout_paths, before_texts, candidate_texts, behavior_before)
            if preserve_report["ok"]:
                repaired_behavior_after = behavior_snapshot(worktree, checkout_paths)
                repaired_behavior_report = detect_behavior_regression(behavior_before, repaired_behavior_after)
                if repaired_behavior_report["ok"]:
                    results.append({
                        "command": ["integrator", "behavior-preserving-merge"],
                        "cwd": str(worktree),
                        "exit_code": 0,
                        "stdout": json.dumps(preserve_report, ensure_ascii=False),
                        "stderr": "",
                    })
                else:
                    behavior_report = repaired_behavior_report
                    restore = run(["git", "restore", "--staged", "--worktree", "--", *checkout_paths], worktree)
                    results.append(restore)
                    routed.append({
                        "task_id": task_id,
                        "next_owner": "dispatcher" if repairable_worker_failure else "integrator",
                        **({"route": "needs_worker_fix", "integration_repair_kind": "behavior_preservation"} if repairable_worker_failure else {}),
                        **({"integration_repair_source_head_sha": repair_source_head_sha} if repairable_worker_failure and repair_source_head_sha else {}),
                        "reason": "semantic_regression_detected: automatic behavior-preserving merge could not preserve all existing target-branch behavior",
                        "lost_behavior": behavior_report["lost_behavior"],
                        "lost_count": behavior_report["lost_count"],
                    })
                    continue
            else:
                routed.append({
                    "task_id": task_id,
                    "next_owner": "dispatcher" if repairable_worker_failure else "integrator",
                    **({"route": "needs_worker_fix", "integration_repair_kind": "behavior_preservation"} if repairable_worker_failure else {}),
                    **({"integration_repair_source_head_sha": repair_source_head_sha} if repairable_worker_failure and repair_source_head_sha else {}),
                    "reason": "semantic_regression_detected: candidate removes existing target-branch behavior and has no safe automatic additions to integrate",
                    "lost_behavior": behavior_report["lost_behavior"],
                    "lost_count": behavior_report["lost_count"],
                })
                restore = run(["git", "restore", "--staged", "--worktree", "--", *checkout_paths], worktree)
                results.append(restore)
                continue
        repaired_paths = repair_text_whitespace(worktree, checkout_paths)
        if repaired_paths:
            results.append({"command": ["integrator", "repair-text-whitespace"], "cwd": str(worktree), "exit_code": 0, "stdout": "\n".join(repaired_paths), "stderr": ""})
        add = run(["git", "add", "-A", "--", *checkout_paths], worktree)
        results.append(add)
        status = run(["git", "status", "--porcelain", "--", *checkout_paths], worktree)
        results.append(status)
        if (
            add["exit_code"] == 0
            and status["exit_code"] == 0
            and not str(status.get("stdout") or "").strip()
        ):
            # A successful three-way apply can legitimately produce no diff
            # when the exact task delta was already published by a previous
            # integration attempt whose state commit failed. Finalize the task
            # against the current base instead of sending it back to a worker.
            current_head = run(["git", "rev-parse", "HEAD"], worktree)
            results.append(current_head)
            current_head_sha = str(current_head.get("stdout") or "").strip()
            if current_head["exit_code"] == 0 and current_head_sha:
                ready.append(task_id)
                commits.append(current_head_sha)
                ready_metadata[task_id] = item
                results.append({
                    "command": ["integrator", "already-applied-task-delta"],
                    "cwd": str(worktree),
                    "exit_code": 0,
                    "stdout": task_id,
                    "stderr": "",
                })
                continue
        if add["exit_code"] != 0 or status["exit_code"] != 0 or not str(status.get("stdout") or "").strip():
            routed.append({"task_id": task_id, "next_owner": "worker", "reason": "no usable task-scoped diff"})
            continue
        validation_paths = focused_validation_paths(worktree, checkout_paths)
        validation_targets = focused_validation_targets(worktree, validation_paths)
        required_test_checks = required_focused_test_checks(task_row)
        uncovered_test_paths = uncovered_focused_test_paths(worktree, checkout_paths, validation_paths)
        if required_test_checks and uncovered_test_paths:
            restore = run(["git", "restore", "--staged", "--worktree", "--", *checkout_paths], worktree)
            results.append(restore)
            routed.append({
                "task_id": task_id,
                "next_owner": "worker",
                "reason": "required_focused_test_coverage_missing",
                "required_test_checks": required_test_checks,
                "uncovered_python_paths": uncovered_test_paths,
                "validation_paths": validation_paths,
                "validation_targets": validation_targets,
            })
            continue
        if validation_targets:
            validation = run([sys.executable, "-m", "pytest", *validation_targets, "-q"], worktree)
            results.append(validation)
            if validation["exit_code"] != 0:
                restore = run(["git", "restore", "--staged", "--worktree", "--", *checkout_paths], worktree)
                results.append(restore)
                routed.append({
                    "task_id": task_id,
                    "next_owner": "dispatcher" if repairable_worker_failure else "integrator",
                    **({"route": "needs_worker_fix", "integration_repair_kind": "focused_validation"} if repairable_worker_failure else {}),
                    **({"integration_repair_source_head_sha": repair_source_head_sha} if repairable_worker_failure and repair_source_head_sha else {}),
                    "reason": "focused_validation_failed: " + short(validation),
                    "validation_paths": validation_paths,
                    "validation_targets": validation_targets,
                })
                continue
        commit = run(["git", "commit", "-m", f"chore(integrator): integrate {task_id}"], worktree)
        results.append(commit)
        if commit["exit_code"] != 0:
            routed.append({"task_id": task_id, "next_owner": "worker", "reason": "commit failed: " + short(commit)})
            continue
        sha = run(["git", "rev-parse", "HEAD"], worktree)
        results.append(sha)
        commits.append((sha.get("stdout") or "").strip())
        ready.append(task_id)
        ready_metadata[task_id] = item

    diff_check = run(["git", "diff", "--check", f"{base}..HEAD" if base else "HEAD"], worktree)
    results.append(diff_check)
    if diff_check["exit_code"] != 0:
        report = {"schema_version": 1, "created_at": utc_now(), "status": "blocked", "reason": "diff_check_failed", "ready": ready, "routed": routed, "command_results": results}
        write_json(report_path, report)
        return report

    pushed = False
    if ready:
        current = run(["git", "rev-parse", args.base_ref], project_root)
        results.append(current)
        current_sha = (current.get("stdout") or "").strip()
        if current_sha and base and current_sha != base:
            report = {"schema_version": 1, "created_at": utc_now(), "status": "blocked", "reason": "base_changed_during_direct_merge", "ready": ready, "routed": routed, "command_results": results}
            write_json(report_path, report)
            return report
        push = run(["git", "push", "origin", "HEAD:develop"], worktree)
        results.append(push)
        pushed = push["exit_code"] == 0
        if not pushed:
            report = {"schema_version": 1, "created_at": utc_now(), "status": "blocked", "reason": "push_failed", "ready": ready, "routed": routed, "command_results": results}
            write_json(report_path, report)
            return report

    ready_commits = dict(zip(ready, commits))
    if pushed:
        source_pr_closures.update(close_source_pull_requests(worktree, ready_commits, ready_metadata, task_rows, results))
    state_ready_commits = {**source_close_retry_commits, **ready_commits}
    state_ready_metadata = {**source_close_retry_metadata, **ready_metadata}
    source_close_failed = any(item.get("status") != "closed" for item in source_pr_closures.values())
    report_status = (
        "direct_merge_needs_source_pr_close"
        if source_close_failed
        else "direct_merge_done"
        if pushed or source_close_retry_commits
        else "no_ready_items"
    )
    report = {
        "schema_version": 1,
        "created_at": utc_now(),
        "status": report_status,
        "input_source": input_source,
        "batch_exists": batch_exists,
        "base_sha": base,
        "ready": ready,
        "source_pr_close_retried": sorted(source_close_retry_commits),
        "routed": routed,
        "pushed": pushed,
        "commits": commits,
        "source_pr_closures": source_pr_closures,
        "state_commit": None,
        "command_results": results,
    }
    state_commit = record_state_commit(
        worktree,
        project_root,
        batch,
        report,
        state_ready_commits,
        state_ready_metadata,
        routed,
        results,
        getattr(args, "consume_event_id", []),
        source_pr_closures,
        set(source_close_retry_commits),
    )
    report["state_commit"] = state_commit
    report["command_results"] = results
    if should_write_final_report_without_state_commit(report, state_commit):
        write_json(report_path, report)
    clean_worktree(project_root, worktree, results)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--batch")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--worktree-root")
    parser.add_argument("--max-items", type=int, default=10)
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--consume-event-id", action="append", default=[])
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = direct_merge(args)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"status: {report.get('status')}")
        print(f"ready: {len(report.get('ready') or [])}")
    return 0 if report.get("status") in {"direct_merge_done", "direct_merge_needs_source_pr_close", "no_ready_items", "dry_run", "no_candidates", "routed_no_direct_merge_candidates"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
