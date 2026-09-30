#!/usr/bin/env python3
"""Resolve a Codex model request against the local host catalog before claim."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def default_catalog_path() -> Path:
    return Path.home() / ".codex" / "models_cache.json"


def _load_object(path: Path) -> dict[str, Any] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


def catalog_model_ids(path: Path) -> set[str]:
    data = _load_object(path)
    if data is None:
        return set()
    models = data.get("models")
    if not isinstance(models, list):
        return set()
    return {
        str(item.get("slug") or item.get("id") or "").strip()
        for item in models
        if isinstance(item, dict) and str(item.get("slug") or item.get("id") or "").strip()
    }


def policy_aliases(project_root: Path) -> dict[str, str]:
    data = _load_object(project_root / ".agent" / "model_routing_policy.json")
    registry = data.get("model_registry") if isinstance(data, dict) else None
    if not isinstance(registry, dict):
        return {}
    return {
        str(alias).strip().lower(): str(value.get("model_id") or "").strip()
        for alias, value in registry.items()
        if isinstance(value, dict) and str(alias).strip() and str(value.get("model_id") or "").strip()
    }


def resolve_requested_model(project_root: Path, requested_model: str | None, catalog_path: Path) -> dict[str, Any]:
    requested = str(requested_model or "").strip()
    resolved_catalog = catalog_path.expanduser().resolve()
    base = {"requested_model": requested or None, "catalog_path": str(resolved_catalog)}
    if not requested:
        return {**base, "ok": False, "reason": "model_missing"}
    supported = catalog_model_ids(resolved_catalog)
    if not supported:
        reason = "model_catalog_missing" if not resolved_catalog.exists() else "model_catalog_invalid"
        return {**base, "ok": False, "reason": reason}
    if requested in supported:
        return {**base, "ok": True, "resolved_model": requested, "resolution": "exact_catalog_id"}
    alias_target = policy_aliases(project_root).get(requested.lower())
    if alias_target and alias_target in supported:
        return {**base, "ok": True, "resolved_model": alias_target, "resolution": "policy_alias"}
    return {**base, "ok": False, "reason": "unknown_model_alias", "supported_model_count": len(supported)}
