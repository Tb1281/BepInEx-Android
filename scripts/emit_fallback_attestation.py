#!/usr/bin/env python3
"""
Emit fallback CI provenance statement for private repositories.
When actions/attest-build-provenance is unavailable (user-owned private repos),
this script generates a deterministic provenance bundle recording GitHub Actions
runtime metadata and SHA-256 digests of the produced artifacts.
"""
import argparse
import glob
import hashlib
import json
import os
import sys
from datetime import datetime, timezone


def parse_args():
    parser = argparse.ArgumentParser(description="Emit fallback CI provenance bundle")
    parser.add_argument("--paths", required=True, help="Paths or globs of artifacts")
    parser.add_argument("--output", required=True, help="Destination bundle JSON path")
    parser.add_argument("--repository", required=True, help="GitHub repository (owner/repo)")
    parser.add_argument("--commit", required=True, help="Git commit SHA")
    parser.add_argument("--run-id", required=True, help="GitHub Actions run ID")
    parser.add_argument("--workflow", default="unknown", help="Workflow name")
    parser.add_argument("--actor", default="unknown", help="Triggering actor")
    return parser.parse_args()


def calculate_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def main():
    args = parse_args()
    subjects = []
    seen = set()

    for pattern in args.paths.strip().splitlines():
        pattern = pattern.strip()
        if not pattern:
            continue
        # Support glob expansion
        matched = glob.glob(pattern, recursive=True)
        if not matched and os.path.isfile(pattern):
            matched = [pattern]
        for f in sorted(matched):
            if os.path.isfile(f) and f not in seen:
                seen.add(f)
                digest = calculate_sha256(f)
                subjects.append({
                    "name": os.path.basename(f),
                    "path": f,
                    "sha256": digest,
                })

    if not subjects:
        sys.stderr.write(f"emit_fallback_attestation: warning: no files matched in {args.paths}\n")

    bundle = {
        "version": "1.0",
        "type": "fallback-ci-provenance",
        "repository": args.repository,
        "commit": args.commit,
        "run_id": args.run_id,
        "workflow": args.workflow,
        "actor": args.actor,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "subjects": subjects,
    }

    out_dir = os.path.dirname(os.path.abspath(args.output))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    with open(args.output, "w", encoding="utf-8") as fp:
        json.dump(bundle, fp, indent=2)

    print(f"fallback provenance bundle -> {args.output} ({len(subjects)} subjects)")


if __name__ == "__main__":
    main()
