#!/usr/bin/env python3
"""Run a worker lane through repeated central claims.

The runner owns task selection and locking. The LLM worker receives exactly one
assigned task per launch and should not pick another task from the queue.
"""

from __future__ import annotations

import argparse
import atexit
import fnmatch
import hashlib
import json
import os
import re
import signal
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from codex_host_readiness import codex_host_readiness
from codex_model_capability import default_catalog_path
import execution_lease_manager
from process_log import append_log
from project_paths import task_manager_dir
from task_control_postgres import (
    TaskControlConfigurationError,
    TaskControlPostgres,
)
from uuid import uuid4


PROMPTS = {
    "auto-worker-5.3-mini": "Auto Worker 5.3 mini",
    "auto-worker-5.3": "Auto Worker 5.3",
    "auto-worker-5.5": "Auto Worker 5.5",
    "auto-worker-5.5max": "Auto Worker 5.5max",
}
DEFAULT_MODELS = {
    "auto-worker-5.3-mini": "gpt-5.3-codex-spark",
    "auto-worker-5.3": "gpt-5.3-codex-spark",
    "auto-worker-5.5": "gpt-5.5",
    "auto-worker-5.5max": "gpt-5.5",
}
DEFAULT_UNSUPPORTED_CODEX_MODELS: set[str] = set()
DEFAULT_MODEL_FALLBACKS: dict[str, str | None] = {}
FINALIZE_STATUS_ATTEMPTS = 3
FINALIZE_STATUS_RETRY_DELAY_SECONDS = 0.25
WORKER_QUEUE_REL = "AiStudio/Task_manager/task_queue.json"
ACTIVE_LOCK_STATES = {"locked", "in_progress"}

# Agent Core contracts use stable, provider-neutral effort names. Codex CLI
# currently exposes provider-specific enum values at the execution boundary.
CODEX_CLI_REASONING_EFFORTS = {
    "extra_high": "xhigh",
    "ultra": "max",
}


def codex_cli_reasoning_effort(value: str | None) -> str | None:
    """Translate a canonical Agent Core effort into the installed Codex CLI enum."""
    if value is None:
        return None
    normalized = str(value).strip().lower()
    if not normalized:
        return None
    return CODEX_CLI_REASONING_EFFORTS.get(normalized, normalized)


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def append_event(project_root: Path, event: dict[str, Any]) -> None:
    path = task_manager_dir(project_root) / "agent_events.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")


def emit_batch_complete(project_root: Path, worker_id: str, completed: int, max_tasks: int) -> None:
    append_event(
        project_root,
        {
            "event_id": f"evt-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}-{uuid4().hex[:8]}",
            "created_at": utc_now(),
            "project": project_root.name,
            "event": "task_worker_done",
            "role": worker_id,
            "next_role": "auto_integrator",
            "task_id": None,
            "pr": None,
            "branch": None,
            "severity": "info",
            "consumed_by": None,
            "consumed_at": None,
            "payload": {
                "reason": "worker_batch_limit_reached",
                "worker_id": worker_id,
                "completed_count": completed,
                "max_tasks": max_tasks,
            },
        },
    )


def run_json(cmd: list[str], cwd: Path | None = None) -> tuple[int, dict[str, Any], str, str]:
    proc = subprocess.run(cmd, cwd=str(cwd) if cwd else None, text=True, capture_output=True)
    data: dict[str, Any] = {}
    if proc.stdout.strip():
        try:
            data = json.loads(proc.stdout)
        except json.JSONDecodeError:
            data = {"raw_stdout": proc.stdout}
    return proc.returncode, data, proc.stdout, proc.stderr


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def load_worker_profile(project_root: Path, worker_id: str) -> dict[str, Any]:
    data = load_json(project_root / ".agent" / "worker_profiles.json")
    profiles = data if isinstance(data, list) else data.get("profiles", []) if isinstance(data, dict) else []
    for profile in profiles if isinstance(profiles, list) else []:
        if isinstance(profile, dict) and profile.get("worker_id") == worker_id:
            return profile
    return {}


def resolve_worker_model(project_root: Path, worker_id: str) -> str | None:
    profile = load_worker_profile(project_root, worker_id)
    value = profile.get("codex_model") or profile.get("model") or profile.get("model_alias")
    if isinstance(value, str) and value.strip():
        return apply_model_fallback(value.strip())
    return apply_model_fallback(DEFAULT_MODELS.get(worker_id))


def model_fallbacks() -> dict[str, str | None]:
    raw = os.environ.get("AGENT_CODEX_MODEL_FALLBACKS")
    if raw is None:
        return dict(DEFAULT_MODEL_FALLBACKS)
    result: dict[str, str | None] = {}
    for item in raw.split(","):
        if "=" not in item:
            continue
        source, target = item.split("=", 1)
        source = source.strip()
        target = target.strip()
        if not source:
            continue
        result[source] = None if target.lower() in {"", "default", "host-default", "none"} else target
    return result


def apply_model_fallback(model: str | None) -> str | None:
    if not model:
        return model
    return model_fallbacks().get(model, model)


def unsupported_codex_models() -> set[str]:
    raw = os.environ.get("AGENT_UNSUPPORTED_CODEX_MODELS")
    if raw is None:
        return set(DEFAULT_UNSUPPORTED_CODEX_MODELS)
    return {item.strip() for item in raw.split(",") if item.strip()}


def model_is_available(model: str | None) -> bool:
    if not model:
        return True
    return model not in unsupported_codex_models()


def codex_executable_available(codex_bin: str) -> bool:
    return codex_host_readiness(codex_bin, require_auth=False).ok


def codex_worker_host_readiness(codex_bin: str, model: str | None = None) -> dict[str, Any]:
    return codex_host_readiness(codex_bin, model=model).to_dict()


def run_command(cmd: list[str], cwd: Path | None = None) -> tuple[int, str, str]:
    proc = subprocess.run(cmd, cwd=str(cwd) if cwd else None, text=True, capture_output=True)
    return proc.returncode, proc.stdout, proc.stderr


def run_recovery_cycle(project_root: Path, args: argparse.Namespace) -> int:
    artifact_discovery_cmd = [
        sys.executable,
        str(script_path("artifact_discovery_cycle.py")),
        "--project-root",
        str(project_root),
        "--output-dir",
        str(task_manager_dir(project_root) / "reports" / "discovery"),
        "--worker-ready-first-safe",
        "--json",
    ]
    if args.recovery_apply:
        artifact_discovery_cmd.append("--apply-normalized")

    normalize_cmd = [
        sys.executable,
        str(script_path("normalize_task_packets.py")),
        "--project-root",
        str(project_root),
    ]
    if args.recovery_apply:
        normalize_cmd.append("--apply")

    promote_cmd = [
        sys.executable,
        str(script_path("promote_worker_ready_tasks.py")),
        "--queue",
        str(task_manager_dir(project_root) / "task_queue.json"),
        "--locks",
        str(task_manager_dir(project_root) / "agent_locks.json"),
    ]
    if args.recovery_apply:
        promote_cmd.append("--apply")

    readiness_cmd = [
        sys.executable,
        str(script_path("validate_task_queue_readiness.py")),
        "--queue",
        str(task_manager_dir(project_root) / "task_queue.json"),
    ]
    guard_cmd = [
        sys.executable,
        str(script_path("dispatcher_decision_guard.py")),
        "--queue",
        str(task_manager_dir(project_root) / "task_queue.json"),
    ]

    commands = [normalize_cmd, promote_cmd, readiness_cmd, guard_cmd]
    if args.artifact_discovery_recovery:
        commands.insert(0, artifact_discovery_cmd)

    for cmd in commands:
        code, stdout, stderr = run_command(cmd)
        label = Path(cmd[1]).name
        print(json.dumps({"status": "recovery_step", "step": label, "exit_code": code}, ensure_ascii=False))
        if stdout:
            print(stdout.rstrip())
        if stderr:
            print(stderr.rstrip(), file=sys.stderr)
        if code != 0:
            return code

    for path in (
        task_manager_dir(project_root) / "task_queue.json",
        task_manager_dir(project_root) / "agent_locks.json",
        task_manager_dir(project_root) / "agent_activity_state.json",
    ):
        if not path.exists():
            print(json.dumps({"status": "recovery_block", "reason": "missing_file", "file": str(path)}, ensure_ascii=False))
            return 2
        code, _, stderr = run_command([sys.executable, "-m", "json.tool", str(path)])
        if code != 0:
            print(json.dumps({"status": "recovery_block", "reason": "json_invalid", "file": str(path), "stderr": stderr}, ensure_ascii=False))
            return code
        print(json.dumps({"status": "json_ok", "file": str(path)}, ensure_ascii=False))
    return 0


def pid_alive(pid: int) -> bool:
    if os.name == "nt":
        proc = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
            text=True,
            capture_output=True,
            check=False,
        )
        stdout = proc.stdout.lower()
        return proc.returncode == 0 and str(pid) in stdout and "no tasks" not in stdout
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def terminate_pid_group(pid: int) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], text=True, capture_output=True, check=False)
        return
    try:
        os.killpg(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    except OSError:
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            return
    deadline = time.monotonic() + 10.0
    while pid_alive(pid) and time.monotonic() < deadline:
        time.sleep(0.5)
    if pid_alive(pid):
        try:
            os.killpg(pid, signal.SIGKILL)
        except OSError:
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass


def wait_for_pid(pid: int, poll_seconds: float, timeout_seconds: float = 0.0) -> bool:
    started = time.monotonic()
    while pid_alive(pid):
        if timeout_seconds > 0 and time.monotonic() - started >= timeout_seconds:
            terminate_pid_group(pid)
            return False
        time.sleep(max(1.0, poll_seconds))
    return True


def git_output(cmd: list[str], cwd: Path) -> tuple[int, str, str]:
    proc = subprocess.run(cmd, cwd=str(cwd), text=True, encoding="utf-8", capture_output=True, check=False)
    return proc.returncode, proc.stdout, proc.stderr


def git_longpaths_command(*args: str) -> list[str]:
    return ["git", "-c", "core.longpaths=true", *args]


def literal_pathspec(path: str) -> str:
    return f":(literal){path}"


def write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def push_ref_name(base_ref: str, push_ref: str | None) -> str:
    return push_ref or str(base_ref or "develop").removeprefix("origin/") or "develop"


def reset_launch_failed_claim(state_root: Path, claim: dict[str, Any], reason: str) -> bool:
    task_id = str(claim.get("task_id") or "").strip()
    if not task_id:
        return False
    queue_path = task_manager_dir(state_root) / "task_queue.json"
    locks_path = task_manager_dir(state_root) / "agent_locks.json"
    if not queue_path.exists():
        return False
    queue = load_json(queue_path)
    locks = load_json(locks_path) if locks_path.exists() else {"locks": []}
    if not isinstance(queue, dict):
        return False
    now = utc_now()
    changed = False
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict) or str(task.get("id") or "") != task_id:
            continue
        if str(task.get("status") or "") == "in_progress":
            task["status"] = "planned"
        task["lock"] = "free"
        task["worker_ready"] = True
        task["status_reason"] = reason
        for key in ("worker_id", "machine_id", "branch", "github_branch", "started_at", "lock_expires_at"):
            task.pop(key, None)
        history = task.get("status_history")
        if not isinstance(history, list):
            history = []
        history.append(
            {
                "at": now,
                "by": "run_worker_cycle",
                "event": "worker_launch_failed_claim_released",
                "reason": reason,
                "next_owner": "Worker",
            }
        )
        task["status_history"] = history
        changed = True
        break
    if isinstance(locks, dict):
        for lock in locks.get("locks") or []:
            if not isinstance(lock, dict) or str(lock.get("task_id") or "") != task_id:
                continue
            if str(lock.get("state") or "") in {"locked", "in_progress"}:
                lock["previous_state"] = lock.get("state")
                lock["state"] = "released"
                lock["released_at"] = now
                lock["released_by"] = "run_worker_cycle"
                lock["release_reason"] = "worker_launch_failed"
                changed = True
        locks["updated_at"] = now
    if changed:
        queue["updated_at"] = now
        write_json(queue_path, queue)
        if isinstance(locks, dict):
            write_json(locks_path, locks)
        append_event(
            state_root,
            {
                "event_id": f"evt-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}-{uuid4().hex[:8]}",
                "created_at": now,
                "project": state_root.name,
                "event": "worker_launch_failed_claim_released",
                "role": "run_worker_cycle",
                "next_role": "auto-workers",
                "task_id": task_id,
                "branch": claim.get("branch"),
                "severity": "warning",
                "consumed_by": None,
                "consumed_at": None,
                "payload": {"reason": reason, "worker_id": claim.get("worker_id"), "machine_id": claim.get("machine_id")},
            },
        )
    return changed


