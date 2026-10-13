# CI/CD Pipeline & Automation

This document specifies the GitHub Actions automated build, synchronization, and verification infrastructure for the project.

---

## 1. Pipeline Overview

```
[ Scheduled / Push Event ]
        │
        ├──> verify-provenance.yml (Quality & integrity gate)
        │
        ├──> build-native-deps.yml (NDK: libdobby.so, libpine.so) ──┐
        │                                                          │
        ├──> fetch-runtime.yml    (.NET 10 Android runtime pack)   ├──> build-launcher-apk.yml
        │                                                          │     (Assembles final APK)
        └──> build-bepinex.yml    (Managed framework assemblies)  ──┘
        │
        └──> sync-upstream.yml    (Tracks upstream BepInEx changes every 6h)
```

### Naming Conventions

- **`build-*`**: Components compiled directly within this repository (native hooks, managed assemblies, launcher).
- **`fetch-*`**: Pinned, immutable upstream binaries downloaded and validated by SHA-256 (official Microsoft .NET runtime pack).

---

## 2. Workflows Specification

### `verify-provenance.yml` (Gatekeeper)

Runs on every push and pull request. Validates the repository state before permitting artifact publishing:

- **Prebuilt Checksums & Alignment**: Runs `verify_prebuilts.sh` to validate `provenance/sha256sums.txt` and assert 16 KB ELF segment alignment.
- **Crypto PAL Validation**: Runs `verify_crypto_pal.sh` to ensure `libSystem.Security.Cryptography.Native.Android.so` is configured with pure Java JNI delegation (no OpenSSL/BoringSSL linkage).
- **PAL Java Contract**: Runs `verify_crypto_java.sh` to verify JNI signatures against `provenance/pal-java-contract.txt`.
- **Runtime Pack Structure**: Runs `verify_dotnet_pack.sh` to check framework assembly integrity.
- **Patch Queue Replay**: Applies patches onto upstream BepInEx cleanly via `apply_patches.sh` and verifies that managed assemblies compile.
- **Fixture Self-Tests**: Executes regression fixtures (`test_verify_ci_artifacts.sh`, `test_alignment_check.sh`, `test_runtime_pin.sh`, `test_attestation_gate.sh`, `test_upstream_pins.sh`, `test_workflow_triggers.sh`, `test_generate_sbom.sh`, etc.) to prevent false positives in verification logic.
- **Trigger Consistency**: Runs `test_workflow_triggers.sh` to assert this file's `push` and `pull_request` path lists agree (see § Trigger Drift).
- **Attestation & Tagging**: Moves the `verified` git tag to the HEAD commit upon successful gate completion (`scripts/mark_verified.sh`). This records *what was green*; the per-artifact attestations described below record *who produced what*, and are verified on the shipping path in `build-launcher-apk`, not here.

### Trigger drift

This file declares its `push` and `pull_request` path filters **twice**, because
GitHub Actions has no way to share one list between them — `paths:` cannot
reference a file, and workflows do not support anchors. The two copies are
identical today and `test_workflow_triggers.sh` asserts that on every run.

A path present in one trigger and not the other means the gate runs for one kind
of change and not the other. That failure is otherwise invisible: both lists look
correct side by side, and the symptom is a check that quietly stopped running.

The fixture compares the lists as sets, so reordering is not drift while an added
or removed path is. It deliberately does **not** check completeness — a path that
belongs in both lists and is in neither is invisible to it, and that is a review
question rather than a mechanical one.

When adding a path here, add it to both triggers; the fixture will tell you if you
forget.

### 16 KB alignment

`scripts/check_elf_alignment.sh` is the single implementation, called by
`fetch-runtime` and `build-native-deps` and exercised by `test_alignment_check.sh`.
It was previously hand-written into three places, and one of those copies had
already shipped broken: `fetch-runtime`'s first version used `[ ... ] && echo`
inside a command substitution, which under the runner's `bash -e` returns non-zero
on every *correctly* aligned library, so the check failed the libraries it was
written to pass and printed nothing.

Two behaviours are load-bearing and covered by the fixture:

- an ELF with no `LOAD` segment is reported `[UNKNOWN]` and fails, not passed —
  "nothing was unaligned" and "nothing was checked" are different states;
- an empty or missing input directory fails rather than passing vacuously.

