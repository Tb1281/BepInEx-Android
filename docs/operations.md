# Operations & Developer Guide

This document specifies patch queue operations, native-to-managed API contracts, execution state machines, and diagnostic specifications for the BepInEx Android port.

---

## 1. Patch Queue Management

Changes to upstream BepInEx are maintained via serialized git mail patches in `patches/`. Each patch is created via `git format-patch` and replayed using `git am --3way`.

### Patch Operations

- **Apply Patch Queue to Pinned Commit**:
  ```bash
  ./scripts/apply_patches.sh
  ```
  Validates that all patches apply cleanly onto the pristine upstream commit (`5b766a3b`). Any conflict aborts the build.

- **Upstream Synchronization**:
  ```bash
  ./scripts/sync_upstream.sh
  ```
  Fetches upstream master, replays the queue with 3-way merge, and stops on conflicts for manual resolution (`git am --continue`).

- **Conflict Diagnostics**:
  ```bash
  ./scripts/report_patch_conflict.sh
  ```
  Parses conflict markers and prints failing patch names, conflicting lines, and 3-way diffs to `GITHUB_STEP_SUMMARY`.

### Active Patch Specifications

| Patch | Target Scope | Specification |
|---|---|---|
| `0001` | Core / Paths | Android path resolution, environment variables, and platform detection |
| `0002` | Core / Logging | Route BepInEx logs and console output to Android logcat (`__android_log_write`) |
| `0003` | IL2CPP / Entry | Replace Doorstop entry point with `AndroidEntrypoint` and add `NativeInterop` |
| `0004` | IL2CPP / Detour | Route detours through `libfusion` exports and implement `ExportThunkResolver` |
| `0005` | Build / Solution | Retarget solution and project files for Android IL2CPP runtime |
| `0006` | Core / Mono | Remove desktop Mono runtime references |
| `0007` | Core / Console | Remove legacy Unix and Windows desktop console drivers |
| `0008` | Build / Desktop | Strip desktop-specific targets (Cake build scripts, Doorstop loaders) |
| `0009` | IL2CPP / Arch | Correct AArch64 `B` relative branch displacement sign-extension math |
| `0010` | Build / Interop | Pin Il2CppInterop to Android-compatible `1.5.2-android.3` from local feed |
| `0011` | Build / MonoMod | Pin MonoMod to Android-compatible `25.3.7-android.1` from local feed |

---

## 2. Native-to-Managed API Contracts (`libfusion.so` ↔ `NativeInterop.cs`)

`libfusion.so` exports C-linkage primitives consumed via P/Invoke (`LibraryImport`) by the managed framework:

```c
extern "C" {
    void write_log_level(int level, const char* text);
    void* hook(void* target, void* detour, bool specialReturnBuffer);
    void unhook(void* target);
}
```

### Detailed Function Specifications

#### `write_log_level`
- **Managed Signature**: `void write_log_level(int logLevel, [MarshalAs(UnmanagedType.LPStr)] string message)`
- **Parameters**:
  - `level` (`int`): Android log priority (`2`=VERBOSE, `3`=DEBUG, `4`=INFO, `5`=WARN, `6`=ERROR, `7`=FATAL).
  - `text` (`const char*`): Null-terminated UTF-8 string. Memory owned by caller; must not be freed by native callee.
- **Thread Safety**: Thread-safe (backed by atomic Android logger `__android_log_write`).

#### `hook`
- **Managed Signature**: `IntPtr hook(IntPtr target, IntPtr detour, [MarshalAs(UnmanagedType.I1)] bool specialReturnBuffer)`
- **Parameters**:
  - `target` (`void*`): Absolute target function address in memory.
  - `detour` (`void*`): Address of the replacing detour function.
  - `specialReturnBuffer` (`bool`): Reserved for ABI return buffer handling (ignored on ARM64).
- **Return Value**: Returns pointer to the generated trampoline for calling the original function. Returns `nullptr` (`IntPtr.Zero`) on failure.
- **Preconditions**: `libdobby.so` must be loaded in the process address space.

