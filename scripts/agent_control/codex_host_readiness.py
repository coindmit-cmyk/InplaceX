from __future__ import annotations

import math
import os
import shutil
import subprocess
import time
from dataclasses import dataclass
from typing import Any


AUTH_MISSING_MARKERS = (
    "no codex credentials were found",
    "missing bearer or basic authentication",
    "unauthorized",
)
NON_TTY_MARKERS = (
    "stdin is not a terminal",
)
DEFAULT_DOCTOR_TIMEOUT_SECONDS = 75.0
DEFAULT_SMOKE_TIMEOUT_SECONDS = 75.0
MAX_READINESS_TIMEOUT_SECONDS = 3600.0


@dataclass(frozen=True)
class CodexHostReadiness:
    ok: bool
    reason: str
    codex_bin: str
    codex_path: str | None = None
    doctor_checked: bool = False
    doctor_exit_code: int | None = None
    doctor_attempts: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "reason": self.reason,
            "codex_bin": self.codex_bin,
            "codex_path": self.codex_path,
            "doctor_checked": self.doctor_checked,
            "doctor_exit_code": self.doctor_exit_code,
            "doctor_attempts": self.doctor_attempts,
        }


def _bounded_timeout(candidate: object, default: float) -> float:
    try:
        value = float(candidate)
    except (TypeError, ValueError, OverflowError):
        value = default
    if not math.isfinite(value) or value > MAX_READINESS_TIMEOUT_SECONDS:
        value = default
    return max(1.0, value)


def _resolve_doctor_timeout(timeout_seconds: float | None) -> float:
    if timeout_seconds is not None:
        candidate = timeout_seconds
    else:
        raw = os.environ.get("AGENT_CODEX_DOCTOR_TIMEOUT_SECONDS")
        candidate = raw if raw else DEFAULT_DOCTOR_TIMEOUT_SECONDS
    return _bounded_timeout(candidate, DEFAULT_DOCTOR_TIMEOUT_SECONDS)


def _resolve_smoke_timeout(timeout_seconds: float | None) -> float:
    if timeout_seconds is not None:
        candidate = timeout_seconds
    else:
        raw = os.environ.get("AGENT_CODEX_SMOKE_TIMEOUT_SECONDS")
        candidate = raw if raw else DEFAULT_SMOKE_TIMEOUT_SECONDS
    return _bounded_timeout(candidate, DEFAULT_SMOKE_TIMEOUT_SECONDS)


def codex_host_readiness(
    codex_bin: str,
    *,
    require_auth: bool = True,
    timeout_seconds: float | None = None,
    smoke_timeout_seconds: float | None = None,
    model: str | None = None,
    ignore_user_config: bool = True,
    doctor_attempts: int = 2,
    retry_delay_seconds: float = 1.0,
) -> CodexHostReadiness:
    codex_bin = str(codex_bin or "codex")
    codex_path = shutil.which(codex_bin)
    if not codex_path:
        return CodexHostReadiness(False, "codex_executable_missing", codex_bin)
    if not require_auth:
        return CodexHostReadiness(True, "codex_executable_available", codex_bin, codex_path)

    env = os.environ.copy()
    env.setdefault("NO_COLOR", "1")
    attempts = max(1, int(doctor_attempts))
    resolved_timeout_seconds = _resolve_doctor_timeout(timeout_seconds)
    resolved_smoke_timeout_seconds = _resolve_smoke_timeout(smoke_timeout_seconds)
    last_exit_code: int | None = None
    saw_doctor_timeout = False
    for attempt in range(1, attempts + 1):
        proc = None
        try:
            proc = subprocess.run(
                [codex_bin, "doctor"],
                text=True,
                capture_output=True,
                check=False,
                timeout=resolved_timeout_seconds,
                env=env,
            )
        except subprocess.TimeoutExpired:
            saw_doctor_timeout = True
        except OSError:
            return CodexHostReadiness(False, "codex_doctor_failed", codex_bin, codex_path, True, None, attempt)

        combined = ""
        if proc is not None:
            last_exit_code = proc.returncode
            combined = f"{proc.stdout}\n{proc.stderr}".lower()
            if any(marker in combined for marker in AUTH_MISSING_MARKERS):
                return CodexHostReadiness(False, "codex_auth_missing", codex_bin, codex_path, True, proc.returncode, attempt)
            if proc.returncode == 0:
                return CodexHostReadiness(True, "codex_ready", codex_bin, codex_path, True, proc.returncode, attempt)

        # `codex doctor` can require an interactive terminal or hang on Windows.
        # In both cases the non-interactive exec path is the authoritative worker
        # readiness probe and keeps the auth gate fail-closed.
        if proc is None or any(marker in combined for marker in NON_TTY_MARKERS):
            try:
                smoke_command = [
                    codex_bin,
                    "exec",
                    "--json",
                    "--ephemeral",
                    "--skip-git-repo-check",
                ]
                if ignore_user_config:
                    smoke_command.append("--ignore-user-config")
                if model:
                    smoke_command.extend(["--model", model])
                smoke_command.extend(
                    [
                        "--dangerously-bypass-approvals-and-sandbox",
                        "Respond with OK only.",
                    ]
                )
                smoke = subprocess.run(
                    smoke_command,
                    stdin=subprocess.DEVNULL,
                    text=True,
                    capture_output=True,
                    check=False,
                    timeout=resolved_smoke_timeout_seconds,
                    env=env,
                )
            except subprocess.TimeoutExpired:
                if attempt < attempts and retry_delay_seconds > 0:
                    time.sleep(retry_delay_seconds)
                continue
            except OSError:
                return CodexHostReadiness(False, "codex_doctor_failed", codex_bin, codex_path, True, None, attempt)
            smoke_combined = f"{smoke.stdout}\n{smoke.stderr}".lower()
            if any(marker in smoke_combined for marker in AUTH_MISSING_MARKERS):
                return CodexHostReadiness(False, "codex_auth_missing", codex_bin, codex_path, True, smoke.returncode, attempt)
            if smoke.returncode == 0:
                doctor_exit_code = proc.returncode if proc is not None else None
                return CodexHostReadiness(True, "codex_ready", codex_bin, codex_path, True, doctor_exit_code, attempt)
        if attempt < attempts and retry_delay_seconds > 0:
            time.sleep(retry_delay_seconds)
    reason = "codex_doctor_timeout" if saw_doctor_timeout else "codex_doctor_failed"
    return CodexHostReadiness(False, reason, codex_bin, codex_path, True, last_exit_code, attempts)


def codex_host_available(codex_bin: str, *, require_auth: bool = True) -> bool:
    return codex_host_readiness(codex_bin, require_auth=require_auth).ok
