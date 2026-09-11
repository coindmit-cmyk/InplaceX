import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from bot import (
    TelegramApi,
    is_chat_allowed,
    load_catalog,
    load_platform_catalog,
    verify_release_file,
)


def write_platform_v3_catalog(
    root: Path,
    *,
    rf_stable_status: str = "active",
    rf_beta_status: str = "active",
    rf_relative_path: str | None = None,
    omit_policy_for: str | None = None,
) -> Path:
    fingerprint = ":".join(["AA"] * 32)
    distributions = [
        {
            "id": "rf-mirkori",
            "platform": "android",
            "marketScope": "rf",
            "packageName": "com.mirkori.inplacex.rf",
            "signingIdentityRef": "inplacex-rf-release",
            "certificateSha256Fingerprints": [fingerprint],
            "paymentChannel": "mirkori",
            "deliveryChannel": "direct_apk",
            "releaseChannels": ["stable", "beta"],
            "status": "active",
            "effectiveConfigurationVersion": 1,
        },
        {
            "id": "global-google",
            "platform": "android",
            "marketScope": "global",
            "packageName": "com.mirkori.inplacex",
            "signingIdentityRef": "inplacex-global-play",
            "certificateSha256Fingerprints": [fingerprint],
            "paymentChannel": "google_play",
            "deliveryChannel": "google_play",
            "releaseChannels": ["stable", "beta"],
            "status": "active",
            "effectiveConfigurationVersion": 1,
        },
    ]
    release_specs = [
        ("inplacex-rf-stable-5", "rf-mirkori", "stable", 5),
        ("inplacex-rf-beta-9", "rf-mirkori", "beta", 9),
        ("inplacex-global-stable-20", "global-google", "stable", 20),
    ]
    releases = []
    for release_id, distribution_id, channel, version_code in release_specs:
        relative = Path("inplacex") / distribution_id / channel / f"InplaceX-{version_code}.apk"
        apk = root / relative
        apk.parent.mkdir(parents=True, exist_ok=True)
        apk.write_bytes(f"verified-{release_id}".encode())
        releases.append(
            {
                "id": release_id,
                "distributionId": distribution_id,
                "channel": channel,
                "versionName": f"1.0.{version_code}",
                "versionCode": version_code,
                "minimumSupportedVersionCode": 1,
                "minimumAndroidSdk": 26,
                "publishedAt": "2026-09-01T12:00:00Z",
                "changelogs": {"ru": f"Релиз {version_code}\nПроверено", "en": f"Release {version_code}\nVerified"},
                "fileName": apk.name,
                "relativePath": (
                    rf_relative_path
                    if distribution_id == "rf-mirkori" and channel == "stable" and rf_relative_path is not None
                    else relative.as_posix()
                ),
                "sizeBytes": apk.stat().st_size,
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            },
        )
    statuses = {
        "inplacex-rf-stable-5": rf_stable_status,
        "inplacex-rf-beta-9": rf_beta_status,
        "inplacex-global-stable-20": "active",
    }
    policies = [
        {
            "releaseId": release_id,
            "status": status,
            "effectiveAt": "2026-09-02T12:00:00Z",
            "policyVersion": 1,
            **(
                {"reasonCode": "security_recall", "supportPath": "/ru/support/releases"}
                if status == "recalled"
                else {}
            ),
        }
        for release_id, status in statuses.items()
        if release_id != omit_policy_for
    ]
    catalog = root / "catalog-v3.json"
    catalog.write_text(
        json.dumps(
            {
                "schemaVersion": 3,
                "games": [
                    {
                        "id": "inplacex",
                        "slug": "inplacex",
                        "displayName": "InplaceX",
                        "description": "Logic game",
                        "distributionVariants": distributions,
                        "releases": releases,
                        "releasePolicies": policies,
                    },
                ],
            },
        ),
        encoding="utf-8",
    )
    return catalog


