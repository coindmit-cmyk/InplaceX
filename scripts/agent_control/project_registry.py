#!/usr/bin/env python3
"""Shared AiStudio project registry normalization.

Registry v2 adds workspace containers, branch roles and navigation paths. This
module keeps old v1 registries readable and returns the legacy fields existing
controller/dashboard code expects.
"""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any


DEFAULT_TASK_QUEUE = "AiStudio/Task_manager/task_queue.json"
DEFAULT_AGENT_LOCKS = "AiStudio/Task_manager/agent_locks.json"
DEFAULT_OWNER_DIRECTIVES = "AiStudio/Task_manager/owner_directives.json"
DEFAULT_PROJECT_INDEX = "PROJECT_INDEX.md"
DEFAULT_DOCUMENTATION_MANIFEST = "DOCUMENTATION_MANIFEST.json"
DEFAULT_VERSION_FILE = "PROJECT_VERSION.json"
DEFAULT_START_HERE = ".agent/START_HERE.md"
DEFAULT_AGENT_VERSION = ".agent/agent_version.json"
DEFAULT_CHANGELOG = "CHANGELOG.md"
DEFAULT_FLEET_TOPOLOGY_VERSION = "1.0"


def default_fleet_topology() -> dict[str, Any]:
    """Return the safe shape used when a legacy Registry has no topology."""
    return {
        "schema_version": DEFAULT_FLEET_TOPOLOGY_VERSION,
        "canonical_writer_host": "",
        "vps_writer_failover": False,
        "hosts": [],
    }


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def clean_string(value: Any) -> str:
    return str(value or "").strip()


def normalize_fleet_topology(raw: Any) -> dict[str, Any]:
    """Normalize the optional Registry topology without introducing authority.

    Older project Registries do not carry host information.  They remain
    readable and produce an empty, non-failover topology until a local-only
    topology is explicitly configured.
    """
    if not isinstance(raw, dict):
        return default_fleet_topology()

    topology = copy.deepcopy(raw)
    topology["schema_version"] = clean_string(topology.get("schema_version")) or DEFAULT_FLEET_TOPOLOGY_VERSION
    canonical_writer = topology.get("canonical_writer_host")
    if not canonical_writer and isinstance(topology.get("canonical_writer"), dict):
        canonical_writer = topology["canonical_writer"].get("host_id")
    topology["canonical_writer_host"] = clean_string(canonical_writer)
    topology["vps_writer_failover"] = bool(topology.get("vps_writer_failover", False))

    raw_hosts = topology.get("hosts")
    hosts: list[dict[str, Any]] = []
    if isinstance(raw_hosts, list):
        for raw_host in raw_hosts:
            if not isinstance(raw_host, dict):
                continue
            host = copy.deepcopy(raw_host)
            host["host_id"] = clean_string(host.get("host_id") or host.get("machine_id"))
            host["role"] = clean_string(host.get("role") or host.get("host_role")) or "observer"
            host["can_write"] = bool(host.get("can_write", host["role"] == "writer"))
            services = host.get("services")
            host["services"] = [clean_string(value) for value in services if clean_string(value)] if isinstance(services, list) else []
            hosts.append(host)
    topology["hosts"] = hosts
    return topology


def fleet_topology_warnings(topology: dict[str, Any]) -> list[str]:
    """Return fail-closed topology diagnostics for callers and dashboards."""
    warnings: list[str] = []
    canonical = clean_string(topology.get("canonical_writer_host"))
    hosts = topology.get("hosts") if isinstance(topology.get("hosts"), list) else []
    by_id: dict[str, dict[str, Any]] = {}
    for host in hosts:
        if not isinstance(host, dict):
            warnings.append("fleet topology contains a non-object host")
            continue
        host_id = clean_string(host.get("host_id"))
        if not host_id:
            warnings.append("fleet topology host is missing host_id")
            continue
        if host_id in by_id:
            warnings.append(f"fleet topology contains duplicate host_id {host_id!r}")
        by_id[host_id] = host

    if not canonical:
        warnings.append("fleet topology is not configured with canonical_writer_host")
    elif canonical not in by_id:
        warnings.append(f"canonical writer host {canonical!r} is not registered")
    else:
        writer = by_id[canonical]
        if writer.get("role") != "writer" or writer.get("can_write") is not True:
            warnings.append(f"canonical writer host {canonical!r} is not write-capable")

    writable = [host_id for host_id, host in by_id.items() if host.get("can_write") is True]
    if canonical and writable != [canonical] and set(writable) != {canonical}:
        warnings.append(f"fleet topology has unexpected writable hosts: {writable!r}")
    if topology.get("vps_writer_failover") is not False:
        warnings.append("vps_writer_failover must be false")
    return warnings


