# Handoff: CIFI PairIP router falsification (2026-10-27)

Point-in-time snapshot for continuing the CIFI
(`com.OctocubeGamesCompany.CIFI`) hosted-launch investigation on another
machine. The crash is still unresolved. This file records only the current
state and what changed since the previous snapshot; it does not restate the
mission, the acceptance criteria, the ruled-out list, or the next move.

The living record is
[`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md). The append-only
run log is
[`docs/cifi-pairip-walkthrough.md`](../cifi-pairip-walkthrough.md). The
earlier snapshot,
[`docs/handoffs/2026-10-27-cifi-pairip.md`](2026-10-27-cifi-pairip.md), is
read-only and is not updated by this file.

## Binding rules

`docs/cifi-pairip-agent-rules.md` is on `origin/docs/cifi-agent-rules` at
`8eb4bb9`, not on `test/feat-cifi`. Its own text says merging it is the
maintainer's decision, so it was not merged. Read it before acting:

```bash
git show origin/docs/cifi-agent-rules:docs/cifi-pairip-agent-rules.md
```

It defines the session-start protocol, the idempotency rules, the walkthrough
format, and the hard constraints. Follow it over this snapshot if they
conflict.

## Repo state (verified 2026-10-27 ~17:05 JST)

- Branch: `test/feat-cifi`
- Tip: `66ad889` (`docs(pairip): correct the router comparison width in the
  pre-registration`)
- Working tree: clean; no stash
- Upstream: `origin/test/feat-cifi` at `3b94d1d`
- Local is **15 commits ahead**; nothing was pushed
- `git pull --ff-only`: `Already up to date.`
- New remote branch: `origin/docs/cifi-agent-rules` at `8eb4bb9`; not merged
- Debug-only probe code is committed; it is not merged to `main`

The debug APK is not tracked. Rebuild from `launcher/`:

```bash
cd launcher
./gradlew :app:assembleDebug --console=plain
```

The APK lands at `launcher/app/build/outputs/apk/debug/app-debug.apk`.

## Device and installed state (verified)

- Device: SM-S931N, Android 17 / API 37, `user` build, `ro.debuggable=0`,
  arm64-v8a. Connect with the same `adb` serial the other machine uses; no
  root.
- Debug launcher `com.bepinex.android.debug`: installed APK sha256
  `8694a819709f9797538d78c20ce5dbd710c6d0589ae8562a6ff22b9bfabe1df2`,
  115,103,986 bytes, `lastUpdateTime=2026-10-27 16:58:18`. This is the
  `state-router-retry` build from code commit `d20fa9e`.
- Game `com.OctocubeGamesCompany.CIFI`: version `0.7.3.64`, untouched. Base
  APK sha256 `390fbd7ff3823c69bc3c4ff2f191f6fa787d0e8b33539a4c189c4cecfab9dc5f`
  (122,714,162 bytes); arm64 split sha256
  `6b9a27886f5c2b7257fcb3ee8d59e27df788a6b097f35d787e0af4bfe037dc02`
  (107,115,296 bytes).
- `klB` asset sha256
  `112b6e592c18c9877b90b8f0df2c2d4dd037bf0742bacbd914a03eed242011fa`
  (282,603 bytes) at `tmp/cifi-apk/vm-assets/klB1wZSojiVvDvCN`.
- The installed debug APK hash matches the built APK. Check the installed
  hash before installing again; do not reinstall identical bits.

## What changed in this session

The state-router hypothesis was tested and falsified.

1. `hash-gate` was already falsified: hooks at `+0x3dc0c`/`+0x3dc50`
   installed `rc=0` but recorded zero hits; the `+0x3dc08` block is a
   different handler's tail. Recorded in `a119a0b`.
2. `state-router` attempt 1 (`71072b5`, run key in `a18ab36`) was
   **INCONCLUSIVE**: hooking `+0x32c2c` and `+0x32c48` crashed in
   `malloc(...)` / `split_config.arm64_v8a.apk+0x4c4` before any router
   callback.
3. `state-router-retry` (`d20fa9e`, run key in `f359e24`) hooked only
   `+0x32c20`, tailed through `x9`, and computed the table lookup,
   comparison, and selector offline. It is **FALSIFIED**: the hook installed
   `rc=0`, but `router ring pos=0 eq=0 ne=0`; the run crashed in the frozen
   baseline `__memmove_aarch64_nt+140` with the same bad reader slots.
   Verdict in `5735af9`; the `cmp x8, x22` width correction is `66ad889`.

Key evidence from the retry:

- Run key: `variant=state-router-retry`, code `d20fa9e`, APK
  `8694a819…1df2`, window 2026-10-27 16:59:07–16:59:35 JST.
- Exact command and toggles:
  [`docs/cifi-pairip-walkthrough.md`](../cifi-pairip-walkthrough.md),
  section `2026-10-27 16:59 — state-router-retry`.
- Log: `tmp/cifi-apk/logs/verify/state-router-retry.log`
  (1,284,213 bytes, sha256
  `982682e413235d564ef9c2197e532de4ab80c1048ce422390e99340ab1df47c7`).
- Summary: `tmp/cifi-apk/logs/verify/state-router-retry.summary.txt`
  (sha256
  `258b89bafeba0bcf2b6a2ae65519582588d03fa0321c0a05bf7126c607108bd1`).
- State ring ends at `id=0x872a21c sel=0x31bb`; the builtin ring shows
  `id=0x9a0c0031 pc=0x40a73` immediately before the crash.
- Static route: the dispatcher compares `w8` with `0x872a21c` at
  `+0x34d68..+0x34d74` and takes `b.eq 0x3dc88`, directly to the reader
  builder. The `+0x32c20` block is reached from `+0x3d760`/`+0x3d798` in a
  different handler tail and is not on the reader path.

What this proves: the `+0x32c20` router hash is not the hosted gate for the
reader path, and the retry probe did not perturb the baseline crash. What it
does not prove: why the five image globals or the reader slots differ between
hosted and bare launch.

## Where the live state is

Do not copy the open questions or the next move into this snapshot. They are
maintained in:

- [`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md), sections
  `Open questions`, `Next steps`, and `Retry result`.
