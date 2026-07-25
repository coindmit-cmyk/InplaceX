#!/usr/bin/env python3
"""Validate and probe a VPS reverse SSH relay to the remote automation PC."""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
from pathlib import Path
from typing import Any


LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
SECRET_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"['\"]?\b(?:password|token|private_key)\b['\"]?\s*[:=]\s*['\"]?[^,'\"\s}]+", re.IGNORECASE),
]


def load_json(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    for pattern in SECRET_PATTERNS:
        if pattern.search(text):
            raise ValueError(f"possible secret value in config: {path}")
    data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def safe_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def cfg_value(data: dict[str, Any], section: str, key: str, default: Any = "") -> Any:
    value = data.get(section)
    if not isinstance(value, dict):
        return default
    return value.get(key, default)


def validate_config(data: dict[str, Any]) -> tuple[list[str], list[str]]:
    blockers: list[str] = []
    warnings: list[str] = []
    bind_host = str(cfg_value(data, "relay", "vps_bind_host") or "")
    bind_port = safe_int(cfg_value(data, "relay", "vps_bind_port"))
    vps_host = str(cfg_value(data, "vps", "host_alias") or "")
    vps_user = str(cfg_value(data, "vps", "ssh_user") or "")
    remote_user = str(cfg_value(data, "remote_pc", "ssh_user") or "")
    if bind_host not in LOOPBACK_HOSTS:
        blockers.append("relay_bind_host_not_loopback")
    if bind_port <= 0 or bind_port > 65535:
        blockers.append("relay_port_invalid")
    if not vps_host:
        blockers.append("vps_host_alias_missing")
    if not vps_user:
        blockers.append("vps_ssh_user_missing")
    if not remote_user:
        blockers.append("remote_pc_ssh_user_missing")
    if not cfg_value(data, "vps", "secret_ref"):
        warnings.append("vps_secret_ref_missing")
    if not cfg_value(data, "remote_pc", "secret_ref"):
        warnings.append("remote_pc_secret_ref_missing")
    return blockers, warnings


def shell_join(parts: list[str]) -> str:
    return " ".join(shlex.quote(str(part)) for part in parts)


def command_hints(data: dict[str, Any]) -> dict[str, str]:
    bind_host = str(cfg_value(data, "relay", "vps_bind_host") or "127.0.0.1")
    bind_port = str(safe_int(cfg_value(data, "relay", "vps_bind_port")))
    vps_host = str(cfg_value(data, "vps", "host_alias") or "vps")
    vps_user = str(cfg_value(data, "vps", "ssh_user") or "root")
    relay_user = str(cfg_value(data, "relay", "relay_user") or "relay")
    remote_user = str(cfg_value(data, "remote_pc", "ssh_user") or "agent")
    remote_identity = str(cfg_value(data, "remote_pc", "ssh_identity_file") or "")
    remote_host = str(cfg_value(data, "relay", "remote_pc_ssh_host") or "127.0.0.1")
    remote_port = str(safe_int(cfg_value(data, "relay", "remote_pc_ssh_port")) or 22)
    default_command = str(cfg_value(data, "checks", "default_command") or "hostname; pwd")
    reverse = [
        "ssh",
        "-N",
        "-o",
        "ExitOnForwardFailure=yes",
        "-o",
        "ServerAliveInterval=30",
        "-o",
        "ServerAliveCountMax=3",
        "-R",
        f"{bind_host}:{bind_port}:{remote_host}:{remote_port}",
        f"{relay_user}@{vps_host}",
    ]
    from_vps = ["ssh", "-p", bind_port, f"{remote_user}@{bind_host}", default_command]
    via_jump = ["ssh", "-J", f"{vps_user}@{vps_host}", "-p", bind_port]
    if remote_identity:
        via_jump.extend(["-i", remote_identity])
    via_jump.extend([f"{remote_user}@{bind_host}", default_command])
    return {
        "remote_pc_reverse_tunnel": shell_join(reverse),
        "from_vps_to_remote_pc": shell_join(from_vps),
        "operator_via_vps_jump": shell_join(via_jump),
    }


def run_command(args: list[str], timeout: int = 20) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=timeout)
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
        return None


def check_vps_listener(data: dict[str, Any]) -> dict[str, Any]:
    vps_host = str(cfg_value(data, "vps", "host_alias") or "")
    vps_user = str(cfg_value(data, "vps", "ssh_user") or "")
    bind_port = safe_int(cfg_value(data, "relay", "vps_bind_port"))
    if not vps_host or not vps_user or not bind_port:
        return {"checked": False, "ok": None, "reason": "missing_vps_or_port"}
    remote = (
        f"ss -ltn '( sport = :{bind_port} )' 2>/dev/null "
        f"|| netstat -ltn 2>/dev/null | grep ':{bind_port} ' || true"
    )
    proc = run_command(["ssh", "-o", "BatchMode=yes", f"{vps_user}@{vps_host}", remote], timeout=25)
    if not proc:
        return {"checked": True, "ok": False, "reason": "ssh_to_vps_failed"}
    output = (proc.stdout or "") + (proc.stderr or "")
    return {
        "checked": True,
        "ok": proc.returncode == 0 and f":{bind_port}" in output,
        "returncode": proc.returncode,
        "output_sample": output.strip()[-500:],
    }


