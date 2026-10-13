# Project Milestones & Architecture Decisions

## 1. Architectural Decision Records (ADRs)

### ADR 1: Official Microsoft .NET Runtime Pack vs. Source Cross-Compilation

- Context: cross-compiling CoreCLR from source took hours, needed host packages, and risked mixing runtime-identifier assemblies.
- Decision: consume the official `Microsoft.NETCore.App.Runtime.android-arm64` NuGet package.
- Outcome: deterministic sub-two-minute fetch with guaranteed native/managed consistency.

### ADR 2: .NET 10 LTS over .NET 11

- Context: .NET 11 previews threw at runtime under MonoMod.Core version gating.
- Decision: pin the runtime to .NET 10 LTS.
- Outcome: full MonoMod/Harmony compatibility with long-term support.

### ADR 3: Pure Java JNI Crypto PAL (No OpenSSL/BoringSSL)

- Context: bundling OpenSSL collides with system BoringSSL symbols on Android.
- Decision: use the official Android Crypto PAL delegating to Conscrypt/JNI providers.
- Outcome: zero native OpenSSL deps; compatible across OS versions.

### ADR 4: In-Process Hooking via `libfusion.so`

- Context: desktop launcher binaries do not fit Android zygote/ART activity lifecycles.
- Decision: intercept `il2cpp_init` with `libmain`/`libfusion` and host CoreCLR in-process.
- Outcome: seamless integration with standard Unity Android startup.

### ADR 5: Safe Dynamic Export Thunk Resolution

- Context: AArch64 linkers export functions via short jump stubs; hooking a stub corrupts neighbours.
- Decision: `ExportThunkResolver` follows branch displacement and validates targets via process maps before hooking.
- Outcome: no stub corruption or pointer-authentication faults.

### ADR 6: Process Hosting Identity Shims

- Context: hosting the game in an auxiliary launcher process breaks Firebase discovery and billing identity lookups.
- Decision: add caller-whitelisted storage redirection, a Firebase manifest-registrar fix, and a Pine-based billing fix.
- Outcome: full playability on modern Unity 6000 titles without backend exceptions.

### ADR 7: Universal Thunk Resolution in `NativeDetour`

- Context: interop-generated hooks passed raw stub addresses into the hook engine, overwriting adjacent stubs.
- Decision: route every `NativeDetour` target through thunk resolution.
- Outcome: all inline hooks land on true function bodies.

### ADR 8: AArch64 Inline Hook Space Guard

- Context: AArch64 hooks write 16 bytes; targets near a `RET` would stomp adjacent code.
- Decision: refuse targets with a `RET` inside the first 16 bytes; skip cleanly via exception.
- Outcome: eliminated native memory corruption during hook install.

### ADR 9: Android Native Logcat Decoupling

- Context: listening to Unity logs duplicated Logcat traffic and forced early interop init.
- Decision: default `UnityLogListening` to false in config and chainloader.
- Outcome: single log path; no premature hook registration on vanilla launches.

### ADR 10: Il2CppInterop Hook Validation

- Context: one xref resolver followed a tail-call branch into an epilogue on Unity 2022.3 ARM64.
- Decision: reject null, misaligned, or too-short hook candidates; return null instead.
- Outcome: matches Unity 6 fallback behaviour; safe skip without bad pointers.

### ADR 11: MetadataCache Hook Exclusion on Android

- Context: one upstream hook forwarded a 64-bit handle as 32-bit int on Metadata v29+, faulting the runtime.
- Decision: omit that single hook from setup on Android, matching the legacy fork.
- Outcome: custom MonoBehaviour registration works without crashes.

### ADR 12: BootstrapActivity Configuration Changes

- Context: rotation during bootstrap recreated the activity, which misread launch state and killed the process.
- Decision: handle config changes in-manifest, ignore recreated state, set launch flag only at real launch.
- Outcome: no premature exits or launch blocks on startup.

### ADR 13: Release Minification and the Non-Debuggable Blocker It Exposed

- Context: the app had only shipped debuggable builds; first release build could not start any game (PAL init abort).
- Decision: keep minify/shrink on (proven innocent by minify-off release still failing) and fix the `debuggable` blocker underneath.
- Outcome: release roughly half the debug size; blocker root-caused to class-loader visibility, fixed per ADR 15.

### ADR 14: `proguardFiles` on Every Build Type That Can Minify

- Context: keep rules were declared on release only, so any minified debug build ran without them.
- Decision: declare the same `proguardFiles` on debug.
- Outcome: PAL keep rules enforced wherever minification can run.

### ADR 15: JNI FindClass Fallback for the Native-Hosted Crypto PAL

- Context: the PAL arrived via native load with no class loader, so its class lookup fell back to boot and failed; preloading from Java instead created duplicate linker-namespace instances.
- Decision: capture the app class loader once and fall back to it when native lookup misses, in a single instance.
- Outcome: PAL resolves on any thread; release boots on all verified titles.

---

## 2. Hardware Verification Matrix

Verified on physical hardware, stock firmware:

| Device class | Android | Unity | Game | Verdict |
|---|---|---|---|---|
| Snapdragon 8 Gen 3, arm64 | Android 15+ | Unity 6000 | The Tower | Pass — chainloader complete, modded play; crash signatures zero |
| Snapdragon 8 Gen 3, arm64 | Android 15+ | Unity 6000 | Unlock! | Pass — chainloader complete; crash signatures zero |
| Snapdragon 8 Gen 2, arm64 | Android 15+ | Unity 2022.3 | Revolution Idle | Pass — chainloader complete, plugins loaded; crash signatures zero |

Post-boot game-SDK native crashes (Firebase database, Pine binder path) are pre-existing, timing-variant, and tracked separately; A/B runs exonerated the shipped managed bytes.
