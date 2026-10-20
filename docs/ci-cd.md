# CI/CD Pipeline & Automation

## 1. Pipeline Overview

```
[ Scheduled / Push / Tag Event ]
        │
        ├──> verify-provenance.yml (quality and integrity gate)
        │
        ├──> build-native-deps.yml (NDK: libdobby.so, libpine.so) ──┐
        │                                                          │
        ├──> fetch-runtime.yml    (.NET 10 Android runtime pack)   ├──> build-launcher-apk.yml
        │                                                          │     (CI artifact APK)
        └──> build-bepinex.yml    (managed framework assemblies)  ──┘
        │
        ├──> sync-upstream.yml    (daily upstream tracking; CI artifact only)
        │
        └──> release-tag.yml      (v-tag: preflight -> release APK -> GitHub Release)
```

Naming: `build-*` compiles components in this repository; `fetch-*`
downloads a pinned, immutable upstream binary and validates it by SHA-256.
Only `release-tag.yml` produces a release. `build-launcher-apk.yml` and
`sync-upstream.yml` build the CI-only `artifact` variant, which is
debug-signed under `com.bepinex.android.artifact` and is never published.

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
  `test_workflow_triggers.sh`, `test_generate_sbom.sh`,
  `test_emit_provenance.sh`, `test_mark_verified.sh`, `test_ci_failure_notice.sh`,
  `test_launcher_compile_gate.sh`, `test_release_preflight.sh`,
  `test_verify_release_assets.sh`).
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
`patches/*.patch` with `git am --3way`, then refuses to continue unless the
tree-object hash of the replayed sources matches
`references/patched-tree.sha256` (`scripts/verify_patches.sh`; the tip SHA
is never comparable because `git am` mints committer dates of "now"). Builds MonoMod
(`25.3.7-android.1`) and Il2CppInterop (`1.5.3-android.1`) into a local NuGet
cache, compiles `BepInEx.Unity.IL2CPP` in Release with .NET SDK `10.0.401`
(pinned via root `global.json`), asserts the `AndroidEntrypoint.Start` entry
point is present, and packages `BepInEx.Android.zip`. Emits
`bepinex-managed.provenance.json` (as-built record: upstream pin, achieved
patched-tree hash, patch file hashes, SDK version) and attests it alongside
the zip. Artifacts:
`BepInEx.Android.zip`, `bepinex-managed.sha256`,
`bepinex-managed.provenance.json`, attestation bundle.

### `build-launcher-apk.yml`

Assembles a CI-only launcher artifact from the three producer artifacts:

- Verifies hashes against producer manifests (`verify_ci_artifacts.sh`).
- Verifies all producers built from the current commit
  (`verify_producer_commit.sh`; API failure fails closed).
- Verifies attestations before Gradle (`verify_attestation.sh`, see below).
- Compiles the JNI bridge (`libmain.so`, `libfusion.so`) and Kotlin UI.
- Builds the **artifact** APK (`assembleArtifact`): the same R8
  minification, crypto PAL keep rules, resource shrinking, and 16 KB
  alignment as release, but debug-signed under
  `com.bepinex.android.artifact`. Only `release-tag.yml` builds the release
  variant with the release key.
- Asserts `zipalign -c -P 16 4` and APK entry consistency
  (`verify_apk_entries.sh`).
- Generates, checksums, and attests `sbom.spdx.json` alongside the APK.
- Artifacts: `app-artifact-aligned.apk`, checksum manifest, source SBOM,
  attestation bundle (`launcher-artifact-aligned`).

The artifact installs alongside the release and debug builds, and is never
published as a release.

### `release-tag.yml`

Triggered only by a `v[0-9]*` tag; the narrower trigger keeps the moving
`verified` tag from starting a release. The build job declares the `release`
GitHub Environment, checks out full history and tags, and runs
`scripts/release_preflight.sh` before the expensive build. The preflight
fails closed unless all of the following hold:

- The tag is `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-<label>`.
- The tag commit is an ancestor of remote `main`.
- The tag commit is covered by the **remote** `verified` tag.
- The tag-derived `versionCode` is greater than the highest stable release.
- The four release-key secrets are present, the keystore opens, and its
  certificate matches the committed `provenance/release-cert.sha256`.

The preflight emits `version_name`, `version_code`, `is_prerelease`, and the
materialized `keystore_path`. Gradle receives the tag-derived version and
the release build type refuses to run without the key and version, so there
is no debug-key fallback. The job builds `app-release-aligned.apk` under
`com.bepinex.android`, checks zip entries and 16 KB alignment, generates the
SBOM with the tag version, emits `provenance.json`, and uploads
`tag-ship-inputs`. An `always()` step removes the materialized keystore even
when the build fails. The job then calls `ship-release.yml` with
`prerelease: true`: every tag release starts as a GitHub pre-release, and
stable promotion is a separate step.