def commit_and_push_state(state_root: Path, push_ref: str, message: str) -> dict[str, Any]:
    if os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY") == "postgres":
        return {
            "state_root": str(state_root),
            "push_ref": push_ref,
            "committed": False,
            "pushed": False,
            "persisted_in_runtime_mirror": True,
            "session_id": os.environ.get("AISTUDIO_TASK_CONTROL_SESSION_ID"),
            "commands": [],
        }
    rel_paths = [
        str(task_manager_dir(state_root).relative_to(state_root) / "task_queue.json"),
        str(task_manager_dir(state_root).relative_to(state_root) / "agent_locks.json"),
        str(task_manager_dir(state_root).relative_to(state_root) / "agent_events.jsonl"),
    ]
    result: dict[str, Any] = {"state_root": str(state_root), "push_ref": push_ref, "committed": False, "pushed": False, "commands": []}
    add_code, add_out, add_err = git_output(["git", "add", "-A", "--", *rel_paths], state_root)
    result["commands"].append({"command": "git add state files", "exit_code": add_code, "stdout": add_out, "stderr": add_err})
    if add_code != 0:
        result["error"] = "git add failed"
        return result
    status_code, status_out, status_err = git_output(["git", "status", "--porcelain", "--", *rel_paths], state_root)
    result["commands"].append({"command": "git status state files", "exit_code": status_code, "stdout": status_out, "stderr": status_err})
    if status_code != 0:
        result["error"] = "git status failed"
        return result
    if not status_out.strip():
        result["pushed"] = True
        return result
    commit_code, commit_out, commit_err = git_output(["git", "commit", "-m", message], state_root)
    result["commands"].append({"command": "git commit state files", "exit_code": commit_code, "stdout": commit_out, "stderr": commit_err})
    if commit_code != 0:
        result["error"] = "git commit failed"
        return result
    result["committed"] = True
    push_code, push_out, push_err = git_output(["git", "push", "origin", f"HEAD:{push_ref}"], state_root)
    result["commands"].append({"command": f"git push origin HEAD:{push_ref}", "exit_code": push_code, "stdout": push_out, "stderr": push_err})
    result["pushed"] = push_code == 0
    if push_code != 0:
        result["error"] = "git push failed"
    return result


def read_tail(path: Path, limit: int = 12000) -> str:
    if not path.exists():
        return ""
    text = path.read_text(encoding="utf-8", errors="ignore")
    return text[-limit:]


def report_log_text(path: Path) -> str:
    """Preserve captured log content without emitting repository-invalid line endings."""
    return "\n".join(line.rstrip() for line in read_tail(path).split("\n"))


NEGATIVE_CHECK_MARKERS = (
    "tests not run",
    "checks not run",
    "not run",
    "skipped tests",
    "skipped checks",
    "no tests run",
    "did not run tests",
    "unable to run tests",
    "tests failed",
    "checks failed",
    "command failed",
    "failed:",
    "failed ",
    "error:",
    "commanderror",
    "traceback",
    "conflicting migrations detected",
    "returned non-zero",
    "exit code 1",
    "упал",
    "упала",
    "проверки не пройдены",
    "тесты не пройдены",
    "не выполнял runtime-проверки",
    "runtime-проверки не выполнял",
    "проверки не выполнял",
    "проверки не запускал",
    "не запускал проверки",
    "не запускал тесты",
    "тесты не запускал",
    "проектные проверки не запускал",
)

MODEL_LIMIT_MARKERS = (
    "you've hit your usage limit",
    "you have hit your usage limit",
    "hit your usage limit",
    "usage limit",
    "rate limit",
    "quota exceeded",
    "model currently unavailable",
    "model unavailable",
)

WORKER_RUNTIME_ERROR_MARKERS = (
    "requires a newer version of codex",
    "invalid_request_error",
    "unknown model",
    "model is not supported",
)

POSITIVE_CHECK_MARKERS = (
    " ran ",
    " ok",
    "passed",
    "tests passed",
    "checks passed",
    "0 errors, 0 warnings",
    "system check identified no issues",
    "git diff --check",
    "pytest",
    "manage.py test",
    "npm test",
)


EXPLICIT_PASSED_CHECK_MARKERS = (
    "check_status=passed",
    "check_status = passed",
    "check_status=`passed`",
    "check_status: passed",
    '"check_status": "passed"',
)

EXPLICIT_CHECK_STATUS_RE = re.compile(
    r"\bcheck_status\b[^\S\r\n]*(?:[`*_]*(?:=|:)[^\S\r\n]*|[`*_]*\r?\n[^\S\r\n]*)"
    r"[`\"'*_]*(passed|partial|not[_ -]?run(?:_or_missing)?|blocked|failed(?:_or_skipped)?)\b",
    re.IGNORECASE,
)


EXPLICIT_DISPATCHER_REPAIR_MARKERS = (
    "route to dispatcher",
    "route=needs_dispatcher_repair",
    "route: needs_dispatcher_repair",
    "result=needs_dispatcher_repair",
    "result: needs_dispatcher_repair",
    "status=needs_dispatcher_repair",
    "status: needs_dispatcher_repair",
    "blocked - needs_dispatcher_repair",
    "blocked -- needs_dispatcher_repair",
    "blocked \u2014 needs_dispatcher_repair",
    "`needs_dispatcher_repair`",
)

SCOPE_REPAIR_MARKERS = (
    "outside allowed_paths",
    "outside `allowed_paths`",
    "outside allowed paths",
    "cannot satisfy its acceptance criteria within scope",
)
REQUESTED_PATH_HINT_RE = re.compile(r"`([^`\r\n]+\.(?:py|json|md|yaml|yml|toml))`", re.IGNORECASE)
SAFE_REQUESTED_SCOPE_PREFIXES = (
    "scripts/agent_control/",
    "schemas/agent-control/",
    "templates/agent-control/",
    "tests/",
    "control/tests/",
    "docs/reports/workers/",
)


def requested_scope_path_hints(text: str) -> list[str]:
    hints: list[str] = []
    for match in REQUESTED_PATH_HINT_RE.finditer(text):
        value = match.group(1).strip().replace("\\", "/")
        if value and value not in hints:
            hints.append(value)
    return hints


def resolve_requested_allowed_paths(worktree: Path, hints: list[str]) -> list[str]:
    code, stdout, _stderr = git_output(["git", "ls-files"], worktree)
    if code != 0:
        return []
    tracked = [
        path.strip().replace("\\", "/")
        for path in stdout.splitlines()
        if path.strip().replace("\\", "/").startswith(SAFE_REQUESTED_SCOPE_PREFIXES)
    ]
    resolved: list[str] = []
    for raw_hint in hints:
        hint = raw_hint.strip().replace("\\", "/")
        if not hint or hint.startswith("/") or ".." in Path(hint).parts:
            continue
        matches = [path for path in tracked if path == hint] if "/" in hint else [
            path for path in tracked if Path(path).name == hint
        ]
        if (
            not matches
            and "/" in hint
            and hint.startswith(SAFE_REQUESTED_SCOPE_PREFIXES)
            and Path(hint).suffix.lower() in {".py", ".json", ".md", ".yaml", ".yml", ".toml"}
            and any(path.startswith(f"{Path(hint).parent.as_posix()}/") for path in tracked)
        ):
            matches = [hint]
        if len(matches) == 1 and matches[0] not in resolved:
            resolved.append(matches[0])
    return resolved


def classify_check_evidence(stdout_tail: str, stderr_tail: str) -> dict[str, Any]:
    stdout_text = stdout_tail.lower()
    text = f"{stdout_tail}\n{stderr_tail}".lower()
    model_limit_hits = [marker for marker in MODEL_LIMIT_MARKERS if marker in text]
    runtime_error_hits = [marker for marker in WORKER_RUNTIME_ERROR_MARKERS if marker in text]
    negative_hits = [marker for marker in NEGATIVE_CHECK_MARKERS if marker in text]
    positive_hits = [marker.strip() for marker in POSITIVE_CHECK_MARKERS if marker in text]
    explicit_pass_hits = [marker for marker in EXPLICIT_PASSED_CHECK_MARKERS if marker in stdout_text]
    explicit_statuses = [
        match.group(1).lower().replace(" ", "_").replace("-", "_")
        for match in EXPLICIT_CHECK_STATUS_RE.finditer(stdout_tail)
    ]
    explicit_nonpass_statuses = [status for status in explicit_statuses if status != "passed"]
    explicit_dispatcher_repair_hits = [
        marker
        for marker in EXPLICIT_DISPATCHER_REPAIR_MARKERS
        if marker in stdout_text
    ]
    if runtime_error_hits:
        return {
            "ok": False,
            "route": "blocked",
            "check_status": "model_limit",
            "integration_status": "blocked_model_limit",
            "reason": "worker could not start because the selected model is incompatible with the installed Codex runtime; Dispatcher must choose a supported fallback",
            "runtime_error_markers": runtime_error_hits,
            "negative_markers": negative_hits,
            "positive_markers": positive_hits,
        }
    if "needs_dispatcher_repair" in stdout_text and explicit_dispatcher_repair_hits:
        evidence = {
            "ok": False,
            "route": "needs_dispatcher_repair",
            "check_status": "passed" if explicit_pass_hits else "not_run_or_missing",
            "integration_status": "needs_dispatcher_repair",
            "reason": "worker explicitly reported a stale or incomplete packet that requires Dispatcher reconciliation",
            "worker_route_markers": explicit_dispatcher_repair_hits,
            "negative_markers": negative_hits,
            "positive_markers": [*positive_hits, *explicit_pass_hits],
        }
        if any(marker in stdout_text for marker in SCOPE_REPAIR_MARKERS):
            evidence["repair_kind"] = "allowed_paths"
            evidence["repair_fields"] = ["allowed_paths"]
            evidence["requested_path_hints"] = requested_scope_path_hints(stdout_tail)
        return evidence
    if explicit_nonpass_statuses:
        check_status = explicit_nonpass_statuses[-1]
        return {
            "ok": False,
            "route": "needs_worker_fix",
            "check_status": check_status,
            "integration_status": "needs_worker_fix",
            "reason": f"worker explicitly reported required checks as {check_status}",
            "negative_markers": negative_hits,
            "positive_markers": positive_hits,
            "explicit_check_statuses": explicit_statuses,
        }
    if explicit_pass_hits:
        return {
            "ok": True,
            "route": "agent_done",
            "check_status": "passed",
            "integration_status": "pending",
            "reason": "worker output contains explicit passed check_status",
            "negative_markers": [],
            "positive_markers": [*positive_hits, *explicit_pass_hits],
        }
    if model_limit_hits:
        return {
            "ok": False,
            "route": "blocked",
            "check_status": "model_limit",
            "integration_status": "blocked_model_limit",
            "reason": "worker could not start because the requested model was unavailable or usage-limited",
            "model_limit_markers": model_limit_hits,
            "negative_markers": negative_hits,
            "positive_markers": positive_hits,
        }
    if negative_hits:
        return {
            "ok": False,
            "route": "agent_done",
            "check_status": "failed_or_skipped",
            "integration_status": "pending_checks",
            "reason": "worker result requires Integrator checks or triage",
            "negative_markers": negative_hits,
            "positive_markers": positive_hits,
        }
    if positive_hits:
        return {
            "ok": True,
            "route": "agent_done",
            "check_status": "passed",
            "integration_status": "pending",
            "reason": "worker output contains check evidence",
            "negative_markers": [],
            "positive_markers": positive_hits,
        }
    return {
        "ok": False,
        "route": "agent_done",
        "check_status": "not_run_or_missing",
        "integration_status": "pending_checks",
        "reason": "worker output lacks targeted check evidence; Integrator must run required checks",
        "negative_markers": [],
        "positive_markers": [],
    }


