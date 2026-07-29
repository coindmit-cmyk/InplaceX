#!/usr/bin/env python3
"""Build deterministic, model-aware implementation slices for a large repository PR."""

from __future__ import annotations

import argparse
import json
import re
import shlex
from collections import defaultdict
from pathlib import Path, PurePosixPath
from typing import Any


SCHEMA_VERSION = "1.0"
PLANNER_ID = "repository_pr_decomposition_planner"
DEFAULT_MIN_PATHS = 8
DEFAULT_MAX_PATHS_PER_SLICE = 6
DEFAULT_MAX_SLICES = 16

COORDINATION_PREFIXES = (
    "AiStudio/Task_manager/",
    "AiStudio/Project_state/indexes/",
    "docs/reports/",
)
SENSITIVE_MARKERS = {
    ".env",
    "credentials",
    "private_keys",
    "private_key",
    "secrets",
}
DEEP_MARKERS = {
    "access",
    "auth",
    "authorization",
    "billing",
    "deploy",
    "credential",
    "credentials",
    "firewall",
    "migration",
    "migrations",
    "permission",
    "release",
    "security",
    "secret",
    "secrets",
    "systemd",
    "token",
    "tokens",
}
CODE_SUFFIXES = {
    ".c",
    ".cc",
    ".cpp",
    ".cs",
    ".go",
    ".java",
    ".js",
    ".jsx",
    ".kt",
    ".php",
    ".py",
    ".rb",
    ".rs",
    ".sh",
    ".swift",
    ".ts",
    ".tsx",
}
TEST_NAME_RE = re.compile(r"(^|/)(tests?|specs?)(/|$)|(^|/)(test_[^/]+|[^/]+_(test|spec))\.", re.IGNORECASE)


def normalize_paths(values: Any) -> list[str]:
    if not isinstance(values, list):
        return []
    result: set[str] = set()
    for raw in values:
        value = str(raw or "").strip().replace("\\", "/")
        if not value or value.startswith("/") or re.match(r"^[A-Za-z]:/", value):
            continue
        path = PurePosixPath(value)
        if ".." in path.parts or ".git" in path.parts:
            continue
        result.add(path.as_posix())
    return sorted(result)


def is_coordination_path(path: str) -> bool:
    return path.startswith(COORDINATION_PREFIXES)


def is_test_path(path: str) -> bool:
    return bool(TEST_NAME_RE.search(path))


def path_category(path: str) -> str:
    lowered = path.lower()
    suffix = PurePosixPath(lowered).suffix
    if is_test_path(lowered):
        return "tests"
    if lowered.startswith("docs/") or PurePosixPath(lowered).name in {"readme.md", "changelog.md"}:
        return "docs"
    if lowered.startswith(("schemas/", "templates/", ".agent/", "agent-core/.agent/")):
        return "contract"
    if lowered.startswith(("scripts/", "agent-core/scripts/", ".github/workflows/")):
        return "automation"
    if suffix in CODE_SUFFIXES:
        return "code"
    return "asset"


def path_tokens(path: str) -> set[str]:
    return {
        token
        for token in re.split(r"[^a-z0-9]+", path.lower())
        if token
    }


def sensitive_paths(paths: list[str]) -> list[str]:
    blocked: list[str] = []
    for path in paths:
        lowered = path.lower()
        pure = PurePosixPath(lowered)
        parts = set(pure.parts)
        if (
            pure.name.startswith(".env")
            or parts & SENSITIVE_MARKERS
            or pure.name in {"id_rsa", "id_ed25519", "credentials.json", "service-account.json"}
            or pure.suffix in {".key", ".pem", ".p12", ".pfx"}
        ):
            blocked.append(path)
    return blocked


