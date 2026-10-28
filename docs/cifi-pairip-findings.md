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
crash path. The earlier statement that `sel=0x31bb` is produced by the main
state-router hash at `+0x32c20..+0x32c54` is **withdrawn**: both the
`+0x32c20` router hook and the `+0x383f4`/`+0x38420` selector-gate hooks
recorded zero hits. The state ring is now known to be a **global** ring: it
interleaves dispatches from nested interpreter instances, so adjacent ring
entries are not necessarily a transition. In particular, the run log's
`0x7ae3f9a6` entry at index 39148 (`reader=0x1`) is the inner interpreter's
termination return; the following `0x81398368` entry (`sel=0x31bb`) is the
outer interpreter resuming with `w5` already holding the residue. State
`0x7ae3f9a6` is therefore **not** the predecessor of `0x81398368`, and the
earlier "entered from `0x7ae3f9a6`" wording is withdrawn. `sel=0x31bb` is
first *observed* at `0x81398368` and is then carried unchanged through
`0x5ddd74f5`, `0x65faffff`, `0xd72aa61e`, `0x692efcd5`, `0x1e35ad97`,
`0xeb7a0bec`, `0x629d5907` into `0x872a21c`, but it is a caller register,
not a dispatcher-selected state. See the static map below.

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
| selector-gate (`+0x383f4`, `+0x38420`) | Both hooks `rc=0` (trampolines `0x7e1e46d250`, `0x7e1e46d26c`), gate ring `pos=0 eq=0 ne=0`: the string-hash selector block never executes before the crash. State tail, five globals, reader slots, and crash are unchanged. The gate is not the producer of `sel=0x31bb`; route closed. |
| Offset-0 base-APK mapping (`pairip_apk_zero_map`) | Mapping installed at the planned address (`0x7900000000`, `fixed=1`) and appears in klB's maps dump (`r--p 00000000`), yet the crash, reader slots, FNV input, state tail, and builtin tail are unchanged. Falsified: the missing offset-0 mapping is not the gate. |
| Process-name spoof (`pairip_process_name`) | Main-thread `prctl` succeeded and the VM's `/proc/self/status` read saw `mesCompany.CIFI` (argv0/`__progname` were rewritten too), yet the FNV input, five globals, reader slots, state/builtin tails, crash signature, and image diff are byte-identical to baseline. Falsified: process identity is not the gate. |
| Identity conjunction (`pairip_process_name` + `pairip_proc_sanitize` + `pairip_apk_zero_map`) | All three facts were active: status showed `Name:\tmesCompany.CIFI` and game uid, the sanitized maps had zero `bepinex` lines (59 in the raw copy), and the game base APK was at offset 0 on line 32. Reader slots (`bbb9...`/`0153...`), append fields, `vm[0x540]`, state tail, crash signature, and image invariants were unchanged. Falsified: the stock identity conjunction is not the gate. |
| Stock-bound context (`pairip_stock_bound` with `lateHooks`) | PairIP's own `InitContextProvider` ran (`ContextImpl.createAppContext` hidden-API grant from `com/pairip/InitContextProvider`) and built the app context from the game LoadedApk, instead of the seeded package context. Final state `0x872a21c sel=0x31bb`, reader slots (`bbb9...`/`0153...`), append fields, `vm[0x540]`, builtin tail, crash signature, and both ZIP slots were unchanged; the 45-byte image diff vs identity is entirely inside the known 368-byte variable set. Falsified: the context object type is not the gate. |
| Game Application (`pairip_game_application`) | `LoadedApk.makeApplication` created `com.pairip.application.Application`; `VMRunner.context` was set to it, `SignatureCheck` passed, and `LicenseClient` attempted its check. The licensing bind was rejected because the real Binder UID is the launcher's (10616), not the game's (10610). Final state `0x872a21c sel=0x31bb`, reader slots (`bbb9...`/`0153...`), append fields, `vm[0x540]`, builtin tail, crash signature, and both ZIP slots were unchanged; the 48-byte/29-range image diff vs identity is inside the known 368-byte variable set with zero new-only offsets. Falsified: the Application lifecycle is not the gate. The license bind failure remains an unmeasured hosted-only condition. |
| License-state consumption (`pairip_game_application` + `pairip_jni_trace`) | 4,676 JNI records covering klB through the reader crash: zero `LicenseClient`/`LicenseCheckState` names, zero `GetStaticFieldID`/`GetStaticObjectField`/`GetStaticBooleanField`/`GetFieldID` records, and the same six `FindClass` names (`Object`, `File`, `Long`, `Exception`, `StackTraceElement`, `Debug`). The only Application-object use is the Play Integrity `LOG_TELEMETRY` `sendBroadcast`; there is no license query. Reader slots, append, state tail, builtin tail, and crash are unchanged. Ruled out: klB does not consume license state; the hosted-only bind failure is not an input to the crashing VM. |
| syscall-tail (`pairip_syscall_trace`) | 181 raw syscalls, all file paths procfs; no game-APK `openat`/`read` before the `0x31` reader. The maps scan is 143 full 1 KB reads (146,432 bytes) and stops before both real CD mappings. All 32 builtin-ring entries carry the same `args=` words, so the ring exposes shared dispatcher context, not per-builtin operands. PROVEN for the no-native-file-read hypothesis. |
| Low CD mapping (`pairip_apk_cd_map`) | The base APK's real CD was mapped at `0x7900000000` (`MAP_SHARED`, offset `0x74f5000`), and its `r--s 074f5000` line appeared at byte 3,596, inside the VM's 146,432-byte maps scan. The five globals, reader slots, state/builtin tails, crash signature, and 368-byte/79-range image diff are unchanged. Falsified: a CD line inside the scanned window is not the gate. |
| JNI call trace (`pairip_jni_trace`) | 3,571 records; no non-null `ExceptionOccurred`, no `ExceptionClear`, no `Throw`/`ThrowNew`, and no pending-exception result before the crash. The only exception-like object is the VM's own `java.lang.Exception` (`NewObjectA` at 33.887 and 34.076) used for its `getStackTrace` stack walk; the launcher's spoof hook returns the fabricated 24-frame chain, then the VM calls `Debug.isDebuggerConnected`/`waitingForDebugger` (both false) and reaches the same reader crash. Falsified: `x20` is not a pending exception. |
| JNI `IsSameObject` probe (`pairip_jni_trace` + `IsSameObject`) | 1,053 records; every one of the 529 calls at `+0x702e4` is `0x0 0x0 -> 1`, with no non-null argument and no class name. `[state+0x20]` is null at every observed `0x6ff60` check, so the clear/delete branch is never taken and the `cset eq` flag is 0. Crash, five globals, reader slots, tails, and image diff are unchanged. Falsified: there is no Java object in `x20` to name. |
| Shared sequence counter (`seq-7e`) | `seqev B seq=12787` (`id=0x40b0007e pc=0x1f813 ctx=0x7ff9372b88 img=b400007b1224d010`) precedes `seqev D seq=12788` (`state=0x7ff9372bd0`, `fetch=0x1f811 next=0x1f813 op=0x7e`, same image) by 1; the second launch repeats it as 12797/12798. PROVEN: the `0x1f813` builtin is the fetch/prep step before the dispatcher entry, `0x6ff60` is the handler for the final `0x7e`, and the nested `0x31` at `pc=0x40a73` (`seq=12824`) runs after the dispatcher entry, inside the handler. |
| JNI `Long` unboxing (`pairip_jni_trace` + `GetLongField`/`CallLongMethodA`) | Zero `GetLongField` records. Two `CallLongMethodA` calls, both `java.io.File.length()` on `/proc/self/maps` and `/proc/self/status`, each returning 0; no `Long.longValue`, no field read on a `Long`, no method call on a `Long` receiver. The VM constructs 421 `java.lang.Long` objects from hash-like image values but never reads them back through JNI. The argument `0x79f72af000` still resolves to split-APK offset `0x4eec000` (`libunity.so` base). Crash, five globals, reader slots, ring positions, and pointer-only image diff unchanged. Falsified: the `Long` argument is not consumed through JNI. |
| Anti-debug stack text (`pairip_jni_trace` + `GetStringUTFChars`) | 48 `GetStringUTFChars` records, all from `libpairipcore+0x73814` (the `+0x7380c` call site), returning the 24 fabricated `pairIpUnityLaunchStack` frames twice (two stack walks). The VM writes each UTF-8 pointer and length into the VM image at hash-derived byte offsets (`[x19]` is the image base, `w23=0x44feb` the image size). Five globals, reader slots, state/builtin tails, and the 368-byte/79-range image diff are byte-identical to `long-unbox`; the crash is unchanged. Ring counters differ (builtin 3,548 vs 3,699; state 39,572 vs 41,366), so path length varies and counters are not an invariant. CONFIRMED: the spoofed stack text is consumed, but it does not populate the reader slots by itself. |
| `0x31` builtin operand boundary (`nested-builtin-operand`) | `b31-in` and `b31-reader` are byte-identical: `out32=0x22b39`, the 32-word operand block, the instruction bytes at `0x40a73`, the descriptor table at `image+0xb0`, the five globals, and both reader slots do not change between the builtin entry and the reader append. The builtin does not produce a pointer/size pair. FALSIFIED: the `0x31` builtin is a consumer/validator, not the producer of the CD view. |

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

The `license-consumption-trace` run closed the hosted-only license fork:
klB makes no `LicenseClient` or static-field JNI calls, so the bind rejection
cannot influence the reader path. It also confirmed that the Application
lifecycle is not the gate. The remaining live question is the source of the
`0x31` reader operands: at `+0x34d74` the dispatcher branches to the reader at
`+0x3dc88`, which loads `w8`/`w9` from image slots and builds the
`{vtable=0x93ce8,cursor=0,data,size}` reader. The next probe must identify
which operand block or earlier handler should have supplied those slots, or
why the hosted path reaches the reader with the pristine values.

The `stock-libmain-reader-slots` run (2026-10-28 08:27) closed the loader
fork: the game's own `libmain`, loaded from the split APK without Fusion,
still produces `w8=0x3f874`/`w9=0x1ba5c`, the pristine words
`bbb9eb6daed20ed0`/`015370f87525d57b`, and the identical
`__memmove_aarch64_nt+140` crash. The remaining hosted-only difference is
therefore earlier than `libmain`: the branch/state that reaches pc `0x40a73`
must differ from a bare launch, or the CD view it expects is absent. Because
the image is byte-identical at the reader slots and the fake-CD runs advance
past the crash, the next probe is the `0x31` operand block and the preceding
state's `next`/`fallback`, not another loader or environment input.

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
4. Falsified (apk-cd-map, 2026-10-27): a real `r--s 074f5000` base-APK line
   inside the 146,432-byte maps scan changes nothing. Refined 2026-10-28: the
   split-APK `r--s 06627000` line is a distinct variable associated with the
   libunity argument and is pre-registered below; do not run other maps/procfs
   experiments.
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
9. Done (seq-7e, 2026-10-27): the final dispatcher entry `0x1f811` is
   preceded by 1 by the `0x1f813` builtin from `+0x31de4`, so that builtin is
   the fetch/prep step and `0x6ff60` is the handler. The nested `0x31` at
   `pc=0x40a73` runs after the dispatcher entry, inside the handler, and
   writes the five globals.
10. Closed statically (2026-10-28): `+0x83470` is the `ExceptionCheck`
    epilogue and `+0x834dc`/`+0x834f0` is its pending-exception save into
    `[state+0x20]`, not the reader-slot writer. With no pending exception the
    branch is never taken, which is correct. Do not look for a writer there.
11. Done (long-unbox, 2026-10-28): the `klB` argument is not unboxed through
    JNI. The only `CallLongMethodA` calls are two `File.length()` probes that
    return 0; there is no `GetLongField` and no `Long.longValue`. The
    argument route is closed; the missing reader-slot initialization must
    come from a different state-machine branch, a direct-memory read of the
    boxed `Long`, or a writer outside the current trace.
12. Next: after the `0x31` semantics, identify the writer or patch that
    should populate `image+0x3f874`/`image+0x1ba5c`, or prove that a bare
    launch takes a non-null path that avoids both slots.
13. Done (stack-strings, 2026-10-28): the 48 `GetStringUTFChars` calls from
    `+0x73814` return the fabricated stack frames, and the VM writes their
    pointers and lengths into the VM image at hash-derived byte offsets
    (`[x19]` is the image base; `w23=0x44feb` is the image size). The crash,
    five globals, reader slots, and image are unchanged, so the stack text
    alone does not populate the reader slots. Ring counters are not
    invariant (builtin 3,548 vs 3,699; state 39,572 vs 41,366); compare
    final tails and image only.
14. Corrected statically (2026-10-28): the `+0x7cdf4` gate hashes
    image-static bytes at `image[(w26^image32[pc+0x22])%size]`, not the
    JNI text; the expected hash, candidates, and salt are bytecode/image
    values. The "text hash gate" hypothesis is withdrawn, and the
    observation-only gate probe was not run.
