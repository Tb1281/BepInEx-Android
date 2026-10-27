# CIFI PairIP startup findings

Working notes for the CIFI (`com.OctocubeGamesCompany.CIFI`) hosted-launch
investigation tracked by #56, #58, #59, #60, #62, #63, and #64. This is a
checkpoint, not a fix: the crash is still unresolved and the mechanism ADR
(#60) is not written yet.

The debug-only switches used here are described in
[`pairip-isolation-harness.md`](pairip-isolation-harness.md). All of them
default to off and are ignored by a non-debuggable build unless the temporary
`pairip_allow_nondebug_experiments` bypass is passed. That bypass is for
experiments only and must be removed before the branch is merged.

The cross-device continuation snapshot is
[`handoffs/2026-10-27-cifi-pairip.md`](handoffs/2026-10-27-cifi-pairip.md).

## Verified startup path

- `StartupLauncher` invokes the PairIP program `YTw4BW9dg3brGdIP`.
- libunity's patched `DT_INIT` stub (vaddr `0x16bc000`) calls
  `ExecuteProgram("klB1wZSojiVvDvCN", {libunity base}, 1)`. The stub loads the
  import from `libpairipcore.so+0x6b3d0` and passes a one-element argument
  array.
- `VMRunner.invoke` calls `readByteCode`, which reopens the installed base APK
  on every launch. `VmDecryptor.decrypt` is a no-op, so `executeVM` receives
  the stored `klB` asset unchanged (`282,603` bytes, `0x44feb`, magic `\0IAP`).
- `executeVM` allocates the writable image and copies the asset at the traced
  copy point; register `x20` points at the image when the copy hook runs.
- The installed base APK (`122,714,162` bytes, sha256
  `390fbd7ff3823c69bc3c4ff2f191f6fa787d0e8b33539a4c189c4cecfab9dc5f`) and
  arm64 split (`107,115,296` bytes, sha256
  `6b9a27886f5c2b7257fcb3ee8d59e27df788a6b097f35d787e0af4bfe037dc02`) match
  the copies used for static analysis. The game uses
  `extractNativeLibs=false`, so its libraries are mapped from the split APK.

## Crash signature

- SIGSEGV in `__memmove_aarch64_nt+140`, fault address shaped like
  `0xbbb9eb6daed20ed0`; the call is `libpairipcore.so+0x8d514` and the return
  address is `libpairipcore.so+0x8d518`.
- The failing read is a 46-byte ZIP central-directory record. `+0x8d460` is a
  stream/reader vtable method (`vtable 0x93ce8`, slot `+0x10`), not
  `std::string::append`. The reader object is laid out as
  `{vtable, cursor, data, size}`.
- The reader is built at `+0x3e030`. It loads the data word from
  `image+0x3f874` and the size word from `image+0x1ba5c`; `+0x3e170` then
  parses a ZIP entry whose magic is `PK\x01\x02` (`0x02014b50`). Entry length
  is the sum of the `u16` fields at `+0x1c`, `+0x1e`, and `+0x20`.
- Both image slots are byte-identical to the stored asset and are never
  written at runtime: they contain pointer-shaped garbage
  (`0xbbb9eb6daed20ed0`, `0x015370f87525d57b`) rather than a valid address and
  length. Write attribution over the whole run found 368 changed bytes in 79
  ranges, none covering either slot. That count is from the retry image
  `tmp/cifi-apk/vmrun/klB-image-retry.bin` (282,603 bytes, sha256
  `004f0fd50a65507baf1892b5f16e78df5c4d34ddc52a0eda4f5eb640ab561375`).
  The older `tmp/cifi-apk/vmrun/klB-image-at-crash.bin` (sha256
  `77c0088f1742c06a6cc8d27a1e984fd66fd7d96adf483e86ed32a31b9c8c9024`)
  has 367 changed bytes in 80 ranges. The `fnv-input-retry` verdict's
  "78 ranges" is a miscount against the older image; `0x24f47` is unchanged
  in both images and in the pristine asset.
- Captured VM state at the failing read: image base
  `0xb400007b1224d010`, size `0x44feb`, VM pc field `0x40aab`.

## Reader slot derivation (continuation, 2026-10-27)

The failed reader is built by the native block `+0x3dc88..+0x3e030`, not by
the ZIP parser at `+0x3e170`. `+0x3e030` loads two words from the writable
image and fills the stream object at `sp+0x550`:

```
x8 = image[w8]   // data
x9 = image[w9]   // size
reader = { vtable=0x93ce8, cursor=0, data=x8, size=x9 }
```

`w8` and `w9` come from two inputs:

1. The first five entries of the decoded table at `image+0xb0`. The same
   `(value ^ ~len) % len` cipher used for strings decodes them to `0x2129a`,
   `0x377ff`, `0x1f422`, `0x1da6f`, `0x38edd`; the block reads the five u32
   globals stored at those offsets.
2. The instruction words at the current bytecode pc (the failing call is at
   bytecode pc `0x40a73`).

Those five globals are rewritten at fixed bytecode sites before the reader is
built:

| Bytecode fetch | Op | Shape |
| --- | --- | --- |
| `0x333eb` | `0x7e` | one combined update of all five |
| `0x395ad` | `0x6d` | one combined update of all five |
| `0xf656` | `0x1b` | per-byte FNV input loop; counter `0x266f2` (u32) runs `0x1a3` -> 1 over 419 calls |
| `0x158e1` | `0x92` | 16-iteration block loop (`0x2ee6`) |
| `0x1831` | `0x92` | 16-iteration block loop (`0x33358`) |
| `0x1f811` | `0x7e` | in progress at the crash; produces the final values |

The `0x1b`/`0x92` shape is a five-word hash state. The runtime write ring
records 419 one-byte updates at `0xf656`; the earlier 70-byte estimate is not
supported by that capture. The fnv-input probe reads the counter as a u32 and
sees one `0x1a3` -> 1 countdown over those 419 calls (plus a counter-0
boundary call). The ring's `0x100` -> `0xff` and `0xa3` -> `0xa2` entries are
consistent with 2-byte and 1-byte stores of the same countdown, not separate
phases.
The fnv-input probe identifies the input: the same static image byte `0x99`
at `image+0x24f47` is hashed once per call for the 419-call loop (plus one
counter-0 boundary call). It matches the pristine asset at that offset and
lies outside every runtime diff range, so the input is static image data.
A stock launch therefore computes the same five globals; the gate is not the
input source. What remains is why stock does not hit the invalid slots: it
either initializes/relocates the selected slots or takes a different branch
before the `0x31` call.

The crash image reads `0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`,
`0x4d062af3` at `0x1da6f`, `0x1f422`, `0x2129a`, `0x377ff`, `0x38edd`.
The post-copy prologue seeds them with `0xddf5ddaf`, `0xbe61b175`,
`0xa5c5514d`, `0xd6db4e18`, `0x9b708920` (write ring `by=-1`).

Emulating `+0x3dc88..+0x3e030` over the image reproduces the runtime result:

| Image | `w8` | `w9` |
| --- | --- | --- |
| pristine asset | `0x31c1b` | `0x3b302` |
| runtime image at crash | `0x3f874` | `0x1ba5c` |

The pristine pair also points at non-pointer asset bytes, so the shipped `klB`
cannot satisfy the reader without a runtime write or a different branch.

The reader state is entered directly: the dispatcher's
`+0x34d74 b.eq +0x3dc88` jumps to the reader body, so the
`+0x3dc08..+0x3dc84` hash block is not a fall-through predecessor. That block
belongs to a different handler and ends with `+0x3dc84 b +0x51f44`. The
hosted-run `hash-gate` probe installed hooks at `+0x3dc0c` and `+0x3dc50` and
recorded zero hits (`hash ring pos=0`), confirming the block is skipped on the
crash path. The selector `sel=0x31bb` is produced by the main state-router
hash at `+0x32c20..+0x32c54`, not by the `+0x3dc08` block.

## Experiments and results

| Experiment | Result |
| --- | --- |
| Write attribution / image diff | Both ZIP slots stay at their asset values; no writer found. |
| Fake central directory from base APK (`0x1281c` bytes) | SIGSEGV disappears, run continues to a CheckJNI `FindClass` SIGABRT. |
| Fake central directory from arm64 split (`0x30a` bytes) | Same behaviour as the base-APK run. |
| VM-pc-tagged class trace | UNVERIFIABLE: the reported fake-CD entry #9 (`caller=+0x7295c`, `vm=0xf0df/0x30`, `idx=0x19ded`, `len=12928`) has no local raw trace. The local hosted traces instead show `vm=0xd198/0xa9` at #9. Do not use this row until the raw trace is restored. |
| Procfs trace | klB reads `/proc/self/maps` in 1 KB chunks through `0x23800` (141-142 reads), then `/proc/self/status`, `readlink` of the maps fd, six `clock_gettime`, then the failing read. |
| Served maps snapshot | Contains launcher-only `.so` paths (`libcoreclr.so`, `libfusion.so`, `libmscordacore.so`, ...); game libraries appear as split-APK mappings. |
| `memcmp` needle trace | Closed: the captured comparisons are the shield logger's own tag checks, not a klB maps needle. |
| Five-global emulation | Reproduces `0x3f874`/`0x1ba5c` from the crash image and `0x31c1b`/`0x3b302` from the pristine asset. |
| Cross-run global comparison | The five globals reach byte-identical values in three hosted runs while ASLR pointers differ. |
| Image-watch (read-only first page) | One u32 increment at `image+0xae3` from `+0x5f714`; no write to either ZIP slot. |
| fnv-input probe (`+0x7fddc`) | 419 calls, `len=1`, all at `image+0x24f47`, byte `0x99`, checksum `ba6cb68e62faac68`; pristine asset and crash image match at that offset; baseline crash unchanged. The input is static image data. |
| hash-gate probe (`+0x3dc0c`, `+0x3dc50`) | Both hooks `rc=0`, zero hits (`hash ring pos=0`); state `0x872a21c` reaches the reader via `+0x34d74 b.eq +0x3dc88`; the `+0x3dc08` hash block is a different handler's tail. Baseline crash unchanged. The block is not the hosted gate. |
| state-router retry (`+0x32c20`) | Hook `rc=0`, zero hits (`router ring pos=0`, `eq=0`, `ne=0`); state `0x872a21c` branches directly to the reader at `+0x34d74 b.eq +0x3dc88`; the `0x31` builtin runs at `pc=0x40a73` immediately before the baseline crash. The router hash block is not on the reader path. |
| Offset-0 base-APK mapping (`pairip_apk_zero_map`) | Mapping installed at the planned address (`0x7900000000`, `fixed=1`) and appears in klB's maps dump (`r--p 00000000`), yet the crash, reader slots, FNV input, state tail, and builtin tail are unchanged. Falsified: the missing offset-0 mapping is not the gate. |
| Process-name spoof (`pairip_process_name`) | Main-thread `prctl` succeeded and the VM's `/proc/self/status` read saw `mesCompany.CIFI` (argv0/`__progname` were rewritten too), yet the FNV input, five globals, reader slots, state/builtin tails, crash signature, and image diff are byte-identical to baseline. Falsified: process identity is not the gate. |
| syscall-tail (`pairip_syscall_trace`) | 181 raw syscalls, all file paths procfs; no game-APK `openat`/`read` before the `0x31` reader. The maps scan is 143 full 1 KB reads (146,432 bytes) and stops before both real CD mappings. All 32 builtin-ring entries carry the same `args=` words, so the ring exposes shared dispatcher context, not per-builtin operands. PROVEN for the no-native-file-read hypothesis. |
| Low CD mapping (`pairip_apk_cd_map`) | The base APK's real CD was mapped at `0x7900000000` (`MAP_SHARED`, offset `0x74f5000`), and its `r--s 074f5000` line appeared at byte 3,596, inside the VM's 146,432-byte maps scan. The five globals, reader slots, state/builtin tails, crash signature, and 368-byte/79-range image diff are unchanged. Falsified: a CD line inside the scanned window is not the gate. |
| JNI call trace (`pairip_jni_trace`) | 3,571 records; no non-null `ExceptionOccurred`, no `ExceptionClear`, no `Throw`/`ThrowNew`, and no pending-exception result before the crash. The only exception-like object is the VM's own `java.lang.Exception` (`NewObjectA` at 33.887 and 34.076) used for its `getStackTrace` stack walk; the launcher's spoof hook returns the fabricated 24-frame chain, then the VM calls `Debug.isDebuggerConnected`/`waitingForDebugger` (both false) and reaches the same reader crash. Falsified: `x20` is not a pending exception. |
| JNI `IsSameObject` probe (`pairip_jni_trace` + `IsSameObject`) | 1,053 records; every one of the 529 calls at `+0x702e4` is `0x0 0x0 -> 1`, with no non-null argument and no class name. `[state+0x20]` is null at every observed `0x6ff60` check, so the clear/delete branch is never taken and the `cset eq` flag is 0. Crash, five globals, reader slots, tails, and image diff are unchanged. Falsified: there is no Java object in `x20` to name. |

Fake-CD runs are diagnostic only. Pointing the reader at a real central
directory proves the reader expects ZIP bytes, but it does not make the hosted
path valid and it must not become the fix.

## Open questions

1. Answered (fnv-input, 2026-10-27): the `0xf656` input is the static image
   byte `0x99` at `image+0x24f47`, hashed 419 times. Stock computes the same
   five globals for that input; the input source is not the gate.
2. If the input is static, why does stock not hit the invalid slots: does it
   write/relocate the selected slots, or does the state router take an earlier
   branch and never issue the `0x31` call?
3. Does the reader expect a full APK or a stream positioned at one central
   directory entry? The base and split fake-CD runs both advance past the
   crash, so the current evidence only proves the magic and length
   interpretation.
4. UNVERIFIABLE until the raw fake-CD class trace is restored: the reported
   entry #9 (`0xf0df/0x30`) has no local artifact. Do not treat it as a lead.

The `hash-gate` run answered the "name-hash before the reader" branch
negatively: the `+0x3dc08` block is skipped, so it cannot be the hosted-only
gate. The `state-router-retry` run answered the main state-router hash
negatively too: `+0x32c20` never executes, and the dispatcher sends state
`0x872a21c` straight to the reader at `+0x34d74 b.eq +0x3dc88`. The
`apk-zero-map` run answered the mapping hypothesis negatively: the offset-0
game APK mapping is present and visible to the VM, but the same two slots are
read. The `process-name-spoof` run answered the identity hypothesis
negatively: the VM saw the stock task name and cmdline, yet the same two slots
are read. The `syscall-tail` run answered the native file-read branch
negatively: the VM's syscall wrapper never opens or reads either game APK
before the `0x31` reader, so the ZIP-view literals are image/VM state rather
than the residue of a skipped native read. It also showed that the builtin
ring's `args=` words are shared dispatcher context, so the `0x31` operand
block cannot be read from that ring. The `apk-cd-map` run answered the
maps-window lead negatively: a real `r--s 074f5000` line at byte 3,596,
inside the 146,432-byte prefix, changed nothing. Maps content, uid/gid,
process name, the offset-0 mapping, and now CD-line position are all
independently varied with no effect; that branch is closed and must not be
repeated. The live part of question 2 is now only the `0x31` builtin at
bytecode pc `0x40a73`: what operand block it executes, and whether it
computes the slots itself or expects an earlier writer that the hosted path
skips. It must be recovered statically from the interpreter/bytecode or with
a dedicated probe, not from the builtin ring.

The `jni-x20` run answered the `x20` naming question negatively: the slot is
null at every observed `0x6ff60` check, so there is no Java object to name
and the clear/delete branch is never taken. The live question is now the
opposite: why the null branch is the crashing one, i.e. what should have
written `image+0x3f874`/`image+0x1ba5c`, or what should have made the handler
avoid reading them. It also leaves the `0x1f811` dispatcher entry and the
`0x1f813` builtin entry unordered; that ordering is the next empirical
question.

## Pre-registered experiment (2026-10-27, fnv-input)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The next step is one diagnostic run against the frozen baseline
(`pairip_preload=true`, `pairip_trace=true`, `pairip_dump_vm=true`, debug
launcher). It adds one probe, hooked at `libpairipcore.so+0x7fddc` (the
`add x11, x9, w11, uxtw` immediately before the FNV-1 loop at `+0x7fde0` in the
`0x1b` handler). The probe is compiled in but only active while
`pairip_trace` is set; it adds no extra.

- Hypothesis: the `0xf656/0x1b` hash input is static image data.
- Prediction: every probe call with VM fetch `0xf656` has the computed input
  pointer `x9 + uxtw(x11)` inside `[image, image+0x44feb]`, and the accumulated
  bytes match the pristine `klB` asset at the recorded offsets. At the hook
  `x11` is the pre-`add` offset; the relocated `add` runs in the trampoline
  after the callback. The probe reads the counter as the u32 at
  `image+0x266f2` and dumps when it is `<= 1`.
- Falsification: any call has the computed pointer outside that range, or the
  bytes do not match the asset, or the observed input is not the 419-byte
  image loop. In that case the input is environment-fed and the fork moves to
  reproducing stock maps/status content.
- Attempt 1 (`599cf34`, APK `be31b43e…`): INCONCLUSIVE, probe invalid. One
  filtered call read `counter=0x1a3` (419), `off=0x24f47`, `len=1`; the
  counter was outside the registered `0x100` bound, so nothing accumulated.
  The run then crashed in `__strlen_aarch64+12` (caller frame
  `split_config.arm64_v8a.apk +0x18de8`), not in the baseline
  `__memmove_aarch64_nt+140` / `+0x8d514` path. The shim tail had loaded the
  trampoline through `x16`, but `x16` is live at `+0x7fddc` (loaded at
  `+0x7fdbc`, consumed at `+0x7fe18`), so the hook corrupted the hash
  comparison. `x15` is dead there (the loop at `+0x7fde0` overwrites it), so
  the retry branches through `x15` and accepts a counter up to `0x1000`.
- Attempt 2 (`b3f6dfd`, APK `d8dc1f00…`): CONFIRMED. 419 calls (counter
  `0x1a3` -> 1), all `off=0x24f47`, `len=1`, byte `0x99`, `outside=0`,
  checksum `ba6cb68e62faac68`, plus one counter-0 boundary call with the same
  byte. The pristine asset and the crash image both hold `0x99` at `0x24f47`;
  `0x24f47` is outside all 78 runtime diff ranges. The baseline
  `__memmove_aarch64_nt+140` / `+0x8d514` crash is unchanged.

## Reproduction

Cold launch definition and baseline commands are in
[`pairip-isolation-harness.md`](pairip-isolation-harness.md). Use the debug
launcher, `android install` (not `adb install`), force-stop both packages
before each run, and pass `pairip_delay_ms` with `--el` because `--ez`
truncates it.

```bash
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_dump_vm true \
  --ez pairip_syscall_trace true
```

The VM ring dump waits for `vm ring pos=`; abort runs never print it, so use a
manual `am start` plus a 20-25 s logcat capture for crash runs.

## Next steps

1. Done (fnv-input, 2026-10-27): the `0xf656` input is static image data, so
   the next question is why stock does not hit the invalid slots.
2. Static branch: prove a stock launch computes the same five globals, then
   find the earlier branch stock takes. The state-router hash block at
   `+0x32c20` is ruled out (the dispatcher sends `0x872a21c` directly to the
   reader at `+0x34d74`); decode the `0x31` state selection and the preceding
   state's `next`/`fallback` decision instead.
3. Falsified (apk-zero-map, 2026-10-27): the offset-0 game `base.apk`
   mapping is present and visible to the VM, but the reader slots and crash
   are unchanged. The missing mapping is not the gate.
4. Falsified (apk-cd-map, 2026-10-27): a real `r--s 074f5000` line inside the
   146,432-byte maps scan changes nothing either. The maps-window branch is
   closed; do not run further maps/procfs experiments.
5. Ruled out by the fnv-input result: reproducing stock maps/status content
   for the hash input.
6. Decode the `0x31` builtin semantics and the `0x40a73` operand block. The
   `syscall-tail` run proved the builtin ring's six `args=` words are shared
   dispatcher context (identical for all 32 entries), so the operand block
   must be recovered from the VM frame/bytecode or with a dedicated probe.
   Also decode the preceding state's `next`/`fallback` decision.
7. Only after a real mechanism is proven: apply a gated fix, build, reinstall
   with `android install`, run three cold launches, record results on #62-#64,
   then write the ADR for #60.
8. Before merge: remove the debug experiment switches,
   `pairip_allow_nondebug_experiments`, the Frida gadget/DiagnosticApp/sleeps,
   and the stale `pairip_desc_trace`/`pairip_branch_trace` declarations.
   Include `pairip_apk_cd_map` and the other mapping diagnostics.
9. jni-x20 (2026-10-27): `[state+0x20]` is null at all 529 observed checks,
   so the JNI-object route is closed. Order the final `0x1f811` dispatcher
   entry (`0x6ff60`) against the `0x1f813` builtin entry (`+0x31de4`) with a
   shared sequence counter, then identify which code reads
   `image+0x3f874`/`image+0x1ba5c` and what should have written them.

## Pre-registered experiment (2026-10-27, state-router)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The next step is one diagnostic run against the frozen baseline
(`pairip_preload=true`, `pairip_trace=true`, `pairip_dump_vm=true`, debug
launcher). It tests the main state-router hash, which the `hash-gate` run
originally pointed at as the producer of `sel=0x31bb` (the retry result below
falsifies that route).

- Hypothesis: the comparison at `+0x32c44` (`cmp x8, x22`) and the
  `+0x32c48 csel w8, w24, w25, eq` choose between a pass and a fallback
  selector; the hosted run takes the side that yields `sel=0x31bb` and the
  reader state, while the other side is the non-reader path.
- Prediction: the probe records the table index, table base, loaded entry,
  computed value, expected `0x0cd47edb`, `w24`/`w25`, the `eq` flag, and the
  resulting `w5`; the last transitions before `state 0x872a21c` identify the
  side that produced `0x31bb`. A first-`ne` sample survives even if the last
  64 transitions are all `eq`.
- Falsification: no router transition selects the reader side; the comparison
  is always `eq` with a static table entry and a constant selected candidate
  (a fixed dispatch, not the hosted gate); or the `+0x32c2c`/`+0x32c48` sites
  never execute. In any of those cases the router hash is not the gate and
  the fork moves to the `0x31` operand block at `0x40a73` and the state that
  re-dispatches to `0x872a21c`.

### Retry result (2026-10-27 16:59, `d20fa9e`)

Attempt 1 (`71072b5`) was invalid: hooking `+0x32c2c` and `+0x32c48` crashed in
`malloc(...)` / `split_config.arm64_v8a.apk+0x4c4` before any router callback.
The retry hooks only `+0x32c20`, tails through `x9`, and computes the table
lookup, comparison, and selector offline.

The retry is **FALSIFIED**. The hook installed at `+0x32c20` (`rc=0`,
trampoline non-null), but the ring stayed at `pos=0 eq=0 ne=0`; no `router:`
line appeared. The probe did not perturb the run: the crash is the frozen
baseline `__memmove_aarch64_nt+140` with the same bad reader slots, and the
state ring ends at `id=0x872a21c sel=0x31bb` with `builtin id=0x9a0c0031
pc=0x40a73` immediately before it.

Static confirmation: the dispatcher at `+0x34d68..+0x34d74` compares the state
with `0x872a21c` and branches directly to `+0x3dc88` (the reader builder), so
the route is `state 0x872a21c -> reader`, not `state -> +0x32c20 -> reader`.
The `+0x32c20` block is reached from `+0x3d760`/`+0x3d798` in a different
handler tail and is not on the reader path. The router is not the gate.

## Pre-registered experiment (2026-10-27, apk-zero-map)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The next step is one diagnostic run against the frozen baseline
(`pairip_preload=true`, `pairip_trace=true`, `pairip_dump_vm=true`, debug
launcher) plus a debug-only offset-0 mapping of the game's base APK. The
hosted process currently has no offset-0 game `base.apk` mapping (only
`r--s` mappings at file offsets `0x74ba000` and `0x74f5000`), while the
launcher's own APK is mapped at offset 0. If PairIP's ZIP/central-directory
resolver locates the game APK through `/proc/self/maps` and refuses to
initialize the reader slots without an offset-0 mapping, adding one should
change the slots before the reader runs.

- Hypothesis: the missing offset-0 game base-APK mapping is the hosted-only
  gate; with the mapping present, PairIP's resolver writes the selected
  reader pointer/size slots (`image+0x3f874` / `image+0x1ba5c`) or skips the
  `0x31` reader call.
- Change from the frozen baseline: add `pairip_apk_zero_map=true`, which
  `mmap`s the whole installed base APK `PROT_READ`, `MAP_PRIVATE`, offset 0,
  at hint `0x7900000000` with `MAP_FIXED_NOREPLACE` and a null-hint fallback.
  Everything else stays at `pairip_preload=true`, `pairip_trace=true`,
  `pairip_dump_vm=true`; no fake CD, no seed, no sanitizer.
- Prediction: the native log shows `apk-zero-map: base=... size=122714162`
  and the matching `/proc/self/maps` range before `libmain.so` runs. In the
  crash dump, either the reader slots differ from `0x3f874` / `0x1ba5c`
  (and the reader gets valid ZIP bytes or the path diverges), or the
  `__memmove_aarch64_nt+140` crash disappears. The five shadow globals may
  stay byte-identical because their FNV input is static; that does not by
  itself falsify the mapping gate.
- Falsification: the mapping line is present and the crash still reads the
  same two slots with the same `__memmove` signature and the same state ring
  tail (`id=0x872a21c sel=0x31bb`, `builtin id=0x9a0c0031 pc=0x40a73`). Then
  the offset-0 mapping is not the gate; the next fork is the `0x31` builtin:
  instrument the `0x40a73` operand block to find which resolver-side write
  should have initialized the slots, or prove stock takes an earlier branch.
- Diagnostic only: the offset-0 mapping must not ship. If it changes the
  path, the next step is to find the production-shaped trigger (the platform
  loader/AssetManager mapping stock gets), not to keep an mmap experiment.

### Result (2026-10-27 17:59, `4cd16ed`)

The run is **FALSIFIED**. The mapping installed exactly as planned:
`apk-zero-map: base=0x7900000000 size=122714162 hint=0x7900000000 fixed=1
fallback_errno=0`. The VM's own maps dump contains
`7900000000-7907508000 r--p 00000000 fe:60 942208 .../base.apk` at line 32,
before the two `r--s` game mappings, so PairIP's maps reader could see it.

Nothing downstream changed. The FNV input is still 419 calls of byte `0x99`
at `image+0x24f47` (checksum `ba6cb68e62faac68`), the hash and router rings are
still empty, the state ring still ends at `id=0x872a21c sel=0x31bb
reader=0xf93727d8`, and the builtin ring still ends at `id=0x9a0c0031
pc=0x40a73`. The crash is the same `__memmove_aarch64_nt+140` (`+0x8d514`)
with the same reader slots `bbb9eb6daed20ed0` / `015370f87525d57b`.

Artifacts: log `tmp/cifi-apk/logs/verify/apk-zero-map.log` (sha256
`81ebb14cb2d2dbff2375048c66a5012e162db3c28d13a40c682eaf30eecdd014`),
crash image `tmp/cifi-apk/vmrun/klB-image-zero-map.bin` (sha256
`92958cc0804ac7be0a9e53f62446f506e1dcc3d26dbf2360b728eca9b266b513`,
368 changed bytes in 79 ranges), and maps dump
`tmp/cifi-apk/vmrun/klB-maps-zero-map.txt` (sha256
`515c939aa55bce3b53cea8b87eccf7babc898620ca622cffc850c933c8b942f9`).
The crash image differs from the retry image by only 50 bytes in 29 ranges,
all ASLR pointers plus the known `image+0xae3` counter. The offset-0 mapping
had no behavioral effect.

## Pre-registered experiment (2026-10-27, process-name-spoof)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The `0x7e` handler reads `/proc/self/status` and `/proc/self/cmdline` before
writing the five globals. The hosted `:game` process reports
`com.bepinex.android.debug:game` (task name `roid.debug:game`); a stock launch
reports `com.OctocubeGamesCompany.CIFI`. The maps/uid/gid and offset-0 APK
differences are already falsified, so the process identity is the remaining
environmental tell not yet exercised.

- Hypothesis: PairIP takes the decoy/integrity branch because the process
  name is the launcher's. Spoofing argv0 and the task name to the game before
  the first VM restores the stock branch.
- Change from the frozen baseline: add `pairip_process_name=true`. The
  launcher rewrites argv0 in place to the 29-byte game package and sets the
  task name to its last 15 characters, `mesCompany.CIFI`. Everything else is
  unchanged.
- Prediction: the spoof succeeds, the VM's status sample shows
  `Name:\tmesCompany.CIFI`, and either the reader slots differ from
  `0xbbb9eb6daed20ed0` / `0x015370f87525d57b` or the crash/path diverges
  before the reader.
- Falsification: the spoof succeeds and is visible to the VM, but the crash
  signature, reader slots, five globals, FNV input, state tail, and builtin
  tail are byte-identical to baseline.
- Invalid: the spoof fails or the VM still sees the launcher name.
- Diagnostic only: the spoof must not ship; if it changes the path, the
  production fix has to reproduce the stock launch shape, not rename the
  hosted process.

### Result (2026-10-27 18:26, `defa262`)

The run is **FALSIFIED**. `prctl(PR_SET_NAME)` succeeded on the main
thread and the log recorded `process name spoofed: comm=mesCompany.CIFI` at
18:26:08.941, before `libpairipcore.so` loaded at 18:26:10.071. AOSP
`AndroidRuntime::setArgv0` writes the name into the argv0 block and calls
`setprogname` on the same block, so `/proc/self/cmdline` and
`/proc/self/status` `Name:` both changed; the VM's `0x7e` handler opens
`/proc/self/status`, so it saw the game name rather than the launcher name.
No status sample was re-dumped because `pairip_syscall_trace` was off, but
the identity change is guaranteed by the successful `prctl` on the main
thread and the kernel's comm semantics.

Nothing downstream changed. The FNV input is still 419 calls of byte `0x99`
at `image+0x24f47` (checksum `ba6cb68e62faac68`), the five globals still
reach `0x534a6211` / `0xe29e9c3b` / `0x8a1aeb2d` / `0xbfe458cb` /
`0x4d062af3`, and the state and builtin rings still end at
`id=0x872a21c sel=0x31bb` and `id=0x9a0c0031 pc=0x40a73`. The crash is the
same `__memmove_aarch64_nt+140` with the same reader slots
`bbb9eb6daed20ed0` / `015370f87525d57b`.

Artifacts: log `tmp/cifi-apk/logs/verify/process-name-spoof.log` (sha256
`f4916b3246da0545514f1988abfa212b2957af9fe3d0a4d091d02e11268ec45d`),
crash image `tmp/cifi-apk/vmrun/klB-image-at-crash-pns.bin` (sha256
`a4db6ad23e750685d31c33c308ac34ae9b16b98544fdb16636ae92165e391158`,
368 changed bytes in 79 ranges versus the pristine asset), and maps dump
`tmp/cifi-apk/vmrun/klB-maps-process-name-spoof.txt` (sha256
`a807ebc4e502032db8a1f4f1ea1bc8851e335783731f288982c68ab811e65dd9`).
The crash image differs from the zero-map image by 50 bytes in 29 ranges,
ASLR pointers plus the known `image+0xae3` counter. The process name had no
behavioral effect.

This closes the environment-input branch. Maps content, uid/gid, process
name and the offset-0 APK mapping are all independently changed and none
feeds the five globals. Stock computes the same five globals, so the hosted
difference must be the branch or state before the `0x31` reader. Do not
repeat maps/status/name experiments; decode the `0x31` operand block and the
preceding state's `next`/`fallback` decision instead.

## Builtin id provenance (static follow-up, 2026-10-27 19:20)

No device run in this step. The goal was to resolve the `[sp+0x44]` /
`0x9a0c0031` conflict left by the process-name run.

- `+0x6adb4` is the only call site to `+0x31de4` in the trace: `ldr w1,
  [sp, #0x44]`, `add x0, sp, #0x78`, `add x2, sp, #0x74`, `bl +0x31de4`;
  the return address is `+0x6adc4`. All 128 text-logged calls and all 32
  ring entries report `lr=+0x6adc4` and target `+0x31de4`. The trace does
  not hook `+0x5ef50`, so it cannot say whether the `+0x6add8` path ran.
- `+0x31de4` never uses the high 16 bits of `w1`: the entry masks are
  `and w8, w1, #0xffff` at `+0x31e04` and `tst w1, #0xfffc` at `+0x31eb0`.
  The original `x1` is not spilled, so the high half is gone once `w1` is
  reused. The high half is not used for dispatch. Every one of the 128
  logged ids has `id & 0xffff` in `0x04..0xb5`, and the high halves form a
  small set of block tags (`0x0`, `0x34e1`, `0x52d8`, `0x40b0`, `0x40c`,
  `0x1124`, `0x9a0c`). `0x9a0c0031` therefore means opcode `0x31`; the
  `0x9a0c` tag is inert at the builtin entry.
- Frame provenance: the builtin argument block is `0x6a968.sp + 0x78`.
  The VM dump places that block at dump offset `0x6e0`, so
  `0x6a968.sp = arg - 0x78` and `[0x6a968.sp + 0x44]` is at dump offset
  `0x6ac` (`dump_sp + 0x6ac`). The crash dump's `0x680` row holds the
  64-bit value `0x0000007aa3e30db4` at `+0x6a8`; its high half is `0x7a`,
  so the 32-bit slot at `+0x6ac` is `0x7a` at crash time. The entry value
  was `0x9a0c0031`, so the slot is rewritten after entry while the reader
  is being built. The crash-time value is not the value passed at entry.
- The only static store to `[sp+0x44]` in `+0x6a968` is `+0x6ad70`,
  `((w9 ^ 0x7f45) % 0xba)`, which produces `0..0xb9`. That is consistent
  with the low 16 bits of every observed id but not with the tag. The tag
  is written by the interpreter/runtime path, not by `+0x6ad70`; the exact
  site is still unidentified, but it is lower priority because `+0x31de4`
  ignores it.

This closes the "full 32-bit builtin id" question for the reader path: the
effective dispatch key is the low half, and it is the selector range the
state machine computes. It does not settle the five globals. The FNV input
is static, but the final values are written by the `0x7e` handler at fetch
`0x1f811`; no stock-side capture exists. "Stock computes the same five
globals" remains an inference, not a direct stock observation. If stock's
globals differ, the reader pair differs; if they are the same, stock must
patch the selected image slots or take a different branch. The next
diagnostic should discriminate those cases rather than continue chasing the
tag.

## Pre-registered experiment (2026-10-27, syscall-tail)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The 18:26 process-name-spoof run did reach the crash ring dump (its
`klB-image-at-crash.bin` and maps were recovered from the device), but the
logcat buffer rolled over before capture. The `args=` words of the `0x31`
entry and the crash-time syscall tail therefore exist in no surviving log, and
no run under this key has combined `pairip_dump_vm` with
`pairip_syscall_trace`. The next run is the frozen baseline plus that one
instrumentation toggle.

