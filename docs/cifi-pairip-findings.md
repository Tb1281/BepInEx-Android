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

- SIGSEGV in `__memmove_aarch64_nt+144`, fault address shaped like
  `0xbbb9eb6daed20ed0`, return address `libpairipcore.so+0x8d514`.
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

## Experiments and results

| Experiment | Result |
| --- | --- |
| Write attribution / image diff | Both ZIP slots stay at their asset values; no writer found. |
| Fake central directory from base APK (`0x1281c` bytes) | SIGSEGV disappears, run continues to a CheckJNI `FindClass` SIGABRT. |
| Fake central directory from arm64 split (`0x30a` bytes) | Same behaviour as the base-APK run. |
| VM-pc-tagged class trace | Entry #9: `caller=+0x7295c`, `vm=0xf0df/0x30`, `idx=0x19ded`, `len=12928`. |
| Procfs trace | klB reads `/proc/self/maps` in 1 KB chunks through `0x23800` (141-142 reads), then `/proc/self/status`, `readlink` of the maps fd, six `clock_gettime`, then the failing read. |
| Served maps snapshot | Contains launcher-only `.so` paths (`libcoreclr.so`, `libfusion.so`, `libmscordacore.so`, ...); game libraries appear as split-APK mappings. |
| `memcmp` needle trace | Hook installed; the comparison operands that identify the maps needle have not been captured yet. |
| Image-watch (read-only first page) | Instrumentation added; result not yet recorded. |

Fake-CD runs are diagnostic only. Pointing the reader at a real central
directory proves the reader expects ZIP bytes, but it does not make the hosted
path valid and it must not become the fix.

## Open questions

1. How does a stock launch make `image+0x3f874` and `image+0x1ba5c` valid?
   Candidate explanations are a runtime image relocation/decryption step that
   the hosted path skips, or a PairIP environment gate that selects a different
   image.
2. Does the reader expect a full APK or a stream positioned at one central
   directory entry? The base and split fake-CD runs both advance past the
   crash, so the current evidence only proves the magic and length
   interpretation.
3. Is class-trace #9 (`0xf0df/0x30`) a fake-CD artifact or a genuine decoy path
   that stock also takes?
4. Which maps line does klB search for? Logging the `memcmp` operands and the
   caller should answer this; the served map deliberately hides launcher-only
   libraries.

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

1. Log the `memcmp` caller and return address to identify the maps needle and
   the `.so` check.
2. Determine whether the two ZIP slots are meant to be relocated at load time,
   and whether that step is skipped only in the hosted process.
3. Decide whether class-trace #9 is on the stock path; keep fake-CD labelled as
   diagnostic either way.
4. Only after a real mechanism is proven: apply the fix, build, reinstall with
   `android install`, run three cold launches, and record the results on
   #62-#64 before writing the ADR for #60.
