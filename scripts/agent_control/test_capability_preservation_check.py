#!/usr/bin/env python3
from __future__ import annotations

import json
from itertools import permutations
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import capability_preservation_check as check


class CapabilityPreservationCheckTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name) / "repository with spaces"
        self.repo.mkdir()
        self.git("init")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def git(self, *args: str) -> None:
        subprocess.run(
            ["git", *args], cwd=self.repo, check=True, capture_output=True
        )

    def commit(self, files: dict[str, str], message: str) -> None:
        for name, content in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-m", message)

    def report(self) -> dict:
        return check.result_for(self.repo, "HEAD^", "HEAD", True)

    def test_unchanged_refs_are_preserved_and_deterministic(self) -> None:
        self.commit({"tool.py": "def keep():\n    return 1\n"}, "initial")
        first = check.result_for(self.repo, "HEAD", "HEAD", True)
        second = check.result_for(self.repo, "HEAD", "HEAD", True)
        self.assertEqual("preserved", first["status"])
        self.assertEqual(first, second)

    def test_symbols_moved_to_renamed_file_are_preserved(self) -> None:
        self.commit(
            {"old.py": "class Guard:\n    pass\n\ndef keep():\n    pass\n"},
            "initial",
        )
        self.git("mv", "old.py", "new.py")
        self.git("commit", "-m", "rename")
        report = self.report()
        self.assertEqual("preserved", report["status"])
        self.assertIn(
            "function:keep",
            [item["capability"] for item in report["preserved_moves"]],
        )

    def test_symbols_moved_between_changed_files_are_preserved(self) -> None:
        self.commit(
            {
                "source.py": "def moved():\n    pass\n",
                "destination.py": "def existing():\n    pass\n",
            },
            "initial",
        )
        self.commit(
            {
                "source.py": "# moved\n",
                "destination.py": (
                    "def existing():\n    pass\n\ndef moved():\n    pass\n"
                ),
            },
            "move symbol",
        )
        report = self.report()
        self.assertEqual("preserved", report["status"])
        self.assertIn(
            "function:moved",
            [item["capability"] for item in report["preserved_moves"]],
        )

    def test_duplicate_function_does_not_mask_removal(self) -> None:
        self.commit(
            {
                "source.py": "def duplicate():\n    return 'source'\n",
                "other.py": (
                    "def duplicate():\n    return 'other'\n\n"
                    "def changed():\n    return 1\n"
                ),
            },
            "initial",
        )
        self.commit(
            {
                "source.py": "# duplicate removed\n",
                "other.py": (
                    "def duplicate():\n    return 'other'\n\n"
                    "def changed():\n    return 2\n"
                ),
            },
            "hostile duplicate function",
        )
        report = self.report()
        self.assertEqual("review_required", report["status"])
        self.assertIn(
            {
                "kind": "capability_removed",
                "source_path": "source.py",
                "capability": "function:duplicate",
            },
            report["potential_removals"],
        )

    def test_duplicate_class_does_not_mask_removal(self) -> None:
        self.commit(
            {
                "source.py": "class Duplicate:\n    pass\n",
                "other.py": (
                    "class Duplicate:\n    pass\n\n"
                    "def changed():\n    return 1\n"
                ),
            },
            "initial",
        )
        self.commit(
            {
                "source.py": "# class removed\n",
                "other.py": (
                    "class Duplicate:\n    pass\n\n"
                    "def changed():\n    return 2\n"
                ),
            },
            "hostile duplicate class",
        )
        report = self.report()
        self.assertEqual("review_required", report["status"])
        self.assertIn(
            {
                "kind": "capability_removed",
                "source_path": "source.py",
                "capability": "class:Duplicate",
            },
            report["potential_removals"],
        )

    def test_function_class_and_raw_file_removals_require_review(self) -> None:
        self.commit(
            {
                "tool.py": (
                    "class Removed:\n    pass\n\ndef removed():\n    pass\n"
                ),
                "note.md": "plain content\n",
                "entry.py": (
                    "#!/usr/bin/env python3\ndef entry():\n    pass\n"
                ),
            },
            "initial",
        )
        for name in ("tool.py", "note.md", "entry.py"):
            (self.repo / name).unlink()
        self.git("add", "-A")
        self.git("commit", "-m", "remove files")
        report = self.report()
        self.assertEqual("review_required", report["status"])
        removed = [
            item.get("capability") for item in report["potential_removals"]
        ]
        self.assertIn("function:removed", removed)
        self.assertIn("class:Removed", removed)
        self.assertIn("entrypoint:executable", removed)
        self.assertEqual(
            ["entry.py", "note.md", "tool.py"],
            [
                item["source_path"]
                for item in report["potential_removals"]
                if item["kind"] == "file_removed"
            ],
        )

    def test_cli_flag_json_field_status_and_heading_loss_require_review(self) -> None:
        self.commit(
            {
                "command.py": "parser.add_argument('--safe')\n",
                "contract.json": '{"status":"ready","required":true}',
                "README.md": "# Public Contract\n",
            },
            "initial",
        )
        self.commit(
            {
                "command.py": "pass\n",
                "contract.json": "{}",
                "README.md": "# Different Heading\n",
            },
            "replace",
        )
        report = self.report()
        removed = [
            item.get("capability") for item in report["potential_removals"]
        ]
        self.assertIn("flag:--safe", removed)
        self.assertIn("field:required", removed)
        self.assertIn("status:ready", removed)
        self.assertIn("heading:public contract", removed)
        command = [
            sys.executable,
            str(Path(check.__file__)),
            "--base-ref",
            "HEAD^",
            "--head-ref",
            "HEAD",
            "--all-changed",
            "--json",
        ]
        self.assertEqual(
            1,
            subprocess.run(
                command, cwd=self.repo, capture_output=True, check=False
            ).returncode,
        )

    def test_invalid_ref_invalid_json_and_incomplete_request_fail_closed(self) -> None:
        self.commit({"contract.json": '{"field": true}'}, "initial")
        invalid_ref = check.result_for(
            self.repo, "missing-ref", "HEAD", True
        )
        self.assertEqual("error", invalid_ref["status"])
        self.assertIn("ref_unresolved:missing-ref", invalid_ref["errors"])
        self.assertEqual(
            "error",
            check.result_for(self.repo, "HEAD", "HEAD", False)["status"],
        )
        self.commit({"contract.json": "{"}, "invalid json")
        self.assertEqual("error", self.report()["status"])

    def test_cli_json_handles_paths_with_spaces(self) -> None:
        self.commit(
            {"dir with spaces/tool.py": "def keep():\n    pass\n"}, "initial"
        )
        self.commit(
            {"dir with spaces/tool.py": "def keep():\n    return 1\n"},
            "change",
        )
        command = [
            sys.executable,
            str(Path(check.__file__)),
            "--base-ref",
            "HEAD^",
            "--head-ref",
            "HEAD",
            "--all-changed",
            "--json",
        ]
        result = subprocess.run(
            command,
            cwd=self.repo,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("preserved", json.loads(result.stdout)["status"])

    def test_deleted_path_attribution_is_independent_of_entry_order(self) -> None:
        self.commit(
            {
                "deleted.py": "def gone():\n    pass\n",
                "same.py": "def retained():\n    return 1\n",
                "old.py": "def renamed():\n    pass\n",
                "source.py": "def moved():\n    pass\n",
                "destination.py": "def destination():\n    pass\n",
            },
            "initial",
        )
        (self.repo / "deleted.py").unlink()
        self.git("mv", "old.py", "renamed.py")
        (self.repo / "same.py").write_text(
            "def retained():\n    return 2\n", encoding="utf-8"
        )
        (self.repo / "source.py").write_text(
            "# moved to destination\n", encoding="utf-8"
        )
        (self.repo / "destination.py").write_text(
            "def destination():\n    pass\n\ndef moved():\n    pass\n",
            encoding="utf-8",
        )
        self.git("add", "-A")
        self.git("commit", "-m", "delete modify rename move")

        entries, errors = check.changed_entries(self.repo, "HEAD^", "HEAD")
        self.assertEqual([], errors)
        reports = []
        for ordering in permutations(entries):
            with mock.patch.object(
                check, "changed_entries", return_value=(ordering, [])
            ):
                reports.append(
                    check.result_for(self.repo, "HEAD^", "HEAD", True)
                )

        serialized = [
            json.dumps(report, sort_keys=True, separators=(",", ":"))
            for report in reports
        ]
        self.assertEqual([serialized[0]] * len(serialized), serialized)
        file_removals = [
            item["source_path"]
            for item in reports[0]["potential_removals"]
            if item["kind"] == "file_removed"
        ]
        self.assertEqual(["deleted.py"], file_removals)
        self.assertNotIn("old.py", file_removals)
        self.assertNotIn("source.py", file_removals)


if __name__ == "__main__":
    unittest.main()
