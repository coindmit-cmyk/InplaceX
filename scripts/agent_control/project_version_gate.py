#!/usr/bin/env python3
"""Validate and update Project Standard v2 PROJECT_VERSION.json files."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
from pathlib import Path
from typing import Any

from action_report import build_report as build_action_report


FULL_GIT_OID_RE = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def git_head(repo: Path) -> str:
    proc = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, text=True, capture_output=True, check=False)
    return proc.stdout.strip() if proc.returncode == 0 else ""


def git_commit_exists(repo: Path, commit: str) -> bool | None:
    try:
        inside = subprocess.run(
            ["git", "rev-parse", "--is-inside-work-tree"],
            cwd=repo,
            text=True,
            capture_output=True,
            check=False,
        )
    except OSError:
        return None
    if inside.returncode != 0:
        return None
    try:
        probe = subprocess.run(
            ["git", "cat-file", "-e", f"{commit}^{{commit}}"],
            cwd=repo,
            text=True,
            capture_output=True,
            check=False,
        )
    except OSError:
        return None
    return probe.returncode == 0


def validate_version(
    project_root: Path,
    *,
    version_file: str = "PROJECT_VERSION.json",
    expected_branch_role: str | None = None,
    require: bool = False,
) -> dict[str, Any]:
    path = project_root / version_file
    errors: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []
    if not path.is_file():
        issue = {"code": "project_version_missing", "message": f"missing {version_file}", "owner": "architect"}
        if require:
            errors.append(issue)
        else:
            warnings.append(issue)
        return {"ok": not errors, "path": str(path), "exists": False, "errors": errors, "warnings": warnings}
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        return {"ok": False, "path": str(path), "exists": True, "errors": [{"code": "project_version_invalid_json", "message": str(exc), "owner": "architect"}], "warnings": []}
    required = [
        "schema_version",
        "project_id",
        "branch_role",
        "product_version",
        "state_revision",
        "documentation_revision",
        "source_of_truth",
        "updated_at",
        "updated_by",
        "project_index",
        "documentation_manifest",
        "task_manager",
    ]
    for key in required:
        if data.get(key) in (None, ""):
            errors.append({"code": "project_version_missing_field", "field": key, "message": f"{key} is required", "owner": "architect"})
    for key in ("state_revision", "documentation_revision"):
        if key in data and not isinstance(data.get(key), int):
            errors.append({"code": "project_version_revision_invalid", "field": key, "message": f"{key} must be integer", "owner": "architect"})
    components = data.get("component_versions")
    if components is not None:
        if not isinstance(components, list):
            errors.append({"code": "project_version_component_versions_invalid", "field": "component_versions", "message": "component_versions must be an array", "owner": "architect"})
        else:
            seen_components: set[str] = set()
            for index, component in enumerate(components):
                component_path = f"component_versions[{index}]"
                if not isinstance(component, dict):
                    errors.append({"code": "project_version_component_invalid", "field": component_path, "message": "component version row must be an object", "owner": "architect"})
                    continue
                component_id = str(component.get("id") or "").strip()
                if not component_id:
                    errors.append({"code": "project_version_component_missing_field", "field": f"{component_path}.id", "message": "component id is required", "owner": "architect"})
                elif component_id in seen_components:
                    errors.append({"code": "project_version_component_duplicate", "field": f"{component_path}.id", "message": f"duplicate component id: {component_id}", "owner": "architect"})
                else:
                    seen_components.add(component_id)
                for key in ("kind", "version", "path", "updated_at", "updated_by"):
                    if component.get(key) in (None, ""):
                        errors.append({"code": "project_version_component_missing_field", "field": f"{component_path}.{key}", "message": f"component {key} is required", "owner": "architect"})
    if expected_branch_role and data.get("branch_role") != expected_branch_role:
        errors.append({
            "code": "project_version_branch_role_mismatch",
            "message": f"branch_role {data.get('branch_role')!r} does not match {expected_branch_role!r}",
            "owner": "dispatcher",
        })
    if not (data.get("git_commit") or (data.get("content_base_commit") and data.get("recorded_by_commit"))):
        errors.append({"code": "project_version_commit_missing", "message": "git_commit or content_base_commit+recorded_by_commit is required", "owner": "integrator"})
    else:
        commit_fields = ("git_commit",) if data.get("git_commit") else ("content_base_commit", "recorded_by_commit")
        for field in commit_fields:
            commit = str(data.get(field) or "").strip()
            if commit and FULL_GIT_OID_RE.fullmatch(commit) is None:
                errors.append({
                    "code": "project_version_commit_invalid",
                    "field": field,
                    "message": f"{field} must be a full lowercase Git object ID",
                    "owner": "integrator",
                })
            elif commit and git_commit_exists(project_root, commit) is False:
                errors.append({
                    "code": "project_version_commit_unknown",
                    "field": field,
                    "message": f"{field} does not resolve to a commit: {commit}",
                    "owner": "integrator",
                })
    return {"ok": not errors, "path": str(path), "exists": True, "version": data, "errors": errors, "warnings": warnings}


def bump_version(
    project_root: Path,
    *,
    version_file: str = "PROJECT_VERSION.json",
    action: str,
    updated_by: str,
    product_version: str | None = None,
) -> dict[str, Any]:
    path = project_root / version_file
    data = load_json(path)
    before = {
        "product_version": data.get("product_version"),
        "state_revision": data.get("state_revision"),
        "documentation_revision": data.get("documentation_revision"),
    }
    if action == "product":
        if not product_version:
            raise ValueError("--product-version is required for product action")
        data["product_version"] = product_version
        data["state_revision"] = int(data.get("state_revision") or 0) + 1
    elif action == "state":
        data["state_revision"] = int(data.get("state_revision") or 0) + 1
    elif action == "documentation":
        data["documentation_revision"] = int(data.get("documentation_revision") or 0) + 1
        data["state_revision"] = int(data.get("state_revision") or 0) + 1
    else:
        raise ValueError(f"unsupported action: {action}")
    head = git_head(project_root)
    if head:
        data["recorded_by_commit"] = head
    data["updated_at"] = now_utc()
    data["updated_by"] = updated_by
    write_json_atomic(path, data)
    after = {
        "product_version": data.get("product_version"),
        "state_revision": data.get("state_revision"),
        "documentation_revision": data.get("documentation_revision"),
    }
    return {"ok": True, "path": str(path), "action": action, "before": before, "after": after}


def _next_owner_and_action(errors: list[dict[str, Any]], default: str) -> tuple[str, str]:
    if not errors:
        return "none", "No follow-up required for project version validate mode."
    first_error = errors[0]
    next_owner = str(first_error.get("owner") or default).strip() or default
    next_action = str(first_error.get("message") or first_error.get("field") or "Resolve validation blockers.")
    return next_owner or "dispatcher", f"Review action blockers: {next_action}"


def build_validate_action_report(
    project_root: Path,
    *,
    version_report: dict[str, Any],
    version_file: str,
    actor: str = "project-version-gate",
) -> dict[str, Any]:
    validation_ok = bool(version_report.get("ok"))
    result = "succeeded" if validation_ok else "failed"
    version_data = version_report.get("version") or {}
    before_state = {
        "version_file": version_report.get("path", str(project_root / version_file)),
        "version": {
            "schema_version": version_data.get("schema_version"),
            "project_id": version_data.get("project_id"),
            "branch_role": version_data.get("branch_role"),
            "product_version": version_data.get("product_version"),
            "state_revision": version_data.get("state_revision"),
            "documentation_revision": version_data.get("documentation_revision"),
        },
    }
    next_owner, next_action = _next_owner_and_action(version_report.get("errors") or [], "architect")
    return build_action_report(
        action_id="project-version-gate.validate",
        action_type="version.validate",
        project_id=str(version_data.get("project_id") or version_report.get("project_id") or version_report.get("path") or project_root.name),
        actor=actor,
        mode="dry_run",
        result=result,
        next_owner=next_owner,
        next_action=next_action,
        input_refs=[
            "project_root",
            f"version_file={version_file}",
            "mode=validate",
            f"required={version_report.get('exists', False)}",
        ],
        before_state=before_state,
        after_state=before_state,
        actions_planned=[{"action": "validate_project_version", "artifact": "PROJECT_VERSION.json"}],
        actions_executed=[{"action": "validate_project_version", "result": "passed" if validation_ok else "failed"}],
        actions_failed=[{"action": "validate_project_version", "errors": version_report.get("errors", [])}] if not validation_ok else [],
        affected_paths=[str(version_report.get("path"))] if version_report.get("path") else [],
        validation={"ok": validation_ok, "errors": version_report.get("errors", []), "warnings": version_report.get("warnings", [])},
        source="project-version-gate.py",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--version-file", default="PROJECT_VERSION.json")
    parser.add_argument("--expected-branch-role")
    parser.add_argument("--require", action="store_true")
    parser.add_argument("--bump", choices=["product", "state", "documentation"])
    parser.add_argument("--product-version")
    parser.add_argument("--updated-by", default="project-version-gate")
    parser.add_argument("--action-report", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    root = Path(args.project_root).expanduser()
    if args.bump:
        report = bump_version(root, version_file=args.version_file, action=args.bump, updated_by=args.updated_by, product_version=args.product_version)
    else:
        report = validate_version(root, version_file=args.version_file, expected_branch_role=args.expected_branch_role, require=args.require)
    if args.action_report and not args.bump:
        report_payload = build_validate_action_report(
            root,
            version_report=report,
            version_file=args.version_file,
        )
        write_json_atomic(args.action_report.expanduser(), report_payload)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    elif report["ok"]:
        print("ok")
    else:
        print("; ".join(error["message"] for error in report["errors"]))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