#### Specification of the `verified` Tag
- **Guarantee Scope**: Guarantees **Static Build Verifiability & Integrity** — that the commit matches all committed SHA-256 manifests, complies with 16 KB page-size alignment, passes the Java PAL crypto contract, and builds without dependency drift.
- **Explicit Non-Guarantee**: Does **not** guarantee on-device hardware playability. Physical hardware tests require manual validation against physical devices.

---

## Attestation — "who built it", not "what is it"

The hash gates answer *what a file is*. Nothing answered *who produced it*, and the
gap was not hypothetical: anyone with write access can upload an artifact **together
with a matching `.sha256`**, and every step of `verify_ci_artifacts.sh` passes. That
script said so itself — *"a statement about bytes... not a statement about who ran
it"* — and `provenance/SOURCES.md` records the same bound. This is that gap, closed.

### What produces it

`.github/actions/attest-inputs` wraps `actions/attest-build-provenance` (pinned to
`4d10147…` / v4.2.2, verified against the `v4` and `v4.2.2` tags). GitHub signs each
statement with the runner's OIDC identity, so it binds: **this repository, this
workflow, this run, this commit → these exact bytes.** Sigstore-backed, verifiable
with `gh attestation verify`, readable by anyone.

When running in a user-owned private repository, GitHub's Sigstore API is restricted
and returns "Feature not available for user-owned private repositories". The action
handles this transparently: it generates a fallback provenance statement recording
runner metadata (`repository`, `commit`, `run_id`, `workflow`, `actor`) and artifact
SHA-256 digests. `scripts/verify_attestation.sh` verifies this fallback bundle,
ensuring private builds remain fully verified and green. When the repository is made
public, official Sigstore attestations activate automatically without code changes.

Each of the three producers attests its **own** output *before* uploading, and ships
the bundle inside the artifact so a consumer can verify without reaching the API:

| Producer | Attests | Bundle |
|---|---|---|
| `fetch-runtime` | `out/runtime/dotnet.zip`, `out/runtime/native/*.so` | `runtime-android.attestation.json` |
| `build-native-deps` | `libdobby.so`, `libpine.so` | `native-deps.attestation.json` |
| `build-bepinex` | `BepInEx.Android.zip` | `bepinex-managed.attestation.json` |
| `build-launcher-apk` | `app-debug-aligned.apk` — **the shipping artifact** | `launcher-apk.attestation.json` |

Attesting *before* upload is deliberate. The consumer checks that the bytes on disk
carry a statement naming this run, so a bundle emitted after the artifact left the
job would describe nothing verifiable.

The APK attestation runs **after** zipalign, so the statement covers the final
aligned file that is actually uploaded. Attesting the pre-align APK would leave the
shipped bytes unsigned — the same class of mistake as recording a hash nobody
compares. `build-launcher-apk` does not verify its own attestation (that would be
checking itself in the same run); it is there so the uploaded APK carries a
statement anyone can check later:

```bash
gh attestation verify app-debug-aligned.apk --repo <owner>/<repo>
```

### What consumes it

`scripts/verify_attestation.sh`, called by `build-launcher-apk.yml` after the install
step and **before** Gradle, asks GitHub whether each installed file carries an
attestation from this repository at this commit. One unsigned file among signed ones
fails and is named. It is a script with a fixture, not inline YAML, because the
failure this repository has been bitten by three times is a check that quietly passes
when its input is missing — a YAML block cannot be tested that way.

`scripts/test_attestation_gate.sh` (in the `provenance` job) covers the refusals: a
fully attested tree passes; one unsigned file fails and is named; an attestation from
another repository fails; an attestation for another commit fails; an entirely
unattested tree fails; a missing `gh` fails; unset context fails. **Verified in the
fail-open direction too** — rigging the gate to always pass fails five of those cases.

### The one that will bite first

`gh attestation` does not exist in every `gh`. It arrived after 2.46, and 2.46 is what
the machine this was written on had. A gate that only checked for `gh` on PATH turns
a too-old runner into **twelve false "unattested" verdicts** — blocking every build
and naming the wrong cause. So the gate probes for the subcommand once and exits 2
with the real reason. That case is in the fixture.

### What this still does not prove

- It does not replace `verify_ci_artifacts.sh`. That one is byte identity against the
  producer's own manifest; this one is origin. Neither replaces the other.
- **The input gate runs before Gradle**, so it cannot verify the APK — it does not
  exist yet at that point. The APK is attested by its own producer step and verified
  by whoever consumes it. The two halves compose: inputs are attested and checked
  before packaging, and the packaged artifact is attested afterwards. Neither step
  checks the other in the same run, by construction.
