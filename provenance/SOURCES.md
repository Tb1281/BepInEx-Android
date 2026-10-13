# Artifact provenance

Every binary that ships inside the APK is listed here with the remote source it is
built from and the CI job that produces it. A binary that cannot be named here
does not belong in the tree.

Checksums live in `sha256sums.txt`, generated from the same CI runs and enforced
by `launcher/scripts/verify_prebuilts.sh`.

**The binaries in this tree are not what ships.** `build-launcher-apk` overwrites
all eleven `.so` files and both archives from CI artifacts before Gradle runs, so
the committed blobs are a cache and a rebuild baseline, never a packaging input.
Their role is reproducibility: when a pin moves, a clean rebuild has to reproduce
the committed bytes, and without a recorded baseline "the pin changed" and "the
artifact changed" cannot be told apart. `docs/ci-cd.md` § *Why binaries are
committed* sets out the full boundary, including the one case the manifest does
**not** catch — a commit that replaces a binary and its manifest entry together,
which is a property of push access rather than of committing binaries.

## What the checksum manifest proves, and what it does not

Worth being precise about, because "reproducible" is easy to overstate.

**It proves integrity.** Every file that ships matches the hash recorded when CI
built it. A substituted or corrupted binary fails `verify_prebuilts.sh`. This is
checked on every push and blocks the Gradle build.

**The CI shipping path is a separate manifest, and it was unverified until
2026-10-08.** `build-launcher-apk` does not build from the committed bytes. It
downloads three artifacts from three separate runs and copies them in, so
`sha256sums.txt` cannot be its comparison — the managed assemblies legitimately
differ, because they embed the commit they were built from. Each producer job did
record a hash beside its own output and shipped that file inside its artifact, and
the consumer downloaded all three without reading any of them. The workflow said so:

> The committed manifest describes the committed artifacts. CI-produced artifacts
> legitimately differ, so the checksum attestation for this build is recorded below
> instead.

Recording is not checking; a hash nothing is compared against cannot fail.
`scripts/verify_ci_artifacts.sh` is now the comparison, and it runs before Gradle.
It also checks the reverse direction — that a producer did not ship something its
manifest does not name — and that every `.so` in `jniLibs` is accounted for by some
producer job, which is the shape a substituted library takes. `libc++_shared.so` is
the one declared exception, because it comes from the NDK and has no producing job;
it is named rather than passed over, since "not mentioned" and "verified" are
different states.

Two bounds on that gate, both of which follow from something already recorded
below: it covers the **inputs to packaging**, not the bytes inside the APK, because
AGP strips native libraries during packaging; and it binds a file to the run that
produced it, which is not a statement about who ran it. `build-launcher-apk`
separately checks that all three runs were built from the current commit, and the
two checks compose rather than replace each other.

**The second bound was closed on 2026-10-11.** Each producer now attests its own
output with `actions/attest-build-provenance` before uploading, and
`scripts/verify_attestation.sh` — run by `build-launcher-apk` before Gradle — asks
GitHub whether each installed artifact carries a statement binding it to this
repository, this workflow and this commit. Anyone with write access can upload an
artifact alongside a matching `.sha256`; they cannot produce a GitHub-signed
statement about a commit they did not build. The two gates compose: the hash gate
is byte identity, the attestation gate is origin. Neither replaces the other, and
neither covers the bytes inside the packaged APK. Full detail and the known limits
are in `docs/ci-cd.md` under "Attestation".

**It does not prove bit-for-bit reproducibility, and for the managed assemblies
that is not achievable as the build is currently wired.** Two runs of
`scripts/apply_patches.sh` over identical patches produce different commits in the
scratch clone — `git am` mints a commit whose timestamp is the moment of the run —
and that commit SHA is compiled into the output:

```
run 1 → tip cef62cd → BepInEx.Core.dll contains "6.0.0+cef62cd…"
run 2 → tip 7562ce3 → BepInEx.Core.dll contains "6.0.0+7562ce3…"
```

The .NET SDK appends `SourceRevisionId` to `AssemblyInformationalVersion` once
`RepositoryUrl` is set, which `Directory.Build.props` does. The embedded PDB blob
carries a GUID derived from the same input, which accounts for the rest of the
difference (~19 KB of a ~115 KB assembly). The 83 bytes before it are the version
string itself.

This was checked rather than assumed: two builds were decompiled to IL and label
names normalised, and the IL is byte-identical. The difference is build metadata
only, with no behavioural effect. In a ~115 KB assembly, 19,898 bytes differ.

