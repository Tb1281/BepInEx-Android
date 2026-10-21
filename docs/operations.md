# Operations & Developer Guide

## 1. Patch Queue Management

Upstream changes live as serialized patches in `patches/`, replayed with
`git am --3way` onto the pin in `provenance/bepinex.env`:

```bash
./scripts/apply_patches.sh       # apply queue onto pinned commit; conflict aborts build
./scripts/sync_upstream.sh       # advance the pin and update UPSTREAM_DESCRIBE
./scripts/report_patch_conflict.sh  # print failing patch, files, markers, upstream diff
```

`sync_upstream.sh` derives the repository from the pin file, so a fixture can
point `BEPINEX_PIN_FILE` at a throwaway repository. It refuses to run when
the pin has uncommitted changes.

## 1a. Patch manifest

`patches/manifest.json` is the machine-readable interface of the queue:
purpose, touched files, depends-on, ADR references and compile-unit status
for each patch. It is generated, not authored:

```bash
./scripts/generate_patch_manifest.py   # writes patches/manifest.json
./scripts/test_patch_manifest.sh      # drift test + validation
```

`patches/*.patch` remains the only source of truth. Metadata that is not
derivable from the diff (depends-on, ADR) is carried as commit-message
trailers (`Depends-On:`, `ADR:`) inside the patch itself, so the manifest
can be regenerated and checked for drift.``

Patches `0001`-`0012` cover: Android paths, logcat logging, the
`AndroidEntrypoint` native entrypoint, libfusion detour routing with export
thunk resolution, Android solution retargeting, Mono/runtime and console
driver removal, desktop target stripping, AArch64 export-thunk arithmetic,
Il2CppInterop/MonoMod version pins, ARM64 hook guards, and the Cpp2IL
published-prerelease pin. Never skip a patch: a skip ships an archive
missing that adaptation.

## 1b. NuGet Lock Files

Transitive versions float per restore (measured: identical pins compiled to
different assemblies on different machines), so every restored project
carries a `packages.lock.json` and version drift fails loudly instead of
substituting quietly:

- BepInEx solution: locks vendored in `references/nuget-locks/bepinex/`
  (mirroring the source layout). They are NOT staged into the scratch tree
  and NuGet's `RestoreLockedMode` is NOT used: NuGet validates a lock file
  found on disk even without the flag, and the locally-built
  MonoMod/Il2CppInterop packages are rebuilt on every run, so their
  contentHash can never match a vendored file (NU1403 on every restore).
  Instead, `restore_and_build.sh` runs `scripts/verify_locked_versions.sh`
  after restore, which compares the restored versions against the vendored
  locks and fails on drift, ignoring the hashes that cannot be stable.
- Il2CppInterop: locks vendored in `references/nuget-locks/il2cppinterop/`,
  staged into the scratch tree by `build_il2cppinterop.sh` and enforced with
  `RestoreLockedMode`. These are external packages with stable hashes, so
  the hash validation holds here.
- MonoMod: upstream ships its own locks in-tree and sets
  `RestorePackagesWithLockFile=false`; our build passes
  `RestoreLockedMode` but it is a no-op there. Enforcement covers the
  patched state via the version pins.

Regeneration is deliberate, like a manifest rebaseline: restore once
unlocked in a scratch tree, inspect the version diff, copy the locks over
the vendored copies, and commit. Never regenerate silently as part of a
normal build.

## 1c. Patched-Tree Hash

`git am` mints commits with committer dates of "now", so the tip SHA of the
replayed queue can never be compared across runs. The tree object it points
at is the stable identity instead: a pure content hash of the patched
sources (modes, names, blob hashes; no timestamps), and replaying the same
pin plus the same ordered queue always yields the same tree on any machine
(measured: two fresh replays produced identical tree hashes).

`apply_patches.sh` therefore ends with `scripts/verify_patches.sh`, which
refuses to continue unless the tree hash matches
`references/patched-tree.sha256`. The gate also refuses untracked files in
the scratch tree (the hash cannot see them) and a tree not built on the
pinned commit. This is the answer to "the output hash differs, so was the
patch applied correctly?": the patch is verified at the source layer, where
a hash is stable, and the build is verified by properties (package
versions, SourceRevisionId, 16KB page alignment) at the artifact layer,
where it is not. Verifying the patch at the artifact layer is the NU1403
trap - it fails on every run for reasons that have nothing to do with the
patch.

Re-recording is deliberate, like a manifest rebaseline:

```bash
./scripts/apply_patches.sh
./scripts/record_patched_tree.sh   # writes references/patched-tree.sha256
```

`record_patched_tree.sh` refuses anything that is not a complete, clean
replay onto the pin: an open `git am` session, a dirty tree, a tree not
built on the pinned commit, or a replay whose commit count does not match
the current queue glob. The fixture
`scripts/test_patch_queue_workflow.sh` proves those refusals together with
the conflict report. The fixture `scripts/test_verify_patches.sh` proves
the tree-gate refusals (tampered tree, untracked file, wrong pin,
missing record). Never re-record as part of a normal build.

The one flow that legitimately changes the tree is the daily upstream sync:
`sync-upstream.yml` advances the pin first, so the replay runs with
`BEPINEX_SKIP_TREE_GATE=1` and `scripts/record_patched_tree.sh` re-records
immediately after. The skip is opt-in per invocation, never a default, and
the re-recorded hash still has to survive the gate on the sync PR's own CI
run.

As-built provenance (`scripts/emit_provenance.sh`) records what a run
actually built with - upstream pin, achieved tree hash, patch file hashes,
SDK version - as JSON next to the artifacts (`build-bepinex.yml` attests it
with the zip; `sync-upstream.yml` ships it in the CI artifact). The pins say
what was intended; this says what was built. Informational by design: a
missing tool degrades a field to `"unknown"` instead of failing the build,
because enforcement lives in the gates.

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

One tree builds debug, CI artifact, and release
(`./gradlew assembleDebug/assembleArtifact/assembleRelease`). Release builds
require the release key and tag-derived version; use `assembleArtifact` for a
local installable build without release credentials.
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
adb pull /sdcard/Android/data/com.bepinex.android/files/BepInEx_Android/<game-package>/BepInEx/LogOutput.log ./
```

The managed log is per game under `BepInEx_Android/<package>/`
(`files/BepInEx/` without the infix does not exist); the launcher's own
log is `files/bepinex_launcher.log`. When grepping on-device
(`adb shell "logcat -d | grep ..."`), exclude `adbd`: the daemon echoes
the full shell command into logcat, so the pattern matches the monitoring
command itself (`grep -v adbd`).

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
