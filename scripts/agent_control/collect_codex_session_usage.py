#!/usr/bin/env python3
"""Collect privacy-safe Codex token usage and attribute it to automation runs.

The authoritative token counters live in Codex session JSONL files. Automation
identity lives in runtime ``launch.json`` files. This collector joins them by
the exact worker worktree without publishing prompts, messages, raw paths, or
tool payloads.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "1.1"
DEFAULT_LOOKBACK_DAYS = 8
DEFAULT_MAX_DETAIL_ROWS = 500
WEEKLY_WINDOW_MINUTES = 7 * 24 * 60
LIVE_LIMIT_MAX_AGE_SECONDS = 10 * 60


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_datetime(value: Any) -> dt.datetime | None:
    if not value:
        return None
    try:
        parsed = dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def safe_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def safe_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    return value if isinstance(value, dict) else {}


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + f".{os.getpid()}.tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def newest_usage_input_mtime(runtime_root: Path, sessions_root: Path) -> float:
    newest = 0.0
    live_limits = runtime_root / "codex-limits" / "latest.json"
    try:
        newest = live_limits.stat().st_mtime
    except OSError:
        pass
    if sessions_root.exists():
        for path in sessions_root.rglob("*.jsonl"):
            try:
                newest = max(newest, path.stat().st_mtime)
            except OSError:
                continue
    return newest


def normalized_path(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    try:
        return str(Path(text).expanduser().resolve())
    except Exception:
        return text


def slug(value: Any) -> str:
    result = re.sub(r"[^a-z0-9]+", "-", str(value or "").strip().lower()).strip("-")
    return result or "unknown"


def project_id_from_root(value: Any) -> str | None:
    text = str(value or "").strip()
    if not text:
        return None
    return slug(Path(text).name)


def model_family(model: Any) -> str:
    value = str(model or "").strip().lower()
    if "5.3" in value and ("spark" in value or "codex" in value):
        return "spark"
    return "general"


def model_label(model: Any) -> str:
    value = str(model or "").strip()
    return value or "unknown"


def stage_from_role(role: Any) -> str:
    value = slug(role)
    aliases = {
        "auto-worker": "worker",
        "automation-worker": "worker",
        "strong-integrator": "integrator",
        "auto-finalizer": "finalizer",
        "manual-codex": "manual",
    }
    return aliases.get(value, value if value != "unknown" else "unattributed")


def reset_bucket(value: Any) -> int | None:
    epoch = safe_int(value)
    if not epoch:
        return None
    return ((epoch + 30) // 60) * 60


def epoch_iso(value: Any) -> str | None:
    epoch = safe_int(value)
    if not epoch:
        return None
    try:
        return dt.datetime.fromtimestamp(epoch, tz=dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    except (OverflowError, OSError, ValueError):
        return None


def datetime_epoch(value: Any) -> int | None:
    parsed = parse_datetime(value)
    return int(parsed.timestamp()) if parsed is not None else None


def load_live_weekly_pools(
    runtime_root: Path,
    *,
    now: dt.datetime,
    max_age_seconds: int = LIVE_LIMIT_MAX_AGE_SECONDS,
) -> dict[str, dict[str, Any]]:
    """Load the fresh app-server limit snapshot used by the legacy dashboard.

    Session logs remain authoritative for exact tokens. The app-server snapshot
    owns the current percentage and reset marker because it refreshes even when
    a model pool has no new session after an account-side reset.
    """

    payload = read_json(runtime_root / "codex-limits" / "latest.json")
    rows = payload.get("limits")
    if not isinstance(rows, list):
        return {}

    pools: dict[str, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict) or str(row.get("window") or "").lower() != "weekly":
            continue
        model = str(row.get("model") or "").lower()
        scope = str(row.get("scope") or "").lower()
        if scope == "global":
            family = "general"
        elif "5.3" in model and ("spark" in model or "codex" in model):
            family = "spark"
        else:
            continue
        observed_at = str(row.get("observed_at") or payload.get("updated_at") or "").strip()
        observed_dt = parse_datetime(observed_at)
        if observed_dt is None:
            continue
        age_seconds = (now - observed_dt).total_seconds()
        if age_seconds < -60 or age_seconds > max_age_seconds:
            continue
        used = safe_float(row.get("used_percent"))
        if used is None:
            remaining = safe_float(row.get("remaining_percent"))
            used = None if remaining is None else 100.0 - remaining
        reset_epoch = datetime_epoch(row.get("reset_at"))
        if used is None or reset_epoch is None:
            continue
        candidate = {
            "used_percent": min(100.0, max(0.0, used)),
            "reset_at_epoch": reset_epoch,
            "observed_at": observed_dt.isoformat().replace("+00:00", "Z"),
            "source": str(row.get("source") or "codex_app_server"),
        }
        previous = pools.get(family)
        if previous is None or observed_dt > (parse_datetime(previous.get("observed_at")) or dt.datetime.min.replace(tzinfo=dt.timezone.utc)):
            pools[family] = candidate
    return pools


def scan_launches(runtime_root: Path) -> dict[str, dict[str, Any]]:
    launches: dict[str, dict[str, Any]] = {}
    for path in sorted((runtime_root / "runs").glob("*/*/launch.json")):
        data = read_json(path)
        worktree = normalized_path(data.get("worktree"))
        if not worktree:
            continue
        run_dir = Path(str(data.get("run_dir") or path.parent))
        agent_role = str(data.get("agent_role") or "worker").strip() or "worker"
        explicit_stage = str(data.get("execution_stage") or data.get("stage") or "").strip()
        execution_stage = slug(explicit_stage) if explicit_stage else stage_from_role(agent_role)
        run_id = run_dir.name or path.parent.name
        launches[worktree] = {
            "_project_root": normalized_path(data.get("project_root")),
            "run_id": run_id,
            "attempt_id": str(data.get("attempt_id") or run_id),
            "attempt_number": safe_int(data.get("attempt_number")) or None,
            "task_id": str(data.get("task_id") or "").strip() or None,
            "task_title": str(data.get("task_title") or "").strip() or None,
            "agent_id": str(data.get("worker_id") or "").strip() or None,
            "agent_role": agent_role,
            "execution_stage": execution_stage,
            "stage_attribution_source": (
                "launch_execution_stage" if explicit_stage else "launch_agent_role"
            ),
            "project_id": project_id_from_root(data.get("project_root")),
            "launch_model": str(data.get("model") or "").strip() or None,
            "launch_reasoning_effort": str(data.get("reasoning_effort") or "").strip() or None,
            "attribution_source": "launch_worktree_exact",
        }
    return launches


def task_records(path: Path) -> list[dict[str, Any]]:
    payload = read_json(path)
    for key in ("tasks", "history", "items"):
        rows = payload.get(key)
        if isinstance(rows, list):
            return [row for row in rows if isinstance(row, dict)]
    return []


def canonical_task_outcome(task: dict[str, Any], source: str) -> dict[str, Any]:
    status = str(task.get("final_status") or task.get("status") or "").strip().lower()
    integration_status = str(task.get("integration_status") or "").strip().lower()
    finalization_status = str(task.get("finalization_status") or "").strip().lower()
    merge_commit = str(task.get("merge_commit") or task.get("accepted_commit") or "").strip()
    finalized_at = str(task.get("finalized_at") or task.get("closed_at") or "").strip() or None
    finalized_by = str(task.get("finalized_by") or task.get("closed_by") or "").strip() or None
    accepted = (
        status == "done"
        and integration_status in {"finalized", "already_integrated"}
        and finalization_status in {"recorded", "done_recorded", "finalized", "complete", "completed"}
        and bool(merge_commit)
    )
    if accepted:
        outcome = "accepted"
        reason = "canonical_finalizer_evidence"
    elif status in {"rejected", "failed"}:
        outcome = "rejected"
        reason = f"terminal_status:{status}"
    elif status in {"cancelled", "canceled"}:
        outcome = "cancelled"
        reason = f"terminal_status:{status}"
    else:
        outcome = "inconclusive"
        reason = "canonical_acceptance_evidence_missing"
    return {
        "task_outcome": outcome,
        "task_outcome_reason": reason,
        "task_outcome_source": source,
        "accepted_commit": merge_commit or None,
        "accepted_at": finalized_at,
        "acceptance_authority": finalized_by,
    }


def load_task_outcomes(launches: dict[str, dict[str, Any]]) -> dict[tuple[str, str], dict[str, Any]]:
    project_roots = {
        (str(launch.get("project_id") or ""), str(launch.get("_project_root") or ""))
        for launch in launches.values()
        if launch.get("project_id") and launch.get("_project_root")
    }
    outcomes: dict[tuple[str, str], dict[str, Any]] = {}
    for project_id, root_text in sorted(project_roots):
        task_manager = Path(root_text) / "AiStudio" / "Task_manager"
        for filename, source in (
            ("task_history.json", "task_history"),
            ("task_queue.json", "task_queue"),
        ):
            for task in task_records(task_manager / filename):
                task_id = str(task.get("id") or task.get("canonical_task_id") or "").strip()
                if not task_id:
                    continue
                outcomes[(project_id, task_id)] = canonical_task_outcome(task, source)
    return outcomes


def join_task_outcomes(
    rows: list[dict[str, Any]],
    outcomes: dict[tuple[str, str], dict[str, Any]],
) -> list[dict[str, Any]]:
    for row in rows:
        key = (str(row.get("project_id") or ""), str(row.get("task_id") or ""))
        outcome = outcomes.get(key) or {
            "task_outcome": "inconclusive",
            "task_outcome_reason": "canonical_task_record_missing",
            "task_outcome_source": None,
            "accepted_commit": None,
            "accepted_at": None,
            "acceptance_authority": None,
        }
        row.update(outcome)
        accepted = outcome["task_outcome"] == "accepted"
        row["tokens_per_accepted_result"] = row.get("effective_tokens") if accepted else None
        row["rejected_and_retry_token_share"] = (
            round(safe_int(row.get("incomplete_effective_tokens")) / safe_int(row.get("effective_tokens")), 6)
            if safe_int(row.get("effective_tokens"))
            else None
        )
        row["cache_hit_ratio"] = (
            round(safe_int(row.get("cached_input_tokens")) / safe_int(row.get("input_tokens")), 6)
            if safe_int(row.get("input_tokens"))
            else None
        )
        row["accepted_first_pass"] = bool(
            accepted
            and safe_int(row.get("attempt_count")) == 1
            and safe_int(row.get("completed_session_count")) == 1
        )
        row["baseline_eligible"] = bool(
            accepted
            and safe_int(row.get("completed_session_count")) > 0
            and safe_int(row.get("effective_tokens")) > 0
        )
        row["baseline_eligibility_reason"] = (
            "accepted_quality_authority_present"
            if row["baseline_eligible"]
            else "accepted_outcome_required"
        )
    return rows


def infer_context_from_cwd(cwd: str) -> dict[str, Any]:
    parts = [part for part in Path(cwd).parts if part not in {"/", "\\"}]
    lowered = [part.lower() for part in parts]

    if "worker-worktrees" in lowered:
        index = lowered.index("worker-worktrees")
        project_id = slug(parts[index + 1]) if len(parts) > index + 1 else None
        agent_id = parts[index + 2] if len(parts) > index + 2 else "automation-worker"
        return {
            "project_id": project_id,
            "task_id": None,
            "agent_id": agent_id,
            "agent_role": "worker",
            "execution_stage": "worker",
            "stage_attribution_source": "worktree_role_inferred",
            "attribution_source": "worktree_path_inferred",
        }
    if "integrator-worktrees" in lowered:
        index = lowered.index("integrator-worktrees")
        return {
            "project_id": slug(parts[index + 1]) if len(parts) > index + 1 else None,
            "task_id": None,
            "agent_id": "strong-integrator",
            "agent_role": "integrator",
            "execution_stage": "integrator",
            "stage_attribution_source": "worktree_role_inferred",
            "attribution_source": "integrator_path_inferred",
        }
    if "agent-relay-worktrees" in lowered:
        return {
            "project_id": "ai-project-agent",
            "task_id": None,
            "agent_id": "agent-control",
            "agent_role": "automation",
            "execution_stage": "automation",
            "stage_attribution_source": "worktree_role_inferred",
            "attribution_source": "agent_relay_path_inferred",
        }
    if "devops" in lowered:
        index = lowered.index("devops")
        return {
            "project_id": slug(parts[index + 1]) if len(parts) > index + 1 else None,
            "task_id": None,
            "agent_id": "manual-codex",
            "agent_role": "manual",
            "execution_stage": "manual",
            "stage_attribution_source": "project_role_inferred",
            "attribution_source": "project_path_inferred",
        }
    return {
        "project_id": None,
        "task_id": None,
        "agent_id": "unattributed",
        "agent_role": "unknown",
        "execution_stage": "unattributed",
        "stage_attribution_source": "unattributed",
        "attribution_source": "unattributed",
    }


def source_agent_id(source: Any) -> str | None:
    if not isinstance(source, dict):
        return None
    thread_spawn = source.get("thread_spawn")
    if not isinstance(thread_spawn, dict):
        return None
    agent_path = str(thread_spawn.get("agent_path") or "").strip()
    if agent_path:
        return f"subagent:{agent_path.removeprefix('/root/')}"
    nickname = str(thread_spawn.get("agent_nickname") or "").strip()
    return f"subagent:{nickname}" if nickname else None


def parse_primary_rate(rate_limits: Any) -> dict[str, Any] | None:
    if not isinstance(rate_limits, dict):
        return None
    primary = rate_limits.get("primary")
    if not isinstance(primary, dict):
        return None
    used = safe_float(primary.get("used_percent"))
    window = safe_int(primary.get("window_minutes"))
    reset = safe_int(primary.get("resets_at"))
    if used is None or not window or not reset:
        return None
    return {
        "limit_id": str(rate_limits.get("limit_id") or "codex"),
        "used_percent": used,
        "window_minutes": window,
        "reset_at_epoch": reset,
    }


def parse_session(path: Path) -> dict[str, Any] | None:
    meta: dict[str, Any] = {}
    model: str | None = None
    effort: str | None = None
    total_usage: dict[str, Any] | None = None
    rate_buckets: dict[tuple[int, int | None], dict[str, Any]] = {}
    selected_rate_bucket: tuple[int, int | None] | None = None
    last_token_at: str | None = None
    last_event_at: str | None = None
    task_complete = False
    turn_active = False
    task_started_pending_context = False
    turn_context_pending_task_start = False
    turn_start_effective_tokens = 0
    completed_effective_tokens = 0
    incomplete_effective_tokens = 0
    completed_turn_count = 0
    incomplete_turn_count = 0

    def current_effective_tokens() -> int:
        usage = total_usage or {}
        input_tokens = safe_int(usage.get("input_tokens"))
        cached_input_tokens = min(input_tokens, safe_int(usage.get("cached_input_tokens")))
        return max(0, input_tokens - cached_input_tokens) + safe_int(usage.get("output_tokens"))

    def start_turn() -> None:
        nonlocal turn_active, turn_start_effective_tokens, task_complete
        turn_active = True
        task_complete = False
        turn_start_effective_tokens = (
            0
            if completed_turn_count + incomplete_turn_count == 0
            else current_effective_tokens()
        )

    def close_turn(*, complete: bool) -> None:
        nonlocal turn_active, task_complete
        nonlocal completed_effective_tokens, incomplete_effective_tokens
        nonlocal completed_turn_count, incomplete_turn_count
        delta = max(0, current_effective_tokens() - turn_start_effective_tokens)
        if complete:
            completed_effective_tokens += delta
            completed_turn_count += 1
        else:
            incomplete_effective_tokens += delta
            incomplete_turn_count += 1
        turn_active = False
        task_complete = complete

    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None

    for line in lines:
        try:
            event = json.loads(line)
        except Exception:
            continue
        if not isinstance(event, dict):
            continue
        timestamp = str(event.get("timestamp") or "").strip() or None
        if timestamp:
            last_event_at = timestamp
        payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
        event_type = str(event.get("type") or "")
        payload_type = str(payload.get("type") or "")
        if event_type == "session_meta" and not meta:
            meta = payload
        elif event_type == "event_msg" and payload_type == "task_started":
            if turn_context_pending_task_start:
                turn_context_pending_task_start = False
                task_started_pending_context = False
            else:
                if turn_active:
                    close_turn(complete=False)
                start_turn()
                task_started_pending_context = True
        elif event_type == "turn_context":
            if model is None and payload.get("model"):
                model = str(payload.get("model"))
            if effort is None and payload.get("effort"):
                effort = str(payload.get("effort"))
            if task_started_pending_context:
                task_started_pending_context = False
            else:
                if turn_active:
                    close_turn(complete=False)
                start_turn()
                turn_context_pending_task_start = True
        elif event_type == "event_msg" and payload_type == "token_count":
            info = payload.get("info") if isinstance(payload.get("info"), dict) else {}
            current_usage = info.get("total_token_usage")
            if isinstance(current_usage, dict):
                total_usage = current_usage
                last_token_at = timestamp or last_token_at
            rate = parse_primary_rate(payload.get("rate_limits"))
            if rate is not None:
                bucket_key = (
                    safe_int(rate.get("window_minutes")),
                    reset_bucket(rate.get("reset_at_epoch")),
                )
                bucket = rate_buckets.setdefault(
                    bucket_key,
                    {"first": rate, "last": rate, "last_at": timestamp},
                )
                bucket["last"] = rate
                bucket["last_at"] = timestamp
                selected_rate_bucket = bucket_key
        elif event_type == "event_msg" and payload_type == "task_complete":
            task_started_pending_context = False
            turn_context_pending_task_start = False
            if not turn_active:
                start_turn()
            close_turn(complete=True)

    if turn_active:
        close_turn(complete=False)

    session_id = str(meta.get("id") or meta.get("session_id") or path.stem).strip()
    cwd = normalized_path(meta.get("cwd"))
    if not session_id or not cwd:
        return None

    tokens = total_usage or {}
    input_tokens = safe_int(tokens.get("input_tokens"))
    cached_input_tokens = min(input_tokens, safe_int(tokens.get("cached_input_tokens")))
    output_tokens = safe_int(tokens.get("output_tokens"))
    reasoning_output_tokens = safe_int(tokens.get("reasoning_output_tokens"))
    total_tokens = safe_int(tokens.get("total_tokens")) or input_tokens + output_tokens
    effective_tokens = max(0, input_tokens - cached_input_tokens) + output_tokens
    classified_effective_tokens = completed_effective_tokens + incomplete_effective_tokens
    if classified_effective_tokens < effective_tokens:
        unclassified_effective_tokens = effective_tokens - classified_effective_tokens
        if task_complete:
            completed_effective_tokens += unclassified_effective_tokens
        else:
            incomplete_effective_tokens += unclassified_effective_tokens
    started_at = str(meta.get("timestamp") or "").strip() or None
    finished_at = last_token_at or last_event_at
    started_dt = parse_datetime(started_at)
    finished_dt = parse_datetime(finished_at)
    duration_seconds = None
    if started_dt is not None and finished_dt is not None:
        duration_seconds = max(0, int((finished_dt - started_dt).total_seconds()))

    selected_rate = rate_buckets.get(selected_rate_bucket or (-1, None), {})
    first_rate = selected_rate.get("first") if isinstance(selected_rate.get("first"), dict) else {}
    last_rate = selected_rate.get("last") if isinstance(selected_rate.get("last"), dict) else {}
    rate_observed_at = selected_rate.get("last_at")
    first_used = safe_float(first_rate.get("used_percent"))
    last_used = safe_float(last_rate.get("used_percent"))
    observed_delta = None
    if first_used is not None and last_used is not None:
        observed_delta = max(0.0, round(last_used - first_used, 4))

    return {
        "_cwd": cwd,
        "_source": meta.get("source"),
        "session_id": session_id,
        "originator": str(meta.get("originator") or ""),
        "model": model,
        "reasoning_effort": effort,
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_seconds": duration_seconds,
        "complete": task_complete,
        "input_tokens": input_tokens,
        "cached_input_tokens": cached_input_tokens,
        "uncached_input_tokens": max(0, input_tokens - cached_input_tokens),
        "output_tokens": output_tokens,
        "reasoning_output_tokens": reasoning_output_tokens,
        "total_tokens": total_tokens,
        "effective_tokens": effective_tokens,
        "completed_effective_tokens": completed_effective_tokens,
        "incomplete_effective_tokens": incomplete_effective_tokens,
        "completed_turn_count": completed_turn_count,
        "incomplete_turn_count": incomplete_turn_count,
        "has_token_usage": bool(total_usage),
        "rate_limit_id": last_rate.get("limit_id"),
        "rate_window_minutes": safe_int(last_rate.get("window_minutes")) or None,
        "rate_reset_at_epoch": safe_int(last_rate.get("reset_at_epoch")) or None,
        "rate_first_used_percent": first_used,
        "rate_last_used_percent": last_used,
        "observed_limit_delta_percent": observed_delta,
        "rate_observed_at": rate_observed_at,
    }


def public_session_row(session: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in session.items()
        if not key.startswith("_")
    }


def aggregate_rows(
    sessions: list[dict[str, Any]],
    dimension: str,
    *,
    now: dt.datetime,
) -> list[dict[str, Any]]:
    buckets: dict[str, dict[str, Any]] = {}
    since_24h = now - dt.timedelta(hours=24)

    for session in sessions:
        if dimension == "task":
            task_id = str(session.get("task_id") or "").strip()
            if not task_id:
                continue
            project_id = str(session.get("project_id") or "unknown")
            key = f"{project_id}:{task_id}"
            label = task_id
        elif dimension == "agent":
            key = str(session.get("agent_id") or "unattributed")
            label = key
        elif dimension == "project":
            key = str(session.get("project_id") or "unattributed")
            label = key
        elif dimension == "stage":
            key = str(session.get("execution_stage") or "unattributed")
            label = key
        elif dimension == "task_stage":
            task_id = str(session.get("task_id") or "").strip()
            if not task_id:
                continue
            project_id = str(session.get("project_id") or "unknown")
            stage = str(session.get("execution_stage") or "unattributed")
            key = f"{project_id}:{task_id}:{stage}"
            label = f"{task_id} · {stage}"
        else:
            key = str(session.get("model") or "unknown")
            label = key

        row = buckets.setdefault(
            key,
            {
                "id": key,
                "label": label,
                "project_id": session.get("project_id") if dimension in {"task", "task_stage"} else None,
                "task_id": session.get("task_id") if dimension in {"task", "task_stage"} else None,
                "execution_stage": (
                    session.get("execution_stage")
                    if dimension in {"stage", "task_stage"}
                    else None
                ),
                "session_count": 0,
                "attempt_count": 0,
                "completed_session_count": 0,
                "completed_turn_count": 0,
                "incomplete_turn_count": 0,
                "input_tokens": 0,
                "cached_input_tokens": 0,
                "uncached_input_tokens": 0,
                "output_tokens": 0,
                "reasoning_output_tokens": 0,
                "total_tokens": 0,
                "effective_tokens": 0,
                "completed_effective_tokens": 0,
                "incomplete_effective_tokens": 0,
                "tokens_last_24h": 0,
                "estimated_general_limit_percent": 0.0,
                "estimated_spark_limit_percent": 0.0,
                "last_at": None,
                "_models": set(),
                "_agents": set(),
                "_projects": set(),
                "_attempts": set(),
            },
        )
        row["session_count"] += 1
        if session.get("complete"):
            row["completed_session_count"] += 1
        row["completed_turn_count"] += safe_int(session.get("completed_turn_count"))
        row["incomplete_turn_count"] += safe_int(session.get("incomplete_turn_count"))
        for field in (
            "input_tokens",
            "cached_input_tokens",
            "uncached_input_tokens",
            "output_tokens",
            "reasoning_output_tokens",
            "total_tokens",
            "effective_tokens",
        ):
            row[field] += safe_int(session.get(field))
        row["completed_effective_tokens"] += safe_int(session.get("completed_effective_tokens"))
        row["incomplete_effective_tokens"] += safe_int(session.get("incomplete_effective_tokens"))
        started_at = parse_datetime(session.get("started_at"))
        if started_at is not None and started_at >= since_24h:
            row["tokens_last_24h"] += safe_int(session.get("total_tokens"))
        row["estimated_general_limit_percent"] += safe_float(session.get("estimated_general_limit_percent")) or 0.0
        row["estimated_spark_limit_percent"] += safe_float(session.get("estimated_spark_limit_percent")) or 0.0
        current_last = parse_datetime(row.get("last_at"))
        next_last = parse_datetime(session.get("finished_at") or session.get("started_at"))
        if next_last is not None and (current_last is None or next_last > current_last):
            row["last_at"] = session.get("finished_at") or session.get("started_at")
        if session.get("model"):
            row["_models"].add(str(session["model"]))
        if session.get("agent_id"):
            row["_agents"].add(str(session["agent_id"]))
        if session.get("project_id"):
            row["_projects"].add(str(session["project_id"]))
        if session.get("attempt_id"):
            row["_attempts"].add(str(session["attempt_id"]))

    rows: list[dict[str, Any]] = []
    for row in buckets.values():
        row["models"] = sorted(row.pop("_models"))
        row["agents"] = sorted(row.pop("_agents"))
        row["projects"] = sorted(row.pop("_projects"))
        row["attempt_count"] = len(row.pop("_attempts"))
        row["estimated_general_limit_percent"] = round(row["estimated_general_limit_percent"], 4)
        row["estimated_spark_limit_percent"] = round(row["estimated_spark_limit_percent"], 4)
        row["incomplete_effective_percent"] = (
            round(row["incomplete_effective_tokens"] * 100.0 / row["effective_tokens"], 2)
            if row["effective_tokens"]
            else None
        )
        rows.append(row)
    rows.sort(key=lambda item: (-safe_int(item.get("total_tokens")), str(item.get("label") or "")))
    return rows


def compact_summary(report: dict[str, Any]) -> dict[str, Any]:
    coverage = report.get("coverage") if isinstance(report.get("coverage"), dict) else {}
    last_24h = report.get("last_24h") if isinstance(report.get("last_24h"), dict) else {}
    return {
        "generated_at": report.get("generated_at"),
        "source": report.get("source"),
        "weekly_pools": report.get("weekly_pools", []),
        "tokens_last_24h": safe_int(last_24h.get("total_tokens")),
        "sessions_last_24h": safe_int(last_24h.get("session_count")),
        "task_token_attribution_percent": coverage.get("task_token_attribution_percent"),
        "captured_session_count": safe_int(coverage.get("captured_session_count")),
        "sessions_with_tokens": safe_int(coverage.get("sessions_with_tokens")),
    }


def collect_usage(
    runtime_root: Path,
    sessions_root: Path,
    *,
    now: dt.datetime | None = None,
    lookback_days: int = DEFAULT_LOOKBACK_DAYS,
    max_detail_rows: int = DEFAULT_MAX_DETAIL_ROWS,
) -> dict[str, Any]:
    now = (now or dt.datetime.now(dt.timezone.utc)).astimezone(dt.timezone.utc)
    cutoff = now - dt.timedelta(days=max(1, lookback_days))
    launches = scan_launches(runtime_root)
    task_outcomes = load_task_outcomes(launches)
    sessions: list[dict[str, Any]] = []

    if sessions_root.exists():
        for path in sorted(sessions_root.rglob("*.jsonl")):
            try:
                modified_at = dt.datetime.fromtimestamp(path.stat().st_mtime, tz=dt.timezone.utc)
            except OSError:
                continue
            if modified_at < cutoff:
                continue
            session = parse_session(path)
            if session is None:
                continue
            launch = launches.get(session["_cwd"])
            if launch is not None:
                session.update(launch)
                if not session.get("model"):
                    session["model"] = launch.get("launch_model")
                if not session.get("reasoning_effort"):
                    session["reasoning_effort"] = launch.get("launch_reasoning_effort")
            else:
                session.update(infer_context_from_cwd(session["_cwd"]))
                spawned_agent = source_agent_id(session.get("_source"))
                if spawned_agent:
                    session["agent_id"] = spawned_agent
                    session["agent_role"] = "subagent"
                    session["execution_stage"] = "subagent"
                    session["stage_attribution_source"] = "session_spawn_exact"
                    session["attribution_source"] = "session_spawn_exact"
            session["attempt_id"] = str(
                session.get("attempt_id")
                or session.get("run_id")
                or session.get("session_id")
            )
            session["outcome"] = "completed" if session.get("complete") else "incomplete"
            session["model"] = model_label(session.get("model"))
            session["model_family"] = model_family(session.get("model"))
            session["estimated_general_limit_percent"] = 0.0
            session["estimated_spark_limit_percent"] = 0.0
            session["current_weekly_pool"] = False
            sessions.append(session)

    weekly_candidates = [
        session
        for session in sessions
        if session.get("rate_window_minutes") == WEEKLY_WINDOW_MINUTES
        and session.get("rate_reset_at_epoch")
        and session.get("rate_observed_at")
    ]
    live_weekly_pools = load_live_weekly_pools(runtime_root, now=now)
    current_pools: list[dict[str, Any]] = []
    current_sessions: list[dict[str, Any]] = []

    for family in ("general", "spark"):
        family_candidates = [session for session in weekly_candidates if session.get("model_family") == family]
        live_pool = live_weekly_pools.get(family)
        if not family_candidates and live_pool is None:
            continue
        latest_session = (
            max(
                family_candidates,
                key=lambda session: parse_datetime(session.get("rate_observed_at")) or dt.datetime.min.replace(tzinfo=dt.timezone.utc),
            )
            if family_candidates
            else None
        )
        latest_session_at = (
            parse_datetime(latest_session.get("rate_observed_at"))
            if latest_session is not None
            else None
        )
        live_observed_at = parse_datetime(live_pool.get("observed_at")) if live_pool is not None else None
        live_controls = live_pool is not None and (
            latest_session_at is None
            or (live_observed_at is not None and live_observed_at >= latest_session_at)
        )
        reset_epoch = (
            live_pool.get("reset_at_epoch")
            if live_controls
            else latest_session.get("rate_reset_at_epoch") if latest_session is not None else None
        )
        latest_bucket = reset_bucket(reset_epoch)
        pool_sessions = [
            session
            for session in family_candidates
            if reset_bucket(session.get("rate_reset_at_epoch")) == latest_bucket
        ]
        first_values = [
            value
            for value in (safe_float(session.get("rate_first_used_percent")) for session in pool_sessions)
            if value is not None
        ]
        last_values = [
            value
            for value in (safe_float(session.get("rate_last_used_percent")) for session in pool_sessions)
            if value is not None
        ]
        if live_controls:
            current_used = safe_float(live_pool.get("used_percent"))
            observed_at = live_pool.get("observed_at")
            limit_source = live_pool.get("source")
        else:
            current_used = max(last_values) if last_values else 0.0
            observed_at = latest_session.get("rate_observed_at") if latest_session is not None else None
            limit_source = "codex_session_token_count"
        current_used = current_used if current_used is not None else 0.0
        observed_start = min(first_values) if first_values else current_used
        observed_end = current_used if live_controls else max(last_values) if last_values else current_used
        observed_change = max(0.0, observed_end - observed_start)
        token_sessions = [session for session in pool_sessions if session.get("has_token_usage")]
        pool_tokens = sum(safe_int(session.get("total_tokens")) for session in token_sessions)
        task_tokens = sum(
            safe_int(session.get("total_tokens"))
            for session in token_sessions
            if session.get("task_id")
        )
        estimate_field = f"estimated_{family}_limit_percent"
        if pool_tokens and observed_change:
            for session in token_sessions:
                session[estimate_field] = round(
                    observed_change * safe_int(session.get("total_tokens")) / pool_tokens,
                    6,
                )
        for session in pool_sessions:
            session["current_weekly_pool"] = True
        current_sessions.extend(pool_sessions)
        current_pools.append({
            "pool_id": family,
            "label": "Общий недельный" if family == "general" else "Spark недельный",
            "window_minutes": WEEKLY_WINDOW_MINUTES,
            "used_percent": round(current_used, 2),
            "remaining_percent": round(max(0.0, 100.0 - current_used), 2),
            "reset_at": epoch_iso(reset_epoch),
            "observed_at": observed_at,
            "limit_source": limit_source,
            "observed_start_percent": round(observed_start, 2),
            "observed_end_percent": round(observed_end, 2),
            "observed_change_percent": round(observed_change, 2),
            "unallocated_baseline_percent": round(max(0.0, current_used - observed_change), 2),
            "captured_session_count": len(pool_sessions),
            "sessions_with_tokens": len(token_sessions),
            "tokens_total": pool_tokens,
            "tokens_attributed_to_tasks": task_tokens,
            "task_token_attribution_percent": round(task_tokens * 100.0 / pool_tokens, 2) if pool_tokens else None,
            "allocation_method": "observed_pool_change_weighted_by_exact_session_tokens",
            "allocation_quality": "estimated",
        })

    unique_current = {session["session_id"]: session for session in current_sessions}
    current_sessions = list(unique_current.values())
    current_sessions.sort(
        key=lambda session: parse_datetime(session.get("started_at")) or dt.datetime.min.replace(tzinfo=dt.timezone.utc),
        reverse=True,
    )
    since_24h = now - dt.timedelta(hours=24)
    last_24h_sessions = [
        session
        for session in sessions
        if (parse_datetime(session.get("started_at")) or dt.datetime.min.replace(tzinfo=dt.timezone.utc)) >= since_24h
    ]
    token_sessions = [session for session in sessions if session.get("has_token_usage")]
    total_tokens = sum(safe_int(session.get("total_tokens")) for session in token_sessions)
    effective_tokens = sum(safe_int(session.get("effective_tokens")) for session in token_sessions)
    completed_effective_tokens = sum(
        safe_int(session.get("completed_effective_tokens"))
        for session in token_sessions
    )
    incomplete_effective_tokens = sum(
        safe_int(session.get("incomplete_effective_tokens"))
        for session in token_sessions
    )
    task_tokens = sum(
        safe_int(session.get("total_tokens"))
        for session in token_sessions
        if session.get("task_id")
    )
    current_token_sessions = [session for session in current_sessions if session.get("has_token_usage")]
    current_total_tokens = sum(safe_int(session.get("total_tokens")) for session in current_token_sessions)
    current_task_tokens = sum(
        safe_int(session.get("total_tokens"))
        for session in current_token_sessions
        if session.get("task_id")
    )
    by_stage = aggregate_rows(sessions, "stage", now=now)
    task_efficiency = join_task_outcomes(
        aggregate_rows(sessions, "task", now=now),
        task_outcomes,
    )
    by_task_stage = join_task_outcomes(
        aggregate_rows(sessions, "task_stage", now=now),
        task_outcomes,
    )
    outcome_effective_tokens = sum(
        safe_int(row.get("effective_tokens"))
        for row in task_efficiency
        if row.get("task_outcome") != "inconclusive"
    )
    stage_rows_total = sum(safe_int(row.get("total_tokens")) for row in by_stage)
    stage_rows_effective = sum(safe_int(row.get("effective_tokens")) for row in by_stage)
    stage_attributed_tokens = sum(
        safe_int(session.get("total_tokens"))
        for session in token_sessions
        if session.get("execution_stage") != "unattributed"
    )
    detail_sessions = sorted(
        sessions,
        key=lambda session: parse_datetime(session.get("started_at"))
        or dt.datetime.min.replace(tzinfo=dt.timezone.utc),
        reverse=True,
    )

    report = {
        "schema_version": SCHEMA_VERSION,
        "generated_at": now.replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "source": "codex_session_token_count+launch_worktree+codex_app_server_limits",
        "lookback_days": max(1, lookback_days),
        "metric_definitions": {
            "exact_tokens": "Codex total_token_usage from the latest token_count event in each session.",
            "cached_input_tokens": "Subset of input_tokens served from cache; do not add it to total_tokens.",
            "observed_limit_delta_percent": "Global pool movement during one session; non-additive when sessions overlap.",
            "estimated_limit_percent": "Additive allocation of observed pool movement, weighted by exact session tokens.",
            "unallocated_baseline_percent": "Current pool usage that predates or is outside captured attributable sessions.",
            "execution_stage": "The model-execution lifecycle stage from exact launch metadata or a declared path-role fallback.",
            "stage_reconciliation": "Stage row totals must equal exact session token totals; the difference must be zero.",
            "effective_tokens": "Diagnostic spend metric: uncached_input_tokens plus output_tokens. It is not a provider billing claim.",
            "incomplete_effective_tokens": "Effective-token deltas from turns whose latest lifecycle did not reach task_complete; observational retry-cost evidence, not proof that no artifact was produced.",
            "accepted_outcome": "Canonical task acceptance requires done status, finalized integration, recorded finalization and an accepted merge commit.",
            "baseline_eligible": "Only accepted tasks with canonical authority, completed usage and non-zero effective tokens enter future baseline samples.",
        },
        "coverage": {
            "captured_session_count": len(sessions),
            "sessions_with_tokens": len(token_sessions),
            "sessions_without_tokens": len(sessions) - len(token_sessions),
            "sessions_attributed_to_tasks": len([session for session in token_sessions if session.get("task_id")]),
            "tokens_total": total_tokens,
            "effective_tokens_total": effective_tokens,
            "completed_effective_tokens": completed_effective_tokens,
            "incomplete_effective_tokens": incomplete_effective_tokens,
            "incomplete_effective_percent": (
                round(incomplete_effective_tokens * 100.0 / effective_tokens, 2)
                if effective_tokens
                else None
            ),
            "tokens_attributed_to_tasks": task_tokens,
            "task_token_attribution_percent": round(task_tokens * 100.0 / total_tokens, 2) if total_tokens else None,
            "tokens_attributed_to_stages": stage_attributed_tokens,
            "stage_token_attribution_percent": (
                round(stage_attributed_tokens * 100.0 / total_tokens, 2)
                if total_tokens
                else None
            ),
            "current_pool_tokens_total": current_total_tokens,
            "current_pool_tokens_attributed_to_tasks": current_task_tokens,
            "current_pool_task_token_attribution_percent": (
                round(current_task_tokens * 100.0 / current_total_tokens, 2)
                if current_total_tokens
                else None
            ),
        },
        "last_24h": {
            "session_count": len(last_24h_sessions),
            "sessions_with_tokens": len([session for session in last_24h_sessions if session.get("has_token_usage")]),
            "total_tokens": sum(safe_int(session.get("total_tokens")) for session in last_24h_sessions),
            "input_tokens": sum(safe_int(session.get("input_tokens")) for session in last_24h_sessions),
            "cached_input_tokens": sum(safe_int(session.get("cached_input_tokens")) for session in last_24h_sessions),
            "uncached_input_tokens": sum(safe_int(session.get("uncached_input_tokens")) for session in last_24h_sessions),
            "output_tokens": sum(safe_int(session.get("output_tokens")) for session in last_24h_sessions),
            "reasoning_output_tokens": sum(safe_int(session.get("reasoning_output_tokens")) for session in last_24h_sessions),
            "effective_tokens": sum(safe_int(session.get("effective_tokens")) for session in last_24h_sessions),
            "incomplete_effective_tokens": sum(
                safe_int(session.get("incomplete_effective_tokens"))
                for session in last_24h_sessions
            ),
        },
        "weekly_pools": sorted(current_pools, key=lambda item: item["pool_id"]),
        "by_task": join_task_outcomes(
            aggregate_rows(current_sessions, "task", now=now),
            task_outcomes,
        ),
        "by_agent": aggregate_rows(current_sessions, "agent", now=now),
        "by_project": aggregate_rows(current_sessions, "project", now=now),
        "by_model": aggregate_rows(current_sessions, "model", now=now),
        "by_stage": by_stage,
        "by_task_stage": by_task_stage,
        "task_efficiency": task_efficiency,
        "outcome_coverage": {
            "task_count": len(task_efficiency),
            "accepted_task_count": len(
                [row for row in task_efficiency if row.get("task_outcome") == "accepted"]
            ),
            "rejected_task_count": len(
                [row for row in task_efficiency if row.get("task_outcome") == "rejected"]
            ),
            "cancelled_task_count": len(
                [row for row in task_efficiency if row.get("task_outcome") == "cancelled"]
            ),
            "inconclusive_task_count": len(
                [row for row in task_efficiency if row.get("task_outcome") == "inconclusive"]
            ),
            "baseline_eligible_task_count": len(
                [row for row in task_efficiency if row.get("baseline_eligible")]
            ),
            "accepted_first_pass_task_count": len(
                [row for row in task_efficiency if row.get("accepted_first_pass")]
            ),
            "task_effective_tokens_total": sum(
                safe_int(row.get("effective_tokens")) for row in task_efficiency
            ),
            "task_effective_tokens_with_terminal_outcome": outcome_effective_tokens,
            "terminal_outcome_effective_token_coverage_percent": (
                round(
                    outcome_effective_tokens
                    * 100.0
                    / sum(safe_int(row.get("effective_tokens")) for row in task_efficiency),
                    2,
                )
                if task_efficiency
                and sum(safe_int(row.get("effective_tokens")) for row in task_efficiency)
                else None
            ),
        },
        "stage_reconciliation": {
            "scope": f"lookback_{max(1, lookback_days)}d",
            "exact_session_tokens": total_tokens,
            "stage_rows_tokens": stage_rows_total,
            "difference_tokens": stage_rows_total - total_tokens,
            "balanced": stage_rows_total == total_tokens,
            "exact_effective_tokens": effective_tokens,
            "stage_rows_effective_tokens": stage_rows_effective,
            "effective_difference_tokens": stage_rows_effective - effective_tokens,
            "effective_balanced": stage_rows_effective == effective_tokens,
        },
        "sessions": [
            public_session_row(session)
            for session in detail_sessions[: max(1, max_detail_rows)]
        ],
    }
    return report


def refresh_usage_snapshot(
    runtime_root: Path,
    *,
    sessions_root: Path | None = None,
    output_path: Path | None = None,
    max_age_seconds: int = 60,
    now: dt.datetime | None = None,
    lookback_days: int = DEFAULT_LOOKBACK_DAYS,
    max_detail_rows: int = DEFAULT_MAX_DETAIL_ROWS,
) -> dict[str, Any]:
    output_path = output_path or runtime_root / "codex-usage" / "latest.json"
    effective_sessions_root = sessions_root or Path.home() / ".codex" / "sessions"
    if max_age_seconds > 0 and output_path.exists():
        try:
            output_mtime = output_path.stat().st_mtime
            age = dt.datetime.now(dt.timezone.utc).timestamp() - output_mtime
        except OSError:
            output_mtime = 0.0
            age = max_age_seconds + 1
        inputs_unchanged = newest_usage_input_mtime(runtime_root, effective_sessions_root) <= output_mtime
        if age <= max_age_seconds and inputs_unchanged:
            cached = read_json(output_path)
            if cached.get("schema_version") == SCHEMA_VERSION:
                return cached
    report = collect_usage(
        runtime_root,
        effective_sessions_root,
        now=now,
        lookback_days=lookback_days,
        max_detail_rows=max_detail_rows,
    )
    write_json_atomic(output_path, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--sessions-root", default="~/.codex/sessions")
    parser.add_argument("--output", default="")
    parser.add_argument("--lookback-days", type=int, default=DEFAULT_LOOKBACK_DAYS)
    parser.add_argument("--max-detail-rows", type=int, default=DEFAULT_MAX_DETAIL_ROWS)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    runtime_root = Path(args.runtime_root).expanduser()
    output_path = Path(args.output).expanduser() if args.output else runtime_root / "codex-usage" / "latest.json"
    report = refresh_usage_snapshot(
        runtime_root,
        sessions_root=Path(args.sessions_root).expanduser(),
        output_path=output_path,
        max_age_seconds=0,
        lookback_days=max(1, args.lookback_days),
        max_detail_rows=max(1, args.max_detail_rows),
    )
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else str(output_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
