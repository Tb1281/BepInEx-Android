# CIFI PairIP cold-launch walkthrough

Append-only run log for the CIFI (`com.OctocubeGamesCompany.CIFI`) hosted-launch
investigation. The frozen baseline, cold-launch definition, and scope note are
in [`pairip-isolation-harness.md`](pairip-isolation-harness.md). Living facts
are in [`cifi-pairip-findings.md`](cifi-pairip-findings.md). This file records
run keys and verdicts; do not rewrite earlier entries.

## Run key

A run has a verdict only when all of these are recorded:

- launcher source commit (the code in the APK),
- installed `app-debug.apk` sha256,
- exact `am start` command and extras,
- build variant/package and device.

Log captures without a complete key are evidence, not verdicts. Do not repeat a
run that already has a verdict; cite its entry instead.

## Baseline

The frozen baseline is the debug launcher `com.bepinex.android.debug`, target
`com.OctocubeGamesCompany.CIFI`, activity
`com.bepinex.android.BootstrapActivity`, `variant=default`, no extras. The
investigation baseline adds only the instrumentation toggles recorded in each
entry. Force-stop both packages before every run. Use `--el` only for
`pairip_delay_ms`; `--ez` truncates it.

## Existing evidence (not verdicts under this key)

These captures predate this walkthrough. They are cited so the same run is not
repeated, but their exact launcher commit and installed APK hash were not
recorded at capture time.

| Capture | Window | Extras | What it shows |
| --- | --- | --- | --- |
| `tmp/cifi-apk/logs/verify/baseline-dumpvm.log` | 2026-10-27 14:28:05–14:28:34 | `pairip_preload=true`, `pairip_stack_spoof=false`, `pairip_dump_vm=true`, `pairip_trace=true` | SIGSEGV in `__memmove_aarch64_nt+140`; caller `libpairipcore.so+0x8d514`. |
| `tmp/cifi-apk/logs/verify/hash-ring.log` | 2026-10-27 14:52:35–14:53:11 | `pairip_preload=true`, `pairip_trace=true`, `pairip_dump_vm=true` | Same crash; image-watch records a u32 increment at `image+0xae3` from `+0x5f714`; no hash-input lines from the `+0x3dc08` hook. |
| `tmp/cifi-apk/logs/verify/state-ring.log` | 2026-10-27 14:55:41–14:56:15 | `pairip_preload=true`, `pairip_trace=true`, `pairip_dump_vm=true` | Same crash; 130,282 state entries; final `state[130281] id=0x872a21c sel=0x31bb`; builtin ring ends `0x9a0c0031 pc=0x40a73`. |
| `tmp/cifi-apk/logs/verify/proc-sanitize.log` | 2026-10-27 14:37–14:37 | procfs sanitizer | Same crash signature; procfs content is not the gate. |
| `tmp/cifi-apk/logs/verify/syscall-maps.log` | 2026-10-27 13:46–13:47 | syscall trace | klB maps/status reads precede the failing ZIP read. |
| `tmp/cifi-apk/logs/verify/reader-guard.log` | 2026-10-27 13:11–13:12 | reader guard | Guard advances past the crash but is diagnostic only. |

The latest installed debug APK has `lastUpdateTime=2026-10-27 14:55:41`; the
local `launcher/app/build/outputs/apk/debug/app-debug.apk` was built at 14:55
with sha256 `ec7f71c79f92f60b121ae6566a699e036e1fe916b85a08b09750fd1cf1ad385f`.
It corresponds to code checkpoint `028caa2`; later commits to `3b94d1d` are
documentation-only.

## Planned run: fnv-input

- Purpose: identify the byte source of the five-word hash state rewritten at
  bytecode `0xf656` (`op=0x1b`).
- Hypothesis: the input is static image data.
- Falsification: any FNV call at `+0x7fddc` with VM fetch `0xf656` has its
  buffer outside `[image, image+0x44feb]`, or the accumulated bytes do not
  match the pristine asset at the recorded offsets.
