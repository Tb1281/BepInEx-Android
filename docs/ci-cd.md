# CI/CD Pipeline & Automation

## 1. Pipeline Overview

```
[ Scheduled / Push Event ]
        │
        ├──> verify-provenance.yml (quality and integrity gate)
        │
        ├──> build-native-deps.yml (NDK: libdobby.so, libpine.so) ──┐
        │                                                          │
        ├──> fetch-runtime.yml    (.NET 10 Android runtime pack)   ├──> build-launcher-apk.yml
        │                                                          │     (assembles final APK)
        └──> build-bepinex.yml    (managed framework assemblies)  ──┘
        │
        └──> sync-upstream.yml    (tracks upstream BepInEx changes daily)
```

Naming: `build-*` compiles components in this repository; `fetch-*`
downloads a pinned, immutable upstream binary and validates it by SHA-256.

---

## 2. Workflow Specification

### `verify-provenance.yml` (gatekeeper)

Runs on every push and pull request. The `provenance` job rebuilds every
staged input from the pins first (runtime pack, hook engines, managed
framework), so the checks below examine bytes produced in that run, never
bytes read from the repository:

- Validates staged files against `provenance/sha256sums.txt` via
  `verify_prebuilts.sh`, including 16 KB ELF segment alignment.
- Validates the crypto PAL (`verify_crypto_pal.sh`, pure Java JNI delegation,
  no OpenSSL/BoringSSL linkage) and the PAL Java contract
  (`verify_crypto_java.sh` against `provenance/pal-java-contract.txt`).
- Validates runtime pack structure (`verify_dotnet_pack.sh`).
- Replays `patches/*.patch` onto the pinned upstream commit via
  `apply_patches.sh` and verifies the managed build compiles.
- Runs regression fixtures (`test_verify_ci_artifacts.sh`,
  `test_alignment_check.sh`, `test_runtime_pin.sh`,
  `test_attestation_gate.sh`, `test_upstream_pins.sh`,
  `test_workflow_triggers.sh`, `test_generate_sbom.sh`).
- Asserts push and pull-request path filters agree
  (`test_workflow_triggers.sh`); when adding a path, add it to both triggers.
- On success moves the `verified` tag to HEAD (`scripts/mark_verified.sh`).
  The tag records what was green; per-artifact attestations record who
  produced what and are verified on the shipping path.

`verified` guarantees static build verifiability and integrity only. It does
not guarantee on-device playability, which requires manual device validation.

### `build-native-deps.yml`

Compiles the native hook engines from the pins in `provenance/dobby.env`
and `provenance/pine.env` (`UPSTREAM_URL` + `UPSTREAM_COMMIT`), asserting
`rev-parse HEAD` equals the pin before compiling and applying the patches
named in the same pin files. Pine's nested `xz-embedded` submodule is pinned
and asserted separately. Environment: Ubuntu 24.04, JDK 17, NDK
`30.0.16248370`, CMake `3.22.1` (pinned by the script, not PATH),
`-Wl,-z,max-page-size=16384`, and `-ffile-prefix-map` so checked-out paths
do not leak into `.rodata`. Artifacts: `libdobby.so`, `libpine.so`,
`native-deps.sha256`, attestation bundle.

### `fetch-runtime.yml`

Fetches the official Microsoft .NET 10 Android arm64 runtime pack pinned in
`provenance/dotnet-runtime.env` (`DOTNET_RUNTIME_VERSION=10.0.12` plus pack
SHA-256). Verifies the package hash before extraction, extracts native
libraries (CoreCLR, CLR JIT, PALs) and framework assemblies, emits
`dotnet.zip`, and regenerates `provenance/pal-java-contract.txt`.
`check_runtime_pin.sh` reports channel status without auto-bumping.
Artifacts: `out/runtime/`, `coreclr.sha256`, attestation bundle.

### `build-bepinex.yml`

Builds the managed framework from the pin in `provenance/bepinex.env`.
`apply_patches.sh` clones the pinned commit, asserts it, and replays
`patches/*.patch` with `git am --3way`. Builds MonoMod
(`25.3.7-android.1`) and Il2CppInterop (`1.5.3-android.1`) into a local NuGet
cache, compiles `BepInEx.Unity.IL2CPP` in Release with .NET SDK `10.0.401`
(pinned via root `global.json`), asserts the `AndroidEntrypoint.Start` entry
point is present, and packages `BepInEx.Android.zip`. Artifacts:
`BepInEx.Android.zip`, `bepinex-managed.sha256`, attestation bundle.

### `build-launcher-apk.yml`

Assembles the launcher APK from the three producer artifacts:

- Verifies hashes against producer manifests (`verify_ci_artifacts.sh`).
- Verifies all producers built from the current commit
  (`verify_producer_commit.sh`; API failure fails closed).
- Verifies attestations before Gradle (`verify_attestation.sh`, see below).
- Compiles the JNI bridge (`libmain.so`, `libfusion.so`) and Kotlin UI.
- Asserts `zipalign -c -P 16 4` and APK entry consistency
  (`verify_apk_entries.sh`).
- Generates, checksums, and attests `sbom.spdx.json` alongside the APK.
- Artifacts: signed/aligned debug APK, checksum manifest, source SBOM.

### `sync-upstream.yml`

Runs daily at 04:00 UTC plus manual dispatch. Reports pin drift
(`check_upstream_pins.sh`, reports only; pins move by hand), stages a
one-line pin bump in `provenance/bepinex.env` when upstream moved, replays
the queue, rebuilds, asserts the new pin is on `origin/master`, and opens a
PR only when upstream actually moved. On conflict publishes diagnostics via
`report_patch_conflict.sh`. Attests the sync-built APK and SBOM inline and
publishes both on the release.

No Dependabot: the load-bearing pins live in `provenance/*.env`, which
Dependabot cannot see, and every bump needs human judgment plus
on-device validation that no green check can provide. Automated bump PRs
for Actions/Gradle/NuGet would add review noise against that discipline;
drift reporting above is the detector.

---

## 3. Pin Sources

Every upstream dependency is pinned by a text file in `provenance/`,
consumed identically by scripts and CI. A URL clone always asserts
`rev-parse HEAD` equals the pin and fails closed on mismatch. Values live in
the env files and in `provenance/SOURCES.md`; they are not repeated here so
the two tables cannot disagree.

Text pins (not submodule gitlinks) so a rewritten upstream branch fails
loudly instead of building silently against the wrong tree.

---

## 4. Determinism and Security Policies

- **Action pinning.** All workflow actions are pinned to full commit SHAs
  with version tags in comments.
- **Runner egress audit.** Every job starts with `step-security/harden-runner`
  pinned to SHA. Default policy is `audit` (records, never blocks).
  `scripts/test_harden_runner.sh` asserts every job carries the block first
  and that only the registered pilot set may use `block`.
- **Block pilot.** `verify-provenance:provenance` and `:patch-queue` use
  `egress-policy: block` with explicit allow-lists (the former rebuilds from
  pins, so its list covers the .NET/Java/NDK toolchains plus upstream clones
  and restore feeds). Further jobs graduate one at a time after a green
  blocked run confirms their allow-list. Rollback is one line back to
  `audit`.
- **SDK pinning.** .NET SDK `10.0.401` is pinned in workflows and root
  `global.json`. NDK `30.0.16248370` is pinned for native builds.
- **Deterministic archives.** `BepInEx.Android.zip` and `dotnet.zip` are
  written by `scripts/zip_deterministic.py` (sorted entries, fixed
  mtime/mode). Epochs are canonical, not wall-clock: the framework zip uses
  the BepInEx pin's commit date, the runtime zip preserves pack entry dates
  (`cp -p` staging). Managed assemblies still embed build metadata (see
  `provenance/SOURCES.md`).
- **No caches.** No Actions caches are used. Retention is 1 day (the
  minimum); all jobs download fresh. Revisit only with quota headroom.
- **Producer-consumer verification.** `verify_ci_artifacts.sh` checks hash
  match against producer manifests, reverse-checks that every produced file
  is cataloged, and rejects unknown native binaries in `jniLibs`.
- **Attestation.** Each producer attests its output with
  `actions/attest-build-provenance` before upload (Sigstore-backed; a
  metadata fallback bundle covers environments where Sigstore is
  unavailable). `verify_attestation.sh` runs in `build-launcher-apk` before
  Gradle and fails closed on any unsigned input. Producers require
  `attestations: write` and `id-token: write`. Consumer example:
  `gh attestation verify app-debug-aligned.apk --repo <owner>/<repo>`.
  Attestation proves origin; hashes prove byte identity. Neither replaces
  the other, and neither covers bytes inside the packaged APK beyond the
  APK's own attestation.
- **SBOM.** `scripts/generate_sbom.py` emits an SPDX 2.3 source SBOM from
  `provenance/*.env`, the Gradle catalog, and the pinned toolchain, with no
  network access. `build-launcher-apk` checksums and attests it with the APK.
- **Branch protection.** `main` should require `verify-provenance` to pass.
  Without it, push access bypasses every check above in a single commit.
- **Rolling failure notifications.** Scheduled workflows report failures via
  `scripts/ci_failure_notice.sh`, which opens or updates a single rolling
  `ci-failure` issue and closes it on recovery, always exiting 0.
