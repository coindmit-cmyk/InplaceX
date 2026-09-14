#!/usr/bin/env python3
"""Render compact, read-only execution progress and authorized disclosure.

The renderer accepts already validated lifecycle data.  It deliberately has no
imports from the Router, scheduler, launcher or integration writer: rendering
is presentation only and cannot authorize or mutate execution.
"""

from __future__ import annotations

from copy import deepcopy
from typing import Any


COMPLETED_UNIT_STATUSES = {"accepted", "completed", "success"}
NO_FOOTER_OUTPUT_CLASSES = {
    "child_stdout", "child_stderr", "stream_fragment", "heartbeat",
    "lock_event", "scheduler_log", "low_level_event", "machine_artifact",
}
STATES = {
    "advisory", "planned", "running", "completed", "degraded", "blocked",
    "override", "fallback", "terminal",
}

LABELS = {
    "ru": {
        "parallel": "Параллельные проверки",
        "required": "Обязательные проверки",
        "next": "Следующий шаг",
        "recommended": "Рекомендуемый режим",
        "selected": "Выбранный режим",
        "actual": "Фактически использованный режим",
        "status": "Статус",
        "synthesis": "Синтез результата",
        "planned": "запланировано",
        "router_pending": "окончательный запуск выберет Router.",
        "waiting": "ожидает остальные проверки.",
        "done": "завершён",
        "degraded": "завершён с остаточным риском.",
        "blocked": "Результат пока не собран",
        "terminal": "Работа завершена",
        "no_next": "Следующий агентный запуск не требуется.",
        "reason": "Причина",
        "fallback": "Резервный режим",
        "override": "Изменение",
        "required_missing": "Не завершена обязательная проверка",
        "optional_missing": "Пропущена дополнительная проверка",
        "next_action": "Следующий шаг",
        "residual_risk": "Остаточный риск",
        "none": "не указан",
    },
    "en": {
        "parallel": "Parallel checks",
        "required": "Required checks",
        "next": "Next step",
        "recommended": "Recommended mode",
        "selected": "Selected mode",
        "actual": "Actually used mode",
        "status": "Status",
        "synthesis": "Result synthesis",
        "planned": "planned",
        "router_pending": "Router will choose the final launch.",
        "waiting": "waiting for remaining checks.",
        "done": "complete",
        "degraded": "complete with residual risk.",
        "blocked": "Result is not ready",
        "terminal": "Work complete",
        "no_next": "No further agent run is required.",
        "reason": "Reason",
        "fallback": "Fallback mode",
        "override": "Change",
        "required_missing": "Required check not complete",
        "optional_missing": "Optional check skipped",
        "next_action": "Next step",
        "residual_risk": "Residual risk",
        "none": "not specified",
    },
}


