#!/usr/bin/env python3
"""Regression tests for fail-closed worker execution lease propagation."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import claim_next_task
import execution_lease_manager


class WorkerExecutionLeasePropagationTest(unittest.TestCase):
    def test_active_lease_is_resolved_and_bound_to_task_and_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            runtime_root = Path(temp_dir)
            acquired = execution_lease_manager.acquire(
                runtime_root,
                execution_lease_manager.default_policy(),
                "inplacex",
                "auto-worker-5.5",
                "gpt-5.6-terra",
                ttl_seconds=600,
            )
            self.assertTrue(acquired["acquired"])
            lease = acquired["lease"]

            resolved = claim_next_task.resolve_execution_lease(
                runtime_root,
                lease["lease_id"],
                "inplacex",
                "auto-worker-5.5",
                "gpt-5.6-terra",
            )
            task: dict[str, object] = {}
            lock: dict[str, object] = {}
            claim_next_task.bind_execution_lease(task, resolved)
            claim_next_task.bind_execution_lease(lock, resolved)

            for target in (task, lock):
                self.assertEqual(target["lease_id"], lease["lease_id"])
                self.assertEqual(target["execution_lease_id"], lease["lease_id"])
                self.assertEqual(target["lease_expires_at"], lease["expires_at"])
                public_lease = target["lease"]
                self.assertIsInstance(public_lease, dict)
                self.assertEqual(public_lease["worker_id"], "auto-worker-5.5")
                self.assertNotIn("pid", public_lease)

    def test_mismatched_or_missing_lease_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            runtime_root = Path(temp_dir)
            acquired = execution_lease_manager.acquire(
                runtime_root,
                execution_lease_manager.default_policy(),
                "inplacex",
                "auto-worker-5.5",
                "gpt-5.6-terra",
                ttl_seconds=600,
            )
            lease_id = acquired["lease"]["lease_id"]

            with self.assertRaisesRegex(ValueError, "worker_id"):
                claim_next_task.resolve_execution_lease(
                    runtime_root,
                    lease_id,
                    "inplacex",
                    "auto-worker-5.5max",
                    "gpt-5.6-terra",
                )
            with self.assertRaisesRegex(ValueError, "missing or expired"):
                claim_next_task.resolve_execution_lease(
                    runtime_root,
                    "lease-does-not-exist",
                    "inplacex",
                    "auto-worker-5.5",
                    "gpt-5.6-terra",
                )


if __name__ == "__main__":
    unittest.main()