def feature_key(path: str) -> str:
    pure = PurePosixPath(path)
    name = pure.name.lower()
    for suffix in (".json", ".yaml", ".yml", ".toml", ".md", ".py", ".ts", ".tsx", ".js", ".jsx"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
            break
    name = re.sub(r"^(test_|tests_|spec_)", "", name)
    name = re.sub(r"(_tests?|_spec)$", "", name)
    name = re.sub(r"\.(schema|example)$", "", name)
    name = re.sub(r"_(schema|example)$", "", name)
    name = re.sub(r"[^a-z0-9]+", "_", name).strip("_")
    if name in {"", "index", "readme", "changelog"}:
        parent = pure.parent.as_posix().lower().replace("/", "_")
        name = f"{parent}_{name or 'root'}".strip("_")
    return name[:80]


def group_paths(paths: list[str]) -> list[list[str]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for path in paths:
        grouped[feature_key(path)].append(path)
    return [sorted(grouped[key]) for key in sorted(grouped)]


def lane_for_paths(paths: list[str]) -> dict[str, Any]:
    categories = {path_category(path) for path in paths}
    tokens = set().union(*(path_tokens(path) for path in paths)) if paths else set()
    deep = bool(tokens & DEEP_MARKERS)
    if deep or len(paths) > 8 or len(categories & {"automation", "code", "contract"}) > 1:
        return {
            "execution_lane": "llm_deep",
            "complexity": "L",
            "capability_profile_hint": "deep",
            "recommended_agent": "auto-worker-5.5",
            "eligible_worker_profiles": ["auto-worker-5.5", "auto-worker-5.5max"],
            "model_candidates": ["gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.6-luna"],
            "reasoning_effort_hint": "extra_high" if deep else "high",
        }
    if categories <= {"docs", "tests"} and len(paths) <= 4:
        return {
            "execution_lane": "llm_efficient",
            "complexity": "S",
            "capability_profile_hint": "efficient",
            "recommended_agent": "auto-worker-5.3-mini",
            "eligible_worker_profiles": ["auto-worker-5.3-mini", "auto-worker-5.3"],
            "model_candidates": ["gpt-5.3-codex-spark", "gpt-5.6-luna"],
            "reasoning_effort_hint": "medium",
        }
    return {
        "execution_lane": "llm_balanced",
        "complexity": "M",
        "capability_profile_hint": "balanced",
        "recommended_agent": "auto-worker-5.3",
        "eligible_worker_profiles": ["auto-worker-5.3", "auto-worker-5.5"],
        "model_candidates": ["gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.3-codex-spark"],
        "reasoning_effort_hint": "high",
    }


def pack_groups(groups: list[list[str]], max_paths_per_slice: int) -> list[list[str]]:
    bounded_groups = [
        group[offset : offset + max_paths_per_slice]
        for group in groups
        for offset in range(0, len(group), max_paths_per_slice)
    ]
    bins: list[dict[str, Any]] = []
    for group in sorted(bounded_groups, key=lambda item: (-len(item), item)):
        lane = lane_for_paths(group)["execution_lane"]
        target = next(
            (
                item
                for item in bins
                if item["lane"] == lane and len(item["paths"]) + len(group) <= max_paths_per_slice
            ),
            None,
        )
        if target is None:
            bins.append({"lane": lane, "paths": list(group)})
        else:
            target["paths"].extend(group)
    return [sorted(item["paths"]) for item in bins]


def quoted(paths: list[str]) -> str:
    return " ".join(shlex.quote(path) for path in paths)


def deterministic_checks(paths: list[str]) -> list[str]:
    checks: list[str] = []
    tests = [path for path in paths if is_test_path(path) and path.endswith(".py")]
    python_files = [path for path in paths if path.endswith(".py") and path not in tests]
    json_files = [path for path in paths if path.endswith(".json")]
    if tests:
        checks.append(f"python -m pytest {quoted(tests)} -q")
    if python_files:
        checks.append(f"python -m py_compile {quoted(python_files)}")
    checks.extend(f"python -m json.tool {shlex.quote(path)}" for path in json_files)
    checks.append("git diff --check")
    return checks


def decomposition_reasons(task: dict[str, Any], paths: list[str], groups: list[list[str]]) -> list[str]:
    reasons: list[str] = []
    categories = {path_category(path) for path in paths}
    implementation_groups = [
        group
        for group in groups
        if any(path_category(path) != "tests" for path in group)
    ]
    if task.get("force_pr_decomposition") is True:
        reasons.append("explicit_parent_policy")
    if str(task.get("complexity") or "").upper() in {"L", "XL"} and len(paths) >= 4:
        reasons.append("large_parent_complexity")
    if len(paths) >= DEFAULT_MIN_PATHS:
        reasons.append("large_changed_path_set")
    if len(implementation_groups) >= 4 and len(paths) >= 6:
        reasons.append("multiple_feature_families")
    if len(categories) >= 3 and len(paths) >= 6:
        reasons.append("cross_surface_change")
    return reasons


def build_decomposition_plan(
    task: dict[str, Any],
    raw_paths: list[str],
    *,
    max_paths_per_slice: int = DEFAULT_MAX_PATHS_PER_SLICE,
    max_slices: int = DEFAULT_MAX_SLICES,
) -> dict[str, Any]:
    task_id = str(task.get("id") or task.get("task_id") or "").strip()
    paths = [path for path in normalize_paths(raw_paths) if not is_coordination_path(path)]
    blocked_paths = sensitive_paths(paths)
    groups = group_paths(paths)
    reasons = decomposition_reasons(task, paths, groups)
    packed = pack_groups(groups, max(1, max_paths_per_slice)) if paths else []

    base = {
        "schema_version": SCHEMA_VERSION,
        "planner_id": PLANNER_ID,
        "task_id": task_id,
        "source_branch": task.get("branch") or task.get("source_branch"),
        "source_head_sha": task.get("repository_hygiene_head_sha") or task.get("source_head_sha"),
        "changed_paths": paths,
        "path_count": len(paths),
        "feature_family_count": len(groups),
        "decomposition_reasons": reasons,
        "routing_authority": "central_model_router",
        "deterministic_parent_gates": ["git diff --check", "capability preservation comparison", "project-required tests"],
    }
    if blocked_paths:
        return {
            **base,
            "status": "needs_human",
            "should_decompose": False,
            "reason": "sensitive_paths_require_owner_review",
            "blocked_paths": blocked_paths,
            "slices": [],
        }
    if not reasons or len(packed) < 2:
        return {
            **base,
            "status": "direct_integration",
            "should_decompose": False,
            "reason": "change_is_small_or_not_independently_decomposable",
            "blocked_paths": [],
            "slices": [],
        }
    if len(packed) > max_slices:
        return {
            **base,
            "status": "needs_architect",
            "should_decompose": False,
            "reason": "decomposition_exceeds_slice_ceiling",
            "blocked_paths": [],
            "proposed_slice_count": len(packed),
            "slices": [],
        }

    slices: list[dict[str, Any]] = []
    for index, slice_paths in enumerate(packed, start=1):
        lane = lane_for_paths(slice_paths)
        categories = sorted({path_category(path) for path in slice_paths})
        slices.append(
            {
                "slice_key": f"S{index:02d}",
                "title": f"{task_id} implementation slice {index}: {', '.join(categories)}",
                "paths": slice_paths,
                "categories": categories,
                "execution_kind": "llm_write",
                "deterministic_checks": deterministic_checks(slice_paths),
                "depends_on": [],
                **lane,
            }
        )

    implementation_categories = {"automation", "code", "contract", "asset"}
    code_slice_keys = [
        item["slice_key"]
        for item in slices
        if any(category in implementation_categories for category in item["categories"])
    ]
    for item in slices:
        categories = set(item["categories"])
        if "tests" in categories and not categories.intersection(implementation_categories):
            item["depends_on"] = code_slice_keys

    return {
        **base,
        "status": "decomposed",
        "should_decompose": True,
        "reason": "large_repository_pr_split_into_independent_worker_packets",
        "blocked_paths": [],
        "slice_count": len(slices),
        "slices": slices,
        "integration": {
            "strategy": "merge_finalized_children_then_close_source_pr",
            "required_child_keys": [item["slice_key"] for item in slices],
            "synthesis_required": True,
            "read_only_synthesis": {
                "capability_profile": "delegated_deep",
                "model_candidates": ["gpt-5.6-sol", "gpt-5.6-terra"],
                "reasoning_effort": "ultra",
                "mutation_allowed": False,
                "authority_effect": "none",
            },
            "write_integrator": {
                "capability_profile": "maximum_coherent",
                "model_candidates": ["gpt-5.6-sol", "gpt-5.6-terra"],
                "reasoning_effort": "max",
                "merge_authority": "integrator_only",
            },
        },
    }


def load_task(path: Path, task_id: str | None) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and isinstance(payload.get("tasks"), list):
        matches = [item for item in payload["tasks"] if isinstance(item, dict) and str(item.get("id") or "") == str(task_id or "")]
        if len(matches) != 1:
            raise ValueError("--task-id must identify exactly one queue task")
        return matches[0]
    if not isinstance(payload, dict):
        raise ValueError("task file must contain an object or queue")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task-file", required=True)
    parser.add_argument("--task-id")
    parser.add_argument("--path", action="append", default=[])
    parser.add_argument("--max-paths-per-slice", type=int, default=DEFAULT_MAX_PATHS_PER_SLICE)
    parser.add_argument("--max-slices", type=int, default=DEFAULT_MAX_SLICES)
    args = parser.parse_args()
    task = load_task(Path(args.task_file), args.task_id)
    paths = args.path or task.get("integration_changed_paths") or task.get("changed_paths") or []
    plan = build_decomposition_plan(
        task,
        paths,
        max_paths_per_slice=max(1, args.max_paths_per_slice),
        max_slices=max(2, args.max_slices),
    )
    print(json.dumps(plan, ensure_ascii=False, indent=2))
    return 0 if plan["status"] not in {"needs_human", "needs_architect"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
