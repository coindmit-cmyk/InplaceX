#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
import shlex
import sys
from pathlib import Path
from typing import Iterable

import yaml

REQUIRED_FIXTURE_CATEGORIES = {
    "pass_baseline",
    "pass_artifact",
    "fail_continue_on_error",
    "fail_instrumentation_if_false",
    "fail_boot_wait_bypass",
    "fail_kvm_skipped",
    "fail_release_missing_assemble",
    "fail_artifact_static_signing",
    "fail_emulator_noop",
    "fail_connected_noop",
    "fail_short_circuit",
}

CONTROL_KEYWORDS = {"if", "then", "fi", "do", "done", "for", "while", "elif", "else", "case", "esac", "in"}


def _fail(messages: list[str], message: str) -> None:
    messages.append(message)


def _strip_comments(line: str) -> str:
    if "#" not in line:
        return line

    in_single = False
    in_double = False
    for index, char in enumerate(line):
        if char == "'" and not in_double:
            in_single = not in_single
            continue
        if char == '"' and not in_single:
            in_double = not in_double
            continue
        if char == "#" and not in_single and not in_double:
            return line[:index]
    return line


def _normalize_script_lines(script: str) -> list[str]:
    lines: list[str] = []
    continuation = ""
    for raw_line in script.splitlines():
        line = _strip_comments(raw_line).rstrip()
        if not line.strip():
            continue

        if continuation:
            line = continuation + line.lstrip()
            continuation = ""

        if line.endswith("\\"):
            continuation = line[:-1] + " "
            continue

        lines.append(line)

    if continuation:
        lines.append(continuation.strip())

    return lines


def _split_segments(text: str) -> list[str]:
    normalized = "\n".join(_normalize_script_lines(text))
    split_ops = re.split(r"[\n;]", normalized)

    segments: list[str] = []
    for segment in split_ops:
        segment = segment.strip()
        if not segment:
            continue
        for logical in segment.split("&&"):
            logical = logical.strip()
            if not logical:
                continue
            if "||" in logical:
                parts = [part.strip() for part in logical.split("||") if part.strip()]
                segments.extend(parts)
            else:
                segments.append(logical)

    return segments


def _iter_commands(script: str) -> list[list[str]]:
    commands: list[list[str]] = []
    for segment in _split_segments(script):
        for pipeline_part in segment.split("|"):
            candidate = pipeline_part.strip()
            if not candidate:
                continue
            try:
                words = shlex.split(candidate, posix=True)
            except ValueError:
                continue

            idx = 0
            while idx < len(words) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", words[idx]):
                idx += 1
            if idx >= len(words):
                continue
            if words[idx] in CONTROL_KEYWORDS:
                continue
            commands.append(words[idx:])
    return commands


def _contains_command(commands: list[list[str]], command: str, required_args: Iterable[str] = ()) -> bool:
    required_args = tuple(required_args)
    for words in commands:
        if not words or words[0] != command:
            continue
        if required_args and not all(arg in words for arg in required_args):
            continue
        if command == "echo":
            continue
        return True
    return False


def _contains_artifact_identity(commands: list[list[str]]) -> bool:
    return any(
        any("scripts/ci/artifact_identity.sh" in token for token in words)
        for words in commands
    )


def _contains_gradle_task(commands: list[list[str]], task: str) -> bool:
    for words in commands:
        if not words:
            continue
        if words[0] not in {"./gradlew", "gradlew"}:
            continue
        if task in words:
            return True
    return False


def _contains_connected_test(commands: list[list[str]]) -> bool:
    return _contains_command(commands, "./gradlew", (":app:connectedDebugAndroidTest",))


def _contains_avdmanager_create(commands: list[list[str]], text: str) -> bool:
    for words in commands:
        if not words:
            continue
        if words[0] != "avdmanager":
            continue
        if "create" in words and "avd" in words:
            return True
    return re.search(r"\bavdmanager\b[^\n]*\bcreate\b[^\n]*\bavd\b", text) is not None


def _contains_emulator_start(commands: list[list[str]]) -> bool:
    for words in commands:
        if not words:
            continue
        if words[0] == "emulator" and "-avd" in words:
            return True
    return False


