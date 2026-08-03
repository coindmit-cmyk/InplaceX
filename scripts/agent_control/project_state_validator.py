#!/usr/bin/env python3
"""Validate Project State Hub foundation files."""

from __future__ import annotations

import argparse
import io
import json
import subprocess
import tarfile
import tempfile
from pathlib import Path
from typing import Any

import project_state_summary_builder


REQUIRED_PATHS = [
    "AiStudio/Project_state/README.md",
    "AiStudio/Project_state/indexes/current_summary.md",
    "PROJECT_MAP.json",
    "PROJECT_VERSION.json",
    "AiStudio/Task_manager/task_queue.json",
]


def validate(project_root: Path) -> dict[str, Any]:
    errors: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []
    for rel in REQUIRED_PATHS:
        if not (project_root / rel).exists():
            errors.append({"code": "missing_required_path", "path": rel, "message": f"missing {rel}"})
    summary = project_root / "AiStudio/Project_state/indexes/current_summary.md"
    if summary.exists():
        text = summary.read_text(encoding="utf-8")
        if "initial static summary" in text:
            errors.append({"code": "static_current_summary", "path": summary.as_posix(), "message": "current summary is still static"})
        elif project_state_summary_builder.normalize_generated_at(text) != project_state_summary_builder.normalize_generated_at(
            project_state_summary_builder.markdown(project_root.resolve(), generated_at=project_state_summary_builder.extract_generated_at(text) or "generated-at")
        ):
            errors.append({"code": "stale_current_summary", "path": summary.as_posix(), "message": "current summary is stale relative to PROJECT_VERSION, PROJECT_MAP or task queue"})
    memory = project_root / "AiStudio/ProjectMemory"
    if not memory.exists():
        warnings.append({"code": "project_memory_workspace_missing", "path": memory.as_posix(), "message": "ProjectMemory workspace is not initialized"})
    return {
        "schema_version": "1.0",
        "mode": "project_state_validator",
        "project_root": str(project_root.resolve()),
        "ok": not errors,
        "errors": errors,
        "warnings": warnings,
    }


def refresh_and_validate(project_root: Path) -> dict[str, Any]:
    project_root = project_root.resolve()
    missing = [rel for rel in REQUIRED_PATHS if not (project_root / rel).exists()]
    if missing:
        return {
            "ok": True,
            "skipped": True,
            "reason": "project_state_contract_not_present",
            "missing": missing,
            "refreshed": False,
            "validation": None,
        }
    try:
        preview = project_state_summary_builder.build_report(project_root, apply=False)
        refreshed = bool(preview.get("stale"))
        refresh = (
            project_state_summary_builder.build_report(project_root, apply=True)
            if refreshed
            else preview
        )
        validation = validate(project_root)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return {
            "ok": False,
            "skipped": False,
            "reason": "project_state_refresh_failed",
            "error": str(exc),
            "refreshed": False,
            "validation": None,
        }
    return {
        "ok": bool(validation.get("ok")),
        "skipped": False,
        "reason": (
            "project_state_refreshed_and_valid"
            if refreshed and validation.get("ok")
            else "project_state_valid"
            if validation.get("ok")
            else "project_state_validation_failed"
        ),
        "refreshed": refreshed,
        "refresh": refresh,
        "validation": validation,
    }


