#!/usr/bin/env python3
"""Call a local advisory-only LLM for Auto Integrator assistance."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from validate_integrator_llm_advice import validate


SYSTEM_PROMPT = """You are LOCAL_INTEGRATOR_ASSISTANT for ai-project-agent.
You are advisory-only. Scripts and Auto Integrator decide durable state.
You must not merge, push, delete branches, close PRs, release locks, mark tasks done,
or edit task queues, locks, events, branches, PRs or handoff files.
Use only the provided compact redacted context.
Return strict JSON only. No Markdown fences.
Required JSON fields:
schema_version, created_at, source_context_hash, model, route, status,
overall_summary, confidence, candidate_advice, batch_suggestions, warnings,
forbidden_actions_detected.
Route must be LOCAL_INTEGRATOR_ASSISTANT.
Candidate advice may explain blockers and suggest next owners, but it is non-binding.
"""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def adapter_path(value: str) -> Path:
    path = Path(value)
    if path.exists():
        return path.resolve()
    return (Path(__file__).resolve().parent / value).resolve()


def extract_json_object(raw: str) -> dict[str, Any] | None:
    text = raw.strip()
    if text.startswith("```"):
        lines = [line for line in text.splitlines() if not line.strip().startswith("```")]
        text = "\n".join(lines).strip()
    try:
        data = json.loads(text)
        return data if isinstance(data, dict) else None
    except json.JSONDecodeError:
        pass
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        try:
            data = json.loads(text[start : end + 1])
            return data if isinstance(data, dict) else None
        except json.JSONDecodeError:
            return None
    return None


def dry_run_advice(context: dict[str, Any], model: str) -> dict[str, Any]:
    summary = context.get("summary") if isinstance(context.get("summary"), dict) else {}
    routes = summary.get("excluded_route_counts") if isinstance(summary.get("excluded_route_counts"), dict) else {}
    bits: list[str] = []
    if summary.get("batch_included_count"):
        bits.append(f"{summary['batch_included_count']} item(s) are included in the deterministic integration batch.")
    if summary.get("batch_excluded_count"):
        bits.append(f"{summary['batch_excluded_count']} item(s) are excluded and should stay out of Finalizer packages.")
    if routes:
        top = ", ".join(f"{key}={value}" for key, value in sorted(routes.items(), key=lambda item: str(item[0]))[:8])
        bits.append(f"Excluded route counts: {top}.")
    if not bits:
        bits.append("No obvious Integrator blockers were present in the compact context.")
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "source_context_hash": context.get("source_context_hash", ""),
        "model": model,
        "route": "LOCAL_INTEGRATOR_ASSISTANT",
        "status": "dry_run",
        "overall_summary": " ".join(bits),
        "confidence": 0.5,
        "candidate_advice": [],
        "batch_suggestions": [],
        "warnings": ["dry_run advice; no local model call was performed"],
        "forbidden_actions_detected": [],
    }


def build_prompt(context: dict[str, Any]) -> str:
    return json.dumps(
        {
            "instruction": "Return advisory-only JSON for Auto Integrator. Do not propose forbidden actions.",
            "context": context,
        },
        ensure_ascii=False,
        indent=2,
    )


def call_adapter(args: argparse.Namespace, prompt_file: Path) -> str:
    path = adapter_path(args.adapter)
    cmd = [
        sys.executable,
        str(path),
        "--prompt-file",
        str(prompt_file),
        "--model",
        args.model,
        "--base-url",
        args.base_url,
        "--system-prompt",
        SYSTEM_PROMPT,
        "--timeout",
        str(args.timeout),
        "--json",
    ]
    if args.config:
        cmd.extend(["--config", args.config])
    proc = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or proc.stdout.strip() or f"adapter exited {proc.returncode}")
    wrapper = json.loads(proc.stdout)
    output = wrapper.get("output")
    if not isinstance(output, str):
        raise RuntimeError("local_llm_adapter did not return text output")
    return output


def invalid_advice(context: dict[str, Any], model: str, reason: str, raw: str = "") -> dict[str, Any]:
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "source_context_hash": context.get("source_context_hash", ""),
        "model": model,
        "route": "LOCAL_INTEGRATOR_ASSISTANT",
        "status": "invalid",
        "overall_summary": reason,
        "confidence": 0.0,
        "candidate_advice": [],
        "batch_suggestions": [],
        "warnings": [reason, raw[:1000]] if raw else [reason],
        "forbidden_actions_detected": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Advisory-only local LLM assistant for Auto Integrator.")
    parser.add_argument("--context", required=True)
    parser.add_argument("--output", help="Defaults to <context dir>/integrator_llm_advice.json")
    parser.add_argument("--validation-output")
    parser.add_argument("--adapter", default="local_llm_adapter.py")
    parser.add_argument("--config")
    parser.add_argument("--base-url", default="http://127.0.0.1:11434/v1")
    parser.add_argument("--model", default="Qwen2.5-Coder-7B-Instruct-Q4_K_M")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    context_path = Path(args.context).resolve()
    context = load_json(context_path)
    output = Path(args.output).resolve() if args.output else context_path.with_name("integrator_llm_advice.json")

    if args.dry_run:
        advice = dry_run_advice(context, args.model)
    else:
        prompt_file = output.with_suffix(".prompt.json")
        prompt_file.write_text(build_prompt(context), encoding="utf-8")
        try:
            raw = call_adapter(args, prompt_file)
            advice = extract_json_object(raw) or invalid_advice(context, args.model, "model did not return a JSON object", raw)
        except (OSError, RuntimeError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
            advice = invalid_advice(context, args.model, f"local model call failed: {exc}")

    advice.setdefault("schema_version", 1)
    advice.setdefault("created_at", utc_now())
    advice.setdefault("source_context_hash", context.get("source_context_hash", ""))
    advice.setdefault("model", args.model)
    advice.setdefault("route", "LOCAL_INTEGRATOR_ASSISTANT")
    advice.setdefault("status", "invalid")
    advice.setdefault("candidate_advice", [])
    advice.setdefault("batch_suggestions", [])
    advice.setdefault("warnings", [])
    advice.setdefault("forbidden_actions_detected", [])

    report = validate(context, advice)
    write_json(output, advice)
    validation_output = Path(args.validation_output).resolve() if args.validation_output else output.with_name("integrator_llm_advice.validation.json")
    write_json(validation_output, report)

    result = {
        "advice": str(output),
        "validation": str(validation_output),
        "valid": report["ok"],
        "status": advice.get("status"),
        "confidence": advice.get("confidence"),
    }
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"status: {result['status']}")
        print(f"confidence: {result['confidence']}")
        print(f"valid: {result['valid']}")
        print(f"advice: {output}")
        print(f"validation: {validation_output}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
