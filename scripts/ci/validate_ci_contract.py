#!/usr/bin/env python3
"""Structural, fail-closed validation for the Android CI delivery contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ci.yml"
ARTIFACT_SCRIPT = ROOT / "scripts/ci/artifact_identity.sh"
INSTRUMENTATION_SCRIPT = ROOT / "scripts/ci/run_instrumentation.sh"
HOSTILE_FIXTURES = ROOT / "scripts/ci/contract_mutations/hostile_fixtures.json"

ARTIFACT_SCRIPT_SHA256 = "b22e695b41b58bd8b01153458ca84a2d75049245ff95032b48158976418076fc"
INSTRUMENTATION_SCRIPT_SHA256 = "ac415fe647a3a7bbf4d752c021debb8e00c3ce8b8332886cac2844d81ad7291a"
EMULATOR_ACTION = "ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d"
ACTIONLINT_ACTION = "raven-actions/actionlint@3d39aea434753780c3b3d4a1a31c854b4dbf49d7"


class ContractError(Exception):
    def __init__(self, code: str, detail: str = "") -> None:
        self.code = code
        super().__init__(f"{code}: {detail}" if detail else code)


@dataclass(frozen=True)
class Step:
    name: str
    lines: tuple[str, ...]

    def scalar(self, key: str, indentation: int = 8) -> str | None:
        prefix = " " * indentation + key + ":"
        values = [line[len(prefix) :].strip() for line in self.lines if line.startswith(prefix)]
        if len(values) > 1:
            raise ContractError(f"step.{self.name}.duplicate-{key}")
        if not values:
            return None
        value = values[0]
        return value.split(" #", 1)[0].rstrip()

    def nested_scalar(self, key: str) -> str | None:
        return self.scalar(key, indentation=10)


@dataclass(frozen=True)
class Job:
    name: str
    lines: tuple[str, ...]

    def step(self, name: str) -> Step:
        starts = [index for index, line in enumerate(self.lines) if line == f"      - name: {name}"]
        if len(starts) != 1:
            raise ContractError(f"job.{self.name}.step.{name}.count", f"found {len(starts)}")
        start = starts[0]
        end = next(
            (index for index in range(start + 1, len(self.lines)) if self.lines[index].startswith("      - ")),
            len(self.lines),
        )
        return Step(name, self.lines[start:end])

    def require_blocking(self, needs_verify: bool) -> None:
        if any(line.startswith("    continue-on-error:") for line in self.lines):
            raise ContractError(f"job.{self.name}.continue-on-error")
        if any(line.startswith("    if:") for line in self.lines):
            raise ContractError(f"job.{self.name}.conditional")
        if needs_verify and "    needs: verify" not in self.lines:
            raise ContractError(f"job.{self.name}.needs-verify")


class Workflow:
    def __init__(self, source: str) -> None:
        self.lines = tuple(source.splitlines())
        if self.lines.count("jobs:") != 1:
            raise ContractError("workflow.jobs.count")
        jobs_index = self.lines.index("jobs:")
        starts = [
            (index, match.group(1))
            for index in range(jobs_index + 1, len(self.lines))
            if (match := re.fullmatch(r"  ([A-Za-z0-9_-]+):", self.lines[index]))
        ]
        self.jobs: dict[str, Job] = {}
        for position, (start, name) in enumerate(starts):
            if name in self.jobs:
                raise ContractError(f"workflow.job.{name}.duplicate")
            end = starts[position + 1][0] if position + 1 < len(starts) else len(self.lines)
            self.jobs[name] = Job(name, self.lines[start:end])

    def job(self, name: str) -> Job:
        try:
            return self.jobs[name]
        except KeyError as error:
            raise ContractError(f"workflow.job.{name}.missing") from error


def require_exact_run(job: Job, step_name: str, command: str, code: str) -> None:
    step = job.step(step_name)
    if step.scalar("run") != command:
        raise ContractError(code, f"expected `{command}`")
    if step.scalar("uses") is not None:
        raise ContractError(code, "run step cannot also use an action")
    if step.scalar("if") is not None or step.scalar("continue-on-error") is not None:
        raise ContractError(code, "step must be unconditional and blocking")


def require_exact_action(job: Job, step_name: str, action: str, code: str) -> Step:
    step = job.step(step_name)
    if step.scalar("uses") != action:
        raise ContractError(code, f"expected `{action}`")
    if step.scalar("run") is not None:
        raise ContractError(code, "action step cannot also contain run")
    if step.scalar("if") is not None or step.scalar("continue-on-error") is not None:
        raise ContractError(code, "step must be unconditional and blocking")
    return step


def require_script_hash(content: bytes, expected: str, code: str) -> None:
    actual = hashlib.sha256(content).hexdigest()
    if actual != expected:
        raise ContractError(code, f"expected {expected}, got {actual}")


def validate(
    workflow_source: str,
    artifact_script: bytes,
    instrumentation_script: bytes,
) -> None:
    workflow = Workflow(workflow_source)
    expected_jobs = {"verify", "instrumentation", "release", "ci-contract"}
    if not expected_jobs.issubset(workflow.jobs):
        raise ContractError("workflow.required-jobs")

    verify = workflow.job("verify")
    instrumentation = workflow.job("instrumentation")
    release = workflow.job("release")
    guard = workflow.job("ci-contract")
    verify.require_blocking(needs_verify=False)
    instrumentation.require_blocking(needs_verify=True)
    release.require_blocking(needs_verify=True)
    guard.require_blocking(needs_verify=False)

    require_exact_run(verify, "Run unit verification", "./gradlew verifyProject", "step.verify.unit.exact-run")
    require_exact_run(verify, "Run Android lint", "./gradlew lint", "step.verify.lint.exact-run")
    require_exact_run(verify, "Assemble debug APK", "./gradlew :app:assembleDebug", "step.verify.debug.exact-run")
    require_exact_run(
        verify,
        "Write debug artifact identity",
        "bash scripts/ci/artifact_identity.sh --apk InplaceX-android/app/build/outputs/apk/debug/app-debug.apk --output-dir build/ci-artifacts --artifact-type debug --expected-signing verified",
        "step.verify.artifact.exact-run",
    )

    kvm = instrumentation.step("Enable KVM group permissions")
    expected_kvm = (
        "      - name: Enable KVM group permissions",
        "        run: |",
        "          echo 'KERNEL==\"kvm\", GROUP=\"kvm\", MODE=\"0666\", OPTIONS+=\"static_node=kvm\"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules",
        "          sudo udevadm control --reload-rules",
        "          sudo udevadm trigger --name-match=kvm",
        "",
    )
    if kvm.lines != expected_kvm:
        raise ContractError("step.instrumentation.kvm.exact-block")

    emulator = require_exact_action(
        instrumentation,
        "Run connected tests on API 35 emulator",
        EMULATOR_ACTION,
        "step.instrumentation.emulator.pinned-action",
    )
    expected_inputs = {
        "api-level": "35",
        "target": "google_apis",
        "arch": "x86_64",
        "profile": "pixel_6",
        "disable-animations": "true",
        "emulator-options": "-no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim",
        "script": "bash scripts/ci/run_instrumentation.sh",
    }
    for key, expected in expected_inputs.items():
        if emulator.nested_scalar(key) != expected:
            raise ContractError(f"step.instrumentation.emulator.input.{key}")

    require_exact_run(
        release,
        "Run release unit tests",
        "./gradlew :app:testReleaseUnitTest",
        "step.release.unit.exact-run",
    )
    require_exact_run(
        release,
        "Run release lint",
        "./gradlew :app:lintRelease",
        "step.release.lint.exact-run",
    )
    require_exact_run(
        release,
        "Assemble unsigned release artifact",
        "./gradlew :app:assembleRelease",
        "step.release.assemble.exact-run",
    )
    require_exact_run(
        release,
        "Write release artifact identity",
        "bash scripts/ci/artifact_identity.sh --apk InplaceX-android/app/build/outputs/apk/release/app-release-unsigned.apk --output-dir build/ci-release-artifacts --artifact-type release --expected-signing unverified",
        "step.release.artifact.exact-run",
    )

    require_exact_action(guard, "Run actionlint", ACTIONLINT_ACTION, "step.guard.actionlint.pinned-action")
    require_exact_run(
        guard,
        "Validate CI contract and hostile fixtures",
        "python3 scripts/ci/validate_ci_contract.py --self-test",
        "step.guard.contract.exact-run",
    )

    require_script_hash(artifact_script, ARTIFACT_SCRIPT_SHA256, "script.artifact.sha256")
    require_script_hash(instrumentation_script, INSTRUMENTATION_SCRIPT_SHA256, "script.instrumentation.sha256")


def mutate_once(source: str, anchor: str, replacement: str, name: str) -> str:
    if source.count(anchor) != 1:
        raise ContractError(f"fixture.{name}.anchor", f"found {source.count(anchor)}")
    return source.replace(anchor, replacement, 1)


def run_hostile_fixtures(workflow: str, artifact: bytes, instrumentation: bytes) -> int:
    fixtures = json.loads(HOSTILE_FIXTURES.read_text(encoding="utf-8"))
    passed = 0
    for fixture in fixtures:
        name = fixture["name"]
        target = fixture["target"]
        mutated_workflow = workflow
        mutated_artifact = artifact
        mutated_instrumentation = instrumentation
        if target == "workflow":
            mutated_workflow = mutate_once(workflow, fixture["anchor"], fixture["replacement"], name)
        elif target == "artifact_script":
            mutated_artifact = mutate_once(
                artifact.decode("utf-8"), fixture["anchor"], fixture["replacement"], name
            ).encode("utf-8")
        elif target == "instrumentation_script":
            mutated_instrumentation = mutate_once(
                instrumentation.decode("utf-8"), fixture["anchor"], fixture["replacement"], name
            ).encode("utf-8")
        else:
            raise ContractError(f"fixture.{name}.target")
        try:
            validate(mutated_workflow, mutated_artifact, mutated_instrumentation)
        except ContractError as error:
            if error.code != fixture["expected"]:
                raise ContractError(
                    f"fixture.{name}.diagnostic",
                    f"expected {fixture['expected']}, got {error.code}",
                ) from error
            passed += 1
        else:
            raise ContractError(f"fixture.{name}.accepted")
    return passed


def bash_path(path: Path) -> str:
    if os.name != "nt":
        return str(path)
    resolved = path.resolve()
    drive, tail = os.path.splitdrive(str(resolved))
    if not drive:
        raise ContractError("windows-bash-path", str(resolved))
    return f"/{drive[0].lower()}/{tail.lstrip('\\/').replace('\\', '/')}"


def bash_executable() -> str:
    if os.name != "nt":
        return "bash"
    candidate = Path(os.environ.get("PROGRAMFILES", r"C:\Program Files")) / "Git/bin/bash.exe"
    if not candidate.is_file():
        raise ContractError("git-bash.missing", str(candidate))
    return str(candidate)


def run_artifact_script(
    apk: Path,
    output: Path,
    signer: Path,
    metadata_tool: Path,
    expected: str,
    artifact_type: str = "debug",
    expected_certificate: str | None = None,
) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment["APKSIGNER"] = bash_path(signer)
    environment["AAPT"] = bash_path(metadata_tool)
    environment["GITHUB_SHA"] = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()
    command = [
        bash_executable(),
        bash_path(ARTIFACT_SCRIPT),
        "--apk",
        bash_path(apk),
        "--output-dir",
        bash_path(output),
        "--artifact-type",
        artifact_type,
        "--expected-signing",
        expected,
    ]
    if expected_certificate is not None:
        command.extend(["--expected-certificate-sha256", expected_certificate])
    return subprocess.run(
        command,
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def write_fake_aapt(
    path: Path,
    debuggable: bool,
    version_name: str = "1.0",
    version_code: int = 1,
) -> None:
    debug_line = "application-debuggable\n" if debuggable else ""
    path.write_text(
        "#!/usr/bin/env bash\n"
        "cat <<'EOF'\n"
        f"package: name='com.mirkori.inplacex' versionCode='{version_code}' versionName='{version_name}'\n"
        "sdkVersion:'29'\n"
        f"{debug_line}"
        "EOF\n",
        encoding="utf-8",
    )
    path.chmod(0o755)


def run_fake_artifact_tests() -> int:
    passed = 0
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        apk = temporary / "candidate.apk"
        apk.write_bytes(b"fake-apk-for-contract-test")
        metadata_tool = temporary / "aapt"
        write_fake_aapt(metadata_tool, debuggable=True)
        signer = temporary / "apksigner"
        signer.write_text(
            "#!/usr/bin/env bash\n"
            f"printf '%s\\n' 'Signer #1 certificate SHA-256 digest: {'a' * 64}'\n"
            "exit 0\n",
            encoding="utf-8",
        )
        signer.chmod(0o755)

        verified_output = temporary / "verified"
        result = run_artifact_script(apk, verified_output, signer, metadata_tool, "verified")
        if result.returncode != 0:
            raise ContractError("fake-apksigner.verified", result.stderr)
        manifest = json.loads(next(verified_output.glob("*.json")).read_text(encoding="utf-8"))
        expected_fields = {
            "packageName": "com.mirkori.inplacex",
            "versionName": "1.0",
            "versionCode": 1,
            "minimumAndroidSdk": 29,
            "sizeBytes": len(b"fake-apk-for-contract-test"),
            "signing_status": "verified",
            "debuggable": True,
            "releaseId": "inplacex-1.0-1",
            "sourceFileName": "candidate.apk",
        }
        if any(manifest.get(key) != value for key, value in expected_fields.items()):
            raise ContractError("fake-apksigner.verified-manifest")
        fingerprint = manifest.get("certificateSha256Fingerprint")
        if not isinstance(fingerprint, str) or fingerprint.count(":") != 31:
            raise ContractError("fake-apksigner.verified-fingerprint")
        passed += 1

        signer.write_text("#!/usr/bin/env bash\nexit 1\n", encoding="utf-8")
        unverified_output = temporary / "unverified"
        result = run_artifact_script(apk, unverified_output, signer, metadata_tool, "unverified")
        if result.returncode != 0:
            raise ContractError("fake-apksigner.unverified", result.stderr)
        manifest = json.loads(next(unverified_output.glob("*.json")).read_text(encoding="utf-8"))
        if manifest["signing_status"] != "unverified" or manifest["certificateSha256Fingerprint"] is not None:
            raise ContractError("fake-apksigner.unverified-manifest")
        passed += 1

        mismatch_output = temporary / "mismatch"
        result = run_artifact_script(apk, mismatch_output, signer, metadata_tool, "verified")
        if result.returncode == 0 or "expected signing status verified" not in result.stderr:
            raise ContractError("fake-apksigner.mismatch")
        passed += 1

        missing = temporary / "missing-apksigner"
        result = run_artifact_script(apk, temporary / "missing", missing, metadata_tool, "verified")
        if result.returncode == 0 or "APKSIGNER does not exist" not in result.stderr:
            raise ContractError("fake-apksigner.missing")
        passed += 1

        missing_aapt = temporary / "missing-aapt"
        result = run_artifact_script(apk, temporary / "missing-aapt-output", signer, missing_aapt, "verified")
        if result.returncode == 0 or "AAPT does not exist" not in result.stderr:
            raise ContractError("fake-aapt.missing")
        passed += 1

        write_fake_aapt(metadata_tool, debuggable=True)
        result = run_artifact_script(
            apk,
            temporary / "debuggable-release",
            signer,
            metadata_tool,
            "unverified",
            artifact_type="release",
        )
        if result.returncode == 0 or "release APK must not be debuggable" not in result.stderr:
            raise ContractError("fake-release.debuggable")
        passed += 1

        write_fake_aapt(metadata_tool, debuggable=False)
        signer.write_text(
            "#!/usr/bin/env bash\n"
            f"printf '%s\\n' 'V2 Signer: certificate SHA-256 digest: {'b' * 64}'\n"
            "exit 0\n",
            encoding="utf-8",
        )
        signer_certificate = "b" * 64

        missing_policy_output = temporary / "missing-owner-policy"
        result = run_artifact_script(
            apk,
            missing_policy_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
        )
        if result.returncode == 0 or "--expected-certificate-sha256 is required" not in result.stderr:
            raise ContractError("fake-release.missing-owner-policy")
        if missing_policy_output.exists():
            raise ContractError("fake-release.missing-owner-policy-output")
        passed += 1

        wrong_policy_output = temporary / "wrong-owner-policy"
        result = run_artifact_script(
            apk,
            wrong_policy_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
            expected_certificate="c" * 64,
        )
        if result.returncode == 0 or "owner certificate SHA-256 does not match expected policy" not in result.stderr:
            raise ContractError("fake-release.wrong-owner-policy")
        if any(wrong_policy_output.iterdir()):
            raise ContractError("fake-release.wrong-owner-policy-output")
        passed += 1

        signed_release_output = temporary / "signed-release"
        result = run_artifact_script(
            apk,
            signed_release_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
            expected_certificate=":".join(
                signer_certificate[index : index + 2] for index in range(0, len(signer_certificate), 2)
            ),
        )
        if result.returncode != 0:
            raise ContractError("fake-release.verified", result.stderr)
        release_directory = signed_release_output / "inplacex-1.0-1"
        manifest_path = release_directory / "InplaceX-1.0-1.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest["fileName"] != "InplaceX-1.0-1.apk" or manifest["debuggable"] is not False:
            raise ContractError("fake-release.manifest")
        expected_bundle_files = {
            "InplaceX-1.0-1.apk",
            "InplaceX-1.0-1.json",
            "InplaceX-1.0-1.apk.sha256",
            "apksigner-release.txt",
            "apk-metadata-release.txt",
        }
        if {path.name for path in release_directory.iterdir()} != expected_bundle_files:
            raise ContractError("fake-release.bundle-files")
        original_apk = apk.read_bytes()
        passed += 1

        result = run_artifact_script(
            apk,
            signed_release_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
            expected_certificate=signer_certificate,
        )
        if result.returncode != 0:
            raise ContractError("fake-release.idempotent-replay", result.stderr)
        if any(path.name.startswith(".inplacex-1.0-1") for path in signed_release_output.iterdir()):
            raise ContractError("fake-release.idempotent-replay-temp")
        passed += 1

        apk.write_bytes(b"different-apk-with-the-same-release-id")
        result = run_artifact_script(
            apk,
            signed_release_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
            expected_certificate=signer_certificate,
        )
        if result.returncode == 0 or "releaseId already exists with different APK SHA-256" not in result.stderr:
            raise ContractError("fake-release.release-id-conflict")
        if (release_directory / "InplaceX-1.0-1.apk").read_bytes() != original_apk:
            raise ContractError("fake-release.release-id-conflict-overwrite")
        if any(path.name.startswith(".inplacex-1.0-1") for path in signed_release_output.iterdir()):
            raise ContractError("fake-release.release-id-conflict-temp")
        passed += 1

        apk.write_bytes(original_apk)
        stale_output = temporary / "stale-release"
        result = run_artifact_script(
            apk,
            stale_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
            expected_certificate=signer_certificate,
        )
        if result.returncode != 0:
            raise ContractError("fake-release.stale-setup", result.stderr)
        passed += 1
        stale_directory = stale_output / "inplacex-1.0-1"
        (stale_directory / "stale.txt").write_text("stale", encoding="utf-8")
        result = run_artifact_script(
            apk,
            stale_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
            expected_certificate=signer_certificate,
        )
        if result.returncode == 0 or "stale or incomplete files" not in result.stderr:
            raise ContractError("fake-release.stale-rejected")
        if any(path.name.startswith(".inplacex-1.0-1") for path in stale_output.iterdir()):
            raise ContractError("fake-release.stale-temp")
        passed += 1

        maximum_version_name = "1" + ("a" * 52)
        write_fake_aapt(metadata_tool, debuggable=False, version_name=maximum_version_name)
        maximum_id_output = temporary / "maximum-release-id"
        result = run_artifact_script(
            apk,
            maximum_id_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
            expected_certificate=signer_certificate,
        )
        if result.returncode != 0:
            raise ContractError("fake-release.maximum-release-id", result.stderr)
        maximum_release_id = f"inplacex-{maximum_version_name}-1"
        if len(maximum_release_id) != 64 or not (maximum_id_output / maximum_release_id).is_dir():
            raise ContractError("fake-release.maximum-release-id-directory")
        passed += 1

        excessive_version_name = "1" + ("a" * 53)
        write_fake_aapt(metadata_tool, debuggable=False, version_name=excessive_version_name)
        excessive_id_output = temporary / "excessive-release-id"
        result = run_artifact_script(
            apk,
            excessive_id_output,
            signer,
            metadata_tool,
            "verified",
            artifact_type="release",
            expected_certificate=signer_certificate,
        )
        if result.returncode == 0 or "releaseId exceeds Mirkori catalog limit of 64 characters" not in result.stderr:
            raise ContractError("fake-release.excessive-release-id")
        if any(excessive_id_output.iterdir()):
            raise ContractError("fake-release.excessive-release-id-output")
        passed += 1
    return passed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        artifact = ARTIFACT_SCRIPT.read_bytes()
        instrumentation = INSTRUMENTATION_SCRIPT.read_bytes()
        validate(workflow, artifact, instrumentation)
        if args.self_test:
            hostile_count = run_hostile_fixtures(workflow, artifact, instrumentation)
            artifact_count = run_fake_artifact_tests()
            print(f"OK: {hostile_count} hostile fixtures and {artifact_count} artifact executions passed")
    except (ContractError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("OK: CI workflow contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