def _has_bounded_boot_wait(text: str) -> bool:
    return (
        "sys.boot_completed" in text
        and re.search(r"for\s+\w+\s+in\s+\$\(seq\s+1\s+\d+\)", text) is not None
        and "sleep 1" in text
        and "exit 1" in text
    )


def _has_kvm_guard(commands: list[list[str]], text: str) -> bool:
    has_token = any(any("/dev/kvm" in token for token in words) for words in commands)
    has_check = any(
        words
        and words[0] in {"ls", "test", "[", "[[", "if"}
        and any("/dev/kvm" in token for token in words)
        for words in commands
    )
    return has_token and has_check


def _has_no_step_conditionals_or_failures(steps: list, location: str, messages: list[str]) -> None:
    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            continue

        if "if" in step:
            _fail(messages, f"{location} step {index + 1} has conditional if")
        if "continue-on-error" in step:
            _fail(messages, f"{location} step {index + 1} has continue-on-error")


def _collect_step_runs(job: dict) -> tuple[str, list[list[str]], list[dict]]:
    run_texts: list[str] = []
    commands: list[list[str]] = []
    steps: list[dict] = []

    for step in job.get("steps", []) if isinstance(job, dict) else []:
        if not isinstance(step, dict):
            continue
        steps.append(step)
        run_text = step.get("run")
        if not isinstance(run_text, str):
            continue
        run_texts.append(run_text)
        commands.extend(_iter_commands(run_text))

    return "\n".join(run_texts), commands, steps


def _has_needs(job: dict, expected: str) -> bool:
    needs = job.get("needs")
    if isinstance(needs, str):
        return needs == expected
    if isinstance(needs, list):
        return expected in needs
    return False


def _validate_artifact_identity_text(text: str, messages: list[str], tag: str = "artifact_identity.sh") -> None:
    has_apksigner_verify = bool(
        re.search(r"(^|[^A-Za-z0-9_])(?:\$\{?apksigner\}?|apksigner)\b[^\n]*\bverify\b", text)
    )
    if not has_apksigner_verify:
        _fail(messages, f"{tag} must execute apksigner verify for signing evidence")
        return

    if re.search(r"^\s*signing_status\s*=\s*[\"']?unknown[\"']?\s*$", text, re.MULTILINE):
        _fail(messages, f"{tag} must not assign signing_status unknown")

    if not re.search(r"signing_status", text):
        _fail(messages, f"{tag} must include signing_status references")

    if re.findall(r"^\s*signing_status\s*=\s*[\"']?(signed|unsigned)[\"']?\s*$", text, flags=re.MULTILINE) and not has_apksigner_verify:
        _fail(messages, f"{tag} must not assign signing_status statically to signed/unsigned")

    required_outputs = {
        "signing_status=": "signing_status output",
        "sha256=": "sha256 output",
        "artifact_path=": "artifact_path output",
        "manifest_path=": "manifest_path output",
    }
    for token, description in required_outputs.items():
        if token not in text:
            _fail(messages, f"{tag} must emit {description}")


