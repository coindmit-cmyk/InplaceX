#!/usr/bin/env python3
"""Verify the exact Mirkori Platform checkout and release-validator contract."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from build_platform_catalog_release import ReleaseBuildError, validate_platform_checkout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform-repo-dir", type=Path, required=True)
    parser.add_argument("--expected-platform-commit", required=True)
    parser.add_argument("--expected-platform-validator-sha256", required=True)
    args = parser.parse_args()
    identity = validate_platform_checkout(
        args.platform_repo_dir,
        args.expected_platform_commit,
        args.expected_platform_validator_sha256.lower(),
    )
    print(
        f"platform_release_contract=verified commit={identity.commit} "
        f"validator_sha256={identity.tool_sha256}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ReleaseBuildError) as error:
        print(f"Platform release contract verification failed: {error}", file=sys.stderr)
        raise SystemExit(65) from error
