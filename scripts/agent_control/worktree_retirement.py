#!/usr/bin/env python3
"""Archive terminal Git worktrees before retiring their branches."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PROTECTED_BRANCHES = {"main", "master", "develop", "release", "release/main", "codex", "old"}
PROTECTION_MODES = ("default", "canonical-only")
CANONICAL_PROTECTED_BRANCHES = {"develop", "release/main"}
TERMINAL_LIFECYCLE_CLASSES = {"merged_safe_delete", "archive_candidate", "cleanup_candidate"}
RECOVERY_CAPTURE_CLASSES = {"dirty_worker_candidate", "integration_recovery_candidate"}


def run(command: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=str(cwd) if cwd else None, text=True, capture_output=True, check=False)


def git(project_root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return run(["git", *args], project_root)


def now_utc() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip(".-") or "branch"


def list_worktrees(project_root: Path) -> list[dict[str, Any]]:
    proc = git(project_root, "worktree", "list", "--porcelain")
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "git worktree list failed")
    rows: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    for line in proc.stdout.splitlines():
        if line.startswith("worktree "):
            if current:
                rows.append(current)
            current = {"path": line.removeprefix("worktree ").strip(), "branch": None, "detached": False}
        elif current is not None and line.startswith("branch refs/heads/"):
            current["branch"] = line.removeprefix("branch refs/heads/").strip()
        elif current is not None and line == "detached":
            current["detached"] = True
    if current:
        rows.append(current)
    return rows


def commit_sha(project_root: Path, branch: str) -> str:
    proc = git(project_root, "rev-parse", "--verify", f"refs/heads/{branch}")
    return proc.stdout.strip() if proc.returncode == 0 else ""


def ref_sha(project_root: Path, ref: str) -> str:
    proc = git(project_root, "rev-parse", "--verify", ref)
    return proc.stdout.strip() if proc.returncode == 0 else ""


def branch_source(project_root: Path, branch: str, remote: str) -> dict[str, Any]:
    local_ref = f"refs/heads/{branch}"
    remote_ref = f"refs/remotes/{remote}/{branch}"
    local_sha = ref_sha(project_root, local_ref)
    remote_sha = ref_sha(project_root, remote_ref)
    source_ref = local_ref if local_sha else remote_ref if remote_sha else ""
    return {
        "source_ref": source_ref,
        "sha": local_sha or remote_sha,
        "local_branch_present": bool(local_sha),
        "remote_branch_present": bool(remote_sha),
        "remote_sha": remote_sha or None,
    }


def branch_age_days(project_root: Path, ref: str, now: datetime) -> int:
    proc = git(project_root, "show", "-s", "--format=%cI", ref)
    try:
        value = datetime.fromisoformat(proc.stdout.strip().replace("Z", "+00:00"))
    except ValueError:
        return 10**6
    return max(0, (now - value.astimezone(timezone.utc)).days)


def merged_base(project_root: Path, ref: str, base_refs: list[str]) -> str | None:
    for base_ref in base_refs:
        if git(project_root, "merge-base", "--is-ancestor", ref, base_ref).returncode == 0:
            return base_ref
    return None


def load_lifecycle_terminal_heads(path: Path) -> dict[str, str]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    decisions = (payload.get("scanner") or {}).get("decisions") if isinstance(payload, dict) else None
    if not isinstance(decisions, list):
        raise ValueError("lifecycle evidence does not contain scanner decisions")
    result: dict[str, str] = {}
    for row in decisions:
        if not isinstance(row, dict) or row.get("source_classification") not in TERMINAL_LIFECYCLE_CLASSES:
            continue
        branch = str(row.get("branch") or "")
        sha = str(row.get("sha") or "")
        if not branch or not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("terminal lifecycle evidence has an invalid branch or SHA")
        if branch in result and result[branch] != sha:
            raise ValueError(f"conflicting lifecycle evidence for {branch}")
        result[branch] = sha
    return result


def recovery_ledger_sidecar(path: Path) -> Path:
    return path.with_suffix(path.suffix + ".sha256")


def load_recovery_capture_rows(path: Path) -> dict[str, dict[str, Any]]:
    path = path.expanduser().resolve()
    sidecar = recovery_ledger_sidecar(path)
    if not path.is_file() or not sidecar.is_file():
        raise ValueError("recovery ledger or checksum sidecar is missing")
    digest = sha256(path)
    expected = sidecar.read_text(encoding="utf-8").strip().split()
    if not expected or expected[0] != digest:
        raise ValueError("recovery ledger checksum mismatch")
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = payload.get("rows") if isinstance(payload, dict) else None
    if not isinstance(payload, dict) or payload.get("schema_version") != "1.0" or not isinstance(rows, list) or not rows:
        raise ValueError("recovery ledger structure is invalid")
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("recovery ledger row is invalid")
        branch = str(row.get("branch") or "")
        commit = str(row.get("sha") or "")
        classification = str(row.get("classification") or "")
        row_id = str(row.get("row_id") or "")
        if (
            not branch
            or not re.fullmatch(r"[0-9a-f]{40}", commit)
            or classification not in RECOVERY_CAPTURE_CLASSES
            or not row_id
            or not str(row.get("reason") or "")
            or not str(row.get("recommended_next_action") or "")
            or not isinstance(row.get("restore"), dict)
        ):
            raise ValueError("recovery ledger row is incomplete")
        if branch in result:
            raise ValueError(f"duplicate recovery ledger branch: {branch}")
        result[branch] = {
            "ledger_path": str(path),
            "ledger_sha256": digest,
            "ledger_id": str(payload.get("ledger_id") or ""),
            "row_id": row_id,
            "branch": branch,
            "sha": commit,
            "classification": classification,
            "reason": str(row["reason"]),
            "recommended_next_action": str(row["recommended_next_action"]),
        }
    return result


def dirty_entries(path: Path) -> list[str]:
    proc = git(path, "status", "--porcelain")
    if proc.returncode != 0:
        return ["status_unavailable"]
    return [line for line in proc.stdout.splitlines() if line.strip()]


def build_plan(
    project_root: Path,
    *,
    branches: set[str] | None = None,
    open_pr_heads: set[str] | None = None,
    protected_branches: set[str] | None = None,
    base_refs: list[str] | None = None,
    min_age_days: int = 14,
    now: datetime | None = None,
    remote: str = "origin",
    include_selected_without_worktree: bool = False,
    allow_unintegrated_archive_namespace: bool = False,
    lifecycle_terminal_heads: dict[str, str] | None = None,
    recovery_capture_rows: dict[str, dict[str, Any]] | None = None,
    protection_mode: str = "default",
) -> dict[str, Any]:
    if protection_mode not in PROTECTION_MODES:
        raise ValueError(f"unsupported protection mode: {protection_mode}")
    project_root = project_root.resolve()
    selected = branches or set()
    open_heads = open_pr_heads or set()
    base_protected = CANONICAL_PROTECTED_BRANCHES if protection_mode == "canonical-only" else PROTECTED_BRANCHES
    protected = base_protected | (protected_branches or set())
    bases = base_refs or ["origin/develop", "origin/release/main"]
    current_time = now or now_utc()
    rows: list[dict[str, Any]] = []
    seen_branches: set[str] = set()

    try:
        inventory = list_worktrees(project_root)
    except RuntimeError as exc:
        return {
            "schema_version": "1.0",
            "generated_at": current_time.isoformat().replace("+00:00", "Z"),
            "min_age_days": max(0, min_age_days),
            "base_refs": bases,
            "protection_mode": protection_mode,
            "selected_branches": sorted(selected),
            "candidate_count": 0,
            "eligible_count": 0,
            "blocked_count": 0,
            "inventory_error": str(exc),
            "worktrees": [],
        }

    for item in inventory:
        path = Path(str(item["path"])).resolve()
        branch = str(item.get("branch") or "")
        if selected and branch not in selected:
            continue
        if branch:
            seen_branches.add(branch)
        reasons: list[str] = []
        source = branch_source(project_root, branch, remote) if branch else {}
        source_ref = str(source.get("source_ref") or "")
        sha = str(source.get("sha") or "")
        age_days = branch_age_days(project_root, source_ref, current_time) if source_ref else 0
        integrated_into = merged_base(project_root, source_ref, bases) if source_ref else None
        dirty = dirty_entries(path) if path.exists() and path != project_root else []

        if not branch:
            reasons.append("detached_or_bare")
        if path == project_root:
            reasons.append("current_worktree")
        if branch in protected:
            reasons.append("protected_branch")
        if branch in open_heads:
            reasons.append("open_pr")
        if not sha:
            reasons.append("branch_tip_unavailable")
        if source.get("remote_sha") and source.get("remote_sha") != sha:
            reasons.append("remote_tip_mismatch")
        archive_namespace_migration = bool(
            allow_unintegrated_archive_namespace and branch.startswith("archive/")
        )
        lifecycle_terminal = bool(sha and (lifecycle_terminal_heads or {}).get(branch) == sha)
        recovery_capture = (recovery_capture_rows or {}).get(branch)
        recovery_capture_verified = bool(sha and recovery_capture and recovery_capture.get("sha") == sha)
        if recovery_capture and not recovery_capture_verified:
            reasons.append("recovery_capture_sha_mismatch")
        if not integrated_into and not archive_namespace_migration and not lifecycle_terminal and not recovery_capture_verified:
            reasons.append("tip_not_integrated")
        if age_days < max(0, min_age_days):
            reasons.append("inside_retention_window")
        if dirty:
            reasons.append("dirty_worktree")

        rows.append(
            {
                "path": str(path),
                "branch": branch or None,
                "sha": sha or None,
                "source_ref": source_ref or None,
                "local_branch_present": bool(source.get("local_branch_present")),
                "remote_branch_present": bool(source.get("remote_branch_present")),
                "age_days": age_days,
                "integrated_into": integrated_into,
                "archive_namespace_migration": archive_namespace_migration,
                "lifecycle_terminal_verified": lifecycle_terminal,
                "recovery_capture_verified": recovery_capture_verified,
                "recovery_ledger": recovery_capture if recovery_capture_verified else None,
                "dirty_count": len(dirty),
                "dirty_sample": dirty[:5],
                "eligible": not reasons,
                "reasons": reasons or ["eligible"],
            }
        )

    if selected and include_selected_without_worktree:
        for branch in sorted(selected - seen_branches):
            source = branch_source(project_root, branch, remote)
            source_ref = str(source.get("source_ref") or "")
            sha = str(source.get("sha") or "")
            reasons: list[str] = []
            age_days = branch_age_days(project_root, source_ref, current_time) if source_ref else 0
            integrated_into = merged_base(project_root, source_ref, bases) if source_ref else None
            if branch in protected:
                reasons.append("protected_branch")
            if branch in open_heads:
                reasons.append("open_pr")
            if not sha:
                reasons.append("branch_tip_unavailable")
            if source.get("remote_sha") and source.get("local_branch_present") and source.get("remote_sha") != sha:
                reasons.append("remote_tip_mismatch")
            archive_namespace_migration = bool(
                allow_unintegrated_archive_namespace and branch.startswith("archive/")
            )
            lifecycle_terminal = bool(sha and (lifecycle_terminal_heads or {}).get(branch) == sha)
            recovery_capture = (recovery_capture_rows or {}).get(branch)
            recovery_capture_verified = bool(sha and recovery_capture and recovery_capture.get("sha") == sha)
            if recovery_capture and not recovery_capture_verified:
                reasons.append("recovery_capture_sha_mismatch")
            if not integrated_into and not archive_namespace_migration and not lifecycle_terminal and not recovery_capture_verified:
                reasons.append("tip_not_integrated")
            if age_days < max(0, min_age_days):
                reasons.append("inside_retention_window")
            rows.append(
                {
                    "path": None,
                    "branch": branch,
                    "sha": sha or None,
                    "source_ref": source_ref or None,
                    "local_branch_present": bool(source.get("local_branch_present")),
                    "remote_branch_present": bool(source.get("remote_branch_present")),
                    "age_days": age_days,
                    "integrated_into": integrated_into,
                    "archive_namespace_migration": archive_namespace_migration,
                    "lifecycle_terminal_verified": lifecycle_terminal,
                    "recovery_capture_verified": recovery_capture_verified,
                    "recovery_ledger": recovery_capture if recovery_capture_verified else None,
                    "dirty_count": 0,
                    "dirty_sample": [],
                    "eligible": not reasons,
                    "reasons": reasons or ["eligible"],
                }
            )

    return {
        "schema_version": "1.0",
        "generated_at": current_time.isoformat().replace("+00:00", "Z"),
        "min_age_days": max(0, min_age_days),
        "base_refs": bases,
        "protection_mode": protection_mode,
        "selected_branches": sorted(selected),
        "candidate_count": len(rows),
        "eligible_count": sum(1 for row in rows if row["eligible"]),
        "blocked_count": sum(1 for row in rows if not row["eligible"]),
        "worktrees": rows,
    }


def repository_name(project_root: Path, remote: str) -> str:
    proc = git(project_root, "remote", "get-url", remote)
    value = proc.stdout.strip().rstrip("/")
    name = value.rsplit("/", 1)[-1].removesuffix(".git")
    return safe_name(name or project_root.name)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_remote_target(host: str, root: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", host):
        raise ValueError("unsafe archive SSH host")
    if not root.startswith("/") or not re.fullmatch(r"[A-Za-z0-9._/-]+", root):
        raise ValueError("unsafe remote archive root")


def archive_relative_dir(archive_root: str, repo: str, date: str) -> str:
    root_name = archive_root.rstrip("/\\").replace("\\", "/").rsplit("/", 1)[-1].lower()
    prefix = "" if root_name == "git-branches" else "git-branches/"
    return f"{prefix}{repo}/{date}"


def archive_bundle(
    project_root: Path,
    row: dict[str, Any],
    *,
    archive_root: str,
    archive_ssh_host: str | None,
    remote: str,
) -> dict[str, Any]:
    branch = str(row["branch"])
    expected_sha = str(row["sha"])
    date = now_utc().strftime("%Y-%m-%d")
    repo = repository_name(project_root, remote)
    stem = f"{safe_name(branch)}-{expected_sha[:12]}"
    relative_dir = archive_relative_dir(archive_root, repo, date)

    with tempfile.TemporaryDirectory(prefix="branch-retirement-") as tmp:
        tmp_path = Path(tmp)
        bundle = tmp_path / f"{stem}.bundle"
        manifest = tmp_path / f"{stem}.json"
        source_ref = str(row.get("source_ref") or f"refs/heads/{branch}")
        created = git(project_root, "bundle", "create", str(bundle), source_ref)
        if created.returncode != 0:
            raise RuntimeError(created.stderr.strip() or "git bundle create failed")
        heads = git(project_root, "bundle", "list-heads", str(bundle))
        if heads.returncode != 0 or expected_sha not in heads.stdout:
            raise RuntimeError("local bundle does not contain the expected branch tip")
        checksum = sha256(bundle)
        payload = {
            "schema_version": "1.0",
            "archived_at": now_utc().isoformat().replace("+00:00", "Z"),
            "repository": repo,
            "branch": branch,
            "sha": expected_sha,
            "integrated_into": row.get("integrated_into"),
            "bundle": bundle.name,
            "bundle_sha256": checksum,
            "source_worktree": row.get("path"),
            "recovery_ledger": row.get("recovery_ledger"),
        }
        manifest.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        if archive_ssh_host:
            validate_remote_target(archive_ssh_host, archive_root)
            target_dir = f"{archive_root.rstrip('/')}/{relative_dir}"
            mkdir = run(["ssh", archive_ssh_host, f"mkdir -p -- {shlex.quote(target_dir)}"])
            if mkdir.returncode != 0:
                raise RuntimeError(mkdir.stderr.strip() or "remote archive directory creation failed")
            copied = run(["scp", str(bundle), str(manifest), f"{archive_ssh_host}:{target_dir}/"])
            if copied.returncode != 0:
                raise RuntimeError(copied.stderr.strip() or "remote archive upload failed")
            remote_bundle = f"{target_dir}/{bundle.name}"
            verify = run(
                [
                    "ssh",
                    archive_ssh_host,
                    f"sha256sum {shlex.quote(remote_bundle)}; git bundle list-heads {shlex.quote(remote_bundle)}",
                ]
            )
            if verify.returncode != 0 or checksum not in verify.stdout or expected_sha not in verify.stdout:
                raise RuntimeError("remote archive verification failed")
            location = f"ssh://{archive_ssh_host}{target_dir}/{bundle.name}"
        else:
            target_dir_path = Path(archive_root).expanduser().resolve() / relative_dir
            target_dir_path.mkdir(parents=True, exist_ok=True)
            target_bundle = target_dir_path / bundle.name
            target_manifest = target_dir_path / manifest.name
            target_bundle.write_bytes(bundle.read_bytes())
            target_manifest.write_bytes(manifest.read_bytes())
            verify = git(project_root, "bundle", "list-heads", str(target_bundle))
            if sha256(target_bundle) != checksum or expected_sha not in verify.stdout:
                raise RuntimeError("local archive verification failed")
            location = str(target_bundle)
        return {**payload, "location": location, "verified": True}


def apply_plan(
    project_root: Path,
    plan: dict[str, Any],
    *,
    archive_root: str,
    archive_ssh_host: str | None = None,
    remote: str = "origin",
    max_count: int = 10,
    delete_local_branch: bool = True,
    delete_remote_branch: bool = False,
) -> list[dict[str, Any]]:
    if not plan.get("selected_branches"):
        raise ValueError("apply requires explicit branch selection")
    eligible = [item for item in plan.get("worktrees", []) if item.get("eligible")]
    if not eligible:
        raise ValueError("no selected worktree passed retirement gates")
    results: list[dict[str, Any]] = []
    for row in eligible[: max(0, max_count)]:
        branch = str(row["branch"])
        expected_sha = str(row["sha"])
        source_ref = str(row.get("source_ref") or f"refs/heads/{branch}")
        result: dict[str, Any] = {"branch": branch, "sha": expected_sha}
        try:
            if ref_sha(project_root, source_ref) != expected_sha:
                raise RuntimeError("branch tip changed after planning")
            result["archive"] = archive_bundle(
                project_root,
                row,
                archive_root=archive_root,
                archive_ssh_host=archive_ssh_host,
                remote=remote,
            )
            recovery = row.get("recovery_ledger")
            if recovery:
                current = load_recovery_capture_rows(Path(str(recovery.get("ledger_path") or ""))).get(branch)
                if (
                    not current
                    or current.get("sha") != expected_sha
                    or current.get("row_id") != recovery.get("row_id")
                    or result["archive"].get("recovery_ledger") != recovery
                ):
                    raise RuntimeError("recovery ledger verification failed after archive")
            worktree_path = row.get("path")
            if worktree_path:
                removed = git(project_root, "worktree", "remove", str(worktree_path))
                if removed.returncode != 0:
                    raise RuntimeError(removed.stderr.strip() or "worktree removal failed")
                result["worktree_removed"] = True
            else:
                result["worktree_removed"] = False
            if delete_local_branch and row.get("local_branch_present"):
                deleted = git(project_root, "update-ref", "-d", f"refs/heads/{branch}", expected_sha)
                if deleted.returncode != 0:
                    raise RuntimeError(deleted.stderr.strip() or "local branch deletion failed")
                result["local_branch_deleted"] = True
            else:
                result["local_branch_deleted"] = False
            if delete_remote_branch and row.get("remote_branch_present"):
                lease = f"--force-with-lease=refs/heads/{branch}:{expected_sha}"
                deleted = git(project_root, "push", lease, remote, "--delete", branch)
                if deleted.returncode != 0:
                    raise RuntimeError(deleted.stderr.strip() or "remote branch deletion failed")
                result["remote_branch_deleted"] = True
            else:
                result["remote_branch_deleted"] = False
            result["status"] = "retired"
        except (OSError, RuntimeError, ValueError) as exc:
            result.update({"status": "blocked", "error": str(exc)})
        results.append(result)
    return results


def open_pr_heads(project_root: Path) -> set[str]:
    proc = run(["gh", "pr", "list", "--state", "open", "--limit", "1000", "--json", "headRefName"], project_root)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "open PR check failed")
    rows = json.loads(proc.stdout or "[]")
    return {str(row["headRefName"]) for row in rows if isinstance(row, dict) and row.get("headRefName")}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--branch", action="append", default=[])
    parser.add_argument("--base-ref", action="append", default=[])
    parser.add_argument("--min-age-days", type=int, default=14)
    parser.add_argument("--archive-root")
    parser.add_argument("--archive-ssh-host")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--protection-mode", choices=PROTECTION_MODES, default="default")
    parser.add_argument("--max-count", type=int, default=10)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--delete-local-branch", action="store_true")
    parser.add_argument("--delete-remote-branch", action="store_true")
    parser.add_argument(
        "--allow-unintegrated-archive-namespace",
        action="store_true",
        help="Allow explicit archive/* refs to migrate off-repository without base ancestry.",
    )
    parser.add_argument(
        "--lifecycle-evidence",
        help="Exact branch/SHA terminal classifications emitted by branch_lifecycle_scanner.py.",
    )
    parser.add_argument(
        "--recovery-ledger",
        help="Checksummed exact branch/SHA recovery rows that authorize archive-first retirement.",
    )
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(args.project_root).resolve()
    try:
        plan = build_plan(
            root,
            branches=set(args.branch or []),
            open_pr_heads=open_pr_heads(root),
            base_refs=args.base_ref or ["origin/develop", "origin/release/main"],
            min_age_days=max(0, args.min_age_days),
            remote=args.remote,
            include_selected_without_worktree=bool(args.branch),
            allow_unintegrated_archive_namespace=bool(args.allow_unintegrated_archive_namespace),
            protection_mode=args.protection_mode,
            lifecycle_terminal_heads=(
                load_lifecycle_terminal_heads(Path(args.lifecycle_evidence).resolve())
                if args.lifecycle_evidence else None
            ),
            recovery_capture_rows=(
                load_recovery_capture_rows(Path(args.recovery_ledger))
                if args.recovery_ledger else None
            ),
        )
        results: list[dict[str, Any]] = []
        if args.apply:
            if not args.archive_root:
                raise ValueError("--archive-root is required with --apply")
            results = apply_plan(
                root,
                plan,
                archive_root=args.archive_root,
                archive_ssh_host=args.archive_ssh_host,
                remote=args.remote,
                max_count=max(0, args.max_count),
                delete_local_branch=bool(args.delete_local_branch),
                delete_remote_branch=bool(args.delete_remote_branch),
            )
        payload = {
            "schema_version": "1.0",
            "dry_run": not args.apply,
            "plan": plan,
            "results": results,
            "ok": all(row.get("status") == "retired" for row in results),
        }
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        payload = {"schema_version": "1.0", "dry_run": not args.apply, "ok": False, "error": str(exc)}
    print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else json.dumps(payload, ensure_ascii=False))
    return 0 if payload.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main())