def _validate_workflow(workflow: dict, workflow_path: Path, messages: list[str]) -> None:
    jobs = workflow.get("jobs")
    if not isinstance(jobs, dict):
        _fail(messages, f"{workflow_path}: missing jobs block")
        return

    verify = jobs.get("verify")
    instrumentation = jobs.get("instrumentation")
    release = jobs.get("release")

    if not isinstance(verify, dict):
        _fail(messages, "missing verify job")
    if not isinstance(instrumentation, dict):
        _fail(messages, "missing instrumentation job")
    if not isinstance(release, dict):
        _fail(messages, "missing release job")
    if messages:
        return

    for name, job in (("verify", verify), ("instrumentation", instrumentation), ("release", release)):
        if "if" in job:
            _fail(messages, f"{name} job has conditional if")
        if "continue-on-error" in job:
            _fail(messages, f"{name} job has continue-on-error")

    verify_text, verify_commands, verify_steps = _collect_step_runs(verify)
    instrumentation_text, instrumentation_commands, instrumentation_steps = _collect_step_runs(instrumentation)
    release_text, release_commands, release_steps = _collect_step_runs(release)

    _has_no_step_conditionals_or_failures(verify_steps, "verify", messages)
    _has_no_step_conditionals_or_failures(instrumentation_steps, "instrumentation", messages)
    _has_no_step_conditionals_or_failures(release_steps, "release", messages)

    if not _contains_command(verify_commands, "./gradlew", ("verifyProject",)):
        _fail(messages, "verify job must run ./gradlew verifyProject")
    if not _contains_command(verify_commands, "./gradlew", ("lint",)):
        _fail(messages, "verify job must run ./gradlew lint")
    if not _contains_gradle_task(verify_commands, ":app:assembleDebugAndroidTest"):
        _fail(messages, "verify job must run ./gradlew :app:assembleDebugAndroidTest")
    if not _contains_command(verify_commands, "./gradlew", ("assembleDebug",)):
        _fail(messages, "verify job must run ./gradlew assembleDebug")
    if not _contains_artifact_identity(verify_commands):
        _fail(messages, "verify job must call scripts/ci/artifact_identity.sh")
    if not _contains_command(verify_commands, "python", ("scripts/ci/validate_ci_contract.py",)):
        _fail(messages, "verify job must run python scripts/ci/validate_ci_contract.py")

    if not _contains_command(verify_commands, "./gradlew", ("assembleRelease",)) and not _contains_command(verify_commands, "./gradlew", ("assembleDebug",)):
        _fail(messages, "verify job must include an artifact-producing gradle step")

    if not _has_needs(instrumentation, "verify"):
        _fail(messages, "instrumentation job must depend on verify")
    if not _contains_artifact_identity(instrumentation_commands + release_commands):
        _fail(messages, "workflow must call scripts/ci/artifact_identity.sh")
    if not _has_kvm_guard(instrumentation_commands, instrumentation_text):
        _fail(messages, "instrumentation job must verify Linux KVM")
    if not _contains_avdmanager_create(instrumentation_commands, instrumentation_text):
        _fail(messages, "instrumentation job must create AVD")
    if not _contains_emulator_start(instrumentation_commands):
        _fail(messages, "instrumentation job must start an emulator")
    if not _has_bounded_boot_wait(instrumentation_text):
        _fail(messages, "instrumentation job must use bounded boot wait")
    if not _contains_connected_test(instrumentation_commands):
        _fail(messages, "instrumentation job must run :app:connectedDebugAndroidTest")

    if not _has_needs(release, "verify"):
        _fail(messages, "release job must depend on verify")
    if not _contains_gradle_task(release_commands, ":app:assembleRelease"):
        _fail(messages, "release job must run ./gradlew :app:assembleRelease")
    if not _contains_artifact_identity(release_commands):
        _fail(messages, "release job must call scripts/ci/artifact_identity.sh")

    if not release_text:
        _fail(messages, "release job has empty content")

    artifact_script = Path("scripts/ci/artifact_identity.sh")
    if not artifact_script.exists():
        _fail(messages, "scripts/ci/artifact_identity.sh is missing")
    else:
        _validate_artifact_identity_text(artifact_script.read_text(encoding="utf-8"), messages)


def _load_yaml(path: Path) -> dict:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise TypeError(f"{path} is not a YAML mapping")
    return data


