#!/usr/bin/env python3
"""Read-only capability preservation checker.

The checker compares a before/after file pair or git refs for one path and reports
removed capabilities that may indicate silent replacement.
"""

from __future__ import annotations

import argparse
import ast
from dataclasses import dataclass
import difflib
from functools import lru_cache
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

STATUS_OK = "preservation_ok"
STATUS_WARNING = "preservation_warning"
STATUS_RISK = "silent_replacement_detected"

TEXT_SUFFIXES = {
    ".json", ".java", ".js", ".kt", ".md", ".py", ".rst", ".sh",
    ".toml", ".ts", ".yaml", ".yml",
}
FUNCTION_PATTERN = re.compile(
    r"^\s*(?:async\s+)?(?:def|fun|function)\s+([A-Za-z_]\w*)\b|"
    r"^\s*(?:public|private|protected|internal|static|suspend|override|final|open|abstract|inline|operator|external|native|synchronized|\s)+"
    r"\s*(?:[A-Za-z_][\w<>?,.\[\] ]*\s+)?([A-Za-z_]\w*)\s*\("
)
CLASS_PATTERN = re.compile(
    r"^\s*(?:data\s+)?(?:class|interface|object|enum(?:\s+class)?)\s+([A-Za-z_]\w*)\b"
)
FLAG_PATTERN = re.compile(r"(?<![\w-])(--[a-z][a-z0-9-]*)(?![\w-])", re.IGNORECASE)
HEADING_PATTERN = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$")
YAML_FIELD_PATTERN = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_-]*)\s*:")


@dataclass(frozen=True)
class ChangedEntry:
    status: str
    source_path: str | None
    destination_path: str | None


def git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, check=False)


def resolve_ref(repo: Path, ref: str) -> tuple[str | None, str | None]:
    result = git(repo, ["rev-parse", "--verify", f"{ref}^{{commit}}"])
    if result.returncode:
        return None, f"ref_unresolved:{ref}"
    return result.stdout.decode("ascii", errors="replace").strip(), None


def changed_entries(
    repo: Path, base_sha: str, head_sha: str
) -> tuple[list[ChangedEntry], list[str]]:
    result = git(repo, ["diff", "--name-status", "-z", "-M", "-C", base_sha, head_sha])
    if result.returncode:
        return [], ["changed_path_scan_failed"]
    fields = result.stdout.split(b"\0")
    if fields and not fields[-1]:
        fields.pop()
    entries: list[ChangedEntry] = []
    errors: list[str] = []
    index = 0
    while index < len(fields):
        try:
            status = fields[index].decode("utf-8")
        except UnicodeDecodeError:
            errors.append("changed_path_not_utf8")
            break
        index += 1
        path_count = 2 if status.startswith(("R", "C")) else 1
        if index + path_count > len(fields):
            errors.append("changed_path_scan_incomplete")
            break
        try:
            paths = [
                fields[index + offset].decode("utf-8")
                for offset in range(path_count)
            ]
        except UnicodeDecodeError:
            errors.append("changed_path_not_utf8")
            break
        index += path_count
        if status.startswith(("R", "C")):
            entries.append(ChangedEntry(status, paths[0], paths[1]))
        elif status.startswith("A"):
            entries.append(ChangedEntry(status, None, paths[0]))
        elif status.startswith("D"):
            entries.append(ChangedEntry(status, paths[0], None))
        elif status and status[0] in {"M", "T"}:
            entries.append(ChangedEntry(status, paths[0], paths[0]))
        else:
            errors.append(f"unsupported_changed_status:{status or 'empty'}")
    return entries, errors


@lru_cache(maxsize=4096)
def read_blob(repo: Path, sha: str, rel_path: str) -> tuple[str | None, str | None]:
    result = git(repo, ["show", f"{sha}:{rel_path}"])
    if result.returncode:
        return None, f"blob_unreadable:{rel_path}"
    try:
        return result.stdout.decode("utf-8"), None
    except UnicodeDecodeError:
        return None, f"blob_not_utf8:{rel_path}"


@lru_cache(maxsize=4096)
def path_exists(repo: Path, sha: str, rel_path: str) -> tuple[bool, str | None]:
    result = git(repo, ["cat-file", "-e", f"{sha}:{rel_path}"])
    if result.returncode == 0:
        return True, None
    tree = git(repo, ["ls-tree", "-z", sha, "--", rel_path])
    if tree.returncode:
        return False, f"path_presence_scan_failed:{rel_path}"
    return bool(tree.stdout), None


def json_tokens(value: Any, tokens: set[str]) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            field = str(key)
            tokens.add(f"field:{field}")
            if field in {"status", "state"} and isinstance(nested, str):
                tokens.add(f"status:{nested}")
            json_tokens(nested, tokens)
    elif isinstance(value, list):
        for nested in value:
            json_tokens(nested, tokens)