- Hypothesis: `libpairipcore.so`'s raw syscall wrapper never opens the game
  `base.apk` or `split_config.arm64_v8a.apk` before the `0x31` reader, so the
  unresolved ZIP-view literals are not the residue of a skipped native file
  read in this process.
- Prediction: the crash-time builtin ring ends with `id=0x9a0c0031
  pc=0x40a73` and records six `args=` words; the syscall tail's file opens are
  `/proc/self/status` and `/proc/self/maps` only, with no `path=` ending in
  `.apk`; the crash and reader slots stay at the frozen values.
- Falsification: any `openat` (`n=56`) whose recorded path is either game APK
  before the reader. A changed crash signature or reader-slot pair makes the
  run inconclusive because the added trace perturbed the path.
- Scope: the ring sees only syscalls issued through the VM's wrapper, not
  Java-side `VMRunner`/`libart` reads.

The `args=` words will be compared offline with the reader handler's operand
block at `[sp+0x538]` and the five-global emulation. If the block points into
the image, the next step is to decode that operand statically; if it points at
a heap ZIP view, the next step is to find its writer.

## syscall-tail result (2026-10-27 21:50)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen baseline plus `pairip_syscall_trace=true` ran one cold launch to the
same crash. The pre-registered no-native-file-read hypothesis is **PROVEN**
for the native wrapper.

