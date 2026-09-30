#!/usr/bin/env python3
"""Validate and activate layered power recovery for a remote automation PC."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import socket
import subprocess
import time
from pathlib import Path
from typing import Any


MAC_RE = re.compile(r"^(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$")
SAFE_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]+$")


def load_config(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("power recovery config must be a JSON object")
    return data


def section(data: dict[str, Any], name: str) -> dict[str, Any]:
    value = data.get(name)
    return value if isinstance(value, dict) else {}


def normalize_mac(value: object) -> str:
    text = str(value or "").strip()
    if not MAC_RE.fullmatch(text):
        raise ValueError("invalid remote_pc.mac_address")
    return text.replace("-", ":").upper()


def safe_name(value: object, field: str) -> str:
    text = str(value or "").strip()
    if not text or not SAFE_NAME_RE.fullmatch(text):
        raise ValueError(f"invalid {field}")
    return text


def positive_int(value: object, field: str, minimum: int, maximum: int) -> int:
    try:
        result = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid {field}") from exc
    if result < minimum or result > maximum:
        raise ValueError(f"invalid {field}")
    return result


def validated_config(data: dict[str, Any]) -> dict[str, Any]:
    remote = section(data, "remote_pc")
    network = section(data, "network")
    router = section(data, "router")
    initiator = section(data, "initiator")
    rtc = section(data, "rtc_fallback")
    watchdog = section(data, "watchdog")
    host = str(ipaddress.ip_address(str(remote.get("ipv4_address") or "")))
    broadcast = str(ipaddress.ip_address(str(network.get("broadcast_address") or "")))
    target_network = ipaddress.ip_network(
        str(network.get("target_cidr") or f"{host}/24"), strict=False
    )
    if ipaddress.ip_address(host) not in target_network:
        raise ValueError("remote_pc.ipv4_address must belong to network.target_cidr")
    if ipaddress.ip_address(broadcast) != target_network.broadcast_address:
        raise ValueError("network.broadcast_address must match network.target_cidr")

    # ``router`` is retained for configs released before the generic initiator model.
    kind = str(initiator.get("kind") or router.get("vendor") or "none").strip().lower()
    if kind not in {"mikrotik_routeros", "openwrt", "none"}:
        raise ValueError("initiator.kind must be mikrotik_routeros, openwrt or none")
    initiator_cidr = str(initiator.get("ipv4_cidr") or "").strip()
    initiator_network = ipaddress.ip_network(initiator_cidr, strict=False) if initiator_cidr else None
    same_l2 = bool(initiator.get("same_l2", kind == "mikrotik_routeros"))
    wol_ready = kind != "none" and same_l2 and (
        initiator_network is None or initiator_network == target_network
    )
    if not same_l2:
        wol_reason = "initiator_not_on_target_l2"
    elif initiator_network is not None and initiator_network != target_network:
        wol_reason = "initiator_network_mismatch"
    elif kind == "none":
        wol_reason = "initiator_not_configured"
    else:
        wol_reason = "ready"

    rtc_enabled = bool(rtc.get("enabled", False))
    return {
        "recovery_id": safe_name(data.get("recovery_id"), "recovery_id"),
        "host": host,
        "mac": normalize_mac(remote.get("mac_address")),
        "ssh_port": positive_int(remote.get("ssh_port", 22), "remote_pc.ssh_port", 1, 65535),
        "os_interface": safe_name(remote.get("os_interface"), "remote_pc.os_interface"),
        "network_profile": safe_name(remote.get("network_profile"), "remote_pc.network_profile"),
        "linux_user": safe_name(remote.get("linux_user"), "remote_pc.linux_user"),
        "broadcast": broadcast,
        "target_network": str(target_network),
        "wol_port": positive_int(network.get("wol_port", 9), "network.wol_port", 1, 65535),
        "initiator_kind": kind,
        "initiator_network": str(initiator_network) if initiator_network else "",
        "wol_ready": wol_ready,
        "wol_reason": wol_reason,
        "router_interface": safe_name(
            initiator.get("lan_interface") or router.get("lan_interface") or "bridge",
            "initiator.lan_interface",
        ),
        "script_name": safe_name(router.get("script_name", "aistudio-pc-wol-watchdog"), "router.script_name"),
        "scheduler_name": safe_name(router.get("scheduler_name", "aistudio-pc-wol-watchdog"), "router.scheduler_name"),
        "interval_seconds": positive_int(watchdog.get("interval_seconds", 180), "watchdog.interval_seconds", 30, 86400),
        "ping_count": positive_int(watchdog.get("ping_count", 3), "watchdog.ping_count", 1, 10),
        "rtc_enabled": rtc_enabled,
        "rtc_device": str(rtc.get("device") or "/sys/class/rtc/rtc0/wakealarm"),
        "rtc_wake_after_seconds": positive_int(
            rtc.get("wake_after_seconds", 900), "rtc_fallback.wake_after_seconds", 60, 86400
        ),
        "rtc_refresh_seconds": positive_int(
            rtc.get("refresh_interval_seconds", 300),
            "rtc_fallback.refresh_interval_seconds",
            60,
            43200,
        ),
    }


def interval_text(seconds: int) -> str:
    if seconds % 3600 == 0:
        return f"{seconds // 3600}h"
    if seconds % 60 == 0:
        return f"{seconds // 60}m"
    return f"{seconds}s"


def render_routeros_commands(config: dict[str, Any]) -> list[str]:
    cfg = validated_config(config)
    if cfg["initiator_kind"] != "mikrotik_routeros":
        return []
    if not cfg["wol_ready"]:
        return []
    source = (
        f':local host "{cfg["host"]}"; '
        f':if ([/ping $host count={cfg["ping_count"]} interval=1s] = 0) do={{ '
        f'/tool wol interface={cfg["router_interface"]} mac={cfg["mac"]}; '
        f':log warning "AiStudio power watchdog sent WOL to {cfg["host"]}"; }}'
    )
    return [
        f'/system scheduler remove [find where name="{cfg["scheduler_name"]}"]',
        f'/system script remove [find where name="{cfg["script_name"]}"]',
        f'/system script add name="{cfg["script_name"]}" policy=read,write,test source={{{source}}}',
        (
            f'/system scheduler add name="{cfg["scheduler_name"]}" '
            f'interval={interval_text(cfg["interval_seconds"])} start-time=startup '
            f'on-event="/system script run {cfg["script_name"]}" policy=read,write,test'
        ),
        f'/system script run "{cfg["script_name"]}"',
    ]


def host_activation_commands(config: dict[str, Any]) -> list[str]:
    cfg = validated_config(config)
    return [
        f"sudo nmcli connection modify {cfg['network_profile']} 802-3-ethernet.wake-on-lan magic",
        f"sudo ethtool -s {cfg['os_interface']} wol g",
        f"sudo loginctl enable-linger {cfg['linux_user']}",
        "sudo systemctl enable ssh",
        f"sudo ethtool {cfg['os_interface']} | grep -E 'Supports Wake-on|Wake-on'",
        f"loginctl show-user {cfg['linux_user']} -p Linger",
    ]


def rtc_systemd_files(config: dict[str, Any]) -> dict[str, str]:
    cfg = validated_config(config)
    if not cfg["rtc_enabled"]:
        return {}
    device = cfg["rtc_device"]
    if not re.fullmatch(r"/sys/class/rtc/rtc[0-9]+/wakealarm", device):
        raise ValueError("rtc_fallback.device must be a wakealarm sysfs path")
    refresh = cfg["rtc_refresh_seconds"]
    wake_after = cfg["rtc_wake_after_seconds"]
    helper = f"""#!/bin/sh
