#!/usr/bin/env bash
# Check 16KB ELF LOAD-segment alignment (required from Android 15 / API 35).
#
# Usage: scripts/check_elf_alignment.sh <file-or-dir>...
#
# WHY THIS IS A SCRIPT AND NOT INLINE YAML
#
# The 16KB check was hand-written into three places: fetch-runtime.yml,
# build-native-deps.yml, and launcher/scripts/verify_prebuilts.sh. They did not
# agree, and one of them had already shipped broken. fetch-runtime's first
# version used `[ ... ] && echo` inside a command substitution, which under the
# runner's `bash -e` returns non-zero on every *correctly* aligned library - so
# the check failed the libraries it was written to pass, printed nothing, and was
# indistinguishable in the log from the misaligned case it was written to catch.
# It survived because fetch-runtime runs weekly. See scripts/test_alignment_check.sh
# for the pinned regression cases; that fixture kept testing two copies of the
# logic that lived in YAML, which is why the third copy went unnoticed.
#
# One implementation, exercised by one fixture.
#
# The loop body reads through a process substitution so the accumulator lives in
# this shell rather than in a subshell whose value would be lost. The pipe form
# (`... | while read`) runs the body in a subshell: an assignment there is
# discarded, so the verdict depends entirely on whether the pipeline happens to
# return non-zero. That is the same construct verify_prebuilts.sh uses over the
# staged binaries; this is now the third caller of one shared function.
set -euo pipefail

[ "$#" -gt 0 ] || {
    echo "usage: $0 <file-or-dir>..." >&2
    exit 2
}

FAIL=0
checked=0

check_one() {
    local so="$1" label="${1##*/}" bad="" segs=0
    while read -r align; do
        [ -n "$align" ] || continue
        segs=$((segs + 1))
        if [ "$((align))" -lt 16384 ]; then
            bad="${bad}${align} "
        fi
    done < <(readelf -lW "$so" 2>/dev/null | awk '$1 == "LOAD" { print $NF }')

    # No LOAD segment at all is not a pass. An unreadable or non-ELF file, or a
    # truncated one, yields nothing here, and "nothing was unaligned" would be
    # reported as aligned - a fail-open on exactly the input a check cannot
    # evaluate. Anything that cannot be inspected has to be visible.
    if [ "$segs" -eq 0 ]; then
        echo "  [UNKNOWN]   ${label} - no LOAD segment reported; alignment cannot be determined"
        FAIL=1
        return 0
    fi

    if [ -n "$bad" ]; then
        echo "  [UNALIGNED] ${label} PT_LOAD p_align: ${bad}"
        FAIL=1
    else
        echo "  [OK]        ${label}"
    fi
}

for target in "$@"; do
    if [ -f "$target" ]; then
        check_one "$target"
        checked=$((checked + 1))
    elif [ -d "$target" ]; then
        # shellcheck disable=SC2012
        for so in "$target"/*.so; do
            [ -f "$so" ] || continue
            check_one "$so"
            checked=$((checked + 1))
        done
    else
        echo "  [MISSING]   ${target}" >&2
        FAIL=1
    fi
done

if [ "$checked" -eq 0 ]; then
    # No input is not a pass. An empty directory or a glob that matched nothing
    # must be visible, or a caller that renamed its output directory would get a
    # green step that checked nothing.
    echo "  [FATAL]     no shared objects found in: $*" >&2
    exit 1
fi

if [ "$FAIL" -ne 0 ]; then
    echo "  RESULT: FAILED - ${checked} checked, at least one needs 16KB alignment"
    exit 1
fi
echo "  RESULT: OK - ${checked} shared objects are 16KB aligned"