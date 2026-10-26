# BepInEx-Android

Unofficial community port of [BepInEx](https://github.com/BepInEx/BepInEx) (LGPL-2.1)
to arm64 Android IL2CPP games. Targets the official
`Microsoft.NETCore.App.Runtime.android-arm64` pack (.NET 10 LTS) with the
`canyie/dobby` and `LSPosed/LSPlant` native hook engines.

---

## Architecture

```
[ Game Process ]
   └── libmain.so (JNI bootstrap)
         └── libfusion.so (CoreCLR host + Dobby inline hook engine)
               ├── Intercepts il2cpp_init, resolves export thunks
               └── Boots CoreCLR (.NET runtime + libcoreclr.so)
                     └── BepInEx.Android.zip
                           ├── AndroidEntrypoint.Start()
                           ├── IL2CPP chainloader
                           └── Mod plugins (.dll)
```

1. **Launcher**: hosts the game activity in an isolated process (`:game`).
   Native injection loads `libfusion.so` via `libmain.so`.
2. **CoreCLR hosting**: `libfusion.so` starts the official Microsoft .NET 10
   Android runtime. (`libfusion.so` is original to this project and unrelated
   to the third-party FusionCore framework credited below.)
3. **Detour engine**: dynamic export thunks (`il2cpp_runtime_invoke`) are
   resolved by `ExportThunkResolver` before Dobby attaches inline hooks.
4. **Managed framework**: upstream BepInEx IL2CPP chainloader, adapted for
   Android logcat, path conventions, and custom interop.

---

## Pinned Dependencies

Every upstream dependency is pinned by a text file in `provenance/`
(`UPSTREAM_URL` / `UPSTREAM_REF` / `UPSTREAM_COMMIT`). The env files are the
single source of truth; the table below is a summary. Pins move by hand only —
never automatically.

| Component | Pin | Notes |
|---|---|---|
| BepInEx | `provenance/bepinex.env` | Patch queue in `patches/` |
| .NET runtime | `provenance/dotnet-runtime.env` | SHA-256 verified Microsoft pack |
| MonoMod | `provenance/monomod.env` | Rebuilt to local NuGet feed |
| Il2CppInterop | `provenance/il2cppinterop.env` | Rebuilt to local NuGet feed |
| Dobby | `provenance/dobby.env` | 16 KB page aligned |
| LSPlant | `provenance/lsplant.env` | 16 KB page aligned, nested DexBuilder/parallel_hashmap pinned |

---

## Repository Structure

```
├── launcher/                  # Launcher app (Kotlin + C++/CMake)
│   ├── app/src/main/cpp/      # libmain.so, libfusion.so
│   ├── app/src/main/jniLibs/  # Staged native libs (gitignored, built by CI/scripts)
│   ├── app/src/main/assets/   # Staged framework archives (gitignored, built by CI/scripts)
│   └── scripts/               # Prebuilt verification utilities
├── patches/                   # Patch queue applied to upstream BepInEx
├── references/                # Adaptation patches for pinned native deps
├── scripts/                   # Build, packaging, and verification scripts
├── provenance/                # Upstream pins (*.env), hashes, contracts
└── docs/                      # Architecture, operations, and pipeline specs
```

No binaries are committed. CI producers build every artifact from the pins;
local builds stage outputs into the gitignored directories above.
`provenance/sha256sums.txt` records the expected hashes, and
`verify_prebuilts.sh` checks staged files against it.

---

## Prerequisites

- Host OS: Linux (x86_64 or aarch64)
- JDK 21, Android SDK (API 35), NDK `30.0.16248370`, CMake 3.22.1+
- .NET SDK `10.0.401` (pinned via `global.json`)
- `git`, `zip`, `unzip`, `curl`

## Building from Source

```bash
# 1. Native hook engines (libdobby.so, liblsplant.so), 16 KB aligned
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/30.0.16248370
./scripts/build_native_deps.sh

# 2. .NET runtime pack -> dotnet.zip (SHA-256 verified)
./scripts/fetch_dotnet_runtime.sh /tmp/runtime

# 3. Managed framework (clone pin, replay patches, compile, package).
# Run from the repository root. The restore/build step is bash-only
# (scripts/bepinex_paths.sh resolves paths through BASH_SOURCE); raw
# `dotnet` commands need BEPINEX_SRC exported first on other shells.
./scripts/apply_patches.sh
./scripts/build_packages_parallel.sh
./scripts/restore_and_build.sh
./scripts/package_bepinex_android.sh
./scripts/stage_launcher_inputs.sh out/runtime out/BepInEx.Android.zip

# 4. Launcher APK
cd launcher
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

APK: `launcher/app/build/outputs/apk/debug/app-debug.apk`

For device installs, prefer the Android CLI. `android install` uses an
incremental/delta install, so it pushes only the changed parts of the APK and is
much faster than `adb install` on repeated debug builds:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
```

## Device Diagnostics

```bash
adb logcat -s fusion BepInEx Unity
adb logcat | grep -E '\[Error:Unity\]|\[Error:Il2CppInterop\]'
```

## Verification Gates

| Script | Scope |
|---|---|
| `launcher/scripts/verify_prebuilts.sh` | Staged binaries vs `provenance/sha256sums.txt`, 16 KB alignment |
| `launcher/scripts/verify_crypto_pal.sh` | Android Crypto PAL without OpenSSL/BoringSSL linkage |
| `launcher/scripts/verify_crypto_java.sh` | PAL JNI signatures vs `provenance/pal-java-contract.txt` |
| `scripts/verify_dotnet_pack.sh` | Framework assembly count and structure of `dotnet.zip` |
| `scripts/verify_ci_artifacts.sh` | Producer-consumer artifact hash agreement across workflows |

---

## Documentation

- [Operations Guide](docs/operations.md)
- [Release Runbook](docs/release-runbook.md)
- [CI/CD Pipeline](docs/ci-cd.md)
- [Roadmap](docs/todo.md)
- [Glossary](GLOSSARY.md)
- [Architecture Decisions](docs/progress.md)
- [ADR Index](docs/adr/README.md)
- [Modding Contracts](docs/responsibility.md)
- [Unity 6000 Case Study](docs/thetower-6000.md)
- [Storage Audit](docs/ci-storage-audit.md)

---

## Credits

- [BepInEx](https://github.com/BepInEx/BepInEx) — upstream modding framework.
  This port tracks upstream plus a patch queue instead of forking it.
- [FusionCore](https://github.com/All-Of-Us-Mods/FusionCore) (Starlight team) —
  reference for the injection chain, native hook integration, and native-loader
  architecture. Several launcher classes are direct ports, noted at each file.
- [NextBep BepInEx.Android.Launcher](https://github.com/NextBep/BepInEx.Android.Launcher) —
  reference for launcher UI and features.
- [LSPlant](https://github.com/LSPosed/LSPlant), [Dobby](https://github.com/canyie/dobby) —
  hook engines.
- [MonoMod](https://github.com/MonoMod/MonoMod),
  [Il2CppInterop](https://github.com/BepInEx/Il2CppInterop) — detours and
  interop generation. The MonoMod Android support (`AndroidSystem`, Android
  interop) is the published work of the
  [XtraCube/MonoMod](https://github.com/XtraCube/MonoMod) fork, reproduced
  here as a patch (byte-identical files also ship in
  [All-Of-Us-Mods/MonoMod](https://github.com/All-Of-Us-Mods/MonoMod)).
  The ARM64 adaptation (Disarm xref scans, `libil2cpp.so`
  module match, `specialReturnBuffer` detours) follows the design of
  [All-Of-Us-Mods/Il2CppInterop](https://github.com/All-Of-Us-Mods/Il2CppInterop)
  (`starlight`).
- [dotnet/runtime](https://github.com/dotnet/runtime) — shipped as the official
  Microsoft Android arm64 pack, not built from source.

---

## License

- **Launcher and native injection (`libmain`, `libfusion`)**: [GPL-3.0](LICENSE)
- **BepInEx framework**: [LGPL-2.1](https://github.com/BepInEx/BepInEx/blob/master/LICENSE)
- **.NET runtime and BCL**: [MIT](https://github.com/dotnet/runtime/blob/main/LICENSE.TXT)
- **Dobby**: [Apache-2.0](https://github.com/canyie/dobby/blob/master/LICENSE)
- **LSPlant**: [LGPL-3.0](https://github.com/LSPosed/LSPlant/blob/master/LICENSE) —
  see `launcher/THIRDPARTY_NATIVE.md` for the third-party native inventory.