So the guarantee is: **given the recorded hash, these are the exact artifacts that
were built and tested on device.** It is not: *anyone can regenerate these exact
bytes*. Anyone *can* regenerate equivalent behaviour from the pinned sources plus
the patch queue; to confirm that, compare decompiled IL rather than hashes.

That comparison was actually run when MonoMod moved from the fork's `*-star`
builds to packages rebuilt here. Decompiled IL of `MonoMod.Core` and
`MonoMod.RuntimeDetour` is identical to the device-tested assemblies once the
version string, CRLF differences and static field offsets are normalised;
`MonoMod.Utils` differs only in its version string. All three have identical file
sizes and identical shipped type sets. The rebuilt archive was then installed
and the game reached its lobby with a Harmony plugin patching `Main.Update`.

**So do not use the manifest as a reproducibility claim, and do not update it as
bookkeeping.** `--update-manifest` retires a hash that recorded what was tested,
which is the one thing the file is for. It is the correct response to a
deliberate rebuild and the wrong response to a routine one — and note that it
*blocks* the build rather than merely annotating it, because `preparePrebuilts`
runs `verify_prebuilts.sh` before every Gradle build.

Making it bit-reproducible would mean stopping `apply_patches.sh` from creating
commits and pinning `SourceRevisionId` to the upstream commit, at the cost of the
per-patch commit history that keeps upstream syncs reviewable. That trade has not
been made here, so this is stated rather than fixed.

### What a patch conflict costs, and who finds out

Upstream moves. When it does, the queue is replayed by `sync-upstream` on a
6-hour schedule, and some patches stop applying. That is a normal outcome, not a
defect to engineer away — but the *reporting* is where a silent failure would
live, so it is worth stating precisely.

**Pinning the upstream commit and opening a PR when it moves are not in tension.**
The pin is a statement about the present — "this commit was built against that
commit" — not a wall. Its value changes later, and the only path by which it
changes is a merge. `sync_upstream.sh` stages the new pin with `git add` and
nothing commits it; the commit happens when the PR is merged, and the PR step is
only reached if the replay and the build both succeeded. So a conflict means no PR,
which means the pin stays where it was. **The absence of a PR is itself the
evidence that the last known-good state is intact.**

Why the pin is needed at all, given that: it is what makes "this commit, that
upstream" checkable, and it is what turns an upstream history rewrite into a loud
failure instead of a silent substitution. On 2026-10-06 BepInEx upstream force-pushed
a commit away, `main`'s pin `c5b3087` stopped being fetchable, and every job that
checks out submodules failed for three days. Nothing silently built against a
different upstream — which is the property that mattered. The *manner* of that
failure, `git` exit 128 during checkout, was a consequence of the pin being stored
as a submodule gitlink rather than as text. **That is now fixed**: the pin is
`UPSTREAM_COMMIT` in `provenance/bepinex.env`, read as text, and the clone is
asserted against it (`apply_patches.sh`) so a wrong commit fails loudly rather
than being fetched silently. The 2026-10-06 property is preserved and now holds by
construction instead of by git's exit code.

What `git am --3way` actually survives, measured against upstream `5b766a3` with
a synthetic commit each time:

| upstream change | result |
|---|---|
| edits elsewhere in a file the port patches | applies cleanly |
| deletes a block the port also deletes | applies cleanly |
| deletes a member the port deletes | applies cleanly |
| **changes a line the port rewrites** | **conflict** |

So the queue tolerates upstream moving underneath it, and breaks only where both
sides had something to say about the same line. Resolving that is a human job:
regenerate that one patch. `apply_patches.sh` refuses to skip a patch, because a
skip ships an archive missing that adaptation and nothing notices until the
device behaves differently.