- Syscall ring: `pos=181`, all dumped. Seven `openat` calls (four
  `/proc/self/maps`, three `/proc/self/status`), six `close`, two `fstat`, two
  `fstatfs`, one `readlinkat(/proc/self/fd/149)`, 146 `read` calls (three
  status, 143 maps), 16 `clock_gettime`, one `getpid`. No `.apk` path and no
  read from an APK descriptor.
- `ExecuteProgram`: `klB1wZSojiVvDvCN` is called with `argc=1`; `arg[0]` is
  `java.lang.Long=0x79fa071000`. That address is the first split-APK mapping
  (`r--p 04eec000`), and `libunity.so`'s entry data starts at file offset
  `0x4eec000` with first `LOAD` `p_offset=0`, `p_vaddr=0`. It is libunity's
  ELF load base, not an APK base or a ZIP view.
- Builtin ring: `pos=11260`, last 32 entries dumped. Every entry has the same
  `arg`, `out`, `img`, `len`, and six `args=` words; only `pc` and `id` vary.
  The final entry is `id=0x9a0c0031 pc=0x40a73`, preceded by `0x0c`, `0x36`,
  `0x09`, `0x2d`, `0x5e`. The shared block is dispatcher context, so the ring
  does not expose the `0x31` operand block.
- Crash and image: unchanged from baseline. The reader slots are still
  `bbb9eb6daed20ed0` / `015370f87525d57b`, the state tail is still
  `id=0x872a21c sel=0x31bb`, the five globals are still `0x534a6211`,
  `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`, `0x4d062af3`, and the image diff
  is 368 bytes in 79 ranges with both ZIP slots unchanged.

