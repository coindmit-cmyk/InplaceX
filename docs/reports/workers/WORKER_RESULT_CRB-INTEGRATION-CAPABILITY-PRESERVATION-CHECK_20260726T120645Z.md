# Worker Result CRB-INTEGRATION-CAPABILITY-PRESERVATION-CHECK

- Generated: `2026-07-26T12:06:45Z`
- Worker: `auto-worker-5.5max`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5max/crb-integration-capability-preservation-check/implement-fail-closed-capability-preservation-in-retry-20260726T115941Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T115944Z`
- Result: `agent_done`
- Check evidence: `passed`
- Next owner: `Integrator`

## Check Evidence

```json
{
  "ok": true,
  "route": "agent_done",
  "check_status": "passed",
  "integration_status": "pending",
  "reason": "worker output contains explicit passed check_status",
  "negative_markers": [],
  "positive_markers": [
    "ok",
    "passed",
    "git diff --check",
    "check_status=passed"
  ]
}
```

## stdout

```text
Реализован fail-closed capability-preservation gate с детерминированным JSON и проверкой всех перестановок delete/modify/rename/move.

Изменения:

- [capability_preservation_check.py](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T115944Z/scripts/agent_control/capability_preservation_check.py)
- [test_capability_preservation_check.py](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T115944Z/scripts/agent_control/test_capability_preservation_check.py)
- [SCRIPTS_CATALOG.md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T115944Z/docs/automation/SCRIPTS_CATALOG.md)
- [worker report](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T115944Z/docs/reports/workers/WORKER_RESULT_CRB-INTEGRATION-CAPABILITY-PRESERVATION-CHECK_20260726T120545Z.md)

Проверки:

- `python scripts/agent_control/test_capability_preservation_check.py` — passed, 8 tests.
- `python scripts/agent_control/test_integrator_guard_regressions.py` — passed, 6 tests.
- `python scripts/agent_control/capability_preservation_check.py --base-ref HEAD^ --head-ref HEAD --all-changed --json` — passed, `status=preserved`.
- `git diff --check` — passed.
- Проверка scope и отсутствия bytecode-артефактов — passed.

`check_status=passed`

Task Manager не изменялся; handoff: `integration_requested`.

