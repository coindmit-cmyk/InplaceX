#!/usr/bin/env python3
from __future__ import annotations

import unittest

from claim_next_task import needs_fresh_retry_branch
from integrator_direct_merge import detect_behavior_regression
from pr_readiness_classifier import scope_issues


class PathScopeRegressionTests(unittest.TestCase):
    def test_git_forbidden_scope_does_not_match_github_workflow(self) -> None:
        task = {
            "allowed_paths": [".github/workflows/**"],
            "forbidden_paths": [".git/**"],
        }

        self.assertEqual(
            [],
            scope_issues([".github/workflows/ci.yml"], [task]),
        )

    def test_git_forbidden_scope_still_matches_git_directory(self) -> None:
        task = {
            "allowed_paths": [".github/workflows/**"],
            "forbidden_paths": [".git/**"],
        }

        self.assertEqual(
            ["forbidden path: .git/config"],
            scope_issues([".git/config"], [task]),
        )


class BehaviorMoveRegressionTests(unittest.TestCase):
    def test_symbol_moved_to_another_changed_file_is_preserved(self) -> None:
        before = {
            "GameProgressRepository.kt": [
                "GameProgressDbHelper",
                "onCreate",
                "onUpgrade",
            ],
        }
        after = {
            "GameProgressRepository.kt": [],
            "GameProgressDatabase.kt": [
                "GameProgressDbHelper",
                "onCreate",
                "onUpgrade",
            ],
        }

        self.assertEqual(
            {
                "ok": True,
                "lost_behavior": {},
                "lost_count": 0,
            },
            detect_behavior_regression(before, after),
        )

    def test_symbol_missing_from_all_changed_files_is_reported(self) -> None:
        before = {"GameProgressRepository.kt": ["onCreate", "onUpgrade"]}
        after = {"GameProgressRepository.kt": ["onCreate"]}

        self.assertEqual(
            {
                "ok": False,
                "lost_behavior": {
                    "GameProgressRepository.kt": ["onUpgrade"],
                },
                "lost_count": 1,
            },
            detect_behavior_regression(before, after),
        )


class RetryBranchRegressionTests(unittest.TestCase):
    def test_design_handoff_integration_retry_gets_fresh_branch(self) -> None:
        task = {
            "branch": "AiStudio/Agent/worker/machine/worker/task/original",
            "status_history": [
                {
                    "event": "design_handoff_integration_retry",
                    "next_owner": "worker_pool",
                },
            ],
        }

        self.assertTrue(needs_fresh_retry_branch(task))


if __name__ == "__main__":
    unittest.main()
