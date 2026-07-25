#!/usr/bin/env python3
"""Read-only local dashboard for remote agent activity.

The dashboard scans adopted project queues and agent-run reports, stores a
small SQLite analytics snapshot, and serves HTML plus JSON endpoints.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import html
import os
import json
import re
import sqlite3
import subprocess
import sys
import shutil
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, quote, unquote, urlparse
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import command_bus
import remote_chat_bus
import remote_chat_server
import automation_worktree_planner
import collect_remote_automation_status
import project_registry
import project_doctor

try:
    import psutil as _psutil
except Exception:  # pragma: no cover - optional dependency fallback
    _psutil = None


COMPLETED_STATUSES = {
    "done",
    "agent_done",
    "owner_approved",
    "finalized",
    "integrated",
    "merged",
    "released",
    "archived",
    "completed",
    "production",
}
ACTIVE_STATUSES = {
    "in_progress",
    "running",
    "review",
    "integration",
    "finalization",
    "integration_ready",
    "integration_requested",
    "worker_ready",
    "locked",
    "draft_pr",
    "base_ready",
    "base_ready_integration_pr",
    "smoke_passed",
    "tests_passed",
    "staging_deployed",
    "release_candidate",
    "local_branch",
    "finalization_requested",
    "finalizer_auto_merge_ready",
    "claimed",
    "worker_claimed",
    "integration_package_ready",
    "pr_ready_for_review",
    "ready_for_merge",
    "ready_for_explicit_merge",
    "base_ready_for_owner_review",
    "stale_branch_report",
}
POSTPONED_STATUSES = {
    "postpone",
    "postponed",
    "deferred",
    "stale",
    "stale_or_superseded",
    "superseded",
    "duplicate_linked",
    "deprecated",
    "on_hold",
}
WAITING_STATUSES = {
    "planned",
    "todo",
    "queued",
    "ready",
    "backlog",
    "idea",
    "local_branch",
    "pending",
}
HUMAN_STATUSES = {
    "blocked",
    "failed",
    "needs_human",
    "needs_owner",
    "needs_review",
    "needs_stronger_agent",
    "needs_dispatcher_split",
    "needs_rework",
    "needs_worker_fix",
    "finalization_blocked",
    "integration_blocked",
    "test_failed",
    "review_blocked",
    "owner_review",
    "owner_approval_needed",
    "owner_input_needed",
}
TASK_PACKET_STATUSES = {
    "needs_task_packet",
    "needs_task_spec",
    "needs_task_specification",
    "needs_dispatcher",
    "needs_dispatcher_repair",
    "needs_dispatcher_split",
    "needs_architect",
    "needs_packet",
}
LOCK_STATUS_STATES = {
    "locked": "in_progress",
    "active": "in_progress",
    "in_progress": "in_progress",
    "running": "in_progress",
    "review": "review",
    "agent_done": "agent_done",
    "integration_ready": "integration_ready",
    "integration_requested": "integration_requested",
    "needs_rework": "needs_rework",
    "needs_review": "needs_human",
    "integration_blocked": "blocked",
    "integrated": "integrated",
    "done": "done",
    "finalization_blocked": "needs_human",
    "needs_human": "needs_human",
    "needs_stronger_agent": "needs_human",
    "needs_dispatcher_split": "needs_human",
    "finalization_requested": "in_progress",
    "finalizer_auto_merge_ready": "in_progress",
    "owner_review": "needs_human",
    "blocked": "blocked",
    "failed": "failed",
    "finalized": "done",
    "claimed": "in_progress",
    "worker_claimed": "in_progress",
    "released": None,
    "stale": None,
    "ready_for_review": "needs_human",
}
ACTIVE_LOCK_STATES = {"locked", "active", "running", "claimed", "worker_claimed", "in_progress"}
HUMAN_PACKET_ACTIVE_STATUSES = {"open", "acknowledged"}

GIT_REF_FETCH_TTL_SECONDS = 60
_GIT_REF_FETCH_STATE: dict[tuple[str, str], float] = {}
_GIT_REF_FETCH_LOCK = threading.Lock()
_GIT_REF_FETCH_ERROR: dict[tuple[str, str], str] = {}
GITHUB_ACCESS_TTL_SECONDS = 300
_GITHUB_ACCESS_STATE: dict[tuple[str, str], tuple[float, dict[str, Any]]] = {}
_GITHUB_ACCESS_LOCK = threading.Lock()
TASK_STATUS_RANK: dict[str, int] = {
    "planned": 0,
    "todo": 0,
    "queued": 0,
    "ready": 0,
    "backlog": 0,
    "task_packet": 0,
    "in_progress": 1,
    "running": 1,
    "review": 2,
    "agent_done": 7,
    "completed": 7,
    "worker_ready": 1,
    "locked": 1,
    "base_ready": 3,
    "draft_pr": 2,
    "integration_ready": 3,
    "base_ready_integration_pr": 3,
    "integration_requested": 4,
    "integration_conflict": 4,
    "needs_rework": 4,
    "needs_worker_fix": 4,
    "needs_stronger_agent": 4,
    "needs_dispatcher_review": 4,
    "needs_dispatcher_repair": 4,
    "needs_dispatcher_split": 4,
    "needs_architect": 4,
    "needs_task_spec": 4,
    "needs_task_packet": 4,
    "needs_review": 5,
    "needs_human": 5,
    "blocked": 5,
    "integration_blocked": 5,
    "finalization_blocked": 5,
    "test_failed": 5,
    "superseded": 5,
    "owner_review": 5,
    "needs_owner": 5,
    "owner_ready": 5,
    "owner_approval_needed": 5,
    "owner_input_needed": 5,
    "finalization_requested": 6,
    "finalizer_auto_merge_ready": 6,
    "failed": 6,
    "dry_run_ready": 6,
    "ready_for_explicit_merge": 6,
    "integration_package_ready": 6,
    "ready_for_merge": 6,
    "pr_ready_for_review": 6,
    "stale_branch_report": 6,
    "smoke_passed": 7,
    "tests_passed": 7,
    "staging_deployed": 7,
    "owner_approved": 9,
    "base_ready_for_owner_review": 8,
    "release_candidate": 9,
    "released": 9,
    "finalized": 8,
    "integrated": 8,
    "done": 9,
    "merged": 9,
    "archived": 9,
    "production": 9,
    "worker_claimed": 1,
    "claimed": 1,
    "idea": 0,
    "local_branch": 0,
    "pending": 0,
    "on_hold": 0,
    "ready_for_review": 5,
}

TASK_STATUS_COMPLETED_KEYWORDS = (
    "done",
    "merged",
    "released",
    "archived",
    "finalized",
    "owner_approved",
    "production",
)
TASK_STATUS_ACTIVE_KEYWORDS = (
    "ready",
    "running",
    "progress",
    "in_progress",
    "locked",
    "worker_ready",
    "integration_ready",
    "integration_requested",
    "integration_conflict",
    "base_ready",
    "base_ready_integration_pr",
    "draft_pr",
    "smoke_passed",
    "tests_passed",
    "staging_deployed",
    "ready_for_explicit_merge",
    "dry_run_ready",
    "finalization_requested",
    "finalizer_auto_merge_ready",
    "release_candidate",
    "integration",
    "claimed",
    "worker_claimed",
    "package_ready",
    "pr_ready",
)
TASK_STATUS_HUMAN_KEYWORDS = (
    "blocked",
    "needs_human",
    "needs_owner",
    "needs_review",
    "needs_stronger_agent",
    "needs_dispatcher_split",
    "needs_rework",
    "needs_worker_fix",
    "failed",
    "integration_conflict",
    "integration_blocked",
    "finalization_blocked",
    "test_failed",
    "superseded",
    "conflict",
    "owner_review",
    "owner_input",
    "owner_approval_needed",
    "owner_decision",
    "owner_input_needed",
    "review_blocked",
    "owner_ready",
    "ready_for_review",
    "owner_approval_needed",
    "owner_input_needed",
)
TASK_STATUS_POSTPONED_KEYWORDS = ("postponed", "postpone", "deferred", "stale", "on_hold")
TASK_STATUS_WAITING_KEYWORDS = ("planned", "todo", "queued", "backlog", "idea", "pending")

STATUS_UNKNOWN_TOKENS = (
    "status",
    "queue",
    "pipeline",
    "phase",
    "state",
)


def is_recognized_task_status(normalized: str) -> bool:
    if not normalized:
        return True
    if normalized in TASK_STATUS_RANK:
        return True
    if normalized in COMPLETED_STATUSES:
        return True
    if normalized in ACTIVE_STATUSES:
        return True
    if normalized in HUMAN_STATUSES:
        return True
    if normalized in POSTPONED_STATUSES:
        return True
    if normalized in WAITING_STATUSES:
        return True
    if normalized in TASK_PACKET_STATUSES:
        return True
    if any(token in normalized for token in TASK_STATUS_COMPLETED_KEYWORDS):
        return True
    if any(token in normalized for token in TASK_STATUS_ACTIVE_KEYWORDS):
        return True
    if any(token in normalized for token in TASK_STATUS_HUMAN_KEYWORDS):
        return True
    if any(token in normalized for token in TASK_STATUS_POSTPONED_KEYWORDS):
        return True
    if any(token in normalized for token in TASK_STATUS_WAITING_KEYWORDS):
        return True
    if any(token in normalized for token in STATUS_UNKNOWN_TOKENS):
        return False
    return False

WORKER_LANE_STATUSES = {
    "planned",
    "todo",
    "queued",
    "ready",
    "backlog",
    "worker_ready",
    "worker_claimed",
    "in_progress",
    "running",
    "claimed",
}
INTEGRATOR_LANE_STATUSES = {
    "agent_done",
    "integration_ready",
    "integration_requested",
    "review",
    "base_ready",
    "base_ready_integration_pr",
    "draft_pr",
    "base_ready_for_owner_review",
    "smoke_passed",
    "tests_passed",
    "staging_deployed",
    "integration_conflict",
    "owner_review",
    "ready_for_review",
}
FINALIZER_LANE_STATUSES = {
    "owner_approved",
    "integrated",
    "finalized",
    "merged",
    "done",
    "dry_run_ready",
    "ready_for_explicit_merge",
    "finalization_requested",
    "finalizer_auto_merge_ready",
    "release_candidate",
    "production",
    "archived",
    "released",
}
RUN_ROLE_TO_LANE = {
    "architecture": "architect",
    "architect": "architect",
    "architects": "architect",
    "auto_architect": "architect",
    "auto_architects": "architect",
    "dispatch": "dispatcher",
    "dispatcher": "dispatcher",
    "dispatchers": "dispatcher",
    "auto_dispatcher": "dispatcher",
    "auto_dispatchers": "dispatcher",
    "dispatcher_worker_bridge": "dispatcher",
    "dispatcher_integration_repair": "dispatcher",
    "clean_rebuild_router": "dispatcher",
    "clean-rebuild-router": "dispatcher",
    "dispatcher_integrator_router": "dispatcher",
    "dispatcher-integrator-router": "dispatcher",
    "implementation": "worker",
    "worker": "worker",
    "workers": "worker",
    "worker_integrator_bridge": "worker",
    "worker_result_handoff_gate": "worker",
    "worker-result-handoff-gate": "worker",
    "worker-sync": "worker",
    "worker_sync": "worker",
    "worker-sync-targeted": "worker",
    "worker_sync_targeted": "worker",
    "targeted-worker-sync": "worker",
    "targeted_worker_sync": "worker",
    "role_contract_migration": "worker",
    "role-contract-migration": "worker",
    "integration": "integrator",
    "integrator": "integrator",
    "integrators": "integrator",
    "auto_integrator": "integrator",
    "auto_integrators": "integrator",
    "finalizer": "finalizer",
    "finalization": "finalizer",
    "finalizers": "finalizer",
    "auto_finalizer": "finalizer",
    "auto_finalizers": "finalizer",
}
TASK_LANE_LABELS = {
    "architect": "Архитектор",
    "dispatcher": "Диспетчер",
    "worker": "Воркер",
    "integrator": "Интегратор",
    "finalizer": "Финализер",
}
RUN_OUTCOME_KIND_ORDER = ("done", "rework", "rejected")
RUN_OUTCOME_LABELS = {
    "done": "Сделал",
    "rework": "На доработку",
    "rejected": "Забраковал",
}
RUN_OUTCOME_KEYS = ("done", "rework", "rejected")
COMMAND_BUS_NOOP_DECISIONS = {"no_handoff", "already_finalized", "no_model_limit_retry_candidates"}
CONTROLLER_BLOCKING_ERRORS = {
    "command_root_preflight_failed",
    "runner_readiness_preflight_failed",
    "worker_lock_preflight_failed",
}

CODEX_MODEL_KEYS = ("5.3", "5.5")
CODEX_MODEL_LABELS = {
    "5.3": "GPT-5.3-Codex-Spark",
    "5.5": "GPT-5.5",
}

def _resolve_local_tz() -> dt.tzinfo:
    preferred_tz = os.environ.get("LOCAL_TZ")
    for tz_name in filter(None, (preferred_tz, "Europe/Moscow")):
        try:
            return ZoneInfo(tz_name)
        except ZoneInfoNotFoundError:
            continue

    try:
        return dt.datetime.now().astimezone().tzinfo or dt.timezone.utc
    except Exception:
        return dt.timezone.utc


LOCAL_TZ = _resolve_local_tz()
CODEX_LIMIT_STALE_MINUTES_DEFAULT = 240
VPS_TELEMETRY_TASK_ID = "AISTUDIO-DASH-VPS-TELEMETRY-001"
CONTROL_DISK_PRESSURE_TASK_ID = "AISTUDIO-CONTROL-DISK-PRESSURE-001"
CONTROL_DISK_PRESSURE_WARNING_PERCENT = 90.0
SNAPSHOT_SOURCE_STALE_MINUTES_DEFAULT = 60
VPS_FLEET_SCHEMA_VERSION = "1.0"
VPS_FLEET_MAX_SERVERS = 50
VPS_FLEET_MAX_DETAIL_ROWS = 8
VPS_FLEET_STALE_MINUTES_DEFAULT = 30
VPS_FLEET_SOURCE = "project-local-inventories"
RUNNER_READINESS_STALE_MINUTES_DEFAULT = 60
RUNNER_READINESS_CANDIDATE_PREVIEW_LIMIT = 10
AUTOMATION_CONTROLLER_STALE_MINUTES_DEFAULT = 60
SYSTEM_RESOURCE_SAMPLE_RETENTION_HOURS_DEFAULT = 24
SYSTEM_RESOURCE_ACTIVITY_WINDOW_MINUTES_DEFAULT = 5
SYSTEM_RESOURCE_SAMPLE_INTERVAL_SECONDS_DEFAULT = 60
SYSTEM_RESOURCE_SAMPLER_INTERVAL_SECONDS_DEFAULT = 60
DASHBOARD_SNAPSHOT_RETENTION_DEFAULT = 24
PROJECT_SNAPSHOT_RETENTION_DEFAULT = 24

RESOURCE_ACTIVITY_PATHS = (
    "automation-status/latest.json",
    "activity-log/events.jsonl",
    "codex-limits/latest.json",
    "codex-limits/remote-session-latest.json",
    "manual-runs",
    "automation-progress",
    "activity-log",
)

RESOURCE_ACTIVITY_PROCESS_MARKERS = (
    "llama-server",
    "ollama serve",
    "ollama",
    "vllm",
    "local-llm",
    "cuda",
    "nvidia",
    "transformers",
    "codex exec",
    "auto worker",
    "auto-workers",
    "--role workers",
    "--agent-role workers",
    "--agent-role integrator",
    "--agent-role finalizer",
    "agent_run_wrapper_stub.py",
    "status_orchestrator.py",
    "run_manual_automation.py",
    "worker_pool_manager.py",
    "worker_pool_manager",
    "automation_progress_wrapper.py",
    "local_llm_adapter.py",
    "llm_eval_runner.py",
    "llm_context_packer.py",
    "collect_codex_cli_status.py",
    "collect_codex_desktop_limits.py",
    "collect_remote_automation_status.py",
    "auto-role-run",
    "auto-dispatcher-run",
)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> tuple[Any | None, str | None]:
    if not path.exists():
        return None, f"File does not exist: {path}"
    try:
        return json.loads(path.read_text(encoding="utf-8")), None
    except json.JSONDecodeError as exc:
        return None, f"Invalid JSON in {path}: {exc}"
    except OSError as exc:
        return None, f"Cannot read {path}: {exc}"


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


def safe_int_or_none(value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, str) and not value.strip():
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def normalize_percent(value: Any) -> float | None:
    value = safe_float(value)
    if value is None:
        return None
    if value < 0:
        return 0.0
    if value > 100:
        return 100.0
    return round(float(value), 1)


def parse_number(text: str) -> float | None:
    if not isinstance(text, str):
        return None
    match = re.search(r"-?\d+(?:\.\d+)?", text)
    if not match:
        return None
    return safe_float(match.group(0))


def scan_cpu_load() -> float | None:
    if _psutil is not None:
        try:
            return normalize_percent(_psutil.cpu_percent(interval=0.1))
        except Exception:
            pass

    load_average: float | None = None
    try:
        load_average = os.getloadavg()[0]
    except (AttributeError, OSError):
        pass
    except Exception:
        load_average = None

    if load_average is None:
        loadavg_path = Path("/proc/loadavg")
        if loadavg_path.exists():
            try:
                line = loadavg_path.read_text(encoding="utf-8").split()[0]
                load_average = safe_float(line)
            except (OSError, ValueError, IndexError):
                load_average = None
    if load_average is None:
        return None

    cpu_count = os.cpu_count() or 1
    if cpu_count <= 0:
        cpu_count = 1
    return normalize_percent((load_average / cpu_count) * 100.0)


def scan_cpu_temperature() -> float | None:
    candidates: list[float] = []
    if _psutil is not None:
        try:
            temps = _psutil.sensors_temperatures()
        except Exception:
            temps = None

        if isinstance(temps, dict):
            preferred = ("coretemp", "k10temp", "cpu_thermal", "cpu", "acpitz", "tz")
            for key in preferred:
                sensors = temps.get(key)
                if not isinstance(sensors, list):
                    continue
                for item in sensors:
                    current = safe_float(getattr(item, "current", None))
                    if current is not None:
                        candidates.append(current)
            if not candidates:
                for key, sensors in temps.items():
                    if not isinstance(sensors, list):
                        continue
                    key_lower = key.lower()
                    if "cpu" not in key_lower and "core" not in key_lower:
                        continue
                    for item in sensors:
                        current = safe_float(getattr(item, "current", None))
                        if current is not None:
                            candidates.append(current)
            if candidates:
                return round(max(candidates), 1)

    best_temp: float | None = None
    for path in Path("/sys/class/thermal").glob("thermal_zone*"):
        temp_path = path / "temp"
        if not temp_path.exists():
            continue
        try:
            raw = temp_path.read_text(encoding="utf-8").strip()
        except OSError:
            continue
        parsed = parse_number(raw)
        if parsed is None:
            continue
        if parsed > 1500:
            parsed = parsed / 1000.0
        parsed = round(parsed, 1)
        if best_temp is None or parsed > best_temp:
            best_temp = parsed
    if best_temp is not None:
        return best_temp

    for path in Path("/sys/class/hwmon").glob("hwmon*/temp*_input"):
        try:
            raw = path.read_text(encoding="utf-8").strip()
        except OSError:
            continue
        parsed = parse_number(raw)
        if parsed is None:
            continue
        if parsed > 1500:
            parsed = parsed / 1000.0
        parsed = round(parsed, 1)
        if parsed is not None:
            candidates.append(parsed)
    if candidates:
        return round(max(candidates), 1)

    try:
        proc = subprocess.run(
            ["sensors"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=4,
        )
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
        proc = None
    if proc is not None and proc.returncode == 0:
        for line in proc.stdout.splitlines():
            if "°C" not in line:
                continue
            if not any(token in line.lower() for token in ("cpu", "core", "package", "k10temp", "coretemp", "acpitz")):
                continue
            match = re.search(r"([+-]?\d+(?:[.,]\d+)?)", line)
            if not match:
                continue
            value = parse_number(match.group(1).replace(",", "."))
            if value is not None:
                candidates.append(round(value, 1))
        if candidates:
            return round(max(candidates), 1)

    if os.name == "nt":
        try:
            proc = subprocess.run(
                [
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature | "
                    "Select-Object -ExpandProperty CurrentTemperature",
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
                timeout=5,
            )
        except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
            proc = None
        if proc is not None and proc.returncode == 0:
            for raw in proc.stdout.splitlines():
                value = parse_number(raw)
                if value is not None:
                    candidates.append(round(value / 10.0 - 273.15, 1))
            if candidates:
                return round(max(candidates), 1)

    return None


def scan_ram_usage() -> float | None:
    if _psutil is not None:
        try:
            return normalize_percent(_psutil.virtual_memory().percent)
        except Exception:
            pass

    meminfo = Path("/proc/meminfo")
    if not meminfo.exists():
        return None

    def parse_kilobytes(raw: str) -> int | None:
        parsed = parse_number(raw)
        if parsed is None:
            return None
        return int(parsed)

    total_kb: int | None = None
    available_kb: int | None = None
    free_kb: int | None = None
    buffers_kb: int | None = None
    cached_kb: int | None = None
    sreclaimable_kb: int | None = None
    try:
        for line in meminfo.read_text(encoding="utf-8").splitlines():
            if line.startswith("MemTotal:"):
                total_kb = parse_kilobytes(line.replace("MemTotal:", ""))
            elif line.startswith("MemAvailable:"):
                available_kb = parse_kilobytes(line.replace("MemAvailable:", ""))
            elif line.startswith("MemFree:"):
                free_kb = parse_kilobytes(line.replace("MemFree:", ""))
            elif line.startswith("Buffers:"):
                buffers_kb = parse_kilobytes(line.replace("Buffers:", ""))
            elif line.startswith("Cached:"):
                cached_kb = parse_kilobytes(line.replace("Cached:", ""))
            elif line.startswith("SReclaimable:"):
                sreclaimable_kb = parse_kilobytes(line.replace("SReclaimable:", ""))
    except OSError:
        return None

    if total_kb is None or total_kb <= 0:
        return None
    if available_kb is None:
        parts = [value for value in (free_kb, buffers_kb, cached_kb, sreclaimable_kb) if value is not None]
        if not parts:
            return None
        available_kb = sum(parts)
    used_kb = total_kb - available_kb
    if used_kb < 0:
        used_kb = 0
    return normalize_percent((used_kb / total_kb) * 100.0)


def scan_gpu_details() -> dict[str, Any]:
    empty = {
        "gpu_load": None,
        "vram_load": None,
        "gpu_temp": None,
        "used_mb": None,
        "total_mb": None,
        "power_w": None,
        "power_limit_w": None,
        "fan_percent": None,
        "pstate": None,
        "graphics_clock_mhz": None,
        "memory_clock_mhz": None,
        "state": "unavailable",
        "processes": [],
    }
    if not shutil.which("nvidia-smi"):
        return empty
    try:
        proc = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,power.limit,fan.speed,pstate,clocks.gr,clocks.mem",
                "--format=csv,noheader,nounits",
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
        return empty
    if proc.returncode != 0:
        return empty

    gpu_load: float | None = None
    vram_load: float | None = None
    gpu_temp: float | None = None
    used_mb_best: float | None = None
    total_mb_best: float | None = None
    power_w: float | None = None
    power_limit_w: float | None = None
    fan_percent: float | None = None
    pstate: str | None = None
    graphics_clock_mhz: float | None = None
    memory_clock_mhz: float | None = None
    for line in proc.stdout.splitlines():
        raw_parts = [part.strip() for part in line.split(",")]
        parts = [parse_number(part) for part in raw_parts]
        if len(parts) < 4 or any(value is None for value in (parts[0], parts[1], parts[2])):
            continue
        util = normalize_percent(parts[0])
        used = float(parts[1])
        total = float(parts[2])
        if total <= 0:
            continue
        used_mb_best = used if used_mb_best is None else max(used_mb_best, used)
        total_mb_best = total if total_mb_best is None else max(total_mb_best, total)
        mem = normalize_percent((used / total) * 100.0)
        if util is not None and (gpu_load is None or util > gpu_load):
            gpu_load = util
        if mem is not None and (vram_load is None or mem > vram_load):
            vram_load = mem
        temperature = parse_number(f"{parts[3]}")
        if temperature is not None and (gpu_temp is None or temperature > gpu_temp):
            gpu_temp = temperature
        if len(parts) >= 6:
            if parts[4] is not None and (power_w is None or float(parts[4]) > power_w):
                power_w = float(parts[4])
            if parts[5] is not None and (power_limit_w is None or float(parts[5]) > power_limit_w):
                power_limit_w = float(parts[5])
        if len(parts) >= 10:
            if parts[6] is not None and (fan_percent is None or float(parts[6]) > fan_percent):
                fan_percent = float(parts[6])
            if len(raw_parts) >= 8 and raw_parts[7]:
                pstate = raw_parts[7]
            if parts[8] is not None and (graphics_clock_mhz is None or float(parts[8]) > graphics_clock_mhz):
                graphics_clock_mhz = float(parts[8])
            if parts[9] is not None and (memory_clock_mhz is None or float(parts[9]) > memory_clock_mhz):
                memory_clock_mhz = float(parts[9])

    processes: list[dict[str, Any]] = []
    try:
        proc_apps = subprocess.run(
            [
                "nvidia-smi",
                "--query-compute-apps=pid,process_name,used_memory",
                "--format=csv,noheader,nounits",
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
        proc_apps = None
    if proc_apps is not None and proc_apps.returncode == 0:
        for line in proc_apps.stdout.splitlines():
            process_parts = [part.strip() for part in line.split(",")]
            if len(process_parts) < 3:
                continue
            processes.append({
                "pid": process_parts[0],
                "name": process_parts[1],
                "used_mb": parse_number(process_parts[2]),
            })

    state = "idle"
    if gpu_load is not None and gpu_load >= 5:
        state = "compute"
    elif used_mb_best is not None and used_mb_best >= 1024:
        state = "model_loaded_idle"
    elif pstate == "P8" and fan_percent == 0 and power_w is not None and power_w <= 20:
        state = "idle_fan_stop"
    elif processes:
        state = "gpu_process_idle"

    return {
        "gpu_load": gpu_load,
        "vram_load": vram_load,
        "gpu_temp": round(gpu_temp, 1) if gpu_temp is not None else None,
        "used_mb": round(used_mb_best, 1) if used_mb_best is not None else None,
        "total_mb": round(total_mb_best, 1) if total_mb_best is not None else None,
        "power_w": round(power_w, 1) if power_w is not None else None,
        "power_limit_w": round(power_limit_w, 1) if power_limit_w is not None else None,
        "fan_percent": round(fan_percent, 1) if fan_percent is not None else None,
        "pstate": pstate,
        "graphics_clock_mhz": round(graphics_clock_mhz, 1) if graphics_clock_mhz is not None else None,
        "memory_clock_mhz": round(memory_clock_mhz, 1) if memory_clock_mhz is not None else None,
        "state": state,
        "processes": processes,
    }


def scan_gpu_vram() -> tuple[float | None, float | None, float | None]:
    details = scan_gpu_details()
    return details.get("gpu_load"), details.get("vram_load"), details.get("gpu_temp")


def _read_linux_diskstats(device_names: set[str]) -> dict[str, dict[str, int]]:
    diskstats_path = Path("/proc/diskstats")
    if not device_names or not diskstats_path.exists():
        return {}
    try:
        lines = diskstats_path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return {}

    samples: dict[str, dict[str, int]] = {}
    for line in lines:
        parts = line.split()
        if len(parts) < 14 or parts[2] not in device_names:
            continue
        try:
            samples[parts[2]] = {
                "read_sectors": int(parts[5]),
                "write_sectors": int(parts[9]),
                "io_ms": int(parts[12]),
            }
        except (TypeError, ValueError):
            continue
    return samples


def _storage_io_rates(
    before: dict[str, dict[str, int]],
    after: dict[str, dict[str, int]],
    elapsed_seconds: float,
) -> dict[str, dict[str, float | None]]:
    if elapsed_seconds <= 0:
        return {}
    rates: dict[str, dict[str, float | None]] = {}
    for device_name, current in after.items():
        previous = before.get(device_name)
        if not isinstance(previous, dict):
            continue
        read_delta = max(0, current.get("read_sectors", 0) - previous.get("read_sectors", 0))
        write_delta = max(0, current.get("write_sectors", 0) - previous.get("write_sectors", 0))
        io_ms_delta = max(0, current.get("io_ms", 0) - previous.get("io_ms", 0))
        rates[device_name] = {
            "read_bytes_per_second": round((read_delta * 512) / elapsed_seconds, 1),
            "write_bytes_per_second": round((write_delta * 512) / elapsed_seconds, 1),
            "io_util_percent": normalize_percent((io_ms_delta / (elapsed_seconds * 1000.0)) * 100.0),
        }
    return rates


def _storage_media_type(device: dict[str, Any]) -> str:
    name = str(device.get("name") or device.get("kname") or "").lower()
    transport = str(device.get("tran") or "").lower()
    if name.startswith("nvme") or transport == "nvme":
        return "nvme"
    rotational = device.get("rota")
    if rotational is True or str(rotational).strip() == "1":
        return "hdd"
    if rotational is False or str(rotational).strip() == "0":
        return "ssd"
    return "unknown"


def _storage_mountpoints(node: dict[str, Any]) -> list[str]:
    raw_mountpoints = node.get("mountpoints")
    if isinstance(raw_mountpoints, str):
        raw_mountpoints = [raw_mountpoints]
    if not isinstance(raw_mountpoints, list):
        raw_mountpoint = node.get("mountpoint")
        raw_mountpoints = [raw_mountpoint] if raw_mountpoint else []
    result: list[str] = []
    for value in raw_mountpoints:
        mountpoint = str(value or "").strip()
        if mountpoint and mountpoint not in result:
            result.append(mountpoint)
    return result


def _storage_filesystems(device: dict[str, Any]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []

    def visit(node: dict[str, Any]) -> None:
        filesystem_type = str(node.get("fstype") or "").strip()
        for mountpoint in _storage_mountpoints(node):
            try:
                usage = shutil.disk_usage(mountpoint)
            except (OSError, ValueError):
                continue
            total_bytes = int(usage.total)
            used_bytes = int(usage.used)
            free_bytes = int(usage.free)
            usable_bytes = used_bytes + free_bytes
            used_percent = normalize_percent((used_bytes / usable_bytes) * 100.0) if usable_bytes > 0 else None
            rows.append({
                "mountpoint": mountpoint,
                "filesystem": filesystem_type or None,
                "total_bytes": total_bytes,
                "used_bytes": used_bytes,
                "free_bytes": free_bytes,
                "used_percent": used_percent,
            })
        children = node.get("children")
        if isinstance(children, list):
            for child in children:
                if isinstance(child, dict):
                    visit(child)

    visit(device)
    unique: list[dict[str, Any]] = []
    seen_mountpoints: set[str] = set()
    for row in rows:
        mountpoint = str(row.get("mountpoint") or "")
        if not mountpoint or mountpoint in seen_mountpoints:
            continue
        seen_mountpoints.add(mountpoint)
        unique.append(row)
    return unique[:12]


def _storage_devices_from_lsblk(payload: dict[str, Any]) -> list[dict[str, Any]]:
    block_devices = payload.get("blockdevices")
    if not isinstance(block_devices, list):
        return []
    devices: list[dict[str, Any]] = []
    for raw_device in block_devices:
        if not isinstance(raw_device, dict) or str(raw_device.get("type") or "") != "disk":
            continue
        device_name = str(raw_device.get("kname") or raw_device.get("name") or "").strip()
        if not device_name:
            continue
        filesystems = _storage_filesystems(raw_device)
        total_bytes = sum(safe_int(row.get("total_bytes")) for row in filesystems)
        used_bytes = sum(safe_int(row.get("used_bytes")) for row in filesystems)
        free_bytes = sum(safe_int(row.get("free_bytes")) for row in filesystems)
        usable_bytes = used_bytes + free_bytes
        used_percent = normalize_percent((used_bytes / usable_bytes) * 100.0) if usable_bytes > 0 else None
        devices.append({
            "id": device_name,
            "media_type": _storage_media_type(raw_device),
            "model": str(raw_device.get("model") or "").strip() or None,
            "size_bytes": safe_int_or_none(raw_device.get("size")),
            "total_bytes": total_bytes or None,
            "used_bytes": used_bytes if total_bytes else None,
            "free_bytes": free_bytes if total_bytes else None,
            "used_percent": used_percent,
            "read_bytes_per_second": None,
            "write_bytes_per_second": None,
            "io_util_percent": None,
            "filesystems": filesystems,
        })
    return devices[:12]


def scan_storage_devices(sample_seconds: float = 0.2) -> dict[str, Any]:
    observed_at = now_utc()
    if os.name == "nt" or not shutil.which("lsblk"):
        return {
            "state": "unavailable",
            "devices": [],
            "observed_at": observed_at,
            "source": None,
        }
    try:
        proc = subprocess.run(
            [
                "lsblk",
                "-J",
                "-b",
                "-o",
                "NAME,KNAME,TYPE,SIZE,ROTA,TRAN,MOUNTPOINTS,FSTYPE,MODEL",
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
        proc = None
    if proc is None or proc.returncode != 0:
        return {
            "state": "unavailable",
            "devices": [],
            "observed_at": observed_at,
            "source": None,
        }
    try:
        payload = json.loads(proc.stdout)
    except (TypeError, ValueError, json.JSONDecodeError):
        payload = {}
    devices = _storage_devices_from_lsblk(payload if isinstance(payload, dict) else {})
    device_names = {str(device.get("id") or "") for device in devices if device.get("id")}
    started_at = time.monotonic()
    before = _read_linux_diskstats(device_names)
    minimum_sample = max(0.0, min(float(sample_seconds), 1.0))
    remaining = minimum_sample - (time.monotonic() - started_at)
    if remaining > 0:
        time.sleep(remaining)
    elapsed = time.monotonic() - started_at
    rates = _storage_io_rates(before, _read_linux_diskstats(device_names), elapsed)
    for device in devices:
        device_rates = rates.get(str(device.get("id") or ""))
        if isinstance(device_rates, dict):
            device.update(device_rates)
    return {
        "state": "available" if devices else "unavailable",
        "devices": devices,
        "observed_at": observed_at,
        "source": "host:lsblk/diskstats",
    }


def scan_system_resources() -> dict[str, Any]:
    observed_at = now_utc()
    gpu_details = scan_gpu_details()
    gpu_load = gpu_details.get("gpu_load")
    vram_load = gpu_details.get("vram_load")
    gpu_temp = gpu_details.get("gpu_temp")
    return {
        "cpu": {"current": scan_cpu_load(), "average": None, "unit": "%"},
        "gpu": {"current": gpu_load, "average": None, "unit": "%"},
        "vram": {"current": vram_load, "average": None, "unit": "%"},
        "ram": {"current": scan_ram_usage(), "average": None, "unit": "%"},
        "cpu_temp": {"current": scan_cpu_temperature(), "average": None, "unit": "°C"},
        "gpu_temp": {"current": gpu_temp, "average": None, "unit": "°C"},
        "gpu_details": gpu_details,
        "storage": scan_storage_devices(),
        "observed_at": observed_at,
        "source": "host:nvidia-smi/proc/lsblk/diskstats",
    }


def scan_resource_samples_active_processes() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    try:
        result = subprocess.run(
            ["ps", "-eo", "pid,etimes,cmd"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return rows

    for line in result.stdout.splitlines()[1:]:
        lowered = line.lower()
        if not command_has_resource_activity_signature(lowered):
            continue
        parts = line.strip().split(maxsplit=2)
        if len(parts) < 3:
            continue
        pid, etimes, cmd = parts
        rows.append({"pid": pid, "elapsed_sec": safe_int(etimes), "cmd": cmd})
    return rows


def now_utc_dt() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def utc_timestamp_text(value: dt.datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _is_recent_activity_timestamp(value: Any, cutoff: dt.datetime) -> bool:
    parsed = parse_datetime(value)
    return parsed is not None and parsed >= cutoff


def _latest_path_mtime(path: Path) -> dt.datetime | None:
    if not path.exists():
        return None

    try:
        if path.is_file():
            return dt.datetime.fromtimestamp(path.stat().st_mtime, tz=dt.timezone.utc).astimezone(LOCAL_TZ)
        if not path.is_dir():
            return None
    except OSError:
        return None

    latest: dt.datetime | None = None
    try:
        for entry in path.iterdir():
            if not entry.is_file():
                continue
            try:
                modified = dt.datetime.fromtimestamp(entry.stat().st_mtime, tz=dt.timezone.utc).astimezone(LOCAL_TZ)
            except OSError:
                continue
            if latest is None or modified > latest:
                latest = modified
    except OSError:
        return None
    return latest


def command_has_resource_activity_signature(command: str) -> bool:
    lowered = (command or "").lower()
    if any(marker in lowered for marker in ("llama-server", "ollama", "vllm", "local-llm", "cuda")):
        return True
    if "python3" not in lowered and "python" not in lowered and "codex exec" not in lowered and "codex" not in lowered:
        return False
    return any(marker in lowered for marker in RESOURCE_ACTIVITY_PROCESS_MARKERS)


def build_resource_activity_state(
    runtime_root: Path,
    runs: list[dict[str, Any]],
    automation_status: dict[str, Any],
    automation_progress: list[dict[str, Any]],
    activity_log: list[dict[str, Any]],
    *,
    window_minutes: int = SYSTEM_RESOURCE_ACTIVITY_WINDOW_MINUTES_DEFAULT,
) -> dict[str, Any]:
    now_local = dt.datetime.now(LOCAL_TZ)
    cutoff = now_local - dt.timedelta(minutes=max(0, window_minutes))

    reasons: list[str] = []
    def _mark(reason: str) -> None:
        if reason not in reasons:
            reasons.append(reason)

    for run in runs:
        if _is_recent_activity_timestamp(run.get("started_at"), cutoff):
            _mark("run_started")
            break

    for run in runs:
        status = normalize_status(run.get("status"))
        if status in {"running", "in_progress", "started"}:
            _mark("run_in_progress")
            break
        ended_at = parse_datetime(run.get("ended_at"))
        if ended_at is None and status not in {"", "done", "agent_done", "completed", "merged", "released", "archived"}:
            if parse_datetime(run.get("started_at")) is not None:
                _mark("run_active_no_end")
                break

    for item in activity_log[:20]:
        if _is_recent_activity_timestamp(item.get("created_at"), cutoff):
            _mark("activity_log_recent")
            break

    for item in automation_progress:
        for key in ("updated_at", "generated_at", "created_at"):
            if _is_recent_activity_timestamp(item.get("data", {}).get(key), cutoff):
                _mark("automation_progress_recent")
                break
            if _is_recent_activity_timestamp(item.get(key), cutoff):
                _mark("automation_progress_recent")
                break
        if reasons and reasons[-1] == "automation_progress_recent":
            break

    status_observed = str(automation_status.get("generated_at") or automation_status.get("observed_at") or "")
    if _is_recent_activity_timestamp(status_observed, cutoff):
        _mark("automation_status_recent")

    if not runtime_root.exists():
        return {"is_active": bool(reasons), "reasons": reasons, "last_active_at": None}

    for relative_path in RESOURCE_ACTIVITY_PATHS:
        path = runtime_root / relative_path
        updated_at = _latest_path_mtime(path)
        if updated_at is None:
            continue
        if updated_at >= cutoff:
            _mark(f"path:{path.name}_updated")

    if scan_resource_samples_active_processes():
        _mark("process_activity")

    return {
        "is_active": bool(reasons),
        "reasons": reasons,
        "last_active_at": now_utc(),
    }


def parse_time(value: Any) -> str:
    if isinstance(value, str) and value:
        return value
    return ""


def now_utc() -> str:
    return utc_timestamp_text(now_utc_dt())


def parse_datetime(value: Any) -> dt.datetime | None:
    if not isinstance(value, str) or not value:
        return None
    text = value.strip()
    if not text:
        return None
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = dt.datetime.fromisoformat(text)
    except ValueError:
        try:
            parsed = dt.datetime.fromisoformat(f"{text}T00:00:00")
        except ValueError:
            return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=LOCAL_TZ)
    return parsed.astimezone(LOCAL_TZ)


def is_limit_row_stale(value: Any, max_age_minutes: int | None) -> bool:
    if max_age_minutes is None or max_age_minutes <= 0:
        return False
    observed_at = parse_datetime(value)
    if observed_at is None:
        return True
    cutoff = dt.datetime.now(tz=LOCAL_TZ) - dt.timedelta(minutes=max_age_minutes)
    return observed_at < cutoff


def short_datetime(value: Any) -> str:
    if not isinstance(value, str) or not value:
        return ""
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return value
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(LOCAL_TZ).strftime("%d.%m %H:%M")


def classify_status(status: Any) -> str:
    normalized = normalize_status(status or "planned")
    if normalized == "worker_ready":
        return "waiting"
    if normalized in TASK_PACKET_STATUSES:
        return "task_packet"
    if normalized in COMPLETED_STATUSES:
        return "completed"
    if normalized in HUMAN_STATUSES:
        return "human"
    if normalized in ACTIVE_STATUSES:
        return "active"
    if normalized in POSTPONED_STATUSES:
        return "postponed"
    if normalized in WAITING_STATUSES:
        return "waiting"
    if "human" in normalized or "blocked" in normalized or "failed" in normalized:
        return "human"
    if "task_packet" in normalized or "task_spec" in normalized or "dispatcher" in normalized or "architect" in normalized:
        return "task_packet"
    if "done" in normalized or "approved" in normalized or "merged" in normalized:
        return "completed"
    if "progress" in normalized or "review" in normalized:
        return "active"
    if "postpone" in normalized or "defer" in normalized:
        return "postponed"
    if any(word in normalized for word in TASK_STATUS_HUMAN_KEYWORDS):
        return "human"
    if any(word in normalized for word in TASK_STATUS_POSTPONED_KEYWORDS):
        return "postponed"
    if any(word in normalized for word in TASK_STATUS_ACTIVE_KEYWORDS):
        return "active"
    if any(word in normalized for word in TASK_STATUS_WAITING_KEYWORDS):
        return "waiting"
    if "other" in normalized:
        return "waiting"
    return "waiting"


def normalize_status(value: Any) -> str:
    value = str(value or "").strip().lower()
    if not value:
        return ""
    value = re.sub(r"[^a-z0-9]+", "_", value)
    return value.strip("_")


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict, tuple, set)):
        return bool(value)
    return True


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def task_effective_status(task: dict[str, Any]) -> str:
    """Resolve task state from the current normalized task-packet fields."""
    raw_status = normalize_status(task.get("status"))
    if raw_status in COMPLETED_STATUSES or raw_status in POSTPONED_STATUSES:
        return raw_status
    if (
        task.get("requires_current_context_review") is True
        and (task.get("worker_ready") is True or normalize_status(task.get("dispatcher_decision")) == "worker_ready")
        and not has_current_context_verification(task)
    ):
        return "needs_dispatcher_repair"
    if raw_status in HUMAN_STATUSES or raw_status in ACTIVE_STATUSES or raw_status in TASK_PACKET_STATUSES:
        return raw_status
    for key in (
        "dispatcher_decision",
        "routing_status",
        "route",
        "state",
        "normalization_status",
        "packet_status",
        "status",
    ):
        value = normalize_status(task.get(key))
        if value:
            return value
    if task.get("worker_ready") is True:
        return "worker_ready"
    return "planned"


def status_rank(value: Any) -> int:
    normalized = normalize_status(value)
    if normalized in TASK_STATUS_RANK:
        return TASK_STATUS_RANK[normalized]
    return status_rank_fallback(normalized)


def lock_state_to_status(state: Any) -> str:
    resolved = LOCK_STATUS_STATES.get(normalize_status(state), None)
    if isinstance(resolved, str):
        return resolved.strip()
    return ""


def is_active_lock_state(state: Any) -> bool:
    status = normalize_status(state)
    if status in ACTIVE_LOCK_STATES:
        return True
    return lock_state_to_status(status) in {"in_progress", "review"}


def is_blocking_lock_state(state: Any) -> bool:
    status = normalize_status(state)
    return status in {"active", "locked", "in_progress", "running", "claimed", "worker_claimed"}


def task_has_blocking_lock(task: dict[str, Any]) -> bool:
    for key in ("lock_state", "lock"):
        if key in task:
            return is_blocking_lock_state(task.get(key))
    return has_value(task.get("locked_by"))


def is_active_human_packet(packet: dict[str, Any]) -> bool:
    return str(packet.get("status", "open")).strip().lower() in HUMAN_PACKET_ACTIVE_STATUSES


def infer_task_status(
    queue_status: str,
    lock_state: Any,
    task_run: dict[str, Any] | None,
    running: bool,
    stale_worktree: bool,
) -> tuple[str, str]:
    candidates: list[tuple[str, str]] = []
    lock_status = lock_state_to_status(lock_state)
    if lock_status:
        candidates.append((lock_status, "lock"))
    if task_run:
        run_status = normalize_status(task_run.get("task_status_after"))
        if run_status:
            candidates.append((run_status, "run"))
    if running:
        candidates.append(("in_progress", "worktree_running"))
    elif stale_worktree and queue_status in {"planned", "todo", "queued", "ready", "backlog"}:
        candidates.append(("agent_done", "worktree_stale"))
    best_status = queue_status
    best_source = "queue"
    best_rank = status_rank(queue_status)
    for status, source in candidates:
        if status_rank(status) > best_rank:
            best_status = status
            best_source = source
            best_rank = status_rank(status)
    return best_status, best_source


def infer_task_lane(
    status: str,
    task_run: dict[str, Any] | None,
) -> str:
    if isinstance(task_run, dict):
        role = RUN_ROLE_TO_LANE.get(normalize_status(task_run.get("agent_role")), "")
        if role:
            return role

    normalized_status = normalize_status(status)
    if normalized_status == "needs_architect" or "architect" in normalized_status:
        return "architect"
    if (
        normalized_status in TASK_PACKET_STATUSES
        or normalized_status.startswith("needs_dispatcher")
        or normalized_status in {"needs_packet", "packet_defect"}
        or "dispatcher" in normalized_status
    ):
        return "dispatcher"
    if (
        normalized_status in INTEGRATOR_LANE_STATUSES
        or "integration" in normalized_status
        or "base_ready" in normalized_status
        or any(token in normalized_status for token in ("draft_pr", "staging_deployed", "smoke_passed", "tests_passed", "integration_conflict"))
    ):
        return "integrator"
    if (
        normalized_status in FINALIZER_LANE_STATUSES
        or "finalization" in normalized_status
        or "finalizer" in normalized_status
        or normalized_status in {
            "ready_for_explicit_merge",
            "dry_run_ready",
            "release_candidate",
            "released",
            "archived",
            "finalization_requested",
            "finalizer_auto_merge_ready",
            "production",
        }
    ):
        return "finalizer"
    if normalized_status in WORKER_LANE_STATUSES:
        return "worker"
    return "worker"


def infer_task_role_lane(task: dict[str, Any] | None, status: str, task_run: dict[str, Any] | None) -> str:
    if not isinstance(task, dict):
        return infer_task_lane(status, task_run)
    owner = RUN_ROLE_TO_LANE.get(normalize_status(task.get("next_owner")), "")
    if owner:
        return owner
    if has_value(task.get("architect_request")) or has_value(task.get("architecture_question")):
        return "architect"
    normalized_status = normalize_status(status)
    if (
        normalized_status == "needs_architect"
        or "architect" in normalized_status
        or normalized_status in TASK_PACKET_STATUSES
        or normalized_status.startswith("needs_dispatcher")
        or "dispatcher" in normalized_status
        or normalized_status == "needs_worker_fix"
        or normalized_status in INTEGRATOR_LANE_STATUSES
        or "integration" in normalized_status
        or normalized_status in FINALIZER_LANE_STATUSES
        or "finalization" in normalized_status
        or "finalizer" in normalized_status
    ):
        return infer_task_lane(status, task_run)
    decision = normalize_status(task.get("dispatcher_decision"))
    if decision == "needs_architect" or "architect" in decision:
        return "architect"
    if decision in TASK_PACKET_STATUSES or decision.startswith("needs_dispatcher") or "dispatcher" in decision:
        return "dispatcher"
    if decision in WORKER_LANE_STATUSES or decision in {"needs_worker_fix", "worker_retry", "worker_fix"} or "worker" in decision:
        return "worker"
    if decision in INTEGRATOR_LANE_STATUSES or "integration" in decision or "integrator" in decision:
        return "integrator"
    if decision in FINALIZER_LANE_STATUSES or "finalization" in decision or "finalizer" in decision:
        return "finalizer"
    return infer_task_lane(status, task_run)


ENVIRONMENT_BLOCKER_TOKENS = (
    "missing_environment",
    "environment",
    "clean vps",
    "clean-vps",
    "model unavailable",
    "model was unavailable",
    "model limit",
    "usage-limited",
    "usage limited",
    "blocked_model_limit",
)


def is_environment_blocked_attention_task(task: dict[str, Any] | None) -> bool:
    if not isinstance(task, dict):
        return False
    text = " ".join(
        str(task.get(key) or "")
        for key in (
            "status",
            "status_raw",
            "reason",
            "blocked_reason",
            "status_reason",
            "dispatcher_decision",
            "integration_status",
            "next_action",
        )
    ).lower()
    return any(token in text for token in ENVIRONMENT_BLOCKER_TOKENS)


def task_attention_lane(task: dict[str, Any] | None, status: str, task_run: dict[str, Any] | None) -> str:
    if is_environment_blocked_attention_task(task):
        return "environment"
    owner = normalize_status((task or {}).get("next_owner") if isinstance(task, dict) else "")
    if owner in {"human", "owner", "operator", "manual"}:
        return "human"
    if owner in {"worker", "workers", "worker_pool", "auto_worker", "auto_workers"}:
        return "worker"
    if owner in {"integrator", "integration", "auto_integrator"}:
        return "integrator"
    if owner in {"dispatcher", "auto_dispatcher"}:
        return "dispatcher"
    if owner in {"architect", "architecture", "auto_architect"}:
        return "architect"
    if owner in {"finalizer", "finalization", "auto_finalizer"}:
        return "finalizer"
    normalized_status = normalize_status(status)
    if normalized_status in {"needs_human", "needs_owner", "owner_review", "owner_approval_needed", "owner_input_needed"}:
        return "human"
    return infer_task_lane(status, task_run)


QUEUE_ATTENTION_PLAN_ACTIONS = (
    "owner_required",
    "environment_required",
    "role_actionable_after_infra",
    "role_rework",
    "unknown",
)
QUEUE_ATTENTION_ENVIRONMENT_REASONS = (
    "model_limit",
    "worker_host",
    "infrastructure",
    "unknown",
)
MODEL_LIMIT_RETRY_BATCH_LIMIT = 5
COORDINATION_PATH_PREFIXES = (
    "AiStudio/Task_manager/",
    "docs/plans/",
    "old/agent-runs/",
)
COORDINATION_EXACT_PATHS = {
    ".agent/mvp_tasks.json",
    ".agent/next_tasks.json",
}


def zero_queue_attention_plan() -> dict[str, Any]:
    return {
        "total": 0,
        "by_action": {action: 0 for action in QUEUE_ATTENTION_PLAN_ACTIONS},
        "by_environment_reason": {reason: 0 for reason in QUEUE_ATTENTION_ENVIRONMENT_REASONS},
        "model_limit_retry": {
            "total": 0,
            "eligible": 0,
            "waiting_for_approval": 0,
            "blocked": 0,
            "next_batch_limit": MODEL_LIMIT_RETRY_BATCH_LIMIT,
            "next_batch_approval_count": 0,
            "runs_needed_for_waiting_approval": 0,
        },
        "by_lane": {
            "human": 0,
            "environment": 0,
            "architect": 0,
            "dispatcher": 0,
            "worker": 0,
            "integrator": 0,
            "finalizer": 0,
            "unknown": 0,
        },
        "items": [],
    }


def update_model_limit_retry_batch_metrics(plan: dict[str, Any]) -> None:
    retry = plan.get("model_limit_retry")
    if not isinstance(retry, dict):
        return
    limit = safe_int(retry.get("next_batch_limit")) or MODEL_LIMIT_RETRY_BATCH_LIMIT
    waiting = safe_int(retry.get("waiting_for_approval"))
    retry["next_batch_limit"] = limit
    retry["next_batch_approval_count"] = min(waiting, limit) if limit > 0 else waiting
    retry["runs_needed_for_waiting_approval"] = ((waiting + limit - 1) // limit) if waiting > 0 and limit > 0 else 0


def queue_attention_environment_reason(task: dict[str, Any]) -> str:
    if task.get("synthetic") and task.get("source") == "runner-readiness":
        return "worker_host"
    reason_text = " ".join(
        str(task.get(key) or "")
        for key in (
            "status",
            "status_raw",
            "reason",
            "blocked_reason",
            "status_reason",
            "dispatcher_decision",
            "integration_status",
            "next_action",
        )
    ).lower()
    if "blocked_model_limit" in reason_text or "model limit" in reason_text or "usage-limited" in reason_text or "usage limited" in reason_text:
        return "model_limit"
    if any(token in reason_text for token in ("missing_environment", "environment", "clean vps", "clean-vps")):
        return "infrastructure"
    return "unknown"


def model_limit_retry_state(task: dict[str, Any], active_locks: set[str] | None = None) -> str | None:
    if queue_attention_environment_reason(task) != "model_limit":
        return None
    if task.get("model_limit_retry_allowed") is not True:
        return "waiting_for_approval"
    blockers: list[str] = []
    task_id = str(task.get("id") or task.get("task_id") or "").strip()
    if active_locks and task_id in active_locks:
        blockers.append("active_lock")
    if not has_value(task.get("complexity")):
        blockers.append("missing_complexity")
    if not has_value(task.get("priority")):
        blockers.append("missing_priority")
    if not has_value(task.get("type")):
        blockers.append("missing_type")
    if not has_value(task.get("allowed_paths")):
        blockers.append("missing_allowed_paths")
    if not has_value(task.get("forbidden_paths")):
        blockers.append("missing_forbidden_paths")
    if not has_value(task.get("acceptance_criteria")):
        blockers.append("missing_acceptance_criteria")
    if not has_value(task.get("checks")):
        blockers.append("missing_checks")
    if not (has_value(task.get("recommended_agent")) or has_value(task.get("eligible_worker_profiles"))):
        blockers.append("missing_worker_profile")
    if not (has_value(task.get("context_docs")) or has_value(task.get("source_file")) or has_value(task.get("provenance"))):
        blockers.append("missing_context")
    return "blocked" if blockers else "eligible"


def runner_readiness_clears_worker_host_blocker(project_report: dict[str, Any]) -> bool:
    readiness = project_report.get("runner_readiness")
    if not isinstance(readiness, dict):
        return False
    host = readiness.get("codex_host_readiness") if isinstance(readiness.get("codex_host_readiness"), dict) else {}
    return bool(readiness.get("worker_run_applicable")) and not bool(readiness.get("worker_run_blocked")) and host.get("ok") is True


def queue_attention_task_action(
    task: dict[str, Any],
    project_worktree_plan: dict[str, Any] | None,
    effective_infra_blockers: list[str] | None = None,
) -> str:
    lane = str(task.get("attention_lane") or "unknown")
    if lane == "human":
        return "owner_required"
    reason_text = " ".join(
        str(task.get(key) or "")
        for key in (
            "status",
            "status_raw",
            "reason",
            "blocked_reason",
            "status_reason",
            "dispatcher_decision",
            "integration_status",
            "next_action",
        )
    ).lower()
    if any(token in reason_text for token in ENVIRONMENT_BLOCKER_TOKENS):
        return "environment_required"
    if lane in {"architect", "dispatcher", "worker", "integrator", "finalizer"}:
        blockers = set(effective_infra_blockers or [])
        if not blockers and isinstance(project_worktree_plan, dict):
            blockers = set(project_worktree_plan.get("blockers") or [])
        command_root_is_git = bool(project_worktree_plan.get("command_root_is_git_worktree")) if isinstance(project_worktree_plan, dict) else True
        if blockers:
            return "environment_required"
        if not command_root_is_git:
            return "role_actionable_after_infra"
        return "role_rework"
    return "unknown"


def is_split_parent_attention_task(task: dict[str, Any]) -> bool:
    if str(task.get("dispatcher_decision") or "") == "split_into_children":
        return True
    split_into = task.get("split_into")
    if not isinstance(split_into, list) or not split_into:
        return False
    task_id = str(task.get("id") or task.get("task_id") or "").strip()
    child_ids = {str(item).strip() for item in split_into if str(item).strip()}
    if not child_ids:
        return False
    return bool(child_ids - {task_id})


def normalized_path_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).replace("\\", "/").strip() for item in value if str(item).strip()]


def is_coordination_path(path: str) -> bool:
    normalized = path.replace("\\", "/").strip()
    if normalized.startswith("docs/plans/contracts/"):
        return False
    return normalized in COORDINATION_EXACT_PATHS or any(normalized.startswith(prefix) for prefix in COORDINATION_PATH_PREFIXES)


def is_coordination_only_passed_worker_result(task: dict[str, Any]) -> bool:
    if str(task.get("status") or "") != "blocked":
        return False
    evidence = task.get("worker_check_evidence")
    if not isinstance(evidence, dict) or evidence.get("ok") is not True:
        return False
    if str(evidence.get("check_status") or "") != "passed":
        return False
    paths = normalized_path_list(task.get("changed_paths")) or normalized_path_list(task.get("worker_changed_paths"))
    return bool(paths) and all(is_coordination_path(path) for path in paths)


def is_suppressed_attention_task(task: dict[str, Any]) -> bool:
    return is_split_parent_attention_task(task) or is_coordination_only_passed_worker_result(task)


def suppressed_attention_reason(task: dict[str, Any]) -> str | None:
    if is_split_parent_attention_task(task):
        return "split_parent"
    if is_coordination_only_passed_worker_result(task):
        return "coordination_only_passed_worker_result"
    return None


def build_queue_attention_plan(
    project_report: dict[str, Any],
    project_worktree_plan: dict[str, Any] | None = None,
) -> dict[str, Any]:
    plan = zero_queue_attention_plan()
    project_id = str(project_report.get("project_id") or "")
    project_name = str(project_report.get("name") or project_id)
    infra_action = str(project_worktree_plan.get("action") or "") if isinstance(project_worktree_plan, dict) else ""
    infra_blockers = list(project_worktree_plan.get("blockers") or []) if isinstance(project_worktree_plan, dict) else []
    proposed_automation_path = (
        str(project_worktree_plan.get("proposed_automation_path") or "")
        if isinstance(project_worktree_plan, dict)
        else ""
    )
    remote_access = project_worktree_plan.get("remote_access") if isinstance(project_worktree_plan, dict) and isinstance(project_worktree_plan.get("remote_access"), dict) else {}
    infra_remote_checked = bool(remote_access.get("checked"))
    infra_remote_reason = str(remote_access.get("reason") or "")
    infra_requires_remote_check = bool(infra_action == "clone_and_set_automation_path" and not infra_remote_checked)
    effective_infra_blockers = list(infra_blockers)
    if infra_requires_remote_check and "remote_check_required" not in effective_infra_blockers:
        effective_infra_blockers.append("remote_check_required")
    active_locks = {
        str(lock.get("task_id") or "").strip()
        for lock in project_report.get("locks") or []
        if isinstance(lock, dict) and str(lock.get("state") or "") in {"locked", "in_progress", "review"}
    }
    for task in project_report.get("tasks") or []:
        if not isinstance(task, dict) or task.get("bucket") != "human":
            continue
        if is_suppressed_attention_task(task):
            continue
        lane = str(task.get("attention_lane") or "unknown")
        if lane not in plan["by_lane"]:
            lane = "unknown"
        action = queue_attention_task_action(task, project_worktree_plan, effective_infra_blockers)
        if action not in plan["by_action"]:
            action = "unknown"
        if action == "environment_required" and is_environment_blocked_attention_task(task):
            lane = "environment"
        environment_reason = queue_attention_environment_reason(task) if action == "environment_required" else None
        retry_state = model_limit_retry_state(task, active_locks) if environment_reason == "model_limit" else None
        item = {
            "project_id": project_id,
            "project_name": project_name,
            "task_id": task.get("id"),
            "title": task.get("title"),
            "status": task.get("status"),
            "status_raw": task.get("status_raw"),
            "lane": lane,
            "attention_lane": lane,
            "action": action,
            "environment_reason": environment_reason,
            "model_limit_retry_state": retry_state,
            "model_limit_retry_allowed": task.get("model_limit_retry_allowed"),
            "reason": task.get("reason"),
            "next_owner": task.get("next_owner"),
            "dispatcher_decision": task.get("dispatcher_decision"),
            "integration_status": task.get("integration_status"),
            "next_action": task.get("next_action"),
            "worker_ready": task.get("worker_ready"),
            "infra_action": infra_action or None,
            "infra_blockers": effective_infra_blockers,
            "infra_remote_checked": infra_remote_checked,
            "infra_remote_reason": infra_remote_reason or None,
            "infra_requires_remote_check": infra_requires_remote_check,
            "proposed_automation_path": proposed_automation_path or None,
        }
        plan["total"] += 1
        plan["by_lane"][lane] += 1
        plan["by_action"][action] += 1
        if action == "environment_required":
            reason_key = environment_reason if environment_reason in plan["by_environment_reason"] else "unknown"
            plan["by_environment_reason"][reason_key] += 1
            if reason_key == "model_limit":
                plan["model_limit_retry"]["total"] += 1
                retry_key = retry_state if retry_state in {"eligible", "waiting_for_approval", "blocked"} else "blocked"
                plan["model_limit_retry"][retry_key] += 1
        plan["items"].append(item)
    worker_host_blocker = project_report.get("worker_host_blocker")
    if (
        not isinstance(worker_host_blocker, dict)
        and not runner_readiness_clears_worker_host_blocker(project_report)
        and isinstance(project_worktree_plan, dict)
    ):
        proposed_root = str(project_worktree_plan.get("proposed_automation_path") or "").strip()
        if proposed_root:
            worker_host_blocker = load_worker_host_blocker_from_root(Path(proposed_root))
    if isinstance(worker_host_blocker, dict):
        affected_worker_ready = sum(
            1
            for task in project_report.get("tasks") or []
            if isinstance(task, dict)
            and str(task.get("bucket") or "") == "waiting"
            and str(task.get("dispatcher_decision") or "") == "worker_ready"
        )
        affected_worker_ready = max(affected_worker_ready, safe_int(worker_host_blocker.get("affected_task_count")))
        if affected_worker_ready > 0:
            item = {
                "project_id": project_id,
                "project_name": project_name,
                "task_id": None,
                "status": "blocked",
                "lane": "environment",
                "attention_lane": "environment",
                "action": "environment_required",
                "environment_reason": "worker_host",
                "reason": worker_host_blocker.get("reason") or "worker host unavailable",
                "next_action": worker_host_blocker.get("next_action"),
                "affected_task_count": affected_worker_ready,
                "synthetic": True,
                "codex_bin": worker_host_blocker.get("codex_bin"),
                "source": worker_host_blocker.get("source"),
                "source_path": worker_host_blocker.get("source_path"),
                "source_generated_at": worker_host_blocker.get("source_generated_at"),
                "source_age_seconds": worker_host_blocker.get("source_age_seconds"),
                "source_stale": worker_host_blocker.get("source_stale"),
                "source_stale_minutes": worker_host_blocker.get("source_stale_minutes"),
                "infra_action": infra_action or None,
                "infra_blockers": effective_infra_blockers,
                "infra_remote_checked": infra_remote_checked,
                "infra_remote_reason": infra_remote_reason or None,
                "infra_requires_remote_check": infra_requires_remote_check,
                "proposed_automation_path": proposed_automation_path or None,
            }
            plan["total"] += 1
            plan["by_lane"]["environment"] += 1
            plan["by_action"]["environment_required"] += 1
            plan["by_environment_reason"]["worker_host"] += 1
            plan["items"].append(item)
    update_model_limit_retry_batch_metrics(plan)
    return plan


def merge_queue_attention_plan(target: dict[str, Any], source: dict[str, Any]) -> None:
    target["total"] += safe_int(source.get("total"))
    for action in target["by_action"]:
        target["by_action"][action] += safe_int((source.get("by_action") or {}).get(action))
    target_env = target.setdefault("by_environment_reason", {reason: 0 for reason in QUEUE_ATTENTION_ENVIRONMENT_REASONS})
    source_env = source.get("by_environment_reason") if isinstance(source.get("by_environment_reason"), dict) else {}
    for reason in QUEUE_ATTENTION_ENVIRONMENT_REASONS:
        target_env[reason] = safe_int(target_env.get(reason)) + safe_int(source_env.get(reason))
    target_retry = target.setdefault("model_limit_retry", {"total": 0, "eligible": 0, "waiting_for_approval": 0, "blocked": 0})
    source_retry = source.get("model_limit_retry") if isinstance(source.get("model_limit_retry"), dict) else {}
    for key in ("total", "eligible", "waiting_for_approval", "blocked"):
        target_retry[key] = safe_int(target_retry.get(key)) + safe_int(source_retry.get(key))
    update_model_limit_retry_batch_metrics(target)
    for lane in target["by_lane"]:
        target["by_lane"][lane] += safe_int((source.get("by_lane") or {}).get(lane))
    target["items"].extend(source.get("items") or [])


def merge_project_attention_lanes(target: dict[str, int], project: dict[str, Any]) -> None:
    project_attention_plan = project.get("queue_attention_plan")
    plan_lanes = project_attention_plan.get("by_lane") if isinstance(project_attention_plan, dict) else None
    if isinstance(plan_lanes, dict) and (safe_int(project_attention_plan.get("total")) > 0 or any(safe_int(value) for value in plan_lanes.values())):
        source = plan_lanes
    else:
        source = (project.get("counts") or {}).get("queue_attention_by_lane")
    if isinstance(source, dict):
        for key in target:
            target[key] += safe_int(source.get(key))


def merge_role_outcome_totals(target: dict[str, Any], source: dict[str, Any] | None) -> None:
    if not isinstance(source, dict):
        return
    for role, role_stats in source.items():
        if role not in target or not isinstance(role_stats, dict):
            continue
        for outcome in RUN_OUTCOME_KEYS:
            source_period = role_stats.get(outcome)
            target_period = target[role].get(outcome)
            if not isinstance(source_period, dict) or not isinstance(target_period, dict):
                continue
            target_period["total"] += safe_int(source_period.get("total"))
            target_period["today"] += safe_int(source_period.get("today"))
            target_period["week"] += safe_int(source_period.get("week"))
            target_period["last_24h"] += safe_int(source_period.get("last_24h"))


HUMAN_ATTENTION_BREAKDOWN_KEYS = (
    "queue_tasks",
    "human_packets",
    "owner_directives",
    "failed_runs",
    "failed_role_states",
    "stale_locks",
    "non_git_project_roots",
)


def zero_human_attention_breakdown() -> dict[str, int]:
    return {key: 0 for key in HUMAN_ATTENTION_BREAKDOWN_KEYS}


def recompute_snapshot_project_totals(snapshot: dict[str, Any]) -> None:
    totals = {"completed": 0, "waiting": 0, "active": 0, "human": 0, "task_packet": 0, "postponed": 0, "total": 0}
    added_totals = {"today": 0, "week": 0, "with_known_created_at": 0}
    completed_recent_totals = {"today": 0, "week": 0, "with_known_at": 0}
    recent_task_outcome_totals = {
        "window_hours": 24,
        "completed": 0,
        "failed": 0,
        "completed_with_known_at": 0,
        "attempted_tasks_with_known_at": 0,
    }
    role_outcome_totals = zero_role_outcome_stats()
    task_origin_totals = zero_task_origin_breakdown()
    task_origin_active_totals = zero_task_origin_breakdown()
    local_llm_pre_worker_items: list[dict[str, Any]] = []
    worktree_active_total = 0
    active_total = 0
    project_count = 0
    for project in snapshot.get("projects") or []:
        if not isinstance(project, dict):
            continue
        project_count += 1
        counts = project.get("counts") if isinstance(project.get("counts"), dict) else {}
        for key in totals:
            totals[key] += safe_int(counts.get(key))
        for key in added_totals:
            added_totals[key] += safe_int((project.get("added_counts") or {}).get(key))
        for key in completed_recent_totals:
            completed_recent_totals[key] += safe_int((project.get("completed_recent") or {}).get(key))
        project_recent_outcomes = project.get("task_outcomes_last_24h") or {}
        for key in ("completed", "failed", "completed_with_known_at", "attempted_tasks_with_known_at"):
            recent_task_outcome_totals[key] += safe_int(project_recent_outcomes.get(key))
        merge_role_outcome_totals(role_outcome_totals, counts.get("role_outcomes"))
        project_origins = project.get("task_origin_breakdown") if isinstance(project.get("task_origin_breakdown"), dict) else {}
        for key in task_origin_totals:
            task_origin_totals[key] += safe_int(project_origins.get(key))
        project_active_origins = project.get("task_origin_active_breakdown") if isinstance(project.get("task_origin_active_breakdown"), dict) else {}
        for key in task_origin_active_totals:
            task_origin_active_totals[key] += safe_int(project_active_origins.get(key))
        local_llm_pre_worker_items.append(project.get("local_llm_pre_worker") or {})
        worktree_active_total += safe_int(counts.get("worktree_active"))
        active_total += safe_int(counts.get("active_total", counts.get("active")))
    summary = snapshot.setdefault("summary", {})
    summary["project_count"] = project_count
    totals["active_queue_total"] = live_backlog_total({**totals, "active_total": active_total})
    totals["terminal_queue_total"] = safe_int(totals.get("completed")) + safe_int(totals.get("postponed"))
    summary["task_counts"] = totals
    summary["tasks_added"] = added_totals
    summary["tasks_completed_recent"] = completed_recent_totals
    summary["task_outcomes_last_24h"] = recent_task_outcome_totals
    summary["role_outcomes"] = role_outcome_totals
    summary["task_flow_last_24h"] = task_flow_last_24h(role_outcome_totals, recent_task_outcome_totals)
    summary["task_origin_breakdown"] = task_origin_totals
    summary["task_origin_active_breakdown"] = task_origin_active_totals
    summary["task_work_class_active_breakdown"] = task_work_class_breakdown(task_origin_active_totals)
    summary["local_llm_pre_worker"] = merge_local_llm_pre_worker_stats(local_llm_pre_worker_items)
    summary["worktree_active_total"] = worktree_active_total
    summary["active_total"] = active_total


def actionable_queue_attention_total(plan: dict[str, Any] | None, fallback_queue_tasks: int = 0) -> int:
    if not isinstance(plan, dict):
        return safe_int(fallback_queue_tasks)
    if safe_int(plan.get("total")) == 0:
        return safe_int(fallback_queue_tasks)
    by_action = plan.get("by_action") if isinstance(plan.get("by_action"), dict) else {}
    return (
        safe_int(by_action.get("owner_required"))
        + safe_int(by_action.get("role_rework"))
        + safe_int(by_action.get("unknown"))
    )


def infra_blocked_queue_attention_total(plan: dict[str, Any] | None) -> int:
    if not isinstance(plan, dict):
        return 0
    by_action = plan.get("by_action") if isinstance(plan.get("by_action"), dict) else {}
    return (
        safe_int(by_action.get("environment_required"))
        + safe_int(by_action.get("role_actionable_after_infra"))
    )


def worktree_plan_covers_non_git_root(project_worktree_plan: dict[str, Any] | None) -> bool:
    if not isinstance(project_worktree_plan, dict):
        return False
    blockers = project_worktree_plan.get("blockers")
    if isinstance(blockers, list) and blockers:
        return True
    action = str(project_worktree_plan.get("action") or "")
    return action in {"clone_and_set_automation_path", "set_automation_path"}


def effective_human_attention_total(
    breakdown: dict[str, Any],
    queue_plan: dict[str, Any] | None = None,
    project_worktree_plan: dict[str, Any] | None = None,
    *,
    suppress_failed_role_state: bool = False,
) -> int:
    return sum(effective_human_attention_breakdown(
        breakdown,
        queue_plan,
        project_worktree_plan,
        suppress_failed_role_state=suppress_failed_role_state,
    ).values())


def effective_human_attention_breakdown(
    breakdown: dict[str, Any],
    queue_plan: dict[str, Any] | None = None,
    project_worktree_plan: dict[str, Any] | None = None,
    *,
    suppress_failed_role_state: bool = False,
) -> dict[str, int]:
    result = zero_human_attention_breakdown()
    result["queue_tasks"] = actionable_queue_attention_total(queue_plan, safe_int(breakdown.get("queue_tasks")))
    infra_covers_role_state = worktree_plan_covers_non_git_root(project_worktree_plan)
    non_git_roots = safe_int(breakdown.get("non_git_project_roots"))
    result["non_git_project_roots"] = 0 if infra_covers_role_state else non_git_roots
    result["failed_role_states"] = 0 if infra_covers_role_state or suppress_failed_role_state else safe_int(breakdown.get("failed_role_states"))
    for key in result:
        if key in {"queue_tasks", "non_git_project_roots", "failed_role_states"}:
            continue
        result[key] = safe_int(breakdown.get(key))
    return result


def infra_blocked_attention_total(
    breakdown: dict[str, Any],
    queue_plan: dict[str, Any] | None = None,
    project_worktree_plan: dict[str, Any] | None = None,
) -> int:
    total = infra_blocked_queue_attention_total(queue_plan)
    if worktree_plan_covers_non_git_root(project_worktree_plan):
        total += safe_int(breakdown.get("non_git_project_roots"))
        total += safe_int(breakdown.get("failed_role_states"))
    return total


def queue_plan_has_worker_host_blocker(queue_plan: dict[str, Any] | None) -> bool:
    if not isinstance(queue_plan, dict):
        return False
    for item in queue_plan.get("items") or []:
        if not isinstance(item, dict):
            continue
        if item.get("synthetic") and item.get("codex_bin") and item.get("action") == "environment_required":
            return True
    return False


def annotate_project_effective_attention(project: dict[str, Any]) -> None:
    breakdown = project.get("needs_human_breakdown")
    if not isinstance(breakdown, dict):
        return
    worker_host_blocker = project.get("worker_host_blocker")
    project["worker_host_blocked_candidates"] = (
        safe_int(worker_host_blocker.get("affected_task_count"))
        if isinstance(worker_host_blocker, dict)
        else 0
    )
    queue_plan = project.get("queue_attention_plan")
    if not isinstance(queue_plan, dict):
        queue_plan = None
    worktree_plan = project.get("automation_worktree_plan")
    if not isinstance(worktree_plan, dict):
        worktree_plan = None
    suppress_failed_role_state = isinstance(project.get("worker_host_blocker"), dict) or queue_plan_has_worker_host_blocker(queue_plan)
    project_effective_breakdown = effective_human_attention_breakdown(
        breakdown,
        queue_plan,
        worktree_plan,
        suppress_failed_role_state=suppress_failed_role_state,
    )
    project["needs_human_effective_breakdown"] = project_effective_breakdown
    project["needs_human_effective"] = sum(project_effective_breakdown.values())
    project["infra_blocked_attention"] = infra_blocked_attention_total(breakdown, queue_plan, worktree_plan)
    if isinstance(queue_plan, dict) and isinstance(queue_plan.get("by_lane"), dict):
        counts = project.get("counts")
        if isinstance(counts, dict):
            counts["queue_attention_by_lane"] = {
                lane: safe_int(queue_plan["by_lane"].get(lane))
                for lane in ("human", "environment", "architect", "dispatcher", "worker", "integrator", "finalizer", "unknown")
            }
    if worktree_plan_covers_non_git_root(worktree_plan):
        warnings = project.get("warnings")
        if isinstance(warnings, list):
            queue_suffix = str(project.get("task_queue_path") or "AiStudio/Task_manager/task_queue.json").replace("\\", "/").strip("/")
            project["warnings"] = [
                item
                for item in warnings
                if not (
                    str(item).startswith("File does not exist:")
                    and str(item).replace("\\", "/").endswith(queue_suffix)
                )
            ]


def infer_run_outcome(run: dict[str, Any] | None) -> str:
    if not isinstance(run, dict):
        return ""
    status = normalize_status(run.get("status"))
    task_status_after = normalize_status(run.get("task_status_after"))
    if run.get("needs_human"):
        return "rework"
    if status == "needs_human" or "needs_" in task_status_after or task_status_after in {
        "blocked",
        "owner_review",
        "owner_approval_needed",
        "owner_input_needed",
        "review_blocked",
        "needs_review",
        "needs_owner",
        "needs_rework",
        "needs_dispatcher_split",
        "needs_stronger_agent",
        "needs_architect",
        "needs_task_packet",
        "needs_task_spec",
        "failed",
        "superseded",
        "test_failed",
    }:
        return "rework"
    if status == "failed" or safe_int(run.get("exit_code")) != 0:
        return "rejected"
    if status == "success" or safe_int(run.get("exit_code")) == 0:
        if task_status_after in HUMAN_STATUSES:
            return "rework"
        if task_status_after in {
            "failed",
            "blocked",
            "superseded",
            "test_failed",
            "finalization_blocked",
            "integration_blocked",
        }:
            return "rework"
        return "done"
    return "done" if status else ""


def infer_event_role_outcome(event: dict[str, Any] | None) -> tuple[str, str, dt.datetime | None]:
    if not isinstance(event, dict):
        return "", "", None
    role = RUN_ROLE_TO_LANE.get(normalize_status(event.get("role")), "")
    event_name = normalize_status(event.get("event"))
    next_role = RUN_ROLE_TO_LANE.get(normalize_status(event.get("next_role")), "")
    if event_name == "task_worker_done":
        return "worker", "done", parse_datetime(event.get("created_at"))
    if event_name == "integration_requested" and role == "worker":
        return "worker", "done", parse_datetime(event.get("created_at"))
    if event_name in {"worker_ready_available", "integration_routed_closed"} and role == "dispatcher":
        return "dispatcher", "done", parse_datetime(event.get("created_at"))
    if event_name in {"dispatcher_requested", "dispatcher_rebuild_requested", "needs_human_created"} and (
        role == "dispatcher" or next_role == "dispatcher" or not role
    ):
        return "dispatcher", "rework", parse_datetime(event.get("created_at"))
    if event_name in {"worker_fix_requested", "worker_retry_requested"}:
        return "worker", "rework", parse_datetime(event.get("created_at"))
    if event_name in {"integration_recorded", "integration_done", "direct_merge_recorded"}:
        return "integrator", "done", parse_datetime(event.get("created_at"))
    if event_name == "finalization_requested":
        return role or "integrator", "done", parse_datetime(event.get("created_at"))
    if event_name == "finalization_recorded":
        return "finalizer", "done", parse_datetime(event.get("created_at"))
    if event_name in {"integration_routed", "integration_rework_requested", "integrator_review_required"}:
        return role or "integrator", "rework", parse_datetime(event.get("created_at"))
    if event_name in {"finalization_blocked", "finalization_rework_requested"}:
        return role or "finalizer", "rework", parse_datetime(event.get("created_at"))
    return "", "", None


def infer_event_target_role_outcome(event: dict[str, Any] | None) -> tuple[str, str, dt.datetime | None]:
    if not isinstance(event, dict):
        return "", "", None
    event_name = normalize_status(event.get("event"))
    next_role = RUN_ROLE_TO_LANE.get(normalize_status(event.get("next_role")), "")
    if next_role and event_name in {
        "architect_requested",
        "architecture_requested",
        "dispatcher_requested",
        "dispatcher_rebuild_requested",
        "needs_human_created",
        "needs_architect",
        "needs_task_packet",
        "needs_dispatcher",
        "worker_fix_requested",
        "worker_retry_requested",
        "integration_rework_requested",
        "integrator_review_required",
        "finalization_rework_requested",
    }:
        return next_role, "rework", parse_datetime(event.get("created_at"))
    return "", "", None


def infer_task_outcome(task: dict[str, Any] | None) -> str:
    normalized_status = normalize_status(task.get("status") if isinstance(task, dict) else "")
    fallback_status = normalize_status(task.get("status_raw") if isinstance(task, dict) else "")
    if not normalized_status:
        normalized_status = fallback_status
    if "failed" in normalized_status:
        return "rejected"
    if normalized_status in {"needs_human", "needs_review", "needs_owner", "needs_dispatcher_split", "needs_rework"}:
        return "rework"
    if normalized_status.startswith("needs_") or normalized_status in HUMAN_STATUSES:
        return "rework"
    if normalized_status in {"stale", "stale_or_superseded", "superseded"}:
        return "rework"
    if (
        normalized_status in COMPLETED_STATUSES
        or "done" in normalized_status
        or "approved" in normalized_status
        or "merged" in normalized_status
    ):
        return "done"
    if fallback_status and fallback_status != normalized_status:
        return infer_task_outcome({"status": fallback_status})
    return ""


def run_completed_at(run: dict[str, Any] | None) -> dt.datetime | None:
    if not isinstance(run, dict):
        return None
    completed = parse_datetime(run.get("ended_at"))
    if completed is not None:
        return completed
    return parse_datetime(run.get("started_at"))


def adapt_snapshot_source_payload(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Convert legacy snapshot field names to current dashboard schema."""

    def set_alias_if_missing(
        target: dict[str, Any],
        canonical: str,
        *aliases: str,
        require_dict: bool = False,
    ) -> None:
        if canonical in target:
            return
        for key in aliases:
            value = target.get(key)
            if value is None:
                continue
            if require_dict and not isinstance(value, dict):
                continue
            target[canonical] = value
            return

    for project in snapshot.get("projects") or []:
        if not isinstance(project, dict):
            continue
        set_alias_if_missing(project, "project_id", "id")
        set_alias_if_missing(project, "counts", "task_counts", "task_count", require_dict=True)
        set_alias_if_missing(project, "added_counts", "tasks_added", "added_counts", "added_count", require_dict=True)
        set_alias_if_missing(
            project,
            "completed_recent",
            "tasks_completed_recent",
            "completed_counts",
            "completed_count",
            require_dict=True,
        )

    summary = snapshot.get("summary")
    if isinstance(summary, dict):
        set_alias_if_missing(summary, "task_counts", "task_count", "counts", require_dict=True)
        set_alias_if_missing(summary, "tasks_added", "added_counts", "added_count", require_dict=True)
        set_alias_if_missing(summary, "tasks_completed_recent", "completed_counts", "completed_count", "completed_recent", require_dict=True)

    return snapshot


def zero_role_outcome_stats() -> dict[str, Any]:
    return {
        role: {
            outcome: {
                "total": 0,
                "today": 0,
                "week": 0,
                "last_24h": 0,
            }
            for outcome in RUN_OUTCOME_KEYS
        }
        for role in TASK_LANE_LABELS
    }


def accumulate_role_outcome_stats(
    target: dict[str, Any],
    role: str,
    outcome: str,
    at: dt.datetime | None,
    current: dt.datetime | None = None,
) -> None:
    if not outcome:
        return
    role = str(role or "").strip()
    if role not in target:
        return
    if outcome not in target[role]:
        return
    bucket = target[role][outcome]
    bucket["total"] = safe_int(bucket.get("total")) + 1
    if not isinstance(at, dt.datetime):
        return
    current = current or dt.datetime.now(LOCAL_TZ)
    task_date = at.astimezone(LOCAL_TZ).date()
    today = current.date()
    week_start = today - dt.timedelta(days=today.weekday())
    if task_date == today:
        bucket["today"] = safe_int(bucket.get("today")) + 1
    if task_date >= week_start:
        bucket["week"] = safe_int(bucket.get("week")) + 1
    cutoff = current - dt.timedelta(hours=24)
    if cutoff <= at <= current:
        bucket["last_24h"] = safe_int(bucket.get("last_24h")) + 1


def normalize_snapshot_role_outcomes(snapshot: dict[str, Any]) -> dict[str, Any]:
    projects = snapshot.get("projects")
    if not isinstance(projects, list):
        return snapshot
    role_outcome_totals = zero_role_outcome_stats()
    for project in projects:
        if not isinstance(project, dict):
            continue
        counts = project.setdefault("counts", {})
        if not isinstance(counts, dict):
            counts = {}
            project["counts"] = counts
        role_outcomes = zero_role_outcome_stats()
        for role in TASK_LANE_LABELS:
            counts[f"lane_{role}"] = 0
        runs_bucket = project.get("runs") if isinstance(project.get("runs"), dict) else {}
        project_runs = runs_bucket.get("all") or runs_bucket.get("recent") or []
        latest_task_run: dict[str, dict[str, Any]] = {}
        queue_task_keys: set[str] = set()
        completed_task_keys: set[str] = set()
        for run in project_runs:
            if not isinstance(run, dict):
                continue
            task_id = str(run.get("task_id") or "").strip()
            if task_id and task_id not in latest_task_run:
                latest_task_run[task_id] = run
        for task in project.get("tasks") or []:
            if not isinstance(task, dict):
                continue
            task_id = str(task.get("id") or task.get("task_id") or "").strip()
            latest_run = latest_task_run.get(task_id)
            status = task_effective_status(task)
            bucket = classify_status(status)
            if task_id:
                queue_task_keys.update(task_target_keys({"task_id": task_id, "canonical_target_id": task.get("canonical_target_id")}))
            if bucket == "completed" and task_id:
                completed_task_keys.update(task_target_keys({"task_id": task_id, "canonical_target_id": task.get("canonical_target_id")}))
            lane = infer_task_role_lane(task, status, latest_run)
            if bucket not in {"completed", "postponed"}:
                counts[f"lane_{lane}"] = safe_int(counts.get(f"lane_{lane}")) + 1
            if latest_run:
                outcome = infer_run_outcome(latest_run)
                outcome_at = run_completed_at(latest_run)
            elif bucket == "completed":
                outcome = ""
                outcome_at = None
            elif bucket not in {"completed", "postponed"}:
                outcome = infer_task_outcome(task)
                outcome_at = None
            else:
                outcome = ""
                outcome_at = None
            accumulate_role_outcome_stats(role_outcomes, lane, outcome, outcome_at)
        accumulate_event_role_outcomes(
            role_outcomes,
            project.get("agent_events") or [],
            latest_task_run,
            completed_task_keys,
            queue_task_keys,
        )
        counts["role_outcomes"] = role_outcomes
        for role, role_stats in role_outcomes.items():
            for outcome in RUN_OUTCOME_KEYS:
                source = role_stats.get(outcome, {})
                target = role_outcome_totals[role][outcome]
                target["total"] += safe_int(source.get("total"))
                target["today"] += safe_int(source.get("today"))
                target["week"] += safe_int(source.get("week"))
                target["last_24h"] += safe_int(source.get("last_24h"))
    snapshot.setdefault("summary", {})["role_outcomes"] = role_outcome_totals
    return snapshot


def accumulate_event_role_outcomes(
    role_outcomes: dict[str, Any],
    events: list[dict[str, Any]],
    latest_task_run: dict[str, dict[str, Any]] | None = None,
    completed_task_keys: set[str] | None = None,
    queue_task_keys: set[str] | None = None,
) -> None:
    latest_task_run = latest_task_run or {}
    completed_task_keys = completed_task_keys or set()
    queue_task_keys = queue_task_keys or set()
    seen: set[tuple[str, str, str]] = set()
    seen_task_outcomes: set[tuple[str, str, str]] = set()
    sorted_events = sorted(
        [event for event in events if isinstance(event, dict)],
        key=lambda item: str(item.get("created_at") or ""),
        reverse=True,
    )
    for event in sorted_events:
        role, outcome, outcome_at = infer_event_role_outcome(event)
        if role not in TASK_LANE_LABELS or not outcome:
            continue
        target_keys = task_target_keys(event)
        if role in {"integrator", "finalizer"} and event_has_non_task_target(event):
            continue
        task_id = str(event.get("task_id") or event.get("canonical_target_id") or "").strip()
        event_name = normalize_status(event.get("event"))
        dedupe_key = (role, event_name, task_id or str(event.get("event_id") or ""))
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)
        if task_id:
            task_outcome_key = (role, outcome, task_id.removeprefix("task:"))
            if task_outcome_key in seen_task_outcomes:
                continue
            seen_task_outcomes.add(task_outcome_key)
        if task_id and task_id in latest_task_run:
            continue
        if role == "finalizer" and outcome == "done" and target_keys and (target_keys & queue_task_keys) and not (target_keys & completed_task_keys):
            continue
        accumulate_role_outcome_stats(role_outcomes, role, outcome, outcome_at)
        target_role, target_outcome, target_outcome_at = infer_event_target_role_outcome(event)
        if target_role not in TASK_LANE_LABELS or not target_outcome or target_role == role:
            continue
        target_dedupe_key = (target_role, event_name, task_id or str(event.get("event_id") or ""))
        if target_dedupe_key in seen:
            continue
        seen.add(target_dedupe_key)
        if task_id:
            task_outcome_key = (target_role, target_outcome, task_id.removeprefix("task:"))
            if task_outcome_key in seen_task_outcomes:
                continue
            seen_task_outcomes.add(task_outcome_key)
        if task_id and task_id in latest_task_run:
            continue
        accumulate_role_outcome_stats(role_outcomes, target_role, target_outcome, target_outcome_at)


def task_target_keys(value: dict[str, Any]) -> set[str]:
    keys: set[str] = set()
    for field in ("id", "task_id", "canonical_target_id"):
        item = str(value.get(field) or "").strip()
        if not item:
            continue
        keys.add(item)
        keys.add(item.removeprefix("task:") if item.startswith("task:") else f"task:{item}")
    return keys


def event_has_non_task_target(value: dict[str, Any]) -> bool:
    for field in ("id", "task_id", "canonical_target_id"):
        item = str(value.get(field) or "").strip()
        if item.startswith("source-artifact:"):
            return True
    return False


def status_rank_fallback(normalized: str) -> int:
    if not normalized:
        return 0

    if any(word in normalized for word in TASK_STATUS_COMPLETED_KEYWORDS):
        return 9
    if any(word in normalized for word in TASK_STATUS_HUMAN_KEYWORDS):
        return 5
    if any(word in normalized for word in TASK_STATUS_POSTPONED_KEYWORDS):
        return 6
    if any(word in normalized for word in TASK_STATUS_ACTIVE_KEYWORDS):
        return 4
    if any(word in normalized for word in TASK_STATUS_WAITING_KEYWORDS):
        return 0
    return 0


def task_size(task: dict[str, Any]) -> str:
    value = task.get("size") or task.get("complexity") or task.get("effort") or task.get("task_size")
    if value is None:
        return "-"
    normalized = str(value).strip().upper()
    return normalized or "-"


def task_created_at(task: dict[str, Any]) -> dt.datetime | None:
    for key in ("created_at", "added_at", "created_on", "added_on", "date_added", "imported_at"):
        parsed = parse_datetime(task.get(key))
        if parsed is not None:
            return parsed
    return None


def task_created_text(task: dict[str, Any]) -> str:
    for key in ("created_at", "added_at", "created_on", "added_on", "date_added", "imported_at"):
        value = task.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def first_datetime_value(*items: tuple[dict[str, Any] | None, tuple[str, ...]]) -> dt.datetime | None:
    for source, keys in items:
        if not isinstance(source, dict):
            continue
        for key in keys:
            parsed = parse_datetime(source.get(key))
            if parsed is not None:
                return parsed
    return None


def first_text_value(*items: tuple[dict[str, Any] | None, tuple[str, ...]]) -> str:
    for source, keys in items:
        if not isinstance(source, dict):
            continue
        for key in keys:
            value = source.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return ""


def task_started_at(task: dict[str, Any], lock: dict[str, Any] | None, run: dict[str, Any] | None) -> dt.datetime | None:
    return first_datetime_value(
        (task, ("started_at", "claimed_at", "locked_at", "in_progress_at")),
        (lock, ("at", "started_at", "claimed_at", "locked_at")),
        (run, ("started_at",)),
    )


def task_started_text(task: dict[str, Any], lock: dict[str, Any] | None, run: dict[str, Any] | None) -> str:
    return first_text_value(
        (task, ("started_at", "claimed_at", "locked_at", "in_progress_at")),
        (lock, ("at", "started_at", "claimed_at", "locked_at")),
        (run, ("started_at",)),
    )


def task_updated_text(task: dict[str, Any], lock: dict[str, Any] | None, run: dict[str, Any] | None) -> str:
    return first_text_value(
        (task, ("updated_at", "modified_at", "last_updated_at", "last_activity_at")),
        (lock, ("updated_at", "at")),
        (run, ("ended_at", "updated_at", "started_at")),
    )


def task_lock_expires_at(task: dict[str, Any], lock: dict[str, Any] | None) -> dt.datetime | None:
    task_lock_source = task if task_has_blocking_lock(task) else None
    active_lock = lock if isinstance(lock, dict) and is_blocking_lock_state(lock.get("state")) else None
    return first_datetime_value(
        (task_lock_source, ("lock_expires_at", "expires_at")),
        (active_lock, ("expires_at",)),
    )


def task_lock_expires_text(task: dict[str, Any], lock: dict[str, Any] | None) -> str:
    task_lock_source = task if task_has_blocking_lock(task) else None
    active_lock = lock if isinstance(lock, dict) and is_blocking_lock_state(lock.get("state")) else None
    return first_text_value(
        (task_lock_source, ("lock_expires_at", "expires_at")),
        (active_lock, ("expires_at",)),
    )


def active_task_age_summary(task_rows: list[dict[str, Any]], now: dt.datetime | None = None) -> dict[str, Any]:
    current = now or dt.datetime.now(LOCAL_TZ)
    active_rows = [row for row in task_rows if row.get("bucket") == "active"]
    started_values: list[dt.datetime] = []
    stale_lock_count = 0
    missing_started_count = 0
    for row in active_rows:
        started = parse_datetime(row.get("started_at"))
        if started is None:
            missing_started_count += 1
        else:
            started_values.append(started)
        expires_at = parse_datetime(row.get("lock_expires_at"))
        if expires_at is not None and expires_at < current:
            stale_lock_count += 1
    oldest_started = min(started_values) if started_values else None
    oldest_age_hours = None
    if oldest_started is not None:
        oldest_age_hours = round(max(0.0, (current - oldest_started).total_seconds() / 3600), 1)
    return {
        "active": len(active_rows),
        "stale_lock_count": stale_lock_count,
        "missing_started_at": missing_started_count,
        "oldest_started_at": utc_timestamp_text(oldest_started.astimezone(dt.timezone.utc)) if oldest_started else None,
        "oldest_age_hours": oldest_age_hours,
    }


def task_completed_at(task: dict[str, Any]) -> dt.datetime | None:
    for key in ("completed_at", "finalized_at", "merged_at", "done_at", "closed_at", "approved_at"):
        parsed = parse_datetime(task.get(key))
        if parsed is not None:
            return parsed
    return None


def task_completed_text(task: dict[str, Any]) -> str:
    for key in ("completed_at", "finalized_at", "merged_at", "done_at", "closed_at", "approved_at"):
        value = task.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def period_counts_by_date(items: list[dt.datetime], now: dt.datetime | None = None) -> dict[str, int]:
    current = now or dt.datetime.now(LOCAL_TZ)
    today = current.date()
    week_start = today - dt.timedelta(days=today.weekday())
    today_count = 0
    week_count = 0
    for item in items:
        item_date = item.astimezone(LOCAL_TZ).date()
        if item_date == today:
            today_count += 1
        if item_date >= week_start:
            week_count += 1
    return {"today": today_count, "week": week_count, "with_known_at": len(items)}


def task_outcomes_last_24h(
    completed_dates: list[dt.datetime],
    latest_task_run: dict[str, dict[str, Any]],
    now: dt.datetime | None = None,
) -> dict[str, int]:
    """Count completed tasks and distinct latest failed attempts in a rolling window."""

    current = now or dt.datetime.now(LOCAL_TZ)
    cutoff = current - dt.timedelta(hours=24)

    def inside_window(value: dt.datetime | None) -> bool:
        return value is not None and cutoff <= value <= current

    completed = sum(1 for value in completed_dates if inside_window(value))
    failed_task_ids: set[str] = set()
    for task_id, run in latest_task_run.items():
        if not task_id or not inside_window(run_completed_at(run)):
            continue
        status = normalize_status(run.get("status"))
        task_status_after = normalize_status(run.get("task_status_after"))
        if (
            status in {"failed", "error", "test_failed"}
            or safe_int(run.get("exit_code")) != 0
            or task_status_after in {"failed", "error", "test_failed"}
        ):
            failed_task_ids.add(task_id)
    return {
        "window_hours": 24,
        "completed": completed,
        "failed": len(failed_task_ids),
        "completed_with_known_at": len(completed_dates),
        "attempted_tasks_with_known_at": sum(
            1 for task_id, run in latest_task_run.items() if task_id and run_completed_at(run) is not None
        ),
    }


def task_flow_last_24h(
    role_outcomes: dict[str, Any] | None,
    recent_outcomes: dict[str, Any] | None = None,
) -> dict[str, int]:
    """Expose successful task-flow stages from the existing role evidence.

    Dispatcher ``done`` means a task was prepared for the next execution lane,
    Worker ``done`` means implementation/execution completed, Integrator
    ``done`` means accepted integration was recorded, and Finalizer ``done``
    means the task reached the finalization stage. These are stage counters,
    not mutually exclusive tasks: one task may advance through more than one
    stage during the same rolling window.
    """

    stats = role_outcomes if isinstance(role_outcomes, dict) else {}
    recent = recent_outcomes if isinstance(recent_outcomes, dict) else {}

    def successful(role: str) -> int:
        role_stats = stats.get(role) if isinstance(stats.get(role), dict) else {}
        done = role_stats.get("done") if isinstance(role_stats.get("done"), dict) else {}
        return safe_int(done.get("last_24h"))

    return {
        "window_hours": 24,
        "prepared": successful("dispatcher"),
        "executed": successful("worker"),
        "integrated": successful("integrator"),
        "finalized": successful("finalizer"),
        "failed": safe_int(recent.get("failed")),
    }


def task_added_counts(tasks: list[dict[str, Any]], now: dt.datetime | None = None) -> dict[str, int]:
    current = now or dt.datetime.now(LOCAL_TZ)
    today = current.date()
    week_start = today - dt.timedelta(days=today.weekday())
    today_count = 0
    week_count = 0
    with_known_created_at = 0
    for task in tasks:
        created = task_created_at(task)
        if created is None:
            continue
        with_known_created_at += 1
        created_date = created.date()
        if created_date == today:
            today_count += 1
        if created_date >= week_start:
            week_count += 1
    return {
        "today": today_count,
        "week": week_count,
        "with_known_created_at": with_known_created_at,
    }


def rel_project_path(project: dict[str, Any], key: str, default: str) -> Path:
    return effective_project_root(project) / str(project.get(key, default))


def local_project_path(project: dict[str, Any], path: str) -> Path:
    return Path(str(project.get("local_path", ""))).expanduser() / path


def effective_project_root(project: dict[str, Any]) -> Path:
    return Path(str(project.get("automation_path") or project.get("local_path") or "")).expanduser()


def normalize_project(raw: dict[str, Any]) -> dict[str, Any]:
    raw = project_registry.normalize_project(raw)
    project_id = str(raw.get("project_id") or raw.get("id") or raw.get("name") or "unknown")
    configured_task_queue_path = str(
        raw.get("task_queue_path", "AiStudio/Task_manager/task_queue.json")
    ).strip()
    legacy_task_queue_path = ""
    if configured_task_queue_path in {"docs/plans/task_queue.json", "templates/docs/plans/task_queue.json"}:
        legacy_task_queue_path = configured_task_queue_path
        configured_task_queue_path = "AiStudio/Task_manager/task_queue.json"
    base_branch = str(raw.get("base_branch", ""))
    base_ref = str(raw.get("base_ref", ""))
    github_repo = str(raw.get("github_repo", "")).strip()
    task_queue_github_path = str(raw.get("task_queue_github_path", "")).strip()
    if not task_queue_github_path:
        task_queue_github_path = configured_task_queue_path
    task_queue_github_ref = str(raw.get("task_queue_github_ref", "")).strip()
    if not task_queue_github_ref:
        task_queue_github_ref = base_ref or base_branch
    if "prefer_github_task_queue" in raw:
        prefer_github_task_queue = bool(raw.get("prefer_github_task_queue"))
    else:
        prefer_github_task_queue = False
    return {
        "project_id": project_id,
        "name": str(raw.get("name") or project_id),
        "enabled": bool(raw.get("enabled", True)),
        "local_path": str(raw.get("local_path", "")),
        "automation_path": str(raw.get("automation_path", "")).strip(),
        "github_repo": github_repo,
        "base_ref": base_ref,
        "base_branch": base_branch,
        "task_queue_path": configured_task_queue_path,
        "task_history_path": str(raw.get("task_history_path", "AiStudio/Task_manager/task_history.json")),
        "legacy_task_queue_path": legacy_task_queue_path,
        "task_queue_git_ref": str(raw.get("task_queue_git_ref", "")),
        "task_queue_github_path": task_queue_github_path,
        "task_queue_github_ref": task_queue_github_ref,
        "prefer_github_task_queue": prefer_github_task_queue,
        "ignore_local_task_state_overrides": bool(raw.get("ignore_local_task_state_overrides", False)),
        "completed_task_queue_github_paths": [
            str(path)
            for path in raw.get("completed_task_queue_github_paths", [])
            if path
        ],
        "extra_task_queue_paths": [
            str(path)
            for path in raw.get("extra_task_queue_paths", raw.get("additional_task_queue_paths", []))
            if path
        ],
        "prefer_markdown_tasks": bool(raw.get("prefer_markdown_tasks", False)),
        "markdown_task_globs": [
            str(path)
            for path in raw.get("markdown_task_globs", raw.get("markdown_task_paths", []))
            if path
        ],
        "agent_locks_path": str(raw.get("agent_locks_path", "AiStudio/Task_manager/agent_locks.json")),
        "owner_directives_path": str(raw.get("owner_directives_path", "AiStudio/Task_manager/owner_directives.json")),
        "workspace_root": str(raw.get("workspace_root", "")),
        "git_store_path": str(raw.get("git_store_path", "")),
        "checkouts": raw.get("checkouts") if isinstance(raw.get("checkouts"), dict) else {},
        "branches": raw.get("branches") if isinstance(raw.get("branches"), dict) else {},
        "version_file": str(raw.get("version_file", "PROJECT_VERSION.json")),
        "project_index": str(raw.get("project_index", "PROJECT_INDEX.md")),
        "documentation_manifest": str(raw.get("documentation_manifest", "DOCUMENTATION_MANIFEST.json")),
        "task_manager_branch_role": str(raw.get("task_manager_branch_role", "codex")),
        "automation_allowed": bool(raw.get("automation_allowed", raw.get("enabled", True))),
        "health_threshold": safe_int(raw.get("health_threshold", 85)),
        "quarantine_mode": str(raw.get("quarantine_mode", "advisory")),
    }


def load_projects(registry_path: Path | None) -> tuple[list[dict[str, Any]], list[str]]:
    if not registry_path:
        return [], ["No project registry configured."]
    try:
        projects, warnings = project_registry.load_projects(registry_path.expanduser())
    except Exception as exc:
        return [], [str(exc)]
    return [normalize_project(p) for p in projects if p.get("enabled", True)], warnings


def load_tasks_from_data(data: Any, source_path: str) -> tuple[list[dict[str, Any]], list[str]]:
    if isinstance(data, list):
        tasks = data
    elif isinstance(data, dict):
        tasks = data.get("tasks", data.get("queue", data.get("items", [])))
    else:
        return [], [f"Task queue must be a JSON object or array: {source_path}"]
    if not isinstance(tasks, list):
        return [], [f"Task queue tasks must be an array: {source_path}"]
    rows = []
    for task in tasks:
        if not isinstance(task, dict):
            continue
        row = dict(task)
        row["_source_path"] = source_path
        rows.append(row)
    return rows, []


def load_tasks_from_path(queue_path: Path) -> tuple[list[dict[str, Any]], list[str]]:
    data, error = load_json(queue_path)
    if error:
        return [], [error]
    return load_tasks_from_data(data, str(queue_path))


def ensure_task_queue_ref_fresh(project_root: Path, git_ref: str) -> str | None:
    normalized = str(git_ref or "").strip()
    if not normalized or not project_root.exists():
        return None

    remote = ""
    if normalized.startswith("refs/remotes/"):
        parts = normalized.split("/", 2)
        if len(parts) >= 3:
            remote = parts[1]
    elif "/" in normalized:
        remote = normalized.split("/", 1)[0]

    if not remote:
        return None

    cache_key = (str(project_root.resolve()), normalized)
    now = time.time()
    with _GIT_REF_FETCH_LOCK:
        cached_error = _GIT_REF_FETCH_ERROR.get(cache_key)
        last = _GIT_REF_FETCH_STATE.get(cache_key, 0.0)
        if now - last < GIT_REF_FETCH_TTL_SECONDS:
            return cached_error

    with _GIT_REF_FETCH_LOCK:
        _GIT_REF_FETCH_STATE[cache_key] = now
        _GIT_REF_FETCH_ERROR.pop(cache_key, None)

    try:
        subprocess.run(
            ["git", "fetch", remote],
            cwd=str(project_root),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=20,
            check=True,
        )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
        with _GIT_REF_FETCH_LOCK:
            _GIT_REF_FETCH_ERROR[cache_key] = f"Не удалось обновить {normalized}: {exc}"
        return _GIT_REF_FETCH_ERROR[cache_key]

    return None


def load_tasks_from_git_ref(project: dict[str, Any], rel_path: str, git_ref: str) -> tuple[list[dict[str, Any]], list[str]]:
    project_root = effective_project_root(project)
    if not project_root.exists():
        return [], [f"Project path does not exist: {project_root}"]
    fetch_warning = ensure_task_queue_ref_fresh(project_root, git_ref)
    try:
        raw = subprocess.check_output(
            ["git", "show", f"{git_ref}:{rel_path}"],
            cwd=project_root,
            stderr=subprocess.PIPE,
            timeout=15,
        )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        message = f"Cannot read {rel_path} from git ref {git_ref}: {exc}"
        if fetch_warning:
            message = f"{fetch_warning}; {message}"
        return [], [message]
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        message = f"Invalid JSON in {git_ref}:{rel_path}: {exc}"
        if fetch_warning:
            message = f"{fetch_warning}; {message}"
        return [], [message]
    return load_tasks_from_data(data, f"{git_ref}:{rel_path}")


def load_tasks_from_github(project: dict[str, Any], rel_path: str, git_ref: str) -> tuple[list[dict[str, Any]], list[str]]:
    repo = str(project.get("github_repo") or "").strip()
    ref = str(git_ref or project.get("base_branch") or project.get("base_ref") or "").strip()
    path = str(rel_path or "").strip()
    if not repo:
        return [], ["GitHub task source requires github_repo."]
    if not path:
        return [], ["GitHub task source requires task_queue_github_path."]
    if not ref:
        return [], ["GitHub task source requires task_queue_github_ref or base_branch."]
    endpoint = f"repos/{repo}/contents/{quote(path, safe='/')}?ref={quote(ref, safe='')}"
    try:
        raw = subprocess.check_output(
            ["gh", "api", endpoint],
            stderr=subprocess.PIPE,
            timeout=20,
        )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
        return [], [f"Cannot read GitHub task queue {repo}:{ref}:{path}: {exc}"]

    def parse_content(json_payload: str) -> tuple[dict[str, Any] | None, str | None]:
        try:
            return json.loads(json_payload), None
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            return None, f"{exc}"

    payload, parse_error = parse_content(raw.decode("utf-8"))
    if payload is None:
        return [], [f"Invalid GitHub task queue response {repo}:{ref}:{path}: {parse_error}"]

    content = str(payload.get("content") or "")
    encoding = str(payload.get("encoding") or "").strip()

    if encoding == "none" or not content.strip():
        try:
            raw = subprocess.check_output(
                ["gh", "api", "-H", "accept: application/vnd.github.v3.raw", endpoint],
                stderr=subprocess.PIPE,
                timeout=20,
            )
            data = json.loads(raw.decode("utf-8"))
            return load_tasks_from_data(data, f"github:{repo}:{ref}:{path}")
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
            return [], [f"Cannot read raw GitHub task queue {repo}:{ref}:{path}: {exc}"]
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            return [], [f"Invalid raw GitHub task queue JSON {repo}:{ref}:{path}: {exc}"]

    try:
        data = json.loads(base64.b64decode(content).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError, TypeError) as exc:
        return [], [f"Invalid GitHub task queue JSON {repo}:{ref}:{path}: {exc}"]

    return load_tasks_from_data(data, f"github:{repo}:{ref}:{path}")


def is_markdown_task_id(value: Any) -> bool:
    text = str(value or "").strip().strip("`")
    if not text:
        return False
    return bool(
        re.fullmatch(r"[A-Z][A-Z0-9]*-[0-9]+(?:\.[0-9]+)*", text)
        or re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", text)
    )


def load_markdown_tasks_from_path(markdown_path: Path) -> tuple[list[dict[str, Any]], list[str]]:
    try:
        text = markdown_path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = markdown_path.read_text(errors="ignore")
    except OSError as exc:
        return [], [f"Cannot read {markdown_path}: {exc}"]

    rows: list[dict[str, Any]] = []
    table_headers: list[str] | None = None
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            table_headers = None
            continue
        if stripped.startswith("|"):
            cells = [cell.strip() for cell in stripped.strip("|").split("|")]
            if not cells:
                continue
            if all(re.fullmatch(r":?-{3,}:?", cell.replace(" ", "")) for cell in cells):
                continue
            normalized = [cell.lower() for cell in cells]
            if "status" in normalized and ("queue id" in normalized or "id" in normalized):
                table_headers = normalized
                continue
            if table_headers and len(cells) >= 2 and is_markdown_task_id(cells[0]):
                by_header = {
                    table_headers[index]: cells[index]
                    for index in range(min(len(table_headers), len(cells)))
                }
                rows.append({
                    "id": cells[0],
                    "title": by_header.get("result") or by_header.get("source mvp") or cells[-1],
                    "status": by_header.get("status") or "planned",
                    "priority": by_header.get("priority") or "",
                    "complexity": by_header.get("complexity") or "",
                    "reason": by_header.get("result") or by_header.get("check") or "",
                    "_source_path": str(markdown_path),
                    "_source_type": "markdown_table",
                })
            continue

        checklist = re.match(r"^[-*]\s+\[([ xX-])\]\s+(.+?)\s*$", stripped)
        if checklist:
            marker = checklist.group(1)
            title = checklist.group(2).strip()
            if len(title) < 4:
                continue
            task_id_match = re.match(r"`?([A-Z][A-Z0-9]+-[0-9]+(?:\.[0-9]+)?)`?[:.\s-]+(.+)", title)
            task_id = task_id_match.group(1) if task_id_match else ""
            clean_title = task_id_match.group(2).strip() if task_id_match else title
            rows.append({
                "id": task_id or f"{markdown_path.stem}:{len(rows) + 1}",
                "title": clean_title,
                "status": "done" if marker.lower() == "x" else "planned",
                "_source_path": str(markdown_path),
                "_source_type": "markdown_checklist",
            })
    return rows, []


def load_project_tasks(project: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    queue_rel_path = str(project.get("task_queue_path") or "AiStudio/Task_manager/task_queue.json")
    git_ref = str(project.get("task_queue_git_ref") or "").strip()
    base_ref = str(project.get("base_ref") or "").strip()
    github_queue_path = str(project.get("task_queue_github_path") or "").strip()
    github_queue_ref = str(project.get("task_queue_github_ref") or "").strip()
    if not github_queue_ref:
        github_queue_ref = base_ref

    queue_refs: list[str] = []
    if git_ref and not project.get("prefer_github_task_queue"):
        queue_refs.append(git_ref)
    if base_ref and base_ref not in queue_refs and not project.get("prefer_github_task_queue"):
        queue_refs.append(base_ref)

    local_queue_path = rel_project_path(project, "task_queue_path", "AiStudio/Task_manager/task_queue.json")
    queue_paths = [local_queue_path]
    for extra_path in project.get("extra_task_queue_paths", []):
        queue_paths.append(effective_project_root(project) / str(extra_path))
    legacy_task_queue_path = str(project.get("legacy_task_queue_path") or "").strip()
    if legacy_task_queue_path:
        queue_paths.append(local_project_path(project, legacy_task_queue_path))

    tasks: list[dict[str, Any]] = []
    warnings: list[str] = []
    seen_ids: set[str] = set()
    task_index: dict[str, int] = {}

    def add_task(task: dict[str, Any], seen_key: str | None = None) -> None:
        task_id = str(task.get("id") or task.get("task_id") or "")
        if seen_key and seen_key in seen_ids:
            return
        if not task_id:
            tasks.append(task)
            return
        existing_index = task_index.get(task_id)
        if existing_index is None:
            task_index[task_id] = len(tasks)
            seen_ids.add(task_id)
            if seen_key:
                seen_ids.add(seen_key)
            tasks.append(task)
            return
        existing = tasks[existing_index]
        if status_rank(task_effective_status(task)) > status_rank(task_effective_status(existing)):
            tasks[existing_index] = task

    def load_markdown_globs() -> None:
        project_root = effective_project_root(project)
        for pattern in project.get("markdown_task_globs", []):
            for markdown_path in sorted(project_root.glob(str(pattern))):
                if not markdown_path.is_file():
                    continue
                loaded, task_warnings = load_markdown_tasks_from_path(markdown_path)
                warnings.extend(task_warnings)
                for task in loaded:
                    task_id = str(task.get("id") or task.get("task_id") or "")
                    source_key = f"{task_id}|{task.get('_source_path')}|{task.get('_source_type')}"
                    add_task(task, source_key if task_id else None)

    def load_completed_github_queues() -> None:
        for completed_path in project.get("completed_task_queue_github_paths", []):
            loaded, task_warnings = load_tasks_from_github(project, str(completed_path), github_queue_ref)
            warnings.extend(task_warnings)
            for task in loaded:
                if classify_status(task_effective_status(task)) != "completed":
                    continue
                task_id = str(task.get("id") or task.get("task_id") or "")
                source_key = f"completed|{task_id}|{task.get('_source_path')}"
                add_task(task, source_key if task_id else None)

    if project.get("prefer_github_task_queue") and github_queue_path:
        loaded, task_warnings = load_tasks_from_github(project, github_queue_path, github_queue_ref)
        warnings.extend(task_warnings)
        for task in loaded:
            add_task(task)
        if tasks:
            load_completed_github_queues()
            return tasks, warnings

    if project.get("prefer_markdown_tasks"):
        load_markdown_globs()
        if tasks:
            load_completed_github_queues()
            return tasks, warnings

    # Git refs are fallback for older dashboards; local/generated task stores are authoritative.
    project_root = effective_project_root(project)
    use_remote_refs = bool(queue_refs) and not local_queue_path.exists() and is_git_worktree(project_root)
    if use_remote_refs:
        for ref in queue_refs:
            loaded, task_warnings = load_tasks_from_git_ref(project, queue_rel_path, ref)
            warnings.extend(task_warnings)
            for task in loaded:
                add_task(task)

    for queue_path in queue_paths:
        if queue_path == local_queue_path and use_remote_refs and tasks and not queue_path.exists():
            continue
        loaded, task_warnings = load_tasks_from_path(queue_path)
        warnings.extend(task_warnings)
        for task in loaded:
            add_task(task)

    if project.get("prefer_markdown_tasks"):
        load_markdown_globs()
    load_completed_github_queues()
    return tasks, warnings


def load_project_task_history(project: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    history_path = rel_project_path(project, "task_history_path", "AiStudio/Task_manager/task_history.json")
    if not history_path.exists():
        return [], []
    loaded, warnings = load_tasks_from_path(history_path)
    for task in loaded:
        task["_source_kind"] = "task_history"
    return loaded, warnings


def load_project_agent_events(project: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    event_paths = [
        rel_project_path(project, "agent_events_path", "AiStudio/Task_manager/agent_events.jsonl"),
    ]
    legacy_path = str(project.get("legacy_agent_events_path") or "").strip()
    if legacy_path:
        event_paths.append(local_project_path(project, legacy_path))
    events: list[dict[str, Any]] = []
    warnings: list[str] = []
    seen_event_ids: set[str] = set()

    def add_event_lines(lines: list[str]) -> None:
        for line in lines:
            if not line.strip():
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(item, dict):
                continue
            event_id = str(item.get("event_id") or "").strip()
            if event_id and event_id in seen_event_ids:
                continue
            if event_id:
                seen_event_ids.add(event_id)
            events.append(item)

    for event_path in event_paths:
        if not event_path.exists():
            continue
        try:
            lines = event_path.read_text(encoding="utf-8").splitlines()
        except OSError as exc:
            warnings.append(f"Cannot read {event_path}: {exc}")
            continue
        add_event_lines(lines)
    if not events:
        project_root = effective_project_root(project)
        local_queue_path = rel_project_path(project, "task_queue_path", "AiStudio/Task_manager/task_queue.json")
        git_ref = str(project.get("task_queue_git_ref") or project.get("base_ref") or "").strip()
        rel_path = str(project.get("agent_events_path") or "AiStudio/Task_manager/agent_events.jsonl")
        if git_ref and project_root.exists() and not local_queue_path.exists():
            ensure_task_queue_ref_fresh(project_root, git_ref)
            try:
                raw = subprocess.check_output(
                    ["git", "show", f"{git_ref}:{rel_path}"],
                    cwd=project_root,
                    stderr=subprocess.DEVNULL,
                    timeout=15,
                ).decode("utf-8", "replace")
                add_event_lines(raw.splitlines())
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError):
                pass
    events.sort(key=lambda item: str(item.get("created_at") or ""), reverse=True)
    return events, warnings


def load_project_locks(project: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    locks_path = rel_project_path(project, "agent_locks_path", "AiStudio/Task_manager/agent_locks.json")
    if not locks_path.exists():
        return [], []
    data, error = load_json(locks_path)
    if error:
        return [], [error]
    if not isinstance(data, dict):
        return [], [f"Agent locks must be a JSON object: {locks_path}"]
    locks = data.get("locks", [])
    if not isinstance(locks, list):
        return [], [f"Agent locks must contain a locks array: {locks_path}"]
    return [lock for lock in locks if isinstance(lock, dict)], []


def task_embedded_lock(task: dict[str, Any]) -> dict[str, Any] | None:
    task_id = str(task.get("id") or task.get("task_id") or "").strip()
    if not task_id:
        return None
    lock = task.get("lock")
    if isinstance(lock, dict):
        row = dict(lock)
    elif isinstance(lock, str):
        row = {"state": lock}
    else:
        return None
    row.setdefault("task_id", task_id)
    row.setdefault("source", "task_queue")
    return row


def load_owner_directives(project: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    directives_path = rel_project_path(project, "owner_directives_path", "AiStudio/Task_manager/owner_directives.json")
    if not directives_path.exists():
        return [], []
    data, error = load_json(directives_path)
    if error:
        return [], [error]
    if not isinstance(data, dict):
        return [], [f"Owner directives must be a JSON object: {directives_path}"]
    directives = data.get("directives", [])
    if not isinstance(directives, list):
        return [], [f"Owner directives must contain a directives array: {directives_path}"]
    return [d for d in directives if isinstance(d, dict)], []


def owner_directive_requires_attention(directive: dict[str, Any]) -> bool:
    status = str(directive.get("status", "")).strip().lower()
    if status not in {"active", "needs_human", "action_required", "blocked"}:
        return False
    if status in {"needs_human", "action_required", "blocked"}:
        return True
    return any(bool(directive.get(key)) for key in ("requires_human_attention", "needs_human", "action_required"))


def task_display_reason(task: dict[str, Any]) -> str:
    for key in (
        "blocked_reason",
        "blocker",
        "blockers",
        "status_reason",
        "dispatcher_decision_reason",
        "integration_status",
        "dispatcher_decision",
        "escalation_reason",
        "handoff_note",
        "reason",
        "check",
        "type",
    ):
        value = task.get(key)
        if isinstance(value, list):
            value = ", ".join(str(item) for item in value if str(item).strip())
        elif isinstance(value, dict):
            value = json.dumps(value, ensure_ascii=False, sort_keys=True)
        text = str(value or "").strip()
        if text:
            return text
    return ""


TASK_ORIGIN_LABELS = {
    "roadmap": "roadmap",
    "project_rules_remediation": "project_rules_remediation",
    "task_doc_import": "task_doc_import",
    "integration_result": "integration_result",
    "dispatcher_repair": "dispatcher_repair",
    "other": "other",
}


TASK_WORK_CLASS_LABELS = {
    "direct_product_code": "direct_product_code",
    "project_map_rules": "project_map_rules",
    "backlog_task_docs": "backlog_task_docs",
    "dispatcher_preparation": "dispatcher_preparation",
    "integration_delivery": "integration_delivery",
    "roadmap_design": "roadmap_design",
}


def task_work_class_breakdown(origin_stats: dict[str, Any] | None) -> dict[str, int]:
    """Translate technical intake origins into owner-facing active-work classes.

    The mapping is intentionally lossless: every origin belongs to exactly one
    work class, so ``total`` can reconcile with the complete active backlog.
    Direct product/code work is the existing non-pipeline ``other`` lane; the
    remaining lanes are supporting backlog and delivery work, not discarded
    or hidden tasks.
    """
    stats = origin_stats if isinstance(origin_stats, dict) else {}
    result = {
        "direct_product_code": safe_int(stats.get("other")),
        "project_map_rules": safe_int(stats.get("project_rules_remediation")),
        "backlog_task_docs": safe_int(stats.get("task_doc_import")),
        "dispatcher_preparation": safe_int(stats.get("dispatcher_repair")),
        "integration_delivery": safe_int(stats.get("integration_result")),
        "roadmap_design": safe_int(stats.get("roadmap")),
    }
    result["primary_total"] = result["direct_product_code"]
    result["secondary_total"] = sum(
        result[key] for key in TASK_WORK_CLASS_LABELS if key != "direct_product_code"
    )
    result["total"] = result["primary_total"] + result["secondary_total"]
    return result


def task_reference_text(task: dict[str, Any]) -> str:
    parts: list[str] = []
    for key in (
        "source_file",
        "imported_by",
        "created_by",
        "type",
        "category",
        "dispatcher_decision",
        "integration_status",
        "status",
    ):
        value = task.get(key)
        if value is not None:
            parts.append(str(value))
    for key in ("context_docs", "doc_refs", "input_refs"):
        value = task.get(key)
        if isinstance(value, list):
            parts.extend(json.dumps(item, ensure_ascii=False, sort_keys=True) if isinstance(item, dict) else str(item) for item in value)
        elif isinstance(value, dict):
            parts.append(json.dumps(value, ensure_ascii=False, sort_keys=True))
        elif value is not None:
            parts.append(str(value))
    return "\n".join(parts).replace("\\", "/").lower()


def task_origin_class(task: dict[str, Any], effective_status: str | None = None) -> str:
    status = normalize_status(effective_status if effective_status is not None else task_effective_status(task))
    decision = normalize_status(task.get("dispatcher_decision"))
    text = task_reference_text(task)
    if status == "needs_dispatcher_repair" or decision == "needs_dispatcher_repair":
        return "dispatcher_repair"
    if status == "integration_requested" or decision == "needs_integrator_review" or "integration_requested" in text:
        return "integration_result"
    if (
        "project_rules_remediation_candidates" in text
        or "project_rules_update_cycle" in text
        or "automation/project_rules_" in text
    ):
        return "project_rules_remediation"
    if "docs/plans/tasks/" in text or "task_docs_queue_importer.py" in text:
        return "task_doc_import"
    if "docs/plans/mvp-blueprint/" in text or "mvp-roadmap.md" in text or "p0-worker-task-packets.md" in text:
        return "roadmap"
    return "other"


def zero_task_origin_breakdown() -> dict[str, int]:
    return {key: 0 for key in TASK_ORIGIN_LABELS}


def local_llm_pre_worker_stats(tasks: list[dict[str, Any]]) -> dict[str, Any]:
    target_kind = "automation/project_rules_remediation/task_pipeline"
    decisions: dict[str, int] = {}
    eligible = pending = attempted_tasks = attempts = 0
    last_decided_at = ""
    for task in tasks:
        kind = str(task.get("llm_task_kind") or task.get("type") or "")
        if kind != target_kind:
            continue
        eligible += 1
        decision = str(task.get("llm_pre_worker_decision") or "")
        if decision:
            decisions[decision] = safe_int(decisions.get(decision)) + 1
            last_decided_at = max(last_decided_at, str(task.get("llm_pre_worker_decided_at") or ""))
        elif task.get("worker_ready") is True and task.get("dispatcher_decision") == "worker_ready":
            pending += 1
        task_attempts = safe_int(task.get("llm_pre_worker_attempts"))
        attempts += task_attempts
        if task_attempts:
            attempted_tasks += 1
    routed = safe_int(decisions.get("dispatcher_repair"))
    fallback_quality = safe_int(decisions.get("codex_fallback_quality_gate"))
    fallback_backend = safe_int(decisions.get("codex_fallback_backend"))
    fallback_no_blocker = safe_int(decisions.get("codex_fallback_no_blocker"))
    return {
        "eligible": eligible,
        "pending": pending,
        "attempted_tasks": attempted_tasks,
        "attempts": attempts,
        "routed_dispatcher_repair": routed,
        "external_worker_launches_avoided": routed,
        "fallback_total": fallback_quality + fallback_backend + fallback_no_blocker,
        "fallback_quality_gate": fallback_quality,
        "fallback_backend": fallback_backend,
        "fallback_no_blocker": fallback_no_blocker,
        "last_decided_at": last_decided_at or None,
    }


def merge_local_llm_pre_worker_stats(items: list[dict[str, Any]]) -> dict[str, Any]:
    result = local_llm_pre_worker_stats([])
    last_decided_at = ""
    for item in items:
        for key in (
            "eligible", "pending", "attempted_tasks", "attempts",
            "routed_dispatcher_repair", "external_worker_launches_avoided",
            "fallback_total", "fallback_quality_gate", "fallback_backend",
            "fallback_no_blocker",
        ):
            result[key] = safe_int(result.get(key)) + safe_int(item.get(key))
        last_decided_at = max(last_decided_at, str(item.get("last_decided_at") or ""))
    result["last_decided_at"] = last_decided_at or None
    return result


def extract_task_id_from_branch(branch: str) -> str:
    match = re.search(r"([A-Z][A-Z0-9]+-\d+(?:\.\d+)?)", branch)
    return match.group(1) if match else ""


def scan_project_worktrees(project: dict[str, Any]) -> list[dict[str, Any]]:
    project_root = effective_project_root(project)
    if not project_root.exists():
        return []
    try:
        raw = subprocess.check_output(
            ["git", "worktree", "list", "--porcelain"],
            cwd=project_root,
            stderr=subprocess.DEVNULL,
            timeout=10,
        ).decode("utf-8", "replace")
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError):
        return []

    rows: list[dict[str, Any]] = []
    current: dict[str, Any] = {}
    for line in raw.splitlines() + [""]:
        if not line:
            if current:
                path = Path(str(current.get("path", "")))
                branch = str(current.get("branch", ""))
                is_prunable = bool(current.get("prunable"))
                is_worker = (
                    "auto-worker" in branch
                    or re.search(r"/5(?:-[35])?(?:-max)?/", branch) is not None
                )
                if path.exists() and branch and not is_prunable and is_worker:
                    rows.append({
                        "path": str(path),
                        "branch": branch.replace("refs/heads/", ""),
                        "head": str(current.get("head", "")),
                        "task_id": extract_task_id_from_branch(branch),
                    })
            current = {}
            continue
        key, _, value = line.partition(" ")
        if key == "worktree":
            current["path"] = value.strip()
        elif key == "HEAD":
            current["head"] = value.strip()
        elif key == "branch":
            current["branch"] = value.strip()
        elif key == "prunable":
            current["prunable"] = True
    return rows


def scan_runs(runtime_root: Path) -> list[dict[str, Any]]:
    runs_root = runtime_root.expanduser() / "runs"
    if not runs_root.exists():
        return []
    runs: list[dict[str, Any]] = []
    for run_json in sorted(runs_root.glob("*/*/run.json")):
        data, error = load_json(run_json)
        if error or not isinstance(data, dict):
            continue
        data = dict(data)
        data["_run_dir"] = str(run_json.parent)
        runs.append(data)
    runs.sort(key=lambda item: parse_time(item.get("ended_at")) or parse_time(item.get("started_at")), reverse=True)
    return runs


def scan_human_needed(runtime_root: Path) -> list[dict[str, Any]]:
    human_root = runtime_root.expanduser() / "human-needed"
    if not human_root.exists():
        return []
    packets: list[dict[str, Any]] = []
    for packet_json in sorted(human_root.glob("*/*.json")):
        data, error = load_json(packet_json)
        if error or not isinstance(data, dict):
            continue
        data = dict(data)
        data["_packet_path"] = str(packet_json)
        packets.append(data)
    packets.sort(key=lambda item: parse_time(item.get("created_at")), reverse=True)
    return packets


def scan_codex_limits(runtime_root: Path) -> list[dict[str, Any]]:
    data, error = load_json(runtime_root.expanduser() / "codex-limits" / "latest.json")
    if error or not isinstance(data, dict):
        return []
    limits = data.get("limits", [])
    if not isinstance(limits, list):
        return []
    rows = [item for item in limits if isinstance(item, dict)]
    rows.sort(key=lambda item: parse_time(item.get("observed_at")), reverse=True)
    return rows


def scan_codex_limit_estimates(runtime_root: Path) -> list[dict[str, Any]]:
    data, error = load_json(runtime_root.expanduser() / "codex-limits" / "estimate.json")
    if error or not isinstance(data, dict):
        return []
    estimates = data.get("estimates", [])
    if not isinstance(estimates, list):
        return []
    return [item for item in estimates if isinstance(item, dict)]


def limit_percent(value: Any) -> int | None:
    if value is None:
        return None
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return max(0, min(100, int(round(parsed))))


def latest_per_source_limit(
    items: list[dict[str, Any]],
    scope: str,
    window: str,
    model: str | None,
    max_age_minutes: int | None,
) -> list[dict[str, Any]]:

    def is_manual_source(source: str) -> bool:
        source_text = str(source or "").lower()
        return (
            "manual" in source_text
            or "operator" in source_text
            or "hand" in source_text
        )

    by_source: dict[str, dict[str, Any]] = {}
    expected_model_key = codex_model_key(model) if model is not None else None
    for item in items:
        if not isinstance(item, dict):
            continue
        if str(item.get("scope") or "unknown") != scope:
            continue
        if str(item.get("window") or "unknown") != window:
            continue
        if expected_model_key is not None:
            if codex_model_key(item.get("model")) != expected_model_key:
                continue
        elif str(item.get("model") or "") != (model or ""):
            continue
        if is_limit_row_stale(item.get("observed_at"), max_age_minutes):
            continue
        source = str(item.get("source") or "unknown")
        current = by_source.get(source)
        if current is None:
            by_source[source] = item
            continue
        current_at = parse_datetime(current.get("observed_at"))
        item_at = parse_datetime(item.get("observed_at"))
        if item_at is not None and (current_at is None or item_at > current_at):
            by_source[source] = item
    rows: list[dict[str, Any]] = []
    for source, item in by_source.items():
        percent = limit_percent(item.get("remaining_percent"))
        if percent is None:
            continue
        rows.append(
            {
                "source": source,
                "worker_id": str(item.get("worker_id") or ""),
                "remaining_percent": percent,
                "observed_at": str(item.get("observed_at") or ""),
                "reset_at": str(item.get("reset_at") or ""),
            }
        )
    rows.sort(key=lambda row: str(row.get("source")))
    return rows


def is_manual_limit_source(source: str) -> bool:
    source_text = str(source or "").lower()
    return (
        "manual" in source_text
        or "operator" in source_text
        or "hand" in source_text
    )


def limit_source_preference(row: dict[str, Any]) -> int:
    source = str(row.get("source", "")).lower()
    if "app_server" in source:
        return 5
    if is_manual_limit_source(source):
        return 4
    if "cli_status_pty" in source:
        return 3
    if "session" in source or "desktop" in source:
        return 2
    return 1


def pick_limit_consensus_row(rows: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not rows:
        return None

    best_tier = max(limit_source_preference(row) for row in rows)
    candidates = [row for row in rows if limit_source_preference(row) == best_tier]

    def observed_key(row: dict[str, Any]) -> float:
        parsed = parse_datetime(str(row.get("observed_at") or ""))
        if parsed is None:
            return 0.0
        return parsed.timestamp()

    return max(candidates, key=observed_key)


def summarize_limit_by_sources(
    scope: str,
    window: str,
    model: str | None,
    label: str,
    items: list[dict[str, Any]],
    max_age_minutes: int | None,
    display_model: str | None = None,
) -> dict[str, Any]:
    rows = latest_per_source_limit(items, scope, window, model, max_age_minutes)
    best_tier = max((limit_source_preference(row) for row in rows), default=0)
    if best_tier:
        rows = [row for row in rows if limit_source_preference(row) == best_tier]
    values = [row.get("remaining_percent") for row in rows if isinstance(row.get("remaining_percent"), int)]
    if not values:
        return {
            "label": label,
            "scope": scope,
            "window": window,
            "model": display_model if display_model is not None else model,
            "found": False,
            "source_count": 0,
            "source_rows": [],
            "spread": None,
            "agreement": 0.0,
            "status": "missing",
            "consensus_percent": None,
        }
    values_sorted = sorted(values)
    min_value = min(values_sorted)
    max_value = max(values_sorted)
    spread = max_value - min_value
    if len(values) == 1:
        status = "single"
        agreement = 1.0
    elif spread <= 2:
        status = "aligned"
        agreement = 1.0
    elif spread <= 10:
        status = "partial"
        agreement = 0.66
    else:
        status = "diverged"
        agreement = 0.0
    consensus_row = pick_limit_consensus_row(rows)
    consensus_percent = consensus_row.get("remaining_percent") if consensus_row else None

    return {
        "label": label,
        "scope": scope,
        "window": window,
        "model": display_model if display_model is not None else model,
        "found": True,
        "source_count": len(rows),
        "source_rows": rows,
        "spread": spread,
        "agreement": round(agreement, 2),
        "status": status,
        "consensus_percent": consensus_percent,
        "consensus_source": str(consensus_row.get("source") if consensus_row else ""),
    }


def build_codex_limit_consensus(
    items: list[dict[str, Any]],
    max_age_minutes: int | None,
) -> list[dict[str, Any]]:
    specs = [
        ("Общий 5ч", "global", "5h", None, None),
        ("Общий неделя", "global", "weekly", None, None),
        ("Spark 5ч", "model", "5h", "GPT-5.3-Codex-Spark", None),
        ("Spark неделя", "model", "weekly", "GPT-5.3-Codex-Spark", None),
    ]
    return [
        summarize_limit_by_sources(scope, window, model, label, items, max_age_minutes, display_model)
        for label, scope, window, model, display_model in specs
    ]


def scan_automation_status(runtime_root: Path) -> dict[str, Any]:
    data, error = load_json(runtime_root.expanduser() / "automation-status" / "latest.json")
    if error or not isinstance(data, dict):
        return {"timers": []}
    timers = data.get("timers", [])
    if not isinstance(timers, list):
        timers = []
    data = dict(data)
    data["timers"] = [item for item in timers if isinstance(item, dict)]
    return data


def refresh_automation_status(runtime_root: Path) -> dict[str, Any]:
    try:
        data = collect_remote_automation_status.collect(["user", "system"])
    except Exception:
        return scan_automation_status(runtime_root)
    status_path = runtime_root.expanduser() / "automation-status" / "latest.json"
    try:
        status_path.parent.mkdir(parents=True, exist_ok=True)
        status_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except OSError:
        pass
    return data if isinstance(data, dict) else {"timers": []}


def resource_bar_class(value: Any, unit: str | None = None) -> str:
    if unit == "°C":
        temp = safe_float(value)
        if temp is None:
            return "resource-unknown"
        if temp < 60:
            return "resource-low"
        if temp < 75:
            return "resource-medium"
        if temp < 90:
            return "resource-high"
        return "resource-critical"

    normalized = normalize_percent(value)
    if normalized is None:
        return "resource-unknown"
    if normalized < 60:
        return "resource-low"
    if normalized < 80:
        return "resource-medium"
    if normalized < 92:
        return "resource-high"
    return "resource-critical"


def format_metric_value(value: Any, unit: str | None = None) -> str:
    if unit == "°C":
        temperature = safe_float(value)
        if temperature is None:
            return "N/A"
        return f"{temperature:.1f}°C"
    normalized = normalize_percent(value)
    if normalized is None:
        return "N/A"
    return f"{normalized:.1f}%"


def metric_bar_width(value: Any, unit: str | None = None) -> float:
    if unit == "°C":
        current = safe_float(value)
        if current is None:
            return 0.0
        return min(max(current, 0.0), 100.0)
    return normalize_percent(value) or 0.0


def resource_bar(label: str, metric: dict[str, Any] | None) -> str:
    if not isinstance(metric, dict):
        metric = {}
    current = metric.get("current")
    average = metric.get("average")
    unit = str(metric.get("unit", "%"))
    bar_class = resource_bar_class(current, unit)
    value_text = format_metric_value(current, unit)
    if metric and average is not None and unit != "°C":
        value_text = f"{value_text} (ср. {format_metric_value(average, unit)})"
    width = f"{metric_bar_width(current, unit):.1f}%"
    panel_class = html.escape(bar_class)
    unit_label = "загрузка" if unit == "%" else unit
    unit = f'<div class="source-line">{html.escape(unit_label)}</div>'
    text = f'<b>{html.escape(label)}: {html.escape(value_text)}</b>'
    track = (
        f'<div class="track">'
        f'<div class="fill" style="width: {html.escape(str(width))}"></div>'
        "</div>"
    )
    return (
        f'<div class="load-bar limit-bar {panel_class}">'
        f"{text}"
        f"{track}"
        f"{unit}"
        "</div>"
    )


def resource_load_panel(resource_load: dict[str, Any]) -> str:
    if not isinstance(resource_load, dict):
        resource_load = {}
    gpu_details = resource_load.get("gpu_details") if isinstance(resource_load.get("gpu_details"), dict) else {}
    gpu_state_labels = {
        "compute": "GPU считает",
        "model_loaded_idle": "модель загружена в VRAM, compute сейчас idle",
        "gpu_process_idle": "есть GPU-процесс, compute сейчас idle",
        "idle_fan_stop": "простой, вентилятор остановлен",
        "idle": "idle",
        "unavailable": "недоступно",
    }
    gpu_state = gpu_state_labels.get(str(gpu_details.get("state") or ""), str(gpu_details.get("state") or "unknown"))
    used_mb = safe_float(gpu_details.get("used_mb"))
    total_mb = safe_float(gpu_details.get("total_mb"))
    power_w = safe_float(gpu_details.get("power_w"))
    power_limit_w = safe_float(gpu_details.get("power_limit_w"))
    fan_percent = safe_float(gpu_details.get("fan_percent"))
    graphics_clock_mhz = safe_float(gpu_details.get("graphics_clock_mhz"))
    memory_clock_mhz = safe_float(gpu_details.get("memory_clock_mhz"))
    gpu_detail_parts = [f"состояние: {gpu_state}"]
    if gpu_details.get("pstate"):
        gpu_detail_parts.append(f"P-state: {gpu_details.get('pstate')}")
    if used_mb is not None and total_mb is not None:
        gpu_detail_parts.append(f"VRAM: {used_mb:.0f}/{total_mb:.0f} MB")
    if power_w is not None:
        power_text = f"power: {power_w:.1f} W"
        if power_limit_w is not None:
            power_text += f" / {power_limit_w:.0f} W"
        gpu_detail_parts.append(power_text)
    if fan_percent is not None:
        gpu_detail_parts.append(f"fan: {fan_percent:.0f}%")
    if graphics_clock_mhz is not None or memory_clock_mhz is not None:
        clock_parts = []
        if graphics_clock_mhz is not None:
            clock_parts.append(f"core {graphics_clock_mhz:.0f} MHz")
        if memory_clock_mhz is not None:
            clock_parts.append(f"mem {memory_clock_mhz:.0f} MHz")
        gpu_detail_parts.append("clocks: " + ", ".join(clock_parts))
    processes = gpu_details.get("processes") if isinstance(gpu_details.get("processes"), list) else []
    process_names = []
    for process in processes[:3]:
        if not isinstance(process, dict):
            continue
        name = str(process.get("name") or process.get("pid") or "").strip()
        used = safe_float(process.get("used_mb"))
        if not name:
            continue
        if used is not None:
            name = f"{name} ({used:.0f} MB)"
        process_names.append(name)
    if process_names:
        gpu_detail_parts.append("процессы: " + ", ".join(process_names))
    gpu_details_html = f'<div class="muted">GPU: {html.escape(" | ".join(gpu_detail_parts))}</div>'
    observed_at = short_datetime(resource_load.get("observed_at")) or short_datetime(resource_load.get("updated_at"))
    source = str(resource_load.get("source") or "").strip()
    observed_parts = []
    if observed_at:
        observed_parts.append(f"замер: {observed_at}")
    if source:
        observed_parts.append(f"источник: {source}")
    observed_html = f'<div class="muted">{html.escape(" | ".join(observed_parts))}</div>' if observed_parts else ""
    items = [
        ("CPU", resource_load.get("cpu")),
        ("GPU", resource_load.get("gpu")),
        ("VRAM", resource_load.get("vram")),
        ("RAM", resource_load.get("ram")),
        ("CPU temp", resource_load.get("cpu_temp")),
        ("GPU temp", resource_load.get("gpu_temp")),
    ]
    bars = [resource_bar(label, value) for label, value in items]
    return (
        '<section class="summary-panel">'
        "<h2>Нагрузка системы</h2>"
        f'<div class="load-bars">{"".join(bars)}</div>'
        f"{gpu_details_html}"
        f"{observed_html}"
        "</section>"
    )


def resource_status_label(value: Any, unit: str = "%") -> str:
    if unit == "°C":
        temperature = safe_float(value)
        if temperature is None:
            return "Нет данных"
        if temperature < 60:
            return "Норма"
        if temperature < 75:
            return "Тепло"
        if temperature < 90:
            return "Высокая"
        return "Критическая"

    normalized = normalize_percent(value)
    if normalized is None:
        return "Нет данных"
    if normalized < 60:
        return "Норма"
    if normalized < 80:
        return "Повышенная"
    if normalized < 92:
        return "Высокая"
    return "Критическая"


def resource_statistics_card(label: str, metric: dict[str, Any] | None) -> str:
    if not isinstance(metric, dict):
        metric = {}
    current = metric.get("current")
    unit = str(metric.get("unit") or "%")
    status_class = resource_bar_class(current, unit)
    status_label = resource_status_label(current, unit)
    current_text = format_metric_value(current, unit)
    width = metric_bar_width(current, unit)
    aria_value = safe_float(current)
    aria_now = f' aria-valuenow="{aria_value:.1f}"' if aria_value is not None else ""
    return (
        f'<article class="statistics-resource-card {html.escape(status_class)}">'
        '<div class="statistics-resource-head">'
        f'<span class="statistics-resource-label">{html.escape(label)}</span>'
        f'<span class="statistics-status">{html.escape(status_label)}</span>'
        "</div>"
        f'<strong class="statistics-resource-value">{html.escape(current_text)}</strong>'
        '<div class="statistics-resource-average">Текущее значение</div>'
        f'<div class="statistics-track" role="progressbar" aria-label="{html.escape(label)}" '
        f'aria-valuemin="0" aria-valuemax="100"{aria_now}>'
        f'<span style="width:{width:.1f}%"></span>'
        "</div>"
        "</article>"
    )


def format_storage_bytes(value: Any) -> str:
    size = safe_float(value)
    if size is None or size < 0:
        return "Нет данных"
    units = ("Б", "КБ", "МБ", "ГБ", "ТБ")
    index = 0
    while size >= 1024 and index < len(units) - 1:
        size /= 1024
        index += 1
    precision = 0 if index == 0 else 1
    return f"{size:.{precision}f} {units[index]}"


def format_storage_rate(value: Any) -> str:
    rate = safe_float(value)
    if rate is None:
        return "Нет данных"
    return f"{format_storage_bytes(rate)}/с"


def storage_statistics_panel(storage: dict[str, Any] | None) -> str:
    storage = storage if isinstance(storage, dict) else {}
    devices = storage.get("devices") if isinstance(storage.get("devices"), list) else []
    media_labels = {
        "nvme": "NVMe",
        "ssd": "SSD",
        "hdd": "HDD",
        "unknown": "Накопитель",
    }
    media_counts: dict[str, int] = {}
    cards: list[str] = []
    for raw_device in devices:
        if not isinstance(raw_device, dict):
            continue
        media_type = str(raw_device.get("media_type") or "unknown").lower()
        media_label = media_labels.get(media_type, media_labels["unknown"])
        media_counts[media_type] = media_counts.get(media_type, 0) + 1
        model = str(raw_device.get("model") or "").strip()
        device_label = f"{media_label} {media_counts[media_type]}"
        used_percent = normalize_percent(raw_device.get("used_percent"))
        status_class = resource_bar_class(used_percent, "%")
        status_label = resource_status_label(used_percent, "%")
        percent_text = f"{used_percent:.1f}%" if used_percent is not None else "Нет данных"
        total_text = format_storage_bytes(raw_device.get("total_bytes") or raw_device.get("size_bytes"))
        used_text = format_storage_bytes(raw_device.get("used_bytes"))
        free_text = format_storage_bytes(raw_device.get("free_bytes"))
        read_text = format_storage_rate(raw_device.get("read_bytes_per_second"))
        write_text = format_storage_rate(raw_device.get("write_bytes_per_second"))
        io_util = normalize_percent(raw_device.get("io_util_percent"))
        io_text = f"{io_util:.1f}%" if io_util is not None else "Нет данных"
        filesystems = raw_device.get("filesystems") if isinstance(raw_device.get("filesystems"), list) else []
        mount_parts: list[str] = []
        for filesystem in filesystems[:4]:
            if not isinstance(filesystem, dict):
                continue
            mountpoint = str(filesystem.get("mountpoint") or "").strip()
            filesystem_type = str(filesystem.get("filesystem") or "").strip()
            if mountpoint:
                mount_parts.append(f"{mountpoint} · {filesystem_type}" if filesystem_type else mountpoint)
        mounts_text = ", ".join(mount_parts) or "Точка монтирования не определена"
        model_html = (
            f'<span class="statistics-storage-model">{html.escape(model)}</span>'
            if model
            else ""
        )
        width = used_percent or 0.0
        aria_now = f' aria-valuenow="{used_percent:.1f}"' if used_percent is not None else ""
        cards.append(
            f'<article class="statistics-storage-card {html.escape(status_class)}">'
            '<div class="statistics-resource-head">'
            f'<div><span class="statistics-resource-label">{html.escape(device_label)}</span>{model_html}</div>'
            f'<span class="statistics-status">{html.escape(status_label)}</span>'
            "</div>"
            '<div class="statistics-storage-capacity">'
            f'<strong>{html.escape(percent_text)}</strong>'
            f'<span>{html.escape(used_text)} из {html.escape(total_text)}</span>'
            "</div>"
            '<div class="statistics-track" role="progressbar" aria-label="Заполнение '
            f'{html.escape(device_label)}" aria-valuemin="0" aria-valuemax="100"'
            f'{aria_now}><span style="width:{width:.1f}%"></span></div>'
            '<div class="statistics-storage-io">'
            f'<div><span>Чтение</span><strong>{html.escape(read_text)}</strong></div>'
            f'<div><span>Запись</span><strong>{html.escape(write_text)}</strong></div>'
            f'<div><span>I/O</span><strong>{html.escape(io_text)}</strong></div>'
            f'<div><span>Свободно</span><strong>{html.escape(free_text)}</strong></div>'
            "</div>"
            f'<div class="statistics-storage-mounts">{html.escape(mounts_text)}</div>'
            "</article>"
        )

    observed_at = short_datetime(storage.get("observed_at"))
    source = str(storage.get("source") or "").strip()
    source_parts = []
    if observed_at:
        source_parts.append(f"Замер {observed_at}")
    if source:
        source_parts.append(f"Источник: {source}")
    source_text = " · ".join(source_parts)
    if not cards:
        return (
            '<div class="statistics-storage-section">'
            '<div class="statistics-subsection-head"><div><h3>Накопители</h3>'
            '<p>Заполнение и текущая I/O-нагрузка физических дисков.</p></div>'
            '<span class="statistics-development-label">Нет телеметрии</span></div>'
            '<div class="statistics-development"><p>'
            "Сборщик ещё не передал данные HDD, SSD или NVMe. Нулевые значения не подставляются."
            "</p></div></div>"
        )
    return (
        '<div class="statistics-storage-section">'
        '<div class="statistics-subsection-head"><div><h3>Накопители</h3>'
        '<p>Заполнение и текущая I/O-нагрузка физических дисков.</p></div>'
        f'<span>{len(cards)} шт.</span></div>'
        f'<div class="statistics-storage-grid">{"".join(cards)}</div>'
        f'<div class="statistics-storage-source">{html.escape(source_text)}</div>'
        "</div>"
    )


def remote_pc_load_statistics_panel(
    resource_load: dict[str, Any] | None,
    resource_activity: dict[str, Any] | None = None,
) -> str:
    resource_load = resource_load if isinstance(resource_load, dict) else {}
    resource_activity = resource_activity if isinstance(resource_activity, dict) else {}
    primary_metrics = [
        ("CPU", resource_load.get("cpu")),
        ("GPU", resource_load.get("gpu")),
        ("VRAM", resource_load.get("vram")),
        ("RAM", resource_load.get("ram")),
    ]
    has_data = any(
        isinstance(metric, dict) and safe_float(metric.get("current")) is not None
        for _, metric in primary_metrics
    )
    activity_active = bool(resource_activity.get("is_active"))
    if not has_data:
        host_state = "Нет телеметрии"
        host_state_class = "statistics-state-missing"
        host_state_note = "Сборщик ещё не передал измерения."
    elif activity_active:
        host_state = "Есть активность"
        host_state_class = "statistics-state-active"
        host_state_note = "На удалённом ПК обнаружена текущая рабочая активность."
    else:
        host_state = "Ожидание"
        host_state_class = "statistics-state-idle"
        host_state_note = "Телеметрия доступна, активная работа сейчас не обнаружена."

    observed_at = short_datetime(resource_load.get("observed_at")) or short_datetime(resource_load.get("updated_at"))
    source = str(resource_load.get("source") or "").strip()
    freshness_parts = []
    if observed_at:
        freshness_parts.append(f"Замер {observed_at}")
    if source:
        freshness_parts.append(f"Источник: {source}")
    freshness_text = " · ".join(freshness_parts) or "Время и источник замера пока неизвестны"

    gpu_details = resource_load.get("gpu_details") if isinstance(resource_load.get("gpu_details"), dict) else {}
    used_mb = safe_float(gpu_details.get("used_mb"))
    total_mb = safe_float(gpu_details.get("total_mb"))
    power_w = safe_float(gpu_details.get("power_w"))
    power_limit_w = safe_float(gpu_details.get("power_limit_w"))
    fan_percent = safe_float(gpu_details.get("fan_percent"))
    pstate = str(gpu_details.get("pstate") or "—")
    gpu_state_labels = {
        "compute": "Вычисления",
        "model_loaded_idle": "Модель в памяти",
        "gpu_process_idle": "Процесс ожидает",
        "idle_fan_stop": "Простой",
        "idle": "Простой",
        "unavailable": "Недоступно",
    }
    gpu_state = gpu_state_labels.get(
        str(gpu_details.get("state") or ""),
        str(gpu_details.get("state") or "Нет данных"),
    )
    vram_text = "N/A"
    if used_mb is not None and total_mb is not None:
        vram_text = f"{used_mb / 1024:.1f} / {total_mb / 1024:.1f} GB"
    power_text = "N/A"
    if power_w is not None:
        power_text = f"{power_w:.1f} W"
        if power_limit_w is not None:
            power_text += f" из {power_limit_w:.0f} W"
    fan_text = f"{fan_percent:.0f}%" if fan_percent is not None else "N/A"

    processes = gpu_details.get("processes") if isinstance(gpu_details.get("processes"), list) else []
    process_names = []
    for process in processes[:3]:
        if not isinstance(process, dict):
            continue
        name = str(process.get("name") or process.get("pid") or "").strip()
        if name:
            process_names.append(name)
    processes_text = ", ".join(process_names) or "Активные GPU-процессы не найдены"

    metric_cards = "".join(resource_statistics_card(label, metric) for label, metric in primary_metrics)
    thermal_cards = "".join(
        resource_statistics_card(label, resource_load.get(key))
        for label, key in (("Температура CPU", "cpu_temp"), ("Температура GPU", "gpu_temp"))
    )
    storage_panel = storage_statistics_panel(
        resource_load.get("storage") if isinstance(resource_load.get("storage"), dict) else {}
    )
    return (
        '<section class="statistics-host-panel" aria-labelledby="remote-pc-load-title">'
        '<div class="statistics-section-head">'
        "<div>"
        '<div class="statistics-eyebrow">Удалённый ПК</div>'
        '<h2 id="remote-pc-load-title">Нагрузка и состояние ресурсов</h2>'
        '<p>Текущая загрузка без сохранения истории.</p>'
        "</div>"
        '<div class="statistics-host-state">'
        f'<span class="{html.escape(host_state_class)}">{html.escape(host_state)}</span>'
        f'<small>{html.escape(host_state_note)}</small>'
        "</div>"
        "</div>"
        f'<div class="statistics-resource-grid">{metric_cards}</div>'
        '<div class="statistics-resource-details">'
        f'<div class="statistics-thermal-grid">{thermal_cards}</div>'
        '<article class="statistics-gpu-card">'
        '<div class="statistics-resource-head"><span class="statistics-resource-label">GPU подробнее</span>'
        f'<span class="statistics-status">{html.escape(gpu_state)}</span></div>'
        '<div class="statistics-detail-grid">'
        f'<div><span>VRAM</span><strong>{html.escape(vram_text)}</strong></div>'
        f'<div><span>Питание</span><strong>{html.escape(power_text)}</strong></div>'
        f'<div><span>Вентилятор</span><strong>{html.escape(fan_text)}</strong></div>'
        f'<div><span>P-state</span><strong>{html.escape(pstate)}</strong></div>'
        "</div>"
        f'<p class="statistics-processes">{html.escape(processes_text)}</p>'
        "</article>"
        "</div>"
        f"{storage_panel}"
        f'<div class="statistics-freshness">{html.escape(freshness_text)}</div>'
        "</section>"
    )


def task_blocked_statistics(plan: dict[str, Any] | None) -> dict[str, int]:
    data = plan if isinstance(plan, dict) else {}
    items = [item for item in data.get("items") or [] if isinstance(item, dict)]
    if not items:
        by_action = data.get("by_action") if isinstance(data.get("by_action"), dict) else {}
        environment = safe_int(by_action.get("environment_required")) + safe_int(
            by_action.get("role_actionable_after_infra")
        )
        dependency = safe_int(by_action.get("role_rework"))
        human = safe_int(by_action.get("owner_required")) + safe_int(by_action.get("unknown"))
        return {
            "total": environment + dependency + human,
            "environment": environment,
            "dependency": dependency,
            "human": human,
        }

    environment = 0
    dependency = 0
    for item in items:
        action = normalize_status(item.get("action"))
        if action in {"environment_required", "role_actionable_after_infra"}:
            environment += 1
            continue
        dependency_fields = (
            item.get("status"),
            item.get("dispatcher_decision"),
            item.get("integration_status"),
        )
        if any("depend" in normalize_status(value) for value in dependency_fields):
            dependency += 1
    total = max(safe_int(data.get("total")), len(items))
    human = max(0, total - environment - dependency)
    return {
        "total": total,
        "environment": environment,
        "dependency": dependency,
        "human": human,
    }


def task_statistics_panel(snapshot: dict[str, Any]) -> str:
    summary = snapshot.get("summary") if isinstance(snapshot.get("summary"), dict) else {}
    worker_action = summary.get("worker_run_action") if isinstance(summary.get("worker_run_action"), dict) else {}
    route_counts = worker_action.get("route_counts") if isinstance(worker_action.get("route_counts"), dict) else {}
    blocked = task_blocked_statistics(summary.get("queue_attention_plan"))
    recent = summary.get("task_outcomes_last_24h") if isinstance(summary.get("task_outcomes_last_24h"), dict) else {}
    available = safe_int(worker_action.get("candidate_count"))
    prepared_candidates = safe_int(worker_action.get("prepared_candidate_count", available))
    temporarily_blocked_candidates = safe_int(
        worker_action.get("blocked_candidate_count", max(0, prepared_candidates - available))
    )
    task_counts = summary.get("task_counts") if isinstance(summary.get("task_counts"), dict) else {}
    active_backlog = safe_int(task_counts.get("active_queue_total"))
    awaiting_preparation = safe_int(task_counts.get("task_packet"))
    active = safe_int(summary.get("active_total", (summary.get("task_counts") or {}).get("active")))
    flow = summary.get("task_flow_last_24h") if isinstance(summary.get("task_flow_last_24h"), dict) else {}
    if not flow:
        flow = task_flow_last_24h(summary.get("role_outcomes"), recent)
    work_classes = summary.get("task_work_class_active_breakdown")
    if not isinstance(work_classes, dict):
        work_classes = task_work_class_breakdown(summary.get("task_origin_active_breakdown"))
    work_class_total = safe_int(work_classes.get("total"))
    work_class_reconciled = work_class_total == active_backlog
    reconciliation_label = (
        "Сумма совпадает с общей очередью"
        if work_class_reconciled
        else f"Требуется сверка: категории {work_class_total}, очередь {active_backlog}"
    )
    work_class_cards = "".join(
        (
            '<article class="statistics-backlog-card">'
            f'<span>{html.escape(label)}</span><strong>{safe_int(work_classes.get(key))}</strong>'
            f'<small>{html.escape(note)}</small></article>'
        )
        for key, label, note in (
            ("direct_product_code", "Продукт и код", "Прямые задачи: реализация, QA и продуктовые действия"),
            ("project_map_rules", "Project Map и Rules", "Карта проекта, правила и их автоматическое восстановление"),
            ("backlog_task_docs", "Backlog / Task docs", "Импортированные задачи, ожидающие прохождения pipeline"),
            ("dispatcher_preparation", "Подготовка Dispatcher", "Создание и ремонт Worker Packet"),
            ("integration_delivery", "Интеграция", "Результаты Worker, ожидающие приёмки и завершения"),
            ("roadmap_design", "Roadmap и дизайн", "Плановые и проектные задачи"),
        )
    )
    unclassified = safe_int(route_counts.get("unclassified"))
    unclassified_html = (
        f'<span>Без маршрута <b>{unclassified}</b></span>'
        if unclassified
        else ""
    )
    return (
        '<section class="statistics-host-panel statistics-task-panel" aria-labelledby="task-statistics-title">'
        '<div class="statistics-section-head"><div><div class="statistics-eyebrow">Общая сводка</div>'
        '<h2 id="task-statistics-title">Задачи</h2>'
        '<p>Текущее состояние очереди и результаты за скользящие последние 24 часа.</p>'
        '</div></div>'
        '<div class="statistics-task-grid">'
        '<article class="statistics-task-card task-ready">'
        '<span class="statistics-task-label">Можно взять сейчас</span>'
        f'<strong>{available}</strong>'
        '<div class="statistics-task-breakdown statistics-route-breakdown">'
        f'<span>Local LLM <b>{safe_int(route_counts.get("local_llm"))}</b></span>'
        f'<span>Spark (5.3) <b>{safe_int(route_counts.get("spark_5_3"))}</b></span>'
        f'<span>GPT-5.6 <b>{safe_int(route_counts.get("gpt_5_6"))}</b></span>'
        f'{unclassified_html}'
        '</div>'
        f'<small>из {active_backlog} активных задач в очереди</small>'
        '</article>'
        '<article class="statistics-task-card task-ready">'
        '<span class="statistics-task-label">Подготовлены для Worker</span>'
        f'<strong>{prepared_candidates}</strong>'
        f'<small>временно заблокированы текущим runtime gate: {temporarily_blocked_candidates}</small>'
        '</article>'
        '<article class="statistics-task-card task-preparation">'
        '<span class="statistics-task-label">Ожидают подготовки</span>'
        f'<strong>{awaiting_preparation}</strong>'
        '<small>нужен Task / Worker Packet от Dispatcher</small>'
        '</article>'
        '<article class="statistics-task-card task-blocked">'
        '<span class="statistics-task-label">Заблокировано</span>'
        f'<strong>{safe_int(blocked.get("total"))}</strong>'
        '<div class="statistics-task-breakdown">'
        f'<span>Среда <b>{safe_int(blocked.get("environment"))}</b></span>'
        f'<span>Предыдущая задача <b>{safe_int(blocked.get("dependency"))}</b></span>'
        f'<span>Решение человека <b>{safe_int(blocked.get("human"))}</b></span>'
        '</div></article>'
        '<article class="statistics-task-card task-active">'
        '<span class="statistics-task-label">В работе</span>'
        f'<strong>{active}</strong>'
        '<small>задачи с активным выполнением</small>'
        '</article>'
        '<article class="statistics-task-card task-recent">'
        '<span class="statistics-task-label">Последние 24 часа</span>'
        '<div class="statistics-task-outcomes">'
        f'<div><strong>{safe_int(flow.get("prepared"))}</strong><small>Подготовлено Dispatcher</small></div>'
        f'<div><strong>{safe_int(flow.get("executed"))}</strong><small>Выполнено Worker</small></div>'
        f'<div><strong>{safe_int(flow.get("integrated"))}</strong><small>Интегрировано</small></div>'
        f'<div><strong>{safe_int(flow.get("finalized"))}</strong><small>Финализировано</small></div>'
        '</div>'
        f'<small>Ошибок последних попыток: {safe_int(flow.get("failed"))}. '
        'Этапы считаются отдельно: одна задача может пройти несколько этапов за 24 часа.</small>'
        '</article>'
        '</div>'
        '<div class="statistics-backlog-head">'
        '<div><span class="statistics-task-label">Состав активного backlog</span>'
        '<small>Все категории считаются задачами и участвуют в общей статистике выполнения.</small></div>'
        f'<strong>{active_backlog}</strong>'
        '</div>'
        f'<div class="statistics-backlog-grid">{work_class_cards}</div>'
        '<div class="statistics-backlog-reconcile">'
        f'<span>{html.escape(reconciliation_label)}</span>'
        f'<span>Прямые: <b>{safe_int(work_classes.get("primary_total"))}</b> · '
        f'второстепенный backlog: <b>{safe_int(work_classes.get("secondary_total"))}</b></span>'
        '</div>'
        '<div class="statistics-freshness">Можно взять: Runner readiness · '
        'блокировки: Queue Attention Plan · суточные значения считаются по меткам задач и последним попыткам без сохранения истории дашборда.</div>'
        '</section>'
    )


def development_state(task_id: str, note: str) -> str:
    return (
        '<div class="statistics-development">'
        '<span class="statistics-development-label">В разработке</span>'
        f'<code>{html.escape(task_id)}</code>'
        f'<p>{html.escape(note)}</p>'
        "</div>"
    )


def format_uptime(value: Any) -> str:
    seconds = safe_int(value)
    if seconds <= 0:
        return "N/A"
    days, remainder = divmod(seconds, 86400)
    hours, _ = divmod(remainder, 3600)
    if days:
        return f"{days} д {hours} ч"
    return f"{hours} ч"


def vps_status_meta(value: Any) -> tuple[str, str]:
    status = str(value or "unknown").strip().lower()
    if status in {"healthy", "online", "ok", "active", "ready"}:
        return "Работает", "statistics-state-active"
    if status == "stale":
        return "Данные устарели", "statistics-state-warning"
    if status in {"warning", "degraded", "elevated"}:
        return "Есть замечания", "statistics-state-warning"
    if status in {"down", "offline", "unreachable", "failed", "critical"}:
        return "Недоступен", "statistics-state-critical"
    return "Нет данных", "statistics-state-missing"


def compact_percent_metric(label: str, value: Any) -> str:
    normalized = normalize_percent(value)
    value_text = f"{normalized:.0f}%" if normalized is not None else "N/A"
    width = max(0.0, min(100.0, normalized or 0.0))
    status_class = resource_bar_class(normalized)
    aria_now = f' aria-valuenow="{normalized:.1f}"' if normalized is not None else ""
    return (
        '<div class="statistics-mini-metric">'
        f'<div><span>{html.escape(label)}</span><strong>{html.escape(value_text)}</strong></div>'
        f'<div class="statistics-track {html.escape(status_class)}" role="progressbar" '
        f'aria-label="{html.escape(label)}" aria-valuemin="0" aria-valuemax="100"{aria_now}>'
        f'<span style="width:{width:.1f}%"></span>'
        "</div>"
        "</div>"
    )


def vps_summary_card(server: dict[str, Any]) -> str:
    server_id = str(server.get("id") or server.get("vps_id") or "vps").strip()
    label = str(server.get("label") or server_id or "VPS").strip()
    role = str(server.get("role") or "VPS").strip()
    status_label, status_class = vps_status_meta(server.get("status"))
    safe_anchor = re.sub(r"[^a-z0-9_-]+", "-", server_id.lower()).strip("-") or "vps"
    observed = short_datetime(server.get("observed_at")) or "время неизвестно"
    metrics = "".join(
        compact_percent_metric(metric_label, server.get(field))
        for metric_label, field in (("CPU", "cpu_percent"), ("RAM", "memory_percent"), ("Диск", "disk_percent"))
    )
    return (
        f'<article class="statistics-vps-card" id="vps-summary-{html.escape(safe_anchor)}">'
        '<div class="statistics-resource-head">'
        f'<div><span class="statistics-resource-label">{html.escape(label)}</span><small>{html.escape(role)}</small></div>'
        f'<span class="statistics-vps-status {html.escape(status_class)}">{html.escape(status_label)}</span>'
        "</div>"
        f'<div class="statistics-mini-grid">{metrics}</div>'
        '<div class="statistics-vps-footer">'
        f'<span>Замер {html.escape(observed)}</span>'
        f'<a href="/infrastructure#vps-{html.escape(safe_anchor)}">Открыть детали</a>'
        "</div>"
        "</article>"
    )


def vps_fleet_statistics_panel(vps_fleet: dict[str, Any] | None) -> str:
    vps_fleet = vps_fleet if isinstance(vps_fleet, dict) else {}
    servers = [item for item in vps_fleet.get("servers") or [] if isinstance(item, dict)]
    observed = short_datetime(vps_fleet.get("observed_at"))
    freshness = str(vps_fleet.get("freshness_status") or "unavailable").strip().lower()
    development = ""
    if servers:
        content = f'<div class="statistics-vps-grid">{"".join(vps_summary_card(item) for item in servers)}</div>'
        status_note = f"{len(servers)} VPS · замер {observed or 'время неизвестно'}"
        if freshness != "fresh":
            status_note += " · данные не подтверждены как свежие"
            development = development_state(
                VPS_TELEMETRY_TASK_ID,
                "Карточки показывают последний известный снимок. Автоматизация должна восстановить регулярное обновление перед тем, как блок станет рабочим.",
            )
    else:
        content = development_state(
            VPS_TELEMETRY_TASK_ID,
            "Автоматизация готовит безопасный snapshot нагрузки для каждого VPS.",
        )
        status_note = "Телеметрия VPS ещё не опубликована"
    return (
        '<section class="statistics-host-panel" aria-labelledby="vps-fleet-title">'
        '<div class="statistics-section-head">'
        '<div><div class="statistics-eyebrow">Инфраструктура</div>'
        '<h2 id="vps-fleet-title">Нагрузка VPS</h2>'
        '<p>Коротко по каждому серверу: состояние, CPU, RAM и диск.</p></div>'
        '<div class="statistics-section-action">'
        f'<span>{html.escape(status_note)}</span><a class="button" href="/infrastructure">Подробнее о VPS</a>'
        "</div></div>"
        f"{content}"
        f"{development}"
        "</section>"
    )


def weekly_codex_limits_statistics_panel(consensus: list[dict[str, Any]] | None) -> str:
    grouped_rows: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for item in consensus or []:
        if not isinstance(item, dict):
            continue
        scope = str(item.get("scope") or "global").strip().lower()
        model = str(item.get("model") or "").strip().lower() if scope == "model" else ""
        grouped_rows.setdefault((scope, model), []).append(item)

    current_rows = []
    for rows in grouped_rows.values():
        available = [
            item for item in rows
            if item.get("found") is not False
            and normalize_percent(item.get("consensus_percent")) is not None
            and str(item.get("status") or "") != "missing"
        ]
        if not available:
            continue

        def row_priority(item: dict[str, Any]) -> tuple[int, str]:
            window = str(item.get("window") or "").strip().lower()
            source_rows = [row for row in item.get("source_rows") or [] if isinstance(row, dict)]
            observed = max(
                [str(row.get("observed_at") or "") for row in source_rows]
                + [str(item.get("observed_at") or "")]
            )
            return (1 if window in {"weekly", "week", "7d"} else 0, observed)

        current_rows.append(max(available, key=row_priority))

    current_rows.sort(
        key=lambda item: (
            0 if str(item.get("scope") or "").strip().lower() == "global" else 1,
            str(item.get("model") or item.get("label") or "").lower(),
        )
    )
    cards = []
    for item in current_rows:
        label = re.sub(
            r"\s+(?:5ч|8ч|неделя|weekly)$",
            "",
            str(item.get("label") or item.get("model") or item.get("scope") or "Codex").strip(),
            flags=re.IGNORECASE,
        ).strip()
        remaining = normalize_percent(item.get("consensus_percent"))
        source_rows = [row for row in item.get("source_rows") or [] if isinstance(row, dict)]
        reset_at = next((short_datetime(row.get("reset_at")) for row in source_rows if short_datetime(row.get("reset_at"))), None)
        observed_at = next((short_datetime(row.get("observed_at")) for row in source_rows if short_datetime(row.get("observed_at"))), None)
        remaining_text = f"{remaining:.0f}%"
        status_label = resource_status_label(100.0 - remaining)
        status_class = resource_bar_class(100.0 - remaining)
        width = max(0.0, min(100.0, remaining))
        body = (
            f'<strong class="statistics-limit-value">{html.escape(remaining_text)}</strong>'
            '<span class="statistics-limit-caption">осталось на неделю</span>'
            f'<div class="statistics-track limit-remaining {html.escape(status_class)}" role="progressbar" '
            f'aria-label="Недельный лимит {html.escape(label)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="{remaining:.1f}">'
            f'<span style="width:{width:.1f}%"></span></div>'
            f'<small>Сброс: {html.escape(reset_at or "нет данных")} · замер: {html.escape(observed_at or "нет данных")}</small>'
        )
        cards.append(
            '<article class="statistics-limit-card">'
            '<div class="statistics-resource-head">'
            f'<span class="statistics-resource-label">{html.escape(label or "Codex")}</span>'
            f'<span class="statistics-status">{html.escape(status_label)}</span>'
            "</div>"
            f"{body}"
            "</article>"
        )
    if not cards:
        cards.append(
            '<article class="statistics-limit-card">'
            '<div class="statistics-resource-head"><span class="statistics-resource-label">Недельные лимиты</span>'
            '<span class="statistics-status">Нет свежих данных</span></div>'
            '<span class="statistics-limit-caption">Рабочий источник лимитов временно не вернул значения.</span>'
            "</article>"
        )
    return (
        '<section class="statistics-host-panel" aria-labelledby="codex-weekly-title">'
        '<div class="statistics-section-head"><div><div class="statistics-eyebrow">Codex</div>'
        '<h2 id="codex-weekly-title">Недельные лимиты</h2>'
        '<p>Текущие значения из рабочего источника лимитов, уже используемого в Old version.</p>'
        '</div></div>'
        f'<div class="statistics-limit-grid">{"".join(cards)}</div>'
        "</section>"
    )


def infrastructure_entity_rows(items: Any, empty_text: str) -> str:
    rows = []
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict):
            continue
        label = str(item.get("label") or item.get("name") or item.get("id") or "Элемент")
        kind = str(item.get("role") or item.get("kind") or item.get("service") or "—")
        status_label, status_class = vps_status_meta(item.get("status"))
        observed = short_datetime(item.get("observed_at") or item.get("checked_at")) or "—"
        rows.append(
            "<tr>"
            f'<td><strong>{html.escape(label)}</strong></td>'
            f'<td>{html.escape(kind)}</td>'
            f'<td><span class="statistics-table-status {html.escape(status_class)}">{html.escape(status_label)}</span></td>'
            f'<td>{html.escape(observed)}</td>'
            "</tr>"
        )
    return "".join(rows) or f'<tr><td colspan="4">{html.escape(empty_text)}</td></tr>'


def disk_pressure_detail(server: dict[str, Any]) -> str:
    diagnostic = server.get("disk_pressure") if isinstance(server.get("disk_pressure"), dict) else {}
    if not diagnostic.get("active"):
        return ""
    state = str(diagnostic.get("state") or "elevated")
    report_status = str(diagnostic.get("report_status") or "unknown")
    capacity_state = str(diagnostic.get("capacity_state") or "unknown")
    inode_state = str(diagnostic.get("inode_state") or "unknown")
    estimated = diagnostic.get("estimated_recoverable_bytes")
    estimate_text = str(estimated) if isinstance(estimated, int) else "не подтверждён"
    task_id = str(diagnostic.get("task_id") or CONTROL_DISK_PRESSURE_TASK_ID)
    return (
        '<div class="statistics-development" id="control-disk-pressure-task">'
        '<span class="statistics-development-label">Диагностика давления на диске</span>'
        f'<a href="/infrastructure#control-disk-pressure-task"><code>{html.escape(task_id)}</code></a>'
        f'<p>Состояние: {html.escape(state)} · capacity: {html.escape(capacity_state)} · inodes: {html.escape(inode_state)}. '
        f'Последний отчёт: {html.escape(report_status)}; оценка освобождения байт: {html.escape(estimate_text)}. '
        'Любое восстановление требует отдельного reviewed manifest и одобрения владельца.</p>'
        '</div>'
    )


def render_infrastructure_index(snapshot: dict[str, Any]) -> str:
    vps_fleet = snapshot.get("vps_fleet") if isinstance(snapshot.get("vps_fleet"), dict) else {}
    servers = [item for item in vps_fleet.get("servers") or [] if isinstance(item, dict)]
    freshness = str(vps_fleet.get("freshness_status") or "unavailable").strip().lower()
    generated_at = short_datetime(snapshot.get("generated_at")) or "Нет данных"
    sections = []
    for server in servers:
        server_id = str(server.get("id") or server.get("vps_id") or "vps")
        label = str(server.get("label") or server_id or "VPS")
        safe_anchor = re.sub(r"[^a-z0-9_-]+", "-", server_id.lower()).strip("-") or "vps"
        status_label, status_class = vps_status_meta(server.get("status"))
        load_values = [server.get("load_1m"), server.get("load_5m"), server.get("load_15m")]
        load_text = " / ".join(f"{safe_float(value):.2f}" if safe_float(value) is not None else "N/A" for value in load_values)
        metrics = "".join(
            compact_percent_metric(metric_label, server.get(field))
            for metric_label, field in (("CPU", "cpu_percent"), ("RAM", "memory_percent"), ("Диск", "disk_percent"))
        )
        sections.append(
            f'<section class="statistics-host-panel infrastructure-server" id="vps-{html.escape(safe_anchor)}">'
            '<div class="statistics-section-head"><div>'
            f'<div class="statistics-eyebrow">{html.escape(str(server.get("role") or "VPS"))}</div>'
            f'<h2>{html.escape(label)}</h2>'
            f'<p>Uptime: {html.escape(format_uptime(server.get("uptime_seconds")))} · Load 1/5/15: {html.escape(load_text)}</p>'
            "</div>"
            f'<div class="statistics-host-state"><span class="{html.escape(status_class)}">{html.escape(status_label)}</span>'
            f'<small>Замер {html.escape(short_datetime(server.get("observed_at")) or "неизвестен")}</small></div></div>'
            f'<div class="statistics-mini-grid infrastructure-metrics">{metrics}</div>'
            f'{disk_pressure_detail(server)}'
            '<div class="infrastructure-detail-grid">'
            '<div><h3>Хосты</h3><div class="scroll-panel compact"><table><thead><tr><th>Имя</th><th>Роль</th><th>Статус</th><th>Проверено</th></tr></thead><tbody>'
            f'{infrastructure_entity_rows(server.get("hosts"), "Хосты ещё не опубликованы")}</tbody></table></div></div>'
            '<div><h3>Ноды</h3><div class="scroll-panel compact"><table><thead><tr><th>Имя</th><th>Тип</th><th>Статус</th><th>Проверено</th></tr></thead><tbody>'
            f'{infrastructure_entity_rows(server.get("nodes"), "Ноды ещё не опубликованы")}</tbody></table></div></div>'
            '<div><h3>Сервисы</h3><div class="scroll-panel compact"><table><thead><tr><th>Имя</th><th>Роль</th><th>Статус</th><th>Проверено</th></tr></thead><tbody>'
            f'{infrastructure_entity_rows(server.get("services"), "Сервисы ещё не опубликованы")}</tbody></table></div></div>'
            "</div></section>"
        )
    if not sections:
        sections.append(
            '<section class="statistics-host-panel"><div class="statistics-eyebrow">Телеметрия VPS</div>'
            '<h2>Детальная инфраструктура готова к данным</h2>'
            f'{development_state(VPS_TELEMETRY_TASK_ID, "Автоматизация добавит хосты, ноды, сервисы и нагрузку в безопасный snapshot.")}'
            "</section>"
        )
    freshness_notice = ""
    if freshness != "fresh":
        freshness_notice = (
            '<section class="statistics-host-panel"><div class="statistics-eyebrow">Состояние источника</div>'
            '<h2>Телеметрия пока не считается рабочей</h2>'
            + development_state(
                VPS_TELEMETRY_TASK_ID,
                "Ниже сохранён последний известный снимок. Автоматизация должна восстановить свежий bounded probe и регулярную публикацию.",
            )
            + "</section>"
        )
    body = (
        '<div class="statistics-page">'
        '<section class="statistics-hero"><div><div class="statistics-eyebrow">Инфраструктура</div>'
        '<h1>VPS, хосты и ноды</h1>'
        '<p>Подробный read-only обзор инфраструктуры без адресов, учётных данных и секретов.</p></div>'
        f'<div class="statistics-updated">Снимок<strong>{html.escape(generated_at)}</strong><a href="/">← К сводке</a></div>'
        "</section>"
        f'{freshness_notice}'
        f'{"".join(sections)}'
        "</div>"
    )
    return layout("Инфраструктура AiStudio", snapshot, body)


def load_manual_run_status(runtime_root: Path, project_id: str) -> dict[str, Any] | None:
    if not project_id:
        return None
    candidates = [str(project_id)]
    project_lower = str(project_id).strip().lower()
    project_upper = str(project_id).strip().upper()
    if project_lower not in candidates:
        candidates.append(project_lower)
    if project_upper not in candidates:
        candidates.append(project_upper)

    data = None
    error = "manual run status not found"
    for candidate in candidates:
        path = runtime_root.expanduser() / "manual-runs" / f"{candidate}.json"
        if path.exists():
            data, error = load_json(path)
            break
    if error or not isinstance(data, dict):
        return None
    return data


def manual_run_active(data: dict[str, Any] | None) -> bool:
    if not data:
        return False
    state = str(data.get("state") or "").lower()
    if state not in {"running", "active", "started"}:
        return False
    expires_at = parse_datetime(data.get("expires_at"))
    if isinstance(expires_at, dt.datetime) and expires_at < dt.datetime.now(dt.timezone.utc):
        return False
    return True


def scan_automation_progress(runtime_root: Path) -> list[dict[str, Any]]:
    progress_root = runtime_root.expanduser() / "automation-progress"
    if not progress_root.exists():
        return []
    rows: list[dict[str, Any]] = []
    for path in sorted(progress_root.glob("*.json")):
        data, error = load_json(path)
        if error or not isinstance(data, dict):
            continue
        data = dict(data)
        data["_path"] = str(path)
        rows.append(data)
    return rows


def scan_running_codex_workers() -> list[dict[str, Any]]:
    if os.name == "nt":
        return []
    try:
        result = subprocess.run(
            ["ps", "-eo", "pid,etimes,cmd"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    rows = []
    worker_processes = []
    for line in result.stdout.splitlines()[1:]:
        lowered = line.lower()
        if not command_has_resource_activity_signature(lowered):
            continue
        parts = line.strip().split(maxsplit=2)
        if len(parts) < 3:
            continue
        pid, etimes, cmd = parts
        worker_processes.append({"pid": pid, "elapsed_sec": safe_int(etimes), "cmd": cmd})
    if worker_processes:
        rows.append({
            "schema_version": "1.0",
            "role": "workers",
            "unit": "direct-codex-processes",
            "status": "running",
            "current_step": f"direct_codex:{len(worker_processes)}",
            "updated_at": utc_now(),
            "processes": worker_processes,
            "source": "process_scan",
        })
    return rows


def _parse_progress_updated_at(item: dict[str, Any]) -> dt.datetime | None:
    return parse_datetime(item.get("updated_at"))


def _newer_progress_item(current: dict[str, Any] | None, candidate: dict[str, Any]) -> dict[str, Any]:
    if current is None:
        return candidate
    current_at = _parse_progress_updated_at(current)
    candidate_at = _parse_progress_updated_at(candidate)
    if candidate_at is None:
        return current
    if current_at is None:
        return candidate
    return candidate if candidate_at >= current_at else current


def _index_progress_items(progress_items: list[dict[str, Any]]) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    by_service_unit: dict[str, dict[str, Any]] = {}
    by_role: dict[str, dict[str, Any]] = {}
    for item in progress_items:
        if not isinstance(item, dict):
            continue
        service_unit = str(item.get("service_unit") or "").strip()
        if service_unit:
            by_service_unit[service_unit] = _newer_progress_item(by_service_unit.get(service_unit), item)
        role = str(item.get("role") or "").strip()
        if role:
            by_role[role] = _newer_progress_item(by_role.get(role), item)
    return by_service_unit, by_role


def scan_running_codex_worker_processes() -> list[dict[str, Any]]:
    if os.name == "nt":
        return []
    try:
        result = subprocess.run(
            ["ps", "-eo", "pid,etime,cmd"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    rows = []
    for line in result.stdout.splitlines()[1:]:
        lowered = line.lower()
        if not command_has_resource_activity_signature(lowered):
            continue
        parts = line.strip().split(maxsplit=2)
        if len(parts) < 3:
            continue
        pid, etime, cmd = parts
        rows.append({"pid": pid, "elapsed": etime, "cmd": cmd})
    return rows


def extract_worktree_path_from_command(cmd: str, project_root: str | None = None) -> str:
    candidates = re.findall(r"\\S+\\.worktrees\\/[^\\s\"]+", cmd)
    if candidates:
        return candidates[0]
    candidates = re.findall(r"\\S+/.+?\\.worktrees\\/[^\\s\"]+", cmd)
    if candidates:
        return candidates[0]
    if project_root:
        for token in cmd.split():
            token = token.strip("'\"")
            if token.startswith(project_root):
                return token
    return ""


def running_worktree_paths_for_project(project_root: Path) -> set[str]:
    normalized_root = str(project_root)
    running = set()
    for process in scan_running_codex_worker_processes():
        path = extract_worktree_path_from_command(process.get("cmd", ""), normalized_root)
        if path:
            running.add(path)
    return running


def scan_activity_log(runtime_root: Path, limit: int = 20) -> list[dict[str, Any]]:
    path = runtime_root.expanduser() / "activity-log" / "events.jsonl"
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    for line in lines[-max(limit * 5, limit):]:
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict):
            rows.append(item)
    rows.sort(key=lambda item: str(item.get("created_at") or ""), reverse=True)
    return rows[:limit]


def scan_automation_controller_reports(runtime_root: Path, limit: int = 10) -> dict[str, Any]:
    runtime = runtime_root.expanduser()
    roots = [runtime / "automation-controller"]
    if runtime.name == "dashboard-live":
        roots.append(runtime.parent / "automation-controller")
    existing_roots = [root for root in roots if root.exists()]
    if not existing_roots:
        return {"available": True, "report_count": 0, "latest": None, "recent": []}
    paths_by_name: dict[str, Path] = {}
    for root in existing_roots:
        for path in root.glob("*.json"):
            key = str(path.resolve())
            paths_by_name[key] = path
    paths = sorted(paths_by_name.values(), key=lambda path: path.stat().st_mtime, reverse=True)
    reports: list[dict[str, Any]] = []
    for path in paths:
        data, error = load_json(path)
        if error or not isinstance(data, dict):
            continue
        item = dict(data)
        item["_path"] = str(path)
        item["_mtime"] = path.stat().st_mtime
        reports.append(item)
    reports.sort(
        key=lambda item: (
            str(item.get("updated_at") or item.get("finished_at") or item.get("started_at") or ""),
            safe_float(item.get("_mtime")) or 0.0,
        ),
        reverse=True,
    )
    reports = reports[:limit]
    return {
        "available": True,
        "report_count": len(paths),
        "latest": reports[0] if reports else None,
        "recent": reports,
    }


def automation_controller_summary(controller: dict[str, Any] | None) -> dict[str, Any]:
    data = controller or {}
    latest = data.get("latest") if isinstance(data.get("latest"), dict) else {}
    latest_updated_at = latest.get("updated_at") or latest.get("finished_at") if latest else None
    latest_parsed_at = parse_datetime(latest_updated_at)
    latest_age_seconds = (
        max(0, int((dt.datetime.now(LOCAL_TZ) - latest_parsed_at).total_seconds()))
        if latest_parsed_at is not None
        else None
    )
    latest_stale = (
        latest_age_seconds > AUTOMATION_CONTROLLER_STALE_MINUTES_DEFAULT * 60
        if latest_age_seconds is not None
        else False
    )
    parsed = None
    results = latest.get("results") if isinstance(latest, dict) else None
    if isinstance(results, list) and results:
        first = results[0]
        if isinstance(first, dict) and isinstance(first.get("parsed_json"), dict):
            parsed = first["parsed_json"]
    readiness = parsed.get("credential_readiness") if isinstance(parsed, dict) and isinstance(parsed.get("credential_readiness"), dict) else {}
    registry_update = parsed.get("registry_update") if isinstance(parsed, dict) and isinstance(parsed.get("registry_update"), dict) else {}
    raw_child_failed_count = safe_int(parsed.get("failed_count")) if isinstance(parsed, dict) else 0
    if isinstance(parsed, dict):
        for result in parsed.get("results") or []:
            nested = result.get("parsed_json") if isinstance(result, dict) else None
            if not isinstance(nested, dict):
                continue
            if not raw_child_failed_count:
                raw_child_failed_count = safe_int(nested.get("failed_count"))
            if not readiness and isinstance(nested.get("credential_readiness"), dict):
                readiness = nested["credential_readiness"]
    latest_state = latest.get("state") if latest else None
    latest_error = str(latest.get("error") or "") if latest else ""
    normalized_state = latest_state
    child_blocked_count = 0
    child_failed_count = raw_child_failed_count
    runner_readiness_blockers = latest.get("runner_readiness_blockers") if isinstance(latest.get("runner_readiness_blockers"), list) else []
    runner_blocked_projects = [
        str(item.get("project_id") or "")
        for item in runner_readiness_blockers
        if isinstance(item, dict) and str(item.get("project_id") or "")
    ]
    runner_readiness_reason = ""
    for item in runner_readiness_blockers:
        host = item.get("codex_host_readiness") if isinstance(item, dict) else None
        if isinstance(host, dict) and host.get("reason"):
            runner_readiness_reason = str(host.get("reason") or "")
            break
    if latest_state == "rejected" and latest_error in CONTROLLER_BLOCKING_ERRORS:
        normalized_state = "blocked"
    if latest_state == "blocked" and raw_child_failed_count:
        child_blocked_count = raw_child_failed_count
        child_failed_count = 0
    elif latest_state == "failed" and raw_child_failed_count:
        normalized_state = "blocked"
        child_blocked_count = raw_child_failed_count
        child_failed_count = 0
    credential_reason = readiness.get("reason") if readiness else None
    block_reason = runner_readiness_reason or credential_reason or latest_error or None
    blocked_projects = runner_blocked_projects or (
        readiness.get("credential_blocked_projects")
        if isinstance(readiness.get("credential_blocked_projects"), list)
        else []
    )
    return {
        "available": bool(data.get("available", True)),
        "report_count": safe_int(data.get("report_count")),
        "latest_run_id": latest.get("run_id") if latest else None,
        "latest_mode": latest.get("mode") if latest else None,
        "latest_state": normalized_state,
        "raw_latest_state": latest_state,
        "latest_error": latest_error or None,
        "latest_returncode": latest.get("returncode") if latest else None,
        "latest_updated_at": latest_updated_at,
        "latest_age_seconds": latest_age_seconds,
        "latest_stale": latest_stale,
        "latest_stale_minutes": AUTOMATION_CONTROLLER_STALE_MINUTES_DEFAULT,
        "raw_child_failed_count": raw_child_failed_count,
        "child_failed_count": child_failed_count,
        "child_blocked_count": child_blocked_count,
        "credential_readiness_ok": readiness.get("ok") if readiness else None,
        "credential_readiness_reason": credential_reason,
        "credential_blocked_projects": readiness.get("credential_blocked_projects") if isinstance(readiness.get("credential_blocked_projects"), list) else [],
        "runner_readiness_blocked_projects": runner_blocked_projects,
        "runner_readiness_reason": runner_readiness_reason or None,
        "block_reason": block_reason,
        "blocked_projects": blocked_projects,
        "registry_updated_count": safe_int(registry_update.get("updated_count")) if registry_update else 0,
        "secret_values_reported": bool(readiness.get("secret_values_reported")) if readiness else False,
    }


def automation_summary(project_runs: list[dict[str, Any]], automation_status: dict[str, Any]) -> dict[str, Any]:
    timers = automation_status.get("timers", [])
    if not isinstance(timers, list):
        timers = []
    agent_timers = [timer for timer in timers if timer.get("role") != "readonly"]
    next_timer = next((timer for timer in agent_timers if timer.get("next_at")), None)
    last_timer = max(
        [timer for timer in agent_timers if timer.get("last_at")],
        key=lambda timer: str(timer.get("last_at")),
        default=None,
    )
    next_any_timer = next((timer for timer in timers if timer.get("next_at")), None)
    last_any_timer = max(
        [timer for timer in timers if timer.get("last_at")],
        key=lambda timer: str(timer.get("last_at")),
        default=None,
    )
    latest_run = project_runs[0] if project_runs else None
    return {
        "latest_agent_run_at": latest_run.get("started_at") if latest_run else None,
        "latest_agent_run_id": latest_run.get("run_id") if latest_run else None,
        "latest_agent_status": latest_run.get("status") if latest_run else None,
        "latest_agent_role": latest_run.get("agent_role") if latest_run else None,
        "last_scheduler_at": last_timer.get("last_at") if last_timer else None,
        "last_scheduler_unit": last_timer.get("timer_unit") if last_timer else None,
        "next_scheduled_at": next_timer.get("next_at") if next_timer else None,
        "next_scheduled_unit": next_timer.get("timer_unit") if next_timer else None,
        "last_any_timer_at": last_any_timer.get("last_at") if last_any_timer else None,
        "last_any_timer_unit": last_any_timer.get("timer_unit") if last_any_timer else None,
        "next_any_timer_at": next_any_timer.get("next_at") if next_any_timer else None,
        "next_any_timer_unit": next_any_timer.get("timer_unit") if next_any_timer else None,
        "timers": timers,
        "observed_at": automation_status.get("generated_at"),
        "source": automation_status.get("source"),
    }


def manual_mode_allowed() -> list[str]:
    return ["all", "orchestrator", "architect", "dispatcher", "workers", "integrator", "finalizer", "model_limit_retries", "release_locks", "pr_intake", "result_handoff", "full_intake", "cycle"]


def normalize_manual_mode(raw: str) -> str:
    value = str(raw or "").strip().lower()
    if value in {"orchestrator", "cycle"}:
        value = "all"
    if value == "worker":
        value = "workers"
    if value not in manual_mode_allowed():
        return "unknown"
    return value


def resolve_project_base_ref(project: dict[str, Any]) -> str:
    explicit_base_ref = str(project.get("base_ref", "")).strip()
    if explicit_base_ref:
        return explicit_base_ref

    base_branch = str(project.get("base_branch", "")).strip()
    if not base_branch:
        base_branch = "develop"
    if base_branch.startswith("origin/"):
        return base_branch
    return f"origin/{base_branch}"


def github_repo_url(repo: str) -> str:
    value = str(repo or "").strip()
    if not value:
        return ""
    if value.startswith(("http://", "https://", "git@")):
        return value
    return f"https://github.com/{value}.git"


def github_branch_ref(project: dict[str, Any]) -> str:
    base = resolve_project_base_ref(project)
    return base.removeprefix("origin/") or str(project.get("base_branch") or "develop")


def command_root_origin_access(command_root: Path, repo: str, branch: str, env: dict[str, str] | None = None) -> dict[str, Any] | None:
    git_env = dict(env or os.environ)
    if not git_env.get("HOME") and hasattr(os, "geteuid") and os.geteuid() == 0:
        git_env["HOME"] = "/root"
    git_env["GIT_TERMINAL_PROMPT"] = "0"
    try:
        proc = subprocess.run(
            ["git", "ls-remote", "--heads", "origin", branch],
            cwd=command_root,
            text=True,
            capture_output=True,
            check=False,
            timeout=10,
            env=git_env,
        )
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
        return None
    if proc.returncode == 0 and proc.stdout.strip():
        return {
            "checked": True,
            "ok": True,
            "repo": repo,
            "branch": branch,
            "reason": "command_root_origin_accessible",
            "command_root": str(command_root),
        }
    return None


def github_access_diagnostic(project: dict[str, Any]) -> dict[str, Any]:
    repo = str(project.get("github_repo") or "").strip()
    if not repo:
        return {"checked": False, "ok": None, "reason": "github_repo_missing"}
    branch = github_branch_ref(project)
    command_root = project_command_root(project)
    command_root_key = str(command_root) if command_root.exists() and is_git_worktree(command_root) else ""
    key = (repo, branch, command_root_key)
    now = time.time()
    with _GITHUB_ACCESS_LOCK:
        cached = _GITHUB_ACCESS_STATE.get(key)
        if cached and now - cached[0] < GITHUB_ACCESS_TTL_SECONDS:
            return dict(cached[1])
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    if command_root_key:
        result = command_root_origin_access(command_root, repo, branch, env)
        if result is not None:
            with _GITHUB_ACCESS_LOCK:
                _GITHUB_ACCESS_STATE[key] = (now, dict(result))
            return result
    url = github_repo_url(repo)
    try:
        proc = subprocess.run(
            ["git", "ls-remote", "--heads", url, branch],
            text=True,
            capture_output=True,
            check=False,
            timeout=10,
            env=env,
        )
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired) as exc:
        result = {"checked": True, "ok": False, "repo": repo, "branch": branch, "reason": "git_remote_check_failed", "detail": str(exc)}
    else:
        detail = (proc.stderr or proc.stdout or "").strip()[-1000:]
        if proc.returncode == 0 and proc.stdout.strip():
            result = {"checked": True, "ok": True, "repo": repo, "branch": branch, "reason": "remote_branch_accessible"}
        elif proc.returncode == 0:
            result = {"checked": True, "ok": False, "repo": repo, "branch": branch, "reason": "remote_branch_missing_or_empty", "detail": detail}
        else:
            result = {
                "checked": True,
                "ok": False,
                "repo": repo,
                "branch": branch,
                "detail": detail,
                **automation_worktree_planner.classify_remote_access_failure(detail),
            }
    with _GITHUB_ACCESS_LOCK:
        _GITHUB_ACCESS_STATE[key] = (now, dict(result))
    return result


def command_root_preflight(project: dict[str, Any], mode: str) -> tuple[bool, str, str]:
    if mode == "release_locks":
        return True, "", ""
    project_root = project_command_root(project)
    if not str(project_root).strip():
        return False, "Project local_path or automation_path not configured in registry", "command_root_missing"
    if not project_root.exists():
        return False, f"Project command root does not exist: {project_root}", "command_root_missing"
    if not is_git_worktree(project_root):
        return False, f"Project command root is not a git worktree: {project_root}", "command_root_not_git_worktree"
    return True, "", ""


def start_control_command(
    runtime_root: Path,
    registry_path: Path | None,
    project: dict[str, Any],
    mode: str,
    payload: dict[str, Any] | None = None,
) -> tuple[bool, str, dict[str, Any]]:
    project_id = str(project.get("project_id", "")).strip()
    if not project_id:
        return False, "Project ID missing in registry", {}
    if registry_path is None:
        return False, "Project registry is required for command consumer", {}
    mode = normalize_manual_mode(mode)
    if mode == "unknown":
        return False, f"Unknown mode: {mode}", {}
    preflight_ok, preflight_error, preflight_code = command_root_preflight(project, mode)
    if not preflight_ok:
        return False, preflight_error, {"project_id": project_id, "mode": mode, "error_code": preflight_code}
    payload = payload or {}
    task_id = str(payload.get("task_id") or "").strip()
    runner_readiness = load_runner_readiness_by_project(runtime_root).get(project_id)
    if runner_readiness_blocks_worker_run(runner_readiness) and (mode in {"all", "workers"} or task_id):
        reason = runner_readiness_worker_block_reason(runner_readiness)
        return False, reason, {
            "project_id": project_id,
            "mode": mode,
            "error_code": "runner_readiness_blocked",
            "runner_readiness": compact_runner_readiness_block(runner_readiness),
        }
    command_request = {
        "action": "automation.run",
        "mode": "project" if mode == "all" else "role",
        "role": mode,
        "project_id": project_id,
        "apply": True,
    }
    if mode == "model_limit_retries":
        requested_retry_limit = payload.get("model_limit_retry_limit")
        command_request["model_limit_retry_limit"] = max(
            0,
            safe_int(requested_retry_limit) if requested_retry_limit is not None else MODEL_LIMIT_RETRY_BATCH_LIMIT,
        )
    if task_id:
        command_request.update({"mode": "one-task", "task_id": task_id, "worker_id": str(payload.get("worker_id") or "auto-worker-5.3-mini")})
    idempotency_key = str(payload.get("idempotency_key") or f"dashboard:{project_id}:{mode}:{task_id}:{int(time.time())}")
    try:
        created = command_bus.enqueue(runtime_root, command_request, actor="dashboard", idempotency_key=idempotency_key)
    except Exception as exc:
        return False, f"Failed to enqueue command: {exc}", {}
    command = created["command"]
    consumer = Path(__file__).resolve().parent / "command_consumer.py"
    run_command = [
        sys.executable or "python3",
        str(consumer),
        "--runtime-root",
        str(runtime_root),
        "--registry",
        str(registry_path),
        "--lease-owner",
        "dashboard-local-control",
        "--once",
        "--json",
    ]
    try:
        process = subprocess.Popen(run_command, start_new_session=True)
    except Exception as exc:
        command_bus.update_command(runtime_root, str(command["command_id"]), {"state": "failed", "error": f"consumer_start_failed: {exc}"})
        return False, f"Failed to start command consumer: {exc}", {"command_id": command.get("command_id")}
    return True, "Command queued", {"pid": process.pid, "command_id": command.get("command_id"), "created": created.get("created"), "mode": mode, "project_id": project_id}


def start_worktree_remediation_command(
    runtime_root: Path,
    registry_path: Path | None,
    worktree_root: Path,
    payload: dict[str, Any] | None = None,
) -> tuple[bool, str, dict[str, Any]]:
    if registry_path is None:
        return False, "Project registry is required for worktree remediation", {"error_code": "registry_missing"}
    payload = payload or {}
    apply = bool(payload.get("apply", False))
    if apply:
        return False, "Dashboard worktree remediation only supports dry-run commands", {"error_code": "apply_not_allowed"}
    command_request = {
        "action": "automation.run",
        "mode": "worktrees",
        "worktree_root": str(worktree_root),
        "no_remote_check": bool(payload.get("no_remote_check", False)),
        "apply": False,
    }
    idempotency_key = str(payload.get("idempotency_key") or f"dashboard:worktrees:dry-run:{int(time.time())}")
    try:
        created = command_bus.enqueue(runtime_root, command_request, actor="dashboard", idempotency_key=idempotency_key)
    except Exception as exc:
        return False, f"Failed to enqueue worktree remediation command: {exc}", {"error_code": "enqueue_failed"}
    command = created["command"]
    consumer = Path(__file__).resolve().parent / "command_consumer.py"
    run_command = [
        sys.executable or "python3",
        str(consumer),
        "--runtime-root",
        str(runtime_root),
        "--registry",
        str(registry_path),
        "--lease-owner",
        "dashboard-worktree-remediation",
        "--once",
        "--json",
    ]
    try:
        process = subprocess.Popen(run_command, start_new_session=True)
    except Exception as exc:
        command_bus.update_command(runtime_root, str(command["command_id"]), {"state": "failed", "error": f"consumer_start_failed: {exc}"})
        return False, f"Failed to start command consumer: {exc}", {"error_code": "consumer_start_failed", "command_id": command.get("command_id")}
    return True, "Worktree remediation command queued", {
        "pid": process.pid,
        "command_id": command.get("command_id"),
        "created": created.get("created"),
        "mode": "worktrees",
        "worktree_root": str(worktree_root),
        "apply": False,
    }


def resolve_command_project(registry_path: Path | None, snapshot_project: dict[str, Any]) -> tuple[dict[str, Any] | None, str]:
    project_id = str(snapshot_project.get("project_id", "")).strip()
    if not project_id:
        return None, "Project ID missing in snapshot"
    if registry_path is None:
        return None, "Project registry is required for command execution"
    projects, warnings = load_projects(registry_path)
    if warnings:
        return None, "; ".join(warnings)
    project = next((item for item in projects if str(item.get("project_id")) == project_id), None)
    if project is None:
        return None, f"Project {project_id} not found in command registry"
    return project, ""


def annotate_snapshot_command_registry(snapshot: dict[str, Any], registry_path: Path | None) -> dict[str, Any]:
    if registry_path is None:
        for project in snapshot.get("projects", []):
            if isinstance(project, dict):
                project["command_registry"] = {
                    "available": False,
                    "reason": "Project registry is required for command execution",
                }
        return snapshot
    projects, warnings = load_projects(registry_path)
    if warnings:
        reason = "; ".join(warnings)
        for project in snapshot.get("projects", []):
            if isinstance(project, dict):
                project["command_registry"] = {"available": False, "reason": reason}
        return snapshot
    by_id = {str(project.get("project_id")): project for project in projects}
    for project in snapshot.get("projects", []):
        if not isinstance(project, dict):
            continue
        project_id = str(project.get("project_id", "")).strip()
        command_project = by_id.get(project_id)
        if command_project is None:
            project["command_registry"] = {
                "available": False,
                "reason": f"Project {project_id} not found in command registry",
            }
            continue
        command_project = dict(command_project)
        project_worktree_plan = project.get("automation_worktree_plan") if isinstance(project.get("automation_worktree_plan"), dict) else {}
        if project_worktree_plan and not str(command_project.get("automation_path") or "").strip():
            automation_path_from_plan = str(
                project_worktree_plan.get("automation_path")
                or project_worktree_plan.get("proposed_automation_path")
                or ""
            ).strip()
            if automation_path_from_plan:
                command_project["automation_path"] = automation_path_from_plan
        command_root = project_command_root(command_project)
        command_root_is_git = is_git_worktree(command_root)
        github_access = github_access_diagnostic(command_project)
        if command_root_is_git and github_access.get("ok") is False:
            origin_access = command_root_origin_access(
                command_root,
                str(command_project.get("github_repo") or ""),
                github_branch_ref(command_project),
            )
            if origin_access is not None:
                github_access = origin_access
        registry_warnings: list[str] = []
        automation_path = str(command_project.get("automation_path") or "").strip()
        git_backed_command = bool(
            automation_path
            or str(command_project.get("base_ref") or command_project.get("base_branch") or "").strip()
            or str(command_project.get("github_repo") or "").strip()
        )
        command_root_recommendation = ""
        if git_backed_command and not command_root_is_git and not automation_path:
            command_root_recommendation = "Configure automation_path to a git worktree for this project; artifact local_path cannot run worker/integrator/finalizer automation."
        elif git_backed_command and not command_root_is_git:
            command_root_recommendation = "Repair or replace automation_path with a valid git worktree before running write-capable automation."
        project["command_registry"] = {
            "available": True,
            "local_path": command_project.get("local_path"),
            "automation_path": automation_path or None,
            "automation_path_configured": bool(automation_path),
            "command_root": str(command_root),
            "command_root_is_git_worktree": command_root_is_git,
            "command_root_recommendation": command_root_recommendation,
            "github_access": github_access,
            "warnings": registry_warnings,
            "base_ref": resolve_project_base_ref(command_project),
            "base_branch": command_project.get("base_branch"),
        }
        if git_backed_command and not command_root_is_git and (github_access.get("reason") == "github_repo_missing" or github_access.get("ok") is False):
            warnings = project.setdefault("warnings", [])
            if isinstance(warnings, list):
                if github_access.get("reason") == "github_repo_missing":
                    warning = "Server command registry is missing github_repo; automation_path cannot be prepared automatically from this host."
                else:
                    warning = f"Server cannot access GitHub repo {github_access.get('repo')} branch {github_access.get('branch')}; automation_path cannot be prepared automatically from this host."
                registry_warnings.append(warning)
                if warning not in warnings:
                    warnings.append(warning)
    return snapshot


def project_command_root(project: dict[str, Any]) -> Path:
    return Path(str(project.get("automation_path") or project.get("local_path") or "")).expanduser()


def overlay_snapshot_runtime_state(snapshot: dict[str, Any], runtime_root: Path) -> dict[str, Any]:
    for project in snapshot.get("projects", []):
        if not isinstance(project, dict):
            continue
        project_id = str(project.get("project_id", "")).strip()
        if not project_id:
            continue
        project["commands"] = load_project_commands(runtime_root, project_id)
        manual_run = load_manual_run_status(runtime_root, project_id)
        if manual_run is not None and manual_run_active(manual_run):
            project["manual_run"] = manual_run
        elif "manual_run" in project and not manual_run_active(project.get("manual_run") or {}):
            project["manual_run"] = None
    return snapshot


def runner_readiness_candidate_paths(runtime_root: Path) -> list[Path]:
    candidates = [runtime_root.expanduser() / "runner-readiness.json"]
    if runtime_root.expanduser().name == "dashboard-live":
        candidates.append(runtime_root.expanduser().parent / "runner-readiness.json")
    return candidates


def load_runner_readiness_report(runtime_root: Path) -> tuple[dict[str, Any], Path | None]:
    for path in runner_readiness_candidate_paths(runtime_root):
        data, _ = load_json(path)
        if isinstance(data, dict):
            return data, path
    return {}, None


def load_runner_readiness_by_project(runtime_root: Path) -> dict[str, dict[str, Any]]:
    data, path = load_runner_readiness_report(runtime_root)
    if not isinstance(data, dict) or path is None:
        return {}
    generated_at = data.get("generated_at")
    freshness = runner_readiness_freshness(generated_at)
    result: dict[str, dict[str, Any]] = {}
    for project in data.get("projects") or []:
        if isinstance(project, dict) and project.get("project_id"):
            enriched = dict(project)
            enriched["_runner_readiness_source_path"] = str(path)
            enriched["_runner_readiness_generated_at"] = generated_at
            enriched["_runner_readiness_age_seconds"] = freshness["age_seconds"]
            enriched["_runner_readiness_stale"] = freshness["stale"]
            enriched["_runner_readiness_stale_minutes"] = freshness["stale_minutes"]
            result[str(project.get("project_id"))] = enriched
    return result


def runner_readiness_summary(runtime_root: Path) -> dict[str, Any]:
    data, path = load_runner_readiness_report(runtime_root)
    if not isinstance(data, dict) or path is None:
        return {"available": False}
    generated_at = data.get("generated_at")
    freshness = runner_readiness_freshness(generated_at)
    source_summary = data.get("summary") if isinstance(data.get("summary"), dict) else {}
    return {
        "available": True,
        **source_summary,
        "generated_at": generated_at,
        "source_path": str(path),
        "age_seconds": freshness["age_seconds"],
        "stale": freshness["stale"],
        "stale_minutes": freshness["stale_minutes"],
    }


def runner_readiness_freshness(
    generated_at: Any,
    *,
    stale_minutes: int = RUNNER_READINESS_STALE_MINUTES_DEFAULT,
) -> dict[str, Any]:
    parsed = parse_datetime(generated_at)
    if parsed is None:
        return {"age_seconds": None, "stale": False, "stale_minutes": stale_minutes}
    age_seconds = max(0, int((dt.datetime.now(LOCAL_TZ) - parsed).total_seconds()))
    return {
        "age_seconds": age_seconds,
        "stale": age_seconds > stale_minutes * 60,
        "stale_minutes": stale_minutes,
    }


def worker_host_blocker_from_runner_readiness(readiness: dict[str, Any]) -> dict[str, Any] | None:
    host = readiness.get("codex_host_readiness")
    if not isinstance(host, dict) or host.get("ok") is not False:
        return None
    blockers = {str(blocker) for blocker in readiness.get("blockers") or []}
    if "codex host is not ready" not in blockers:
        return None
    reason = str(host.get("reason") or "codex_host_unavailable")
    if not reason.startswith("codex_"):
        return None
    messages = {
        "codex_executable_missing": "Worker host cannot run ready tasks: codex executable is missing.",
        "codex_auth_missing": "Worker host cannot run ready tasks: Codex credentials are missing.",
        "codex_doctor_failed": "Worker host cannot run ready tasks: codex doctor failed.",
        "codex_doctor_timeout": "Worker host cannot run ready tasks: codex doctor timed out.",
    }
    next_actions = {
        "codex_executable_missing": "Install codex CLI on the automation host, then rerun runner readiness.",
        "codex_auth_missing": "Authenticate codex non-interactively on the automation host, then rerun runner readiness.",
        "codex_doctor_failed": "Run codex doctor on the automation host and repair the reported host issue.",
        "codex_doctor_timeout": "Run codex doctor on the automation host and resolve the timeout before retrying workers.",
    }
    return {
        "error": reason,
        "reason": messages.get(reason, "Worker host cannot run ready tasks: codex host is not ready."),
        "next_action": next_actions.get(reason, "Repair codex host readiness before retrying workers."),
        "codex_bin": host.get("codex_bin") or "codex",
        "host_readiness": host,
        "affected_task_count": safe_int(readiness.get("worker_ready_candidate_count") or readiness.get("raw_worker_ready_candidates")),
        "source": "runner-readiness",
        "source_path": readiness.get("_runner_readiness_source_path"),
        "source_generated_at": readiness.get("_runner_readiness_generated_at"),
        "source_age_seconds": readiness.get("_runner_readiness_age_seconds"),
        "source_stale": readiness.get("_runner_readiness_stale"),
        "source_stale_minutes": readiness.get("_runner_readiness_stale_minutes"),
    }


def runner_readiness_blocks_worker_run(readiness: dict[str, Any] | None) -> bool:
    if not isinstance(readiness, dict):
        return False
    blockers = {str(blocker) for blocker in readiness.get("blockers") or []}
    if "codex host is not ready" not in blockers:
        return False
    return safe_int(readiness.get("worker_ready_candidate_count")) > 0


def runner_readiness_worker_block_reason(readiness: dict[str, Any] | None) -> str:
    host = readiness.get("codex_host_readiness") if isinstance(readiness, dict) else {}
    reason = str(host.get("reason") or "runner_readiness_blocked") if isinstance(host, dict) else "runner_readiness_blocked"
    messages = {
        "codex_auth_missing": "Worker run is blocked: Codex credentials are missing on this host.",
        "codex_executable_missing": "Worker run is blocked: codex executable is missing on this host.",
        "codex_doctor_failed": "Worker run is blocked: codex doctor failed on this host.",
        "codex_doctor_timeout": "Worker run is blocked: codex doctor timed out on this host.",
    }
    return messages.get(reason, "Worker run is blocked by runner readiness.")


def compact_runner_readiness_block(readiness: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(readiness, dict):
        return {}
    return {
        "ready": readiness.get("ready"),
        "blockers": readiness.get("blockers") or [],
        "candidate_task_count": safe_int(readiness.get("candidate_task_count")),
        "worker_ready_candidate_count": safe_int(readiness.get("worker_ready_candidate_count")),
        "codex_host_readiness": readiness.get("codex_host_readiness"),
    }


def compact_runner_candidate_tasks(
    readiness: dict[str, Any] | None,
    limit: int = RUNNER_READINESS_CANDIDATE_PREVIEW_LIMIT,
    task_lookup: dict[str, dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    if not isinstance(readiness, dict):
        return []
    result: list[dict[str, Any]] = []
    for task in readiness.get("candidate_tasks") or []:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        source_task = (task_lookup or {}).get(task_id, {})
        result.append({
            "id": task.get("id") or task_id,
            "task_id": task_id,
            "title": task.get("title"),
            "status": task.get("status"),
            "complexity": task.get("complexity"),
            "worker_ready": bool(task.get("worker_ready")),
            "missing_fields": task.get("missing_fields") if isinstance(task.get("missing_fields"), list) else [],
            "blockers": task.get("blockers") if isinstance(task.get("blockers"), list) else [],
            "recommended_agent": task.get("recommended_agent") or source_task.get("recommended_agent"),
            "eligible_worker_profiles": task.get("eligible_worker_profiles") or source_task.get("eligible_worker_profiles") or [],
            "model_candidates": task.get("model_candidates") or source_task.get("model_candidates") or [],
            "execution_route": task.get("execution_route") or source_task.get("execution_route"),
            "preferred_execution_tier": task.get("preferred_execution_tier") or source_task.get("preferred_execution_tier"),
        })
        if len(result) >= max(0, int(limit)):
            break
    return result


def worker_candidate_route(task: dict[str, Any]) -> str:
    route_values: list[Any] = [
        task.get("execution_route"),
        task.get("preferred_execution_tier"),
        task.get("recommended_agent"),
    ]
    route_values.extend(task.get("eligible_worker_profiles") or [])
    route_values.extend(task.get("model_candidates") or [])
    route_text = " ".join(str(value or "").strip().lower() for value in route_values)
    if any(marker in route_text for marker in ("local_llm", "local-llm", "ollama", "llama-server")):
        return "local_llm"
    if any(marker in route_text for marker in ("gpt-5.3-codex-spark", "auto-worker-5.3", "spark")):
        return "spark_5_3"
    if any(marker in route_text for marker in ("gpt-5.6", "auto-worker-5.5")):
        return "gpt_5_6"
    return "unclassified"


def worker_candidate_route_counts(tasks: list[dict[str, Any]], candidate_count: int) -> dict[str, int]:
    counts = {"local_llm": 0, "spark_5_3": 0, "gpt_5_6": 0, "unclassified": 0}
    classified = 0
    for task in tasks:
        if not isinstance(task, dict) or task.get("worker_ready") is not True:
            continue
        counts[worker_candidate_route(task)] += 1
        classified += 1
    counts["unclassified"] += max(0, safe_int(candidate_count) - classified)
    return counts


def worker_run_action_from_runner_readiness(
    readiness: dict[str, Any] | None,
    preview: list[dict[str, Any]] | None = None,
    candidates: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    if not isinstance(readiness, dict):
        return {"ready": False, "candidate_count": 0, "next_task_ids": [], "recommended_mode": None}
    candidate_count = safe_int(readiness.get("worker_ready_candidate_count") or readiness.get("raw_worker_ready_candidates"))
    ready = bool(readiness.get("worker_run_ready")) and candidate_count > 0
    tasks = preview if isinstance(preview, list) else compact_runner_candidate_tasks(readiness)
    route_tasks = candidates if isinstance(candidates, list) else tasks
    next_task_ids = [
        str(task.get("task_id") or task.get("id") or "").strip()
        for task in tasks
        if isinstance(task, dict) and str(task.get("task_id") or task.get("id") or "").strip()
    ]
    return {
        "ready": ready,
        "candidate_count": candidate_count,
        "next_task_ids": next_task_ids,
        "recommended_mode": "workers" if ready else None,
        "blocked": bool(readiness.get("worker_run_blocked")),
        "blockers": readiness.get("current_worker_run_blockers") or readiness.get("blockers") or [],
        "route_counts": worker_candidate_route_counts(route_tasks, candidate_count),
    }


def attach_worker_run_command(action: dict[str, Any], project_id: str) -> dict[str, Any]:
    if not action.get("ready") or not str(project_id or "").strip():
        return action
    task_key = "-".join(str(task_id) for task_id in (action.get("next_task_ids") or [])[:3]) or "ready"
    action["command_request"] = {
        "action": "automation.run",
        "mode": "role",
        "project_id": project_id,
        "role": "workers",
        "max_total_workers": 1,
        "max_tasks_per_lane": 1,
        "apply": True,
    }
    action["idempotency_key"] = f"worker-run:{project_id}:{safe_int(action.get('candidate_count'))}:{task_key}"
    return action


def aggregate_worker_run_action(projects: list[Any]) -> dict[str, Any]:
    ready_projects: list[str] = []
    total_candidates = 0
    prepared_candidates = 0
    next_items: list[dict[str, Any]] = []
    route_counts = {"local_llm": 0, "spark_5_3": 0, "gpt_5_6": 0, "unclassified": 0}
    prepared_route_counts = {"local_llm": 0, "spark_5_3": 0, "gpt_5_6": 0, "unclassified": 0}
    blocked_projects: list[dict[str, Any]] = []
    for project in projects:
        if not isinstance(project, dict):
            continue
        action = project.get("worker_run_action")
        if not isinstance(action, dict):
            continue
        project_id = str(project.get("project_id") or project.get("name") or "").strip()
        project_candidates = safe_int(action.get("candidate_count"))
        prepared_candidates += project_candidates
        project_route_counts = action.get("route_counts") if isinstance(action.get("route_counts"), dict) else {}
        for route in prepared_route_counts:
            prepared_route_counts[route] += safe_int(project_route_counts.get(route))
        if not action.get("ready"):
            if project_candidates:
                blocked_projects.append(
                    {
                        "project_id": project_id,
                        "candidate_count": project_candidates,
                        "blockers": action.get("blockers") or [],
                    }
                )
            continue
        ready_projects.append(project_id)
        total_candidates += project_candidates
        for route in route_counts:
            route_counts[route] += safe_int(project_route_counts.get(route))
        for task_id in action.get("next_task_ids") or []:
            if len(next_items) >= RUNNER_READINESS_CANDIDATE_PREVIEW_LIMIT:
                break
            next_items.append({"project_id": project_id, "task_id": str(task_id)})
    command_requests = [
        {
            "project_id": str(project.get("project_id") or project.get("name") or ""),
            "command_request": (project.get("worker_run_action") or {}).get("command_request"),
            "idempotency_key": (project.get("worker_run_action") or {}).get("idempotency_key"),
        }
        for project in projects
        if isinstance(project, dict)
        and isinstance(project.get("worker_run_action"), dict)
        and (project.get("worker_run_action") or {}).get("ready")
    ]
    return {
        "ready": bool(ready_projects),
        "ready_projects": ready_projects,
        "ready_project_count": len(ready_projects),
        "candidate_count": total_candidates,
        "prepared_candidate_count": prepared_candidates,
        "blocked_candidate_count": max(0, prepared_candidates - total_candidates),
        "blocked_projects": blocked_projects,
        "next_items": next_items,
        "recommended_mode": "workers" if ready_projects else None,
        "command_requests": command_requests,
        "route_counts": route_counts,
        "prepared_route_counts": prepared_route_counts,
    }


def should_preserve_existing_runner_readiness(existing: Any, local_summary: dict[str, Any]) -> bool:
    if not isinstance(existing, dict) or local_summary.get("stale") is not True:
        return False
    existing_freshness = runner_readiness_freshness(existing.get("generated_at"))
    if existing_freshness.get("stale") is False:
        return True
    existing_generated_at = parse_datetime(existing.get("generated_at"))
    local_generated_at = parse_datetime(local_summary.get("generated_at"))
    if existing_generated_at is None or local_generated_at is None:
        return False
    return existing_generated_at > local_generated_at


def overlay_snapshot_runner_readiness(
    snapshot: dict[str, Any],
    runtime_root: Path,
    *,
    preserve_existing_when_local_stale: bool = False,
    prefer_existing: bool = False,
) -> dict[str, Any]:
    readiness_by_project = load_runner_readiness_by_project(runtime_root)
    if not readiness_by_project:
        return snapshot
    compact_summary = runner_readiness_summary(runtime_root)
    summary = snapshot.setdefault("summary", {})
    existing_summary = snapshot.get("runner_readiness")
    if not isinstance(existing_summary, dict):
        existing_summary = summary.get("runner_readiness")
    if prefer_existing and isinstance(existing_summary, dict):
        snapshot["runner_readiness"] = existing_summary
        summary["runner_readiness"] = existing_summary
        summary["runner_readiness_overlay"] = {
            "applied": False,
            "reason": "authoritative_snapshot_source",
            "local_generated_at": compact_summary.get("generated_at"),
            "local_source_path": compact_summary.get("source_path"),
        }
        return snapshot
    if preserve_existing_when_local_stale and should_preserve_existing_runner_readiness(existing_summary, compact_summary):
        snapshot["runner_readiness"] = existing_summary
        summary["runner_readiness"] = existing_summary
        summary["runner_readiness_overlay"] = {
            "applied": False,
            "reason": "local_runner_readiness_stale",
            "local_generated_at": compact_summary.get("generated_at"),
            "local_source_path": compact_summary.get("source_path"),
            "local_age_seconds": compact_summary.get("age_seconds"),
            "local_stale_minutes": compact_summary.get("stale_minutes"),
        }
        return snapshot
    snapshot["runner_readiness"] = compact_summary
    summary["runner_readiness"] = compact_summary
    for key in (
        "worker_run_applicable_projects",
        "worker_run_ready_projects",
        "worker_run_blocked_projects",
        "candidate_tasks",
        "worker_ready_candidates",
        "raw_candidate_tasks",
        "raw_worker_ready_candidates",
        "no_worker_candidate_projects",
    ):
        if key in compact_summary:
            summary[key] = compact_summary.get(key)
    for project in snapshot.get("projects") or []:
        if not isinstance(project, dict):
            continue
        readiness = readiness_by_project.get(str(project.get("project_id") or ""))
        if not isinstance(readiness, dict):
            continue
        project["runner_readiness"] = {
            "ready": readiness.get("ready"),
            "blockers": readiness.get("blockers") or [],
            "candidate_task_count": safe_int(readiness.get("candidate_task_count")),
            "worker_ready_candidate_count": safe_int(readiness.get("worker_ready_candidate_count")),
            "raw_candidate_tasks": safe_int(readiness.get("candidate_task_count")),
            "raw_worker_ready_candidates": safe_int(readiness.get("worker_ready_candidate_count")),
            "worker_run_applicable": bool(readiness.get("worker_run_applicable")),
            "worker_run_ready": bool(readiness.get("worker_run_ready")),
            "worker_run_blocked": bool(readiness.get("worker_run_blocked")),
            "current_worker_run_blockers": readiness.get("current_worker_run_blockers") or [],
            "codex_host_readiness": readiness.get("codex_host_readiness"),
            "source_path": readiness.get("_runner_readiness_source_path"),
            "generated_at": readiness.get("_runner_readiness_generated_at"),
            "age_seconds": readiness.get("_runner_readiness_age_seconds"),
            "stale": readiness.get("_runner_readiness_stale"),
            "stale_minutes": readiness.get("_runner_readiness_stale_minutes"),
        }
        task_lookup = {
            str(task.get("id") or task.get("task_id") or "").strip(): task
            for task in project.get("tasks") or []
            if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "").strip()
        }
        candidate_preview = compact_runner_candidate_tasks(readiness, task_lookup=task_lookup)
        route_candidates = compact_runner_candidate_tasks(
            readiness,
            limit=max(safe_int(readiness.get("candidate_task_count")), RUNNER_READINESS_CANDIDATE_PREVIEW_LIMIT),
            task_lookup=task_lookup,
        )
        project["runner_readiness"]["candidate_task_preview"] = candidate_preview
        project["runner_readiness"]["candidate_task_preview_count"] = len(candidate_preview)
        project["worker_run_action"] = attach_worker_run_command(
            worker_run_action_from_runner_readiness(readiness, candidate_preview, route_candidates),
            str(project.get("project_id") or ""),
        )
        role_state = project.setdefault("role_state", {})
        if not isinstance(role_state, dict):
            role_state = {}
            project["role_state"] = role_state
        worker = role_state.setdefault("worker", {})
        if not isinstance(worker, dict):
            worker = {}
            role_state["worker"] = worker
        if project["worker_run_action"].get("ready") and str(worker.get("state") or "") not in {"running", "active", "leased"}:
            worker["state"] = "ready"
            worker["reason"] = "worker_run_ready"
            worker["last_result"] = "worker_run_ready"
            worker.pop("last_error", None)
        blocker = worker_host_blocker_from_runner_readiness(readiness)
        if not isinstance(blocker, dict):
            project.pop("worker_host_blocker", None)
            continue
        project["worker_host_blocker"] = blocker
        worker["state"] = "blocked"
        worker["reason"] = blocker["error"]
        worker["last_error"] = {
            "state": "blocked",
            "error": blocker["error"],
            "payload": {
                "status": "blocked",
                "error": blocker["error"],
                "host_readiness": blocker.get("host_readiness"),
                "source": "runner-readiness",
            },
        }
    summary["worker_run_action"] = aggregate_worker_run_action(snapshot.get("projects") or [])
    return snapshot


def overlay_snapshot_role_state(snapshot: dict[str, Any], registry_path: Path | None) -> dict[str, Any]:
    if registry_path is None:
        return snapshot
    projects, warnings = load_projects(registry_path)
    if warnings:
        return snapshot
    by_id = {str(project.get("project_id")): project for project in projects}
    for project in snapshot.get("projects", []):
        if not isinstance(project, dict):
            continue
        command_project = by_id.get(str(project.get("project_id", "")).strip())
        if command_project is None:
            continue
        role_state = load_project_role_state(command_project)
        project["role_state"] = role_state
        breakdown = project.get("needs_human_breakdown")
        if isinstance(breakdown, dict):
            breakdown["failed_role_states"] = failed_role_state_count(role_state, project.get("counts") if isinstance(project.get("counts"), dict) else {})
            breakdown["non_git_project_roots"] = non_git_project_root_attention(
                command_project,
                safe_int((project.get("counts") or {}).get("total")),
            )
            if breakdown["non_git_project_roots"]:
                warnings = project.setdefault("warnings", [])
                if isinstance(warnings, list):
                    warning = "Project command registry command root is not a git worktree; git-backed automation cannot integrate or finalize code from this root."
                    if warning not in warnings:
                        warnings.append(warning)
            project["needs_human"] = sum(safe_int(value) for value in breakdown.values())
    return snapshot


def overlay_snapshot_attention_from_registry(snapshot: dict[str, Any], registry_path: Path | None, runtime_root: Path) -> dict[str, Any]:
    if registry_path is None:
        return snapshot
    projects, warnings = load_projects(registry_path)
    if warnings:
        return snapshot
    by_id = {str(project.get("project_id")): project for project in projects}
    for project in snapshot.get("projects", []):
        if not isinstance(project, dict):
            continue
        command_project = by_id.get(str(project.get("project_id", "")).strip())
        if command_project is None:
            continue
        live_report = summarize_project(command_project, [], [], {}, runtime_root)
        for key in (
            "needs_human",
            "needs_human_breakdown",
            "tasks",
            "by_status",
            "unrecognized_statuses",
            "added_counts",
            "active_task_age",
            "locks",
            "counts",
            "owner_directives",
            "warnings",
            "role_state",
            "suppressed_attention",
        ):
            if key in live_report:
                if key == "warnings":
                    command_registry = project.get("command_registry") if isinstance(project.get("command_registry"), dict) else {}
                    registry_warnings = command_registry.get("warnings") if isinstance(command_registry.get("warnings"), list) else []
                    merged_warnings: list[Any] = []
                    for warning in [*registry_warnings, *(live_report.get("warnings") or [])]:
                        if warning not in merged_warnings:
                            merged_warnings.append(warning)
                    project[key] = merged_warnings
                else:
                    project[key] = live_report[key]
    return snapshot


def recompute_snapshot_attention_summary(snapshot: dict[str, Any]) -> dict[str, Any]:
    recompute_snapshot_project_totals(snapshot)
    breakdown = zero_human_attention_breakdown()
    effective_breakdown = zero_human_attention_breakdown()
    suppressed_attention = {"total": 0, "by_reason": {}}
    queue_attention_by_lane = {
        "human": 0,
        "environment": 0,
        "architect": 0,
        "dispatcher": 0,
        "worker": 0,
        "integrator": 0,
        "finalizer": 0,
        "unknown": 0,
    }
    queue_attention_plan = zero_queue_attention_plan()
    projects_need_human = 0
    raw_projects_need_human = 0
    human_attention_total = 0
    infra_blocked_total = 0
    worker_host_blocked_candidates = 0
    for project in snapshot.get("projects", []):
        if not isinstance(project, dict):
            continue
        project_breakdown = project.get("needs_human_breakdown")
        if not isinstance(project_breakdown, dict):
            continue
        for key in breakdown:
            breakdown[key] += safe_int(project_breakdown.get(key))
        annotate_project_effective_attention(project)
        project_effective_breakdown = project.get("needs_human_effective_breakdown")
        if isinstance(project_effective_breakdown, dict):
            for key in effective_breakdown:
                effective_breakdown[key] += safe_int(project_effective_breakdown.get(key))
        merge_project_attention_lanes(queue_attention_by_lane, project)
        project_attention_plan = project.get("queue_attention_plan")
        if isinstance(project_attention_plan, dict):
            merge_queue_attention_plan(queue_attention_plan, project_attention_plan)
        project_suppressed = project.get("suppressed_attention") if isinstance(project.get("suppressed_attention"), dict) else {}
        suppressed_attention["total"] += safe_int(project_suppressed.get("total"))
        project_suppressed_reasons = project_suppressed.get("by_reason") if isinstance(project_suppressed.get("by_reason"), dict) else {}
        for reason, count in project_suppressed_reasons.items():
            key = str(reason or "unknown")
            suppressed_attention["by_reason"][key] = safe_int(suppressed_attention["by_reason"].get(key)) + safe_int(count)
        project_worktree_plan = project.get("automation_worktree_plan")
        if not isinstance(project_worktree_plan, dict):
            project_worktree_plan = None
        project_human_attention = safe_int(project.get("needs_human_effective"))
        human_attention_total += project_human_attention
        worker_host_blocked_candidates += safe_int(project.get("worker_host_blocked_candidates"))
        infra_blocked_total += infra_blocked_attention_total(
            project_breakdown,
            project_attention_plan if isinstance(project_attention_plan, dict) else None,
            project_worktree_plan,
        )
        if safe_int(project.get("needs_human")) > 0:
            raw_projects_need_human += 1
        if project_human_attention > 0:
            projects_need_human += 1
    summary = snapshot.setdefault("summary", {})
    summary["human_attention_breakdown"] = breakdown
    summary["human_attention_effective_breakdown"] = effective_breakdown
    summary["queue_attention_by_lane"] = queue_attention_by_lane
    summary["queue_attention_plan"] = queue_attention_plan
    summary["suppressed_attention"] = suppressed_attention
    summary["human_attention_total"] = human_attention_total
    summary["infra_blocked_attention_total"] = infra_blocked_total
    summary["worker_host_blocked_candidates"] = worker_host_blocked_candidates
    summary["raw_projects_need_human"] = raw_projects_need_human
    summary["projects_need_human"] = projects_need_human
    return snapshot


def queue_owner_attention_packets(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    packets: list[dict[str, Any]] = []
    for project in snapshot.get("projects") or []:
        if not isinstance(project, dict):
            continue
        plan = project.get("queue_attention_plan")
        if not isinstance(plan, dict):
            continue
        for item in plan.get("items") or []:
            if not isinstance(item, dict) or str(item.get("action") or "") != "owner_required":
                continue
            packets.append({
                "project_id": item.get("project_id") or project.get("project_id"),
                "project_name": item.get("project_name") or project.get("name"),
                "task_id": item.get("task_id"),
                "title": item.get("title"),
                "reason": item.get("reason"),
                "requested_action": item.get("next_action") or "owner_required",
                "status": item.get("status"),
                "status_raw": item.get("status_raw"),
                "created_at": item.get("created_at") or "",
                "source": "queue_attention_plan",
                "attention_lane": item.get("attention_lane"),
                "queue_attention_action": item.get("action"),
                "dispatcher_decision": item.get("dispatcher_decision"),
                "integration_status": item.get("integration_status"),
            })
    return packets


def apply_derived_human_needed(snapshot: dict[str, Any]) -> dict[str, Any]:
    existing = [
        item
        for item in snapshot.get("human_needed") or []
        if isinstance(item, dict)
    ]
    seen = {
        (
            str(item.get("project_id") or ""),
            str(item.get("task_id") or ""),
            str(item.get("source") or item.get("_packet_path") or ""),
        )
        for item in existing
    }
    all_derived = queue_owner_attention_packets(snapshot)
    for project in snapshot.get("projects") or []:
        if not isinstance(project, dict):
            continue
        project_id = str(project.get("project_id") or "")
        project_existing = [
            item
            for item in project.get("human_needed") or []
            if isinstance(item, dict)
        ]
        project_seen = {
            (
                str(item.get("task_id") or ""),
                str(item.get("source") or item.get("_packet_path") or ""),
            )
            for item in project_existing
        }
        for item in all_derived:
            if str(item.get("project_id") or "") != project_id:
                continue
            project_key = (str(item.get("task_id") or ""), str(item.get("source") or ""))
            if project_key in project_seen:
                continue
            project_seen.add(project_key)
            project_existing.append(item)
        project["human_needed"] = project_existing
    derived: list[dict[str, Any]] = []
    for item in all_derived:
        key = (
            str(item.get("project_id") or ""),
            str(item.get("task_id") or ""),
            str(item.get("source") or ""),
        )
        if key in seen:
            continue
        seen.add(key)
        derived.append(item)
    combined = [*existing, *derived]
    snapshot["human_needed"] = combined
    snapshot.setdefault("summary", {})["human_needed_open"] = len(combined)
    return snapshot


SUMMARY_TOP_LEVEL_ALIAS_KEYS = (
    "human_attention_breakdown",
    "human_attention_effective_breakdown",
    "human_attention_total",
    "infra_blocked_attention_total",
    "projects_need_human",
    "queue_attention_by_lane",
    "queue_attention_plan",
    "raw_projects_need_human",
    "suppressed_attention",
    "worker_host_blocked_candidates",
)


def snapshot_with_summary_aliases(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Expose summary aggregates at the API top level without changing the full snapshot."""
    summary = snapshot.get("summary") if isinstance(snapshot.get("summary"), dict) else {}
    if not summary:
        return snapshot
    result = dict(snapshot)
    for key in SUMMARY_TOP_LEVEL_ALIAS_KEYS:
        if key in summary and key not in result:
            result[key] = summary[key]
    return result


def compact_project_summary(project: dict[str, Any]) -> dict[str, Any]:
    counts = project.get("counts") if isinstance(project.get("counts"), dict) else {}
    return {
        "project_id": project.get("project_id"),
        "name": project.get("name"),
        "github_repo": project.get("github_repo"),
        "base_branch": project.get("base_branch"),
        "observed_at": project.get("observed_at"),
        "counts": counts,
        "added_counts": project.get("added_counts") if isinstance(project.get("added_counts"), dict) else {},
        "completed_recent": project.get("completed_recent") if isinstance(project.get("completed_recent"), dict) else {},
        "task_outcomes_last_24h": (
            project.get("task_outcomes_last_24h")
            if isinstance(project.get("task_outcomes_last_24h"), dict)
            else {}
        ),
        "active_task_age": project.get("active_task_age") if isinstance(project.get("active_task_age"), dict) else {},
        "needs_human_effective": project.get("needs_human_effective", project.get("needs_human", 0)),
        "infra_blocked_attention": project.get("infra_blocked_attention", 0),
        "worker_run_action": (
            project.get("worker_run_action")
            if isinstance(project.get("worker_run_action"), dict)
            else {}
        ),
        "queue_attention_plan": (
            project.get("queue_attention_plan")
            if isinstance(project.get("queue_attention_plan"), dict)
            else {}
        ),
        "warnings": list(project.get("warnings") or [])[:20],
    }


def dashboard_summary_payload(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Return the bounded public summary contract; /latest.json retains the diagnostic snapshot."""
    result = {
        "schema_version": snapshot.get("schema_version", "1.0"),
        "generated_at": snapshot.get("generated_at"),
        "source_generated_at": snapshot.get("source_generated_at"),
        "summary": snapshot.get("summary") if isinstance(snapshot.get("summary"), dict) else {},
        "projects": [
            compact_project_summary(project)
            for project in snapshot.get("projects") or []
            if isinstance(project, dict)
        ],
        "vps_fleet": snapshot.get("vps_fleet") if isinstance(snapshot.get("vps_fleet"), dict) else {},
        "codex_limit_consensus": (
            snapshot.get("codex_limit_consensus")
            if isinstance(snapshot.get("codex_limit_consensus"), list)
            else []
        ),
        "warnings": list(snapshot.get("warnings") or [])[:50],
    }
    return snapshot_with_summary_aliases(result)


def annotate_snapshot_queue_attention_plan(
    snapshot: dict[str, Any],
    automation_worktree_plan: dict[str, Any],
) -> dict[str, Any]:
    worktree_plan_by_project = {
        str(item.get("project_id") or ""): item
        for item in automation_worktree_plan.get("projects") or []
        if isinstance(item, dict)
    }
    for project in snapshot.get("projects") or []:
        if not isinstance(project, dict):
            continue
        project_worktree_plan = worktree_plan_by_project.get(str(project.get("project_id") or ""))
        if isinstance(project_worktree_plan, dict):
            project["automation_worktree_plan"] = project_worktree_plan
        project["queue_attention_plan"] = build_queue_attention_plan(
            project,
            project_worktree_plan,
        )
        annotate_project_effective_attention(project)
    return snapshot


def annotate_snapshot_source_freshness(
    snapshot: dict[str, Any],
    *,
    stale_minutes: int = SNAPSHOT_SOURCE_STALE_MINUTES_DEFAULT,
) -> dict[str, Any]:
    source_generated_at = snapshot.get("source_generated_at")
    parsed = parse_datetime(source_generated_at)
    summary = snapshot.setdefault("summary", {})
    if parsed is None:
        summary["source_snapshot_age_seconds"] = None
        summary["source_snapshot_stale"] = False
        return snapshot

    age_seconds = max(0, int((dt.datetime.now(LOCAL_TZ) - parsed).total_seconds()))
    stale = age_seconds > stale_minutes * 60
    summary["source_snapshot_age_seconds"] = age_seconds
    summary["source_snapshot_stale"] = stale
    summary["source_snapshot_stale_minutes"] = stale_minutes
    if stale:
        warning = {
            "code": "snapshot_source_stale",
            "severity": "warning",
            "message": (
                f"Dashboard source snapshot is older than {stale_minutes} minutes; "
                "runtime overlays are fresh but base queue counts may lag until the snapshot publisher updates."
            ),
            "source_generated_at": source_generated_at,
            "age_seconds": age_seconds,
        }
        warnings = snapshot.setdefault("warnings", [])
        if isinstance(warnings, list) and not any(isinstance(item, dict) and item.get("code") == "snapshot_source_stale" for item in warnings):
            warnings.append(warning)
    return snapshot


def refresh_overlay_generated_at(snapshot: dict[str, Any]) -> dict[str, Any]:
    snapshot["source_generated_at"] = snapshot.get("generated_at")
    snapshot["generated_at"] = utc_now()
    annotate_snapshot_source_freshness(snapshot)
    return snapshot


def refresh_command_registry_origin_access(snapshot: dict[str, Any]) -> dict[str, Any]:
    for project in snapshot.get("projects") or []:
        if not isinstance(project, dict):
            continue
        registry = project.get("command_registry")
        if not isinstance(registry, dict):
            continue
        access = registry.get("github_access")
        if not isinstance(access, dict) or access.get("ok") is not False:
            continue
        command_root = Path(str(registry.get("command_root") or "")).expanduser()
        if not command_root.exists() or not is_git_worktree(command_root):
            continue
        repo = str(access.get("repo") or project.get("github_repo") or "").strip()
        branch = str(access.get("branch") or project.get("base_branch") or "develop").strip() or "develop"
        origin_access = command_root_origin_access(command_root, repo, branch)
        if origin_access is not None:
            registry["github_access"] = origin_access
    return snapshot


def start_manual_automation(
    runtime_root: Path,
    project: dict[str, Any],
    mode: str,
) -> tuple[bool, str, dict[str, Any]]:
    project_id = str(project.get("project_id", "")).strip()
    if not project_id:
        return False, "Project ID missing in registry", {}
    script_path = Path(__file__).resolve().parent / "run_manual_automation.py"
    if not script_path.exists():
        return False, "Manual runner script not found", {}
    mode = normalize_manual_mode(mode)
    if mode == "unknown":
        return False, f"Unknown mode: {mode}", {}
    project_root = str(project_command_root(project)).strip()
    base_ref = resolve_project_base_ref(project)
    if not project_root:
        return False, "Project local_path or automation_path not configured in registry", {}
    preflight_ok, preflight_error, preflight_code = command_root_preflight(project, mode)
    if not preflight_ok:
        return False, preflight_error, {"project_id": project_id, "project_root": project_root, "mode": mode, "error_code": preflight_code}
    command = [
        sys.executable or "python3",
        str(script_path),
        "--runtime-root",
        str(runtime_root),
        "--project-id",
        project_id,
        "--project-root",
        project_root,
        "--base-ref",
        base_ref,
        "--base-branch",
        str(project.get("base_branch", "develop")),
        "--mode",
        mode,
    ]
    try:
        process = subprocess.Popen(command, start_new_session=True)
    except FileNotFoundError:
        return False, "Manual runner script not found", {}
    except Exception as exc:
        return False, f"Failed to start manual run: {exc}", {}
    return True, "Manual run started", {
        "pid": process.pid,
        "project_root": project_root,
        "base_ref": base_ref,
        "base_branch": str(project.get("base_branch", "develop")),
        "mode": mode,
    }



def command_bus_runtime_roots(runtime_root: Path) -> list[Path]:
    runtime = runtime_root.expanduser()
    roots = [runtime]
    if runtime.name == "dashboard-live":
        roots.append(runtime.parent)
    seen: set[str] = set()
    unique: list[Path] = []
    for root in roots:
        key = str(root)
        if key in seen:
            continue
        seen.add(key)
        unique.append(root)
    return unique


def command_bus_noop_reason(value: Any, depth: int = 0) -> str | None:
    if depth > 6:
        return None
    if isinstance(value, str):
        text = value.strip()
        if not (text.startswith("{") and text.endswith("}")):
            return None
        try:
            value = json.loads(text)
        except Exception:
            return None
    if isinstance(value, list):
        for item in value:
            reason = command_bus_noop_reason(item, depth + 1)
            if reason:
                return reason
        return None
    if not isinstance(value, dict):
        return None

    decision = value.get("decision")
    if isinstance(decision, str) and decision in COMMAND_BUS_NOOP_DECISIONS:
        return str(decision)
    no_op = value.get("no_op")
    if isinstance(no_op, dict):
        decision = no_op.get("decision")
        if isinstance(decision, str) and decision:
            return str(decision)
    if value.get("state") == "no_op":
        return str(decision or "no_op")

    for key in ("parsed_json", "results", "stdout"):
        reason = command_bus_noop_reason(value.get(key), depth + 1)
        if reason:
            return reason
    return None


def command_bus_has_hard_failure(value: Any, depth: int = 0) -> bool:
    if depth > 6:
        return False
    if isinstance(value, str):
        text = value.strip()
        if not (text.startswith("{") and text.endswith("}")):
            return False
        try:
            value = json.loads(text)
        except Exception:
            return False
    if isinstance(value, list):
        return any(command_bus_has_hard_failure(item, depth + 1) for item in value)
    if not isinstance(value, dict):
        return False

    state_sync = value.get("state_sync")
    if isinstance(state_sync, dict) and state_sync.get("ok") is False:
        return True
    returncode = value.get("returncode")
    if isinstance(returncode, int) and returncode != 0 and value.get("state") != "no_op":
        return True
    for key in ("parsed_json", "results"):
        if command_bus_has_hard_failure(value.get(key), depth + 1):
            return True
    return False


def compact_command_bus_item(command: dict[str, Any]) -> dict[str, Any]:
    keep_fields = {
        "command_id",
        "idempotency_key",
        "state",
        "created_at",
        "updated_at",
        "actor",
        "risk",
        "action",
        "mode",
        "role",
        "project_id",
        "task_id",
        "worker_id",
        "worktree_root",
        "no_remote_check",
        "max_total_workers",
        "max_tasks_per_lane",
        "apply",
        "lease_owner",
        "leased_at",
        "lease_expires_at",
        "started_at",
        "finished_at",
        "returncode",
        "error",
        "cancel_reason",
        "_runtime_root",
    }
    item = {key: command.get(key) for key in keep_fields if key in command}
    raw_state = str(command.get("state") or "unknown")
    effective_state = raw_state
    stdout = str(command.get("stdout") or "")
    stderr = str(command.get("stderr") or "")
    if stdout:
        item["stdout_preview"] = stdout[-1000:]
        item["stdout_truncated"] = len(stdout) > 1000
    if stderr:
        item["stderr_preview"] = stderr[-1000:]
        item["stderr_truncated"] = len(stderr) > 1000
    parsed = command.get("parsed_json")
    if isinstance(parsed, dict):
        readiness = parsed.get("credential_readiness")
        failed_count = safe_int(parsed.get("failed_count"))
        if not isinstance(readiness, dict):
            for result in parsed.get("results") or []:
                nested = result.get("parsed_json") if isinstance(result, dict) else None
                if isinstance(nested, dict) and not failed_count:
                    failed_count = safe_int(nested.get("failed_count"))
                if isinstance(nested, dict) and isinstance(nested.get("credential_readiness"), dict):
                    readiness = nested["credential_readiness"]
        item["parsed_summary"] = {
            "state": parsed.get("state"),
            "failed_count": failed_count,
            "credential_readiness_reason": readiness.get("reason") if isinstance(readiness, dict) else None,
            "credential_blocked_projects": readiness.get("credential_blocked_projects") if isinstance(readiness, dict) and isinstance(readiness.get("credential_blocked_projects"), list) else [],
        }
        if raw_state == "failed" and parsed.get("state") == "blocked":
            effective_state = "blocked"
        if raw_state == "failed" and failed_count:
            effective_state = "blocked"
        if (
            raw_state == "failed"
            and not failed_count
            and str(command.get("mode") or "") == "role"
            and str(command.get("role") or "") in {"finalizer", "model_limit_retries"}
            and not command_bus_has_hard_failure(parsed)
        ):
            noop_reason = command_bus_noop_reason(parsed)
            if noop_reason:
                effective_state = "no_op"
                item["parsed_summary"]["no_op_reason"] = noop_reason
        if raw_state == "no_op" or parsed.get("state") == "no_op":
            noop_reason = command_bus_noop_reason(parsed)
            if noop_reason:
                item["parsed_summary"]["no_op_reason"] = noop_reason
    item["raw_state"] = raw_state
    item["effective_state"] = effective_state
    item["state"] = effective_state
    return item


def command_bus_scope_key(command: dict[str, Any]) -> tuple[str, ...]:
    return (
        str(command.get("mode") or ""),
        str(command.get("project_id") or ""),
        str(command.get("role") or ""),
        str(command.get("task_id") or ""),
        str(command.get("worktree_root") or ""),
    )


def annotate_command_bus_resolution(commands: list[dict[str, Any]]) -> dict[str, int]:
    states = ("queued", "leased", "running", "succeeded", "no_op", "blocked", "failed", "cancelled", "expired")
    unresolved_counts = {state: 0 for state in states}
    latest_by_scope: dict[tuple[str, ...], dict[str, Any]] = {}
    resolved_states = {"succeeded", "no_op", "cancelled"}
    for command in commands:
        key = command_bus_scope_key(command)
        state = str(command.get("effective_state") or command.get("state") or "unknown")
        latest = latest_by_scope.get(key)
        if latest is None:
            latest_by_scope[key] = command
            if state not in unresolved_counts:
                unresolved_counts[state] = 0
            unresolved_counts[state] += 1
            continue
        latest_state = str(latest.get("effective_state") or latest.get("state") or "unknown")
        if state in {"blocked", "failed"} and latest_state in resolved_states:
            command["resolved_by_command_id"] = latest.get("command_id")
            command["resolved_by_state"] = latest_state
            command["resolved_at"] = latest.get("updated_at") or latest.get("finished_at") or latest.get("created_at")
    return unresolved_counts


def scan_command_bus(runtime_root: Path, limit: int = 20) -> dict[str, Any]:
    commands: list[dict[str, Any]] = []
    for root in command_bus_runtime_roots(runtime_root):
        try:
            root_commands = command_bus.list_commands(root).get("commands", [])
        except Exception:
            continue
        for command in root_commands:
            if not isinstance(command, dict):
                continue
            item = dict(command)
            item["_runtime_root"] = str(root)
            commands.append(item)
    commands.sort(key=lambda item: str(item.get("updated_at") or item.get("created_at") or ""), reverse=True)
    compact_commands = [compact_command_bus_item(command) for command in commands]
    unresolved_counts = annotate_command_bus_resolution(compact_commands)
    counts = {state: 0 for state in ("queued", "leased", "running", "succeeded", "no_op", "blocked", "failed", "cancelled", "expired")}
    raw_counts = {state: 0 for state in ("queued", "leased", "running", "succeeded", "no_op", "blocked", "failed", "cancelled", "expired")}
    for command in compact_commands:
        state = str(command.get("effective_state") or command.get("state") or "unknown")
        if state not in counts:
            counts[state] = 0
        counts[state] += 1
        raw_state = str(command.get("raw_state") or state)
        if raw_state not in raw_counts:
            raw_counts[raw_state] = 0
        raw_counts[raw_state] += 1
    return {
        "available": True,
        "command_count": len(commands),
        "counts": counts,
        "raw_counts": raw_counts,
        "unresolved_counts": unresolved_counts,
        "latest": compact_commands[0] if compact_commands else None,
        "recent": compact_commands[:limit],
    }


def cancel_command_from_runtime_roots(runtime_root: Path, command_id: str) -> dict[str, Any]:
    for root in command_bus_runtime_roots(runtime_root):
        try:
            result = command_bus.cancel_command(root, command_id)
        except KeyError:
            continue
        if isinstance(result, dict):
            result = dict(result)
            result["_runtime_root"] = str(root)
        return result
    raise KeyError(command_id)


def load_project_commands(runtime_root: Path, project_id: str) -> list[dict[str, Any]]:
    try:
        commands = scan_command_bus(runtime_root).get("recent", [])
    except Exception:
        return []
    project_commands = [cmd for cmd in commands if isinstance(cmd, dict) and str(cmd.get("project_id") or "") == str(project_id)]
    project_commands.sort(key=lambda item: str(item.get("updated_at") or item.get("created_at") or ""), reverse=True)
    return project_commands[:10]


def load_project_role_state(project: dict[str, Any]) -> dict[str, Any]:
    project_root = project_command_root(project)
    task_manager = project_root / "AiStudio" / "Task_manager"
    process_data, _ = load_json(task_manager / "agent_process_state.json")
    activity_data, _ = load_json(task_manager / "agent_activity_state.json")
    processes = process_data.get("processes", {}) if isinstance(process_data, dict) else {}
    activity = activity_data.get("role_activity", {}) if isinstance(activity_data, dict) else {}
    aliases = {
        "architect": ("architect", "auto_architect"),
        "dispatcher": ("dispatcher", "auto_dispatcher"),
        "worker": ("worker", "worker_pool", "auto_workers"),
        "integrator": ("integrator", "auto_integrator"),
        "finalizer": ("finalizer", "auto_finalizer"),
    }
    result: dict[str, Any] = {}
    for role, names in aliases.items():
        process = next((processes.get(name) for name in names if isinstance(processes.get(name), dict)), {})
        role_activity = next((activity.get(name) for name in names if isinstance(activity.get(name), dict)), {})
        result[role] = {
            "state": process.get("state"),
            "reason": process.get("reason"),
            "last_started_at": process.get("last_started_at") or role_activity.get("last_started_at"),
            "last_finished_at": process.get("last_finished_at") or role_activity.get("last_finished_at"),
            "last_result": role_activity.get("last_result"),
            "last_skip_reason": role_activity.get("last_skip_reason"),
            "last_run_decision": role_activity.get("last_run_decision"),
            "last_error": process.get("last_error") if isinstance(process.get("last_error"), dict) else None,
        }
    overlay_worker_role_state_from_last_plan(result, task_manager)
    return result


def worker_pool_plan_last_error(plan: dict[str, Any]) -> dict[str, Any] | None:
    if str(plan.get("status") or "") != "blocked":
        return None
    error = str(plan.get("error") or "").strip()
    if not error:
        return None
    return {
        "state": "blocked",
        "error": error,
        "payload": plan,
    }


def role_error_generated_at(error: dict[str, Any] | None) -> str:
    if not isinstance(error, dict):
        return ""
    payload = error.get("payload")
    if isinstance(payload, dict):
        return str(payload.get("generated_at") or payload.get("updated_at") or payload.get("finished_at") or "")
    return str(error.get("generated_at") or error.get("updated_at") or error.get("finished_at") or "")


def overlay_worker_role_state_from_last_plan(role_state: dict[str, Any], task_manager: Path) -> None:
    plan, _ = load_json(task_manager / "worker_pool_last_plan.json")
    if not isinstance(plan, dict):
        return
    plan_error = worker_pool_plan_last_error(plan)
    if plan_error is None:
        return
    worker = role_state.setdefault("worker", {})
    current_error = worker.get("last_error") if isinstance(worker.get("last_error"), dict) else None
    plan_at = str(plan.get("generated_at") or "")
    current_at = role_error_generated_at(current_error)
    if current_error is not None and current_at and plan_at and current_at > plan_at:
        return
    worker["state"] = "blocked"
    worker["reason"] = str(plan.get("error") or "worker_pool_blocked")
    worker["last_finished_at"] = plan_at or worker.get("last_finished_at")
    worker["last_error"] = plan_error


def load_worker_host_blocker_from_root(project_root: Path) -> dict[str, Any] | None:
    task_manager = project_root / "AiStudio" / "Task_manager"
    plan, _ = load_json(task_manager / "worker_pool_last_plan.json")
    if not isinstance(plan, dict):
        return None
    error = str(plan.get("error") or "")
    if str(plan.get("status") or "") != "blocked" or error not in {"codex_executable_missing", "codex_auth_missing", "codex_doctor_failed", "codex_doctor_timeout"}:
        return None
    codex_bin = str(plan.get("codex_bin") or "codex")
    if error == "codex_executable_missing" and shutil.which(codex_bin):
        return None
    reason_text = {
        "codex_executable_missing": "Worker host cannot run ready tasks: codex executable is missing.",
        "codex_auth_missing": "Worker host cannot run ready tasks: Codex credentials are missing.",
        "codex_doctor_failed": "Worker host cannot run ready tasks: codex doctor failed.",
        "codex_doctor_timeout": "Worker host cannot run ready tasks: codex doctor timed out.",
    }.get(error, "Worker host cannot run ready tasks.")
    next_action = {
        "codex_executable_missing": "Install codex CLI on the automation host, then rerun runner readiness.",
        "codex_auth_missing": "Authenticate codex non-interactively on the automation host, then rerun runner readiness.",
        "codex_doctor_failed": "Run codex doctor on the automation host and repair the reported host issue.",
        "codex_doctor_timeout": "Run codex doctor on the automation host and resolve the timeout before retrying workers.",
    }.get(error, "Repair codex host readiness before retrying workers.")
    return {
        "reason": reason_text,
        "next_action": next_action,
        "codex_bin": codex_bin,
        "error": error,
        "host_readiness": plan.get("host_readiness") if isinstance(plan.get("host_readiness"), dict) else None,
        "checked_at": utc_now(),
        "source": "worker_pool_last_plan",
        "source_generated_at": plan.get("generated_at"),
    }


def load_worker_host_blocker(project: dict[str, Any]) -> dict[str, Any] | None:
    return load_worker_host_blocker_from_root(project_command_root(project))


def failed_role_state_count(role_state: dict[str, Any], counts: dict[str, Any] | None = None) -> int:
    hard_failed_states = {"failed", "blocked", "error"}
    total = 0
    counts = counts if isinstance(counts, dict) else {}
    for role, data in role_state.items():
        if not isinstance(data, dict):
            continue
        state = normalize_status(data.get("state"))
        if state in hard_failed_states:
            total += 1
        elif state == "failed_retryable" and safe_int(counts.get(f"lane_{role}")) > 0:
            total += 1
    return total


def is_git_worktree(path: Path) -> bool:
    if not path.exists():
        return False
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "--is-inside-work-tree"],
            cwd=path,
            text=True,
            capture_output=True,
            check=False,
            timeout=5,
        )
    except Exception:
        return False
    return proc.returncode == 0 and proc.stdout.strip().lower() == "true"


def non_git_project_root_attention(project: dict[str, Any], task_count: int) -> int:
    project_root = project_command_root(project)
    git_expected = bool(str(project.get("base_ref") or project.get("base_branch") or "").strip())
    if git_expected and project_root.exists() and not is_git_worktree(project_root):
        return 1
    return 0


def load_automation_bridge_state(project: dict[str, Any]) -> dict[str, Any] | None:
    path = effective_project_root(project) / "AiStudio" / "Task_manager" / "automation_bridge_state.json"
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"state": "unreadable", "path": str(path)}
    if not isinstance(data, dict):
        return {"state": "invalid", "path": str(path)}
    data.setdefault("path", str(path))
    return data


def automation_bridge_queue_evidence(tasks: list[dict[str, Any]]) -> dict[str, Any]:
    bridge_sources = {
        "project_rules_remediation_import_gate",
        "project_rules_remediation_review_import_gate",
        "scripts/agent_control/task_docs_queue_importer.py",
        "design_handoff_task_queue_importer",
    }
    by_source: dict[str, int] = {}
    by_status: dict[str, int] = {}
    by_next_owner: dict[str, int] = {}
    worker_ready = 0
    dispatcher_review = 0
    imported_total = 0
    for task in tasks:
        if not isinstance(task, dict):
            continue
        source = str(task.get("import_source") or task.get("imported_by") or "").strip()
        if source not in bridge_sources:
            continue
        imported_total += 1
        by_source[source] = by_source.get(source, 0) + 1
        status = str(task.get("status") or task.get("dispatcher_decision") or "").strip() or "unknown"
        by_status[status] = by_status.get(status, 0) + 1
        next_owner = str(task.get("next_owner") or task.get("owner") or "").strip() or "unknown"
        by_next_owner[next_owner] = by_next_owner.get(next_owner, 0) + 1
        if task.get("worker_ready") is True:
            worker_ready += 1
        if status == "needs_dispatcher_review" or task.get("dispatcher_decision") == "needs_dispatcher_review":
            dispatcher_review += 1
    return {
        "imported_task_count": imported_total,
        "worker_ready_count": worker_ready,
        "dispatcher_review_count": dispatcher_review,
        "by_source": dict(sorted(by_source.items())),
        "by_status": dict(sorted(by_status.items())),
        "by_next_owner": dict(sorted(by_next_owner.items())),
    }


def positive_delta(value: Any) -> int:
    number = safe_int(value)
    return number if number > 0 else 0


def automation_bridge_conversion(
    counters: dict[str, Any],
    queue_before: dict[str, Any] | None = None,
    queue_after: dict[str, Any] | None = None,
) -> dict[str, Any]:
    before = queue_before if isinstance(queue_before, dict) else {}
    after = queue_after if isinstance(queue_after, dict) else {}
    queue_total_delta = safe_int(counters.get("queue_total_delta"))
    worker_ready_delta = safe_int(counters.get("worker_ready_delta"))
    pr_processed = safe_int(counters.get("pr_processed"))
    pr_events = safe_int(counters.get("pr_events_applied"))
    project_state_intakes = safe_int(counters.get("project_state_intake_written"))
    task_docs = safe_int(counters.get("task_docs_imported"))
    design_imported = safe_int(counters.get("design_imported"))
    project_rules_staged = safe_int(counters.get("project_rules_staged"))
    packet_applied = safe_int(counters.get("packet_applied"))
    packet_cleaned = safe_int(counters.get("packet_cleaned"))
    dispatcher_actions = packet_applied + packet_cleaned
    worker_queue_updates = positive_delta(worker_ready_delta)
    material_update_count = (
        pr_processed
        + pr_events
        + project_state_intakes
        + task_docs
        + design_imported
        + project_rules_staged
        + dispatcher_actions
        + worker_queue_updates
    )
    return {
        "incoming": {
            "pr_checked": safe_int(counters.get("pr_checked")),
            "pr_missing": safe_int(counters.get("pr_missing")),
            "pr_processed": pr_processed,
            "pr_events_applied": pr_events,
            "project_state_intake_written": project_state_intakes,
        },
        "documentation": {
            "task_docs_imported": task_docs,
            "design_packages": safe_int(counters.get("design_packages")),
            "design_candidates": safe_int(counters.get("design_candidates")),
            "design_imported": design_imported,
            "project_rules_candidates": safe_int(counters.get("project_rules_candidates")),
            "project_rules_staged": project_rules_staged,
            "queued_items_created": task_docs + design_imported + project_rules_staged,
        },
        "dispatcher": {
            "packet_selected": safe_int(counters.get("packet_selected")),
            "packet_applied": packet_applied,
            "packet_cleanup_selected": safe_int(counters.get("packet_cleanup_selected")),
            "packet_cleaned": packet_cleaned,
            "dispatcher_actions": dispatcher_actions,
            "owner_gap_count": safe_int(counters.get("owner_gap_count")),
        },
        "worker_queue": {
            "queue_before_total": safe_int(before.get("total")),
            "queue_after_total": safe_int(after.get("total")),
            "queue_total_delta": queue_total_delta,
            "worker_ready_before": safe_int(before.get("worker_ready")),
            "worker_ready_after": safe_int(after.get("worker_ready")),
            "worker_ready_delta": worker_ready_delta,
            "worker_queue_updates": worker_queue_updates,
        },
        "material_update_count": material_update_count,
        "noop_cycle": material_update_count == 0,
    }


def merge_automation_bridge_conversion(total: dict[str, Any], conversion: dict[str, Any]) -> None:
    for section in ("incoming", "documentation", "dispatcher", "worker_queue"):
        target = total.setdefault(section, {})
        source = conversion.get(section) if isinstance(conversion.get(section), dict) else {}
        for key, value in source.items():
            target[key] = safe_int(target.get(key)) + safe_int(value)
    total["material_update_count"] = safe_int(total.get("material_update_count")) + safe_int(
        conversion.get("material_update_count")
    )
    noop_increment = safe_int(conversion.get("noop_cycle_count")) if "noop_cycle_count" in conversion else 0
    if "noop_cycle_count" not in conversion and conversion.get("noop_cycle"):
        noop_increment = 1
    total["noop_cycle_count"] = safe_int(total.get("noop_cycle_count")) + noop_increment


def merge_automation_bridge_convergence(total: dict[str, Any], convergence: dict[str, Any]) -> None:
    policy = str(convergence.get("policy") or "").strip()
    if policy:
        policies = total.setdefault("policies", {})
        policies[policy] = safe_int(policies.get(policy)) + 1
        if not total.get("policy") or total.get("policy") == policy:
            total["policy"] = policy
        else:
            total["policy"] = "mixed"
    for section in ("scan", "normalize", "cleanup", "documentation_code_tasks"):
        target = total.setdefault(section, {})
        source = convergence.get(section) if isinstance(convergence.get(section), dict) else {}
        for key, value in source.items():
            target[key] = safe_int(target.get(key)) + safe_int(value)
    total["cycle_count"] = safe_int(total.get("cycle_count")) + (safe_int(convergence.get("cycle_count")) or 1)
    total["blocking_count"] = safe_int(total.get("blocking_count")) + safe_int(convergence.get("blocking_count"))
    statuses = total.setdefault("statuses", {})
    status = str(convergence.get("status") or "").strip()
    if status:
        statuses[status] = safe_int(statuses.get(status)) + 1


def automation_bridge_summary(project_reports: list[dict[str, Any]]) -> dict[str, Any]:
    totals: dict[str, int] = {}
    conversion_totals: dict[str, Any] = {}
    convergence_totals: dict[str, Any] = {}
    queue_evidence_totals: dict[str, int] = {}
    latest_at = ""
    latest_material_at = ""
    active_projects = 0
    states: dict[str, int] = {}
    for project in project_reports:
        bridge = project.get("automation_bridge")
        if not isinstance(bridge, dict):
            continue
        active_projects += 1
        state = str(bridge.get("state") or "unknown")
        states[state] = states.get(state, 0) + 1
        updated_at = str(bridge.get("updated_at") or "")
        if updated_at > latest_at:
            latest_at = updated_at
        material_at = str(bridge.get("last_material_update_at") or "")
        if material_at > latest_material_at:
            latest_material_at = material_at
        counters = bridge.get("cumulative_counters")
        if not isinstance(counters, dict):
            counters = bridge.get("counters")
        if not isinstance(counters, dict):
            continue
        for key, value in counters.items():
            try:
                totals[str(key)] = totals.get(str(key), 0) + int(value or 0)
            except (TypeError, ValueError):
                continue
        conversion = bridge.get("cumulative_conversion")
        if not isinstance(conversion, dict):
            conversion = bridge.get("conversion")
        if not isinstance(conversion, dict):
            conversion = automation_bridge_conversion(
                counters,
                bridge.get("queue_before") if isinstance(bridge.get("queue_before"), dict) else None,
                bridge.get("queue_after") if isinstance(bridge.get("queue_after"), dict) else None,
            )
        merge_automation_bridge_conversion(conversion_totals, conversion)
        convergence = bridge.get("cumulative_convergence")
        if not isinstance(convergence, dict):
            convergence = bridge.get("convergence")
        elif isinstance(bridge.get("convergence"), dict):
            latest_convergence = bridge["convergence"]
            convergence = dict(convergence)
            for key in ("policy", "status", "ok"):
                if key not in convergence and key in latest_convergence:
                    convergence[key] = latest_convergence[key]
            if "blocking_count" not in convergence and "blocking_count" in latest_convergence:
                convergence["blocking_count"] = latest_convergence["blocking_count"]
        if isinstance(convergence, dict):
            merge_automation_bridge_convergence(convergence_totals, convergence)
        queue_evidence = bridge.get("queue_evidence")
        if isinstance(queue_evidence, dict):
            for key in ("imported_task_count", "worker_ready_count", "dispatcher_review_count"):
                queue_evidence_totals[key] = queue_evidence_totals.get(key, 0) + safe_int(queue_evidence.get(key))
    return {
        "project_count": active_projects,
        "latest_updated_at": latest_at or None,
        "latest_material_update_at": latest_material_at or None,
        "states": dict(sorted(states.items())),
        "counters": dict(sorted(totals.items())),
        "conversion": conversion_totals,
        "convergence": convergence_totals,
        "queue_evidence": dict(sorted(queue_evidence_totals.items())),
    }


def summarize_project(
    project: dict[str, Any],
    runs: list[dict[str, Any]],
    human_packets: list[dict[str, Any]],
    automation_status: dict[str, Any],
    runtime_root: Path,
) -> dict[str, Any]:
    warnings: list[str] = []
    tasks, task_warnings = load_project_tasks(project)
    warnings.extend(task_warnings)
    history_tasks, history_warnings = load_project_task_history(project)
    warnings.extend(history_warnings)
    agent_events, event_warnings = load_project_agent_events(project)
    warnings.extend(event_warnings)
    locks, lock_warnings = load_project_locks(project)
    warnings.extend(lock_warnings)
    locked_task_ids = {str(lock.get("task_id") or "").strip() for lock in locks if isinstance(lock, dict)}
    for task in tasks:
        embedded_lock = task_embedded_lock(task)
        if embedded_lock is None:
            continue
        if not is_blocking_lock_state(embedded_lock.get("state")):
            continue
        if classify_status(task_effective_status(task)) == "completed":
            continue
        task_id = str(embedded_lock.get("task_id") or "").strip()
        if task_id and task_id in locked_task_ids:
            continue
        locks.append(embedded_lock)
        if task_id:
            locked_task_ids.add(task_id)
    directives, directive_warnings = load_owner_directives(project)
    warnings.extend(directive_warnings)
    active_directives = [d for d in directives if str(d.get("status", "")).lower() == "active"]
    attention_directives = [d for d in directives if owner_directive_requires_attention(d)]
    worktrees = scan_project_worktrees(project)
    project_root = effective_project_root(project)
    running_paths = running_worktree_paths_for_project(project_root)
    running_worktrees = [item for item in worktrees if item.get("path") in running_paths]
    running_set = {item.get("path") for item in running_worktrees}
    running_task_ids = {str(item.get("task_id")) for item in worktrees if item.get("task_id") and item.get("path") in running_set}
    stale_worktrees = [item for item in worktrees if item.get("path") not in running_paths]
    stale_worktree_task_ids = {str(item.get("task_id")) for item in stale_worktrees if item.get("task_id")}
    manual_run = load_manual_run_status(runtime_root=runtime_root, project_id=str(project["project_id"]))
    project_commands = load_project_commands(runtime_root, str(project["project_id"]))
    role_state = load_project_role_state(project)
    worker_host_blocker = load_worker_host_blocker(project)
    automation_bridge = load_automation_bridge_state(project)
    queue_evidence = automation_bridge_queue_evidence(tasks)
    if automation_bridge is None and safe_int(queue_evidence.get("imported_task_count")):
        automation_bridge = {"schema_version": "1.0", "state": "queue_evidence_only"}
    if isinstance(automation_bridge, dict):
        automation_bridge["queue_evidence"] = queue_evidence
    if manual_run is not None and not manual_run_active(manual_run):
        manual_run = None

    project_runs = [run for run in runs if str(run.get("project_id", "")) == project["project_id"]]
    task_token_costs = build_task_token_costs(project_runs)
    failed_runs = [run for run in project_runs if safe_int(run.get("exit_code")) != 0 or run.get("status") == "failed"]
    latest_run = project_runs[0] if project_runs else None
    project_human_packets = [
        packet
        for packet in human_packets
        if str(packet.get("project_id", "")) == project["project_id"] and is_active_human_packet(packet)
    ]
    human_packet_task_ids = {str(packet.get("task_id")) for packet in project_human_packets if packet.get("task_id")}

    latest_task_run: dict[str, dict[str, Any]] = {}
    for run in project_runs:
        task_id = str(run.get("task_id") or "").strip()
        if not task_id or task_id in latest_task_run:
            continue
        latest_task_run[task_id] = run
    latest_lock_by_task: dict[str, dict[str, Any]] = {}
    for lock in locks:
        if not isinstance(lock, dict):
            continue
        task_id = str(lock.get("task_id") or "").strip()
        if not task_id:
            continue
        seen = latest_lock_by_task.get(task_id)
        current_at = parse_datetime(seen.get("at") if isinstance(seen, dict) else None)
        next_at = parse_datetime(lock.get("at"))
        if seen is None or (next_at is not None and (current_at is None or next_at > current_at)):
            latest_lock_by_task[task_id] = lock

    counts = {
        "completed": 0,
        "waiting": 0,
        "active": 0,
        "human": 0,
            "task_packet": 0,
            "postponed": 0,
            "lane_architect": 0,
            "lane_dispatcher": 0,
            "lane_worker": 0,
            "lane_integrator": 0,
        "lane_finalizer": 0,
        "total": len(tasks) + len(history_tasks),
        "active_queue_total": len(tasks),
        "history_total": len(history_tasks),
        "history_completed": 0,
        "history_postponed": 0,
        "role_outcomes": zero_role_outcome_stats(),
        "queue_attention_by_lane": {
            "human": 0,
            "environment": 0,
            "architect": 0,
            "dispatcher": 0,
            "worker": 0,
            "integrator": 0,
            "finalizer": 0,
            "unknown": 0,
        },
    }
    added_counts = task_added_counts(tasks)
    completed_dates: list[dt.datetime] = []
    by_status: dict[str, int] = {}
    task_origin_breakdown = zero_task_origin_breakdown()
    task_origin_active_breakdown = zero_task_origin_breakdown()
    local_llm_pre_worker = local_llm_pre_worker_stats(tasks)
    task_rows: list[dict[str, Any]] = []
    unknown_statuses: dict[str, int] = {}
    human_task_ids: set[str] = set()
    queue_task_keys: set[str] = set()
    completed_task_keys: set[str] = set()
    suppressed_attention = {"total": 0, "by_reason": {}}
    ignore_local_task_state_overrides = bool(project.get("ignore_local_task_state_overrides", False))
    for task in tasks:
        task_id = str(task.get("id") or task.get("task_id") or "")
        queue_status = task_effective_status(task)
        if queue_status and not is_recognized_task_status(queue_status):
            unknown_statuses[queue_status] = unknown_statuses.get(queue_status, 0) + 1
        lock = latest_lock_by_task.get(task_id, {})
        if ignore_local_task_state_overrides:
            inferred_status, status_source = queue_status, "queue"
        else:
            inferred_status, status_source = infer_task_status(
                queue_status=queue_status,
                lock_state=lock.get("state"),
                task_run=latest_task_run.get(task_id),
                running=task_id in running_task_ids,
                stale_worktree=task_id in stale_worktree_task_ids,
            )
        status = inferred_status
        bucket = classify_status(status)
        origin_class = task_origin_class(task, status)
        task_origin_breakdown[origin_class] = safe_int(task_origin_breakdown.get(origin_class)) + 1
        if bucket not in {"completed", "postponed"}:
            task_origin_active_breakdown[origin_class] = safe_int(task_origin_active_breakdown.get(origin_class)) + 1
        counts[bucket] += 1
        if task_id:
            queue_task_keys.update(task_target_keys({
                "task_id": task_id,
                "canonical_target_id": task.get("canonical_target_id"),
            }))
        lane = "completed"
        if bucket not in {"completed", "postponed"}:
            lane = infer_task_role_lane(task, status, latest_task_run.get(task_id))
            counts[f"lane_{lane}"] += 1
        latest_run = latest_task_run.get(task_id)
        completed_at = task_completed_at(task)
        outcome_reference_time = None
        outcome = ""
        if latest_run:
            outcome = infer_run_outcome(latest_run)
            outcome_reference_time = run_completed_at(latest_run)
        elif bucket == "completed":
            outcome = ""
            outcome_reference_time = None
        elif bucket not in {"completed", "postponed"}:
            outcome = infer_task_outcome(task)
        if outcome:
            accumulate_role_outcome_stats(
                counts["role_outcomes"],
                lane if bucket != "completed" else infer_task_role_lane(task, status, latest_run),
                outcome,
                outcome_reference_time,
            )
        if bucket == "completed" and completed_at is None:
            completed_at = run_completed_at(latest_run)
        if bucket == "completed" and completed_at is not None:
            completed_dates.append(completed_at)
        if bucket == "completed" and task_id:
            completed_task_keys.update(task_target_keys({
                "task_id": task_id,
                "canonical_target_id": task.get("canonical_target_id"),
            }))
        attention_lane = ""
        attention_suppressed = False
        attention_suppressed_reason = None
        if bucket == "human":
            if task_id:
                human_task_ids.add(task_id)
            attention_suppressed_reason = suppressed_attention_reason(task)
            attention_suppressed = attention_suppressed_reason is not None
            if not attention_suppressed:
                attention_lane = task_attention_lane(task, status, latest_task_run.get(task_id))
                attention_counts = counts["queue_attention_by_lane"]
                if attention_lane not in attention_counts:
                    attention_lane = "unknown"
                attention_counts[attention_lane] += 1
            else:
                suppressed_attention["total"] += 1
                by_reason = suppressed_attention["by_reason"]
                by_reason[attention_suppressed_reason] = safe_int(by_reason.get(attention_suppressed_reason)) + 1
        by_status[status] = by_status.get(status, 0) + 1
        task_rows.append({
            "id": task_id,
            "title": str(task.get("title") or task.get("module") or task.get("name") or ""),
            "status": status,
            "status_raw": normalize_status(task.get("status") if task.get("status") is not None else queue_status),
            "status_source": status_source,
            "bucket": bucket,
            "size": task_size(task),
            "created_at": task_created_text(task),
            "started_at": task_started_text(task, lock, latest_run),
            "updated_at": task_updated_text(task, lock, latest_run),
            "lock_expires_at": task_lock_expires_text(task, lock),
            "completed_at": task_completed_text(task),
            "priority": str(task.get("priority", "")),
            "worker_id": task.get("worker_id"),
            "recommended_agent": task.get("recommended_agent"),
            "eligible_worker_profiles": task.get("eligible_worker_profiles") if isinstance(task.get("eligible_worker_profiles"), list) else [],
            "model_candidates": task.get("model_candidates") if isinstance(task.get("model_candidates"), list) else [],
            "execution_route": task.get("execution_route") or task.get("route"),
            "preferred_execution_tier": task.get("preferred_execution_tier"),
            "branch": task.get("branch") or task.get("github_branch"),
            "reason": task_display_reason(task),
            "next_owner": task.get("next_owner"),
            "next_action": task.get("next_action"),
            "integration_status": task.get("integration_status"),
            "dispatcher_decision": task.get("dispatcher_decision"),
            "origin_class": origin_class,
            "source_file": task.get("source_file"),
            "imported_by": task.get("imported_by"),
            "split_into": task.get("split_into"),
            "changed_paths": task.get("changed_paths"),
            "worker_changed_paths": task.get("worker_changed_paths"),
            "worker_check_evidence": task.get("worker_check_evidence"),
            "lane": lane if bucket != "completed" else "completed",
            "attention_lane": attention_lane,
            "attention_suppressed": attention_suppressed,
            "attention_suppressed_reason": attention_suppressed_reason,
            "source_path": task.get("_source_path"),
        })
        task_cost = task_token_costs.get(task_id, {})
        task_rows[-1]["token_cost"] = task_cost.get("tokens") if task_cost else None
        task_rows[-1]["token_run_count"] = task_cost.get("runs") if task_cost else 0
        task_rows[-1]["token_success_run_count"] = task_cost.get("success_runs") if task_cost else 0
    for task in history_tasks:
        task_id = str(task.get("id") or task.get("task_id") or "")
        status = task_effective_status(task)
        bucket = classify_status(status)
        if bucket == "completed":
            counts["completed"] += 1
            counts["history_completed"] += 1
            completed_at = task_completed_at(task)
            if completed_at is not None:
                completed_dates.append(completed_at)
            if task_id:
                completed_task_keys.update(task_target_keys({
                    "task_id": task_id,
                    "canonical_target_id": task.get("canonical_target_id"),
                }))
        elif bucket == "postponed":
            counts["postponed"] += 1
            counts["history_postponed"] += 1
        by_status[status] = by_status.get(status, 0) + 1
    recent_task_outcomes = task_outcomes_last_24h(completed_dates, latest_task_run)
    accumulate_event_role_outcomes(counts["role_outcomes"], agent_events, latest_task_run, completed_task_keys, queue_task_keys)
    queue_active_ids = {
        str(task.get("id"))
        for task in task_rows
        if task.get("bucket") == "active" and task.get("id")
    }
    worktree_task_ids = {str(item.get("task_id")) for item in worktrees if item.get("task_id")}
    worktree_only_active = len(running_task_ids - queue_active_ids) + len([item for item in running_worktrees if not item.get("task_id")])
    counts["worktree_active"] = len(running_worktrees)
    counts["active_total"] = safe_int(counts.get("active")) + worktree_only_active
    counts["active_queue_total"] = live_backlog_total(counts)
    counts["terminal_queue_total"] = safe_int(counts.get("completed")) + safe_int(counts.get("postponed"))
    active_age = active_task_age_summary(task_rows)
    counts["active_stale_locks"] = active_age["stale_lock_count"]
    stale_lock_count = safe_int(active_age.get("stale_lock_count"))
    if stale_lock_count:
        warnings.append(f"{stale_lock_count} active lock(s) are expired or stale and require operator review.")
    if unknown_statuses:
        unknown_items = ", ".join(
            f"{name} (x{count})" for name, count in sorted(unknown_statuses.items(), key=lambda item: (-item[1], item[0]))
        )
        warnings.append(f"Unrecognized queue statuses found and mapped by heuristic: {unknown_items}")
    non_git_project_root = bool(non_git_project_root_attention(project, safe_int(counts["total"])))
    if non_git_project_root:
        warnings.append("Project command root (automation_path or local_path) is not a git worktree; git-backed automation cannot integrate or finalize code from this root.")
    needs_human_breakdown = {
        "queue_tasks": len(human_task_ids),
        "human_packets": len(human_packet_task_ids - human_task_ids),
        "owner_directives": len(attention_directives),
        "failed_runs": len(failed_runs),
        "failed_role_states": failed_role_state_count(role_state, counts),
        "stale_locks": stale_lock_count,
        "non_git_project_roots": 1 if non_git_project_root else 0,
    }
    needs_human_total = sum(safe_int(value) for value in needs_human_breakdown.values())

    return {
        **project,
        "observed_at": utc_now(),
        "unrecognized_statuses": unknown_statuses,
        "counts": counts,
        "history_counts": {
            "total": len(history_tasks),
            "completed": counts["history_completed"],
            "postponed": counts["history_postponed"],
        },
        "added_counts": added_counts,
        "completed_recent": period_counts_by_date(completed_dates),
        "task_outcomes_last_24h": recent_task_outcomes,
        "by_status": by_status,
        "task_origin_breakdown": task_origin_breakdown,
        "task_origin_active_breakdown": task_origin_active_breakdown,
        "local_llm_pre_worker": local_llm_pre_worker,
        "tasks": task_rows,
        "active_task_age": active_age,
        "locks": {
            "active": len([lock for lock in locks if is_blocking_lock_state(lock.get("state"))]),
            "total": len(locks),
        },
        "owner_directives": {"active": len(active_directives), "items": active_directives},
        "runs": {
            "total": len(project_runs),
            "failed": len(failed_runs),
            "latest": latest_run,
            "recent": project_runs[:8],
            "all": project_runs,
        },
        "agent_events": agent_events[:500],
        "task_token_costs": [
            {
                "task_id": task_id,
                "tokens": data.get("tokens", 0),
                "runs": data.get("runs", 0),
                "success_runs": data.get("success_runs", 0),
            }
            for task_id, data in sorted(task_token_costs.items(), key=lambda item: str(item[0]))
        ],
        "automation": automation_summary(project_runs, automation_status),
        "automation_bridge": automation_bridge,
        "manual_run": manual_run,
        "commands": project_commands,
        "role_state": role_state,
        "worker_host_blocker": worker_host_blocker,
        "worktrees": running_worktrees,
        "stale_worktrees": stale_worktrees,
        "human_needed": project_human_packets,
        "warnings": warnings,
        "needs_human": needs_human_total,
        "needs_human_breakdown": needs_human_breakdown,
        "suppressed_attention": suppressed_attention,
        "active_stale_locks": stale_lock_count,
    }


def run_token_total(run: dict[str, Any]) -> int:
    usage = run.get("token_usage") or run.get("usage") or {}
    if isinstance(usage, dict):
        for key in ("total_tokens", "tokens_total", "total"):
            value = usage.get(key)
            if value is not None:
                return safe_int(value)
        input_tokens = usage.get("input_tokens") or usage.get("prompt_tokens")
        output_tokens = usage.get("output_tokens") or usage.get("completion_tokens")
        if input_tokens is not None or output_tokens is not None:
            return safe_int(input_tokens) + safe_int(output_tokens)
    for key in ("total_tokens", "tokens_total", "token_total"):
        value = run.get(key)
        if value is not None:
            return safe_int(value)
    prompt_tokens = run.get("prompt_tokens")
    completion_tokens = run.get("completion_tokens")
    if prompt_tokens is not None or completion_tokens is not None:
        return safe_int(prompt_tokens) + safe_int(completion_tokens)
    return 0


def build_task_token_costs(runs: list[dict[str, Any]]) -> dict[str, dict[str, int]]:
    by_task: dict[str, dict[str, int]] = {}
    for run in runs:
        task_id = str(run.get("task_id") or "").strip()
        if not task_id:
            continue
        metrics = by_task.setdefault(task_id, {"tokens": 0, "runs": 0, "success_runs": 0})
        tokens = run_token_total(run)
        metrics["runs"] += 1
        metrics["tokens"] += tokens
        status = str(run.get("status") or "").lower()
        if status == "success" or safe_int(run.get("exit_code")) == 0:
            metrics["success_runs"] += 1
    return by_task


def codex_model_key(value: Any) -> str | None:
    raw = str(value or "").strip().lower()
    if not raw:
        return None
    if "5.3" in raw and "codex" in raw:
        return "5.3"
    if "5.3" in raw and ("spark" in raw or "gpt-5.3" in raw):
        return "5.3"
    if "5.5" in raw and "codex" in raw:
        return "5.5"
    if "5.5" in raw:
        return "5.5"
    if "gpt-5.3" in raw:
        return "5.3"
    if "gpt-5.5" in raw:
        return "5.5"
    return None


def run_model_key(run: dict[str, Any]) -> str | None:
    return codex_model_key(run.get("model")) or codex_model_key(run.get("codex_version"))


def parse_limit_model_metrics(
    limit_estimates: list[dict[str, Any]],
    model_key: str,
) -> dict[str, Any]:
    windows: dict[str, dict[str, float]] = {}
    if not limit_estimates:
        return {}

    for estimate in limit_estimates:
        estimate_model = codex_model_key(estimate.get("model"))
        if estimate_model != model_key:
            continue
        window = str(estimate.get("window") or "").strip()
        if window not in {"5h", "weekly"}:
            continue

        runs_between = safe_int(estimate.get("runs_between"))
        if not runs_between:
            continue

        per_run = estimate.get("estimated_percent_per_run")
        try:
            per_run_value = float(per_run) if per_run is not None else None
        except (TypeError, ValueError):
            per_run_value = None
        if per_run_value is None:
            continue

        row = windows.setdefault(window, {"run_count": 0.0, "percent_sum": 0.0, "samples": 0})
        row["run_count"] += runs_between
        row["percent_sum"] += per_run_value * runs_between
        row["samples"] += 1

    metrics: dict[str, Any] = {}
    for window, window_stats in windows.items():
        run_count = safe_int(window_stats.get("run_count"))
        if run_count:
            metrics[f"est_{window}_per_run"] = round(window_stats.get("percent_sum", 0.0) / run_count, 2)
            metrics[f"est_{window}_runs"] = run_count
            metrics[f"est_{window}_samples"] = safe_int(window_stats.get("samples"))
            metrics[f"est_{window}_per_task"] = metrics[f"est_{window}_per_run"]
    return metrics


def model_cost_analytics(
    project_reports: list[dict[str, Any]],
    *,
    limit_estimates: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    limit_estimates = limit_estimates or []
    metrics: dict[str, dict[str, Any]] = {
        key: {
            "model_key": key,
            "model_label": CODEX_MODEL_LABELS.get(key, key),
            "run_count": 0,
            "success_run_count": 0,
            "tasks": set(),
            "success_tasks": set(),
            "tokens_total": 0,
            "tokens_by_task": {},
            "tokens_by_success_task": {},
        }
        for key in CODEX_MODEL_KEYS
    }
    known_keys = set(CODEX_MODEL_KEYS)

    for project in project_reports:
        run_bucket = (project.get("runs") or {}).get("all", [])
        if not run_bucket:
            run_bucket = (project.get("runs") or {}).get("recent", [])
        for run in run_bucket:
            key = run_model_key(run)
            if not key or key not in known_keys:
                continue
            tokens = run_token_total(run)
            status = str(run.get("status") or "")
            task_id = str(run.get("task_id") or "").strip()
            row = metrics[key]
            row["run_count"] += 1
            row["tasks"].add(task_id or str(run.get("run_id") or len(row["tasks"])))
            if status == "success" or safe_int(run.get("exit_code")) == 0:
                row["success_run_count"] += 1
                if task_id:
                    row["success_tasks"].add(task_id)
                    row["tokens_by_success_task"][task_id] = row["tokens_by_success_task"].get(task_id, 0) + tokens
            if task_id:
                row["tokens_by_task"][task_id] = row["tokens_by_task"].get(task_id, 0) + tokens
            row["tokens_total"] += tokens

    rows: list[dict[str, Any]] = []
    for key in CODEX_MODEL_KEYS:
        row = metrics[key]
        row.update(parse_limit_model_metrics(limit_estimates, key))
        task_ids: set[str] = set(row["tasks"])
        success_task_ids: set[str] = set(row["success_tasks"])
        task_count = len(task_ids)
        success_task_count = len(success_task_ids)
        task_tokens = row["tokens_by_task"]
        success_tokens = row["tokens_by_success_task"]
        tokens_total = safe_int(row["tokens_total"])
        avg_tokens_per_task = round(tokens_total / task_count, 1) if task_count else None
        success_tokens_sum = sum(safe_int(value) for value in success_tokens.values())
        avg_tokens_per_success_task = round(success_tokens_sum / success_task_count, 1) if success_task_count else None
        success_run_count = safe_int(row["success_run_count"])
        avg_tokens_per_run = round(tokens_total / safe_int(row["run_count"]), 1) if row["run_count"] else None
        avg_tokens_per_success_run = round(success_tokens_sum / success_run_count, 1) if success_run_count else None
        rows.append({
            "model_key": key,
            "model_label": row["model_label"],
            "runs": safe_int(row["run_count"]),
            "runs_success": success_run_count,
            "tasks": task_count,
            "success_tasks": success_task_count,
            "tokens_total": tokens_total,
            "tokens_per_task": avg_tokens_per_task,
            "tokens_per_success_task": avg_tokens_per_success_task,
            "tokens_per_run": avg_tokens_per_run,
            "tokens_per_success_run": avg_tokens_per_success_run,
            "estimate_5h_per_run": row.get("est_5h_per_run"),
            "estimate_5h_runs": row.get("est_5h_runs"),
            "estimate_5h_samples": row.get("est_5h_samples"),
            "estimate_weekly_per_run": row.get("est_weekly_per_run"),
            "estimate_weekly_runs": row.get("est_weekly_runs"),
            "estimate_weekly_samples": row.get("est_weekly_samples"),
        })
    rows.sort(key=lambda item: item["model_key"])
    return rows


def build_task_size_analytics(project_reports: list[dict[str, Any]]) -> list[dict[str, Any]]:
    buckets: dict[str, dict[str, Any]] = {}
    for project in project_reports:
        task_by_id = {str(task.get("id")): task for task in project.get("tasks", []) if isinstance(task, dict)}
        for task in task_by_id.values():
            size = str(task.get("size") or "-")
            row = buckets.setdefault(size, {
                "size": size,
                "tasks_total": 0,
                "tasks_completed": 0,
                "runs_total": 0,
                "runs_success": 0,
                "tokens_total": 0,
                "projects": set(),
            })
            row["tasks_total"] += 1
            if task.get("bucket") == "completed":
                row["tasks_completed"] += 1
            row["projects"].add(project.get("project_id"))
        for run in (project.get("runs") or {}).get("recent", []):
            task = task_by_id.get(str(run.get("task_id"))) or {}
            size = str(task.get("size") or run.get("task_size") or run.get("complexity") or "-")
            row = buckets.setdefault(size, {
                "size": size,
                "tasks_total": 0,
                "tasks_completed": 0,
                "runs_total": 0,
                "runs_success": 0,
                "tokens_total": 0,
                "projects": set(),
            })
            row["runs_total"] += 1
            if run.get("status") == "success" or safe_int(run.get("exit_code")) == 0:
                row["runs_success"] += 1
            row["tokens_total"] += run_token_total(run)
            row["projects"].add(project.get("project_id"))
    order = {"XS": 0, "S": 1, "S-M": 2, "M": 3, "L": 4, "XL": 5, "-": 99}
    rows = []
    for row in buckets.values():
        total = safe_int(row.get("tasks_total"))
        completed = safe_int(row.get("tasks_completed"))
        tokens = safe_int(row.get("tokens_total"))
        runs = safe_int(row.get("runs_total"))
        row = dict(row)
        row["completion_percent"] = round((completed / total) * 100, 1) if total else None
        row["tokens_per_success_run"] = round(tokens / safe_int(row.get("runs_success")), 1) if tokens and row.get("runs_success") else None
        row["tokens_per_run"] = round(tokens / runs, 1) if tokens and runs else None
        row["projects"] = sorted(str(item) for item in row.get("projects", set()) if item)
        rows.append(row)
    rows.sort(key=lambda item: (order.get(str(item.get("size")), 50), str(item.get("size"))))
    return rows


def build_automation_worktree_plan(
    registry_path: Path | None,
    worktree_root: Path,
    *,
    check_remote: bool = False,
) -> dict[str, Any]:
    if registry_path is None:
        return {
            "schema_version": "1.0",
            "available": False,
            "reason": "Project registry is required for automation worktree planning",
            "projects": [],
        }
    try:
        report = automation_worktree_planner.build_report(registry_path, worktree_root, check_remote=check_remote)
    except Exception as exc:
        return {
            "schema_version": "1.0",
            "available": False,
            "reason": f"automation worktree plan failed: {exc}",
            "projects": [],
        }
    report["available"] = True
    report["remote_checked"] = check_remote
    return report


def automation_worktree_plan_summary(plan: dict[str, Any]) -> dict[str, Any]:
    projects = [item for item in plan.get("projects") or [] if isinstance(item, dict)]
    remote_check_required_count = 0
    attention_project_ids: set[str] = set()
    for item in projects:
        project_id = str(item.get("project_id") or "")
        blockers = [str(value) for value in item.get("blockers") or []]
        remote_access = item.get("remote_access") if isinstance(item.get("remote_access"), dict) else {}
        remote_check_required = bool(
            item.get("action") == "clone_and_set_automation_path"
            and not bool(remote_access.get("checked"))
        )
        if remote_check_required:
            remote_check_required_count += 1
        if blockers or remote_check_required:
            attention_project_ids.add(project_id)
    blocked_count = safe_int(plan.get("blocked_count"))
    readiness = plan.get("credential_readiness") if isinstance(plan.get("credential_readiness"), dict) else {}
    return {
        "available": plan.get("available"),
        "remote_checked": plan.get("remote_checked", False),
        "needs_action_count": safe_int(plan.get("needs_action_count")),
        "blocked_count": blocked_count,
        "remote_check_required_count": remote_check_required_count,
        "attention_count": len(attention_project_ids),
        "worktree_root": plan.get("worktree_root"),
        "credential_readiness_reason": readiness.get("reason") if readiness else None,
        "credential_blocked_projects": readiness.get("credential_blocked_projects") if isinstance(readiness.get("credential_blocked_projects"), list) else [],
    }


def workspace_control_descriptors() -> list[dict[str, Any]]:
    return [
        {
            "id": "workspace.doctor.scan",
            "label": "Workspace Doctor scan",
            "mode": "dry_run",
            "command_bus_action": "project.health",
            "required_params": ["project_id"],
            "raw_shell_allowed": False,
        },
        {
            "id": "project.rebuild.plan",
            "label": "Project rebuild plan",
            "mode": "dry_run",
            "command_bus_action": "project.rebuild.plan",
            "required_params": ["project_id", "level"],
            "raw_shell_allowed": False,
        },
        {
            "id": "workspace.cleanup.plan",
            "label": "Workspace cleanup plan",
            "mode": "dry_run",
            "command_bus_action": "project.cleanup.plan",
            "required_params": ["project_id"],
            "raw_shell_allowed": False,
        },
        {
            "id": "documentation.check",
            "label": "Documentation impact check",
            "mode": "dry_run",
            "command_bus_action": "documentation.check",
            "required_params": ["project_id"],
            "raw_shell_allowed": False,
        },
    ]


def latest_controller_worktree_payload(controller: dict[str, Any] | None) -> dict[str, Any] | None:
    reports = []
    if isinstance(controller, dict):
        reports = [item for item in controller.get("recent") or [] if isinstance(item, dict)]
        latest = controller.get("latest")
        if isinstance(latest, dict):
            reports.insert(0, latest)
    for report in reports:
        if str(report.get("mode") or "") != "worktrees":
            continue
        for result in report.get("results") or []:
            if not isinstance(result, dict):
                continue
            parsed = result.get("parsed_json")
            if isinstance(parsed, dict) and (isinstance(parsed.get("projects"), list) or isinstance(parsed.get("results"), list)):
                return parsed
    return None


def worktree_plan_item_is_ready(item: dict[str, Any]) -> bool:
    return (
        str(item.get("action") or "") == "none"
        and bool(item.get("command_root_is_git_worktree"))
        and not list(item.get("blockers") or [])
    )


def overlay_worktree_plan_from_controller(plan: dict[str, Any], controller: dict[str, Any] | None) -> dict[str, Any]:
    payload = latest_controller_worktree_payload(controller)
    if not payload:
        return plan
    result = dict(plan)
    payload_projects = {
        str(item.get("project_id") or ""): item
        for item in (payload.get("projects") or payload.get("results") or [])
        if isinstance(item, dict)
    }
    payload_remote_checked = bool(payload.get("remote_checked", result.get("remote_checked")))
    projects = []
    for item in result.get("projects") or []:
        if not isinstance(item, dict):
            continue
        overlay = payload_projects.get(str(item.get("project_id") or ""))
        if not isinstance(overlay, dict) or worktree_plan_item_is_ready(item):
            projects.append(item)
            continue
        updated = dict(item)
        for field in ("remote_access", "blockers", "proposed_path_exists", "proposed_path_is_git_worktree"):
            if field in overlay:
                updated[field] = overlay[field]
        if payload_remote_checked and "remote_access" not in overlay:
            blockers = [str(value) for value in overlay.get("blockers") or []]
            updated["remote_access"] = {
                "checked": True,
                "ok": not blockers,
                "reason": blockers[0] if blockers else "remote_branch_accessible",
            }
        projects.append(updated)
    result["projects"] = projects
    overlay_applied = any(
        isinstance(item, dict)
        and not worktree_plan_item_is_ready(item)
        and str(item.get("project_id") or "") in payload_projects
        for item in plan.get("projects") or []
    )
    if overlay_applied:
        result["remote_checked"] = payload_remote_checked
        for field in ("host_auth_probe", "credential_readiness"):
            if field in payload:
                result[field] = payload[field]
    blocked_count = sum(1 for item in projects if isinstance(item, dict) and list(item.get("blockers") or []))
    result["blocked_count"] = blocked_count
    result["needs_action_count"] = sum(
        1 for item in projects if isinstance(item, dict) and str(item.get("action") or "") != "none"
    )
    result["ready_count"] = sum(1 for item in projects if isinstance(item, dict) and str(item.get("action") or "") == "none")
    if not blocked_count and not result["needs_action_count"]:
        result["credential_readiness"] = {
            "ok": True,
            "reason": "credentials_not_required_or_accessible",
            "credential_blocked_projects": [],
            "secret_values_reported": False,
        }
    return result


def _public_name(value: Any, fallback: str = "vps") -> str:
    text = str(value or "").strip()
    if not text:
        return fallback
    text = text.replace("\x00", "")
    text = re.sub(r"[^0-9A-Za-z ._-]", "-", text)
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return fallback
    return text[:64] or fallback


def _dedupe_paths(paths: list[Path | None]) -> list[Path]:
    items: list[Path] = []
    seen: set[str] = set()
    for item in paths:
        if item is None:
            continue
        normalized = str(item.expanduser())
        if normalized in seen:
            continue
        seen.add(normalized)
        items.append(item.expanduser())
    return items


def _normalized_host(value: Any) -> str:
    return str(value or "").strip().lower()


def _resolve_relative_path(base: Path | None, raw_path: Any) -> Path | None:
    if not isinstance(raw_path, str):
        return None
    candidate = raw_path.strip()
    if not candidate:
        return None
    path = Path(candidate).expanduser()
    if path.is_absolute() or base is None:
        return path
    return base / path


def _load_json_dict(path: Path) -> dict[str, Any] | None:
    payload, error = load_json(path)
    if error or not isinstance(payload, dict):
        return None
    return payload


def _collect_project_access_data(project: dict[str, Any], runtime_root: Path) -> dict[str, set[str]]:
    service = project.get("service") if isinstance(project.get("service"), dict) else {}
    project_id = str(project.get("project_id") or "").strip()
    if not project_id:
        return {}
    local_path = Path(str(project.get("local_path") or "")).expanduser() if project.get("local_path") else Path()
    project_access_paths: list[Path] = []
    explicit_project_access = service.get("project_access_path") if isinstance(service, dict) else None
    if isinstance(explicit_project_access, str) and explicit_project_access.strip():
        explicit = _resolve_relative_path(local_path if local_path.exists() else None, explicit_project_access)
        runtime_candidate = runtime_root / explicit_project_access
        if explicit is not None:
            project_access_paths.append(explicit)
        if runtime_candidate != explicit:
            project_access_paths.append(runtime_candidate)
    project_access_paths.append(local_path / "runtime" / "agent-control" / "project-access.local.json")
    project_access_paths = _dedupe_paths(project_access_paths)

    access_by_host: dict[str, set[str]] = {}
    for path in project_access_paths:
        payload = _load_json_dict(path)
        if not payload:
            continue
        for raw in payload.get("projects", []) if isinstance(payload, dict) else []:
            if not isinstance(raw, dict):
                continue
            if str(raw.get("project_id") or "") != project_id:
                continue
            if bool(raw.get("enabled", True)) is False:
                continue
            vps_access = raw.get("vps_access")
            if not isinstance(vps_access, dict):
                continue
            host = _normalized_host(vps_access.get("host"))
            if not host:
                continue
            access_by_host.setdefault(host, set()).update({project_id})
    return access_by_host


def _collect_vps_inventory_entries(project: dict[str, Any], runtime_root: Path) -> list[dict[str, Any]]:
    service = project.get("service") if isinstance(project.get("service"), dict) else {}
    local_path = Path(str(project.get("local_path") or "")).expanduser() if project.get("local_path") else Path()
    inventory_candidates: list[Path] = [
        local_path / "runtime" / "agent-control" / "vps_inventory.local.json",
        runtime_root / "agent-control" / "vps_inventory.local.json",
    ]
    explicit_inventory = service.get("vps_inventory_path") if isinstance(service, dict) else None
    if isinstance(explicit_inventory, str) and explicit_inventory.strip():
        explicit = _resolve_relative_path(local_path if local_path.exists() else None, explicit_inventory)
        runtime_candidate = runtime_root / explicit_inventory
        if explicit is not None:
            inventory_candidates.append(explicit)
        if runtime_candidate != explicit:
            inventory_candidates.append(runtime_candidate)
        access_dir = Path(explicit_inventory).parent
        if access_dir:
            inventory_candidates.append(local_path / access_dir / "vps_inventory.local.json")
    inventory_candidates = _dedupe_paths(inventory_candidates)

    entries: list[dict[str, Any]] = []
    for path in inventory_candidates:
        payload = _load_json_dict(path)
        if not payload:
            continue
        rows = payload.get("vps") if isinstance(payload, dict) else None
        if not isinstance(rows, list):
            continue
        for row in rows:
            if not isinstance(row, dict):
                continue
            vps_id = str(row.get("vps_id") or row.get("id") or row.get("host") or "").strip()
            if not vps_id:
                continue
            entries.append(row | {"_source_path": str(path)})
    return entries


def _normalize_vps_status(value: Any) -> str:
    normalized = str(value or "").strip().lower()
    if normalized in {"healthy", "online", "ok", "active", "ready"}:
        return "healthy"
    if normalized in {"warning", "elevated", "degraded"}:
        return "elevated"
    if normalized in {"down", "offline", "unreachable", "failed", "critical"}:
        return "unreachable"
    if normalized in {"stale", "outdated", "missing", "unknown"}:
        return "stale"
    if normalized == "" and value is not None:
        return str(value).strip() or "stale"
    return "stale"


def _vps_detail_rows(items: Any, status: str, checked_at: str, row_type: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    normalized_status = _normalize_vps_status(status)
    for raw in (items or []):
        if len(rows) >= VPS_FLEET_MAX_DETAIL_ROWS:
            break
        if not isinstance(raw, str):
            continue
        label = _public_name(raw, f"{row_type}-{len(rows) + 1}")
        rows.append({
            "name": label,
            "role": row_type,
            "status": normalized_status,
            "checked_at": checked_at,
        })
    return rows


def _collect_vps_telemetry(vps: dict[str, Any], observed_at: str, fallback_status: str) -> dict[str, Any]:
    status = _normalize_vps_status(fallback_status)
    telemetry = vps.get("telemetry") if isinstance(vps.get("telemetry"), dict) else {}
    probe_payload = vps.get("probe") if isinstance(vps.get("probe"), dict) else {}
    payload = telemetry if isinstance(telemetry, dict) else probe_payload
    if not payload:
        status = _normalize_vps_status(vps.get("status") or fallback_status)
        if status != "unreachable":
            status = "stale"
        return {
            "status": status,
            "observed_at": observed_at,
            "uptime_seconds": None,
            "load_1m": None,
            "load_5m": None,
            "load_15m": None,
            "cpu_percent": None,
            "memory_percent": None,
            "disk_percent": None,
            "network_rx_bps": None,
            "network_tx_bps": None,
            "process_count": None,
        }

    if "status" in payload:
        status = _normalize_vps_status(payload.get("status"))
    if "observed_at" in payload and isinstance(payload.get("observed_at"), str):
        observed_at = payload.get("observed_at")
    parsed_time = parse_datetime(observed_at)
    if parsed_time is not None:
        cutoff = dt.datetime.now(tz=LOCAL_TZ) - dt.timedelta(minutes=VPS_FLEET_STALE_MINUTES_DEFAULT)
        if status != "unreachable" and parsed_time < cutoff:
            status = "stale"

    uptime_seconds = safe_int_or_none(payload.get("uptime_seconds"))
    load_1m = safe_float(payload.get("load_1m"))
    load_5m = safe_float(payload.get("load_5m"))
    load_15m = safe_float(payload.get("load_15m"))
    cpu_percent = safe_float(payload.get("cpu_percent"))
    memory_percent = safe_float(payload.get("memory_percent"))
    disk_percent = safe_float(payload.get("disk_percent"))
    network_rx_bps = safe_int_or_none(payload.get("network_rx_bps"))
    network_tx_bps = safe_int_or_none(payload.get("network_tx_bps"))
    process_count = safe_int_or_none(payload.get("process_count"))

    if status in {"healthy", "elevated"}:
        missing_required = any(item is None for item in (load_1m, load_5m, load_15m, cpu_percent, memory_percent, disk_percent, process_count, uptime_seconds))
        if missing_required:
            status = "stale"
    if status == "healthy" and any(item is not None and item >= 90 for item in (cpu_percent, memory_percent, disk_percent)):
        status = "elevated"

    return {
        "status": status,
        "observed_at": observed_at,
        "uptime_seconds": uptime_seconds,
        "load_1m": load_1m,
        "load_5m": load_5m,
        "load_15m": load_15m,
        "cpu_percent": cpu_percent,
        "memory_percent": memory_percent,
        "disk_percent": disk_percent,
        "network_rx_bps": network_rx_bps,
        "network_tx_bps": network_tx_bps,
        "process_count": process_count,
    }


def _collect_disk_pressure_summary(vps: dict[str, Any], telemetry: dict[str, Any]) -> dict[str, Any]:
    """Expose only safe diagnostic state for an elevated Control disk."""

    raw = vps.get("disk_pressure") if isinstance(vps.get("disk_pressure"), dict) else {}
    pressure = raw.get("pressure") if isinstance(raw.get("pressure"), dict) else {}
    capacity = pressure.get("capacity") if isinstance(pressure.get("capacity"), dict) else {}
    inodes = pressure.get("inodes") if isinstance(pressure.get("inodes"), dict) else {}
    disk_percent = safe_float(telemetry.get("disk_percent"))
    raw_state = str(raw.get("state") or raw.get("status") or "").strip().lower()
    active = bool(raw.get("active")) or raw_state in {"elevated", "critical", "attention_required"}
    if not active and disk_percent is not None and disk_percent >= CONTROL_DISK_PRESSURE_WARNING_PERCENT:
        active = True
        raw_state = "critical" if disk_percent >= 95.0 else "elevated"
    state = raw_state if raw_state in {"normal", "elevated", "critical", "unknown", "partial"} else ("elevated" if active else "normal")
    recovery_plan = raw.get("recovery_plan") if isinstance(raw.get("recovery_plan"), dict) else {}
    report_status = "available" if raw else "telemetry_threshold_only"
    if raw and raw.get("complete") is False:
        report_status = "partial"
    return {
        "active": active,
        "task_id": CONTROL_DISK_PRESSURE_TASK_ID,
        "state": state,
        "report_status": report_status,
        "observed_at": str(raw.get("generated_at") or raw.get("observed_at") or telemetry.get("observed_at") or ""),
        "capacity_state": str(capacity.get("state") or "unknown"),
        "inode_state": str(inodes.get("state") or "unknown"),
        "estimated_recoverable_bytes": safe_int_or_none(recovery_plan.get("estimated_recoverable_bytes")),
        "human_approval_required": True,
    }


def collect_vps_fleet(runtime_root: Path, projects: list[dict[str, Any]]) -> dict[str, Any]:
    observed_at = utc_now()
    if not projects:
        return {
            "schema_version": VPS_FLEET_SCHEMA_VERSION,
            "observed_at": observed_at,
            "source": VPS_FLEET_SOURCE,
            "inventory_count": 0,
            "freshness_status": "unavailable",
            "servers": [],
        }

    runtime_root = runtime_root.expanduser()
    project_name_by_id: dict[str, str] = {
        str(item.get("project_id") or ""): _public_name(item.get("name") or item.get("project_id"), str(item.get("project_id") or "project"))
        for item in projects
    }
    allowed_project_ids = {str(item.get("project_id") or "") for item in projects if str(item.get("project_id") or "")}
    access_by_host: dict[str, set[str]] = {}
    for project in projects:
        for host, allowed in _collect_project_access_data(project, runtime_root).items():
            access_by_host.setdefault(host, set()).update(allowed)

    inventory_rows: list[dict[str, Any]] = []
    for project in projects:
        inventory_rows.extend(_collect_vps_inventory_entries(project, runtime_root))

    merged: dict[str, dict[str, Any]] = {}
    for row in inventory_rows:
        vps_id = str(row.get("vps_id") or row.get("id") or row.get("host") or "").strip()
        if not vps_id:
            continue
        host = _normalized_host(row.get("host"))
        normalized = merged.get(vps_id)
        if normalized is None:
            normalized = {
                "vps_id": vps_id,
                "label": _public_name(row.get("label") or row.get("name") or vps_id, vps_id),
                "role": _public_name(row.get("role") or "VPS", "VPS"),
                "host": host,
                "host_list": set(),
                "capabilities": [
                    _public_name(item, f"capability-{index}") for index, item in enumerate(row.get("capabilities", []), start=1)
                    if isinstance(item, str) and item.strip()
                ],
                "services": [
                    _public_name(item, f"service-{index}") for index, item in enumerate(row.get("services", []), start=1)
                    if isinstance(item, str) and item.strip()
                ],
                "allowed_project_ids": [str(item) for item in row.get("allowed_project_ids", []) if str(item).strip()],
                "status_hint": row.get("status"),
                "telemetry": row.get("telemetry") if isinstance(row.get("telemetry"), dict) else {}
            }
            merged[vps_id] = normalized
        elif isinstance(normalized.get("allowed_project_ids"), list):
            normalized["allowed_project_ids"].extend(str(item) for item in row.get("allowed_project_ids", []) if str(item).strip())

        if host:
            normalized["host_list"].add(host)

    servers: list[dict[str, Any]] = []
    for row in list(merged.values())[:VPS_FLEET_MAX_SERVERS]:
        server_id = row.get("vps_id")
        if not isinstance(server_id, str):
            continue
        host = str(row.get("host") or "").strip().lower()
        approved_project_ids: list[str] = []
        allowed_ids = {str(item).strip() for item in row.get("allowed_project_ids", []) if str(item).strip()}
        approved_project_ids.extend(sorted(allowed_ids & allowed_project_ids))
        approved_project_ids.extend(sorted((access_by_host.get(host, set()) - set(approved_project_ids))))
        if not approved_project_ids:
            continue

        telemetry = _collect_vps_telemetry(row, observed_at, str(row.get("status_hint") or "stale"))
        disk_pressure = _collect_disk_pressure_summary(row, telemetry)
        checked_at = telemetry["observed_at"]
        host_rows = [
            {
                "name": project_name_by_id.get(project_id, project_id),
                "role": "project",
                "status": telemetry["status"],
                "checked_at": checked_at,
            }
            for project_id in approved_project_ids[:VPS_FLEET_MAX_DETAIL_ROWS]
        ]
        nodes = _vps_detail_rows(row.get("capabilities"), telemetry["status"], checked_at, "node")
        if not nodes:
            nodes = [
                {
                    "name": f"{_public_name(row.get('role'), 'node')}-{_public_name(server_id, 'vps')}",
                    "kind": "vm",
                    "status": telemetry["status"],
                    "checked_at": checked_at,
                }
            ]
        services = _vps_detail_rows(row.get("services"), telemetry["status"], checked_at, "service")
        if not services:
            services = [
                {
                    "name": _public_name(item, "service"),
                    "role": "service",
                    "status": telemetry["status"],
                    "checked_at": checked_at,
                }
                for item in [row.get("role")]
                if isinstance(item, str) and item.strip()
            ]

        servers.append({
            "id": server_id,
            "label": _public_name(row.get("label") or server_id, server_id),
            "role": _public_name(row.get("role") or "VPS", "VPS"),
            "project_ids": sorted(set(approved_project_ids)),
            "status": telemetry["status"],
            "observed_at": checked_at,
            "uptime_seconds": telemetry["uptime_seconds"],
            "load_1m": telemetry["load_1m"],
            "load_5m": telemetry["load_5m"],
            "load_15m": telemetry["load_15m"],
            "cpu_percent": telemetry["cpu_percent"],
            "memory_percent": telemetry["memory_percent"],
            "disk_percent": telemetry["disk_percent"],
            "network_rx_bps": telemetry["network_rx_bps"],
            "network_tx_bps": telemetry["network_tx_bps"],
            "process_count": telemetry["process_count"],
            "disk_pressure": disk_pressure,
            "hosts": host_rows[:VPS_FLEET_MAX_DETAIL_ROWS],
            "nodes": [row for row in nodes if isinstance(row, dict)][:VPS_FLEET_MAX_DETAIL_ROWS],
            "services": [
                {
                    "name": _public_name(item.get("name"), f"service-{index}"),
                    "role": item.get("role", "service"),
                    "status": item.get("status", telemetry["status"]),
                    "checked_at": checked_at,
                }
                for index, item in enumerate(services, start=1)
                if isinstance(item, dict)
            ],
        })

    if not servers:
        freshness_status = "unavailable"
    elif any(server.get("status") == "unreachable" for server in servers):
        freshness_status = "partial"
    elif any(server.get("status") == "elevated" for server in servers):
        freshness_status = "partial"
    elif any(server.get("status") == "stale" for server in servers):
        freshness_status = "stale"
    elif any(
        value is None
        for server in servers
        for value in (server.get("cpu_percent"), server.get("memory_percent"), server.get("disk_percent"))
    ):
        freshness_status = "partial"
    else:
        freshness_status = "fresh"

    return {
        "schema_version": VPS_FLEET_SCHEMA_VERSION,
        "observed_at": observed_at,
        "source": VPS_FLEET_SOURCE,
        "inventory_count": len(servers),
        "freshness_status": freshness_status,
        "servers": servers,
    }


def build_snapshot(
    runtime_root: Path,
    registry_path: Path | None,
    *,
    resource_activity_window_minutes: int = SYSTEM_RESOURCE_ACTIVITY_WINDOW_MINUTES_DEFAULT,
    codex_limit_max_age_minutes: int | None = CODEX_LIMIT_STALE_MINUTES_DEFAULT,
    automation_worktree_root: Path | None = None,
) -> dict[str, Any]:
    projects, registry_warnings = load_projects(registry_path)
    runs = scan_runs(runtime_root)
    human_packets = scan_human_needed(runtime_root)
    codex_limits = scan_codex_limits(runtime_root)
    codex_limit_estimates = scan_codex_limit_estimates(runtime_root)
    codex_limit_consensus = build_codex_limit_consensus(codex_limits, codex_limit_max_age_minutes)
    automation_status = scan_automation_status(runtime_root)
    automation_controller = scan_automation_controller_reports(runtime_root)
    controller_summary = automation_controller_summary(automation_controller)
    command_bus_state = scan_command_bus(runtime_root)
    migration_goal = scan_migration_goals(runtime_root)
    automation_progress = scan_automation_progress(runtime_root) + scan_running_codex_workers()
    activity_log = scan_activity_log(runtime_root)
    automation_status["progress"] = automation_progress
    automation_worktree_plan = build_automation_worktree_plan(
        registry_path,
        automation_worktree_root or (runtime_root / "automation-worktrees"),
        check_remote=False,
    )
    automation_worktree_plan = overlay_worktree_plan_from_controller(automation_worktree_plan, automation_controller)
    try:
        workspace_doctor = project_doctor.build_report(registry_path, devops_root=None) if registry_path else {
            "schema_version": "1.0",
            "mode": "workspace_doctor",
            "project_count": 0,
            "attention_count": 0,
            "projects": [],
        }
    except Exception as exc:
        workspace_doctor = {
            "schema_version": "1.0",
            "mode": "workspace_doctor",
            "project_count": 0,
            "attention_count": 0,
            "projects": [],
            "error": str(exc),
        }
    worktree_plan_by_project = {
        str(item.get("project_id") or ""): item
        for item in automation_worktree_plan.get("projects") or []
        if isinstance(item, dict)
    }
    project_reports = [summarize_project(project, runs, human_packets, automation_status, runtime_root) for project in projects]
    bridge_summary = automation_bridge_summary(project_reports)
    migration_goal_by_project = {
        str(item.get("project_id") or ""): item
        for item in migration_goal.get("goals") or []
        if isinstance(item, dict)
    }
    runner_readiness_overlay = overlay_snapshot_runner_readiness({"projects": project_reports, "summary": {}}, runtime_root)
    runner_readiness_snapshot = (
        runner_readiness_overlay.get("runner_readiness")
        if isinstance(runner_readiness_overlay.get("runner_readiness"), dict)
        else None
    )
    runner_readiness_summary_fields = (
        runner_readiness_overlay.get("summary")
        if isinstance(runner_readiness_overlay.get("summary"), dict)
        else {}
    )
    for project_report in project_reports:
        project_attention_plan = build_queue_attention_plan(
            project_report,
            worktree_plan_by_project.get(str(project_report.get("project_id") or "")),
        )
        project_report["queue_attention_plan"] = project_attention_plan
        project_worktree_plan = worktree_plan_by_project.get(str(project_report.get("project_id") or ""))
        if isinstance(project_worktree_plan, dict):
            project_report["automation_worktree_plan"] = project_worktree_plan
        doctor_project = next(
            (
                item
                for item in workspace_doctor.get("projects", [])
                if isinstance(item, dict) and str(item.get("project_id") or "") == str(project_report.get("project_id") or "")
            ),
            None,
        )
        if isinstance(doctor_project, dict):
            project_report["workspace_doctor"] = doctor_project
        goal_project = migration_goal_by_project.get(str(project_report.get("project_id") or ""))
        if isinstance(goal_project, dict):
            project_report["migration_goal"] = goal_project
        annotate_project_effective_attention(project_report)
    resource_activity = build_resource_activity_state(
        runtime_root,
        runs,
        automation_status,
        automation_progress,
        activity_log,
        window_minutes=resource_activity_window_minutes,
    )
    resource_load = scan_system_resources() if resource_activity.get("is_active") else {
        "cpu": {"current": None, "average": None, "unit": "%"},
        "gpu": {"current": None, "average": None, "unit": "%"},
        "vram": {"current": None, "average": None, "unit": "%"},
        "ram": {"current": None, "average": None, "unit": "%"},
        "cpu_temp": {"current": None, "average": None, "unit": "°C"},
        "gpu_temp": {"current": None, "average": None, "unit": "°C"},
    }

    totals = {"completed": 0, "waiting": 0, "active": 0, "human": 0, "task_packet": 0, "postponed": 0, "total": 0}
    added_totals = {"today": 0, "week": 0, "with_known_created_at": 0}
    completed_recent_totals = {"today": 0, "week": 0, "with_known_at": 0}
    recent_task_outcome_totals = {
        "window_hours": 24,
        "completed": 0,
        "failed": 0,
        "completed_with_known_at": 0,
        "attempted_tasks_with_known_at": 0,
    }
    role_outcome_totals = zero_role_outcome_stats()
    task_origin_totals = zero_task_origin_breakdown()
    task_origin_active_totals = zero_task_origin_breakdown()
    local_llm_pre_worker_items: list[dict[str, Any]] = []
    for project in project_reports:
        for key in totals:
            totals[key] += safe_int((project.get("counts") or {}).get(key))
        for key in added_totals:
            added_totals[key] += safe_int((project.get("added_counts") or {}).get(key))
        for key in completed_recent_totals:
            completed_recent_totals[key] += safe_int((project.get("completed_recent") or {}).get(key))
        project_recent_outcomes = project.get("task_outcomes_last_24h") or {}
        for key in ("completed", "failed", "completed_with_known_at", "attempted_tasks_with_known_at"):
            recent_task_outcome_totals[key] += safe_int(project_recent_outcomes.get(key))
        project_role_outcomes = (project.get("counts") or {}).get("role_outcomes", {})
        if isinstance(project_role_outcomes, dict):
            for role in project_role_outcomes:
                role_stats = project_role_outcomes.get(role)
                if not isinstance(role_stats, dict):
                    continue
                if role not in role_outcome_totals:
                    continue
                for outcome in RUN_OUTCOME_KEYS:
                    source = role_stats.get(outcome)
                    if not isinstance(source, dict):
                        continue
                    role_bucket = role_outcome_totals[role]
                    target = role_bucket.get(outcome)
                    if not isinstance(target, dict):
                        continue
                    target["total"] += safe_int(source.get("total"))
                    target["today"] += safe_int(source.get("today"))
                    target["week"] += safe_int(source.get("week"))
                    target["last_24h"] += safe_int(source.get("last_24h"))
        project_origins = project.get("task_origin_breakdown") if isinstance(project.get("task_origin_breakdown"), dict) else {}
        for key in task_origin_totals:
            task_origin_totals[key] += safe_int(project_origins.get(key))
        project_active_origins = project.get("task_origin_active_breakdown") if isinstance(project.get("task_origin_active_breakdown"), dict) else {}
        for key in task_origin_active_totals:
            task_origin_active_totals[key] += safe_int(project_active_origins.get(key))
        local_llm_pre_worker_items.append(project.get("local_llm_pre_worker") or {})
    active_human_packets = [packet for packet in human_packets if is_active_human_packet(packet)]
    human_needed_open = len(active_human_packets)
    human_attention_breakdown = zero_human_attention_breakdown()
    human_attention_effective_breakdown = zero_human_attention_breakdown()
    queue_attention_by_lane = {
        "human": 0,
        "environment": 0,
        "architect": 0,
        "dispatcher": 0,
        "worker": 0,
        "integrator": 0,
        "finalizer": 0,
        "unknown": 0,
    }
    queue_attention_plan = zero_queue_attention_plan()
    suppressed_attention = {"total": 0, "by_reason": {}}
    human_attention_total = 0
    infra_blocked_attention = 0
    worker_host_blocked_candidates = 0
    projects_need_human = 0
    raw_projects_need_human = 0
    for project in project_reports:
        breakdown = project.get("needs_human_breakdown")
        if not isinstance(breakdown, dict):
            continue
        for key in human_attention_breakdown:
            human_attention_breakdown[key] += safe_int(breakdown.get(key))
        merge_project_attention_lanes(queue_attention_by_lane, project)
        project_attention_plan = project.get("queue_attention_plan")
        if isinstance(project_attention_plan, dict):
            merge_queue_attention_plan(queue_attention_plan, project_attention_plan)
        project_suppressed = project.get("suppressed_attention") if isinstance(project.get("suppressed_attention"), dict) else {}
        suppressed_attention["total"] += safe_int(project_suppressed.get("total"))
        project_suppressed_reasons = project_suppressed.get("by_reason") if isinstance(project_suppressed.get("by_reason"), dict) else {}
        for reason, count in project_suppressed_reasons.items():
            key = str(reason or "unknown")
            suppressed_attention["by_reason"][key] = safe_int(suppressed_attention["by_reason"].get(key)) + safe_int(count)
        project_worktree_plan = project.get("automation_worktree_plan")
        if not isinstance(project_worktree_plan, dict):
            project_worktree_plan = None
        suppress_failed_role_state = isinstance(project.get("worker_host_blocker"), dict) or queue_plan_has_worker_host_blocker(
            project_attention_plan if isinstance(project_attention_plan, dict) else None
        )
        project_human_attention = effective_human_attention_total(
            breakdown,
            project_attention_plan if isinstance(project_attention_plan, dict) else None,
            project_worktree_plan,
            suppress_failed_role_state=suppress_failed_role_state,
        )
        project_effective_breakdown = effective_human_attention_breakdown(
            breakdown,
            project_attention_plan if isinstance(project_attention_plan, dict) else None,
            project_worktree_plan,
            suppress_failed_role_state=suppress_failed_role_state,
        )
        for key in human_attention_effective_breakdown:
            human_attention_effective_breakdown[key] += safe_int(project_effective_breakdown.get(key))
        human_attention_total += project_human_attention
        worker_host_blocked_candidates += safe_int(project.get("worker_host_blocked_candidates"))
        infra_blocked_attention += infra_blocked_attention_total(
            breakdown,
            project_attention_plan if isinstance(project_attention_plan, dict) else None,
            project_worktree_plan,
        )
        if safe_int(project.get("needs_human")) > 0:
            raw_projects_need_human += 1
        if project_human_attention > 0:
            projects_need_human += 1
    worktree_active_total = sum(safe_int((project.get("counts") or {}).get("worktree_active")) for project in project_reports)
    active_total = sum(safe_int((project.get("counts") or {}).get("active_total")) for project in project_reports)
    totals["active_queue_total"] = live_backlog_total({**totals, "active_total": active_total})
    totals["terminal_queue_total"] = safe_int(totals.get("completed")) + safe_int(totals.get("postponed"))
    summary_warnings = list(registry_warnings)
    if resource_activity.get("is_active") and all(
        value is None
        for value in (
            (resource_load.get("cpu") or {}).get("current"),
            (resource_load.get("gpu") or {}).get("current"),
            (resource_load.get("vram") or {}).get("current"),
            (resource_load.get("ram") or {}).get("current"),
            (resource_load.get("cpu_temp") or {}).get("current"),
            (resource_load.get("gpu_temp") or {}).get("current"),
        )
    ):
        summary_warnings.append(
            {
                "code": "resource_stats_unavailable",
                "severity": "warning",
                "message": (
                    "Не удалось собрать метрики аппаратных ресурсов. "
                    "Проверьте доступность /proc или установите psutil/nvidia-smi на хосте."
                ),
            }
        )

    snapshot = {
        "schema_version": "1.0",
        "generated_at": utc_now(),
        "registry_path": str(registry_path.expanduser()) if registry_path else None,
        "runtime_root": str(runtime_root.expanduser()),
        "summary": {
            "project_count": len(project_reports),
            "task_counts": totals,
            "worktree_active_total": worktree_active_total,
            "active_total": active_total,
            "tasks_added": added_totals,
            "tasks_completed_recent": completed_recent_totals,
            "task_outcomes_last_24h": recent_task_outcome_totals,
            "task_flow_last_24h": task_flow_last_24h(role_outcome_totals, recent_task_outcome_totals),
            "runs_total": len(runs),
            "runs_failed": len([run for run in runs if safe_int(run.get("exit_code")) != 0 or run.get("status") == "failed"]),
            "human_needed_open": human_needed_open,
            "human_attention_total": human_attention_total,
            "infra_blocked_attention_total": infra_blocked_attention,
            "worker_host_blocked_candidates": worker_host_blocked_candidates,
            "raw_projects_need_human": raw_projects_need_human,
            "human_attention_breakdown": human_attention_breakdown,
            "human_attention_effective_breakdown": human_attention_effective_breakdown,
            "queue_attention_by_lane": queue_attention_by_lane,
            "queue_attention_plan": queue_attention_plan,
            "suppressed_attention": suppressed_attention,
            "role_outcomes": role_outcome_totals,
            "task_origin_breakdown": task_origin_totals,
            "task_origin_active_breakdown": task_origin_active_totals,
            "task_work_class_active_breakdown": task_work_class_breakdown(task_origin_active_totals),
            "local_llm_pre_worker": merge_local_llm_pre_worker_stats(local_llm_pre_worker_items),
            "resource_load": resource_load,
            "resource_activity": resource_activity,
            "automation_worktree_plan": automation_worktree_plan_summary(automation_worktree_plan),
            "workspace_doctor": {
                "project_count": workspace_doctor.get("project_count", 0),
                "attention_count": workspace_doctor.get("attention_count", 0),
                "average_health_score": workspace_doctor.get("average_health_score"),
                "error": workspace_doctor.get("error"),
            },
            "workspace_controls": {
                "raw_shell_allowed": False,
                "controls": workspace_control_descriptors(),
            },
            "migration_goal": {
                "goal_count": migration_goal.get("goal_count", 0),
                "can_apply_count": migration_goal.get("can_apply_count", 0),
                "stale_count": migration_goal.get("stale_count", 0),
                "by_decision": migration_goal.get("by_decision", {}),
            },
            "automation_controller": controller_summary,
            "automation_bridge": bridge_summary,
            "command_bus": {
                "available": command_bus_state.get("available"),
                "command_count": command_bus_state.get("command_count", 0),
                "counts": command_bus_state.get("counts", {}),
                "raw_counts": command_bus_state.get("raw_counts", {}),
                "unresolved_counts": command_bus_state.get("unresolved_counts", {}),
                "latest_command_id": (command_bus_state.get("latest") or {}).get("command_id") if isinstance(command_bus_state.get("latest"), dict) else None,
                "latest_mode": (command_bus_state.get("latest") or {}).get("mode") if isinstance(command_bus_state.get("latest"), dict) else None,
                "latest_state": (command_bus_state.get("latest") or {}).get("state") if isinstance(command_bus_state.get("latest"), dict) else None,
                "latest_updated_at": (command_bus_state.get("latest") or {}).get("updated_at") if isinstance(command_bus_state.get("latest"), dict) else None,
                "latest_no_op_reason": ((command_bus_state.get("latest") or {}).get("parsed_summary") or {}).get("no_op_reason") if isinstance(command_bus_state.get("latest"), dict) and isinstance((command_bus_state.get("latest") or {}).get("parsed_summary"), dict) else None,
            },
            "codex_limit_min_remaining": min(
                [item.get("consensus_percent") for item in codex_limit_consensus if item.get("consensus_percent") is not None],
                default=None,
            ),
            "projects_need_human": projects_need_human,
        },
        "projects": project_reports,
        "vps_fleet": collect_vps_fleet(runtime_root, projects),
        "automation_bridge": bridge_summary,
        "human_needed": active_human_packets,
        "codex_limits": codex_limits,
        "codex_limit_consensus": codex_limit_consensus,
        "codex_limit_estimates": codex_limit_estimates,
        "model_cost_analytics": model_cost_analytics(project_reports, limit_estimates=codex_limit_estimates),
        "task_size_analytics": build_task_size_analytics(project_reports),
        "automation_status": automation_status,
        "automation_controller": automation_controller,
        "command_bus": command_bus_state,
        "migration_goal": migration_goal,
        "automation_worktree_plan": automation_worktree_plan,
        "automation_progress": automation_progress,
        "activity_log": activity_log,
        "warnings": summary_warnings,
    }
    if runner_readiness_snapshot:
        snapshot["runner_readiness"] = runner_readiness_snapshot
        snapshot["summary"].update(runner_readiness_summary_fields)
    apply_derived_human_needed(snapshot)
    return snapshot


def init_db(db_path: Path) -> None:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS dashboard_snapshots (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              generated_at TEXT NOT NULL,
              project_count INTEGER NOT NULL,
              task_total INTEGER NOT NULL,
              task_completed INTEGER NOT NULL,
              task_waiting INTEGER NOT NULL,
              task_active INTEGER NOT NULL,
              task_human INTEGER NOT NULL,
              runs_total INTEGER NOT NULL,
              runs_failed INTEGER NOT NULL,
              payload_json TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS project_snapshots (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              generated_at TEXT NOT NULL,
              project_id TEXT NOT NULL,
              name TEXT NOT NULL,
              task_total INTEGER NOT NULL,
              task_completed INTEGER NOT NULL,
              task_waiting INTEGER NOT NULL,
              task_active INTEGER NOT NULL,
              task_human INTEGER NOT NULL,
              runs_total INTEGER NOT NULL,
              runs_failed INTEGER NOT NULL,
              needs_human INTEGER NOT NULL,
              payload_json TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS dashboard_task_first_seen (
              project_id TEXT NOT NULL,
              task_id TEXT NOT NULL,
              first_seen_at TEXT NOT NULL,
              baseline INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (project_id, task_id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS system_resource_samples (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              generated_at TEXT NOT NULL,
              cpu_percent REAL,
              gpu_percent REAL,
              vram_percent REAL,
              ram_percent REAL,
              cpu_temp_c REAL,
              gpu_temp_c REAL
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_system_resource_samples_generated_at ON system_resource_samples (generated_at)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_project_snapshots_project_id_id ON project_snapshots (project_id, id)"
        )
        _ensure_system_resource_sample_columns(conn)
        conn.commit()
    finally:
        conn.close()


def _ensure_system_resource_sample_columns(conn: sqlite3.Connection) -> None:
    try:
        existing = {row[1] for row in conn.execute("PRAGMA table_info(system_resource_samples)").fetchall()}
    except sqlite3.Error:
        return
    if "cpu_temp_c" not in existing:
        conn.execute("ALTER TABLE system_resource_samples ADD COLUMN cpu_temp_c REAL")
    if "gpu_temp_c" not in existing:
        conn.execute("ALTER TABLE system_resource_samples ADD COLUMN gpu_temp_c REAL")


def clean_old_system_resource_samples(conn: sqlite3.Connection, retention_hours: int) -> None:
    if retention_hours <= 0:
        return
    cutoff = utc_timestamp_text((now_utc_dt() - dt.timedelta(hours=retention_hours)))
    conn.execute("DELETE FROM system_resource_samples WHERE generated_at < ?", (cutoff,))


def _format_resource_for_insert(resource_load: dict[str, Any] | None) -> tuple[float | None, float | None, float | None, float | None, float | None, float | None]:
    return (
        safe_float((resource_load or {}).get("cpu", {}).get("current")),
        safe_float((resource_load or {}).get("gpu", {}).get("current")),
        safe_float((resource_load or {}).get("vram", {}).get("current")),
        safe_float((resource_load or {}).get("ram", {}).get("current")),
        safe_float((resource_load or {}).get("cpu_temp", {}).get("current")),
        safe_float((resource_load or {}).get("gpu_temp", {}).get("current")),
    )


def record_system_resource_sample(
    db_path: Path,
    resource_load: dict[str, Any] | None,
    *,
    sample_interval_seconds: int = SYSTEM_RESOURCE_SAMPLE_INTERVAL_SECONDS_DEFAULT,
    retention_hours: int = SYSTEM_RESOURCE_SAMPLE_RETENTION_HOURS_DEFAULT,
) -> bool:
    init_db(db_path)
    now_dt = now_utc_dt()
    generated_at = utc_timestamp_text(now_dt)
    current = _format_resource_for_insert(resource_load)
    if all(value is None for value in current):
        return False

    conn = sqlite3.connect(db_path)
    try:
        if sample_interval_seconds > 0:
            row = conn.execute("SELECT generated_at FROM system_resource_samples ORDER BY id DESC LIMIT 1").fetchone()
            if row:
                previous = parse_datetime(row[0])
                if previous and (now_dt - previous).total_seconds() < sample_interval_seconds:
                    clean_old_system_resource_samples(conn, retention_hours)
                    conn.commit()
                    return False
        conn.execute(
            """
            INSERT INTO system_resource_samples (
              generated_at, cpu_percent, gpu_percent, vram_percent, ram_percent, cpu_temp_c, gpu_temp_c
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (generated_at, current[0], current[1], current[2], current[3], current[4], current[5]),
        )
        clean_old_system_resource_samples(conn, retention_hours)
        conn.commit()
    finally:
        conn.close()
    return True


def clean_old_dashboard_snapshots(
    conn: sqlite3.Connection,
    dashboard_retention: int,
    project_retention: int,
) -> None:
    if dashboard_retention > 0:
        conn.execute(
            """
            DELETE FROM dashboard_snapshots
            WHERE id NOT IN (
              SELECT id FROM dashboard_snapshots ORDER BY id DESC LIMIT ?
            )
            """,
            (dashboard_retention,),
        )
    if project_retention > 0:
        conn.execute(
            """
            DELETE FROM project_snapshots
            WHERE id IN (
              SELECT id
              FROM (
                SELECT
                  id,
                  ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY id DESC) AS retention_rank
                FROM project_snapshots
              )
              WHERE retention_rank > ?
            )
            """,
            (project_retention,),
        )


def store_snapshot(
    db_path: Path,
    snapshot: dict[str, Any],
    *,
    dashboard_retention: int = DASHBOARD_SNAPSHOT_RETENTION_DEFAULT,
    project_retention: int = PROJECT_SNAPSHOT_RETENTION_DEFAULT,
) -> None:
    init_db(db_path)
    summary = snapshot.get("summary") or {}
    counts = summary.get("task_counts") or {}
    generated_at = str(snapshot.get("generated_at"))
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(
            """
            INSERT INTO dashboard_snapshots (
              generated_at, project_count, task_total, task_completed, task_waiting,
              task_active, task_human, runs_total, runs_failed, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                generated_at,
                safe_int(summary.get("project_count")),
                safe_int(counts.get("total")),
                safe_int(counts.get("completed")),
                safe_int(counts.get("waiting")),
                safe_int(counts.get("active")),
                safe_int(counts.get("human")),
                safe_int(summary.get("runs_total")),
                safe_int(summary.get("runs_failed")),
                json.dumps(snapshot, ensure_ascii=False),
            ),
        )
        for project in snapshot.get("projects", []):
            project_counts = project.get("counts") or {}
            project_runs = project.get("runs") or {}
            conn.execute(
                """
                INSERT INTO project_snapshots (
                  generated_at, project_id, name, task_total, task_completed,
                  task_waiting, task_active, task_human, runs_total, runs_failed,
                  needs_human, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    generated_at,
                    project.get("project_id"),
                    project.get("name"),
                    safe_int(project_counts.get("total")),
                    safe_int(project_counts.get("completed")),
                    safe_int(project_counts.get("waiting")),
                    safe_int(project_counts.get("active")),
                    safe_int(project_counts.get("human")),
                    safe_int(project_runs.get("total")),
                    safe_int(project_runs.get("failed")),
                    safe_int(project.get("needs_human")),
                    json.dumps(project, ensure_ascii=False),
                ),
            )
        clean_old_dashboard_snapshots(conn, dashboard_retention, project_retention)
        conn.commit()
    finally:
        conn.close()


def augment_added_counts_from_history(db_path: Path, snapshot: dict[str, Any]) -> None:
    init_db(db_path)
    current_seen_at = parse_datetime(snapshot.get("generated_at")) or dt.datetime.now(LOCAL_TZ)
    current_seen_text = current_seen_at.isoformat()
    try:
        with sqlite3.connect(db_path) as conn:
            known_count = safe_int(conn.execute("SELECT COUNT(*) FROM dashboard_task_first_seen").fetchone()[0])
            baseline_mode = known_count == 0
            for project in snapshot.get("projects", []):
                project_id = str(project.get("project_id", ""))
                for task in project.get("tasks", []):
                    task_id = str(task.get("id", ""))
                    if project_id and task_id:
                        conn.execute(
                            """
                            INSERT OR IGNORE INTO dashboard_task_first_seen (
                              project_id, task_id, first_seen_at, baseline
                            ) VALUES (?, ?, ?, ?)
                            """,
                            (project_id, task_id, current_seen_text, 1 if baseline_mode else 0),
                        )
            rows = conn.execute(
                "SELECT project_id, task_id, first_seen_at, baseline FROM dashboard_task_first_seen"
            ).fetchall()
    except sqlite3.Error:
        rows = []

    first_seen = {
        (str(project_id), str(task_id)): {
            "at": parse_datetime(first_seen_at) or current_seen_at,
            "baseline": bool(safe_int(baseline)),
        }
        for project_id, task_id, first_seen_at, baseline in rows
    }
    today = current_seen_at.date()
    week_start = today - dt.timedelta(days=today.weekday())
    totals = {"today": 0, "week": 0, "with_known_created_at": 0}

    for project in snapshot.get("projects", []):
        project_id = str(project.get("project_id", ""))
        counts = {"today": 0, "week": 0, "with_known_created_at": 0}
        for task in project.get("tasks", []):
            task_id = str(task.get("id", ""))
            if not project_id or not task_id:
                continue
            explicit_created = parse_datetime(task.get("created_at"))
            counts["with_known_created_at"] += 1
            seen = first_seen.get((project_id, task_id), {"at": current_seen_at, "baseline": True})
            created = explicit_created or seen["at"]
            if explicit_created is None and seen["baseline"]:
                continue
            created_date = created.date()
            if created_date == today:
                counts["today"] += 1
            if created_date >= week_start:
                counts["week"] += 1
        project["added_counts"] = counts
        for key in totals:
            totals[key] += safe_int(counts.get(key))

    summary = snapshot.setdefault("summary", {})
    summary["tasks_added"] = totals


def average(values: list[float | None]) -> float | None:
    normalized = [value for value in values if value is not None]
    if not normalized:
        return None
    return round(sum(normalized) / len(normalized), 1)


def augment_system_resource_load(
    db_path: Path,
    snapshot: dict[str, Any],
    *,
    retention_hours: int = SYSTEM_RESOURCE_SAMPLE_RETENTION_HOURS_DEFAULT,
) -> None:
    init_db(db_path)
    summary = snapshot.setdefault("summary", {})
    resource_load = summary.setdefault("resource_load", {})
    conn: sqlite3.Connection | None = None
    try:
        conn = sqlite3.connect(db_path)
        latest = conn.execute(
                """
                SELECT cpu_percent, gpu_percent, vram_percent, ram_percent, cpu_temp_c, gpu_temp_c, generated_at
                FROM system_resource_samples
                ORDER BY id DESC LIMIT 1
                """
            ).fetchone()
        rows = None
        if retention_hours > 0:
            cutoff = utc_timestamp_text((now_utc_dt() - dt.timedelta(hours=retention_hours)))
            rows = conn.execute(
                    """
                    SELECT cpu_percent, gpu_percent, vram_percent, ram_percent, cpu_temp_c, gpu_temp_c
                    FROM system_resource_samples
                    WHERE generated_at >= ?
                    ORDER BY id DESC
                    """,
                    (cutoff,),
                ).fetchall()
        else:
            rows = conn.execute(
                    """
                    SELECT cpu_percent, gpu_percent, vram_percent, ram_percent, cpu_temp_c, gpu_temp_c
                    FROM system_resource_samples
                    ORDER BY id DESC
                    """
                ).fetchall()
    except sqlite3.Error:
        rows = []
        latest = None
    finally:
        if conn is not None:
            conn.close()
    if not isinstance(resource_load, dict):
        resource_load = {}
        summary["resource_load"] = resource_load
    for key in ("cpu", "gpu", "vram", "ram", "cpu_temp", "gpu_temp"):
        if not isinstance(resource_load.get(key), dict):
            resource_load[key] = {}
    latest_row = latest if isinstance(latest, tuple) and len(latest) == 7 else None
    latest_at = latest_row[6] if latest_row else None
    for key, index in (
        ("cpu", 0),
        ("gpu", 1),
        ("vram", 2),
        ("ram", 3),
        ("cpu_temp", 4),
        ("gpu_temp", 5),
    ):
        resource_load[key]["average"] = average([row[index] for row in rows])
        if resource_load[key].get("current") is None and latest_row:
            resource_load[key]["current"] = latest_row[index]
    resource_load["updated_at"] = latest_at


def run_resource_sampler_loop(
    runtime_root: Path,
    db_path: Path,
    *,
    sampler_interval_seconds: int,
    sample_interval_seconds: int,
    retention_hours: int,
    activity_window_minutes: int,
) -> None:
    if sampler_interval_seconds <= 0:
        return

    while True:
        start_ts = time.monotonic()
        try:
            runs = scan_runs(runtime_root)
            automation_status = scan_automation_status(runtime_root)
            automation_progress = scan_automation_progress(runtime_root) + scan_running_codex_workers()
            activity_log = scan_activity_log(runtime_root)
            resource_activity = build_resource_activity_state(
                runtime_root,
                runs,
                automation_status,
                automation_progress,
                activity_log,
                window_minutes=activity_window_minutes,
            )
            if resource_activity.get("is_active"):
                record_system_resource_sample(
                    db_path,
                    scan_system_resources(),
                    sample_interval_seconds=sample_interval_seconds,
                    retention_hours=retention_hours,
                )
            else:
                init_db(db_path)
                with sqlite3.connect(db_path) as conn:
                    clean_old_system_resource_samples(conn, retention_hours)
        except Exception:
            # sampler must remain resilient in background mode
            pass

        elapsed = time.monotonic() - start_ts
        delay = sampler_interval_seconds - elapsed
        if delay < 1:
            delay = 1
        time.sleep(delay)


def css() -> str:
    return """
    :root { color-scheme: light; --ink:#18202a; --muted:#647181; --line:#d7dde4; --bg:#f7f8fa; --panel:#fff; --good:#277a49; --wait:#926c14; --active:#2267a8; --human:#b33a3a; --dashboard-width: min(100%, 1320px); }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: Arial, sans-serif; background: var(--bg); color: var(--ink); }
    header { border-bottom: 1px solid var(--line); background: var(--panel); position: sticky; top: 0; z-index: 10; }
    .bar { width: var(--dashboard-width); margin: 0 auto; padding: 14px 18px; display: flex; gap: 14px; align-items: center; justify-content: space-between; }
    .brand { font-size: 18px; font-weight: 700; white-space: nowrap; }
    nav { display: flex; gap: 8px; overflow-x: auto; }
    a { color: #194f86; text-decoration: none; }
    .navlink, .button { min-height: 40px; display: inline-flex; align-items: center; border: 1px solid var(--line); background: #fff; border-radius: 6px; padding: 8px 10px; white-space: nowrap; }
    .navlink.primary { background: #eef4ff; border-color: #9db8df; font-weight: 700; }
    main { width: var(--dashboard-width); margin: 0 auto; padding: 20px 18px 38px; }
    .topline { display: flex; align-items: end; justify-content: space-between; gap: 18px; margin-bottom: 18px; }
    h1 { font-size: 28px; line-height: 1.15; margin: 0; }
    h2 { font-size: 18px; margin: 26px 0 10px; }
    .muted { color: var(--muted); font-size: 13px; }
    .metrics { width: 100%; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
    .metric, .project-card, .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 10px; }
    .metric b { display: block; font-size: 20px; line-height: 1.15; overflow-wrap: anywhere; }
    .metric span { color: var(--muted); font-size: 13px; }
    .summary-panel { width: 100%; display: block; background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; }
    .summary-panel h2 { margin: 0 0 8px; font-size: 16px; }
    .summary-strip { width: 100%; display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 8px; align-items: stretch; }
    .summary-item { min-width: 0; background: #fbfcfd; border: 1px solid var(--line); border-radius: 8px; padding: 8px; }
    .summary-item b { display: block; font-size: 18px; line-height: 1.1; }
    .summary-item span { display: block; color: var(--muted); font-size: 11px; line-height: 1.2; min-height: 14px; }
    .summary-item.completed { border-left: 4px solid var(--good); } .summary-item.waiting { border-left: 4px solid var(--wait); } .summary-item.active { border-left: 4px solid var(--active); } .summary-item.human { border-left: 4px solid var(--human); } .summary-item.task_packet { border-left: 4px solid #7b4aa8; } .summary-item.postponed { border-left: 4px solid #5f6875; }
    .metrics.wide { width: 100%; grid-template-columns: repeat(7, max-content); justify-content: start; align-items: stretch; gap: 8px; overflow-x: auto; padding-bottom: 2px; }
    .metrics.wide .metric { padding: 8px 8px; min-width: 96px; }
    .metrics.wide .metric b { font-size: 18px; }
    .metrics.wide .metric span { display: block; font-size: 12px; line-height: 1.15; }
    .projects-wrap { margin-top: 10px; }
    .projects { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
    .projects.collapsed .project-card:nth-child(n+5) { display: none; }
    .project-card { padding: 12px; }
    .project-card h2 { margin: 0 0 6px; font-size: 17px; }
    .project-title { display: flex; align-items: baseline; gap: 8px; white-space: nowrap; overflow: hidden; }
    .project-title .project-name { font-size: 17px; font-weight: 700; color: var(--ink); white-space: nowrap; }
    .project-title .project-repo { color: var(--muted); font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
    .project-card p { margin: 10px 0 0; }
    .project-card .muted { line-height: 1.25; }
    .statusline { width: 100%; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 10px 0; }
    .pill { border-radius: 8px; padding: 7px 8px; font-size: 12px; text-align: center; border: 1px solid var(--line); background:#fbfcfd; min-width: 0; overflow-wrap: anywhere; min-height: 44px; display: flex; align-items: center; justify-content: center; }
    .pill.completed { border-left: 4px solid var(--good); } .pill.waiting { border-left: 4px solid var(--wait); } .pill.active { border-left: 4px solid var(--active); } .pill.human { border-left: 4px solid var(--human); } .pill.task_packet { border-left: 4px solid #7b4aa8; } .pill.postponed { border-left: 4px solid #5f6875; }
    .show-projects { margin-top: 10px; }
    .completed { color: var(--good); } .waiting { color: var(--wait); } .active { color: var(--active); } .human { color: var(--human); } .task_packet { color: #7b4aa8; } .postponed { color: #5f6875; }
    .limit-bars { width: 100%; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
    .load-bars { width: 100%; display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 10px; }
    .limit-bar { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 12px; min-width: 0; }
    .limit-bar .track { height: 10px; background: #e8edf2; border-radius: 999px; overflow: hidden; margin: 8px 0 4px; }
    .limit-bar .fill { height: 100%; background: var(--good); border-radius: 999px; }
    .limit-bar.low .fill { background: var(--human); }
    .limit-bar.mid .fill { background: var(--wait); }
    .limit-bar.partial .fill { background: #f0a000; }
    .limit-bar.alert .fill { background: #d63c3c; }
    .limit-bar.resource-low .fill { background: var(--good); }
    .limit-bar.resource-medium .fill { background: #f0a000; }
    .limit-bar.resource-high .fill { background: #f28c00; }
    .limit-bar.resource-critical .fill { background: #d63c3c; }
    .limit-bar.resource-unknown .fill { background: #93a1b2; }
    .limit-bar .source-line { margin-top: 6px; }
    .source-line { color: var(--muted); font-size: 12px; line-height: 1.35; }
    .limit-bar b { font-size: 18px; }
    .scroll-panel { width: 100%; max-height: 420px; overflow: auto; border: 1px solid var(--line); border-radius: 8px; background: var(--panel); }
    .scroll-panel.compact { max-height: 300px; }
    .run-controls { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
    .run-controls .button { cursor: pointer; background: #eef4fb; border-color: #c6d3e1; }
    .run-feedback { margin-top: 6px; min-height: 18px; }
    .run-feedback.ok { color: var(--good); }
    .run-feedback.error { color: var(--human); }
    .scroll-panel table { width: 100%; border: 0; border-radius: 0; }
    .scroll-panel thead th { position: sticky; top: 0; z-index: 1; }
    .dashboard-chat { display: grid; gap: 10px; }
    .dashboard-chat-embed {
      width: 100%;
      min-height: 700px;
      height: 78vh;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--panel);
    }
    .dashboard-chat-log { height: 320px; overflow: auto; border: 1px solid var(--line); border-radius: 8px; padding: 10px; background: #fbfcfd; }
    .dashboard-chat-message { max-width: 82%; margin: 8px 0; padding: 9px 10px; border: 1px solid var(--line); border-radius: 8px; background: #fff; white-space: pre-wrap; overflow-wrap: anywhere; }
    .dashboard-chat-message.user { margin-left: auto; border-color: #9ab9d8; }
    .dashboard-chat-message.assistant { margin-right: auto; }
    .dashboard-chat-meta { margin-bottom: 4px; color: var(--muted); font-size: 11px; }
    .dashboard-chat-form { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; align-items: end; }
    .dashboard-chat-form textarea { min-height: 54px; max-height: 180px; resize: vertical; padding: 9px; border: 1px solid var(--line); border-radius: 8px; font: inherit; }
    .dashboard-chat-form button { cursor: pointer; }
    .dashboard-chat-progress { font-size: 12px; color: var(--muted); border: 1px dashed var(--line); border-radius: 8px; padding: 8px; background: #fbfcfd; }
    .dashboard-chat-tasks { border: 1px solid var(--line); border-radius: 8px; padding: 8px; background: #fff; max-height: 160px; overflow: auto; }
    .dashboard-chat-task { border: 1px solid var(--line); border-radius: 8px; padding: 8px; margin: 6px 0; background: #fff; }
    .dashboard-chat-task .muted { color: var(--muted); font-size: 11px; margin-top: 3px; }
    table { width: 100%; border-collapse: collapse; background: var(--panel); border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
    th, td { border-bottom: 1px solid var(--line); padding: 9px 10px; text-align: left; vertical-align: top; font-size: 14px; }
    th { background: #eef2f6; font-size: 12px; color: #4c5968; text-transform: uppercase; }
    tr:last-child td { border-bottom: 0; }
    code { background: #edf1f5; border-radius: 4px; padding: 2px 4px; }
    .warning { border-left: 4px solid var(--human); background: #fff7f7; padding: 10px 12px; margin: 8px 0; }
    .statistics-page { display: grid; min-width: 0; gap: 18px; }
    .statistics-page > *, .infrastructure-detail-grid, .infrastructure-detail-grid > div { min-width: 0; }
    .statistics-hero {
      position: relative;
      overflow: hidden;
      display: flex;
      align-items: end;
      justify-content: space-between;
      gap: 28px;
      padding: 30px 32px;
      border: 1px solid #dce6ef;
      border-radius: 20px;
      background:
        radial-gradient(circle at 92% 18%, rgba(69, 180, 162, .18), transparent 32%),
        linear-gradient(135deg, #f8fbfe 0%, #eef6f7 100%);
      box-shadow: 0 18px 46px rgba(34, 57, 78, .08);
    }
    .statistics-hero h1 { max-width: 760px; font-size: clamp(32px, 4vw, 54px); letter-spacing: -.04em; }
    .statistics-hero p { max-width: 680px; margin: 12px 0 0; color: #536576; font-size: 16px; line-height: 1.55; }
    .statistics-eyebrow { margin-bottom: 8px; color: #2a756d; font-size: 12px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
    .statistics-updated {
      min-width: 210px;
      padding: 14px 16px;
      border: 1px solid rgba(42, 117, 109, .18);
      border-radius: 14px;
      background: rgba(255, 255, 255, .76);
      color: #536576;
      font-size: 12px;
      backdrop-filter: blur(10px);
    }
    .statistics-updated strong { display: block; margin-top: 4px; color: var(--ink); font-size: 16px; }
    .statistics-host-panel {
      padding: 24px;
      border: 1px solid #dce3e9;
      border-radius: 20px;
      background: #fff;
      box-shadow: 0 16px 40px rgba(29, 49, 66, .07);
    }
    .statistics-task-grid { display: grid; grid-template-columns: 1.15fr 1.35fr .8fr 1.15fr; gap: 12px; margin-top: 22px; }
    .statistics-task-card { min-width: 0; padding: 18px; border: 1px solid #e0e6eb; border-radius: 15px; background: #fbfcfd; }
    .statistics-task-card.task-ready { border-top: 4px solid var(--good); }
    .statistics-task-card.task-preparation { border-top: 4px solid #7b4aa8; }
    .statistics-task-card.task-blocked { border-top: 4px solid var(--wait); }
    .statistics-task-card.task-active { border-top: 4px solid var(--active); }
    .statistics-task-card.task-recent { grid-column: 1 / -1; border-top: 4px solid #6e5aa7; }
    .statistics-task-label { display: block; color: #506273; font-size: 13px; font-weight: 800; }
    .statistics-task-card > strong { display: block; margin-top: 14px; font-size: 38px; line-height: 1; letter-spacing: -.04em; }
    .statistics-task-card > small, .statistics-task-outcomes small { display: block; margin-top: 7px; color: var(--muted); font-size: 11px; line-height: 1.35; }
    .statistics-task-breakdown { display: grid; gap: 6px; margin-top: 14px; }
    .statistics-task-breakdown span { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 6px 8px; border-radius: 8px; background: #fff7e5; color: #6e5b33; font-size: 11px; }
    .statistics-task-breakdown b { color: var(--ink); font-size: 13px; }
    .statistics-route-breakdown span { background: #edf8f3; color: #2a6c58; }
    .statistics-task-outcomes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }
    .statistics-task-outcomes > div { padding: 10px; border-radius: 10px; background: #edf8f1; }
    .statistics-task-outcomes strong { display: block; color: var(--good); font-size: 29px; line-height: 1; }
    .statistics-backlog-head { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-top: 24px; padding-top: 20px; border-top: 1px solid #e7ecef; }
    .statistics-backlog-head small { display: block; max-width: 720px; margin-top: 5px; color: var(--muted); font-size: 12px; line-height: 1.4; }
    .statistics-backlog-head > strong { font-size: 30px; line-height: 1; letter-spacing: -.035em; }
    .statistics-backlog-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }
    .statistics-backlog-card { min-width: 0; padding: 14px; border: 1px solid #e1e7eb; border-radius: 13px; background: #f8fafb; }
    .statistics-backlog-card span { display: block; color: #506273; font-size: 12px; font-weight: 800; }
    .statistics-backlog-card strong { display: block; margin-top: 10px; font-size: 27px; line-height: 1; }
    .statistics-backlog-card small { display: block; margin-top: 7px; color: var(--muted); font-size: 11px; line-height: 1.35; }
    .statistics-backlog-reconcile { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 12px; padding: 10px 12px; border-radius: 10px; color: #496475; background: #edf5f7; font-size: 11px; }
    .statistics-section-head { display: flex; align-items: start; justify-content: space-between; gap: 28px; }
    .statistics-section-head h2 { margin: 0; font-size: 25px; letter-spacing: -.02em; }
    .statistics-section-head p { margin: 7px 0 0; color: var(--muted); line-height: 1.45; }
    .statistics-host-state { min-width: 250px; text-align: right; }
    .statistics-host-state > span {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      padding: 7px 11px;
      border-radius: 999px;
      font-size: 12px;
      font-weight: 800;
    }
    .statistics-host-state > span::before { width: 8px; height: 8px; border-radius: 50%; background: currentColor; content: ""; }
    .statistics-state-active { color: #1d7253; background: #e8f7ef; }
    .statistics-state-idle { color: #56677a; background: #edf1f5; }
    .statistics-state-warning { color: #9a6910; background: #fff3d4; }
    .statistics-state-critical { color: #b12736; background: #fdebed; }
    .statistics-state-missing { color: #9a5d18; background: #fff3db; }
    .statistics-host-state small { display: block; max-width: 300px; margin-top: 8px; color: var(--muted); line-height: 1.35; }
    .statistics-resource-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-top: 22px; }
    .statistics-resource-card {
      min-width: 0;
      padding: 16px;
      border: 1px solid #e0e6eb;
      border-radius: 15px;
      background: #fbfcfd;
    }
    .statistics-resource-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
    .statistics-resource-label { color: #506273; font-size: 13px; font-weight: 800; letter-spacing: .02em; }
    .statistics-status { color: var(--muted); font-size: 11px; font-weight: 700; }
    .statistics-resource-value { display: block; margin-top: 18px; font-size: 31px; line-height: 1; letter-spacing: -.035em; }
    .statistics-resource-average { margin-top: 8px; color: var(--muted); font-size: 12px; }
    .statistics-track { height: 7px; margin-top: 15px; overflow: hidden; border-radius: 999px; background: #e5ebef; }
    .statistics-track span { display: block; height: 100%; border-radius: inherit; background: var(--good); }
    .statistics-resource-card.resource-medium .statistics-track span { background: #d59b19; }
    .statistics-resource-card.resource-high .statistics-track span { background: #df741c; }
    .statistics-resource-card.resource-critical .statistics-track span { background: #c83f45; }
    .statistics-resource-card.resource-unknown .statistics-track span { background: #9aa8b5; }
    .statistics-resource-card.resource-medium .statistics-status { color: #9a6910; }
    .statistics-resource-card.resource-high .statistics-status { color: #b0540f; }
    .statistics-resource-card.resource-critical .statistics-status { color: #b12736; }
    .statistics-resource-details { display: grid; grid-template-columns: minmax(0, .86fr) minmax(0, 1.14fr); gap: 12px; margin-top: 12px; }
    .statistics-thermal-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
    .statistics-gpu-card { padding: 16px; border: 1px solid #dfe6eb; border-radius: 15px; background: linear-gradient(145deg, #f8fafc, #f1f6f6); }
    .statistics-detail-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 18px; }
    .statistics-detail-grid div { min-width: 0; }
    .statistics-detail-grid span { display: block; color: var(--muted); font-size: 11px; }
    .statistics-detail-grid strong { display: block; margin-top: 4px; overflow-wrap: anywhere; font-size: 14px; }
    .statistics-processes { margin: 16px 0 0; color: var(--muted); font-size: 12px; line-height: 1.4; overflow-wrap: anywhere; }
    .statistics-storage-section { margin-top: 22px; padding-top: 20px; border-top: 1px solid #e7ecef; }
    .statistics-subsection-head { display: flex; align-items: end; justify-content: space-between; gap: 18px; }
    .statistics-subsection-head h3 { margin: 0; font-size: 19px; letter-spacing: -.015em; }
    .statistics-subsection-head p { margin: 5px 0 0; color: var(--muted); font-size: 12px; line-height: 1.4; }
    .statistics-subsection-head > span { color: var(--muted); font-size: 12px; font-weight: 800; }
    .statistics-storage-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 14px; }
    .statistics-storage-card { min-width: 0; padding: 16px; border: 1px solid #dfe6eb; border-radius: 15px; background: #fbfcfd; }
    .statistics-storage-card.resource-medium { border-top: 4px solid #d59b19; }
    .statistics-storage-card.resource-high { border-top: 4px solid #df741c; }
    .statistics-storage-card.resource-critical { border-top: 4px solid #c83f45; }
    .statistics-storage-card.resource-unknown { border-top: 4px solid #9aa8b5; }
    .statistics-storage-card.resource-medium .statistics-track span { background: #d59b19; }
    .statistics-storage-card.resource-high .statistics-track span { background: #df741c; }
    .statistics-storage-card.resource-critical .statistics-track span { background: #c83f45; }
    .statistics-storage-card.resource-unknown .statistics-track span { background: #9aa8b5; }
    .statistics-storage-model { display: block; max-width: 230px; margin-top: 4px; overflow: hidden; color: var(--muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
    .statistics-storage-capacity { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-top: 16px; }
    .statistics-storage-capacity strong { font-size: 26px; letter-spacing: -.03em; }
    .statistics-storage-capacity span { color: var(--muted); font-size: 11px; text-align: right; }
    .statistics-storage-io { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 16px; }
    .statistics-storage-io > div { min-width: 0; padding: 9px 10px; border-radius: 9px; background: #f0f5f6; }
    .statistics-storage-io span { display: block; color: var(--muted); font-size: 10px; }
    .statistics-storage-io strong { display: block; margin-top: 4px; overflow-wrap: anywhere; font-size: 13px; }
    .statistics-storage-mounts { margin-top: 13px; color: #536576; font-size: 10px; line-height: 1.4; overflow-wrap: anywhere; }
    .statistics-storage-source { margin-top: 10px; color: var(--muted); font-size: 10px; }
    .statistics-freshness { margin-top: 16px; padding-top: 14px; border-top: 1px solid #e7ecef; color: var(--muted); font-size: 12px; }
    .statistics-coming { padding: 24px; border: 1px dashed #cbd6de; border-radius: 18px; background: rgba(255,255,255,.55); }
    .statistics-coming h2 { margin: 0; font-size: 20px; }
    .statistics-coming p { max-width: 720px; margin: 8px 0 0; color: var(--muted); line-height: 1.5; }
    .statistics-section-action { display: flex; min-width: 220px; align-items: end; flex-direction: column; gap: 10px; color: var(--muted); font-size: 12px; text-align: right; }
    .statistics-vps-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 22px; }
    .statistics-vps-card, .statistics-limit-card { min-width: 0; padding: 17px; border: 1px solid #dfe6eb; border-radius: 15px; background: #fbfcfd; }
    .statistics-vps-card .statistics-resource-head small { display: block; margin-top: 4px; color: var(--muted); font-size: 11px; }
    .statistics-vps-status, .statistics-table-status { display: inline-flex; padding: 5px 8px; border-radius: 999px; font-size: 11px; font-weight: 800; white-space: nowrap; }
    .statistics-mini-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 18px; }
    .statistics-mini-metric { min-width: 0; }
    .statistics-mini-metric > div:first-child { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
    .statistics-mini-metric span { color: var(--muted); font-size: 11px; }
    .statistics-mini-metric strong { font-size: 14px; }
    .statistics-mini-metric .statistics-track { margin-top: 7px; }
    .statistics-track.resource-medium span { background: #d59b19; }
    .statistics-track.resource-high span { background: #df741c; }
    .statistics-track.resource-critical span { background: #c83f45; }
    .statistics-track.resource-unknown span { background: #9aa8b5; }
    .statistics-vps-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 16px; padding-top: 12px; border-top: 1px solid #e7ecef; color: var(--muted); font-size: 11px; }
    .statistics-vps-footer a { min-height: 40px; display: inline-flex; align-items: center; color: #286e69; font-weight: 700; text-decoration: none; }
    .statistics-development { margin-top: 18px; padding: 16px; border: 1px dashed #d2b676; border-radius: 14px; background: #fffaf0; }
    .statistics-development-label { display: inline-flex; margin-right: 8px; padding: 5px 8px; border-radius: 999px; color: #8a5e0d; background: #fff0c9; font-size: 11px; font-weight: 800; }
    .statistics-development p { margin: 9px 0 0; color: var(--muted); font-size: 12px; line-height: 1.45; }
    .statistics-limit-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 22px; }
    .statistics-limit-value { display: block; margin-top: 20px; font-size: 34px; letter-spacing: -.035em; }
    .statistics-limit-caption { display: block; margin-top: 4px; color: var(--muted); font-size: 12px; }
    .statistics-limit-card > small { display: block; margin-top: 10px; color: var(--muted); }
    .infrastructure-server { scroll-margin-top: 82px; }
    .infrastructure-metrics { max-width: 620px; margin-bottom: 22px; }
    .infrastructure-detail-grid { display: grid; gap: 18px; }
    .infrastructure-detail-grid h3 { margin: 0 0 8px; font-size: 16px; }
    .project-overview-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 22px; }
    .project-overview-card {
      min-width: 0;
      padding: 18px;
      border: 1px solid #dfe6eb;
      border-radius: 15px;
      background: #fbfcfd;
    }
    .project-overview-head { display: flex; align-items: start; justify-content: space-between; gap: 16px; }
    .project-overview-head h3 { margin: 0; font-size: 19px; letter-spacing: -.02em; }
    .project-overview-head p { margin: 5px 0 0; color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
    .project-state {
      display: inline-flex;
      flex: 0 0 auto;
      padding: 6px 9px;
      border-radius: 999px;
      font-size: 11px;
      font-weight: 800;
      white-space: nowrap;
    }
    .project-kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 16px; }
    .project-kpi { min-width: 0; padding: 10px; border-radius: 10px; background: #fff; }
    .project-kpi span { display: block; color: var(--muted); font-size: 10px; line-height: 1.25; }
    .project-kpi strong { display: block; margin-top: 5px; font-size: 22px; line-height: 1; }
    .project-card-note { min-height: 34px; margin: 14px 0 0; color: #536576; font-size: 11px; line-height: 1.45; }
    .project-actions { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 14px; }
    .project-page { display: grid; min-width: 0; gap: 18px; }
    .project-hero { align-items: center; }
    .project-hero .project-actions { margin-top: 18px; }
    .project-hero-meta { display: grid; min-width: 260px; gap: 8px; }
    .project-hero-meta > div { padding: 11px 13px; border: 1px solid rgba(42, 117, 109, .16); border-radius: 12px; background: rgba(255,255,255,.72); }
    .project-hero-meta span { display: block; color: var(--muted); font-size: 10px; }
    .project-hero-meta strong { display: block; margin-top: 4px; overflow-wrap: anywhere; font-size: 14px; }
    .project-page-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(320px, .8fr); gap: 12px; }
    .project-panel { padding: 22px; border: 1px solid #dce3e9; border-radius: 18px; background: #fff; box-shadow: 0 14px 34px rgba(29,49,66,.06); }
    .project-panel h2 { margin: 0; font-size: 22px; letter-spacing: -.02em; }
    .project-panel-lead { margin: 7px 0 0; color: var(--muted); font-size: 12px; line-height: 1.45; }
    .project-flow-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 9px; margin-top: 16px; }
    .project-flow-step { min-width: 0; padding: 13px; border-radius: 12px; background: #edf8f3; }
    .project-flow-step strong { display: block; color: var(--good); font-size: 25px; line-height: 1; }
    .project-flow-step span { display: block; margin-top: 6px; color: #506273; font-size: 10px; line-height: 1.3; }
    .project-attention-list { display: grid; gap: 9px; margin-top: 16px; }
    .project-attention-item { padding: 13px; border: 1px solid #e1e7eb; border-radius: 12px; background: #fbfcfd; }
    .project-attention-item header { display: flex; align-items: start; justify-content: space-between; gap: 10px; height: auto; padding: 0; border: 0; background: transparent; }
    .project-attention-item code { overflow-wrap: anywhere; font-size: 11px; }
    .project-attention-item .project-attention-kind { color: #8a5e0d; font-size: 10px; font-weight: 800; white-space: nowrap; }
    .project-attention-item h3 { margin: 8px 0 0; font-size: 14px; line-height: 1.35; }
    .project-attention-item p { margin: 6px 0 0; color: var(--muted); font-size: 11px; line-height: 1.4; }
    .project-attention-item a { min-height: 40px; display: inline-flex; align-items: center; margin-top: 9px; color: #286e69; font-size: 11px; font-weight: 800; text-decoration: none; }
    .project-empty { margin-top: 16px; padding: 16px; border: 1px dashed #cad6de; border-radius: 12px; color: var(--muted); background: #f8fafb; font-size: 12px; }
    .project-blocker { margin-top: 14px; padding: 12px 14px; border-radius: 12px; color: #76520f; background: #fff5dc; font-size: 12px; line-height: 1.45; }
    .project-task-link { min-height: 40px; display: inline-flex; align-items: center; color: inherit; text-decoration: none; }
    body[data-visual-variant="variant-02-compact"] { --dashboard-width: min(100%, 1480px); }
    body[data-visual-variant="variant-02-compact"] main { padding-top: 14px; }
    body[data-visual-variant="variant-02-compact"] .statistics-page,
    body[data-visual-variant="variant-02-compact"] .project-page { gap: 12px; }
    body[data-visual-variant="variant-02-compact"] .statistics-host-panel,
    body[data-visual-variant="variant-02-compact"] .project-panel { padding: 18px; }
    body[data-visual-variant="variant-02-compact"] .project-overview-card { padding: 14px; }
    body[data-visual-variant="variant-03-focus"] { --dashboard-width: min(100%, 1180px); }
    body[data-visual-variant="variant-03-focus"] .project-overview-grid { grid-template-columns: 1fr; }
    body[data-visual-variant="variant-03-focus"] .project-overview-card { padding: 22px; }
    body[data-visual-variant="variant-03-focus"] .project-card-note { min-height: 0; font-size: 12px; }
    body[data-visual-variant="variant-03-focus"] .project-state { padding: 8px 11px; font-size: 12px; }
    @media (max-width: 980px) {
      .statistics-task-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .statistics-backlog-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .statistics-resource-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .statistics-resource-details { grid-template-columns: 1fr; }
      .statistics-storage-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .statistics-vps-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .project-overview-grid, .project-page-grid { grid-template-columns: 1fr; }
    }
    @media (max-width: 760px) {
      .bar, .topline, .statistics-hero, .statistics-section-head { align-items: stretch; flex-direction: column; }
      .metrics, .metrics.wide, .limit-bars, .load-bars, .summary-strip, .projects, .dashboard-chat-form, .statistics-task-grid, .statistics-backlog-grid, .statistics-resource-grid, .statistics-thermal-grid, .statistics-detail-grid, .statistics-storage-grid, .statistics-vps-grid, .statistics-limit-grid { grid-template-columns: repeat(1, minmax(0, 1fr)); }
      .statistics-task-card.task-recent { grid-column: auto; }
      .statistics-task-outcomes { grid-template-columns: repeat(1, minmax(0, 1fr)); }
      .statistics-backlog-head, .statistics-backlog-reconcile, .statistics-subsection-head { align-items: start; flex-direction: column; }
      .summary-panel { display: block; }
      .metrics.wide { justify-content: stretch; overflow-x: visible; }
      .statusline { grid-template-columns: repeat(2, 1fr); }
      .projects.collapsed .project-card:nth-child(n+3) { display: none; }
      .dashboard-chat-embed { min-height: 560px; height: 66vh; }
      .statistics-hero, .statistics-host-panel { padding: 20px; border-radius: 16px; }
      .statistics-host-state { min-width: 0; text-align: left; }
      .statistics-host-state small { max-width: none; }
      .statistics-section-action { min-width: 0; align-items: start; text-align: left; }
      .statistics-mini-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
      .statistics-vps-footer { align-items: start; flex-direction: column; }
      .project-overview-head { align-items: stretch; flex-direction: column; }
      .project-state { align-self: flex-start; }
      .project-kpi-grid, .project-flow-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .project-hero-meta { min-width: 0; }
    }
    """


def compact_metrics_script() -> str:
    return """
<script>
(() => {
  const requestedVariant = new URLSearchParams(window.location.search).get('visual_variant') || 'variant-02-compact';
  const allowedVariants = new Set(['variant-01-calm', 'variant-02-compact', 'variant-03-focus']);
  document.body.dataset.visualVariant = allowedVariants.has(requestedVariant) ? requestedVariant : 'variant-01-calm';
  function compactMetricRows() {
    document.querySelectorAll('.metrics.wide').forEach((row) => {
      row.style.gridTemplateColumns = '';
      const cells = Array.from(row.querySelectorAll('.metric'));
      cells.forEach((cell) => { cell.style.width = ''; });
      if (window.innerWidth <= 760 || cells.length === 0) return;
      const maxWidth = Math.ceil(Math.max(...cells.map((cell) => cell.scrollWidth)));
      const width = Math.max(96, maxWidth);
      row.style.gridTemplateColumns = `repeat(${cells.length}, ${width}px)`;
    });
  }
  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(compactMetricRows, 80);
  });
  window.addEventListener('load', compactMetricRows);
  compactMetricRows();
  document.querySelectorAll('[data-toggle-projects]').forEach((button) => {
    const target = document.querySelector(button.getAttribute('data-toggle-projects'));
    if (!target) return;
    const update = () => {
      const collapsed = target.classList.contains('collapsed');
      button.textContent = collapsed ? 'Показать все проекты' : 'Свернуть проекты';
    };
    button.addEventListener('click', () => {
      target.classList.toggle('collapsed');
      update();
    });
    update();
  });

  document.querySelectorAll('[data-run-project]').forEach((button) => {
    const projectId = button.getAttribute('data-project-id');
    const mode = button.getAttribute('data-run-mode') || 'all';
    const label = button.textContent || '';
    button.addEventListener('click', async () => {
      const feedback = document.getElementById(`run-feedback-${projectId}`);
      const original = button.disabled;
      if (feedback) {
        feedback.className = 'run-feedback';
        feedback.textContent = 'Запускаем...';
      }
      button.disabled = true;
      document.querySelectorAll(`[data-run-project=\"${projectId}\"]`).forEach((item) => {
        item.disabled = true;
      });
      try {
        const response = await fetch(`/api/project/${projectId}/run`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ mode }),
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
          if (feedback) {
            feedback.className = 'run-feedback error';
            feedback.textContent = `${payload.error || 'Ошибка запуска'}: ${payload.message || response.statusText}`;
          }
          return;
        }
        if (feedback) {
          feedback.className = 'run-feedback ok';
          feedback.textContent = payload.message || `${label} запущен`;
        }
      } catch (error) {
        if (feedback) {
          feedback.className = 'run-feedback error';
          feedback.textContent = `Ошибка запуска: ${error.message || error}`;
        }
      } finally {
        button.disabled = original;
        document.querySelectorAll(`[data-run-project=\"${projectId}\"]`).forEach((item) => {
          item.disabled = false;
        });
        if (feedback && !feedback.textContent) {
          feedback.textContent = '';
          feedback.className = 'run-feedback';
        }
        setTimeout(() => {
          if (feedback) {
            feedback.textContent = '';
            feedback.className = 'run-feedback';
          }
        }, 8000);
      }
    });
  });

  document.querySelectorAll('[data-run-automation]').forEach((button) => {
    const action = button.getAttribute('data-run-automation');
    button.addEventListener('click', async () => {
      const feedback = document.getElementById('automation-feedback');
      const original = button.disabled;
      if (feedback) {
        feedback.className = 'run-feedback';
        feedback.textContent = 'Ставим команду...';
      }
      button.disabled = true;
      try {
        const response = await fetch(`/api/automation/${action}/run`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ apply: false }),
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
          if (feedback) {
            feedback.className = 'run-feedback error';
            feedback.textContent = `${payload.error || 'Ошибка команды'}: ${payload.message || response.statusText}`;
          }
          return;
        }
        if (feedback) {
          feedback.className = 'run-feedback ok';
          feedback.textContent = payload.message || 'Команда поставлена';
        }
      } catch (error) {
        if (feedback) {
          feedback.className = 'run-feedback error';
          feedback.textContent = `Ошибка команды: ${error.message || error}`;
        }
      } finally {
        button.disabled = original;
        setTimeout(() => {
          if (feedback) {
            feedback.textContent = '';
            feedback.className = 'run-feedback';
          }
        }, 8000);
      }
    });
  });

  const chatRoot = document.querySelector('[data-dashboard-chat]');
  if (chatRoot) {
    const log = chatRoot.querySelector('[data-chat-log]');
    const form = chatRoot.querySelector('[data-chat-form]');
    const textarea = chatRoot.querySelector('[data-chat-text]');
    const feedback = chatRoot.querySelector('[data-chat-feedback]');
    const send = chatRoot.querySelector('[data-chat-send]');
    const progress = chatRoot.querySelector('[data-chat-progress]');
    const taskList = chatRoot.querySelector('[data-chat-tasks]');
    const esc = (value) => String(value || '').replace(/[&<>"']/g, (ch) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
    const renderChat = (messages) => {
      if (!log) return;
      log.innerHTML = (messages || []).map((item) => `
        <article class="dashboard-chat-message ${esc(item.role)}">
          <div class="dashboard-chat-meta">${esc(item.role)} · ${esc(item.state)} · ${esc(item.created_at || '')}</div>
          <div>${esc(item.text)}</div>
        </article>
      `).join('');
      log.scrollTop = log.scrollHeight;
    };
    const renderTasks = (session, tasks) => {
      if (!taskList) return;
      const items = Array.isArray(tasks) ? tasks : [];
      const sorted = items.sort((a, b) => {
        const l = Number(a.order_index || 0);
        const r = Number(b.order_index || 0);
        if (l !== r) return l - r;
        return String(a.created_at || '').localeCompare(String(b.created_at || ''));
      });
      if (!sorted.length) {
        taskList.innerHTML = '<div class="dashboard-chat-task"><span class="muted">Шаги не заданы.</span></div>';
        return;
      }
      taskList.innerHTML = sorted.map((item) => {
        const status = esc(item.status || 'pending');
        const eta = item.eta_minutes == null ? '—' : `${Number(item.eta_minutes)} мин`;
        return `<div class="dashboard-chat-task">
          <div>${esc(item.text || '').replace(/\\n/g, '<br/>')}</div>
          <div class="muted">${status} • ETA ${eta}</div>
        </div>`;
      }).join('');
    };
    const refreshChat = async () => {
      try {
        const response = await fetch('api/chat/messages');
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(payload.error || response.statusText);
        renderChat(payload.messages || []);
        renderTasks(payload.session || {}, payload.tasks || []);
        if (progress) {
          const session = payload.session || {};
          const line = session.progress_line || '0/0 (0%)';
          const eta = session.eta_minutes_estimate == null ? '—' : `${session.eta_minutes_estimate} мин`;
          const status = esc(session.status || 'planning');
          progress.textContent = `Прогресс: ${line} • ETA: ${eta} • Статус: ${status}`;
        }
        if (feedback) feedback.textContent = payload.messages?.some((item) => item.state === 'queued' || item.state === 'running' || item.state === 'leased') ? 'Есть сообщение в обработке' : '';
      } catch (error) {
        if (feedback) feedback.textContent = `Чат недоступен: ${error.message || error}`;
      }
    };
    form?.addEventListener('submit', async (event) => {
      event.preventDefault();
      const text = (textarea?.value || '').trim();
      if (!text) return;
      if (send) send.disabled = true;
      if (feedback) feedback.textContent = 'Отправляем...';
      try {
        const response = await fetch('api/chat/messages', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text }),
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(payload.error || response.statusText);
        if (textarea) textarea.value = '';
        await refreshChat();
      } catch (error) {
        if (feedback) feedback.textContent = `Ошибка отправки: ${error.message || error}`;
      } finally {
        if (send) send.disabled = false;
      }
    });
    refreshChat();
    window.setInterval(refreshChat, 2500);
  }
})();
</script>
"""


def nav(snapshot: dict[str, Any]) -> str:
    links = [
        '<a class="navlink primary" href="/">Статистика</a>',
        '<a class="navlink" href="/infrastructure">VPS</a>',
        '<a class="navlink" href="/old">Old version</a>',
        '<a class="navlink primary" href="/chat">Чат задач</a>',
    ]
    for project in snapshot.get("projects", []):
        pid = html.escape(str(project.get("project_id")))
        name = html.escape(str(project.get("name")))
        links.append(f'<a class="navlink" href="/project/{pid}">{name}</a>')
    return f'<header><div class="bar"><div class="brand">Agent Dashboard</div><nav>{"".join(links)}</nav></div></header>'


def layout(title: str, snapshot: dict[str, Any], body: str) -> str:
    return f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>{css()}</style>
</head>
<body>
{nav(snapshot)}
<main>{body}</main>
{compact_metrics_script()}
</body>
</html>
"""


def metric(label: str, value: Any, class_name: str = "") -> str:
    return f'<div class="metric {class_name}"><b>{html.escape(str(value))}</b><span>{html.escape(label)}</span></div>'


def completed_ratio(counts: dict[str, Any]) -> str:
    return f'{safe_int(counts.get("completed"))}/{safe_int(counts.get("total"))}'


def live_backlog_total(counts: dict[str, Any]) -> int:
    return (
        safe_int(counts.get("waiting"))
        + safe_int(counts.get("active_total", counts.get("active")))
        + safe_int(counts.get("human"))
        + safe_int(counts.get("task_packet"))
    )


def summary_item(label: str, value: Any, class_name: str = "") -> str:
    return f'<div class="summary-item {class_name}"><b>{html.escape(str(value))}</b><span>{html.escape(label)}</span></div>'


def period_ratio(values: dict[str, Any]) -> str:
    return f'{safe_int(values.get("today"))}/{safe_int(values.get("week"))}'


def period_count_label(values: dict[str, Any] | None) -> str:
    values = values or {}
    return (
        f'{safe_int(values.get("total"))}'
        f' ({safe_int(values.get("today"))}/{safe_int(values.get("week"))})'
    )


def role_outcome_cards(role_stats: dict[str, Any] | None, section_title: str = "По ролям") -> str:
    rows = []
    stats = role_stats or {}
    for role in TASK_LANE_LABELS:
        role_data = stats.get(role, {})
        if not isinstance(role_data, dict):
            role_data = {}
        done = period_count_label(role_data.get("done", {}))
        rework = period_count_label(role_data.get("rework", {}))
        rejected = period_count_label(role_data.get("rejected", {}))
        role_label = TASK_LANE_LABELS.get(role, role)
        rows.append(
            f'<div class="summary-item">'
            f'<b>{html.escape(str(role_label))}</b>'
            f'<span>Сделал: {html.escape(done)}</span>'
            f'<span>На доработку: {html.escape(rework)}</span>'
            f'<span>Забраковал: {html.escape(rejected)}</span>'
            '</div>'
        )
    if not rows:
        rows.append(
            f'<div class="summary-item"><span>Данные по ролям пока не найдены</span></div>'
        )
    return (
        f'<section class="summary-panel">'
        f'<h2>{html.escape(section_title)}</h2>'
        f'<div class="summary-strip">{"".join(rows)}</div>'
        "</section>"
    )


TASK_ORIGIN_DISPLAY_LABELS = {
    "roadmap": "Roadmap",
    "project_rules_remediation": "Project rules",
    "task_doc_import": "Task docs",
    "integration_result": "Integration",
    "dispatcher_repair": "Dispatcher repair",
    "other": "Other",
}


def task_origin_cards(origin_stats: dict[str, Any] | None, section_title: str = "Происхождение задач") -> str:
    stats = origin_stats if isinstance(origin_stats, dict) else {}
    rows = []
    for key in TASK_ORIGIN_LABELS:
        value = safe_int(stats.get(key))
        class_name = ""
        if key == "dispatcher_repair" and value:
            class_name = "task_packet"
        elif key == "integration_result" and value:
            class_name = "active"
        elif value:
            class_name = "waiting"
        rows.append(summary_item(TASK_ORIGIN_DISPLAY_LABELS.get(key, key), value, class_name))
    return (
        '<section class="summary-panel">'
        f'<h2>{html.escape(section_title)}</h2>'
        f'<div class="summary-strip">{"".join(rows)}</div>'
        "</section>"
    )


def local_llm_pre_worker_cards(stats: dict[str, Any] | None) -> str:
    data = stats if isinstance(stats, dict) else {}
    return (
        '<section class="summary-panel">'
        '<h2>Local LLM до Worker</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Ожидают triage", safe_int(data.get("pending")), "waiting" if safe_int(data.get("pending")) else "")}'
        f'{summary_item("Проверено", safe_int(data.get("attempted_tasks")), "active" if safe_int(data.get("attempted_tasks")) else "")}'
        f'{summary_item("Отправлено Dispatcher", safe_int(data.get("routed_dispatcher_repair")), "task_packet" if safe_int(data.get("routed_dispatcher_repair")) else "")}'
        f'{summary_item("Codex fallback", safe_int(data.get("fallback_total")), "postponed" if safe_int(data.get("fallback_total")) else "")}'
        f'{summary_item("Backend fallback", safe_int(data.get("fallback_backend")), "postponed" if safe_int(data.get("fallback_backend")) else "")}'
        f'{summary_item("Внешних запусков сэкономлено", safe_int(data.get("external_worker_launches_avoided")), "active" if safe_int(data.get("external_worker_launches_avoided")) else "")}'
        '</div></section>'
    )


def role_state_cards(role_state: dict[str, Any] | None) -> str:
    state = role_state or {}
    rows = []
    for role in TASK_LANE_LABELS:
        data = state.get(role, {})
        if not isinstance(data, dict):
            data = {}
        parts = [
            str(data.get("state") or "unknown"),
            str(data.get("reason") or data.get("last_result") or data.get("last_skip_reason") or ""),
        ]
        finished = str(data.get("last_finished_at") or "")
        if finished:
            parts.append(finished)
        last_error = data.get("last_error") if isinstance(data.get("last_error"), dict) else {}
        error_tail = str(last_error.get("stderr_tail") or last_error.get("stdout_tail") or "").strip()
        if error_tail:
            parts.append(error_tail[-240:])
        rows.append(
            f'<div class="summary-item">'
            f'<b>{html.escape(TASK_LANE_LABELS.get(role, role))}</b>'
            f'<span>{html.escape(" | ".join(part for part in parts if part))}</span>'
            '</div>'
        )
    return (
        '<section class="summary-panel">'
        '<h2>Состояние ролей</h2>'
        f'<div class="summary-strip">{"".join(rows)}</div>'
        '</section>'
    )


def worker_run_action_cards(action: dict[str, Any] | None, section_title: str = "Worker run") -> str:
    data = action if isinstance(action, dict) else {}
    next_items = data.get("next_items") if isinstance(data.get("next_items"), list) else []
    if not next_items:
        next_task_ids = data.get("next_task_ids") if isinstance(data.get("next_task_ids"), list) else []
        next_items = [{"task_id": task_id} for task_id in next_task_ids]
    next_text = ", ".join(
        str(item.get("task_id") or "")
        for item in next_items[:RUNNER_READINESS_CANDIDATE_PREVIEW_LIMIT]
        if isinstance(item, dict) and str(item.get("task_id") or "").strip()
    )
    ready = bool(data.get("ready"))
    return (
        '<section class="summary-panel">'
        f'<h2>{html.escape(section_title)}</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Готов к запуску", "да" if ready else "нет", "active" if ready else "")}'
        f'{summary_item("Кандидатов", safe_int(data.get("candidate_count")), "waiting" if safe_int(data.get("candidate_count")) else "")}'
        f'{summary_item("Проектов", safe_int(data.get("ready_project_count")), "waiting" if safe_int(data.get("ready_project_count")) else "")}'
        f'{summary_item("Режим", data.get("recommended_mode") or "-")}'
        f'{summary_item("Следующие задачи", next_text or "-")}'
        '</div></section>'
    )


def queue_attention_plan_cards(plan: dict[str, Any] | None, section_title: str = "План внимания") -> str:
    data = plan or zero_queue_attention_plan()
    by_action = data.get("by_action") if isinstance(data.get("by_action"), dict) else {}
    by_environment_reason = data.get("by_environment_reason") if isinstance(data.get("by_environment_reason"), dict) else {}
    model_limit_retry = data.get("model_limit_retry") if isinstance(data.get("model_limit_retry"), dict) else {}
    by_lane = data.get("by_lane") if isinstance(data.get("by_lane"), dict) else {}
    rows = [
        summary_item("Всего в очереди внимания", safe_int(data.get("total")), "human"),
        summary_item("Решение владельца", safe_int(by_action.get("owner_required")), "human"),
        summary_item("Нужна среда", safe_int(by_action.get("environment_required")), "postponed"),
        summary_item("Model limits", safe_int(by_environment_reason.get("model_limit")), "postponed"),
        summary_item("Retry ready", safe_int(model_limit_retry.get("eligible")), "waiting"),
        summary_item("Retry approval", safe_int(model_limit_retry.get("waiting_for_approval")), "postponed"),
        summary_item("Retry next batch", safe_int(model_limit_retry.get("next_batch_approval_count")), "waiting"),
        summary_item("Retry runs needed", safe_int(model_limit_retry.get("runs_needed_for_waiting_approval")), "postponed"),
        summary_item("Worker host", safe_int(by_environment_reason.get("worker_host")), "postponed"),
        summary_item("Infra blockers", safe_int(by_environment_reason.get("infrastructure")), "postponed"),
        summary_item("После git/credentials", safe_int(by_action.get("role_actionable_after_infra")), "waiting"),
        summary_item("Ролевой rework", safe_int(by_action.get("role_rework")), "active"),
        summary_item("Environment lane", safe_int(by_lane.get("environment"))),
        summary_item("Worker lane", safe_int(by_lane.get("worker"))),
        summary_item("Integrator lane", safe_int(by_lane.get("integrator"))),
    ]
    return (
        '<section class="summary-panel">'
        f'<h2>{html.escape(section_title)}</h2>'
        f'<div class="summary-strip">{"".join(rows)}</div>'
        '</section>'
    )


def automation_controller_cards(controller: dict[str, Any] | None, summary: dict[str, Any] | None = None) -> str:
    data = controller or {}
    compact = summary or automation_controller_summary(data)
    latest = data.get("latest") if isinstance(data.get("latest"), dict) else {}
    if not latest:
        return (
            '<section class="summary-panel">'
            '<h2>Controller</h2>'
            '<div class="summary-strip">'
            f'{summary_item("Отчетов", safe_int(data.get("report_count")))}'
            f'{summary_item("Последний", "нет")}'
            '</div></section>'
        )
    block_reason = compact.get("block_reason") or "-"
    blocked_projects = compact.get("blocked_projects") or []
    blocked_project_text = ", ".join(str(item) for item in blocked_projects) if isinstance(blocked_projects, list) and blocked_projects else "-"
    return (
        '<section class="summary-panel">'
        '<h2>Controller</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Отчетов", safe_int(data.get("report_count")))}'
        f'{summary_item("Состояние", compact.get("latest_state") or latest.get("state") or "unknown", "active" if compact.get("latest_state") == "running" else "")}'
        f'{summary_item("Режим", latest.get("mode") or "-")}'
        f'{summary_item("Return code", latest.get("returncode") if latest.get("returncode") is not None else "-")}'
        f'{summary_item("Child blocked", compact.get("child_blocked_count", 0), "postponed" if safe_int(compact.get("child_blocked_count")) else "")}'
        f'{summary_item("Child failed", compact.get("child_failed_count", 0), "human" if safe_int(compact.get("child_failed_count")) and not safe_int(compact.get("child_blocked_count")) else "")}'
        f'{summary_item("Raw child failed", compact.get("raw_child_failed_count", 0))}'
        f'{summary_item("Credentials", compact.get("credential_readiness_reason") or "-")}'
        f'{summary_item("Причина", block_reason, "postponed" if compact.get("latest_state") == "blocked" else "")}'
        f'{summary_item("Проекты", blocked_project_text)}'
        f'{summary_item("Обновлено", short_datetime(latest.get("updated_at")) or short_datetime(latest.get("finished_at")) or "-")}'
        '</div></section>'
    )


def command_bus_cards(bus: dict[str, Any] | None, summary: dict[str, Any] | None = None) -> str:
    data = bus or {}
    compact = summary or {}
    counts = compact.get("counts") if isinstance(compact.get("counts"), dict) else data.get("counts") if isinstance(data.get("counts"), dict) else {}
    raw_counts = compact.get("raw_counts") if isinstance(compact.get("raw_counts"), dict) else data.get("raw_counts") if isinstance(data.get("raw_counts"), dict) else {}
    unresolved_counts = compact.get("unresolved_counts") if isinstance(compact.get("unresolved_counts"), dict) else data.get("unresolved_counts") if isinstance(data.get("unresolved_counts"), dict) else {}
    raw_failed = safe_int(raw_counts.get("failed"))
    effective_failed = safe_int(counts.get("failed"))
    normalized_failed = max(0, raw_failed - effective_failed)
    unresolved_attention = safe_int(unresolved_counts.get("blocked")) + safe_int(unresolved_counts.get("failed"))
    latest = data.get("latest") if isinstance(data.get("latest"), dict) else {}
    latest_state = compact.get("latest_state") or latest.get("state") or "-"
    latest_mode = compact.get("latest_mode") or latest.get("mode") or "-"
    latest_updated = compact.get("latest_updated_at") or latest.get("updated_at") or latest.get("created_at")
    latest_no_op_reason = compact.get("latest_no_op_reason") or ((latest.get("parsed_summary") or {}).get("no_op_reason") if isinstance(latest.get("parsed_summary"), dict) else None)
    return (
        '<section class="summary-panel">'
        '<h2>Command Bus</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Всего команд", safe_int(compact.get("command_count", data.get("command_count", 0))))}'
        f'{summary_item("Queued", safe_int(counts.get("queued")), "waiting" if safe_int(counts.get("queued")) else "")}'
        f'{summary_item("Running", safe_int(counts.get("running")) + safe_int(counts.get("leased")), "active" if safe_int(counts.get("running")) + safe_int(counts.get("leased")) else "")}'
        f'{summary_item("Blocked", safe_int(counts.get("blocked")), "postponed" if safe_int(counts.get("blocked")) else "")}'
        f'{summary_item("Failed", safe_int(counts.get("failed")), "human" if safe_int(counts.get("failed")) else "")}'
        f'{summary_item("Unresolved", unresolved_attention, "human" if unresolved_attention else "")}'
        f'{summary_item("Raw failed", raw_failed, "human" if raw_failed else "")}'
        f'{summary_item("Normalized", normalized_failed, "postponed" if normalized_failed else "")}'
        f'{summary_item("Последний режим", latest_mode)}'
        f'{summary_item("Последний статус", latest_state)}'
        f'{summary_item("No-op reason", latest_no_op_reason or "-")}'
        f'{summary_item("Обновлено", short_datetime(latest_updated) or "-")}'
        '</div></section>'
    )


def automation_bridge_cards(summary: dict[str, Any] | None = None, title: str = "Automation Bridge") -> str:
    data = summary or {}
    counters = data.get("counters") if isinstance(data.get("counters"), dict) else {}
    conversion = data.get("conversion") if isinstance(data.get("conversion"), dict) else {}
    incoming = conversion.get("incoming") if isinstance(conversion.get("incoming"), dict) else {}
    documentation = conversion.get("documentation") if isinstance(conversion.get("documentation"), dict) else {}
    dispatcher = conversion.get("dispatcher") if isinstance(conversion.get("dispatcher"), dict) else {}
    worker_queue = conversion.get("worker_queue") if isinstance(conversion.get("worker_queue"), dict) else {}
    convergence = data.get("convergence") if isinstance(data.get("convergence"), dict) else {}
    scan = convergence.get("scan") if isinstance(convergence.get("scan"), dict) else {}
    normalize = convergence.get("normalize") if isinstance(convergence.get("normalize"), dict) else {}
    cleanup = convergence.get("cleanup") if isinstance(convergence.get("cleanup"), dict) else {}
    doc_code_tasks = convergence.get("documentation_code_tasks") if isinstance(convergence.get("documentation_code_tasks"), dict) else {}
    statuses = convergence.get("statuses") if isinstance(convergence.get("statuses"), dict) else {}
    doc_policy_state = "needs_repair" if safe_int(statuses.get("needs_repair")) else "active" if safe_int(statuses.get("active")) else "converged"
    queue_evidence = data.get("queue_evidence") if isinstance(data.get("queue_evidence"), dict) else {}
    return (
        '<section class="summary-panel">'
        f'<h2>{html.escape(title)}</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Проектов со state", safe_int(data.get("project_count")))}'
        f'{summary_item("Real pickups", safe_int(conversion.get("material_update_count")), "active" if safe_int(conversion.get("material_update_count")) else "")}'
        f'{summary_item("Doc=Code+Tasks", doc_policy_state, "human" if doc_policy_state == "needs_repair" else "active" if doc_policy_state == "active" else "")}'
        f'{summary_item("Scan findings", safe_int(scan.get("artifact_findings")), "waiting" if safe_int(scan.get("artifact_findings")) else "")}'
        f'{summary_item("Normalized rows", safe_int(normalize.get("artifact_normalized_rows")), "waiting" if safe_int(normalize.get("artifact_normalized_rows")) else "")}'
        f'{summary_item("Cleanup actions", safe_int(cleanup.get("packet_cleaned")) + safe_int(cleanup.get("packet_cleanup_selected")), "active" if safe_int(cleanup.get("packet_cleaned")) else "")}'
        f'{summary_item("PR converted", safe_int(incoming.get("pr_processed")))}'
        f'{summary_item("Docs to queue", safe_int(documentation.get("queued_items_created")), "waiting" if safe_int(documentation.get("queued_items_created")) else "")}'
        f'{summary_item("Dispatcher actions", safe_int(dispatcher.get("dispatcher_actions")), "active" if safe_int(dispatcher.get("dispatcher_actions")) else "")}'
        f'{summary_item("Worker queue updates", safe_int(worker_queue.get("worker_queue_updates")))}'
        f'{summary_item("Queue evidence", safe_int(queue_evidence.get("imported_task_count")), "waiting" if safe_int(queue_evidence.get("imported_task_count")) else "")}'
        f'{summary_item("Review rows", safe_int(queue_evidence.get("dispatcher_review_count")), "waiting" if safe_int(queue_evidence.get("dispatcher_review_count")) else "")}'
        f'{summary_item("PR checked", safe_int(counters.get("pr_checked")))}'
        f'{summary_item("PR missing", safe_int(counters.get("pr_missing")), "waiting" if safe_int(counters.get("pr_missing")) else "")}'
        f'{summary_item("PR processed", safe_int(counters.get("pr_processed")))}'
        f'{summary_item("Task docs imported", safe_int(counters.get("task_docs_imported")), "waiting" if safe_int(counters.get("task_docs_imported")) else "")}'
        f'{summary_item("Design imported", safe_int(counters.get("design_imported")), "waiting" if safe_int(counters.get("design_imported")) else "")}'
        f'{summary_item("Packets applied", safe_int(counters.get("packet_applied")), "active" if safe_int(counters.get("packet_applied")) else "")}'
        f'{summary_item("Owner gaps", safe_int(counters.get("owner_gap_count")), "human" if safe_int(counters.get("owner_gap_count")) else "")}'
        f'{summary_item("Worker-ready delta", safe_int(counters.get("worker_ready_delta")))}'
        f'{summary_item("Last pickup", short_datetime(data.get("latest_material_update_at")) or "-")}'
        f'{summary_item("Обновлено", short_datetime(data.get("latest_updated_at")) or "-")}'
        '</div></section>'
    )


def project_automation_bridge_cards(bridge: dict[str, Any] | None) -> str:
    if not isinstance(bridge, dict):
        return automation_bridge_cards({"project_count": 0, "counters": {}}, "Automation Bridge")
    return automation_bridge_cards(
        {
            "project_count": 1,
            "latest_updated_at": bridge.get("updated_at"),
            "latest_material_update_at": bridge.get("last_material_update_at"),
            "counters": bridge.get("cumulative_counters")
            if isinstance(bridge.get("cumulative_counters"), dict)
            else bridge.get("counters")
            if isinstance(bridge.get("counters"), dict)
            else {},
            "conversion": bridge.get("cumulative_conversion")
            if isinstance(bridge.get("cumulative_conversion"), dict)
            else bridge.get("conversion")
            if isinstance(bridge.get("conversion"), dict)
            else automation_bridge_conversion(
                bridge.get("counters") if isinstance(bridge.get("counters"), dict) else {},
                bridge.get("queue_before") if isinstance(bridge.get("queue_before"), dict) else None,
                bridge.get("queue_after") if isinstance(bridge.get("queue_after"), dict) else None,
            ),
            "convergence": bridge.get("cumulative_convergence")
            if isinstance(bridge.get("cumulative_convergence"), dict)
            else bridge.get("convergence")
            if isinstance(bridge.get("convergence"), dict)
            else {},
            "queue_evidence": bridge.get("queue_evidence") if isinstance(bridge.get("queue_evidence"), dict) else {},
        },
        "Automation Bridge",
    )


def dashboard_chat_session(runtime_root: Path) -> dict[str, Any]:
    return remote_chat_bus.get_or_create_session(
        runtime_root,
        channel="dashboard",
        external_id="main",
        title="Dashboard chat",
        actor="dashboard",
    )


def dashboard_chat_panel() -> str:
    return (
        '<section class="summary-panel dashboard-chat">'
        '<h2>Чат</h2>'
        '<div class="muted">Полноэкранный UX: вкладки проектов, отдельные чаты и линейка прогресса.</div>'
        '<p><a class="button" href="/chat" target="_blank">Открыть чат задач</a></p>'
        '<iframe id="chat-embed-frame" class="dashboard-chat-embed" src="/chat" title="AiStudio Remote Chat" loading="lazy"></iframe>'
        '<div class="muted">Если чат не грузится в блоке, откройте <a href="/chat" target="_blank">чаты задач</a>.</div>'
        '</section>'
    )


def automation_worktree_cards(summary: dict[str, Any] | None = None) -> str:
    data = summary or {}
    if not data:
        return ""
    needs_action = safe_int(data.get("needs_action_count"))
    blocked = safe_int(data.get("blocked_count"))
    remote_required = safe_int(data.get("remote_check_required_count"))
    attention = safe_int(data.get("attention_count"))
    remote_state = "checked" if data.get("remote_checked") else "required" if remote_required else "not checked"
    credential_reason = str(data.get("credential_readiness_reason") or "")
    blocked_class = "postponed" if blocked or credential_reason else ""
    return (
        '<section class="summary-panel">'
        '<h2>Worktrees</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Needs action", needs_action, "waiting" if needs_action else "")}'
        f'{summary_item("Attention", attention, "postponed" if attention else "")}'
        f'{summary_item("Blocked", blocked, blocked_class)}'
        f'{summary_item("Credentials", credential_reason or "- ", "postponed" if credential_reason else "")}'
        f'{summary_item("Remote check", remote_state, "waiting" if remote_required else "")}'
        f'{summary_item("Remote required", remote_required, "waiting" if remote_required else "")}'
        f'{summary_item("Root", data.get("worktree_root") or "-")}'
        '</div></section>'
    )


def scan_migration_goals(runtime_root: Path) -> dict[str, Any]:
    roots = [
        runtime_root / "agent-control" / "goals",
        Path("runtime/agent-control/goals"),
    ]
    candidates: list[dict[str, Any]] = []
    seen: set[str] = set()
    for root in roots:
        if not root.exists():
            continue
        for path in sorted(root.glob("*.json")):
            key = str(path.resolve())
            if key in seen:
                continue
            seen.add(key)
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except Exception as exc:
                candidates.append({
                    "project_id": path.stem,
                    "path": str(path),
                    "mode": "workspace_goal_decision",
                    "decision": "stale",
                    "can_apply": False,
                    "stale": True,
                    "error": str(exc),
                })
                continue
            if not isinstance(data, dict):
                continue
            if str(data.get("mode") or "") not in {"workspace_goal_status", "workspace_goal_decision"}:
                continue
            item = dict(data)
            decisions = item.get("decisions") if isinstance(item.get("decisions"), dict) else {}
            role_reviews = decisions.get("role_reviews") if isinstance(decisions.get("role_reviews"), dict) else {}
            if role_reviews and "role_reviews" not in item:
                item["role_reviews"] = role_reviews
            item["path"] = str(path)
            candidates.append(item)
    goals_by_project: dict[str, dict[str, Any]] = {}
    for item in candidates:
        project_id = str(item.get("project_id") or Path(str(item.get("path") or "")).stem)
        existing = goals_by_project.get(project_id)
        if existing is None:
            goals_by_project[project_id] = item
            continue
        existing_rank = 1 if str(existing.get("mode") or "") == "workspace_goal_decision" else 0
        item_rank = 1 if str(item.get("mode") or "") == "workspace_goal_decision" else 0
        if item_rank >= existing_rank:
            goals_by_project[project_id] = item
    goals = sorted(goals_by_project.values(), key=lambda item: str(item.get("project_id") or ""))
    by_decision: dict[str, int] = {}
    can_apply_count = 0
    stale_count = 0
    for goal in goals:
        decision = str(goal.get("decision") or "unknown")
        by_decision[decision] = by_decision.get(decision, 0) + 1
        if goal.get("can_apply"):
            can_apply_count += 1
        if goal.get("stale") or decision == "stale":
            stale_count += 1
    return {
        "schema_version": "1.0",
        "goal_count": len(goals),
        "can_apply_count": can_apply_count,
        "stale_count": stale_count,
        "by_decision": by_decision,
        "goals": goals,
    }


def migration_goal_cards(migration_goal: dict[str, Any] | None) -> str:
    data = migration_goal if isinstance(migration_goal, dict) else {}
    goals = [item for item in data.get("goals") or [] if isinstance(item, dict)]
    rows = [
        summary_item("Целей", safe_int(data.get("goal_count"))),
        summary_item("Can apply", safe_int(data.get("can_apply_count")), "waiting" if safe_int(data.get("can_apply_count")) else ""),
        summary_item("Stale", safe_int(data.get("stale_count")), "human" if safe_int(data.get("stale_count")) else ""),
        summary_item("Applied", safe_int((data.get("by_decision") or {}).get("applied")), "completed" if safe_int((data.get("by_decision") or {}).get("applied")) else ""),
    ]
    if not goals:
        return (
            '<section class="summary-panel">'
            '<h2>Migration Goal</h2>'
            f'<div class="summary-strip">{"".join(rows)}</div>'
            '<p class="muted">Goal artifacts not found.</p>'
            '</section>'
        )
    goal_rows = []
    for goal in goals[:8]:
        validation = goal.get("validation") if isinstance(goal.get("validation"), dict) else {}
        prepared = validation.get("prepared_queue") if isinstance(validation.get("prepared_queue"), dict) else {}
        target = validation.get("target_queue") if isinstance(validation.get("target_queue"), dict) else {}
        diff = goal.get("diff") if isinstance(goal.get("diff"), dict) else {}
        route_tasks = goal.get("route_tasks") if isinstance(goal.get("route_tasks"), dict) else {}
        route_decisions = goal.get("route_decisions") if isinstance(goal.get("route_decisions"), dict) else {}
        role_reviews = goal.get("role_reviews") if isinstance(goal.get("role_reviews"), dict) else {}
        next_actions = goal.get("next_actions") if isinstance(goal.get("next_actions"), list) else []
        role_text = (
            f'W {safe_int(role_reviews.get("worker_ready") or route_decisions.get("ready_for_packet_count"))} / '
            f'A {safe_int(role_reviews.get("architect_review"))} / '
            f'I {safe_int(role_reviews.get("integrator_review"))} / '
            f'O {safe_int(role_reviews.get("owner_review"))}'
        )
        goal_rows.append(
            '<tr>'
            f'<td>{html.escape(str(goal.get("project_id") or "-"))}</td>'
            f'<td>{html.escape(str(goal.get("decision") or "-"))}</td>'
            f'<td>{html.escape(str(goal.get("goal_version") or "-"))}</td>'
            f'<td>{html.escape(str(goal.get("artifact_version") or "-"))}</td>'
            f'<td>{safe_int(prepared.get("errors"))}/{safe_int(prepared.get("warnings"))}</td>'
            f'<td>{safe_int(target.get("errors"))}/{safe_int(target.get("warnings"))}</td>'
            f'<td>+{safe_int(diff.get("added_count"))} / ~{safe_int(diff.get("changed_count"))} / -{safe_int(diff.get("removed_count"))}</td>'
            f'<td>{safe_int(route_tasks.get("seed_count"))}</td>'
            f'<td>{html.escape(role_text)}</td>'
            f'<td>{html.escape(str(goal.get("next_owner") or "-"))}</td>'
            f'<td>{html.escape(str(next_actions[0] if next_actions else "-"))}</td>'
            '</tr>'
        )
    table = (
        '<table><thead><tr>'
        '<th>Project</th><th>Decision</th><th>Goal</th><th>Artifact</th>'
        '<th>Prepared</th><th>Target</th><th>Diff</th><th>Routes</th><th>Roles</th><th>Owner</th><th>Next</th>'
        '</tr></thead><tbody>'
        + ''.join(goal_rows)
        + '</tbody></table>'
    )
    return (
        '<section class="summary-panel">'
        '<h2>Migration Goal</h2>'
        f'<div class="summary-strip">{"".join(rows)}</div>'
        f'{table}'
        '</section>'
    )


def tasks_summary_panel(
    counts: dict[str, Any],
    added: dict[str, Any],
    completed_recent: dict[str, Any],
    human_total: Any,
    infra_blocked_total: Any = 0,
    worker_host_blocked_candidates: Any = 0,
    active_total: Any = 0,
    worktree_active_total: Any = 0,
    role_outcomes: dict[str, Any] | None = None,
) -> str:
    active_text = safe_int(active_total)
    if safe_int(worktree_active_total):
        active_text = f'{safe_int(active_total)} (wt {safe_int(worktree_active_total)})'
    active_backlog = counts.get("active_queue_total", live_backlog_total(counts))
    terminal_total = counts.get("terminal_queue_total", safe_int(counts.get("completed")) + safe_int(counts.get("postponed")))
    return (
        '<section class="summary-panel">'
        '<h2>Активные задачи</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Всего к действию", active_backlog, "waiting" if safe_int(active_backlog) else "")}'
        f'{summary_item("Ждут выполнения", counts.get("waiting", 0), "waiting")}'
        f'{summary_item("В работе", active_text, "active")}'
        f'{summary_item("Нужен человек", human_total, "human")}'
        f'{summary_item("Нужен task packet", counts.get("task_packet", 0), "task_packet")}'
        f'{summary_item("Ждут среды", infra_blocked_total, "postponed")}'
        f'{summary_item("Worker host", worker_host_blocked_candidates, "postponed" if safe_int(worker_host_blocked_candidates) else "")}'
        '</div>'
        '</section>'
        '<section class="summary-panel ledger-panel">'
        '<h2>История ledger</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Выполнено из ledger", completed_ratio(counts), "completed")}'
        f'{summary_item("Закрыто/отложено", terminal_total, "completed" if safe_int(terminal_total) else "")}'
        f'{summary_item("Всего записей", counts.get("total", 0), "completed" if safe_int(counts.get("total")) else "")}'
        f'{summary_item("Отложено", counts.get("postponed", 0), "postponed")}'
        f'{summary_item("Выполнено сегодня/неделя", period_ratio(completed_recent), "completed")}'
        f'{summary_item("Добавлено сегодня/неделя", period_ratio(added))}'
        '</div>'
        '</section>'
        f'{role_outcome_cards(role_outcomes, "Задания по ролям")}'
    )


def project_statusline(project: dict[str, Any]) -> str:
    counts = project.get("counts") or {}
    active_total = safe_int(counts.get("active_total", counts.get("active")))
    worktree_active = safe_int(counts.get("worktree_active"))
    needs_human = project.get("needs_human_effective", project.get("needs_human", counts.get("human")))
    infra_blocked = safe_int(project.get("infra_blocked_attention"))
    worker_host_blocked = safe_int(project.get("worker_host_blocked_candidates"))
    active_label = str(active_total)
    if worktree_active:
        active_label = f"{active_total} (wt {worktree_active})"
    active_backlog = counts.get("active_queue_total", live_backlog_total(counts))
    return (
        '<div class="statusline">'
        f'<div class="pill waiting">К действию: {safe_int(active_backlog)}</div>'
        f'<div class="pill waiting">Ждут выполнения: {safe_int(counts.get("waiting"))}</div>'
        f'<div class="pill active">В работе: {html.escape(active_label)}</div>'
        f'<div class="pill postponed">Отложено: {safe_int(counts.get("postponed"))}</div>'
        f'<div class="pill postponed">Ждет среды: {infra_blocked}</div>'
        f'<div class="pill postponed">Host blocked: {worker_host_blocked}</div>'
        f'<div class="pill human">Нужен человек: {safe_int(needs_human)}</div>'
        f'<div class="pill task_packet">Подготовить: {safe_int(counts.get("task_packet"))}</div>'
        f'<div class="pill completed">Выполнено в истории: {safe_int(counts.get("completed"))}</div>'
        "</div>"
    )


def automation_line(project: dict[str, Any]) -> str:
    automation = project.get("automation") or {}
    manual = project.get("manual_run") or {}
    manual_active = manual_run_active(manual)
    if manual_active:
        manual_text = f"Ручной прогон: {manual.get('mode', 'all')} ({manual.get('state', 'running')})"
    else:
        manual_text = ""
    latest = short_datetime(automation.get("latest_agent_run_at")) or "нет запусков"
    return (
        '<div class="muted">'
        f'Агент по проекту: {html.escape(str(latest))}'
        f'{f" • {manual_text}" if manual_text else ""}'
        '</div>'
    )


def project_sort_key(project: dict[str, Any]) -> tuple[int, int, int, int, str]:
    counts = project.get("counts") or {}
    human_attention = project.get("needs_human_effective", project.get("needs_human"))
    active_score = (
        safe_int(counts.get("active_total", counts.get("active")))
        + safe_int(human_attention)
        + safe_int((project.get("runs") or {}).get("total"))
    )
    work_score = (
        safe_int(counts.get("waiting"))
        + safe_int(counts.get("task_packet"))
        + safe_int(counts.get("postponed"))
    )
    total = safe_int(counts.get("total"))
    completed = safe_int(counts.get("completed"))
    return (-active_score, -work_score, -total, -completed, str(project.get("name") or ""))


def human_needed_table(items: list[dict[str, Any]]) -> str:
    rows = []
    for item in items:
        report = str(item.get("report") or "")
        report_link = f'<a href="/api/human-needed.json">JSON</a>'
        if report:
            report_link = f'<code>{html.escape(report)}</code>'
        rows.append(
            "<tr>"
            f'<td>{html.escape(str(item.get("project_id") or ""))}</td>'
            f'<td><code>{html.escape(str(item.get("task_id") or ""))}</code></td>'
            f'<td>{html.escape(str(item.get("reason") or ""))}</td>'
            f'<td>{html.escape(str(item.get("requested_action") or ""))}</td>'
            f'<td>{html.escape(str(item.get("created_at") or ""))}</td>'
            f"<td>{report_link}</td>"
            "</tr>"
        )
    return (
        '<table><thead><tr><th>Project</th><th>Task</th><th>Reason</th><th>Action</th><th>Created</th><th>Report</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=6>Открытых запросов человеку нет</td></tr>"}</tbody></table>'
    )


def codex_limits_table(items: list[dict[str, Any]]) -> str:
    sorted_items = sorted(
        items,
        key=lambda item: (
            str(item.get("scope") or ""),
            str(item.get("model") or ""),
            str(item.get("window") or ""),
            str(item.get("source") or ""),
            str(item.get("observed_at") or ""),
        ),
    )
    rows = []
    for item in sorted_items:
        remaining = safe_int(item.get("remaining_percent"))
        class_name = "human" if remaining < 15 else ("waiting" if remaining < 30 else "completed")
        rows.append(
            "<tr>"
            f'<td>{html.escape(str(item.get("worker_id") or ""))}</td>'
            f'<td>{html.escape(str(item.get("scope") or ""))}</td>'
            f'<td>{html.escape(str(item.get("window") or ""))}</td>'
            f'<td>{html.escape(str(item.get("model") or "unknown"))}</td>'
            f'<td class="{class_name}">{remaining}%</td>'
            f'<td>{html.escape(str(item.get("reset_at") or ""))}</td>'
            f'<td>{html.escape(str(item.get("observed_at") or ""))}</td>'
            f'<td>{html.escape(str(item.get("source") or ""))}</td>'
        "</tr>"
    )
    return (
        '<table><thead><tr><th>Worker</th><th>Scope</th><th>Window</th><th>Model</th><th>Remaining</th><th>Reset</th><th>Observed</th><th>Source</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=8>Снимков лимитов пока нет</td></tr>"}</tbody></table>'
    )


def codex_limit_bars(
    items: list[dict[str, Any]],
    consensus: list[dict[str, Any]] | None = None,
) -> str:
    specs = consensus if isinstance(consensus, list) else build_codex_limit_consensus(items, CODEX_LIMIT_STALE_MINUTES_DEFAULT)
    bars = []
    for item in specs:
        remaining_raw = item.get("consensus_percent")
        remaining = normalize_percent(remaining_raw)
        if remaining is None:
            remaining = 0.0
            remaining_text = "Н/Д"
            class_name = "alert"
        else:
            remaining = max(0.0, min(100.0, remaining))
            remaining_text = f"{remaining:.0f}%"
            class_name = "low" if remaining < 15 else ("mid" if remaining < 30 else "")
        status = str(item.get("status") or "")
        if status == "partial":
            class_name = f"{class_name} partial".strip()
        elif status == "diverged":
            class_name = f"{class_name} alert".strip()

        source_rows = [entry for entry in item.get("source_rows") or [] if isinstance(entry, dict)]
        source_values = []
        reset_values = []
        observed_values = []
        for entry in source_rows:
            source_name = str(entry.get("source") or "unknown")
            source_value = entry.get("remaining_percent")
            source_percent = normalize_percent(source_value)
            if source_percent is None:
                source_text = f"{source_name}: Н/Д"
            else:
                source_text = f'{source_name}: {source_percent:.0f}%'
            source_values.append(source_text)
            reset = short_datetime(entry.get("reset_at"))
            observed = short_datetime(entry.get("observed_at"))
            if reset:
                reset_values.append(reset)
            if observed:
                observed_values.append(observed)
        sources_label = ", ".join(source_values) if source_values else "источники пока не найдены"
        resets_line = ", ".join(dict.fromkeys(reset_values)) if reset_values else "нет данных"
        updated_line = ", ".join(dict.fromkeys(observed_values)) if observed_values else "нет данных"
        agreement = safe_int((item.get("agreement") or 0) * 100)
        bars.append(
            f'<div class="load-bar limit-bar {class_name}">'
            f'<div class="muted">{html.escape(str(item.get("label") or ""))}</div>'
            f'<b>Осталось: {html.escape(remaining_text)}</b>'
            f'<div class="source-line">Источники: {html.escape(sources_label)}</div>'
            f'<div class="source-line">Сброс: {html.escape(resets_line)} · обновлено: {html.escape(updated_line)}</div>'
            f'<div class="source-line">Согласие: {agreement}% · данных: {safe_int(item.get("source_count"))}</div>'
            '<div class="track">'
            f'<div class="fill" style="width:{remaining:.1f}%"></div>'
            '</div>'
            '</div>'
        )
    return (
        '<section class="summary-panel">'
        "<h2>Лимиты Codex</h2>"
        f'<div class="load-bars">{"".join(bars)}</div>'
        "</section>"
    )


def codex_limit_estimates_table(items: list[dict[str, Any]]) -> str:
    rows = []
    for item in items:
        model = item.get("model") or "global"
        consumed = item.get("estimated_consumed_percent")
        per_run = item.get("estimated_percent_per_run")
        rows.append(
            "<tr>"
            f'<td>{html.escape(str(item.get("scope") or ""))}</td>'
            f'<td>{html.escape(str(item.get("window") or ""))}</td>'
            f'<td>{html.escape(str(model))}</td>'
            f'<td>{html.escape(str(item.get("previous_remaining_percent") if item.get("previous_remaining_percent") is not None else ""))}</td>'
            f'<td>{html.escape(str(item.get("remaining_percent") if item.get("remaining_percent") is not None else ""))}</td>'
            f'<td>{html.escape(str(consumed if consumed is not None else ""))}</td>'
            f'<td>{safe_int(item.get("runs_between"))}</td>'
            f'<td>{html.escape(str(per_run if per_run is not None else ""))}</td>'
            f'<td>{html.escape(str(item.get("estimate_status") or ""))}</td>'
            "</tr>"
        )
    return (
        '<table><thead><tr><th>Scope</th><th>Window</th><th>Model</th><th>Before %</th><th>Now %</th><th>Spent %</th><th>Runs</th><th>%/run</th><th>Status</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=9>Оценок расхода пока нет</td></tr>"}</tbody></table>'
    )


def task_size_analytics_table(items: list[dict[str, Any]]) -> str:
    rows = []
    for item in items:
        rows.append(
            "<tr>"
            f'<td>{html.escape(str(item.get("size") or "-"))}</td>'
            f'<td>{safe_int(item.get("tasks_total"))}</td>'
            f'<td>{safe_int(item.get("tasks_completed"))}</td>'
            f'<td>{html.escape(str(item.get("completion_percent") if item.get("completion_percent") is not None else ""))}</td>'
            f'<td>{safe_int(item.get("runs_total"))}</td>'
            f'<td>{safe_int(item.get("tokens_total"))}</td>'
            f'<td>{html.escape(str(item.get("tokens_per_run") if item.get("tokens_per_run") is not None else ""))}</td>'
            "</tr>"
        )
    return (
        '<table><thead><tr><th>Size</th><th>Tasks</th><th>Done</th><th>Done %</th><th>Runs</th><th>Tokens</th><th>Tokens/run</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=7>Данных по размерам задач пока нет</td></tr>"}</tbody></table>'
    )


def model_cost_analytics_table(items: list[dict[str, Any]]) -> str:
    rows = []
    for item in items:
        estimate_5h = item.get("estimate_5h_per_run")
        estimate_weekly = item.get("estimate_weekly_per_run")
        rows.append(
            "<tr>"
            f'<td>{html.escape(str(item.get("model_label") or ""))}</td>'
            f'<td>{safe_int(item.get("runs"))}</td>'
            f'<td>{safe_int(item.get("runs_success"))}</td>'
            f'<td>{safe_int(item.get("tasks"))}</td>'
            f'<td>{safe_int(item.get("success_tasks"))}</td>'
            f'<td>{html.escape("" if item.get("tokens_per_task") is None else f"{item.get("tokens_per_task"):.1f}")}</td>'
            f'<td>{html.escape("" if item.get("tokens_per_success_task") is None else f"{item.get("tokens_per_success_task"):.1f}")}</td>'
            f'<td>{html.escape("" if item.get("tokens_per_run") is None else f"{item.get("tokens_per_run"):.1f}")}</td>'
            f'<td>{html.escape("" if estimate_5h is None else f"{float(estimate_5h):.2f}%")}</td>'
            f'<td>{html.escape("" if estimate_weekly is None else f"{float(estimate_weekly):.2f}%")}</td>'
            "</tr>"
        )
    return (
        '<table><thead><tr>'
        '<th>Модель</th><th>Запусков</th><th>Усп. запусков</th><th>Задач</th>'
        '<th>Усп. задач</th><th>Токенов/задачу</th><th>Токенов/усп. задачу</th>'
        '<th>Токенов/запуск</th><th>~% лимита/задачу 5ч</th><th>~% лимита/задачу неделя</th>'
        '</tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=10>Данных по моделям пока нет</td></tr>"}</tbody></table>'
    )


def progress_text(item: dict[str, Any] | None) -> str:
    if not item:
        return ""
    status = str(item.get("status") or "")
    step = str(item.get("current_step") or "")
    updated = short_datetime(item.get("updated_at")) or ""
    if status == "running":
        return f"running {step} {updated}".strip()
    if status:
        return f"{status} {updated}".strip()
    return ""


def automation_timers_table(items: list[dict[str, Any]], progress_items: list[dict[str, Any]] | None = None) -> str:
    progress_by_service_unit, progress_by_role = _index_progress_items(progress_items or [])
    rows = []
    for item in items:
        success_total = safe_int(item.get("success_total"))
        failed_total = safe_int(item.get("failed_total"))
        success_today = safe_int(item.get("success_today"))
        failed_today = safe_int(item.get("failed_today"))
        progress_item = progress_by_service_unit.get(str(item.get("service_unit") or ""))
        if progress_item is None:
            role = str(item.get("role") or "")
            progress_item = progress_by_role.get(role)
        result = f"успешно {success_total} (+{success_today} сегодня), неуспешно {failed_total} (+{failed_today} сегодня)"
        rows.append(
            "<tr>"
            f'<td>{html.escape(str(item.get("role") or ""))}</td>'
            f'<td>{html.escape(short_datetime(item.get("last_at")) or "")}</td>'
            f'<td>{html.escape(short_datetime(item.get("next_at")) or "")}</td>'
            f'<td>{html.escape(progress_text(progress_item))}</td>'
            f'<td>{html.escape(result)}</td>'
            "</tr>"
        )
    return (
        '<table><thead><tr><th>Role</th><th>Last</th><th>Next</th><th>Now</th><th>Result</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=5>Расписание автоматизации пока не собрано</td></tr>"}</tbody></table>'
    )


def activity_log_table(items: list[dict[str, Any]]) -> str:
    rows = []
    for item in items[:20]:
        transition = ""
        before = item.get("status_before")
        after = item.get("status_after")
        if before or after:
            transition = f"{before or '-'} -> {after or '-'}"
        duration = item.get("duration_sec")
        duration_text = ""
        if duration is not None:
            duration_text = f"{safe_float(duration) or 0:.1f}s"
        rows.append(
            "<tr>"
            f'<td>{html.escape(short_datetime(item.get("created_at")) or str(item.get("created_at") or ""))}</td>'
            f'<td>{html.escape(str(item.get("role") or ""))}</td>'
            f'<td>{html.escape(str(item.get("project_id") or ""))}</td>'
            f'<td>{html.escape(str(item.get("action") or ""))}</td>'
            f'<td>{html.escape(transition)}</td>'
            f'<td>{html.escape(duration_text)}</td>'
            f'<td>{html.escape(str(item.get("message") or ""))}</td>'
            "</tr>"
        )
    return (
        '<table><thead><tr><th>Время</th><th>Роль</th><th>Проект</th><th>Действие</th><th>Статус</th><th>Длительность</th><th>Сообщение</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=7>Событий пока нет</td></tr>"}</tbody></table>'
    )


def project_activity_log(snapshot: dict[str, Any], project: dict[str, Any]) -> list[dict[str, Any]]:
    aliases = {
        str(project.get("project_id") or "").strip().lower(),
        str(project.get("name") or "").strip().lower(),
        Path(str(project.get("local_path") or "")).name.strip().lower(),
    }
    aliases = {alias for alias in aliases if alias}
    rows = []
    for item in snapshot.get("activity_log", []):
        value = str(item.get("project_id") or "").strip().lower()
        if value in aliases:
            rows.append(item)
    return rows[:20]


def task_table(rows: list[str], empty_text: str) -> str:
    return (
        '<div class="scroll-panel">'
        '<table><thead><tr><th>ID</th><th>Название</th><th>Статус</th><th>Размер</th><th>Приоритет</th><th>Worker</th><th>Старт</th><th>Lock до</th><th>Причина/заметка</th><th>Токены (примерно)</th></tr></thead>'
        f'<tbody>{"".join(rows) or f"<tr><td colspan=10>{html.escape(empty_text)}</td></tr>"}</tbody></table>'
        '</div>'
    )


def owner_directives_table(items: list[dict[str, Any]]) -> str:
    rows = []
    for item in items:
        note = item.get("instruction") or item.get("notes") or item.get("source") or ""
        rows.append(
            "<tr>"
            f'<td><code>{html.escape(str(item.get("id") or ""))}</code></td>'
            f'<td>{html.escape(str(item.get("title") or ""))}</td>'
            f'<td class="human">{html.escape(str(item.get("status") or ""))}</td>'
            f'<td>{html.escape(str(item.get("scope") or ""))}</td>'
            f'<td>{html.escape(str(note))}</td>'
            "</tr>"
        )
    return (
        '<div class="scroll-panel compact">'
        '<table><thead><tr><th>ID</th><th>Название</th><th>Статус</th><th>Scope</th><th>Директива</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=5>Активных директив владельца нет</td></tr>"}</tbody></table>'
        '</div>'
    )


def worktrees_table(items: list[dict[str, Any]]) -> str:
    rows = []
    for item in items:
        rows.append(
            "<tr>"
            f'<td><code>{html.escape(str(item.get("task_id") or "-"))}</code></td>'
            f'<td>{html.escape(str(item.get("branch") or ""))}</td>'
            f'<td>{html.escape(str(item.get("path") or ""))}</td>'
            f'<td><code>{html.escape(str(item.get("head") or "")[:12])}</code></td>'
            "</tr>"
        )
    return (
        '<div class="scroll-panel compact">'
        '<table><thead><tr><th>Task</th><th>Branch</th><th>Worktree</th><th>HEAD</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=4>Worker worktree не найдены</td></tr>"}</tbody></table>'
        '</div>'
    )


def worktrees_stale_table(items: list[dict[str, Any]]) -> str:
    rows = []
    for item in items:
        rows.append(
            "<tr>"
            f'<td><code>{html.escape(str(item.get("task_id") or "-"))}</code></td>'
            f'<td>{html.escape(str(item.get("branch") or ""))}</td>'
            f'<td>{html.escape(str(item.get("path") or ""))}</td>'
            f'<td><code>{html.escape(str(item.get("head") or "")[:12])}</code></td>'
            "</tr>"
        )
    return (
        '<div class="scroll-panel compact">'
        '<table><thead><tr><th>Task</th><th>Branch</th><th>Worktree</th><th>HEAD</th></tr></thead>'
        f'<tbody>{"".join(rows) or "<tr><td colspan=4>Ненастоящие worktree не найдены</td></tr>"}</tbody></table>'
        '</div>'
    )


def find_project(snapshot: dict[str, Any], project_id: str) -> dict[str, Any] | None:
    return next((project for project in snapshot.get("projects", []) if str(project.get("project_id")) == project_id), None)


def project_overview_state(project: dict[str, Any]) -> tuple[str, str, str]:
    counts = project.get("counts") if isinstance(project.get("counts"), dict) else {}
    attention = project.get("queue_attention_plan") if isinstance(project.get("queue_attention_plan"), dict) else {}
    by_action = attention.get("by_action") if isinstance(attention.get("by_action"), dict) else {}
    worker_action = project.get("worker_run_action") if isinstance(project.get("worker_run_action"), dict) else {}
    warnings = [item for item in project.get("warnings") or [] if item]
    owner_required = safe_int(by_action.get("owner_required"))
    environment_required = safe_int(by_action.get("environment_required")) + safe_int(
        by_action.get("role_actionable_after_infra")
    )
    candidate_count = safe_int(worker_action.get("candidate_count"))
    blockers = [str(item) for item in worker_action.get("blockers") or [] if str(item).strip()]
    active = safe_int(counts.get("active_total", counts.get("active")))
    if warnings:
        return "Требует проверки", "statistics-state-critical", str(warnings[0])
    if owner_required:
        return "Нужно решение", "statistics-state-warning", f"Решение владельца требуется для {owner_required} задач"
    if environment_required:
        return "Ждёт среду", "statistics-state-warning", f"Среда блокирует {environment_required} задач"
    if candidate_count and blockers:
        return "Подготовлено, но заблокировано", "statistics-state-warning", "; ".join(blockers[:2])
    if worker_action.get("ready") and candidate_count:
        return "Можно запускать", "statistics-state-active", f"Готово к выполнению: {candidate_count}"
    if active:
        return "В работе", "statistics-state-active", f"Сейчас выполняется: {active}"
    return "Ожидание", "statistics-state-idle", "Активное выполнение сейчас не зафиксировано"


def project_flow_summary(project: dict[str, Any]) -> dict[str, int]:
    direct = project.get("task_flow_last_24h")
    if isinstance(direct, dict):
        return {key: safe_int(direct.get(key)) for key in ("prepared", "executed", "integrated", "finalized", "failed")}
    counts = project.get("counts") if isinstance(project.get("counts"), dict) else {}
    outcomes = project.get("task_outcomes_last_24h") if isinstance(project.get("task_outcomes_last_24h"), dict) else {}
    return task_flow_last_24h(counts.get("role_outcomes"), outcomes)


def project_overview_card(project: dict[str, Any]) -> str:
    project_id = str(project.get("project_id") or "")
    name = str(project.get("name") or project_id or "Проект")
    counts = project.get("counts") if isinstance(project.get("counts"), dict) else {}
    worker_action = project.get("worker_run_action") if isinstance(project.get("worker_run_action"), dict) else {}
    outcomes = project.get("task_outcomes_last_24h") if isinstance(project.get("task_outcomes_last_24h"), dict) else {}
    state_label, state_class, state_note = project_overview_state(project)
    active_backlog = safe_int(counts.get("active_queue_total", live_backlog_total(counts)))
    return (
        '<article class="project-overview-card">'
        '<div class="project-overview-head"><div>'
        f'<h3><a class="project-task-link" href="/project/{quote(project_id)}">{html.escape(name)}</a></h3>'
        f'<p>{html.escape(str(project.get("github_repo") or project.get("base_branch") or project_id))}</p>'
        '</div>'
        f'<span class="project-state {html.escape(state_class)}">{html.escape(state_label)}</span></div>'
        '<div class="project-kpi-grid">'
        f'<div class="project-kpi"><span>Активный backlog</span><strong>{active_backlog}</strong></div>'
        f'<div class="project-kpi"><span>Подготовлено Worker</span><strong>{safe_int(worker_action.get("candidate_count"))}</strong></div>'
        f'<div class="project-kpi"><span>В работе</span><strong>{safe_int(counts.get("active_total", counts.get("active")))}</strong></div>'
        f'<div class="project-kpi"><span>Завершено 24ч</span><strong>{safe_int(outcomes.get("completed"))}</strong></div>'
        '</div>'
        f'<p class="project-card-note">{html.escape(state_note)}</p>'
        '<div class="project-actions">'
        f'<a class="button" href="/project/{quote(project_id)}">Сводка проекта</a>'
        f'<a class="button" href="/chat?project_id={quote(project_id)}">Открыть чат</a>'
        '</div>'
        '</article>'
    )


def project_statistics_panel(snapshot: dict[str, Any]) -> str:
    projects = sorted(
        [item for item in snapshot.get("projects") or [] if isinstance(item, dict)],
        key=project_sort_key,
    )
    cards = "".join(project_overview_card(project) for project in projects)
    empty = '<div class="project-empty">Проекты ещё не опубликованы в текущем snapshot.</div>'
    content = f'<div class="project-overview-grid">{cards}</div>' if cards else empty
    return (
        '<section class="statistics-host-panel" aria-labelledby="project-statistics-title">'
        '<div class="statistics-section-head"><div><div class="statistics-eyebrow">Проекты</div>'
        '<h2 id="project-statistics-title">Где сейчас нужна работа</h2>'
        '<p>Короткая сводка по движению, готовности и блокировкам каждого проекта.</p>'
        '</div></div>'
        f'{content}'
        '</section>'
    )


def project_attention_items(project: dict[str, Any], limit: int = 6) -> list[dict[str, Any]]:
    plan = project.get("queue_attention_plan") if isinstance(project.get("queue_attention_plan"), dict) else {}
    items = [item for item in plan.get("items") or [] if isinstance(item, dict)]
    priority = {
        "owner_required": 0,
        "environment_required": 1,
        "role_actionable_after_infra": 2,
        "role_rework": 3,
        "unknown": 4,
    }
    return sorted(
        items,
        key=lambda item: (
            priority.get(str(item.get("action") or "unknown"), 9),
            str(item.get("task_id") or ""),
        ),
    )[:limit]


def project_attention_card(project_id: str, item: dict[str, Any]) -> str:
    task_id = str(item.get("task_id") or "Без ID")
    title = str(item.get("title") or item.get("reason") or "Требуется внимание")
    reason = str(item.get("reason") or item.get("next_action") or "Причина не опубликована")
    action = str(item.get("action") or "unknown")
    kind_labels = {
        "owner_required": "Решение владельца",
        "environment_required": "Нужна среда",
        "role_actionable_after_infra": "После подготовки среды",
        "role_rework": "Ролевая доработка",
        "unknown": "Требуется разбор",
    }
    chat_url = f"/chat?project_id={quote(project_id)}&task_id={quote(task_id)}"
    return (
        '<article class="project-attention-item">'
        '<header>'
        f'<code>{html.escape(task_id)}</code>'
        f'<span class="project-attention-kind">{html.escape(kind_labels.get(action, "Требуется внимание"))}</span>'
        '</header>'
        f'<h3>{html.escape(title)}</h3>'
        f'<p>{html.escape(reason)}</p>'
        f'<a href="{html.escape(chat_url)}">Обсудить задачу в чате →</a>'
        '</article>'
    )


def project_next_task_items(project: dict[str, Any], limit: int = 6) -> list[dict[str, Any]]:
    worker_action = project.get("worker_run_action") if isinstance(project.get("worker_run_action"), dict) else {}
    next_ids = [str(item) for item in worker_action.get("next_task_ids") or [] if str(item).strip()]
    by_id = {
        str(item.get("id") or item.get("task_id") or ""): item
        for item in project.get("tasks") or []
        if isinstance(item, dict)
    }
    return [
        by_id.get(task_id, {"id": task_id, "title": task_id, "status": "planned"})
        for task_id in next_ids[:limit]
    ]


def render_project_overview(snapshot: dict[str, Any], project_id: str) -> tuple[int, str]:
    project = find_project(snapshot, project_id)
    if not project:
        return HTTPStatus.NOT_FOUND, layout("Проект не найден", snapshot, "<h1>Проект не найден</h1>")

    name = str(project.get("name") or project_id)
    counts = project.get("counts") if isinstance(project.get("counts"), dict) else {}
    worker_action = project.get("worker_run_action") if isinstance(project.get("worker_run_action"), dict) else {}
    attention = project.get("queue_attention_plan") if isinstance(project.get("queue_attention_plan"), dict) else {}
    by_action = attention.get("by_action") if isinstance(attention.get("by_action"), dict) else {}
    outcomes = project.get("task_outcomes_last_24h") if isinstance(project.get("task_outcomes_last_24h"), dict) else {}
    flow = project_flow_summary(project)
    state_label, state_class, state_note = project_overview_state(project)
    active_backlog = safe_int(counts.get("active_queue_total", live_backlog_total(counts)))
    observed = short_datetime(project.get("observed_at")) or short_datetime(snapshot.get("source_generated_at"))
    attention_items = project_attention_items(project)
    attention_html = "".join(project_attention_card(project_id, item) for item in attention_items)
    if not attention_html:
        attention_html = '<div class="project-empty">Срочных задач внимания сейчас нет.</div>'
    next_items = project_next_task_items(project)
    next_html = "".join(
        project_attention_card(
            project_id,
            {
                "task_id": item.get("id") or item.get("task_id"),
                "title": item.get("title"),
                "reason": item.get("reason") or "Подготовлено как следующий кандидат выполнения.",
                "action": "unknown",
            },
        )
        for item in next_items
    )
    if not next_html:
        next_html = '<div class="project-empty">Следующие кандидаты выполнения не опубликованы.</div>'
    blocker_text = "; ".join(str(item) for item in worker_action.get("blockers") or [] if str(item).strip())
    blocker_html = (
        f'<div class="project-blocker"><strong>Почему Worker не запускается:</strong> {html.escape(blocker_text)}</div>'
        if blocker_text
        else ""
    )
    body = (
        '<div class="project-page">'
        '<section class="statistics-hero project-hero"><div>'
        '<div class="statistics-eyebrow">Сводка проекта</div>'
        f'<h1>{html.escape(name)}</h1>'
        f'<p>{html.escape(state_note)}. Здесь показаны только показатели для ежедневного решения; '
        'полный технический отчёт сохранён отдельно.</p>'
        '<div class="project-actions">'
        f'<a class="button" href="/chat?project_id={quote(project_id)}">Открыть чат проекта</a>'
        f'<a class="button" href="/project/{quote(project_id)}/technical">Технический отчёт</a>'
        f'<a class="button" href="/project/{quote(project_id)}/health">Проверка здоровья</a>'
        '</div></div>'
        '<div class="project-hero-meta">'
        f'<div><span>Состояние</span><strong class="{html.escape(state_class)}">{html.escape(state_label)}</strong></div>'
        f'<div><span>Последний снимок</span><strong>{html.escape(observed or "Нет данных")}</strong></div>'
        f'<div><span>Ветка</span><strong>{html.escape(str(project.get("base_branch") or project.get("base_ref") or "—"))}</strong></div>'
        '</div></section>'
        '<section class="project-panel">'
        '<h2>Текущее состояние</h2>'
        '<p class="project-panel-lead">Один набор взаимно сопоставимых показателей очереди и выполнения.</p>'
        '<div class="project-kpi-grid">'
        f'<div class="project-kpi"><span>Активный backlog</span><strong>{active_backlog}</strong></div>'
        f'<div class="project-kpi"><span>Подготовлено Worker</span><strong>{safe_int(worker_action.get("candidate_count"))}</strong></div>'
        f'<div class="project-kpi"><span>В работе</span><strong>{safe_int(counts.get("active_total", counts.get("active")))}</strong></div>'
        f'<div class="project-kpi"><span>Ожидают подготовки</span><strong>{safe_int(counts.get("task_packet"))}</strong></div>'
        f'<div class="project-kpi"><span>Предыдущая задача</span><strong>{safe_int(by_action.get("role_rework"))}</strong></div>'
        f'<div class="project-kpi"><span>Среда</span><strong>{safe_int(by_action.get("environment_required")) + safe_int(by_action.get("role_actionable_after_infra"))}</strong></div>'
        f'<div class="project-kpi"><span>Решение человека</span><strong>{safe_int(by_action.get("owner_required"))}</strong></div>'
        f'<div class="project-kpi"><span>Завершено 24ч</span><strong>{safe_int(outcomes.get("completed"))}</strong></div>'
        '</div>'
        f'{blocker_html}'
        '</section>'
        '<section class="project-panel">'
        '<h2>Движение за 24 часа</h2>'
        '<p class="project-panel-lead">Этапы считаются отдельно: одна задача может пройти несколько этапов.</p>'
        '<div class="project-flow-grid">'
        f'<div class="project-flow-step"><strong>{safe_int(flow.get("prepared"))}</strong><span>Подготовлено Dispatcher</span></div>'
        f'<div class="project-flow-step"><strong>{safe_int(flow.get("executed"))}</strong><span>Выполнено Worker</span></div>'
        f'<div class="project-flow-step"><strong>{safe_int(flow.get("integrated"))}</strong><span>Интегрировано</span></div>'
        f'<div class="project-flow-step"><strong>{safe_int(flow.get("finalized"))}</strong><span>Финализировано</span></div>'
        '</div>'
        f'<div class="statistics-freshness">Ошибок последних попыток: {safe_int(flow.get("failed"))}</div>'
        '</section>'
        '<div class="project-page-grid">'
        '<section class="project-panel"><h2>Что требует внимания</h2>'
        '<p class="project-panel-lead">Сначала решения владельца и среда, затем зависимые ролевые доработки.</p>'
        f'<div class="project-attention-list">{attention_html}</div></section>'
        '<section class="project-panel"><h2>Следующие кандидаты</h2>'
        '<p class="project-panel-lead">Задачи, которые очередь предлагает подготовить или выполнить следующими.</p>'
        f'<div class="project-attention-list">{next_html}</div></section>'
        '</div>'
        '<section class="project-panel">'
        '<div class="statistics-section-head"><div><h2>Технические данные</h2>'
        '<p>Пути, роли, worktree, команды и полные таблицы не удалены.</p></div>'
        '<div class="project-actions">'
        f'<a class="button" href="/project/{quote(project_id)}/technical">Открыть полный отчёт</a>'
        f'<a class="button" href="/api/project/{quote(project_id)}.json">JSON проекта</a>'
        '</div></div></section>'
        '</div>'
    )
    return HTTPStatus.OK, layout(f"{name} · Сводка", snapshot, body)


def render_statistics_index(snapshot: dict[str, Any]) -> str:
    summary = snapshot.get("summary") if isinstance(snapshot.get("summary"), dict) else {}
    generated_at = short_datetime(snapshot.get("generated_at")) or str(snapshot.get("generated_at") or "Нет данных")
    source_generated_at = short_datetime(snapshot.get("source_generated_at"))
    source_line = f"Источник обновлён {source_generated_at}" if source_generated_at else "Live snapshot"
    body = (
        '<div class="statistics-page">'
        '<section class="statistics-hero">'
        "<div>"
        '<div class="statistics-eyebrow">Новая сводная страница</div>'
        "<h1>Статистика AiStudio</h1>"
        "<p>Спокойная обзорная страница для оценки состояния системы. "
        "Рабочие команды, очереди и технические таблицы сохранены в Old version.</p>"
        "</div>"
        '<div class="statistics-updated">Последнее обновление'
        f'<strong>{html.escape(generated_at)}</strong>'
        f'<span>{html.escape(source_line)}</span>'
        "</div>"
        "</section>"
        f'{task_statistics_panel(snapshot)}'
        f'{project_statistics_panel(snapshot)}'
        f'{remote_pc_load_statistics_panel(summary.get("resource_load"), summary.get("resource_activity"))}'
        f'{vps_fleet_statistics_panel(snapshot.get("vps_fleet"))}'
        f'{weekly_codex_limits_statistics_panel(snapshot.get("codex_limit_consensus"))}'
        '<section class="statistics-coming">'
        '<div class="statistics-eyebrow">Заполняем постепенно</div>'
        "<h2>Следующие статистические блоки появятся здесь</h2>"
        "<p>Каждый новый блок будет подключаться только к проверенному источнику данных. "
        "Если нужного backend-контракта ещё нет, на странице появится статус «В разработке» "
        "и номер задачи автоматизации.</p>"
        "</section>"
        "</div>"
    )
    return layout("Статистика AiStudio", snapshot, body)


def render_index(snapshot: dict[str, Any]) -> str:
    summary = snapshot.get("summary") or {}
    counts = summary.get("task_counts") or {}
    added = summary.get("tasks_added") or {}
    completed_recent = summary.get("tasks_completed_recent") or {}
    cards = []
    projects = sorted(snapshot.get("projects", []), key=project_sort_key)
    for project in projects:
        latest = (project.get("runs") or {}).get("latest") or {}
        latest_text = latest.get("status") or "нет запусков"
        project_added = project.get("added_counts") or {}
        project_name = html.escape(str(project.get("name")))
        project_repo = html.escape(str(project.get("github_repo") or project.get("local_path")))
        cards.append(
            '<section class="project-card">'
            f'<div class="project-title"><span class="project-name">{project_name}</span><span class="project-repo">{project_repo}</span></div>'
            f'{project_statusline(project)}'
            f'<div class="muted">Добавлено: {safe_int(project_added.get("today"))} сегодня, {safe_int(project_added.get("week"))} за неделю</div>'
            f'<div class="muted">Запуски: {safe_int((project.get("runs") or {}).get("total"))}, последний: {html.escape(str(latest_text))}</div>'
            f'{automation_line(project)}'
            f'<p><a class="button" href="/project/{html.escape(str(project.get("project_id")))}">Открыть отчет</a> '
            f'<a class="button" href="/project/{html.escape(str(project.get("project_id")))}/health">Только здоровье</a></p>'
            "</section>"
        )
    warnings = "".join(f'<div class="warning">{html.escape(str(item))}</div>' for item in snapshot.get("warnings", []))
    projects_toggle = (
        '<button class="button show-projects" type="button" data-toggle-projects="#projects-grid">Показать все проекты</button>'
        if len(cards) > 4
        else ""
    )
    body = (
        '<div class="topline"><div>'
        "<h1>Сводка по проектам</h1>"
        f'<div class="muted">Обновлено: <code>{html.escape(str(snapshot.get("generated_at")))}</code></div>'
        '</div><a class="button" href="/api/summary.json">JSON сводки</a></div>'
        f'{tasks_summary_panel(counts, added, completed_recent, summary.get("human_attention_total", counts.get("human", 0)), summary.get("infra_blocked_attention_total", 0), summary.get("worker_host_blocked_candidates", 0), summary.get("active_total", counts.get("active", 0)), summary.get("worktree_active_total", 0), summary.get("role_outcomes"))}'
        f'{task_origin_cards(summary.get("task_origin_active_breakdown") or summary.get("task_origin_breakdown"))}'
        f'{local_llm_pre_worker_cards(summary.get("local_llm_pre_worker"))}'
        f'{worker_run_action_cards(summary.get("worker_run_action"), "Worker run")}'
        f'{queue_attention_plan_cards(summary.get("queue_attention_plan"), "План внимания очереди")}'
        f'{migration_goal_cards(snapshot.get("migration_goal"))}'
        f'{automation_controller_cards(snapshot.get("automation_controller"), summary.get("automation_controller"))}'
        f'{command_bus_cards(snapshot.get("command_bus"), summary.get("command_bus"))}'
        f'{automation_bridge_cards(summary.get("automation_bridge"))}'
        f'{dashboard_chat_panel()}'
        f'{automation_worktree_cards(summary.get("automation_worktree_plan"))}'
        f'{resource_load_panel(summary.get("resource_load", {}))}'
        "<h2>Сравнение моделей</h2>"
        '<div class="muted">По % лимита показывается оценка из estimate.json (model/5ч/weekly), если есть данные.</div>'
        f'{model_cost_analytics_table(snapshot.get("model_cost_analytics", []))}'
        f'{codex_limit_bars(snapshot.get("codex_limits", []), snapshot.get("codex_limit_consensus"))}'
        f'<h2>Проекты ({safe_int(summary.get("project_count"))})</h2>'
        '<div class="projects-wrap">'
        f'<div id="projects-grid" class="projects collapsed">{"".join(cards)}</div>'
        f'{projects_toggle}'
        '</div>'
        "<h2>Автоматизация</h2>"
        '<div class="run-controls">'
        '<button class="button" data-run-automation="worktrees">Worktrees dry-run</button>'
        '</div>'
        '<div class="run-feedback" id="automation-feedback"></div>'
        f'{automation_timers_table((snapshot.get("automation_status") or {}).get("timers", []), snapshot.get("automation_progress", []))}'
        f"{warnings}"
        "<h2>Журнал действий</h2>"
        f'{activity_log_table(snapshot.get("activity_log", []))}'
    )
    return layout("Agent Dashboard", snapshot, body)



def command_rows(commands: list[dict[str, Any]]) -> str:
    if not commands:
        return '<p class="muted">Команд пока нет.</p>'
    rows = []
    for item in commands[:8]:
        rows.append(
            '<tr>'
            f'<td><code>{html.escape(str(item.get("command_id") or ""))}</code></td>'
            f'<td>{html.escape(str(item.get("state") or ""))}</td>'
            f'<td>{html.escape(str(item.get("mode") or ""))}</td>'
            f'<td>{html.escape(str(item.get("role") or ""))}</td>'
            f'<td>{html.escape(str(item.get("task_id") or ""))}</td>'
            f'<td>{html.escape(str(item.get("updated_at") or item.get("created_at") or ""))}</td>'
            '</tr>'
        )
    return '<table><thead><tr><th>ID</th><th>State</th><th>Mode</th><th>Role</th><th>Task</th><th>Updated</th></tr></thead><tbody>' + ''.join(rows) + '</tbody></table>'

def render_project_health(snapshot: dict[str, Any], project_id: str) -> tuple[int, str]:
    project = find_project(snapshot, project_id)
    if not project:
        return HTTPStatus.NOT_FOUND, layout("Проект не найден", snapshot, "<h1>Проект не найден</h1>")

    counts = project.get("counts") or {}
    active_age = project.get("active_task_age") or {}
    doctor = project.get("workspace_doctor") or {}
    command_registry = project.get("command_registry") or {}
    automation_worktree_plan = project.get("automation_worktree_plan") or {}

    command_registry_badge = ""
    if command_registry:
        if command_registry.get("available"):
            root_is_git = bool(command_registry.get("command_root_is_git_worktree"))
            root_label = "git" if root_is_git else "not git"
            root_class = "muted" if root_is_git else "warning"
            github_access = command_registry.get("github_access") if isinstance(command_registry.get("github_access"), dict) else {}
            github_badge = ""
            if github_access:
                access_ok = github_access.get("ok")
                access_class = "muted" if access_ok else "warning"
                access_state = "ok" if access_ok else str(github_access.get("reason") or "unknown")
                github_badge = (
                    f'<div class="{access_class}">GitHub access ({html.escape(access_state)}): '
                    f'{html.escape(str(github_access.get("repo") or ""))} '
                    f'{html.escape(str(github_access.get("branch") or ""))}</div>'
                )
            command_registry_badge = (
                f'<div class="{root_class}">Command root ({root_label}): '
                f'{html.escape(str(command_registry.get("command_root") or command_registry.get("automation_path") or command_registry.get("local_path") or ""))}</div>'
                f'{github_badge}'
            )
        else:
            command_registry_badge = (
                f'<div class="warning">Command registry: {html.escape(str(command_registry.get("reason") or "not available") )}</div>'
            )

    health_score = safe_int(doctor.get("health_score"))
    health_threshold = safe_int(doctor.get("health_threshold") or 100)
    health_status = str(doctor.get("status") or "unknown")
    health_class = "completed" if health_status == "healthy" else "human" if health_status == "attention" else ""
    deduced_items = doctor.get("deductions") if isinstance(doctor.get("deductions"), list) else []
    deduced_count = len(deduced_items)
    deduced_first = html.escape(str(deduced_items[0].get("code"))) if deduced_items else "-"

    checkouts = doctor.get("checkouts") if isinstance(doctor.get("checkouts"), list) else []
    checkouts_rows = []
    for checkout in checkouts:
        if not isinstance(checkout, dict):
            continue
        checkouts_rows.append(
            "<tr>"
            f'<td>{html.escape(str(checkout.get("role") or "-"))}</td>'
            f'<td>{html.escape(str(checkout.get("branch") or "-"))}</td>'
            f'<td>{html.escape(str(checkout.get("head") or "-")[:12])}</td>'
            f'<td>{html.escape(str(checkout.get("path") or "-"))}</td>'
            "</tr>"
        )
    checkouts_table = (
        '<div class="scroll-panel compact">'
        '<table><thead><tr><th>Роль</th><th>Ветка</th><th>HEAD</th><th>Путь</th></tr></thead>'
        f'<tbody>{"".join(checkouts_rows) or "<tr><td colspan=4>Workspace doctor не вернул checkout</td></tr>"}</tbody></table>'
        '</div>'
    )

    lock_rows = []
    for lock in (project.get("locks") or {}).get("items") or []:
        if not isinstance(lock, dict):
            continue
        lock_rows.append(
            "<tr>"
            f'<td>{html.escape(str(lock.get("task_id") or "-"))}</td>'
            f'<td>{html.escape(str(lock.get("title") or "-"))}</td>'
            f'<td>{html.escape(str(lock.get("state") or lock.get("status") or "-"))}</td>'
            f'<td>{html.escape(str(lock.get("by") or lock.get("owner") or lock.get("worker_id") or "-"))}</td>'
            f'<td>{html.escape(str(lock.get("expires_at") or "-"))}</td>'
            "</tr>"
        )
    locks_table = (
        '<div class="scroll-panel compact">'
        '<table><thead><tr><th>Task</th><th>Task title</th><th>State</th><th>By</th><th>Expires</th></tr></thead>'
        f'<tbody>{"".join(lock_rows) or "<tr><td colspan=5>Active locks not found</td></tr>"}</tbody></table>'
        '</div>'
    )

    run_rows = []
    for run in (project.get("runs") or {}).get("recent", []):
        run_rows.append(
            "<tr>"
            f'<td><code>{html.escape(str(run.get("run_id") or ""))}</code></td>'
            f'<td>{html.escape(str(run.get("task_id") or ""))}</td>'
            f'<td>{html.escape(str(run.get("worker_id") or ""))}</td>'
            f'<td>{html.escape(str(run.get("agent_role") or ""))}</td>'
            f'<td>{html.escape(str(run.get("status") or ""))}</td>'
            f'<td>{html.escape(str(run.get("started_at") or ""))}</td>'
            f'<td>{html.escape(str(run.get("_run_dir") or ""))}</td>'
            "</tr>"
        )

    cleanup_state = str(automation_worktree_plan.get("action") or "unknown")
    blockers = automation_worktree_plan.get("blockers") if isinstance(automation_worktree_plan.get("blockers"), list) else []
    blockers_text = "нет" if not blockers else ", ".join(str(item) for item in blockers)

    body = (
        '<div class="topline"><div>'
        f'<h1>{html.escape(str(project.get("name")))} · Здоровье</h1>'
        f'<div class="muted">{html.escape(str(project.get("local_path")))}</div>'
        f'{command_registry_badge}'
        '</div>'
        f'<a class="button" href="/project/{html.escape(project_id)}">Открыть полный отчет</a>'
        f'<a class="button" href="/api/project/{html.escape(project_id)}.json">JSON проекта</a>'
        '</div>'
        f'{project_statusline(project)}'
        '<section class="summary-panel">'
        '<h2>Сводка здоровья</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Health score", f"{health_score}/{health_threshold}", health_class)}'
        f'{summary_item("Health status", health_status, health_class)}'
        f'{summary_item("Задач всего", safe_int(counts.get("total")))}'
        f'{summary_item("Готово", safe_int(counts.get("completed")))}'
        f'{summary_item("В работе", safe_int(counts.get("active")))}'
        f'{summary_item("Требует человека", safe_int(counts.get("human")), "human")}'
        f'{summary_item("Active locks", safe_int((project.get("locks") or {}).get("active")))}'
        '</div>'
        '</section>'
        '<section class="summary-panel">'
        '<h2>Workspace doctor</h2>'
        '<div class="summary-strip">'
        f'{summary_item("Порог", safe_int(health_threshold), "")}'
        f'{summary_item("Deduction", deduced_count, "human" if deduced_count else "")}'
        f'{summary_item("Deduction sample", deduced_first)}'
        f'{summary_item("Старый active lock", active_age.get("oldest_age_hours") or "нет")}'
        f'{summary_item("Старые locks", safe_int(active_age.get("stale_lock_count")), "human" if safe_int(active_age.get("stale_lock_count")) else "")}'
        f'{summary_item("Cleanup action", cleanup_state)}'
        '</div>'
        f'<div class="muted">Blockers: {html.escape(blockers_text)}</div>'
        '</section>'
        f'{role_state_cards(project.get("role_state") or {})}'
        '<h2>Workspace doctor: checkouts</h2>'
        f'{checkouts_table}'
        '<h2>Locks</h2>'
        f'{locks_table}'
        '<h2>Действия человека</h2>'
        f'{human_needed_table(project.get("human_needed", []))}'
        '<h2>Последние запуски</h2>'
        '<div class="scroll-panel compact">'
        '<table><thead><tr><th>Run</th><th>Task</th><th>Worker</th><th>Role</th><th>Status</th><th>Start</th><th>Folder</th></tr></thead>'
        f'<tbody>{"".join(run_rows) or "<tr><td colspan=7>Запусков пока не было</td></tr>"}</tbody></table>'
        '</div>'
        '<h2>Команды</h2>'
        f'{command_rows(project.get("commands") or [])}'
        '<h2>Журнал действий</h2>'
        f'{activity_log_table(project_activity_log(snapshot, project))}'
    )
    return HTTPStatus.OK, layout(str(project.get("name")), snapshot, body)


def render_project(snapshot: dict[str, Any], project_id: str) -> tuple[int, str]:
    project = find_project(snapshot, project_id)
    if not project:
        return HTTPStatus.NOT_FOUND, layout("Проект не найден", snapshot, "<h1>Проект не найден</h1>")

    architect_rows = []
    dispatcher_rows = []
    worker_rows = []
    integrator_rows = []
    finalizer_rows = []
    action_rows = []
    infra_blocked_rows = []
    task_packet_rows = []
    postponed_rows = []
    completed_rows = []
    attention_action_by_task = {
        str(item.get("task_id") or ""): str(item.get("action") or "")
        for item in ((project.get("queue_attention_plan") or {}).get("items") or [])
        if isinstance(item, dict)
    }

    def append_lane_row(lane: str, row: str) -> None:
        if lane == "architect":
            architect_rows.append(row)
        elif lane == "dispatcher":
            dispatcher_rows.append(row)
        elif lane == "integrator":
            integrator_rows.append(row)
        elif lane == "finalizer":
            finalizer_rows.append(row)
        else:
            worker_rows.append(row)

    for task in project.get("tasks", []):
        status = str(task.get("status") or "")
        status_source = str(task.get("status_source") or "queue")
        status_raw = str(task.get("status_raw") or status)
        status_badge = html.escape(status)
        token_cost_value = task.get("token_cost")
        token_cost_text = "-" if token_cost_value is None else f"~{safe_int(token_cost_value)}"
        if status_source != "queue":
            status_badge = (
                f'<span title="Скорректировано по данным рантайма: {html.escape(status_source)}; '
                f'очередь: {html.escape(status_raw)}">{status_badge}</span>'
            )
        row = (
            "<tr>"
            f'<td><code>{html.escape(str(task.get("id")))}</code></td>'
            f'<td>{html.escape(str(task.get("title")))}</td>'
            f'<td class="{html.escape(str(task.get("bucket")))}">{status_badge}</td>'
            f'<td>{html.escape(str(task.get("size") or "-"))}</td>'
            f'<td>{html.escape(str(task.get("priority") or ""))}</td>'
            f'<td>{html.escape(str(task.get("worker_id") or ""))}</td>'
            f'<td>{html.escape(short_datetime(task.get("started_at")) or str(task.get("started_at") or ""))}</td>'
            f'<td>{html.escape(short_datetime(task.get("lock_expires_at")) or str(task.get("lock_expires_at") or ""))}</td>'
            f'<td>{html.escape(str(task.get("reason") or ""))}</td>'
            f'<td>{html.escape(token_cost_text)}</td>'
            "</tr>"
        )
        if task.get("bucket") == "completed":
            completed_rows.append(row)
        else:
            if task.get("bucket") == "task_packet":
                task_packet_rows.append(row)
            elif task.get("bucket") == "postponed":
                postponed_rows.append(row)
            elif task.get("bucket") == "human":
                if task.get("attention_suppressed"):
                    continue
                attention_action = attention_action_by_task.get(str(task.get("id") or ""))
                if attention_action in {"environment_required", "role_actionable_after_infra"}:
                    infra_blocked_rows.append(row)
                elif attention_action == "role_rework":
                    append_lane_row(str(task.get("lane") or task.get("attention_lane") or "worker"), row)
                else:
                    action_rows.append(row)
            else:
                lane = str(task.get("lane") or task.get("attention_lane") or "worker")
                append_lane_row(lane, row)
    run_rows = []
    for run in (project.get("runs") or {}).get("recent", []):
        run_rows.append(
            "<tr>"
            f'<td><code>{html.escape(str(run.get("run_id")))}</code></td>'
            f'<td>{html.escape(str(run.get("task_id")))}</td>'
            f'<td>{html.escape(str(run.get("worker_id")))}</td>'
            f'<td>{html.escape(str(run.get("agent_role") or ""))}</td>'
            f'<td>{html.escape(str(run.get("model") or "unknown"))}</td>'
            f'<td>{html.escape(str(run.get("codex_version") or ""))}</td>'
            f'<td>{html.escape(str(run.get("status")))}</td>'
            f'<td>{html.escape(str(run.get("started_at")))}</td>'
            f'<td>{html.escape(str(run.get("_run_dir")))}</td>'
            "</tr>"
        )
    warnings = "".join(f'<div class="warning">{html.escape(str(item))}</div>' for item in project.get("warnings", []))
    automation = project.get("automation") or {}
    added = project.get("added_counts") or {}
    active_directives = (project.get("owner_directives") or {}).get("items", [])
    worktrees = project.get("worktrees") or []
    stale_worktrees = project.get("stale_worktrees") or []
    manual = project.get("manual_run") or {}
    manual_active = manual_run_active(manual)
    active_age = project.get("active_task_age") or {}
    command_registry = project.get("command_registry") or {}
    command_registry_badge = ""
    if command_registry:
        if command_registry.get("available"):
            root_is_git = bool(command_registry.get("command_root_is_git_worktree"))
            root_class = "muted" if root_is_git else "warning"
            root_state = "git" if root_is_git else "not git"
            github_access = command_registry.get("github_access") if isinstance(command_registry.get("github_access"), dict) else {}
            github_badge = ""
            if github_access:
                access_ok = github_access.get("ok")
                access_class = "muted" if access_ok else "warning"
                access_state = "ok" if access_ok else str(github_access.get("reason") or "unknown")
                github_badge = (
                    f'<div class="{access_class}">GitHub access ({html.escape(access_state)}): '
                    f'{html.escape(str(github_access.get("repo") or ""))} '
                    f'{html.escape(str(github_access.get("branch") or ""))}</div>'
                )
            command_registry_badge = (
                f'<div class="{root_class}">Command root ({root_state}): '
                f'{html.escape(str(command_registry.get("command_root") or command_registry.get("automation_path") or command_registry.get("local_path") or ""))}</div>'
                f'{github_badge}'
            )
        else:
            command_registry_badge = (
                f'<div class="warning">Command registry: '
                f'{html.escape(str(command_registry.get("reason") or "not available"))}</div>'
            )
    oldest_active_age = active_age.get("oldest_age_hours")
    oldest_active_text = "нет"
    if oldest_active_age is not None:
        oldest_active_text = f'{oldest_active_age}ч'
    project_counts = project.get("counts") or {}
    active_backlog = project_counts.get("active_queue_total", live_backlog_total(project_counts))
    manual_badge = ""
    if manual_active:
        manual_badge = (
            f'<div class="muted">Ручной прогон: '
            f'{html.escape(str(manual.get("mode", "all")))} '
            f'({html.escape(str(manual.get("state", "running")))} )</div>'
        )
    run_controls_blocked = False
    run_controls_reason = ""
    if command_registry and command_registry.get("available"):
        root_is_git = bool(command_registry.get("command_root_is_git_worktree"))
        github_access = command_registry.get("github_access") if isinstance(command_registry.get("github_access"), dict) else {}
        github_failed = github_access.get("ok") is False or github_access.get("reason") == "github_repo_missing"
        run_controls_blocked = not root_is_git or github_failed
        if not root_is_git:
            run_controls_reason = "Command root is not a git worktree"
        elif github_failed:
            run_controls_reason = "GitHub access is not available from this host"
    worker_run_blocked = runner_readiness_blocks_worker_run(project.get("runner_readiness") if isinstance(project.get("runner_readiness"), dict) else None)
    worker_run_block_reason = runner_readiness_worker_block_reason(project.get("runner_readiness") if isinstance(project.get("runner_readiness"), dict) else None) if worker_run_blocked else ""

    def run_button(mode: str, label: str) -> str:
        disabled = (run_controls_blocked and mode != "release_locks") or (worker_run_blocked and mode in {"all", "workers"})
        disabled_attr = " disabled" if disabled else ""
        reason = worker_run_block_reason if worker_run_blocked and mode in {"all", "workers"} else run_controls_reason
        title_attr = f' title="{html.escape(reason)}"' if disabled and reason else ""
        return (
            f'<button class="button" data-run-project data-project-id="{html.escape(str(project_id))}" '
            f'data-run-mode="{html.escape(mode)}"{disabled_attr}{title_attr}>{html.escape(label)}</button>'
        )

    body = (
        '<div class="topline"><div>'
        f'<h1>{html.escape(str(project.get("name")))}</h1>'
        f'<div class="muted">{html.escape(str(project.get("local_path")))}</div>'
        f'{command_registry_badge}'
        f'</div><a class="button" href="/api/project/{html.escape(str(project_id))}.json">JSON проекта</a></div>'
        f'{manual_badge}'
        f'{project_statusline(project)}'
        f'{role_outcome_cards((project.get("counts") or {}).get("role_outcomes"), "Результаты по ролям проекта")}'
        f'{queue_attention_plan_cards(project.get("queue_attention_plan"), "План внимания проекта")}'
        f'{worker_run_action_cards(project.get("worker_run_action"), "Worker run проекта")}'
        f'{project_automation_bridge_cards(project.get("automation_bridge") if isinstance(project.get("automation_bridge"), dict) else None)}'
        f'{role_state_cards(project.get("role_state") or {})}'
        '<div class="run-controls">'
        f'{run_button("all", "Запустить обработку")}'
        f'{run_button("full_intake", "Full intake")}'
        f'{run_button("dispatcher", "Dispatcher")}'
        f'{run_button("workers", "Workers")}'
        f'{run_button("integrator", "Integrator")}'
        f'{run_button("finalizer", "Finalizer")}'
        f'{run_button("model_limit_retries", "Model retries")}'
        f'{run_button("release_locks", "Release locks")}'
        '</div>'
        f'<div class="run-feedback" id="run-feedback-{html.escape(str(project_id))}"></div>'
        '<h2>Команды</h2>'
        f'{command_rows(project.get("commands") or [])}'
        '<section class="metrics wide">'
        f'{metric("Закрыто в истории", completed_ratio(project_counts), "completed")}'
        f'{metric("Активный backlog", safe_int(active_backlog), "waiting" if safe_int(active_backlog) else "")}'
        f'{metric("Активные locks", (project.get("locks") or {}).get("active", 0))}'
        f'{metric("Просроченные active locks", active_age.get("stale_lock_count", 0), "human" if safe_int(active_age.get("stale_lock_count")) else "")}'
        f'{metric("Старейшая активная", oldest_active_text)}'
        f'{metric("Активные директивы", (project.get("owner_directives") or {}).get("active", 0))}'
        f'{metric("Запуски агентов", (project.get("runs") or {}).get("total", 0))}'
        f'{metric("Сигналы человеку", project.get("needs_human_effective", project.get("needs_human", 0)), "human")}'
        f'{metric("Добавлено сегодня", added.get("today", 0))}'
        f'{metric("Добавлено за неделю", added.get("week", 0))}'
        "</section>"
        "<h2>Глобальная автоматизация</h2>"
        '<section class="metrics">'
        f'{metric("Запуск агента по проекту", short_datetime(automation.get("latest_agent_run_at")) or "нет")}'
        f'{metric("Последний scheduler", short_datetime(automation.get("last_scheduler_at")) or "нет")}'
        f'{metric("Следующий запуск", short_datetime(automation.get("next_scheduled_at")) or "нет")}'
        f'{metric("Следующий timer", automation.get("next_scheduled_unit") or "нет")}'
        f'{metric("Источник", automation.get("source") or "нет")}'
        "</section>"
        f'{automation_timers_table(automation.get("timers", []), (snapshot.get("automation_status") or {}).get("progress", []))}'
        f"{warnings}"
        f'<h2>Запросы человеку ({len(project.get("human_needed", []))})</h2>'
        f'{human_needed_table(project.get("human_needed", []))}'
        f'<h2>Архитектор ({len(architect_rows)})</h2>'
        f'{task_table(architect_rows, "Задач для архитектора не найдено")}'
        f'<h2>Диспетчер ({len(dispatcher_rows)})</h2>'
        f'{task_table(dispatcher_rows, "Задач для диспетчера не найдено")}'
        f'<h2>Воркер ({len(worker_rows)})</h2>'
        f'{task_table(worker_rows, "Задач для воркера не найдено")}'
        f'<h2>Интегратор ({len(integrator_rows)})</h2>'
        f'{task_table(integrator_rows, "Задач для интегратора не найдено")}'
        f'<h2>Финализер ({len(finalizer_rows)})</h2>'
        f'{task_table(finalizer_rows, "Задач для финализера не найдено")}'
        f'<h2>Нужно решение/действие ({len(action_rows)} задач, {len(active_directives)} директив)</h2>'
        f'{task_table(action_rows, "Задач, требующих решения или действия, нет")}'
        f'{owner_directives_table(active_directives)}'
        f'<h2>Ждут среды/credentials ({len(infra_blocked_rows)})</h2>'
        f'{task_table(infra_blocked_rows, "Задач, ожидающих среду или credentials, нет")}'
        f'<h2>Нужна подготовка задачи ({len(task_packet_rows)})</h2>'
        f'{task_table(task_packet_rows, "Задач для подготовки диспетчером нет")}'
        f'<h2>Отложенные задачи ({len(postponed_rows)})</h2>'
        f'{task_table(postponed_rows, "Отложенных задач нет")}'
        f'{worktrees_table(worktrees)}'
        f'<h2>Ненастоящие worktree ({len(stale_worktrees)})</h2>'
        f'{worktrees_stale_table(stale_worktrees)}'
        f'<h2>Выполненные задачи ({len(completed_rows)})</h2>'
        f'{task_table(completed_rows, "Выполненных задач не найдено")}'
        "<h2>Последние отчеты агентов</h2>"
        '<div class="scroll-panel compact">'
        '<table><thead><tr><th>Run</th><th>Task</th><th>Worker</th><th>Role</th><th>Model</th><th>Codex</th><th>Status</th><th>Start</th><th>Folder</th></tr></thead>'
        f'<tbody>{"".join(run_rows) or "<tr><td colspan=9>Отчетов запусков не найдено</td></tr>"}</tbody></table>'
        '</div>'
        "<h2>Журнал действий проекта</h2>"
        f'{activity_log_table(project_activity_log(snapshot, project))}'
    )
    return HTTPStatus.OK, layout(str(project.get("name")), snapshot, body)


class DashboardHandler(BaseHTTPRequestHandler):
    runtime_root: Path = Path("~/agent-runtime").expanduser()
    registry_path: Path | None = None
    automation_worktree_root: Path = Path("~/agent-runtime/automation-worktrees").expanduser()
    db_path: Path = Path("~/agent-runtime/dashboard/analytics.sqlite").expanduser()
    snapshot_source_path: Path | None = None
    chat_worker_token: str | None = None
    chat_access_password: str | None = None
    refresh_interval_sec: int = 60
    codex_limit_max_age_minutes: int | None = CODEX_LIMIT_STALE_MINUTES_DEFAULT
    resource_sample_retention_hours: int = SYSTEM_RESOURCE_SAMPLE_RETENTION_HOURS_DEFAULT
    resource_sample_interval_seconds: int = SYSTEM_RESOURCE_SAMPLE_INTERVAL_SECONDS_DEFAULT
    resource_activity_window_minutes: int = SYSTEM_RESOURCE_ACTIVITY_WINDOW_MINUTES_DEFAULT
    resource_sampler_interval_seconds: int = SYSTEM_RESOURCE_SAMPLER_INTERVAL_SECONDS_DEFAULT
    history_enabled: bool = False
    dashboard_snapshot_retention: int = DASHBOARD_SNAPSHOT_RETENTION_DEFAULT
    project_snapshot_retention: int = PROJECT_SNAPSHOT_RETENTION_DEFAULT

    def log_message(self, format: str, *args: Any) -> None:
        return

    def snapshot(self) -> dict[str, Any]:
        if self.snapshot_source_path is not None:
            data, error = load_json(self.snapshot_source_path)
            if isinstance(data, dict):
                adapted_data = adapt_snapshot_source_payload(data)
                snapshot = overlay_snapshot_runtime_state(
                    overlay_snapshot_role_state(
                        annotate_snapshot_command_registry(
                            normalize_snapshot_role_outcomes(adapted_data),
                            self.registry_path,
                        ),
                        self.registry_path,
                    ),
                    self.runtime_root,
                )
                overlay_snapshot_runner_readiness(
                    snapshot,
                    self.runtime_root,
                    preserve_existing_when_local_stale=True,
                    prefer_existing=True,
                )
                automation_controller = scan_automation_controller_reports(self.runtime_root)
                plan = overlay_worktree_plan_from_controller(
                    build_automation_worktree_plan(self.registry_path, self.automation_worktree_root, check_remote=False),
                    automation_controller,
                )
                refresh_overlay_generated_at(snapshot)
                snapshot["automation_worktree_plan"] = plan
                snapshot["automation_controller"] = automation_controller
                snapshot["command_bus"] = scan_command_bus(self.runtime_root)
                snapshot["automation_status"] = refresh_automation_status(self.runtime_root)
                annotate_snapshot_queue_attention_plan(snapshot, plan)
                annotate_snapshot_command_registry(snapshot, self.registry_path)
                recompute_snapshot_attention_summary(snapshot)
                summary = snapshot.setdefault("summary", {})
                summary["automation_controller"] = automation_controller_summary(snapshot.get("automation_controller"))
                command_bus_state = snapshot.get("command_bus") if isinstance(snapshot.get("command_bus"), dict) else {}
                latest_command = command_bus_state.get("latest") if isinstance(command_bus_state.get("latest"), dict) else {}
                summary["command_bus"] = {
                    "available": command_bus_state.get("available"),
                    "command_count": command_bus_state.get("command_count", 0),
                    "counts": command_bus_state.get("counts", {}),
                    "raw_counts": command_bus_state.get("raw_counts", {}),
                    "unresolved_counts": command_bus_state.get("unresolved_counts", {}),
                    "latest_command_id": latest_command.get("command_id") if latest_command else None,
                    "latest_mode": latest_command.get("mode") if latest_command else None,
                    "latest_state": latest_command.get("state") if latest_command else None,
                    "latest_updated_at": latest_command.get("updated_at") if latest_command else None,
                    "latest_no_op_reason": (latest_command.get("parsed_summary") or {}).get("no_op_reason") if latest_command and isinstance(latest_command.get("parsed_summary"), dict) else None,
                }
                summary["automation_worktree_plan"] = automation_worktree_plan_summary(plan)
                apply_derived_human_needed(snapshot)
                return snapshot
            return {
                "schema_version": "1.0",
                "generated_at": utc_now(),
                "summary": {
                    "warnings": [error or f"Cannot read snapshot source: {self.snapshot_source_path}"],
                },
                "projects": [],
                "human_needed": [],
            }

        snapshot = build_snapshot(
            self.runtime_root,
            self.registry_path,
            resource_activity_window_minutes=self.resource_activity_window_minutes,
            codex_limit_max_age_minutes=self.codex_limit_max_age_minutes,
            automation_worktree_root=self.automation_worktree_root,
        )
        if self.history_enabled:
            augment_added_counts_from_history(self.db_path, snapshot)
            resource_activity = snapshot.get("summary", {}).get("resource_activity", {})
            if resource_activity.get("is_active"):
                record_system_resource_sample(
                    self.db_path,
                    snapshot.get("summary", {}).get("resource_load", {}),
                    sample_interval_seconds=self.resource_sample_interval_seconds,
                    retention_hours=self.resource_sample_retention_hours,
                )
            else:
                init_db(self.db_path)
                with sqlite3.connect(self.db_path) as conn:
                    clean_old_system_resource_samples(conn, self.resource_sample_retention_hours)
            store_snapshot(
                self.db_path,
                snapshot,
                dashboard_retention=self.dashboard_snapshot_retention,
                project_retention=self.project_snapshot_retention,
            )
            augment_system_resource_load(self.db_path, snapshot, retention_hours=self.resource_sample_retention_hours)
        latest_path = self.runtime_root / "dashboard" / "latest.json"
        latest_path.parent.mkdir(parents=True, exist_ok=True)
        latest_path.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
        return snapshot

    def send_text(self, status: int, content_type: str, body: str) -> None:
        encoded = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.end_headers()
        self.wfile.write(encoded)

    def send_json(self, status: int, data: Any) -> None:
        self.send_text(status, "application/json", json.dumps(data, ensure_ascii=False, indent=2))

    def send_redirect(self, location: str) -> None:
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", location)
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.end_headers()

    def client_ip(self) -> str:
        forwarded = self.headers.get("X-Forwarded-For") or ""
        if forwarded.strip():
            return forwarded.split(",", 1)[0].strip()
        return str(self.client_address[0] if self.client_address else "")

    def chat_access_token(self) -> str:
        password = self.chat_access_password or ""
        return hashlib.sha256(f"{password}|{self.client_ip()}".encode("utf-8")).hexdigest()

    def chat_access_authorized(self) -> bool:
        if not self.chat_access_password:
            return True
        cookie = self.headers.get("Cookie") or ""
        expected = self.chat_access_token()
        for part in cookie.split(";"):
            name, _, value = part.strip().partition("=")
            if name == "aistudio_chat_access" and value == expected:
                return True
        return False

    def safe_chat_next_path(self, value: Any) -> str:
        candidate = str(value or "/chat").strip()
        parsed = urlparse(candidate)
        if parsed.scheme or parsed.netloc or parsed.path not in {"/chat", "/chat/"}:
            return "/chat"
        return parsed.path + (f"?{parsed.query}" if parsed.query else "")

    def send_chat_login(self, error: str = "", next_path: str = "/chat") -> None:
        error_html = f'<p class="error">{html.escape(error)}</p>' if error else ""
        safe_next = self.safe_chat_next_path(next_path)
        body = f"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>AiStudio Chat Login</title>
  <style>
    :root {{ color-scheme: light dark; --line:#d8dee8; --text:#18202b; --panel:#f7f9fc; --accent:#1867c0; }}
    @media (prefers-color-scheme: dark) {{ :root {{ --line:#303946; --text:#e8edf4; --panel:#151a21; --accent:#6aa9ff; }} }}
    * {{ box-sizing: border-box; }}
    body {{ margin:0; min-height:100vh; display:grid; place-items:center; font:16px/1.45 system-ui, -apple-system, Segoe UI, sans-serif; color:var(--text); background:Canvas; padding:16px; }}
    form {{ width:min(420px, 100%); border:1px solid var(--line); border-radius:8px; background:var(--panel); padding:18px; display:grid; gap:12px; }}
    h1 {{ font-size:20px; margin:0; }}
    input, button {{ width:100%; min-height:44px; border-radius:8px; border:1px solid var(--line); font:inherit; padding:0 12px; }}
    button {{ border-color:var(--accent); background:var(--accent); color:white; cursor:pointer; }}
    .muted {{ color: color-mix(in srgb, var(--text) 65%, Canvas); font-size:13px; }}
    .error {{ color:#b00020; margin:0; }}
  </style>
</head>
<body>
  <form method="post" action="/chat/login">
    <h1>Доступ к AiStudio Chat</h1>
    <div class="muted">Введите временный пароль для этого IP.</div>
    {error_html}
    <input name="next" type="hidden" value="{html.escape(safe_next, quote=True)}">
    <input name="password" type="password" autocomplete="current-password" autofocus>
    <button type="submit">Войти</button>
  </form>
</body>
</html>"""
        self.send_text(HTTPStatus.UNAUTHORIZED, "text/html", body)

    def require_chat_access(self) -> bool:
        if self.chat_access_authorized() or self.chat_worker_authorized():
            return True
        self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "chat_password_required"})
        return False

    def read_json_body(self, max_bytes: int = 65536) -> tuple[dict[str, Any] | None, str | None]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}, None
        if length > max_bytes:
            return None, "request_body_too_large"
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8", errors="replace"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None, "invalid_json"
        if not isinstance(payload, dict):
            return None, "json_object_required"
        return payload, None

    def chat_worker_authorized(self) -> bool:
        token = self.chat_worker_token
        if not token:
            return False
        auth = self.headers.get("Authorization") or ""
        header_token = self.headers.get("X-Aistudio-Chat-Worker-Token") or ""
        return auth == f"Bearer {token}" or header_token == token

    def do_GET(self) -> None:
        path = unquote(urlparse(self.path).path)
        if path in {"/chat/login", "/chat/login/"}:
            requested_next = str((parse_qs(urlparse(self.path).query).get("next") or ["/chat"])[0])
            if self.chat_access_authorized():
                self.send_redirect(self.safe_chat_next_path(requested_next))
            else:
                self.send_chat_login(next_path=requested_next)
            return
        if path in {"/chat", "/chat/"} or path.startswith("/chat/"):
            if not self.chat_access_authorized():
                self.send_chat_login(next_path=self.path)
                return
            self.send_text(HTTPStatus.OK, "text/html", remote_chat_server.INDEX_HTML)
            return
        if path.startswith("/api/chat/") and not self.require_chat_access():
            return
        if path == "/api/chat/messages":
            query = parse_qs(urlparse(self.path).query)
            session_id = str((query.get("session_id") or [""])[0]).strip()
            if not session_id:
                session = dashboard_chat_session(self.runtime_root)
                session_id = str(session["session_id"])
                try:
                    limit = max(1, min(200, int((query.get("limit") or ["80"])[0])))
                except (TypeError, ValueError):
                    limit = 80
                messages = remote_chat_bus.messages_for_session(self.runtime_root, session_id, limit=limit)
                tasks = remote_chat_bus.list_tasks(self.runtime_root, session_id=session_id)
                self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "session": session, "messages": messages, "tasks": tasks})
                return
            try:
                limit = max(1, min(200, int((query.get("limit") or ["80"])[0])))
            except (TypeError, ValueError):
                limit = 80
            messages = remote_chat_bus.messages_for_session(self.runtime_root, session_id, limit=limit)
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "messages": messages})
            return
        if path == "/api/chat/state":
            query = parse_qs(urlparse(self.path).query)
            project_id = str((query.get("project_id") or [""])[0]).strip()
            state = remote_chat_bus.list_state(self.runtime_root)
            if project_id and project_id != "all":
                target = [item for item in state.get("sessions", []) if str(item.get("project_id") or "all") == project_id]
                state["sessions"] = target
            self.send_json(HTTPStatus.OK, state)
            return
        if path == "/api/chat/projects":
            state = remote_chat_bus.list_state(self.runtime_root)
            projects = {
                str(item.get("project_id") or "all")
                for item in state.get("sessions", [])
                if isinstance(item, dict)
                and str(item.get("channel") or "").strip().lower() not in {"automation-debug", "system", "monitor"}
                and str(item.get("chat_mode") or "").strip().lower() != "automation_debug"
            }
            snapshot = self.snapshot()
            for project in snapshot.get("projects", []):
                if isinstance(project, dict) and project.get("project_id"):
                    projects.add(str(project.get("project_id")))
            projects = sorted(projects)
            if "all" not in projects:
                projects = ["all", *[item for item in projects if item != "all"]]
            else:
                projects = ["all", *[item for item in projects if item != "all"]]
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "projects": projects})
            return
        if path == "/api/chat/sessions":
            query = parse_qs(urlparse(self.path).query)
            project_id = str((query.get("project_id") or [""])[0]).strip()
            sessions = remote_chat_bus.list_sessions(self.runtime_root, project_id=project_id if project_id and project_id != "all" else None)
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "sessions": sessions})
            return
        if path == "/api/chat/tasks":
            query = parse_qs(urlparse(self.path).query)
            tasks = remote_chat_bus.list_tasks(self.runtime_root, session_id=str((query.get("session_id") or [""])[0]))
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "tasks": tasks})
            return
        snapshot = self.snapshot()
        refresh_command_registry_origin_access(snapshot)

        if path in {"/", "/index.html"}:
            self.send_text(HTTPStatus.OK, "text/html", render_statistics_index(snapshot))
            return
        if path in {"/old", "/old/", "/old-version", "/old-version/"}:
            self.send_text(HTTPStatus.OK, "text/html", render_index(snapshot))
            return
        if path in {"/infrastructure", "/infrastructure/", "/vps", "/vps/"}:
            self.send_text(HTTPStatus.OK, "text/html", render_infrastructure_index(snapshot))
            return
        if path == "/latest.json":
            self.send_json(HTTPStatus.OK, snapshot)
            return
        if path == "/api/summary.json":
            self.send_json(HTTPStatus.OK, dashboard_summary_payload(snapshot))
            return
        if path == "/api/projects.json":
            self.send_json(HTTPStatus.OK, snapshot.get("projects", []))
            return
        if path == "/api/vps-fleet.json":
            self.send_json(HTTPStatus.OK, snapshot.get("vps_fleet", {}))
            return
        if path == "/api/commands.json":
            bus = snapshot.get("command_bus") if isinstance(snapshot.get("command_bus"), dict) else scan_command_bus(self.runtime_root)
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "commands": bus.get("recent", []), "summary": snapshot.get("summary", {}).get("command_bus", {})})
            return
        if path == "/api/human-needed.json":
            self.send_json(HTTPStatus.OK, snapshot.get("human_needed", []))
            return
        if path == "/api/codex-limits.json":
            self.send_json(HTTPStatus.OK, snapshot.get("codex_limits", []))
            return
        if path == "/api/codex-limit-estimates.json":
            self.send_json(HTTPStatus.OK, snapshot.get("codex_limit_estimates", []))
            return
        if path == "/api/codex-limit-consensus.json":
            self.send_json(HTTPStatus.OK, snapshot.get("codex_limit_consensus", []))
            return
        if path == "/api/model-cost-analytics.json":
            self.send_json(HTTPStatus.OK, snapshot.get("model_cost_analytics", []))
            return
        if path == "/api/task-size-analytics.json":
            self.send_json(HTTPStatus.OK, snapshot.get("task_size_analytics", []))
            return
        if path in {"/api/automation-status.json", "/api/automation/status"}:
            self.send_json(HTTPStatus.OK, snapshot.get("automation_status", {}))
            return
        if path == "/api/automation-worktree-plan.json":
            self.send_json(
                HTTPStatus.OK,
                build_automation_worktree_plan(self.registry_path, self.automation_worktree_root, check_remote=True),
            )
            return
        if path.startswith("/api/project/") and path.endswith(".json"):
            project_id = path.removeprefix("/api/project/").removesuffix(".json")
            project = next((p for p in snapshot.get("projects", []) if str(p.get("project_id")) == project_id), None)
            if project:
                self.send_json(HTTPStatus.OK, project)
            else:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "project_not_found", "project_id": project_id})
            return
        if path.startswith("/project/"):
            project_path = path.removeprefix("/project/").strip("/")
            if project_path.endswith("/health"):
                project_id = project_path.removesuffix("/health").strip("/")
                status, body = render_project_health(snapshot, project_id)
                self.send_text(status, "text/html", body)
                return
            if project_path.endswith("/technical"):
                project_id = project_path.removesuffix("/technical").strip("/")
                status, body = render_project(snapshot, project_id)
                self.send_text(status, "text/html", body)
                return
            project_id = project_path
            status, body = render_project_overview(snapshot, project_id)
            self.send_text(status, "text/html", body)
            return

        self.send_text(HTTPStatus.NOT_FOUND, "text/plain", "Not found")

    def do_POST(self) -> None:
        path = unquote(urlparse(self.path).path)
        if path in {"/chat/login", "/chat/login/"}:
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length > 8192:
                self.send_chat_login("Слишком большой запрос.")
                return
            body = self.rfile.read(length).decode("utf-8", errors="replace") if length > 0 else ""
            form = parse_qs(body)
            password = str((form.get("password") or [""])[0])
            next_path = self.safe_chat_next_path((form.get("next") or ["/chat"])[0])
            if self.chat_access_password and password == self.chat_access_password:
                self.send_response(HTTPStatus.FOUND)
                self.send_header("Location", next_path)
                self.send_header("Set-Cookie", f"aistudio_chat_access={self.chat_access_token()}; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800")
                self.send_header("Cache-Control", "no-store, max-age=0")
                self.end_headers()
                return
            self.send_chat_login("Неверный пароль.", next_path=next_path)
            return
        if path.startswith("/api/chat/") and path not in {"/api/chat/claim", "/api/chat/answer"} and not self.require_chat_access():
            return
        if path == "/api/chat/sessions":
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            project_id = str(payload.get("project_id") or "").strip()
            session = remote_chat_bus.get_or_create_session(
                self.runtime_root,
                channel=str(payload.get("channel") or "web"),
                external_id=str(payload.get("external_id") or ""),
                title=str(payload.get("title") or ""),
                actor=str(payload.get("actor") or "web"),
                project_id=project_id if project_id != "all" else "",
                status=str(payload.get("status") or "planning"),
                eta_minutes_estimate=payload.get("eta_minutes_estimate") if isinstance(payload.get("eta_minutes_estimate"), (int, float, str)) else None,
                chat_mode=str(payload.get("chat_mode") or "general"),
                skill=str(payload.get("skill") or ""),
            )
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "session": session})
            return
        if path == "/api/chat/sessions/update":
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            project_id = None
            if payload.get("project_id") is not None:
                project_id = str(payload.get("project_id") or "")
                if project_id == "all":
                    project_id = ""
            updated = remote_chat_bus.update_session(
                self.runtime_root,
                session_id=str(payload.get("session_id") or ""),
                title=str(payload.get("title")) if payload.get("title") is not None else None,
                project_id=project_id,
                status=str(payload.get("status") or "planning") if payload.get("status") is not None else None,
                eta_minutes_estimate=int(payload.get("eta_minutes_estimate"))
                if payload.get("eta_minutes_estimate") is not None and str(payload.get("eta_minutes_estimate")).strip() != ""
                else None,
                chat_mode=str(payload.get("chat_mode")) if payload.get("chat_mode") is not None else None,
                skill=str(payload.get("skill")) if payload.get("skill") is not None else None,
            )
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "session": updated})
            return
        if path == "/api/chat/sessions/recompute_eta":
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            project_id = str(payload.get("project_id") or "")
            updated = remote_chat_bus.recompute_project_eta(
                self.runtime_root,
                project_id=project_id if project_id and project_id != "all" else None,
            )
            self.send_json(HTTPStatus.OK, {
                "schema_version": "1.0",
                "updated_sessions": updated,
                "updated_count": len(updated),
            })
            return
        if path == "/api/chat/claim":
            if not self.chat_worker_authorized():
                self.send_json(HTTPStatus.FORBIDDEN, {"error": "chat_worker_token_required"})
                return
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            worker_id = str(payload.get("worker_id") or "remote-dashboard-chat-worker").strip()
            try:
                lease_seconds = max(30, min(3600, int(payload.get("lease_seconds") or 900)))
            except (TypeError, ValueError):
                lease_seconds = 900
            message = remote_chat_bus.claim_next(self.runtime_root, worker_id, lease_seconds=lease_seconds)
            if message is None:
                self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "claimed": False})
                return
            messages = remote_chat_bus.messages_for_session(self.runtime_root, str(message.get("session_id")), limit=80)
            session = next(
                (item for item in remote_chat_bus.list_sessions(self.runtime_root) if str(item.get("session_id") or "") == str(message.get("session_id") or "")),
                {},
            )
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "claimed": True, "message": message, "messages": messages, "session": session})
            return

        if path == "/api/chat/answer":
            if not self.chat_worker_authorized():
                self.send_json(HTTPStatus.FORBIDDEN, {"error": "chat_worker_token_required"})
                return
            payload, error = self.read_json_body(max_bytes=256000)
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            message_id = str(payload.get("message_id") or "").strip()
            worker_id = str(payload.get("worker_id") or "remote-dashboard-chat-worker").strip()
            if not message_id:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "message_id_required"})
                return
            try:
                if payload.get("failed"):
                    message = remote_chat_bus.fail_message(
                        self.runtime_root,
                        message_id,
                        error=str(payload.get("error") or "worker failed"),
                        worker_id=worker_id,
                    )
                    self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "message": message})
                    return
                reply = remote_chat_bus.answer_message(
                    self.runtime_root,
                    message_id,
                    text=str(payload.get("text") or ""),
                    worker_id=worker_id,
                    run=payload.get("run") if isinstance(payload.get("run"), dict) else {},
                )
            except KeyError:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "message_not_found", "message_id": message_id})
                return
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "reply": reply})
            return

        if path == "/api/chat/messages":
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            session_id = str(payload.get("session_id") or "").strip()
            if not session_id:
                session = dashboard_chat_session(self.runtime_root)
                session_id = str(session["session_id"])
            else:
                session = remote_chat_bus.list_sessions(self.runtime_root, project_id=None)
                if not any(item.get("session_id") == session_id for item in session):
                    self.send_json(HTTPStatus.NOT_FOUND, {"error": "session_not_found", "session_id": session_id})
                    return
            try:
                message = remote_chat_bus.add_user_message(
                    self.runtime_root,
                    session_id=session_id,
                    text=str(payload.get("text") or ""),
                    actor="dashboard",
                    metadata={"source": "remote_dashboard_stub"},
                )
            except ValueError as exc:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                return
            if "session_id" in payload:
                self.send_json(HTTPStatus.ACCEPTED, {"schema_version": "1.0", "session_id": session_id, "message": message})
            else:
                session = remote_chat_bus.list_sessions(self.runtime_root, project_id=None)
                dashboard_session = next((item for item in session if str(item.get("session_id") or "") == session_id), None)
                self.send_json(HTTPStatus.ACCEPTED, {"schema_version": "1.0", "session": dashboard_session or {"session_id": session_id}, "message": message})
            return
        if path == "/api/chat/clarify":
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            session_id = str(payload.get("session_id") or "").strip()
            if not session_id:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "session_id_required"})
                return
            if not any(item.get("session_id") == session_id for item in remote_chat_bus.list_sessions(self.runtime_root)):
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "session_not_found", "session_id": session_id})
                return
            try:
                message = remote_chat_bus.add_system_message(
                    self.runtime_root,
                    session_id=session_id,
                    text=str(payload.get("text") or ""),
                    actor=str(payload.get("actor") or "dashboard"),
                    metadata={"kind": "clarification", "source": "remote_dashboard_stub"},
                )
            except ValueError as exc:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                return
            self.send_json(HTTPStatus.ACCEPTED, {"schema_version": "1.0", "message": message})
            return
        if path == "/api/chat/tasks":
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            created = remote_chat_bus.add_session_task(
                self.runtime_root,
                session_id=str(payload.get("session_id") or "").strip(),
                text=str(payload.get("text") or ""),
                status=str(payload.get("status") or "pending"),
                eta_minutes=int(payload.get("eta_minutes")) if payload.get("eta_minutes") is not None and str(payload.get("eta_minutes")).strip() != "" else None,
                order_index=int(payload.get("order_index")) if payload.get("order_index") is not None and str(payload.get("order_index")).strip() != "" else None,
            )
            self.send_json(HTTPStatus.ACCEPTED, {"schema_version": "1.0", "task": created})
            return
        if path == "/api/chat/tasks/update":
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            task_id = str(payload.get("task_id") or "").strip()
            if not task_id:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "task_id_required"})
                return
            updated = remote_chat_bus.update_session_task(
                self.runtime_root,
                task_id=task_id,
                status=str(payload.get("status") or "pending") if payload.get("status") is not None else None,
                text=str(payload.get("text") or "")
                if payload.get("text") is not None and str(payload.get("text")).strip() != ""
                else None,
                eta_minutes=int(payload.get("eta_minutes")) if payload.get("eta_minutes") is not None and str(payload.get("eta_minutes")).strip() != "" else None,
                order_index=int(payload.get("order_index")) if payload.get("order_index") is not None and str(payload.get("order_index")).strip() != "" else None,
            )
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "task": updated})
            return
        if path == "/api/chat/tasks/delete":
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            task_id = str(payload.get("task_id") or "").strip()
            if not task_id:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "task_id_required"})
                return
            remote_chat_bus.delete_session_task(self.runtime_root, task_id=task_id)
            self.send_json(HTTPStatus.OK, {"schema_version": "1.0"})
            return

        cancel_match = re.fullmatch(r"/api/commands/([^/]+)/cancel", path)
        if cancel_match is not None:
            if self.snapshot_source_path is not None and self.registry_path is None:
                self.send_json(
                    HTTPStatus.FORBIDDEN,
                    {
                        "error": "mirror_mode_read_only",
                        "message": "Dashboard runs from uploaded snapshots on this host and has no command registry.",
                    },
                )
                return
            try:
                result = cancel_command_from_runtime_roots(self.runtime_root, cancel_match.group(1))
            except KeyError:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "command_not_found", "command_id": cancel_match.group(1)})
                return
            self.send_json(HTTPStatus.OK, result)
            return

        if path == "/api/automation/worktrees/run":
            if self.snapshot_source_path is not None and self.registry_path is None:
                self.send_json(
                    HTTPStatus.FORBIDDEN,
                    {
                        "error": "mirror_mode_read_only",
                        "message": "Dashboard runs from uploaded snapshots on this host and has no command registry.",
                    },
                )
                return
            payload, error = self.read_json_body()
            if error:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
                return
            assert payload is not None
            started, message, info = start_worktree_remediation_command(
                self.runtime_root,
                self.registry_path,
                self.automation_worktree_root,
                payload,
            )
            if not started:
                status = HTTPStatus.FORBIDDEN if info.get("error_code") in {"registry_missing", "apply_not_allowed"} else HTTPStatus.INTERNAL_SERVER_ERROR
                self.send_json(status, {"error": info.get("error_code") or "start_failed", "message": message, "info": info})
                return
            self.send_json(HTTPStatus.ACCEPTED, {"message": "Worktree dry-run команда поставлена в очередь", "mode": "worktrees", "command_id": info.get("command_id"), "info": info})
            return

        match = re.fullmatch(r"/api/project/([^/]+)/run", path)
        if not path.startswith("/api/project/") or match is None:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return

        project_id = match.group(1)
        snapshot = self.snapshot()
        project = next((p for p in snapshot.get("projects", []) if str(p.get("project_id")) == project_id), None)
        if not project:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "project_not_found", "project_id": project_id})
            return

        payload, error = self.read_json_body()
        if error:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": error})
            return
        assert payload is not None
        mode = normalize_manual_mode(str(payload.get("mode", "all")))
        if mode == "unknown":
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_mode", "mode": payload.get("mode")})
            return

        command_project = project
        if self.snapshot_source_path is not None:
            command_project, command_error = resolve_command_project(self.registry_path, project)
            if command_project is None:
                self.send_json(
                    HTTPStatus.FORBIDDEN,
                    {
                        "error": "mirror_mode_read_only",
                        "message": command_error or "Dashboard runs from uploaded snapshots on this host and has no command registry.",
                    },
                )
                return

        manual = project.get("manual_run") or {}
        if manual_run_active(manual):
            self.send_json(
                HTTPStatus.CONFLICT,
                {
                    "error": "manual_run_active",
                    "message": f"По проекту {project_id} уже запущен ручной прогон",
                    "project_id": project_id,
                    "mode": manual.get("mode"),
                },
            )
            return

        started, message, info = start_control_command(self.runtime_root, self.registry_path, command_project, mode, payload)
        if not started:
            status = HTTPStatus.UNPROCESSABLE_ENTITY if str(info.get("error_code") or "").startswith("command_root_") else HTTPStatus.INTERNAL_SERVER_ERROR
            self.send_json(status, {"error": info.get("error_code") or "start_failed", "message": message, "info": info})
            return
        self.send_json(HTTPStatus.ACCEPTED, {"message": "Команда поставлена в очередь", "mode": mode, "project_id": project_id, "command_id": info.get("command_id"), "info": info})


def main() -> int:
    parser = argparse.ArgumentParser(description="Serve a read-only remote agent dashboard")
    parser.add_argument("--runtime-root", default="~/agent-runtime", help="Runtime root with runs/ and dashboard/")
    parser.add_argument("--registry", default="", help="Project registry JSON with projects[]")
    parser.add_argument(
        "--automation-worktree-root",
        default="",
        help="Root directory proposed by the automation worktree planner. Defaults to <runtime-root>/automation-worktrees.",
    )
    parser.add_argument("--db", default="", help="SQLite analytics path")
    parser.add_argument(
        "--snapshot-source",
        default="",
        help="Serve dashboard from an uploaded latest.json snapshot instead of scanning local project files.",
    )
    parser.add_argument(
        "--refresh-interval",
        type=int,
        default=60,
        help=(
            "Compatibility flag kept for old wrappers. Dashboard now rebuilds snapshot on every request, "
            "so this value no longer throttles scans."
        ),
    )
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument(
        "--codex-limit-max-age-minutes",
        type=int,
        default=CODEX_LIMIT_STALE_MINUTES_DEFAULT,
        help="Ignore Codex limit rows older than this many minutes when building consensus.",
    )
    parser.add_argument(
        "--resource-sample-retention-hours",
        type=int,
        default=SYSTEM_RESOURCE_SAMPLE_RETENTION_HOURS_DEFAULT,
        help="Keep system resource samples for this many hours for moving averages.",
    )
    parser.add_argument(
        "--dashboard-snapshot-retention",
        type=int,
        default=DASHBOARD_SNAPSHOT_RETENTION_DEFAULT,
        help="Keep at most this many global dashboard history rows; 0 disables pruning.",
    )
    parser.add_argument(
        "--project-snapshot-retention",
        type=int,
        default=PROJECT_SNAPSHOT_RETENTION_DEFAULT,
        help="Keep at most this many history rows per project; 0 disables pruning.",
    )
    parser.add_argument(
        "--resource-sample-interval-seconds",
        type=int,
        default=SYSTEM_RESOURCE_SAMPLE_INTERVAL_SECONDS_DEFAULT,
        help=(
            "Minimum delay between consecutive system-resource samples while workload is active."
        ),
    )
    parser.add_argument(
        "--resource-activity-window-minutes",
        type=int,
        default=SYSTEM_RESOURCE_ACTIVITY_WINDOW_MINUTES_DEFAULT,
        help="How far back to look for recent activity signals before sampling resources.",
    )
    parser.add_argument(
        "--resource-sampler-interval-seconds",
        type=int,
        default=SYSTEM_RESOURCE_SAMPLER_INTERVAL_SECONDS_DEFAULT,
        help="How often the background sampler checks activity and stores system resource samples.",
    )
    parser.add_argument(
        "--enable-history",
        action="store_true",
        help="Opt in to SQLite snapshot and resource history. Disabled by default.",
    )
    parser.add_argument(
        "--chat-worker-token-env",
        default="AISTUDIO_CHAT_WORKER_TOKEN",
        help="Environment variable that contains the bearer token for /api/chat/claim and /api/chat/answer.",
    )
    parser.add_argument(
        "--chat-access-password-env",
        default="AISTUDIO_CHAT_ACCESS_PASSWORD",
        help="Environment variable that contains the temporary password for browser access to /chat.",
    )
    args = parser.parse_args()

    runtime_root = Path(args.runtime_root).expanduser()
    DashboardHandler.runtime_root = runtime_root
    DashboardHandler.registry_path = Path(args.registry).expanduser() if args.registry else None
    DashboardHandler.automation_worktree_root = Path(args.automation_worktree_root).expanduser() if args.automation_worktree_root else runtime_root / "automation-worktrees"
    DashboardHandler.db_path = Path(args.db).expanduser() if args.db else runtime_root / "dashboard" / "analytics.sqlite"
    DashboardHandler.snapshot_source_path = Path(args.snapshot_source).expanduser() if args.snapshot_source else None
    DashboardHandler.refresh_interval_sec = max(1, args.refresh_interval)
    DashboardHandler.codex_limit_max_age_minutes = max(0, args.codex_limit_max_age_minutes)
    DashboardHandler.resource_sample_retention_hours = max(0, args.resource_sample_retention_hours)
    DashboardHandler.dashboard_snapshot_retention = max(0, args.dashboard_snapshot_retention)
    DashboardHandler.project_snapshot_retention = max(0, args.project_snapshot_retention)
    DashboardHandler.resource_sample_interval_seconds = max(0, args.resource_sample_interval_seconds)
    DashboardHandler.resource_activity_window_minutes = max(0, args.resource_activity_window_minutes)
    DashboardHandler.resource_sampler_interval_seconds = max(0, args.resource_sampler_interval_seconds)
    DashboardHandler.history_enabled = args.enable_history
    DashboardHandler.chat_worker_token = os.environ.get(args.chat_worker_token_env) or None
    DashboardHandler.chat_access_password = os.environ.get(args.chat_access_password_env) or None

    server = ThreadingHTTPServer((args.host, args.port), DashboardHandler)
    server.daemon_threads = True
    print(f"Serving dashboard on http://{args.host}:{args.port}")
    print(f"Runtime root: {runtime_root}")
    if DashboardHandler.registry_path:
        print(f"Project registry: {DashboardHandler.registry_path}")
    print(f"Automation worktree root: {DashboardHandler.automation_worktree_root}")
    if DashboardHandler.snapshot_source_path:
        print(f"Snapshot source: {DashboardHandler.snapshot_source_path}")
    print(f"Analytics history: {'enabled' if DashboardHandler.history_enabled else 'disabled'}")
    print(f"Refresh interval (compatibility): {DashboardHandler.refresh_interval_sec}s")
    if DashboardHandler.history_enabled:
        print(
            "Snapshot history: "
            f"dashboard={DashboardHandler.dashboard_snapshot_retention}, "
            f"per_project={DashboardHandler.project_snapshot_retention}"
        )
        print(
            "Resource samples: "
            f"retention={DashboardHandler.resource_sample_retention_hours}h, "
            f"interval={DashboardHandler.resource_sample_interval_seconds}s, "
            f"activity_window={DashboardHandler.resource_activity_window_minutes}m, "
            f"sampler_interval={DashboardHandler.resource_sampler_interval_seconds}s"
        )
    else:
        print("Resource samples: realtime-only; SQLite writes disabled")
    print(f"Dashboard chat worker API: {'token configured' if DashboardHandler.chat_worker_token else 'disabled'}")
    print(f"Dashboard chat browser access: {'password configured' if DashboardHandler.chat_access_password else 'open'}")
    if (
        DashboardHandler.history_enabled
        and DashboardHandler.snapshot_source_path is None
        and DashboardHandler.resource_sampler_interval_seconds > 0
    ):
        thread = threading.Thread(
            target=run_resource_sampler_loop,
            args=(
                DashboardHandler.runtime_root,
                DashboardHandler.db_path,
            ),
            kwargs={
                "sampler_interval_seconds": DashboardHandler.resource_sampler_interval_seconds,
                "sample_interval_seconds": DashboardHandler.resource_sample_interval_seconds,
                "retention_hours": DashboardHandler.resource_sample_retention_hours,
                "activity_window_minutes": DashboardHandler.resource_activity_window_minutes,
            },
            name="resource-sampler",
            daemon=True,
        )
        thread.start()
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
