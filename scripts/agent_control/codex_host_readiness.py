from __future__ import annotations

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


def codex_host_readiness(
    codex_bin: str,
    *,
    require_auth: bool = True,
    timeout_seconds: float = 75.0,
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
    last_exit_code: int | None = None
    for attempt in range(1, attempts + 1):
        try:
            proc = subprocess.run(
                [codex_bin, "doctor"],
                text=True,
                capture_output=True,
                check=False,
                timeout=max(1.0, timeout_seconds),
                env=env,
            )
        except subprocess.TimeoutExpired:
            return CodexHostReadiness(False, "codex_doctor_timeout", codex_bin, codex_path, True, None, attempt)
        except OSError:
            return CodexHostReadiness(False, "codex_doctor_failed", codex_bin, codex_path, True, None, attempt)

        last_exit_code = proc.returncode
        combined = f"{proc.stdout}\n{proc.stderr}".lower()
        if any(marker in combined for marker in AUTH_MISSING_MARKERS):
            return CodexHostReadiness(False, "codex_auth_missing", codex_bin, codex_path, True, proc.returncode, attempt)
        if proc.returncode == 0:
            return CodexHostReadiness(True, "codex_ready", codex_bin, codex_path, True, proc.returncode, attempt)
        if any(marker in combined for marker in NON_TTY_MARKERS):
            try:
                smoke = subprocess.run(
                    [
                        codex_bin,
                        "exec",
                        "--json",
                        "--ephemeral",
                        "--skip-git-repo-check",
                        "--dangerously-bypass-approvals-and-sandbox",
                        "Respond with OK only.",
                    ],
                    text=True,
                    capture_output=True,
                    check=False,
                    timeout=max(1.0, timeout_seconds),
                    env=env,
                )
            except subprocess.TimeoutExpired:
                return CodexHostReadiness(False, "codex_doctor_timeout", codex_bin, codex_path, True, None, attempt)
            except OSError:
                return CodexHostReadiness(False, "codex_doctor_failed", codex_bin, codex_path, True, None, attempt)
            smoke_combined = f"{smoke.stdout}\n{smoke.stderr}".lower()
            if any(marker in smoke_combined for marker in AUTH_MISSING_MARKERS):
                return CodexHostReadiness(False, "codex_auth_missing", codex_bin, codex_path, True, smoke.returncode, attempt)
            if smoke.returncode == 0:
                return CodexHostReadiness(True, "codex_ready", codex_bin, codex_path, True, proc.returncode, attempt)
        if attempt < attempts and retry_delay_seconds > 0:
            time.sleep(retry_delay_seconds)
    return CodexHostReadiness(False, "codex_doctor_failed", codex_bin, codex_path, True, last_exit_code, attempts)


def codex_host_available(codex_bin: str, *, require_auth: bool = True) -> bool:
    return codex_host_readiness(codex_bin, require_auth=require_auth).ok