def check_relay_command(data: dict[str, Any], command: str) -> dict[str, Any]:
    bind_host = str(cfg_value(data, "relay", "vps_bind_host") or "127.0.0.1")
    bind_port = safe_int(cfg_value(data, "relay", "vps_bind_port"))
    remote_user = str(cfg_value(data, "remote_pc", "ssh_user") or "")
    if not remote_user or not bind_port:
        return {"checked": False, "ok": None, "reason": "missing_remote_user_or_port"}
    proc = run_command(
        ["ssh", "-o", "BatchMode=yes", "-p", str(bind_port), f"{remote_user}@{bind_host}", command],
        timeout=60,
    )
    if not proc:
        return {"checked": True, "ok": False, "reason": "ssh_to_relay_failed"}
    return {
        "checked": True,
        "ok": proc.returncode == 0,
        "returncode": proc.returncode,
        "stdout_sample": (proc.stdout or "").strip()[-1000:],
        "stderr_sample": (proc.stderr or "").strip()[-1000:],
    }


def check_jump_command(data: dict[str, Any], command: str) -> dict[str, Any]:
    bind_host = str(cfg_value(data, "relay", "vps_bind_host") or "127.0.0.1")
    bind_port = safe_int(cfg_value(data, "relay", "vps_bind_port"))
    vps_host = str(cfg_value(data, "vps", "host_alias") or "")
    vps_user = str(cfg_value(data, "vps", "ssh_user") or "")
    remote_user = str(cfg_value(data, "remote_pc", "ssh_user") or "")
    remote_identity = str(cfg_value(data, "remote_pc", "ssh_identity_file") or "")
    host_key_alias = str(data.get("host_key_alias") or f"{data.get('relay_id') or 'remote-pc-relay'}-target")
    if not vps_host or not vps_user or not remote_user or not bind_port:
        return {"checked": False, "ok": None, "reason": "missing_vps_remote_user_or_port"}
    command_args = [
            "ssh",
            "-o",
            "BatchMode=yes",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "-o",
            f"HostKeyAlias={host_key_alias}",
            "-J",
            f"{vps_user}@{vps_host}",
            "-p",
            str(bind_port),
    ]
    if remote_identity:
        command_args.extend(["-i", remote_identity])
    command_args.extend([f"{remote_user}@{bind_host}", command])
    proc = run_command(command_args, timeout=60)
    if not proc:
        return {"checked": True, "ok": False, "reason": "ssh_jump_to_relay_failed"}
    return {
        "checked": True,
        "ok": proc.returncode == 0,
        "returncode": proc.returncode,
        "stdout_sample": (proc.stdout or "").strip()[-1000:],
        "stderr_sample": (proc.stderr or "").strip()[-1000:],
    }


def build_report(
    config_path: Path,
    *,
    check_vps: bool = False,
    check_command: str = "",
    check_jump_command_text: str = "",
) -> dict[str, Any]:
    data = load_json(config_path)
    blockers, warnings = validate_config(data)
    vps_listener = check_vps_listener(data) if check_vps else {"checked": False, "ok": None}
    command_probe = check_relay_command(data, check_command) if check_command else {"checked": False, "ok": None}
    jump_probe = check_jump_command(data, check_jump_command_text) if check_jump_command_text else {"checked": False, "ok": None}
    if vps_listener.get("checked") and vps_listener.get("ok") is False:
        warnings.append("vps_relay_port_not_listening")
    if command_probe.get("checked") and command_probe.get("ok") is False:
        blockers.append("relay_command_failed")
    if jump_probe.get("checked") and jump_probe.get("ok") is False:
        blockers.append("relay_jump_command_failed")
    return {
        "schema_version": "1.0",
        "mode": "remote_pc_relay_check",
        "config": str(config_path),
        "ok": not blockers,
        "blockers": sorted(set(blockers)),
        "warnings": sorted(set(warnings)),
        "relay_id": data.get("relay_id"),
        "commands": command_hints(data),
        "vps_listener": vps_listener,
        "command_probe": command_probe,
        "jump_probe": jump_probe,
        "secret_values_reported": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--check-vps", action="store_true")
    parser.add_argument("--check-command", default="")
    parser.add_argument("--check-jump-command", default="")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_report(
        args.config.expanduser(),
        check_vps=args.check_vps,
        check_command=args.check_command,
        check_jump_command_text=args.check_jump_command,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"ok={report['ok']} blockers={len(report['blockers'])} warnings={len(report['warnings'])}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
