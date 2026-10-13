#!/usr/bin/env python3
"""
Write a deterministic zip archive from a stage directory.

Why this exists:

  Both shippable archives (`BepInEx.Android.zip` via
  scripts/package_bepinex_android.sh, `dotnet.zip` via
  scripts/fetch_dotnet_runtime.sh) were built with `zip -qr`, which records
  each file's mtime, walks the tree in filesystem order, stores the invoking
  uid/gid, and writes explicit directory entries. Two runs over identical
  inputs therefore produced different bytes — measured 2026-10-12 as a 47 vs
  49 entry divergence between a fresh and a committed `BepInEx.Android.zip`
  (explicit `core/` and `config/` directory entries present in one, absent in
  the other; no file lost either way).

  That non-determinism is not the managed-assembly reproducibility gap in
  provenance/SOURCES.md (embedded commit SHAs — intentionally kept for
  reviewable per-patch history). Archive-level bytes *are* fixable without any
  trade-off, so they are fixed here: sorted file-only entries, fixed mtimes,
  fixed permissions, no owner metadata.

What is fixed:

  - entry order: sorted by archive name.
  - entry set: files only, no explicit directory entries (extraction is
    identical — ZipInputStream/Expand-Archive create parents as needed).
  - mtime: SOURCE_DATE_EPOCH when set (reproducible-builds.org), else the
    newest mtime found under the inputs, so a build without the variable is
    still stable against checkout-time skew rather than wall-clock.
  - permissions: 0o644 for files; no uid/gid/extra fields.
  - compression: ZIP_DEFLATED throughout.

Fail-closed: a missing input path exits 1 rather than writing a partial
archive. An archive that silently drops a directory is the same class of
failure as the stale-assembly packaging this repository already refuses.

Usage:
  scripts/zip_deterministic.py <root> <output.zip> <relpath> [<relpath> ...]
    <root>      stage directory the relpaths are resolved against.
    <output>    destination archive (overwritten).
    <relpath>   file or directory under <root> to include, recursively.
"""
import datetime
import os
import sys
import zipfile

FIXED_FILE_MODE = 0o644 << 16


def epoch():
    raw = os.environ.get("SOURCE_DATE_EPOCH", "").strip()
    if raw:
        try:
            return int(raw)
        except ValueError:
            sys.stderr.write(
                f"zip_deterministic: ignoring malformed SOURCE_DATE_EPOCH={raw!r}\n"
            )
    return None


def collect(root, relpaths):
    files = []
    for rel in relpaths:
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            sys.stderr.write(f"zip_deterministic: missing input {path}\n")
            sys.exit(1)
        if os.path.isfile(path):
            files.append(rel)
        else:
            for directory, _subdirs, names in os.walk(path):
                for name in names:
                    full = os.path.join(directory, name)
                    files.append(os.path.relpath(full, root))
    # De-duplicate (overlapping relpaths) and sort for a stable entry order.
    return sorted(set(files))


def main():
    if len(sys.argv) < 4:
        sys.stderr.write(
            "usage: zip_deterministic.py <root> <output.zip> <relpath> [...]\n"
        )
        sys.exit(2)
    root, output = sys.argv[1], sys.argv[2]
    relpaths = sys.argv[3:]

    if not os.path.isdir(root):
        sys.stderr.write(f"zip_deterministic: missing root {root}\n")
        sys.exit(1)

    names = collect(root, relpaths)
    if not names:
        sys.stderr.write("zip_deterministic: nothing to archive\n")
        sys.exit(1)

    fixed = epoch()
    if fixed is None:
        # Newest input mtime: stable for a given tree, unlike time.now().
        latest = 0
        for name in names:
            try:
                mtime = int(os.stat(os.path.join(root, name)).st_mtime)
            except OSError:
                sys.stderr.write(f"zip_deterministic: cannot stat {name}\n")
                sys.exit(1)
            latest = max(latest, mtime)
        fixed = latest
    date_time = datetime.datetime.fromtimestamp(
        fixed, tz=datetime.timezone.utc
    ).timetuple()[:6]

    out_dir = os.path.dirname(os.path.abspath(output))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            info = zipfile.ZipInfo(filename=name, date_time=date_time)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = FIXED_FILE_MODE
            with open(os.path.join(root, name), "rb") as fp:
                archive.writestr(info, fp.read())

    print(f"zip -> {output} ({len(names)} entries, mtime={fixed})")


if __name__ == "__main__":
    main()
