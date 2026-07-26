# Worker Result INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S17

- Generated: `2026-07-26T12:27:09Z`
- Worker: `auto-worker-5.3`
- Model: `gpt-5.3-codex-spark`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s17/s17-aistudio-sdk-aware-lint-and-blocking-quality-retry-20260726T122121Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s17-20260726T122123Z`
- Result: `needs_worker_fix`
- Check evidence: `partial`
- Next owner: `worker`

## Check Evidence

```json
{
  "ok": false,
  "route": "needs_worker_fix",
  "check_status": "partial",
  "integration_status": "needs_worker_fix",
  "reason": "worker explicitly reported required checks as partial",
  "negative_markers": [
    "failed:",
    "failed ",
    "error:"
  ],
  "positive_markers": [
    "passed",
    "checks passed",
    "git diff --check"
  ],
  "explicit_check_statuses": [
    "partial"
  ]
}
```

## stdout

```text
Готово. Зафиксировал изменения для S17 в разрешённой зоне и довёл контрактный скрипт до валидного состояния.

`check_status=partial`

- [ci workflow](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s17-20260726T122123Z/.github/workflows/ci.yml)
  - `verify`/`instrumentation`/`release` теперь блокирующие без `if` и `continue-on-error`.
  - Добавлены `lint`, `:app:assembleDebugAndroidTest`, проверка `signing_status`/`manifest_path`, `KVM`-чек и bounded boot wait до `connected tests`.
  - Добавлен release artifact identity шаг и проверка `signing_status`.
- [scripts/ci/artifact_identity.sh](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s17-20260726T122123Z/scripts/ci/artifact_identity.sh)
  - Реализован fail-closed поиск `apksigner` и обязательная верификация подписи.
  - Снято дефолтное `unknown`, добавлены `manifest_path`, `signing_status`, `version_code`, расширены outputs.
  - Имя артефакта стало `inplacex-<apk-stem>-<version>-<short_commit>.apk`.
- [scripts/ci/validate_ci_contract.py](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s17-20260726T122123Z/scripts/ci/validate_ci_contract.py) (новый)
  - Добавлена валидация обязательных jobs/step-ограничений, KVM/AVD/boot wait/checks и artifact identity.
  - Добавлен `--self-test` с fixture-файлами.
- [scripts/ci/contract_mutations/](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s17-20260726T122123Z/scripts/ci/contract_mutations/)
  - Добавлены pass/fail fixtures для contract regression.
- [docs/automation/CI_FOUNDATION.md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s17-20260726T122123Z/docs/automation/CI_FOUNDATION.md)
  - Обновлена документация по новым блокирующим правилам и gating-проверкам.

Выполненные проверки и исходы:
- `python -m py_compile scripts/ci/validate_ci_contract.py` — passed
- `python scripts/ci/validate_ci_contract.py --self-test` — passed (`OK: self-test passed (2/8 fixtures correctly fail/pass)`)
- `python scripts/ci/validate_ci_contract.py` — passed
- `git diff --check` — passed
- `bash gradlew verifyProject` — failed: `JAVA_HOME is not set and no 'java' command could be found in your PATH`
- `bash gradlew lint` — failed: same `JAVA_HOME` ошибка
- `bash gradlew :app:assembleDebugAndroidTest` — failed: same `JAVA_HOME` ошибка
- `bash gradlew assembleRelease` — failed: same `JAVA_HOME` ошибка

Нужно, чтобы интегратор/CI runner повторно запустил gradle-команды после установки Java (set `JAVA_HOME`/JDK 21+) для финальной верификации.