- Probe: hook `libpairipcore.so+0x7fddc` (the `add x11, x9, w11, uxtw` before
  the FNV-1 loop at `+0x7fde0`), log `x9`/`x11`/`w26`, and accumulate bytes
  while `image+0x266f2` is in the observed loop ranges; dump when the counter
  is `<= 1`. At entry `x11` is still the zero-extended offset because Dobby's
  trampoline runs the relocated `add` afterwards, so the probe reads
  `x9 + uxtw(x11)`. The counter is the little-endian u32 at `image+0x266f2`
  (the image ring shows 4-, 2-, and 1-byte writes there).
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=fnv-input`, code commit `599cf34`, APK sha256
  `be31b43e80bdc215d58f883f873ccc35596f18e6b9e7556c3d6d591f6edfbecf`
  (115,103,986 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Command:

```bash
OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25 \
  tmp/cifi-apk/run_variant.sh fnv-input \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true
```

- Status: attempted; attempt 1 was inconclusive because the probe clobbered
  live `x16` (verdict below). Retry key follows the verdict log.

## Verdict log

Append one entry per completed run; never rewrite an earlier verdict.

### 2026-10-27 16:04 — fnv-input attempt 1: INCONCLUSIVE (probe invalid)

- Key: `variant=fnv-input`, code commit `599cf34`, APK sha256
  `be31b43e80bdc215d58f883f873ccc35596f18e6b9e7556c3d6d591f6edfbecf`,
  package `com.bepinex.android.debug` on SM-S931N (Android 17/API 37).
- Command: `OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25
  tmp/cifi-apk/run_variant.sh fnv-input --ez pairip_preload true
  --ez pairip_trace true --ez pairip_dump_vm true`.
- Window: 2026-10-27T16:04:28+09:00 to 16:04:57+09:00 (29 s).
- Hook: installed, `rc=0`, target `0x7a8c9d1ddc`, base `0x7a8c952000`.
- Probe: one filtered call at 16:04:32.670: `image=0xb400007b1224d010`,
  `off=0x24f47`, `buffer=0xb400007b12271f57`, `len=1`, `counter=0x1a3`.
  The counter was outside the pre-registered `0x100` bound, so the byte was
  skipped and nothing accumulated.
- Crash: SIGSEGV in `__strlen_aarch64+12`, caller frame reported as
  `split_config.arm64_v8a.apk +0x18de8`, at 16:04:32.671. This is not the
  frozen baseline's `__memmove_aarch64_nt+140` / `+0x8d514` signature.
- Verdict: the run does not test the hypothesis. The probe perturbed the
  path: the shim's tail loaded the trampoline through `x16`, but `x16` is
  live at `+0x7fddc` (loaded at `+0x7fdbc`, consumed at `+0x7fe18`). The
  hypothesis is neither confirmed nor falsified by this run.
- Fix: tail-branch through `x15` instead. `x15` is dead at this site because
  the loop at `+0x7fde0` overwrites it before use, and the `w26==0` path
  never reaches the hook. Code commit
  `b3f6dfd3923e467b2411f49d18fa9b5a012085d0`.

## Planned run: fnv-input (retry)

- Key: `variant=fnv-input`, code commit `b3f6dfd`, APK sha256
  `d8dc1f00a74e43f9f369f0e51a2cf735f8599d40357ec859d17694c33cffad25`
  (115,103,986 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Same frozen baseline and command as attempt 1.
- Probe changes since attempt 1: `x15` tail scratch; counter accepted up to
  `0x1000`; dump at counter `<= 1` or at the observed 419-byte total.
- Expected falsification unchanged; the input buffer must lie in
  `[image, image+0x44feb]` and match the pristine asset at the recorded
  offsets.
- Status: completed; verdict appended below.

## Verdict log (continued)

### 2026-10-27 16:11 — fnv-input retry: CONFIRMED (static image byte)

- Key: `variant=fnv-input-retry`, code commit `b3f6dfd`, APK sha256
  `d8dc1f00a74e43f9f369f0e51a2cf735f8599d40357ec859d17694c33cffad25`,
  package `com.bepinex.android.debug` on SM-S931N (Android 17/API 37,
  `user`, `ro.debuggable=0`). Installed 16:11:02.
- Command: `OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25
  tmp/cifi-apk/run_variant.sh fnv-input-retry --ez pairip_preload true
  --ez pairip_trace true --ez pairip_dump_vm true`.
- Window: 2026-10-27T16:11:07+09:00 to 16:11:37+09:00 (30 s).
- Hook: installed, `rc=0`, target `0x7a8f549ddc`, base `0x7a8f4ca000`.
- Probe: 419 calls with counter `0x1a3` -> 1, every call `off=0x24f47`,
  `len=1`, byte `0x99`; `outside=0`, `nonunit_len=0`, accumulated `419`
  bytes, FNV-1 checksum `ba6cb68e62faac68`. One further counter-0 boundary
  call repeated the same byte (checksum `af63bd4c8601b746`). The 419-byte
  checksum re-computes exactly from `0x99` repeated 419 times.
- Comparison: the pristine asset at `0x24f47` is `0x99` (sha256
  `112b6e592c18c9877b90b8f0df2c2d4dd037bf0742bacbd914a03eed242011fa`),
  the crash image at `0x24f47` is `0x99` (sha256
  `1861a24abd21aed494b8fa0a43ee1ac794096f97032c26a563969ed4fc3d04c5`),
  and `0x24f47` is outside all 78 runtime diff ranges (367 bytes).
- Crash: SIGSEGV in `__memmove_aarch64_nt+140`, caller frame
  `split_config.arm64_v8a.apk +0x514` (the baseline `+0x8d514`), at
  16:11:12.225. The probe did not change the crash signature.
- Verdict: hypothesis confirmed. The `0xf656/0x1b` hash input is static
  image data, not maps/status/path-derived: it is the single byte `0x99` at
  `image+0x24f47`, hashed once per call for the 419-call loop. The
  falsification conditions did not trigger: every computed input pointer is
  inside `[image, image+0x44feb]`, the bytes match the pristine asset, and
  the observed loop is the registered 419-call loop (plus one counter-0
  boundary call).

## Planned run: hash-gate

- Purpose: identify the state-selection hash check that routes the hosted path
  into the `0x31` reader state. The reader slots are deterministic: the five
  globals written at `0xf656/0x1b` are byte-identical in the hosted and
  `stock-libmain` captures even though the loop counts differ (`0x1a3` vs
  `0x1a4`), and the `+0x3dc88..+0x3e030` emulation over those globals always
  yields `w8=0x3f874`, `w9=0x1ba5c`. The gate is therefore earlier: the
  `+0x3dc08` name-hash check that feeds `csel w5, w24, w23, eq`.
- Hypothesis: the last hash check before the reader compares a hosted-specific
  string and the comparison result selects `0x31bb`, the selector that reaches
  the `0x31` builtin.
- Falsification: the last check before the reader is `ok` (`computed ==
  expected`) and still selects `0x31bb`, or the `+0x3dc0c`/`+0x3dc50` sites
  never run, or the hash input is static image data. In any of those cases the
  hash result is not the hosted-only gate and the fork moves to the state that
  fills the `w24`/`w23` candidates.
- Probe: the original `+0x3dc08` hook sat on `cbz w25`; Dobby relocates that
  branch without running the callback. The probe now hooks `+0x3dc0c`
  (`add x9, x8, w29, uxtw`) to record the `w25`-byte input at `image + w29`,
  and `+0x3dc50` (`csel w5, w24, w23, eq`) to record the computed/expected
  hash, the saved NZCV, the two candidate ids, and the selected id. Both shims
  tail through `x9`, which the relocated `add` overwrites or which is dead
  after the `csel`. A 64-entry ring is dumped from the failing-read path with
  the other rings.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=hash-gate`, code commit `c3da43b`, APK sha256
  `c839417723f1f04009504a67d8ac31aee04c70755aaa72ef8f81d086915f42af`
  (115,103,986 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Command:

```bash
OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25 \
  tmp/cifi-apk/run_variant.sh hash-gate \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true
```

- Status: completed; verdict appended below.

## Verdict log (continued)

### 2026-10-27 16:33 — hash-gate: FALSIFIED (the hash block is not on the reader path)

- Key: `variant=hash-gate`, code commit `c3da43b`, APK sha256
  `c839417723f1f04009504a67d8ac31aee04c70755aaa72ef8f81d086915f42af`
  (115,103,986 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`). Installed 16:32:25.
- Command: `OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25
  tmp/cifi-apk/run_variant.sh hash-gate --ez pairip_preload true
  --ez pairip_trace true --ez pairip_dump_vm true`.
- Window: 2026-10-27T16:32:52+09:00 to 16:33:20+09:00 (28 s).
- Hooks: `hash-input hook rc=0 target=+0x3dc0c`, `hash-check hook rc=0
  target=+0x3dc50`; both trampolines non-null. Target addresses resolve to
  `base 0x7a90d58000 + 0x3dc0c` and `+ 0x3dc50`.
- Probe: `trace: hash ring pos=0 dumping last 0`; no `hash:` lines. Neither
  site executed during the run.
- State path: 130,225 state entries; the last entry is
  `state[130224] id=0x872a21c sel=0x31bb reader=0xf93727d8`, immediately after
  `state[130223] id=0x629d5907 sel=0x31bb`. The `0x31` builtin
  `id=0x9a0c0031 pc=0x40a73` is the last builtin ring entry.
- Disassembly check: the state `0x872a21c` dispatch is
  `+0x34d74 b.eq +0x3dc88`; it enters the reader builder directly. The
  `+0x3dc08..+0x3dc84` hash block is the tail of a different handler (it ends
  `+0x3dc84 b +0x51f44`) and is skipped for this state. The earlier
  "hash check before the reader" attribution was a fall-through assumption,
  not a real control-flow edge.
- Crash: SIGSEGV at 16:32:57.907 in `__memmove_aarch64_nt+140`, caller
  `+0x8d514`; unchanged baseline signature.
- Verdict: the pre-registered falsification triggered ("the
  `+0x3dc0c`/`+0x3dc50` sites never run"). The `+0x3dc08` name-hash is not
  the hosted-only gate. The selector `0x31bb` is produced by the main state
  router hash at `+0x32c20..+0x32c54`, which is the next probe target; the
  `+0x3dc08` block is ruled out for this path.

## Planned run: state-router

- Purpose: test whether the main state-router hash at `+0x32c20..+0x32c54`
  selects the `0x31bb`/reader path, and identify the table entry, comparison
  result, and candidate selectors (`w24`/`w25`) that produced it. This is the
  live part of open question 2 after the `+0x3dc08` block was ruled out.
- Hypothesis: the router comparison at `+0x32c44` (`cmp x8, w22` followed by
  `+0x32c48 csel w8, w24, w25, eq`) chooses between a pass and a fallback
  selector; the hosted run takes the side that yields `sel=0x31bb` and the
  reader state, while the other side is the non-reader path.
- Prediction: the probe records at least one router transition with the table
  index, table base, loaded entry, computed value, expected `0x0cd47edb`,
  `w24`/`w25`, the `eq` flag, and the resulting `w5`. The last transitions
  before `state[..] id=0x872a21c` identify the side that produced `0x31bb`.
  A first-`ne` sample is kept even if the last 64 transitions are all `eq`.
- Falsification: no router transition selects the reader side; the comparison
  is always `eq` with a static table entry and the selected candidate is
  constant (the router is a fixed dispatch, not the hosted gate); or the
  router sites never execute. In any of those cases the router hash is not
  the gate and the fork moves to the `0x31` builtin's operand block at
  `0x40a73` and the state that re-dispatches to `0x872a21c`.
- Probe: hook `+0x32c2c` (`ldrsw x8, [x8, w9, uxtw]`, the table load) and
  `+0x32c48` (`csel w8, w24, w25, eq`, the selector pick). Both are non-branch
  sites. Tail through `x10` at the load (dead after the quotient at
  `+0x32c24`) and `x9` at the csel (dead after `+0x32c40`). A 64-entry ring
  plus eq/ne counters and one first-`ne` sample are dumped from the
  failing-read path with the other rings.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Command:

```bash
OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25 \
  tmp/cifi-apk/run_variant.sh state-router \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true
```

- Run key: `variant=state-router`, code commit `71072b5`, APK sha256
  `0abb3e8292f77f79b118ab72bc3db76408238d2ed2af8e2b269910c59f32b8f7`
  (115,103,986 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Status: attempt 1 invalid; retry below.

## Verdict log (continued)

### 2026-10-27 16:50 — state-router attempt 1: INCONCLUSIVE (probe invalid)

- Key: `variant=state-router`, code commit `71072b5`, APK sha256
  `0abb3e8292f77f79b118ab72bc3db76408238d2ed2af8e2b269910c59f32b8f7`
  (115,103,986 bytes), installed 16:44:58.
- Command: `OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25
  tmp/cifi-apk/run_variant.sh state-router --ez pairip_preload true
  --ez pairip_trace true --ez pairip_dump_vm true`.
- Window: 2026-10-27T16:49:36+09:00 to 16:50:03+09:00 (27 s).
- Hooks: `router-load hook rc=0 target=+0x32c2c`, `router-check hook rc=0
  target=+0x32c48`; both trampolines non-null.
- Probe: no `router:` lines, no `router ring` dump, no `state` dump. The run
  died after six builtins at 16:49:40.548, before any router callback.
- Crash: `malloc(12970367457329746080)` failed (the size is
  `0xb400007b1224d020`, the image base `0xb400007b1224d010` + 0x10), then
  SIGSEGV at `split_config.arm64_v8a.apk +0x4c4` at 16:49:40.549. This is not
  the frozen baseline `__memmove_aarch64_nt+140` / `+0x8d514` signature.
- Verdict: the run does not test the router hypothesis. The probe perturbed
  the dispatcher before the first router callback; the likely cause is the
  `+0x32c2c` tail through `x10` or the `+0x32c48` csel hook in the hot
  dispatcher loop. Retry hooks the trampoline entry `+0x32c20` instead, tails
  through `x9` (dead because `+0x32c20 eor w9, w21, w23` writes it), and
  computes the comparison offline from the recorded inputs, so the csel site
  is not hooked.

## Pre-registered experiment (2026-10-27, state-router-retry)

Run log and key: this section and the verdict appended after the run.

- Hypothesis (unchanged): the comparison at `+0x32c44` (`cmp x8, x22`) and the
  `+0x32c48 csel w8, w24, w25, eq` choose between a pass and a fallback
  selector; the hosted run takes the side that yields `sel=0x31bb` and the
  reader state, while the other side is the non-reader path.
- Change from attempt 1: one hook at the trampoline entry `+0x32c20`
  (`eor w9, w21, w23`) instead of `+0x32c2c` and `+0x32c48`. The shim tails
  through `x9` (the relocated `eor` writes it). The callback records the table
  base `x8`, mask `x12`, expected `x22`, candidates `w24`/`w25`, mix `w21`, and
  modulus `w19`, reads the table word at `x8 + ((w21 ^ w23) % w19) * 4`, and
  computes the native comparison, `csel`, and `w5` offline. No csel hook.
- Prediction: at least one `router:` transition is recorded before
  `state[..] id=0x872a21c`; the ring and first-`ne` sample identify whether
  `0x31bb` comes from the `eq` or the `ne` side, and whether the table word is
  static across runs.
- Falsification: no router transition selects the reader side; the comparison
  is always `eq` with a static table entry and a constant selected candidate
  (a fixed dispatch, not the hosted gate); or the `+0x32c20` site never
  executes. In any of those cases the router hash is not the gate and the fork
  moves to the `0x31` builtin's operand block at `0x40a73` and the state that
  re-dispatches to `0x872a21c`.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Command:

```bash
OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25 \
  tmp/cifi-apk/run_variant.sh state-router-retry \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true
```

- Run key: `variant=state-router-retry`, code commit `d20fa9e`, APK sha256
  `8694a819709f9797538d78c20ce5dbd710c6d0589ae8562a6ff22b9bfabe1df2`
  (115,103,986 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Status: pre-registered; ready to install and run.

### 2026-10-27 16:59 — state-router-retry: FALSIFIED (router block is not on the reader path)

- Key: `variant=state-router-retry`, code commit `d20fa9e`, APK sha256
  `8694a819709f9797538d78c20ce5dbd710c6d0589ae8562a6ff22b9bfabe1df2`
  (115,103,986 bytes), installed 16:58:18 (installed hash matches).
- Command: `OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25
  tmp/cifi-apk/run_variant.sh state-router-retry --ez pairip_preload true
  --ez pairip_trace true --ez pairip_dump_vm true`.
- Window: 2026-10-27T16:59:07+09:00 to 16:59:35+09:00 (28 s).
- Hooks: only `router-load hook rc=0 target=+0x32c20`, trampoline non-null.
  The `+0x32c2c` load and `+0x32c48` csel sites are not hooked.
- Probe: `router ring pos=0 eq=0 ne=0 dumping last 0`; no `router:` line.
  The probe did not perturb the run. The state ring reached
  `state[130814] id=0x872a21c sel=0x31bb reader=0xf93727d8`, and the builtin
  ring shows `id=0x9a0c0031 pc=0x40a73` immediately before the crash.
- Crash: `__memmove_aarch64_nt+140` (the frozen baseline signature), with the
  reader slots `bbb9eb6daed20ed0` / `015370f87525d57b` in the VM dump. The
  attempt-1 `malloc(...)` / `split+0x4c4` crash did not recur.
- Verdict: the `+0x32c20` hash block never executes on this path. The
  dispatcher compares `w8` with `0x872a21c` at `+0x34d68..+0x34d74` and branches
  directly to `+0x3dc88` (`b.eq 0x3dc88`), so state `0x872a21c` builds the
  reader without the router hash. The router is not the hosted gate. The
  retry is a clean falsification, not an invalid probe.

## Planned run: apk-zero-map

- Purpose: test whether the missing offset-0 mapping of the game base APK is
  what prevents PairIP's central-directory resolver from initializing klB's
  reader slots.
- Hypothesis: the hosted process must expose an offset-0 game `base.apk`
  mapping for the resolver to write the selected reader pointer/size slots
  (`image+0x3f874` / `image+0x1ba5c`) or to skip the `0x31` reader call.
- Change from the frozen baseline: add `pairip_apk_zero_map=true`. The launcher
  maps the whole installed base APK `PROT_READ`, `MAP_PRIVATE`, offset 0, at
  hint `0x7900000000` with `MAP_FIXED_NOREPLACE`; on failure it retries with a
  null hint. The native log prints the range and its `/proc/self/maps` line.
- Prediction: the mapping line is present before `libmain.so` runs, and either
  the reader slots differ from `0x3f874` / `0x1ba5c` or the
  `__memmove_aarch64_nt+140` crash disappears. The five shadow globals may
  remain byte-identical because their FNV input is static; that alone does not
  falsify the mapping gate.
- Falsification: the mapping is present and the crash still reads the same two
  slots with the same signature and the same state ring tail
  (`id=0x872a21c sel=0x31bb`, `builtin id=0x9a0c0031 pc=0x40a73`). Then the
  offset-0 mapping is not the gate.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=apk-zero-map`, code commit `4cd16ed`, APK sha256
  `9aa20aa3e3481fb3e1cde83729df34bb17b145d9ef5a1ce2eb411970c576b892`
  (115,104,390 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Command:

```bash
OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25 \
  tmp/cifi-apk/run_variant.sh apk-zero-map \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_apk_zero_map true
```

- Status: pre-registered; ready to install and run.

### 2026-10-27 17:59 — apk-zero-map: FALSIFIED (offset-0 mapping is not the gate)

- Key: `variant=apk-zero-map`, code commit `4cd16ed`, APK sha256
  `9aa20aa3e3481fb3e1cde83729df34bb17b145d9ef5a1ce2eb411970c576b892`
  (115,104,390 bytes), installed hash matches, `lastUpdateTime=2026-10-27
  17:53:23`, package `com.bepinex.android.debug` on SM-S931N (Android 17/API
  37, `user`, `ro.debuggable=0`).
- Command: `OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25
  tmp/cifi-apk/run_variant.sh apk-zero-map --ez pairip_preload true
  --ez pairip_trace true --ez pairip_dump_vm true --ez pairip_apk_zero_map
  true`.
- Window: 2026-10-27T17:59:25+09:00 to 17:59:55+09:00 (30 s).
- Mapping: `apk-zero-map: base=0x7900000000 size=122714162
  hint=0x7900000000 fixed=1 fallback_errno=0`. The VM's maps dump contains
  `7900000000-7907508000 r--p 00000000 fe:60 942208 .../base.apk` at line 32,
  so the game APK is now mapped at offset 0 below the existing `r--s`
  mappings.
- Probe: the FNV input is unchanged (419 calls, `off=0x24f47`, byte `0x99`,
  checksum `ba6cb68e62faac68`). The hash and router rings remain at `pos=0`.
  The state ring ends `id=0x872a21c sel=0x31bb reader=0xf93727d8`; the builtin
  ring ends `id=0x9a0c0031 pc=0x40a73`.
- Crash: `__memmove_aarch64_nt+140` (`libsigchain #03`), caller
  `split_config.arm64_v8a.apk +0x514`, at 17:59:32.431. The reader slots are
  still `bbb9eb6daed20ed0` / `015370f87525d57b`; the process dies by signal 11
  at 17:59:32.468.
- Image: `tmp/cifi-apk/vmrun/klB-image-zero-map.bin` (crash time,
  282,603 bytes, sha256
  `92958cc0804ac7be0a9e53f62446f506e1dcc3d26dbf2360b728eca9b266b513`)
  has 368 changed bytes in 79 ranges. It differs from the retry image by only
  50 bytes in 29 ranges (ASLR pointers plus the known `image+0xae3` counter).
  Maps dump: `tmp/cifi-apk/vmrun/klB-maps-zero-map.txt`, sha256
  `515c939aa55bce3b53cea8b87eccf7babc898620ca622cffc850c933c8b942f9`.
  Log sha256 `81ebb14cb2d2dbff2375048c66a5012e162db3c28d13a40c682eaf30eecdd014`.
- Verdict: the mapping was present and visible to the VM, but the crash and
  every pre-crash state are unchanged. The missing offset-0 game `base.apk`
  mapping is not the hosted gate. The next fork is the `0x31` builtin at
  `pc=0x40a73`: instrument its operand block, or prove stock takes an earlier
  branch before the reader.

## Planned run: process-name-spoof

- Purpose: test whether the hosted process identity is the PairIP gate. The
  `0x7e` handler reads `/proc/self/status` and `/proc/self/cmdline`; the
  hosted `:game` process exposes `com.bepinex.android.debug:game` there while
  a stock launch exposes `com.OctocubeGamesCompany.CIFI`. Every other hosted
  difference exercised so far (missing offset-0 APK mapping, host maps lines,
  uid/gid) has been falsified.
- Hypothesis: PairIP's first VM takes the decoy/integrity branch because the
  process name is the launcher's, not the game's. Restoring the stock name
  before the first VM makes the VM select or initialize the reader slots the
  way stock does.
- Change from the frozen baseline: add `pairip_process_name=true`. The debug
  launcher rewrites argv0 in place to `com.OctocubeGamesCompany.CIFI`
  (29 bytes, fits the current 30-byte buffer) and sets the task name to its
  last 15 characters, `mesCompany.CIFI`, matching Android's own suffix rule.
  No maps sanitizer, no offset-0 mapping, no fake CD, no seed.
- Prediction: the log shows `process name spoofed: comm=mesCompany.CIFI` and
  the VM's status sample at `fetch=0x333eb op=0x7e` starts with
  `Name:\tmesCompany.CIFI` (instead of `Name:\troid.debug:game`), and the
  cmdline sample is `com.OctocubeGamesCompany.CIFI`. Either the reader slots
  differ from `0xbbb9eb6daed20ed0` / `0x015370f87525d57b` (and the
  `__memmove_aarch64_nt+140` crash disappears or diverges to a different
  failure), or the state/builtin tail diverges before the reader.
- Invalid run: the spoof call fails, or the VM's status sample still shows the
  launcher name. Then the identity variable was not actually changed and the
  result is not a falsification.
- Falsification: the spoof succeeds and the VM status sample shows the stock
  name, but the crash signature, both reader slots, the five-global values,
  the FNV input, the state tail (`id=0x872a21c sel=0x31bb`), and the builtin
  tail (`id=0x9a0c0031 pc=0x40a73`) are byte-identical to baseline. Then the
  process name is not the hosted gate.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=process-name-spoof`, code commit `defa262`, APK sha256
  `9aa20aa3e3481fb3e1cde83729df34bb17b145d9ef5a1ce2eb411970c576b892`
  (115,104,390 bytes), installed `lastUpdateTime=2026-10-27 17:53:23`,
  package `com.bepinex.android.debug` on SM-S931N (Android 17/API 37, `user`,
  `ro.debuggable=0`).
- Command:

```bash
OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25 \
  tmp/cifi-apk/run_variant.sh process-name-spoof \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_process_name true
```

- Status: pre-registered; ready to run.