@lru_cache(maxsize=4096)
def capability_tokens(text: str, rel_path: str) -> tuple[set[str], str | None]:
    suffix = Path(rel_path).suffix.lower()
    if suffix not in TEXT_SUFFIXES:
        return set(), f"unsupported_changed_file:{rel_path}"
    tokens: set[str] = set()
    if suffix == ".json":
        try:
            json_tokens(json.loads(text), tokens)
        except json.JSONDecodeError:
            return set(), f"json_parse_failed:{rel_path}"
    if suffix == ".py":
        try:
            tree = ast.parse(text, filename=rel_path)
        except SyntaxError:
            return set(), f"python_parse_failed:{rel_path}"
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                tokens.add(f"function:{node.name}")
                if node.name == "main":
                    tokens.add("entrypoint:main")
            elif isinstance(node, ast.ClassDef):
                tokens.add(f"class:{node.name}")
    for line in text.splitlines():
        function = FUNCTION_PATTERN.search(line)
        if function:
            name = next((value for value in function.groups() if value), "")
            if name:
                tokens.add(f"function:{name}")
                if name == "main":
                    tokens.add("entrypoint:main")
        class_match = CLASS_PATTERN.match(line)
        if class_match:
            tokens.add(f"class:{class_match.group(1)}")
        heading = HEADING_PATTERN.match(line)
        if heading:
            tokens.add(f"heading:{' '.join(heading.group(1).split()).lower()}")
        yaml_field = YAML_FIELD_PATTERN.match(line)
        if yaml_field and suffix in {".yaml", ".yml"}:
            name = yaml_field.group(1)
            tokens.add(f"field:{name}")
            if name in {"status", "state"}:
                value = line.split(":", 1)[1].strip().strip("\"'")
                if value:
                    tokens.add(f"status:{value}")
    for flag in FLAG_PATTERN.findall(text):
        tokens.add(f"flag:{flag.lower()}")
    if text.startswith("#!") or re.search(
        r"if\s+__name__\s*==\s*[\"']__main__[\"']", text
    ):
        tokens.add("entrypoint:executable")
    return tokens, None


def append_unique(items: list[dict[str, Any]], value: dict[str, Any]) -> None:
    if value not in items:
        items.append(value)


def tokens_at(
    repo: Path,
    sha: str,
    rel_path: str,
    report: dict[str, Any],
    *,
    missing_ok: bool = False,
) -> set[str] | None:
    exists, presence_error = path_exists(repo, sha, rel_path)
    if presence_error:
        report["errors"].append(presence_error)
        return None
    if not exists:
        if missing_ok:
            return set()
        report["errors"].append(f"blob_unreadable:{rel_path}")
        return None
    text, read_error = read_blob(repo, sha, rel_path)
    if read_error:
        report["errors"].append(read_error)
        return None
    tokens, token_error = capability_tokens(text or "", rel_path)
    if token_error:
        report["errors"].append(token_error)
        return None
    return tokens


def compare_entries(
    repo: Path,
    base_sha: str,
    head_sha: str,
    entries: list[ChangedEntry],
    report: dict[str, Any],
) -> None:
    before: dict[str, set[str]] = {}
    after: dict[str, set[str]] = {}
    destination_before: dict[str, set[str]] = {}
    explicit_destinations: dict[str, str] = {}

    for entry in entries:
        if entry.source_path:
            source_tokens = tokens_at(repo, base_sha, entry.source_path, report)
            if source_tokens is not None:
                before[entry.source_path] = source_tokens
            source_in_head, presence_error = path_exists(
                repo, head_sha, entry.source_path
            )
            if presence_error:
                report["errors"].append(presence_error)
            if not source_in_head and entry.destination_path is None:
                append_unique(
                    report["potential_removals"],
                    {"kind": "file_removed", "source_path": entry.source_path},
                )
            if entry.status.startswith(("R", "C")) and entry.destination_path:
                explicit_destinations[entry.source_path] = entry.destination_path

        if entry.destination_path:
            destination_tokens = tokens_at(
                repo, head_sha, entry.destination_path, report
            )
            if destination_tokens is not None:
                after[entry.destination_path] = destination_tokens
            base_tokens = tokens_at(
                repo,
                base_sha,
                entry.destination_path,
                report,
                missing_ok=True,
            )
            if base_tokens is not None:
                destination_before[entry.destination_path] = base_tokens

    if report["errors"]:
        return

    newly_added = {
        path: tokens - destination_before.get(path, set())
        for path, tokens in after.items()
    }
    for source_path in sorted(before):
        source_tokens = before[source_path]
        source_after_tokens = after.get(source_path, set())
        explicit_destination = explicit_destinations.get(source_path)
        for token in sorted(source_tokens):
            if token in source_after_tokens:
                continue

            moved_to: list[str] = []
            if (
                explicit_destination
                and token in after.get(explicit_destination, set())
            ):
                moved_to.append(explicit_destination)
            moved_to.extend(
                path
                for path in sorted(newly_added)
                if path != source_path
                and path != explicit_destination
                and token in newly_added[path]
            )
            if moved_to:
                append_unique(
                    report["preserved_moves"],
                    {
                        "capability": token,
                        "source_path": source_path,
                        "destination_paths": sorted(set(moved_to)),
                    },
                )
            else:
                append_unique(
                    report["potential_removals"],
                    {
                        "kind": "capability_removed",
                        "source_path": source_path,
                        "capability": token,
                    },
                )

        if (
            not source_tokens
            and (
                source_path in after
                or source_path in explicit_destinations
            )
        ):
            append_unique(
                report["potential_removals"],
                {
                    "kind": "comparison_evidence_missing",
                    "source_path": source_path,
                },
            )


