#!/usr/bin/env python3
"""Build Markdown reports from Artifact Discovery JSON reports."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def markdown(report: dict[str, Any]) -> str:
    summary = report.get("summary") or {}
    lines = [
        "# Artifact Discovery Report",
        "",
        f"Generated: `{report.get('generated_at', '')}`",
        f"Project root: `{report.get('project_root', '')}`",
        "",
        "## Summary",
        "",
        f"- Inventory: `{summary.get('inventory_count', 0)}`",
        f"- Findings: `{summary.get('finding_count', 0)}`",
        f"- Blocking: `{summary.get('blocking_count', 0)}`",
        f"- Task candidates: `{summary.get('task_candidate_count', 0)}`",
        "",
    ]
    for title, key in [
        ("By category", "by_category"),
        ("By severity", "by_severity"),
        ("By owner", "by_owner"),
        ("By disposition", "by_disposition"),
        ("By semantic kind", "by_semantic_kind"),
        ("By implementation status", "by_implementation_status"),
        ("By integration status", "by_integration_status"),
        ("Resolution", "resolution_counts"),
    ]:
        values = summary.get(key)
        if isinstance(values, dict) and values:
            lines.extend([f"### {title}", ""])
            for name, count in values.items():
                lines.append(f"- `{name}`: `{count}`")
            lines.append("")
    lines.extend(["## Findings", ""])
    for finding in report.get("findings") or []:
        if not isinstance(finding, dict):
            continue
        lines.extend([
            f"### {finding.get('id', 'finding')}",
            "",
            f"- Path: `{finding.get('path', '')}`",
            f"- Category: `{finding.get('category', '')}`",
            f"- Artifact flags: `{', '.join(str(flag) for flag in finding.get('artifact_flags') or [])}`",
            f"- Artifact disposition: `{finding.get('artifact_disposition', '')}`",
            f"- Semantic kind: `{finding.get('semantic_kind', '')}`",
            f"- Implementation status: `{finding.get('implementation_status', '')}`",
            f"- Integration status: `{finding.get('integration_status', '')}`",
            f"- Severity: `{finding.get('severity', '')}`",
            f"- Confidence: `{finding.get('confidence', '')}`",
            f"- Owner: `{finding.get('suggested_owner', '')}`",
            f"- Action: `{finding.get('suggested_action', '')}`",
            f"- Resolution: `{finding.get('resolution_status', 'unknown')}`",
            "",
        ])
    lines.append("## Task Candidates")
    lines.append("")
    for task in report.get("task_candidates") or []:
        if not isinstance(task, dict):
            continue
        lines.extend([
            f"- `{task.get('id', '')}` — {task.get('title', '')}",
        ])
    lines.append("")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    report = load_json(Path(args.input))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(markdown(report), encoding="utf-8")
    payload = {"ok": True, "output": str(output)}
    print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else str(output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
