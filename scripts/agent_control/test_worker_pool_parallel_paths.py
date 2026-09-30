#!/usr/bin/env python3
"""Regression tests for worker-pool parallel path selection."""

from __future__ import annotations

import unittest

import worker_pool_manager


class ParallelConflictPathsTest(unittest.TestCase):
    def test_runner_owned_queue_does_not_serialize_product_tasks(self) -> None:
        paths = worker_pool_manager.parallel_conflict_paths(
            [
                "AiStudio/Task_manager/task_queue.json",
                "InplaceX-android/app/src/main/java/com/mirkori/inplacex/data/local/**",
            ]
        )

        self.assertEqual(
            paths,
            ["InplaceX-android/app/src/main/java/com/mirkori/inplacex/data/local/**"],
        )

    def test_product_paths_remain_conflict_inputs(self) -> None:
        paths = worker_pool_manager.parallel_conflict_paths(
            [
                "InplaceX-android/app/src/main/java/com/mirkori/inplacex/core/**",
                "InplaceX-android/app/src/test/java/com/mirkori/inplacex/core/**",
            ]
        )

        self.assertEqual(
            paths,
            [
                "InplaceX-android/app/src/main/java/com/mirkori/inplacex/core/**",
                "InplaceX-android/app/src/test/java/com/mirkori/inplacex/core/**",
            ],
        )


if __name__ == "__main__":
    unittest.main()
