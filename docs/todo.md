# Project Roadmap & Task Tracking

This document specifies active engineering phases, dependency tracking, operational principles, and acceptance criteria for the BepInEx Android port.

---

## Operational Principles

1. **Human-Gated Dependency Bumps**: Never bump critical dependencies (Runtime, MonoMod, Il2CppInterop) automatically. Upstream updates may compile cleanly but fail on-device due to ABI or runtime contracts.
2. **Decouple Detection from Upgrading**: Scheduled jobs report drift but never auto-bump versions directly.
3. **Fail-Closed Verification**: Absence of verification manifests or contract mismatch must halt execution rather than silently succeeding.
4. **Test the Verifiers**: Verification logic must include automated fixtures to ensure tests fail as expected when inputs are invalid.

---

## Active & Planned Phases

### Phase 1: MonoMod Pin Upgrade & Validation
- **Current Pinned Baseline**: `d2f2fd91` (`25.3.7-android.1`, .NET SDK `10.0.401`) on `origin/reorganize`.
- **Target Tracking**: Branch tip `045c362` (131 commits ahead of current base).
- **Status & Tasks**: 
  - [x] Apply `references/monomod-android.patch` against branch tip (`045c362`).
  - [x] Verify `build_monomod.sh` comparison with upstream fork matches.
  - [x] Validate build with .NET SDK `10.0.401`.
  - [x] **On-Device Verification**: Deployed to physical device and verified the ported MonoMod is what actually loads (see record below).

#### On-Device Verification Record — 2026-10-11

**Verdict**: PASS. Chainloader reaches `startup complete` on-device with the pinned MonoMod, and no crash signature is present.

- **Device**: Samsung Galaxy S24 Ultra (`SM-S931N`), Android 17 / API 37, arm64-v8a.
- **Game**: The Tower (`com.TechTreeGames.TheTower`) v29.0.6, Unity 6000.3.15f1.
- **Commit under test**: `b7e9dc2` on `main`, NDK `30.0.16248370` (Pkg.Revision confirmed on disk).
- **Package installed**: `com.bepinex.android.test` (the test variant; production `com.bepinex.android` was **not** touched). APK SHA-256 `73e142c2b0ad36a32737bb8be219bfded7cc2c78aa2054305007cc04695ea247`, signer cert SHA-256 `15bccd8ccfc2aa99a07513697728a4fb487baea69291eadc297350e51268513c`.

**Provenance — the shipped binaries match the committed manifest**:
- `BepInEx.Android.zip` and `dotnet.zip`: manifest = working tree = APK SHA-256 all identical.
- The three AGP-stripped natives (`libc++_shared.so`, `libdobby.so`, `libpine.so`) differ from the manifest in the APK, as already documented; public symbols survive the strip (`Dobby*` 6, `Pine*` 7), `.symtab` removed — the known non-functional gap.

**Decisive evidence the pinned MonoMod is what ran**:
- Device-extracted `MonoMod.RuntimeDetour.dll` SHA-256 `74592ad6…79df` is byte-identical to the local committed `BepInEx.Android.zip` copy, which carries `25.3.7-android.1` built from `d2f2fd91`.

**Observed on-device** (logcat, `com.bepinex.android.test` `:game`):
- `Chainloader initialized`
- `Chainloader startup complete`
- Interop generation complete — 29,197 methods restored, 18,207 IL-unstripped
- `Resolved il2cpp_runtime_invoke export stub: 0x7969E0C370=>7969E648B8 (1 hop(s))`
- Crash signatures, all zero: `JNI abort` 0, `SIGABRT` 0, `Stack overflow` 0, `Fatal signal` 0, `PlatformNotSupported` 0.

**Known Il2CppInterop benign errors — reconfirmed, not new** (the messages, hook classes, and exception types match `docs/progress.md`):
- `MetadataCache::GetTypeInfoFromTypeDefinitionIndex not found` + `ArgumentOutOfRangeException`
- `Class::GetDefaultFieldValue not found` + `Sequence contains more than one element`

**Not covered by the vanilla run above**: it used vanilla mode (no `active_modpack` extra). Closed by the modpack run below.

#### Modpack Run — 2026-10-11 (closes the Harmony-patch criterion)

Same device, game, commit, APK and NDK as the vanilla run above. Only difference: `active_modpack` was set.

**Verdict**: PASS. A mod's Harmony patch applied and changed game behaviour on-device.

- **Modpack**: `TowerSpeed` v0.4.0, pushed to `<gameRoot>/modpacks/TowerSpeed/plugins/TowerSpeed.dll`. Three further modpacks (`TheTowerMod`, `TowerBanLogger`, `TowerEconomy`) were built and staged but not activated in this run.
- **Build note**: Mod source code is managed in the `TheTowerMods` (separate mods repository) repository. Reference assemblies (`refs/`) are populated from a device per `TheTowerMods/README.md`.

**Observed on-device**:
- `Loading [TowerSpeed 0.4.0]` — plugin loaded
- `[Info: TowerSpeed] selected=1  cap=100  ceiling=100  timeScale=1.15  applied=100` — **the patch took effect**; this is a mod-side log line emitted from inside the patched code path, not a loader message
- `Chainloader startup complete`
- Crash signatures, all zero: `JNI abort` 0, `SIGABRT` 0, `Stack overflow` 0, `Fatal signal` 0, `NullReferenceException` 0, Harmony exceptions 0

`0 patcher plugins loaded` is expected and not a failure: that counter is IL-rewriting patchers, and `TowerSpeed` patches at runtime through Harmony.

The session was force-stopped before this run so that `BootstrapActivity` started a fresh `:game` process instead of taking its in-process restart path, which would have mixed this run's logs with the vanilla run's.

#### Deterministic-zip re-verification — 2026-10-12 (closes the archive-determinism change)

Same device, game (v29.0.6), NDK. Only difference from the runs above: both shippable zips rebuilt by `scripts/zip_deterministic.py` (sorted, files-only, fixed mtime/mode) and the manifest rebaselined to exactly those two lines (11 `.so` untouched).

**Verdict**: PASS on the boot/extraction path; one post-boot native crash observed and exonerated by A/B.

