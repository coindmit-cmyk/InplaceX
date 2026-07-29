#!/usr/bin/env python3
"""Launch a bounded pool of worker runner lanes."""

from __future__ import annotations

import argparse
from collections import Counter
import json
import os
import shlex
import signal
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from codex_host_readiness import codex_host_readiness
import claim_next_task
import execution_lease_manager
import fast_track_gate
import model_resource_router
import runner_readiness_report
from process_log import append_log
from project_paths import task_file, task_manager_dir


DEFAULT_PROFILES = ("auto-worker-5.3-mini", "auto-worker-5.3", "auto-worker-5.5", "auto-worker-5.5max")
WORKER_POOL_PLAN_SCHEMA_VERSION = 1
DEFAULT_UNSUPPORTED_CODEX_MODELS: set[str] = set()
DEFAULT_MODEL_FALLBACKS: dict[str, str | None] = {}
SHARED_RECONCILIATION_PATHS = {
    "CHANGELOG.md",
    "README.md",
    "AiStudio/Task_manager/clean_rebuild_plan.json",
    "docs/plans/allowed_paths_repair_plan.json",
}

FAST_TRACK_PROMPT = (
    "Fast Track / Minimal First Execution: execute only the injected assigned packet. "
    "Treat its assigned_packet_snapshot as the task_queue read for this run; do not scan the full queue. "
    "Inspect only the named evidence and allowed paths, run only the 1-3 contract checks, "
    "do not start broad review, scanners or unrelated repair, and stop after the accepted scope is complete."
)


def worker_prompt(worker_id: str, fast_track: dict[str, Any] | None) -> str:
    if isinstance(fast_track, dict) and fast_track.get("eligible") is True:
        return f"{worker_id}\n\n{FAST_TRACK_PROMPT}"
    return worker_id


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def worker_profiles(project_root: Path) -> dict[str, dict[str, Any]]:
    data = load_json(project_root / ".agent" / "worker_profiles.json")
    profiles = data if isinstance(data, list) else data.get("profiles", []) if isinstance(data, dict) else []
    result: dict[str, dict[str, Any]] = {}
    for profile in profiles if isinstance(profiles, list) else []:
        if isinstance(profile, dict) and profile.get("worker_id"):
            result[str(profile["worker_id"])] = profile
    return result


def profile_task_limit(profiles: dict[str, dict[str, Any]], worker_id: str) -> int:
    value = profiles.get(worker_id, {}).get("max_tasks_per_session")
    return int(value) if isinstance(value, int) and value > 0 else 0


def profile_model(profiles: dict[str, dict[str, Any]], worker_id: str) -> str | None:
    profile = profiles.get(worker_id) or {}
    value = profile.get("codex_model") or profile.get("model") or profile.get("model_alias")
    return str(value).strip() if isinstance(value, str) and value.strip() else None


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


def codex_executable_available(codex_bin: str) -> bool:
    return codex_host_readiness(codex_bin, require_auth=False).ok


def codex_worker_host_readiness(codex_bin: str) -> dict[str, Any]:
    return codex_host_readiness(codex_bin).to_dict()


