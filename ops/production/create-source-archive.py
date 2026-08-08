#!/usr/bin/python3
"""Create a deterministic tar directly from one Git commit's blob objects."""

from __future__ import annotations

import io
import os
import pathlib
import re
import subprocess
import sys
import tarfile
from typing import NoReturn


def fail(message: str) -> NoReturn:
    raise SystemExit(message)


if len(sys.argv) != 5:
    fail("Usage: create-source-archive.py <repository> <commit> <output-tar> <empty-git-home>")

repository = pathlib.Path(sys.argv[1]).resolve(strict=True)
commit = sys.argv[2]
output = pathlib.Path(sys.argv[3])
git_home = pathlib.Path(sys.argv[4]).resolve(strict=True)
if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
    fail("Source archive commit must be one exact SHA-1 commit")
if not git_home.is_dir() or any(git_home.iterdir()):
    fail("Source archive Git HOME must be an existing empty directory")
if output.exists() or not output.parent.is_dir():
    fail("Source archive output must be a new file in an existing directory")

git_environment = {
    "GIT_CONFIG_GLOBAL": "/dev/null",
    "GIT_CONFIG_NOSYSTEM": "1",
    "HOME": str(git_home),
    "LANG": "C",
    "LC_ALL": "C",
    "PATH": "/usr/sbin:/usr/bin:/sbin:/bin",
}
git_command = [
    "/usr/bin/git",
    "--no-replace-objects",
    "-c",
    "core.fsmonitor=false",
    "-c",
    "core.hooksPath=/dev/null",
    "-c",
    "core.pager=cat",
    "-C",
    str(repository),
]


def git_output(*arguments: str) -> bytes:
    return subprocess.check_output(
        [*git_command, *arguments],
        env=git_environment,
        stderr=subprocess.PIPE,
    )


top_level = pathlib.Path(git_output("rev-parse", "--show-toplevel").decode().strip()).resolve()
if top_level != repository:
    fail("Source archive resolved a different repository/worktree")
resolved_commit = git_output("rev-parse", "--verify", f"{commit}^{{commit}}").decode().strip()
if resolved_commit != commit:
    fail("Source archive commit identity changed")

git_directory_value = git_output("rev-parse", "--absolute-git-dir").decode().strip()
git_directory = pathlib.Path(git_directory_value)
if not git_directory.is_absolute():
    git_directory = repository / git_directory
git_directory = git_directory.resolve(strict=True)
common_directory_value = git_output("rev-parse", "--git-common-dir").decode().strip()
common_directory = pathlib.Path(common_directory_value)
if not common_directory.is_absolute():
    common_directory = repository / common_directory
common_directory = common_directory.resolve(strict=True)
attributes_path_value = git_output("rev-parse", "--git-path", "info/attributes").decode().strip()
attributes_path = pathlib.Path(attributes_path_value)
if not attributes_path.is_absolute():
    attributes_path = repository / attributes_path
for forbidden_path in {
    attributes_path.resolve(strict=False),
    (git_directory / "info" / "attributes").resolve(strict=False),
    (common_directory / "info" / "attributes").resolve(strict=False),
    common_directory / "objects" / "info" / "alternates",
    common_directory / "objects" / "info" / "http-alternates",
}:
    if forbidden_path.exists():
        fail(f"Source archive forbids repository-local attributes/alternates: {forbidden_path}")

commit_payload = git_output("cat-file", "commit", commit)
committer_lines = [line for line in commit_payload.splitlines() if line.startswith(b"committer ")]
if len(committer_lines) != 1:
    fail("Source commit has no exact committer timestamp")
timestamp_match = re.search(rb" ([0-9]+) [+-][0-9]{4}$", committer_lines[0])
if timestamp_match is None:
    fail("Source commit timestamp is invalid")
commit_timestamp = int(timestamp_match.group(1))

tree_payload = git_output("ls-tree", "-rz", "--full-tree", "-r", commit)
entries: list[tuple[str, str, str]] = []
for raw_entry in tree_payload.split(b"\0"):
    if not raw_entry:
        continue
    metadata, separator, raw_path = raw_entry.partition(b"\t")
    if not separator:
        fail("Git tree entry has no path separator")
    fields = metadata.decode("ascii").split(" ")
    if len(fields) != 3:
        fail("Git tree entry metadata is invalid")
    mode, object_type, object_id = fields
    if object_type != "blob" or mode not in {"100644", "100755", "120000"}:
        fail(f"Unsupported source tree entry: {mode} {object_type}")
    path = raw_path.decode("utf-8")
    path_parts = pathlib.PurePosixPath(path).parts
    if not path_parts or path.startswith("/") or any(part in {"", ".", ".."} for part in path_parts):
        fail("Git tree contains a non-canonical archive path")
    entries.append((mode, object_id, path))
if not entries:
    fail("Source commit tree is empty")

batch = subprocess.Popen(
    [*git_command, "cat-file", "--batch"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    env=git_environment,
)
assert batch.stdin is not None
assert batch.stdout is not None

try:
    with tarfile.open(output, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for mode, object_id, path in entries:
            batch.stdin.write(object_id.encode("ascii") + b"\n")
            batch.stdin.flush()
            header = batch.stdout.readline().decode("ascii").strip().split(" ")
            if len(header) != 3 or header[0] != object_id or header[1] != "blob":
                fail(f"Git blob batch identity mismatch for {path}")
            size = int(header[2])
            payload = batch.stdout.read(size)
            if len(payload) != size or batch.stdout.read(1) != b"\n":
                fail(f"Git blob batch payload is truncated for {path}")

            member = tarfile.TarInfo(path)
            member.uid = 0
            member.gid = 0
            member.uname = "root"
            member.gname = "root"
            member.mtime = commit_timestamp
            if mode == "120000":
                member.type = tarfile.SYMTYPE
                member.mode = 0o777
                member.linkname = payload.decode("utf-8")
                member.size = 0
                archive.addfile(member)
            else:
                member.type = tarfile.REGTYPE
                member.mode = 0o755 if mode == "100755" else 0o644
                member.size = size
                archive.addfile(member, io.BytesIO(payload))
finally:
    batch.stdin.close()
    stderr = batch.stderr.read() if batch.stderr is not None else b""
    status = batch.wait()
    if status != 0:
        fail(f"Git blob batch failed: {stderr.decode(errors='replace').strip()}")

with output.open("rb") as archive_file:
    os.fsync(archive_file.fileno())