def update_worker_task_result(
    worktree: Path,
    task_id: str,
    evidence: dict[str, Any],
    *,
    worker_report: str | None = None,
) -> bool:
    queue_path = task_manager_dir(worktree) / "task_queue.json"
    if not queue_path.exists():
        return False
    data = load_json(queue_path)
    if not isinstance(data, dict):
        return False
    tasks = data.get("tasks")
    if not isinstance(tasks, list):
        return False
    now = utc_now()
    changed = False
    for task in tasks:
        if not isinstance(task, dict) or str(task.get("id") or "") != str(task_id):
            continue
        before = str(task.get("status") or "")
        if evidence.get("route") == "agent_done":
            promotable_statuses = {"in_progress", "planned", "worker_ready", "needs_worker_fix"}
            task["status"] = "agent_done" if before in promotable_statuses else before or "agent_done"
            task["lock"] = "review"
            task["integration_status"] = str(evidence.get("integration_status") or "pending")
            reason = (
                "worker result includes targeted check evidence"
                if evidence.get("ok")
                else str(evidence.get("reason") or "worker result requires Integrator checks")
            )
            next_owner = "Integrator"
        elif evidence.get("route") == "needs_dispatcher_repair":
            task["status"] = "needs_dispatcher_repair"
            task["lock"] = "free"
            task["worker_ready"] = False
            task["dispatcher_decision"] = "needs_dispatcher_repair"
            task["packet_status"] = "needs_dispatcher_repair"
            task["normalization_status"] = "needs_dispatcher_repair"
            task["integration_status"] = "needs_dispatcher_repair"
            task["repair_request"] = str(
                evidence.get("reason")
                or "Dispatcher must reconcile the assigned packet against current canonical state"
            )
            repair_fields = [
                str(value)
                for value in evidence.get("repair_fields") or []
                if str(value)
            ]
            task["missing_packet_fields"] = repair_fields or ["current_context_or_source_freshness"]
            requested_paths = [
                str(value)
                for value in evidence.get("requested_allowed_paths") or []
                if str(value)
            ]
            if requested_paths:
                task["requested_allowed_paths"] = requested_paths
                task["requested_allowed_paths_verified_by"] = str(
                    evidence.get("requested_allowed_paths_verified_by")
                    or "scripts/agent_control/run_worker_cycle.py"
                )
                task["dispatcher_repair_kind"] = str(evidence.get("repair_kind") or "allowed_paths")
            task["repair_owner"] = "Dispatcher"
            task["next_action"] = (
                "Reconcile the stale or incomplete packet against current develop, then close it as "
                "superseded or issue one fresh Worker Packet v2."
            )
            task["next_role"] = "auto_dispatcher"
            task["next_owner"] = "Dispatcher"
            reason = task["repair_request"]
            next_owner = "Dispatcher"
        elif evidence.get("route") == "blocked":
            task["status"] = "blocked"
            task["lock"] = "free"
            task["worker_ready"] = False
            task["dispatcher_decision"] = "blocked_by_missing_environment"
            task["integration_status"] = str(evidence.get("integration_status") or "blocked")
            task["blocked_reason"] = str(evidence.get("reason") or "worker execution blocked")
            reason = task["blocked_reason"]
            next_owner = "Dispatcher"
        else:
            task["status"] = "needs_worker_fix"
            task["lock"] = "free"
            task["worker_ready"] = False
            task["dispatcher_decision"] = "needs_worker_fix"
            task["integration_status"] = "returned_to_worker"
            reason = str(evidence.get("reason") or "worker result lacks check evidence")
            next_owner = "worker"
        task["worker_check_evidence"] = evidence
        if worker_report:
            task["worker_report"] = worker_report
        task["status_reason"] = reason
        history = task.get("status_history")
        if not isinstance(history, list):
            history = []
        history.append(
            {
                "at": now,
                "by": "run_worker_cycle",
                "from": before,
                "to": task["status"],
                "reason": reason,
                "event": "worker_check_evidence_recorded",
                "next_owner": next_owner,
            }
        )
        task["status_history"] = history
        changed = True
        break
    if changed:
        queue_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return changed


def record_worker_integration_candidate(
    state_root: Path,
    task_id: str,
    launch: dict[str, Any],
    finalize_result: dict[str, Any],
) -> bool:
    if os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY") != "postgres":
        return False
    queue_path = task_manager_dir(state_root) / "task_queue.json"
    queue = load_json(queue_path) if queue_path.exists() else None
    if not isinstance(queue, dict):
        return False
    head_sha = str(finalize_result.get("head_sha") or "").strip().lower()
    base_sha = str(launch.get("base_ref_sha") or "").strip().lower()
    branch = str(finalize_result.get("branch") or launch.get("branch") or "").strip()
    if not re.fullmatch(r"[0-9a-f]{40}", head_sha) or not re.fullmatch(r"[0-9a-f]{40}", base_sha) or not branch:
        return False
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict) or str(task.get("id") or task.get("task_id") or "") != task_id:
            continue
        project_id = os.environ.get("AISTUDIO_TASK_CONTROL_PROJECT_ID", "").strip()
        task["integration_candidate"] = {
            "candidate_id": worker_integration_candidate_id(
                project_id or state_root.name,
                task_id,
                branch,
            ),
            "state": "ready",
            "base_branch": str(launch.get("base_ref") or "develop").removeprefix("origin/"),
            "base_sha": base_sha,
            "work_branch": branch,
            "head_sha": head_sha,
            "changed_paths": [str(path) for path in finalize_result.get("changed_paths") or []],
            "evidence": {
                "source": "run_worker_cycle",
                "worker_report": finalize_result.get("worker_report"),
                "pushed": finalize_result.get("pushed") is True,
            },
        }
        queue["updated_at"] = utc_now()
        write_json(queue_path, queue)
        return True
    return False


def worker_integration_candidate_id(
    project_id: str,
    task_id: str,
    work_branch: str,
) -> str:
    identity = f"{project_id}\n{work_branch}\n{task_id}".encode("utf-8")
    return f"worker-{hashlib.sha256(identity).hexdigest()[:24]}"


def record_sql_worker_integration_candidate(
    task_id: str,
    launch: dict[str, Any],
    finalize_result: dict[str, Any],
    *,
    state: str,
) -> dict[str, Any] | None:
    if os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY", "").strip() != "postgres":
        return None
    project_id = os.environ.get("AISTUDIO_TASK_CONTROL_PROJECT_ID", "").strip()
    session_id = os.environ.get("AISTUDIO_TASK_CONTROL_SESSION_ID", "").strip()
    dsn_env = os.environ.get("AISTUDIO_TASK_DB_DSN_ENV", "").strip()
    dsn = os.environ.get(dsn_env, "") if dsn_env else ""
    if not project_id or not session_id or not dsn:
        raise TaskControlConfigurationError(
            "SQL worker candidate requires project, session, and configured DSN"
        )
    head_sha = require_commit_sha(finalize_result.get("head_sha"), "worker head_sha")
    base_sha = require_commit_sha(launch.get("base_ref_sha"), "worker base_ref_sha")
    branch = str(finalize_result.get("branch") or launch.get("branch") or "").strip()
    if not branch:
        raise TaskControlConfigurationError("SQL worker candidate requires work branch")
    worker_report = str(finalize_result.get("worker_report") or "").strip()
    return TaskControlPostgres(dsn).upsert_integration_candidate(
        project_id,
        task_id,
        candidate_id=worker_integration_candidate_id(project_id, task_id, branch),
        state=state,
        base_branch=str(launch.get("base_ref") or "develop").removeprefix("origin/"),
        base_sha=base_sha,
        work_branch=branch,
        head_sha=head_sha,
        session_id=session_id,
        changed_paths=[str(path) for path in finalize_result.get("changed_paths") or []],
        evidence={
            "source": "run_worker_cycle",
            "session_id": session_id,
            "worker_report": worker_report or None,
            "pushed": state == "ready",
        },
    )


def write_worker_report(worktree: Path, launch: dict[str, Any], task_id: str) -> Path:
    report_dir = worktree / "docs" / "reports" / "workers"
    report_dir.mkdir(parents=True, exist_ok=True)
    safe_task = str(task_id).replace("/", "-").replace("\\", "-")
    report_path = report_dir / f"WORKER_RESULT_{safe_task}_{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.md"
    stdout_log = Path(str(launch.get("stdout_log") or ""))
    stderr_log = Path(str(launch.get("stderr_log") or ""))
    stdout_tail = report_log_text(stdout_log)
    stderr_tail = report_log_text(stderr_log)
    check_evidence = classify_check_evidence(stdout_tail, stderr_tail)
    if check_evidence.get("route") == "needs_dispatcher_repair":
        requested_paths = resolve_requested_allowed_paths(
            worktree,
            [str(value) for value in check_evidence.get("requested_path_hints") or []],
        )
        if requested_paths:
            check_evidence["requested_allowed_paths"] = requested_paths
            check_evidence["requested_allowed_paths_verified_by"] = (
                "scripts/agent_control/run_worker_cycle.py"
            )
    report_relpath = report_path.relative_to(worktree).as_posix()
    update_worker_task_result(worktree, task_id, check_evidence, worker_report=report_relpath)
    lines = [
        f"# Worker Result {task_id}",
        "",
        f"- Generated: `{utc_now()}`",
        f"- Worker: `{launch.get('worker_id')}`",
        f"- Model: `{launch.get('model')}`",
        f"- Branch: `{launch.get('branch')}`",
        f"- Worktree: `{worktree}`",
        f"- Result: `{check_evidence.get('route')}`",
        f"- Check evidence: `{check_evidence.get('check_status') or ('passed' if check_evidence.get('ok') else 'pending_integrator')}`",
        f"- Next owner: `{'Integrator' if check_evidence.get('route') == 'agent_done' else 'Dispatcher' if check_evidence.get('route') in {'blocked', 'needs_dispatcher_repair'} else 'worker'}`",
        "",
        "## Check Evidence",
        "",
        "```json",
        json.dumps(check_evidence, ensure_ascii=False, indent=2),
        "```",
        "",
        "## stdout",
        "",
        "```text",
        stdout_tail,
        "```",
        "",
        "## stderr",
        "",
        "```text",
        stderr_tail,
        "```",
        "",
    ]
    report_path.write_text("\n".join(lines), encoding="utf-8")
    return report_path


def changed_paths_from_porcelain(status_out: str) -> list[str]:
    paths: list[str] = []
    for line in status_out.splitlines():
        if not line.strip() or len(line) < 4:
            continue
        # Unstaged paths are covered by git diff and untracked files by
        # git ls-files. Only the index column is needed here.
        if line[0] in {" ", "?"}:
            continue
        path = line[3:].strip()
        if " -> " in path:
            source, target = path.rsplit(" -> ", 1)
            paths.extend(
                endpoint.strip('"').replace("\\", "/")
                for endpoint in (source, target)
            )
            continue
        paths.append(path.strip('"').replace("\\", "/"))
    return paths


def task_allowed_paths(worktree: Path, task_id: str) -> list[str]:
    queue_path = task_manager_dir(worktree) / "task_queue.json"
    try:
        queue = json.loads(queue_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    return task_allowed_paths_from_queue(queue, task_id)


def task_allowed_paths_from_queue(queue: Any, task_id: str) -> list[str]:
    if not isinstance(queue, dict):
        return []
    for task in queue.get("tasks") or []:
        if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "") == task_id:
            return [str(item).replace("\\", "/") for item in task.get("allowed_paths") or [] if str(item)]
    return []


def task_forbidden_paths_from_queue(queue: Any, task_id: str) -> list[str]:
    if not isinstance(queue, dict):
        return []
    for task in queue.get("tasks") or []:
        if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "") == task_id:
            return [str(item).replace("\\", "/") for item in task.get("forbidden_paths") or [] if str(item)]
    return []