### Maps-window lead

The maps reader consumes exactly 143 full 1,024-byte chunks (146,432 bytes,
`0x23c00`) and then stops mid-line; the maps file at crash time is 426,335
bytes, so this is not EOF. The base APK's CD mapping (`r--s 074f5000`) starts
at byte 316,554 (line 3263), and the split APK's CD mapping
(`r--s 06627000`) starts at byte 366,461 (line 3787); both lie outside the
scanned prefix.

This is a lead, not a proven gate. `proc-sanitize`, `apk-zero-map` and
`process-name-spoof` changed maps content, mapping presence and process
identity but never moved a real CD line into the scanned window, so they do not
test this mechanism. On the other hand, the five globals did not change under
those content changes, so the `0x6d` parser may not consume the CD line at all.
A causal test would have to place a real CD mapping (base offset `0x74f5000`
or split offset `0x6627000`) at an address whose maps line falls inside the
146,432-byte prefix, and predict a reader-slot change. No such run is
pre-registered; do not treat the window as causal until it is.

### Consequence

The file-backed native-resolution branch is ruled out. The live target is the
`0x31` operand block and the state that re-dispatches to it, not another file
or maps input experiment.

## Pre-registered experiment (2026-10-27, apk-cd-map)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The syscall-tail run narrowed the maps lead: klB reads 143 full 1 KB chunks
(146,432 bytes) and stops mid-line, while the real base-APK CD mapping
(`r--s 074f5000`) starts at byte 316,554 and the split CD at byte 366,461.
The earlier offset-0 experiment did not test this window because it exposed
`r--p 00000000`, not a CD-offset line. This run maps the base APK's real CD at
`0x7900000000` with `MAP_SHARED`, inside the scanned prefix, and adds no other
variable.

