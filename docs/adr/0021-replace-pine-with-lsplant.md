# Replace Pine with LSPlant as the ART hook engine

Status: accepted (2026-10-22). Supersedes ADR-0019.

ADR-0019 kept Pine (Anti 996 v1.0) under an open license review, with the
LSPlant migration named as the documented fallback if the combination with
this project's GPL-3.0 was found incompatible. Two things changed:

1. The review did not close. The combination stays legally undecided, and
   stable promotion stays blocked for as long as Pine ships.
2. The Pine bridge turned out to be a crash source, not only a license
   question. The release-crash investigation (October 2026) attributed a
   class of boot-time SIGSEGVs to Pine's design: intercepted frames are held
   as raw register addresses and converted to references lazily inside the
   bridge (`Pine.getObject` → `AddLocalRef`, no validity check), so a GC
   between capture and conversion leaves ART validating a stale reference
   in `CheckReferenceResult`. Four of 27 analysed tombstones carried that
   exact signature, all in the first seconds of boot under concurrent class
   loading, independent of which mods were loaded. Allocation discipline in
   the callbacks (PlayBillingFix) shrinks the window; it cannot close it,
   because the raw addresses predate the callbacks.

Decision: replace Pine with LSPlant (LGPL-3.0), retaining Dobby (Apache-2.0)
as the inline-hook backend — LSPlant's supported configuration and the same
Dobby the project already pins.

- LSPlant is pinned in `provenance/lsplant.env` and built from source by
  `scripts/build_native_deps.sh`, like Dobby before it. The pin is a master
  snapshot (`6ac2280`), not the v6.4 release: v6.4 predates upstream's
  completed Android 17 support and cannot initialize on the target ART
  (several internal entry points it requires were inlined, renamed, or
  removed there). The snapshot's DexBuilder and parallel_hashmap gitlinks
  are pinned alongside it. One source patch is carried
  (`references/lsplant-android17-missing-internal-hooks.patch`): it makes
  the JIT code-cache collection hook in `JitCodeCache::Init` non-fatal,
  because that ART removed both collection entry points upstream knows.
  Building from source rather than consuming the Maven prefab AAR is what
  keeps every LOAD segment 16KB aligned (`scripts/check_elf_alignment.sh`):
  the published AAR's `liblsplant.so` is 4KB aligned. The build sets
  `ANDROID_STL=c++_static`, matching `libhookengine.so`, so LSPlant's
  `std::function`-based `InitInfo` crosses between two libc++ builds of
  the same ABI; LSPlant master's C++ modules compile against the `cxx`
  package from the hash-pinned `org.lsposed.libcxx:libcxx` AAR recorded
  in the same env file. LSPlant requires CMake ≥ 3.28
  (`cmake_minimum_required` in its CMakeLists) and a Ninja ≥ 1.11 when
  the Ninja generator is used; the workflows that run
  `build_native_deps.sh` install CMake 3.30.3.
- The Java side is a small facade, `com.bepinex.android.hooks`
  (`HookEngine`/`MethodHook`/`CallFrame`), over a JNI shim
  (`libhookengine.so`). The facade mirrors the Pine/Xposed semantics the
  hook sites were written against — result assignment in `beforeCall` skips
  the original, result assignment clears a pending throwable,
  `invokeOriginalMethod` invokes LSPlant's backup on an explicit receiver —
  so the eight hook sites changed only in imports and call targets, plus
  the removal of Pine's object-unwrapping in ClassLoaderHooks (under
  LSPlant every value a callback sees is already a real reference).
  Exceptions thrown by `beforeCall`/`afterCall` callbacks are caught and
  logged by the facade, and the original call (or its result) proceeds —
  the same contract Pine's bridge provided; a throwing callback must
  never abort a boot. The shim resolves libart symbols with its own ELF
  parser over the `.gnu_debugdata` section of the on-disk `libart.so` —
  an xz-compressed mini-ELF (decoded by a vendored xz) that is the only
  table on the target device carrying the full internal symbol set
  (18,579 symbols merged); the stripped library's `.symtab`/`.dynsym`
  do not. No additional dependency (lsparself) is vendored.
- Pine's pins, patches and provenance (`provenance/pine.env`,
  `references/pine-*.patch`, the Maven `pine-core` dependency, the staged
  `libpine.so`) are removed.

Consequences:

- The license review that blocked stable promotion is closed: no Anti-996
  component ships. Relicensing the launcher itself is unaffected and still
  requires the FusionCore copyright audit ADR-0019 described.
- The raw-address/GC crash class is removed structurally, not mitigated:
  LSPlant callbacks are ordinary Java methods holding real references.
- Behaviour of every hook site is preserved by construction of the facade
  and was verified on device (Galaxy S25, Android 17). An early
  verification round was invalidated when the test APK's `.debug`
  application-id suffix was found to have routed installs to a separate
  package, leaving the device booting the old Pine build; the claims
  below rest only on the re-verification that followed, with install
  timestamps checked per boot. LSPlant init and the hook sites were
  exercised in live boots of two games: Revolution Idle booted clean
  repeatedly (plugins loaded, chainloader complete), and The Tower
  booted clean repeatedly after the Firebase fix in the next bullet —
  loadClass cross-loader fallback (without which the hosted game cannot
  even instantiate its activities), JNI-name normalization, findLibrary
  redirection, and Firebase `getInstance` all observed working, with no
  new tombstones across the boot set. `:app:testDebugUnitTest` passes.
- The migration exposed the Firebase RTDB boot crash documented in
  `docs/release-crash-investigation.md` as deterministic rather than
  timing-variant. Its root cause was in the launcher, not the hook
  engine: `FirebaseBridgeLoader` defined the extracted bridge jar's
  classes in alphabetical order, attempting `CppChildEventListener`
  before its superclass, and ART's recorded linkage failure then
  poisoned every retry of that class (see §6 of the investigation,
  marked resolved 2026-10-22). The bridge now defines jar classes in
  topological order. The bug was latent under Pine — Pine's request
  pattern happened to reach a full define on a second request — so this
  fix is owed to the migration's scrutiny, not to LSPlant itself.
- One Pine side effect disappears with it: Pine's AAR consumer keep rule
  (matching every class with native methods) no longer neuters R8
  shrinking. The facade carries one narrow keep rule for its own
  reflection-invoked entry points instead.
- LSPlant is an ART-internals library: new Android versions can need new
  upstream fixes. The pin is a single env file, and upstream's Android 17
  support is stated and was exercised here; the mitigation for future
  versions is the same pin-bump path the other native deps use.