- **APK under test**: `com.bepinex.android.test`, SHA-256 `1eb303737fc9ed2a26e88a48f0fd84da8fbf0d8939555c897373c1562881e28c` — byte-identical across two separate clean builds minutes apart. Two install cycles (fresh bytes, then committed bytes for the control, then fresh again for the modpack run).
- **Vanilla (fresh bytes)**: `Chainloader startup complete`, `Restored 29197 methods` + 18,207 IL-unstripped (exact baseline counts), `il2cpp_runtime_invoke` stub resolved (1 hop), crash signatures zero. ~100 s after startup the `:game` process died with SIGSEGV fault `0x60` in `top.canyie.pine.Pine.getObject` via ART `CheckReferenceResult` on a ThreadPool thread — a different signature from the recorded Firebase `DeleteInternal` fault `0x8`.
- **Control (committed bytes, same device, same procedure)**: `startup complete`, then the **same** SIGSEGV `0x60` via `Pine.getObject`, this time on the main thread ~13 s after startup. Not a rebuild regression: identical fault address and frame with the old bytes. Both tombstones preserved (40 + 30 frames). The Pine/ART/JNI crash is pre-existing, timing- and thread-variant, and independent of the managed bytes; it joins the open Firebase crash as game-flow crashes under hosting, both post-boot.
- **Modpack (fresh bytes, `TowerSpeed` 0.4.0 adb-staged)**: `Applying modpacks: TowerSpeed` → `Loading [TowerSpeed 0.4.0]` → `Patched Main.GameSpeedModifier` + `Patched Main.Update` → `startup complete` → `selected=1 cap=100 ceiling=100 timeScale=1.15 applied=100` (the mod-side line from inside the patched path — Harmony proven end-to-end on the new bytes), followed by live `selected=0 … timeScale=0` game ticks. Crash signatures zero; `:game` alive past both vanilla runs' death marks.

Two process notes from this session: `verify_prebuilts.sh` refused the fresh zips before the manifest rebaseline (the gate working as designed — rebased via `--update-manifest`, old manifest kept for revert); and an incremental (non-clean) Gradle build packaged 958 local entries against a 475-entry central directory (+31 MB), caught by entry-count inspection before install — always `clean` after swapping staged assets.

### Phase 2: Upstream Pin Drift Monitoring
- **Scope**: Automated drift tracking across all external submodules and dependencies.
- **Status & Tasks**:
  - [x] Implement unified drift check script (`scripts/check_upstream_pins.sh`).
  - [x] Integrate drift check into `sync-upstream.yml`.
  - [x] Implement rolling issue notification for failures (`scripts/ci_failure_notice.sh`).

### Phase 3: Declarative `.env` Pin Migration (submodule gitlink → `.env`) [COMPLETE]
- **Objective**: Replace every submodule gitlink with a declarative `.env` pin in `provenance/`, so all five upstream dependencies (BepInEx, Pine, dobby, MonoMod, Il2CppInterop) plus the .NET Runtime are tracked as plain text in one shape. `.gitmodules` and the `third_party/` gitlinks go away last.
- **Rationale**: The `.env` shape is already proven on the three dependencies that use it (`provenance/dotnet-runtime.env`, `il2cppinterop.env`, `monomod.env`) — `UPSTREAM_URL` / `UPSTREAM_REF` / `UPSTREAM_COMMIT`, diffable in review and consumed by `check_upstream_pins.sh` unchanged. Submodules are a *second* pin representation for the same three remaining repos, and the divergence is what makes `check_upstream_pins.sh` carry two code paths today.
- **Correction to the previous framing of this phase**: it was written as "completely remove all git submodules", listing only `third_party/BepInEx`. That undercounted the work and misdescribed it. Two things were wrong with it, both verified against the tree on 2026-10-12:
  1. **Pine and dobby are in scope and are architectural dependencies, not optional.** `build_native_deps.sh` builds `libpine.so` and `libdobby.so` from them (`scripts/build_native_deps.sh:97,107`). Dropping Pine removes `PlayBillingFix` (ADR 6, milestone 16); dropping dobby removes the ADR 4 `libfusion.so` hook engine. Neither is a cleanup.
  2. **The task list missed five files that read the gitlinks.** Removing the submodule without migrating these breaks the build or, worse, breaks it *silently* — see the ordering note below.

- **Trust model change — read before starting**. A submodule gitlink is verified *by the checkout itself*: `actions/checkout` fetches that exact SHA and fails if it cannot. A `.env` pin is a **URL plus a hash that CI fetches for the first time**, so `git clone $UPSTREAM_URL` retrieves whatever the remote serves and the pin becomes an assertion rather than a guarantee. This migration therefore moves these three deps across a trust boundary, not just across a representation. The mitigation is mandatory and is task 5 below; without it this change is a regression in supply-chain strength, not an improvement.

- **Scope, verified file by file on 2026-10-12** — reads the gitlink / `.gitmodules` / `third_party/`:
  | File | What it does with the pin |
  |---|---|
  | `scripts/check_upstream_pins.sh:82-88` | `derive_pins()` loops submodule paths, parses `.gitmodules` for the URL, `git ls-tree` for the SHA |
  | `scripts/test_upstream_pins.sh:80` | Hardcodes `for name in BepInEx Pine dobby`; a residual gitlink must fail here |
  | `scripts/apply_patches.sh:28,36` | `cd` into the submodule; reads the pin via `git -C "$ROOT" rev-parse :third_party/BepInEx` |
  | `scripts/build_native_deps.sh:97,107` | Clones `third_party/dobby` and `third_party/Pine` from **local paths** — becomes a URL clone |
  | `scripts/sync_upstream.sh:11` | Stages the submodule upstream |
  | `scripts/report_patch_conflict.sh:25,53` | Resolves the pin for conflict reporting |
  | `scripts/package_bepinex_android.sh:19` | Reads from the submodule |
  | `build-bepinex.yml`, `sync-upstream.yml`, `verify-provenance.yml` | `submodules: true` + `third_party/BepInEx` paths |
  | `build-native-deps.yml` | `submodules: true` + `paths: third_party/**` trigger |
  | `build-launcher-apk.yml:40` | `submodules: true` but **consumes artifacts only** — no `third_party` reference; drop the flag |
  | `scripts/test_ci_failure_notice.sh:48` | Names `third_party/BepInEx/foo.cs` inside a fixture string only — cosmetic, no change needed |

- **Out of scope, deliberately**: `launcher/scripts/verify_prebuilts.sh` (note the `launcher/` prefix) validates SHA-256 and 16 KB alignment and never reads a submodule. todo.md previously cited it as `scripts/verify_prebuilts.sh`; that path does not exist and the real one is not affected by this phase.