- Hypothesis: if the `0x6d` maps parser uses the `r--s 074f5000` line (offset
  or shared flag) to locate the base APK central directory, the duplicate line
  will change the five globals, the reader slots, or the crash.
- Prediction: `apk-cd-map: base=0x7900000000 size=0x13000 hint=0x7900000000
  fixed=1 offset=0x74f5000 eocd=0x750781c`, with an `r--s 074f5000` line at
  `0x7900000000` inside the first 146,432 bytes. A change in the five globals,
  the `bbb9…`/`0153…` reader slots, or the `__memmove_aarch64_nt+140` crash is
  a positive result.
- Falsification: the line is inside the prefix, but the five globals, reader
  slots, state/builtin tails, crash signature, and image diff are byte-
  identical to baseline. Then the maps window is not the gate.
- Invalid: mapping error, missing or out-of-prefix line, or an unrelated
  perturbation. The low mapping is diagnostic only and must not ship.
- Scope: this makes the CD line visible; it does not prove the parser consumes
  the CD bytes or that stock's maps order matches. A negative closes the
  window lead; it does not reopen maps-content/identity experiments.

## apk-cd-map result (2026-10-27 22:10)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen baseline plus `pairip_apk_cd_map=true` ran one cold launch to the
same crash. The pre-registered maps-window hypothesis is **FALSIFIED**.

