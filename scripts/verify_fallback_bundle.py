#!/usr/bin/env python3
"""
Verify an installed artifact against a fallback CI provenance bundle.
Used when the repository is private and Sigstore attestations cannot be persisted.
Validates repository, commit SHA, and subject SHA-256 digest match the file on disk.
"""
import hashlib
import json
import os
import sys


def calculate_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def main():
    if len(sys.argv) < 5:
        sys.stderr.write("usage: verify_fallback_bundle.py <file> <expected_repo> <expected_commit> <bundle_file>\n")
        sys.exit(2)

    target_file = sys.argv[1]
    expected_repo = sys.argv[2]
    expected_commit = sys.argv[3]
    bundle_file = sys.argv[4]

    if not os.path.isfile(target_file):
        sys.stderr.write(f"target file {target_file} not found\n")
        sys.exit(1)

    if not os.path.isfile(bundle_file):
        sys.stderr.write(f"bundle file {bundle_file} not found\n")
        sys.exit(1)

    try:
        with open(bundle_file, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        sys.stderr.write(f"failed to parse JSON from {bundle_file}: {e}\n")
        sys.exit(1)

    if data.get("type") != "fallback-ci-provenance":
        sys.exit(1)

    if data.get("repository") != expected_repo:
        sys.stderr.write(f"repo mismatch: expected {expected_repo}, got {data.get('repository')}\n")
        sys.exit(1)

    if data.get("commit") != expected_commit:
        sys.stderr.write(f"commit mismatch: expected {expected_commit}, got {data.get('commit')}\n")
        sys.exit(1)

    target_name = os.path.basename(target_file)
    target_sha = calculate_sha256(target_file)

    for subject in data.get("subjects", []):
        sub_name = subject.get("name") or os.path.basename(subject.get("path", ""))
        if sub_name == target_name:
            if subject.get("sha256") == target_sha:
                sys.exit(0)
            else:
                sys.stderr.write(f"hash mismatch for {target_name}: expected {subject.get('sha256')}, got {target_sha}\n")
                sys.exit(1)

    sys.exit(1)


if __name__ == "__main__":
    main()
