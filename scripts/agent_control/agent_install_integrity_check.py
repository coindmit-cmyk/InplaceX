#!/usr/bin/env python3
"""Validate that an installed agent_control script set is internally complete."""

from __future__ import annotations

import argparse
import ast
import importlib.util
import json
import py_compile
from pathlib import Path
from typing import Any


DEFAULT_OPTIONAL_IMPORTS = {"fcntl", "msvcrt", "pexpect", "psutil", "resource", "scripts"}
IGNORED_DIRS = {"__pycache__"}
REQUIRED_LOCAL_IMPORTS = {"validators"}


def iter_python_files(scripts_dir: Path) -> list[Path]:
    return sorted(
        path
        for path in scripts_dir.rglob("*.py")
        if path.is_file() and not any(part in IGNORED_DIRS for part in path.parts)
    )


def rel_file(scripts_dir: Path, path: Path) -> str:
    try:
        return path.relative_to(scripts_dir).as_posix()
    except ValueError:
        return path.name


def local_modules(scripts_dir: Path) -> set[str]:
    modules = {
        path.stem
        for path in iter_python_files(scripts_dir)
        if path.stem != "__init__"
    }
    modules.update(
        path.parent.name
        for path in iter_python_files(scripts_dir)
        if path.name == "__init__.py"
    )
    return modules


def imported_modules(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8-sig"), filename=str(path))
    result: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            module = node.module.split(".", 1)[0]
            result.append(module)
        elif isinstance(node, ast.Import):
            for alias in node.names:
                module = alias.name.split(".", 1)[0]
                result.append(module)
    return sorted(set(result))


def scan_imports(scripts_dir: Path, optional_imports: set[str] | None = None) -> tuple[list[dict[str, Any]], list[str]]:
    optional = set(DEFAULT_OPTIONAL_IMPORTS)
    optional.update(optional_imports or set())
    present = local_modules(scripts_dir)
    rows: list[dict[str, Any]] = []
    errors: list[str] = []
    for path in iter_python_files(scripts_dir):
        relative = rel_file(scripts_dir, path)
        try:
            imports = imported_modules(path)
        except SyntaxError as exc:
            errors.append(f"{relative}: syntax parse failed: {exc}")
            continue
        missing = [
            module
            for module in imports
            if module not in present
            and module not in optional
            and (module in REQUIRED_LOCAL_IMPORTS or importlib.util.find_spec(module) is None)
        ]
        errors.extend(f"{relative}: missing import module: {module}" for module in missing)
        rows.append({"file": relative, "imports": imports, "missing_imports": missing})
    return rows, errors


def compile_files(scripts_dir: Path) -> list[str]:
    errors: list[str] = []
    for path in iter_python_files(scripts_dir):
        relative = rel_file(scripts_dir, path)
        try:
            py_compile.compile(str(path), doraise=True)
        except py_compile.PyCompileError as exc:
            errors.append(f"{relative}: py_compile failed: {exc.msg}")
    return errors


def build_report(scripts_dir: Path, compile_check: bool = False, optional_imports: set[str] | None = None) -> dict[str, Any]:
    scripts_dir = scripts_dir.resolve()
    if not scripts_dir.exists():
        return {
            "ok": False,
            "scripts_dir": str(scripts_dir),
            "errors": [f"scripts_dir does not exist: {scripts_dir}"],
            "files_checked": 0,
            "imports": [],
        }
    imports, errors = scan_imports(scripts_dir, optional_imports=optional_imports)
    if compile_check:
        errors.extend(compile_files(scripts_dir))
    return {
        "ok": not errors,
        "scripts_dir": str(scripts_dir),
        "errors": errors,
        "files_checked": len(imports),
        "imports": imports,
        "compile_check": compile_check,
        "optional_imports": sorted(set(DEFAULT_OPTIONAL_IMPORTS) | set(optional_imports or set())),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scripts-dir", default="scripts/agent_control")
    parser.add_argument("--compile", action="store_true", help="Also run py_compile for every installed script.")
    parser.add_argument("--allow-missing-import", action="append", default=[], help="Treat this optional module as non-fatal when unavailable.")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = build_report(Path(args.scripts_dir), compile_check=args.compile, optional_imports=set(args.allow_missing_import))
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"scripts_dir: {report['scripts_dir']}")
        print(f"files_checked: {report['files_checked']}")
        print(f"ok: {str(report['ok']).lower()}")
        for error in report["errors"]:
            print(f"ERROR {error}")
    return 0 if report["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
