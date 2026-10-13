# Operations & Developer Guide

## 1. Patch Queue Management

Upstream changes live as serialized patches in `patches/`, replayed with
`git am --3way` onto the pin in `provenance/bepinex.env`:

```bash
./scripts/apply_patches.sh       # apply queue onto pinned commit; conflict aborts build
./scripts/sync_upstream.sh       # replay queue onto a new upstream tip
./scripts/report_patch_conflict.sh  # print failing patch, files, markers
```

Patches `0001`-`0013` cover: Android paths, logcat logging, the
`AndroidEntrypoint` native entrypoint, libfusion detour routing with export
thunk resolution, Android solution retargeting, Mono/runtime and console
driver removal, desktop target stripping, AArch64 export-thunk arithmetic,
Il2CppInterop/MonoMod version pins, ARM64 hook guards, and the Cpp2IL
published-prerelease pin. Never skip a patch: a skip ships an archive
missing that adaptation.

## 1b. NuGet Lock Files

Transitive versions float per restore (measured: identical pins compiled to
different assemblies on different machines), so every restored project
carries a `packages.lock.json` and every restore runs locked
(`RestoreLockedMode`), which fails loudly instead of substituting quietly:

- BepInEx solution and Il2CppInterop: locks vendored in
  `references/nuget-locks/<area>/` (mirroring the source layout), staged
  into the scratch trees by `apply_patches.sh` / `build_il2cppinterop.sh`.
- MonoMod: upstream ships its own locks in-tree; our build only enforces
  them. Our patch updates some; enforcement covers the patched state.

Regeneration is deliberate, like a manifest rebaseline: restore once
unlocked in a scratch tree, inspect the version diff, copy the locks over
the vendored copies, and commit. Never regenerate silently as part of a
normal build.

## 2. Native-to-Managed Contract (`libfusion.so`)

C-linkage exports consumed via P/Invoke by the managed framework:

```c
void write_log_level(int level, const char *text);
void *hook(void *target, void *detour, bool specialReturnBuffer);
void unhook(void *target);
```

- `write_log_level`: Android log priority 2-7; UTF-8 string owned by caller;
  thread-safe via `__android_log_write`.
- `hook`: returns the trampoline for the original, or null on failure;
  requires `libdobby.so` loaded. Never attach to a forwarding stub; all
  targets go through `ExportThunkResolver` first.
- `unhook`: reverts the hook via `DobbyDestroy`.

## 3. Export Thunk Resolution

ELF exports may be 4-byte `B` stubs or literal-pool veneers; a 16-byte Dobby
patch on one corrupts neighbors. The resolver decodes AArch64 `B` opcodes,
follows hops up to `MAX_HOPS = 8`, stops on cycles or null, validates the
target is in an executable mapping via `/proc/self/maps`, and throws
`InvalidOperationException` otherwise. On ARM64 it also refuses targets with
a `RET` in the first 16 bytes. Fail-closed: halt before attaching.

## 4. Deterministic Boot States

One tree builds debug and release (`./gradlew assembleDebug/assembleRelease`).
A successful launch transitions in order:

```
Launcher Init (.extracted-version) -> Native Injection (libfusion loads)
-> Runtime Init (CoreCLR boots) -> Managed Entry (AndroidEntrypoint.Start rc=0)
-> Thunk Resolution (within 8 hops) -> Chainloader Startup (first scene event)
-> In-Game (menu reachable, no unhandled exceptions)
```

Before installing over an existing build, back up its data if it matters.
Legacy `/sdcard/BepInEx_Android` layouts are no longer read; document that in
release notes rather than treating orphaned data as a regression.

## 5. Diagnostics

```bash
adb logcat -s fusion BepInEx Unity
adb logcat | grep -E '\[Error:Unity\]|\[Error:Il2CppInterop\]'
adb pull /sdcard/Android/data/com.bepinex.android/files/BepInEx/LogOutput.log ./
```

CoreCLR stdout may stay silent when Unity intercepts exceptions internally;
always check logcat.

## 6. Hook Safety and Activity Robustness

Inline-hook targets go through `ExportThunkResolver` (`NativeDetour`
constructor): resolve past stubs, refuse `RET` inside the first 16 bytes
(`CanSafelyHook`), validate Il2CppInterop xref candidates, and skip the
`MetadataCache` type-index hook on Android. Misses stay non-fatal and log.

`UnityLogListening` defaults to `false`: Unity already streams to logcat, so
listening duplicates traffic and forces early interop init.

`BootstrapActivity` declares full `configChanges`, ignores recreation state,
sets the launched flag only at real launch, and preserves orientation. This
prevents rotation-triggered restarts and background-launch blocks.