- **Tasks, in dependency order**:
  - [x] **1.** Add `provenance/pine.env` and `provenance/dobby.env` in the existing shape (`UPSTREAM_URL`, `UPSTREAM_REF`, `UPSTREAM_COMMIT`), carrying the current pins `216d910` and `6813ca7`. No behaviour changes yet — this only establishes the pins so the later steps have something to move to. Verified 2026-10-12: both files source cleanly *into one shell together* (a multi-line patch list sourced unquoted executed its second line as a command — now single-quoted, same construct as `MONOMOD_COMPONENTS`), each `UPSTREAM_COMMIT` matches the gitlink it replaces, each `UPSTREAM_URL` matches `.gitmodules`, and both patches apply to their pinned commit in fresh clones from upstream. Both pins are current upstream `master` heads, so this records state rather than freezing drift.
  - [x] **2.** Add `provenance/bepinex.env` for pin `5b766a3` (the commit the completed `Submodule Out-of-Sync` milestone resynchronised to; do not move it in this task). Verified 2026-10-12: pin and `UPSTREAM_DESCRIBE` (`v6.0.0-pre.2-74-g5b766a3`) both match the live gitlink. Two things were deliberately **not** recorded as values: the patch queue is stored as the `patches/*.patch` glob rather than twelve filenames, because `apply_patches.sh` applies by glob in lexicographic order and an enumerated list would be a second source that can disagree (and would need editing on every new patch); and the entrypoint coordinate is stored as a *pointer* to `dotnet.cpp` rather than as `TYPE`/`METHOD` strings, because `build-bepinex.yml:112-114` parses it out of that file on purpose — duplicating it here would let a stale C++ source and a fresh pin file agree with each other while disagreeing with the shipped `libfusion`.
  - [x] **3.** Rework `check_upstream_pins.sh` `derive_pins()` to read all five deps from `provenance/*.env`, dropping the `.gitmodules`/ls-tree loop, so the Phase 2 drift report has one input shape. Verified 2026-10-12. The file list is **explicit, not a `provenance/*.env` glob** — a glob would silently start treating an unrelated file in that directory as a pin, and silently stop reporting one that had been renamed away. Added `check_gitlink_agreement()`: while the gitlinks still exist (they are removed only in task 11), each is compared against its env file and a disagreement is **exit 1**, not a report. Without it the migration window produces precisely the failure this repository has removed three times — a check that quietly passes on stale input, reporting "current" about a commit the build never checks out. Verified fail-closed by moving one pin file's commit.
  - [x] **4.** Update `test_upstream_pins.sh` expectations to the env-derived table. **Done immediately after 3, as required — and it worked as designed:** between the two edits the fixture was **red**, failing on `bepinex`/`pine` because the script still emitted `BepInEx`/`Pine`. That is the half-migration detector firing against the real tree. Three cases added beyond the rename: the exact pin **set** (nothing extra, nothing missing), **every `provenance/*.env` on disk is watched** (`dotnet-runtime.env` exempt — it has its own stricter checker, and duplicating it here would make two scripts that can disagree), and the **cross-check is live**. All three verified by fault injection against the real tree rather than by rigging the fixture: a moved pin is detected *and the offending pin is named*; an unwatched `newthing.env` is detected; dropping `dobby.env` from the list is detected three ways at once.
  - [x] **5.** Add the post-clone pin assertion — `test "$(git -C "$DIR" rev-parse HEAD)" = "$PIN"` — to every new `.env`-driven clone, mirroring what `sync-upstream.yml:75-77` already does for the submodule. Load-bearing per the trust-model note above. Without it a moved or substituted upstream builds silently. **Done together with 7**, because it is the first `.env`-driven clone and an assertion with nothing to assert is not a check. Implemented in `build_native_deps.sh`: `stage()` clones, asserts `rev-parse HEAD` equals `UPSTREAM_COMMIT`, and refuses on mismatch. Also asserts the **nested** `xz-embedded` pin — see 7.
  - [x] **6.** Convert `apply_patches.sh` to clone into a scratch directory and replay `patches/*.patch` via `git am --3way`, replacing the in-place `cd` and the `git -C "$ROOT" rev-parse :third_party/...` pin read. **Done together with 9**, as flagged: the clone destination had to land where three workflows already expected it. Verified 2026-10-12 by deleting `third_party/` **entirely** and running the pipeline — the queue replays all 12 patches onto a fresh URL clone, and the repository is left untouched because `build/` is gitignored. The old script refused to run on a dirty tree precisely *because* it mutated the checkout it was reading; it now owns a scratch tree, which is what makes the submodule deletable afterwards without any build having left residue in it.
    - Added **`scripts/bepinex_paths.sh`** rather than rewriting the path in each caller. The destination was written out in six places across three workflows and four scripts, and moving only some of them fails silently: `apply_patches.sh` clones to the new location while a workflow restores the old one, and the job passes against a stale tree. One sourced definition makes that half-migration inexpressible.
  - [x] **7.** Convert `build_native_deps.sh` `stage()` to clone from `$UPSTREAM_URL` at `$UPSTREAM_COMMIT` instead of the local `third_party/` path. The scratch-dir + `git apply --3way` structure already existed and is unchanged. Verified 2026-10-12 by **actually running it**: a clean `rm -rf build/deps` rebuild from the URL pins reproduced `libdobby.so` and `libpine.so` **byte-identical to the committed blobs** (`git status` on `jniLibs/arm64-v8a/` is empty). Three things this surfaced:
    1. **Pine carries a nested submodule that is not optional.** `core/src/main/cpp/external/xz-embedded` is compiled straight into `libpine.so` — `CMakeLists.txt:16-36` pulls in `xz_{crc32,dec_lzma2,dec_stream}.c`. `--recurse-submodules` resolves it to whatever the *remote* serves, so the checked-out top-level commit could be identical across two runs while the compiled `.so` differed — a reproducibility hole no hash here would catch, because the hash is of the top-level commit. Now pinned and asserted as `PINE_SUBMODULE_XZ_COMMIT=d4a9bc83` (the value the committed submodule already resolved to, so this pins what was built rather than changing it). Pine's second nested submodule, `enhances/.../dobby`, sits in the Xposed target this port does not build; recorded as not-fetched.
    2. **A pin naming a commit upstream does not serve used to fail as `fatal: unable to read tree`** — which reads like a corrupt clone, not a wrong pin. Now checked with `cat-file -e` first and reported as what it is.
    3. The patch lists moved into the pin files (`PINE_PATCH_FILES`, `DOBBY_PATCH_FILE`), so "what this dependency is adapted by" is recorded in one place rather than hardcoded next to the build.
  - [x] **8.** Migrate `sync_upstream.sh`, `report_patch_conflict.sh`, `package_bepinex_android.sh`. Verified 2026-10-12: `sync_upstream.sh` was run against a throwaway local upstream and, at the pin, reports `upstream unchanged` with `moved=false` (which suppresses the PR); one commit later it reports the move and stages **exactly one line** — `UPSTREAM_COMMIT` — leaving every explanatory comment intact. It queries upstream through a throwaway clone rather than fetching into a submodule, so the repository stays clean and no local-only commit can reach a PR. `report_patch_conflict.sh` reads the pin from the env file and refuses to diagnose a tree that is not there, rather than reporting "upstream changed" from an empty directory.
  - [x] **9.** Update the four workflows: drop `submodules: true` (and the `third_party/**` trigger in `build-native-deps.yml`), retarget paths. Drop the flag outright in `build-launcher-apk.yml`, which never needed it. Verified 2026-10-12: all five workflow YAMLs parse; every `third_party/BepInEx` path is retargeted to `$BEPINEX_SRC`; `submodules: true` appears in **none** of them. `sync-upstream.yml`'s "keep the PR gitlink pristine" step became "the advertised pin is a real upstream commit" — `cat-file -e` plus `merge-base --is-ancestor` against `origin/master`, asserting the same property the old gitlink reset did, on the text pin instead. `fetch-depth: 0` is kept there because the ancestry check needs full history, which a gitlink used to bring along and a text pin does not. Trigger paths moved from `third_party/BepInEx` to `provenance/bepinex.env`.
  - [x] **10.** Update `provenance/SOURCES.md`, `docs/ci-cd.md` and `README.md`. Verified 2026-10-12: the two source tables now name `provenance/*.env` instead of `third_party/`, `libpine.so` records its nested `xz-embedded` pin, the 2026-10-06 incident passage now says the gitlink failure mode is fixed rather than describing it in the future tense, `ci-cd.md` gains a **Pin Sources** table with all six pins (each value re-read from the file rather than typed), and `README.md` documents that nothing is vendored or checked out. Deliberately **not** rewritten: `progress.md:75` (historical record of what milestone 1 did) and MonoMod's own `external/iced` submodule (`SOURCES.md:266`), which is a fact about MonoMod's build, not this repository's layout.
  - [x] **11.** Remove `.gitmodules` and the `third_party/` gitlinks. Done last, so every state above was one where CI could still build. Pre-flight first: all six pins (including the nested `xz-embedded`) confirmed **fetchable from upstream** before anything was deleted, so removal could not orphan a commit. Removed the three gitlinks, `.gitmodules`, `third_party/`, and the stale `submodule.*` sections in `.git/config` — leaving those would have kept stale URL config alive after the file defining it was gone. Verified with `third_party/` absent and `build/` deleted from scratch: the queue replays all 12 patches, both hook engines build, and `libdobby.so` / `libpine.so` come out **byte-identical to the committed blobs**.
  - **A real gap surfaced only after removal**, and it was in the check written to guard the migration. `check_gitlink_agreement()` returned early when `.gitmodules` was absent and tested `[ -e third_party/... ]` on the working tree — so a residual gitlink with no `.gitmodules` was reported as *"no .gitmodules: pin source is provenance/*.env alone"* and **passed**, while `git ls-files -s` still listed mode `160000`. The check said it verified nothing, in the tone of something that had. Rewritten to read gitlinks from the **index**, not the working tree (`git ls-tree HEAD` also describes a commit rather than what is about to be built, so it still shows gitlinks staged for deletion), and to refuse **both** half-removed states. All three verified by injection, and the fixture case that would have caught it is now present — itself verified against a rigged guard.
