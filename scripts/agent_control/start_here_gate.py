#!/usr/bin/env python3
"""Validate project-specific AiStudio START_HERE orientation files."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


REQUIRED_SECTIONS = [
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

FORBIDDEN_TEMPLATE_MARKERS = [
    "replace-me",
    "replace_with",
    "todo",
    "<project",
    "project-name",
    "example.com",
]

REQUIRED_TERMS = [
    "orientation_blocked",
    "Registry",
    "GitHub",
    "PROJECT_VERSION.json",
    "component_versions",
    "task_queue",
    "secrets",
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

    for section in REQUIRED_SECTIONS:
        if section not in text:
            errors.append(issue("start_here_missing_section", f"{section} is required", field=section))

    for marker in FORBIDDEN_TEMPLATE_MARKERS:
        if marker in text_lower:
            errors.append(issue("start_here_template_marker", f"template marker {marker!r} must be replaced", field=marker))

    for term in REQUIRED_TERMS:
        if term.lower() not in text_lower:
            errors.append(issue("start_here_missing_term", f"{term} must be referenced", field=term))

    project_id = expected_project_id or (registry_project or {}).get("project_id")
    if project_id and str(project_id) not in text:
        errors.append(issue("start_here_project_id_mismatch", f"project id {project_id!r} is not recorded", owner="dispatcher"))

    repo = expected_repo or (registry_project or {}).get("github_repo")
    if repo and str(repo) not in text:
        errors.append(issue("start_here_repo_mismatch", f"repository {repo!r} is not recorded", owner="dispatcher"))

    local_path = expected_local_path or (registry_project or {}).get("local_path")
    recorded_local_path = extract_list_value(text, "Local path")
    if local_path and recorded_local_path and normalize_path_text(recorded_local_path) != normalize_path_text(local_path):
        errors.append(
            issue(
                "start_here_local_path_mismatch",
                f"local path {recorded_local_path!r} does not match current workspace {local_path!r}",
                owner="dispatcher",
            )
        )

    base_ref = expected_base_ref or (registry_project or {}).get("code_base_ref") or normalize_ref((registry_project or {}).get("base_branch"))
    if base_ref and str(base_ref) not in text:
        errors.append(issue("start_here_base_ref_mismatch", f"base ref {base_ref!r} is not recorded", owner="dispatcher"))

    release_ref = expected_release_ref or normalize_ref(((registry_project or {}).get("branches") or {}).get("release") if isinstance((registry_project or {}).get("branches"), dict) else "")
    if release_ref and str(release_ref) not in text:
        errors.append(issue("start_here_release_ref_mismatch", f"release ref {release_ref!r} is not recorded", owner="dispatcher"))

    if version_file and version_file not in text:
        errors.append(issue("start_here_version_file_mismatch", f"version file {version_file!r} is not recorded"))

    context = load_json_if_exists(root / ".agent" / "context.json")
    if context:
        context_project_id = str(context.get("project_id") or "").strip()
        if project_id and context_project_id and context_project_id != str(project_id):
            errors.append(issue("start_here_context_project_id_mismatch", f"context project_id {context_project_id!r} does not match {project_id!r}", owner="dispatcher"))
        context_base_ref = str(context.get("base_ref") or "").strip()
        if base_ref and context_base_ref and context_base_ref != str(base_ref):
            errors.append(issue("start_here_context_base_ref_mismatch", f"context base_ref {context_base_ref!r} does not match {base_ref!r}", owner="dispatcher"))

    return {
        "ok": not errors,
        "status": "ok" if not errors else "orientation_blocked",
        "path": str(path),
        "exists": True,
        "required_sections": REQUIRED_SECTIONS,
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
