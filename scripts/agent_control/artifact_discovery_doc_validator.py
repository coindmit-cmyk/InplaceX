#!/usr/bin/env python3
"""Validate Artifact Discovery documentation and entrypoint coverage."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


ADL_SCRIPT_PATHS = [
    "scripts/agent_control/artifact_discovery_scanner.py",
    "scripts/agent_control/artifact_discovery_classifier.py",
    "scripts/agent_control/artifact_discovery_router.py",
    "scripts/agent_control/artifact_discovery_report_builder.py",
    "scripts/agent_control/artifact_discovery_normalizer.py",
    "scripts/agent_control/artifact_discovery_cycle.py",
    "scripts/agent_control/artifact_discovery_doc_validator.py",
]

REQUIRED_DOC_TEXT = {
    "docs/agent/discovery/README.md": [
        "Scanner detects.",
        "Normalizer scopes noisy routes",
        "artifact_discovery_cycle.py",
    ],
    "docs/agent/discovery/ARTIFACT_DISCOVERY_RUNBOOK.md": [
        "scan -> classify -> route -> report -> normalize",
        "artifact_discovery_normalizer.py",
        "--apply-normalized",
    ],
    "docs/agent/discovery/VALIDATION.md": [
        "artifact_discovery_cycle.py",
        "artifact_discovery_doc_validator.py",
        "tests/test_artifact_discovery_doc_validator.py",
    ],
    "docs/agent/discovery/README_FIRST_RUN.md": [
        "artifact_discovery_cycle.py",
        "--worker-ready-first-safe",
    ],
}


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def validate(project_root: Path) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []
    discovery_dir = project_root / "docs" / "agent" / "discovery"
    index_path = discovery_dir / "INDEX.md"
    index_text = read_text(index_path)
    catalog_text = read_text(project_root / "agent-core" / "docs" / "automation" / "SCRIPTS_CATALOG.md")

    if not discovery_dir.is_dir():
        errors.append("missing docs/agent/discovery directory")
        discovery_files: list[Path] = []
    else:
        discovery_files = sorted(discovery_dir.glob("*.md"), key=lambda item: item.name.lower())

    for path in discovery_files:
        marker = f"`{path.name}`"
        if marker not in index_text:
            errors.append(f"INDEX.md missing discovery document: {path.name}")

    for rel, required_fragments in REQUIRED_DOC_TEXT.items():
        text = read_text(project_root / rel)
        if not text:
            errors.append(f"missing required doc: {rel}")
            continue
        for fragment in required_fragments:
            if fragment not in text:
                errors.append(f"{rel} missing required text: {fragment}")

    for rel in ADL_SCRIPT_PATHS:
        if not (project_root / rel).is_file():
            errors.append(f"missing ADL script: {rel}")
        if rel not in catalog_text:
            errors.append(f"SCRIPTS_CATALOG.md missing ADL script path: {rel}")

    return {
        "ok": not errors,
        "discovery_doc_count": len(discovery_files),
        "adl_script_count": len(ADL_SCRIPT_PATHS),
        "errors": errors,
        "warnings": warnings,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    report = validate(Path(args.project_root).resolve())
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"ok: {report['ok']}")
        print(f"discovery_docs: {report['discovery_doc_count']}")
        print(f"adl_scripts: {report['adl_script_count']}")
        for error in report["errors"]:
            print(f"ERROR: {error}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