- [x] **12.** End-to-end reproduction with submodules gone. Run locally on 2026-10-12, from a deleted `build/` and an absent `third_party/`: `verify_prebuilts.sh` PASS (13 binaries, checksums + 16 KB alignment) → `apply_patches.sh` (12 patches) → `build_native_deps.sh` (both hook engines **byte-identical to committed**) → `build_monomod.sh` → `build_il2cppinterop.sh` (all four `1.5.2-android.3` packages) → `dotnet restore` (no NU1602/3 substitution for our packages) → `dotnet build` (0 errors) → both entrypoint assertions (`AndroidEntrypoint.Start` present, `ExportThunkResolver` present, `ignoreRetn` fallback absent) → `package_bepinex_android.sh` → `verify_prebuilts.sh` PASS again. `verify_crypto_pal.sh` and `verify_dotnet_pack.sh` also pass. All 8 fixtures pass; working tree clean.
  - **The packaging gate caught a stale local NuGet cache, and that is the interesting part.** `package_bepinex_android.sh` refused with `stale/wrong Il2CppInterop.Runtime.dll`. Investigated rather than cleared: the *built* DLL hashed `a4ab4069…`, which is byte-identical to the copy inside the zip committed on 2026-10-08 — so the build was right and the **feed** was the stale side. Cause is `~/.nuget/packages/il2cppinterop.runtime/1.5.2-android.3`, written **2026-10-08**: NuGet keys its global cache by `id+version`, not by source feed, so restore satisfied the reference from a four-day-old cache entry instead of the package just rebuilt into `local-nuget/`. Clearing that one cache directory and rebuilding made the gate pass unchanged. **Not a regression from this phase**, and CI is unaffected because it starts from a clean runner — but it is the exact substitution `package_bepinex_android.sh` exists to catch, and it is worth knowing that a green local package step can be the *stale* side of the comparison.
  - The fresh zip has 47 entries against the committed zip's 49: the difference is only the explicit `core/` and `config/` directory entries. No file is lost, confirmed by name-set comparison. **Closed 2026-10-12:** both shippable zips are now written by `scripts/zip_deterministic.py` (sorted, files-only, fixed mtime/mode), so this divergence cannot recur; the next deliberate rebuild re-baselines the committed bytes.

### Phase 4: Storage Access Framework (SAF) Migration
- **Objective**: Comply with modern Android Scoped Storage requirements on Android 11+ (API 30+).
- **Status & Tasks**:
  - [x] Implement version-stamped core extraction (`.extracted-version` in `FileExtractor.kt`) to ensure clean core upgrades without data loss.
  - [x] Verify app-specific sandbox storage in test builds (`com.bepinex.android.test`). Re-verified on device 2026-10-11: the launcher wrote to `/storage/emulated/0/Android/data/com.bepinex.android.test/files/...` while `com.bepinex.android` kept `/data/user/0/...`.
  - [x] Drop `MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`. No code path used shared storage: `BepInExPaths.getGameRootDir()` resolves through `getExternalFilesDir()`, and the one hardcoded path (`trace_log.h`) is app-specific storage. Verified two ways — `aapt2 dump permissions` on the built APK and `dumpsys package` on the installed one both show zero storage permissions. This also removed the runtime all-files-access request in `MainActivity`, which was asking for access it could not use; the launcher log now reads "No storage permission required".
  - [x] Resolve modpack import archive name resolution in `BepInExNavHost.kt` / `ModpackManager.kt`. `importModpack` resolves the display name from the SAF URI via `resolveDisplayName`, rejects non-zip extensions, and stages into a temp dir with a path-traversal check.
  - [x] Migrate modpack export from a directly chosen `File` to `CreateDocument` + `OutputStream` (`ModpackManager.exportModpackToUri`). The archive is built in the app cache and streamed to the URI the user chose, so a failure part-way cannot leave a truncated archive in the user's chosen location.
  - [x] Align `FileProvider` paths in `file_paths.xml` with diagnostic log outputs. The file declared only `BepInEx/plugins/`, but all three `getUriForFile` call sites (crash diagnostics in cacheDir, and the BepInEx log in external files) were outside it — each would have thrown `IllegalArgumentException` when a user tapped Share. Now covers `external-files-path .` and `cache-path .`.
  - [x] Grant the share receiver actual read access (`setClipData`) at all three share sites. See the promotion gate below for the measured evidence.
  - [x] Remove the `게임 리소스 복사` settings button, which duplicated behaviour that is already automatic. `BootstrapActivity` copies the game's Data assets on first run and on a version-stamp change (`vc:versionName`), so the button's only real effect was clearing the directory and deferring to the next launch — indirect, and invisible to the user. Removed with `MainViewModel.copyGameResources`, its four-step wiring and 16 strings across four locales.
