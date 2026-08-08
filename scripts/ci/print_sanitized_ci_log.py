#!/usr/bin/env python3
"""Print a bounded, credential-redacted CI log excerpt."""

from __future__ import annotations

import pathlib
import sys


HEAD_LINE_LIMIT = 100
TAIL_LINE_LIMIT = 100
REDACTION_MARKER = "[redacted-test-credential]"


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit("Usage: print_sanitized_ci_log.py <log-path> [secret ...]")

    text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
    secrets = sorted({value for value in sys.argv[2:] if value}, key=len, reverse=True)
    for secret in secrets:
        text = text.replace(secret, REDACTION_MARKER)

    lines = text.splitlines()
    if len(lines) <= HEAD_LINE_LIMIT + TAIL_LINE_LIMIT:
        excerpt = lines
    else:
        omitted = len(lines) - HEAD_LINE_LIMIT - TAIL_LINE_LIMIT
        excerpt = [
            *lines[:HEAD_LINE_LIMIT],
            f"... {omitted} additional lines omitted ...",
            *lines[-TAIL_LINE_LIMIT:],
        ]

    if excerpt:
        sys.stdout.write("\n".join(excerpt) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
