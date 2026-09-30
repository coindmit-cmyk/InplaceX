#!/usr/bin/env python3
"""Read-only Artifact Discovery scanner.

The scanner inventories significant project artifacts and emits findings. It does
not mutate repository files, PROJECT_MAP, Task_manager, indexes or reports.
"""

from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

import local_llm_adapter

IGNORE_PARTS = {
    ".git",
    "node_modules",
    ".venv",
    "venv",
    "__pycache__",
    ".pytest_cache",
    "agent-runtime",
    "runtime",
}
IGNORE_PREFIXES = (
    "AiStudio/Task_manager/backups/",
    "AiStudio/Task_manager/reports/discovery/",
    "AiStudio/Task_manager/reports/full-intake-cycle/",
    "docs/reports/discovery/",
)

SIGNIFICANT_PREFIXES = (
    ".agent/",
    "agent-core/.agent/",
    "docs/agent/",
    "docs/automation/",
    "agent-core/docs/automation/",
    "docs/reports/",
    "scripts/agent_control/",
    "schemas/",
    "templates/",
    "AiStudio/Task_manager/",
    "src/",
    "app/",
    "apps/",
    "packages/",
    "services/",
    "modules/",
    "lib/",
    "api/",
    "backend/",
    "frontend/",
    "web/",
    "ui/",
    "components/",
    "pages/",
    "routes/",
    "tests/",
    "test/",
)

SIGNIFICANT_ROOT_FILES = {
    "PROJECT_MAP.json",
    "PROJECT_MAP.md",
    "README.md",
    "CHANGELOG.md",
    "PROJECT_VERSION.json",
    "VERSION",
}