- **SAF export and import: verified on device 2026-10-11.** *(This line previously said "not yet verified on device", which contradicted the promotion gate below — that gate records the picker exercised end to end, and the gate is the newer record. Corrected 2026-10-12.)* What is verified: `CreateDocument` writes an archive the user chose, that archive re-imports cleanly, and all three `FileProvider` share actions return a readable URI. The permission removal is verified separately by `aapt2 dump permissions` and `dumpsys`.

#### Decision: no legacy migration; the test build is the production candidate

**Superseded 2026-10-12:** the test variant is gone — single package `com.bepinex.android`, build types debug/release only (`-Pbepinex.applicationId` removed). The decision below is kept as the record of why the legacy tree was abandoned; "ships as production unchanged" is now true by construction rather than by promotion.

**Decided 2026-10-11. The legacy `/sdcard/BepInEx_Android` tree is abandoned, not migrated.**

The test build is verified on-device and, once fully verified, ships as the production build unchanged. The same tree produces both — `assembleDebug`, default `applicationId` `com.bepinex.android.test`; add `-Pbepinex.applicationId=com.bepinex.android` for production. There is no second code path.

**Why no migration.** The legacy tree holds 548 MB across 13 game directories, written by `com.bepinex.android` 0.181 (installed 2026-10-06), which predates the `getExternalFilesDir()` move. Migrating it would mean shipping a build that both drops all-files access and reads it, which the new build cannot do: once the permission is gone it cannot read a tree it does not own. A one-shot migration would need a separate step carrying its own grant — more moving parts than the data is worth, against a path that still works on the installed build.

**What is therefore true, stated plainly:**
- The installed 0.181 keeps working. It still holds its grants and still reads the legacy tree.
- Installing the new build over it orphans those 548 MB. The new code never looks at `/sdcard/BepInEx_Android`, so the games re-extract fresh. Expected, not a regression to chase.
- Any modpack, config or progress a user had under the legacy tree is lost at promotion. That is the accepted cost.

**Promotion gate** — the test build ships as production when all of these hold:
- [x] Zero storage permissions in the built APK (`aapt2 dump permissions`) and on device (`dumpsys package`).
- [x] Game detection and framework extraction work with no permission request (`No storage permission required; paths are app-specific`, 6 IL2CPP games found).
- [x] BepInEx boots to `Chainloader startup complete` on device, with and without a modpack (see the Phase 1 record).
- [x] SAF export writes an archive to a location the user chose, and that archive re-imports cleanly. Verified on device 2026-10-11, end to end: `CreateDocument` picker → `Exported modpack via SAF … (4491 bytes)` → `OpenDocument` picker → `Imported modpack: TowerSpeed (1)`. The imported directory holds both `plugins/TowerSpeed.dll` and a `modpack.json` with `modCount: 1`. Staging files are gone on both sides afterwards.
- [x] Import of a user-chosen file produces a working modpack. Same run as above.
- [x] All three `FileProvider` share actions return a URI and the receiver can read it. Verified on device 2026-10-11 through the log viewer's share button: the sheet opens, the preview renders `항목 1개 / LogOutput.log`, and KakaoTalk, Gmail and Drive are offered as targets with no `Permission Denial`. This required two separate fixes — see below.

**Network dependency, measured.** The launcher has exactly one hardcoded endpoint: `https://unity.bepinex.dev/libraries/`, called once per game from `BootstrapActivity` via `UnityLibsDownloader.ensureLibraries`. It returns early when the version's zip is already cached, so it is a first-run cost, not a per-boot one — the Tower's `6000.3.15.zip` (3 MB) was present on the device after the first launch. A failure does not block boot: the caller logs it and disables auto-download in `BepInEx.cfg`. **The launcher therefore boots offline after the first successful download per Unity version.** The Firebase calls in the logs are the game's own SDK running in the `:game` process, not launcher code.

**Per-boot copying, measured — not wasteful.** `BepInEx/` is 103 MB on the device (`interop` 45 M, `core` 24 M, `dummy` 22 M, `unity-libs` 11 M), and none of it is copied on launch: `copyModpackRootContents` skips a `runtimeOwned` set that names exactly those. What is copied each boot is modpack state only — `plugins/` 16 K, `config/` 16 K, `vanilla/` 31 K. The one remaining inefficiency is in import: after staging it tries `renameTo` first and only falls back to `copyRecursively`, and since staging and destination share a filesystem the fallback should be unreachable. Left alone because it is a rare path, not because it was checked out.

**Verification ran against `com.bepinex.android.test` only** (until 2026-10-12, when the variant was removed; records below keep the package name they were measured under). The production package on the device was left untouched until promotion, so the pre-existing install stayed available for comparison.

**Two defects found while closing the last gate item, neither visible from reading the code:**

1. `file_paths.xml` declared only `BepInEx/plugins/`, while all three `getUriForFile` call sites share files outside it — crash diagnostics in `cacheDir`, and the BepInEx log under external files. Each would have thrown `IllegalArgumentException("Failed to find configured root")` the first time a user tapped Share. Now covers `external-files-path` and `cache-path`.

2. Every share built `ACTION_SEND` with `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION` but **never called `setClipData`**. The path resolved and the sheet opened, so it looked like it worked, and then the system logged `ChooserPreview: Could not read content://… metadata … call Intent#setClipData()`. The grant on the chooser's copy of the URI comes from ClipData; `EXTRA_STREAM` alone does not carry it. Fixed at all three sites (LogViewerScreen, GameLogOverlay, MainActivity crash export).

