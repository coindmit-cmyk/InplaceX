#!/usr/bin/env python3
"""Render validated Integrator LLM advice as Markdown."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def load_json(path: Path | None) -> dict[str, Any]:
    if not path or not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def line(value: Any) -> str:
    return str(value).replace("|", "/").replace("\n", " ").strip()


def render(advice: dict[str, Any], validation: dict[str, Any]) -> str:
    lines = [
        "# Integrator LLM Advice Summary",
        "",
        f"- Status: `{advice.get('status')}`",
        f"- Validated: `{validation.get('ok')}`",
        f"- Model: `{advice.get('model')}`",
        f"- Route: `{advice.get('route')}`",
        f"- Confidence: `{advice.get('confidence')}`",
        f"- Context hash: `{advice.get('source_context_hash')}`",
        "",
        "## Overall Summary",
        "",
        line(advice.get("overall_summary") or ""),
        "",
    ]
    issues = validation.get("issues") or []
    if issues:
        lines.extend(["## Validation Issues", "", "| Severity | Code | Path | Message |", "| --- | --- | --- | --- |"])
        for issue in issues:
            lines.append(f"| `{line(issue.get('severity'))}` | `{line(issue.get('code'))}` | `{line(issue.get('path'))}` | {line(issue.get('message'))} |")
        lines.append("")
    warnings = advice.get("warnings") or []
    if warnings:
        lines.extend(["## Model Warnings", ""])
        for warning in warnings:
            lines.append(f"- {line(warning)}")
        lines.append("")
    candidate_advice = advice.get("candidate_advice") or []
    if candidate_advice:
        lines.extend(["## Candidate Advice", "", "| Branch | Suggested | Confidence | Next Owner | Reason |", "| --- | --- | --- | --- | --- |"])
        for item in candidate_advice:
            if not isinstance(item, dict):
                continue
            lines.append(
                f"| `{line(item.get('branch'))}` | `{line(item.get('suggested_classification'))}` | "
                f"`{line(item.get('confidence'))}` | `{line(item.get('next_owner'))}` | {line(item.get('reason'))} |"
            )
        lines.append("")
    batch_suggestions = advice.get("batch_suggestions") or []
    if batch_suggestions:
        lines.extend(["## Batch Suggestions", "", "| Name | Risk | Branches | Reason |", "| --- | --- | --- | --- |"])
        for item in batch_suggestions:
            if not isinstance(item, dict):
                continue
            branches = ", ".join(str(branch) for branch in item.get("branches") or [])
            lines.append(f"| `{line(item.get('name'))}` | `{line(item.get('risk_class'))}` | `{line(branches)}` | {line(item.get('reason'))} |")
        lines.append("")
    lines.extend(
        [
            "## Advisory Boundary",
            "",
            "This file is advisory evidence only. It does not authorize merge, push, branch deletion, PR closure, lock release, task completion or direct handoff edits.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Render Integrator LLM advice summary.")
    parser.add_argument("--advice", required=True)
    parser.add_argument("--validation")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    advice_path = Path(args.advice).resolve()
    validation_path = Path(args.validation).resolve() if args.validation else advice_path.with_name("integrator_llm_advice.validation.json")
    advice = load_json(advice_path)
    validation = load_json(validation_path)
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(advice, validation), encoding="utf-8")
    print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
