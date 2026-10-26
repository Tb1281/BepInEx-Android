# PairIP isolation harness verification (#57)

This note records the verification of the debug-only isolation harness used for
the CIFI PairIP investigation. The harness lets a launch select compatibility
layers independently and prints one variant line before any layer is installed.

## Cold launch definition

All verification runs used the same definition of a cold launch:

1. `am force-stop` the debug launcher, the artifact launcher, the release
   launcher, and the target game.
2. Confirm `pidof` returns nothing for all four packages.
3. `adb logcat -c`.
4. Start `BootstrapActivity` with the exact intent shown below.
5. Wait a fixed 15 s (debug) or 12 s (artifact) before capturing the buffer.

## Frozen baseline

| Item | Value |
| --- | --- |
| Launcher | `com.bepinex.android.debug` (debug build) |
| Target | `com.OctocubeGamesCompany.CIFI` |
| Extras | none |
| Activity | `com.bepinex.android.BootstrapActivity` |

```bash
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI
```

The variant line appears before the shield and hook-engine layers:

```
I BepInEx : PairIP experiment: variant=default noHooks=false ...
I BepInEx : Installing hook engine hooks (LSPlant)...
```

The baseline crash is the known hosted YTw path:

```
F libc: Fatal signal 11 (SIGSEGV) ... fault addr 0x855d0001509c42b3
DEBUG: #01 pc ... libpairipcore.so
```

## Results

| Run | Build | Variant / extras | Result |
| --- | --- | --- | --- |
| H0 | debug | `variant=default`, no extras | PASS: same YTw SIGSEGV as the pre-harness hosted path; variant line precedes all layers. |
| H1a | debug | `variant=har-no-hooks`, `pairip_no_hooks=true` | FAIL: LSPlant still initialized through Fusion. |
| H1b | debug | same as H1a, after the fix | PASS: no `HookEngine`/`LSPlant` lines; `Fusion skipped with hook engine`; hook-engine and base-hook layers skipped. |
| H2 | artifact | `variant=artifact-inert`, `pairip_no_hooks=true`, plus the temporary non-debug bypass | PASS: extras inert; log reports `variant=default noHooks=false`; LSPlant initializes and the run hits the same YTw SIGSEGV. |

H2 used an artifact build produced after temporarily removing the uncommitted
`pairip_allow_nondebug_experiments` escape hatch. The harness commit does not
contain that hatch, so a non-debuggable build always ignores the extras.

## Defect found and fixed

The `noHooks` variant skipped the hook-engine and base-hook installation but
still called `initializeFusion()`, whose `findLibrary` hook goes through
`HookEngine` and initializes LSPlant. The fix skips Fusion when `noHooks` is
set, so the variant is a genuine no-LSPlant control.

The no-hooks variant does not start the game activity because the launcher
`onCreate` hook is also skipped. That is expected for this layer-isolation
control; use `pairip_bare=true` when a no-layer run that starts the game in its
own process is required.

## Scope note

The tracer, fake central-directory, VM-dump, process-name, proc-sanitizer, and
preload experiment switches are not part of #57. They are debug-gated and are
tracked as a separate work-in-progress checkpoint. That checkpoint also carries
a temporary non-debug bypass used only to reproduce retail (CheckJNI-off)
behaviour; final cleanup must remove the bypass and keep all extras inert in a
non-debuggable build.
