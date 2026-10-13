#!/usr/bin/env python3
"""
Refuse an APK whose zip local headers and central directory disagree.

Why this exists:

  After swapping staged assets, an incremental (non-clean) Gradle build
  packaged the APK with 958 local entries against a 475-entry central
  directory (+31 MB) — and once more with 478 vs 475 (+3 files). Android
  installs from the central directory, so both APKs worked; both also
  carried dead weight to the device, and a wider divergence (stale files
  shadowing fresh ones by name) would fail in ways nothing else here checks.
  verify_prebuilts.sh guards the inputs; this guards the packaged output.

  It is a script with a fixture rather than inline YAML because the failure
  this repository keeps removing is a check that quietly passes when its
  input changes shape — and only a fixture can prove the check still decides.

Usage: scripts/verify_apk_entries.sh <apk>
  Exit 0 when every local entry has exactly one central record and vice
  versa; exit 1 naming the discrepancy otherwise.
"""
import struct
import sys
import zipfile


def local_names(path):
    """Names in local-header order, including duplicates and orphans."""
    names = []
    with open(path, "rb") as fp:
        data = fp.read()
    pos = 0
    while pos + 30 <= len(data):
        if struct.unpack("<I", data[pos:pos + 4])[0] != 0x04034B50:
            break
        fn_len, ef_len = struct.unpack("<HH", data[pos + 26:pos + 30])
        comp_size, = struct.unpack("<I", data[pos + 18:pos + 22])
        name = data[pos + 30:pos + 30 + fn_len].decode("utf-8", "replace")
        names.append(name)
        pos = pos + 30 + fn_len + ef_len + comp_size
    return names


def main():
    if len(sys.argv) != 2:
        sys.stderr.write("usage: verify_apk_entries.sh <apk>\n")
        sys.exit(2)
    path = sys.argv[1]

    try:
        local = local_names(path)
        with zipfile.ZipFile(path) as zf:
            central = zf.namelist()
    except Exception as e:
        sys.stderr.write(f"verify_apk_entries: cannot read {path}: {e}\n")
        sys.exit(1)

    fails = 0
    if len(local) != len(central):
        sys.stderr.write(
            f"verify_apk_entries: {path}: {len(local)} local entries "
            f"vs {len(central)} central records\n"
        )
        fails += 1

    from collections import Counter
    dupes = sorted(n for n, c in Counter(local).items() if c > 1)
    if dupes:
        sys.stderr.write(
            f"verify_apk_entries: {path}: {len(dupes)} duplicated local "
            f"entries (e.g. {dupes[0]})\n"
        )
        fails += 1

    if set(local) != set(central):
        only_local = sorted(set(local) - set(central))[:5]
        only_central = sorted(set(central) - set(local))[:5]
        sys.stderr.write(f"verify_apk_entries: {path}: name sets differ\n")
        if only_local:
            sys.stderr.write(f"  local-only: {only_local}\n")
        if only_central:
            sys.stderr.write(f"  central-only: {only_central}\n")
        fails += 1

    if fails:
        sys.exit(1)
    print(f"apk entries OK: {len(central)} local == {len(central)} central, no duplicates")


if __name__ == "__main__":
    main()
