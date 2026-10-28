# Handoff: CIFI PairIP klB crash (2026-10-28, post `split-cd-map`)

Point-in-time snapshot for continuing the CIFI
(`com.OctocubeGamesCompany.CIFI`) hosted-launch investigation on another
machine. The crash is still unresolved. The maps/procfs branch is now closed
for both the base-APK and split-APK paths; the live work is a static decode of
the `0x31` caller frame and instruction semantics.

This snapshot does not replace the living record. Read, in this order:

1. [`docs/cifi-pairip-agent-rules.md`](../cifi-pairip-agent-rules.md) — binding
   workflow and evidence rules.
2. [`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md) — proven facts,
   ruled-out list, open questions, next steps.
3. [`docs/cifi-pairip-walkthrough.md`](../cifi-pairip-walkthrough.md) —
   append-only run log with every verdict. Check the run key there before
   proposing any device run.
4. [`docs/pairip-isolation-harness.md`](../pairip-isolation-harness.md) —
   frozen baseline and cold-launch definition.
5. Earlier snapshots in [`docs/handoffs/`](.) for history; do not edit them.

## Mission and constraints (unchanged)

- Goal: launch CIFI through the BepInEx Android launcher with no PairIP
  `libpairipcore.so` SIGSEGV, chainloader complete, `interop/` generated, title
  screen reached.
- Acceptance: three consecutive cold launches pass (#63), title screen +
  chainloader + interop (#62), no regression for non-PairIP games (#64). The
  mechanism ADR (#60), startup map (#58), and isolation matrix (#59) follow.
- Hard constraints: no root; never modify, repack, re-sign, or reinstall the
  installed game APK; do not patch `memmove`, fake the CD reader pointer in a
  shipping path, forge signatures, extract keys, or bypass `LicenseClient`.
  Experiment switches stay off by default and are debug-only. Remove the
  temporary `pairip_allow_nondebug_experiments` bypass and debug extras before
  merge (#64).
- Workflow: one variable per run against the frozen baseline; pre-register the
  run in the walkthrough and findings before the run; force-stop both packages
  and clear logcat first; use `android install` (delta) and `android info`;
  `android layout`/`screen` only to verify the title screen; pass
  `pairip_delay_ms` with `--el`; serialize all device work.

## Branch and code checkpoint

- Repo: `Tb1281/BepInEx-Android`
- Branch: `test/feat-cifi` (tracking `origin/test/feat-cifi`)
- Code checkpoint at snapshot: `28803ad` (`docs(pairip): record the
  split-cd-map falsification`). This handoff commit is docs-only on top.
- Before the handoff push the branch was 17 commits ahead of the old remote tip
  `9057298`; the handoff push moves the remote tip to the handoff commit.
- The branch contains debug-only WIP instrumentation. It is not merged to
  `main` and must not be.
- The temporary `pairip_allow_nondebug_experiments` bypass is still committed
  (from `6a45811`). It exists only to run artifact-build experiments on a
  non-debuggable build; remove it before merge and final verification.
- Local build output `launcher/app/build/outputs/apk/debug/app-debug.apk` is
  not in git. Rebuild on the new machine.

## Device and installed state at snapshot

- Device: SM-S931N, Android 17 / API 37, `user` build, `ro.debuggable=0`,
  reachable at `100.125.173.79:5555` from the old machine. The new machine will
  have its own device; do not assume this address.
- Installed launcher APK sha256
  `13319e8ebd0e638978374932ad7b1417f65b732e22f67f02b612f2df2721b58a`
  (`versionCode=1`, `versionName=0.0.0-dev-debug`, installed 2026-10-28
  12:18:17). The device `base.apk` size and sha256 match the local build.
- Game APK untouched: `versionCode=3022`, `versionName=0.7.3.64`,
  `lastUpdateTime=2026-10-22 16:35:02`. Base APK `122,714,162` bytes, sha256
  `390fbd7ff3823c69bc3c4ff2f191f6fa787d0e8b33539a4c189c4cecfab9dc5f`; arm64
  split `107,115,296` bytes, sha256
  `6b9a27886f5c2b7257fcb3ee8d59e27df788a6b097f35d787e0af4bfe037dc02`.
  Re-verify these before any run; if they differ, stop and report.

## What changed in this round

Two probes were pre-registered, run once each against the frozen baseline, and
both are **FALSIFIED**. Details and log excerpts are in the findings and
walkthrough docs; do not repeat either run.

1. `nested-builtin-operand` (code `4239299`, verdict `08045d2`): dumped the
   `0x31` operand block at the builtin entry and again at the reader append.
   The two dumps are byte-identical — same `out32=0x22b39`, same 32-word
   operand block, same instruction bytes at `0x40a73`, same descriptor table,
   same five globals, same two reader slots. The `0x31` builtin is a
   consumer/validator, not the producer of the CD view.
2. `split-cd-map` (code `555ae05`, verdict `28803ad`): mapped the installed
   arm64 split APK's real central directory at `0x7900000000`
   (`MAP_SHARED`, file offset `0x6627000`, length `0x1000`). The matching
   `r--s 06627000 ... split_config.arm64_v8a.apk` line landed at byte 3,596
   (line 32) inside the VM's 146,432-byte maps scan; the original split line
   stayed at byte 367,195. Nothing changed: five globals, reader slots
   (`bbb9eb6daed20ed0` / `015370f87525d57b`), reader-source offsets
   (`0x3f874` / `0x1ba5c`), state tail (`id=0x872a21c sel=0x31bb`), builtin
   tail (`id=0x9a0c0031 pc=0x40a73`), crash
   (`__memmove_aarch64_nt+140`, split `+0x514`), and the image diff are all
   byte-identical to baseline.

Consequence: the missing reader-slot input is not a maps line, an APK
mapping, an identity fact, a loader, or a stack-text value. Do not run further
maps/procfs experiments.

## Artifacts on the old machine (not committed)

These are under `tmp/cifi-apk/` and are gitignored. They will not arrive with
`git pull`; regenerate them on the new machine if needed, or ask the old
machine's owner for copies.

- Log: `tmp/cifi-apk/logs/verify/split-cd-map.log`, 1,535,574 bytes,
  sha256 `40491a4d73352cf9f3e84b0a58a451fcbd6893ebbd2b0578bca845c69f0173cc`.
- Crash image: `tmp/cifi-apk/images/split-cd-map-image.bin`, 282,603 bytes,
  sha256 `62f79c9165ba260ac526e91194b37341abb8053d31aa4c4ab5d8c359164d2951`.
- Maps dump: `tmp/cifi-apk/vmrun/klB-maps-split-cd-map.txt`, 427,211 bytes,
  sha256 `b184265757c517d2d627cde617c471f99bf95b9e532a86e362fda4d6b3c1dfa9`.
- Disassembly: `tmp/cifi-apk/analysis/libpairipcore.asm` (contains
  `+0x6adc0`, `+0x31de4`, `+0x3dc88`, `+0x6a968`).
- Split APK copy: `tmp/cifi-apk/split_config.arm64_v8a.apk` (CD
  `0x6627000`, size `0x30a`, file size `0x6627320`).

Two older artifacts referenced by the findings are **absent**:
`tmp/cifi-apk/logs/verify/stock-libmain-reader-slots.log` and
`tmp/cifi-apk/logs/verify/seq-7e.log`. Their verdicts are documented, but
log-level evidence is UNVERIFIABLE. Do not re-derive them silently.

## Current live question

Why does the `0x31` builtin at bytecode `pc=0x40a73` execute with pristine
reader slots, and what should have populated them (or made the handler avoid
reading them)?

Established sub-facts:

- The `0x31` builtin receives a 32-word operand block but does not write the
  slots.
- `+0x31de4` ignores the high 16 bits of `w1`; the `0x9a0c` tag is inert.
  Effective dispatch is opcode `0x31` (low 16 bits).
- The Java caller/source is `VMRunner.invoke:57` → `getVmByteCode` /
  `readByteCode` (base-APK asset `assets/klB1wZSojiVvDvCN`) →
  `VmDecryptor.decrypt` (no-op). The image is byte-identical to the asset.
- The vtable-caller hypothesis is ruled out: `+0x83478` is JNI
  `ExceptionCheck`. The nested path at `+0x8b1f0` is a JNI native-method entry
  that registers `executeVM_inner` (`+0x6a968`); do not run
  `early-register-natives`.
- A return hook at `+0x6adc4` was rejected: the builtin does not return before
  the crash. The reader append at `+0x3e170` is the usable "after" point.

## Next move (static only until a probe is pre-registered)

Decode the caller frame at `+0x6adb4` and the `0x40a73` instruction/operand
semantics from the disassembly and the pristine image:

- Caller frame fields: `sp+0x44` (id), `sp+0x74` (out), `sp+0x78` (operand
  block).
- Instruction at `0x40a73` and the descriptor table at `image+0xb0`; trace how
  the 32-word operand block is formed and which image words it references.
- Decide whether the `0x31` handler computes the slots itself, or expects an
  earlier writer that the hosted path skips. If an earlier writer exists,
  identify the exact branch/state and why hosted differs from bare launch.
- Only then pre-register one new device probe in the walkthrough and findings.
  A device run without a pre-registered, falsifiable prediction violates the
  agent rules.

Do not pre-seed the slots or fake the reader pointer. A fix must make PairIP's
own initialization run and pass. Do not run further maps/procfs experiments.

## Ruled out / do not repeat

The authoritative list is the findings doc's ruled-out table plus every
walkthrough verdict. Before proposing a run, search the walkthrough for its run
key. Keys already closed include: `nested-start-caller`,
`nested-start-caller-pc22727`, `nested-start-caller-vtable`,
`nested-register-natives`, `nested-builtin-operand`,
`stock-libmain-reader-slots`, `reader-source`, `syscall-tail`, `apk-zero-map`,
`apk-cd-map`, `split-cd-map`, `process-name-spoof`, `identity-conjunction`,
`stock-bound-context`, `game-application`, `license-consumption-trace`,
`jni-x20`, `long-unbox`, `stack-strings`, `stack-len-plus-one`,
`selector-gate`, `hash-gate`, `state-router-retry`.

Also closed: the vtable-caller hypothesis (`+0x83478` is
`ExceptionCheck`), the maps/status/name/uid/stack branch, and both APK
paths for the maps-window lead.

## Repro / environment

Build:

```bash
cd launcher && ./gradlew :app:assembleDebug
android install launcher/app/build/outputs/apk/debug/app-debug.apk
```

Cold launch baseline (force-stop both packages, clear logcat, then capture
20–25 s):

```bash
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI
```

`BootstrapActivity` is `singleTop`; force-stop both packages before every
experiment. Extra switches are `pairip_*` in `PairIpExperiment.kt`; use `--el`
for `pairip_delay_ms`. Run `run_dumpvm.sh` only for successful runs: it waits
for `vm ring pos=` and times out on abort runs.

## Gotchas

- `/proc/<pid>/maps` is unreadable from adb on this device; use the in-process
  dumper or Frida. `debuggerd` needs root.
- The syscall ring is small; earlier "klB never opens the APK" conclusions
  were ring-truncation artifacts. Read the full ring and check for wraparound.
- `0x3dd00` / `0x6ff60` are not clean handler entries; do not hook them as if
  they were.
- `0x3f874` and `0x1ba5c` already contain the crash garbage on disk. Seeing
  those values is not evidence of a runtime write.
- The Frida gadget is hidden from `dl_iterate_phdr` but visible in maps; do not
  confuse it with a game library.
- Debug extras are ignored in a non-debuggable build unless the temporary
  `pairip_allow_nondebug_experiments` bypass is present. That bypass must be
  removed before merge.
- Determinism across hosted runs alone is not evidence of an environment
  input; instruction-derived values are also deterministic.
- Instrumentation is not neutral. Confirm any traced result with tracing off
  before treating it as the uninstrumented behavior.

## Suggested skills

Call the Skill tool for:

- `android-cli` — device management, `android install`/`info`, screenshots, and
  log capture instead of raw `adb` shortcuts.
- `diagnosing-bugs` — structure the next static-decode → hypothesis → single
  run loop around the `0x31` caller frame and the hosted-vs-bare branch.
- `research` — if the decode needs primary-source grounding (AArch64 operand
  semantics, ART/linker behavior, PairIP-adjacent public analysis).
- `android-profiler` — if a Perfetto or native trace is needed to order the
  maps scan and JNI preamble relative to the crash.
- `handoff` — when handing the work off again after the next checkpoint.
- `pr` — only when the branch is ready for review; not for this WIP branch.
