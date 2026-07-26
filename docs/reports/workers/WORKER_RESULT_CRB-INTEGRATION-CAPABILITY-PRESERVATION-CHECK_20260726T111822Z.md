# Worker Result CRB-INTEGRATION-CAPABILITY-PRESERVATION-CHECK

- Generated: `2026-07-26T11:18:22Z`
- Worker: `auto-worker-5.5max`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5max/crb-integration-capability-preservation-check/implement-fail-closed-capability-preservation-in`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T111236Z`
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
Реализован fail-closed capability-preservation gate и тесты.

Изменены:
- [capability_preservation_check.py](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T111236Z/scripts/agent_control/capability_preservation_check.py)
- [test_capability_preservation_check.py](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T111236Z/scripts/agent_control/test_capability_preservation_check.py)
- [SCRIPTS_CATALOG.md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T111236Z/docs/automation/SCRIPTS_CATALOG.md)
- [worker report](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-integration-capability-preservation-check-20260726T111236Z/docs/reports/workers/WORKER_RESULT_CRB-INTEGRATION-CAPABILITY-PRESERVATION-CHECK_20260726T111236Z.md)

Проверки:

- `python scripts/agent_control/test_capability_preservation_check.py` — passed, 8 tests.
- `python scripts/agent_control/test_integrator_guard_regressions.py` — passed, 6 tests.
- `python scripts/agent_control/capability_preservation_check.py --base-ref HEAD^ --head-ref HEAD --all-changed --json` — passed, `status=preserved`.
- `git diff --check` — passed.
- `python -m py_compile ...` — passed.

`integration_requested`: Integrator должен запустить gate для точного candidate SHA перед интеграцией. Runner-managed Task_manager не изменялся.

check_status=passed