15. Closed (stack-len-plus-one, 2026-10-28): the transient per-frame length
    written at `+0x7383c` is not consumed by the failing path. A 51 -> 52
    byte frame produced zero new image offsets beyond the known
    ASLR/counter noise, and the five globals, reader slots, tails, and
    crash stayed identical. The stack-content route is closed; do not vary
    the spoof text again.
16. Done (reader-source, 2026-10-28): the reader slots `0x3f874`/`0x1ba5c`
    are still pristine at the crash and no traced image write touches them.
    The `vm[0x740]` table was closed statically (see the closure section):
    it is built by the reader itself and its offsets come from registers, so
    there is no upstream writer, tag, or selector to follow. The five globals
    are stable across every hosted run, so they are not the missing
    discriminator.
17. Done (stock-libmain-reader-slots, 2026-10-28): the game's own `libmain`
    from the split APK, loaded without Fusion, still reaches `+0x3e034`
    with `w8=0x3f874`/`w9=0x1ba5c`, the pristine words
    `bbb9eb6daed20ed0`/`015370f87525d57b`, and the identical
    `__memmove_aarch64_nt+140` crash. The launcher-libmain/DT_INIT variable
    is ruled out, and the walkthrough's line-166 claim now has a surviving
    log. Do not repeat this control.
18. Next: determine why klB reaches the `0x31` builtin at pc `0x40a73` at
    all on the hosted path. The image is byte-identical at the reader slots
    and the reader is a ZIP/CD view (the fake-CD runs advance past the
    crash), so the missing input is the branch/state that should either
    supply the CD view or skip the `0x31` call. Decode the `0x31` operand
    block and the preceding state's `next`/`fallback` decision, then derive
    one probe that distinguishes "reached `0x31`" from "skipped `0x31`".
    Do not repeat the loader, license, application, map, identity, or
    stack-text experiments.

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

## Pre-registered experiment (2026-10-27, seq-7e)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The `jni-x20` run closed the `x20` object question but left the final
instruction unordered: the dispatcher hook sees `fetch=0x1f811 op=0x7e`,
whose jump-table entry is `0x6ff60`, while the builtin hook sees
`id=0x40b0007e pc=0x1f813` from the only direct `bl +0x31de4` at `0x6adc0`.
The two rings are dumped independently and carry no common timestamp or
sequence, so their relative order cannot be recovered from the `jni-x20` log.
This run adds one shared, monotonically increasing entry counter
(`g_trace_seq`) to both klB-filtered hooks and stores it in both rings. Two
targeted `seqev` records log the exact events with the state/context
pointers, image, fetch/next pc, cursor, link-register offset, and eight
qwords of the hook context. No game APK is modified, repacked, or re-signed.

- Hypothesis: the dispatcher entry at `0x1f811` and the builtin entry at
  `0x1f813` belong to the same final `0x7e` instruction, with `0x6ff60` as
  its handler and `+0x31de4` either the preceding fetch/prep step or a nested
  callee.
- Prediction: `seqev D` and `seqev B` each appear exactly once in the final
  window, both name the same image pointer, and `|seq(B) - seq(D)| <= 64`.
  If `seq(B) > seq(D)`, the builtin call is nested after the dispatcher
  entry, inside `0x6ff60` or its callees; if `seq(B) < seq(D)`, the builtin
  call is the fetch/prep step that precedes the dispatcher. Either way the
  final instruction is the same `0x7e` at `0x1f811`.
- Falsification: either `seqev` record is absent, either appears more than
  once, the two image pointers differ, or the sequence gap exceeds 64. Then
  the `pc=0x1f813` correlation is coincidental, the builtin event is not part
  of the final instruction, and the `0x6ff60` handler mapping must be
  re-derived.
- Invariants: the five globals, reader slots, crash signature, state/builtin
  tails, and every compared image byte stay at the frozen values. The only
  new output is the `seq=` field on the existing ring lines and the two
  `seqev` lines; the counter is diagnostic only and must be removed with the
  trace hooks before merge.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher. Run key:
  `variant=seq-7e`, code commit `9003ed9`, APK sha256
  `32dc845833094484cf3c05bab82bfebc4038d8cd575a4b4e39b1561425b984fa`
  (115,274,306 bytes, 488 entries; `libpairipshield.so` is 479,264 bytes).

## seq-7e result (2026-10-27 23:48)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The frozen baseline plus the shared `g_trace_seq` counter ran one cold launch
command: `Status: ok`, `LaunchState: COLD`, `TotalTime: 320` ms. The APK
sha256 is
`32dc845833094484cf3c05bab82bfebc4038d8cd575a4b4e39b1561425b984fa`
(115,274,306 bytes, 488 entries, `libpairipshield.so` 479,264 bytes); the
installed game APK was not touched. The log is
`tmp/cifi-apk/logs/verify/seq-7e.log` (22,972 lines, sha256
`64e3c2c6978d02995994fbb32e75adf1e21ca04f0a8fe763b257b463e5145433`).

The pre-registered prediction is **CONFIRMED**, with one harness caveat: the
log contains two launches, pid 1277 at 23:47:05 and pid 1664 at 23:47:24.
The OS replayed the activity after the first crash, so one `am start -W`
produced two cold launches. Each launch contains exactly one `seqev B` and
one `seqev D`, and both launches show the same ordering and the same pointer
relationship, so the verdict is robust. This duplication is a harness
deviation, not a behavioural difference.

- Launch 1: `seqev B seq=12787 arg=0x7ff9372ba8 out=0x7ff9372ba4
  ctx=0x7ff9372b88 img=b400007b1224d010 id=0x40b0007e pc=0x1f813
  lr=+0x6adc4`, then `seqev D seq=12788 state=0x7ff9372bd0
  image=0xb400007b1224d010 fetch=0x1f811 next=0x1f813 cursor=0xb0
  lr=+0x6aa68`. Gap 1.
- Launch 2: `seqev B seq=12797` and `seqev D seq=12798`, same fields and the
  same image pointer.
- The dispatcher state equals the builtin context plus `0x48`
  (`0x7ff9372b88 + 0x48 = 0x7ff9372bd0`), and `vminsn[1579] seq=12798
  fetch=0x1f811 next=0x1f813 cursor=0xb0 op=0x7e` is the same event as
  `seqev D`. The builtin `0x1f813` entry is therefore the fetch/prep step
  (`+0x31de4` called from `0x6adc0`) for the instruction that the dispatcher
  then runs at `0x1f811`; `0x6ff60` is its handler.

The nested `0x31` event is not part of that preparation. In launch 2 the
builtin ring shows `builtin[11244] seq=12824 id=0x9a0c0031 pc=0x40a73`, 26
entries after the `0x40b0007e` prep event and after the dispatcher entry
`seq=12798`. It runs inside `0x6ff60` and writes the five globals.

Invariants are unchanged. The crash is still signal 11 in
`__memmove_aarch64_nt+140` with the `split_config.arm64_v8a.apk +0x514`
frame; `vm[0x540]` still holds `bbb9eb6daed20ed0` / `015370f87525d57b`; the
state tail is `id=0x872a21c sel=0x31bb`; the builtin tail is
`id=0x9a0c0031 pc=0x40a73`; the five globals are `0x534a6211`, `0xe29e9c3b`,
`0x8a1aeb2d`, `0xbfe458cb`, `0x4d062af3`; and the reader slots are
`bbb9eb6daed20ed0` / `015370f87525d57b`. The crash image is
`/tmp/opencode/seq-7e-image.bin` (sha256
`4b992f2adcda9e4d0a6b36dd48d9aad26a7fea8755c4b7d9d4b0cdc50d7f84b6`); the
diff vs `jni-x20` is 34 ranges / 76 bytes, and vs the pristine asset 79
ranges / 368 bytes. The five globals and both reader slots are byte-identical
in all three images. Ring positions in launch 2 are state 130,579, builtin
11,245, VM 1,580, and image write 2,209.

Consequence: the `pc=0x1f813` correlation was real, so the handler mapping
needs no re-derivation and the `0x1f813` builtin cannot be the writer of the
reader slots. The missing writer is now a forward problem inside `0x6ff60`
(after the nested `0x31` return) and its callees. The `+0x834dc`/`+0x834f0`
setter remains unvisited because `[state+0x20]` is null, so the handler
falls back to the image-backed string view at `image+0x3f874` /
`image+0x1ba5c`. The next step is static: follow `0x6ff60` from the nested
`0x31` return to the string builder at `0x3e12c`, and identify the branch
condition and the value that would make the setter run (or make the null
branch avoid the two slots).

The `g_trace_seq`, `seq=` ring fields, and `seqev` records are diagnostic
only and must be removed with the trace hooks before merge.

## Pre-registered experiment (2026-10-28, long-unbox)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The next step is one diagnostic run against the frozen baseline. It closes
the open question from the `jni-trace` result: the VM extracts the `klB`
argument object (`GetObjectArrayElement` at `+0x6af94`) and wraps it in a
`std::any`, but the tracer does not hook `GetLongField` or `CallLongMethodA`,
so whether `klB` actually unboxes the `java.lang.Long` is unobserved. The
argument is the load address of `libunity.so` in the split APK
(`split_config.arm64_v8a.apk` file offset `0x4eec000`). If the VM unboxes
it, the reader-slot population is probably derived from that base and the
hosted run's failure is an argument-consumption bug; if it does not, the
argument cannot be the source of the missing slots.

- Change: add observation-only hooks for `GetLongField` and
  `CallLongMethodA` to `pairip_jni_trace`, with saved originals and the
  existing `IsPairIpCaller` filter. The VM, image, argument value, and
  experiment switches are not modified. Both hooks log the receiver/class,
  the member name or raw ID, the returned value, and the return address.
- Prediction: if `klB` consumes the `Long` through JNI, at least one
  `jni: GetLongField java.lang.Long.value:J` or
  `jni: CallLongMethodA java.lang.Long.longValue()J` record appears from a
  `libpairipcore` caller before the reader crash, and its value is the
  `exec-prog` argument (`0x79f7efd000`-style split-APK base) or a value
  derived from it. The crash signature, five globals, reader slots, ring
  positions, and image diff stay identical to `seq-7e`.
- Falsification: zero `GetLongField` and zero `CallLongMethodA` records from
  `libpairipcore` callers before the crash. Then the VM does not unbox the
  argument through JNI; the `Long` route is closed and the next step is the
  `0x31`/state-machine branch or a direct-memory read of the boxed value.
- Follow-up mapping: if a matching record exists, record its call-site
  offset and value and disassemble that site to find how the base reaches
  the reader slots. If the only records are on VM-created `Long` objects
  with hash-like values, the argument is not consumed and the run counts as
  a falsification for the argument route.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, `pairip_jni_trace=true`,
  debug launcher.
- Run key: `variant=long-unbox`, code commit `HEAD`, APK sha256 recorded
  after the build; package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Command target:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_jni_trace true --es pairip_variant long-unbox
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/long-unbox.log
```

- Status: pre-registered; ready to build and run.

### Result

- Build: APK sha256
  `f676f109c165a020feef3b132824556c52d1ed18c7021aea96860429991c9fca`
  (115,274,306 bytes, 488 entries, `libpairipshield.so` 480,736 bytes).
  The installed game APK was not touched.
- Install: `android install` sent a 904.94 KB delta patch. Both packages were
  force-stopped and the log buffer was cleared before the launch.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 317` ms. Unlike
  `seq-7e`, this run produced exactly one launch (pid 8138 at 00:06:22), so
  the counters below are directly comparable to a single `seq-7e` launch.
- Log: `tmp/cifi-apk/logs/verify/long-unbox.log`, 26,235 lines, sha256
  `637f1569772a4ae09c095064ab69793f803f2b2ac4e58bab539ac87813ee9241`.
- Instrumentation: line 1,783 reports `variant=long-unbox`,
  `jniTrace=true`, `trace=true`, `preload=true`, `dumpVmImage=true`; every
  other experiment switch is off.
- Argument: `vm-args: [0] java.lang.Long=0x79f72af000`. The maps dump line
  32 resolves it to `79f72af000-79f77a3000 r--p 04eec000
  split_config.arm64_v8a.apk`, i.e. the same `libunity.so` ELF base as
  `seq-7e` (offset `0x4eec000`). ASLR changed the numeric base; the mapping
  identity did not.
- JNI surface: 4,628 records (3,571 from the `jni-trace` baseline plus 1,053
  `IsSameObject` records already compiled into the tracer plus the four new
  `CallLongMethodA` records). `GetLongField`: 0 records.
  `CallLongMethodA`: 2 calls, both `java.io.File.length()J`, on
  `/proc/self/maps` and `/proc/self/status`, each returning `0`. There is no
  `Long.longValue`, no `GetFieldID`/`GetObjectClass` on `java.lang.Long`,
  and no `Call*Method` on a `Long` receiver.
- The VM does construct 421 `java.lang.Long` objects from hash-like 64-bit
  image values (for example `0x112268b1c431feb3`), but it never reads them
  back through JNI. They are VM data values, not the `klB` argument.