def allowed_profiles(project_root: Path) -> dict[str, bool]:
    proc = subprocess.run(
        [sys.executable, str(script_path("budget_guard.py")), "--project-root", str(project_root), "--json"],
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode not in (0, 2) or not proc.stdout.strip():
        return {profile: True for profile in DEFAULT_PROFILES}
    data = json.loads(proc.stdout)
    return {str(k): bool(v) for k, v in (data.get("allow_worker_profiles") or {}).items()}


def path_prefix(value: str) -> str:
    normalized = value.replace("\\", "/").strip().lstrip("./")
    for marker in ("/**", "/*", "*"):
        if marker in normalized:
            normalized = normalized.split(marker, 1)[0]
    return normalized.rstrip("/")


def paths_overlap(left: list[str], right: list[str]) -> bool:
    for raw_left in left:
        left_prefix = path_prefix(raw_left)
        if not left_prefix:
            return True
        for raw_right in right:
            right_prefix = path_prefix(raw_right)
            if not right_prefix:
                return True
            broad_only = "*" in raw_left or "*" in raw_right
            if not broad_only and (left_prefix == right_prefix or left_prefix.startswith(right_prefix + "/") or right_prefix.startswith(left_prefix + "/")):
                return True
    return False


def parallel_conflict_paths(paths: list[str]) -> list[str]:
    normalized = []
    for raw_path in paths:
        path = str(raw_path).replace("\\", "/").strip()
        if not path:
            continue
        if path.startswith("./"):
            path = path[2:]
        normalized.append(path)
    semantic = [
        path
        for path in normalized
        if path not in SHARED_RECONCILIATION_PATHS
        and not path.startswith("/")
    ]
    return semantic or normalized


def choose_claimable_task(
    project_root: Path,
    profiles_by_id: dict[str, dict[str, Any]],
    worker_id: str,
    locked_ids: set[str],
    selected_paths: list[str] | None = None,
) -> dict[str, Any] | None:
    queue_path = task_file(project_root, "task_queue.json")
    if not queue_path.exists():
        return None
    queue = load_json(queue_path)
    tasks = queue.get("tasks")
    if not isinstance(tasks, list):
        return None
    profile = profiles_by_id.get(worker_id) or claim_next_task.DEFAULT_PROFILES.get(worker_id, {"selection_order": []})
    candidates = [task for task in tasks if isinstance(task, dict)]
    if selected_paths:
        candidates = [
            task for task in candidates
            if not paths_overlap(
                parallel_conflict_paths([str(path) for path in task.get("allowed_paths") or []]),
                selected_paths,
            )
        ]
    task = claim_next_task.choose_task(
        candidates,
        profile,
        worker_id,
        locked_ids,
    )
    if isinstance(task, dict):
        return task
    defective = claim_next_task.choose_defective_packet_task(candidates, profile, worker_id, locked_ids)
    if defective:
        return {**defective[0], "_packet_repair_only": True}
    return None


def choose_claimable_task_id(
    project_root: Path,
    profiles_by_id: dict[str, dict[str, Any]],
    worker_id: str,
    locked_ids: set[str],
) -> str | None:
    """Compatibility wrapper retained for callers and tests using the old API."""
    task = choose_claimable_task(project_root, profiles_by_id, worker_id, locked_ids)
    return claim_next_task.task_id(task) if task else None


def expanded_profile_slots(profiles: list[str], profiles_by_id: dict[str, dict[str, Any]], limit: int) -> list[str]:
    result: list[str] = []
    maximum = max((int((profiles_by_id.get(profile) or {}).get("max_parallel_lanes") or 1) for profile in profiles), default=1)
    for slot in range(maximum):
        for profile in profiles:
            profile_max = int((profiles_by_id.get(profile) or {}).get("max_parallel_lanes") or 1)
            if slot < profile_max:
                result.append(profile)
                if len(result) >= limit:
                    return result
    return result


def prioritized_profile_slots(
    project_root: Path,
    profiles_by_id: dict[str, dict[str, Any]],
    slots: list[str],
    locked_ids: set[str],
) -> list[str]:
    planned, _ = profile_slot_fairness_plan(
        project_root,
        profiles_by_id,
        slots,
        locked_ids,
    )
    return planned


def profile_slot_fairness_plan(
    project_root: Path,
    profiles_by_id: dict[str, dict[str, Any]],
    slots: list[str],
    locked_ids: set[str],
    *,
    capacity: int | None = None,
) -> tuple[list[str], dict[str, Any]]:
    """Rank distinct claimable work and reserve primary capacity ahead of background work."""
    entries: list[dict[str, Any]] = []
    reserved_task_ids: set[str] = set()
    for index, profile_id in enumerate(slots):
        task = choose_claimable_task(
            project_root,
            profiles_by_id,
            profile_id,
            locked_ids | reserved_task_ids,
        )
        selected_task_id = claim_next_task.task_id(task) if task else ""
        if task:
            task_class, class_reason = claim_next_task.scheduling_class(task)
            class_rank = claim_next_task.SCHEDULING_CLASS_RANK[task_class]
        else:
            task_class, class_reason, class_rank = "none", "no_claimable_task", 99
        if selected_task_id:
            reserved_task_ids.add(selected_task_id)
        entries.append(
            {
                "worker_id": profile_id,
                "task_id": selected_task_id or None,
                "scheduling_class": task_class,
                "scheduling_class_reason": class_reason,
                "class_rank": class_rank,
                "original_index": index,
            }
        )

    ranked = sorted(entries, key=lambda item: (item["class_rank"], item["original_index"]))
    selected = ranked if capacity is None else ranked[:max(0, capacity)]
    primary_candidates = [item for item in ranked if item["scheduling_class"] == "primary_delivery"]
    displaced_class = None
    reservation_deferred_reason = None
    if primary_candidates and not any(item["scheduling_class"] == "primary_delivery" for item in selected):
        primary = primary_candidates[0]
        replacement_index = next(
            (
                index
                for index in range(len(selected) - 1, -1, -1)
                if selected[index]["scheduling_class"] == "background_remediation"
            ),
            None,
        )
        if replacement_index is None and len(selected) > 1:
            replacement_index = next(
                (
                    index
                    for index in range(len(selected) - 1, -1, -1)
                    if selected[index]["scheduling_class"] == "integration_continuation"
                ),
                None,
            )
        if replacement_index is not None:
            displaced_class = selected[replacement_index]["scheduling_class"]
            selected[replacement_index] = primary
            selected = sorted(selected, key=lambda item: (item["class_rank"], item["original_index"]))
        elif selected:
            reservation_deferred_reason = "single_slot_reserved_for_integration_continuation"
        else:
            reservation_deferred_reason = "no_available_capacity"

    selected_classes = Counter(str(item["scheduling_class"]) for item in selected)
    candidate_classes = Counter(str(item["scheduling_class"]) for item in ranked if item["task_id"])
    evidence = {
        "candidate_class_counts": dict(sorted(candidate_classes.items())),
        "selected_class_counts": dict(sorted(selected_classes.items())),
        "background_candidate_count": candidate_classes.get("background_remediation", 0),
        "primary_candidate_count": candidate_classes.get("primary_delivery", 0),
        "primary_slot_reservation_required": bool(primary_candidates),
        "primary_slot_reserved": bool(
            primary_candidates
            and any(item["scheduling_class"] == "primary_delivery" for item in selected)
        ),
        "reservation_displaced_class": displaced_class,
        "reservation_deferred_reason": reservation_deferred_reason,
        "slot_evidence": [
            {
                key: item[key]
                for key in (
                    "worker_id",
                    "task_id",
                    "scheduling_class",
                    "scheduling_class_reason",
                )
            }
            for item in selected
        ],
    }
    return [str(item["worker_id"]) for item in selected], evidence


def active_queue_lock_ids(project_root: Path) -> set[str]:
    return claim_next_task.active_lock_ids(load_json(task_file(project_root, "agent_locks.json")))


def queue_exists(project_root: Path) -> bool:
    return task_file(project_root, "task_queue.json").exists()


def worker_lock_preflight(
    args: argparse.Namespace,
    project_root: Path,
    running: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    cfg = {
        "project_id": project_root.name,
        "name": project_root.name,
        "local_path": str(project_root),
        "base_ref": args.base_ref,
        "base_branch": args.push_ref or str(args.base_ref).removeprefix("origin/") or "develop",
        "task_queue_github_ref": args.base_ref,
        "agent_locks_git_ref": args.base_ref,
    }
    preflight = runner_readiness_report.worker_lock_preflight(cfg, {})
    active_ids = active_queue_lock_ids(project_root)
    running_task_ids = {
        str(item.get("task_id") or "").strip()
        for item in (running or [])
        if str(item.get("task_id") or "").strip()
    }
    correlated = (
        bool(active_ids)
        and active_ids <= running_task_ids
        and int(preflight.get("active_locks") or 0) == len(active_ids)
        and int(preflight.get("expired_queue_locks") or 0) == 0
    )
    preflight["active_lock_task_ids"] = sorted(active_ids)
    preflight["running_task_ids"] = sorted(running_task_ids)
    preflight["uncorrelated_active_lock_task_ids"] = sorted(active_ids - running_task_ids)
    if correlated:
        preflight.update(
            {
                "ok": True,
                "reason": "active_worker_locks_correlated_to_live_cycles",
                "blockers": [],
                "replenishment_safe": True,
            }
        )
    else:
        preflight["replenishment_safe"] = not active_ids
    if not runner_readiness_report.is_git_worktree(project_root):
        blockers = list(preflight.get("blockers") or [])
        blockers.append("command_root_not_git_worktree")
        preflight.update({
            "ok": False,
            "reason": "command_root_not_git_worktree",
            "blockers": blockers,
        })
    return preflight


def running_worker_cycles(project_root: Path) -> list[dict[str, Any]]:
    try:
        proc = subprocess.run(["pgrep", "-af", "run_worker_cycle.py"], text=True, capture_output=True, check=False)
    except OSError:
        return []
    if proc.returncode not in (0, 1):
        return []
    current_pid = str(os.getpid())
    result: list[dict[str, Any]] = []
    for line in proc.stdout.splitlines():
        if not line.strip():
            continue
        pid, _, command = line.partition(" ")
        if pid == current_pid:
            continue
        try:
            argv = shlex.split(command, posix=os.name != "nt")
        except ValueError:
            continue
        arguments: dict[str, str] = {}
        for index, value in enumerate(argv):
            if value.startswith("--") and "=" in value:
                key, parsed = value.split("=", 1)
                arguments[key] = parsed
            elif value.startswith("--") and index + 1 < len(argv):
                arguments[value] = argv[index + 1]
        process_root = arguments.get("--project-root")
        if not process_root or Path(process_root).resolve() != project_root:
            continue
        result.append(
            {
                "pid": int(pid) if pid.isdigit() else pid,
                "command": command,
                "task_id": arguments.get("--task-id"),
                "worker_id": arguments.get("--worker-id"),
            }
        )
    return result


def active_locked_paths(project_root: Path, active_ids: set[str]) -> list[str]:
    queue = load_json(task_file(project_root, "task_queue.json"))
    paths: list[str] = []
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict) or claim_next_task.task_id(task) not in active_ids:
            continue
        paths.extend(parallel_conflict_paths([str(path) for path in task.get("allowed_paths") or []]))
    return paths


def terminate_process_group(proc: subprocess.Popen[Any]) -> None:
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    except OSError:
        proc.terminate()


def kill_process_group(proc: subprocess.Popen[Any]) -> None:
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    except OSError:
        proc.kill()


def wait_for_started_lanes(
    processes: list[tuple[subprocess.Popen[Any], dict[str, Any], dict[str, Any] | None, float]],
    *,
    runtime_root: Path,
    timeout_seconds: float,
) -> None:
    timeout_enabled = timeout_seconds > 0
    deadline = time.monotonic() + timeout_seconds if timeout_enabled else None
    for proc, item, lease_result, started_at in processes:
        wait_timeout = None
        if deadline is not None:
            wait_timeout = max(0.0, deadline - time.monotonic())
        try:
            returncode = proc.wait(timeout=wait_timeout)
        except subprocess.TimeoutExpired:
            terminate_process_group(proc)
            try:
                returncode = proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                kill_process_group(proc)
                returncode = proc.wait(timeout=10)
            item.update({
                "completed": False,
                "timed_out": True,
                "returncode": returncode,
                "duration_seconds": round(time.monotonic() - started_at, 3),
            })
        else:
            item.update({
                "completed": True,
                "timed_out": False,
                "returncode": returncode,
                "duration_seconds": round(time.monotonic() - started_at, 3),
            })
        if isinstance(lease_result, dict) and lease_result.get("lease"):
            execution_lease_manager.release(
                runtime_root,
                str(lease_result["lease"]["lease_id"]),
            )


def write_worker_pool_plan(project_root: Path, report: dict[str, Any]) -> Path:
    """Persist worker-pool generated state using the pre-apply recovery contract."""
    report["schema_version"] = WORKER_POOL_PLAN_SCHEMA_VERSION
    output = task_manager_dir(project_root) / "worker_pool_last_plan.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output


def persist_worker_pool_plan(
    project_root: Path,
    runtime_root: Path,
    report: dict[str, Any],
    *,
    detached: bool,
) -> Path:
    if not detached:
        return write_worker_pool_plan(project_root, report)
    report["schema_version"] = WORKER_POOL_PLAN_SCHEMA_VERSION
    output = runtime_root / "worker-pool-plans" / project_root.name / "latest.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description="Start bounded worker pool lanes.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--worker-base-ref")
    parser.add_argument("--worker-context-ref")
    parser.add_argument("--push-ref")
    parser.add_argument("--machine-id", default="aistudio")
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--execution-policy", help="Optional execution policy JSON for host-level worker leases. Applies only with --apply.")
    parser.add_argument("--max-total-workers", type=int, default=0, help="0 uses limit-aware dynamic capacity.")
    parser.add_argument("--profiles", nargs="*", default=list(DEFAULT_PROFILES))
    parser.add_argument("--max-tasks-per-lane", type=int, default=0, help="Override profile max_tasks_per_session for every lane. 0 uses profile defaults.")
    parser.add_argument("--ignore-profile-task-limit", action="store_true", help="Allow unbounded lanes unless --max-tasks-per-lane is set.")
    parser.add_argument("--claim-push-retries", type=int, default=5)
    parser.add_argument("--claim-push-retry-delay", type=float, default=1.0)
    parser.add_argument("--worker-timeout-seconds", type=float, default=7200.0, help="Maximum seconds per launched Codex worker. 0 disables the timeout.")
    parser.add_argument("--codex-bin", default=os.environ.get("CODEX_BIN", "codex"), help="Codex executable required before worker claims are launched.")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--apply", action="store_true", help="Actually start worker runner lanes. Default prints a plan.")
    parser.add_argument("--detach", action="store_true", help="Return after lanes start; each lane owns its execution lease and result lifecycle.")
    parser.add_argument("--replenish-active", action="store_true", help="Fill free capacity only when every active lock maps to an exact live worker cycle.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    running = running_worker_cycles(project_root) if args.apply else []
    if running and not args.replenish_active:
        report = {
            "generated_at": utc_now(),
            "apply": args.apply,
            "status": "already_running",
            "selected_count": 0,
            "skipped_count": 1,
            "skipped": [{"reason": "worker_pool_already_running", "running": running}],
            "budget_allowed": {},
            "launches": [],
        }
        persist_worker_pool_plan(
            project_root,
            Path(args.runtime_root).expanduser(),
            report,
            detached=bool(args.detach),
        )
        append_log(project_root, "worker-pool", "worker_pool_already_running", severity="info", running=running)
        print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else "worker pool already running")
        return 0

    preflight = worker_lock_preflight(args, project_root, running)
    if args.apply and not preflight.get("ok"):
        report = {
            "generated_at": utc_now(),
            "apply": args.apply,
            "status": "rejected",
            "error": "worker_lock_preflight_failed",
            "selected_count": 0,
            "skipped_count": 1,
            "skipped": [{"reason": "worker_lock_preflight_failed", "preflight": preflight}],
            "budget_allowed": {},
            "launches": [],
            "preflight": preflight,
        }
        persist_worker_pool_plan(
            project_root,
            Path(args.runtime_root).expanduser(),
            report,
            detached=bool(args.detach),
        )
        append_log(project_root, "worker-pool", "worker_lock_preflight_failed", severity="warning", preflight=preflight)
        print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else "worker lock preflight failed")
        return 2

    host_readiness = codex_worker_host_readiness(args.codex_bin) if args.apply else {"ok": True}
    if args.apply and not host_readiness.get("ok"):
        reason = str(host_readiness.get("reason") or "codex_host_unavailable")
        skipped = [{"worker_id": profile, "reason": reason, "codex_bin": args.codex_bin} for profile in args.profiles]
        report = {
            "generated_at": utc_now(),
            "apply": args.apply,
            "status": "blocked",
            "error": reason,
            "selected_count": 0,
            "skipped_count": len(skipped),
            "skipped": skipped,
            "budget_allowed": {},
            "launches": [],
            "preflight": preflight,
            "codex_bin": args.codex_bin,
            "host_readiness": host_readiness,
        }
        persist_worker_pool_plan(
            project_root,
            Path(args.runtime_root).expanduser(),
            report,
            detached=bool(args.detach),
        )
        append_log(project_root, "worker-pool", reason, severity="warning", codex_bin=args.codex_bin)
        print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else reason)
        return 0

    profiles_by_id = worker_profiles(project_root)
    budget_allowed = allowed_profiles(project_root)
    unsupported_models = unsupported_codex_models()
    capacity_plan = model_resource_router.route(
        project_root,
        Path(args.runtime_root).expanduser().resolve(),
        "worker",
        unavailable=unsupported_models,
    )
    max_total_workers = args.max_total_workers if args.max_total_workers > 0 else int(capacity_plan.get("recommended_max_workers") or len(DEFAULT_PROFILES))
    available_capacity = max(0, max_total_workers - len(running))
    if args.apply and available_capacity == 0:
        report = {
            "generated_at": utc_now(),
            "apply": args.apply,
            "status": "capacity_full",
            "selected_count": 0,
            "skipped_count": 1,
            "skipped": [{"reason": "worker_capacity_full", "running": running}],
            "budget_allowed": {},
            "capacity_plan": capacity_plan,
            "max_total_workers": max_total_workers,
            "available_capacity": 0,
            "running": running,
            "launches": [],
            "preflight": preflight,
        }
        persist_worker_pool_plan(
            project_root,
            Path(args.runtime_root).expanduser(),
            report,
            detached=bool(args.detach),
        )
        print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else "worker capacity full")
        return 0
    skipped: list[dict[str, Any]] = []
    planned_lanes: list[dict[str, Any]] = []
    planned_lock_ids = active_queue_lock_ids(project_root)
    planned_paths = active_locked_paths(project_root, planned_lock_ids)
    has_queue = queue_exists(project_root)
    queue_data = load_json(task_file(project_root, "task_queue.json")) if has_queue else {"tasks": []}
    locks_data = load_json(task_file(project_root, "agent_locks.json")) if has_queue else {"locks": []}
    potential_slot_limit = sum(
        max(1, int((profiles_by_id.get(profile) or {}).get("max_parallel_lanes") or 1))
        for profile in args.profiles
    )
    profile_slots = expanded_profile_slots(args.profiles, profiles_by_id, potential_slot_limit)
    running_profiles = Counter(str(item.get("worker_id") or "") for item in running)
    available_profile_slots: list[str] = []
    for profile in profile_slots:
        if running_profiles.get(profile, 0):
            running_profiles[profile] -= 1
            continue
        available_profile_slots.append(profile)
    if has_queue:
        profile_slots, fairness = profile_slot_fairness_plan(
            project_root,
            profiles_by_id,
            available_profile_slots,
            planned_lock_ids,
            capacity=available_capacity,
        )
    else:
        profile_slots = available_profile_slots[:available_capacity]
        fairness = {
            "candidate_class_counts": {},
            "selected_class_counts": {},
            "background_candidate_count": 0,
            "primary_candidate_count": 0,
            "primary_slot_reservation_required": False,
            "primary_slot_reserved": False,
            "reservation_displaced_class": None,
            "reservation_deferred_reason": None,
            "slot_evidence": [],
        }
    for profile in profile_slots:
        if not budget_allowed.get(profile, True):
            skipped.append({"worker_id": profile, "reason": "budget_guard"})
            continue
        planned_task = choose_claimable_task(project_root, profiles_by_id, profile, planned_lock_ids, planned_paths) if has_queue else None
        planned_task_id = claim_next_task.task_id(planned_task) if planned_task else None
        if has_queue and not planned_task_id:
            skipped.append({"worker_id": profile, "reason": "no_eligible_task"})
            if args.apply:
                append_log(project_root, "worker-pool", "worker_lane_skipped", severity="info", worker_id=profile, reason="no_eligible_task")
            continue
        routing_task = dict(planned_task or {})
        profile_config = profiles_by_id.get(profile) or {}
        fast_track = (
            fast_track_gate.evaluate(
                planned_task_id,
                queue_data,
                locks_data,
                profile,
                profile_config,
            )
            if planned_task_id
            else None
        )
        if profile_config.get("model_candidates") and not routing_task.get("model_candidates"):
            routing_task["model_candidates"] = profile_config["model_candidates"]
        packet_repair_only = bool(routing_task.pop("_packet_repair_only", False))
        if packet_repair_only:
            route = {
                "status": "selected",
                "model": None,
                "reasoning_effort": None,
                "reason": "task_packet_defect_repair_only",
            }
            model = None
        elif has_queue:
            route = model_resource_router.route(
                project_root,
                Path(args.runtime_root).expanduser().resolve(),
                "worker",
                routing_task,
                unavailable=unsupported_models,
            )
            model = apply_model_fallback(str(route.get("model") or profile_model(profiles_by_id, profile) or "")) or None
        else:
            model = apply_model_fallback(profile_model(profiles_by_id, profile))
            route = {
                "status": "selected" if model not in unsupported_models else "blocked",
                "model": model,
                "reasoning_effort": profile_config.get("reasoning_effort"),
                "reason": "profile_default_without_queue",
            }
        if route.get("status") != "selected" or model in unsupported_models:
            skipped.append({"worker_id": profile, "reason": "model_unavailable", "model": model, "routing": route})
            continue
        if planned_task_id:
            planned_lock_ids.add(planned_task_id)
            planned_paths.extend(
                parallel_conflict_paths(
                    [str(path) for path in (planned_task or {}).get("allowed_paths") or []],
                )
            )
        scheduling_class, scheduling_class_reason = (
            claim_next_task.scheduling_class(planned_task)
            if planned_task
            else ("none", "queue_not_available")
        )
        planned_lanes.append({
            "worker_id": profile,
            "task_id": planned_task_id,
            "model": model,
            "reasoning_effort": route.get("reasoning_effort"),
            "routing": route,
            "packet_repair_only": packet_repair_only,
            "scheduling_class": scheduling_class,
            "scheduling_class_reason": scheduling_class_reason,
            "fast_track": fast_track,
        })
    planned_lanes = planned_lanes[:available_capacity]
    if not args.apply and any(lane.get("task_id") for lane in planned_lanes) and not preflight.get("ok"):
        report = {
            "generated_at": utc_now(),
            "apply": args.apply,
            "status": "rejected",
            "error": "worker_lock_preflight_failed",
            "selected_count": 0,
            "skipped_count": 1,
            "skipped": [{"reason": "worker_lock_preflight_failed", "preflight": preflight}],
            "budget_allowed": budget_allowed,
            "launches": [],
            "preflight": preflight,
            "fairness": fairness,
        }
        print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else "worker lock preflight failed")
        return 0
    launches: list[dict[str, Any]] = []
    started_processes: list[tuple[subprocess.Popen[Any], dict[str, Any], dict[str, Any] | None, float]] = []
    execution_policy = None
    if args.execution_policy:
        execution_policy = execution_lease_manager.default_policy()
        execution_policy.update(load_json(Path(args.execution_policy).expanduser()))

    for lane in planned_lanes:
        profile = str(lane["worker_id"])
        planned_task_id = str(lane.get("task_id") or "")
        model = lane.get("model")
        reasoning_effort = lane.get("reasoning_effort")
        packet_repair_only = bool(lane.get("packet_repair_only"))
        if packet_repair_only:
            cmd = [
                sys.executable,
                str(script_path("claim_next_task.py")),
                "--project-root", str(project_root),
                "--base-ref", args.base_ref,
                "--worker-id", profile,
                "--machine-id", args.machine_id,
                "--runtime-root", args.runtime_root,
                "--push-retries", str(args.claim_push_retries),
                "--push-retry-delay", str(args.claim_push_retry_delay),
                "--json",
            ]
        else:
            cmd = [
                sys.executable,
                str(script_path("run_worker_cycle.py")),
                "--project-root", str(project_root),
                "--base-ref", args.base_ref,
                "--worker-id", profile,
                "--machine-id", args.machine_id,
                "--runtime-root", args.runtime_root,
                "--claim-push-retries", str(args.claim_push_retries),
                "--claim-push-retry-delay", str(args.claim_push_retry_delay),
                "--worker-timeout-seconds", str(args.worker_timeout_seconds),
                "--codex-bin", args.codex_bin,
            ]
        if model and not packet_repair_only:
            cmd.extend(["--model", str(model)])
        if reasoning_effort:
            cmd.extend(["--reasoning-effort", str(reasoning_effort)])
        if not packet_repair_only:
            cmd.extend(["--prompt", worker_prompt(profile, lane.get("fast_track"))])
        if args.worker_base_ref and not packet_repair_only:
            cmd.extend(["--worker-base-ref", args.worker_base_ref])
        if args.worker_context_ref and not packet_repair_only:
            cmd.extend(["--worker-context-ref", args.worker_context_ref])
        if args.push_ref:
            cmd.extend(["--push-ref", args.push_ref])
        if args.fetch:
            cmd.append("--fetch")
        if planned_task_id:
            cmd.extend(["--task-id", planned_task_id])
        lane_limit = args.max_tasks_per_lane
        if not lane_limit and not args.ignore_profile_task_limit:
            lane_limit = profile_task_limit(profiles_by_id, profile)
        if lane_limit and not packet_repair_only:
            cmd.extend(["--max-tasks", str(lane_limit)])
        if not args.apply:
            cmd.append("--dry-run")

        lease_result = None
        if args.apply and execution_policy is not None and not packet_repair_only:
            lease_ttl = int(args.worker_timeout_seconds + 600) if args.worker_timeout_seconds > 0 else int(execution_policy.get("default_ttl_seconds") or 7200)
            lease_result = execution_lease_manager.acquire(
                Path(args.runtime_root).expanduser(),
                execution_policy,
                project_root.name,
                profile,
                model or "unknown",
                ttl_seconds=lease_ttl,
            )
            if not lease_result.get("acquired"):
                skipped.append({"worker_id": profile, "reason": str(lease_result.get("reason") or "execution_lease_unavailable"), "lease": lease_result})
                if args.apply:
                    append_log(project_root, "worker-pool", "worker_lane_skipped", severity="warning", worker_id=profile, reason=lease_result.get("reason"), lease=lease_result)
                continue
            cmd.extend(["--execution-lease-id", str(lease_result["lease"]["lease_id"])])

        log_root = Path(args.runtime_root).expanduser() / "worker-lane-logs" / project_root.name / datetime.now(timezone.utc).strftime("%Y-%m-%d")
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        stdout_log = log_root / f"{profile}-{stamp}.stdout.log"
        stderr_log = stdout_log.with_suffix(".stderr.log")
        item = {
            "worker_id": profile,
            "planned_task_id": planned_task_id or None,
            "max_tasks": lane_limit or None,
            "command": cmd,
            "started": False,
            "pid": None,
            "stdout_log": str(stdout_log),
            "stderr_log": str(stderr_log),
            "execution_lease": lease_result.get("lease") if isinstance(lease_result, dict) else None,
            "model": model,
            "reasoning_effort": reasoning_effort,
            "routing": lane.get("routing"),
            "scheduling_class": lane.get("scheduling_class"),
            "scheduling_class_reason": lane.get("scheduling_class_reason"),
            "fast_track": lane.get("fast_track"),
        }
        if args.apply:
            log_root.mkdir(parents=True, exist_ok=True)
            stdout_handle = stdout_log.open("a", encoding="utf-8")
            stderr_handle = stderr_log.open("a", encoding="utf-8")
            proc = subprocess.Popen(
                cmd,
                cwd=str(project_root),
                stdin=subprocess.DEVNULL,
                stdout=stdout_handle,
                stderr=stderr_handle,
                close_fds=True,
                start_new_session=True,
            )
            stdout_handle.close()
            stderr_handle.close()
            time.sleep(1.0)
            early_returncode = proc.poll()
            if early_returncode is None:
                item.update({"started": True, "pid": proc.pid})
                if hasattr(proc, "wait"):
                    started_processes.append((proc, item, lease_result, time.monotonic()))
            else:
                stderr_tail = ""
                try:
                    stderr_tail = stderr_log.read_text(encoding="utf-8", errors="replace")[-4000:]
                except OSError:
                    stderr_tail = ""
                item.update(
                    {
                        "started": False,
                        "pid": proc.pid,
                        "error": "worker_lane_exited_early",
                        "returncode": early_returncode,
                        "stderr_tail": stderr_tail,
                    }
                )
                if isinstance(lease_result, dict) and lease_result.get("lease"):
                    execution_lease_manager.release(
                        Path(args.runtime_root).expanduser(),
                        str(lease_result["lease"]["lease_id"]),
                    )
        launches.append(item)
        if args.apply:
            if item.get("started"):
                append_log(
                    project_root,
                    "worker-pool",
                    "worker_lane_started",
                    severity="info",
                    worker_id=profile,
                    apply=args.apply,
                    pid=item.get("pid"),
                    stdout_log=str(stdout_log),
                    stderr_log=str(stderr_log),
                    worker_timeout_seconds=args.worker_timeout_seconds,
                )
            else:
                append_log(
                    project_root,
                    "worker-pool",
                    "worker_lane_failed_to_start",
                    severity="error",
                    worker_id=profile,
                    apply=args.apply,
                    pid=item.get("pid"),
                    returncode=item.get("returncode"),
                    stderr_tail=item.get("stderr_tail"),
                    stdout_log=str(stdout_log),
                    stderr_log=str(stderr_log),
                )

    if args.apply and not args.detach:
        wait_for_started_lanes(
            started_processes,
            runtime_root=Path(args.runtime_root).expanduser(),
            timeout_seconds=args.worker_timeout_seconds,
        )

    planned_class_counts = Counter(
        str(lane.get("scheduling_class") or "none")
        for lane in planned_lanes
        if lane.get("task_id")
    )
    fairness["planned_class_counts"] = dict(sorted(planned_class_counts.items()))
    fairness["primary_slot_reserved"] = bool(
        fairness.get("primary_slot_reservation_required")
        and planned_class_counts.get("primary_delivery", 0)
    )
    report = {
        "generated_at": utc_now(),
        "apply": args.apply,
        "selected_count": len(planned_lanes),
        "skipped_count": len(skipped),
        "skipped": skipped,
        "budget_allowed": budget_allowed,
        "capacity_plan": capacity_plan,
        "max_total_workers": max_total_workers,
        "available_capacity": available_capacity,
        "running": running,
        "detached": bool(args.detach),
        "launches": launches,
        "fairness": fairness,
    }
    if args.apply:
        persist_worker_pool_plan(
            project_root,
            Path(args.runtime_root).expanduser(),
            report,
            detached=bool(args.detach),
        )
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else f"worker lanes: {len(launches)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