### `ship-release.yml`

The shared shipping path, called by `release-tag.yml`. It runs on a fresh
runner, so the APK, SBOM, and provenance arrive as the caller's artifact. It
attests those exact bytes, writes the APK checksum manifest, composes the
release notes, and runs `gh release create --verify-tag`. `--verify-tag`
makes the pushed tag the only way to create the release; if the tag is
missing, the command fails instead of creating it implicitly. The
`prerelease` input defaults to `true`, and the caller can opt out only after
the promotion path exists.

### `sync-upstream.yml`

Runs daily at 04:00 UTC plus manual dispatch. Reports pin drift
(`check_upstream_pins.sh`, reports only; pins move by hand), stages a
one-line pin bump in `provenance/bepinex.env` when upstream moved, replays
the queue, rebuilds, asserts the new pin is on `origin/master`, and opens a
PR only when upstream actually moved. On conflict publishes diagnostics via
`report_patch_conflict.sh`. Because the pin advanced, the replay runs with
`BEPINEX_SKIP_TREE_GATE=1` and `scripts/record_patched_tree.sh` re-records
the tree hash immediately after; the updated
`references/patched-tree.sha256` rides the sync PR, whose own CI re-applies
the gate from scratch. Sync output is a CI-only artifact, not a release: the
job builds `assembleArtifact`, uploads `sync-artifacts`
(`app-artifact-aligned.apk`, `sbom.spdx.json`, `provenance.json`) with
one-day retention, and links the workflow run from the PR body. It never
calls `ship-release.yml` and never creates a release or tag. Merge the PR,
then push a `v` tag on the merged commit to cut a release.

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
- **Block pilot.`verify-provenance:provenance`, `:patch-queue`, `build-bepinex:build`, `build-native-deps:build`, `fetch-runtime:fetch-runtime`, `sync-upstream:sync`, and `build-launcher-apk:apk` use `egress-policy: block` with explicit allow-lists (the former rebuilds from
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
  (`cp -p` staging). Managed assemblies are content-stable but not
  byte-stable across fresh clones; see `provenance/SOURCES.md` for the
  measured boundary.
- **Caches.** The repository is public, so the 10 GB Actions cache quota
  applies. The three Gradle jobs (`build-launcher-apk`, `sync-upstream`,
  `release-tag`) cache `~/.gradle/caches` and `~/.gradle/wrapper`, keyed on the
  dependency inputs (`libs.versions.toml`, Gradle scripts); Gradle verifies
  content hashes on use. This was revisited after the no-cache era, when a
  single 323 MB NuGet HTTP entry blocked verification against the private
  500 MB quota. Retention is 1 day (the minimum); the NDK/SDK installs are
  deliberately not cached (gigabytes per job for minutes saved, and the
  exact NDK pin must come from `sdkmanager`, not a cache entry).
- **Producer-consumer verification.** `verify_ci_artifacts.sh` checks hash
  match against producer manifests, reverse-checks that every produced file
  is cataloged, and rejects unknown native binaries in `jniLibs`.
- **Attestation.** Each producer attests its output with
  `actions/attest-build-provenance` before upload (Sigstore-backed; a
  metadata fallback bundle covers environments where Sigstore is
  unavailable). `verify_attestation.sh` runs in `build-launcher-apk` before
  Gradle and fails closed on any unsigned input. Producers require
  `attestations: write` and `id-token: write`. Consumer example for a
  release: `gh attestation verify app-release-aligned.apk --repo
  <owner>/<repo>`; CI artifacts use `app-artifact-aligned.apk`.
  Attestation proves origin; hashes prove byte identity. Neither replaces
  the other, and neither covers bytes inside the packaged APK beyond the
  APK's own attestation.
- **SBOM.** `scripts/generate_sbom.py` emits an SPDX 2.3 source SBOM from
  `provenance/*.env`, the Gradle catalog, and the pinned toolchain, with no
  network access. `build-launcher-apk` and `sync-upstream` checksum and
  attest it with their artifact; `release-tag` also passes the tag-derived
  `BEPINEX_VERSION_NAME`/`BEPINEX_VERSION_CODE`, which the generator prefers
  over the development defaults.
- **Branch protection.** `main` should require `verify-provenance` to pass.
  Without it, push access bypasses every check above in a single commit.
- **Rolling failure notifications.** Scheduled workflows report failures via
  `scripts/ci_failure_notice.sh`, which opens or updates a single rolling
  `ci-failure` issue and closes it on recovery, always exiting 0. Pin drift
  gets the same treatment via `scripts/pin_drift_notice.sh` (rolling
  `pin-drift` issue): the drift checks report to green-run summaries nobody
  opens, so without it drift is detected and invisible at once. Drift never
  moves a pin — pins move by hand.