- Ring positions are byte-identical to `jni-trace`: builtin 3,699, state
  41,366, vm 1,580, image write 2,211 (`seq-7e`'s two-launch log ends at the
  same positions because it reports the second launch).
- Crash signature unchanged: signal 11 in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk +0x514`; `vm[0x540]` still holds
  `bbb9eb6daed20ed0` / `015370f87525d57b`; state tail `id=0x872a21c
  sel=0x31bb`; builtin tail `id=0x9a0c0031 pc=0x40a73`; dispatcher tail
  `fetch=0x1f811 op=0x7e`.
- Crash image: `klB-image-at-crash.bin`, pulled to
  `/tmp/opencode/long-unbox-image-at-crash.bin`, sha256
  `f5d547c5ef9c8ffd471d06caa8e86c329724b181af039694b538f36a8ee234d5`.
  Five globals `11624a530406f4d0`, `3b9c9ee2f30925fb`, `2deb1a8aeb610c80`,
  `cb58e4bf99df9e13`, `f32a064d1ef862fb` are byte-identical to `seq-7e` and
  differ from pristine at `0x1da6f`/`0x1f422`/`0x2129a`/`0x377ff`/`0x38edd`.
  Reader slots `0x3f874`/`0x1ba5c` are byte-identical to pristine. Diff vs
  pristine is 79 ranges / 368 bytes, the same as `seq-7e`; diff vs
  `jni-trace` is 29 ranges / 53 bytes, all high bytes of ASLR pointers.
- Verdict: the pre-registered prediction is **FALSIFIED**. The `Long`
  argument is not consumed through JNI, so the JNI-unboxing explanation for
  the missing reader slots is closed. The reader-slot source must be a
  different state-machine branch, a direct-memory read of the boxed `Long`,
  or a writer outside the current trace.

## Pre-registered experiment (2026-10-28, stack-strings)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The `long-unbox` run closed the JNI-unboxing route, so the remaining
environment input that differs between hosted and stock is the fabricated
anti-debug stack trace. Static review found that `libpairipcore` does call
the JNI string readers the tracer never hooked: `+0x7dc14` loads
`GetStringUTFLength` (`#0x540`), `+0x7dc30` loads `GetStringUTFChars`
(`#0x548`), and `+0x7f190` loads `ReleaseStringUTFChars` (`#0x550`). The
`+0x7dc00` block is the same loop that calls `GetObjectArrayElement`, so the
VM almost certainly copies the `StackTraceElement.toString()` results into
native memory before the reader. `JStringToUtf8` uses saved originals, so a
new hook will not recurse.

- Change: add an observation-only `GetStringUTFChars` hook to
  `pairip_jni_trace` with the saved original and the `IsPairIpCaller` filter.
  It logs the returned UTF-8 text (truncated to 160 bytes), the caller, and
  nothing else. The VM, image, stack spoof, and experiment switches are not
  modified.
- Prediction: before the reader crash, at least one
  `jni: GetStringUTFChars` record from a `libpairipcore` caller returns a
  string produced by the anti-debug stack walk (a `pairIpUnityLaunchStack`
  frame or similar frame text). The crash signature, five globals, reader
  slots, ring positions, and pointer-only image diff stay identical to
  `long-unbox`.
- Falsification: zero `GetStringUTFChars` records from `libpairipcore`
  callers before the crash, or the only returned strings are unrelated to
  the stack walk. Then the string-content route is closed and the stack-text
  difference is not the gate.
- Follow-up mapping: if stack text is read, record the exact strings and
  disassemble `+0x7dc30` back to the hash/state write to see whether the text
  reaches the five globals. If it does, the next run should change only the
  fabricated stack content and watch the globals and reader slots.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, `pairip_jni_trace=true`,
  debug launcher.
- Run key: `variant=stack-strings`, code commit `HEAD` plus the
  `GetStringUTFChars` hook, APK sha256 recorded after the build; package
  `com.bepinex.android.debug` on SM-S931N (Android 17/API 37, `user`,
  `ro.debuggable=0`).
- Status: run; result below.

### Result

- Build: APK sha256
  `726786777431d86c7689533bca63a19c808e43a2b335da3eb69326c27195e235`
  (115,274,306 bytes, 488 entries; `libpairipshield.so` 481,376 bytes).
  Installed with `android install` (904.94 KB delta); the game APK was not
  modified, repacked, or re-signed.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 228` ms. Both
  packages were force-stopped and the log buffer cleared first. One launch
  only (pid 12999 at 00:16:17).
- Log: `tmp/cifi-apk/logs/verify/stack-strings.log`, 30,053 lines, sha256
  `dac35b7ff6e3ca9a1da3e548cbedca35c295246d610106fc4190ea31e66cfd68`.
- Instrumentation: line 774 reports `variant=stack-strings`,
  `jniTrace=true`, `trace=true`, `preload=true`, `dumpVmImage=true`,
  `stackSpoof=true`; every other experiment switch is off. YTw and klB both
  ran.
- String reads: 48 `GetStringUTFChars` records, all from the same caller
  `0x7a9013f814` = `libpairipcore+0x73814` (the return address after the
  `+0x7380c` load). They are two copies of the same 24 fabricated
  `pairIpUnityLaunchStack` frames (two `getStackTrace` walks): for example
  `com.pairip.VMRunner.executeVM(Native Method)`,
  `com.pairip.VMRunner.invoke(VMRunner.java:69)`,
  `com.unity3d.player.NativeLoader.load(Native Method)`, and
  `com.unity3d.player.UnityPlayerActivity$c2020060322V3.onCreate(SourceFile:0)`.
  The UTF-8 text is the spoof's text, not the launcher's real stack.
- Static: `+0x7380c` loads `GetStringUTFChars` (`#0x548`); `+0x73814`
  returns the pointer. At `+0x7382c` the image base is reloaded from
  `[x19]`, `+0x73830` stores the UTF-8 pointer into the image at byte offset
  `(w26^w24) % w23`, and `+0x7383c` stores the length `w22` at byte offset
  `(w26^w25) % w23`. `w23` is the image size (`0x44feb`), so the spoofed
  text ends up in the VM image's hash table, not in a separate native array.
  The reads do not come from `+0x7dc30`.
- Crash: unchanged. Signal 11 in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk +0x514`; `vm[0x540]` still holds
  `bbb9eb6daed20ed0` / `015370f87525d57b`; state tail `id=0x872a21c
  sel=0x31bb reader=0xf9372668`; builtin tail `id=0x9a0c0031 pc=0x40a73`;
  the dispatcher ring tail is unchanged.
- Crash image: `/tmp/opencode/stack-strings-image.bin`, sha256
  `89512e884c333b75fe9dfa1a062a009aab41e87c7bb68239079dd1e5292b064c`.
  Five globals are byte-identical to `long-unbox`; reader slots
  `0x3f874`/`0x1ba5c` are byte-identical to pristine; diff vs pristine is 79
  ranges / 368 bytes; diff vs `long-unbox` is 30 ranges / 56 bytes:
  pointer-like values at unaligned offsets (low/mid as well as high bytes)
  plus small counters, not only ASLR high bytes.
- Ring counters are **not** identical: builtin 3,548 (vs 3,699), state
  39,572 (vs 41,366), vm 1,580 (same), image write 2,209 (vs 2,211); the
  final shared `seq` is 5,127 (vs 5,278). The difference is 151 builtin
  dispatches, so the VM path length varies run to run (the 48 hook records
  cannot reduce a counter). Only the final tails and image are invariants;
  do not use ring positions as a strict cross-run invariant.
- Verdict: the pre-registered observation is **CONFIRMED**. The VM reads
  the fabricated anti-debug stack text through `GetStringUTFChars` at
  `+0x7380c` and writes it into the VM image. The reader crash, five
  globals, reader slots, and image are unchanged, so the stack text alone
  does not populate the reader slots. The "ring positions identical" clause
  did not hold; counters are path-length-dependent.

## Static correction (2026-10-28): the +0x7cdf4 gate is not a text check

The register-level decode of the handler at `0x73718-0x7383c` and the gate
at `0x7cdb4-0x7ce08` changes the interpretation of the stack-string block.

- At `+0x73718` the handler loads `w23` (image size) and `w13` (operand pc)
  from `[x19+8]`. It then reads, from the image at `pc`:
  a 16-bit length (`w27`) at `+0`, the 32-bit candidate `w24` at `+2`, the
  32-bit candidate `w25` at `+6`, the salt-index mix at `+0xa`, the
  hash-bucket mix at `+0x16`, the 64-bit expected value at `+0x1a`, and the
  hash-input offset at `+0x22`.
- `GetStringUTFLength` (`#0x540`) returns the runtime UTF-8 length in `w22`;
  `+0x7383c` stores it at `image[(w26^image32[pc+6])%size]`.
  `GetStringUTFChars` (`#0x548`) returns the runtime pointer; `+0x73830`
  stores it at `image[(w26^image32[pc+2])%size]`.
- The hash loop at `+0x73864` does **not** read the JNI buffer. It reads
  `w27` bytes from `image[(w26^image32[pc+0x22])%size]`, and `w27` is the
  16-bit immediate at `pc`, not the runtime length.
- At `+0x7cdc0` the VM reads the 32-bit signed salt at
  `image[(w26^image32[pc+0xa])%size]`. At `+0x7cdf0` it compares
  `FNV1(image bytes) ^ salt` with the 64-bit immediate at `pc+0x1a`.
  At `+0x7cdf8` it selects `w25` on equality else `w24`, and at `+0x83470`
  it stores the selected result.
- Therefore the gate is a static bytecode/image self-check, not a comparison
  of the fabricated stack text. The text only reaches the image as a
  transient pointer/length pair. The "text hash gate" hypothesis is
  withdrawn; the planned observation-only probe at `+0x7cdf4` was not run
  because its `eq` pattern cannot discriminate the spoof.
- The runtime length is the only deterministic text-derived value that
  enters the image. The next experiment changes one frame's length.

## Pre-registered experiment (2026-10-28, stack-len-plus-one)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

- Purpose: test whether the per-frame UTF-8 length stored by the
  stack-string handler at `+0x7383c` is consumed by the failing path.
- Change: in `pairIpUnityLaunchStack()`, change the `NativeLoader` method
  name from `load` to `loade` (one extra UTF-8 byte; 51 -> 52). No other
  spoof frame, switch, hook, or baseline change.
- Prediction: the `GetStringUTFChars` log shows
  `com.unity3d.player.NativeLoader.loade(Native Method)` with length 52, and
  at least one of the five globals, the reader slots, the tails, or the
  crash image differs from `stack-strings`; the runtime length is an input
  to the failing path.
- Falsification: the new frame appears with length 52, but the five
  globals, reader slots, state/builtin tails, crash signature, and image
  diff (apart from ASLR pointer bytes) are byte-identical to
  `stack-strings`. Then the per-frame length is not consumed by the failing
  path and the stack-content route is closed.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, `pairip_jni_trace=true`,
  `stackSpoof=true`, `contextSeed=true`, debug launcher.
- Run key: `variant=stack-len-plus-one`, code commit `HEAD` plus the
  one-frame change, APK sha256 recorded after the build; package
  `com.bepinex.android.debug` on SM-S931N (Android 17/API 37, `user`,
  `ro.debuggable=0`).
- Command:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_jni_trace true --es pairip_variant stack-len-plus-one
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/stack-len-plus-one.log
```

The character append also changes the frame text. The handler stores only
the pointer and length, not the bytes, so the length is the intended
variable.

## stack-len-plus-one result (2026-10-28 00:39)

- Build/install: debug APK 131,054,961 bytes, sha256
  `98bf9c7c2f059302e547e728579d383788d96a0eb346fb23c47a0132228acf6f`;
  `android install` sent a 15.99 MB delta (87.21% savings); the game APK
  was not modified. Device SM-S931N (Android 17/API 37, `user`,
  `ro.debuggable=0`).
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 224` ms, one
  launch only (pid 24902). Log
  `tmp/cifi-apk/logs/verify/stack-len-plus-one.log`, 25,867 lines, sha256
  `0c99a19332368b22e121dfa25043e118f7ef7fbf0c15fb64da62d2b2bea013c3`.
- Change observed: the fabricated frame appears twice as
  `com.unity3d.player.NativeLoader.loade(Native Method)` (52 UTF-8 bytes)
  at the two `GetStringUTFChars` stack walks. The run still has exactly 48
  `GetStringUTFChars` records, all from the same
  `libpairipcore+0x73814` caller.