def normalize_registry_payload(registry: dict[str, Any]) -> dict[str, Any]:
    """Return a Registry copy with the canonical topology extension present."""
    normalized = copy.deepcopy(registry)
    service = normalized.get("service") if isinstance(normalized.get("service"), dict) else {}
    topology = normalized.get("fleet_topology")
    if not isinstance(topology, dict):
        topology = service.get("fleet_topology")
    normalized["fleet_topology"] = normalize_fleet_topology(topology)
    return normalized


def join_workspace(workspace_root: str, value: Any) -> str:
    text = clean_string(value)
    if not text:
        return ""
    path = Path(text)
    if path.is_absolute() or not workspace_root:
        return text
    return str(Path(workspace_root) / path)


def workspace_relative_or_absolute(workspace_root: str, value: str) -> str:
    if not workspace_root or not value:
        return value
    try:
        return str(Path(value).expanduser().resolve(strict=False).relative_to(Path(workspace_root).expanduser().resolve(strict=False)))
    except ValueError:
        return value


def path_key(path: str) -> str:
    return str(Path(path).expanduser().resolve(strict=False)).casefold()


def path_is_under(path: str, parent: str) -> bool:
    if not path or not parent:
        return True
    child = Path(path).expanduser().resolve(strict=False)
    root = Path(parent).expanduser().resolve(strict=False)
    try:
        child.relative_to(root)
        return True
    except ValueError:
        return False


def relative_or_default(raw: dict[str, Any], key: str, default: str) -> str:
    return clean_string(raw.get(key)) or default


def normalize_project(raw: dict[str, Any]) -> dict[str, Any]:
    project_id = clean_string(raw.get("project_id") or raw.get("id") or raw.get("name") or "unknown")
    workspace_root = clean_string(raw.get("workspace_root"))
    legacy_local_path = clean_string(raw.get("local_path"))
    legacy_automation_path = clean_string(raw.get("automation_path"))
    checkouts = raw.get("checkouts") if isinstance(raw.get("checkouts"), dict) else {}
    branches = raw.get("branches") if isinstance(raw.get("branches"), dict) else {}
    legacy_checkout_values = {value for value in (legacy_local_path, legacy_automation_path) if value}
    explicit_checkout_values = [clean_string(value) for value in checkouts.values()]
    has_v2_layout = bool(
        workspace_root
        or any(value and value not in legacy_checkout_values for value in explicit_checkout_values)
        or any(clean_string(value) for value in branches.values())
    )

    develop_checkout = join_workspace(workspace_root, checkouts.get("develop")) or (legacy_local_path if not has_v2_layout else "")
    codex_checkout = join_workspace(workspace_root, checkouts.get("codex")) or (legacy_automation_path if not has_v2_layout else "")
    release_checkout = join_workspace(workspace_root, checkouts.get("release"))

    local_path = legacy_local_path or develop_checkout or workspace_root
    automation_path = legacy_automation_path or codex_checkout
    base_ref = clean_string(raw.get("base_ref"))
    develop_branch = (
        clean_string(branches.get("develop"))
        or clean_string(raw.get("base_branch"))
        or (base_ref.removeprefix("origin/") if base_ref.startswith("origin/") else "")
        or ("develop" if has_v2_layout else "")
    )
    codex_branch = clean_string(branches.get("codex"))
    release_branch = clean_string(branches.get("release")) or clean_string(raw.get("release_branch"))
    task_manager_branch_role = clean_string(raw.get("task_manager_branch_role")) or "codex"
    branch_by_role = {"develop": develop_branch, "codex": codex_branch, "release": release_branch}
    state_branch = branch_by_role.get(task_manager_branch_role) or develop_branch
    code_base_ref = clean_string(raw.get("code_base_ref")) or (f"origin/{develop_branch}" if develop_branch else base_ref)
    state_ref = clean_string(raw.get("state_ref")) or (f"origin/{state_branch}" if state_branch else code_base_ref)
    push_ref = clean_string(raw.get("push_ref")) or state_branch

    project = dict(raw)
    git_store_path = join_workspace(workspace_root, raw.get("git_store_path") or raw.get("git_store"))

    project.update({
        "project_id": project_id,
        "name": clean_string(raw.get("name")) or project_id,
        "enabled": bool(raw.get("enabled", True)),
        "workspace_root": workspace_root,
        "git_store_path": git_store_path,
        "checkouts": {
            "develop": develop_checkout,
            "codex": codex_checkout,
            "release": release_checkout,
        },
        "branches": {
            "develop": develop_branch,
            "codex": codex_branch,
            "release": release_branch,
        },
        "local_path": local_path,
        "automation_path": automation_path,
        "github_repo": clean_string(raw.get("github_repo")),
        "base_branch": develop_branch,
        "base_ref": base_ref,
        "version_file": relative_or_default(raw, "version_file", DEFAULT_VERSION_FILE),
        "start_here_path": relative_or_default(raw, "start_here_path", DEFAULT_START_HERE),
        "agent_version_path": relative_or_default(raw, "agent_version_path", DEFAULT_AGENT_VERSION),
        "changelog_path": relative_or_default(raw, "changelog_path", DEFAULT_CHANGELOG),
        "project_index": relative_or_default(raw, "project_index", DEFAULT_PROJECT_INDEX),
        "documentation_manifest": relative_or_default(raw, "documentation_manifest", DEFAULT_DOCUMENTATION_MANIFEST),
        "code_base_ref": code_base_ref,
        "state_ref": state_ref,
        "push_ref": push_ref,
        "task_manager_branch_role": task_manager_branch_role,
        "automation_allowed": bool(raw.get("automation_allowed", raw.get("enabled", True))),
        "health_threshold": int(raw.get("health_threshold", 85) or 85),
        "quarantine_mode": clean_string(raw.get("quarantine_mode")) or "advisory",
        "task_queue_path": relative_or_default(raw, "task_queue_path", DEFAULT_TASK_QUEUE),
        "agent_locks_path": relative_or_default(raw, "agent_locks_path", DEFAULT_AGENT_LOCKS),
        "owner_directives_path": relative_or_default(raw, "owner_directives_path", DEFAULT_OWNER_DIRECTIVES),
    })
    return project


