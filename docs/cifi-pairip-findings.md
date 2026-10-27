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
`0x872a21c` straight to the reader at `+0x34d74 b.eq +0x3dc88`. The live part
of question 2 is now the `0x31` builtin at bytecode pc `0x40a73`: what operand
block it executes and which state re-dispatches to `0x872a21c`.

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
3. Live diagnostic (apk-zero-map): the hosted process has no offset-0 mapping
   of the game's `base.apk`, only `r--s` mappings at file offsets `0x74ba000`
   and `0x74f5000`. Map the whole base APK at offset 0 before `libmain` and
   test whether PairIP's mapping resolver then initializes the reader slots.
4. Ruled out by the fnv-input result: reproducing stock maps/status content
   for the hash input.
5. Decode the `0x31` builtin semantics and the `0x40a73` operand block.
6. Only after a real mechanism is proven: apply a gated fix, build, reinstall
   with `android install`, run three cold launches, record results on #62-#64,
   then write the ADR for #60.
7. Before merge: remove the debug experiment switches,
   `pairip_allow_nondebug_experiments`, the Frida gadget/DiagnosticApp/sleeps,
   and the stale `pairip_desc_trace`/`pairip_branch_trace` declarations.

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