def checked_out_context_paths(launch: dict[str, Any]) -> list[str]:
    paths: list[str] = []
    for item in launch.get("context_checkout") or []:
        if not isinstance(item, dict) or item.get("state") != "checked_out":
            continue
        normalized = normalize_runner_context_path(item.get("path"))
        if normalized not in paths:
            paths.append(normalized)
    return paths


def normalize_runner_context_path(value: Any) -> str:
    normalized = str(value or "").replace("\\", "/").strip("/")
    parts = normalized.split("/") if normalized else []
    if (
        not parts
        or normalized.startswith(":")
        or any(part in {"", ".", ".."} for part in parts)
        or parts[0] == ".git"
    ):
        raise ValueError(f"unsafe runner context path: {normalized or '<empty>'}")
    return normalized


def normalize_resume_context_manifest(paths: list[str]) -> list[str]:
    normalized: list[str] = []
    for value in paths:
        path = normalize_runner_context_path(value)
        if path not in normalized:
            normalized.append(path)
    if not normalized:
        raise RuntimeError("resume requires an externally pinned context path manifest")
    if WORKER_QUEUE_REL not in normalized:
        raise RuntimeError("resume context path manifest must contain the canonical task queue")
    return sorted(normalized)


def context_manifest_digest(paths: list[str]) -> str:
    return hashlib.sha256(("\n".join(paths) + "\n").encode("utf-8")).hexdigest()


def context_ref_sha(launch: dict[str, Any]) -> str | None:
    value = str(launch.get("context_ref_sha") or "").strip().lower()
    if not value:
        return None
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("invalid immutable context_ref_sha in launch evidence")
    return value


def context_baseline_text(worktree: Path, launch: dict[str, Any], path: str) -> tuple[str, str]:
    sha = context_ref_sha(launch)
    if not sha:
        raise RuntimeError("immutable context_ref_sha is required for runner context reconciliation")
    source = f"{sha}:{path}"
    code, stdout, stderr = git_output(["git", "show", source], worktree)
    if code != 0:
        raise RuntimeError(f"unable to read runner context baseline for {path}: {stderr.strip()}")
    return stdout, "immutable_context_sha"


def runner_context_reconcile_baseline(
    worktree: Path,
    launch: dict[str, Any],
    result: dict[str, Any],
) -> tuple[str, str]:
    """Select a pinned or canonical-ancestor baseline for runner-owned context."""
    pinned_sha = context_ref_sha(launch)
    if not pinned_sha:
        raise RuntimeError("immutable context_ref_sha is required for runner context reconciliation")

    head_command = ["git", "rev-parse", "--verify", "HEAD^{commit}"]
    head_code, head_out, head_err = git_output(head_command, worktree)
    result["commands"].append(
        {
            "command": " ".join(head_command),
            "exit_code": head_code,
            "stdout": head_out,
            "stderr": head_err,
        }
    )
    head_sha = head_out.strip().lower()
    if head_code != 0 or not re.fullmatch(r"[0-9a-f]{40}", head_sha):
        raise RuntimeError(f"unable to resolve worker HEAD: {head_err.strip()}")
    if head_sha == pinned_sha:
        return pinned_sha, "immutable_context_sha"

    pinned_to_head = ["git", "merge-base", "--is-ancestor", pinned_sha, head_sha]
    ancestor_code, ancestor_out, ancestor_err = git_output(pinned_to_head, worktree)
    result["commands"].append(
        {
            "command": " ".join(pinned_to_head),
            "exit_code": ancestor_code,
            "stdout": ancestor_out,
            "stderr": ancestor_err,
        }
    )
    if ancestor_code != 0:
        initial_base_sha = str(launch.get("base_ref_sha") or "").strip().lower()
        if initial_base_sha and head_sha != initial_base_sha:
            raise RuntimeError("worker HEAD is not a descendant of the pinned runner context")
        return pinned_sha, "immutable_context_sha"

    base_ref = str(launch.get("context_ref") or launch.get("base_ref") or "").strip()
    if not base_ref.startswith("origin/") or not base_ref.removeprefix("origin/"):
        raise RuntimeError("runner context HEAD advance requires an origin canonical base ref")
    base_command = ["git", "rev-parse", "--verify", f"{base_ref}^{{commit}}"]
    base_code, base_out, base_err = git_output(base_command, worktree)
    result["commands"].append(
        {
            "command": " ".join(base_command),
            "exit_code": base_code,
            "stdout": base_out,
            "stderr": base_err,
        }
    )
    base_sha = base_out.strip().lower()
    if base_code != 0 or not re.fullmatch(r"[0-9a-f]{40}", base_sha):
        raise RuntimeError(f"unable to resolve current runner context base {base_ref}: {base_err.strip()}")

    head_to_base = ["git", "merge-base", "--is-ancestor", head_sha, base_sha]
    reachable_code, reachable_out, reachable_err = git_output(head_to_base, worktree)
    result["commands"].append(
        {
            "command": " ".join(head_to_base),
            "exit_code": reachable_code,
            "stdout": reachable_out,
            "stderr": reachable_err,
        }
    )
    if reachable_code != 0:
        raise RuntimeError(f"worker HEAD is not reachable from current {base_ref}")

    result["runner_context_pinned_sha"] = pinned_sha
    result["runner_context_baseline_sha"] = head_sha
    result["runner_context_base_ref"] = base_ref
    result["runner_context_base_sha"] = base_sha
    return head_sha, "trusted_canonical_head"


def queue_result_update_allowed_data(before: Any, after: Any, task_id: str) -> bool:
    before_tasks = before.get("tasks") if isinstance(before, dict) else None
    after_tasks = after.get("tasks") if isinstance(after, dict) else None
    if not isinstance(before_tasks, list) or not isinstance(after_tasks, list):
        return False
    if len(before_tasks) != len(after_tasks):
        return False
    before_without_tasks = dict(before)
    after_without_tasks = dict(after)
    before_without_tasks.pop("tasks", None)
    after_without_tasks.pop("tasks", None)
    if before_without_tasks != after_without_tasks:
        return False
    changed_task_count = 0
    for before_task, after_task in zip(before_tasks, after_tasks):
        if before_task == after_task:
            continue
        if not isinstance(before_task, dict) or not isinstance(after_task, dict):
            return False
        if str(before_task.get("id") or before_task.get("task_id") or "") != str(task_id):
            return False
        if str(after_task.get("id") or after_task.get("task_id") or "") != str(task_id):
            return False
        changed_keys = {key for key in set(before_task) | set(after_task) if before_task.get(key) != after_task.get(key)}
        if not changed_keys.issubset(RUNNER_TASK_RESULT_FIELDS):
            return False
        changed_task_count += 1
    return changed_task_count == 1


def path_allowed(path: str, allowed_paths: list[str]) -> bool:
    normalized = path.replace("\\", "/")
    for pattern in allowed_paths:
        allowed = pattern.strip().replace("\\", "/")
        if not allowed:
            continue
        if allowed.endswith("/**") and normalized.startswith(allowed[:-3].rstrip("/") + "/"):
            return True
        if allowed.endswith("/") and normalized.startswith(allowed):
            return True
        if any(char in allowed for char in "*?[]") and fnmatch.fnmatchcase(normalized, allowed):
            return True
        if normalized == allowed or normalized.startswith(allowed.rstrip("/") + "/"):
            return True
    return False


RUNNER_TASK_RESULT_FIELDS = {
    "status",
    "lock",
    "integration_status",
    "blocked_reason",
    "status_reason",
    "worker_check_evidence",
    "worker_report",
    "status_history",
    "worker_ready",
    "dispatcher_decision",
    "packet_status",
    "normalization_status",
    "repair_request",
    "missing_packet_fields",
    "repair_owner",
    "next_action",
    "next_role",
    "next_owner",
}


