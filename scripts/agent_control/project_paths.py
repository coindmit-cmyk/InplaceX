"""Project path helpers for AiStudio task state.

``AiStudio/Task_manager`` is the canonical location for live automation state.
Legacy ``docs/plans`` state can still be handled by explicit migration and
cleanup tools, but runtime readers must not silently choose it.
"""

from __future__ import annotations

import os
from pathlib import Path


CANONICAL_TASK_MANAGER = Path("AiStudio") / "Task_manager"
LEGACY_TASK_MANAGER = Path("docs") / "plans"


def task_manager_dir(project_root: Path) -> Path:
    override = os.environ.get("AISTUDIO_TASK_MANAGER_DIR", "").strip()
    if override:
        return Path(override).expanduser().resolve()
    if os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY", "").strip() == "postgres":
        raise RuntimeError(
            "PostgreSQL Task Control authority requires a managed project session"
        )
    return project_root / CANONICAL_TASK_MANAGER


def legacy_task_manager_dir(project_root: Path) -> Path:
    return project_root / LEGACY_TASK_MANAGER


def task_file(project_root: Path, name: str) -> Path:
    return task_manager_dir(project_root) / name


def task_relpath(project_root: Path, name: str) -> str:
    # Repository references remain canonical even when file I/O is redirected
    # to an external PostgreSQL compatibility-session mirror.
    return (CANONICAL_TASK_MANAGER / name).as_posix()


def task_reports_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "reports"


def task_reports_relpath(project_root: Path) -> str:
    return (CANONICAL_TASK_MANAGER / "reports").as_posix()


def task_process_logs_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "process-logs"
