# Handoff: CIFI PairIP stack-length falsification (2026-10-28 00:45 KST)

Point-in-time snapshot for continuing the CIFI
(`com.OctocubeGamesCompany.CIFI`) hosted-launch investigation on another
machine. The crash is still unresolved. This is a delta snapshot; the living
record is [`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md) and the
append-only run log is
[`docs/cifi-pairip-walkthrough.md`](../cifi-pairip-walkthrough.md). Read the
binding rules at
[`docs/cifi-pairip-agent-rules.md`](../cifi-pairip-agent-rules.md) first.
Earlier snapshots are read-only:
[`2026-10-27-cifi-pairip.md`](2026-10-27-cifi-pairip.md),
[`2026-10-27-cifi-pairip-session2.md`](2026-10-27-cifi-pairip-session2.md), and
[`2026-10-27-cifi-pairip-router.md`](2026-10-27-cifi-pairip-router.md).

## State to pull

- Branch: `test/feat-cifi`, tracked as `origin/test/feat-cifi`.
- Pre-handoff tip: `2d81625` (docs-only). This handoff is committed on top;
  use `git log -1` after pulling.
- The branch was 23 commits ahead of the last pushed tip (`2ab9720`). This
  handoff and its push include all of them. The branch contains the
  debug-only investigation instrumentation and is not merged to `main`.
- Working tree: clean. The one-character `loade` experiment change was
  reverted after the run, so the code state equals `2d81625`.
- Last device run: `variant=stack-len-plus-one`, APK sha256
  `98bf9c7c2f059302e547e728579d383788d96a0eb346fb23c47a0132228acf6f`
  (131,054,961 bytes), log sha256
  `0c99a19332368b22e121dfa25043e118f7ef7fbf0c15fb64da62d2b2bea013c3`,
  one cold launch, still SIGSEGV.
- Device: SM-S931N, Android 17/API 37, `user`, `ro.debuggable=0`, no root.
  The game APK was not modified; the debug launcher
  `com.bepinex.android.debug` was installed with `android install`.

## What changed this round

- Static decode corrected the gate reading: `+0x7cdf4` hashes image-static
  bytes at `image[(w26^image32[pc+0x22])%size]` and compares against a
  bytecode immediate, not the JNI stack text. The planned observation-only
  probe at `+0x7cdf4` was withdrawn, not run.
- `stack-len-plus-one` (2026-10-28 00:39) changed only
  `NativeLoader.load` -> `loade` (51 -> 52 UTF-8 bytes) in
  `pairIpUnityLaunchStack()`. The 52-byte frame reached the VM twice (48
  `GetStringUTFChars` records, the same count as `stack-strings`), but the
  crash signature, five globals, reader slots, state/builtin/dispatcher
  tails, and every deterministic image byte were unchanged.
- The crash image differs from `stack-strings` in 30 ranges / 51 bytes, but
  has **zero** offsets that do not already vary across the older runs. The
  only non-pointer difference is the `image+0xae3` counter (`0x21a`),
  inside the known run-to-run range `0x215..0x21a`.
- Verdict: **FALSIFIED**. The runtime length is not consumed by the failing
  path. The stack-content route is closed; do not vary the spoof text or
  frame length again.
- Exact values and the full argument: findings
  `## Static correction (2026-10-28)` and
  `## stack-len-plus-one result (2026-10-28 00:39)`; walkthrough
  `## Planned run: stack-len-plus-one` / `### Result`.

## Still true / not proven

- The hosted run still dies in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk +0x514`; `vm[0x540]` reader slots remain
  `bbb9eb6daed20ed0` / `015370f87525d57b`.
- The five globals are stable across every hosted run, so they are not the
  missing discriminator.
- Reader slots `0x3f874`/`0x1ba5c` are pristine at the crash, and no dumped
  image-write ring entry touches them. The source of the expected
  initialization is still unknown.
- Stock behavior remains inference only: no root, no debuggable game copy,
  and live `/proc/<pid>/maps` of the game is unreadable from adb.
- Ring positions are not invariants; compare final tails and images only.

## Next move

Follow the reader at `+0x8d460` back to the state handler that fills the
`data`/`size` offsets (`w8`/`w9`), and identify which VM context or image
field should supply them. Before any device run, pre-register exactly one
variable in both docs and check the walkthrough run keys first. Findings
item 16 owns the next move; do not invent a run from this snapshot alone.

## Artifacts on this machine (gitignored)

- Log: `tmp/cifi-apk/logs/verify/stack-len-plus-one.log` (25,867 lines,
  sha256 `0c99a19332368b22e121dfa25043e118f7ef7fbf0c15fb64da62d2b2bea013c3`).
- Crash image: `/tmp/opencode/stack-len-plus-one-image.bin`, sha256
  `28b923f54e2c5b02baf08ef2e5d0928936409df057b5eb66b84fee3121f77c9d`.
- Comparison images: `/tmp/opencode/stack-strings-image.bin`
  (`89512e88...`), `/tmp/opencode/long-unbox-image-at-crash.bin`
  (`f5d547c5...`), `/tmp/opencode/seq-7e-image.bin`,
  `/tmp/opencode/jni-trace-image.bin`, `/tmp/opencode/jni-x20-image.bin`.
- Pristine VM asset:
  `/tmp/opencode/cifi_pairip/vm_assets/assets/klB1wZSojiVvDvCN` (282,603
  bytes, sha256 `112b6e592c18c9877b90b8f0df2c2d4dd037bf0742bacbd914a03eed242011fa`).
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
4. Read the findings, then pre-register the next run. One cold launch at a
   time, `am force-stop` between runs, and `--el` for `pairip_delay_ms`.

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
- `diagnosing-bugs` for the reader-source loop.
- `code-review` before the merge and cleanup pass required by #64.
- `handoff` at the end of the next session.

Do not use `adaptive` or `edge-to-edge`; this is not a UI task.

## Quick orientation

```bash
cd /path/to/BepInEx-Android
git log --oneline -5
grep -n "^## " docs/cifi-pairip-findings.md | tail -20
grep -n "stack-len-plus-one" docs/cifi-pairip-walkthrough.md
```