### Phase 5: Build Provenance & Attestation
- **Objective**: Implement verifiable build attestations for public releases.
- **Status & Tasks**:
  - [x] Integrate `actions/attest-build-provenance` into publishing workflows (`.github/actions/attest-inputs`, used by all three producers).
  - [x] Digitally sign build manifests against workflow run identities — GitHub's OIDC-backed Sigstore statement binds repository + workflow + run + commit to the exact bytes, and `scripts/verify_attestation.sh` enforces it on the shipping path.
  - [x] Fixture for the gate (`scripts/test_attestation_gate.sh`), verified in the fail-open direction.
- **Remaining before it covers a release**: nothing structural. The input gate runs before Gradle (the APK does not exist yet), and the shipping APK is attested by `build-launcher-apk` after zipalign for downstream consumers. A release consumer should verify the downloaded APK with `gh attestation verify` before installing; that consumer-side check is outside this repository's own run.
- **Operational note**: the gate needs `gh attestation` (gh ≥ 2.49) on the runner. It probes for the subcommand and exits 2 with that reason rather than reporting every artifact as unattested.

### Phase 6: ARM64 Inline Hook Space Guard & Logging Alignment
- **Objective**: Prevent native memory corruption (`SIGSEGV` in `mono_class_get_checked`) during inline hooking on ARM64 Unity 2022.3, prevent orientation-induced launch kills, and align logging with Android Logcat architecture.
- **Key References**: `docs/arm64-hook-safety-and-log-architecture.md`.
- **Status & Tasks**:
  - [x] Set `UnityLogListening = false` in `build_assets/BepInEx/config/BepInEx.cfg` and `IL2CPPChainloader.cs` (ADR 9).
  - [x] Implement `ExportThunkResolver.CanSafelyHook` on ARM64 (ADR 8).
  - [x] Wire `ExportThunkResolver.Resolve` into `NativeDetour` constructor with space check (ADR 7).
  - [x] Sanitize `FindClassGetFieldDefaultValueXref()` in `references/il2cppinterop-arm64-disarm.patch` (ADR 10).
  - [x] Omit broken `MetadataCache_GetTypeInfoFromTypeDefinitionIndex_Hook` from `InjectorHelpers.Setup()` on Android/ARM64 (ADR 11).
  - [x] Add `android:configChanges` to `BootstrapActivity` and guard against orientation launch restarts / BAL blocks (ADR 12).
  - [x] Rebuild `Il2CppInterop` packages and update local NuGet feed.
  - [x] Rebuild `BepInEx.Android.zip` and launcher APK.
  - [x] On-device verification on `SM-S931N`: *Revolution Idle* (with active plugins) and *The Tower* clean launch without crash.

### Phase 7: Release Build (R8 Minification)
- **Objective**: Make a non-debuggable build both possible and trustworthy. Found by turning on R8 on a project that had only ever shipped `assembleDebug`.
- **Status & Tasks**:
  - [x] Enable `isMinifyEnabled` + `isShrinkResources` on `release`. APK 104.4 MB → **53.4 MB (−49%)**, dex 57.6 MB → 7.9 MB. The dex bulk is `androidx.compose.material.icons.extended` — 21,320 icon classes shipped, 33 used.
  - [x] Add `-keep class net.dot.android.crypto.** { *; }` for the PAL, which is looked up by JNI name and whose members R8 would otherwise strip.
  - [x] Declare `proguardFiles` on `debug` as well (ADR 14). It was on `release` only, so any debug minification ran R8 with no project rules at all.
  - [x] Point `verify_crypto_java.sh` at the **release** APK; it previously inspected `apk/debug/app-debug.apk`, so the shipped artifact was never checked.
  - [x] Fix an awk fail-open in that script: the `inc` flag was never cleared at the next class header, so one class's constructor could satisfy another's check.
  - [x] Re-run the PAL gate **after** the Gradle build in `build-launcher-apk.yml`. It skips when no APK exists and ran before Gradle, so the dex stage had never executed in CI.
  - [x] **Fix the non-debuggable PAL abort via JNI FindClass VMT bridge (ADR 15).** Initial `System.load()` preloading caused duplicate DSO loading under linker namespace isolation (`clns-9`), leaving CoreCLR's copy with an uninitialized `g_vm` and crashing *Revolution Idle* during Cpp2IL SHA1 token computation. Replaced by capturing the Application `PathClassLoader` and hooking `env->functions->FindClass` via VMT redirection during native `JNI_OnLoad`. Release now boots stably across both *The Tower* and *Revolution Idle*.

#### The blocker, and what was ruled out — 2026-10-12

`SM-S931N` / Android 17, *The Tower* 6000.3.15f1, `com.bepinex.android` 0.184:

| Build | minify | debuggable | Result |
|---|---|---|---|
| debug | off | true | `Chainloader startup complete`, then Firebase SIGSEGV |
| release | on | false | SIGABRT at PAL init |
| release | off | false | SIGABRT at PAL init |
| release (fixed) | on | false | `Chainloader startup complete`, then Firebase SIGSEGV |

```
GetClassGRef: class net/dot/android/crypto/DotnetProxyTrustManager was not found
signal 6 (SIGABRT) ... com.bepinex.android:game
  #01 libSystem.Security.Cryptography.Native.Android.so
  #02 libfusion.so (dotnet_start_runtime()+1764)
```

- **R8 is not the cause**, proven rather than assumed: the release APK's three PAL classes were dumped with `dexdump` and all 17 descriptors in `provenance/pal-java-contract.txt` are present and unrenamed. The third row above is the proof — minify off, still dead.
- **The abort was resolved via JNI FindClass VMT Redirection (ADR 15).** `System.load()` was eliminated. During `init_platform_pal`, `env->functions->FindClass` is redirected to fallback to the captured Application `PathClassLoader`, allowing `net/dot/android/crypto/*` to resolve cleanly without duplicate DSOs or uninitialized `g_vm` state.
- **A crash remains after `Chainloader startup complete`, and it is not this bug**: `firebase::database::Database::GetInstance` → `DeleteInternal` SIGSEGV at fault `0x8`. A debug build dies at the same address with the same backtrace, so it predates this work and is unaffected by minification, `debuggable`, or the PAL fix. It is the game's own Firebase Realtime Database initialising in a hosted process; `FirebaseComponentDiscoveryFix` covers discovery and `FirebaseOptions`, not the native `Database::GetInstance` layer. Open, and tracked separately.
- **Minify stays on regardless.** It is worth 51 MB and is not implicated.

---

## Completed Milestones