set -eu
wakealarm={device}
test -w \"$wakealarm\"
now=$(date +%s)
echo 0 > \"$wakealarm\"
echo $((now + {wake_after})) > \"$wakealarm\"
"""
    service = """[Unit]
Description=Refresh AiStudio remote PC RTC wake alarm
ConditionPathExists=/sys/class/rtc/rtc0/wakealarm

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/aistudio-rtc-wake-refresh
"""
    timer = f"""[Unit]
Description=Keep a near-term RTC wake alarm armed for AiStudio automation

[Timer]
OnBootSec=2min
OnUnitActiveSec={refresh}s
AccuracySec=30s
Unit=aistudio-rtc-wake-refresh.service

[Install]
WantedBy=timers.target
"""
    return {
        "/usr/local/sbin/aistudio-rtc-wake-refresh": helper,
        "/etc/systemd/system/aistudio-rtc-wake-refresh.service": service,
        "/etc/systemd/system/aistudio-rtc-wake-refresh.timer": timer,
    }


def install_rtc_fallback(
    config: dict[str, Any], *, root: Path = Path("/"), activate: bool = True
) -> list[str]:
    files = rtc_systemd_files(config)
    if not files:
        raise ValueError("rtc_fallback.enabled must be true")
    written: list[str] = []
    for absolute, content in files.items():
        destination = root / absolute.lstrip("/")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8", newline="\n")
        if absolute.startswith("/usr/local/sbin/"):
            destination.chmod(0o755)
        written.append(absolute)
    if activate:
        subprocess.run(["systemctl", "daemon-reload"], check=True)
        subprocess.run(
            ["systemctl", "enable", "--now", "aistudio-rtc-wake-refresh.timer"], check=True
        )
        subprocess.run(["systemctl", "start", "aistudio-rtc-wake-refresh.service"], check=True)
    return written


def magic_packet(mac: str) -> bytes:
    raw = bytes.fromhex(normalize_mac(mac).replace(":", ""))
    return b"\xff" * 6 + raw * 16


def send_magic_packet(mac: str, broadcast: str, port: int) -> None:
    packet = magic_packet(mac)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.sendto(packet, (broadcast, port))


def tcp_probe(host: str, port: int, timeout: float = 2.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def wait_for_host(host: str, port: int, timeout_seconds: int) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if tcp_probe(host, port):
            return True
        time.sleep(2)
    return tcp_probe(host, port)


def build_report(config_path: Path, *, send_wol: bool = False, wait_seconds: int = 0) -> dict[str, Any]:
    data = load_config(config_path)
    cfg = validated_config(data)
    before = tcp_probe(cfg["host"], cfg["ssh_port"])
    if send_wol and not cfg["wol_ready"]:
        raise ValueError(f"WOL blocked: {cfg['wol_reason']}")
    if send_wol:
        send_magic_packet(cfg["mac"], cfg["broadcast"], cfg["wol_port"])
    after = wait_for_host(cfg["host"], cfg["ssh_port"], wait_seconds) if send_wol and wait_seconds else before
    recovery_ready = cfg["wol_ready"] or cfg["rtc_enabled"]
    return {
        "schema_version": "1.0",
        "mode": "apply" if send_wol else "dry_run",
        "ok": recovery_ready,
        "blockers": [] if recovery_ready else ["no_power_recovery_path"],
        "recovery_id": cfg["recovery_id"],
        "host_online_before": before,
        "host_online_after": after,
        "wol_sent": send_wol,
        "wol_ready": cfg["wol_ready"],
        "wol_reason": cfg["wol_reason"],
        "initiator_kind": cfg["initiator_kind"],
        "routeros_commands": render_routeros_commands(data),
        "host_activation_commands": host_activation_commands(data),
        "rtc_fallback_enabled": cfg["rtc_enabled"],
        "rtc_systemd_files": sorted(rtc_systemd_files(data)),
        "degraded": recovery_ready and not cfg["wol_ready"],
        "secret_values_reported": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--send-wol", action="store_true")
    parser.add_argument("--install-rtc", action="store_true")
    parser.add_argument("--wait-seconds", type=int, default=0)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.install_rtc:
            if os.name != "posix" or os.geteuid() != 0:
                raise ValueError("--install-rtc must run as root on Linux")
            data = load_config(args.config.expanduser())
            written = install_rtc_fallback(data)
            print(json.dumps({"ok": True, "rtc_installed": True, "written": written}, indent=2))
            return 0
        report = build_report(args.config.expanduser(), send_wol=args.send_wol, wait_seconds=max(0, args.wait_seconds))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        report = {"schema_version": "1.0", "ok": False, "blockers": [str(exc)], "secret_values_reported": False}
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else ("ok" if report.get("ok") else "blocked"))
    return 0 if report.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main())
