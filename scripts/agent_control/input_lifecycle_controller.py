#!/usr/bin/env python3
"""Run the bounded Project Input lifecycle without replacing existing authorities."""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import json
import os
from pathlib import Path
from typing import Any, Iterator


INPUT_REL = Path("AiStudio/Project_state/input")
ACTIVE_NAME = "ACTIVE_REGISTRY.json"
HISTORY_NAME = "HISTORY.jsonl"


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def input_root(project_root: Path) -> Path:
    return project_root / INPUT_REL


def read_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json_atomic(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def load_active(project_root: Path) -> dict[str, Any]:
    path = input_root(project_root) / ACTIVE_NAME
    if not path.is_file():
        return {
            "$schema": "../../../schemas/agent-control/project_input_active_registry.schema.json",
            "schema_version": "1.0",
            "records": [],
        }
    data = read_json(path)
    if not isinstance(data.get("records"), list):
        raise ValueError("ACTIVE_REGISTRY.json records must be an array")
    return data


def save_active(project_root: Path, registry: dict[str, Any]) -> None:
    records = registry.get("records") or []
    registry["records"] = sorted(records, key=lambda item: str(item.get("package_id") or ""))
    write_json_atomic(input_root(project_root) / ACTIVE_NAME, registry)


def history_records(project_root: Path) -> list[dict[str, Any]]:
    path = input_root(project_root) / HISTORY_NAME
    if not path.is_file():
        return []
    records: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            records.append(json.loads(line))
    return records


def find_record(registry: dict[str, Any], package_id: str) -> dict[str, Any] | None:
    return next((item for item in registry.get("records") or [] if item.get("package_id") == package_id), None)


def find_ref(record: dict[str, Any], prefix: str) -> str:
    for value in record.get("downstream_refs") or []:
        if str(value).startswith(prefix):
            return str(value)[len(prefix) :]
    return ""


def set_ref(record: dict[str, Any], prefix: str, value: str) -> None:
    refs = [str(item) for item in record.get("downstream_refs") or [] if not str(item).startswith(prefix)]
    refs.append(f"{prefix}{value}")
    record["downstream_refs"] = sorted(set(refs))


def package_source(package_dir: Path, root: Path) -> str:
    parts = package_dir.relative_to(input_root(root)).parts
    if len(parts) == 2 and parts[0] in {"GPT", "Codex"}:
        return parts[0]
    if len(parts) == 3 and parts[0] == "Other":
        return parts[1]
    raise ValueError(f"invalid input package location: {package_dir}")


def load_manifest(package_dir: Path, root: Path) -> dict[str, Any]:
    manifest = read_json(package_dir / "manifest.json")
    package_id = str(manifest.get("package_id") or "")
    if package_id != package_dir.name or not package_id.startswith("PR-"):
        raise ValueError(f"package id does not match directory: {package_dir}")
    if manifest.get("source") != package_source(package_dir, root):
        raise ValueError(f"manifest source does not match directory: {package_dir}")
    source_pr = manifest.get("source_pr") or {}
    if not isinstance(source_pr.get("number"), int) or source_pr.get("target_branch") != "develop":
        raise ValueError(f"merged package requires a numbered develop PR: {package_dir}")
    authority = manifest.get("authority") or {}
    if any(authority.get(field) is not False for field in ("execution_authorized", "worker_ready", "merge_authorized")):
        raise ValueError(f"input package cannot grant authority: {package_dir}")
    return manifest


def package_dirs(project_root: Path) -> list[Path]:
    root = input_root(project_root)
    if not root.is_dir():
        return []
    packages: list[Path] = []
    for manifest_path in root.rglob("manifest.json"):
        package_dir = manifest_path.parent
        try:
            package_source(package_dir, project_root)
        except ValueError:
            continue
        packages.append(package_dir)
    return sorted(packages)


def git_head(project_root: Path) -> str:
    import subprocess

    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=project_root,
        check=False,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip() if result.returncode == 0 else ""


@contextlib.contextmanager
def package_lease(project_root: Path, package_id: str) -> Iterator[None]:
    lease_dir = project_root / "runtime" / "agent-control" / "input-lifecycle"
    lease_dir.mkdir(parents=True, exist_ok=True)
    lease_path = lease_dir / f"{package_id}.lock"
    try:
        handle = lease_path.open("x", encoding="utf-8")
    except FileExistsError as exc:
        raise RuntimeError(f"package already leased: {package_id}") from exc
    try:
        handle.write(json.dumps({"package_id": package_id, "created_at": utc_now()}) + "\n")
        handle.close()
        yield
    finally:
        handle.close()
        lease_path.unlink(missing_ok=True)


def move_to_history(
    project_root: Path,
    package_id: str,
    *,
    terminal_state: str,
    merge_commit: str,
    apply: bool,
) -> dict[str, Any]:
    registry = load_active(project_root)
    record = find_record(registry, package_id)
    existing = next((item for item in history_records(project_root) if item.get("package_id") == package_id), None)
    if existing:
        if apply and record:
            registry["records"] = [item for item in registry["records"] if item.get("package_id") != package_id]
            save_active(project_root, registry)
        return {"ok": True, "duplicate": True, "record": existing}
    if not record:
        return {"ok": False, "reason": "active_record_missing"}
    history = {
        "schema_version": "1.0",
        "package_id": package_id,
        "terminal_state": terminal_state,
        "completed_at": utc_now(),
        "evidence": {
            "source_pr": record["source_pr"],
            "merge_commit": merge_commit,
            "downstream_refs": record.get("downstream_refs") or [],
        },
    }
    if not apply:
        return {"ok": True, "applied": False, "record": history}

    input_dir = input_root(project_root)
    history_path = input_dir / HISTORY_NAME
    active_path = input_dir / ACTIVE_NAME
    transaction_path = input_dir / f".finalize-{package_id}.json"
    transaction_path.write_text(json.dumps(history, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    history_text = history_path.read_text(encoding="utf-8") if history_path.exists() else ""
    history_tmp = history_path.with_suffix(".jsonl.tmp")
    history_tmp.write_text(history_text.rstrip() + ("\n" if history_text.strip() else "") + json.dumps(history, ensure_ascii=False) + "\n", encoding="utf-8")
    registry["records"] = [item for item in registry["records"] if item.get("package_id") != package_id]
    active_tmp = active_path.with_suffix(".json.tmp")
    active_tmp.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(history_tmp, history_path)
    os.replace(active_tmp, active_path)
    transaction_path.unlink(missing_ok=True)
    return {"ok": True, "applied": True, "record": history}


def run_cycle(
    project_root: Path,
    *,
    apply: bool,
    processing_limit: int,
    discover_github: bool,
    repository: str,
) -> dict[str, Any]:
    import input_direct_processor
    import input_outcome_finalizer
    import input_package_analyzer
    import input_pr_collector
    import input_task_bridge

    report: dict[str, Any] = {
        "schema_version": "1.0",
        "generated_at": utc_now(),
        "apply": apply,
        "capture": input_pr_collector.collect(
            project_root,
            apply=apply,
            discover_github=discover_github,
            repository=repository,
        ),
        "processed": [],
    }
    package_ids = [
        str(item.get("package_id"))
        for item in load_active(project_root).get("records") or []
        if item.get("state") in {"awaiting_analysis", "awaiting_execution", "awaiting_finalization"}
    ][: max(0, processing_limit)]
    for package_id in package_ids:
        with package_lease(project_root, package_id):
            record = find_record(load_active(project_root), package_id) or {}
            steps: list[dict[str, Any]] = []
            if record.get("state") == "awaiting_analysis":
                steps.append(input_package_analyzer.analyze(project_root, package_id, apply=apply))
                record = find_record(load_active(project_root), package_id) or record
            if record.get("state") == "awaiting_execution":
                route = find_ref(record, "decision:")
                if route == "direct_processing":
                    steps.append(input_direct_processor.process(project_root, package_id, apply=apply))
                elif route == "delegate_to_dispatcher":
                    steps.append(input_task_bridge.bridge(project_root, package_id, apply=apply))
                record = find_record(load_active(project_root), package_id) or record
            if record.get("state") == "awaiting_finalization":
                steps.append(input_outcome_finalizer.finalize(project_root, package_id, apply=apply))
            report["processed"].append({"package_id": package_id, "steps": steps})
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path("."))
    parser.add_argument("--processing-limit", type=int, default=1)
    parser.add_argument("--discover-github", action="store_true")
    parser.add_argument("--repository", default="")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    report = run_cycle(
        args.project_root.resolve(),
        apply=args.apply,
        processing_limit=args.processing_limit,
        discover_github=args.discover_github,
        repository=args.repository,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
