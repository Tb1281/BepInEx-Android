# Diagnostics export is a curated report, not a mirror of the storage tree

Status: accepted (2026-10-23).

The crash-diagnostics export (`CrashDiagnostics.export`) used to collect
files by walking the whole per-game storage root and keeping every file
whose name matched a log-name list. A real export captured on device
(2026-10-23, The Tower) shows what that produces:

- 21 entries, of which 12 are `LogOutput.log` with only 4 distinct
  contents. One run's runtime log is snapshotted into every modpack that
  was active for it (`ModpackManager.persistRuntimeState` runs per
  previously active pack), so the walk re-collects one copy per pack —
  identical files that falsely look like per-pack logs — plus stale
  snapshots from runs days older and an unrelated leftover under
  `gamedata/`. The live runtime log (`BepInEx/LogOutput.log`) is not
  distinguishable from the copies, and every zip entry carries the
  export time, so the original run times are lost.
- Four of the seven root entries are permanently empty of information
  on an unrooted device: `exit_info.txt` and `package_info.txt` contain
  only a `Permission Denial` line (the app holds no `DUMP` permission),
  `tombstones.txt` is `Permission denied`, and `meminfo.txt` is a
  service error. `exit-info` also named the target game package, but
  the hosted game runs in the launcher's own `:game` process, so even
  with permission it would describe the wrong package.
- `device.txt` omits the facts a report is read for: no launcher or
  game version, no launch time, and no record of which modpacks the
  run actually used. The pending-launch record already stored the
  launch time (`PendingLaunch.startedAt`) but the export never read it.

Decision: the export is a curated report about **one** launch — the
pending launch that triggered the crash dialog — not a mirror of the
storage tree.

- The pending-launch record snapshots the modpack list at launch time
  (`markLaunch` gains the launched modpack names, in launch order, in
  addition to the package and start time). The export must not query
  the active/last-launched state at export time: a later launch can
  already have replaced it (the sample above was exported after a
  re-launch of the same game).
- Attribution is a list, not folders. A multi-pack run produces one
  combined runtime log; splitting it into per-pack folders invents a
  distinction that does not exist. `device.txt` therefore carries
  `activeModpacks` as one comma-separated line in launch order, with
  `(vanilla)` for an empty list and `(not recorded)` for a pending
  record written before this field existed.
- Game logs are collected from the runtime locations only, at fixed
  zip paths: `game/LogOutput.log` (the live runtime log) and
  `game/logs/…` (the runtime `BepInEx/logs/` directory). The storage
  walk, per-pack snapshots, the vanilla snapshot, and anything under
  `gamedata/` are no longer collected.
- File entries keep their original modification time in the zip
  (`ZipEntry.time`), so a log's run time survives the export.
- The four `dumpsys`/`ls` entries (`exit_info`, `package_info`,
  `meminfo`, `tombstones`) are removed rather than repaired: without
  `DUMP` permission they cannot contain anything, and requesting that
  permission for a diagnostics convenience is not justified.
- `device.txt` also gains the launcher version and the target game's
  version (both via `PackageManager`, `unknown` when unavailable),
  the recorded launch time (`launchedAt`), and the export time.

Consequences: an export now contains at most one `LogOutput.log`, and
its entries answer "what ran, when, with which packs" without opening
a log. Per-pack snapshot folders remain an internal storage concern of
`ModpackManager`; they are simply no longer exported. The one loss is
history: older runs' snapshot logs are no longer attached, which is
accepted — they were unattributable duplicates in practice, and the
crash dialog concerns the most recent launch.