```

## stderr

```text
tance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
+                tokens.add(f"function:{node.name}")
+                if node.name == "main":
+                    tokens.add("entrypoint:main")
+            elif isinstance(node, ast.ClassDef):
+                tokens.add(f"class:{node.name}")
+    for line in text.splitlines():
+        function = FUNCTION_PATTERN.search(line)
+        if function:
+            name = next((item for item in function.groups() if item), "")
+            if name:
+                tokens.add(f"function:{name}")
+                if name == "main":
+                    tokens.add("entrypoint:main")
+        class_match = CLASS_PATTERN.search(line)
+        if class_match:
+            tokens.add(f"class:{class_match.group(1)}")
+        heading = HEADING_PATTERN.match(line)
+        if heading:
+            tokens.add(f"heading:{' '.join(heading.group(1).split()).lower()}")
+        yaml_field = YAML_FIELD_PATTERN.match(line)
+        if yaml_field and suffix in {".yaml", ".yml"}:
+            name = yaml_field.group(1)
+            tokens.add(f"field:{name}")
+            if name in {"status", "state"}:
+                value = line.split(":", 1)[1].strip().strip("\"'")
+                if value:
+                    tokens.add(f"status:{value}")
+    for flag in FLAG_PATTERN.findall(text):
+        tokens.add(f"flag:{flag.lower()}")
+    if re.search(r"^#!", text) or re.search(r"if\s+__name__\s*==\s*[\"']__main__[\"']", text, re.MULTILINE):
+        tokens.add("entrypoint:executable")
+    return tokens, None
+
+
+def result_for(repo: Path, base_ref: str, head_ref: str, all_changed: bool) -> dict[str, Any]:
+    base_sha, base_error = resolve_ref(repo, base_ref)
+    head_sha, head_error = resolve_ref(repo, head_ref)
+    errors = [item for item in (base_error, head_error) if item]
+    report: dict[str, Any] = {
+        "base_ref": base_ref,
+        "head_ref": head_ref,
+        "base_sha": base_sha,
+        "head_sha": head_sha,
+        "all_changed": all_changed,
+        "changed_paths": [],
+        "preserved_moves": [],
+        "potential_removals": [],
+        "errors": errors,
+    }
+    if errors:
+        return finish(report)
+    assert base_sha and head_sha
+    entries, scan_errors = changed_entries(repo, base_sha, head_sha)
+    report["errors"].extend(scan_errors)
+    report["changed_paths"] = sorted({path for entry in entries for path in entry if path})
+    before: dict[str, set[str]] = {}
+    after: dict[str, set[str]] = {}
+    for old_path, new_path in entries:
+        if old_path:
+            text, error = read_blob(repo, base_sha, old_path)
+            if error:
+                report["errors"].append(error)
+            else:
+                tokens, error = capability_tokens(text or "", old_path)
+                if error:
+                    report["errors"].append(error)
+                else:
+                    before[old_path] = tokens
+                    if not tokens and new_path:
+                        report["potential_removals"].append({"kind": "comparison_evidence_missing", "source_path": old_path})
+        if new_path:
+            text, error = read_blob(repo, head_sha, new_path)
+            if error:
+                report["errors"].append(error)
+            else:
+                tokens, error = capability_tokens(text or "", new_path)
+                if error:
+                    report["errors"].append(error)
+                else:
+                    after[new_path] = tokens
+    after_global = set().union(*after.values()) if after else set()
+    for old_path, tokens in sorted(before.items()):
+        if new_path is None:
+            report["potential_removals"].append({"kind": "file_removed", "source_path": old_path})
+        for token in sorted(tokens - after_global):
+            report["potential_removals"].append({"kind": "capability_removed", "source_path": old_path, "capability": token})
+        for token in sorted(tokens & after_global - after.get(old_path, set())):
+            destinations = sorted(path for path, values in after.items() if token in values)
+            report["preserved_moves"].append({"capability": token, "source_path": old_path, "destination_paths": destinations})
+    return finish(report)
+
+
+def finish(report: dict[str, Any]) -> dict[str, Any]:
+    report["errors"] = sorted(set(report["errors"]))
+    report["potential_removals"] = sorted(report["potential_removals"], key=lambda item: (item.get("kind", ""), item.get("source_path", ""), item.get("capability", "")))
+    report["preserved_moves"] = sorted(report["preserved_moves"], key=lambda item: (item["capability"], item["source_path"], item["destination_paths"]))
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
+    parser.add_argument("--all-changed", action="store_true", help="Record that the complete changed-ref surface is required.")
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
index 0000000000000000000000000000000000000000..87d006b19c1b693b22fabf77807b545cfe817813
--- /dev/null
+++ b/scripts/agent_control/test_capability_preservation_check.py
@@ -0,0 +1,108 @@
+#!/usr/bin/env python3
+from __future__ import annotations
+
+import json
+import subprocess
+import sys
+import tempfile
+import unittest
+from pathlib import Path
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
+    def delete(self, name: str, message: str) -> None:
+        (self.repo / name).unlink()
+        self.git("add", "-A")
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
+        self.git("commit", "-m", "move")
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
+    def test_function_and_raw_file_removals_require_review(self) -> None:
+        self.commit({"tool.py": "class Removed:\n    pass\n\ndef removed():\n    pass\n", "note.md": "plain content\n"}, "initial")
+        self.delete("tool.py", "remove tool")
+        report = self.report()
+        self.assertEqual("review_required", report["status"])
+        self.assertIn("function:removed", [item.get("capability") for item in report["potential_removals"]])
+        self.assertIn("class:Removed", [item.get("capability") for item in report["potential_removals"]])
+        self.assertIn("file_removed", [item["kind"] for item in report["potential_removals"]])
+
+    def test_empty_file_deletion_requires_review_without_extracted_tokens(self) -> None:
+        self.commit({"note.md": "plain content\n"}, "initial")
+        self.delete("note.md", "remove note")
+        self.assertEqual("review_required", self.report()["status"])
+
+    def test_cli_flag_json_field_status_and_heading_loss_require_review(self) -> None:
+        self.commit({"command.py": "parser.add_argument('--safe')\n", "contract.json": '{"status":"ready","required":true}', "README.md": "# Public Contract\n"}, "initial")
+        self.commit({"command.py": "pass\n", "contract.json": "{}", "README.md": "# Different Heading\n"}, "replace")
+        report = self.report()
+        removed = [item.get("capability") for item in report["potential_removals"]]
+        self.assertIn("flag:--safe", removed)
+        self.assertIn("field:required", removed)
+        self.assertIn("status:ready", removed)
+        self.assertIn("heading:public contract", removed)
+
+    def test_invalid_ref_and_invalid_json_fail_closed(self) -> None:
+        self.commit({"contract.json": '{"field": true}'}, "initial")
+        invalid_ref = check.result_for(self.repo, "missing-ref", "HEAD", True)
+        self.assertEqual("error", invalid_ref["status"])
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
+
+if __name__ == "__main__":
+    unittest.main()

tokens used
91 682

```
