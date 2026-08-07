#!/usr/bin/env python3
"""Verify the exact Mirkori Platform checkout and release-validator contract."""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

BUILDER_PATH = Path(__file__).with_name("build_platform_catalog_release.py")
SPEC = importlib.util.spec_from_file_location("inplacex_release_builder", BUILDER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load the release builder contract")
release_builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release_builder
SPEC.loader.exec_module(release_builder)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform-repo-dir", type=Path, required=True)
    parser.add_argument("--expected-platform-commit", required=True)
    parser.add_argument("--expected-platform-validator-sha256", required=True)
    args = parser.parse_args()
    identity = release_builder.validate_platform_checkout(
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
    except (OSError, release_builder.ReleaseBuildError) as error:
        print(f"Platform release contract verification failed: {error}", file=sys.stderr)
        raise SystemExit(65) from error