def finish(report: dict[str, Any]) -> dict[str, Any]:
    report["errors"] = sorted(set(report["errors"]))
    report["potential_removals"] = sorted(
        report["potential_removals"],
        key=lambda item: (
            item.get("kind", ""),
            item.get("source_path", ""),
            item.get("capability", ""),
        ),
    )
    report["preserved_moves"] = sorted(
        report["preserved_moves"],
        key=lambda item: (
            item["capability"],
            item["source_path"],
            item["destination_paths"],
        ),
    )
    if report["errors"]:
        report.update(status="error", exit_decision="error", ok=False)
    elif report["potential_removals"]:
        report.update(
            status="review_required", exit_decision="review_required", ok=False
        )
    else:
        report.update(status="preserved", exit_decision="allow", ok=True)
    return report


def result_for(
    repo: Path, base_ref: str, head_ref: str, all_changed: bool
) -> dict[str, Any]:
    base_sha, base_error = resolve_ref(repo, base_ref)
    head_sha, head_error = resolve_ref(repo, head_ref)
    report: dict[str, Any] = {
        "all_changed": all_changed,
        "base_ref": base_ref,
        "base_sha": base_sha,
        "changed_paths": [],
        "errors": [error for error in (base_error, head_error) if error],
        "exit_decision": "error",
        "head_ref": head_ref,
        "head_sha": head_sha,
        "ok": False,
        "potential_removals": [],
        "preserved_moves": [],
        "status": "error",
    }
    if not all_changed:
        report["errors"].append("all_changed_required")
    if report["errors"]:
        return finish(report)
    assert base_sha and head_sha
    entries, scan_errors = changed_entries(repo, base_sha, head_sha)
    report["errors"].extend(scan_errors)
    report["changed_paths"] = sorted(
        {
            path
            for entry in entries
            for path in (entry.source_path, entry.destination_path)
            if path
        }
    )
    if not report["errors"]:
        compare_entries(repo, base_sha, head_sha, entries, report)
    return finish(report)


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
        text=True,
        encoding="utf-8",
    )
    return result.stdout if result.returncode == 0 else ""


def git_changed_paths(base_ref: str, head_ref: str) -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACDMRT", base_ref, head_ref, "--"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    return [line.strip().replace("\\", "/") for line in result.stdout.splitlines() if line.strip()]


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


def compare_refs(base_ref: str, head_ref: str, paths: list[str] | None = None) -> dict[str, Any]:
    selected_paths = paths or git_changed_paths(base_ref, head_ref)
    reports = [
        compare_text(
            git_show_optional(base_ref, path),
            git_show_optional(head_ref, path),
            path_hint=path,
        )
        for path in selected_paths
    ]
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
        if args.all_changed:
            report = result_for(Path.cwd(), args.base_ref, args.head_ref, True)
        else:
            before = git_show(args.base_ref, args.path)
            after = git_show(args.head_ref, args.path)
            report = compare_text(before, after, path_hint=args.path)
    else:
        if not args.after:
            raise SystemExit("--before requires --after")
        before = read_text(Path(args.before))
        after = read_text(Path(args.after))
        report = compare_text(before, after, path_hint=args.path or args.after)
    if args.json:
        print(
            json.dumps(
                report, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            )
        )
    else:
        print(report["status"])
        for item in report.get("removed_capabilities", []):
            print(f"removed: {item}")
    if report["status"] in {"preserved", STATUS_OK}:
        return 0
    if report["status"] in {"review_required", STATUS_WARNING}:
        return 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