class CatalogTest(unittest.TestCase):
    def test_platform_v3_selects_active_rf_stable_and_never_global_google(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            release = load_platform_catalog(write_platform_v3_catalog(root), root)[0]

            self.assertEqual("1.0.5", release.version)
            self.assertEqual("Релиз 5\nПроверено", release.notes)
            self.assertEqual(
                "https://games.dmit.life/downloads/inplacex-rf-stable-5/InplaceX-5.apk",
                release.download_url,
            )

    def test_platform_v3_falls_back_to_active_rf_beta(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            release = load_platform_catalog(
                write_platform_v3_catalog(root, rf_stable_status="delisted"),
                root,
            )[0]

            self.assertEqual("1.0.9", release.version)
            self.assertIn("inplacex-rf-beta-9", release.download_url)

    def test_platform_v3_rejects_catalog_with_only_global_delivery(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            with self.assertRaisesRegex(ValueError, "no active RF direct APK"):
                load_platform_catalog(
                    write_platform_v3_catalog(
                        root,
                        rf_stable_status="delisted",
                        rf_beta_status="recalled",
                    ),
                    root,
                )

    def test_platform_v3_requires_lifecycle_policy_for_every_release(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            with self.assertRaisesRegex(ValueError, "cover every release"):
                load_platform_catalog(
                    write_platform_v3_catalog(root, omit_policy_for="inplacex-rf-beta-9"),
                    root,
                )

    def test_platform_v3_rejects_artifact_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            with self.assertRaisesRegex(ValueError, "artifact path is invalid"):
                load_platform_catalog(
                    write_platform_v3_catalog(root, rf_relative_path="../InplaceX-5.apk"),
                    root,
                )

    def test_platform_catalog_prefers_latest_stable_then_beta_release(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            releases = []
            for channel, version_code in (("beta", 9), ("stable", 3), ("stable", 5)):
                relative = Path("inplacex") / channel / str(version_code) / f"InplaceX-{version_code}.apk"
                apk = root / relative
                apk.parent.mkdir(parents=True, exist_ok=True)
                apk.write_bytes(f"verified-{channel}-{version_code}".encode())
                releases.append(
                    {
                        "id": f"inplacex-{channel}-{version_code}",
                        "platform": "android",
                        "channel": channel,
                        "versionName": f"1.0.{version_code}",
                        "versionCode": version_code,
                        "fileName": apk.name,
                        "relativePath": relative.as_posix(),
                        "sizeBytes": apk.stat().st_size,
                        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                        "changelog": f"Release {version_code}",
                    },
                )
            catalog = root / "catalog.json"
            catalog.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "games": [
                            {
                                "id": "inplacex",
                                "slug": "inplacex",
                                "displayName": "InplaceX",
                                "description": "Logic game",
                                "releases": releases,
                            },
                        ],
                    },
                ) + (" " * (300 * 1024)),
                encoding="utf-8",
            )

            release = load_platform_catalog(catalog, root)[0]

            self.assertEqual("1.0.5", release.version)
            self.assertEqual(
                "https://games.dmit.life/downloads/inplacex-stable-5/InplaceX-5.apk",
                release.download_url,
            )

    def test_platform_catalog_uses_beta_when_stable_is_absent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "inplacex" / "beta" / "InplaceX.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"verified-beta")
            catalog = root / "catalog.json"
            catalog.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "games": [
                            {
                                "id": "inplacex",
                                "displayName": "И" * 120,
                                "releases": [
                                    {
                                        "id": "i" * 64,
                                        "platform": "android",
                                        "channel": "beta",
                                        "versionName": "v" * 64,
                                        "versionCode": 1,
                                        "fileName": apk.name,
                                        "relativePath": apk.relative_to(root).as_posix(),
                                        "sizeBytes": apk.stat().st_size,
                                        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                        "changelog": "Б" * 4000,
                                    },
                                ],
                            },
                        ],
                    },
                ),
                encoding="utf-8",
            )

            release = load_platform_catalog(catalog, root)[0]

            self.assertEqual("v" * 64, release.version)
            self.assertEqual("Б" * 4000, release.notes)
            self.assertEqual(apk, release.apk_path)

    def test_platform_catalog_rejects_artifact_outside_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifacts = root / "artifacts"
            artifacts.mkdir()
            escaped_apk = root / "escaped.apk"
            escaped_apk.write_bytes(b"must-not-be-readable")
            catalog = root / "catalog.json"
            catalog.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "games": [
                            {
                                "id": "inplacex",
                                "displayName": "InplaceX",
                                "releases": [
                                    {
                                        "id": "inplacex-beta-escape",
                                        "platform": "android",
                                        "channel": "beta",
                                        "versionName": "1.0",
                                        "versionCode": 1,
                                        "fileName": escaped_apk.name,
                                        "relativePath": "../escaped.apk",
                                        "sizeBytes": escaped_apk.stat().st_size,
                                        "sha256": hashlib.sha256(escaped_apk.read_bytes()).hexdigest(),
                                        "changelog": "Beta",
                                    },
                                ],
                            },
                        ],
                    },
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "escapes artifact root"):
                load_platform_catalog(catalog, artifacts)

    def test_valid_catalog_requires_matching_apk_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "inplacex.apk"
            apk.write_bytes(b"verified-apk")
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            catalog = root / "games.json"
            catalog.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "games": [
                            {
                                "id": "inplacex",
                                "title": "InplaceX",
                                "version": "test",
                                "apk": "inplacex.apk",
                                "sha256": digest,
                                "notes": "Проверенная тестовая сборка",
                                "downloadUrl": "https://inplacex.dmit.life/downloads/InplaceX.apk",
                            },
                        ],
                    },
                ),
                encoding="utf-8",
            )

            releases = load_catalog(catalog, root)

            self.assertEqual(1, len(releases))
            self.assertEqual(digest, releases[0].sha256)

    def test_catalog_rejects_path_traversal_and_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root.parent / "outside.apk"
            outside.write_bytes(b"outside")
            self.addCleanup(outside.unlink, missing_ok=True)
            catalog = root / "games.json"
            catalog.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "games": [
                            {
                                "id": "inplacex",
                                "title": "InplaceX",
                                "version": "test",
                                "apk": "../outside.apk",
                                "sha256": hashlib.sha256(outside.read_bytes()).hexdigest(),
                                "notes": "",
                                "downloadUrl": "https://inplacex.dmit.life/downloads/InplaceX.apk",
                            },
                        ],
                    },
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                load_catalog(catalog, root)

            apk = root / "inplacex.apk"
            apk.write_bytes(b"apk")
            source = json.loads(catalog.read_text(encoding="utf-8"))
            source["games"][0]["apk"] = "inplacex.apk"
            source["games"][0]["sha256"] = "0" * 64
            catalog.write_text(json.dumps(source), encoding="utf-8")
            with self.assertRaises(ValueError):
                load_catalog(catalog, root)

    def test_downloads_fail_closed_without_allowlist(self) -> None:
        self.assertFalse(is_chat_allowed(100, frozenset(), public_downloads=False))
        self.assertTrue(is_chat_allowed(100, frozenset({100}), public_downloads=False))
        self.assertTrue(is_chat_allowed(100, frozenset(), public_downloads=True))

    def test_rechecks_apk_before_each_delivery(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "inplacex.apk"
            apk.write_bytes(b"verified-apk")
            catalog = root / "games.json"
            catalog.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "games": [
                            {
                                "id": "inplacex",
                                "title": "InplaceX",
                                "version": "test",
                                "apk": "inplacex.apk",
                                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                "notes": "",
                                "downloadUrl": "https://inplacex.dmit.life/downloads/InplaceX.apk",
                            },
                        ],
                    },
                ),
                encoding="utf-8",
            )
            release = load_catalog(catalog, root)[0]

            apk.write_bytes(b"replaced-apk")

            with self.assertRaises(ValueError):
                verify_release_file(release)

    def test_catalog_rejects_unapproved_download_url(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "inplacex.apk"
            apk.write_bytes(b"verified-apk")
            catalog = root / "games.json"
            catalog.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "games": [
                            {
                                "id": "inplacex",
                                "title": "InplaceX",
                                "version": "test",
                                "apk": "inplacex.apk",
                                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                "notes": "",
                                "downloadUrl": "https://example.com/InplaceX.apk",
                            },
                        ],
                    },
                ),
                encoding="utf-8",
            )

            with self.assertRaises(ValueError):
                load_catalog(catalog, root)

    def test_delivery_uses_https_link_instead_of_telegram_document(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "inplacex.apk"
            apk.write_bytes(b"verified-apk")
            catalog = root / "games.json"
            catalog.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "games": [
                            {
                                "id": "inplacex",
                                "title": "InplaceX",
                                "version": "test",
                                "apk": "inplacex.apk",
                                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                "notes": "",
                                "downloadUrl": "https://inplacex.dmit.life/downloads/InplaceX.apk",
                            },
                        ],
                    },
                ),
                encoding="utf-8",
            )
            api = RecordingTelegramApi()

            api.send_download_link(100, load_catalog(catalog, root)[0])

            self.assertEqual("sendMessage", api.method)
            self.assertEqual(
                "https://inplacex.dmit.life/downloads/InplaceX.apk",
                api.payload["reply_markup"]["inline_keyboard"][0][0]["url"],
            )


class RecordingTelegramApi(TelegramApi):
    def __init__(self) -> None:
        super().__init__("test-token")
        self.method = ""
        self.payload = {}

    def call(self, method, payload):
        self.method = method
        self.payload = payload
        return {"ok": True}


if __name__ == "__main__":
    unittest.main()