| Milestone | Resolution | Verification |
|---|---|---|
| **Pin Migration (Phase 3)** | All three submodules replaced by `provenance/*.env` pins; `build/` scratch clones asserted against the pin. Nested `xz-embedded` pinned too. | Zero submodules; full local end-to-end rebuild reproduces the natives byte-identically and packages a passing `BepInEx.Android.zip`; 8/8 fixtures. |
| **Submodule Out-of-Sync** | Re-synchronized main branch with upstream pinned commit (`5b766a3b`). | `sync-upstream` workflow passing. |
| **Il2CppInterop Pin** | Pinned to `1.5.2-android.3` via patch `0010` and local NuGet feed. | Deterministic restore and build verified. |
| **MonoMod Pin** | Reconstructed `25.3.7-android.1` via patch `0011` and local NuGet feed. | Deterministic restore and build verified. |
| **Actions Deprecation** | Pinned all GitHub Actions to commit SHAs and migrated to Node 24 runners. | Zero deprecation warnings in CI logs. |
| **Runner Image Pinning** | Standardized all build environments on `ubuntu-24.04`. | Clean workflow runs across all jobs. |
| **16 KB Page Alignment** | Fixed ELF alignment checks in `verify_prebuilts.sh` and build scripts. | Verified with `scripts/test_alignment_check.sh`. |
| **Release Minification** | `isMinifyEnabled`/`isShrinkResources` on for release. APK 104.4 MB → 53.4 MB (−49%); dex 57.6 MB → 7.9 MB, from `material.icons.extended` shipping 21,320 icon classes of which 33 are used. `-keep class net.dot.android.crypto.** { *; }` added for the PAL, and `proguardFiles` now also declared on `debug`. | R8 proven **innocent** on device — see the release-blocking finding below. Minify stays on; the blocker is separate. |
| **Release PAL Blocker** | Non-debuggable builds abort at PAL init (`GetClassGRef` → SIGABRT). **Fixed.** | Root cause: ART could not resolve `net/dot/android/crypto/*` from a `dlopen`-loaded DSO with no ClassLoader. Fixed via ADR 15 (JNI FindClass VMT redirection). On-device: both *The Tower* and *Revolution Idle* boot to `Chainloader startup complete`. |
| **Firebase SIGSEGV** | `firebase::database::Database::DeleteInternal` → SIGSEGV. **Not reproduced in current session (2026-10-12 18:32+).** | Three tombstones recorded earlier today (16:01, 16:49, 17:10) from previous runs. Current session boots to title screen with no crash. Requires in-game Firebase code path to confirm resolution. |
| **Pine getObject SIGSEGV** | `top.canyie.pine.Pine.getObject` → ART `CheckReferenceResult` SIGSEGV fault `0x60`, timing-/thread-variant (ThreadPool vs main, +13 s vs +100 s). A/B-exonerated from the managed bytes: committed bytes crash identically on the same device/procedure. | Both tombstones preserved (40 + 30 frames). `PlayBillingFix` callbacks dieted 2026-10-12 — install-time resolution plus per-class caches, zero lookups on the calling thread — to shrink the GC-danger window; install path re-verified on device (hooks on 2 classes, startup complete, zero signatures). Repeat measurement 2026-10-12 (diet build, same device/procedure): **9/9 survival** — played 6/6 plus game-wiped 3/3, billing bursts present every session — against 2 deaths in ~4 comparable pre-diet sessions. True full-fresh is infeasible: progress is cloud-restored despite local wipes (user-observed), so the state hypothesis cannot be isolated locally; G-series aborted. Pine-side race remains open upstream. |
| **CI Artifact Validation** | Introduced `verify_ci_artifacts.sh` for producer-consumer hash checks. | Verified with `scripts/test_verify_ci_artifacts.sh`. |
| **Producer Commit Gate** | Extracted the three-run-ids-agree-on-commit check from inline YAML to `scripts/verify_producer_commit.sh` with fixture; wired into `build-launcher-apk` and `verify-provenance`. | One mismatch fails and is named; API failure closes as `<unknown>`; missing gh/context/args fail; CI green. |
| **NDK libc++ Pin** | `scripts/verify_ndk_libcxx.sh` checks the NDK-copied `libc++_shared.so` (the one binary with no producing run) against `provenance/sha256sums.txt` on both shipping paths (`build-launcher-apk`, `sync-upstream`). | Tampered/missing/entry-less/manifest-less all fail; real-tree acceptance; CI green. |
| **Egress Block Pilot** | `block` with explicit allow-lists on `verify-provenance:provenance` + `:patch-queue` (green-confirmed) and `build-bepinex:build` (toolchain-half confirmed; upload half pending quota recalc). | `test_harden_runner.sh` asserts per-job policy via registered `PILOTS`, fail-closed both ways; `ci-cd.md` records pilot + quota incident. |
| **.NET SDK Pinning** | Fixed .NET SDK to `10.0.401` in workflows and root `global.json`. | Deterministic builds across environments. |
| **Verified Commit Tag** | Added rolling `verified` tag to mark latest green main build. | Verified via `scripts/mark_verified.sh`. |

---

## Next Steps (2026-10-12)

### Priority 1: Trust Relationship Building

| Item | Current State | Target |
|------|--------------|--------|
| **SLSA Level** | Unofficially SLSA 2–3 (private repo: fallback provenance; Sigstore inactive) | SLSA 3 formal achievement (requires public repo for Rekor) |
| **Reproducible builds** | Archive-level deterministic (sorted, fixed mtime/mode, `SOURCE_DATE_EPOCH`); managed assemblies intentionally not bit-reproducible (embedded commit SHA keeps per-patch history) | All artifacts reproducible |
| **SBOM** | Source SBOM in `spdx-json` (`scripts/generate_sbom.py`, 2026-10-12) | Artifact inventory via `syft` as consumer-side complement |
| **Transparency log** | None (blocked on public repo) | Rekor integration |
| **Signed releases** | Fallback provenance on private; APK+SBOM attested per-run | Sigstore signing + verification (requires public repo) |

