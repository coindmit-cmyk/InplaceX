#!/usr/bin/env python3
"""Validate legacy and universal AiStudio START_HERE orientation files."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


LEGACY_REQUIRED_SECTIONS = [
    "## Identity",
    "## First Steps",
    "## Branch Model",
    "## Version Contract",
    "## Navigation",
    "## Tasks And State",
    "## Architecture And Specs",
    "## Local-Only Access",
    "## Work Protocol",
    "## Approval Gates",
]

UNIVERSAL_REQUIRED_SECTIONS = [
    "## Project Links",
    "## Environment Route",
    "## Execution Recommendation Gate",
    "## Freshness And Conflict Gate",
    "## Authority Boundaries",
]

REQUIRED_SHARED_FILES = [
    "GPT_CHAT.md",
    "CODEX_CHAT.md",
    "AUTOMATION.md",
    "REVIEWER.md",
    "SECOND_BRAIN.md",
    "SKILLS_CAPABILITIES.md",
    "routing.md",
    "permissions.md",
    "agents.md",
]

PROJECT_LINK_PATH_FIELDS = [
    ("project", "description"),
    ("project", "current_state"),
    ("project", "rules"),
    ("execution", "task_queue"),
    ("execution", "locks"),
    ("execution", "owner_directives"),
]

FORBIDDEN_TEMPLATE_MARKERS = [
    "replace-me",
    "replace_with",
    "todo",
    "<project",
    "project-name",
    "example.com",
]

LEGACY_REQUIRED_TERMS = [
    "orientation_blocked",
    "Registry",
    "GitHub",
    "PROJECT_VERSION.json",
    "component_versions",
    "task_queue",
    "secrets",
    "approval",
]

UNIVERSAL_REQUIRED_TERMS = [
    "orientation_blocked",
    "GitHub",
    "PROJECT_LINKS.json",
    "GPT Chat",
    "Codex Chat",
    "Automation",
    "Reviewer",
    "skills",
    "model",
    "approval",
]


def issue(code: str, message: str, *, owner: str = "architect", field: str | None = None) -> dict[str, str]:
    payload = {"code": code, "message": message, "owner": owner}
    if field:
        payload["field"] = field
    return payload


def load_json_if_exists(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def normalize_ref(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    return text if text.startswith("origin/") else f"origin/{text}"


def normalize_path_text(value: Any) -> str:
    return str(value or "").strip().replace("\\", "/").rstrip("/").lower()


def resolve_project_path(root: Path, value: Any) -> Path | None:
    text = str(value or "").strip()
    if not text:
        return None
    candidate = Path(text)
    if candidate.is_absolute():
        return None
    resolved_root = root.resolve()
    resolved = (root / candidate).resolve()
    try:
        resolved.relative_to(resolved_root)
    except ValueError:
        return None
    return resolved


def extract_list_value(text: str, label: str) -> str:
    prefix = f"- {label}:"
    for line in text.splitlines():
        if not line.startswith(prefix):
            continue
        value = line[len(prefix) :].strip()
        if value.startswith("`") and value.endswith("`") and len(value) >= 2:
            value = value[1:-1]
        return value.strip()
    return ""


def replace_list_value(text: str, label: str, value: str) -> str:
    prefix = f"- {label}:"
    replacement = f"{prefix} `{value}`"
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.startswith(prefix):
            lines[index] = replacement
            return "\n".join(lines).rstrip() + "\n"
    return text.rstrip() + f"\n{replacement}\n"


def apply_local_path(project_root: Path, *, start_here_path: str = ".agent/START_HERE.md", local_path: str) -> dict[str, Any]:
    path = project_root.expanduser() / start_here_path
    if not path.is_file():
        return {"ok": False, "mutated_target": False, "path": str(path), "error": "start_here_missing"}
    before = path.read_text(encoding="utf-8")
    if ".agent/PROJECT_LINKS.json" in before:
        links_path = project_root.expanduser() / ".agent" / "PROJECT_LINKS.json"
        try:
            links = load_json_if_exists(links_path) or {}
        except (json.JSONDecodeError, ValueError):
            return {"ok": False, "mutated_target": False, "path": str(links_path), "error": "project_links_invalid"}
        context_path = resolve_project_path(project_root.expanduser(), links.get("context"))
        if context_path is None or not context_path.is_file():
            return {"ok": False, "mutated_target": False, "path": str(context_path or ""), "error": "context_missing"}
        context = load_json_if_exists(context_path) or {}
        previous = str(context.get("local_repository_path") or "")
        context["local_repository_path"] = local_path
        mutated = previous != local_path
        if mutated:
            context_path.write_text(json.dumps(context, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return {"ok": True, "mutated_target": mutated, "path": str(context_path), "local_path": local_path}
    after = replace_list_value(before, "Local path", local_path)
    mutated = before != after
    if mutated:
        path.write_text(after, encoding="utf-8")
    return {"ok": True, "mutated_target": mutated, "path": str(path), "local_path": local_path}


def validate_start_here(
    project_root: Path,
    *,
    start_here_path: str = ".agent/START_HERE.md",
    version_file: str = "PROJECT_VERSION.json",
    expected_project_id: str | None = None,
    expected_repo: str | None = None,
    expected_base_ref: str | None = None,
    expected_release_ref: str | None = None,
    expected_local_path: str | None = None,
    registry_project: dict[str, Any] | None = None,
    require: bool = True,
) -> dict[str, Any]:
    root = project_root.expanduser()
    path = root / start_here_path
    errors: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []

    if not path.is_file():
        target = errors if require else warnings
        target.append(issue("start_here_missing", f"missing {start_here_path}"))
        return {
            "ok": not errors,
            "status": "orientation_blocked" if errors else "orientation_advisory",
            "path": str(path),
            "exists": False,
            "errors": errors,
            "warnings": warnings,
        }

    text = path.read_text(encoding="utf-8")
    text_lower = text.lower()
    universal = ".agent/PROJECT_LINKS.json" in text
    required_sections = UNIVERSAL_REQUIRED_SECTIONS if universal else LEGACY_REQUIRED_SECTIONS
    required_terms = UNIVERSAL_REQUIRED_TERMS if universal else LEGACY_REQUIRED_TERMS

    for section in required_sections:
        if section not in text:
            errors.append(issue("start_here_missing_section", f"{section} is required", field=section))

    for marker in FORBIDDEN_TEMPLATE_MARKERS:
        if marker in text_lower:
            errors.append(issue("start_here_template_marker", f"template marker {marker!r} must be replaced", field=marker))

    for term in required_terms:
        if term.lower() not in text_lower:
            errors.append(issue("start_here_missing_term", f"{term} must be referenced", field=term))

    project_id = expected_project_id or (registry_project or {}).get("project_id")
    repo = expected_repo or (registry_project or {}).get("github_repo")
    local_path = expected_local_path or (registry_project or {}).get("local_path")
    base_ref = expected_base_ref or (registry_project or {}).get("code_base_ref") or normalize_ref((registry_project or {}).get("base_branch"))
    release_ref = expected_release_ref or normalize_ref(((registry_project or {}).get("branches") or {}).get("release") if isinstance((registry_project or {}).get("branches"), dict) else "")

    links: dict[str, Any] = {}
    context_path = root / ".agent" / "context.json"
    if universal:
        links_path = root / ".agent" / "PROJECT_LINKS.json"
        try:
            links = load_json_if_exists(links_path) or {}
        except (json.JSONDecodeError, ValueError) as exc:
            errors.append(issue("project_links_invalid", str(exc), field=".agent/PROJECT_LINKS.json"))
        if not links:
            errors.append(issue("project_links_missing", "missing or empty .agent/PROJECT_LINKS.json", field=".agent/PROJECT_LINKS.json"))
        else:
            project_links = links.get("project") if isinstance(links.get("project"), dict) else {}
            shared_links = links.get("shared") if isinstance(links.get("shared"), dict) else {}
            execution_links = links.get("execution") if isinstance(links.get("execution"), dict) else {}
            for field in ("id", "repository", "description", "current_state", "memory_namespace", "rules"):
                if not str(project_links.get(field) or "").strip():
                    errors.append(issue("project_links_missing_field", f"project.{field} is required", field=f"project.{field}"))
            for field in ("rules_root",):
                if not str(shared_links.get(field) or "").strip():
                    errors.append(issue("project_links_missing_field", f"shared.{field} is required", field=f"shared.{field}"))
            for field in ("task_queue", "locks", "owner_directives"):
                if not str(execution_links.get(field) or "").strip():
                    errors.append(issue("project_links_missing_field", f"execution.{field} is required", field=f"execution.{field}"))
            if project_id and str(project_links.get("id") or "") != str(project_id):
                errors.append(issue("start_here_project_id_mismatch", f"project id in PROJECT_LINKS does not match {project_id!r}", owner="dispatcher"))
            if repo and str(project_links.get("repository") or "") != str(repo):
                errors.append(issue("start_here_repo_mismatch", f"repository in PROJECT_LINKS does not match {repo!r}", owner="dispatcher"))

            for section, field in PROJECT_LINK_PATH_FIELDS:
                values = project_links if section == "project" else execution_links
                target = resolve_project_path(root, values.get(field))
                if target is None or not target.exists():
                    errors.append(issue("project_links_target_missing", f"{section}.{field} does not resolve to an existing project path", field=f"{section}.{field}"))

            map_targets = [resolve_project_path(root, project_links.get(field)) for field in ("map_human", "map_machine")]
            if not any(target is not None and target.is_file() for target in map_targets):
                errors.append(issue("project_links_map_missing", "project map_human or map_machine must resolve", field="project.map"))

            rules_root = resolve_project_path(root, shared_links.get("rules_root"))
            if rules_root is None or not rules_root.is_dir():
                errors.append(issue("project_links_rules_root_missing", "shared.rules_root must resolve to a directory", field="shared.rules_root"))
            else:
                for filename in REQUIRED_SHARED_FILES:
                    if not (rules_root / filename).is_file():
                        errors.append(issue("project_links_shared_rule_missing", f"missing shared rule {filename}", field=f"shared.rules_root/{filename}"))

            resolved_context = resolve_project_path(root, links.get("context"))
            if resolved_context is None or not resolved_context.is_file():
                errors.append(issue("project_links_target_missing", "context does not resolve to an existing file", field="context"))
            else:
                context_path = resolved_context
            resolved_version = resolve_project_path(root, links.get("version"))
            if resolved_version is None or not resolved_version.is_file():
                errors.append(issue("project_links_target_missing", "version does not resolve to an existing file", field="version"))
            elif version_file and normalize_path_text(links.get("version")) != normalize_path_text(version_file):
                errors.append(issue("start_here_version_file_mismatch", f"version link does not match {version_file!r}"))
    else:
        if project_id and str(project_id) not in text:
            errors.append(issue("start_here_project_id_mismatch", f"project id {project_id!r} is not recorded", owner="dispatcher"))
        if repo and str(repo) not in text:
            errors.append(issue("start_here_repo_mismatch", f"repository {repo!r} is not recorded", owner="dispatcher"))
        recorded_local_path = extract_list_value(text, "Local path")
        if local_path and recorded_local_path and normalize_path_text(recorded_local_path) != normalize_path_text(local_path):
            errors.append(
                issue(
                    "start_here_local_path_mismatch",
                    f"local path {recorded_local_path!r} does not match current workspace {local_path!r}",
                    owner="dispatcher",
                )
            )
        if base_ref and str(base_ref) not in text:
            errors.append(issue("start_here_base_ref_mismatch", f"base ref {base_ref!r} is not recorded", owner="dispatcher"))
        if release_ref and str(release_ref) not in text:
            errors.append(issue("start_here_release_ref_mismatch", f"release ref {release_ref!r} is not recorded", owner="dispatcher"))
        if version_file and version_file not in text:
            errors.append(issue("start_here_version_file_mismatch", f"version file {version_file!r} is not recorded"))

    context = load_json_if_exists(context_path)
    if context:
        context_project_id = str(context.get("project_id") or "").strip()
        if project_id and context_project_id and context_project_id != str(project_id):
            errors.append(issue("start_here_context_project_id_mismatch", f"context project_id {context_project_id!r} does not match {project_id!r}", owner="dispatcher"))
        context_base_ref = str(context.get("base_ref") or "").strip()
        if base_ref and context_base_ref and context_base_ref != str(base_ref):
            errors.append(issue("start_here_context_base_ref_mismatch", f"context base_ref {context_base_ref!r} does not match {base_ref!r}", owner="dispatcher"))
        context_release_ref = str(context.get("release_ref") or "").strip()
        if release_ref and context_release_ref and context_release_ref != str(release_ref):
            errors.append(issue("start_here_context_release_ref_mismatch", f"context release_ref {context_release_ref!r} does not match {release_ref!r}", owner="dispatcher"))
        context_local_path = str(context.get("local_repository_path") or "").strip()
        if local_path and context_local_path and normalize_path_text(context_local_path) != normalize_path_text(local_path):
            errors.append(issue("start_here_local_path_mismatch", f"context local path {context_local_path!r} does not match {local_path!r}", owner="dispatcher"))

    return {
        "ok": not errors,
        "status": "ok" if not errors else "orientation_blocked",
        "path": str(path),
        "exists": True,
        "contract": "universal" if universal else "legacy",
        "required_sections": required_sections,
        "errors": errors,
        "warnings": warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--start-here-path", default=".agent/START_HERE.md")
    parser.add_argument("--version-file", default="PROJECT_VERSION.json")
    parser.add_argument("--project-id")
    parser.add_argument("--repo")
    parser.add_argument("--base-ref")
    parser.add_argument("--release-ref")
    parser.add_argument("--local-path")
    parser.add_argument("--advisory", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = validate_start_here(
        Path(args.project_root),
        start_here_path=args.start_here_path,
        version_file=args.version_file,
        expected_project_id=args.project_id,
        expected_repo=args.repo,
        expected_base_ref=args.base_ref,
        expected_release_ref=args.release_ref,
        expected_local_path=args.local_path,
        require=not args.advisory,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    elif report["ok"]:
        print("ok")
    else:
        print("; ".join(error["message"] for error in report["errors"]))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
