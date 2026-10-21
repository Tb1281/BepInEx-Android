#!/usr/bin/env python3
"""
Generate patches/manifest.json from the patch queue.

The manifest is the review-facing interface of the queue: one entry per patch
with its subject, purpose, touched files, dependencies, ADR references and
whether it carries compilable source. It is generated, not authored: the
patches are the only source of truth, and `scripts/test_patch_manifest.sh`
fails when this output disagrees with the committed manifest.

Metadata that is not derivable from the diff (depends-on, ADR) is read from
commit-message trailers in the patch:

    Depends-On: 0001-..., 0002-...
    ADR: 0019

compile-unit is derived from the touched file extensions, so no trailer is
needed for it.

Usage:
  scripts/generate_patch_manifest.py [--output patches/manifest.json]
      [--patches-dir patches]
"""
import argparse
import glob
import json
import os
import re

COMPILE_EXTENSIONS = {
    ".cs", ".cpp", ".cc", ".c", ".h", ".hpp", ".hh",
    ".csproj", ".props", ".targets", ".sln",
}

TRAILER_RE = re.compile(r"^(Depends-On|ADR):\s*(.+)$", re.IGNORECASE)


def parse_patch(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    lines = text.splitlines()

    subject = ""
    subject_lines = []
    in_subject = False
    for line in lines:
        if line.startswith("Subject:"):
            in_subject = True
            subject_lines.append(line[len("Subject:"):].strip())
            continue
        if in_subject:
            if line.strip() == "" or line.strip() == "---" or line.startswith("diff --git "):
                break
            if line.startswith(" ") or line.startswith("\t"):
                subject_lines.append(line.strip())
                continue
            break
    subject = " ".join(subject_lines).strip()
    subject = re.sub(r"^\[PATCH(?: \d+/\d+)?\]\s*", "", subject)

    # Body: lines after the subject block until the "---" separator or diff.
    body_start = None
    for i, line in enumerate(lines):
        if line.startswith("Subject:"):
            body_start = i
            # skip subject lines
            j = i + 1
            while j < len(lines) and (lines[j].startswith(" ") or lines[j].startswith("\t") or lines[j].startswith("Subject:")):
                j += 1
            body_start = j
            break
    body_lines = []
    if body_start is not None:
        for line in lines[body_start:]:
            if line.strip() == "---" or line.startswith("diff --git "):
                break
            body_lines.append(line)
    # First paragraph = description.
    desc_lines = []
    for line in body_lines:
        if line.strip() == "":
            break
        desc_lines.append(line.strip())
    description = " ".join(desc_lines)

    # Trailers may appear anywhere in the body.
    depends_on = []
    adr = []
    for line in body_lines:
        m = TRAILER_RE.match(line.strip())
        if not m:
            continue
        key = m.group(1).lower()
        values = [v.strip() for v in re.split(r"[,\s]+", m.group(2)) if v.strip()]
        if key == "depends-on":
            depends_on.extend(values)
        elif key == "adr":
            adr.extend(values)

    files = []
    seen = set()
    for line in lines:
        m = re.match(r"^diff --git a/(.+?) b/(.+)$", line)
        if m:
            p = m.group(2)
            if p == "/dev/null":
                p = m.group(1)
            if p not in seen:
                seen.add(p)
                files.append(p)
    files.sort()

    compile_unit = any(os.path.splitext(f)[1].lower() in COMPILE_EXTENSIONS for f in files)

    stem = os.path.splitext(os.path.basename(path))[0]
    number_str = stem.split("-", 1)[0]
    try:
        number = int(number_str)
    except ValueError:
        number = 0

    return {
        "id": stem,
        "number": number,
        "subject": subject,
        "purpose": subject,
        "description": description,
        "files": files,
        "depends_on": depends_on,
        "adr": adr,
        "compile_unit": compile_unit,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--patches-dir", default="patches")
    parser.add_argument("--output", default="patches/manifest.json")
    args = parser.parse_args()

    paths = sorted(glob.glob(os.path.join(args.patches_dir, "*.patch")))
    entries = [parse_patch(p) for p in paths]
    entries.sort(key=lambda e: (e["number"], e["id"]))

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(entries, f, indent=2, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()