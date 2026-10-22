# Release-Build Game Crash: Investigation Record

Date: 2026-10-13/14. Device: Galaxy S25 (SM-S931N, Android 17/API 37).
Game: The Tower (`com.TechTreeGames.TheTower` 29.0.6, Unity 6000.3.15f1).
Launcher release tested side-by-side as `com.bepinex.android.release`.

## 1. Symptom

Debug build: game boots to title, billing mocks dispatch, no crash.
Release build: `:game` process dies ~5–60s after launch, deterministically
(~20/20 non-debuggable runs).

## 2. Root cause (proven)

NOT R8/minify. The crash is identical every time:

```
signal 11 (SIGSEGV), SEGV_MAPERR, fault addr 0x8 (null+8 read)
#00 libFirebaseCppApp-13_12_0.so  firebase::database::Database::DeleteInternal()+192
#01 libFirebaseCppApp-13_12_0.so  firebase::database::Database::GetInstance(...)+792
#02 libFirebaseCppApp-13_12_0.so  Firebase_Database_CSharp_InternalFirebaseDatabase_GetInstanceInternal
```

Chain: game takes its production Firebase path → C++ `Database::GetInstance`
→ `DatabaseInternal` init fails → `delete database` → `DeleteInternal`
dereferences a null `App*` (`MakeKey(app=null)` → `app->name()`).

The init fails resolving embedded Java bridge classes
(`net.dot` analogues: `com.google.firebase.database.internal.cpp.*`).
Firebase C++ ships them inside `*_resources_lib.jar` files embedded in its
`.so` files, extracts them to the app cache dir at init, then looks them up
via `FindClassInFiles` — whose Android path passes JNI-slashed names
(`com/google/...`) straight into `DexClassLoader.loadClass()` (contract
requires dotted binary names). Verified in shipped v13.12.0 sources
(`app/src/util_android.cc`); the desktop (`FIREBASE_ANDROID_FOR_DESKTOP`)
path converts slashes to dots, the Android path does not. Preceded in-log by:

```
E firebase: Java class com/google/firebase/database/internal/cpp/CppTransactionHandler not found.
```

## 3. Why debug survives

Debug builds take a different game branch (`Using Auth Prod for testing`,
Firestore-based player data) that never calls C++ RTDB `GetInstance`.
Manifest-debuggable isolates as the strongest correlate (debuggable release
A/B: 4/5 survivals incl. billing/title; non-debuggable: 0 survivals), but it
is NOT deterministic — one debuggable run crashed differently (Pine JNI-ref
abort, diagnostic-code artifact), and one non-debuggable run reached the
testing branch. Timing/state co-determine the branch. Spoofing
`FLAG_DEBUGGABLE` via PM hook + context wrapper did NOT flip the branch;
disabling hidden-API enforcement device-wide did NOT help either.

## 4. Dead ends (corrected during investigation)

- **R8 tree-shaking.** Initial suspect; wrong. All launcher Java/Kotlin paths
  (options rescue, 21 registrars, hooks, CoreCLR, interop, chainloader) work
  identically in crashing release logs. No missing-class aborts anywhere.
- **Stale/corrupt jars.** Runtime-extracted jars byte-verified (sha1 match
  across runs, ART enumeration lists all 4 bridge classes, dex headers sane).
- **File-rewrite races.** Suspected from rebuild churn; the churn was our own
  retry logic re-reading (removed). Live files are stable (sha1-identical).
- **ART can't open the jars.** Disproven: `DexFile` enumeration + sibling
  classes define fine from the same files.
- **Duplicate/stale class loaders.** Loader identities logged; single game
  loader throughout, complete (base + 3 splits).
- **`applicationIdSuffix`/`versionNameSuffix`.** Postdate the original report
  (which crashed on the shared ID); orthogonal.

## 5. Fixes landed (all log-verified on device)

- `applicationIdSuffix=".release"` + `appLabel` placeholder: release installs
  beside debug (`launcher/app/build.gradle.kts`, `AndroidManifest.xml`).
- JNI-slash → dot normalization in `ClassLoaderHooks.beforeCall`
  (real Firebase traffic, ~18 hits/run).
- Re-entrancy guard (unbounded hook→bridge→loader recursion ends in
  `StackOverflowError` without it).
- `FirebaseBridgeLoader` (new): defines extracted bridge classes directly
  into the game loader via `DexFile.loadClassBinaryName`; pre-loads
  linkage failures' supertypes through the game loader; warms all 3119 game
  Firebase dex classes pre-hook at bootstrap. Firebase init now progresses
  App → Auth → Firestore → Database-embedded (baseline died at the first
  bridge class).
- `FirebaseDatabase.getInstance` observer hook (proves the Java layer is
  never reached — failure is upstream in C++ embedded init).
- Rescued `FirebaseOptions` verified complete (appId, apiKey, dbUrl
  `https://the-tower-4f99a-default-rtdb...`, sender, bucket, project).

## 6. Open residual

Game-dex supertypes (e.g. `ChildEventListener`, present in game dex and
pre-defined) still fail resolution inside Firebase-private loader
namespaces: ART linkage/verification there bypasses `loadClass` hooks, and
those namespaces never see game-loader definitions. So Database embedded
init still aborts on the production path while debuggable/testing-branch
runs never enter it.

**Resolved 2026-10-22.** The residual was traced during the LSPlant
migration (ADR-0021), under which the failure became deterministic. The
blocker was not the private namespaces: `FirebaseBridgeLoader.defineJar`
defined the extracted bridge jar's classes in `DexFile.entries()` order,
which is alphabetical, so `CppChildEventListener` was always attempted
before its superclass `CppEventListener`. That first failed define is
sticky in ART — every later retry of the same class (later passes, fresh
DexFiles, other threads) fails with the recorded linkage error, even
after the superclass is defined — so the jar never got past 12 of 16
defines and Database init kept aborting. Under Pine the request pattern
happened to mask it (a second request reached 16). The fix defines jar
classes in topological order (superclasses and interfaces first),
parsed from the jar's own `class_defs`; the database jar now defines
16/16, `FirebaseDatabase.getInstance` succeeds, and repeated boots show
no new tombstones. The upstream report (#1936) stands for the C++ side;
this residual was ours.

## 7. Options

1. **Observe in production use** (recommended next step): the testing branch
   reaches title; if real usage rarely takes the production RTDB path, impact
   is bounded. Collect field logs before further work.
2. **Upstream report to Firebase**: `DeleteInternal` null-deref on init
   failure + slashed names to `loadClass` on Android — both are theirs, with
   tombstones + sources attached.
   Filed 2026-10-14 as
   [firebase/firebase-cpp-sdk#1936](https://github.com/firebase/firebase-cpp-sdk/issues/1936)
   (13.12.0, tombstone + `FindClassInFiles` branch comparison + suggested
   `std::replace` parity fix). When Firebase ships a fix, this becomes a
   game-side SDK-upgrade tracking item.
3. **Deeper ART work** (linker-level visibility for hosted game dex) — long,
   version-fragile; deferred unless (1) shows real impact.
4. Kept-out diagnostics removed; tree builds clean (`assembleRelease`
   green). No device-side or manifest changes pending except the release
   notes above.