- The base APK's real central directory was mapped at `0x7900000000`
  (`MAP_SHARED`, file offset `0x74f5000`, length `0x13000`), and the VM's own
  maps dump contains the line
  `7900000000-7900013000 r--s 074f5000 fe:60 942208 ... base.apk` at byte
  3,596 (line 32), inside the 146,432-byte prefix the VM scans.
- Every observable is unchanged: the five globals are `0x534a6211`,
  `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`, `0x4d062af3`; the reader slots are
  `bbb9eb6daed20ed0` / `015370f87525d57b`; the state tail is
  `id=0x872a21c sel=0x31bb`; the builtin tail is `id=0x9a0c0031 pc=0x40a73`;
  the crash is `__memmove_aarch64_nt+140` with the
  `split_config.arm64_v8a.apk +0x514` frame; and the image diff is 368 bytes
  in 79 ranges with both ZIP slots unchanged.
- Consequence: the `0x6d` maps parser does not locate the APK by an
  `r--s 074f5000` line inside its read window, and the five globals do not
  depend on that line. The maps/content/identity branch is fully closed:
  maps content, uid/gid, process name, offset-0 mapping, and CD-line position
  have each been changed independently without effect.
- The live target is only the `0x31` operand block at bytecode pc `0x40a73`
  and the preceding state's `next`/`fallback` decision. The
  `pairip_apk_cd_map` switch is diagnostic only and must be removed before
  merge.

