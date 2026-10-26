# Handoff: CIFI PairIP klB crash (2026-10-27)

Snapshot of the hosted-launch investigation for CIFI
(`com.OctocubeGamesCompany.CIFI`), written so a fresh agent on another device
can continue from a clean checkout. The crash is still unresolved; the work is
at the "identify the gate" stage, not the "apply the fix" stage.

This handoff is a point-in-time summary. The living record is
[`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md); update that file
as facts change instead of editing this snapshot.

## Mission and constraints

- Goal: launch CIFI through the BepInEx Android launcher with no PairIP
  `libpairipcore.so` SIGSEGV, chainloader complete, `interop/` generated, title
  screen reached.
- Acceptance: three consecutive cold launches pass (#63), title screen +
  chainloader + interop (#62), no regression for non-PairIP games (#64).
  Mechanism ADR (#60), startup map (#58), and isolation matrix (#59) follow
  from that.
- Hard constraints:
  - No root. The device is a production `user` build (`ro.debuggable=0`).
  - Never modify, repack, re-sign, or reinstall the installed game APK. Keep
    the Play-installed copy.
  - Do not patch `memmove`, fake the CD reader pointer in a shipping path,
    forge signatures, extract keys, or bypass `LicenseClient`.
  - Do not change behavior for non-PairIP targets. All experiment switches
    stay off by default.
  - Use `android install`, not `adb install`. Use `--el` for
    `pairip_delay_ms` because `--ez` truncates it.

## Where the work is

- Repo: `Tb1281/BepInEx-Android`
- Branch: `test/feat-cifi`, pushed to `origin/test/feat-cifi`
- Code checkpoint: `71f534e` (`wip(pairip): checkpoint klB image-watch and
  maps-scan probes`)
- Findings doc: `8bf5ec1`, wording fix `3277036`
- This handoff adds documentation only; it does not change code.
- The branch contains debug-only WIP instrumentation. It is not merged to
  `main`.
- The temporary `pairip_allow_nondebug_experiments` bypass is still committed
  (from `6a45811`). It exists only to run artifact-build experiments on a
  non-debuggable build. Remove it before merge and final verification.
- A debug APK from the previous session is at
  `launcher/app/build/outputs/apk/debug/app-debug.apk` on that machine. It is
  not in git; rebuild on the new machine.

## Current failure

Hosted launch dies in `libpairipcore.so` before the game's Java `Application`
finishes starting:

```
F libc: Fatal signal 11 (SIGSEGV) in __memmove_aarch64_nt+144
LR libpairipcore.so+0x8d518  (call at 0x8d514)
fault addr 0xbbb9eb6daed20ed0  length 0x2e (46)
```

The 46-byte source is a ZIP central-directory header read (`PK\x01\x02`,
`0x02014b50`). The pointer and size are read from two slots in the klB VM
image:

| VM image offset | Value at crash | Meaning |
| --- | --- | --- |
| `0x3f874` | `0xbbb9eb6daed20ed0` | CD reader pointer (garbage) |
| `0x1ba5c` | `0x015370f87525d57b` | CD reader size (garbage) |

Those bytes are literally present in the on-disk `klB` asset; they are not
written at runtime. Whole-image write attribution found 368 changed bytes in
79 ranges, none covering either slot.

## What is proven (do not re-derive)

- `klB` is byte-identical to the APK asset. `VmDecryptor.decrypt` is a no-op;
  `VMRunner.getVmByteCode` returns the asset unchanged; `executeVM` copies but
  does not decrypt or relocate.
- Stock bare launch of CIFI is healthy and reaches the title screen, including
  a successful licensing-service bind. The failure is specific to the hosted
  process, not the game or device.
- Running the real game `Application` lifecycle first (SignatureCheck +
  LicenseClient) does not fix klB. Neither does the non-debuggable artifact
  build, so the launcher's debuggable flag is not the cause.
- Letting `LoadedApk.createAppFactory` instantiate the factory naturally (stock
  stack shape, LSPlant still deferred) still crashes identically.
- The stack-trace spoof is applied for klB (`nativeLoader=true`) and does not
  fix it. The crash is independent of LSPlant, JNI tracing, process-name/UID
  spoofing, and the proc sanitizer.
- Preloading runs `YTw4` to completion and only then `dlopen`s `libunity`;
  klB still crashes.
- Hosted klB Java preamble resolves `java/io/File`, `java/lang/Long`,
  `java/lang/Exception`, `java/lang/StackTraceElement`, `android/os/Debug`,
  reads `/proc/self/maps` and `/proc/self/status`, creates 420 `Long`s, takes
  two 20-frame stack traces, checks `Debug.isDebuggerConnected` /
  `waitingForDebugger` (both false), resolves the APK path twice, sends the
  normal Play Integrity `LOG_TELEMETRY` broadcast, then crashes.
- The 420 `Long`s are FNV-1 64-bit hashes of loaded-module basenames: 418
  unique paths from maps plus `FNV1("libpairipcore.so")` and
  `FNV1("libunity.so")`. They are telemetry, not the gate. The set excludes
  host-only modules (`liblsplant`, `libdobby`, `libfusion`, `libhookengine`,
  `libpairipshield`, .NET libs, `libmain`), matching the shield's hidden
  prefixes.
- klB opens both APKs read-only, `fstat`s them, scans the APK directory with
  `getdents64`, and resolves `/proc/self/map_files/` for the libpairipcore exec
  range. It performs no `mmap`/`pread`/`read` of either APK file. The CD
  buffer must come from existing mappings or JNI state.
- Hosted maps does contain the APK and CD mappings: `base.apk` `r--s` at file
  offset `0x74ba000` and a CD mapping at `0x74f5000` (for example
  `7e12dbd000-7e12dd0000`), covering EOCD at `0x750781c`. Split APK CD is at
  `0x6627000` (`7e1f620000-7e1f621000`). `base.apk` has 839 CD entries; the
  420 count is unrelated.
- The VM decodes image strings with a key equal to the image length
  (`0x44feb`), per-string XOR with `keyoff`/`data` offsets. The class-trace log
  prints `idx`, `enc`, `data`, `keyoff`, `len`, and decoded text.
- VM dispatcher is `libpairipcore+0x6e918`, called as `(state, op)`;
  `state+0x88` points to `{image_ptr, length, pc}`, `state+0x90` is a u32
  cursor, `state-0x40` packs image length (low 32) and fetch cursor (high 32).
- klB trace shape: 16-instruction prologue; 3-instruction loop x420; 12-insn
  block; 6-insn loop A x~23; 6-insn block; 6-insn loop B x~24; then
  `0xd198 op=0xa9`, `0x1026f op=0x7e`, `0x121c2 op=0xa9`,
  `0x1f813 op=0x7e` -> crash. The 420-loop is
  `0x036818 op=0x41`, `0x03f2b7 op=0x7e`, `0x00f658 op=0x1b`, i.e. the `0x7e`
  builtin runs once per module.
- The `0x3dd00` CD-entry path parses name/extra/comment lengths, CRC, reads
  the variable part, extracts the name, hashes it via `0x57b98`, and interns
  `{hash,name,crc}` into a hash set (`0x3e414`-`0x3e5f0`).
- `0x3dd00` is not the direct handler for opcode `0x7e`; `0x6ff60` is inside
  the MBA-flattened dispatcher, not a clean handler entry. The native handler
  at `+0x31e00` contains inlined FNV-1 loops compared against VM-context
  values; still a candidate, not yet tied to the CD gate.

## What changed in this round

- The reader is a `{vtable, cursor, data, size}` stream object
  (`vtable 0x93ce8`, method `+0x8d460` at slot `+0x10`), built at `+0x3e030`.
  It loads the data word from `image+0x3f874` and the size word from
  `image+0x1ba5c`. `+0x3e170` then parses a ZIP central directory entry
  (`PK\x01\x02`). Entry length is the sum of the u16 fields at `+0x1c`,
  `+0x1e`, and `+0x20`.
- Fake-CD diagnostics: mapping the base APK central directory (`0x1281c`) or
  the arm64 split directory (`0x30a`) removes the SIGSEGV in both cases, then
  the run dies in a CheckJNI `FindClass` SIGABRT. The VM-pc-tagged class trace
  entry #9 is `caller=+0x7295c`, `vm=0xf0df/0x30`, `idx=0x19ded`,
  `len=12928`.
- klB scans `/proc/self/maps` in 1 KB chunks through `0x23800` (about 141-142
  reads), then `/proc/self/status`, `readlink` of the maps fd, six
  `clock_gettime`, then the failing ZIP read. The served maps contain
  launcher-only `.so` paths and show game libraries as split-APK mappings
  (`extractNativeLibs=false`).
- New debug-only instrumentation in `71f534e`: VM write attribution, an
  image-watch SIGSEGV probe, maps `memcmp` needle tracing, `ExecuteProgram`
  argument logging, and a fake-CD path override.
- The installed base APK (`122,714,162` bytes, sha256
  `390fbd7ff3823c69bc3c4ff2f191f6fa787d0e8b33539a4c189c4cecfab9dc5f`) and
  arm64 split (`107,115,296` bytes, sha256
  `6b9a27886f5c2b7257fcb3ee8d59e27df788a6b097f35d787e0af4bfe037dc02`) match
  the copies used for static analysis. The game uses `extractNativeLibs=false`,
  so its libraries are mapped from the split APK.

## Ruled out

- LSPlant / hook engine presence, JNI table tracing, proc sanitizer,
  process-name and UID spoofing.
- Debuggable build flag (non-debuggable artifact build crashes identically).
- Missing game `Application` lifecycle (running it first changes nothing).
- Stock launch-stack shape for the app factory (natural factory crashes
  identically).
- Stack-trace spoof absence (confirmed applied, still crashes).
- PairIP decrypting/relocating the image differently in stock (image is
  identical).
- Play Integrity telemetry as a tamper response (stock sends it too).
- "klB never opens base.apk" (earlier syscall ring was truncated; full trace
  shows both APKs are opened and fstat'd).
- The 420 Longs as the gate (telemetry only).
- A runtime writer for the two ZIP slots (whole-image attribution found none).

## Open question

Why are image slots `+0x3f874` (CD pointer) and `+0x1ba5c` (CD size) never
written in the hosted process, and what is supposed to write them?

Given klB never reads APK bytes itself, the CD data must come from:

1. an existing `r--s` mapping the VM is expected to locate (the maps scan or
   `/proc/self/map_files` resolution may differ from stock), or
2. a JNI-provided buffer/state (for example from the game's `Application` or
   `LoadedApk`), or
3. a VM relocation/initialization step that is skipped by a guard.

A fix must make PairIP's own initialization run and pass; do not pre-seed the
two slots or fake the reader pointer.

## Next moves (priority order)

1. Capture the maps-scan `memcmp` operands and the caller/return address. The
   hook is installed with the PairIP trace and logging arms when klB opens
   `/proc/self/maps`; run with `--ez pairip_syscall_trace true` and no other
   extras. This should name the `.so` string klB searches for and identify
   whether the check is an anti-tamper gate or a real lookup.
2. Hook the maps parser at the read helper `libpairipcore+0x8cc78`
   (`this.returnAddress`) to find the parser and how it selects the APK/CD
   mapping; dump the buffer it builds. Confirm the hosted parse sees the same
   `r--s` CD ranges as stock.
3. Hook `+0x4ab64` (readlinkat / APK verify) and `+0x391b0` (dir open) to log
   the path strings and `fstat` results; compare with the stock path strings.
4. Decide what the reader expects: a full APK or a stream positioned at one
   central-directory entry. The fake-CD runs only prove the magic/length
   interpretation. Inspect the cursor arithmetic around `+0x3e030`/`+0x3e170`
   and the VM instructions that set the two slots.
5. Determine whether class-trace #9 is on the stock path or is a fake-CD
   artifact. If it is genuine, the `0xf0df/0x30` descriptor is the next decoy
   to decode.
6. Determine how stock turns `image+0x3f874` and `image+0x1ba5c` into valid
   values. Candidates: a runtime relocation/decryption step skipped by the
   hosted path, or an environment branch that selects a different image.
7. Only after a real mechanism is proven: apply the fix, build, `android
   install`, run three cold launches, record results on #62-#64, then write the
   #60 ADR and finish #58/#59.

## Repro / environment

Build:

```bash
cd launcher && ./gradlew :app:assembleDebug
android install launcher/app/build/outputs/apk/debug/app-debug.apk
```

Cold launch baseline (force-stop both packages first, then capture 20-25 s of
logcat):

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

Local artifacts from the previous device are not transferable. Regenerate them
with `pairip_dump_artifacts` or the Frida probes:

- `/tmp/pairip-analysis/REPORT.md` - full static analysis, crash
  reconstruction, hypotheses H1-H5.
- `/tmp/pairip-analysis/libpairipcore_full.asm` - full disassembly.
- `/tmp/pairip-analysis/executeprogram.asm` - `ExecuteProgram` disassembly.
- `/tmp/opencode/cifi-verify/` - logcat captures.
- `/tmp/opencode/vmrun/klB-image-at-crash.bin` - heap image at the failing
  read.
- `/tmp/opencode/vmrun/klB-maps2.txt` - maps snapshot served to klB.
- `/tmp/opencode/trace_fakecd_split.raw` - split fake-CD run with VM-pc tags.
- `/tmp/opencode/probe_syscall_caller.js`, `probe_syscall_full.js`,
  `run_probe4.sh` - Frida probes.

## Gotchas

- `/proc/<pid>/maps` is unreadable from adb (`Permission denied`);
  `debuggerd` needs root. Use the in-process dumper or Frida.
- The syscall ring is small; earlier "klB never opens the APK" conclusions were
  ring-truncation artifacts. Read the full ring and check for wraparound.
- `0x3dd00` / `0x6ff60` are not clean handler entries; do not hook them as if
  they were.
- `0x3f874` and `0x1ba5c` already contain the crash garbage on disk. Seeing
  those values is not evidence of a runtime write.
- The Frida gadget is hidden from `dl_iterate_phdr` but visible in maps; do not
  confuse it with a game library.
- The debug extras are ignored in a non-debuggable build unless the temporary
  `pairip_allow_nondebug_experiments` bypass is present. That bypass must be
  removed before merge.

## Suggested skills

Call the Skill tool for:

- `diagnosing-bugs` - structure the next hypothesis/test loop around the CD
  globals and the maps-scan needle.
- `android-cli` - use `android install`, device/emulator management, and log
  capture instead of raw `adb` shortcuts.
- `research` - if stock-vs-hosted behavior needs primary-source grounding
  (ART `LoadedApk`, linker mappings, PairIP-adjacent public analysis).
- `android-profiler` - if a Perfetto/Java heap or native trace is needed to see
  when the maps scan and JNI preamble run relative to the crash.
- `pr` - only when the branch is ready for review; not for this WIP push.
- `handoff` - when handing the work off again after the next checkpoint.

`handoff` itself is only for writing this document; do not re-invoke it for
the continuation.

## References

- [`GLOSSARY.md`](../../GLOSSARY.md) - PairIP hosting vocabulary.
- [`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md) - living facts,
  experiment table, open questions.
- [`docs/pairip-isolation-harness.md`](../pairip-isolation-harness.md) -
  cold-launch definition, baseline commands, #57 verification, scope note.
- [`docs/adr/README.md`](../adr/README.md) - read existing ADRs before writing
  #60.
- [`docs/agents/issue-tracker.md`](../agents/issue-tracker.md) - issue
  workflow.
- Issues: #56 (umbrella), #57 (harness), #58 (startup map), #59 (matrix),
  #60 (mechanism ADR), #61 (past PairIP startup), #62 (title), #63 (three cold
  launches), #64 (regression gate). Read the issues for the authoritative
  acceptance text; do not restate it in new docs.
