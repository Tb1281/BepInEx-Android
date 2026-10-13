# Artifact Provenance

This repository is `BepInEx-Android/BepInEx-Android`, an unofficial community
port of [BepInEx/BepInEx](https://github.com/BepInEx/BepInEx) (LGPL-2.1).

No binaries are committed. CI producers build from the pins below; the
launcher assembles the APK from CI artifacts; local builds stage files into
the gitignored `launcher/app/src/main/jniLibs/` and
`launcher/app/src/main/assets/` directories. `provenance/sha256sums.txt`
records the expected hashes and `verify_prebuilts.sh` validates staged files
against it. The pins plus the manifest are the baseline.

## Pin Table

| Artifact | Source | Pin |
|---|---|---|
| BepInEx framework (`BepInEx.Android.zip`) | [BepInEx/BepInEx](https://github.com/BepInEx/BepInEx), master + `patches/*.patch` via `build-bepinex` | `5b766a3b7f6c164d4798924a93f3acf4db769d06` |
| `libdobby.so` | [canyie/dobby](https://github.com/canyie/dobby), master via `build-native-deps` | `6813ca76ddeafcaece525bf8c6cde7ff4c21d3ce` |
| `libpine.so` | [canyie/Pine](https://github.com/canyie/Pine), master via `build-native-deps` | `216d910f18b18430a5d21c510affb221a9833a55`, nested xz-embedded `d4a9bc83c72d8087fe36ff388e89599626da7873` |
| MonoMod packages (`25.3.7-android.1`) | [MonoMod/MonoMod](https://github.com/MonoMod/MonoMod), reorganize + `references/monomod-android.patch` | `d2f2fd91f0daababe41b81239b3d53a4be77cd43` |
| Il2CppInterop packages (`1.5.2-android.3`) | [BepInEx/Il2CppInterop](https://github.com/BepInEx/Il2CppInterop), master + `references/il2cppinterop-arm64-disarm.patch` | `81a6f78c8b653e0da4a3420ac4cd00819e8b6292` |
| `libcoreclr.so`, `libclrjit.so`, PALs, BCL (`dotnet.zip`) | `microsoft.netcore.app.runtime.android-arm64` 10.0.12 from nuget.org via `fetch-runtime` | pack SHA-256 `7f67905e5e5324e0c2b03920b62a3b09c036737ba84f9e7cb61f7e058d285ad3` |
| `libmain.so`, `libfusion.so` | `launcher/app/src/main/cpp/`, compiled by Gradle in `build-launcher-apk` | current commit |
| `libc++_shared.so` | Pinned NDK `30.0.16248370`, copied in `build-launcher-apk` | NDK pin + manifest hash |

Machine-readable pins live in `provenance/*.env`. Every URL clone asserts
`rev-parse HEAD` equals the pin and fails closed on mismatch. The runtime TFM
is `net10.0`; the .NET SDK is `10.0.401` via root `global.json`.

## Trust Notes

- **Integrity.** Staged files must match `provenance/sha256sums.txt`;
  mismatches fail the build. Producer artifacts are checked against producer
  manifests by `verify_ci_artifacts.sh` before Gradle, in both directions
  (hash match plus reverse catalog check).
- **Origin.** Each producer attests its output before upload; the launcher
  verifies attestations before packaging. Hashes prove byte identity,
  attestations prove origin; neither replaces the other. Consumer check:
  `gh attestation verify app-debug-aligned.apk --repo <owner>/<repo>`.
  Full policy is in `docs/ci-cd.md`.
- **Reproducibility bounds.** Archives are deterministic
  (`scripts/zip_deterministic.py`, with canonical epochs), native builds map
  scratch paths out (`-ffile-prefix-map`), and managed builds are byte-stable
  for identical pins and toolchain (verified by rebuilding twice and
  comparing). The manifest enforces all of it on every gate run.
- **Crypto backend.** CoreCLR binds `AndroidCryptoNative_*` through
  `libSystem.Security.Cryptography.Native.Android.so`. OpenSSL PALs
  (`libcrypto`, `libssl`, `CryptoNative_*`) are rejected by
  `verify_crypto_pal.sh`. There is no managed PAL shim; the binding is
  asserted on P/Invoke targets and native exports instead.
- **Deliberately absent.** No third-party repackagers or unofficial mirrors;
  no upstream fork of the runtime; no desktop Doorstop/Mono/Cake components
  (removed by the patch queue). Anything that cannot be traced to a pin
  above does not ship.