- Invariants: signal 11 in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk +0x514`; `vm[0x540]` still holds
  `bbb9eb6daed20ed0` / `015370f87525d57b`; state tail
  `id=0x872a21c sel=0x31bb reader=0xf9372668`; builtin tail
  `id=0x9a0c0031 pc=0x40a73`; dispatcher tail `fetch=0x1f811 op=0x7e`,
  next `0x1f813`.
- Five globals `11624a530406f4d0`, `3b9c9ee2f30925fb`,
  `2deb1a8aeb610c80`, `cb58e4bf99df9e13`, `f32a064d1ef862fb` are
  byte-identical to `stack-strings`, `long-unbox`, `jni-trace`,
  `jni-x20`, and `seq-7e`. Reader slots `0x3f874`/`0x1ba5c` are
  byte-identical to pristine; no dumped image-write ring entry touches
  either slot, and the crash image keeps both pristine.
- Crash image: `/tmp/opencode/stack-len-plus-one-image.bin`, sha256
  `28b923f54e2c5b02baf08ef2e5d0928936409df057b5eb66b84fee3121f77c9d`.
  Diff vs `stack-strings` is 30 ranges / 51 bytes, but there are **zero**
  new-only differing offsets: every difference already varies across
  `seq-7e`/`jni-trace`/`jni-x20`/`long-unbox`/`stack-strings`. The
  non-pointer `image+0xae3` counter is `0x21a`, inside the observed
  run-to-run range `0x215..0x21a` (`jni-trace` is also `0x21a`). Diff vs
  pristine is 78 ranges / 367 bytes.
- Ring counters: builtin 3,729, state 41,720, vm 1,580, image write 2,211;
  final `seq=5,308`. They differ from `stack-strings` (3,548 / 39,572 /
  1,580 / 2,209, `seq=5,127`) as in earlier identical-input runs, so the
  differences are path-length variance, not an effect of the length.
- Verdict: the pre-registered prediction is **FALSIFIED**. The 52-byte
  frame reaches the VM, but no deterministic image, global, reader, or tail
  difference follows. The literal "byte-identical image" clause fails only
  on the nondeterministic `0xae3` counter; with zero new-only offsets the
  falsification holds in substance. The per-frame runtime length is not
  consumed by the failing path. The stack-content route is closed: neither
  the fabricated text bytes nor their length gate the reader
  initialization.

## Pre-registered experiment (2026-10-28, selector-gate)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

- Purpose: test whether the string-hash selector gate at
  `libpairipcore.so+0x38420` is the producer of the crash's `sel=0x31bb`
  state selector, and record the gate inputs.
- Static basis: at `+0x38340` the block loads the string pointer `x19` from
  `[x21+w8]`, calls `strlen`, computes FNV-1 into `x10`, and stores that hash
  at `image[(w28^w29)%size]`. At `+0x383f4` it loads the image hash-range
  offset into `w9`; at `+0x38400-0x3841c` it FNV-hashes `w26` bytes from
  `image+w9` into `x12`. At `+0x38420` it loads the sign-extended word at
  `image+w23`, at `+0x38428` XORs it with `x12`, at `+0x3842c` compares the
  result with `x25`, and at `+0x38430` selects `w29` on equality else `w27`
  into `w5`, then branches to `+0x32c5c` and the state dispatcher.
- Change: debug-only observation hooks at `+0x383f4` (stash the range length
  `w26`) and `+0x38420` (record the string, hashes, expected word, range,
  candidates, and current VM fetch/op). The callback computes `eq`/`sel`;
  the `csel` and branch are not hooked. No VM, image, spoof, or switch
  change.
- Prediction: both hooks install; at least one gate record is emitted before
  the crash; the last record's `sel` equals the state-ring tail `sel=0x31bb`;
  the interned string is non-empty and the hashed range is valid. The `eq`
  value and string content decide the next step; no crash-signature change
  is predicted.
- Falsification: zero gate records, the last `sel` differs from `0x31bb`, or
  a hook fails to install. Then this gate is not the producer of the crash
  selector; close the route and move to the reader-slot writer.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, `pairip_jni_trace=true`,
  `stackSpoof=true`, `contextSeed=true`, debug launcher.
- Run key: `variant=selector-gate`, code commit `HEAD` plus the hooks,
  committed with this record; APK sha256 recorded after the build.
- Status: pre-registered; ready to run.

## selector-gate result (2026-10-28 01:15)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen baseline plus the two observation hooks ran one cold launch to the
same crash. The pre-registered producer prediction is **FALSIFIED**.

- Build/install: debug APK `131,239,292` bytes, sha256
  `711c602db877bc65836f88951f785c4d9929bcd2b2a84a07a7e06e971a5affaf`;
  `libpairipshield.so` `486,088` bytes, 490 entries. `android install` sent a
  1.29 MB delta (98.97% savings); the game APK was not modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 221` ms, one launch
  only (pid 10464). Log `tmp/cifi-apk/logs/verify/selector-gate.log`, 22,477
  lines, sha256
  `7e853d7b1cd00087abdb4385c801393ef3e2f9bbbd1114b5cd52f5b8588c3124`.
- Hooks: `gate-len hook rc=0 target=0x7a9b54c3f4 trampoline=0x7e1e46d250`
  and `gate hook rc=0 target=0x7a9b54c420 trampoline=0x7e1e46d26c`, base
  `0x7a9b514000`. All other observation hooks also installed with `rc=0`.
- Gate ring: `pos=0 eq=0 ne=0`; no `gate:` record and no first-`ne` record.
  The `+0x383f4`/`+0x38420` block is never entered on the crashing path. The
  hash ring and router ring are likewise empty (`pos=0`), consistent with the
  `hash-gate` and `state-router-retry` results.
- State tail: `state ring pos=39158`, last
  `state[39157] id=0x872a21c sel=0x31bb reader=0xf9372668`. Builtin ring
  `pos=3513`, VM ring `pos=1580`, image write ring `pos=2211`; these are
  path-length counters, not invariants.
- Crash: unchanged. Signal 11 in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk+0x514`; `vm[0x540]`=`0xf9372668`,
  `+0x544`=`0x7ff93726f9`, `+0x548`=`0xbbb9eb6daed20ed0`,
  `+0x550`=`0x015370f87525d57b`; `vm[0x7f8]`=`0x31bb`. Append record
  `src=0x7ff9372630 n=46 dst=0x7ff9372698 lr=0x7a9b552170 off=0x3e170
  fields=0000007a9b5a7ce8,0000000000000000,bbb9eb6daed20ed0,015370f87525d57b
  mapped=0`.
- Crash image: `/tmp/opencode/selector-gate-image.bin`, sha256
  `952fa36e4d60dfbbc87334f8c65065bbc6d34f7f6befed39e4bb3db688aa0bfa`.
  Diff vs `stack-len-plus-one` is 31 ranges / 58 bytes with **zero** new-only
  offsets; five globals and both reader slots are byte-identical to every
  prior image.
- Verdict: the `+0x38420` string-hash selector gate is not the producer of
  `sel=0x31bb`; the route is closed together with `+0x32c20`, `+0x3dc0c`,
  and `+0x3dc50`. The follow-up static decode (below) shows that
  `0x7ae3f9a6` is a termination state and that `sel` is not a state
  selector. Do not install further probes at the closed addresses.

## Static map (2026-10-28): the state ring is not a selector

This section records the read-only disassembly pass that followed the
selector-gate falsification. No device run was made; the evidence is
`/tmp/pairip-analysis/libpairipcore_full.asm` (sha256 recorded in the
walkthrough) plus the ring-field code in
`launcher/app/src/main/cpp/pairipshield/shield.cpp`.

- Ring fields: `pairip_state_trace_c` stores `state=regs[10]`,
  `selected=regs[5]`, `reader=regs[9]`. The hook target is `+0x32c6c`, the
  dispatcher entry used after a handler has chosen the next state.
- Dispatcher body: `+0x31de4` is the interpreter/dispatcher called from
  `+0x6adc0` (`bl`, return `+0x6adc4`); its epilogue is `+0x56c54`. At
  `+0x32c6c` the dispatcher has already loaded `w10` with the next state and
  has usually copied the carried value into `w9` (`mov w9, w29`). The
  dispatcher does not assign `w5` at the hook; `w5` is whatever the previous
  handler or the nested interpreter left in the register.
- `+0x32c54` is one real assignment on this path:
  `w5 = (w21 ^ csel) % w19`, an image index. The `0xe36e031a` handler
  (`+0x32e1c..+0x32e34`) stores that `w5` into
  `[[sp+0x538]+8]+0xc` (the reader object's `+0xc` field), sets `w9=1` and
  `w10=0x7ae3f9a6`, then re-enters `+0x32c6c`. `+0x332b8` sets
  `w5=-9` on a different state branch. The function contains many other
  `w5` writes; the earlier "only at" wording is withdrawn. What matters is
  that none of them is a dispatcher-selected state.
- `0x7ae3f9a6` is terminal: `+0x532d4..+0x532e4` compares `w8` with the
  state id and `b.eq +0x56c54`; `+0x56c54` loads `[sp+0x540]` and returns
  `w0 = value & 1`. It does not choose a successor.
- JNI entry: `+0x6a968..+0x6b3c4` (`executeVM`) never references `x5`/`w5`.
  The state-ring `selected` value therefore cannot come from the JNI
  argument contract; it is a residue of the interpreter's own call/return
  path.
- Nested interpreters: the state ring is global and interleaves dispatches
  from nested interpreter instances. The run log's `state[39148]
  id=0x7ae3f9a6 sel=0x22b39 reader=0x1` is the inner interpreter's
  termination; `state[39149] id=0x81398368 sel=0x31bb reader=0xf9372668`
  is the outer interpreter resuming. They are adjacent ring entries, not a
  transition. This is why the old "first appears at `0x81398368`, entered
  from `0x7ae3f9a6`" reading was wrong.
- Consequence: `sel=0x31bb` is not a state selector and has no producer to
  find. The `+0x32c20`, `+0x32c54`, `+0x3dc0c`, and `+0x3dc50` probes remain
  closed. The open problem is still the missing writer of the reader-backed
  string view at `image+0x3f874`/`image+0x1ba5c`, which is what the append
  crash dereferences.
- Reader builder for that view: `+0x3dc88` loads the descriptor from
  `[sp+0x538]`, stores `w29` to `[sp+0x540]`, loads `x16` (reader object)
  and `w9` (descriptor `+0x10`, observed `0xb0`) from the descriptor,
  then reads the image base from `[x16]` and the image size from `[x16+8]`.
  The append call at `+0x3e16c`/`+0x3e170` consumes the string view from
  the image slots. The append record (`src=0x7ff9372630 n=46 ...
  off=0x3e170`) is that call, not evidence about `w5`.

## Pre-registered experiment (2026-10-28, identity-conjunction)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

- Purpose: test the untested conjunction of stock process name, a maps view
  with no launcher-package line, and the game base APK mapped at offset 0.
  The three facts were each tested alone and falsified, but if PairIP picks
  the first APK identity in maps, every single change still leaves the
  launcher as the winner.
- Change: `pairip_process_name=true`, `pairip_proc_sanitize=true` with the
  host package name added to the hidden substrings, and
  `pairip_apk_zero_map=true`. No VM, image, or reader change.
- Prediction: spoof success, a sanitized maps read, and the game zero-map
  line inside the dump; then either the reader slots differ from
  `bbb9eb6daed20ed0`/`015370f87525d57b` (and the crash changes), or the
  state/builtin tail diverges. The five globals may remain stable.
- Falsification: all three facts are active and visible, but the reader
  slots, crash signature, and both tails stay byte-identical. Then the
  identity conjunction is not the gate and the identity branch is closed.
- Invalid run: any toggle inactive, spoof failure, no sanitized maps read,
  no zero-map line, or the sanitized maps still shows
  `com.bepinex.android.debug`.
- Frozen diagnostic baseline: `pairip_preload=true`, `pairip_trace=true`,
  `pairip_dump_vm=true`, `stackSpoof=true`, `contextSeed=true`.
- Run key: `variant=identity-conjunction`, code commit `HEAD` with the
  hidden-substring change, APK sha256 recorded after the build.
- Status: pre-registered; ready to build and run.

## identity-conjunction result (2026-10-28 01:41)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen diagnostic baseline plus the three identity toggles ran one cold
launch. The pre-registered prediction is **FALSIFIED**.

- Build/install: APK sha256
  `f4f71ba28ce60a06c82da4e0353ed30a74dfde1635c30ba7dceab232bc1c1c17`
  (131,239,294 bytes, 490 entries; `libpairipshield.so` 486,088 bytes).
  `android install` sent 1.09 MB (99.13% savings); the game APK was not
  modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 220` ms, one launch
  only (pid 14833). Log
  `tmp/cifi-apk/logs/verify/identity-conjunction.log`, 10,010 lines, sha256
  `0e54ede30a6c3c05008b7a73aa06d4808ae1b59a2c04f310a87cea13dade2a7d`.
- Identity facts, all confirmed in the VM's own sanitized view:
  - `process name spoofed: comm=mesCompany.CIFI`; the status dump shows
    `Name:\tmesCompany.CIFI` and uid/gid `10610`.
  - `proc: sanitized /proc/self/maps fd=151 bytes=427872`; the sanitized
    file (`identity-conjunction-maps.txt`, sha256
    `ad238d11093371fef486cae2f58524165fbd02b2b6b2d9721777a36bda89669b`)
    has zero `bepinex` lines. The raw copy has 59.
  - `apk-zero-map` mapped the game base APK at `0x7900000000`; sanitized
    line 32 is its `r--p 00000000` mapping, the first APK line in the scan.
