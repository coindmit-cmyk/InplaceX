#!/usr/bin/env python3
"""Run a resumable, read-only local-LLM benchmark over unique remediation packets."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import threading
from typing import Any

import local_llm_adapter
import local_llm_prompt_builder
import local_llm_quality_gate
from project_paths import task_file


DEFAULT_KIND = "automation/project_rules_remediation/task_pipeline"
MIN_PROMOTION_SAMPLE_SIZE = 100


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def task_id(task: dict[str, Any]) -> str:
    return local_llm_prompt_builder.task_id(task)


def task_kind(task: dict[str, Any]) -> str:
    return str(task.get("llm_task_kind") or task.get("type") or task.get("category") or "").strip()


def contract_fingerprint(task: dict[str, Any]) -> str:
    payload = local_llm_prompt_builder.minimal_task(task)
    payload.pop("id", None)
    payload.pop("task_id", None)
    payload.pop("canonical_task_id", None)
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def select_tasks(queue: dict[str, Any], kind: str, sample_size: int, seed: str) -> list[dict[str, Any]]:
    unique: dict[str, dict[str, Any]] = {}
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict) or task_kind(task) != kind:
            continue
        tid = task_id(task)
        if tid:
            unique.setdefault(tid.upper(), task)
    ranked = sorted(
        unique.values(),
        key=lambda task: hashlib.sha256(f"{seed}:{task_id(task).upper()}".encode("utf-8")).hexdigest(),
    )
    if len(ranked) < sample_size:
        raise ValueError(f"requested {sample_size} unique packets, found {len(ranked)} for {kind}")
    return ranked[:sample_size]


def response_path(output_dir: Path, tid: str) -> Path:
    safe = "".join(char if char.isalnum() or char in "-_." else "_" for char in tid)
    return output_dir / "responses" / f"{safe}.response.json"


def validate_saved_response(project_root: Path, task: dict[str, Any], path: Path) -> dict[str, Any]:
    parsed, raw = local_llm_quality_gate.parse_response(path)
    return local_llm_quality_gate.validate(task, parsed, raw, project_root=project_root)


def run_one(
    project_root: Path,
    output_dir: Path,
    task: dict[str, Any],
    *,
    base_url: str,
    model: str,
    timeout: int,
    resume: bool,
) -> dict[str, Any]:
    isolated_task = json.loads(json.dumps(task, ensure_ascii=False))
    isolated_task["llm_triage_only"] = True
    tid = task_id(isolated_task)
    path = response_path(output_dir, tid)
    prompt = local_llm_prompt_builder.build_prompt(
        project_root,
        isolated_task,
        max_doc_chars=local_llm_prompt_builder.DEFAULT_MAX_DOC_CHARS,
        doc_mode="metadata",
        task_mode="minimal",
    )
    prompt_hash = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
    reused = resume and path.is_file()
    error: str | None = None
    if not reused:
        try:
            output = local_llm_adapter.openai_compatible_chat(
                base_url,
                "",
                model,
                prompt,
                local_llm_adapter.DEFAULT_SYSTEM_PROMPT,
                timeout,
                True,
            )
            write_json(path, {"ok": True, "backend": "openai_compatible", "model": model, "output": output})
        except Exception as exc:  # Endpoint errors belong in benchmark evidence.
            error = f"{type(exc).__name__}: {exc}"
    validation = validate_saved_response(project_root, isolated_task, path) if path.is_file() else {
        "ok": False,
        "classification": "backend_error",
        "errors": [{"code": "backend_error", "message": error or "response not written"}],
        "warnings": [],
    }
    source_report = str((isolated_task.get("input_refs") or {}).get("source_report") or "")
    source_path = Path(source_report) if source_report else None
    if source_path is not None and not source_path.is_absolute():
        source_path = project_root / source_path
    return {
        "task_id": tid,
        "contract_fingerprint": contract_fingerprint(isolated_task),
        "prompt_sha256": prompt_hash,
        "response_path": path.as_posix(),
        "response_reused": reused,
        "source_report": source_report or None,
        "source_report_exists": source_path.is_file() if source_path is not None else None,
        "ok": bool(validation.get("ok")),
        "classification": validation.get("classification"),
        "verdict": validation.get("verdict"),
        "errors": validation.get("errors") or [],
        "warnings": validation.get("warnings") or [],
    }


def summarize(
    *,
    project_root: Path,
    queue_path: Path,
    queue_sha256: str,
    output_dir: Path,
    kind: str,
    sample_size: int,
    seed: str,
    model: str,
    execute_model: bool,
    items: list[dict[str, Any]],
) -> dict[str, Any]:
    passed = sum(1 for item in items if item.get("ok"))
    unique_task_ids = len({item["task_id"] for item in items})
    unique_contract_fingerprints = len({item["contract_fingerprint"] for item in items})
    failures: dict[str, int] = {}
    for item in items:
        for error in item.get("errors") or []:
            code = str(error.get("code") or "unknown")
            failures[code] = failures.get(code, 0) + 1
    return {
        "schema_version": 1,
        "source": "local_llm_remediation_benchmark.py",
        "project_root": str(project_root),
        "queue": str(queue_path),
        "queue_sha256_before": queue_sha256,
        "queue_mutated": hashlib.sha256(queue_path.read_bytes()).hexdigest() != queue_sha256,
        "output_dir": str(output_dir),
        "task_kind": kind,
        "sample_size": sample_size,
        "selection_seed": seed,
        "model": model,
        "execute_model": execute_model,
        "counts": {
            "selected": sample_size,
            "completed": len(items),
            "passed": passed,
            "failed": len(items) - passed,
            "reused": sum(1 for item in items if item.get("response_reused")),
        },
        "diversity": {
            "unique_task_ids": unique_task_ids,
            "unique_contract_fingerprints": unique_contract_fingerprints,
            "unique_source_reports": len({item["source_report"] for item in items if item.get("source_report")}),
            "source_reports_present": sum(1 for item in items if item.get("source_report_exists") is True),
            "source_reports_missing": sum(1 for item in items if item.get("source_report_exists") is False),
        },
        "failure_codes": failures,
        "quality_target_met": len(items) == sample_size and passed == sample_size and not failures,
        "promotion_ready": (
            sample_size >= MIN_PROMOTION_SAMPLE_SIZE
            and len(items) == sample_size
            and unique_task_ids == sample_size
            and unique_contract_fingerprints == sample_size
            and passed == sample_size
            and not failures
        ),
        "items": sorted(items, key=lambda item: item["task_id"]),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue")
    parser.add_argument("--task-kind", default=DEFAULT_KIND)
    parser.add_argument("--sample-size", type=int, default=100)
    parser.add_argument("--selection-seed", default="remediation-benchmark-v1")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:11434/v1")
    parser.add_argument("--model", default="qwen2.5-coder:14b")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--execute-model", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if args.sample_size < 1 or args.concurrency < 1 or args.concurrency > 4:
        raise SystemExit("sample-size must be positive and concurrency must be 1..4")

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    output_dir = Path(args.output_dir).resolve()
    queue_sha256 = hashlib.sha256(queue_path.read_bytes()).hexdigest()
    selected = select_tasks(load_json(queue_path), args.task_kind, args.sample_size, args.selection_seed)
    manifest = {
        "task_kind": args.task_kind,
        "sample_size": args.sample_size,
        "selection_seed": args.selection_seed,
        "task_ids": [task_id(task) for task in selected],
        "queue_sha256": queue_sha256,
    }
    write_json(output_dir / "sample_manifest.json", manifest)

    if not args.execute_model:
        report = summarize(
            project_root=project_root, queue_path=queue_path, queue_sha256=queue_sha256,
            output_dir=output_dir, kind=args.task_kind, sample_size=args.sample_size,
            seed=args.selection_seed, model=args.model, execute_model=False, items=[],
        )
        write_json(output_dir / "report.json", report)
        if args.json:
            print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    items: list[dict[str, Any]] = []
    lock = threading.Lock()
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(
                run_one, project_root, output_dir, task,
                base_url=args.base_url, model=args.model, timeout=args.timeout, resume=args.resume,
            )
            for task in selected
        ]
        for future in as_completed(futures):
            item = future.result()
            with lock:
                items.append(item)
                checkpoint = summarize(
                    project_root=project_root, queue_path=queue_path, queue_sha256=queue_sha256,
                    output_dir=output_dir, kind=args.task_kind, sample_size=args.sample_size,
                    seed=args.selection_seed, model=args.model, execute_model=True, items=items,
                )
                write_json(output_dir / "report.json", checkpoint)

    report = summarize(
        project_root=project_root, queue_path=queue_path, queue_sha256=queue_sha256,
        output_dir=output_dir, kind=args.task_kind, sample_size=args.sample_size,
        seed=args.selection_seed, model=args.model, execute_model=True, items=items,
    )
    write_json(output_dir / "report.json", report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["promotion_ready"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