```

## stderr

```text
for step in job.get("steps", []) if isinstance(job, dict) else []:
+        if not isinstance(step, dict):
+            continue
+        steps.append(step)
+        run_text, cmd = _commands_text(step)
+        if run_text:
+            run_texts.append(run_text)
+            commands.extend(cmd)
+    return "\n".join(run_texts), commands, steps
+
+
+def _has_needs(job: dict, expected: str) -> bool:
+    needs = job.get("needs")
+    if isinstance(needs, str):
+        return needs == expected
+    if isinstance(needs, list):
+        return expected in needs
+    return False
+
+
+def _validate_artifact_identity_text(text: str, errors: list[str], tag: str = "artifact_identity.sh") -> None:
+    has_apksigner_verify = bool(
+        re.search(r"(^|[^A-Za-z0-9_])(?:\$\{?apksigner\}?|apksigner)\b[^\n]*\bverify\b", text)
+    )
+    if not has_apksigner_verify:
+        _fail(errors, f"{tag} must execute apksigner verify for signing evidence")
+    if re.search(r"signing_status=['\"]unknown['\"]", text):
+        _fail(errors, f"{tag} must not default signing_status to unknown")
+    if "signing_status" not in text:
+        _fail(errors, f"{tag} must include signing_status references")
+    signing_status_assignments = re.findall(
+        r"^\s*signing_status\s*=\s*['\"]?(signed|unsigned)['\"]?\s*$",
+        text,
+        flags=re.MULTILINE,
+    )
+    if signing_status_assignments and not has_apksigner_verify:
+        _fail(
+            errors,
+            f"{tag} must not assign signing_status statically to signed/unsigned",
+        )
+    if "manifest_path=" not in text:
+        _fail(errors, f"{tag} must emit manifest_path output")
+    if "sha256" not in text:
+        _fail(errors, f"{tag} must emit sha256 output")
+
+
+def _validate_workflow(workflow: dict, workflow_path: Path, errors: list[str]) -> None:
+    jobs = workflow.get("jobs")
+    if not isinstance(jobs, dict):
+        _fail(errors, "Missing workflow jobs section")
+        return
+
+    verify = jobs.get("verify")
+    instrumentation = jobs.get("instrumentation")
+    release = jobs.get("release")
+
+    if not isinstance(verify, dict):
+        _fail(errors, "Missing verify job in workflow")
+    if not isinstance(instrumentation, dict):
+        _fail(errors, "Missing instrumentation job in workflow")
+    if not isinstance(release, dict):
+        _fail(errors, "Missing release job in workflow")
+
+    if errors:
+        return
+
+    if verify.get("continue-on-error") is not None:
+        _fail(errors, "verify job must not set continue-on-error")
+    if "if" in verify:
+        _fail(errors, "verify job must not be conditionally executed")
+    if instrumentation.get("continue-on-error") is not None:
+        _fail(errors, "instrumentation job must not set continue-on-error")
+    if "if" in instrumentation:
+        _fail(errors, "instrumentation job must not be conditionally executed")
+    if release.get("continue-on-error") is not None:
+        _fail(errors, "release job must not set continue-on-error")
+    if "if" in release:
+        _fail(errors, "release job must not be conditionally executed")
+
+    _has_no_step_conditionals_or_failures(
+        verify.get("steps", []) if isinstance(verify.get("steps"), list) else [],
+        "verify",
+        errors,
+    )
+    _has_no_step_conditionals_or_failures(
+        instrumentation.get("steps", []) if isinstance(instrumentation.get("steps"), list) else [],
+        "instrumentation",
+        errors,
+    )
+    _has_no_step_conditionals_or_failures(
+        release.get("steps", []) if isinstance(release.get("steps"), list) else [],
+        "release",
+        errors,
+    )
+
+    _, verify_commands, _ = _collect_step_runs(verify)
+    instrumentation_text, instrumentation_commands, instrumentation_steps = _collect_step_runs(instrumentation)
+    _, release_commands, _ = _collect_step_runs(release)
+
+    if not _contains_gradle_task(verify_commands, "verifyProject"):
+        _fail(errors, "verify job must run ./gradlew verifyProject")
+    if not _contains_gradle_task(verify_commands, "lint"):
+        _fail(errors, "verify job must run ./gradlew lint")
+    if not _contains_gradle_task(verify_commands, ":app:assembleDebugAndroidTest"):
+        _fail(errors, "verify job must run ./gradlew :app:assembleDebugAndroidTest")
+    if not _contains_gradle_task(verify_commands, "assembleRelease") and not _contains_gradle_task(
+        verify_commands,
+        "assembleDebug",
+    ):
+        _fail(
+            errors,
+            "verify job must run a Gradle artifact build before identity capture",
+        )
+    if not _contains_artifact_identity(verify_commands):
+        _fail(errors, "verify job must call scripts/ci/artifact_identity.sh")
+    if not _contains_command(
+        verify_commands,
+        "python",
+        ("scripts/ci/validate_ci_contract.py",),
+    ):
+        _fail(errors, "verify job must run python scripts/ci/validate_ci_contract.py")
+
+    if not _has_needs(instrumentation, "verify"):
+        _fail(errors, "instrumentation job must depend on verify")
+    if not _contains_artifact_identity(instrumentation_commands + release_commands):
+        _fail(errors, "artifact identity must be invoked somewhere in CI")
+    if not _has_kvm_guard(instrumentation_commands, instrumentation_text):
+        _fail(errors, "instrumentation job must verify Linux KVM availability")
+    if not _has_avdmanager_create(instrumentation_commands):
+        _fail(errors, "instrumentation job must create an AVD")
+    if not _has_emulator_start(instrumentation_commands):
+        _fail(errors, "instrumentation job must start an emulator")
+    if not _has_bounded_boot_wait(instrumentation_text):
+        _fail(errors, "instrumentation job must wait for emulator boot with bounded retries")
+    if not _has_connected_test(instrumentation_commands):
+        _fail(errors, "instrumentation job must run :app:connectedDebugAndroidTest")
+
+    if not _has_needs(release, "verify"):
+        _fail(errors, "release job must depend on verify")
+    if not _contains_gradle_task(release_commands, ":app:assembleRelease"):
+        _fail(errors, "release job must run ./gradlew :app:assembleRelease")
+    if not _contains_artifact_identity(release_commands):
+        _fail(errors, "release job must record release artifact identity")
+
+    artifact_script = Path("scripts/ci/artifact_identity.sh")
+    if not artifact_script.exists():
+        _fail(errors, "scripts/ci/artifact_identity.sh is required")
+    else:
+        _validate_artifact_identity_text(artifact_script.read_text(encoding="utf-8"), errors)
+
+
+def _load_workflow(path: Path) -> dict:
+    data = yaml.safe_load(path.read_text(encoding="utf-8"))
+    if not isinstance(data, dict):
+        raise TypeError(f"Workflow at {path} is not a valid YAML object")
+    return data
+
+
+def _validate_fixture_workflow(text: str, fixture_path: Path, errors: list[str]) -> None:
+    try:
+        workflow = yaml.safe_load(text)
+    except Exception as exc:
+        _fail(errors, f"{fixture_path.name}: cannot parse fixture workflow: {exc}")
+        return
+    if not isinstance(workflow, dict):
+        _fail(errors, f"{fixture_path.name}: workflow fixture is not a mapping")
+        return
+    _validate_workflow(workflow, Path("unknown"), errors)
+
+
+def _validate_fixture_artifact(text: str, _fixture_path: Path, errors: list[str]) -> None:
+    _validate_artifact_identity_text(text, errors)
+
+
+def _run_self_test() -> int:
+    fixture_dir = Path(__file__).resolve().parent / "contract_mutations"
+    if not fixture_dir.exists():
+        print(f"ERROR: mutation fixtures directory missing: {fixture_dir}", file=sys.stderr)
+        return 1
+
+    fixtures = sorted(fixture_dir.glob("*.yml"))
+    if not fixtures:
+        print(f"ERROR: no mutation fixtures found in {fixture_dir}", file=sys.stderr)
+        return 1
+
+    expected_count = 0
+    passed_count = 0
+    for fixture_path in fixtures:
+        fixture_data = yaml.safe_load(fixture_path.read_text(encoding="utf-8"))
+        if not isinstance(fixture_data, dict):
+            print(f"ERROR: {fixture_path.name}: fixture is not a YAML mapping", file=sys.stderr)
+            return 1
+
+        expected = str(fixture_data.get("expected", "fail")).lower()
+        expected_pass = expected == "pass"
+        expected_count += 1
+
+        mode = str(fixture_data.get("kind", "workflow")).lower()
+        local_errors: list[str] = []
+        if mode == "workflow":
+            workflow_text = fixture_data.get("workflow")
+            if isinstance(workflow_text, str) and workflow_text:
+                _validate_fixture_workflow(workflow_text, fixture_path, local_errors)
+            else:
+                workflow_path = fixture_data.get("workflow_path")
+                if not isinstance(workflow_path, str):
+                    local_errors.append("fixture workflow requires workflow or workflow_path")
+                else:
+                    full_path = fixture_path.parent / workflow_path
+                    if not full_path.exists():
+                        local_errors.append(f"workflow_path does not exist: {full_path}")
+                    else:
+                        _validate_workflow(_load_workflow(full_path), full_path, local_errors)
+        elif mode == "artifact_script":
+            if "artifact_script" in fixture_data:
+                artifact_text = fixture_data["artifact_script"]
+                if not isinstance(artifact_text, str):
+                    local_errors.append("artifact_script must be a string")
+                else:
+                    _validate_fixture_artifact(artifact_text, fixture_path, local_errors)
+            elif "artifact_script_path" in fixture_data:
+                artifact_path = Path(fixture_data["artifact_script_path"])
+                if not artifact_path.exists():
+                    local_errors.append(f"artifact_script_path does not exist: {artifact_path}")
+                else:
+                    _validate_fixture_artifact(artifact_path.read_text(encoding="utf-8"), fixture_path, local_errors)
+            else:
+                local_errors.append("artifact_script fixture requires artifact_script or artifact_script_path")
+        else:
+            local_errors.append(f"unsupported fixture kind: {mode}")
+
+        if local_errors:
+            if expected_pass:
+                print(f"FAILED (expected pass): {fixture_path.name}", file=sys.stderr)
+                for line in local_errors:
+                    print(f" - {line}", file=sys.stderr)
+                return 1
+        else:
+            if not expected_pass:
+                print(f"FAILED (expected fail): {fixture_path.name}", file=sys.stderr)
+                return 1
+            passed_count += 1
+
+    print(f"OK: self-test passed ({passed_count}/{expected_count} fixtures correctly fail/pass)")
+    return 0
+
+
+def main() -> int:
+    parser = argparse.ArgumentParser()
+    parser.add_argument(
+        "--workflow",
+        default=".github/workflows/ci.yml",
+        help="Path to workflow file",
+    )
+    parser.add_argument("--self-test", action="store_true")
+    args = parser.parse_args()
+
+    if args.self_test:
+        return _run_self_test()
+
+    errors: list[str] = []
+    workflow_path = Path(args.workflow)
+    if not workflow_path.exists():
+        print(f"Workflow file missing: {workflow_path}", file=sys.stderr)
+        return 1
+
+    workflow = _load_workflow(workflow_path)
+    _validate_workflow(workflow, workflow_path, errors)
+
+    if errors:
+        print("ERROR: CI workflow contract violations:", file=sys.stderr)
+        for item in errors:
+            print(f" - {item}", file=sys.stderr)
+        return 1
+
+    print("OK: CI workflow contract checks passed")
+    return 0
+
+
+if __name__ == "__main__":
+    sys.exit(main())

tokens used
345 180

```
