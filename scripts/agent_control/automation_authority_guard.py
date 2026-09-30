#!/usr/bin/env python3
"""Fail closed when an apply-capable automation entry point is not on the writer host."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import project_registry


def evaluate_authority(
    topology: dict[str, Any],
    *,
    host_id: str,
    mode: str = "dry_run",
) -> dict[str, Any]:
    """Return a non-mutating authority decision for one configured host.

    The guard deliberately does not acquire leases or write runtime state.  A
    caller must use the returned ``apply_allowed`` decision before it performs
    its own mutation.
    """
    if mode not in {"dry_run", "apply"}:
        raise ValueError("mode must be 'dry_run' or 'apply'")

    normalized = project_registry.normalize_fleet_topology(topology)
    canonical_writer_host = str(normalized.get("canonical_writer_host") or "")
    hosts = {
        str(host.get("host_id") or ""): host
        for host in normalized.get("hosts", [])
        if isinstance(host, dict) and str(host.get("host_id") or "")
    }
    host = hosts.get(host_id)
    host_role = str((host or {}).get("role") or "unknown")
    topology_warnings = project_registry.fleet_topology_warnings(normalized)
    apply_allowed = bool(
        not topology_warnings
        and host is not None
        and host_id == canonical_writer_host
        and host_role == "writer"
        and host.get("can_write") is True
    )
    errors: list[dict[str, str]] = []
    if topology_warnings:
        errors.append({
            "code": "fleet_topology_invalid",
            "message": "; ".join(topology_warnings),
        })
    if host is None:
        errors.append({
            "code": "host_not_registered",
            "message": f"host {host_id!r} is not registered in fleet topology",
        })
    if mode == "apply" and not apply_allowed:
        errors.append({
            "code": "observer_host_apply_forbidden",
            "message": "apply mode is permitted only on the configured canonical writer host",
        })

    return {
        "schema_version": "1.0",
        "ok": not errors if mode == "dry_run" else apply_allowed and not errors,
        "mode": mode,
        "host_id": host_id,
        "host_role": host_role,
        "canonical_writer_host": canonical_writer_host,
        "apply_allowed": apply_allowed,
        "mutations_performed": False,
        "topology_warnings": topology_warnings,
        "errors": errors,
    }


def evaluate_registry_authority(
    registry_path: Path,
    *,
    host_id: str,
    mode: str = "dry_run",
) -> dict[str, Any]:
    """Load a Registry's topology and evaluate one host without modifying it."""
    try:
        topology = project_registry.load_fleet_topology(registry_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return {
            "schema_version": "1.0",
            "ok": False,
            "mode": mode,
            "host_id": host_id,
            "host_role": "unknown",
            "canonical_writer_host": "",
            "apply_allowed": False,
            "mutations_performed": False,
            "topology_warnings": [],
            "errors": [{"code": "fleet_topology_unreadable", "message": str(exc)}],
        }
    report = evaluate_authority(topology, host_id=host_id, mode=mode)
    report["registry_path"] = str(registry_path)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--host-id", required=True)
    parser.add_argument("--mode", choices=["dry_run", "apply"], default="dry_run")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = evaluate_registry_authority(args.registry, host_id=args.host_id, mode=args.mode)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    elif report["ok"]:
        print(f"ok: {report['host_id']} is {report['host_role']}")
    else:
        print("; ".join(error["message"] for error in report["errors"]))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
