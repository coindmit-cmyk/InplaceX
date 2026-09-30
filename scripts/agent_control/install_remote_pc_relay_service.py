#!/usr/bin/env python3
"""Install the self-healing remote-PC reverse SSH relay as a user service."""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
from pathlib import Path
from typing import Any

import remote_pc_relay_check


UNIT_NAME = "remote-pc-reverse-relay.service"


def load_config(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("relay config must be a JSON object")
    return data


def safe_value(value: object, field: str) -> str:
    text = str(value or "").strip()
    if not text or "\n" in text or "\r" in text or "\x00" in text:
        raise ValueError(f"invalid {field}")
    return text


def safe_port(value: object, field: str) -> int:
    try:
        port = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid {field}") from exc
    if not 1 <= port <= 65535:
        raise ValueError(f"invalid {field}")
    return port


def render_environment(config: dict[str, Any], identity_file: Path, known_hosts_file: Path) -> str:
    vps = config.get("vps") if isinstance(config.get("vps"), dict) else {}
    relay = config.get("relay") if isinstance(config.get("relay"), dict) else {}
    values = {
        "RELAY_HOST": safe_value(vps.get("host_alias"), "vps.host_alias"),
        "RELAY_SSH_PORT": safe_port(vps.get("ssh_port", 22), "vps.ssh_port"),
        "RELAY_USER": safe_value(relay.get("relay_user"), "relay.relay_user"),
        "RELAY_BIND_HOST": safe_value(relay.get("vps_bind_host"), "relay.vps_bind_host"),
        "RELAY_BIND_PORT": safe_port(relay.get("vps_bind_port"), "relay.vps_bind_port"),
        "REMOTE_PC_SSH_HOST": safe_value(relay.get("remote_pc_ssh_host"), "relay.remote_pc_ssh_host"),
        "REMOTE_PC_SSH_PORT": safe_port(relay.get("remote_pc_ssh_port"), "relay.remote_pc_ssh_port"),
        "IDENTITY_FILE": str(identity_file),
        "KNOWN_HOSTS_FILE": str(known_hosts_file),
    }
    return "\n".join(f"{key}={shlex.quote(str(value))}" for key, value in values.items()) + "\n"


def atomic_write(path: Path, content: str, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    os.chmod(temporary, mode)
    temporary.replace(path)


def render_unit(unit_source: Path, env_target: Path) -> str:
    unit = unit_source.read_text(encoding="utf-8")
    marker = "EnvironmentFile=%h/.config/aistudio/remote-pc-relay.env"
    if marker not in unit:
        raise ValueError("unit environment marker missing")
    return unit.replace(marker, f"EnvironmentFile={env_target}", 1)


def run_systemctl(*args: str) -> dict[str, object]:
    process = subprocess.run(
        ["systemctl", "--user", *args],
        text=True,
        capture_output=True,
        check=False,
    )
    return {
        "command": ["systemctl", "--user", *args],
        "returncode": process.returncode,
        "stdout": process.stdout.strip()[-1000:],
        "stderr": process.stderr.strip()[-1000:],
    }


def install(
    config_path: Path,
    unit_source: Path,
    unit_target: Path,
    env_target: Path,
    identity_file: Path,
    known_hosts_file: Path,
    *,
    apply: bool,
    enable: bool,
) -> dict[str, object]:
    relay_report = remote_pc_relay_check.build_report(config_path)
    blockers = list(relay_report.get("blockers") or [])
    warnings = list(relay_report.get("warnings") or [])
    if not unit_source.is_file():
        blockers.append("unit_source_missing")
    if apply and not identity_file.is_file():
        blockers.append("identity_file_missing")
    if apply and not known_hosts_file.is_file():
        blockers.append("known_hosts_file_missing")

    report: dict[str, object] = {
        "schema_version": "1.0",
        "mode": "apply" if apply else "dry_run",
        "ok": not blockers,
        "unit_name": UNIT_NAME,
        "unit_target": str(unit_target),
        "environment_target": str(env_target),
        "identity_configured": bool(str(identity_file)),
        "known_hosts_configured": bool(str(known_hosts_file)),
        "blockers": blockers,
        "warnings": warnings,
        "actions": [],
        "secret_values_reported": False,
    }
    if blockers:
        return report

    config = load_config(config_path)
    environment = render_environment(config, identity_file, known_hosts_file)
    actions = [
        {"action": "install_unit", "target": str(unit_target)},
        {"action": "install_environment", "target": str(env_target), "mode": "0600"},
    ]
    report["actions"] = actions
    if not apply:
        if enable:
            actions.append({"action": "enable_and_start", "unit": UNIT_NAME})
        return report

    unit_target.parent.mkdir(parents=True, exist_ok=True)
    atomic_write(unit_target, render_unit(unit_source, env_target), 0o644)
    atomic_write(env_target, environment, 0o600)

    if enable:
        commands = [
            run_systemctl("daemon-reload"),
            run_systemctl("enable", "--now", UNIT_NAME),
            run_systemctl("is-enabled", UNIT_NAME),
            run_systemctl("is-active", UNIT_NAME),
        ]
        report["systemctl"] = commands
        failures = [item for item in commands if item["returncode"] != 0]
        if failures:
            report["blockers"] = ["systemctl_activation_failed"]
            report["ok"] = False
    return report


def main() -> int:
    home = Path.home()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--identity-file", type=Path, required=True)
    parser.add_argument("--known-hosts-file", type=Path, default=home / ".ssh" / "known_hosts")
    parser.add_argument(
        "--unit-source",
        type=Path,
        default=Path(__file__).resolve().parent / "systemd" / UNIT_NAME,
    )
    parser.add_argument(
        "--unit-target",
        type=Path,
        default=home / ".config" / "systemd" / "user" / UNIT_NAME,
    )
    parser.add_argument(
        "--env-target",
        type=Path,
        default=home / ".config" / "aistudio" / "remote-pc-relay.env",
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--no-enable", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    try:
        report = install(
            args.config.resolve(),
            args.unit_source.resolve(),
            args.unit_target.resolve(),
            args.env_target.resolve(),
            args.identity_file.expanduser().resolve(),
            args.known_hosts_file.expanduser().resolve(),
            apply=args.apply,
            enable=not args.no_enable,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        report = {
            "schema_version": "1.0",
            "mode": "apply" if args.apply else "dry_run",
            "ok": False,
            "blockers": [f"invalid_install_input:{exc}"],
            "secret_values_reported": False,
        }
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print("ok" if report.get("ok") else "blocked")
        for blocker in report.get("blockers", []):
            print(f"blocker: {blocker}")
    return 0 if report.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main())
