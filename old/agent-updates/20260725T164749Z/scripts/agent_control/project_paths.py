"""Project path helpers for AiStudio task state.

``AiStudio/Task_manager`` is the canonical location for live automation state.
Legacy ``docs/plans`` state can still be handled by explicit migration and
cleanup tools, but runtime readers must not silently choose it.
"""

from __future__ import annotations

from pathlib import Path


CANONICAL_TASK_MANAGER = Path("AiStudio") / "Task_manager"
LEGACY_TASK_MANAGER = Path("docs") / "plans"


def task_manager_dir(project_root: Path) -> Path:
    return project_root / CANONICAL_TASK_MANAGER


def legacy_task_manager_dir(project_root: Path) -> Path:
    return project_root / LEGACY_TASK_MANAGER


def task_file(project_root: Path, name: str) -> Path:
    return task_manager_dir(project_root) / name


def task_relpath(project_root: Path, name: str) -> str:
    return task_file(project_root, name).relative_to(project_root).as_posix()


def task_reports_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "reports"


def task_reports_relpath(project_root: Path) -> str:
    return task_reports_dir(project_root).relative_to(project_root).as_posix()


def task_process_logs_dir(project_root: Path) -> Path:
    return task_manager_dir(project_root) / "process-logs"