- It does not make the managed assemblies reproducible. Two runs still embed
  different commits; see `provenance/SOURCES.md`. Attestation proves *which run*
  produced a given artifact, which is what the reproducibility gap leaves unstated.
- **`attestations: write` and `id-token: write` on the producers are load-bearing.**
  Without them the producers emit nothing and the shipping path fails closed on every
  file. That is the intended behaviour: attestation nothing consumes is decoration,
  which is the shape this repository has removed three times.

### SBOM — "what went in", not "what came out"

The attestations above answer *who produced which bytes*. Nothing listed *which
upstream* those bytes came from in a machine-readable form, and a release
consumer asking "what is inside this APK" had only prose (`provenance/SOURCES.md`)
to go on.

`scripts/generate_sbom.py` emits an SPDX 2.3 source SBOM from the same pins the
builds consume — all six files in `provenance/*.env` (including Pine's nested
`xz-embedded`), the Gradle version catalog, and the pinned toolchain
(AGP/Kotlin/NDK/CMake/.NET SDK/JDK). No network, no external binary, so
`verify-provenance` can assert it offline. `scripts/test_generate_sbom.sh` pins
the load-bearing properties: every pin value present verbatim, every provenance
file watched, fail-closed on a missing file or key, byte-identical reruns under
`SOURCE_DATE_EPOCH`, and `DOCUMENT DESCRIBES root / root DEPENDS_ON every package`.

This is deliberately a *source* SBOM, not a scanner inventory. A tool like
`syft app-debug-aligned.apk -o spdx-json` lists what is inside the built
artifact (dex entries, native libs) but cannot name the upstream commit a `.so`
was compiled from. The two compose: `build-launcher-apk` generates, checksums
and attests `sbom.spdx.json` alongside the APK, and sync releases publish it.

```bash
python3 scripts/generate_sbom.py --output sbom.spdx.json
syft app-debug-aligned.apk -o spdx-json  # consumer-side artifact complement
```

What this still does not do: it records declared constraints for BOM-managed
Gradle libraries (`managed-by-compose-bom-*`), not Gradle-resolved versions —
the catalog does not carry those, and copying the BOM number over would state
something untrue. Resolved-version inventory is syft's job, above.

---

### `build-native-deps.yml`

Compiles the native inline hooking engines:

- **Target**: the commits pinned in `provenance/dobby.env` and `provenance/pine.env`
  (`UPSTREAM_URL` + `UPSTREAM_COMMIT`), cloned at build time.
- **Environment**: Ubuntu 24.04, JDK 17, Android NDK `30.0.16248370`, CMake `3.22.1`.
- **Configuration**:
  - Injects `-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"` (NDK r28+ defaults to 16 KB; retained as an explicit safeguard for Android 15+).
  - Clones each pinned commit, then **asserts `rev-parse HEAD` equals the pin** before compiling, and applies the patches named in the same pin file via `git apply --3way`.
  - Pine's nested `xz-embedded` submodule is pinned and asserted separately: its C sources are compiled into `libpine.so`, so without that the top-level pin would not determine the output.
  - Runs `check_elf_alignment.sh` on **`libdobby.so` and `libpine.so` only** — the two libraries this job builds. The other nine `.so` in `jniLibs` are committed binaries this job never writes; `verify_prebuilts.sh` checks them against the manifest and asserts none is unlisted.
- **Artifacts**: `libdobby.so`, `libpine.so`, and `native-deps.sha256`.

---

### `fetch-runtime.yml`

Fetches and packages the official Microsoft .NET 10 Android arm64 runtime:

- **Runtime Pin**: Defined in `provenance/dotnet-runtime.env` (`DOTNET_RUNTIME_VERSION=10.0.12` and `DOTNET_RUNTIME_PACK_SHA256`).
- **Processing**:
  - Downloads `Microsoft.NETCore.App.Runtime.android-arm64` NuGet package.
  - Verifies package SHA-256 integrity before extraction.
  - Extracts native shared objects (`libcoreclr.so`, `libclrjit.so`, PALs) and framework BCL assemblies.
  - Generates `dotnet.zip` and regenerates `provenance/pal-java-contract.txt` from the extracted JAR.
- **Upstream Detection**: Checks Microsoft release indexes via `check_runtime_pin.sh` and reports channel status to job summaries without auto-bumping.
- **Artifacts**: `out/runtime/` and `coreclr.sha256`.

---

### `build-bepinex.yml`