- VM invariants: reader slots `bbb9eb6daed20ed0` / `015370f87525d57b`;
  append `src=0x7ff93727a0 n=46 ... off=0x3e170
  fields=0000007a89d69ce8,0000000000000000,bbb9eb6daed20ed0,015370f87525d57b
  mapped=0`; `vm[0x540]` = `00000000f93727d8`/`0000007ff9372869`;
  `vm[0x7f8]` = `0x31bb`; state tail `id=0x872a21c sel=0x31bb
  reader=0xf93727d8`; builtin tail `0x9a0c0031`/`0x40a73`.
- Crash: signal 11 in `__memmove_aarch64_nt+140`, frame #04
  `split_config.arm64_v8a.apk (+0)`, at 01:41:13.779. Image
  `identity-conjunction-image.bin`, sha256
  `c5a7aed37e811548cbf82335a218f5d2d16c505b491cc817f2585b8c1781279f`;
  diff vs selector-gate is 81 bytes / 39 ranges with zero new-only offsets.
- Verdict: presenting the stock process name, the stock APK-first maps view,
  and the game base APK at offset 0 changes nothing in the reader path. The
  missing reader-slot writer is not selected by process/maps identity. Move
  to the next single fork: `contextSeed=false` (stock starts with a null
  `VMRunner.context`) or a reader-source probe at `+0x3dc88`. Do not repeat
  identity/maps experiments.

## stock-bound-context result (2026-10-28 02:01)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen diagnostic baseline plus `pairip_stock_bound=true` ran one cold
launch. The pre-registered prediction is **FALSIFIED**.

- Build/install: no build; APK sha256
  `f4f71ba28ce60a06c82da4e0353ed30a74dfde1635c30ba7dceab232bc1c1c17`.
  Install skipped because the installed APK's `lastUpdateTime` is after the
  build and no launcher code changed since. The game APK was not modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 213` ms, one launch
  only (pid 18971). Log
  `tmp/cifi-apk/logs/verify/stock-bound-context.log`, 9,945 lines, sha256
  `5fd8a8af638dcacd3fd90767bb9da4c1858933d89545024136f2a5bc8d0b82de`.
- Context: PairIP's `InitContextProvider` actually ran. The log shows the
  `ContextImpl.createAppContext` and `ActivityThread.mInstrumentation`
  hidden-API grants from `com/pairip/InitContextProvider`, then
  `restored mBoundApplication.info` and `stock-bound factory started`. This
  is the stock first-invoke path: null `VMRunner.context`, app context built
  from the game LoadedApk.
- Invariants: final state `id=0x872a21c sel=0x31bb reader=0xf93727d8`;
  reader slots `bbb9eb6daed20ed0` / `015370f87525d57b`; append
  `src=0x7ff93727a0 n=46 ... off=0x3e170
  fields=0000007a908d4ce8,0000000000000000,bbb9eb6daed20ed0,015370f87525d57b
  mapped=0`; `vm[0x540]` = `00000000f93727d8`/`0000007ff9372869`;
  `vm[0x7c0]` ends in `0x31bb`; builtin tail `0x9a0c0031`/`0x40a73`.
- Crash: unchanged, signal 11 in `__memmove_aarch64_nt+140`, frame #04
  `split_config.arm64_v8a.apk (+0)`. Image
  `stock-bound-context-image.bin`, sha256
  `ba9fb2b936980ee138acb4e1057b32683cef40073ab7489dd08e3ced1f9e27c5`;
  it differs from the identity-conjunction image in 45 bytes, all inside the
  known 368-byte variable set, with zero new-only offsets. The five globals
  and both ZIP slots are byte-identical.
- Verdict: the stock-faithful `InitContextProvider` context does not change
  the reader path. The context object type is not the gate. The remaining
  unmeasured part of the stock boot is the game `Application` itself:
  `attachBaseContext` sets `VMRunner.context` to the Application, then runs
  `SignatureCheck.verifyIntegrity` and `LicenseClient.checkLicense`. The
  next single fork is `pairip_game_application=true` with the current
  reader-slot/image instrumentation. The 2026-10-27 handoff says that
  lifecycle was tried, but it has no run key or log in this repository and
  did not measure the reader slots, so that claim is UNVERIFIABLE as a
  falsification; the new run must state the current invariants. If it is
  unchanged, close the Application branch and move to the reader-source
  probe at `+0x3dc88`. Do not repeat identity/maps/context-only experiments.

## game-application result (2026-10-28 02:04)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen diagnostic baseline plus `pairip_game_application=true` ran one
cold launch. The pre-registered prediction is **FALSIFIED**.

- Build/install: no build; APK sha256
  `f4f71ba28ce60a06c82da4e0353ed30a74dfde1635c30ba7dceab232bc1c1c17`.
  Install skipped because the installed APK's `lastUpdateTime` is after the
  build and no launcher code changed since. The game APK was not modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 202` ms, one launch
  only (pid 20237). Log
  `tmp/cifi-apk/logs/verify/game-application.log`, 10,331 lines, sha256
  `333ab8538198fb4223e3095a9a0fc283d8cb3ad83426792170f973fb7dbf1a93`.
- Lifecycle: `installGameApplication` used `LoadedApk.makeApplication` and
  logged `game Application com.pairip.application.Application
  package=com.OctocubeGamesCompany.CIFI uid=10610 myUid=10616`. PairIP's
  `SignatureCheck` ran and logged `Signature check ok`; `VMRunner.context`
  was the Application for the subsequent klB execution. This is the part of
  the stock boot that the context-object probe did not cover.
- License: `LicenseClient: Connecting to the main licensing service...`
  follows the Application line. The platform rejects the bind:
  `com.OctocubeGamesCompany.CIFI does not belong to uid 10616`,
  `Permission Denial: Accessing service .../LicensingService from pid=20237,
  uid=10616 requires com.android.vending.CHECK_LICENSE`, then
  `License check failed with error 'Not allowed to bind with the licensing
  service: com.android.vending.licensing.ILicensingService'`. The real
  Binder caller UID is the launcher's 10616, not the game's 10610; the
  native `getuid`/status spoof does not affect Binder. This is a hosted-only
  fact, not yet a measured gate.
- Invariants: final state `id=0x872a21c sel=0x31bb reader=0xf93727d8`;
  reader slots `bbb9eb6daed20ed0` / `015370f87525d57b`; append
  `src=0x7ff93727a0 n=46 ... off=0x3e170
  fields=0000007a90df2ce8,0000000000000000,bbb9eb6daed20ed0,015370f87525d57b
  mapped=0`; `vm[0x540]` = `00000000f93727d8`/`0000007ff9372869`;
  `vm[0x7c0]` ends in `0x31bb`; builtin tail `0x9a0c0031`/`0x40a73`.
- Crash: unchanged, signal 11 in `__memmove_aarch64_nt+140`, frame #04
  `split_config.arm64_v8a.apk (+0)`, at 02:04:13.474; process exit by signal
  11 at 02:04:13.501. Image `game-application-image.bin`, sha256
  `0ccecb66b8b097d70dbfc6614bd41f339fabef5cb9de37b9dfa45aae96a30b46`;
  it differs from the identity-conjunction image in 48 bytes across 29
  ranges, all inside the known 368-byte variable set, with zero new-only
  offsets. The five globals and both ZIP slots are byte-identical.
- Verdict: creating the game Application, running `attachBaseContext`'s
  signature check, and setting `VMRunner.context` to the Application change
  no reader invariant. The Application lifecycle is not the gate; close it.
  The only new fact is the hosted-only license bind rejection. Before any
  device run, inspect whether klB reads the `LicenseClient` result or
  `licenseCheckState`; if it does not, the next probe is the reader source at
  `+0x3dc88`. Do not repeat identity/maps/context/application experiments.

## license-consumption-trace result (2026-10-28 02:11)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen diagnostic baseline plus `pairip_game_application=true` and
`pairip_jni_trace=true` ran one cold launch. The pre-registered
falsification (any `LicenseClient` access) did **not** occur, so the
license-state fork is closed.

- Build/install: no build; APK sha256
  `f4f71ba28ce60a06c82da4e0353ed30a74dfde1635c30ba7dceab232bc1c1c17`.
  Install skipped because the installed APK's `lastUpdateTime` is after the
  build and no launcher code changed since. The game APK was not modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 214` ms, one launch
  only (pid 22000). Log
  `tmp/cifi-apk/logs/verify/license-consumption-trace.log`, 17,025 lines,
  sha256 `1ad2601dcbed28af371ad904faebcdaec33c371f6b3026c7fe2774df1b3114b0`.
- Trace coverage: `PairIP JNI trace: installed=true` at 02:11:35.535, before
  the first JNI record at 02:11:35.653 and the crash at 02:11:36.440. The
  4,676 records include the newer `IsSameObject` (1,053) and
  `GetStringUTFChars` (48) probes.
- License result: zero records name `LicenseClient`,
  `LicenseCheckState`, or `licenseCheckState`; zero `GetStaticFieldID`,
  `GetStaticObjectField`, `GetStaticBooleanField`, and `GetFieldID` records;
  the `FindClass` set is still only `Object`, `File`, `Long`, `Exception`,
  `StackTraceElement`, and `Debug`. The single Application-object use is
  `CallVoidMethod com.pairip.application.Application.?` with the
  `com.google.android.gms.play.integrity.autoprotect.LOG_TELEMETRY` Intent,
  i.e. a telemetry broadcast, not a license query.
- Static basis: only
  `com.pairip.application.Application.attachBaseContext` references
  `LicenseClient`; `VMRunner`, `StartupLauncher`, and `SignatureCheck` do
  not. The Application static initializer calls `StartupLauncher.launch()`
  before `attachBaseContext` starts the license check, so the startup
  program cannot consume a license result that does not exist yet.
- Invariants: reader slots `bbb9eb6daed20ed0` / `015370f87525d57b`; append
  `src=0x7ff9372630 n=46 ... off=0x3e170
  fields=0000007a8c7f8ce8,0000000000000000,bbb9eb6daed20ed0,015370f87525d57b
  mapped=0`; `vm[0x540]` = `00000000f9372668`/`0000007ff93726f9`;
  `vm[0x7c0]` ends in `0x31bb`; final state `id=0x872a21c sel=0x31bb
  reader=0xf9372668`; builtin tail `0x9a0c0031`/`0x40a73`.
- Crash: unchanged, signal 11 in `__memmove_aarch64_nt+140`, frame #04
  `split_config.arm64_v8a.apk (+0)`, at 02:11:36.440; process exit by signal
  11 at 02:11:36.457.
- Image caveat: `license-consumption-trace-image.bin`, sha256
  `112b6e592c18c9877b90b8f0df2c2d4dd037bf0742bacbd914a03eed242011fa`,
  differs from the game-application dump in 368 bytes/79 ranges, the whole
  known variable set. `copy-hook` dumps at `ExecuteProgram` entry, so tracing
  shifts the snapshot to a different initialization phase. The dump is not a
  crash-time image; final-state invariants are the comparison basis.
- Verdict: klB does not read `LicenseClient` state. The hosted-only bind
  failure is unrelated to the reader path and must not be bypassed. Move to
  the reader-source probe at `+0x3dc88`: identify what should supply the
  reader's `w8`/`w9` pair, or why the `0x31` handler runs against the
  pristine slots. Do not repeat identity/maps/context/application/license
  experiments.

## reader-source result (2026-10-28 02:24)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen diagnostic baseline plus one observation-only hook at
`+0x3e034` ran one cold launch. The reader-source hypothesis is
**CONFIRMED**: the failing reader consumes the unrelocated image slots.

- Build/install: no build; APK sha256
  `5be5c199376dc5f4d0849a6bd88593429e78d1a2a00a86734b76acf18a124428`.
  The installed base APK has the same sha256, `versionCode=1`,
  `lastUpdateTime=2026-10-28 02:23:47`. The game APK was not modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 205` ms, one
  launch only (pid 24061). Log
  `tmp/cifi-apk/logs/verify/reader-source-trace.log`, 8,820 lines, sha256
  `5c1897538124867bf3c57e6a5ee5331545147ab69d5b506f7a09b6d350f0302b`.
- At the load: `x10=0xb400007b1224d010` is the mapped VM image base,
  `w8=0x3f874`, `w9=0x1ba5c`, `image[w8]=bbb9eb6daed20ed0`,
  `image[w9]=015370f87525d57b`. Both are exactly the pristine asset words.
  `descw[2] & 0xffffffff = 0xb0` is the descriptor index. The image-write
  ring (`pos=2209`) has no entry at either offset, and neither slot is in
  the known 368-byte/79-range variable set.
- Probe-field caveat: the logged `index=0xf93727d8` is `[sp+0x540]` (w29),
  the reader pointer, not the descriptor index; the logged `fetch=0x1f811`
  is the last dispatcher fetch, not the nested `0x31` pc. `entry_ok=0` is a
  consequence of reading `image+0xf93727d8`, not of an invalid `0xb0`
  entry. The pre-registration's `index=0xb0` / `fetch=0x40a73` referred to
  fields the shim does not print; the falsification criteria themselves all
  pass.
