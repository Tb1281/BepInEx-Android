# Handoff: CIFI PairIP stock-libmain control (2026-10-28 08:32 KST)

Point-in-time snapshot for continuing the CIFI
(`com.OctocubeGamesCompany.CIFI`) hosted-launch investigation on another
machine. The crash is still unresolved. This is a delta snapshot; the living
record is [`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md) and the
append-only run log is
[`docs/cifi-pairip-walkthrough.md`](../cifi-pairip-walkthrough.md). Read the
binding rules at
[`docs/cifi-pairip-agent-rules.md`](../cifi-pairip-agent-rules.md) first.
Earlier snapshots are read-only:
[`2026-10-28-cifi-pairip-stack-len.md`](2026-10-28-cifi-pairip-stack-len.md)
and the older entries in this directory.

## State to pull

- Branch: `test/feat-cifi`, tracked as `origin/test/feat-cifi`.
- Pre-handoff tip: `6d0b01c` (docs-only: static closure plus the
  stock-libmain pre-registration). The verdict, the doc updates, and this
  handoff are committed on top; use `git log -1` after pulling.
- This push includes all local commits that were previously unpushed. The
  branch contains the debug-only investigation instrumentation and is not
  merged to `main`.
- Working tree: clean after the handoff commit.
- Last device run: `variant=stock-libmain-reader-slots`, code commit
  `9be04e4`, APK sha256
  `5be5c199376dc5f4d0849a6bd88593429e78d1a2a00a86734b76acf18a124428`,
  log sha256
  `60a30e02e291e0a420993664a7a6354dc1065ea26f4b10c885be0bcb1c31b333`,
  one cold launch, still SIGSEGV.
- Device: SM-S931N (`R3CY10S8TPH`), Android 17/API 37, `user`,
  `ro.debuggable=0`, no root. The game APK was not modified
  (`versionCode=3022`, `versionName=0.7.3.64`,
  `lastUpdateTime=2026-10-22 16:35:02`).

## What changed this round

- The `vm[0x740]` tagged-offset lead is closed statically. The reader builder
  writes the table itself: `+0x3e000`/`+0x3e008`/`+0x3e010` store the 64-bit
  context at `sp+0x750`/`0x760`/`0x770`, and `+0x3e004`/`+0x3e00c`/`+0x3e028`
  store only 32-bit `w8`/`w9`/`w10` at `sp+0x758`/`0x768`/`0x778`. The high
  words are uninitialized padding, not tags. `+0x3e034` loads `[image+w8]`
  and `[image+w9]` from registers, not from the table, so the table is not a
  selector. `+0x702e4` (`tst w0,#0xff; b.ne 0x70324`) has no alternative
  successor: both the hosted (both args null, return 1) and a hypothetical
  non-null path rejoin at `+0x70324`. There is no upstream writer to find.
- `stock-libmain-reader-slots` (2026-10-28 08:27) loaded the game's own
  `libmain` from the arm64 split APK with no Fusion (`pairip_stock_libmain=true`)
  and ran one cold launch. The reader still saw `w8=0x3f874`,
  `w9=0x1ba5c`, the pristine words `bbb9eb6daed20ed0`/`015370f87525d57b`,
  and the identical `__memmove_aarch64_nt+140` crash. Hypothesis
  **CONFIRMED**; the launcher-libmain/Fusion/DT_INIT chain is ruled out.
  This also gives the 2026-10-27 line-166 `stock-libmain` claim a surviving
  log.
- The crash image is `klB-image-at-crash.bin`, 282,603 bytes, sha256
  `50648e90916e9fd48d3ea89332c798422d5bd2854a4fa395b681f6fb58aa9919`.
  It is byte-identical to the pristine asset at `0x3f874`, `0x1ba5c`,
  `0x19b5e`, `0x40a73`, `0x1f811`, and `0x24f47`; 368 bytes across 79 ranges
  differ (the known variable set). Vs the `stack-len-plus-one` image it
  differs in 76 bytes across 34 ranges, with one new-only byte at `0x14a9e`.
- Pre-registration defect recorded: `PairIP preload: stock libmain completed`
  is not printed because the crash happens inside `NativeLoader.load(...)`
  before the call returns. The branch is proven by the `stock libmain=...`
  line, the reader-source hook line, and the klB trace. This is not a failed
  control.

## Still true / not proven

- The hosted run still dies in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk +0x514`; `vm[0x540]` reader slots remain
  `bbb9eb6daed20ed0` / `015370f87525d57b`; final state
  `id=0x872a21c sel=0x31bb`; builtin tail `0x9a0c0031 pc=0x40a73`.
- The reader slots are pristine at the crash, no dumped image-write ring
  entry touches them, and the tagged-offset table is not a writer or
  selector. The missing initialization is still unknown.
- Ruled out by prior runs and not to be repeated: launcher libmain/DT_INIT
  (this round), Application lifecycle and license consumption, maps
  content/CD-line position/APK zero map, process name/identity, stack text
  and frame length, and the reader-source buffer.
- The reader is a ZIP/CD view, not a plain image string: the fake-CD runs
  advance past the memmove crash (to a CheckJNI `FindClass` SIGABRT). The
  current evidence only proves the magic/length interpretation, not whether
  the reader expects a full APK or a stream at one central-directory entry.
- Bare behavior remains inference only: no root, no debuggable game copy,
  and the live `/proc/<pid>/maps` of the game is unreadable from adb.
- `preloadDelayMs=33000` is parsed but unused; only `pairip_delay_ms`
  (`delayMs`) waits before `libmain`. A timing run is possible but weak,
  because the VM image is built before `libmain` and the slots are already
  pristine at that point.
- Ring positions are not invariants; compare final tails and images only.

## Next move

Find why klB reaches the `0x31` builtin at pc `0x40a73` at all on the hosted
path, when a bare launch does not. Decode the `0x31` operand block and the
preceding state's `next`/`fallback` decision, then derive exactly one probe
that distinguishes "reached `0x31`" from "skipped `0x31`". The reader is a
ZIP/CD view, so the other half of the fork is which branch should have
supplied the CD view. Findings `Next steps` item 18 owns the next move; the
open questions paragraph above it records the closure. Do not repeat the
loader, license, application, map, identity, or stack-text experiments.

## Artifacts on this machine (gitignored)

- Log: `tmp/cifi-apk/logs/verify/stock-libmain-reader-slots.log`
  (14,661 lines, sha256 `60a30e02e291e0a420993664a7a6354dc1065ea26f4b10c885be0bcb1c31b333`).
- Crash image: `tmp/cifi-apk/images/stock-libmain-reader-slots-image.bin`,
  sha256 `50648e90916e9fd48d3ea89332c798422d5bd2854a4fa395b681f6fb58aa9919`.
- Previous crash image: `/tmp/opencode/stack-len-plus-one-image.bin`,
  sha256 `28b923f54e2c5b02baf08ef2e5d0928936409df057b5eb66b84fee3121f77c9d`.
- Pristine VM asset:
  `/tmp/opencode/cifi_pairip/vm_assets/assets/klB1wZSojiVvDvCN` (282,603
  bytes, sha256 `112b6e592c18c9877b90b8f0df2c2d4dd037bf0742bacbd914a03eed242011fa`).
- Reader-source log: `tmp/cifi-apk/logs/verify/reader-source-trace.log`
  (8,820 lines, sha256
  `5c1897538124867bf3c57e6a5ee5331545147ab69d5b506f7a09b6d350f0302b`).
- Split APK: `/tmp/opencode/cifi_pairip/split_config.arm64_v8a.apk`
  (107,115,296 bytes); `libpairipcore.so` sits at offset `0x4e51a64`.
- Disassembly: `/tmp/pairip-analysis/libpairipcore_full.asm`.
- These paths are not in git. Copy them over, or re-capture on the new
  machine; the committed docs carry the derived values.

## On the new machine

1. `git pull` on `test/feat-cifi`; confirm `git log -1` matches the pushed
   tip.
2. `cd launcher && ./gradlew assembleDebug`. The fixed debug keystore is
   committed at `launcher/debug.keystore`, so `android install` remains a
   delta update.
3. `adb devices` / `android info`; confirm SM-S931N and the installed
   package. Use `android install`, not `adb install`.
4. Read the findings and the walkthrough, then pre-register the next run.
   One cold launch at a time, `am force-stop` between runs, and `--el` for
   `pairip_delay_ms`.
5. Check the walkthrough run keys first: `stock-libmain-reader-slots` now has
   a verdict and must not be repeated.

## Constraints (unchanged)

No root; never modify, repack, re-sign, or reinstall the game APK; no
shipping bypasses; one variable per run; check the walkthrough run keys
first; update both docs before and after each run; commit locally. Do not
push, merge, or create branches/tickets without approval. The user
explicitly approved the push of this handoff and the existing branch state
on 2026-10-28.

## Suggested skills

Call the Skill tool for:

- `android-cli` before device install/launch/screenshot work.
- `diagnosing-bugs` for the `0x31` branch/operand loop.
- `code-review` before the merge and cleanup pass required by #64.
- `handoff` at the end of the next session.

Do not use `adaptive` or `edge-to-edge`; this is not a UI task.

## Quick orientation

```bash
cd /path/to/BepInEx-Android
git log --oneline -5
grep -n "^## " docs/cifi-pairip-findings.md | tail -20
grep -n "stock-libmain-reader-slots" docs/cifi-pairip-walkthrough.md
```