- [`docs/cifi-pairip-walkthrough.md`](../cifi-pairip-walkthrough.md), the
  append-only verdicts.

Before any new run, search the walkthrough for the run key. The following
runs already have verdicts and must not be repeated:

- `hash-gate` — falsified.
- `state-router` attempt 1 — invalid (probe perturbation).
- `state-router-retry` — falsified.

Check the installed APK hash before installing. `git pull` alone does not
bring the debug APK; rebuild it.

## Artifacts on this machine

`tmp/` is gitignored and is not transferred by `git pull`. Copy it manually
or regenerate it. The useful local files:

- `tmp/cifi-apk/analysis/libpairipcore.asm` — static disassembly
  (`+0x32c20..+0x32c54`, `+0x34d68..+0x34d74`, `+0x3dc88..+0x3e030`).
- `tmp/cifi-apk/base.apk` — base APK copy (hash above).
- `tmp/cifi-apk/split_config.arm64_v8a.apk` — arm64 split (hash above).
- `tmp/cifi-apk/vm-assets/klB1wZSojiVvDvCN` — pristine `klB` asset.
- `tmp/cifi-apk/vmrun/klB-image-at-crash.bin` — crash image copy from an
  earlier run (282,603 bytes); its bytes differ from the current run by ASLR
  pointers. The current run's VM dump is in the retry log.
- `tmp/cifi-apk/run_variant.sh` — cold-launch helper. It force-stops the
  launcher, artifact/release packages, and the game, then captures 25 s.

The previous session's host-local fake-CD Frida trace
(`/tmp/opencode/trace_fakecd_split.raw`) and fake-CD class-trace log are
absent. The prior handoff's claim about `vm=0xf0df/0x30`, `idx=0x19ded`,
`len=12928` therefore remains **UNVERIFIABLE** from local artifacts. Do not
re-derive it silently.

## Suggested skills

Call the Skill tool for:

- `android-cli` — before `android install`, `android layout`, or
  `android screen`; read its `SKILL.md` and `references/interact.md` first.
- `diagnosing-bugs` — for the next diagnostic loop on the reader-slot /
  operand-block question.
- `code-review` — before the merge and cleanup pass required by #64.
- `handoff` — when the next session ends, to write the next snapshot.

Do not use the `adaptive` or `edge-to-edge` skills; this is not a UI task.