def _validate_fixture_yaml(path: Path, messages: list[str]) -> bool:
    fixture_data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(fixture_data, dict):
        _fail(messages, f"{path.name}: fixture is not a YAML mapping")
        return False

    category = str(fixture_data.get("category", "")).strip()
    if not category:
        _fail(messages, f"{path.name}: fixture missing category")
        return False

    expected = str(fixture_data.get("expected", "fail")).lower()
    expected_pass = expected == "pass"

    local_messages: list[str] = []
    mode = str(fixture_data.get("kind", "workflow")).lower()

    if mode == "workflow":
        workflow_text = fixture_data.get("workflow")
        if isinstance(workflow_text, str) and workflow_text.strip():
            try:
                fixture_workflow = yaml.safe_load(workflow_text)
                if not isinstance(fixture_workflow, dict):
                    _fail(local_messages, "fixture workflow is not a mapping")
                else:
                    _validate_workflow(fixture_workflow, path, local_messages)
            except Exception as exc:
                _fail(local_messages, f"cannot parse workflow fixture: {exc}")
        else:
            workflow_path = fixture_data.get("workflow_path")
            if not isinstance(workflow_path, str):
                _fail(local_messages, "workflow fixture requires workflow or workflow_path")
            else:
                full_path = path.parent / workflow_path
                if not full_path.exists():
                    _fail(local_messages, f"workflow fixture path missing: {full_path}")
                else:
                    try:
                        _validate_workflow(_load_yaml(full_path), full_path, local_messages)
                    except Exception as exc:
                        _fail(local_messages, f"cannot validate workflow fixture: {exc}")
    elif mode == "artifact_script":
        if "artifact_script" in fixture_data:
            value = fixture_data["artifact_script"]
            if not isinstance(value, str):
                _fail(local_messages, "artifact_script fixture must be a string")
            else:
                _validate_artifact_identity_text(value, local_messages)
        elif "artifact_script_path" in fixture_data:
            script_path = Path(fixture_data["artifact_script_path"])
            if not script_path.exists():
                _fail(local_messages, f"artifact_script_path not found: {script_path}")
            else:
                try:
                    _validate_artifact_identity_text(script_path.read_text(encoding="utf-8"), local_messages)
                except Exception as exc:
                    _fail(local_messages, f"cannot validate artifact fixture: {exc}")
        else:
            _fail(local_messages, "artifact_script fixture missing script")
    else:
        _fail(local_messages, f"unsupported fixture kind: {mode}")

    if expected_pass:
        if local_messages:
            _fail(messages, f"{path.name} should pass category {category} but failed")
            messages.extend(local_messages)
            return False
        return True

    if local_messages:
        return True

    _fail(messages, f"{path.name} should fail category {category} but unexpectedly passed")
    return False


def _run_self_test() -> int:
    fixture_dir = Path(__file__).resolve().parent / "contract_mutations"
    if not fixture_dir.exists():
        print(f"ERROR: mutation fixtures directory missing: {fixture_dir}", file=sys.stderr)
        return 1

    fixtures = sorted(fixture_dir.glob("*.yml"))
    if not fixtures:
        print(f"ERROR: no mutation fixtures found in {fixture_dir}", file=sys.stderr)
        return 1

    remaining_categories = set(REQUIRED_FIXTURE_CATEGORIES)
    passed = 0
    total = len(fixtures)
    errors: list[str] = []

    for fixture_path in fixtures:
        fixture_data = yaml.safe_load(fixture_path.read_text(encoding="utf-8"))
        if not isinstance(fixture_data, dict):
            print(f"{fixture_path.name}: fixture is not a YAML mapping", file=sys.stderr)
            return 1

        category = str(fixture_data.get("category", "")).strip()
        remaining_categories.discard(category)

        if not _validate_fixture_yaml(fixture_path, errors):
            print("ERROR: self-test failed")
            for line in errors:
                print(f" - {line}")
            return 1

        if category in REQUIRED_FIXTURE_CATEGORIES:
            passed += 1

    if remaining_categories:
        print(
            "FAILED: missing required fixture categories: "
            + ", ".join(sorted(remaining_categories)),
            file=sys.stderr,
        )
        return 1

    print(f"OK: self-test passed ({passed}/{total} fixtures correctly fail/pass)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--workflow",
        default=".github/workflows/ci.yml",
        help="Path to workflow file",
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return _run_self_test()

    workflow_path = Path(args.workflow)
    if not workflow_path.exists():
        print(f"Workflow file missing: {workflow_path}", file=sys.stderr)
        return 1

    try:
        workflow = _load_yaml(workflow_path)
    except Exception as exc:
        print(f"Cannot load workflow {workflow_path}: {exc}", file=sys.stderr)
        return 1

    errors: list[str] = []
    _validate_workflow(workflow, workflow_path, errors)

    if errors:
        print("ERROR: CI workflow contract violations:", file=sys.stderr)
        for item in errors:
            print(f" - {item}", file=sys.stderr)
        return 1

    print("OK: CI workflow contract checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