def project_path_warnings(project: dict[str, Any]) -> list[str]:
    pid = clean_string(project.get("project_id"))
    workspace_root = clean_string(project.get("workspace_root"))
    if not workspace_root:
        return []
    warnings: list[str] = []
    paths: list[tuple[str, str]] = [
        ("local_path", clean_string(project.get("local_path"))),
        ("automation_path", clean_string(project.get("automation_path"))),
        ("git_store_path", clean_string(project.get("git_store_path"))),
    ]
    checkouts = project.get("checkouts") if isinstance(project.get("checkouts"), dict) else {}
    for role in ("develop", "codex", "release"):
        paths.append((f"checkouts.{role}", clean_string(checkouts.get(role))))
    for key, value in paths:
        if value and not path_is_under(value, workspace_root):
            warnings.append(f"{pid}: {key} path {value!r} escapes workspace_root {workspace_root!r}")
    return warnings


def duplicate_path_warnings(projects: list[dict[str, Any]]) -> list[str]:
    warnings: list[str] = []
    seen: dict[str, str] = {}
    for project in projects:
        pid = clean_string(project.get("project_id"))
        for key in ("local_path", "automation_path", "workspace_root"):
            value = clean_string(project.get(key))
            if not value:
                continue
            normalized = path_key(value)
            previous = seen.get(normalized)
            if previous and previous != pid:
                warnings.append(f"duplicate registry path {value!r} used by {previous} and {pid}")
            else:
                seen[normalized] = pid
    return warnings


def infer_workspace_root(raw: dict[str, Any]) -> str:
    workspace_root = clean_string(raw.get("workspace_root"))
    if workspace_root:
        return workspace_root
    local_path = clean_string(raw.get("local_path"))
    automation_path = clean_string(raw.get("automation_path"))
    if local_path and automation_path:
        local = Path(local_path).expanduser()
        automation = Path(automation_path).expanduser()
        if local.parent == automation.parent and local.name != automation.name:
            return str(local.parent)
    for value in (local_path, automation_path):
        if not value:
            continue
        path = Path(value).expanduser()
        if path.name.lower() in {"develop", "codex", "release"}:
            return str(path.parent)
    return ""


def migrate_project_to_v2(raw: dict[str, Any]) -> dict[str, Any]:
    migrated = copy.deepcopy(raw)
    normalized = normalize_project(raw)
    workspace_root = infer_workspace_root(raw)
    if workspace_root:
        migrated.setdefault("workspace_root", workspace_root)
        migrated.setdefault("git_store_path", str(Path(workspace_root) / ".git-store"))

    checkouts = migrated.get("checkouts") if isinstance(migrated.get("checkouts"), dict) else {}
    checkouts = dict(checkouts)
    if workspace_root:
        if clean_string(raw.get("local_path")) and "develop" not in checkouts:
            checkouts["develop"] = workspace_relative_or_absolute(workspace_root, clean_string(raw.get("local_path")))
        else:
            checkouts.setdefault("develop", "develop")
        if clean_string(raw.get("automation_path")) and "codex" not in checkouts:
            checkouts["codex"] = workspace_relative_or_absolute(workspace_root, clean_string(raw.get("automation_path")))
        else:
            checkouts.setdefault("codex", "codex")
        checkouts.setdefault("release", "release")
        migrated["checkouts"] = checkouts

    branches = migrated.get("branches") if isinstance(migrated.get("branches"), dict) else {}
    branches = dict(branches)
    if normalized.get("base_branch"):
        branches.setdefault("develop", normalized["base_branch"])
    branches.setdefault("codex", clean_string(branches.get("codex")))
    branches.setdefault("release", clean_string(raw.get("release_branch")))
    if any(clean_string(value) for value in branches.values()):
        migrated["branches"] = branches

    for key in (
        "project_id",
        "name",
        "enabled",
        "version_file",
        "project_index",
        "documentation_manifest",
        "code_base_ref",
        "state_ref",
        "push_ref",
        "task_manager_branch_role",
        "automation_allowed",
        "health_threshold",
        "quarantine_mode",
        "task_queue_path",
        "agent_locks_path",
        "owner_directives_path",
        "start_here_path",
        "agent_version_path",
        "changelog_path",
    ):
        migrated.setdefault(key, normalized.get(key))
    return migrated


