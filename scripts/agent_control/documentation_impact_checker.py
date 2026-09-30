#!/usr/bin/env python3
"""Documentation manifest, impact and debt checker."""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
from pathlib import Path
from typing import Any

from action_report import build_report as build_action_report


DOC_EXTENSIONS = {".md", ".rst", ".txt"}
CRITICAL_DOCS = ("PROJECT_INDEX.md", "DOCUMENTATION_MANIFEST.json")
DOCUMENTATION_STATUSES = {"current", "draft", "legacy", "deprecated", "generated", "orphan_candidate"}
EXCLUDED_ROOT_DIRS = {
    ".git",
    "archive",
    "build",
    "dist",
    "node_modules",
    "runtime",
    "temp",
    ".pytest_cache",
    ".mypy_cache",
    "__pycache__",
    ".venv",
}
GENERATED_REPORT_DIRS = {
    "AiStudio/Task_manager/reports",
    "docs/reports",
}


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def normalize_path(value: Any) -> str:
    return str(value or "").replace("\\", "/").strip("/")


def manifest_docs(manifest: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(manifest, dict):
        return {}
    return {
        normalize_path(item.get("path")): item
        for item in manifest.get("documents") or []
        if isinstance(item, dict) and normalize_path(item.get("path"))
    }


def manifest_rules(manifest: Any) -> list[dict[str, Any]]:
    if not isinstance(manifest, dict):
        return []
    return [
        item
        for item in manifest.get("classification_rules") or []
        if isinstance(item, dict) and normalize_path(item.get("pattern"))
    ]


def matching_manifest_rule(path: str, manifest: Any) -> dict[str, Any] | None:
    normalized = normalize_path(path)
    for rule in manifest_rules(manifest):
        pattern = normalize_path(rule.get("pattern"))
        if fnmatch.fnmatchcase(normalized, pattern):
            return rule
    return None


def scan_docs(project_root: Path) -> list[str]:
    result: list[str] = []
    for root, dirs, files in os.walk(project_root, topdown=True):
        relative_root = Path(root).relative_to(project_root)
        dirs[:] = [
            directory
            for directory in dirs
            if directory not in EXCLUDED_ROOT_DIRS
            and (relative_root / directory).as_posix() not in GENERATED_REPORT_DIRS
        ]
        for filename in files:
            path = Path(root) / filename
            if path.suffix.lower() not in DOC_EXTENSIONS:
                continue
            result.append(path.relative_to(project_root).as_posix())
    for rel in CRITICAL_DOCS:
        if (project_root / rel).is_file() and rel not in result:
            result.append(rel)
    return sorted(result)


def project_document_paths(project_root: Path) -> tuple[str, str]:
    version = load_json(project_root / "PROJECT_VERSION.json")
    if isinstance(version, dict):
        index_path = normalize_path(version.get("project_index")) or "PROJECT_INDEX.md"
        manifest_path = normalize_path(version.get("documentation_manifest")) or "DOCUMENTATION_MANIFEST.json"
        return index_path, manifest_path
    return "PROJECT_INDEX.md", "DOCUMENTATION_MANIFEST.json"


def normalize_status(value: Any) -> str:
    return str(value or "").strip().lower()


def _build_manifest_classification(manifest: Any) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for path, item in manifest_docs(manifest).items():
        status = normalize_status(item.get("status"))
        if status not in DOCUMENTATION_STATUSES:
            status = "orphan_candidate"
        entries.append(
            {
                "path": path,
                "status": status,
                "classification_source": "explicit_document",
                "actionable": status == "orphan_candidate",
                "next_owner": "architect",
                "next_action": (
                    "Classify in DOCUMENTATION_MANIFEST.json or create documentation debt."
                    if status == "orphan_candidate"
                    else "No task required while the explicit manifest classification remains valid."
                ),
            }
        )
    return sorted(entries, key=lambda item: item["path"])


def classify_discovered_documents(
    project_root: Path,
    manifest: Any,
    critical_docs: tuple[str, ...] = CRITICAL_DOCS,
) -> list[dict[str, Any]]:
    known = manifest_docs(manifest)
    classified: list[dict[str, Any]] = []
    for rel in scan_docs(project_root):
        if rel in critical_docs:
            continue
        if rel in known:
            continue
        rule = matching_manifest_rule(rel, manifest)
        if rule:
            status = normalize_status(rule.get("status"))
            if status not in DOCUMENTATION_STATUSES:
                status = "orphan_candidate"
            source = "manifest_rule"
            pattern = normalize_path(rule.get("pattern"))
            owner = str(rule.get("owner") or "architect")
        else:
            status = "generated" if rel.startswith("docs/reports/") else "orphan_candidate"
            source = "default_generated_rule" if status == "generated" else "unclassified"
            pattern = "docs/reports/**" if status == "generated" else None
            owner = "architect"
        actionable = status == "orphan_candidate"
        item = {
            "path": rel,
            "status": status,
            "classification_source": source,
            "actionable": actionable,
            "next_owner": owner,
            "next_action": (
                "Classify in DOCUMENTATION_MANIFEST.json or create documentation debt."
                if actionable
                else "No task required while the manifest classification remains valid."
            ),
        }
        if pattern:
            item["matched_pattern"] = pattern
        classified.append(item)
    return classified


def classify_orphans(project_root: Path, manifest: Any, critical_docs: tuple[str, ...] = CRITICAL_DOCS) -> list[dict[str, Any]]:
    return [
        item
        for item in classify_discovered_documents(project_root, manifest, critical_docs)
        if item.get("actionable") is True
    ]


def classification_counts(items: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for item in items:
        status = normalize_status(item.get("status")) or "unknown"
        counts[status] = counts.get(status, 0) + 1
    return dict(sorted(counts.items()))


def validate_manifest_documents(project_root: Path, manifest: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    resolved: list[dict[str, Any]] = []
    for rel, item in manifest_docs(manifest).items():
        if (project_root / rel).exists():
            continue
        status = normalize_status(item.get("status"))
        replacement = normalize_path(item.get("replaced_by"))
        if status in {"legacy", "deprecated"} and replacement and (project_root / replacement).exists():
            resolved.append({"path": rel, "status": status, "replaced_by": replacement})
            continue
        finding = {
            "code": "manifest_document_missing",
            "path": rel,
            "status": status or "unknown",
            "next_owner": "architect",
            "next_action": "Restore the document, remove the manifest entry, or provide an existing replacement.",
        }
        if status == "generated":
            warnings.append(finding)
        else:
            errors.append(finding)
    return errors, warnings, resolved


def impact_for_paths(changed_paths: list[str]) -> str:
    docs = [path for path in changed_paths if Path(path).suffix.lower() in DOC_EXTENSIONS or path in CRITICAL_DOCS]
    code = [path for path in changed_paths if path and path not in docs]
    if not changed_paths:
        return "none"
    if code and not docs:
        return "docs_task_required"
    if docs and not code:
        return "updated_inline"
    return "updated_inline"


def debt_task(project_id: str, required_paths: list[str], reason: str, now: str = "generated") -> dict[str, Any]:
    suffix = "-".join(Path(path).stem.upper().replace("_", "-") for path in required_paths[:2]) or "DOCS"
    return {
        "id": f"DOC-DEBT-{suffix}",
        "type": "documentation_debt",
        "status": "planned",
        "priority": "P1",
        "complexity": "S" if len(required_paths) <= 2 else "M",
        "project_id": project_id,
        "title": "Resolve documentation debt",
        "required_paths": required_paths,
        "reason": reason,
        "owner": "architect",
        "eligible_worker_profiles": ["auto-worker-5.3-mini", "auto-worker-5.3"],
        "allowed_paths": sorted(set(required_paths + ["DOCUMENTATION_MANIFEST.json", "PROJECT_INDEX.md"])),
        "forbidden_paths": [".env", ".env.*", "secrets/**", "production config"],
        "checks": ["python scripts/agent_control/documentation_impact_checker.py --project-root . --json"],
        "acceptance_criteria": [
            "Required documents are current or explicitly classified in DOCUMENTATION_MANIFEST.json",
            "No orphan_candidate document is used as source of truth before classification",
            "Documentation revision is bumped when the project enforces PROJECT_VERSION.json",
        ],
        "created_at": now,
    }


def build_action_report_payload(
    *,
    project_root: Path,
    project_id: str,
    report: dict[str, Any],
    changed_paths: list[str] | None = None,
) -> dict[str, Any]:
    is_blocked = bool(report.get("release_blocking"))
    errors = report.get("errors") or []
    if not is_blocked:
        result = "succeeded"
        next_owner = "none"
        next_action = "No follow-up required for documentation check."
    else:
        first_error = errors[0] if errors else {}
        result = "blocked"
        next_owner = str(first_error.get("next_owner") or "architect").strip() or "architect"
        next_action = str(first_error.get("next_action") or "Close documentation blockers before continuing.")
    normalized_changed = [normalize_path(path) for path in (changed_paths or []) if normalize_path(path)]
    validation = {
        "ok": not is_blocked,
        "errors": errors,
        "warnings": report.get("warnings", []),
        "documentation_impact": report.get("documentation_impact"),
    }
    before_state = {
        "project_id": project_id or "",
        "project_root": str(project_root),
        "project_index": report.get("project_index"),
        "documentation_manifest": report.get("documentation_manifest"),
        "documentation_impact": report.get("documentation_impact"),
        "changed_paths": sorted(set(normalized_changed)),
    }
    return build_action_report(
        action_id="documentation-impact-check",
        action_type="documentation.check",
        project_id=project_id or "unknown",
        actor="documentation-impact-checker",
        mode="dry_run",
        result=result,
        next_owner=next_owner,
        next_action=next_action,
        input_refs=[f"project_root={project_root}", f"changed_paths={','.join(sorted(set(normalized_changed)))}"],
        before_state=before_state,
        after_state=before_state,
        actions_planned=[{"action": "documentation_impact_check", "changed_paths": sorted(set(normalized_changed))}],
        actions_executed=[{"action": "documentation_impact_check", "result": result}],
        actions_failed=[{"action": "documentation_impact_check", "documentation_impact": report.get("documentation_impact")} ] if is_blocked else [],
        affected_paths=[item["path"] for item in report.get("orphan_candidates", [])],
        validation=validation,
        rollback={"path": report.get("documentation_manifest")},
        residual_risks=[report.get("documentation_impact")],
        source="documentation_impact_checker.py",
    )


def build_report(project_root: Path, *, changed_paths: list[str] | None = None, project_id: str = "") -> dict[str, Any]:
    index_rel, manifest_rel = project_document_paths(project_root)
    manifest_path = project_root / manifest_rel
    index_path = project_root / index_rel
    manifest = load_json(manifest_path)
    changed = [normalize_path(path) for path in (changed_paths or []) if normalize_path(path)]
    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    if not index_path.is_file():
        errors.append({"code": "project_index_missing", "path": index_rel, "next_owner": "architect", "next_action": "Create project index from template without overwriting local deviations."})
    if not isinstance(manifest, dict):
        errors.append({"code": "documentation_manifest_missing_or_invalid", "path": manifest_rel, "next_owner": "architect", "next_action": "Create or repair documentation manifest."})
    else:
        manifest_errors, manifest_warnings, resolved_replacements = validate_manifest_documents(project_root, manifest)
        errors.extend(manifest_errors)
        warnings.extend(manifest_warnings)
    if not isinstance(manifest, dict):
        resolved_replacements = []
    classified = _build_manifest_classification(manifest)
    discovered = classify_discovered_documents(project_root, manifest, critical_docs=(index_rel, manifest_rel))
    all_classified = sorted([*classified, *discovered], key=lambda item: item["path"])
    orphans = [item for item in all_classified if item.get("actionable") is True]
    if orphans:
        warnings.append({"code": "orphan_docs_found", "count": len(orphans), "next_owner": "architect", "next_action": "Classify orphan candidates or create docs debt."})
    impact = impact_for_paths(changed)
    debt: list[dict[str, Any]] = []
    missing_required = [item["path"] for item in errors if item.get("path")]
    if impact == "docs_task_required":
        missing_required.append("PROJECT_INDEX.md")
    if missing_required:
        debt.append(debt_task(project_id or "unknown", sorted(set(missing_required)), "documentation gate requires explicit follow-up"))
    for offset in range(0, len(orphans), 20):
        required_paths = [item["path"] for item in orphans[offset : offset + 20]]
        debt.append(debt_task(project_id or "unknown", required_paths, "unclassified documentation requires an explicit owner route"))
    return {
        "schema_version": "1.0",
        "project_root": str(project_root),
        "project_id": project_id or None,
        "project_index": index_rel,
        "documentation_manifest": manifest_rel,
        "documentation_impact": "blocked_missing_docs" if errors else impact,
        "changed_paths": changed,
        "errors": errors,
        "warnings": warnings,
        "classified_documents": all_classified,
        "classification_summary": {
            "total": len(all_classified),
            "actionable_orphan_count": len(orphans),
            "non_actionable_count": len([item for item in all_classified if item.get("actionable") is not True]),
            "by_status": classification_counts(all_classified),
            "rule_count": len(manifest_rules(manifest)),
        },
        "orphan_candidates": orphans,
        "resolved_replacements": resolved_replacements,
        "documentation_debt": debt,
        "release_blocking": bool(errors),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--project-id", default="")
    parser.add_argument("--changed-path", action="append", default=[])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--action-report", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    project_root = Path(args.project_root).expanduser()
    report = build_report(project_root, changed_paths=args.changed_path, project_id=args.project_id)
    if args.action_report:
        action_report_payload = build_action_report_payload(
            project_root=project_root,
            project_id=args.project_id,
            report=report,
            changed_paths=args.changed_path,
        )
        write_json_atomic(args.action_report.expanduser(), action_report_payload)
    if args.output:
        write_json_atomic(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(report["documentation_impact"])
    return 0 if not report["release_blocking"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
