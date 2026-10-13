# Third-Party Native Binaries & Provenance

Every `.so` in `app/src/main/jniLibs/arm64-v8a/` is either compiled from source in
this repository or built by a workflow in this repository from a pinned upstream.
The authoritative, detailed table lives in [`provenance/SOURCES.md`](../provenance/SOURCES.md);
the machine-checked checksums live in [`provenance/sha256sums.txt`](../provenance/sha256sums.txt)
and are enforced by `scripts/verify_prebuilts.sh` before any APK is produced.

There is no default remote for downloading staged artifacts. CI producers build
them from the pins in `provenance/*.env`; local builds produce them with
`scripts/*` (see the root README, Building from Source).

---

## 1. Built from source in this repository

Compiled by Gradle/CMake during `./gradlew assembleDebug` using the Android NDK
30.0.16248370.

| Binary | Source path | License | Role |
| :--- | :--- | :--- | :--- |
| `libmain.so` | `app/src/main/cpp/main/` | GPL-3.0 | Replaces the game's `libmain.so`; preloads the .NET runtime libraries and resolves `libfusion.so`. |
| `libfusion.so` | `app/src/main/cpp/fusion/` | GPL-3.0 | Injection engine: `il2cpp_init` hook, runtime code-cave allocation, CoreCLR bootstrap, and the `hook`/`unhook`/`write_log_level` exports the managed layer calls. |

---

## 2. Official published package, or built by this repository's CI

Section 2.1 is Microsoft's own released package from nuget.org. Section 2.2 onward
is built here from pinned upstream source. Nothing comes from a third-party
repackager or an unofficial mirror.

### A. Microsoft .NET — CoreCLR, native PALs and the BCL
* **Upstream**: Microsoft's official `Microsoft.NETCore.App.Runtime.android-arm64`
  package (nuget.org), pinned by version and SHA-256 in
  `provenance/dotnet-runtime.env`
* **Version**: 10.0.12
* **License**: MIT
* **Fetched by**: `.github/workflows/fetch-runtime.yml` →
  `scripts/fetch_dotnet_runtime.sh` (SHA-256 verified before unpacking)

| Binary | Role |
| :--- | :--- |
| `libcoreclr.so` | CoreCLR execution engine |
| `libclrjit.so` | RyuJIT compiler for ARM64 |
| `libSystem.Native.so` | Platform abstraction layer |
| `libSystem.IO.Compression.Native.so` | zlib PAL |
| `libSystem.Globalization.Native.so` | ICU/NLS shim |
| `libSystem.Security.Cryptography.Native.Android.so` | **Crypto PAL** — exports `AndroidCryptoNative_*`, reaches Android platform crypto (Conscrypt) through JNI |
| `libmscordaccore.so` | Diagnostics data access component |
| `libmscordbi.so` | Diagnostics IPC server |
| `dotnet.zip` (BCL, 172 assemblies) | `runtimes/android-arm64/lib/net10.0/` of the same package |

The BCL and `libcoreclr.so` come out of one immutable package, so they cannot be
from different runtime builds — a mismatch that no build step catches and that
fails on device instead. `libSystem.IO.Ports.Native.so` was in this list while the
runtime was cross-built here; the package does not contain it and nothing in the
BCL references `System.IO.Ports.Native`, so it is no longer shipped.

The same package id publishes a GA build for every .NET 10 servicing release
(10.0.0 … 10.0.12), each carrying `libcoreclr.so`, `libclrjit.so`, the platform
PALs and a `net10.0` BCL. Fetching rather than cross-building was therefore a
choice available on .NET 10 as well as on .NET 11; see `docs/progress.md`
ADR 2 for why .NET 10 is the pinned one.

### B. Hooking frameworks

| Binary | Upstream | Built by | License |
| :--- | :--- | :--- | :--- |
| `libdobby.so` | `provenance/dobby.env` → [canyie/dobby](https://github.com/canyie/dobby) | `build-native-deps` | Apache-2.0 |
| `libpine.so` | `provenance/pine.env` → [canyie/Pine](https://github.com/canyie/Pine) | `build-native-deps` | Anti 996 v1.0 |

> License compatibility is an open review item, not a resolved claim: the
> GPL-3.0 launcher links the Anti 996-licensed Pine (native `libpine.so` and
> the Java API). Tracked alongside the Pine R8 vendoring decision in
> `docs/todo.md`.

Dobby is the inline hook engine behind `libfusion`'s `hook`/`unhook` exports, and
is what the managed `NativeDetour` drives through Il2CppInterop. Pine hooks the ART
side (`ClassLoader.findLibrary`, `PackageManager`, `UnityPlayer`).

### C. Android NDK

| Binary | Source | License |
| :--- | :--- | :--- |
| `libc++_shared.so` | Android NDK 30.0.16248370 (pinned) | LLVM/Apache-2.0 with LLVM exceptions |

---

## 3. Crypto backend

CoreCLR on Android binds `System.Security.Cryptography.Native` to
`libSystem.Security.Cryptography.Native.Android.so`, which exports
`AndroidCryptoNative_*` and calls the platform BoringSSL through `liblog`.

This repository ships **no OpenSSL**. `libcrypto.so`, `libssl.so` and
`libSystem.Security.Cryptography.Native.OpenSsl.so` (which exports `CryptoNative_*`
and links `libcrypto`/`libssl`) are not produced by an upstream `dotnet/runtime`
build, so they were removed; `scripts/verify_crypto_pal.sh` fails the build if any
of them reappears or if `libcoreclr.so` grows a `libcrypto`/`libssl` dependency.

Managed HTTPS is kept off the crypto PAL entirely where it matters: the launcher
downloads the Unity reference libraries with Android's own HTTP stack
(`UnityLibsDownloader.kt`) before BepInEx starts, so the managed
`Il2CppInteropManager.DownloadUnityAssemblies` finds the cached file and never opens
a TLS stream.

---

## 4. Game and engine runtime injection architecture

No proprietary game binary is in this repository.

1. **Target game libraries** are read at run time from the installed game APK
   directory (`config.gameLibraryDir`): `libil2cpp.so` and `libunity.so`.
2. **Dynamic code cave**: `fusion.cpp` allocates a 1 MB pool in app data
   (`allocate_setup_injected`) to place relocatable code on Android 14+ W^X
   restrictions.
3. **Pine redirects** intercept `ClassLoader.findLibrary` so Unity and game
   libraries resolve to the installed APK paths.

---

## 5. Verification and automation

```bash
./launcher/scripts/verify_prebuilts.sh    # checksums + 16KB LOAD alignment + no unlisted binaries
./launcher/scripts/verify_crypto_pal.sh   # platform PAL only, no OpenSSL/BoringSSL
```

`preparePrebuilts` (see `app/build.gradle.kts`) runs the first two as part of every
Gradle build, so an APK cannot be produced from unverified binaries.

All `.so` files are page-aligned to 16 KB, required from Android 15 / API 35.