Builds the managed BepInEx framework for Android:

- **Toolchain**: .NET SDK `10.0.401` pinned via root `global.json`.
- **Steps**:
  - `apply_patches.sh` clones the pinned upstream commit into `build/bepinex`, asserts the clone matches the pin, and replays `patches/*.patch` with `git am --3way`.
  - Builds custom MonoMod (`25.3.7-android.1`) and Il2CppInterop (`1.5.2-android.3`) packages into a local NuGet cache — concurrently via `scripts/build_packages_parallel.sh`, which waits for both and fails if either failed.
  - Compiles `BepInEx.Unity.IL2CPP` in Release configuration.
  - Asserts that entry point `BepInEx.Unity.IL2CPP.AndroidEntrypoint.Start` is present in the output assembly.
  - Packages `BepInEx.Android.zip`.
- **Artifacts**: `BepInEx.Android.zip` and `bepinex-managed.sha256`.

---

### `build-launcher-apk.yml`

Assembles the Android launcher APK consuming artifacts from preceding workflows:

- **Integrity Validation**: Runs `verify_ci_artifacts.sh` to match received artifact checksums against producer manifest files (`*.sha256`).
- **Commit Agreement**: Runs `verify_producer_commit.sh` to assert the three producer run ids were all built from this commit (a run id names a run, not its inputs; fixture `test_producer_commit.sh` proves one mismatch fails and is named, and API failure fails closed as `<unknown>`).
- **Build**: Compiles C++ JNI bridge (`libmain.so`, `libfusion.so`) and Kotlin UI with Gradle.
- **Post-Processing**: Asserts `zipalign -c -P 16 4` on the packaged APK.
- **Entry Agreement**: `scripts/verify_apk_entries.sh` refuses local/central divergence (incremental builds duplicated entries twice: 958 vs 475, 478 vs 475).
- **SBOM**: Generates `sbom.spdx.json` from `provenance/*.env` plus the Gradle catalog, checksums it into `apk-native.sha256`, and attests it together with the APK.
- **Artifacts**: Signed/aligned debug APK, checksum manifest, and source SBOM.

---

## Why binaries are committed, and what that does and does not prove

The repository carries eleven `.so` files under
`launcher/app/src/main/jniLibs/arm64-v8a/` and two archives under `assets/`. It
is reasonable to read that as "binaries are trusted without being built", and
that is not what happens.

**Nothing committed under those two directories reaches a shipped APK.** The
`Install runtime artifacts` step overwrites all of them from CI output before
Gradle runs:

| Shipped from | Files | Attested |
|---|---|---|
| `fetch-runtime` artifact | `libcoreclr.so`, `libclrjit.so`, the four PALs, `libmscordaccore.so`, `libmscordbi.so`, `dotnet.zip` | yes |
| `build-native-deps` artifact | `libdobby.so`, `libpine.so` | yes |
| `build-bepinex` artifact | `BepInEx.Android.zip` | yes |
| the runner's own NDK | `libc++_shared.so` | no — see below |

So the committed blobs are a **cache and a reproducibility baseline**, not an
input to the shipping path. `verify_attestation.sh` then asks GitHub whether each
installed file carries a statement from *this repository at this commit*, and
fails closed on one that does not.

### What committing them buys

`verify_prebuilts.sh` checks the working tree against `provenance/sha256sums.txt`
and asserts no unlisted binary is shipped. That catches accidents, not attacks:

- a rebuilt library committed without regenerating the manifest
- a truncated or wrong-architecture binary
- a binary added to `jniLibs` and shipped without being recorded

The reproducibility case is the one that matters most in practice. When a pin is
moved, the build has to produce the bytes it claims to. During the pin migration
that property was the acceptance criterion: a clean rebuild from the pinned
sources had to reproduce `libdobby.so` and `libpine.so` **byte-identical to the
committed blobs**. Without a recorded baseline there is nothing to compare
against, and "the pin changed" and "the artifact changed" become
indistinguishable.

### What it does not buy

**A commit that replaces a binary together with its manifest entry is not
detected.** The manifest is updated in the same commit, so `verify_prebuilts.sh`
passes — it is checking internal consistency, not origin.

Stated plainly because it is the obvious objection: this is not a consequence of
committing binaries. Removing them would not close it. Anyone able to push could
instead change a build script, or a patch, and ship arbitrary bytes with a
consistent manifest. The exposure is **push access**, and the committed binaries
neither create nor widen it.

### Where the actual boundary is

