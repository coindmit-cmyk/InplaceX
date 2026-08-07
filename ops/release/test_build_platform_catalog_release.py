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

            self.run_builder(candidate, output, "--allow-empty-base")

            catalog = json.loads((output / "catalog.json").read_text(encoding="utf-8"))
            self.assertEqual(1, catalog["schemaVersion"])
            self.assertEqual(["inplacex"], [game["id"] for game in catalog["games"]])
            game = catalog["games"][0]
            self.assertEqual("com.mirkori.inplacex", game["androidAppLink"]["packageName"])
            self.assertEqual([self.fingerprint()], game["androidAppLink"]["certificateSha256Fingerprints"])
            release = game["releases"][0]
            self.assertEqual("inplacex-1.0-1", release["id"])
            self.assertEqual(1, release["minimumSupportedVersionCode"])
            self.assertEqual("2026-08-07T12:00:00Z", release["publishedAt"])
            artifact = output / "artifacts" / Path(release["relativePath"])
            self.assertEqual(b"signed-production-apk", artifact.read_bytes())
            self.assertEqual(release["sha256"], hashlib.sha256(artifact.read_bytes()).hexdigest())
            provenance_directory = root / "catalog-release.provenance"
            provenance_path = provenance_directory / "release-provenance.json"
            provenance_bytes = provenance_path.read_bytes()
            provenance = json.loads(provenance_bytes)
            self.assertFalse(provenance["activationProof"])
            self.assertEqual("a" * 40, provenance["inplaceX"]["commit"])
            self.assertEqual(release["sha256"], provenance["release"]["apkSha256"])
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
                self.run_builder(candidate, missing_parent / "catalog-release", "--allow-empty-base")

            self.assertFalse(missing_parent.exists())

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
            self.assertEqual(
                b"other-game-artifact",
                (output / "artifacts" / "another-game" / "windows" / "another.zip").read_bytes(),
            )

    def test_preserves_release_and_certificate_history_during_rotation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first_candidate = self.create_candidate(root)
            base = root / "base-output"
            self.run_builder(first_candidate, base, "--allow-empty-base")
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
            self.assertEqual(
                [self.fingerprint(), next_fingerprint],
                game["androidAppLink"]["certificateSha256Fingerprints"],
            )
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
                self.run_builder(candidate, output, "--allow-empty-base")

            self.assertFalse(output.exists())
            self.assertEqual([], list(root.glob(".catalog-release.tmp.*")))

    def test_refuses_to_replace_existing_release_or_output_with_different_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            output = root / "catalog-release"
            self.run_builder(candidate, output, "--allow-empty-base")
            (output / "catalog.json").write_text("{}\n", encoding="utf-8")

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(candidate, output, "--allow-empty-base")

            self.assertEqual("{}\n", (output / "catalog.json").read_text(encoding="utf-8"))

    def test_rejects_same_version_code_with_different_release_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = self.create_candidate(root)
            base_output = root / "base"
            self.run_builder(candidate, base_output, "--allow-empty-base")
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
                    "--allow-empty-base",
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
            self.run_builder(candidate, output, "--allow-empty-base")
            unexpected = output / "unexpected-empty-directory"
            unexpected.mkdir()

            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(candidate, output, "--allow-empty-base")

            self.assertTrue(unexpected.is_dir())

    def test_rejects_catalog_shapes_rejected_by_platform(self) -> None:
        def duplicate_slug(catalog: dict, _: Path) -> None:
            catalog["games"].append(
                {
                    "id": "third-game",
                    "slug": "another-game",
                    "displayName": "Third Game",
                    "description": "Duplicate slugs are forbidden by Platform.",
                    "releases": [],
                }
            )

        def relative_path(value: str):
            return lambda catalog, _: catalog["games"][0]["releases"][0].update(relativePath=value)

        def boolean_size(catalog: dict, base: Path) -> None:
            artifact = base / "artifacts" / "another-game" / "windows" / "another.zip"
            artifact.write_bytes(b"x")
            release = catalog["games"][0]["releases"][0]
            release["sizeBytes"] = True
            release["sha256"] = hashlib.sha256(b"x").hexdigest()

        def android_non_apk(catalog: dict, _: Path) -> None:
            game = catalog["games"][0]
            game["androidAppLink"] = {
                "packageName": "com.example.another",
                "certificateSha256Fingerprints": [self.fingerprint()],
            }
            release = game["releases"][0]
            release["platform"] = "android"
            release["minimumAndroidSdk"] = 29

        mutations = {
            "duplicate slug": duplicate_slug,
            "double slash": relative_path("another-game//windows/another.zip"),
            "dot segment": relative_path("another-game/./windows/another.zip"),
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
                    "--allow-empty-base",
                )

            missing_descendant = output_parent_link / "must-not-be-created"
            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(
                    real_candidate,
                    missing_descendant / "catalog-release",
                    "--allow-empty-base",
                )
            self.assertFalse(real_output_parent.joinpath("must-not-be-created").exists())

            real_output = root / "real-output"
            self.run_builder(real_candidate, real_output, "--allow-empty-base")
            output_link = root / "output-link"
            junction(output_link, real_output)
            with self.assertRaises(release_builder.ReleaseBuildError):
                self.run_builder(real_candidate, output_link, "--allow-empty-base")

    def test_gradle_workflow_consumes_exact_release_candidate_and_commit(self) -> None:
        gradle_script = (MODULE_PATH.parents[2] / "build.gradle.kts").read_text(encoding="utf-8")
        for required_fragment in (
            'tasks.register<Exec>("buildPlatformCatalogRelease")',
            'dependsOn(":app:releaseCandidate", testPlatformReleaseContract)',
            'releaseDistributionCandidateDirectory',
            '"--expected-commit"',
            'inplacexPlatformCatalogBaseReleaseDir',
            'inplacexPlatformRepositoryDir',
            'inplacexPlatformExpectedCommit',
            'inplacexPlatformValidatorSha256',
            'verify_platform_release_contract.py',
        ):
            self.assertIn(required_fragment, gradle_script)

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
            "--changelog",
            "Первый ограниченный релиз.",
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
            mock.patch.object(release_builder, "run_platform_validator"),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            release_builder.main(arguments)

    def create_platform_repository(self, root: Path, schema_version: int = 1) -> Path:
        repository = root / "platform"
        tool = repository / "ops" / "catalog_release_tool.py"
        tool.parent.mkdir(parents=True)
        tool.write_text(
            f"SCHEMA_VERSION = {schema_version}\n"
            "GAME_FIELDS = {'id', 'slug', 'displayName', 'description', 'releases'}\n"
            "APP_LINK_FIELDS = {'packageName', 'certificateSha256Fingerprints'}\n"
            "RELEASE_FIELDS = {\n"
            "    'id', 'platform', 'channel', 'versionName', 'versionCode',\n"
            "    'minimumSupportedVersionCode', 'minimumAndroidSdk', 'publishedAt',\n"
            "    'changelog', 'fileName', 'relativePath', 'sizeBytes', 'sha256',\n"
            "}\n",
            encoding="utf-8",
            newline="\n",
        )
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
            "sourceFileName": "app-signedReleaseCandidate.apk",
            "packageName": "com.mirkori.inplacex",
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
            "package_name=com.mirkori.inplacex\n"
            f"version_name={version_name}\n"
            f"version_code={version_code}\n"
            "minimum_android_sdk=29\n"
            "debuggable=false\n",
            encoding="utf-8",
        )
        return candidate

    def create_base_catalog(self, root: Path) -> Path:
        base = root / "base"
        artifact = base / "artifacts" / "another-game" / "windows" / "another.zip"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"other-game-artifact")
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        catalog = {
            "schemaVersion": 1,
            "games": [
                {
                    "id": "another-game",
                    "slug": "another-game",
                    "displayName": "Another Game",
                    "description": "Existing game must remain in the shared catalog.",
                    "releases": [
                        {
                            "id": "another-release",
                            "platform": "windows",
                            "channel": "stable",
                            "versionName": "2.0",
                            "versionCode": 2,
                            "minimumSupportedVersionCode": 1,
                            "minimumAndroidSdk": None,
                            "publishedAt": "2026-08-01T00:00:00Z",
                            "changelog": "Existing release.",
                            "fileName": "another.zip",
                            "relativePath": "another-game/windows/another.zip",
                            "sizeBytes": artifact.stat().st_size,
                            "sha256": digest,
                        }
                    ],
                }
            ],
        }
        (base / "catalog.json").write_text(json.dumps(catalog), encoding="utf-8")
        return base

    @staticmethod
    def fingerprint() -> str:
        return ":".join(["AB"] * 32)


if __name__ == "__main__":
    unittest.main()