SENSITIVE_PATTERNS = [
    re.compile(r"BEGIN [A-Z ]*PRIVATE KEY"),
    re.compile(r"(?im)(api[_-]?key|access[_-]?token|auth[_-]?token|password)[ \t]*[:=][ \t]*['\"]?[A-Za-z0-9_./+=-]{20,}"),
]


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def rel_path(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def should_ignore(path: Path, root: Path) -> bool:
    rel = path.relative_to(root)
    rel_text = rel.as_posix()
    return any(part in IGNORE_PARTS for part in rel.parts) or any(
        rel_text == prefix.rstrip("/") or rel_text.startswith(prefix)
        for prefix in IGNORE_PREFIXES
    )


def artifact_type(rel: str) -> str:
    if rel == "PROJECT_MAP.json":
        return "project_map"
    if rel.startswith(".agent/roles/") or rel.startswith("agent-core/.agent/roles/"):
        return "agent_role"
    if rel.startswith(".agent/prompts/") or rel.startswith("agent-core/.agent/prompts/"):
        return "prompt"
    if rel.startswith(".agent/skills/") or rel.startswith("agent-core/.agent/skills/"):
        return "skill"
    if rel.startswith(".agent/lenses/") or rel.startswith("agent-core/.agent/lenses/"):
        return "lens"
    if rel in {".agent/START_HERE.md", "agent-core/.agent/START_HERE.md"}:
        return "agent_entrypoint"
    if rel.startswith(".agent/") or rel.startswith("agent-core/.agent/"):
        return "agent_metadata"
    if rel.startswith("agent-core/.agent/roles/"):
        return "agent_role"
    if rel == "agent-core/.agent/routing.md":
        return "routing_policy"
    if rel.startswith("docs/agent/workflows/"):
        return "agent_workflow"
    if rel.startswith("docs/agent/prompts/"):
        return "prompt"
    if rel.startswith("docs/agent/skills/"):
        return "skill"
    if rel.startswith("docs/agent/lenses/"):
        return "lens"
    if rel.startswith("docs/agent/integration/"):
        return "integration_policy"
    if rel.startswith("docs/agent/discovery/"):
        return "discovery_policy"
    if rel.startswith("scripts/agent_control/") and rel.endswith(".py"):
        return "automation_script"
    if rel.startswith("schemas/") and rel.endswith(".json"):
        return "schema"
    if rel.startswith("templates/"):
        return "template"
    if rel.startswith("AiStudio/Task_manager/"):
        return "task_state"
    if rel.startswith("docs/reports/"):
        return "report"
    if rel.startswith("docs/") and rel.endswith(".md"):
        return "document"
    if rel.endswith(".py"):
        return "python_module"
    if rel.endswith((".js", ".ts", ".tsx", ".jsx")):
        return "frontend_or_node_module"
    return "artifact"


def is_significant(rel: str) -> bool:
    return rel in SIGNIFICANT_ROOT_FILES or rel.startswith(SIGNIFICANT_PREFIXES)


def artifact_flags(rel: str, art_type: str, significant: bool) -> list[str]:
    flags: list[str] = []
    if significant:
        flags.append("significant")
    if rel in SIGNIFICANT_ROOT_FILES:
        flags.append("root_surface")
    if art_type in {"agent_role", "agent_workflow", "prompt", "skill", "lens", "agent_entrypoint", "agent_metadata", "routing_policy"}:
        flags.append("agent_surface")
    if art_type == "agent_entrypoint":
        flags.append("entrypoint_surface")
    if art_type == "agent_metadata":
        flags.append("metadata_surface")
    if art_type in {"integration_policy", "discovery_policy"}:
        flags.append("policy_surface")
    if art_type == "automation_script":
        flags.append("automation_surface")
    if art_type in {"schema", "template"}:
        flags.append("contract_surface")
    if art_type == "task_state":
        flags.append("task_manager_state")
    if art_type == "report":
        flags.append("evidence_surface")
    if art_type in {"python_module", "frontend_or_node_module"}:
        flags.append("implementation_surface")
    if rel.endswith((".tmp", ".bak", ".old")) or "/tmp/" in rel or "/scratch/" in rel:
        flags.append("cleanup_candidate")
    return flags


def semantic_kind(rel: str, art_type: str, flags: list[str]) -> str:
    if art_type in {"python_module", "frontend_or_node_module", "automation_script"}:
        return "code"
    if art_type in {"agent_role", "agent_workflow", "prompt", "skill", "lens", "agent_entrypoint", "agent_metadata", "routing_policy"}:
        return "agent_contract"
    if art_type in {"integration_policy", "discovery_policy"}:
        return "policy"
    if art_type == "schema":
        return "schema"
    if art_type == "template":
        return "template"
    if art_type == "task_state":
        return "task_state"
    if art_type == "report":
        return "report"
    if art_type == "project_map":
        return "project_map"
    if art_type == "document" or rel.endswith(".md"):
        return "documentation"
    if rel.endswith((".json", ".yaml", ".yml", ".toml", ".ini", ".env", ".example")):
        return "config"
    if "implementation_surface" in flags:
        return "code"
    return "other"


def implementation_status(rel: str, kind: str, art_type: str, flags: list[str]) -> tuple[str, list[str]]:
    if "cleanup_candidate" in flags:
        return "needs_review", ["temporary-looking artifact requires cleanup review"]
    if kind == "code":
        return "implemented", ["code or executable automation artifact exists"]
    if kind in {"schema", "template"}:
        return "contract_exists", ["schema/template contract artifact exists"]
    if kind == "task_state":
        return "state_exists", ["Task Manager state artifact exists"]
    if kind == "report":
        return "evidence_exists", ["report/evidence artifact exists"]
    if kind == "project_map":
        return "map_exists", ["Project Map artifact exists"]
    if kind in {"documentation", "policy", "agent_contract"}:
        if any(part in rel for part in ("/plans/", "/requirements/", "/spec", "/architecture", "BACKLOG", "TECHNICAL_SPEC")):
            return "documented_only", ["planning/specification artifact exists; implementation must be verified by consumers"]
        return "documented", ["documentation or agent-facing contract artifact exists"]
    if kind == "config":
        return "configured", ["configuration-like artifact exists"]
    return "unknown", ["implementation status requires review"]


def inventory_disposition(flags: list[str]) -> str:
    if "cleanup_candidate" in flags:
        return "needs_cleanup_review"
    if "task_manager_state" in flags:
        return "state_evidence"
    if "evidence_surface" in flags:
        return "evidence"
    if "significant" in flags:
        return "needs_coverage_check"
    return "inventory_only"


def read_text(path: Path, max_bytes: int = 1_000_000) -> str:
    try:
        data = path.read_bytes()
    except OSError:
        return ""
    if len(data) > max_bytes:
        data = data[:max_bytes]
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return ""


def project_map_text(root: Path) -> str:
    chunks: list[str] = []
    for name in ("PROJECT_MAP.json", "PROJECT_MAP.md"):
        path = root / name
        if path.is_file():
            chunks.append(read_text(path))
    return "\n".join(chunks)


def project_map_patterns(root: Path) -> list[str]:
    path = root / "PROJECT_MAP.json"
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    if not isinstance(data, dict):
        return []
    patterns: list[str] = []
    for value in data.get("coverage_patterns") or []:
        if isinstance(value, str) and value.strip():
            patterns.append(value.strip().replace("\\", "/"))
    for module in data.get("modules") or []:
        if not isinstance(module, dict):
            continue
        for key in ("primary_paths", "coverage_patterns"):
            for value in module.get(key) or []:
                if isinstance(value, str) and value.strip():
                    patterns.append(value.strip().replace("\\", "/"))
    return sorted(set(patterns))


def pattern_covers(pattern: str, rel: str) -> bool:
    normalized = pattern.strip().replace("\\", "/")
    if not normalized:
        return False
    if normalized.endswith("/**"):
        prefix = normalized[:-3]
        return rel == prefix.rstrip("/") or rel.startswith(prefix)
    if normalized.endswith("/"):
        return rel.startswith(normalized)
    if any(char in normalized for char in "*?[]"):
        return fnmatch.fnmatchcase(rel, normalized)
    return rel == normalized or rel.startswith(normalized.rstrip("/") + "/")


def covered_by_project_map(rel: str, map_text: str, patterns: list[str]) -> bool:
    return rel in map_text or any(pattern_covers(pattern, rel) for pattern in patterns)


def linked_in(path: Path, rel: str) -> bool:
    return path.is_file() and rel in read_text(path)


def automation_manifest_script_paths(root: Path) -> set[str]:
    path = root / "templates" / "agent-control" / "automation_manifest.json"
    if not path.is_file():
        return set()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return set()
    paths: set[str] = set()
    for component in data.get("components") or []:
        if not isinstance(component, dict):
            continue
        if component.get("kind") != "script":
            continue
        value = component.get("path")
        if isinstance(value, str) and value.strip():
            paths.add(value.strip().replace("\\", "/"))
    return paths


def linked_in_script_catalog(root: Path, scripts_catalog: Path, rel: str) -> bool:
    return linked_in(scripts_catalog, rel) or rel in automation_manifest_script_paths(root)


def finding_id(category: str, rel: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "-", rel).strip("-")[:80]
    return f"ad-{category}-{safe or 'root'}"


def make_finding(category: str, rel: str, art_type: str, *, severity: str, confidence: str, current_scope: bool, evidence: list[str], owner: str, task_type: str, action: str, blocking_gate: str, auto_task_allowed: bool = True, flags: list[str] | None = None, disposition: str | None = None) -> dict[str, Any]:
    effective_flags = flags or artifact_flags(rel, art_type, is_significant(rel))
    kind = semantic_kind(rel, art_type, effective_flags)
    status, status_evidence = implementation_status(rel, kind, art_type, effective_flags)
    return {
        "id": finding_id(category, rel),
        "detected_at": utc_now(),
        "detector": "artifact_discovery_scanner",
        "path": rel,
        "artifact_type": art_type,
        "artifact_flags": effective_flags,
        "artifact_disposition": disposition or inventory_disposition(effective_flags),
        "semantic_kind": kind,
        "implementation_status": status,
        "implementation_evidence": status_evidence,
        "category": category,
        "severity": severity,
        "confidence": confidence,
        "current_scope": bool(current_scope),
        "evidence": evidence,
        "related_refs": [],
        "suggested_owner": owner,
        "suggested_task_type": task_type,
        "suggested_action": action,
        "blocking_gate": blocking_gate,
        "auto_task_allowed": bool(auto_task_allowed),
    }


def detect_sensitive(path: Path, rel: str, art_type: str, current_scope: bool) -> dict[str, Any] | None:
    text = read_text(path, max_bytes=200_000)
    if not text:
        return None
    for pattern in SENSITIVE_PATTERNS:
        if pattern.search(text):
            return make_finding(
                "possible_secret_pattern",
                rel,
                art_type,
                severity="blocking",
                confidence="medium",
                current_scope=current_scope,
                evidence=["sensitive-like pattern detected; value redacted"],
                owner="Human",
                task_type="security_review",
                action="review sensitive-risk finding",
                blocking_gate="human_security_review",
                auto_task_allowed=False,
                flags=artifact_flags(rel, art_type, is_significant(rel)) + ["sensitive_risk"],
                disposition="needs_human_security_review",
            )
    return None


def inventory_files(root: Path) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    for current, directories, filenames in os.walk(root):
        current_path = Path(current)
        directories[:] = sorted(
            name for name in directories if not should_ignore(current_path / name, root)
        )
        for filename in sorted(filenames):
            path = current_path / filename
            if not path.is_file() or should_ignore(path, root):
                continue
            rel = rel_path(path, root)
            art_type = artifact_type(rel)
            significant = is_significant(rel)
            flags = artifact_flags(rel, art_type, significant)
            kind = semantic_kind(rel, art_type, flags)
            status, status_evidence = implementation_status(rel, kind, art_type, flags)
            items.append({
                "path": rel,
                "artifact_type": art_type,
                "semantic_kind": kind,
                "implementation_status": status,
                "implementation_evidence": status_evidence,
                "significant": significant,
                "flags": flags,
                "disposition": inventory_disposition(flags),
            })
    return sorted(items, key=lambda item: str(item.get("path") or ""))


def valid_llm_value(value: Any, allowed: set[str]) -> str | None:
    text = str(value or "").strip()
    return text if text in allowed else None


def apply_local_llm_semantics(inventory: list[dict[str, Any]], *, timeout_sec: int) -> dict[str, Any]:
    prompt = {
        "instruction": (
            "Classify repository artifacts. Return JSON object with key 'artifacts'. "
            "Each artifact must include path, semantic_kind, implementation_status and implementation_evidence. "
            "Use only provided paths/types/flags. Do not invent files."
        ),
        "allowed_semantic_kind": [
            "code",
            "documentation",
            "policy",
            "agent_contract",
            "schema",
            "template",
            "task_state",
            "report",
            "project_map",
            "config",
            "other",
        ],
        "allowed_implementation_status": [
            "implemented",
            "documented",
            "documented_only",
            "contract_exists",
            "state_exists",
            "evidence_exists",
            "map_exists",
            "configured",
            "needs_review",
            "unknown",
        ],
        "artifacts": [
            {
                "path": item.get("path"),
                "artifact_type": item.get("artifact_type"),
                "flags": item.get("flags") or [],
                "semantic_kind": item.get("semantic_kind"),
                "implementation_status": item.get("implementation_status"),
            }
            for item in inventory
        ],
    }
    allowed_kinds = set(prompt["allowed_semantic_kind"])
    allowed_statuses = set(prompt["allowed_implementation_status"])
    base_url = os.environ.get("LOCAL_LLM_BASE_URL", "http://127.0.0.1:11434/v1")
    model = os.environ.get("LOCAL_LLM_MODEL", "qwen2.5-coder:14b")
    try:
        raw = local_llm_adapter.openai_compatible_chat(
            base_url,
            os.environ.get(os.environ.get("LOCAL_LLM_API_KEY_ENV", "LOCAL_LLM_API_KEY"), ""),
            model,
            json.dumps(prompt, ensure_ascii=False),
            (
                "You are an Artifact Discovery semantic classifier. "
                "Return strict JSON only. Keep evidence short and path-grounded."
            ),
            timeout_sec,
            response_format_json=True,
        )
        parsed = json.loads(raw)
    except (OSError, TimeoutError, json.JSONDecodeError, RuntimeError) as exc:
        return {"ok": False, "mode": "local-llm", "error": str(exc), "updated": 0}
    artifacts = parsed.get("artifacts") if isinstance(parsed, dict) else None
    if not isinstance(artifacts, list):
        return {"ok": False, "mode": "local-llm", "error": "LLM response has no artifacts list", "updated": 0}
    by_path = {str(item.get("path") or ""): item for item in artifacts if isinstance(item, dict)}
    updated = 0
    for item in inventory:
        llm_item = by_path.get(str(item.get("path") or ""))
        if not llm_item:
            continue
        kind = valid_llm_value(llm_item.get("semantic_kind"), allowed_kinds)
        status = valid_llm_value(llm_item.get("implementation_status"), allowed_statuses)
        evidence = llm_item.get("implementation_evidence")
        if kind:
            item["semantic_kind"] = kind
        if status:
            item["implementation_status"] = status
        if isinstance(evidence, list) and evidence:
            item["implementation_evidence"] = [str(value) for value in evidence[:3]]
        item["semantic_source"] = "local_llm"
        updated += 1
    return {"ok": True, "mode": "local-llm", "base_url": base_url, "model": model, "updated": updated}


def apply_inventory_semantics_to_findings(findings: list[dict[str, Any]], inventory: list[dict[str, Any]]) -> None:
    by_path = {str(item.get("path") or ""): item for item in inventory}
    for finding in findings:
        item = by_path.get(str(finding.get("path") or ""))
        if not item:
            continue
        finding["semantic_kind"] = item.get("semantic_kind")
        finding["implementation_status"] = item.get("implementation_status")
        finding["implementation_evidence"] = item.get("implementation_evidence") or []
        if item.get("semantic_source"):
            finding["semantic_source"] = item.get("semantic_source")


def integration_evidence_for(gaps: list[str], significant: bool) -> tuple[str, list[str]]:
    if "possible_secret_pattern" in gaps:
        return "needs_human_review", ["sensitive-risk finding blocks automatic integration status"]
    if "missing_project_map_coverage" in gaps:
        return "not_integrated", ["artifact is not covered by Project Map"]
    if gaps:
        return "partially_integrated", [f"integration gaps remain: {', '.join(gaps)}"]
    if significant:
        return "integrated", ["no Artifact Discovery integration gaps detected"]
    return "inventory_only", ["artifact is outside significant integration scope"]


def apply_integration_status(inventory: list[dict[str, Any]], findings: list[dict[str, Any]]) -> None:
    gaps_by_path: dict[str, list[str]] = {}
    for finding in findings:
        path = str(finding.get("path") or "")
        category = str(finding.get("category") or "")
        if path and category:
            gaps_by_path.setdefault(path, []).append(category)
    by_path = {str(item.get("path") or ""): item for item in inventory}
    for item in inventory:
        path = str(item.get("path") or "")
        gaps = sorted(set(gaps_by_path.get(path, [])))
        status, evidence = integration_evidence_for(gaps, bool(item.get("significant")))
        item["integration_status"] = status
        item["integration_gaps"] = gaps
        item["integration_evidence"] = evidence
    for finding in findings:
        item = by_path.get(str(finding.get("path") or ""))
        if not item:
            continue
        finding["integration_status"] = item.get("integration_status")
        finding["integration_gaps"] = item.get("integration_gaps") or []
        finding["integration_evidence"] = item.get("integration_evidence") or []


def count_by(items: list[dict[str, Any]], key: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for item in items:
        value = str(item.get(key) or "")
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def integration_coverage(inventory: list[dict[str, Any]]) -> dict[str, Any]:
    significant = [item for item in inventory if item.get("significant") is True]
    integrated = [item for item in significant if item.get("integration_status") == "integrated"]
    gaps = [item for item in significant if item.get("integration_status") != "integrated"]
    non_significant_inventory = [
        item
        for item in inventory
        if item.get("significant") is not True and item.get("integration_status") == "inventory_only"
    ]
    percentage = round((len(integrated) / len(significant) * 100.0), 2) if significant else 100.0
    return {
        "significant_count": len(significant),
        "significant_integrated_count": len(integrated),
        "significant_gap_count": len(gaps),
        "significant_coverage_percent": percentage,
        "non_significant_inventory_only_count": len(non_significant_inventory),
        "inventory_only_is_actionable": False,
    }


def scan(root: Path, *, changed_paths: set[str] | None = None, semantic_mode: str = "deterministic", local_llm_timeout: int = 60) -> dict[str, Any]:
    changed_paths = changed_paths or set()
    inventory = inventory_files(root)
    semantic_check: dict[str, Any] = {"name": "artifact_discovery_semantics", "result": "deterministic"}
    if semantic_mode == "local-llm":
        llm_result = apply_local_llm_semantics(inventory, timeout_sec=local_llm_timeout)
        semantic_check = {"name": "artifact_discovery_semantics", "result": "local_llm_completed" if llm_result.get("ok") else "local_llm_fallback", **llm_result}
    findings: list[dict[str, Any]] = []
    map_text = project_map_text(root)
    map_patterns = project_map_patterns(root)
    has_project_map = bool(map_text.strip())
    index_path = root / "docs" / "AISTUDIO_INDEX.md"
    scripts_catalog = root / "agent-core" / "docs" / "automation" / "SCRIPTS_CATALOG.md"
    for item in inventory:
        rel = item["path"]
        art_type = item["artifact_type"]
        flags = [str(flag) for flag in item.get("flags") or []]
        disposition = str(item.get("disposition") or "inventory_only")
        current_scope = rel in changed_paths or any(rel.startswith(prefix.rstrip("/") + "/") for prefix in changed_paths)
        if item["significant"]:
            if not has_project_map or not covered_by_project_map(rel, map_text, map_patterns):
                severity = "blocking" if current_scope else "warning"
                gate = "integration" if current_scope else "none"
                findings.append(make_finding(
                    "missing_project_map_coverage",
                    rel,
                    art_type,
                    severity=severity,
                    confidence="medium",
                    current_scope=current_scope,
                    evidence=["significant artifact not found in PROJECT_MAP coverage"],
                    owner="Dispatcher",
                    task_type="reality_map_backfill",
                    action="create ProjectMapPlanner backfill task",
                    blocking_gate=gate,
                    flags=flags + ["map_gap"],
                    disposition="needs_project_map_backfill",
                ))
        if art_type in {"agent_workflow", "prompt", "skill", "lens", "integration_policy", "discovery_policy"} and not linked_in(index_path, rel):
            findings.append(make_finding(
                "missing_index_link",
                rel,
                art_type,
                severity="blocking" if current_scope else "warning",
                confidence="medium",
                current_scope=current_scope,
                evidence=["agent-facing artifact not linked from docs/AISTUDIO_INDEX.md"],
                owner="Integrator",
                task_type="integration_repair",
                action="add index/discovery link or record explicit exception",
                blocking_gate="integration" if current_scope else "none",
                flags=flags + ["index_gap"],
                disposition="needs_index_or_exception",
            ))
        if art_type == "automation_script" and not linked_in_script_catalog(root, scripts_catalog, rel):
            findings.append(make_finding(
                "missing_script_catalog_entry",
                rel,
                art_type,
                severity="blocking" if current_scope else "warning",
                confidence="medium",
                current_scope=current_scope,
                evidence=["automation script not linked from Scripts Catalog"],
                owner="Integrator",
                task_type="automation_surface_integration",
                action="add script catalog entry or record exception",
                blocking_gate="integration" if current_scope else "none",
                flags=flags + ["catalog_gap"],
                disposition="needs_script_catalog_or_exception",
            ))
        if art_type == "schema":
            stem = Path(rel).stem.replace(".schema", "")
            template_found = any(p["path"].startswith("templates/") and stem in p["path"] for p in inventory)
            if not template_found:
                findings.append(make_finding("missing_validator_template_pair", rel, art_type, severity="warning", confidence="low", current_scope=current_scope, evidence=["schema has no obvious matching template"], owner="Integrator", task_type="schema_template_integration", action="add template/example or record exception", blocking_gate="integration" if current_scope else "none", flags=flags + ["template_pair_gap"], disposition="needs_schema_template_or_exception"))
        if rel.endswith(('.tmp', '.bak', '.old')) or '/tmp/' in rel or '/scratch/' in rel:
            findings.append(make_finding("cleanup_candidate", rel, art_type, severity="advisory", confidence="low", current_scope=current_scope, evidence=["temporary-looking artifact"], owner="Integrator", task_type="cleanup_candidate_review", action="review cleanup candidate; do not auto-delete", blocking_gate="none", flags=flags + ["cleanup_candidate"], disposition="needs_cleanup_review"))
        sensitive = detect_sensitive(root / rel, rel, art_type, current_scope)
        if sensitive:
            findings.append(sensitive)
    apply_inventory_semantics_to_findings(findings, inventory)
    apply_integration_status(inventory, findings)
    return {
        "schema_version": "1.0",
        "generated_at": utc_now(),
        "project_root": str(root),
        "mode": "scan",
        "summary": {
            "inventory_count": len(inventory),
            "finding_count": len(findings),
            "blocking_count": sum(1 for f in findings if f.get("severity") in {"blocking", "critical"}),
            "by_semantic_kind": count_by(findings, "semantic_kind"),
            "by_implementation_status": count_by(findings, "implementation_status"),
            "by_integration_status": count_by(findings, "integration_status"),
            "inventory_by_semantic_kind": count_by(inventory, "semantic_kind"),
            "inventory_by_implementation_status": count_by(inventory, "implementation_status"),
            "inventory_by_integration_status": count_by(inventory, "integration_status"),
            "integration_coverage": integration_coverage(inventory),
        },
        "inventory": inventory,
        "findings": findings,
        "routes": [],
        "task_candidates": [],
        "checks": [{"name": "artifact_discovery_scan", "result": "completed"}, semantic_check],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--changed-path", action="append", default=[])
    parser.add_argument("--semantic-mode", choices=["deterministic", "local-llm"], default="deterministic")
    parser.add_argument("--local-llm-timeout", type=int, default=60)
    parser.add_argument("--output", help="Optional JSON output path. The scanner remains read-only for project state.")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    report = scan(
        Path(args.project_root).resolve(),
        changed_paths=set(args.changed_path or []),
        semantic_mode=args.semantic_mode,
        local_llm_timeout=args.local_llm_timeout,
    )
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"findings: {report['summary']['finding_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