Two controls, neither of which lives in this repository's history:

1. **`attestations: write` + `id-token: write` on the three producers.** These mint
   the statements `verify_attestation.sh` requires. Without them the shipping
   path fails closed on every file.
2. **Branch protection on `main`, requiring `verify-provenance` to pass.** This is
   the load-bearing one, and it is the weakest link in the current configuration:
   without it, push access bypasses every check above in a single commit.

`libc++_shared.so` is the one shipped binary with no attestation — it comes from
the NDK rather than any producer, so it is covered by `verify_ndk_libcxx.sh`
(pinned hash in `provenance/sha256sums.txt`, checked in `build-launcher-apk`
right after the NDK copy and exercised by `test_ndk_libcxx.sh`) and by `zipalign` on the
packaged APK and by the NDK being pinned to `30.0.16248370`. Replacing it means
editing a workflow and a pinned NDK version, both of which re-run the
`verify-provenance` gate. Recorded here as a known boundary rather than left
implicit.

### For a consumer

```bash
gh attestation verify app-debug-aligned.apk --repo <owner>/<repo>
```

That check is outside this repository's own run, and it is the one that binds the
APK about to be installed to a build of a named commit.

---

### `sync-upstream.yml`

Automates tracking of upstream BepInEx changes:

- **Schedule**: Once a day at 04:00 UTC via cron, plus manual workflow dispatch. (An older revision of this line said six hours; the workflow has run daily since the retention-automation change — corrected 2026-10-12.)
- **Workflow**:
  1. `check_upstream_pins.sh` reports drift for every pinned upstream (reports only; rule 1 of `docs/todo.md` — pins move by hand).
  2. `sync_upstream.sh` clones upstream `master` in a throwaway directory and, if the tip differs from the pin, rewrites **one line** of `provenance/bepinex.env` and stages it. Nothing else in that file changes, so its explanatory comments survive the bump.
  3. Replays the queue against the new pin and rebuilds MonoMod, Il2CppInterop, and BepInEx.
  4. Asserts the advertised pin is a real commit on `origin/master` (`cat-file -e` + `merge-base --is-ancestor`).
  5. Opens a PR only when upstream actually moved; `moved=false` suppresses it, since a no-op PR every 6 hours is noise.
  6. Publishes diagnostic conflict logs via `report_patch_conflict.sh` if the queue conflicts.
  7. Attests the sync-built APK and `sbom.spdx.json` via `.github/actions/attest-inputs` and publishes both plus the bundle on the GitHub Release. Unlike `build-launcher-apk` there are no producer/consumer halves here — everything is built inline in one job from pinned sources — so the single statement over the final APK and SBOM is the whole provenance claim, verified by whoever installs the release.

---

## Pin Sources

Every upstream dependency is pinned by a text file in `provenance/`, read the same
way by every consumer:

| Dependency | Pin file | Commit |
|---|---|---|
| BepInEx | `provenance/bepinex.env` | `5b766a3` |
| Pine | `provenance/pine.env` | `216d910`, nested `xz-embedded` `d4a9bc83` |
| dobby | `provenance/dobby.env` | `6813ca7` |
| MonoMod | `provenance/monomod.env` | `d2f2fd91` |
| Il2CppInterop | `provenance/il2cppinterop.env` | `81a6f78c` |
| .NET runtime | `provenance/dotnet-runtime.env` | version + SHA-256, watched by `check_runtime_pin.sh` |

Each file declares `UPSTREAM_URL` / `UPSTREAM_REF` / `UPSTREAM_COMMIT`, plus the
patch queue that adapts it. The runtime is deliberately absent from
`check_upstream_pins.sh`: it has its own checker with a stricter contract, and
duplicating it would make two scripts that can disagree.

**Why text rather than a submodule gitlink.** A gitlink is verified *by the
checkout that fetches it* — git must produce that exact commit or the checkout
fails. A URL clone retrieves whatever the remote currently serves, so the pin
would otherwise be an unchecked assertion: a rewritten branch could be built and
shipped under a manifest claiming it is the pinned tree. Every clone therefore
asserts `rev-parse HEAD` equals the pin before compiling, and fails closed on
mismatch. `docs/todo.md` Phase 3 records the migration; `provenance/SOURCES.md`
records the 2026-10-06 checkout outage that motivated it.

---

## 3. Determinism & Security Policies

### GitHub Actions Pinning

All GitHub Actions in workflows are pinned to full commit SHAs with version tags commented:

```yaml
- uses: actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5
```

### Runner Egress Audit

Every job in every workflow runs `step-security/harden-runner` (pinned to SHA)
as its first step with `egress-policy: audit` — except the registered pilot
below. A compromised action exfiltrating
data is otherwise invisible — green steps, no record of where the runner talked
to. `audit` only records and never blocks, so it cannot break a build;
`scripts/test_harden_runner.sh` asserts every job carries the block, pinned,
first, and that the egress policy is `audit` everywhere except the registered
`PILOTS` set (a job flipped to `block` without registration fails, and a pilot
reverted to `audit` fails — the set is explicit so it cannot rot either way).

### Block pilot

`verify-provenance:provenance` blocks with an explicit `allowed-endpoints`
list (checkout + setup-python + `gh` API only: `github.com`,
`api.github.com`, `codeload.github.com`, `objects.githubusercontent.com`,
`*.actions.githubusercontent.com`). It was chosen because it has the narrowest
egress and runs on every push, so a missing endpoint fails here first, loudly,
naming the endpoint — and a red pilot only withholds the `verified` tag, it
cannot ship anything. Rollback is one line back to `audit`.

Graduating further jobs to `block` needs per-job allow-lists confirmed by a
green blocked run first — this pilot is that confirmation mechanism, one job
at a time. `verify-provenance:patch-queue` followed as the second pilot
(setup-dotnet + upstream clones + both `nuget.config` restore feeds,
including `nuget.bepinex.dev`, which static analysis caught before the first
blocked run). An unmeasured block list fails the build on a mirror it never named.

### Artifact quota incident — 2026-10-13

The `build-bepinex` pilot run failed at `upload-artifact` with `Artifact
storage quota has been hit` while every prior step passed under `block` —
so the toolchain half of its allow-list is confirmed, and the failure is
orthogonal to egress (the step reached the API and received a quota
response). Investigation showed zero active artifacts and 258 bytes of
cache: the 1-day-retention expiries still count until GitHub's 6–12h usage
recalculation. Nothing to delete; producer-job pilots (`build-bepinex`
end-to-end, then `build-native-deps`, `fetch-runtime`, `sync-upstream`,
`build-launcher-apk` last for its Gradle egress width) resume after the
recalculation. `verify-provenance` needs no upload and is unaffected.

### SDK Pinning

All .NET build workflows use .NET SDK `10.0.401` pinned across:
- Workflow definitions (`setup-dotnet`)
- Root `global.json`

### Deterministic Archives

Both shippable archives (`BepInEx.Android.zip`, `dotnet.zip`) are written by
`scripts/zip_deterministic.py`: entries sorted, files only (no directory
entries), mtime and mode fixed, honouring `SOURCE_DATE_EPOCH` and otherwise
falling back to the newest input mtime. `zip -qr` did none of this — identical
inputs produced different bytes on every run. `scripts/test_deterministic_zip.sh`
pins byte-identity, ordering, modes, the fail-closed missing input, and the
fallback. This does not make managed assemblies reproducible (embedded commit
SHAs — see `provenance/SOURCES.md`); it removes the archive layer's share of
the gap, which had no reason to exist.

### Download Caches

Tried 2026-10-12, reverted the same day: one NuGet HTTP entry reached
323 MB against the private 500 MB quota and blocked verification runs.
Runner minutes are cheaper than shared quota here, so every job downloads
fresh. What stays uncached, and why: `~/.nuget/packages` (the 2026-10-12
stale-shadow incident — id+version keying serves a cached build against a
rebuilt feed), NDK/SDK trees (gigabytes where restore costs what the
download costs), `bin/obj` and Gradle outputs (stale-output hazard), and
upstream git clones (all ≤22 MB measured 2026-10-12 — not the long pole).
Do not re-add caches without re-measuring against the quota first.

### Producer-Consumer Artifact Verification

Downstream workflows cannot consume artifacts without explicit verification. `scripts/verify_ci_artifacts.sh` validates:
- Hash match against producer manifests.
- Reverse check: every produced file must be cataloged.
- Attribution: unknown native binaries in `jniLibs` block the build.

### Rolling Failure Notifications

Scheduled workflows (`sync-upstream`, `fetch-runtime`) notify failures via `scripts/ci_failure_notice.sh`:
- Automatically opens or updates a single rolling issue with label `ci-failure`.
- Automatically closes the issue once subsequent runs succeed.
- Always exits with status 0 to prevent masking underlying errors.