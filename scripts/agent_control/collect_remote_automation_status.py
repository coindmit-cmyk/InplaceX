#!/usr/bin/env python3
"""Collect remote automation timer status for the dashboard."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
from pathlib import Path
from typing import Any


AUTOMATION_PREFIXES = ("auto-", "agent-control-")
AUTOMATION_NAME_PARTS = ("dashboard-publisher",)


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def usec_to_iso(value: Any) -> str | None:
    try:
        raw = int(value)
    except (TypeError, ValueError):
        return None
    if raw <= 0:
        return None
    return dt.datetime.fromtimestamp(raw / 1_000_000, dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def systemd_timestamp_to_iso(value: Any) -> str | None:
    text = str(value or "").strip()
    if not text or text.lower() in {"n/a", "never"}:
        return None
    try:
        parsed = dt.datetime.strptime(text, "%a %Y-%m-%d %H:%M:%S UTC")
    except ValueError:
        return None
    return parsed.replace(tzinfo=dt.timezone.utc).isoformat().replace("+00:00", "Z")


def timer_time_to_iso(primary: Any, fallback: Any) -> str | None:
    return usec_to_iso(primary) or systemd_timestamp_to_iso(fallback)


def run(command: list[str], *, timeout_seconds: int = 20) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            command,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=timeout_seconds,
        )
    except OSError as exc:
        return 127, "", str(exc)
    except subprocess.TimeoutExpired as exc:
        stdout = exc.stdout if isinstance(exc.stdout, str) else ""
        stderr = exc.stderr if isinstance(exc.stderr, str) else ""
        return 124, stdout, stderr or f"command timed out after {timeout_seconds}s"
    return proc.returncode, proc.stdout, proc.stderr


def systemctl_base(scope: str) -> list[str]:
    return ["systemctl", "--user"] if scope == "user" else ["systemctl"]


def journalctl_base(scope: str) -> list[str]:
    return ["journalctl", "--user"] if scope == "user" else ["journalctl"]


def systemctl_json_timers(scope: str = "user") -> list[dict[str, Any]]:
    code, stdout, _stderr = run([*systemctl_base(scope), "list-timers", "--all", "--output=json", "--no-pager"])
    if code != 0 or not stdout.strip():
        return []
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError:
        return []
    if not isinstance(data, list):
        return []
    return [item for item in data if isinstance(item, dict)]


def systemctl_show(unit: str, scope: str = "user") -> dict[str, str]:
    props = [
        "Id",
        "ActiveState",
        "SubState",
        "Result",
        "ExecMainStatus",
        "ExecMainStartTimestamp",
        "ExecMainExitTimestamp",
        "FragmentPath",
        "NextElapseUSecRealtime",
        "LastTriggerUSec",
    ]
    code, stdout, _stderr = run([*systemctl_base(scope), "show", unit, "--no-pager", *[f"-p{x}" for x in props]])
    if code != 0:
        return {}
    values: dict[str, str] = {}
    for line in stdout.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


def journal_counts(service_unit: str, since: str | None = None, scope: str = "user") -> dict[str, int]:
    command = [*journalctl_base(scope), "-u", service_unit, "--no-pager", "--output=json", "-n", "500"]
    if since:
        command.extend(["--since", since])
    code, stdout, _stderr = run(command, timeout_seconds=3)
    if code != 0:
        return {"success": 0, "failed": 0}
    success = 0
    failed = 0
    for line in stdout.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        message = str(item.get("MESSAGE") or "")
        if message.startswith(f"Finished {service_unit}"):
            success += 1
        elif message.startswith(f"Failed {service_unit}") or "Main process exited" in message:
            failed += 1
    return {"success": success, "failed": failed}


def is_automation_timer(timer_unit: str) -> bool:
    name = timer_unit.lower()
    return (
        name.startswith(AUTOMATION_PREFIXES)
        or name.endswith("-status-orchestrator.timer")
        or any(part in name for part in AUTOMATION_NAME_PARTS)
    )


def classify_role(timer_unit: str, service_unit: str) -> str:
    name = (timer_unit or service_unit).lower()
    if "status-orchestrator" in name or "status_orchestrator" in name:
        return "orchestrator"
    if "dashboard" in name:
        return "dashboard"
    for role in ("dispatcher", "workers", "worker", "architect", "integrator", "finalizer", "readonly"):
        if role in name:
            return "worker" if role == "workers" else role
    return "automation"


def collect(scopes: list[str] | None = None) -> dict[str, Any]:
    timers = []
    selected_scopes = scopes or ["user", "system"]
    for scope in selected_scopes:
        for item in systemctl_json_timers(scope):
            timer_unit = str(item.get("unit") or "")
            service_unit = str(item.get("activates") or "")
            if not timer_unit.endswith(".timer"):
                continue
            if not is_automation_timer(timer_unit):
                continue
            timer = systemctl_show(timer_unit, scope)
            service = systemctl_show(service_unit, scope) if service_unit else {}
            counts_total = journal_counts(service_unit, scope=scope) if service_unit else {"success": 0, "failed": 0}
            counts_today = journal_counts(service_unit, "today", scope=scope) if service_unit else {"success": 0, "failed": 0}
            timers.append(
                {
                    "timer_unit": timer_unit,
                    "service_unit": service_unit,
                    "scope": scope,
                    "role": classify_role(timer_unit, service_unit),
                    "next_at": timer_time_to_iso(item.get("next"), timer.get("NextElapseUSecRealtime")),
                    "last_at": timer_time_to_iso(item.get("last"), timer.get("LastTriggerUSec")),
                    "active_state": service.get("ActiveState"),
                    "sub_state": service.get("SubState"),
                    "result": service.get("Result"),
                    "exit_status": service.get("ExecMainStatus"),
                    "success_total": counts_total["success"],
                    "failed_total": counts_total["failed"],
                    "success_today": counts_today["success"],
                    "failed_today": counts_today["failed"],
                    "service_started_at": service.get("ExecMainStartTimestamp") or None,
                    "service_exited_at": service.get("ExecMainExitTimestamp") or None,
                    "fragment_path": service.get("FragmentPath") or None,
                }
            )
    timers.sort(key=lambda row: row.get("next_at") or "9999")
    return {
        "schema_version": "1.0",
        "generated_at": now_utc(),
        "source": "systemd_timers",
        "scopes": selected_scopes,
        "timers": timers,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--output", default="")
    parser.add_argument("--scope", choices=("user", "system", "all"), default="all")
    args = parser.parse_args()

    runtime = Path(args.runtime_root).expanduser()
    output = Path(args.output).expanduser() if args.output else runtime / "automation-status" / "latest.json"
    scopes = ["user", "system"] if args.scope == "all" else [args.scope]
    payload = collect(scopes)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"ok": True, "output": str(output), "timers": len(payload["timers"])}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