def migrate_registry_payload(registry: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    migrated = normalize_registry_payload(registry)
    raw_projects = registry.get("projects")
    if not isinstance(raw_projects, list):
        raise ValueError("project registry must contain projects array")
    migrated_projects: list[Any] = []
    project_reports: list[dict[str, Any]] = []
    for raw in raw_projects:
        if not isinstance(raw, dict):
            migrated_projects.append(raw)
            continue
        updated = migrate_project_to_v2(raw)
        before_keys = set(raw.keys())
        added_fields = sorted(set(updated.keys()) - before_keys)
        changed_fields = sorted(key for key in set(updated.keys()).union(raw.keys()) if updated.get(key) != raw.get(key))
        project_reports.append({
            "project_id": clean_string(updated.get("project_id")),
            "changed": bool(changed_fields),
            "added_fields": added_fields,
            "changed_fields": changed_fields,
        })
        migrated_projects.append(updated)
    migrated["schema_version"] = "2.0"
    migrated["projects"] = migrated_projects
    normalized_projects = [normalize_project(project) for project in migrated_projects if isinstance(project, dict)]
    warnings: list[str] = []
    for project in normalized_projects:
        warnings.extend(project_path_warnings(project))
    warnings.extend(duplicate_path_warnings(normalized_projects))
    report = {
        "schema_version": "2.0",
        "changed": migrated != registry,
        "project_count": len(project_reports),
        "migrated_project_count": sum(1 for item in project_reports if item["changed"]),
        "projects": project_reports,
        "warnings": warnings,
    }
    return migrated, report


def write_json(path: Path, payload: dict[str, Any]) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def migrate_registry(registry_path: Path, *, apply: bool = False) -> dict[str, Any]:
    path = registry_path.expanduser()
    registry = load_json(path)
    migrated, report = migrate_registry_payload(registry)
    report["registry_path"] = str(path)
    report["apply"] = apply
    if apply and report["changed"]:
        write_json(path, migrated)
    return report


def load_registry(registry_path: Path) -> dict[str, Any]:
    """Load a Registry and expose its normalized, optional fleet topology."""
    return normalize_registry_payload(load_json(registry_path.expanduser()))


def load_fleet_topology(registry_path: Path) -> dict[str, Any]:
    """Load only the normalized fleet topology from a project Registry."""
    return load_registry(registry_path).get("fleet_topology", default_fleet_topology())


def load_projects(registry_path: Path, project_id: str | None = None, *, include_disabled: bool = False) -> tuple[list[dict[str, Any]], list[str]]:
    registry = load_json(registry_path.expanduser())
    raw_projects = registry.get("projects")
    if not isinstance(raw_projects, list):
        raise ValueError("project registry must contain projects array")
    projects: list[dict[str, Any]] = []
    warnings: list[str] = []
    for raw in raw_projects:
        if not isinstance(raw, dict):
            continue
        project = normalize_project(raw)
        pid = clean_string(project.get("project_id"))
        if not pid:
            continue
        if project_id and pid != project_id:
            continue
        if not include_disabled and project.get("enabled", True) is False:
            continue
        if not clean_string(project.get("local_path")):
            warnings.append(f"{pid}: local_path or workspace checkout is missing")
            continue
        warnings.extend(project_path_warnings(project))
        projects.append(project)
    warnings.extend(duplicate_path_warnings(projects))
    if project_id and not projects:
        raise ValueError(f"project not found or disabled: {project_id}")
    return projects, warnings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Normalize and migrate AiStudio project registry files.")
    parser.add_argument("--registry", type=Path, required=True, help="Path to projects.json")
    parser.add_argument("--migrate", action="store_true", help="Build a v2 migration report")
    parser.add_argument("--apply", action="store_true", help="Write migrated registry back to disk")
    args = parser.parse_args(argv)
    if not args.migrate:
        parser.error("--migrate is required")
    report = migrate_registry(args.registry, apply=args.apply)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
