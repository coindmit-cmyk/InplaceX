#!/usr/bin/env python3
"""Fail-closed contract tests for the blocking Android CI workflow."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WORKFLOW = ROOT / ".github/workflows/ci.yml"
DEFAULT_ARTIFACT_SCRIPT = ROOT / "scripts/ci/artifact_identity.sh"
DEFAULT_INSTRUMENTATION_SCRIPT = ROOT / "scripts/ci/run_instrumentation.sh"


class ContractError(Exception):
    pass


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        raise ContractError(f"cannot read {path}: {error}") from error


def job_blocks(workflow: str) -> dict[str, str]:
    jobs_marker = re.search(r"^jobs:\s*$", workflow, re.MULTILINE)
    if not jobs_marker:
        raise ContractError("workflow.jobs: missing jobs mapping")
    section = workflow[jobs_marker.end() :]
    starts = list(re.finditer(r"^  ([A-Za-z0-9_-]+):\s*$", section, re.MULTILINE))
    return {
        match.group(1): section[match.start() : starts[index + 1].start() if index + 1 < len(starts) else len(section)]
        for index, match in enumerate(starts)
    }


def step_block(job: str, name: str) -> str:
    starts = list(re.finditer(r"^      - name: " + re.escape(name) + r"\s*$", job, re.MULTILINE))
    if len(starts) != 1:
        raise ContractError(f"step.{name}: expected exactly one named step")
    start = starts[0]
    next_step = re.search(r"^      - ", job[start.end() :], re.MULTILINE)
    return job[start.start() : start.end() + next_step.start() if next_step else len(job)]


def exact_run(step: str, name: str, command: str) -> None:
    match = re.search(r"^        run: ([^\n]+)$", step, re.MULTILINE)
    if not match or match.group(1).strip() != command:
        raise ContractError(f"step.{name}: requires exact executable command `{command}`")
    if re.search(r"^        (?:if|continue-on-error):", step, re.MULTILINE):
        raise ContractError(f"step.{name}: cannot be conditional or non-blocking")


def blocking_job(jobs: dict[str, str], name: str, needs_verify: bool) -> str:
    try:
        job = jobs[name]
    except KeyError as error:
        raise ContractError(f"job.{name}: missing") from error
    if re.search(r"^    (?:if|continue-on-error):", job, re.MULTILINE):
        raise ContractError(f"job.{name}: cannot be conditional or non-blocking")
    if needs_verify and not re.search(r"^    needs: verify\s*$", job, re.MULTILINE):
        raise ContractError(f"job.{name}: must depend on verify")
    return job


def validate_scripts(artifact: str, instrumentation: str) -> None:
    required_artifact = (
        "find_apksigner()",
        "apksigner is required to derive APK signing status",
        "apksigner_path=$(find_apksigner)",
        'verify --verbose --print-certs "$apk_path"',
        "signing_status='verified'",
        "signing_status='unverified'",
        '"signing_status": "$signing_status"',
    )
    for marker in required_artifact:
        if marker not in artifact:
            raise ContractError(f"artifact-signing: missing derived signing evidence `{marker}`")
    required_instrumentation = (
        "set -euo pipefail",
        "trap cleanup EXIT",
    )
    for marker in required_instrumentation:
        if marker not in instrumentation:
            raise ContractError(f"instrumentation-script: missing `{marker}`")
    required_commands = (
        (r'^\[\[ -r /dev/kvm && -w /dev/kvm \]\] \|\| \{ echo .+; exit 1; \}$', "KVM access guard"),
        (r'^echo no \| avdmanager create avd --force --name "\$avd_name" --package "\$avd_package" --device pixel_2$', "AVD creation"),
        (r'^emulator -avd "\$avd_name" .+ &$', "emulator start"),
        (r'^timeout 120 adb wait-for-device$', "bounded device wait"),
        (r'^for _ in \$\(seq 1 60\); do$', "bounded boot loop"),
        (r"^    boot_completed=\$\(adb -s emulator-5554 shell getprop sys.boot_completed \| tr -d '\\r'\)$", "boot property polling"),
        (r'^    sleep 2$', "boot poll delay"),
        (r'^\[\[ "\$boot_completed" == .+\]\] \|\| \{ echo .+; exit 1; \}$', "boot timeout failure"),
        (r'^\[\[ "\$\(adb -s emulator-5554 get-state\)" == .+\]\] \|\| \{ echo .+; exit 1; \}$', "device readiness check"),
        (r'^\./gradlew :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest$', "direct connectedDebugAndroidTest"),
    )
    for pattern, description in required_commands:
        if not re.search(pattern, instrumentation, re.MULTILINE):
            raise ContractError(f"instrumentation-script: missing direct {description}")
    if "yes | avdmanager" in instrumentation:
        raise ContractError("instrumentation-script: pipefail-unsafe yes | avdmanager is forbidden")


def validate(workflow_path: Path, artifact_path: Path, instrumentation_path: Path) -> None:
    workflow = read(workflow_path)
    jobs = job_blocks(workflow)
    verify = blocking_job(jobs, "verify", needs_verify=False)
    instrumentation = blocking_job(jobs, "instrumentation", needs_verify=True)
    release = blocking_job(jobs, "release", needs_verify=True)
    guard = blocking_job(jobs, "ci-contract", needs_verify=False)

    exact_run(step_block(verify, "Run unit verification"), "verify.unit", "./gradlew verifyProject")
    exact_run(step_block(verify, "Run Android lint"), "verify.lint", "./gradlew lint")
    exact_run(
        step_block(verify, "Write artifact identity"),
        "verify.artifact",
        "bash scripts/ci/artifact_identity.sh --apk InplaceX-android/app/build/outputs/apk/debug/app-debug.apk --output-dir build/ci-artifacts",
    )
    exact_run(
        step_block(instrumentation, "Provision and run instrumentation checks"),
        "instrumentation.run",
        "bash scripts/ci/run_instrumentation.sh",
    )
    exact_run(step_block(release, "Assemble release candidate"), "release.assemble", "./gradlew :app:assembleRelease")
    exact_run(
        step_block(release, "Write release artifact identity"),
        "release.artifact",
        "bash scripts/ci/artifact_identity.sh --apk InplaceX-android/app/build/outputs/apk/release/app-release-unsigned.apk --output-dir build/ci-release-artifacts --artifact-type release",
    )
    actionlint = step_block(guard, "Run actionlint")
    if "uses: raven-actions/actionlint@v2" not in actionlint:
        raise ContractError("ci-contract.actionlint: missing maintained actionlint action")
    exact_run(step_block(guard, "Validate CI contract"), "ci-contract.validate", "python3 scripts/ci/validate_ci_contract.py --self-test")
    validate_scripts(read(artifact_path), read(instrumentation_path))


def run_artifact(script: Path, apk: Path, output: Path, path: str) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment.pop("APKSIGNER", None)
    environment.pop("ANDROID_HOME", None)
    environment.pop("ANDROID_SDK_ROOT", None)
    environment["PATH"] = path
    return subprocess.run(
        ["bash", str(script), "--apk", str(apk), "--output-dir", str(output)],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=environment,
        check=False,
    )


def self_test(workflow_path: Path, artifact_path: Path, instrumentation_path: Path) -> None:
    original_workflow = read(workflow_path)
    original_artifact = read(artifact_path)
    original_instrumentation = read(instrumentation_path)
    fixtures = {
        "short_circuit_lint": ("workflow", "run: ./gradlew lint", "run: true || ./gradlew lint", "verify.lint"),
        "step_continue_on_error": ("workflow", "run: ./gradlew lint", "run: ./gradlew lint\n        continue-on-error: true", "step.verify.lint"),
        "disabled_job": ("workflow", "  instrumentation:\n", "  instrumentation:\n    if: false\n", "job.instrumentation"),
        "artifact_echo": ("workflow", "run: bash scripts/ci/artifact_identity.sh --apk InplaceX-android/app/build/outputs/apk/debug/app-debug.apk --output-dir build/ci-artifacts", "run: echo bash scripts/ci/artifact_identity.sh", "verify.artifact"),
        "heredoc_lint": ("workflow", "run: ./gradlew lint", "run: cat <<'EOF'\n          ./gradlew lint\n          EOF", "verify.lint"),
        "release_echo": ("workflow", "run: ./gradlew :app:assembleRelease", "run: echo ./gradlew :app:assembleRelease", "release.assemble"),
        "avd_echo": ("instrumentation", "echo no | avdmanager create avd", "echo 'avdmanager create avd'", "instrumentation-script"),
        "avd_short_circuit": ("instrumentation", "echo no | avdmanager create avd", "true || echo no | avdmanager create avd", "instrumentation-script"),
        "unbounded_device_wait": ("instrumentation", "timeout 120 adb wait-for-device", "adb wait-for-device", "instrumentation-script"),
        "boot_wait_echo": ("instrumentation", "getprop sys.boot_completed", "echo getprop sys.boot_completed", "instrumentation-script"),
        "connected_short_circuit": ("instrumentation", "./gradlew :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest", "true || ./gradlew :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest", "instrumentation-script"),
        "static_signing": ("artifact", "apksigner_path=$(find_apksigner)", "signing_status='verified'", "artifact-signing"),
    }
    passed: list[str] = []
    with tempfile.TemporaryDirectory() as temp_dir:
        temporary = Path(temp_dir)
        for name, (target, before, after, diagnostic) in fixtures.items():
            workflow = original_workflow
            artifact = original_artifact
            instrumentation = original_instrumentation
            source = {"workflow": workflow, "artifact": artifact, "instrumentation": instrumentation}[target]
            if source.count(before) != 1:
                raise ContractError(f"self-test.{name}: baseline mutation anchor is ambiguous")
            replacement = source.replace(before, after, 1)
            if target == "workflow":
                workflow = replacement
            elif target == "artifact":
                artifact = replacement
            else:
                instrumentation = replacement
            workflow_file = temporary / f"{name}.yml"
            artifact_file = temporary / f"{name}-artifact.sh"
            instrumentation_file = temporary / f"{name}-instrumentation.sh"
            workflow_file.write_text(workflow, encoding="utf-8")
            artifact_file.write_text(artifact, encoding="utf-8")
            instrumentation_file.write_text(instrumentation, encoding="utf-8")
            try:
                validate(workflow_file, artifact_file, instrumentation_file)
            except ContractError as error:
                if diagnostic not in str(error):
                    raise ContractError(f"self-test.{name}: expected `{diagnostic}`, got `{error}`") from error
                passed.append(name)
            else:
                raise ContractError(f"self-test.{name}: hostile full-workflow mutation was accepted")

        fake_apk = temporary / "candidate.apk"
        fake_apk.write_bytes(b"not-a-real-apk")
        fake_bin = temporary / "bin"
        fake_bin.mkdir()
        fake_apksigner = fake_bin / "apksigner"
        fake_apksigner.write_text("#!/usr/bin/env bash\necho verified-by-fake\n", encoding="utf-8")
        fake_apksigner.chmod(0o755)
        result = run_artifact(artifact_path, fake_apk, temporary / "verified", f"{fake_bin}:/usr/bin:/bin")
        if result.returncode != 0 or '"signing_status": "verified"' not in next((temporary / "verified").glob("*.json")).read_text(encoding="utf-8"):
            raise ContractError("self-test.fake_apksigner_success: verifier success was not recorded")
        passed.append("fake_apksigner_success")
        fake_apksigner.write_text("#!/usr/bin/env bash\nexit 1\n", encoding="utf-8")
        result = run_artifact(artifact_path, fake_apk, temporary / "unverified", f"{fake_bin}:/usr/bin:/bin")
        if result.returncode != 0 or '"signing_status": "unverified"' not in next((temporary / "unverified").glob("*.json")).read_text(encoding="utf-8"):
            raise ContractError("self-test.fake_apksigner_failure: verifier failure was not recorded")
        passed.append("fake_apksigner_failure")
        result = run_artifact(artifact_path, fake_apk, temporary / "missing", "/usr/bin:/bin")
        if result.returncode == 0:
            raise ContractError("self-test.missing_apksigner: missing verifier was accepted")
        passed.append("missing_apksigner")

    expected = len(fixtures) + 3
    if len(passed) != expected:
        raise ContractError(f"self-test: incomplete hostile fixture coverage {len(passed)}/{expected}")
    print(f"OK: self-test passed ({len(passed)}/{expected} hostile fixtures rejected or proved)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workflow", type=Path, default=DEFAULT_WORKFLOW)
    parser.add_argument("--artifact-script", type=Path, default=DEFAULT_ARTIFACT_SCRIPT)
    parser.add_argument("--instrumentation-script", type=Path, default=DEFAULT_INSTRUMENTATION_SCRIPT)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        validate(args.workflow, args.artifact_script, args.instrumentation_script)
        if args.self_test:
            self_test(args.workflow, args.artifact_script, args.instrumentation_script)
    except ContractError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("OK: CI workflow contract checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
