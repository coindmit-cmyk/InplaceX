#!/usr/bin/env python3
"""Dry-run-first activation and SSD/HDD smoke verification for Second Brain."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from project_memory_engine import ProjectMemoryStore, StorageConfig, ValidationError


UNSAFE_ACTIVE_FILESYSTEMS = {"exfat", "vfat", "fat", "fat32", "fuseblk"}
VIRTUAL_MOUNT_FILESYSTEMS = {"autofs"}


@dataclass(frozen=True)
class StorageProbe:
    requested_path: str
    existing_ancestor: str
    resolved_ancestor: str
    source: str | None
    filesystem: str | None
    mount_target: str | None
    device_id: int
    free_bytes: int
    total_bytes: int
    free_percent: float
    writable: bool


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _nearest_existing(path: Path) -> Path:
    current = path.resolve(strict=False)
    while not current.exists():
        parent = current.parent
        if parent == current:
            raise ValidationError(f"no existing ancestor for path: {path}")
        current = parent
    return current


def _findmnt(path: Path) -> tuple[str | None, str | None, str | None]:
    executable = shutil.which("findmnt")
    if not executable:
        return None, None, None
    completed = subprocess.run(
        [executable, "--json", "--target", str(path), "--output", "SOURCE,FSTYPE,TARGET"],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        return None, None, None
    try:
        filesystems = json.loads(completed.stdout).get("filesystems") or []
    except json.JSONDecodeError:
        return None, None, None
    if not filesystems:
        return None, None, None
    # systemd automounts can report an outer ``autofs`` row before the real
    # backing filesystem for the same target. Prefer the deepest concrete row
    # so storage policy and evidence describe the actual disk (for example,
    # ``/dev/sdb1`` + ``exfat``) instead of the automount trigger.
    concrete = [
        item
        for item in filesystems
        if str(item.get("fstype") or "").lower() not in VIRTUAL_MOUNT_FILESYSTEMS
    ]
    item = (concrete or filesystems)[-1]
    return item.get("source"), item.get("fstype"), item.get("target")


def probe_storage(path: Path) -> StorageProbe:
    ancestor = _nearest_existing(path)
    resolved = ancestor.resolve()
    stat = resolved.stat()
    usage = shutil.disk_usage(resolved)
    source, filesystem, target = _findmnt(resolved)
    free_percent = round((usage.free / usage.total) * 100, 2) if usage.total else 0.0
    return StorageProbe(
        requested_path=str(path.resolve(strict=False)),
        existing_ancestor=str(ancestor),
        resolved_ancestor=str(resolved),
        source=source,
        filesystem=filesystem,
        mount_target=target,
        device_id=int(stat.st_dev),
        free_bytes=int(usage.free),
        total_bytes=int(usage.total),
        free_percent=free_percent,
        writable=os.access(resolved, os.W_OK),
    )


def _allowed_local_path(path: Path, project_root: Path) -> bool:
    resolved = path.resolve(strict=False)
    root = project_root.resolve()
    if not _is_relative_to(resolved, root):
        return True
    return _is_relative_to(resolved, root / "runtime")


def build_plan(
    *,
    project_root: Path,
    config_path: Path,
    active_db: Path,
    archive_root: Path,
    expected_archive_mount: Path | None = None,
    minimum_active_free_percent: float = 10.0,
    minimum_archive_free_percent: float = 15.0,
    require_separate_devices: bool = True,
) -> dict[str, Any]:
    project_root = project_root.resolve()
    config_path = config_path.resolve(strict=False)
    active_db = active_db.resolve(strict=False)
    archive_root = archive_root.resolve(strict=False)
    errors: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []

    if not (project_root / "scripts" / "agent_control" / "project_memory_engine.py").is_file():
        errors.append({"code": "memory_engine_missing", "message": "project_memory_engine.py is missing"})
    if not config_path.name.endswith(".local.json"):
        errors.append({"code": "config_not_local", "message": "config filename must end with .local.json"})
    if not _allowed_local_path(config_path, project_root):
        errors.append(
            {
                "code": "config_inside_tracked_tree",
                "message": "config inside project_root must be under ignored runtime/",
            }
        )
    if not _allowed_local_path(active_db, project_root):
        errors.append(
            {
                "code": "database_inside_tracked_tree",
                "message": "active database inside project_root must be under ignored runtime/",
            }
        )
    if active_db == archive_root or _is_relative_to(active_db, archive_root):
        errors.append(
            {
                "code": "live_database_on_archive",
                "message": "active database must not be stored under the bulk archive root",
            }
        )
    if _is_relative_to(archive_root, project_root):
        errors.append(
            {
                "code": "archive_inside_repository",
                "message": "raw customer archive must not be stored inside the Git project",
            }
        )

    active_probe = probe_storage(active_db.parent)
    archive_probe = probe_storage(archive_root)
    active_fs = (active_probe.filesystem or "").lower()
    if active_fs in UNSAFE_ACTIVE_FILESYSTEMS:
        errors.append(
            {
                "code": "unsafe_active_filesystem",
                "message": f"active database filesystem {active_fs!r} is not allowed",
            }
        )
    if not active_probe.writable:
        errors.append({"code": "active_storage_not_writable", "message": "active storage ancestor is not writable"})
    if not archive_probe.writable:
        errors.append({"code": "archive_storage_not_writable", "message": "archive storage ancestor is not writable"})
    if active_probe.free_percent < minimum_active_free_percent:
        errors.append(
            {
                "code": "active_storage_low_space",
                "message": f"active storage free space {active_probe.free_percent}% is below {minimum_active_free_percent}%",
            }
        )
    if archive_probe.free_percent < minimum_archive_free_percent:
        errors.append(
            {
                "code": "archive_storage_low_space",
                "message": f"archive storage free space {archive_probe.free_percent}% is below {minimum_archive_free_percent}%",
            }
        )
    if require_separate_devices and active_probe.device_id == archive_probe.device_id:
        errors.append(
            {
                "code": "storage_devices_not_separate",
                "message": "active database and bulk archive resolve to the same device",
            }
        )
    if expected_archive_mount:
        expected = expected_archive_mount.resolve(strict=False)
        resolved_archive = Path(archive_probe.resolved_ancestor)
        if not _is_relative_to(resolved_archive, expected):
            errors.append(
                {
                    "code": "archive_mount_mismatch",
                    "message": f"archive resolves outside expected mount {expected}",
                }
            )
    if archive_probe.filesystem and archive_probe.filesystem.lower() in UNSAFE_ACTIVE_FILESYSTEMS:
        warnings.append(
            {
                "code": "archive_filesystem_not_for_live_db",
                "message": f"archive filesystem {archive_probe.filesystem!r} is allowed only for bulk blobs, not SQLite",
            }
        )

    return {
        "schema_version": 1,
        "mode": "second_brain_activation",
        "ok": not errors,
        "apply": False,
        "project_root": str(project_root),
        "config_path": str(config_path),
        "active_db": str(active_db),
        "archive_root": str(archive_root),
        "expected_archive_mount": str(expected_archive_mount.resolve(strict=False)) if expected_archive_mount else None,
        "storage": {
            "active": asdict(active_probe),
            "archive": asdict(archive_probe),
            "separate_devices": active_probe.device_id != archive_probe.device_id,
        },
        "policy": {
            "minimum_active_free_percent": minimum_active_free_percent,
            "minimum_archive_free_percent": minimum_archive_free_percent,
            "require_separate_devices": require_separate_devices,
            "recurring_maintenance_enabled": False,
        },
        "planned_mutations": [
            f"create config {config_path} with mode 0600",
            f"create active SQLite database {active_db}",
            f"create bulk archive root {archive_root}",
        ],
        "errors": errors,
        "warnings": warnings,
    }


def _write_local_config(
    *,
    config_path: Path,
    active_db: Path,
    archive_root: Path,
) -> None:
    config_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": 1,
        "storage": {
            "active_db": str(active_db),
            "bulk_archive_root": str(archive_root),
        },
        "pattern_library": {
            "tenant_id": "aistudio-internal",
            "project_id": "pattern-library",
        },
        "policy": {"max_results": 50},
    }
    temporary = config_path.with_name(f".{config_path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(temporary, config_path)
    os.chmod(config_path, 0o600)


def run_smoke(
    store: ProjectMemoryStore,
    *,
    require_separate_devices: bool = True,
) -> dict[str, Any]:
    marker = f"second-brain-storage-smoke-{uuid.uuid4().hex}"
    result = store.intake(
        {
            "tenant_id": "aistudio-smoke",
            "project_id": "activation",
            "memory_type": "source_note",
            "access_level": "owner_only",
            "summary": f"Synthetic activation marker {marker}",
            "facts": ["This record exists only to verify SSD/HDD placement and lifecycle."],
            "tags": ["activation", "storage-smoke"],
            "source_refs": [f"smoke://{marker}"],
            "evidence_refs": [],
            "raw_text": f"Synthetic raw payload for {marker}; contains no customer data.",
            "consent_state": "not_required",
            "confidence": 1.0,
            "freshness_reason": "synthetic activation smoke",
            "idempotency_key": marker,
        },
        actor_id="activation-smoke",
    )
    item = result["item"]
    memory_id = item["memory_id"]
    blob_ref = item["raw_blob_ref"]
    blob_path = store.config.bulk_archive_root / blob_ref
    try:
        store.review(
            tenant_id="aistudio-smoke",
            project_id="activation",
            memory_id=memory_id,
            decision="approve",
            reviewer="activation-owner",
            reason="synthetic activation smoke",
        )
        retrieved = store.retrieve(
            tenant_id="aistudio-smoke",
            project_id="activation",
            query=marker,
            principal_id="activation-owner",
            principal_role="owner",
        )
        if [entry["memory_id"] for entry in retrieved["items"]] != [memory_id]:
            raise ValidationError("smoke retrieval did not return the synthetic memory item")
        if not store.config.active_db.is_file():
            raise ValidationError("active SQLite database was not created")
        if not blob_path.is_file():
            raise ValidationError("raw archive blob was not created")
        db_device = store.config.active_db.stat().st_dev
        blob_device = blob_path.stat().st_dev
        if require_separate_devices and db_device == blob_device:
            raise ValidationError("smoke database and raw blob are on the same device")
        store.forget(
            tenant_id="aistudio-smoke",
            project_id="activation",
            memory_id=memory_id,
            requester="activation-owner",
            reason="synthetic activation smoke cleanup",
        )
        after = store.retrieve(
            tenant_id="aistudio-smoke",
            project_id="activation",
            query=marker,
            principal_id="activation-owner",
            principal_role="owner",
        )
        if after["items"]:
            raise ValidationError("forgotten smoke item is still retrievable")
        if blob_path.exists():
            raise ValidationError("forgotten smoke raw blob still exists")
        events_after_forget = store.audit_events(tenant_id="aistudio-smoke", project_id="activation")
        return {
            "ok": True,
            "memory_id": memory_id,
            "marker": marker,
            "retrieved_with_source_refs": bool(retrieved["items"][0]["source_refs"]),
            "freshness_state": retrieved["items"][0]["freshness"]["state"],
            "active_db_device_id": int(db_device),
            "raw_blob_device_id": int(blob_device),
            "separate_devices": db_device != blob_device,
            "audit_event_types": sorted({event["event_type"] for event in events_after_forget}),
            "forgotten_not_retrievable": True,
            "raw_blob_removed": True,
        }
    except Exception:
        try:
            store.forget(
                tenant_id="aistudio-smoke",
                project_id="activation",
                memory_id=memory_id,
                requester="activation-owner",
                reason="synthetic activation smoke failure cleanup",
            )
        except Exception:
            pass
        raise


def activate(
    *,
    project_root: Path,
    config_path: Path,
    active_db: Path,
    archive_root: Path,
    expected_archive_mount: Path | None = None,
    minimum_active_free_percent: float = 10.0,
    minimum_archive_free_percent: float = 15.0,
    require_separate_devices: bool = True,
    apply: bool = False,
    smoke: bool = False,
) -> dict[str, Any]:
    plan = build_plan(
        project_root=project_root,
        config_path=config_path,
        active_db=active_db,
        archive_root=archive_root,
        expected_archive_mount=expected_archive_mount,
        minimum_active_free_percent=minimum_active_free_percent,
        minimum_archive_free_percent=minimum_archive_free_percent,
        require_separate_devices=require_separate_devices,
    )
    if smoke and not apply:
        plan["ok"] = False
        plan["errors"].append({"code": "smoke_requires_apply", "message": "--smoke requires --apply"})
    if not apply or not plan["ok"]:
        return plan

    config_path = config_path.resolve(strict=False)
    active_db = active_db.resolve(strict=False)
    archive_root = archive_root.resolve(strict=False)
    active_db.parent.mkdir(parents=True, exist_ok=True)
    archive_root.mkdir(parents=True, exist_ok=True)
    _write_local_config(config_path=config_path, active_db=active_db, archive_root=archive_root)
    store = ProjectMemoryStore(StorageConfig(active_db=active_db, bulk_archive_root=archive_root))
    initialized = store.initialize()
    if active_db.exists():
        os.chmod(active_db, 0o600)
    result = dict(plan)
    result.update(
        {
            "ok": True,
            "apply": True,
            "mutated_runtime": True,
            "initialized": initialized,
            "smoke": run_smoke(store, require_separate_devices=require_separate_devices) if smoke else None,
        }
    )
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--config", required=True)
    parser.add_argument("--active-db", required=True)
    parser.add_argument("--archive-root", required=True)
    parser.add_argument("--expected-archive-mount")
    parser.add_argument("--minimum-active-free-percent", type=float, default=10.0)
    parser.add_argument("--minimum-archive-free-percent", type=float, default=15.0)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        report = activate(
            project_root=Path(args.project_root),
            config_path=Path(args.config),
            active_db=Path(args.active_db),
            archive_root=Path(args.archive_root),
            expected_archive_mount=Path(args.expected_archive_mount) if args.expected_archive_mount else None,
            minimum_active_free_percent=args.minimum_active_free_percent,
            minimum_archive_free_percent=args.minimum_archive_free_percent,
            require_separate_devices=True,
            apply=bool(args.apply),
            smoke=bool(args.smoke),
        )
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if report["ok"] else 2
    except (OSError, ValidationError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
