# Handoff: CIFI PairIP klB crash (2026-10-27)

Snapshot of the hosted-launch investigation for CIFI
(`com.OctocubeGamesCompany.CIFI`), written so a fresh agent on another device
can continue from a clean checkout. The crash is still unresolved; the work has
narrowed from "which image slot is wrong" to "what feeds the five-word hash
state that selects the slots", not the "apply the fix" stage.

This handoff is a point-in-time summary. The living record is
[`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md); update that file
as facts change instead of editing this snapshot.

Continuation update (later 2026-10-27): the five u32 image globals that feed
the reader-slot computation are now identified, their update sites are mapped,
and an offline emulator reproduces both the crash pair and the pristine pair.
The open fork is whether the 70-byte input to that hash is static (stock
computes the same globals and must take a different branch) or
maps/status/path-derived (the globals differ in stock). See
"What changed in this round" below.

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
- Branch: `test/feat-cifi`, tracked as `origin/test/feat-cifi`
- Code checkpoint: the WIP commit containing this update (on top of
  `dff372c`). It commits the debug instrumentation that was previously
  unstaged: builtin/state/hash rings, `skipKlb`/`readerGuard`, and the
  five-global analysis notes.
- The local branch previously pointed its upstream at
  `origin/feat/cifi-pairip-hosting`; it is now `origin/test/feat-cifi`.
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

## What changed in this round (continuation)

- The reader object is a `{vtable, cursor, data, size}` stream (`vtable
  0x93ce8`, method `+0x8d460` at slot `+0x10`). `+0x3e030` fills it with
  `data = image[w8]` and `size = image[w9]`; `+0x3e170` then parses a ZIP
  central-directory entry (`PK\x01\x02`). Entry length is the sum of the u16
  fields at `+0x1c`, `+0x1e`, and `+0x20`.
- `w8`/`w9` are computed by `+0x3dc88..+0x3e030` from the five u32 image
  globals at `0x2129a`, `0x377ff`, `0x1f422`, `0x1da6f`, `0x38edd` (the
  offsets are the first five decoded entries of the table at `image+0xb0`)
  plus the instruction words at the current bytecode pc.
- Emulating that block over the pristine asset gives
  `w8/w9 = 0x31c1b/0x3b302`; over the runtime image at the crash it gives
  `0x3f874/0x1ba5c`, reproducing the crash exactly. The pristine pair also
  points at non-pointer asset bytes.
- The five globals are rewritten at `0x333eb/0x7e`, `0x395ad/0x6d`,
  `0xf656/0x1b` (70-byte per-byte loop), `0x158e1/0x92` and `0x1831/0x92`
  (two 16-word block loops), and again in the in-progress `0x1f811/0x7e` at
  the crash. The shape is a five-word hash (SHA-1/RIPEMD-160-like).
- The crash image's globals are `0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`,
  `0xbfe458cb`, `0x4d062af3`; the post-copy prologue seeds them with
  `0xddf5ddaf`, `0xbe61b175`, `0xa5c5514d`, `0xd6db4e18`, `0x9b708920`.
- Three hosted runs reached byte-identical global values while ASLR pointers
  differed, so the chain does not hash raw map addresses. That does not prove
  stock computes the same values: the hash input is still unidentified.
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
- The installed base APK (`122,714,162` bytes, sha256
  `390fbd7ff3823c69bc3c4ff2f191f6fa787d0e8b33539a4c189c4cecfab9dc5f`) and
  arm64 split (`107,115,296` bytes, sha256
  `6b9a27886f5c2b7257fcb3ee8d59e27df788a6b097f35d787e0af4bfe037dc02`) match
  the copies used for static analysis.

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
- The maps-scan `memcmp` needle (the captured comparisons are the shield
  logger's own tag checks).
- Launcher Fusion `libmain` vs game `libmain` (the game's own `libmain`
  produces the identical crash).
- `skipKlb` and the reader guard are diagnostics only; they advance past the
  crash but are not the mechanism or the fix.

## Open question

What 70-byte input feeds the five-word hash state? Two branches:

1. Static image/instruction data: stock computes the same five globals. The
   reader slots are then invalid in stock too, so stock must either write or
   relocate the selected slots, or take an earlier branch and never issue the
   `0x31` call.
2. Maps/status/path-derived data: the globals differ in stock and the
   environment difference is the gate.

Do not pre-seed the slots or fake the reader pointer. A fix must make PairIP's
own initialization run and pass.

## Next moves (priority order)

1. Decode the `0x1b`/`0x92` handlers and log the 70-byte input at `0xf656`.
   Determine the byte source and whether it is static or environment-fed.
2. If static: prove stock computes the same globals, then decode the `0x31`
   state selection (`0x321c0`/`0x32c6c`) and the preceding state's
   `next`/`fallback` decision to find the branch stock takes.
3. If environment-fed: reproduce stock's maps/status content and ordering.
   Running `libunity`'s `DT_INIT` before the Fusion/.NET preload is a
   diagnostic worth trying.
4. Decode the `0x31` builtin semantics and the `0x40a73` operand block.
5. Only after a real mechanism is proven: apply a gated fix, build, `android
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
with `pairip_dump_artifacts` or the Frida probes. The `/tmp/opencode` paths are
host-local and are not committed; the device logs are pulled from logcat:

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
- `/tmp/opencode/scan_block.py` - Python emulator for native
  `+0x3dc88..+0x3e030`. Usage:
  `python3 scan_block.py <bytecode-pc> 0xb0 <image.bin>`; the values are in
  `regs[8]`/`regs[9]` (the script's `__main__` still assumes the old
  iterable return value, so import it and call `run_one()`).
- `/tmp/opencode/dumpvm/` - baseline `klB-image-at-crash.bin`, `imagew.txt`
  (write ring), `klB-maps.txt`.
- `/tmp/opencode/dumpvm-sanitize/` - procfs-sanitizer image and maps/status
  inputs.
- `tmp/cifi-apk/logs/verify/baseline-dumpvm.log`, `hash-ring.log`,
  `state-ring.log`, `proc-sanitize.log`, `syscall-maps.log` - pulled logcat
  captures (repo-local, gitignored).

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

- `diagnosing-bugs` - structure the next hypothesis/test loop around the
  70-byte hash input and the stock-vs-hosted branch.
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
- [`.agents/skills/setup-matt-pocock-skills/issue-tracker-github.md`](../../.agents/skills/setup-matt-pocock-skills/issue-tracker-github.md) -
  issue workflow.
- Issues: #56 (umbrella), #57 (harness), #58 (startup map), #59 (matrix),
  #60 (mechanism ADR), #61 (past PairIP startup), #62 (title), #63 (three cold
  launches), #64 (regression gate). Read the issues for the authoritative
  acceptance text; do not restate it in new docs.
