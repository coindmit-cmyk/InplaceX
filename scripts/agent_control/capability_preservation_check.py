#!/usr/bin/env python3
"""Read-only capability preservation checker.

The checker compares a before/after file pair or git refs for one path and reports
removed capabilities that may indicate silent replacement.
"""

from __future__ import annotations

import argparse
import ast
import difflib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

STATUS_OK = "preservation_ok"
STATUS_WARNING = "preservation_warning"
STATUS_RISK = "silent_replacement_detected"


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def git_show(ref: str, path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    return result.stdout


def git_show_optional(ref: str, path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout.decode("utf-8", errors="replace") if result.returncode == 0 else ""


def git_path_type(ref: str, path: str) -> str | None:
    result = subprocess.run(
        ["git", "cat-file", "-t", f"{ref}:{path}"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        return None
    return result.stdout.decode("ascii", errors="replace").strip() or None


def git_path_exists(ref: str, path: str) -> bool:
    return git_path_type(ref, path) is not None


def git_ref_exists(ref: str) -> bool:
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def git_changed_paths(base_ref: str, head_ref: str) -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "-z", "--diff-filter=ACDMRT", base_ref, head_ref, "--"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return [
        raw_path.decode("utf-8", errors="replace")
        for raw_path in result.stdout.split(b"\0")
        if raw_path
    ]


def capability(kind: str, name: str) -> str:
    return f"{kind}:{name}"


def extract_python(text: str) -> set[str]:
    caps: set[str] = set()
    try:
        tree = ast.parse(text)
    except SyntaxError:
        tree = None
    if tree is not None:
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                caps.add(capability("function", node.name))
            elif isinstance(node, ast.ClassDef):
                caps.add(capability("class", node.name))
                for child in node.body:
                    if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                        caps.add(capability("method", f"{node.name}.{child.name}"))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            func = node.func
            func_name = func.attr if isinstance(func, ast.Attribute) else ""
            if func_name == "add_argument":
                for arg in node.args:
                    if isinstance(arg, ast.Constant) and isinstance(arg.value, str) and arg.value.startswith("-"):
                        caps.add(capability("cli_arg", arg.value))
            elif func_name == "add_parser" and node.args:
                arg = node.args[0]
                if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                    caps.add(capability("cli_command", arg.value))
    for match in re.finditer(r"add_argument\((.*?)\)", text, flags=re.DOTALL):
        for arg in re.findall(r"['\"](--?[A-Za-z0-9][A-Za-z0-9_-]*)['\"]", match.group(1)):
            caps.add(capability("cli_arg", arg))
    for match in re.finditer(r"\.add_parser\(\s*['\"]([^'\"]+)['\"]", text):
        caps.add(capability("cli_command", match.group(1)))
    return caps


def walk_json_keys(value: Any, prefix: str = "") -> set[str]:
    caps: set[str] = set()
    if isinstance(value, dict):
        for key, item in value.items():
            path = f"{prefix}.{key}" if prefix else str(key)
            caps.add(capability("json_key", path))
            if key == "properties" and isinstance(item, dict):
                for prop in item:
                    caps.add(capability("schema_field", prop))
                    scoped_prop = f"{path}.{prop}" if path else prop
                    caps.add(capability("schema_field_path", scoped_prop))
            if key in {"enum", "status", "statuses", "state", "states"} and isinstance(item, list):
                for enum_item in item:
                    if isinstance(enum_item, str):
                        caps.add(capability("state_or_enum", enum_item))
            if key in {"status", "state", "type"} and isinstance(item, str):
                caps.add(capability("state_or_enum", item))
            caps.update(walk_json_keys(item, path))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            caps.update(walk_json_keys(item, f"{prefix}[{index}]"))
    return caps


def extract_json(text: str) -> set[str]:
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return set()
    return walk_json_keys(data)


def extract_markdown(text: str) -> set[str]:
    caps: set[str] = set()
    for line in text.splitlines():
        match = re.match(r"^(#{1,6})\s+(.+?)\s*$", line)
        if match:
            level = len(match.group(1))
            title = re.sub(r"\s+", " ", match.group(2)).strip()
            caps.add(capability("md_heading", f"h{level}:{title}"))
    for match in re.finditer(r"`([A-Za-z0-9_.-]+)`", text):
        token = match.group(1)
        if any(marker in token for marker in (".py", ".json", ".md")):
            caps.add(capability("doc_ref", token))
    return caps


def extract_capabilities(text: str, *, path_hint: str = "") -> set[str]:
    suffix = Path(path_hint).suffix.lower()
    caps: set[str] = set()
    if suffix == ".py" or "def " in text or "class " in text:
        caps.update(extract_python(text))
    if suffix in {".json", ".schema"} or text.lstrip().startswith(("{", "[")):
        caps.update(extract_json(text))
    if suffix in {".md", ".markdown"} or "#" in text[:2000]:
        caps.update(extract_markdown(text))
    return caps


def line_ratio(before: str, after: str) -> float:
    return difflib.SequenceMatcher(a=before.splitlines(), b=after.splitlines()).ratio()


def compare_text(before: str, after: str, *, path_hint: str = "") -> dict[str, Any]:
    before_caps = extract_capabilities(before, path_hint=path_hint)
    after_caps = extract_capabilities(after, path_hint=path_hint)
    removed = sorted(before_caps - after_caps)
    added = sorted(after_caps - before_caps)
    ratio = line_ratio(before, after)
    rewrite_risk = ratio < 0.55 and len(before.strip()) > 200 and len(after.strip()) > 200
    if removed and rewrite_risk:
        status = STATUS_RISK
    elif removed:
        status = STATUS_WARNING
    else:
        status = STATUS_OK
    return {
        "status": status,
        "path": path_hint,
        "before_capability_count": len(before_caps),
        "after_capability_count": len(after_caps),
        "removed_capabilities": removed,
        "added_capabilities": added,
        "rewrite_similarity_ratio": round(ratio, 4),
        "rewrite_risk": rewrite_risk,
        "policy_findings": policy_findings(removed, rewrite_risk),
        "required_evidence": required_evidence(removed, rewrite_risk),
    }


def file_removed_report(path: str) -> dict[str, Any]:
    removed = [capability("file_removed", path)]
    return {
        "status": STATUS_WARNING,
        "path": path,
        "before_capability_count": 0,
        "after_capability_count": 0,
        "removed_capabilities": removed,
        "added_capabilities": [],
        "rewrite_similarity_ratio": 0.0,
        "rewrite_risk": False,
        "file_removed": True,
        "policy_findings": policy_findings(removed, False),
        "required_evidence": required_evidence(removed, False),
    }


def input_error_report(path: str, finding: str) -> dict[str, Any]:
    return {
        "status": STATUS_WARNING,
        "path": path,
        "before_capability_count": 0,
        "after_capability_count": 0,
        "removed_capabilities": [],
        "added_capabilities": [],
        "rewrite_similarity_ratio": 0.0,
        "rewrite_risk": False,
        "input_error": finding,
        "policy_findings": [finding],
        "required_evidence": ["provide valid refs and a path present in at least one ref"],
    }


def compare_ref_path(base_ref: str, head_ref: str, path: str) -> dict[str, Any]:
    base_type = git_path_type(base_ref, path)
    head_type = git_path_type(head_ref, path)
    if base_type is None and head_type is None:
        return input_error_report(path, "path_missing_from_both_refs")
    before = git_show_optional(base_ref, path)
    after = git_show_optional(head_ref, path)
    if base_type == "blob" and head_type != "blob":
        return file_removed_report(path)
    return compare_text(before, after, path_hint=path)


def compare_refs(base_ref: str, head_ref: str, paths: list[str] | None = None) -> dict[str, Any]:
    selected_paths = paths or git_changed_paths(base_ref, head_ref)
    reports = [compare_ref_path(base_ref, head_ref, path) for path in selected_paths]
    risky = [report for report in reports if report["status"] == STATUS_RISK]
    warnings = [report for report in reports if report["status"] == STATUS_WARNING]
    status = STATUS_RISK if risky else (STATUS_WARNING if warnings else STATUS_OK)
    return {
        "status": status,
        "base_ref": base_ref,
        "head_ref": head_ref,
        "checked_path_count": len(reports),
        "risk_path_count": len(risky),
        "warning_path_count": len(warnings),
        "removed_capability_count": sum(len(report["removed_capabilities"]) for report in reports),
        "reports": reports,
        "policy_findings": sorted({finding for report in reports for finding in report["policy_findings"]}),
        "required_evidence": sorted({item for report in reports for item in report["required_evidence"]}),
    }


def policy_findings(removed: list[str], rewrite_risk: bool) -> list[str]:
    findings: list[str] = []
    if removed:
        findings.append("replacement_scope_required")
        findings.append("migration_note_required")
    if removed and rewrite_risk:
        findings.insert(0, "silent_replacement_detected")
    return findings


def required_evidence(removed: list[str], rewrite_risk: bool) -> list[str]:
    evidence: list[str] = []
    if removed:
        evidence.append("explain removed capabilities or restore them")
        evidence.append("provide explicit replacement/migration/cleanup scope")
    if rewrite_risk:
        evidence.append("justify broad rewrite or split into additive patch")
    return evidence


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--before")
    source.add_argument("--base-ref")
    parser.add_argument("--after")
    parser.add_argument("--head-ref")
    parser.add_argument("--path", help="Repository path when using --base-ref/--head-ref, or path hint for extraction.")
    parser.add_argument("--all-changed", action="store_true", help="Compare every changed path between --base-ref and --head-ref.")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    if args.base_ref:
        if not args.head_ref or (not args.path and not args.all_changed):
            raise SystemExit("--base-ref requires --head-ref and either --path or --all-changed")
        invalid_refs = [ref for ref in (args.base_ref, args.head_ref) if not git_ref_exists(ref)]
        if invalid_refs:
            report = input_error_report(args.path or "", f"invalid_git_ref:{','.join(invalid_refs)}")
        elif args.all_changed:
            report = compare_refs(args.base_ref, args.head_ref)
        else:
            report = compare_ref_path(args.base_ref, args.head_ref, args.path)
    else:
        if not args.after:
            raise SystemExit("--before requires --after")
        before = read_text(Path(args.before))
        after = read_text(Path(args.after))
        report = compare_text(before, after, path_hint=args.path or args.after)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(report["status"])
        removed = report.get("removed_capabilities")
        if isinstance(removed, list):
            for item in removed:
                print(f"removed: {item}")
        else:
            for path_report in report.get("reports") or []:
                if not isinstance(path_report, dict):
                    continue
                path = str(path_report.get("path") or "")
                for item in path_report.get("removed_capabilities") or []:
                    prefix = f"removed[{path}]" if path else "removed"
                    print(f"{prefix}: {item}")
    return 0 if report["status"] == STATUS_OK else 2


if __name__ == "__main__":
    raise SystemExit(main())