## Pre-registered experiment (2026-10-27, jni-trace)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The `0x31` operand block and the preceding `0x7e` flag are now the only live
targets. The `0x7e` handler loads `x20` from `[state+0x20]` and folds
`IsSameObject(x20, NULL) == 0` into the flag it stores at `0x263cd`/`0x2904f`.
The hosted run writes `1` at both stores, i.e. `x20` is a non-null Java
object. This run uses the existing debug-only JNI tracer, which patches the
JNIEnv table in place and logs every call whose immediate caller is inside
`libpairipcore.so`, including `ExceptionOccurred`, `ExceptionClear`, `Throw`,
`ThrowNew`, and the throwable text of `Call*` results. The tracer is installed
after the startup program (YTw) and before `libmain`/klB, so the klB window is
covered. It does not hook `IsSameObject`; the `Exception*` hooks are the direct
probe for whether `x20` is a pending exception.

- Hypothesis: the non-null `x20` folded into the `0x7e` flag is a
  Java/environment-derived object (most likely a pending exception) created
  by a klB JNI call, and the tracer will identify the class, method, and
  throwable text that produced it.
- Prediction: immediately before the `vminsn[1579] fetch=0x1f811 op=0x7e` /
  `builtin[11234] id=0x9a0c0031 pc=0x40a73` tail, the log contains a
  `PairIpJni` record from `libpairipcore.so` with either
  `ExceptionOccurred -> 0x...` (non-zero), `ExceptionClear: <class>:
  <message>`, or `Call* ... -> <pending exception>` plus throwable text. The
  class/message names the hosted-side failure. The crash, the
  `bbb9…`/`0153…` reader slots, the five globals, and the state/builtin tails
  remain byte-identical to the frozen baseline.
- Falsification: no non-null `ExceptionOccurred` and no pending-exception
  result from `libpairipcore` before the crash, or no `PairIpJni` records at
  all despite `installed=true`; then `x20` is not an exception (or the trace
  cannot see its origin) and the JNI-exception lead is negative. A changed
  crash signature, reader slots, five globals, or tails makes the run
  inconclusive because the tracer perturbed the path.
- Scope: the tracer sees JNI calls and results, not the VM's internal store
  to `[state+0x20]`. A non-null exception is evidence that the hosted VM
  observed a Java failure; it does not prove stock sees a null `x20`, so a
  positive still needs the class/message to be a hosted-only condition.
- Diagnostic only: `pairip_jni_trace` must be removed before merge.

## jni-trace result (2026-10-27 23:17)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The frozen baseline plus `pairip_jni_trace=true` ran one cold launch
(`LaunchState: COLD`, `TotalTime: 419` ms). The tracer patched 35 JNIEnv slots
at 23:17:31.972, before `libmain`/klB, and logged 3,571 `PairIpJni` records.
The log is `tmp/cifi-apk/logs/verify/jni-trace.log` (22,386 lines, sha256
`9b95ca6a677cdc3b1adf14f849282d05f70883fc7f9288b0d6593439e31bac9e`).

The pre-registered pending-exception prediction is **FALSIFIED**. There is
exactly one `ExceptionOccurred` record, and it returned `0x0` on thread 25201
(the Play Integrity `LOG_TELEMETRY` path). There is no `ExceptionClear`, no
`Throw`/`ThrowNew`, and no `Call* -> <pending exception>` result from
`libpairipcore` anywhere before the crash. `x20` is therefore not a pending
exception visible to the JNI tracer.

The trace did expose the VM's own stack walk. On the main thread, twice
(33.887 and 34.076), PairIP calls `FindClass("java/lang/Exception")`,
`NewObjectA java.lang.Exception`, and `CallObjectMethodA
java.lang.Exception.getStackTrace`. Each result triggers
`PairIP: stack trace spoofed for executeVM (nativeLoader=true)`: the
launcher's `Throwable.getStackTrace` hook replaces the real chain with the
fabricated 24-frame `pairIpUnityLaunchStack`. The tracer's `DumpStackTrace`
frames are that fabricated chain, not a real VM anti-debug walk. After the
second walk the VM calls `Debug.isDebuggerConnected()` and
`Debug.waitingForDebugger()`; both return `false`, and the run then reaches
the same reader/crash path.

The crash, the five globals, and both reader slots are unchanged: signal 11
in `__memmove_aarch64_nt+140` with the `split_config.arm64_v8a.apk +0x514`
frame; `bbb9eb6daed20ed0` / `015370f87525d57b` at `vm[0x540]`; state tail
`id=0x872a21c sel=0x31bb`; builtin tail `id=0x9a0c0031 pc=0x40a73`; fetch
`0x1f811 op=0x7e`. The state/builtin ring positions (`41366`/`3699`) are lower
than the baseline (`130460`/`11234`) because the tracer slowed the loop; the
tails and every compared byte are unchanged. The crash image is
`tmp/cifi-apk/logs/verify/jni-trace-image.bin` (sha256
`c952fdaa414120d7d8dd6561301a57e9e38e47d9300512949e97cb3984865d6d`); diff vs
the prior crash image is 34 ranges / 76 bytes (ASLR/heap/counters), and vs
the pristine asset 79 ranges / 368 bytes.

