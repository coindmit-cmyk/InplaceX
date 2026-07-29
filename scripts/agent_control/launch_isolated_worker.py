#!/usr/bin/env python3
"""Launch a Codex worker in an isolated git worktree.

This is a small safety launcher for Phase 2.1 remote workers. It prevents the
failure mode where several workers share one checkout, switch branches under
each other, and see different task queues.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_MODELS = {
    "auto-worker-5.3-mini": "gpt-5.3-codex-spark",
    "auto-worker-5.3": "gpt-5.3-codex-spark",
    "auto-worker-5.5": "gpt-5.5",
    "auto-worker-5.5max": "gpt-5.5",
}
DEFAULT_MODEL_FALLBACKS: dict[str, str | None] = {}


def now_compact() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def run(cmd: list[str], cwd: Path, check: bool = True) -> subprocess.CompletedProcess[str]:
    if cmd and cmd[0] == "git":
        cmd = ["git", "-c", "core.longpaths=true", *cmd[1:]]
    return subprocess.run(cmd, cwd=str(cwd), check=check, text=True, capture_output=True)


def local_branch_exists(repo: Path, branch: str) -> bool:
    proc = run(["git", "show-ref", "--verify", "--quiet", f"refs/heads/{branch}"], repo, check=False)
    return proc.returncode == 0


def git_path_arg(path: Path) -> str:
    return path.expanduser().as_posix()


def git_longpaths_command(*args: str) -> list[str]:
    return ["git", "-c", "core.longpaths=true", *args]


def worktree_add_command(repo: Path, branch: str, worktree_path: Path, base_ref: str) -> list[str]:
    if local_branch_exists(repo, branch):
        return git_longpaths_command("worktree", "add", git_path_arg(worktree_path), branch)
    return git_longpaths_command("worktree", "add", "-b", branch, git_path_arg(worktree_path), base_ref)


def stale_worktree_path(stderr: str, worktree_path: Path) -> Path | None:
    match = re.search(r"already used by worktree at '([^']+)'", stderr)
    if not match:
        return None
    stale = Path(match.group(1)).expanduser()
    allowed_parent = worktree_path.parent.expanduser()
    try:
        stale.relative_to(allowed_parent)
    except ValueError:
        return None
    return stale


def align_worker_worktree_to_base(worktree_path: Path, base_ref: str) -> None:
    head_sha = resolve_ref_sha(worktree_path, "HEAD")
    base_sha = resolve_ref_sha(worktree_path, base_ref)
    if head_sha == base_sha:
        return

    ancestry = run(["git", "merge-base", "--is-ancestor", head_sha, base_sha], worktree_path, check=False)
    if ancestry.returncode != 0:
        raise RuntimeError(
            "existing worker branch cannot be advanced to the requested base without discarding unique commits\n"
            f"worker_head: {head_sha}\nrequested_base: {base_sha}"
        )

    fast_forward = run(["git", "merge", "--ff-only", base_sha], worktree_path, check=False)
    if fast_forward.returncode != 0:
        raise RuntimeError(
            "existing worker branch fast-forward to requested base failed\n"
            f"worker_head: {head_sha}\nrequested_base: {base_sha}\n"
            f"stdout: {fast_forward.stdout}\nstderr: {fast_forward.stderr}"
        )
    updated_head = resolve_ref_sha(worktree_path, "HEAD")
    if updated_head != base_sha:
        raise RuntimeError(
            "existing worker branch fast-forward did not reach the requested base\n"
            f"worker_head: {updated_head}\nrequested_base: {base_sha}"
        )


def add_worker_worktree(repo: Path, branch: str, worktree_path: Path, base_ref: str) -> None:
    command = worktree_add_command(repo, branch, worktree_path, base_ref)
    proc = run(command, repo, check=False)
    if proc.returncode == 0:
        align_worker_worktree_to_base(worktree_path, base_ref)
        return
    stderr = proc.stderr or ""
    if "-b" in command and "already exists" in stderr.lower():
        retry = git_longpaths_command("worktree", "add", git_path_arg(worktree_path), branch)
        retry_proc = run(retry, repo, check=False)
        if retry_proc.returncode == 0:
            align_worker_worktree_to_base(worktree_path, base_ref)
            return
        raise RuntimeError(
            "git worktree add retry failed\n"
            f"command: {retry}\nstdout: {retry_proc.stdout}\nstderr: {retry_proc.stderr}"
        )
    stale = stale_worktree_path(stderr, worktree_path)
    if stale is not None:
        remove = git_longpaths_command("worktree", "remove", "--force", git_path_arg(stale))
        remove_proc = run(remove, repo, check=False)
        if remove_proc.returncode == 0:
            retry_proc = run(command, repo, check=False)
            if retry_proc.returncode == 0:
                align_worker_worktree_to_base(worktree_path, base_ref)
                return
            raise RuntimeError(
                "git worktree add retry after stale cleanup failed\n"
                f"command: {command}\nstdout: {retry_proc.stdout}\nstderr: {retry_proc.stderr}"
            )
        raise RuntimeError(
            "stale git worktree cleanup failed\n"
            f"command: {remove}\nstdout: {remove_proc.stdout}\nstderr: {remove_proc.stderr}"
        )
    raise RuntimeError(
        "git worktree add failed\n"
        f"command: {command}\nstdout: {proc.stdout}\nstderr: {proc.stderr}"
    )


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


def resolve_model(project_root: Path, worker_id: str, explicit_model: str | None) -> str | None:
    if explicit_model:
        return apply_model_fallback(explicit_model)
    profile = load_worker_profile(project_root, worker_id)
    value = profile.get("codex_model") or profile.get("model") or profile.get("model_alias")
    if isinstance(value, str) and value.strip():
        return apply_model_fallback(value.strip())
    return apply_model_fallback(DEFAULT_MODELS.get(worker_id))


def ref_has_path(repo: Path, ref: str, path: str) -> bool:
    proc = run(["git", "cat-file", "-e", f"{ref}:{path}"], repo, check=False)
    return proc.returncode == 0


def resolve_ref_sha(repo: Path, ref: str) -> str:
    proc = run(["git", "rev-parse", "--verify", f"{ref}^{{commit}}"], repo, check=False)
    sha = proc.stdout.strip().lower()
    if proc.returncode != 0 or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise RuntimeError(f"unable to resolve immutable commit for {ref}: {proc.stderr.strip()}")
    return sha


def normalize_context_path(path: str) -> str:
    normalized = path.replace("\\", "/").strip("/")
    parts = normalized.split("/") if normalized else []
    if (
        not parts
        or normalized.startswith(":")
        or any(part in {"", ".", ".."} for part in parts)
        or parts[0] == ".git"
    ):
        raise RuntimeError(f"unsafe worker context path: {normalized or '<empty>'}")
    return normalized


def checkout_context_paths(worktree: Path, context_ref: str, paths: list[str]) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for path in paths:
        normalized = normalize_context_path(path)
        if not ref_has_path(worktree, context_ref, normalized):
            results.append({"path": normalized, "state": "missing_in_context_ref"})
            continue
        proc = run(["git", "checkout", context_ref, "--", f":(literal){normalized}"], worktree, check=False)
        results.append(
            {
                "path": normalized,
                "state": "checked_out" if proc.returncode == 0 else "failed",
                "exit_code": proc.returncode,
                "stderr": proc.stderr,
            }
        )
    return results


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


def slug(value: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip())
    normalized = normalized.strip("-._")
    return normalized.lower() or "worker"


def default_worktree_root(project_root: Path, runtime_root: Path) -> Path:
    return runtime_root / "worker-worktrees" / project_root.name


def ensure_codex(codex_bin: str) -> str:
    found = shutil.which(codex_bin)
    if not found:
        raise SystemExit(f"codex executable not found: {codex_bin}")
    return found


def build_branch(machine_id: str, worker_id: str, run_id: str) -> str:
    return f"AiStudio/Agent/worker/{slug(machine_id)}/{slug(worker_id)}/{run_id}"


def task_context(worktree: Path, task_id: str) -> dict[str, Any]:
    queue_path = worktree / "AiStudio" / "Task_manager" / "task_queue.json"
    try:
        queue = json.loads(queue_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    for task in queue.get("tasks") or []:
        if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "") == task_id:
            return task
    return {}


def task_context_prompt(task: dict[str, Any]) -> str:
    if not task:
        return ""
    input_refs = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    evidence = input_refs.get("evidence") if isinstance(input_refs.get("evidence"), dict) else {}
    paths = evidence.get("paths") if isinstance(evidence.get("paths"), list) else []
    allowed = [str(item) for item in task.get("allowed_paths") or [] if str(item)]
    forbidden = [str(item) for item in task.get("forbidden_paths") or [] if str(item)]
    lines = [
        "",
        "Assigned Task Packet Constraints:",
        f"- allowed_paths: {json.dumps(allowed, ensure_ascii=False)}",
        f"- forbidden_paths: {json.dumps(forbidden, ensure_ascii=False)}",
    ]
    packet_fields = (
        "priority",
        "complexity",
        "type",
        "summary",
        "description",
        "acceptance_criteria",
        "checks",
        "script_actions",
        "deliverables",
        "expected_output",
        "source_refs",
    )
    packet_snapshot = {
        field: task[field]
        for field in packet_fields
        if field in task and task[field] not in (None, "", [])
    }
    compact_input_refs = {
        field: input_refs[field]
        for field in ("base_branch", "source_candidate", "source_report", "evidence")
        if field in input_refs and input_refs[field] not in (None, "", [])
    }
    if compact_input_refs:
        packet_snapshot["input_refs"] = compact_input_refs
    if packet_snapshot:
        lines.extend(
            [
                f"- assigned_packet_snapshot: {json.dumps(packet_snapshot, ensure_ascii=False, separators=(',', ':'))}",
                "- This injected snapshot satisfies the task_queue read for the assigned row. Do not scan or load the full task_queue.json unless the snapshot is missing a required field or conflicts with current Git evidence.",
            ]
        )
    if str(task.get("complexity") or "").strip().upper() == "S":
        lines.extend(
            [
                "- S-task resource budget: use the shortest direct path; do not perform architecture analysis, repository-wide review, broad discovery, scanner, repair, or backlog creation.",
                "- Inspect only START_HERE plus the named evidence and target files needed for this packet. Prefer one bounded search over repeated file reads.",
                "- Run only the packet checks (maximum 3). If a required target is absent, evidence already satisfies the contract, or no valid edit is possible, record that fact and stop immediately.",
                "- Do not create additional work. End the run as soon as the accepted scope or the first concrete blocker is recorded.",
            ]
        )
    if paths:
        lines.append(f"- batch_evidence_paths: {json.dumps(paths, ensure_ascii=False)}")
    lines.extend(
        [
            "- Only edit files that match allowed_paths and are needed for the assigned batch.",
            "- Do not edit AiStudio/Task_manager files, old/**, docs/imports/**, unrelated worker reports, locks, events or generated runtime artifacts.",
            "- Do not normalize line endings or reformat files outside the assigned batch.",
        ]
    )
    return "\n".join(lines)


def build_task_prompt(
    base_prompt: str,
    task_id: str,
    task_title: str | None = None,
    task: dict[str, Any] | None = None,
    *,
    base_ref_sha: str | None = None,
) -> str:
    task_data = task or {}
    title_line = f"\nAssigned task title: {task_title}" if task_title else ""
    context = task_context_prompt(task_data)
    execution_base = str(base_ref_sha or "").strip()
    freshness_contract = (
        f"Authorized immutable execution base: {execution_base}. "
        if execution_base
        else ""
    )
    prompt = (
        f"{base_prompt}\n\n"
        f"Assigned task: {task_id}{title_line}\n"
        f"{context}\n"
        "This task was already claimed by the central runner from AiStudio/Task_manager/task_queue.json. "
        "Do not select a different task and do not infer executable work from docs/plans/tasks; those files are legacy/context docs only when referenced by the packet. "
        f"{freshness_contract}"
        "The claimed Task_manager row is the execution authority; a source backlog or planning document with worker_ready=false does not revoke this claim. "
        "A remote base advance after launch does not make this packet stale when every intervening path is runner-owned state under AiStudio/Task_manager/. "
        "Inspect the changed paths from the immutable execution base before deciding freshness; continue without merging or editing runner-owned state when drift is state-only. "
        "If intervening changes touch implementation scope, required source refs, or the assigned packet is missing, stop and report needs_dispatcher_repair instead of working from docs. "
        "Keep implementation edits inside the task allowed paths. Do not edit unrelated queue rows, locks, "
        "events, process logs or integration artifacts. If you must record task outcome in task_queue.json, "
        "update only the assigned task status/evidence; the central sync script owns durable queue/lock state. "
        "Run every required script_actions[].command plus git diff --check when practical, or the closest "
        "targeted project checks for the changed paths. Treat prose-only checks as coverage requirements, "
        "not shell commands; if required coverage has no executable action and cannot be proven, report "
        "needs_dispatcher_repair. Your final answer must name the exact commands and "
        "outcomes, and must include check_status=passed, failed, partial, not_run, or blocked. If checks cannot "
        "be run or are intentionally skipped, still finish the implementation when safe and state that Integrator "
        "must run the missing checks. "
        "If the assigned task cannot be completed safely, update that task only with blocked, "
        "needs_task_packet, needs_human or needs_stronger_agent evidence. Always leave a concise worker result "
        "summary in your final answer; the runner will commit and push the worker branch after you exit."
    )
    if str(task_data.get("complexity") or "").strip().upper() == "S":
        prompt += (
            "\n\nFINAL S-TASK EXECUTION LIMIT (higher priority than repository guidance): "
            "use at most 8 total tool or shell calls. The runtime has already completed entry preflight, so do not "
            "follow links from START_HERE or read .agent/general.md, .agent/project.md, .agent/workflows.md, "
            ".agent/modules.md, AGENTS.md, doc/ai/**, or product documentation unless the assigned_packet_snapshot "
            "names that exact file. First test the exact concrete target paths from allowed_paths. If a required "
            "target is absent, do not search for substitutes: run the packet checks, report needs_dispatcher_repair, "
            "and stop. Do not inspect batch evidence source files when the target is absent or already satisfies the "
            "acceptance contract."
        )
    return prompt


def codex_exec_command(
    codex_bin: str,
    worktree_path: Path,
    model: str | None,
    reasoning_effort: str | None = None,
) -> list[str]:
    command = [
        codex_bin,
        "exec",
        "--cd",
        str(worktree_path),
        "--ignore-user-config",
    ]
    if model:
        command.extend(["--model", model])
    if reasoning_effort:
        command.extend(["--config", f'model_reasoning_effort="{reasoning_effort}"'])
    command.extend(
        [
            "--sandbox",
            "danger-full-access",
            "--dangerously-bypass-approvals-and-sandbox",
            "-",
        ]
    )
    return command


def start_codex_process(
    command: list[str],
    worktree_path: Path,
    prompt_file: Path,
    stdout_log: Path,
    stderr_log: Path,
) -> subprocess.Popen[str]:
    with (
        prompt_file.open("r", encoding="utf-8") as stdin_handle,
        stdout_log.open("w", encoding="utf-8") as stdout_handle,
        stderr_log.open("w", encoding="utf-8") as stderr_handle,
    ):
        return subprocess.Popen(
            command,
            cwd=str(worktree_path),
            stdin=stdin_handle,
            stdout=stdout_handle,
            stderr=stderr_handle,
            text=True,
            start_new_session=True,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Launch one Codex worker in its own git worktree.")
    parser.add_argument("--project-root", required=True, help="Main project checkout.")
    parser.add_argument("--base-ref", required=True, help="Fresh base ref for the worktree, for example origin/develop or an update branch.")
    parser.add_argument("--context-ref", help="Optional ref to copy task packet/context files from after creating the clean worktree.")
    parser.add_argument(
        "--context-path",
        action="append",
        default=[],
        help="Path to copy from --context-ref. Can be repeated.",
    )
    parser.add_argument("--worker-id", required=True, help="Worker id, for example auto-worker-5.3.")
    parser.add_argument("--prompt", required=True, help="Prompt passed to codex exec, for example 'Auto Worker 5.3'.")
    parser.add_argument("--task-id", help="Pre-claimed task id. When set, the worker prompt is constrained to this task.")
    parser.add_argument("--task-title", help="Pre-claimed task title to include in the worker prompt.")
    parser.add_argument("--branch-name", help="Worker branch name. Defaults to AiStudio/Agent/worker/<machine>/<worker>/<timestamp>.")
    parser.add_argument("--machine-id", default=os.environ.get("AGENT_MACHINE_ID", "aistudio"))
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--worktree-root", help="Directory that will contain isolated worktrees.")
    parser.add_argument("--codex-bin", default="codex")
    parser.add_argument("--model", help="Explicit Codex model. Defaults from .agent/worker_profiles.json.")
    parser.add_argument("--reasoning-effort", help="Explicit reasoning effort selected by the model resource router.")
    parser.add_argument("--fetch", action="store_true", help="Fetch/prune before creating the worktree.")
    parser.add_argument("--dry-run", action="store_true", help="Print the plan without creating a worktree or starting Codex.")
    args = parser.parse_args()

    project_root = Path(args.project_root).expanduser().resolve()
    runtime_root = Path(args.runtime_root).expanduser().resolve()
    worktree_root = Path(args.worktree_root).expanduser().resolve() if args.worktree_root else default_worktree_root(project_root, runtime_root)
    run_id = now_compact()
    safe_worker = slug(args.worker_id)
    safe_task = slug(args.task_id) if args.task_id else "unassigned"
    worktree_path = worktree_root / safe_worker / f"{safe_task}-{run_id}"
    branch = args.branch_name or build_branch(args.machine_id, args.worker_id, run_id)
    log_dir = runtime_root / "worker-logs" / project_root.name
    run_dir = runtime_root / "runs" / datetime.now(timezone.utc).strftime("%Y-%m-%d") / f"{project_root.name}-{safe_worker}-{run_id}"
    stdout_log = run_dir / "stdout.log"
    stderr_log = run_dir / "stderr.log"
    prompt_file = run_dir / "prompt.txt"
    launch_json = run_dir / "launch.json"
    model = resolve_model(project_root, args.worker_id, args.model)

    plan: dict[str, Any] = {
        "project_root": str(project_root),
        "base_ref": args.base_ref,
        "base_ref_sha": None,
        "context_ref": args.context_ref,
        "context_ref_sha": None,
        "context_paths": args.context_path,
        "context_checkout": [],
        "worker_id": args.worker_id,
        "model": model,
        "reasoning_effort": args.reasoning_effort,
        "prompt": args.prompt,
        "task_id": args.task_id,
        "machine_id": args.machine_id,
        "branch": branch,
        "worktree": str(worktree_path),
        "run_dir": str(run_dir),
        "prompt_file": str(prompt_file),
        "started_at": None,
        "pid": None,
        "dry_run": args.dry_run,
    }

    if args.dry_run:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0

    ensure_codex(args.codex_bin)
    if args.fetch:
        run(["git", "fetch", "--all", "--prune"], project_root)

    base_ref_sha = resolve_ref_sha(project_root, args.base_ref)
    context_ref_sha = resolve_ref_sha(project_root, args.context_ref) if args.context_ref else None
    plan["base_ref_sha"] = base_ref_sha
    plan["context_ref_sha"] = context_ref_sha
    worktree_root.mkdir(parents=True, exist_ok=True)
    run_dir.mkdir(parents=True, exist_ok=True)
    log_dir.mkdir(parents=True, exist_ok=True)

    if worktree_path.exists():
        raise SystemExit(f"worktree path already exists: {worktree_path}")

    add_worker_worktree(project_root, branch, worktree_path, base_ref_sha)
    if args.context_ref:
        context_paths = args.context_path or [
            "AiStudio/Task_manager/task_queue.json",
            "AiStudio/Task_manager/tasks",
            "AiStudio/Task_manager/clean_rebuild_plan.json",
        ]
        plan["context_paths"] = context_paths
        plan["context_checkout"] = checkout_context_paths(worktree_path, str(context_ref_sha), context_paths)

    prompt = args.prompt
    if args.task_id:
        prompt = build_task_prompt(
            args.prompt,
            args.task_id,
            args.task_title,
            task_context(worktree_path, args.task_id),
            base_ref_sha=base_ref_sha,
        )

    prompt_file.write_text(prompt, encoding="utf-8")
    command = codex_exec_command(args.codex_bin, worktree_path, model, args.reasoning_effort)
    proc = start_codex_process(command, worktree_path, prompt_file, stdout_log, stderr_log)

    plan.update(
        {
            "started_at": now_iso(),
            "pid": proc.pid,
            "command": command,
            "stdout_log": str(stdout_log),
            "stderr_log": str(stderr_log),
        }
    )
    launch_json.write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(plan, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
