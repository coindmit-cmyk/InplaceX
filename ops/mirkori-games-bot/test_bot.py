import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from bot import is_chat_allowed, load_catalog, verify_release_file


class CatalogTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
