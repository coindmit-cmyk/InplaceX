from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("build_platform_catalog_release.py")
SPEC = importlib.util.spec_from_file_location("build_platform_catalog_release", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
release_builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release_builder
SPEC.loader.exec_module(release_builder)


class PlatformCatalogReleaseBuilderTest(unittest.TestCase):
    def test_builds_signed_candidate_into_platform_catalog_shape(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            output = root / "catalog-release"

            self.run_builder(candidate, output, *self.base_arguments(candidate))

            catalog = json.loads((output / "catalog.json").read_text(encoding="utf-8"))
            self.assertEqual(3, catalog["schemaVersion"])
            self.assertEqual(["inplacex"], [game["id"] for game in catalog["games"]])
            game = catalog["games"][0]
            self.assertEqual(["rf-mirkori", "global-google"], [item["id"] for item in game["distributionVariants"]])
            rf_distribution = game["distributionVariants"][0]
            self.assertEqual("com.mirkori.inplacex.rf", rf_distribution["packageName"])
            self.assertEqual([self.fingerprint()], rf_distribution["certificateSha256Fingerprints"])
            release = game["releases"][0]
            self.assertEqual("inplacex-1.0-1", release["id"])
            self.assertEqual("rf-mirkori", release["distributionId"])
            self.assertEqual(
                {"ru": "Первый ограниченный релиз.", "en": "Initial limited release."},
                release["changelogs"],
            )
            self.assertEqual(1, release["minimumSupportedVersionCode"])
            self.assertEqual("2026-08-07T12:00:00Z", release["publishedAt"])
            self.assertEqual(
                [{"releaseId": "inplacex-1.0-1", "status": "active", "effectiveAt": "2026-08-07T12:00:00Z", "policyVersion": 1}],
                game["releasePolicies"],
            )
            artifact = output / "artifacts" / Path(release["relativePath"])
            self.assertEqual(b"signed-production-apk", artifact.read_bytes())
            self.assertEqual(release["sha256"], hashlib.sha256(artifact.read_bytes()).hexdigest())
            provenance_directory = root / "catalog-release.provenance"
            provenance_path = provenance_directory / "release-provenance.json"
            provenance_bytes = provenance_path.read_bytes()
            provenance = json.loads(provenance_bytes)
            self.assertEqual(2, provenance["schemaVersion"])
            self.assertFalse(provenance["activationProof"])
            self.assertEqual("a" * 40, provenance["inplaceX"]["commit"])
            self.assertEqual("rf-mirkori", provenance["release"]["distributionId"])
            self.assertEqual("inplacex-rf-signing", provenance["release"]["signingIdentityRef"])
            self.assertEqual(release["sha256"], provenance["release"]["apkSha256"])
            self.assertEqual("e" * 64, provenance["catalog"]["transitionAuditSha256"])
            self.assertEqual(
                hashlib.sha256((output / "catalog.json").read_bytes()).hexdigest(),
                provenance["catalog"]["manifestSha256"],
            )
            self.assertEqual("c" * 40, provenance["platformValidator"]["repositoryCommit"])
            self.assertEqual("d" * 64, provenance["platformValidator"]["toolSha256"])
            self.assertEqual(
                f"{hashlib.sha256(provenance_bytes).hexdigest()}  release-provenance.json\n",
                (provenance_directory / "release-provenance.json.sha256").read_text(encoding="ascii"),
            )

    def test_rejects_missing_output_parent_without_side_effects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            missing_parent = root / "must-not-be-created"

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(candidate, missing_parent / "catalog-release", *self.base_arguments(candidate))

            self.assertFalse(missing_parent.exists())

    def test_rejects_output_mount_boundary_without_side_effects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            output_parent = root / "mounted-output"
            output_parent.mkdir()
            with (
                mock.patch.object(
                    release_builder,
                    "path_is_mount_boundary",
                    side_effect=lambda path: path == output_parent,
                ),
                self.assertRaises(release_builder.ReleaseBuildError),
            ):
                self.run_builder(candidate, output_parent / "catalog-release", *self.base_arguments(candidate))

            self.assertEqual([], list(output_parent.iterdir()))

    def test_merges_with_existing_catalog_without_losing_other_games(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            base = self.create_base_catalog(root)
            output = root / "catalog-release"

            self.run_builder(candidate, output, "--base-release-dir", str(base))

            catalog = json.loads((output / "catalog.json").read_text(encoding="utf-8"))
            self.assertEqual(["another-game", "inplacex"], [game["id"] for game in catalog["games"]])
            another = catalog["games"][0]
            self.assertEqual("another-release", another["releases"][0]["id"])
            inplacex = catalog["games"][1]
            self.assertEqual(
                {"rf-mirkori", "global-google"},
                {distribution["id"] for distribution in inplacex["distributionVariants"]},
            )
            self.assertEqual(
                b"other-game-artifact",
                (output / "artifacts" / "another-game" / "rf" / "another.apk").read_bytes(),
            )

    def test_new_inplacex_game_requires_explicit_global_signing_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            identity, _ = release_builder.candidate_manifest(self.create_candidate(root))
            base_manifest = release_builder.validate_base_catalog(self.create_base_catalog(root))

            with self.assertRaisesRegex(release_builder.ReleaseBuildError, "global certificate"):
                release_builder.build_catalog(
                    identity,
                    base_manifest,
                    "stable",
                    1,
                    "2026-08-07T12:00:00Z",
                    "Первый релиз.",
                    "Initial release.",
                    "Game",
                    None,
                )

    def test_rejects_legacy_catalog_base(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = self.create_base_catalog(Path(directory))
            manifest_path = base / "catalog.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["schemaVersion"] = 1
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaisesRegex(release_builder.ReleaseBuildError, "unsupported base catalog schema"):
                release_builder.validate_base_catalog(base)

    def test_preserves_release_and_certificate_history_during_rotation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first_candidate = self.create_candidate(root)
            base = root / "base-output"
            self.run_builder(first_candidate, base, *self.base_arguments(first_candidate))
            second_parent = root / "second-candidate"
            second_parent.mkdir()
            next_fingerprint = ":".join(["CD"] * 32)
            second_candidate = self.create_candidate(
                second_parent,
                version_name="1.1",
                version_code=2,
                fingerprint=next_fingerprint,
            )
            output = root / "rotated-output"

            self.run_builder(second_candidate, output, "--base-release-dir", str(base))

            game = json.loads((output / "catalog.json").read_text(encoding="utf-8"))["games"][0]
            rf_distribution = next(item for item in game["distributionVariants"] if item["id"] == "rf-mirkori")
            self.assertEqual(
                [self.fingerprint(), next_fingerprint],
                rf_distribution["certificateSha256Fingerprints"],
            )
            self.assertEqual(2, rf_distribution["effectiveConfigurationVersion"])
            self.assertEqual(
                ["inplacex-1.0-1", "inplacex-1.1-2"],
                [release["id"] for release in game["releases"]],
            )

    def test_rejects_candidate_tampering_and_does_not_publish_partial_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            (candidate / "InplaceX-1.0-1.apk").write_bytes(b"tampered")
            output = root / "catalog-release"

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(candidate, output, *self.base_arguments(candidate))

            self.assertFalse(output.exists())
            self.assertEqual([], list(root.glob(".catalog-release.tmp.*")))

    def test_refuses_to_replace_existing_release_or_output_with_different_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            output = root / "catalog-release"
            self.run_builder(candidate, output, *self.base_arguments(candidate))
            (output / "catalog.json").write_text("{}\n", encoding="utf-8")

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(candidate, output, *self.base_arguments(candidate))

            self.assertEqual("{}\n", (output / "catalog.json").read_text(encoding="utf-8"))

    def test_incompatible_existing_provenance_cannot_publish_catalog(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            output = root / "catalog-release"
            provenance = root / "catalog-release.provenance"
            provenance.mkdir()
            (provenance / "release-provenance.json").write_text("{}\n", encoding="utf-8")

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(candidate, output, *self.base_arguments(candidate))

            self.assertFalse(output.exists())
            self.assertEqual("{}\n", (provenance / "release-provenance.json").read_text(encoding="utf-8"))

    def test_rejects_same_version_code_with_different_release_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            base_output = root / "base"
            self.run_builder(candidate, base_output, *self.base_arguments(candidate))
            manifest_path = candidate / "InplaceX-1.0-1.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["releaseId"] = "inplacex-1.0-hotfix-1"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(
                    candidate,
                    root / "conflicting-output",
                    "--base-release-dir",
                    str(base_output),
                )

    def test_requires_explicit_base_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(candidate, root / "catalog-release")

    def test_requires_exact_expected_commit_and_release_candidate_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(
                    candidate,
                    root / "wrong-commit-output",
                    *self.base_arguments(candidate),
                    expected_commit="b" * 40,
                )

            manifest_path = candidate / "InplaceX-1.0-1.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["sourceFileName"] = "arbitrary-release.apk"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(release_builder.ReleaseBuildError):
                release_builder.candidate_manifest(candidate)

    def test_existing_output_identity_includes_exact_directory_layout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            output = root / "catalog-release"
            self.run_builder(candidate, output, *self.base_arguments(candidate))
            unexpected = output / "unexpected-empty-directory"
            unexpected.mkdir()

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(candidate, output, *self.base_arguments(candidate))

            self.assertTrue(unexpected.is_dir())

    def test_rejects_catalog_shapes_rejected_by_platform(self) -> None:
        def duplicate_slug(catalog: dict, _: Path) -> None:
            game = json.loads(json.dumps(catalog["games"][0]))
            game.update(id="third-game", slug="another-game", displayName="Third Game")
            catalog["games"].append(game)

        def relative_path(value: str):
            return lambda catalog, _: catalog["games"][0]["releases"][0].update(relativePath=value)

        def boolean_size(catalog: dict, base: Path) -> None:
            artifact = base / "artifacts" / "another-game" / "rf" / "another.apk"
            artifact.write_bytes(b"x")
            release = catalog["games"][0]["releases"][0]
            release["sizeBytes"] = True
            release["sha256"] = hashlib.sha256(b"x").hexdigest()

        def android_non_apk(catalog: dict, _: Path) -> None:
            game = catalog["games"][0]
            release = game["releases"][0]
            release["fileName"] = "another.zip"

        mutations = {
            "duplicate slug": duplicate_slug,
            "double slash": relative_path("another-game//rf/another.apk"),
            "dot segment": relative_path("another-game/./rf/another.apk"),
            "boolean size": boolean_size,
            "Android non-APK": android_non_apk,
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                base = self.create_base_catalog(root)
                manifest_path = base / "catalog.json"
                catalog = json.loads(manifest_path.read_text(encoding="utf-8"))
                mutate(catalog, base)
                manifest_path.write_text(json.dumps(catalog), encoding="utf-8")

                with self.assertRaises(release_builder.ReleaseBuildError):
                    release_builder.validate_base_catalog(base)

    @unittest.skipUnless(os.name == "nt", "NTFS junction coverage is Windows-specific")
    def test_rejects_windows_junctions_at_every_release_boundary(self) -> None:
        def junction(link: Path, target: Path) -> None:
            result = subprocess.run(
                ["cmd", "/c", "mklink", "/J", str(link), str(target)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            candidate_parent = root / "candidate-source"
            candidate_parent.mkdir()
            real_candidate = self.create_candidate(candidate_parent)
            candidate_link_parent = root / "candidate-link"
            candidate_link_parent.mkdir()
            candidate_link = candidate_link_parent / real_candidate.name
            junction(candidate_link, real_candidate)
            with self.assertRaises(release_builder.ReleaseBuildError):
                release_builder.candidate_manifest(candidate_link)

            base_parent = root / "base-source"
            base_parent.mkdir()
            base = self.create_base_catalog(base_parent)
            artifact_directory = base / "artifacts" / "another-game"
            external_artifact_directory = root / "external-artifact-directory"
            shutil.copytree(artifact_directory, external_artifact_directory)
            shutil.rmtree(artifact_directory)
            junction(artifact_directory, external_artifact_directory)
            with self.assertRaises(release_builder.ReleaseBuildError):
                release_builder.validate_base_catalog(base)

            real_output_parent = root / "real-output-parent"
            real_output_parent.mkdir()
            output_parent_link = root / "output-parent-link"
            junction(output_parent_link, real_output_parent)
            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(
                    real_candidate,
                    output_parent_link / "catalog-release",
                    *self.base_arguments(real_candidate),
                )

            missing_descendant = output_parent_link / "must-not-be-created"
            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(
                    real_candidate,
                    missing_descendant / "catalog-release",
                    *self.base_arguments(real_candidate),
                )
            self.assertFalse(real_output_parent.joinpath("must-not-be-created").exists())

            real_output = root / "real-output"
            self.run_builder(real_candidate, real_output, *self.base_arguments(real_candidate))
            output_link = root / "output-link"
            junction(output_link, real_output)
            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(real_candidate, output_link, *self.base_arguments(real_candidate))

    def test_gradle_workflow_consumes_exact_release_candidate_and_commit(self) -> None:
        gradle_script = (MODULE_PATH.parents[2] / "build.gradle.kts").read_text(encoding="utf-8")
        app_gradle_script = (
            MODULE_PATH.parents[2] / "InplaceX-android" / "app" / "build.gradle.kts"
        ).read_text(encoding="utf-8")
        for required_fragment in (
            'tasks.register<Exec>("buildPlatformCatalogRelease")',
            'dependsOn(":app:releaseCandidate", testPlatformReleaseContract)',
            'releaseDistributionCandidateDirectory',
            '"--expected-commit"',
            'inplacexPlatformCatalogBaseReleaseDir',
            'inplacexPlatformRepositoryDir',
            'inplacexPlatformExpectedCommit',
            'inplacexPlatformValidatorSha256',
            'inplacexPlatformCatalogChangelogRu',
            'inplacexPlatformCatalogChangelogEn',
            'inplacexPlatformGlobalCertificateSha256',
            '"--changelog-ru"',
            '"--changelog-en"',
            'verify_platform_release_contract.py',
            '"-I"',
            '"--no-replace-objects"',
            'setEnvironment(releaseDistributionProcessEnvironment)',
        ):
            self.assertIn(required_fragment, gradle_script)
        for forbidden_startup_environment in ('"BASH_FUNC_"', '"BASH_ENV"', '"ENV"'):
            self.assertIn(forbidden_startup_environment, app_gradle_script)
        self.assertIn("setEnvironment(releaseCandidateProcessEnvironment)", app_gradle_script)
        self.assertIn('releaseCandidateBash,\n        "-p",', app_gradle_script)

    def test_global_distribution_cannot_build_rf_signed_release_candidate(self) -> None:
        app_gradle_script = (
            MODULE_PATH.parents[2] / "InplaceX-android" / "app" / "build.gradle.kts"
        ).read_text(encoding="utf-8")

        self.assertIn(
            'beforeVariants(selector().withBuildType("signedReleaseCandidate"))',
            app_gradle_script,
        )
        self.assertIn(
            'variantBuilder.productFlavors.contains("distribution" to "global")',
            app_gradle_script,
        )
        self.assertIn("variantBuilder.enable = false", app_gradle_script)

    def test_release_candidate_bash_rejects_imported_functions(self) -> None:
        if os.name == "nt":
            bash = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Git" / "bin" / "bash.exe"
            if not bash.is_file():
                self.skipTest("Git Bash is unavailable")
        else:
            bash_path = shutil.which("bash")
            if bash_path is None:
                self.skipTest("Bash is unavailable")
            bash = Path(bash_path)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            imported_probe = root / "imported"
            privileged_probe = root / "privileged"
            filtered_probe = root / "filtered"
            injected_environment = os.environ.copy()
            injected_environment["BASH_FUNC_mkdir%%"] = "() { return 73; }"

            imported = subprocess.run(
                [str(bash), "-c", 'mkdir -p "$1"', "bash", str(imported_probe)],
                check=False,
                env=injected_environment,
                capture_output=True,
                text=True,
            )
            self.assertEqual(73, imported.returncode)
            self.assertFalse(imported_probe.exists())

            privileged = subprocess.run(
                [str(bash), "-p", "-c", 'mkdir -p "$1"', "bash", str(privileged_probe)],
                check=False,
                env=injected_environment,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, privileged.returncode, privileged.stderr)
            self.assertTrue(privileged_probe.is_dir())

            filtered_environment = {
                key: value
                for key, value in injected_environment.items()
                if not key.upper().startswith("BASH_FUNC_")
            }
            filtered = subprocess.run(
                [str(bash), "-c", 'mkdir -p "$1"', "bash", str(filtered_probe)],
                check=False,
                env=filtered_environment,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, filtered.returncode, filtered.stderr)
            self.assertTrue(filtered_probe.is_dir())

    def test_isolated_python_environment_removes_python_injection(self) -> None:
        with mock.patch.dict(
            os.environ,
            {"PYTHONPATH": "hostile", "PythonHome": "hostile", "ANDROID_HOME": "trusted-sdk"},
            clear=True,
        ):
            environment = release_builder.isolated_python_environment()
        self.assertNotIn("PYTHONPATH", environment)
        self.assertNotIn("PythonHome", environment)
        self.assertEqual("trusted-sdk", environment["ANDROID_HOME"])

    def test_isolated_git_environment_removes_git_injection(self) -> None:
        with mock.patch.dict(
            os.environ,
            {"GIT_DIR": "hostile", "Git_Config_Count": "1", "ANDROID_HOME": "trusted-sdk"},
            clear=True,
        ):
            environment = release_builder.isolated_git_environment()
        self.assertNotIn("GIT_DIR", environment)
        self.assertNotIn("Git_Config_Count", environment)
        self.assertEqual("trusted-sdk", environment["ANDROID_HOME"])

    def test_validates_exact_clean_platform_checkout_and_schema(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = self.create_platform_repository(Path(directory))
            commit = self.git(repository, "rev-parse", "HEAD").strip()
            tool = repository / "ops" / "catalog_release_tool.py"
            tool_sha256 = hashlib.sha256(tool.read_bytes()).hexdigest()

            identity = release_builder.validate_platform_checkout(repository, commit, tool_sha256)

            self.assertEqual(commit, identity.commit)
            self.assertEqual(tool_sha256, identity.tool_sha256)
            tool.write_text(tool.read_text(encoding="utf-8") + "# dirty\n", encoding="utf-8")
            with self.assertRaises(release_builder.ReleaseBuildError):
                release_builder.validate_platform_checkout(repository, commit, tool_sha256)

    def test_rejects_platform_schema_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = self.create_platform_repository(Path(directory), schema_version=2)
            commit = self.git(repository, "rev-parse", "HEAD").strip()
            tool = repository / "ops" / "catalog_release_tool.py"
            with self.assertRaises(release_builder.ReleaseBuildError):
                release_builder.validate_platform_checkout(
                    repository,
                    commit,
                    hashlib.sha256(tool.read_bytes()).hexdigest(),
                )

    def test_hashes_exact_canonical_platform_transition_audit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            previous = root / "previous"
            candidate.mkdir()
            previous.mkdir()
            tool_bytes = b"print('validator')\n"
            validator = release_builder.PlatformValidatorIdentity(
                root,
                "c" * 40,
                root / "catalog_release_tool.py",
                hashlib.sha256(tool_bytes).hexdigest(),
                tool_bytes,
            )
            audit_bytes = b'{"schemaVersion":1,"addedReleaseIds":["inplacex-1.0-1"]}\n'
            guard = mock.Mock()
            with mock.patch.object(
                release_builder.subprocess,
                "run",
                side_effect=(
                    subprocess.CompletedProcess([], 0, stdout=b"", stderr=b""),
                    subprocess.CompletedProcess([], 0, stdout=audit_bytes, stderr=b""),
                ),
            ) as run:
                digest = release_builder.run_platform_validator(
                    validator,
                    candidate,
                    previous,
                    root,
                    guard,
                )

            self.assertEqual(hashlib.sha256(audit_bytes).hexdigest(), digest)
            self.assertIn("--previous-release-directory", run.call_args_list[0].args[0])
            self.assertEqual("transition-audit", run.call_args_list[1].args[0][3])
            self.assertGreaterEqual(guard.verify.call_count, 3)

    def test_rejects_platform_checkout_hidden_by_replace_ref(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = self.create_platform_repository(Path(directory))
            trusted_commit = self.git(repository, "rev-parse", "HEAD").strip()
            tool = repository / "ops" / "catalog_release_tool.py"
            tool_sha256 = hashlib.sha256(tool.read_bytes()).hexdigest()
            marker = repository / "release-marker.txt"
            marker.write_text("replacement tree\n", encoding="utf-8")
            self.git(repository, "add", "release-marker.txt")
            self.git(repository, "commit", "--quiet", "-m", "replacement tree")
            replacement_commit = self.git(repository, "rev-parse", "HEAD").strip()
            self.git(repository, "replace", trusted_commit, replacement_commit)
            self.git(repository, "checkout", "--quiet", "--detach", trusted_commit)

            self.assertEqual(trusted_commit, self.git(repository, "rev-parse", "HEAD").strip())
            self.assertEqual("", self.git(repository, "status", "--porcelain=v1").strip())
            with self.assertRaises(release_builder.ReleaseBuildError):
                release_builder.validate_platform_checkout(repository, trusted_commit, tool_sha256)

    def run_builder(
        self,
        candidate: Path,
        output: Path,
        *base_arguments: str,
        expected_commit: str = "a" * 40,
    ) -> None:
        arguments = [
            "--candidate-dir",
            str(candidate),
            "--expected-commit",
            expected_commit,
            "--output-dir",
            str(output),
            "--platform-repo-dir",
            str(output.parent),
            "--expected-platform-commit",
            "c" * 40,
            "--expected-platform-validator-sha256",
            "d" * 64,
            *base_arguments,
            "--minimum-supported-version-code",
            "1",
            "--published-at",
            "2026-08-07T12:00:00Z",
            "--changelog-ru",
            "Первый ограниченный релиз.",
            "--changelog-en",
            "Initial limited release.",
            "--global-certificate-sha256",
            self.global_fingerprint(),
        ]
        validator = release_builder.PlatformValidatorIdentity(
            output.parent,
            "c" * 40,
            output.parent / "catalog_release_tool.py",
            "d" * 64,
            b"fake exact validator",
        )
        with (
            mock.patch.object(release_builder, "validate_platform_checkout", return_value=validator),
            mock.patch.object(release_builder, "run_platform_validator", return_value="e" * 64),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            release_builder.main(arguments)

    def base_arguments(self, candidate: Path) -> tuple[str, str]:
        return "--base-release-dir", str(self.create_inplacex_base(candidate.parent))

    def create_platform_repository(self, root: Path, schema_version: int = 3) -> Path:
        repository = root / "platform"
        tool = repository / "ops" / "catalog_release_tool.py"
        tool.parent.mkdir(parents=True)
        tool.write_bytes((
            f"LIFECYCLE_SCHEMA_VERSION = {schema_version}\n"
            "GAME_FIELDS = {'id', 'slug', 'displayName', 'description', 'releases'}\n"
            "DISTRIBUTION_FIELDS = {\n"
            "    'id', 'platform', 'marketScope', 'packageName', 'signingIdentityRef',\n"
            "    'certificateSha256Fingerprints', 'paymentChannel', 'deliveryChannel',\n"
            "    'releaseChannels', 'status', 'effectiveConfigurationVersion',\n"
            "}\n"
            "DISTRIBUTION_RELEASE_FIELDS = {\n"
            "    'id', 'distributionId', 'channel', 'versionName', 'versionCode',\n"
            "    'minimumSupportedVersionCode', 'minimumAndroidSdk', 'publishedAt',\n"
            "    'changelogs', 'fileName', 'relativePath', 'sizeBytes', 'sha256',\n"
            "}\n"
            "LIFECYCLE_POLICY_FIELDS = {'releaseId', 'status', 'effectiveAt', 'policyVersion'}\n"
        ).encode("utf-8"))
        self.git(repository, "init", "--quiet")
        self.git(repository, "config", "user.email", "release-test@example.invalid")
        self.git(repository, "config", "user.name", "Release Test")
        self.git(repository, "add", "ops/catalog_release_tool.py")
        self.git(repository, "commit", "--quiet", "-m", "test validator")
        return repository

    @staticmethod
    def git(repository: Path, *arguments: str) -> str:
        result = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise AssertionError(result.stdout + result.stderr)
        return result.stdout

    def create_candidate(
        self,
        root: Path,
        version_name: str = "1.0",
        version_code: int = 1,
        fingerprint: str | None = None,
    ) -> Path:
        fingerprint = fingerprint or self.fingerprint()
        release_id = f"inplacex-{version_name.lower()}-{version_code}"
        candidate = root / release_id
        candidate.mkdir()
        apk_name = f"InplaceX-{version_name}-{version_code}.apk"
        apk = candidate / apk_name
        apk.write_bytes(b"signed-production-apk")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        manifest = {
            "schemaVersion": 1,
            "artifact": apk_name,
            "artifact_type": "release",
            "releaseId": release_id,
            "fileName": apk_name,
            "sourceFileName": "app-rf-signedReleaseCandidate.apk",
            "packageName": "com.mirkori.inplacex.rf",
            "version": version_name,
            "version_code": version_code,
            "versionName": version_name,
            "versionCode": version_code,
            "minimumAndroidSdk": 29,
            "commit": "a" * 40,
            "sizeBytes": apk.stat().st_size,
            "sha256": digest,
            "sha256_algorithm": "SHA-256",
            "signing_status": "verified",
            "signingStatus": "verified",
            "certificateSha256Fingerprint": fingerprint,
            "debuggable": False,
        }
        (candidate / f"InplaceX-{version_name}-{version_code}.json").write_text(
            json.dumps(manifest),
            encoding="utf-8",
        )
        (candidate / f"{apk_name}.sha256").write_text(f"{digest}  {apk_name}\n", encoding="ascii")
        (candidate / "apksigner-release.txt").write_text(
            f"signing_status=verified\ncertificate_sha256_fingerprint={fingerprint}\n",
            encoding="utf-8",
        )
        (candidate / "apk-metadata-release.txt").write_text(
            "package_name=com.mirkori.inplacex.rf\n"
            f"version_name={version_name}\n"
            f"version_code={version_code}\n"
            "minimum_android_sdk=29\n"
            "debuggable=false\n",
            encoding="utf-8",
        )
        return candidate

    def create_base_catalog(self, root: Path) -> Path:
        base = root / "base"
        artifact = base / "artifacts" / "another-game" / "rf" / "another.apk"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"other-game-artifact")
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        catalog = {
            "schemaVersion": 3,
            "games": [
                {
                    "id": "another-game",
                    "slug": "another-game",
                    "displayName": "Another Game",
                    "description": "Existing game must remain in the shared catalog.",
                    "distributionVariants": [
                        self.distribution(
                            "another-rf",
                            "rf",
                            "games.example.another.rf",
                            "another-rf-signing",
                            self.fingerprint(),
                        ),
                        self.distribution(
                            "another-global",
                            "global",
                            "games.example.another",
                            "another-global-signing",
                            self.global_fingerprint(),
                        ),
                    ],
                    "releases": [
                        {
                            "id": "another-release",
                            "distributionId": "another-rf",
                            "channel": "stable",
                            "versionName": "2.0",
                            "versionCode": 2,
                            "minimumSupportedVersionCode": 1,
                            "minimumAndroidSdk": 29,
                            "publishedAt": "2026-08-01T00:00:00Z",
                            "changelogs": {"ru": "Существующий релиз.", "en": "Existing release."},
                            "fileName": "another.apk",
                            "relativePath": "another-game/rf/another.apk",
                            "sizeBytes": artifact.stat().st_size,
                            "sha256": digest,
                        }
                    ],
                    "releasePolicies": [
                        {
                            "releaseId": "another-release",
                            "status": "active",
                            "effectiveAt": "2026-08-01T00:00:00Z",
                            "policyVersion": 1,
                        }
                    ],
                }
            ],
        }
        (base / "catalog.json").write_text(json.dumps(catalog), encoding="utf-8")
        return base

    def create_inplacex_base(self, root: Path) -> Path:
        base = root / "schema3-base"
        if base.exists():
            return base
        (base / "artifacts").mkdir(parents=True)
        catalog = {
            "schemaVersion": 3,
            "games": [
                {
                    "id": "inplacex",
                    "slug": "inplacex",
                    "displayName": "InplaceX",
                    "description": "Existing distribution authority.",
                    "distributionVariants": [
                        self.distribution(
                            "rf-mirkori",
                            "rf",
                            "com.mirkori.inplacex.rf",
                            "inplacex-rf-signing",
                            self.fingerprint(),
                        ),
                        self.distribution(
                            "global-google",
                            "global",
                            "com.mirkori.inplacex",
                            "inplacex-global-signing",
                            self.global_fingerprint(),
                        ),
                    ],
                    "releases": [],
                    "releasePolicies": [],
                }
            ],
        }
        (base / "catalog.json").write_text(json.dumps(catalog), encoding="utf-8")
        return base

    @staticmethod
    def distribution(
        distribution_id: str,
        market_scope: str,
        package_name: str,
        signing_identity_ref: str,
        fingerprint: str,
    ) -> dict[str, object]:
        global_distribution = market_scope == "global"
        return {
            "id": distribution_id,
            "platform": "android",
            "marketScope": market_scope,
            "packageName": package_name,
            "signingIdentityRef": signing_identity_ref,
            "certificateSha256Fingerprints": [fingerprint],
            "paymentChannel": "google_play" if global_distribution else "mirkori",
            "deliveryChannel": "google_play" if global_distribution else "direct_apk",
            "releaseChannels": ["stable", "beta"],
            "status": "active",
            "effectiveConfigurationVersion": 1,
        }

    @staticmethod
    def fingerprint() -> str:
        return ":".join(["AB"] * 32)

    @staticmethod
    def global_fingerprint() -> str:
        return ":".join(["EF"] * 32)


if __name__ == "__main__":
    unittest.main()