- Invariants: reader slots `bbb9eb6daed20ed0` / `015370f87525d57b`; append
  `src=0x7ff93727a0 n=46 ... off=0x3e170
  fields=0000007a8a7d7ce8,0000000000000000,bbb9eb6daed20ed0,015370f87525d57b
  mapped=0`; `vm[0x540]` = `00000000f93727d8`/`0000007ff9372869`;
  `vm[0x7c0]` ends in `0x31bb`; final state `id=0x872a21c sel=0x31bb
  reader=0xf93727d8`; builtin tail `0x9a0c0031`/`0x40a73`.
- Crash: unchanged, signal 11 in `__memmove_aarch64_nt+140`, frame #04
  `split_config.arm64_v8a.apk (+0)`, at 02:24:01.606; process exit by signal
  11 at 02:24:01.633.
- Verdict: there is no alternate buffer and no earlier writer on the hosted
  path. The reader is reading the asset's own ciphertext at literal offsets,
  so the hosted/bare difference must be upstream of the reader: either a
  different descriptor/opcode path reaches `0x31`, or the state that makes
  `0x31` legal on a bare launch was never established. Do not re-probe the
  reader source or repeat identity/maps/context/application/license
  experiments.

## Closed lead: tagged offsets at `vm[0x740]` (2026-10-28)

Status: closed statically on 2026-10-28; see the closure section at the end
of this document. The questions below are kept as the record of what was
asked, not as open work.

The crash-time interpreter frame holds a table of `{ctx, tag|offset}`
entries, and it contains both failing offsets:

```
vm[0x750] 0x0000007ff9372b88  ctx
vm[0x758] 0x6018f1f30003f874  tag=0x6018f1f3 offset=0x3f874
vm[0x760] 0x0000007ff9372b88  ctx
vm[0x768] 0x1e9de6930001ba5c  tag=0x1e9de693 offset=0x1ba5c
vm[0x770] 0x0000007ff9372b88  ctx
vm[0x778] 0x000011c200019b5e  tag=0x000011c2 offset=0x19b5e
```

The descriptor index `0xb0` also appears at `vm[0x640]` as
`0x0042117b000000b0`. The low words are the literal offsets the reader later
loads; the high words look like per-entry tags/cookies. Open questions, in
priority order:

1. Where are `vm[0x750..0x778]` written, and by which code path? A writer in
   the hosted path that stores `{ctx, tag|offset}` would show whether the
   table is built from the descriptor or from a separate relocation list.
2. How does the `0x31` handler select its two offsets? If it reads them from
   this table, the tags are selector state; if it recomputes them from the
   descriptor, the table is downstream and not the gate.
3. What do `0x6018f1f3`, `0x1e9de693`, and `0x000011c2` mean? Test
   candidates: a type/length tag, an obfuscated index, or an integrity
   cookie checked before the load. Do not guess a meaning without a writer
   or a consumer.
4. Characterize `+0x702e4`: `IsSameObject([0x982e8], x20)` is `(0,0)->1` in
   the hosted run and branches to `+0x70324`. Determine what a non-null
   first argument would do on a bare launch and whether that path avoids the
   `0x31` reader. This is a branch question, not a repeat of the closed
   JNI/context experiments.

## Tagged-offset table closed statically (2026-10-28)

No device run at the time of this decode: no handset was attached (`adb
devices` empty, no phone on `lsusb`). The device was attached later the same
day for the stock-libmain control below. This section is a static decode of
`libpairipcore.so` from `/tmp/pairip-analysis/libpairipcore_full.asm`; it
closes the four questions in the `vm[0x740]` lead above. Matching the
precedent of the earlier static map sections, it adds no walkthrough entry.

- Writer: the table is written by the reader builder itself, not by an earlier
  relocation pass. `+0x3e000`, `+0x3e008`, and `+0x3e010` store `d0` (the
  64-bit reader context, observed `0x7ff9372b88`) at `[sp+0x750]`,
  `[sp+0x760]`, and `[sp+0x770]`. `+0x3e004`, `+0x3e00c`, and `+0x3e028`
  store only the 32-bit `w8`, `w9`, and `w10` at `[sp+0x758]`, `[sp+0x768]`,
  and `[sp+0x778]`. No instruction writes the upper halves `[sp+0x75c]`,
  `[sp+0x76c]`, or `[sp+0x77c]`, and nothing in the reader reads them. The
  observed low words, `0x3f874`, `0x1ba5c`, and `0x19b5e`, match the stored
  registers exactly.
- Meaning: the record is `{void *ctx; uint32_t off; uint32_t pad;}`. The high
  words are uninitialized stack padding, not tags, cookies, or relocation
  indices. `0x6018f1f3`, `0x1e9de693`, and `0x000011c2` have no producer and
  no consumer on this path, so they must not be interpreted. The
  `0x0042117b` at `vm[0x640]` is the high half of the descriptor word
  `descw[2]` (`0x0042117b000000b0`), not a table tag.
- Selector: `+0x3e034`/`+0x3e038` load `[image+w8]` and `[image+w9]` from the
  registers, not from `[sp+0x758]`/`[sp+0x768]`. The offsets are computed
  immediately above by the builder from the descriptor block (index `0xb0`)
  and the five globals. The table is write-only on this path, so it cannot
  select the reader and there is no separate writer to find.
- `+0x702e4`: `tst w0, #0xff; b.ne 0x70324`. `IsSameObject(NULL, NULL)`
  returns 1 on the hosted path, so the branch goes straight to `+0x70324`. A
  non-null first argument would return 0 and fall through to the
  pending-exception check at `+0x702ec`; with or without a pending exception,
  that block also reaches `+0x70324` (the throw path rejoins at `+0x70320`).
  There is no alternative successor, so this block cannot avoid the `0x31`
  reader.
- Image check: the pristine asset (`sha256 112b6e59…`, 282,603 bytes) and the
  crash-time image (`stack-len-plus-one-image.bin`, `sha256 28b923f5…`) are
  byte-identical at `0x3f874`, `0x1ba5c`, `0x19b5e`, `0x40a73`, and
  `0x1f811`. They differ in 367 bytes across 78 ranges, the known variable
  set. The reader slots are untouched by whatever varies between hosted runs.
- Closure: questions 1-4 above are answered. The `vm[0x740]` table is
  downstream of the builder and carries no selector state. The remaining
  question is not who writes the table but which load chain lets the `0x31`
  handler run against the unrelocated image slots: the hosted path does, and
  a bare launch does not. That was pre-registered as the
  `stock-libmain-reader-slots` control and has since been run; the verdict is
  below.

## Pre-registered next experiment (2026-10-28, stock-libmain-reader-slots)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

With the `vm[0x740]` table closed, the next single variable is the loader
chain, not another image writer. The walkthrough's 2026-10-27 line-166 claim
that the `stock-libmain` chain computes the same five globals and reader
offsets has no surviving run key or log and is UNVERIFIABLE; this control
re-establishes it with the reader-source probe.

- Hypothesis: with the game's own `libmain` from the split APK and no Fusion
  (`pairip_stock_libmain=true`), klB still reaches `+0x3e034` with
  `w8=0x3f874`, `w9=0x1ba5c`, the pristine words
  `bbb9eb6daed20ed0`/`015370f87525d57b`, and the baseline
  `__memmove_aarch64_nt+140` crash.
- Falsification: klB runs, but the reader offsets or loaded words differ, the
  `reader-source:` line is absent, or the `0x872a21c`/`0x31` state is not
  reached. Then the launcher-libmain/DT_INIT chain is the discriminator, and
  the next probe is the earliest divergence between the two chains.
- One change from the frozen diagnostic baseline: `pairip_stock_libmain=true`.
  `preload`, `trace`, and `dumpVmImage` stay true; `stackSpoof` and
  `contextSeed` stay at their defaults; `lateHooks` is forced by `preload`.
- Status: completed at 2026-10-28 08:27; verdict appended below. The
  installed APK was already the reader-source build, so no install was done.

## stock-libmain-reader-slots result (2026-10-28 08:27)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The frozen diagnostic baseline plus `pairip_stock_libmain=true` ran one cold
launch. The pre-registered hypothesis is **CONFIRMED**.

- Build/install: no build; install skipped because the installed `base.apk`
  already hashes to
  `5be5c199376dc5f4d0849a6bd88593429e78d1a2a00a86734b76acf18a124428`
  (`versionCode=1`, `lastUpdateTime=2026-10-28 02:23:47`). The game APK was
  not modified (`versionCode=3022`, `versionName=0.7.3.64`,
  `lastUpdateTime=2026-10-22 16:35:02`).
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 226` ms; one cold
  launch (pid 32473). Both packages were force-stopped, `pidof` was empty
  for both, and logcat was cleared first. Log
  `tmp/cifi-apk/logs/verify/stock-libmain-reader-slots.log`, 14,661 lines,
  sha256
  `60a30e02e291e0a420993664a7a6354dc1065ea26f4b10c885be0bcb1c31b333`.
- Variant line: `stockLibmain=true`, `preload=true`, `trace=true`,
  `dumpVmImage=true`, `stackSpoof=true`, `contextSeed=true`,
  `lateHooks=true`, `delayMs=0`; every other experiment switch false.
  `hiddenApi=true` is also in the reader-source baseline.
- Branch: `PairIP preload: stock
  libmain=.../split_config.arm64_v8a.apk!/lib/arm64-v8a/libmain.so` at
  08:27:10.912; `trace: reader-source hook rc=0 target=0x7a8b59f034
  trampoline=0x7e1b4ef2c0` at 08:27:10.913.
- Reader: `ctx=0x7ff9372088 image=0xb400007b1224d010 off_data=0x3f874
  off_size=0x1ba5c data=bbb9eb6daed20ed0 size=015370f87525d57b`;
  descriptor index `0xb0`. The append record at `off=0x3e170` carries the
  same two words. Final state `state[127417] id=0x872a21c sel=0x31bb
  reader=0xf9371cd8`; builtin tail `0x9a0c0031 pc=0x40a73`.
- Crash: unchanged signal 11 in `__memmove_aarch64_nt+140` at 08:27:11.702,
  frame `#04` `split_config.arm64_v8a.apk (+0)`; process exit by signal 11
  at 08:27:11.730.
- Image: `klB-image-at-crash.bin`, 282,603 bytes, sha256
  `50648e90916e9fd48d3ea89332c798422d5bd2854a4fa395b681f6fb58aa9919`.
  Byte-identical to the pristine asset at `0x3f874`, `0x1ba5c`, `0x19b5e`,
  `0x40a73`, `0x1f811`, and `0x24f47`; 368 bytes across 79 ranges differ
  (the known variable set). Vs `stack-len-plus-one-image.bin`: 76 bytes
  across 34 ranges, one new-only byte at `0x14a9e`.
- Pre-registration defect: `PairIP preload: stock libmain completed` is not
  printed because the crash occurs inside `NativeLoader.load(...)` before the
  call returns. The branch is proven by the `stock libmain=...` line, the
  hook line, and the reader/klB trace. This is a labeling defect, not a
  failed control.
- Verdict: the game's own `libmain`, loaded from the split APK without
  Fusion, still reaches `+0x3e034` with the same offsets, the pristine
  words, and the identical crash. The launcher-libmain/Fusion/DT_INIT chain
  is ruled out as the hosted-only discriminator. The 2026-10-27 line-166
  `stock-libmain` claim is now CONFIRMED with a surviving log.
- Next: the remaining fork is the branch/state that reaches pc `0x40a73`,
  or the CD view the reader expects. See the Open questions and Next steps
  items 17-18. Do not repeat the loader, license, application, map,
  identity, or stack-text experiments.

## Static correction (2026-10-28): the 14:55 handler computes `0x4d94`

No new device run. This section corrects the earlier attempt to explain the
`0x22725` pc by sweeping handler inputs against the 08:27 crash image.

- The 14:55 `state-ring` run's handler-entry image was reconstructed by
  replaying all 2,209 `imagew` writes from `state-ring.log` against the
  pristine 282,603-byte VM asset. Every old-value check matched (0
  mismatches), so the replay is exact for the recorded writes. Output:
  `/tmp/opencode/image-14-55.bin`, sha256
  `1c4570b26620bf47033b1636500528875b4a5e23e432f53d20292393bb690f97`.
- Emulating the `0x7e` handler at `+0x6ff60` on that image reproduces the
  first hash exactly: computed `x13=0x3f1c3aa5e22a8c4d` equals the expected
  word, `w8=0xa1c832fc`, selected constant `w13=0x4515d1f9`, and target
  `(w8 ^ w13) % 0x44feb = 0x4d94`. The crash-time VM dump contains `0x4d94`
  at `vm[0x468]` (the `vm[0x440]` line, sixth word), so the handler did run
  and compute that target on the device.