```

## stderr

```text
.get(source_path, set())
+        for token in sorted(source_tokens & after_global - source_after_tokens):
+            moved_to = sorted(path for path, tokens in after.items() if token in tokens and path != source_path)
+            if moved_to:
+                append_unique(report["preserved_moves"], {
+                    "capability": token,
+                    "source_path": source_path,
+                    "destination_paths": moved_to,
+                })
+            elif destination_path and destination_path != source_path:
+                report["errors"].append(f"move_destination_evidence_missing:{source_path}")
+
+
+def result_for(repo: Path, base_ref: str, head_ref: str, all_changed: bool) -> dict[str, Any]:
+    base_sha, base_error = resolve_ref(repo, base_ref)
+    head_sha, head_error = resolve_ref(repo, head_ref)
+    report: dict[str, Any] = {
+        "all_changed": all_changed,
+        "base_ref": base_ref,
+        "base_sha": base_sha,
+        "changed_paths": [],
+        "errors": [error for error in (base_error, head_error) if error],
+        "exit_decision": "error",
+        "head_ref": head_ref,
+        "head_sha": head_sha,
+        "ok": False,
+        "potential_removals": [],
+        "preserved_moves": [],
+        "status": "error",
+    }
+    if not all_changed:
+        report["errors"].append("all_changed_required")
+    if report["errors"]:
+        return finish(report)
+    assert base_sha and head_sha
+    entries, scan_errors = changed_entries(repo, base_sha, head_sha)
+    report["errors"].extend(scan_errors)
+    report["changed_paths"] = sorted({path for entry in entries for path in (entry.source_path, entry.destination_path) if path})
+    if not report["errors"]:
+        compare_entries(repo, base_sha, head_sha, entries, report)
+    return finish(report)
+
+
+def finish(report: dict[str, Any]) -> dict[str, Any]:
+    report["errors"] = sorted(set(report["errors"]))
+    report["potential_removals"] = sorted(
+        report["potential_removals"],
+        key=lambda item: (item.get("kind", ""), item.get("source_path", ""), item.get("capability", "")),
+    )
+    report["preserved_moves"] = sorted(
+        report["preserved_moves"],
+        key=lambda item: (item["capability"], item["source_path"], item["destination_paths"]),
+    )
+    if report["errors"]:
+        report.update(status="error", exit_decision="error", ok=False)
+    elif report["potential_removals"]:
+        report.update(status="review_required", exit_decision="review_required", ok=False)
+    else:
+        report.update(status="preserved", exit_decision="allow", ok=True)
+    return report
+
+
+def parse_args() -> argparse.Namespace:
+    parser = argparse.ArgumentParser(description=__doc__)
+    parser.add_argument("--base-ref", required=True, help="Existing integration ref to preserve.")
+    parser.add_argument("--head-ref", required=True, help="Candidate ref to inspect.")
+    parser.add_argument("--all-changed", action="store_true", help="Require the complete changed-ref surface.")
+    parser.add_argument("--json", action="store_true", help="Emit deterministic JSON only.")
+    return parser.parse_args()
+
+
+def main() -> int:
+    args = parse_args()
+    report = result_for(Path.cwd(), args.base_ref, args.head_ref, args.all_changed)
+    if args.json:
+        print(json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
+    else:
+        print(f"capability preservation: {report['status']} ({len(report['potential_removals'])} findings)")
+    return 0 if report["status"] == "preserved" else 1 if report["status"] == "review_required" else 2
+
+
+if __name__ == "__main__":
+    sys.exit(main())
diff --git a/scripts/agent_control/test_capability_preservation_check.py b/scripts/agent_control/test_capability_preservation_check.py
new file mode 100644
index 0000000000000000000000000000000000000000..ebcf948ca5948b0e1113bd22530a72a5aa2bca18
--- /dev/null
+++ b/scripts/agent_control/test_capability_preservation_check.py
@@ -0,0 +1,152 @@
+#!/usr/bin/env python3
+from __future__ import annotations
+
+import json
+from itertools import permutations
+import subprocess
+import sys
+import tempfile
+import unittest
+from pathlib import Path
+from unittest import mock
+
+import capability_preservation_check as check
+
+
+class CapabilityPreservationCheckTests(unittest.TestCase):
+    def setUp(self) -> None:
+        self.temp = tempfile.TemporaryDirectory()
+        self.repo = Path(self.temp.name) / "repository with spaces"
+        self.repo.mkdir()
+        self.git("init")
+        self.git("config", "user.email", "test@example.invalid")
+        self.git("config", "user.name", "Test")
+
+    def tearDown(self) -> None:
+        self.temp.cleanup()
+
+    def git(self, *args: str) -> None:
+        subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True)
+
+    def commit(self, files: dict[str, str], message: str) -> None:
+        for name, content in files.items():
+            path = self.repo / name
+            path.parent.mkdir(parents=True, exist_ok=True)
+            path.write_text(content, encoding="utf-8")
+        self.git("add", ".")
+        self.git("commit", "-m", message)
+
+    def report(self) -> dict:
+        return check.result_for(self.repo, "HEAD^", "HEAD", True)
+
+    def test_unchanged_refs_are_preserved_and_deterministic(self) -> None:
+        self.commit({"tool.py": "def keep():\n    return 1\n"}, "initial")
+        first = check.result_for(self.repo, "HEAD", "HEAD", True)
+        second = check.result_for(self.repo, "HEAD", "HEAD", True)
+        self.assertEqual("preserved", first["status"])
+        self.assertEqual(first, second)
+
+    def test_symbols_moved_to_renamed_file_are_preserved(self) -> None:
+        self.commit({"old.py": "class Guard:\n    pass\n\ndef keep():\n    pass\n"}, "initial")
+        self.git("mv", "old.py", "new.py")
+        self.git("commit", "-m", "rename")
+        report = self.report()
+        self.assertEqual("preserved", report["status"])
+        self.assertIn("function:keep", [item["capability"] for item in report["preserved_moves"]])
+
+    def test_symbols_moved_between_changed_files_are_preserved(self) -> None:
+        self.commit({"source.py": "def moved():\n    pass\n", "destination.py": "def existing():\n    pass\n"}, "initial")
+        self.commit({"source.py": "# moved\n", "destination.py": "def existing():\n    pass\n\ndef moved():\n    pass\n"}, "move symbol")
+        report = self.report()
+        self.assertEqual("preserved", report["status"])
+        self.assertIn("function:moved", [item["capability"] for item in report["preserved_moves"]])
+
+    def test_function_class_and_raw_file_removals_require_review(self) -> None:
+        self.commit({
+            "tool.py": "class Removed:\n    pass\n\ndef removed():\n    pass\n",
+            "note.md": "plain content\n",
+            "entry.py": "#!/usr/bin/env python3\ndef entry():\n    pass\n",
+        }, "initial")
+        (self.repo / "tool.py").unlink()
+        (self.repo / "note.md").unlink()
+        (self.repo / "entry.py").unlink()
+        self.git("add", "-A")
+        self.git("commit", "-m", "remove files")
+        report = self.report()
+        self.assertEqual("review_required", report["status"])
+        self.assertIn("function:removed", [item.get("capability") for item in report["potential_removals"]])
+        self.assertIn("class:Removed", [item.get("capability") for item in report["potential_removals"]])
+        self.assertEqual(
+            ["entry.py", "note.md", "tool.py"],
+            [item["source_path"] for item in report["potential_removals"] if item["kind"] == "file_removed"],
+        )
+        self.assertIn("entrypoint:executable", [item.get("capability") for item in report["potential_removals"]])
+
+    def test_cli_flag_json_field_status_and_heading_loss_require_review(self) -> None:
+        self.commit({
+            "command.py": "parser.add_argument('--safe')\n",
+            "contract.json": '{"status":"ready","required":true}',
+            "README.md": "# Public Contract\n",
+        }, "initial")
+        self.commit({"command.py": "pass\n", "contract.json": "{}", "README.md": "# Different Heading\n"}, "replace")
+        report = self.report()
+        removed = [item.get("capability") for item in report["potential_removals"]]
+        self.assertIn("flag:--safe", removed)
+        self.assertIn("field:required", removed)
+        self.assertIn("status:ready", removed)
+        self.assertIn("heading:public contract", removed)
+        command = [sys.executable, str(Path(check.__file__)), "--base-ref", "HEAD^", "--head-ref", "HEAD", "--all-changed", "--json"]
+        self.assertEqual(1, subprocess.run(command, cwd=self.repo, capture_output=True, check=False).returncode)
+
+    def test_invalid_ref_invalid_json_and_incomplete_request_fail_closed(self) -> None:
+        self.commit({"contract.json": '{"field": true}'}, "initial")
+        invalid_ref = check.result_for(self.repo, "missing-ref", "HEAD", True)
+        self.assertEqual("error", invalid_ref["status"])
+        self.assertIn("ref_unresolved:missing-ref", invalid_ref["errors"])
+        self.assertEqual("error", check.result_for(self.repo, "HEAD", "HEAD", False)["status"])
+        self.commit({"contract.json": "{"}, "invalid json")
+        self.assertEqual("error", self.report()["status"])
+
+    def test_cli_json_handles_paths_with_spaces(self) -> None:
+        self.commit({"dir with spaces/tool.py": "def keep():\n    pass\n"}, "initial")
+        self.commit({"dir with spaces/tool.py": "def keep():\n    return 1\n"}, "change")
+        command = [sys.executable, str(Path(check.__file__)), "--base-ref", "HEAD^", "--head-ref", "HEAD", "--all-changed", "--json"]
+        result = subprocess.run(command, cwd=self.repo, text=True, capture_output=True, check=False)
+        self.assertEqual(0, result.returncode, result.stderr)
+        self.assertEqual("preserved", json.loads(result.stdout)["status"])
+
+    def test_deleted_path_attribution_is_independent_of_changed_entry_order(self) -> None:
+        self.commit({
+            "deleted.py": "def gone():\n    pass\n",
+            "same.py": "def retained():\n    return 1\n",
+            "old.py": "def renamed():\n    pass\n",
+            "source.py": "def moved():\n    pass\n",
+            "destination.py": "def destination():\n    pass\n",
+        }, "initial")
+        (self.repo / "deleted.py").unlink()
+        self.git("mv", "old.py", "renamed.py")
+        (self.repo / "same.py").write_text("def retained():\n    return 2\n", encoding="utf-8")
+        (self.repo / "source.py").write_text("# moved to destination\n", encoding="utf-8")
+        (self.repo / "destination.py").write_text("def destination():\n    pass\n\ndef moved():\n    pass\n", encoding="utf-8")
+        self.git("add", "-A")
+        self.git("commit", "-m", "delete modify rename move")
+
+        entries, errors = check.changed_entries(self.repo, "HEAD^", "HEAD")
+        self.assertEqual([], errors)
+        reports = []
+        for ordering in permutations(entries):
+            with mock.patch.object(check, "changed_entries", return_value=(ordering, [])):
+                reports.append(check.result_for(self.repo, "HEAD^", "HEAD", True))
+
+        self.assertEqual(
+            [json.dumps(reports[0], sort_keys=True, separators=(",", ":"))] * len(reports),
+            [json.dumps(report, sort_keys=True, separators=(",", ":")) for report in reports],
+        )
+        file_removals = [item["source_path"] for item in reports[0]["potential_removals"] if item["kind"] == "file_removed"]
+        self.assertEqual(["deleted.py"], file_removals)
+        self.assertNotIn("old.py", file_removals)
+        self.assertNotIn("source.py", file_removals)
+
+
+if __name__ == "__main__":
+    unittest.main()

tokens used
117 610

```