#### `unhook`
- **Managed Signature**: `void unhook(IntPtr target)`
- **Parameters**:
  - `target` (`void*`): The original target address passed to `hook()`.
- **Behavior**: Reverts inline hook and frees allocated trampoline memory via `DobbyDestroy`.

---

## 3. Dynamic Export Thunk Resolution (`ExportThunkResolver`)

ELF dynamic linkers often export native functions (e.g., `il2cpp_runtime_invoke`) through 4-byte jump stubs or literal-pool veneers:
```assembly
B target_offset         ; 4-byte direct relative branch
; or
LDR X16, #literal_pool  ; 8-byte veneer
BR X16
```

Attaching a Dobby inline hook (which writes 16 bytes for a full AArch64 trampoline) directly onto a 4-byte stub corrupts adjacent symbols.

### Resolution Contract & Failure Modes

1. **Step-by-Step Traversal**:
   - Reads 32-bit little-endian instruction at current address.
   - Decodes AArch64 `B` opcode (`0x14000000` mask `0xFC000000`) and calculates sign-extended 28-bit displacement.
   - Max hop depth is strictly bounded to `MAX_HOPS = 8`.
2. **Cycle Prevention**: If next target equals current or `0`, loop terminates immediately.
3. **Memory Validation**: Validates that target address resides within an executable mapping (`r-x`) via `/proc/self/maps`.
4. **Failure Mode (Fail-Closed)**:
   - If target is null or not executable, throws `InvalidOperationException`.
   - Halts initialization before attaching dangerous hooks, preventing memory corruption or `SIGILL` under Pointer Authentication (PAC).

---

## 4. Deterministic Boot Acceptance Criteria

### Building test vs production

One tree builds both. The only difference is the build type (debug/release); there is a single `applicationId`, so installing over the existing production build replaces it — back it up first if its data matters.

```bash
# Debug and release builds — single package com.bepinex.android.
./gradlew assembleDebug
./gradlew assembleRelease
```

(Test/production variants were removed 2026-10-12; `-Pbepinex.applicationId` no longer exists.) There is no separate production source set, flavor or manifest. The promotion gate history is recorded in `docs/todo.md` under Phase 4.

**Before installing a production build over an existing one:** the installed build may still be using the legacy `/sdcard/BepInEx_Android` layout, which no current code reads. Installing over it orphans that data and the games re-extract into app-specific storage. Check `adb shell ls /sdcard/BepInEx_Android` first, and say so in the release notes rather than treating it as a regression afterwards.

To ensure reliable, specification-driven verification on device, a successful launch must transition through the following states in order:

```
[ State 0: Launcher Init ]
       │  Extraction stamp .extracted-version verified / assets decompressed
[ State 1: Native Injection ]
       │  libmain.so resolves and loads libfusion.so
[ State 2: Runtime Initialization ]
       │  il2cpp_init intercepted; CoreCLR boots with SYSTEM_CORELIB_DIRECTORY
[ State 3: Managed Delegate Invocation ]
       │  AndroidEntrypoint.Start() returns 0 (rc=0)
[ State 4: Export Thunk Resolution ]
       │  ExportThunkResolver resolves il2cpp_runtime_invoke within 8 hops
[ State 5: Chainloader Startup ]
       │  First Internal_ActiveSceneChanged event triggered; plugins executed; hook removed
[ State 6: In-Game Interactive State ]
          Main menu reachable; gameplay loop executing without unhandled exceptions
```

---

## 5. Diagnostics & Logging Standards

- **CoreCLR & BepInEx Engine Logs**:
  ```bash
  adb logcat -s fusion BepInEx Unity
  ```
- **Intercepted Game Exceptions**:
  ```bash
  adb logcat | grep -E '\[Error:Unity\]|\[Error:Il2CppInterop\]'
  ```
- **Physical Sandbox Logs**:
  ```bash
  # App-specific external storage (Android 11+)
  adb pull /sdcard/Android/data/com.bepinex.android/files/BepInEx/LogOutput.log ./
  ```