- The 08:27 crash image is the wrong input for the 14:55 run. The five
  globals at `0x1da6f`, `0x1f422`, `0x2129a`, `0x377ff`, and `0x38edd`
  differ between the 14:55 pre-handler image and the 08:27 crash image; for
  example `0x1da6f` is `0x10b65edb` in 14:55 and `0x534a6211` in 08:27.
  Replaying or sweeping the handler against the 08:27 image therefore cannot
  reproduce the 14:55 target. The earlier `0x365e5` result from that image is
  expected and is not evidence about the 14:55 path.
- The handler's only store to the image on this path is the 32-bit 0/1 flag
  at `+0x7044c` (`str w14, [x13, w12, uxtw]`, `cset w14, eq`); it does not
  write the five globals. The final global values come from the nested
  program that runs after the handler selects the next pc.
- The image diff is sampled at the next dispatcher entry, so native writes
  made by the current handler appear in the crash-time image but not in the
  write ring. The replay therefore reconstructs the handler-entry image, not
  the post-handler image.
- `0x22725` is not the outer next fetch. The VM ring ends at
  `fetch=0x1f811 op=0x7e`, and the crash occurs inside that handler before
  the next dispatcher entry. The builtin ring shows `0x22727` as a nested
  builtin-prep pc (`builtin[11194]`, after `builtin[11193] pc=0x1f813`),
  followed by the reader path. The next question is who starts that nested
  program: the VM-driven handler tail or the JNI/vtable call at `+0x83478`.

## Pre-registered experiment (2026-10-28, nested-start-caller)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

- Hypothesis: the nested program that reaches builtin prep pc `0x22725` is
  entered through the `[state+0x8]` vtable call at `+0x83478` (vtable
  `+0x720`) from the `0x7e` handler's tail, not by a second dispatcher fetch.
  The handler entry at fetch `0x1f811` computes `w8^w13 = 0x4d94`, stores it
  as `ctx[1]`, and the vtable call is the first frame above the nested
  builtin.
- Falsification: the builtin stack scan shows no libpairipcore return address
  at or above `+0x83478`, or the handler entry is not reached with
  `fetch=0x1f811`, or the pre/post-xor values differ from the replay. Then
  the nested program is started elsewhere (a different handler tail, a JNI
  callback, or a second dispatcher entry) and the observed caller chain is
  the new lead.
- One change from the frozen diagnostic baseline: add observation-only hooks
  at `+0x6ff60`, `+0x70540`, and `+0x7ea14`, plus a stack scan in the
  existing builtin trace at pc `0x22725`. `preload`, `trace`,
  `dumpVmImage`, `stackSpoof`, `contextSeed`, and `hiddenApi` stay at their
  baseline values; no other experiment switch changes.
- Status: pre-registered; no run yet.

## nested-start-caller result (2026-10-28 10:02)

The three handler hooks ran and confirm the 14:55 replay; the caller-chain
hypothesis is **INCONCLUSIVE** because the stack scan used the wrong prep pc.

- Run log: `tmp/cifi-apk/logs/verify/nested-start-caller.log`, 10,906 lines,
  sha256
  `de345ed516aa54f0720cce840ba60f77dce4f6016843051e27148ce1495e8de1`.
  One cold launch (pid 13301); installed APK sha256
  `a4d831761ddbfd239a6dc72c6dcb5d7fae9819c7e319181de76f86f1530fe36b`;
  game APK untouched.
- Handler: `state=0x7ff9372bd0 ctx=0x7ff9372b88
  image=0xb400007b1224d010 len=0x44feb ctxpc=0x1f813 desc=0xb0
  fetch=0x1f811 op=0x7e`; pre-xor `w8=0xa1c832fc w13=0x4515d1f9
  target=0x4d94`; post-xor `w8=0xe4dde305 quotient=0x3513 rem=0x4d94
  w21_before=0xa0d82eed`. This matches the 14:55 image replay exactly. The
  crash-time VM dump has `0x4d94` at `vm[0x468]`.
- Nested program: after `builtin[11258] pc=0x1f813`, the ring shows
  `builtin[11259] id=0x40b0000c pc=0x22727`, then 25 more builtins ending at
  `builtin[11284] id=0x9a0c0031 pc=0x40a73`. The actual prep pc is
  `0x22727`, not `0x22725`.
- Stack scan: zero lines. The callback condition was `pc == 0x22725`, so
  it did not fire. This is an observation-filter defect, not a failed
  mechanism prediction; the caller chain is still unknown.
- Image: `nested-start-caller-image.bin`, 282,603 bytes, sha256
  `ced13875d79b69aefa2d5148bf24ff028ca94a2affaa001c01a8dd3970e95617`.
  The five globals are `0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`,
  `0xbfe458cb`, `0x4d062af3` (the 08:27 final values); the reader slots
  `0x3f874`/`0x1ba5c` and `0x1f811` are pristine. Vs the 14:55 image: 120
  bytes across 45 ranges, all from the nested program.
- Crash: unchanged signal 11 in `__memmove_aarch64_nt+140` at 10:02:41.358.
- Verdict: handler target CONFIRMED; caller chain INCONCLUSIVE.
- Next: correct the scan filter to `0x22727`, widen the window, and re-run.

## Pre-registered experiment (2026-10-28, nested-start-caller-pc22727)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

- Hypothesis: at the builtin prep pc `0x22727`, the stack above the builtin
  interpreter entry contains a libpairipcore return address at or above
  `+0x83478` (the vtable `blr` at `+0x83484` returns to `+0x83488`), proving
  that the `[state+0x8]` vtable call at `+0x83478` started the nested
  program. The handler target `0x4d94` and the nested global rewrite are
  already confirmed and are not being re-tested.
- Falsification: the stack scan at `0x22727` finds no return address in
  `[+0x83478, +0x83500)`; then the nested program is entered through a
  different frame and the observed caller chain replaces the hypothesis.
- One change from the previous run: the builtin stack-scan filter is
  `pc == 0x22727` (also accepting `0x22725` for safety) and the scan window
  is 256 words. The three handler hooks, the baseline extras, and the launch
  command are unchanged.
- Status: pre-registered; no run yet.

## nested-start-caller-pc22727 result (2026-10-28 10:06)

The corrected filter fired, but the stack scan found no libpairipcore word;
the vtable-caller hypothesis is **INCONCLUSIVE** and the zero-hit result is
the new discriminator.

- Run log: `tmp/cifi-apk/logs/verify/nested-start-caller-pc22727.log`,
  7,375 lines, sha256
  `76b64d05d3e73902b1960904e9d1d7161c0e384404ee19ce24aa929641d8c47b`.
  One cold launch (pid 14846); installed APK sha256
  `b65b73adf81da07f50e245293bb2621abc7dcdf582659d1a48dc6ea9a4d998d0`;
  game APK untouched.
- Handler lines are byte-identical to `nested-start-caller`:
  `state=0x7ff9372bd0 ctx=0x7ff9372b88 fetch=0x1f811 op=0x7e
  x30=+0x6aa68`; `w8=0xa1c832fc w13=0x4515d1f9 target=0x4d94`;
  `w8=0xe4dde305 quotient=0x3513 rem=0x4d94 w21_before=0xa0d82eed`. The
  handler target `0x4d94` is CONFIRMED again; the crash-time VM dump has it
  at `vm[0x468]`.
- Nested program: unchanged; `builtin[11259] id=0x40b0000c pc=0x22727`,
  then 25 more ending at `builtin[11284] id=0x9a0c0031 pc=0x40a73`.
- Stack scan: the filter fired exactly once:
  `trace: nested-stack id=0x40b0000c pc=0x22727 lr=+0x6adc4
  x29=0x5ddd74f5 sp=0x7ff9372b30`. No `trace: nested-stack[...]` hit line
  followed: zero module-range words in the 256-word (`0x800`) window.
- Why zero hits is informative, not just a failed observation: the logged
  `sp` is executeVM's frame base. The dispatcher call at `+0x6aa60` is
  `add x0, sp, #0xa0; bl 0x6e918`, and the logged handler state is
  `sp + 0xa0`. The `+0x6a968` prologue saves its caller's LR at `sp+0x1c8`
  (`stp x29, x30, [sp, #-0x60]!` then `sub sp, sp, #0x1c0`). A
  libpairipcore caller, including the `+0x83478` vtable call returning to
  `+0x83488`, would place a module-range word in the scanned window. Either
  the nested VM is entered from outside libpairipcore (JNI/libart path), or
  the scan/read is defective. The immediate LR `+0x6adc4` is only in x30,
  not on the stack, so it does not satisfy the scan.
- Image: `nested-start-caller-pc22727-image.bin`, 282,603 bytes, sha256
  `b3fadc956f6943fef286e79a4137fe66bde666125cbc360c2aa57cdbbd54be65`;
  46 bytes differ from the 10:02 image. The five globals are the 08:27
  final values `0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`,
  `0x4d062af3`; the reader slots `0x3f874`/`0x1ba5c` and the `0x1f811`
  fetch word are pristine.
- Crash: unchanged signal 11 in `__memmove_aarch64_nt+140` at
  10:06:43.102; exit by signal 11 at 10:06:43.128.
- Verdict: handler target CONFIRMED; caller chain INCONCLUSIVE.
- Next: identify the object and vtable target at the handler entry, and dump
  the executeVM saved-LR slot at the builtin entry.

## Pre-registered experiment (2026-10-28, nested-start-caller-vtable)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

- Hypothesis: the nested program is started through the `[state+0x8]`
  object's vtable `+0x720` at `+0x83478`. That target is either
  `executeVM_inner` (`+0x6a968`) or an external JNI/libart entry; the
  zero-hit stack scan is explained by which one it is.
- Falsification: if the target is not callable, or the `sp+0x1c8` saved-LR
  slot is not `+0x83488` while the target is `+0x6a968`, the handler-tail
  vtable call is not the nested entry; the target's mapping becomes the new
  caller lead.
- One change from the previous run: observation only at the two existing
  hook sites. At `+0x6ff60`, log `obj=[state+0x8]`, `vt=[obj]`,
  `fn=[vt+0x720]`, their in-module offsets, and `dladdr(fn)`'s module and
  offset (with a `/proc/self/maps` fallback). At the builtin entry for pc
  `0x22727`, log the raw slots `sp+0x1b8`, `sp+0x1c0`, `sp+0x1c8`, dump 64
  words from `[sp, sp+0x200)`, and widen the module-range scan to 2,048
  words (`0x4000`), printing the count and first 32 hits. The three handler
  hooks, the baseline extras, and the launch command are unchanged.
- Expected discriminator: `nested: handler ... obj=... vt=... fn=...
  fn_mod=...` and `trace: nested-stack-saved ...`. A non-libpairipcore `fn`
  with `saved[0x1c8]` outside the module proves the JNI/callback branch; a
  `fn=+0x6a968` with `saved[0x1c8]=+0x83488` proves the direct vtable
  branch and marks the earlier zero-hit scan as defective. Handler values
  and crash signature must stay unchanged.
- Status: pre-registered; no run yet.

## nested-start-caller-vtable result (2026-10-28 10:16)

The vtable-caller hypothesis is **RULED OUT**; the nested execution is a JNI
native-method entry, **CONFIRMED**.

- Run log: `tmp/cifi-apk/logs/verify/nested-start-caller-vtable.log`, 7,571
  lines, sha256
  `212f5cee8ab5b4418ce94fe6035818ec1bf62650cd5035634ea61a9ea9746123`.
  One cold launch (pid 19609); installed APK sha256
  `ac20d71c3e32ae7e2883797601eab887fe1cb00c819a0bc04b3d1315a555b155`;
  game APK untouched. `am start -W` printed `Status: ok`,
  `LaunchState: UNKNOWN (0)`, `WaitTime: 3011`; the process was cold by
  construction (both packages force-stopped, `pidof` empty, logcat cleared).
- Handler object: `[state+8]` = `0xb400007bb465c2d0`, a scudo heap object
  whose `[obj]` is `0x7b51e19598` (libart mapping). `[0x7b51e19598+0x720]` =
  `0x7b518533b0` (libart mapping), not `+0x6a968`.
- JNI identification: with the NDK `jni.h` table layout, index
  `0x720/8 = 228` is `ExceptionCheck`; the handler tests its return with
  `tst w0, #0xff` at `+0x83488`, and the next call at `+0x83490` reads index
  `0x78/8 = 15` = `ExceptionOccurred`. `+0x83478` is the handler's JNI
  exception check. It does not start the nested program.
- Native entry: `+0x8b1f0` does `adr x8, 0x6a968`, stores it in a
  `JNINativeMethod.fnPtr` slot, then calls `[x20]+0x6b8` = index 215
  `RegisterNatives`. `executeVM_inner` is registered as a native method.
  Inside it, `+0x6a9b4` calls index 171 `GetArrayLength(env, x2)`,
  `+0x6a9c8` allocates that length, `+0x6a9dc` `memset`s it, and `+0x6a9f8`
  calls index 200 `GetByteArrayRegion(env, array, 0, length, buffer)`. The
  native input is a Java byte array (the VM image length `0x44feb`).