**Concrete tasks:**
1. SLSA 3: Integrate `slsa-github-generator`, set `provenance` level on all workflows, apply Harden-runner — **Harden slice done 2026-10-12** (all 7 jobs across 6 workflows run `step-security/harden-runner` pinned to SHA with `egress-policy: audit`; fixture `test_harden_runner.sh` pins presence/pinning/first-step order, verified fail-closed by injection). `slsa-github-generator` + `block` policy remain **blocked while private** (Sigstore/Rekor unavailable for user-owned private repos; `block` additionally needs per-job allow-lists measured from these audit logs first). **Update 2026-10-13:** `block` is now piloted per-job instead of waiting wholesale — `verify-provenance:provenance` and `:patch-queue` confirmed green in `block` mode with explicit allow-lists (runs `38263462571`, `38265156245`, verified `egress_policy: block` in the logs); `build-bepinex:build` toolchain-half confirmed, upload half pending artifact-quota recalculation (quota incident, see `ci-cd.md`); `test_harden_runner.sh` asserts per-job policy with a registered `PILOTS` set, fail-closed both ways (pilot reverted to audit fails, unregistered flip to block fails). Rollout order: native-deps → fetch-runtime → sync-upstream → launcher-apk last (widest Gradle egress).
2. Reproducible builds: Remove build timestamps (`SOURCE_DATE_EPOCH`), sort file order, add reproducibility tests — **archive slice done 2026-10-12** (`scripts/zip_deterministic.py`: both shippable zips sorted/file-only/fixed mtime+mode, fixture `test_deterministic_zip.sh`, verified name-set-identical on the real 182-file `dotnet.zip` with `verify_dotnet_pack.sh` PASS). Managed-assembly bytes remain commit-stamped by design (see `SOURCES.md`).
3. SBOM: ~~Integrate `syft` or `spdx-sbom-generator`, auto-generate dependency list~~ **Done 2026-10-12 as source SBOM** — `scripts/generate_sbom.py` emits SPDX 2.3 from `provenance/*.env` + Gradle catalog + toolchain; `scripts/test_generate_sbom.sh` pins completeness; `build-launcher-apk` ships and attests `sbom.spdx.json`; sync releases publish it. `syft app.apk -o spdx-json` remains the consumer-side artifact complement.

### Priority 2: CI/CD Optimization

| Item | Current State | Target |
|------|--------------|--------|
| **Build time** | Single-threaded | Parallel builds |
| **Caching** | Basic (deliberately; caches broke the 500 MB quota 2026-10-12) | Strategic caching |
| **Artifact retention** | 1 day on all CI artifacts; releases carry the rest. 2026-10-13: quota hit with zero active artifacts (stale 6–12h accounting) — producer pilots pause until recalculation, see `ci-cd.md` | Optimized |
| **Test matrix** | Single game | Multi-game |
| **Release automation** | Manual | Automated |

**Concrete tasks:**
1. Parallel builds: ~~Run `build_monomod.sh` + `build_il2cppinterop.sh` in parallel~~ **Done 2026-10-12** — `scripts/build_packages_parallel.sh` runs both concurrently (separate scratch clones, distinct `.nupkg` names; waits for both, fails if either failed, logs serially), wired into `build-bepinex`, `verify-provenance` patch-queue and `sync-upstream` (Il2CppInterop moved before the patch replay there — independent trees, same dependencies). Fixture `test_build_parallel.sh` proves overlap (two 5 s stubs in 5 s) and fail-closed attribution.
2. Caching: ~~NuGet package cache, Gradle cache, native build cache~~ **Tried and reverted 2026-10-12** — SDK toolcache + NuGet HTTP + Gradle dep caches worked technically (96 ms vs 1.9 s cold per package) but one NuGet entry hit 323 MB against the private 500 MB quota and blocked verification runs the same day. Cache purged, steps removed. Status quo (fresh downloads, 1-day artifacts) fits the quota; see `ci-cd.md` for the standing no-cache list.
3. Test matrix: The Tower (Unity 6000), Revolution Idle (Unity 2022.3), Unlock! (Unity 6000)
4. Auto-release: ~~Tag-based auto-release, auto-upload artifacts~~ **Sync path automated; sync releases now attested 2026-10-12** — `sync-upstream` publishes APK+SBOM+attestation bundle on upstream moves (verified consumer-side via `gh attestation verify`). A general tag-based release (arbitrary commit → release) remains manual through `build-launcher-apk` dispatch: orchestrating the three producers plus assembly under one tag needs a `workflow_call` refactor of the shipping path, which is load-bearing and can only be verified by a live run — deferred deliberately, not overlooked.

### Priority 3: APK Optimization

| Item | Current State | Target |
|------|--------------|--------|
| **APK size** | 63.8 MB release (Pine keep-rule; was 53.4, see note) | ~~< 40 MB~~ revised: hold — see note |
| **Native libraries** | arm64-v8a only, 16 KB-aligned, AGP-stripped | Done as-is |
| **Resources** | 0.5 MB in APK — negligible | No action |
| **Split APK** | Single ABI by construction (`abiFilters arm64-v8a`) — nothing to split | Done |

**Size-floor analysis (2026-10-12, local `app-debug.apk` 109 MB uncompressed 120 MB):**
- `classes.dex` 41.5 + `classes12.dex` 13.4 + `classes13.dex` 4.4 MB — the `material.icons.extended` bulk in *debug*. Release R8 already cuts dex 57.6 → 7.9 MB, so this is **not touched**: swapping the dependency would churn imports across 11 screens for zero release gain (user decision 2026-10-12).
- `assets/dotnet.zip` 31.5 + `assets/BepInEx.Android.zip` 8.9 + native `.so` ~17 MB (libcoreclr 6.2 biggest) — pinned runtime/framework bytes. All must stay stored uncompressed (16 KB alignment, direct `.so` loading, dex mmap), and recompressing deflated zips gains ~0.
- Therefore the release floor is the runtime itself: 7.9 + 31.5 + 8.9 + ~10–15 stripped native ≈ 53 MB. `< 40 MB` would mean trimming BCL assemblies out of `dotnet.zip`, which `verify_dotnet_pack.sh` exists to forbid (framework completeness → device `MissingMethod`/`DllNotFound`). Target revised rather than chased.
- **Correction 2026-10-12: the ≈53 MB floor does not hold.** A local release build measured 63.8 MB / 16 MB dex, and R8 itself names the cause (`-whyareyoukeeping`): Pine's consumer rule `-keep class * { native <methods>; }` (shipped in `top.canyie.pine:core:0.3.0/proguard.txt`) keeps **every** class — 11,400 icons survive, 38k seeds. Plain `-keep` with a member filter still matches all classes; the conditional form (`-keepclasseswithmembers`, cf. our own rule 7) is what Pine meant. Shrinking is neutered tree-wide, not just for icons. The 53.4/7.9 record predates this finding and is inconsistent with it (the rule predates 0.184), so it stands flagged for re-measurement, not silently overwritten. Options, undecided: vendor Pine's Java API (drop the rule, keep the pinned native — a supply-chain shape decision, not taken unilaterally) or accept ≈64 MB. Either way `< 40 MB` stays out.
- If distribution size ever matters, the lever is shipping the *release* variant (54–64 MB) instead of debug (109 MB) — not the icon dependency.
- `libil2cpp.so` / `libunity.so`: the game's bytes, not this repository's — out of scope, stated so nobody files it here.

### Execution Order

```
Priority 1 (Trust) → Priority 2 (CI/CD) → Priority 3 (APK Optimization)
```