The remaining question is what object the final `0x7e` handler reads from
`[state+0x20]`. The JNI trace proves it is not a pending exception, but it
cannot see `IsSameObject`, `NewLocalRef`, or `DeleteLocalRef`, and it does not
print raw object pointers for every result. The only exception-like object in
the window is the VM's own `java.lang.Exception`; the next diagnostic must
hook `IsSameObject` and log the raw object/class (and, if safe, the class
name) at the handler's call site.

`pairip_jni_trace` is diagnostic only and must be removed before merge.

## Pre-registered experiment (2026-10-27, jni-x20)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The `jni-trace` run closed the pending-exception mechanism but left the
actual argument at `[state+0x20]` unnamed. The `0x7e` handler calls
`IsSameObject(x20, NULL)` at `libpairipcore+0x702e0` (return address
`+0x702e4`), and the result feeds the flag at `+0x7044c`. The handler also
clears the slot and deletes the local reference when `x20` is non-null. This
run adds a debug-only `IsSameObject` patch to the existing JNI tracer and logs
the raw object, the second argument, the result, and the class name only at
the `+0x702e4` call site. The class lookup is deliberately limited to that
site so unrelated VM comparisons do not acquire avoidable JNI traffic.

- Hypothesis: the slot holds one of the Java objects the VM creates or
  receives in the preceding window. The leading candidates are the VM's own
  `java.lang.Exception` (`NewObjectA` at 33.887 and 34.076) and its
  `getStackTrace` result (`[Ljava.lang.StackTraceElement;`); the `Debug`
  class, a `StackTraceElement`, a `String`, or a `Long` are alternatives.
- Prediction: the tracer reports `jni: patched 36 slots`, and before the
  crash at least one `IsSameObject` record with `caller=+0x702e4` has
  `obj2=0x0`, `-> 0`, and a concrete `class=...`. The crash, reader slots,
  five globals, state/builtin tails, and image diff remain at the frozen
  values; the diff vs the prior crash image stays ASLR/heap/counter-only.
- Falsification: no `IsSameObject` record with `caller=+0x702e4` before the
  crash, or the non-null object cannot be named (`class=(pending)` or
  `class=object`). Then the probe is invalid or the slot is not a Java object
  the tracer can name, and this route to identifying `x20` is closed. A
  changed crash signature, reader slots, five globals, or tails makes the run
  inconclusive.
- Scope: naming the class identifies the source object but does not prove
  that stock's slot is null or different. A `java.lang.Exception` result
  would redirect the investigation to the stack-spoof/exception-slot path; it
  would not by itself explain the hosted-only gate.
- Diagnostic only: the new `IsSameObject` logging must be removed with
  `pairip_jni_trace` before merge.

## jni-x20 result (2026-10-27 23:27)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The frozen baseline plus `pairip_jni_trace=true` (with the new
`IsSameObject` hook) ran one cold launch: `Status: ok`, `LaunchState: COLD`,
`TotalTime: 244` ms. The APK sha256 is
`0cf2353393eeb6a2aaed311d5b19e0311c08e9d76dba521def91463298661b92`
(115,274,306 bytes, 488 entries); only `lib/arm64-v8a/libpairipshield.so`
differs from the installed baseline. The tracer patched 36 slots at
23:26:58.112, before `libmain` (23:26:58.113), and logged 1,053
`IsSameObject` records. The log is `tmp/cifi-apk/logs/verify/jni-x20.log`
(14,005 lines, sha256
`00987a9039a3e6bd19ec7ac928980d31b0d762708e25cfd04a4dbdc5a027e3e0`).

The pre-registered prediction is **FALSIFIED**. All 529 records at
`caller=+0x702e4` are `IsSameObject 0x0 0x0 -> 1`; there is no non-null first
argument and no `class=` line at that site. `[state+0x20]` is therefore null
at every observed `0x6ff60` check, the `b.ne` at `+0x702e8` is taken every
time (the clear/delete branch at `+0x702ec` is skipped), and the `cset eq` at
`+0x70448` evaluates to 0. The other call sites are unrelated: 468 at
`+0x76cf0`, 52 at `+0x7aa80`, 3 at `+0x71eb8`, 1 at `+0x79e84`.

The crash and every compared value are unchanged. The signal is still 11 in
`__memmove_aarch64_nt+140` with the `split_config.arm64_v8a.apk +0x514`
frame; `vm[0x540]` still holds `bbb9eb6daed20ed0` / `015370f87525d57b`; the
five globals are `0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`,
`0x4d062af3`; the state tail is `id=0x872a21c sel=0x31bb`; the builtin tail
is `id=0x9a0c0031 pc=0x40a73`; the final VM fetch is
`vminsn[1579] fetch=0x1f811 op=0x7e`. Ring positions are state 44,258,
builtin 3,944, image 2,211, and VM 1,580. The crash image is
`/tmp/opencode/jni-x20-image.bin` (sha256
`d474671d048b9fb877ec4f19e6d99bd6284f4e74cd346b250b9b12f9293a2cdd`); the
diff vs the `jni-trace` image is 30 ranges / 53 bytes, vs `apk-cd-map` 35 /
81, and vs the pristine asset 79 / 368. The five globals and both reader
slots are byte-identical in all three crash images.

Static follow-up for the final `0x7e`: the dispatcher table at `+0x103ec`
maps `(w1 & 0xffff) - 0xd = 0x71` (opcode `0x7e`) to `0x6ff60`. The builtin
ring records `builtin[3917] id=0x40b0007e pc=0x1f813 lr=+0x6adc4`, followed
by 26 more entries ending at `builtin[3943] id=0x9a0c0031 pc=0x40a73`. The
only direct `bl +0x31de4` in the library is at `0x6adc0` (return
`+0x6adc4`); `0x6ff60` has no direct call to `+0x31de4`. The final
dispatcher fetch therefore enters `0x6ff60`, and the builtin `0x7e` at
`0x1f813` is a nested or preceding helper event with the same opcode and the
next pc. Their order is not yet proven.

Consequence: the "name the Java object in `x20`" route is closed; there is
no object. The last observed `0x6ff60` null check is at 23:26:59.057, in the
same millisecond as the append dump that precedes the crash, and it is null.
The image-backed string view is read after that check. The open question is
now which code writes the two image slots (or why the null branch is
supposed to be safe in a bare launch), and whether the `0x1f813` builtin
event runs before or inside the `0x6ff60` handler.

`pairip_jni_trace` and the `IsSameObject` hook are diagnostic only and must
be removed before merge.