def _text(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def _first_text(*values: Any, default: str = "") -> str:
    for value in values:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return default


def _mapping(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _labels(locale: str) -> dict[str, str]:
    return LABELS.get(locale.split("-")[0].lower(), LABELS["en"])


def _presentation(payload: dict[str, Any]) -> dict[str, Any]:
    return _mapping(payload.get("execution_presentation") or payload.get("presentation"))


def _output_class(payload: dict[str, Any]) -> str:
    producer = _mapping(payload.get("producer"))
    return _first_text(
        producer.get("output_class"),
        payload.get("output_class"),
    )


def _route(payload: dict[str, Any], field: str) -> dict[str, Any]:
    if field == "recommended":
        return _mapping(payload.get("next_run_recommendation"))
    decision = _mapping(payload.get("router_decision"))
    return _mapping(decision.get("selected"))


def _mode(route: dict[str, Any], labels: dict[str, str]) -> str:
    profile = _text(route.get("capability_profile"))
    effort = _text(route.get("reasoning_effort"))
    if not profile and not effort:
        return labels["none"]
    profile_label = profile.replace("_", " ").title() if profile else labels["none"]
    effort_label = effort.replace("_", " ").title() if effort else labels["none"]
    return f"{profile_label} — {effort_label}"


def _state(payload: dict[str, Any]) -> str:
    presentation = _presentation(payload)
    explicit = _first_text(
        presentation.get("state"),
        presentation.get("variant"),
        payload.get("execution_state"),
        payload.get("lifecycle_state"),
    ).lower()
    aliases = {"normal": "planned", "recommendation_only": "advisory"}
    explicit = aliases.get(explicit, explicit)
    if explicit in STATES:
        return explicit

    next_run = payload.get("next_run")
    recommendation = _mapping(payload.get("next_run_recommendation"))
    if next_run == "none" or recommendation.get("status") == "terminal_none":
        return "terminal"

    integration = _mapping(payload.get("integration"))
    integration_status = _first_text(integration.get("status"), payload.get("integration_status"))
    if integration_status == "accepted_with_residual_risk":
        return "degraded"
    if integration_status in {"integration_incomplete", "owner_decision_required", "rejected", "rerun_required"}:
        return "blocked"
    if integration_status == "ready_for_finalizer":
        return "completed"

    parallel = _mapping(payload.get("parallel_work") or payload.get("plan"))
    parallel_status = _first_text(parallel.get("status")).lower()
    if parallel_status in {"running"}:
        return "running"
    if parallel_status in {"completed", "awaiting_integration"}:
        return "completed"

    decision = _mapping(payload.get("router_decision"))
    if decision.get("override", {}).get("applied") is True:
        return "override"
    if _first_text(decision.get("status")).lower() in {"fallback", "fallback_selected"}:
        return "fallback"
    if _route(payload, "selected"):
        return "planned"
    return "advisory"


def _progress(payload: dict[str, Any]) -> dict[str, int]:
    source = _mapping(
        payload.get("progress")
        or _mapping(payload.get("execution_presentation")).get("progress")
        or _mapping(payload.get("parallel_work")).get("progress")
        or _mapping(payload.get("plan")).get("progress")
    )
    result = {
        "completed_units": max(0, int(source.get("completed_units", source.get("completed", 0)) or 0)),
        "total_units": max(0, int(source.get("total_units", source.get("total", 0)) or 0)),
        "required_completed": max(0, int(source.get("required_completed", 0) or 0)),
        "required_total": max(0, int(source.get("required_total", 0) or 0)),
    }
    units = payload.get("work_units") or _mapping(payload.get("parallel_work")).get("work_units")
    if isinstance(units, list) and units:
        result["total_units"] = len(units)
        result["completed_units"] = sum(
            _first_text(_mapping(unit).get("status")).lower() in COMPLETED_UNIT_STATUSES
            for unit in units
        )
        result["required_total"] = sum(_mapping(unit).get("criticality") == "required" for unit in units)
        result["required_completed"] = sum(
            _mapping(unit).get("criticality") == "required"
            and _first_text(_mapping(unit).get("status")).lower() in COMPLETED_UNIT_STATUSES
            for unit in units
        )
    lane_coverage = _mapping(payload.get("integration")).get("lane_coverage")
    if isinstance(lane_coverage, list) and lane_coverage and not units:
        result["total_units"] = len(lane_coverage)
        result["completed_units"] = sum(
            _first_text(_mapping(lane).get("status")).lower() in COMPLETED_UNIT_STATUSES
            for lane in lane_coverage
        )
    for key in ("completed_units", "required_completed"):
        result[key] = min(result[key], result["total_units"])
    if result["required_total"]:
        result["required_completed"] = min(result["required_completed"], result["required_total"])
    return result


def _reason(payload: dict[str, Any], state: str, labels: dict[str, str]) -> str:
    presentation = _presentation(payload)
    integration = _mapping(payload.get("integration"))
    reason = _first_text(
        presentation.get("human_reason"),
        presentation.get("reason"),
        payload.get("human_reason"),
        _mapping(payload.get("terminal_reason")).get("human_reason"),
    )
    if not reason:
        blockers = integration.get("blockers") or payload.get("blockers")
        if isinstance(blockers, list) and blockers:
            reason = _first_text(_mapping(blockers[0]).get("human_reason"), _mapping(blockers[0]).get("reason"))
    if not reason and state == "degraded":
        reason = _residual_risk(payload)
    return reason or labels["none"]


def _next_step(payload: dict[str, Any], labels: dict[str, str]) -> str:
    presentation = _presentation(payload)
    return _first_text(
        presentation.get("next_action"),
        payload.get("next_action"),
        payload.get("next_owner"),
        default=labels["none"],
    )


def _residual_risk(payload: dict[str, Any]) -> str:
    integration = _mapping(payload.get("integration"))
    risks = _mapping(integration.get("synthesis")).get("residual_risks") or payload.get("residual_risks")
    if isinstance(risks, list) and risks:
        return _text(risks[0])
    return _text(_presentation(payload).get("residual_risk"))


def _line_counts(progress: dict[str, int], labels: dict[str, str]) -> list[str]:
    lines = []
    if progress["total_units"]:
        lines.append(
            f"{labels['parallel']}: {progress['completed_units']} из {progress['total_units']}"
            if labels is LABELS["ru"]
            else f"{labels['parallel']}: {progress['completed_units']} of {progress['total_units']} completed"
        )
    if progress["required_total"]:
        lines.append(
            f"{labels['required']}: {progress['required_completed']} из {progress['required_total']}"
            if labels is LABELS["ru"]
            else f"{labels['required']}: {progress['required_completed']} of {progress['required_total']} completed"
        )
    return lines


def _disclosure(payload: dict[str, Any], progress: dict[str, int], state: str) -> dict[str, Any]:
    """Return a whitelisted, copied technical view; never expose raw payload."""
    decision = _mapping(payload.get("router_decision"))
    parallel = _mapping(payload.get("parallel_work") or payload.get("plan"))
    integration = _mapping(payload.get("integration"))
    disclosure = {
        "state": state,
        "contract_versions": deepcopy(payload.get("contract_versions") or payload.get("contract_version")),
        "correlation_id": payload.get("correlation_id"),
        "router_decision_id": decision.get("decision_id"),
        "plan_id": parallel.get("plan_id") or payload.get("plan_id"),
        "plan_revision": parallel.get("revision") or payload.get("plan_revision"),
        "plan_digest": parallel.get("plan_content_digest") or payload.get("plan_content_digest"),
        "base_ref": parallel.get("base_ref") or payload.get("base_ref"),
        "lane_counts": {
            "completed": progress["completed_units"],
            "total": progress["total_units"],
            "required_completed": progress["required_completed"],
            "required_total": progress["required_total"],
        },
        "selected_route": deepcopy(decision.get("selected")) if decision.get("selected") else None,
        "actual_route": deepcopy(_mapping(payload.get("actual_use") or payload.get("execution_evidence")).get("actually_used")),
        "work_unit_statuses": deepcopy(payload.get("work_unit_statuses") or parallel.get("work_units")),
        "retry_timeout_cancellation": deepcopy(payload.get("retry_timeout_cancellation")),
        "result_and_evidence_refs": deepcopy(payload.get("result_and_evidence_refs")),
        "conflicts_and_resolution": deepcopy(integration.get("conflicts")),
        "residual_risk": deepcopy(_mapping(integration.get("synthesis")).get("residual_risks") or payload.get("residual_risks")),
        "finalizer_status": payload.get("finalizer_status") or integration.get("finalizer_status"),
        "reason_codes": deepcopy(payload.get("reason_codes") or _mapping(integration.get("audit")).get("reason_codes")),
    }
    return {key: value for key, value in disclosure.items() if value not in (None, "", [], {})}


def render_footer(payload: dict[str, Any], locale: str = "ru", debug: bool = False) -> dict[str, Any]:
    """Render a bounded footer and optional authorized disclosure.

    The returned object is detached from ``payload``.  ``debug`` is only
    honored when the input presentation explicitly grants disclosure access.
    """
    if not isinstance(payload, dict):
        raise TypeError("payload must be an object")
    labels = _labels(locale)
    output_class = _output_class(payload)
    presentation = _presentation(payload)
    state = _state(payload)
    progress = _progress(payload)
    disclosure_allowed = (
        presentation.get("debug_details_available") is True
        or presentation.get("debug_authorized") is True
        or payload.get("debug_authorized") is True
    )
    disclosure = _disclosure(payload, progress, state) if debug and disclosure_allowed else None
    if output_class in NO_FOOTER_OUTPUT_CLASSES or presentation.get("footer_required") is False:
        return {"footer_text": "", "lines": [], "variant": "none", "disclosure": disclosure}

    recommended = _route(payload, "recommended")
    selected = _route(payload, "selected")
    next_step = _next_step(payload, labels)
    reason = _reason(payload, state, labels)
    lines: list[str] = []

    if state == "terminal":
        lines = [labels["terminal"], labels["no_next"]]
    elif state == "advisory":
        lines = [f"{labels['next']}: {next_step}"]
        lines.append(f"{labels['recommended']}: {_mode(recommended, labels)}")
        lines.append(f"{labels['status']}: {labels['router_pending']}")
    elif state in {"override", "fallback"}:
        lines = [f"{labels['recommended']}: {_mode(recommended, labels)}"]
        route_label = labels["fallback"] if state == "fallback" else labels["selected"]
        lines.append(f"{route_label}: {_mode(selected, labels)}")
        lines.append(f"{labels['reason']}: {reason}")
    elif state == "blocked":
        lines = [labels["blocked"]]
        lines.append(f"{labels['reason']}: {reason}")
        lines.append(f"{labels['next_action']}: {next_step}")
    elif state == "degraded":
        lines = _line_counts(progress, labels)
        lines.append(f"{labels['optional_missing']}: {reason}")
        risk = _residual_risk(payload)
        suffix = f": {risk}" if risk else ""
        lines.append(f"{labels['synthesis']}: {labels['degraded']}{suffix}")
    elif state == "completed":
        lines = _line_counts(progress, labels)
        lines.append(f"{labels['synthesis']}: {labels['done']}")
        lines.append(f"{labels['next']}: {next_step}")
    else:  # planned/running
        lines = (
            [f"{labels['parallel']}: {labels['planned']} {progress['total_units']}"]
            if state == "planned" and progress["total_units"]
            else _line_counts(progress, labels)
        )
        if state == "running":
            lines.append(f"{labels['synthesis']}: {labels['waiting']}")
        elif selected:
            lines.append(f"{labels['selected']}: {_mode(selected, labels)}")
        lines.append(f"{labels['next']}: {next_step}") if next_step != labels["none"] else None

    # Safety and readability take precedence over optional details.
    lines = [line for line in lines if line.strip()][:4]
    return {
        "footer_text": "\n".join(lines),
        "lines": lines,
        "variant": state,
        "disclosure": disclosure,
    }


def render_execution_footer(payload: dict[str, Any], locale: str = "ru", debug: bool = False) -> dict[str, Any]:
    """Compatibility alias emphasizing that this renderer covers execution UX."""
    return render_footer(payload, locale=locale, debug=debug)


__all__ = ["render_footer", "render_execution_footer"]