def refresh_ref_and_validate(
    project_root: Path,
    *,
    ref: str = "HEAD",
    apply: bool = False,
) -> dict[str, Any]:
    project_root = project_root.resolve()
    missing_worktree = [rel for rel in REQUIRED_PATHS if not (project_root / rel).exists()]
    if missing_worktree:
        current_tree = subprocess.run(
            ["git", "ls-tree", "-r", "--name-only", ref],
            cwd=project_root,
            capture_output=True,
            text=True,
            check=False,
        )
        current_paths = set(current_tree.stdout.splitlines()) if current_tree.returncode == 0 else set()
        missing_ref = [rel for rel in REQUIRED_PATHS if rel not in current_paths]
        if missing_ref:
            parent_tree = subprocess.run(
                ["git", "ls-tree", "-r", "--name-only", f"{ref}^"],
                cwd=project_root,
                capture_output=True,
                text=True,
                check=False,
            )
            parent_paths = set(parent_tree.stdout.splitlines()) if parent_tree.returncode == 0 else set()
            if all(rel in parent_paths for rel in REQUIRED_PATHS):
                return {
                    "ok": False,
                    "skipped": False,
                    "reason": "project_state_required_path_deleted",
                    "ref": ref,
                    "missing": missing_ref,
                    "refreshed": False,
                    "validation": {
                        "ok": False,
                        "errors": [
                            {
                                "code": "missing_required_path",
                                "path": rel,
                                "message": f"missing {rel} from {ref}",
                            }
                            for rel in missing_ref
                        ],
                        "warnings": [],
                    },
                }
            return {
                "ok": True,
                "skipped": True,
                "reason": "project_state_contract_not_present",
                "ref": ref,
                "missing": missing_ref,
                "refreshed": False,
                "validation": None,
            }
    archive = subprocess.run(
        ["git", "archive", "--format=tar", ref],
        cwd=project_root,
        capture_output=True,
        check=False,
    )
    if archive.returncode != 0:
        return {
            "ok": False,
            "skipped": False,
            "reason": "project_state_ref_archive_failed",
            "ref": ref,
            "error": (
                archive.stderr.decode("utf-8", errors="replace")
                if isinstance(archive.stderr, bytes)
                else str(archive.stderr or "")
            ).strip(),
            "refreshed": False,
            "validation": None,
        }
    try:
        with tempfile.TemporaryDirectory(prefix="project-state-ref-") as tmp:
            snapshot_root = Path(tmp)
            archive_bytes = (
                archive.stdout.encode("utf-8")
                if isinstance(archive.stdout, str)
                else bytes(archive.stdout)
            )
            with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode="r:") as bundle:
                bundle.extractall(snapshot_root, filter="data")
            missing = [rel for rel in REQUIRED_PATHS if not (snapshot_root / rel).exists()]
            if missing:
                parent_contract = subprocess.run(
                    ["git", "cat-file", "-e", f"{ref}^:" + REQUIRED_PATHS[0]],
                    cwd=project_root,
                    capture_output=True,
                    check=False,
                ).returncode == 0 and all(
                    subprocess.run(
                        ["git", "cat-file", "-e", f"{ref}^:{rel}"],
                        cwd=project_root,
                        capture_output=True,
                        check=False,
                    ).returncode == 0
                    for rel in REQUIRED_PATHS[1:]
                )
                if parent_contract:
                    return {
                        "ok": False,
                        "skipped": False,
                        "reason": "project_state_required_path_deleted",
                        "ref": ref,
                        "missing": missing,
                        "refreshed": False,
                        "validation": {
                            "ok": False,
                            "errors": [
                                {
                                    "code": "missing_required_path",
                                    "path": rel,
                                    "message": f"missing {rel} from {ref}",
                                }
                                for rel in missing
                            ],
                            "warnings": [],
                        },
                    }
                return {
                    "ok": True,
                    "skipped": True,
                    "reason": "project_state_contract_not_present",
                    "ref": ref,
                    "missing": missing,
                    "refreshed": False,
                    "validation": None,
                }
            preview = project_state_summary_builder.build_report(snapshot_root, apply=False)
            source_was_stale = bool(preview.get("stale"))
            if source_was_stale:
                project_state_summary_builder.build_report(snapshot_root, apply=True)
            validation = validate(snapshot_root)
            summary_content = (
                snapshot_root / "AiStudio" / "Project_state" / "indexes" / "current_summary.md"
            ).read_text(encoding="utf-8")
    except (OSError, ValueError, json.JSONDecodeError, tarfile.TarError) as exc:
        return {
            "ok": False,
            "skipped": False,
            "reason": "project_state_ref_validation_failed",
            "ref": ref,
            "error": str(exc),
            "refreshed": False,
            "validation": None,
        }

    target = project_root / "AiStudio" / "Project_state" / "indexes" / "current_summary.md"
    current = target.read_text(encoding="utf-8") if target.is_file() else ""
    refreshed = current != summary_content
    if apply and refreshed:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(summary_content, encoding="utf-8")
    return {
        "ok": bool(validation.get("ok")),
        "skipped": False,
        "reason": (
            "project_state_ref_refreshed_and_valid"
            if refreshed and validation.get("ok")
            else "project_state_ref_valid"
            if validation.get("ok")
            else "project_state_ref_validation_failed"
        ),
        "ref": ref,
        "apply": apply,
        "source_was_stale": source_was_stale,
        "refreshed": refreshed,
        "validation": validation,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = validate(Path(args.project_root))
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"ok={report['ok']} errors={len(report['errors'])} warnings={len(report['warnings'])}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