`scripts/report_patch_conflict.sh` runs on failure and prints the patch, its
position in the queue, the conflicted file, the conflict markers (upstream's line
against the port's), and the upstream diff — into `GITHUB_STEP_SUMMARY` on a
runner, so a run that fails every six hours is readable without opening the log.

Two things this does **not** do, stated so nobody assumes otherwise:

- **It does not notify anyone.** A failing scheduled job is visible to whoever
  watches CI. There is no issue, mail or annotation.
- **It cannot fire right now.** The pin is at upstream `origin/master`
  (`5b766a3`, "Follow PE export jmp thunks before applying a native detour
  (#1387)"), zero commits behind. The sync job will find nothing to do, so the
  reporting path above is unexercised against a real upstream move; it was
  verified against a synthetic commit instead. Treat it as tested-on-a-fixture
  until a genuine upstream release lands.

`libdobby.so` and `libpine.so` were rebuilt from source on this machine and
re-pinned through the manifest, so they are demonstrably not carried over from a
third-party release. Whether they are stable across repeated builds is not
verified here.

## Managed framework — `BepInEx.Android.zip`

| Component | Source | Built by |
|---|---|---|
| `core/*.dll` (BepInEx.Core, BepInEx.Preloader.Core, BepInEx.Unity.*, HarmonyX, MonoMod.*, AsmResolver.*, Cpp2IL) | `provenance/bepinex.env` pin → [BepInEx/BepInEx](https://github.com/BepInEx/BepInEx); `Il2CppInterop.*` see § below | `build-bepinex` |
| Android adaptation | `patches/*.patch` in this repository | — |
| `config/BepInEx.cfg` | `launcher/build_assets/BepInEx/config/` | — |

`apply_patches.sh` clones the pinned commit into `build/bepinex` (gitignored),
asserts the clone landed on that commit, then replays the queue there. The build
reads that scratch tree; nothing is checked out into the repository.

The NuGet graph is pinned by the csproj files inside the patch queue, so the
managed output's *behaviour* is determined by the pinned commit plus the queue.
The bytes additionally carry the build metadata described above.

## Il2CppInterop packages — `Il2CppInterop.{Runtime,Common,HarmonySupport,Generator}`

Android games require an Il2CppInterop fork (ARM64 / `libil2cpp.so` loader,
Disarm-driven xref scan, non-fatal hook misses). The fork's sources were lost;
its NuGet feed is no longer available. We rebuild from a pinned official upstream
commit plus a reviewed patch. The distinct `-android.3` version and exact NuGet
references prevent NuGet from silently loading an unrelated cached 1.5.2 DLL:

**The published 1.5.3 cannot be used unmodified, and it is not an architecture
problem.** Upstream `Il2CppInterop.Runtime/Injection/InjectorHelpers.cs` hardcodes

```csharp
internal static IntPtr Il2CppHandle = NativeLibrary.Load("GameAssembly", ...);
```

and matches `Modules.Single(x => x.ModuleName is "GameAssembly.dll" or ... or
"UserAssembly.dll")`. An Android IL2CPP game ships `libil2cpp.so`, so the load
fails and `Single()` throws during type initialisation — observed on device as a
fatal in `InjectorHelpers..cctor()`. The xref scanners additionally use Iced, an
x86 disassembler, which cannot decode arm64. The patch changes the loader and
module match to `libil2cpp.so` and replaces Iced with Disarm. This is why
upstream's package is a base rather than a dependency, and why a direct project
reference to upstream would fail at runtime in the same way.

**The content is 1.5.3, not 1.5.2.** The pin `81a6f78` is `v1.5.3-2-g81a6f78`:
upstream 1.5.3 plus two official commits, both Unity 6 fixes (duplicate types
on 6.4+, access violation scanning Unity 6 modules with protected pages). The
`1.5.2-android.3` version string is not a description of the source; it exists
only because NuGet's global cache is keyed by id+version and a fork build
published as "1.5.2" once shadowed the real one — which made the device fatal
above. Read the version as "our Android build of 1.5.3".

| Package id | Pin of content | Built by |
|---|---|---|
| `Il2CppInterop.Runtime` 1.5.2-android.3 | upstream `BepInEx/Il2CppInterop` @ `81a6f78c8b653e0da4a3420ac4cd00819e8b6292` + `references/il2cppinterop-arm64-disarm.patch` | `scripts/build_il2cppinterop.sh` |
| `Il2CppInterop.Common` 1.5.2-android.3 | same | same |
| `Il2CppInterop.HarmonySupport` 1.5.2-android.3 | same | same |
| `Il2CppInterop.Generator` 1.5.2-android.3 | same | same |

`provenance/il2cppinterop.env` is the machine-readable pin. The resulting
`.nupkg`s go into `local-nuget/`, used by the `nuget.config` inside the cloned
BepInEx tree (`build/bepinex/nuget.config`).
`package_bepinex_android.sh` compares every packaged Il2CppInterop DLL with
its local `.nupkg`, catching stale global NuGet caches before the APK build.
The compiler is pinned as well: upstream's `global.json` rolls to the newest
installed major, so the script builds under the root pin (10.0.401) and
restores the file on exit — local and CI compilers agree, verified by a
single-package on-device boot (startup complete, 29,197 methods, zero
signatures, alive past 12 min).

## MonoMod packages — `MonoMod.{Core,Utils,RuntimeDetour}`

MonoMod is not an optional dependency on this project. Official MonoMod's
`PlatformTriple.CreateCurrentSystem` is:

```csharp
OSKind.Android => throw new NotImplementedException(),
```

So official MonoMod cannot patch anything on an Android device. The Android
support is 8 published commits in the public `XtraCube/MonoMod` fork, on top of
official `MonoMod/MonoMod` commit `20ccecf` — which is itself an upstream commit
(`v22.03.23.04-1491-g20ccecf`). We therefore clone the **official** repository
and carry the delta as `references/monomod-android.patch`; the fork is never
cloned or built, only compared against.

`scripts/build_monomod.sh` asserts on every build that the patched tree still
reproduces the fork's `src/` at `9da670d6e`. Patch drift fails the build instead
of changing behaviour quietly. The resulting `.nupkg`s go into `local-nuget/`,
and `package_bepinex_android.sh` compares each packaged DLL against the one
BepInEx compiled against, on the `net10.0` asset specifically — all three are
multi-targeted upstream, so a same-name comparison would prove nothing.

| Package id | Pin of content | Built by |
|---|---|---|
| `MonoMod.Core` 25.3.4-android.1 | official `MonoMod/MonoMod` @ `20ccecf62438010f9d4ef1f8090ff09c9aca0ca9` + `references/monomod-android.patch` | `scripts/build_monomod.sh` |
| `MonoMod.Utils` 25.3.4-android.1 | same | same |
| `MonoMod.RuntimeDetour` 25.3.4-android.1 | same | same |

`provenance/monomod.env` is the machine-readable pin. As with Il2CppInterop, the
version string exists to keep NuGet's global cache from substituting the official
release — and here that substitution is silent and load-bearing: `-star` pins
resolved nowhere, so NuGet reported NU1603 and carried on with official MonoMod.
The build looked green while being unable to patch anything on device.
CI now fails on NU1602/NU1603 for that reason, and both build jobs rebuild
the packages before restoring - without that, the feed is empty and the
substitution happens with nothing but a warning to show for it.

Two upstream details this build has to handle, both recorded in
`provenance/monomod.env`: `external/iced` is a git submodule imported as a
project (without it the build fails MSB4019 on a missing `Iced.csproj`), and
upstream's `global.json` pins SDK `10.0.100` with `rollForward=latestPatch`,
which cannot cross a feature band — a machine with only 10.0.401 fails before
compiling anything. The pin is overridden deliberately and restored afterwards.

## Native runtime — `launcher/app/src/main/jniLibs/arm64-v8a/`

All of these come out of one package:
[`Microsoft.NETCore.App.Runtime.android-arm64`](https://www.nuget.org/packages/Microsoft.NETCore.App.Runtime.android-arm64),
version **10.0.12**, an official Microsoft release on nuget.org. It is
not built here; `scripts/fetch_dotnet_runtime.sh` downloads it and checks it against
the SHA-256 in `provenance/dotnet-runtime.env` before unpacking anything.

This was .NET 11 (`11.0.0-rc.1.26425.128`) until stage 10 of `docs/progress.md`.
The pack id has published a GA build for the whole .NET 10 line — 10.0.0 through
10.0.12 — so taking the runtime from the package rather than from a local
cross-build was available on both versions. .NET 10 was chosen because
`MonoMod.Core` dispatches on an explicit `switch (version.Major)` that stops at
10 and throws `PlatformNotSupportedException` by default, and the IL2CPP-capable
`-star` MonoMod fork this project needs has no published .NET 11 build. The
BCL/native-engine coincidence property below is unaffected: both versions come
from one immutable package.

| Binary | Source | Produced by |
|---|---|---|
| `libcoreclr.so`, `libclrjit.so` | `runtimes/android-arm64/native/` of the pinned pack | `fetch_dotnet_runtime.sh` |
| `libSystem.Native.so`, `libSystem.IO.Compression.Native.so`, `libSystem.Globalization.Native.so`, `libSystem.Security.Cryptography.Native.Android.so` | `runtimes/android-arm64/native/` of the pinned pack | `fetch_dotnet_runtime.sh` |
| `libmscordaccore.so`, `libmscordbi.so` | `runtimes/android-arm64/native/` of the pinned pack | `fetch_dotnet_runtime.sh` |
| `libc++_shared.so` | Android NDK 30.0.16248370 (pinned) | `build-launcher-apk` |
| `libdobby.so` | `provenance/dobby.env` pin → [canyie/dobby](https://github.com/canyie/dobby) | `build-native-deps` |
| `libpine.so` | `provenance/pine.env` pin → [canyie/Pine](https://github.com/canyie/Pine) + nested `xz-embedded` pin | `build-native-deps` |
| `libmain.so`, `libfusion.so` | `launcher/app/src/main/cpp/` (this repository, compiled by Gradle/CMake) | `build-launcher-apk` |

`libSystem.IO.Ports.Native.so` was in this list while the runtime was cross-built
here and is gone now: the pack does not contain it, and nothing in the BCL
references `System.IO.Ports.Native`. A binary that cannot be traced to a pinned
input does not stay in the tree.

## Runtime pack — `dotnet.zip`

| Component | Source | Produced by |
|---|---|---|
| `*.dll` (framework / BCL) | `runtimes/android-arm64/lib/net10.0/` of the pinned pack | `fetch_dotnet_runtime.sh` |

The pack and `libcoreclr.so` are the same package, which is stronger than the
requirement this file used to state. It used to be "both come from one build of
`dotnet/runtime`", enforced by the discipline of one CI job — and that discipline
failed: `artifacts/bin` was swept too widely, the pack ended up holding another
RID's BCL, and the mismatch only surfaced on device as a
`DllNotFoundException` for `libSystem.Security.Cryptography.Native.OpenSsl`
during Cpp2IL interop generation. A managed assembly from a different runtime
build than the native engine is a load-time failure no build step would catch,
so the two are now taken from one immutable, hash-pinned artifact and cannot
drift apart.

## Crypto backend

CoreCLR on Android binds `System.Security.Cryptography.Native` to
`libSystem.Security.Cryptography.Native.Android.so`, which exports
`AndroidCryptoNative_*` and reaches the platform crypto through `liblog`
(BoringSSL). The runtime package contains no OpenSSL PAL, and this repository
ships none: `libcrypto.so`, `libssl.so` and
`libSystem.Security.Cryptography.Native.OpenSsl.so` (which exports `CryptoNative_*`
and links `libcrypto`/`libssl`) are rejected by
`launcher/scripts/verify_crypto_pal.sh`, and `fetch-runtime` asserts the same
condition on its output.

There is no managed PAL shim assembly, and asserting one exists was a mistake.
.NET ships none: `System.Security.Cryptography` P/Invokes `AndroidCryptoNative_*`
directly, and no `System.Security.Cryptography.Native.*.dll` exists in the host's
own linux-x64 shared framework for 10.0.12 or 11.0.0-rc.1 either. The binding is
therefore asserted twice instead — on the managed side by counting
`AndroidCryptoNative_` P/Invoke targets, on the native side by counting
`AndroidCryptoNative_*` exports in the `.so`. A pack that carries a managed shim
is a desktop-RID pack and is rejected.

Measured on the shipped .NET 10.0.12 pack: 207 `AndroidCryptoNative_` references
in `System.Security.Cryptography.dll` and 0 `Native.OpenSsl` references; 126
`AndroidCryptoNative_*` exports in the PAL. `verify_dotnet_pack.sh` reports these
as 99 and 126 — its managed-side count is a `grep -c` line count, not an
occurrence count, so read it as a presence signal rather than a measurement.

## What is deliberately absent

- **No binary from a third-party repackager or an unofficial mirror.** The
  runtime is Microsoft's own published package from nuget.org, pinned by
  version *and* by SHA-256; the hook engines are built from upstream source
  cloned at pinned commits. There is no default release URL for anything else;
  `launcher/scripts/download_prebuilts.sh` requires `RELEASE_BASE_URL` to point
  at your own CI output.
- **No upstream fork.** The runtime is the official package as published.
- **No desktop distribution.** Doorstop, the Mono runtime, the .NET Framework /
  .NET CoreCLR desktop hosts, and the Cake packaging pipeline are removed by
  `patches/0008`. Removing the Cake project also removes its habit of downloading
  Doorstop, a prebuilt Dobby and a mini-coreclr zip from BepInEx release servers.