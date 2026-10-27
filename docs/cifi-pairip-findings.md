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
  ranges, none covering either slot.
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

## Experiments and results

| Experiment | Result |
| --- | --- |
| Write attribution / image diff | Both ZIP slots stay at their asset values; no writer found. |
| Fake central directory from base APK (`0x1281c` bytes) | SIGSEGV disappears, run continues to a CheckJNI `FindClass` SIGABRT. |
| Fake central directory from arm64 split (`0x30a` bytes) | Same behaviour as the base-APK run. |
| VM-pc-tagged class trace | Entry #9: `caller=+0x7295c`, `vm=0xf0df/0x30`, `idx=0x19ded`, `len=12928`. |
| Procfs trace | klB reads `/proc/self/maps` in 1 KB chunks through `0x23800` (141-142 reads), then `/proc/self/status`, `readlink` of the maps fd, six `clock_gettime`, then the failing read. |
| Served maps snapshot | Contains launcher-only `.so` paths (`libcoreclr.so`, `libfusion.so`, `libmscordacore.so`, ...); game libraries appear as split-APK mappings. |
| `memcmp` needle trace | Closed: the captured comparisons are the shield logger's own tag checks, not a klB maps needle. |
| Five-global emulation | Reproduces `0x3f874`/`0x1ba5c` from the crash image and `0x31c1b`/`0x3b302` from the pristine asset. |
| Cross-run global comparison | The five globals reach byte-identical values in three hosted runs while ASLR pointers differ. |
| Image-watch (read-only first page) | One u32 increment at `image+0xae3` from `+0x5f714`; no write to either ZIP slot. |
| fnv-input probe (`+0x7fddc`) | 419 calls, `len=1`, all at `image+0x24f47`, byte `0x99`, checksum `ba6cb68e62faac68`; pristine asset and crash image match at that offset; baseline crash unchanged. The input is static image data. |

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
4. Is class-trace #9 (`0xf0df/0x30`) a fake-CD artifact or a genuine decoy path
   that stock also takes?

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
   find the earlier branch stock takes. Decode the `0x31` state selection and
   the preceding state's `next`/`fallback` decision.
3. Ruled out by the fnv-input result: reproducing stock maps/status content
   for the hash input.
4. Decode the `0x31` builtin semantics and the `0x40a73` operand block.
5. Only after a real mechanism is proven: apply a gated fix, build, reinstall
   with `android install`, run three cold launches, record results on #62-#64,
   then write the ADR for #60.
6. Before merge: remove the debug experiment switches,
   `pairip_allow_nondebug_experiments`, the Frida gadget/DiagnosticApp/sleeps,
   and the stale `pairip_desc_trace`/`pairip_branch_trace` declarations.
