# Generate the human-readable Pin Table from the Source pins

Status: accepted (2026-10-21)

The BepInEx pin is authored in `provenance/bepinex.env`, but `sync-upstream`
only rewrote that file. The matching row in `provenance/SOURCES.md` and the
commit-subject comment in `bepinex.env` stayed at the old pin, so the next
automated sync could publish a table that said one commit while the build used
another. The patch-count comment had the same shape: it claimed not to be a
gate while `test_patch_manifest.sh` asserted it.

Decision: `provenance/*.env` is the only authored pin source. The Pin Table in
`provenance/SOURCES.md` is a generated projection: row text and static rows live
in `provenance/pin-table.template.md`, env values fill the placeholders, and
`scripts/render_sources_pin_table.py` replaces the marked block. `--check` runs
in `verify-provenance` and fails closed when the committed block drifts. The
`sync-upstream` workflow regenerates the block after a pin bump and includes
`provenance/SOURCES.md` in the pin-bump PR. Pin-specific prose and
`BEPINEX_PATCH_COUNT` are removed; the queue glob and generated manifest are the
completeness authorities.

Consequences:

- Moving a pin means editing its env file; the sync workflow regenerates the
  human-readable table instead of requiring a second edit.
- Hand editing the generated block fails CI; prose outside the block remains
  ordinary Markdown.
- Static rows (the launcher's current commit and the NDK pin) stay in the
  template because they have no env pin.
- The Pin Table and the Source pins cannot disagree silently: a stale projection
  fails the provenance job before merge.
- The generated block is still tracked so a reviewer can read the exact pins in
  the diff; it is a projection, not a second source.