- Stack confirmation: `guard` read at `sp+0x1b8` matches the raw word at
  that frame slot; 64/64 words are readable, so `sp` is executeVM's frame
  base. The saved-LR slot `sp+0x1c8` is `0x7b51488274`, outside
  `[0x7a90c9c000, 0x7a90d2c000)` (libart-side caller), and the 2,048-word
  scan finds only `+0x6b90c` at index 566, a deeper executeVM return
  address. The earlier zero-hit 256-word window was too shallow, not a
  broken read.
- Invariants: handler/pre-xor/post-xor and the reader-source line are
  unchanged (`0x4d94`, `w21_before=0xa0d82eed`); the crash is unchanged
  signal 11 in `__memmove_aarch64_nt+140` at 10:16:57.605. The image
  `nested-start-caller-vtable-image.bin`, 282,603 bytes, sha256
  `581ca63f7b6a2d93580a2b0a31a164331a83902796d110b00549aae55351bf73`,
  differs from the `pc22727` image in 53 bytes; the five final globals and
  pristine reader slots are unchanged.
- Verdict: vtable-caller RULED OUT; native/JNI entry CONFIRMED.
- Next: identify the registered native method name/signature/class, then the
  Java caller that supplies the byte array.

## Pre-registered experiment (2026-10-28, nested-register-natives)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

- Hypothesis: `+0x8b1f0` registers `executeVM_inner` as a native method whose
  name and signature identify the Java-side VM entry and its `byte[]` input;
  the names then locate the Java caller and the array source statically.
- Falsification: the `JNINativeMethod` strings are unreadable, the call is
  not `RegisterNatives`, or the signature does not take a `byte[]`; the
  observed registration then becomes the new lead.
- One change from the previous run: observation-only full-register shim on
  the `adr x8, 0x6a968` at `+0x8b1f0`, logging `env`, `jclass`, `methods`,
  `nMethods`, and the `name`/`signature`/`fnPtr` fields, with `fnPtr`'s
  module and mapping offset. The relocated `adr` in the trampoline
  overwrites `x8`, so `x8` is safe as the tail scratch. All other hooks,
  extras, and the launch command are unchanged.
- Expected discriminator: `register: env=... class=... n=1 name=<method>
  sig=<signature> fn=+0x6a968 fn_map=libpairipcore.so+...`. A `[B` signature
  confirms the byte-array native input.
- Pre-run instrumentation note: the hook sits on `+0x8b1f4`
  (`str x8, [sp, #0xc0]`), not `+0x8b1f0`, because only there has the `adr`
  produced the `fnPtr` now in `x8`. The name/signature are read from the
  caller frame at `[sp+0xb0]`/`[sp+0xb8]`; `x9` is dead and is the
  tail-branch scratch, so the relocated `str` keeps `x8`.
- Status: pre-registered; no run yet.

## nested-register-natives result (2026-10-28 10:23)

The hook installed but never fired; the registration question is
**ANSWERED statically**, so the run is not repeated.

- Run log: `tmp/cifi-apk/logs/verify/nested-register-natives.log`, 7,287
  lines, sha256
  `46f85919e86bbc751dad90b5b02d56008d11777ca3e65d1fdf7282b8eb57a9cc`.
  One cold launch (pid 23752, `Status: ok`, `LaunchState: COLD`,
  `TotalTime: 282`); installed APK sha256 `807ea87a...`; game untouched.
- Miss: `trace: register-native hook rc=0 target=0x7a974971f4` at
  10:23:31.655, but no `register:` line. `+0x8b1f0` is inside `JNI_OnLoad`
  (`0x89c84`, size `6228 = 0x1854`), which runs during
  `System.loadLibrary("pairipcore")` at 10:23:31.426 (in `VMRunner`'s
  static initializer), 229 ms before the hook was installed by the
  post-factory trace setup. The hook site was correct; the timing was not.
- Registration, decoded from the `JNI_OnLoad` store sequence at
  `+0x8b0a4..+0x8b1f4`:
  - class: `com.pairip.VMRunner`
  - name: `executeVM` (9 bytes; libc++ short tag `0x12`)
  - signature: `([B[Ljava/lang/Object;)Ljava/lang/Object;` (41 bytes)
  - `fnPtr`: `libpairipcore.so+0x6a968` (`executeVM_inner`)
- Cross-check: `VMRunner.java:23` declares
  `public static native Object executeVM(byte[] vmCode, Object[] args);`
  and line 26 loads `pairipcore`. `VMRunner.invoke:57` calls it with
  `getVmByteCode` -> `readByteCode` (base-APK asset
  `assets/klB1wZSojiVvDvCN`) and `VmDecryptor.decrypt`, which returns the
  input untouched. The Java-side VM entry and byte-array source are
  therefore fully identified.
- Invariants: handler target `0x4d94`, `w21_before=0xa0d82eed`,
  `saved_lr=0x7b51488274`, crash and reader fallback unchanged. Image
  `nested-register-natives-image.bin`, 282,603 bytes, sha256
  `b52070a08f8b2eb66d478158b59cef2b1f2350ab69faeda845e1268a5626010c`;
  53 bytes differ from the vtable image; five globals and reader slots are
  identical.
- Verdict: hook INCONCLUSIVE; JNI registration CONFIRMED. Do not run
  `early-register-natives`. The remaining live question is the missing CD
  string view (`[state+0x20]` null; `+0x834dc`/`+0x834f0` setter unvisited;
  fallback to `image+0x3f874`/`0x1ba5c`).

## Pre-registered experiment (2026-10-28, nested-builtin-operand)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The next step is one diagnostic run against the frozen baseline
(`pairip_preload=true`, `pairip_trace=true`, `pairip_dump_vm=true`, debug
launcher). It observes the `0x9a0c0031` builtin boundary to decide whether
that builtin produces the missing CD view or only consumes the descriptor.

- Hypothesis: the `0x9a0c0031` builtin (effective opcode `0x31`) is the
  producer of the CD view. At entry it receives the operand block at
  `caller_sp+0x78` and the output slot at `caller_sp+0x74`; a pointer/size
  pair for the view should appear in the output slot, the operand block, or
  the image, while `image+0x3f874`/`0x1ba5c` stay pristine. If the builtin
  only consumes the descriptor and updates the five globals/hashes, no
  pointer/size pair will appear and the CD view must come from the loader
  or an earlier state.
- Falsification: the builtin writes the reader slots during the call, or
  the output/operand block contains no pointer-like and no size-like value
  at either boundary. The first contradicts the image-diff finding; the
  second means the `0x31` builtin is not the producer.
- Observation points: the existing builtin-entry hook at `+0x31de4`
  (filtered to `id & 0xffff == 0x31`, `pc == 0x40a73`, `lr == +0x6adc4`)
  and the existing append hook at `+0x8d460` (filtered to
  `lr == +0x3e170`, the reader builder's call inside the same builtin).
  A return hook at `+0x6adc4` is not used: the crash-time `executeVM`
  frame still holds `id=0x9a0c0031` and the fault is inside the append
  helper called from the reader builder, so the builtin does not return
  before the crash.
- Expected discriminator: `trace: b31-in` / `trace: b31-reader` dumps of
  the 32-word operand block, the 32-bit output slot, the instruction bytes
  at `image+0x40a73`, the descriptor table at `image+0xb0`, the five
  globals, and the reader slots. A pointer-like value with a companion
  size-like value at the reader boundary proves the builtin produced the
  view; an unchanged block with `out32=0` and pristine reader slots proves
  it did not.
- Status: run; verdict below.

## nested-builtin-operand result (2026-10-28 11:23)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).
The producer hypothesis is **FALSIFIED**.

- Run: one cold launch (pid 11283); both packages force-stopped and
  logcat cleared first. `am start -W` printed `Status: ok`,
  `LaunchState: COLD`, `TotalTime: 220`, `WaitTime: 225`. Installed
  launcher APK sha256
  `ff12214238f6e72d0102aa6f35bd92183f32b58c118b11228c8c90b8e48dd0a3`
  (`versionCode=1`, installed 11:22:46); game APK untouched.
- Log: `tmp/cifi-apk/logs/verify/nested-builtin-operand.log`, 7,777
  lines, sha256
  `56948c8856b562f360eb1d05d1fe6a48acca586428b819a8f5e596e2dc4d9b46`.
- Boundary dumps: `b31-in seq=12859 id=0x9a0c0031 pc=0x40a73
  image=0xb400007b1224d010 arg=0x7ff9372ba8 out=0x7ff9372ba4` with
  `out32=0x22b39` and reader slots
  `bbb9eb6daed20ed0`/`015370f87525d57b`; `b31-reader-sp
  pending_sp=0x7ff9372b30 append_sp=0x7ff9372250`; `b31-reader` repeats
  the same seq/id/pc/image/arg/out, the same 32-word operand block, the
  same instruction bytes at `0x40a73`, the same descriptor table at
  `image+0xb0`, the same five globals
  (`0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`, `0x4d062af3`),
  the same `out32`, and the same two reader slots.
- Reader-source line: `off_data=0x3f874`, `off_size=0x1ba5c`,
  `data=bbb9eb6daed20ed0`, `size=015370f87525d57b`,
  `fetch=0x1f811`, `fetch_bytes=e1daf3c012020400`; unchanged.
- Crash: unchanged signal 11 in `__memmove_aarch64_nt+140` at
  11:23:02.944; process exit by signal 11 at 11:23:02.962. Image
  `nested-builtin-operand-image.bin`, 282,603 bytes, sha256
  `7557e541fa96515dcd7b9c954b5377f01130aa42a58be0b28c19a7acfda9afff`;
  29 ranges / 53 bytes differ from the `nested-register-natives` image
  (ASLR and counter noise), 79 ranges / 368 bytes from the pristine
  asset; the reader slots and five globals are identical.
- Verdict: **FALSIFIED**. The `0x31` builtin did not create a
  pointer/size pair; `out32` stayed `0x22b39`, and the operand block,
  instruction/descriptor bytes, globals, and reader slots were unchanged
  between the entry and the reader append. The builtin consumes and
  validates the descriptor; it does not supply the CD view.
- Consequence: the producer is earlier than the `0x31` call. The
  remaining live target is the caller frame at `+0x6adb4`
  (`sp+0x44` id, `sp+0x74` out, `sp+0x78` operand block) and the
  `0x40a73` instruction, or the stock-only branch that avoids the
  reader. Do not repeat this run. The `split-cd-map` probe is
  pre-registered below.

### Maps-window refinement (2026-10-28)

The 2026-10-27 `apk-cd-map` result closed the **base-APK** `r--s
074f5000` line inside the scanned prefix. It did not test the **arm64
split APK's** `r--s 06627000` line. That distinction matters because the
VM's `Long` argument resolves to the split APK's libunity base
(`split_config.arm64_v8a.apk + 0x4eec000`), not to the base APK. The
split CD line starts at byte 368,362, still outside the VM's 146,432-byte
read window. The base-APK falsification therefore does not cover the
split path, and the `split-cd-map` run below is not a repeat of
`apk-cd-map`. If it is negative, the maps-window branch is closed for
both APK paths.

## Pre-registered experiment (2026-10-28, split-cd-map)

Run log and key: [`cifi-pairip-walkthrough.md`](cifi-pairip-walkthrough.md).

The next step is one diagnostic run against the frozen baseline
(`pairip_preload=true`, `pairip_trace=true`, `pairip_dump_vm=true`, debug
launcher) plus `pairip_split_cd_map=true`. It maps the installed arm64
split APK's real central directory at the low hint `0x7900000000` before
`libmain.so` runs, so an `r--s 06627000` line for
`split_config.arm64_v8a.apk` falls inside the VM's maps read window.

- Hypothesis: the VM's maps parser uses the split-APK CD mapping (the
  path that backs the libunity argument) to initialize the reader slots.
  The hosted run misses it because the real line starts at byte 368,362,
  outside the 146,432-byte window.
- Delta from `apk-cd-map`: that run mapped the base APK's CD at file
  offset `0x74f5000`; this run maps the split APK's CD at file offset
  `0x6627000`. The mapped path, file offset, and argument association
  differ; the low hint and the single-variable baseline are the same.
- Prediction: `split-cd-map: base=0x7900000000 size=4096
  hint=0x7900000000 fixed=1 offset=0x6627000 eocd=0x662730a`, with the
  matching `r--s 06627000 ... split_config.arm64_v8a.apk` line inside
  the first 146,432 bytes. A change in the five globals, the
  `bbb9eb6daed20ed0`/`015370f87525d57b` reader slots, the reader-source
  `off_data`/`off_size`, or the crash signature is a positive result.
- Falsification: the line is inside the prefix, but the five globals,
  reader slots, reader-source offsets, state/builtin tails, crash
  signature, and image diff are byte-identical to baseline. Then the
  split-APK CD line is not the gate, and the maps-window branch is
  closed for both APK paths.
- Invalid: mapping error, missing or out-of-prefix line, or an unrelated
  perturbation. The low mapping is diagnostic only and must not ship.
- Status: pre-registered; run pending.