def worker_queue_result_update_allowed(worktree: Path, task_id: str) -> bool:
    queue_path = worktree / WORKER_QUEUE_REL
    if not queue_path.exists():
        return False
    head_code, head_out, _ = git_output(["git", "show", f"HEAD:{WORKER_QUEUE_REL}"], worktree)
    if head_code != 0:
        return False
    try:
        before = json.loads(head_out)
        after = json.loads(queue_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return queue_result_update_allowed_data(before, after, task_id)


def sanitize_worker_queue_result_update(worktree: Path, task_id: str) -> bool:
    """Keep only runner-owned result fields for the assigned task in task_queue."""
    queue_rel = WORKER_QUEUE_REL
    queue_path = worktree / queue_rel
    if not queue_path.exists():
        return False
    head_code, head_out, _ = git_output(["git", "show", f"HEAD:{queue_rel}"], worktree)
    if head_code != 0:
        return False
    try:
        before = json.loads(head_out)
        after = json.loads(queue_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    before_tasks = before.get("tasks") if isinstance(before, dict) else None
    after_tasks = after.get("tasks") if isinstance(after, dict) else None
    if not isinstance(before_tasks, list) or not isinstance(after_tasks, list):
        return False
    if len(before_tasks) != len(after_tasks):
        return False

    changed = False
    sanitized = json.loads(json.dumps(before, ensure_ascii=False))
    sanitized_tasks = sanitized.get("tasks")
    if not isinstance(sanitized_tasks, list):
        return False
    for idx, (before_task, after_task) in enumerate(zip(before_tasks, after_tasks)):
        if not isinstance(before_task, dict) or not isinstance(after_task, dict):
            return False
        before_id = str(before_task.get("id") or before_task.get("task_id") or "")
        after_id = str(after_task.get("id") or after_task.get("task_id") or "")
        if before_id != after_id:
            return False
        if before_id != str(task_id):
            continue
        result_fields = {key: after_task.get(key) for key in RUNNER_TASK_RESULT_FIELDS if before_task.get(key) != after_task.get(key)}
        if not result_fields:
            return False
        target = dict(before_task)
        for key, value in result_fields.items():
            if value is None:
                target.pop(key, None)
            else:
                target[key] = value
        sanitized_tasks[idx] = target
        changed = True
    if not changed:
        return False
    queue_path.write_text(json.dumps(sanitized, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


def restore_worker_queue_from_head(worktree: Path) -> bool:
    queue_rel = WORKER_QUEUE_REL
    queue_path = worktree / queue_rel
    restore_code, _, _ = git_output(git_longpaths_command("restore", "--source=HEAD", "--staged", "--worktree", "--", queue_rel), worktree)
    if restore_code == 0:
        return True
    head_code, head_out, _ = git_output(["git", "show", f"HEAD:{queue_rel}"], worktree)
    if head_code != 0:
        return False
    queue_path.parent.mkdir(parents=True, exist_ok=True)
    queue_path.write_text(head_out, encoding="utf-8")
    reset_code, _, _ = git_output(git_longpaths_command("reset", "-q", "HEAD", "--", queue_rel), worktree)
    return reset_code == 0


def reconcile_runner_context_paths(
    worktree: Path,
    launch: dict[str, Any],
    task_id: str,
    result: dict[str, Any],
) -> bool:
    """Validate launcher-owned context against its baseline, then remove it from the Worker diff."""
    try:
        context_paths = checked_out_context_paths(launch)
    except ValueError as exc:
        result["error"] = str(exc)
        return False
    if not context_paths:
        return True

    try:
        baseline_sha, baseline_kind = runner_context_reconcile_baseline(worktree, launch, result)
    except (ValueError, RuntimeError) as exc:
        result["error"] = f"runner context baseline validation failed: {exc}"
        return False

    queue_baseline: Any = None
    queue_path = worktree / WORKER_QUEUE_REL
    if WORKER_QUEUE_REL in context_paths:
        try:
            baseline_code, baseline_text, baseline_err = git_output(
                ["git", "show", f"{baseline_sha}:{WORKER_QUEUE_REL}"],
                worktree,
            )
            if baseline_code != 0:
                raise RuntimeError(
                    f"unable to read runner context baseline for {WORKER_QUEUE_REL}: {baseline_err.strip()}"
                )
            queue_baseline = json.loads(baseline_text)
            queue_after = json.loads(queue_path.read_text(encoding="utf-8"))
        except (OSError, RuntimeError, json.JSONDecodeError) as exc:
            result["error"] = f"runner context queue validation failed: {exc}"
            return False
        if not queue_result_update_allowed_data(queue_baseline, queue_after, task_id):
            result["runner_context_modified_paths"] = [WORKER_QUEUE_REL]
            result["error"] = "runner-owned context changed beyond assigned task result fields"
            return False
        result["queue_context_baseline"] = baseline_kind

    modified_context_paths: list[str] = []
    for path in context_paths:
        if path == WORKER_QUEUE_REL:
            continue
        command = ["git", "diff", "--quiet", "--no-ext-diff", baseline_sha, "--", literal_pathspec(path)]
        code, stdout, stderr = git_output(command, worktree)
        result["commands"].append(
            {
                "command": " ".join(command),
                "exit_code": code,
                "stdout": stdout,
                "stderr": stderr,
            }
        )
        if code != 0:
            modified_context_paths.append(path)
    if modified_context_paths:
        result["runner_context_modified_paths"] = modified_context_paths
        result["error"] = "runner-owned context changed after launcher checkout"
        return False

    restored: list[str] = []
    for path in context_paths:
        command = git_longpaths_command(
            "restore",
            "--source=HEAD",
            "--staged",
            "--worktree",
            "--",
            literal_pathspec(path),
        )
        code, stdout, stderr = git_output(command, worktree)
        result["commands"].append(
            {
                "command": f"git restore runner context {path}",
                "exit_code": code,
                "stdout": stdout,
                "stderr": stderr,
            }
        )
        if code != 0:
            result["restored_runner_context_paths"] = restored
            result["error"] = f"failed to restore runner-owned context path: {path}"
            return False
        restored.append(path)
    result["restored_runner_context_paths"] = restored
    result["dropped_worker_queue_result_update"] = WORKER_QUEUE_REL in restored
    return True


def run_finalize_consistency_guard(
    guard: Callable[[str], tuple[bool, str]] | None,
    result: dict[str, Any],
    stage: str,
) -> bool:
    if guard is None:
        return True
    try:
        ok, reason = guard(stage)
    except (OSError, RuntimeError, json.JSONDecodeError) as exc:
        ok, reason = False, str(exc)
    result["commands"].append(
        {
            "command": f"resume consistency guard ({stage})",
            "exit_code": 0 if ok else 1,
            "stdout": "",
            "stderr": "" if ok else reason,
        }
    )
    if not ok:
        result["error"] = f"resume consistency guard failed at {stage}: {reason}"
    return ok


def finalize_worker_branch(
    project_root: Path,
    launch: dict[str, Any],
    task_id: str,
    *,
    trusted_allowed_paths: list[str] | None = None,
    trusted_forbidden_paths: list[str] | None = None,
    consistency_guard: Callable[[str], tuple[bool, str]] | None = None,
    allow_push_recovery: bool = True,
) -> dict[str, Any]:
    worktree = Path(str(launch.get("worktree") or "")).resolve()
    branch = str(launch.get("branch") or "").strip()
    result: dict[str, Any] = {"worktree": str(worktree), "branch": branch, "committed": False, "pushed": False, "commands": []}

    def persist_sql_candidate(state: str) -> bool:
        try:
            candidate = record_sql_worker_integration_candidate(
                task_id,
                launch,
                result,
                state=state,
            )
        except Exception as exc:
            result["error"] = f"SQL worker integration candidate {state} record failed: {exc}"
            result["sql_integration_candidate_error"] = {
                "state": state,
                "error_type": type(exc).__name__,
                "message": str(exc),
            }
            result["recovery_required"] = state == "ready"
            return False
        if candidate is not None:
            result["sql_integration_candidate"] = candidate
        return True
    if not worktree.exists() or not branch:
        result["error"] = "missing worker worktree or branch"
        return result
    if trusted_allowed_paths is not None:
        allowed_paths = [str(path).replace("\\", "/") for path in trusted_allowed_paths if str(path)]
        forbidden_paths = [
            str(path).replace("\\", "/") for path in (trusted_forbidden_paths or []) if str(path)
        ]
        if not allowed_paths:
            result["error"] = "trusted allowed_paths are empty"
            return result
    else:
        try:
            context_paths = checked_out_context_paths(launch)
            if WORKER_QUEUE_REL in context_paths:
                queue_text, _baseline_kind = context_baseline_text(worktree, launch, WORKER_QUEUE_REL)
                allowed_paths = task_allowed_paths_from_queue(json.loads(queue_text), task_id)
                forbidden_paths = task_forbidden_paths_from_queue(json.loads(queue_text), task_id)
                if not allowed_paths:
                    result["error"] = "assigned task missing allowed_paths in runner context baseline"
                    return result
            else:
                allowed_paths = task_allowed_paths(worktree, task_id)
                queue_path = task_manager_dir(worktree) / "task_queue.json"
                try:
                    queue_data = json.loads(queue_path.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError):
                    queue_data = {}
                forbidden_paths = task_forbidden_paths_from_queue(queue_data, task_id)
        except (ValueError, RuntimeError, json.JSONDecodeError) as exc:
            result["error"] = f"unable to load trusted task context: {exc}"
            return result
    task_scope_paths = list(allowed_paths)
    report_path = write_worker_report(worktree, launch, task_id)
    if not reconcile_runner_context_paths(worktree, launch, task_id, result):
        return result
    changed_paths: list[str] = []
    outside_allowed: list[str] = []
    has_changes = False
    for attempt in range(FINALIZE_STATUS_ATTEMPTS):
        refresh_code, refresh_out, refresh_err = git_output(["git", "update-index", "-q", "--refresh"], worktree)
        result["commands"].append(
            {
                "command": "git update-index -q --refresh",
                "attempt": attempt + 1,
                "exit_code": refresh_code,
                "stdout": refresh_out,
                "stderr": refresh_err,
            }
        )
        if refresh_code != 0:
            result["error"] = "git update-index refresh failed"
            return result
        status_code, status_out, status_err = git_output(["git", "status", "--porcelain"], worktree)
        result["commands"].append(
            {
                "command": "git status --porcelain",
                "attempt": attempt + 1,
                "exit_code": status_code,
                "stdout": status_out,
                "stderr": status_err,
            }
        )
        if status_code != 0:
            result["error"] = "git status failed"
            return result
        diff_code, diff_out, diff_err = git_output(["git", "diff", "--name-only"], worktree)
        result["commands"].append(
            {
                "command": "git diff --name-only",
                "attempt": attempt + 1,
                "exit_code": diff_code,
                "stdout": diff_out,
                "stderr": diff_err,
            }
        )
        if diff_code != 0:
            result["error"] = "git diff failed"
            return result
        untracked_code, untracked_out, untracked_err = git_output(["git", "ls-files", "--others", "--exclude-standard"], worktree)
        result["commands"].append(
            {
                "command": "git ls-files --others --exclude-standard",
                "attempt": attempt + 1,
                "exit_code": untracked_code,
                "stdout": untracked_out,
                "stderr": untracked_err,
            }
        )
        if untracked_code != 0:
            result["error"] = "git ls-files failed"
            return result
        changed_paths = sorted(
            {
                line.strip().replace("\\", "/")
                for line in [*changed_paths_from_porcelain(status_out), *diff_out.splitlines(), *untracked_out.splitlines()]
                if line.strip()
            }
        )
        has_changes = bool(changed_paths)
        if not has_changes:
            break
        report_rel = str(report_path.relative_to(worktree)).replace("\\", "/")
        allowed_paths = [*task_scope_paths, report_rel, "docs/reports/workers/**"]
        outside_allowed = [path for path in changed_paths if not path_allowed(path, allowed_paths)]
        forbidden_changed = [path for path in changed_paths if path_allowed(path, forbidden_paths)]
        outside_allowed = sorted(set(outside_allowed) | set(forbidden_changed))
        queue_rel = WORKER_QUEUE_REL
        if outside_allowed == [queue_rel] and worker_queue_result_update_allowed(worktree, task_id):
            if restore_worker_queue_from_head(worktree):
                result["dropped_worker_queue_result_update"] = True
                continue
        elif queue_rel in outside_allowed and sanitize_worker_queue_result_update(worktree, task_id):
            if restore_worker_queue_from_head(worktree):
                result["dropped_worker_queue_result_update"] = True
                continue
        if not outside_allowed or attempt == FINALIZE_STATUS_ATTEMPTS - 1:
            break
        time.sleep(FINALIZE_STATUS_RETRY_DELAY_SECONDS)
    if has_changes:
        if outside_allowed:
            result["changed_paths"] = changed_paths
            result["allowed_paths"] = allowed_paths
            result["outside_allowed_paths"] = outside_allowed
            if forbidden_changed:
                result["forbidden_changed_paths"] = forbidden_changed
            result["error"] = "worker changed paths outside allowed_paths"
            return result
        if not run_finalize_consistency_guard(consistency_guard, result, "before_commit"):
            return result
        add_code, add_out, add_err = git_output(git_longpaths_command("add", "-A"), worktree)
        result["commands"].append({"command": "git add -A", "exit_code": add_code, "stdout": add_out, "stderr": add_err})
        if add_code != 0:
            result["error"] = "git add failed"
            return result
        staged_code, staged_out, staged_err = git_output(
            ["git", "diff", "--cached", "--name-only", "--no-renames", "-z"],
            worktree,
        )
        result["commands"].append(
            {
                "command": "git diff --cached --name-only --no-renames -z",
                "exit_code": staged_code,
                "stdout": staged_out,
                "stderr": staged_err,
            }
        )
        if staged_code != 0:
            result["error"] = "staged scope validation failed"
            return result
        staged_paths = sorted(
            path.replace("\\", "/")
            for path in staged_out.split("\0")
            if path
        )
        staged_forbidden = [path for path in staged_paths if path_allowed(path, forbidden_paths)]
        staged_context = [path for path in staged_paths if path in checked_out_context_paths(launch)]
        staged_outside = sorted(
            {
                path
                for path in staged_paths
                if not path_allowed(path, allowed_paths)
            }
            | set(staged_forbidden)
            | set(staged_context)
        )
        if staged_outside:
            result["changed_paths"] = staged_paths
            result["outside_allowed_paths"] = staged_outside
            if staged_forbidden:
                result["forbidden_changed_paths"] = staged_forbidden
            if staged_context:
                result["staged_runner_context_paths"] = staged_context
            result["error"] = "staged paths changed outside finalized scope"
            return result
        commit_code, commit_out, commit_err = git_output(
            git_longpaths_command("commit", "-m", f"chore(agent): worker result {task_id}"),
            worktree,
        )
        result["commands"].append({"command": f"git commit worker result {task_id}", "exit_code": commit_code, "stdout": commit_out, "stderr": commit_err})
        if commit_code != 0 and "nothing to commit" not in (commit_out + commit_err).lower():
            result["error"] = "git commit failed"
            return result
        result["committed"] = commit_code == 0
    if not run_finalize_consistency_guard(consistency_guard, result, "before_push"):
        return result
    cutover_authority = (
        os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY", "").strip() == "postgres"
    )
    if cutover_authority:
        head_code, head_out, head_err = git_output(["git", "rev-parse", "HEAD"], worktree)
        result["commands"].append(
            {
                "command": "git rev-parse HEAD before worker push",
                "exit_code": head_code,
                "stdout": head_out,
                "stderr": head_err,
            }
        )
        if head_code != 0:
            result["error"] = "worker HEAD is unreadable before push"
            return result
        result["head_sha"] = head_out.strip().lower()
        result["changed_paths"] = changed_paths
        result["worker_report"] = str(report_path.relative_to(worktree)).replace("\\", "/")
        if not persist_sql_candidate("integrating"):
            return result
    push_code, push_out, push_err = git_output(git_longpaths_command("push", "-u", "origin", branch), worktree)
    result["commands"].append({"command": f"git push -u origin {branch}", "exit_code": push_code, "stdout": push_out, "stderr": push_err})
    result["pushed"] = push_code == 0
    if push_code != 0:
        push_text = f"{push_out}\n{push_err}".lower()
        if allow_push_recovery and ("non-fast-forward" in push_text or "fetch first" in push_text or "behind" in push_text):
            fetch_ref = f"{branch}:refs/remotes/origin/{branch}"
            fetch_code, fetch_out, fetch_err = git_output(git_longpaths_command("fetch", "origin", fetch_ref), worktree)
            result["commands"].append({"command": f"git fetch origin {fetch_ref}", "exit_code": fetch_code, "stdout": fetch_out, "stderr": fetch_err})
            if fetch_code == 0:
                merge_ref = f"origin/{branch}"
                merge_code, merge_out, merge_err = git_output(git_longpaths_command("merge", "-s", "ours", "--no-edit", merge_ref), worktree)
                result["commands"].append({"command": f"git merge -s ours --no-edit {merge_ref}", "exit_code": merge_code, "stdout": merge_out, "stderr": merge_err})
                if merge_code == 0:
                    if cutover_authority:
                        head_code, head_out, head_err = git_output(
                            ["git", "rev-parse", "HEAD"], worktree
                        )
                        result["commands"].append(
                            {
                                "command": "git rev-parse HEAD before recovered worker push",
                                "exit_code": head_code,
                                "stdout": head_out,
                                "stderr": head_err,
                            }
                        )
                        if head_code != 0:
                            result["error"] = "worker HEAD is unreadable before recovered push"
                            return result
                        result["head_sha"] = head_out.strip().lower()
                        if not persist_sql_candidate("integrating"):
                            return result
                    retry_code, retry_out, retry_err = git_output(git_longpaths_command("push", "-u", "origin", branch), worktree)
                    result["commands"].append({"command": f"git push -u origin {branch} retry", "exit_code": retry_code, "stdout": retry_out, "stderr": retry_err})
                    result["pushed"] = retry_code == 0
                    if retry_code == 0:
                        result["push_recovered"] = "ours_merge_remote_branch"
                        if cutover_authority and not persist_sql_candidate("ready"):
                            return result
                        result["worker_report"] = str(report_path.relative_to(worktree)).replace("\\", "/")
                        return result
        result["error"] = "git push failed"
        return result
    if cutover_authority and not persist_sql_candidate("ready"):
        return result
    result["worker_report"] = str(report_path.relative_to(worktree)).replace("\\", "/")
    return result


def finalize_succeeded(finalize_result: dict[str, Any]) -> bool:
    if not isinstance(finalize_result, dict):
        return False
    if finalize_result.get("error"):
        return False
    return finalize_result.get("pushed") is True


def path_is_within(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
    except ValueError:
        return False
    return True


def unexpired_utc(value: Any) -> bool:
    text = str(value or "").strip()
    if not text:
        return False
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return False
    if parsed.tzinfo is None:
        return False
    return parsed.astimezone(timezone.utc) > datetime.now(timezone.utc)


def require_commit_sha(value: Any, label: str) -> str:
    sha = str(value or "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise RuntimeError(f"{label} must be an exact 40-character commit SHA")
    return sha


def git_common_dir(repo: Path) -> Path:
    code, stdout, stderr = git_output(["git", "rev-parse", "--git-common-dir"], repo)
    if code != 0 or not stdout.strip():
        raise RuntimeError(f"unable to resolve Git common directory: {stderr.strip()}")
    value = Path(stdout.strip())
    return (repo / value).resolve() if not value.is_absolute() else value.resolve()


def git_origin_url(repo: Path) -> str:
    code, stdout, stderr = git_output(["git", "remote", "get-url", "origin"], repo)
    if code != 0 or not stdout.strip():
        raise RuntimeError(f"unable to resolve origin identity: {stderr.strip()}")
    return stdout.strip()


def worktree_registered(project_root: Path, worktree: Path, branch: str) -> bool:
    code, stdout, _stderr = git_output(["git", "worktree", "list", "--porcelain"], project_root)
    if code != 0:
        return False
    current_path: Path | None = None
    current_branch = ""
    registrations: list[tuple[Path, str]] = []
    for line in [*stdout.splitlines(), ""]:
        if line.startswith("worktree "):
            current_path = Path(line.removeprefix("worktree ").strip()).resolve()
        elif line.startswith("branch "):
            current_branch = line.removeprefix("branch ").strip().removeprefix("refs/heads/")
        elif not line.strip() and current_path is not None:
            registrations.append((current_path, current_branch))
            current_path = None
            current_branch = ""
    return (worktree.resolve(), branch) in registrations


def git_operation_in_progress(repo: Path) -> str | None:
    for marker in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "rebase-merge", "rebase-apply"):
        code, marker_path, stderr = git_output(["git", "rev-parse", "--git-path", marker], repo)
        if code != 0:
            raise RuntimeError(f"unable to inspect Git operation state for {marker}: {stderr.strip()}")
        resolved_marker = Path(marker_path.strip())
        if not resolved_marker.is_absolute():
            resolved_marker = repo / resolved_marker
        if resolved_marker.exists():
            return marker
    return None


def remote_branch_sha(repo: Path, branch: str) -> str:
    code, stdout, stderr = git_output(["git", "ls-remote", "--heads", "origin", branch], repo)
    if code != 0:
        raise RuntimeError(f"remote branch check failed for {branch}: {stderr.strip()}")
    matches = [
        parts[0].lower()
        for line in stdout.splitlines()
        if len(parts := line.split()) == 2 and parts[1] == f"refs/heads/{branch}"
    ]
    if len(matches) != 1 or not re.fullmatch(r"[0-9a-f]{40}", matches[0]):
        raise RuntimeError(f"remote branch {branch} is missing or ambiguous")
    return matches[0]


def json_at_ref(repo: Path, ref: str, path: str) -> Any:
    code, stdout, stderr = git_output(["git", "show", f"{ref}:{path}"], repo)
    if code != 0:
        raise RuntimeError(f"unable to read {path} at {ref}: {stderr.strip()}")
    try:
        return json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"invalid UTF-8 JSON in {path} at {ref}: {exc}") from exc


def canonical_resume_authority(
    project_root: Path,
    base_ref: str,
    authority_sha: str,
    context_sha: str,
    worker_branch: str,
    expected_worker_head: str,
    task_id: str,
    worker_id: str,
    machine_id: str,
) -> dict[str, Any]:
    if not base_ref.startswith("origin/") or not base_ref.removeprefix("origin/"):
        raise RuntimeError("resume base_ref must name an origin branch")
    base_branch = base_ref.removeprefix("origin/")
    code, local_base, stderr = git_output(["git", "rev-parse", "--verify", f"{base_ref}^{{commit}}"], project_root)
    if code != 0 or local_base.strip().lower() != authority_sha:
        raise RuntimeError(f"resume authority SHA no longer matches {base_ref}: {stderr.strip()}")
    if remote_branch_sha(project_root, base_branch) != authority_sha:
        raise RuntimeError("remote canonical branch advanced after resume authority was pinned")
    context_code, _stdout, context_err = git_output(["git", "cat-file", "-e", f"{context_sha}^{{commit}}"], project_root)
    if context_code != 0:
        raise RuntimeError(f"resume context commit is missing: {context_err.strip()}")
    ancestor_code, _stdout, ancestor_err = git_output(
        ["git", "merge-base", "--is-ancestor", context_sha, authority_sha],
        project_root,
    )
    if ancestor_code != 0:
        raise RuntimeError(f"resume context commit is not an ancestor of canonical authority: {ancestor_err.strip()}")
    if remote_branch_sha(project_root, worker_branch) != expected_worker_head:
        raise RuntimeError("remote worker branch advanced after resume head was pinned")

    queue = json_at_ref(project_root, authority_sha, WORKER_QUEUE_REL)
    locks = json_at_ref(project_root, authority_sha, "AiStudio/Task_manager/agent_locks.json")
    tasks = [
        task
        for task in (queue.get("tasks") if isinstance(queue, dict) else []) or []
        if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "") == task_id
    ]
    if len(tasks) != 1:
        raise RuntimeError("resume requires exactly one canonical task row")
    task = tasks[0]
    if str(task.get("status") or "") != "in_progress" or str(task.get("lock") or "") != "locked":
        raise RuntimeError("resume canonical task is not in_progress with a locked lease")
    if task.get("worker_ready") is not True:
        raise RuntimeError("resume canonical task is not worker_ready")
    for key, expected in (("worker_id", worker_id), ("machine_id", machine_id)):
        if str(task.get(key) or "") != expected:
            raise RuntimeError(f"resume canonical task {key} mismatch")
    task_branch = str(task.get("branch") or task.get("github_branch") or "")
    if task_branch != worker_branch:
        raise RuntimeError("resume canonical task branch mismatch")
    if not unexpired_utc(task.get("lock_expires_at")):
        raise RuntimeError("resume canonical task lease is expired")

    active_locks = [
        lock
        for lock in (locks.get("locks") if isinstance(locks, dict) else []) or []
        if isinstance(lock, dict)
        and str(lock.get("task_id") or "") == task_id
        and str(lock.get("state") or "") in ACTIVE_LOCK_STATES
    ]
    if len(active_locks) != 1:
        raise RuntimeError("resume requires exactly one active canonical lock")
    lock = active_locks[0]
    if (
        str(lock.get("by") or "") != worker_id
        or str(lock.get("machine_id") or "") != machine_id
        or str(lock.get("branch") or "") != worker_branch
    ):
        raise RuntimeError("resume active lock identity does not match launch evidence")
    if not unexpired_utc(lock.get("expires_at")):
        raise RuntimeError("resume active lock is expired")
    allowed_paths = task_allowed_paths_from_queue(queue, task_id)
    if not allowed_paths:
        raise RuntimeError("resume canonical task has no allowed_paths")
    forbidden_paths = task_forbidden_paths_from_queue(queue, task_id)
    return {"allowed_paths": allowed_paths, "forbidden_paths": forbidden_paths, "task": task, "lock": lock}


def validate_resume_finalize_run(
    project_root: Path,
    runtime_root: Path,
    run_dir: Path,
    task_id: str,
    worker_id: str,
    base_ref: str,
    authority_sha: str,
    expected_worker_head: str,
    expected_context_sha: str,
    expected_context_paths: list[str],
) -> dict[str, Any]:
    authority_sha = require_commit_sha(authority_sha, "resume authority SHA")
    expected_worker_head = require_commit_sha(expected_worker_head, "resume expected worker head")
    expected_context_sha = require_commit_sha(expected_context_sha, "resume context SHA")
    expected_context_paths = normalize_resume_context_manifest(expected_context_paths)
    expected_context_manifest_digest = context_manifest_digest(expected_context_paths)
    run_dir = run_dir.expanduser().resolve()
    runtime_root = runtime_root.expanduser().resolve()
    if not path_is_within(run_dir, runtime_root / "runs"):
        raise RuntimeError("resume run directory is outside runtime-root/runs")
    launch_path = run_dir / "launch.json"
    launch = load_json(launch_path)
    if not isinstance(launch, dict):
        raise RuntimeError("resume launch.json is missing or invalid")
    launch_project = str(launch.get("project_root") or "").strip()
    launch_run_dir = str(launch.get("run_dir") or "").strip()
    if not launch_project or Path(launch_project).expanduser().resolve() != project_root.resolve():
        raise RuntimeError("resume launch project_root does not match canonical project")
    if not launch_run_dir or Path(launch_run_dir).expanduser().resolve() != run_dir:
        raise RuntimeError("resume launch run_dir does not match requested run directory")
    if str(launch.get("task_id") or "") != task_id:
        raise RuntimeError("resume task_id does not match launch evidence")
    if str(launch.get("worker_id") or "") != worker_id:
        raise RuntimeError("resume worker_id does not match launch evidence")

    worktree_text = str(launch.get("worktree") or "").strip()
    branch = str(launch.get("branch") or "").strip()
    machine_id = str(launch.get("machine_id") or "").strip()
    if not worktree_text:
        raise RuntimeError("resume launch does not identify a worker worktree")
    worktree = Path(worktree_text).expanduser().resolve()
    if not worktree.exists() or not path_is_within(worktree, runtime_root / "worker-worktrees"):
        raise RuntimeError("resume worktree is missing or outside runtime-root/worker-worktrees")
    if not branch.startswith("AiStudio/Agent/worker/"):
        raise RuntimeError("resume branch is not an Agent Core worker branch")
    if not machine_id:
        raise RuntimeError("resume launch is missing machine_id")
    launch_context_sha = str(launch.get("context_ref_sha") or "").strip().lower()
    if launch_context_sha and launch_context_sha != expected_context_sha:
        raise RuntimeError("resume context SHA conflicts with launch evidence")

    for key in ("stdout_log", "stderr_log"):
        log_text = str(launch.get(key) or "").strip()
        if not log_text:
            raise RuntimeError(f"resume launch is missing {key}")
        log_path = Path(log_text).expanduser().resolve()
        if not log_path.is_file() or not path_is_within(log_path, run_dir):
            raise RuntimeError(f"resume {key} is missing or outside the run directory")
    try:
        pid = int(launch.get("pid"))
    except (TypeError, ValueError):
        raise RuntimeError("resume launch pid is invalid") from None
    if pid <= 0 or pid_alive(pid):
        raise RuntimeError("resume worker process is still active or pid evidence is invalid")

    if git_common_dir(project_root) != git_common_dir(worktree):
        raise RuntimeError("resume worktree does not belong to the canonical Git repository")
    if git_origin_url(project_root) != git_origin_url(worktree):
        raise RuntimeError("resume worktree origin identity differs from the canonical repository")
    if not worktree_registered(project_root, worktree, branch):
        raise RuntimeError("resume worktree is not registered for the canonical worker branch")

    branch_code, current_branch, branch_err = git_output(["git", "symbolic-ref", "--quiet", "--short", "HEAD"], worktree)
    if branch_code != 0 or current_branch.strip() != branch:
        raise RuntimeError(f"resume worktree branch mismatch: {branch_err.strip() or current_branch.strip()}")
    head_code, local_head, head_err = git_output(["git", "rev-parse", "HEAD"], worktree)
    if head_code != 0:
        raise RuntimeError(f"resume worktree HEAD is unreadable: {head_err.strip()}")
    if local_head.strip().lower() != expected_worker_head:
        raise RuntimeError("resume local worker HEAD differs from the externally pinned head")

    authority = canonical_resume_authority(
        project_root,
        base_ref,
        authority_sha,
        expected_context_sha,
        branch,
        expected_worker_head,
        task_id,
        worker_id,
        machine_id,
    )
    launch_context_paths = sorted(checked_out_context_paths(launch))
    if launch_context_paths != expected_context_paths:
        raise RuntimeError("resume launch context paths differ from the externally pinned manifest")
    for path in expected_context_paths:
        code, _stdout, stderr = git_output(
            ["git", "diff", "--cached", "--quiet", expected_context_sha, "--", literal_pathspec(path)],
            worktree,
        )
        if code != 0:
            raise RuntimeError(f"resume staged context differs from pinned context commit for {path}: {stderr.strip()}")
    context_queue = json_at_ref(project_root, expected_context_sha, WORKER_QUEUE_REL)
    if task_allowed_paths_from_queue(context_queue, task_id) != authority["allowed_paths"]:
        raise RuntimeError("resume context task scope differs from canonical authority")
    if task_forbidden_paths_from_queue(context_queue, task_id) != authority["forbidden_paths"]:
        raise RuntimeError("resume context forbidden scope differs from canonical authority")

    trusted_launch = dict(launch)
    trusted_launch["context_ref_sha"] = expected_context_sha
    trusted_launch["context_checkout"] = [
        {"path": path, "state": "checked_out"} for path in expected_context_paths
    ]

    return {
        "launch": trusted_launch,
        "run_dir": str(run_dir),
        "worktree": str(worktree),
        "branch": branch,
        "local_head": expected_worker_head,
        "machine_id": machine_id,
        "active_lock_count": 1,
        "authority_sha": authority_sha,
        "context_sha": expected_context_sha,
        "context_paths": expected_context_paths,
        "context_manifest_digest": expected_context_manifest_digest,
        "trusted_allowed_paths": authority["allowed_paths"],
        "trusted_forbidden_paths": authority["forbidden_paths"],
    }


def resume_finalize_worker_run(
    project_root: Path,
    runtime_root: Path,
    run_dir: Path,
    task_id: str,
    worker_id: str,
    base_ref: str,
    authority_sha: str,
    expected_worker_head: str,
    expected_context_sha: str,
    expected_context_paths: list[str],
    dry_run: bool = False,
) -> dict[str, Any]:
    try:
        normalized_context_paths = normalize_resume_context_manifest(expected_context_paths)
    except (ValueError, RuntimeError) as exc:
        return {"status": "resume_finalize_blocked", "task_id": task_id, "worker_id": worker_id, "error": str(exc)}
    lock_key = hashlib.sha256(
        f"{project_root.resolve()}\n{run_dir.expanduser().resolve()}\n{task_id}".encode("utf-8")
    ).hexdigest()
    lock_path = runtime_root.expanduser().resolve() / "resume-finalize" / f"{lock_key}.json"

    def validate() -> dict[str, Any]:
        return validate_resume_finalize_run(
            project_root,
            runtime_root,
            run_dir,
            task_id,
            worker_id,
            base_ref,
            authority_sha,
            expected_worker_head,
            expected_context_sha,
            normalized_context_paths,
        )

    try:
        if dry_run:
            validation = validate()
            return {
                "status": "resume_finalize_validated",
                "task_id": task_id,
                "worker_id": worker_id,
                "dry_run": True,
                "validation": {
                    key: value
                    for key, value in validation.items()
                    if key not in {"launch", "trusted_allowed_paths", "trusted_forbidden_paths"}
                },
            }
        with execution_lease_manager.file_lock(lock_path, timeout_seconds=5.0):
            validation = validate()

            def consistency_guard(stage: str) -> tuple[bool, str]:
                try:
                    canonical_resume_authority(
                        project_root,
                        base_ref,
                        validation["authority_sha"],
                        validation["context_sha"],
                        validation["branch"],
                        validation["local_head"],
                        task_id,
                        worker_id,
                        validation["machine_id"],
                    )
                    branch_code, branch_out, branch_err = git_output(
                        ["git", "symbolic-ref", "--quiet", "--short", "HEAD"],
                        Path(validation["worktree"]),
                    )
                    if branch_code != 0 or branch_out.strip() != validation["branch"]:
                        raise RuntimeError(
                            f"resume local branch changed before {stage}: {branch_err.strip() or branch_out.strip()}"
                        )
                    operation = git_operation_in_progress(Path(validation["worktree"]))
                    if operation:
                        raise RuntimeError(f"resume Git operation {operation} is active before {stage}")
                    head_code, head_out, head_err = git_output(
                        ["git", "rev-parse", "HEAD"], Path(validation["worktree"])
                    )
                    if head_code != 0:
                        raise RuntimeError(f"resume local HEAD is unreadable before {stage}: {head_err.strip()}")
                    current_head = head_out.strip().lower()
                    if stage == "before_commit" and current_head != validation["local_head"]:
                        raise RuntimeError("resume local HEAD changed before commit")
                    if stage == "before_push":
                        count_code, count_out, count_err = git_output(
                            ["git", "rev-list", "--count", f'{validation["local_head"]}..{current_head}'],
                            Path(validation["worktree"]),
                        )
                        if count_code != 0 or count_out.strip() not in {"0", "1"}:
                            raise RuntimeError(
                                f"resume local commit count changed before push: {count_err.strip() or count_out.strip()}"
                            )
                        status_code, status_out, status_err = git_output(
                            ["git", "status", "--porcelain"], Path(validation["worktree"])
                        )
                        if status_code != 0 or status_out.strip():
                            raise RuntimeError(
                                f"resume worktree changed after commit: {status_err.strip() or status_out.strip()}"
                            )
                except (OSError, RuntimeError, json.JSONDecodeError) as exc:
                    return False, str(exc)
                return True, ""

            finalize_result = finalize_worker_branch(
                project_root,
                validation["launch"],
                task_id,
                trusted_allowed_paths=validation["trusted_allowed_paths"],
                trusted_forbidden_paths=validation["trusted_forbidden_paths"],
                consistency_guard=consistency_guard,
                allow_push_recovery=False,
            )
    except (OSError, RuntimeError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
        return {"status": "resume_finalize_blocked", "task_id": task_id, "worker_id": worker_id, "error": str(exc)}
    status = "worker_finalize_resumed" if finalize_succeeded(finalize_result) else "resume_finalize_failed"
    return {
        "status": status,
        "task_id": task_id,
        "worker_id": worker_id,
        "validation": {key: value for key, value in validation.items() if key != "launch"},
        "finalize_result": finalize_result,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run one worker profile in a central-claim task cycle.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base-ref", required=True, help="Queue/base branch or ref.")
    parser.add_argument("--worker-base-ref", help="Clean code base for isolated worker worktrees. Defaults to --base-ref.")
    parser.add_argument("--worker-context-ref", help="Ref to copy task context files from. Defaults to --base-ref when --worker-base-ref differs.")
    parser.add_argument("--push-ref", help="Branch to push claim commits to. Defaults to --base-ref without origin/.")
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--model", help="Explicit model selected for this lane.")
    parser.add_argument("--model-catalog", default=str(default_catalog_path()), help="Host-local Codex models_cache.json used by the pre-claim gate.")
    parser.add_argument("--reasoning-effort", help="Explicit reasoning effort selected for this lane.")
    parser.add_argument("--task-id", help="Run only this exact eligible task id, then stop.")
    parser.add_argument("--prompt", help="Worker prompt. Defaults from worker id.")
    parser.add_argument("--machine-id", default=os.environ.get("AGENT_MACHINE_ID", "aistudio"))
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--execution-lease-id", help="Host execution lease id to release when this worker cycle exits.")
    parser.add_argument("--max-tasks", type=int, default=0, help="0 means keep going until no eligible task or stop condition.")
    parser.add_argument("--task-delay", type=float, default=1.0, help="Seconds between completed task launches.")
    parser.add_argument("--cycle-delay", type=float, default=1800.0, help="Seconds to sleep when no task is available in watch mode.")
    parser.add_argument("--claim-push-retries", type=int, default=5, help="Retry central claims after rejected pushes.")
    parser.add_argument("--claim-push-retry-delay", type=float, default=1.0, help="Seconds between rejected-push claim retries.")
    parser.add_argument("--watch", action="store_true", help="After no eligible task, sleep and check again.")
    parser.add_argument("--queue-recovery-only", action="store_true", help="Run queue recovery pass only; do not claim or launch workers.")
    parser.add_argument("--recovery-apply", action="store_true", help="Apply queue recovery mutations (normalize/promotion) instead of dry-run mode.")
    parser.add_argument("--artifact-discovery-recovery", action="store_true", help="Run the safe Artifact Discovery scanner/classifier/router/report/normalizer cycle during queue recovery.")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--poll-seconds", type=float, default=15.0)
    parser.add_argument("--worker-timeout-seconds", type=float, default=7200.0, help="Maximum seconds to wait for one Codex worker. 0 disables the timeout.")
    parser.add_argument("--codex-bin", default=os.environ.get("CODEX_BIN", "codex"), help="Codex executable required before claiming a task.")
    parser.add_argument(
        "--resume-finalize-run-dir",
        help="Resume only the guarded commit/push finalization for one completed launch.json run.",
    )
    parser.add_argument("--resume-authority-sha", help="Exact fetched canonical base commit for resume authorization.")
    parser.add_argument("--resume-expected-head-sha", help="Exact local/remote Worker head allowed for resume.")
    parser.add_argument("--resume-context-sha", help="Exact claim/context commit whose staged blobs must match.")
    parser.add_argument(
        "--resume-context-path",
        action="append",
        default=[],
        help="Exact runner-owned context path; repeat for the complete immutable resume manifest.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Run claim in dry-run and do not launch workers.")
    parser.add_argument("--json", action="store_true", help="Accepted for automation_controller compatibility; output is already JSON lines.")
    args = parser.parse_args()

    project_root = Path(args.project_root).expanduser().resolve()
    if args.execution_lease_id:
        runtime_for_lease = Path(args.runtime_root).expanduser()
        atexit.register(execution_lease_manager.release, runtime_for_lease, args.execution_lease_id)
    prompt = args.prompt or PROMPTS.get(args.worker_id, args.worker_id)
    requested_model = args.model or resolve_worker_model(project_root, args.worker_id)
    completed = 0

    if args.resume_finalize_run_dir:
        if os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY") == "postgres":
            print(
                json.dumps(
                    {
                        "status": "resume_finalize_blocked",
                        "task_id": args.task_id,
                        "worker_id": args.worker_id,
                        "error": "SQL cutover requires a fresh managed project session; Git queue resume authority is disabled",
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 2
        missing_resume_args = [
            flag
            for flag, value in (
                ("--task-id", args.task_id),
                ("--resume-authority-sha", args.resume_authority_sha),
                ("--resume-expected-head-sha", args.resume_expected_head_sha),
                ("--resume-context-sha", args.resume_context_sha),
                ("--resume-context-path", args.resume_context_path),
            )
            if not value
        ]
        if missing_resume_args or not args.fetch:
            reason = (
                f"resume finalization requires: {', '.join(missing_resume_args)}"
                if missing_resume_args
                else "resume finalization requires --fetch"
            )
            print(json.dumps({"status": "resume_finalize_blocked", "error": reason}, ensure_ascii=False, indent=2))
            return 2
        fetch_code, fetch_out, fetch_err = git_output(["git", "fetch", "--all", "--prune"], project_root)
        if fetch_code != 0:
            print(
                json.dumps(
                    {
                        "status": "resume_finalize_blocked",
                        "error": "resume fetch failed",
                        "fetch": {"exit_code": fetch_code, "stdout": fetch_out, "stderr": fetch_err},
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 2
        resume_result = resume_finalize_worker_run(
            project_root,
            Path(args.runtime_root).expanduser().resolve(),
            Path(args.resume_finalize_run_dir),
            args.task_id,
            args.worker_id,
            args.base_ref,
            args.resume_authority_sha,
            args.resume_expected_head_sha,
            args.resume_context_sha,
            args.resume_context_path,
            dry_run=args.dry_run,
        )
        resume_ok = resume_result.get("status") in {"resume_finalize_validated", "worker_finalize_resumed"}
        if not args.dry_run:
            append_log(
                project_root,
                "worker-cycle",
                str(resume_result.get("status") or "resume_finalize_failed"),
                severity="info" if resume_ok else "error",
                worker_id=args.worker_id,
                task_id=args.task_id,
                run_dir=str(Path(args.resume_finalize_run_dir).expanduser()),
                resume_result=resume_result,
            )
        print(json.dumps(resume_result, ensure_ascii=False, indent=2))
        return 0 if resume_ok else 2

    if not model_is_available(requested_model):
        print(
            json.dumps(
                {
                    "status": "model_unavailable",
                    "worker_id": args.worker_id,
                    "model": requested_model,
                    "dry_run": bool(args.dry_run),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    host_readiness = (
        codex_worker_host_readiness(args.codex_bin, model=requested_model)
        if not args.dry_run and not args.queue_recovery_only
        else {"ok": True}
    )
    if not args.dry_run and not args.queue_recovery_only and not host_readiness.get("ok"):
        event = {
            "event_id": f"evt-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}-{uuid4().hex[:8]}",
            "created_at": utc_now(),
            "project": project_root.name,
            "event": "worker_host_unavailable",
            "role": args.worker_id,
            "next_role": "environment",
            "task_id": None,
            "pr": None,
            "branch": None,
            "severity": "warning",
            "consumed_by": None,
            "consumed_at": None,
            "payload": {
                "worker_id": args.worker_id,
                "codex_bin": args.codex_bin,
                "reason": host_readiness.get("reason") or "codex is not ready on this automation host",
                "host_readiness": host_readiness,
            },
        }
        append_event(project_root, event)
        print(json.dumps({
            "status": "worker_host_unavailable",
            "worker_id": args.worker_id,
            "codex_bin": args.codex_bin,
            "reason": host_readiness.get("reason"),
            "host_readiness": host_readiness,
        }, ensure_ascii=False, indent=2))
        return 0

    if args.queue_recovery_only:
        if args.dry_run:
            args.recovery_apply = False
        while True:
            code = run_recovery_cycle(project_root, args)
            if code != 0:
                return code
            if not args.watch:
                return 0
            print(json.dumps({"status": "recovery_cycle_complete", "next_in_seconds": args.cycle_delay}, ensure_ascii=False))
            time.sleep(max(1.0, args.cycle_delay))

    while True:
        claim_cmd = [
            sys.executable,
            str(script_path("claim_next_task.py")),
            "--project-root",
            str(project_root),
            "--base-ref",
            args.base_ref,
            "--worker-id",
            args.worker_id,
            "--machine-id",
            args.machine_id,
            "--runtime-root",
            args.runtime_root,
            "--push-retries",
            str(args.claim_push_retries),
            "--push-retry-delay",
            str(args.claim_push_retry_delay),
            "--json",
            "--requested-model",
            str(requested_model or ""),
            "--model-catalog",
            str(args.model_catalog),
        ]
        if args.task_id:
            claim_cmd.extend(["--task-id", args.task_id])
        if args.push_ref:
            claim_cmd.extend(["--push-ref", args.push_ref])
        if args.fetch:
            claim_cmd.append("--fetch")
        if args.dry_run:
            claim_cmd.append("--dry-run")

        code, claim, stdout, stderr = run_json(claim_cmd)
        if code != 0:
            print(json.dumps({"status": "claim_failed", "exit_code": code, "claim": claim, "stderr": stderr}, ensure_ascii=False, indent=2))
            return code
        if not claim.get("claimed"):
            print(json.dumps({"status": "no_task", "worker_id": args.worker_id, "claim": claim}, ensure_ascii=False, indent=2))
            if not args.watch:
                return 0
            time.sleep(max(1.0, args.cycle_delay))
            continue
        model = str(claim.get("resolved_model") or requested_model or "").strip()
        if not model or not model_is_available(model):
            print(json.dumps({"status": "model_unavailable", "worker_id": args.worker_id, "claim": claim}, ensure_ascii=False, indent=2))
            return 2
        if args.dry_run:
            print(json.dumps({"status": "dry_run_claim", "claim": claim}, ensure_ascii=False, indent=2))
            return 0

        launch_cmd = [
            sys.executable,
            str(script_path("launch_isolated_worker.py")),
            "--project-root",
            str(project_root),
            "--base-ref",
            args.worker_base_ref or args.base_ref,
            "--worker-id",
            args.worker_id,
            "--prompt",
            prompt,
            "--task-id",
            str(claim["task_id"]),
            "--task-title",
            str(claim.get("title") or ""),
            "--branch-name",
            str(claim["branch"]),
            "--machine-id",
            args.machine_id,
            "--runtime-root",
            args.runtime_root,
            "--codex-bin",
            args.codex_bin,
        ]
        if model:
            launch_cmd.extend(["--model", model])
        runtime_reasoning_effort = codex_cli_reasoning_effort(args.reasoning_effort)
        if runtime_reasoning_effort:
            launch_cmd.extend(["--reasoning-effort", runtime_reasoning_effort])
        context_ref = args.worker_context_ref
        if not context_ref and args.worker_base_ref and args.worker_base_ref != args.base_ref:
            context_ref = args.base_ref
        if context_ref:
            launch_cmd.extend(["--context-ref", context_ref])
        if args.fetch:
            launch_cmd.append("--fetch")
        code, launch, _stdout, launch_stderr = run_json(launch_cmd)
        if code != 0:
            reason = f"worker launch failed before isolated worker started: {launch_stderr.strip()[:500]}"
            state_root = Path(str(claim.get("worktree") or project_root)).expanduser().resolve()
            reset_changed = reset_launch_failed_claim(state_root, claim, reason) if state_root.exists() else False
            state_push: dict[str, Any] | None = None
            if reset_changed:
                state_push = commit_and_push_state(
                    state_root,
                    push_ref_name(args.base_ref, args.push_ref),
                    f"chore(runner): release failed launch {claim['task_id']}",
                )
            print(
                json.dumps(
                    {
                        "status": "launch_failed",
                        "exit_code": code,
                        "claim": claim,
                        "stderr": launch_stderr,
                        "launch_failure_release": {"changed": reset_changed, "state_push": state_push},
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return code

        pid = int(launch["pid"])
        append_log(
            project_root,
            "worker-cycle",
            "worker_launched",
            severity="info",
            worker_id=args.worker_id,
            task_id=claim.get("task_id"),
            pid=pid,
            branch=launch.get("branch"),
            worktree=launch.get("worktree"),
            stdout_log=launch.get("stdout_log"),
            stderr_log=launch.get("stderr_log"),
        )
        print(json.dumps({"status": "launched", "claim": claim, "launch": launch}, ensure_ascii=False, indent=2), flush=True)
        completed_before_wait = wait_for_pid(pid, args.poll_seconds, args.worker_timeout_seconds)
        if not completed_before_wait:
            evidence = {
                "ok": False,
                "route": "agent_done",
                "check_status": "worker_timeout",
                "integration_status": "pending_checks",
                "reason": f"worker exceeded timeout_seconds={args.worker_timeout_seconds}",
            }
            update_worker_task_result(Path(str(launch.get("worktree") or project_root)), str(claim["task_id"]), evidence)
            append_log(
                project_root,
                "worker-cycle",
                "worker_timeout",
                severity="error",
                worker_id=args.worker_id,
                task_id=claim.get("task_id"),
                pid=pid,
                timeout_seconds=args.worker_timeout_seconds,
                branch=launch.get("branch"),
            )
        finalize_result = finalize_worker_branch(project_root, launch, str(claim["task_id"]))
        completed += 1
        append_log(
            project_root,
            "worker-cycle",
            "worker_finished" if completed_before_wait else "worker_timeout_finalized",
            severity="info" if completed_before_wait else "warning",
            worker_id=args.worker_id,
            task_id=claim.get("task_id"),
            pid=pid,
            completed_count=completed,
            finalize_result=finalize_result,
        )
        print(
            json.dumps(
                {
                    "status": "worker_finished" if completed_before_wait else "worker_timeout_finalized",
                    "task_id": claim["task_id"],
                    "pid": pid,
                    "completed_count": completed,
                    "finalize_result": finalize_result,
                },
                ensure_ascii=False,
                indent=2,
            ),
            flush=True,
        )

        if not finalize_succeeded(finalize_result):
            append_log(
                project_root,
                "worker-cycle",
                "worker_finalize_failed",
                severity="error",
                worker_id=args.worker_id,
                task_id=claim.get("task_id"),
                pid=pid,
                completed_count=completed,
                finalize_result=finalize_result,
            )
            print(
                json.dumps(
                    {
                        "status": "worker_finalize_failed",
                        "worker_id": args.worker_id,
                        "task_id": claim["task_id"],
                        "completed_count": completed,
                        "next_role": "worker",
                        "finalize_result": finalize_result,
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                flush=True,
            )
            return 2

        record_worker_integration_candidate(
            project_root,
            str(claim["task_id"]),
            launch,
            finalize_result,
        )

        if args.task_id:
            print(
                json.dumps(
                    {
                        "status": "exact_task_complete",
                        "worker_id": args.worker_id,
                        "task_id": claim["task_id"],
                        "completed_count": completed,
                        "next_role": "auto_integrator",
                        "finalize_result": finalize_result,
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                flush=True,
            )
            return 0

        if args.max_tasks and completed >= args.max_tasks:
            emit_batch_complete(project_root, args.worker_id, completed, args.max_tasks)
            print(
                json.dumps(
                    {
                        "status": "batch_limit_reached",
                        "worker_id": args.worker_id,
                        "completed_count": completed,
                        "max_tasks": args.max_tasks,
                        "next_role": "auto_integrator",
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                flush=True,
            )
            return 0
        time.sleep(max(0.0, args.task_delay))


if __name__ == "__main__":
    raise SystemExit(main())
