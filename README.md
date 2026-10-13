# BepInEx for Android

Runs [BepInEx](https://github.com/BepInEx/BepInEx) on arm64 Android IL2CPP games.

This project packages upstream BepInEx with an Android patch queue, targeting Microsoft's official `Microsoft.NETCore.App.Runtime.android-arm64` runtime pack (.NET 10 LTS) and native hook engines (`canyie/dobby`, `canyie/Pine`).

---

## Architecture Overview

```
[ Game Process ]
   └── libmain.so (JNI bootstrap)
         └── libfusion.so (CoreCLR host + Dobby inline hook engine)
               ├── Intercepts il2cpp_init & resolves export thunks
               └── Boots CoreCLR runtime (dotnet.zip + libcoreclr.so)
                     └── BepInEx.Android.zip
                           ├── AndroidEntrypoint.Start()
                           ├── IL2CPP Chainloader
                           └── Mod Plugins (.dll)
```

1. **Launcher & Process Isolation**: A launcher Android application hosts the target game activity in an isolated process (`:game`). Native injection loads `libfusion.so` via `libmain.so`.
2. **CoreCLR Hosting**: `libfusion.so` initializes the official Microsoft .NET 10 Android runtime.
3. **Detour Engine**: Dynamic export thunks (`il2cpp_runtime_invoke`) are resolved via `ExportThunkResolver` before inline hooks are attached using Dobby.
4. **Managed Framework**: Rebuilt upstream BepInEx IL2CPP chainloader, adapted for Android logcat, path conventions, and custom interop.

---

## Pinned Dependencies (Single Source of Truth)

All dependencies are strictly pinned to verifiable upstream commits.
The pin values live in `provenance/*.env` — **this table is a convenience summary
and is not the authoritative source.** When `sync-upstream` bumps a pin, it updates
the env file only; this table is regenerated from it.

| Component | Upstream Reference | Packaging Format |
|---|---|---|
| **BepInEx** | `BepInEx/BepInEx @ master` | `.env` Pin + Patches `0001`–`0011` |
| **.NET Runtime** | Microsoft NuGet Package | SHA-256 Verified Pack (`dotnet.zip`) |
| **MonoMod** | `MonoMod/MonoMod @ reorganize` | `25.3.7-android.1` (Local Feed) |
| **Il2CppInterop** | `BepInEx/Il2CppInterop @ master` | `1.5.2-android.3` (Local Feed) |
| **Dobby** | `canyie/dobby @ master` | `.env` Pin (16 KB Aligned) |
| **Pine** | `canyie/Pine @ master` | `.env` Pin (16 KB Aligned) |

See `provenance/*.env` for the actual commit values.

---

## Repository Structure

```
BepInEx-Android/
├── launcher/                  # Android launcher app (Kotlin + C++/CMake)
│   ├── app/src/main/cpp/      # libmain.so, libfusion.so (injection & CoreCLR host)
│   ├── app/src/main/jniLibs/  # Verified native runtime libraries (arm64-v8a)
│   ├── app/src/main/assets/   # Framework archives (BepInEx.Android.zip, dotnet.zip)
│   └── scripts/               # Prebuilt verification utilities
├── patches/                   # Replayable patches applied to upstream BepInEx
├── references/                # Source patches for pinned native deps
├── scripts/                   # Patch queue management, packaging, and validation
├── provenance/                # Upstream pins (*.env), checksums, and contracts
├── build/                     # Scratch clones (gitignored, never committed)
└── docs/                      # Architectural specs, operations, and test reports
```

Upstream sources are **not** vendored or checked out. Each dependency is pinned by
a text file in `provenance/` (`bepinex.env`, `pine.env`, `dobby.env`, `monomod.env`,
`il2cppinterop.env`) and cloned at its pinned commit into the gitignored `build/`
tree, then asserted against the pin before anything is compiled. See
`docs/ci-cd.md` § Pin Sources.

---

## Prerequisites

- **Host OS**: Linux (x86_64 or aarch64)
- **JDK**: Java 17 (Eclipse Temurin recommended)
- **Android SDK**: API Platform 35, Build-tools 35.0.0+
- **Android NDK**: `30.0.16248370` (NDK r30)
- **CMake**: `3.22.1+`
- **.NET SDK**: `10.0.401` (pinned via `global.json`)
- **CLI Tools**: `git`, `zip`, `unzip`, `curl`

---

## Building from Source

### 1. Build Native Dependencies

Compiles `libdobby.so` and `libpine.so` with 16 KB page-size alignment:

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/30.0.16248370
./scripts/build_native_deps.sh
```

### 2. Fetch & Prepare .NET Runtime

Downloads the pinned Microsoft Android arm64 runtime package, verifies SHA-256 integrity, and generates `dotnet.zip`:

```bash
./scripts/fetch_dotnet_runtime.sh /tmp/runtime
```

### 3. Build BepInEx Managed Assemblies

Clones the pinned upstream commit, verifies the clone against the pin, applies the
patch queue, and compiles the managed framework:

```bash
./scripts/apply_patches.sh
./scripts/build_monomod.sh
./scripts/build_il2cppinterop.sh
dotnet build Runtimes/Unity/BepInEx.Unity.IL2CPP/BepInEx.Unity.IL2CPP.csproj -c Release
./scripts/package_bepinex_android.sh
```

### 4. Build Launcher APK

```bash
cd launcher
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

Output APK: `launcher/app/build/outputs/apk/debug/app-debug.apk`

---

## Device Deployment & Diagnostics

### Fast Delta Install (Recommended)

When iterating on the launcher over ADB/Wi-Fi, use official Android CLI Delta Install:

```bash
android install --apks=launcher/app/build/outputs/apk/debug/app-debug.apk
```

### Real-Time Diagnostics

Inspect engine initialization, CoreCLR, and Unity game logs:

```bash
adb logcat -s fusion BepInEx Unity
```

Inspect captured Unity game exceptions:

```bash
adb logcat | grep -E '\[Error:Unity\]|\[Error:Il2CppInterop\]'
```

---

## Verification & Integrity Gates

All binaries and runtime packages are verified by strict CI gates:

| Script | Verification Scope |
|---|---|
| `launcher/scripts/verify_prebuilts.sh` | Validates binary SHA-256 against `provenance/sha256sums.txt` and enforces 16 KB page alignment. |
| `launcher/scripts/verify_crypto_pal.sh` | Verifies that native Android Crypto PAL is present without OpenSSL/BoringSSL linkage. |
| `launcher/scripts/verify_crypto_java.sh` | Validates PAL JNI method signatures against `provenance/pal-java-contract.txt`. |
| `scripts/verify_dotnet_pack.sh` | Confirms framework assembly count, flat structure, and absence of build tool leaks in `dotnet.zip`. |
| `scripts/verify_ci_artifacts.sh` | Validates producer-consumer artifact hashes across GitHub Actions workflows. |

---

## Documentation Index

- [Operations & Maintenance Guide](file:///home/com/BepInEx-Android/docs/operations.md) — API contracts, patch queue workflows, and debugging specifications.
- [CI/CD Pipeline Specifications](file:///home/com/BepInEx-Android/docs/ci-cd.md) — Workflow execution graphs, gate criteria, and determinism policies.
- [Roadmap & Tasks](file:///home/com/BepInEx-Android/docs/todo.md) — Specification-driven milestones and verification checklists.
- [Architecture Boundaries](file:///home/com/BepInEx-Android/docs/responsibility.md) — System responsibilities, modding contracts, and platform constraints.
- [Unity 6000 Case Study](file:///home/com/BepInEx-Android/docs/thetower-6000.md) — Detailed technical analysis of hosting identity, Firebase, and Play Billing shims.
- [Storage & Permissions Audit](file:///home/com/BepInEx-Android/docs/ci-storage-audit.md) — Scoped Storage architecture, permissions model, and sandbox specifications.
- [Progress & Architecture Log](file:///home/com/BepInEx-Android/docs/progress.md) — Architecture Decision Records (ADRs) and hardware verification history.

---

## License

- **Launcher & Native Injection (`libmain`, `libfusion`)**: [GPL-3.0](file:///home/com/BepInEx-Android/LICENSE)
- **BepInEx Framework**: [LGPL-2.1](https://github.com/BepInEx/BepInEx/blob/master/LICENSE)
- **.NET Runtime & Base Class Libraries**: [MIT](https://github.com/dotnet/runtime/blob/main/LICENSE.TXT)
- **Dobby & Pine**: [MIT](https://github.com/canyie/Pine/blob/master/LICENSE)