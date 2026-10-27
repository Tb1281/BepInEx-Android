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

### 2026-10-27 18:26 — process-name-spoof: FALSIFIED (process identity is not the gate)

- Key: `variant=process-name-spoof`, code commit `defa262`, APK sha256
  `9aa20aa3e3481fb3e1cde83729df34bb17b145d9ef5a1ce2eb411970c576b892`
  (115,104,390 bytes), installed hash matches,
  `lastUpdateTime=2026-10-27 17:53:23`, package
  `com.bepinex.android.debug` on SM-S931N (Android 17/API 37, `user`,
  `ro.debuggable=0`).
- Command: `OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25
  tmp/cifi-apk/run_variant.sh process-name-spoof --ez pairip_preload true
  --ez pairip_trace true --ez pairip_dump_vm true --ez pairip_process_name
  true`.
- Window: 2026-10-27T18:26:05+09:00 to 18:26:47+09:00 (42 s).
- Spoof: `process name spoofed: comm=mesCompany.CIFI` at 18:26:08.941,
  before `libpairipcore.so` was loaded (18:26:10.071) and before the first
  `0x7e` batch. `prctl(PR_SET_NAME)` succeeded on the main thread
  (pid=tid 6446). AOSP `AndroidRuntime::setArgv0` writes the name into the
  argv0 block and calls `setprogname` on that block, so the rewritten buffer
  is also `/proc/self/cmdline`; `/proc/self/status` `Name:` is the main-thread
  comm. The VM's `0x7e` handler opens `/proc/self/status`, so it saw
  `Name:\tmesCompany.CIFI` rather than `roid.debug:game`.
- Probe: the FNV input is unchanged (419 calls, `off=0x24f47`, byte `0x99`,
  checksum `ba6cb68e62faac68`), and the five globals reach the same values as
  every hosted run (`0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`,
  `0x4d062af3`). The state ring ends `id=0x872a21c sel=0x31bb
  reader=0xf93727d8`; the builtin ring ends `id=0x9a0c0031 pc=0x40a73`.
- Crash: `__memmove_aarch64_nt+140` at 18:26:11.179; frame #04 is
  `split_config.arm64_v8a.apk +0x514`, the frozen shadow-frame signature.
  The reader slots are still `bbb9eb6daed20ed0` / `015370f87525d57b`.
- Image: `tmp/cifi-apk/vmrun/klB-image-at-crash-pns.bin` (282,603 bytes,
  sha256 `a4db6ad23e750685d31c33c308ac34ae9b16b98544fdb16636ae92165e391158`)
  has 368 changed bytes in 79 ranges versus the pristine asset, exactly the
  baseline count. It differs from the zero-map crash image by 50 bytes in 29
  ranges (ASLR pointers plus the known `image+0xae3` counter), and both ZIP
  slots are unchanged. The pre-execution `klB-image.bin` is byte-identical to
  the pristine asset (sha256 `112b6e59…`), as in every prior run.
- Maps dump: `tmp/cifi-apk/vmrun/klB-maps-process-name-spoof.txt`, sha256
  `a807ebc4e502032db8a1f4f1ea1bc8851e335783731f288982c68ab811e65dd9`.
  Log sha256
  `f4916b3246da0545514f1988abfa212b2957af9fe3d0a4d091d02e11268ec45d`.
- Verdict: the process name changed and was visible to the VM, but every
  pre-crash VM value and the crash are byte-identical to baseline. The process
  identity is not the hosted gate. Together with `proc-sanitize` and
  `apk-zero-map`, this closes the environment-input branch: maps content,
  uid/gid, process name and the offset-0 APK mapping do not feed the five
  globals. Stock computes the same five globals, so the difference must be the
  branch/state before the `0x31` reader, not the hash input. Do not repeat
  maps/status/name experiments; the next fork is the `0x31` operand block and
  the preceding state's `next`/`fallback` decision.

## Planned run: syscall-tail

- Purpose: capture the crash-time builtin ring with the six `args=` words of
  the `0x31` entry at `pc=0x40a73`, and the raw-syscall tail, to decide whether
  the hosted path attempts a file-backed ZIP-view resolution before the reader.
  The 18:26 process-name-spoof log that contained these ring dumps was lost
  when the logcat buffer rolled over; the device image and maps were recovered,
  but the `args=` fields and syscall tail were not.
- Hypothesis: under the frozen hosted baseline, `libpairipcore.so`'s raw
  syscall wrapper performs no `openat`/`read` of the game `base.apk` or
  `split_config.arm64_v8a.apk` before the `0x31` reader. The two ZIP-view
  literals are therefore expected from image/VM state, not from a file read
  that the hosted path skipped.
- Change from the frozen baseline: add `pairip_syscall_trace=true` and the
  inert label `pairip_variant=syscall-tail`. No environment input, no code
  patch, no reader seed, no fake CD.
- Prediction: the log contains `trace: builtin ring pos=... dumping last 32`
  with the final entry `id=0x9a0c0031 pc=0x40a73` and six non-zero `args=`
  words, and `trace: syscall ring pos=... dumping last 256` whose file opens
  are only `/proc/self/status` and `/proc/self/maps` (plus non-file syscalls
  such as `clock_gettime`). No `path=` ends in `.apk`. The crash stays
  `__memmove_aarch64_nt+140` / `+0x8d514`, and the reader slots stay
  `0xbbb9eb6daed20ed0` / `0x015370f87525d57b`.
- Discriminator: if the tail confirms no APK open, the `0x31` operand block is
  the next static target and the file-backed-resolution branch is ruled out.
  If an `openat` of either game APK appears, the native VM did attempt a
  file-backed resolution; that call site becomes the next probe.
- Scope limit: the ring covers only syscalls issued through
  `libpairipcore.so`'s wrapper. It cannot see Java-side reads in `VMRunner` or
  `libart`; a negative result rules out the native VM path only.
- Invalid run: no `trace: builtin ring` line, no `0x31` entry in the ring, or
  no `trace: syscall ring` line (syscall trace not installed). Then the run
  does not test either branch.
- Falsification: an `openat` (`n=56`) with `path=` containing `base.apk` or
  `split_config.arm64_v8a.apk`, or a different crash signature / reader-slot
  pair. The latter two mean the added tracing perturbed the path, so the run is
  inconclusive rather than a falsification.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=syscall-tail`, code commit `2ab9720` (code identical to
  `4cd16ed`; later commits are documentation-only), APK sha256
  `9aa20aa3e3481fb3e1cde83729df34bb17b145d9ef5a1ce2eb411970c576b892`
  (115,104,390 bytes), installed `lastUpdateTime=2026-10-27 17:53:23`,
  package `com.bepinex.android.debug` on SM-S931N (Android 17/API 37, `user`,
  `ro.debuggable=0`).
- Command:

```bash
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_syscall_trace true --es pairip_variant syscall-tail
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/syscall-tail.log
```

- Status: run; verdict below.

## Verdict log (continued)

### syscall-tail (2026-10-27 21:50)

- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 249` ms. Both packages
  were force-stopped first and the log buffer was cleared.
- Log: `tmp/cifi-apk/logs/verify/syscall-tail.log`, 19,146 lines, sha256
  `304b6787e1bca798cde94afbc9de4b28fc276a960f81773647c6fc82483523e6`.
  Crash image: `tmp/cifi-apk/logs/verify/syscall-tail-image.bin`, 282,603
  bytes, sha256
  `ede029895f614adc5a0980bcfda7f6eb0f7f02b9d483d1479cb10bea7ba29fba`.
  Maps: `tmp/cifi-apk/logs/verify/syscall-tail-maps.txt`, 426,335 bytes,
  sha256
  `ea11ee397fbe1f659fd9da73cdbc5b18ecdd30849b51e04f0c9694c10e432bd4`.
- Instrumentation check: the experiment line reports `variant=syscall-tail`,
  `preload=true`, `trace=true`, `dumpVmImage=true`, `syscallTrace=true`; every
  other experiment switch is off.
- `ExecuteProgram`: `name=klB1wZSojiVvDvCN args=0x7ff9373ee0 argc=1
  caller=0x79fb72d038`; `arg[0]=0x79fa071000`; `vm-args: [0]
  java.lang.Long=0x79fa071000`. The maps line
  `79fa071000-79fa565000 r--p 04eec000 ... split_config.arm64_v8a.apk` is the
  first split-APK mapping, and `libunity.so`'s entry data starts at file
  offset `0x4eec000` with first `LOAD` `p_offset=0`, `p_vaddr=0`. `arg[0]` is
  therefore libunity's ELF load base, not an APK base or a ZIP view.
- Builtin ring: `pos=11260`, last 32 entries dumped. All 32 entries have the
  same `arg=0x7ff9372ba8`, `out=0x7ff9372ba4`, `img=0xb400007b1224d010`,
  `len=0x44feb`, and the same six `args=` words:
  `0000007a89767680,0000007ff9372b88,0042117b000000b0,b400007bb465c2d0,0000000000000000,0000007a89767830`.
  Only `pc` and `id` vary. The six words are the shared dispatcher context
  block, not per-builtin operands; the `0x31` entry's operand block is not in
  the ring. The sixth word is zero, so the pre-registration's "six non-zero"
  wording was not literal; that is a miss in the prediction's operand
  expectation, not a falsification of the no-read hypothesis. Final entry:
  `builtin[11259] id=0x9a0c0031 pc=0x40a73 lr=+0x6adc4`; the preceding five
  ids are `0x0c`, `0x36`, `0x09`, `0x2d`, `0x5e`.
- Syscall ring: `pos=181`, all 181 entries dumped. Counts: 7 `openat` (4
  `/proc/self/maps`, 3 `/proc/self/status`), 6 `close`, 2 `fstat`, 2
  `fstatfs`, 1 `readlinkat(/proc/self/fd/149)`, 146 `read` (3 status, 143
  maps), 16 `clock_gettime`, 1 `getpid`. No `path=` ends in `.apk`, and no
  read consumes an APK descriptor. Every file path in the ring is procfs.
- Maps scan: 143 full 1,024-byte reads = 146,432 bytes (`0x23c00`), with no
  short read and no EOF; it stops mid-line. The base APK CD mapping
  (`r--s 074f5000`) starts at byte 316,554 (line 3263); the split CD mapping
  (`r--s 06627000`) starts at byte 366,461 (line 3787). Both lie outside the
  scanned prefix. After the last maps read come 16 `clock_gettime` calls in
  pairs at vm `0x395ad/0x6d`, `0xf656/0x1b`, `0x158e1/0x92`, `0x1831/0x92`,
  and `0x1f811/0x7e`; the last pair is at the five-global writer immediately
  before the fault.
- Crash: unchanged. `SIGSEGV` in `__memmove_aarch64_nt+140`; shadow frame
  `#04 ... split_config.arm64_v8a.apk +0x514`; reader slots at `vm[0x540]`
  are `bbb9eb6daed20ed0` / `015370f87525d57b`; state ring ends
  `id=0x872a21c sel=0x31bb reader=0xf93727d8`; image diff 368 bytes in 79
  ranges, both ZIP slots unchanged. The five globals are the baseline values
  `0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`, `0x4d062af3`.
- Verdict: **PROVEN** for the pre-registered hypothesis. The native VM's
  syscall wrapper performs no `openat`/`read` of the game base or split APK
  before the `0x31` reader, so the unresolved ZIP-view literals are not the
  residue of a skipped native file read in this process. The file-backed
  native-resolution branch is ruled out. The scope limit stands: the ring sees
  only syscalls through `libpairipcore.so`'s wrapper, not Java-side
  `VMRunner`/`libart` reads.
- New facts, not part of the hypothesis: `arg[0]` is libunity's load base; the
  builtin `args=` block is shared dispatcher context; the maps scanner stops
  at 143 KB while the real CD mappings lie beyond it. The last is a lead, not
  a proven gate: the earlier maps experiments changed content but never placed
  the CD line inside the scanned window.
- Next: static decode of the `0x31` handler/operand block and the preceding
  state's `next`/`fallback` decision. The maps-window lead is recorded in the
  findings; it must not be run without a separate pre-registration.

## Planned run: apk-cd-map

- Purpose: test whether the maps window is the gate. klB's maps reader consumes
  exactly the first 146,432 bytes; the real base-APK central-directory line
  (`r--s 074f5000`) starts at byte 316,554, so the VM never sees it. This run
  maps the real CD at a low address so an identical line appears inside the
  scanned prefix. It is not a repeat of `apk-zero-map`: that run mapped file
  offset 0 (`r--p 00000000`) and did not put a CD-offset line anywhere.
- Hypothesis: if the `0x6d` maps parser uses the `r--s 074f5000` line to locate
  the base APK central directory, the low duplicate will change the five
  globals or the reader slots, or move the crash past the failing read.
- Change from the frozen baseline: add `pairip_apk_cd_map=true` and the inert
  label `pairip_variant=apk-cd-map`. The debug APK also contains the new
  debug-only switch (commit `e5cd358`); no game APK is modified, repacked, or
  re-signed.
- Prediction: the log contains `apk-cd-map: base=0x7900000000 size=0x13000
  hint=0x7900000000 fixed=1 offset=0x74f5000 eocd=0x750781c` and the maps dump
  has an `r--s 074f5000` line starting at `0x7900000000` within the first
  146,432 bytes. If the window is causal, at least one of the five globals
  differs from `0x534a6211`/`0xe29e9c3b`/`0x8a1aeb2d`/`0xbfe458cb`/`0x4d062af3`,
  the reader slots differ from `bbb9eb6daed20ed0`/`015370f87525d57b`, or the
  crash no longer stops at `__memmove_aarch64_nt+140`.
- Falsification: the line is present inside the prefix, yet the five globals,
  reader slots, state/builtin tails, crash signature, and image diff are
  byte-identical to baseline. Then the maps window is not the gate; close the
  lead and return to the `0x31` operand block. If the mapping is absent, lies
  outside the prefix, or `apk-cd-map:` reports an error, the run is invalid.
- Scope limit: the mapping makes the CD line visible to the maps parser; it
  does not prove that the parser reads the CD bytes or that stock's maps
  ordering matches. A positive result still needs the parser's consumption
  path; a negative closes the window lead only.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=apk-cd-map`, code commit `e5cd358`, APK sha256
  `e36de8a080d7cc131b205f2f22d73fccdbdbb8d426d0a7f23976aa6398334071`
  (115,274,306 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`). Installed hash and
  `lastUpdateTime` are recorded after the install.
- Build note: the ignored Frida gadget (`libaudiopolicy.so`, 39 MB) was moved
  out of `jniLibs` for this build and restored afterwards, so the APK matches
  the installed baseline's 488 entries. The `BepInEx.Android.zip` asset is
  rebuilt and differs slightly; it is not loaded before the PairIP crash.
- Command:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_apk_cd_map true --es pairip_variant apk-cd-map
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/apk-cd-map.log
```

- Status: run; verdict below.

### apk-cd-map (2026-10-27 22:10)

- Install: delta install sent a 49.00 MB patch; `lastUpdateTime=2026-10-27
  22:10:17`, installed `base.apk` sha256
  `e36de8a080d7cc131b205f2f22d73fccdbdbb8d426d0a7f23976aa6398334071`
  (115,274,306 bytes), identical to the built APK.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 359` ms. Both packages
  were force-stopped first and the log buffer was cleared.
- Log: `tmp/cifi-apk/logs/verify/apk-cd-map.log`, 18,913 lines, sha256
  `3d2fef203795afaf497188876cd5a446038e1e3afde0b584cb919f1882dfa962`.
- Instrumentation check: the experiment line reports `variant=apk-cd-map`,
  `preload=true`, `trace=true`, `dumpVmImage=true`, `apkCdMap=true`; every
  other switch, including `syscallTrace` and `apkZeroMap`, is off.
- Mapping: `apk-cd-map: base=0x7900000000 size=77824
  hint=0x7900000000 fixed=1 offset=0x74f5000 eocd=0x750781c` from the game's
  base APK, followed by the live maps line
  `7900000000-7900013000 r--s 074f5000 fe:60 942208 ... base.apk`.
- Window check: in `tmp/cifi-apk/logs/verify/apk-cd-map-maps.txt` (427,080
  bytes, sha256
  `c1342391216e23b325c23fd002174bf10361e013f796802bd1b6167d889c2c47`) the
  duplicate line is line 32, starting at byte 3,596 and ending at byte 3,769.
  The VM's reader consumes 146,432 bytes, so the line is inside the scanned
  prefix. The split-APK CD line is at line 3808, byte 368,362, still outside
  the prefix.
- Crash: unchanged. `SIGSEGV` at 22:10:41.788 in
  `__memmove_aarch64_nt+140`; frame `#04 pc 00000514
  split_config.arm64_v8a.apk (+0)` is the frozen shadow-frame signature. The
  reader slots at `vm[0x540]` are still `bbb9eb6daed20ed0` /
  `015370f87525d57b`; the state ring ends `id=0x872a21c sel=0x31bb
  reader=0xf93727d8`; the builtin ring ends `id=0x9a0c0031 pc=0x40a73`.
- Image: `tmp/cifi-apk/logs/verify/apk-cd-map-image.bin`, 282,603 bytes,
  sha256
  `cc6c9669a819686e27580842693e1c737228f1ba5b8b367d9cbc2cedd3247dc1`.
  The five globals are the baseline values `0x534a6211`, `0xe29e9c3b`,
  `0x8a1aeb2d`, `0xbfe458cb`, `0x4d062af3`; the diff versus the pristine
  asset is 368 bytes in 79 ranges; both ZIP slots are unchanged.
- Verdict: **FALSIFIED** for the maps-window hypothesis. The real
  `r--s 074f5000` line was present at byte 3,596, deep inside the scanned
  prefix, and it changed neither the five globals nor the reader slots nor the
  crash. The maps-window/content/identity branch is closed. A CD line inside
  the window is not the gate, so the parser is not selecting the base APK by
  that line.
- Consequence: no further maps/procfs experiment is justified. The only live
  target is the `0x31` operand block at bytecode pc `0x40a73` and the
  preceding state's `next`/`fallback` decision. The `pairip_apk_cd_map`
  switch is diagnostic only and must be removed before merge.

## Planned run: jni-trace

- Purpose: identify the Java object that the `0x7e` handler reads from
  `[state+0x20]`. The handler stores `IsSameObject(x20, NULL) == 0` into
  `image+0x263cd` and `image+0x2904f`; both stores are `1` in every hosted
  run, so `x20` is a non-null Java object at both checks. The `0x31` builtin
  at `pc=0x40a73` then writes the five globals, and the reader pair diverges.
- Hypothesis: `x20` is a pending exception (or another Java-side object)
  produced by a klB JNI call in the hosted environment; the tracer will name
  the class/method/throwable and identify the hosted-only failure.
- Change from the frozen baseline: add `pairip_jni_trace=true` and the inert
  label `pairip_variant=jni-trace`. The tracer is data-only: it patches the
  process-wide JNIEnv table in place and forwards every call to the saved
  original. No game APK is modified, repacked, or re-signed.
- Prediction: before the `vminsn[1579] fetch=0x1f811 op=0x7e` /
  `builtin[11234] id=0x9a0c0031 pc=0x40a73` tail, the log contains a
  `PairIpJni` record from `libpairipcore.so` with a non-null
  `ExceptionOccurred -> 0x...`, an `ExceptionClear: <class>: <message>`, or a
  `Call* ... -> <pending exception>` with throwable text. The five globals,
  reader slots, crash signature, and state/builtin tails stay at the frozen
  values.
- Falsification: no non-null `ExceptionOccurred` and no pending-exception
  result before the crash, or no `PairIpJni` records despite
  `installed=true`. Then `x20` is not an exception visible to the tracer and
  this lead is negative. A changed crash, reader slots, five globals, or
  tails makes the run inconclusive.
- Scope: the tracer does not hook `IsSameObject`, `NewLocalRef`, or
  `DeleteLocalRef`; it sees calls/results/exceptions, not the VM's store to
  `[state+0x20]`. A positive names the hosted Java failure but does not prove
  stock's `x20` is null.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=jni-trace`, launcher source tree `505a125` (last launcher
  change `e5cd358`), APK sha256
  `e36de8a080d7cc131b205f2f22d73fccdbdbb8d426d0a7f23976aa6398334071`
  (115,274,306 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Command:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_jni_trace true --es pairip_variant jni-trace
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/jni-trace.log
```

- Status: pre-registered; ready to run.

### jni-trace (2026-10-27 23:17)

- Install: `android install` sent a 728.94 KB delta patch; the installed base
  APK remained
  `e36de8a080d7cc131b205f2f22d73fccdbdbb8d426d0a7f23976aa6398334071`
  (115,274,306 bytes), the same debug build used by `apk-cd-map`.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 419` ms. Both packages
  were force-stopped first and the log buffer was cleared.
- Log: `tmp/cifi-apk/logs/verify/jni-trace.log`, 22,386 lines, sha256
  `9b95ca6a677cdc3b1adf14f849282d05f70883fc7f9288b0d6593439e31bac9e`.
- Instrumentation check: line 1,205 reports `variant=jni-trace`,
  `preload=true`, `trace=true`, `dumpVmImage=true`, `jniTrace=true`; every
  other experiment switch is off.
- Tracer: `jni: patched 35 slots in [0x7b51e19000, 0x7b51e1a000) prot=1`,
  `jni: trace installed, libpairipcore=[0x7a8d352000, 0x7a8d3eb4c8)`, and
  `PairIP JNI trace: installed=true` at 23:17:31.972, before `libmain`/klB.
  3,571 `PairIpJni` records.
- JNI result: one `ExceptionOccurred -> 0x0` on thread 25201 (Play Integrity
  `LOG_TELEMETRY`), zero `ExceptionClear`, zero `Throw`/`ThrowNew`, and zero
  `pending exception` results anywhere. The main thread's window instead
  contains two `FindClass("java/lang/Exception")` ->
  `NewObjectA java.lang.Exception` -> `getStackTrace` sequences (lines 8,357
  and 8,830, at 33.887 and 34.076). Each is followed by
  `PairIP: stack trace spoofed for executeVM (nativeLoader=true)`, so the
  launcher's hook returned the fabricated 24-frame `pairIpUnityLaunchStack`;
  the tracer's `DumpStackTrace` output is that fabricated chain, not the real
  Java stack. After the second walk PairIP calls
  `Debug.isDebuggerConnected()` and `Debug.waitingForDebugger()`, both `false`,
  then reaches the reader/crash path.
- Crash: unchanged; signal 11 in `__memmove_aarch64_nt+140`, with the
  `split_config.arm64_v8a.apk +0x514` frame. `vm[0x540]` still holds
  `bbb9eb6daed20ed0` / `015370f87525d57b`.
- Rings: `state pos=41366`, tail `state[41365] id=0x872a21c sel=0x31bb`;
  `builtin pos=3699`, tail `builtin[3698] id=0x9a0c0031 pc=0x40a73`;
  `vminsn[1579] fetch=0x1f811 op=0x7e`. The ring positions are lower than the
  baseline (`130460`/`11234`) because the tracer slowed the loop; the tails,
  the five globals, and both reader slots are identical.
- Image: `tmp/cifi-apk/logs/verify/jni-trace-image.bin`, sha256
  `c952fdaa414120d7d8dd6561301a57e9e38e47d9300512949e97cb3984865d6d`. The
  five globals (`0x534a6211`, `0xe29e9c3b`, `0x8a1aeb2d`, `0xbfe458cb`,
  `0x4d062af3`) and both reader slots are byte-identical to
  `apk-cd-map-image.bin`. Diff vs the prior crash image is 34 ranges / 76
  bytes (ASLR, heap and counters); diff vs the pristine asset is 79 ranges /
  368 bytes.
- Verdict: the pre-registered pending-exception prediction is **FALSIFIED**,
  and the run is valid. `x20` is not a pending exception visible to the
  tracer. The JNI trace did identify the only exception-like object in the
  window: the VM's own `java.lang.Exception` used for the stack walk, whose
  frames the launcher replaces. Whether `[state+0x20]` holds that exception,
  the `StackTraceElement[]`, or a different object is still open; the next
  diagnostic must read the object at the `IsSameObject` call.

## Planned run: jni-x20

- Purpose: name the non-null Java object that the `0x7e` handler reads from
  `[state+0x20]`. The handler calls `IsSameObject(x20, NULL)` at
  `libpairipcore+0x702e0` (return address `+0x702e4`) and stores the result at
  `+0x70448`/`+0x7044c`; when `x20` is non-null it also clears the slot and
  deletes the local reference. Every hosted run reaches this check with the
  flag at `1`. The `jni-trace` run falsified the pending-exception mechanism
  but could not see the argument. This run adds a debug-only `IsSameObject`
  hook to the existing JNI tracer and logs the raw object, the null argument,
  the result, and the class name only for the `+0x702e4` call site.
- Hypothesis: the slot holds one of the Java objects that the VM creates or
  receives in the preceding window. The trace makes the VM's own
  `java.lang.Exception` (`NewObjectA` at 33.887 and 34.076) and its
  `getStackTrace` result the leading candidates; the `Debug` class, a
  `StackTraceElement`, a `String`, or a `Long` are the alternatives.
- Change from the frozen baseline: the JNI tracer now also patches
  `JNINativeInterface.IsSameObject` (36 slots instead of 35) and logs
  `jni: IsSameObject <obj1> <obj2> -> <result> class=<class> caller=+0x702e4`
  when the caller is that handler. No game APK is modified, repacked, or
  re-signed; the probe is diagnostic only and must be removed before merge.
- Prediction: the tracer reports `jni: patched 36 slots`, and before the
  crash at least one `IsSameObject` record with `caller=+0x702e4` has
  `obj2=0x0`, `-> 0`, and a concrete `class=...`. The five globals, reader
  slots, crash signature, state/builtin tails, and every compared image byte
  remain at the frozen values; the image diff vs the prior crash image stays
  ASLR/heap/counter-only.
- Falsification: no `IsSameObject` record with `caller=+0x702e4` before the
  crash, or the non-null object cannot be named (`class=(pending)` or
  `class=object`). Then the probe is invalid or the slot is not a Java object
  the tracer can name, and this route to identifying `x20` is closed. A
  changed crash signature, reader slots, five globals, or tails makes the run
  inconclusive because the probe perturbed the path.
- Scope: naming the class identifies the source object but does not prove
  that stock's slot is null or different. A `java.lang.Exception` result
  would redirect the investigation to the stack-spoof/exception-slot path; it
  would not by itself explain the hosted-only gate.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=jni-x20`, code commit `62427ce`, APK sha256
  `0cf2353393eeb6a2aaed311d5b19e0311c08e9d76dba521def91463298661b92`
  (115,274,306 bytes), package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Build note: the ignored Frida gadget (`libaudiopolicy.so`) and its
  120-byte `libaudiopolicy.config.so` were moved out of `jniLibs` for this
  build and restored afterwards. The APK has the same 488 entries as the
  installed baseline; the only differing entry is
  `lib/arm64-v8a/libpairipshield.so` (477,744 bytes vs 477,056).
- Command:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_jni_trace true --es pairip_variant jni-x20
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/jni-x20.log
```

- Status: pre-registered; ready to run.

### jni-x20 (2026-10-27 23:27)

- Run key: `variant=jni-x20`, code commit `62427ce`, APK sha256
  `0cf2353393eeb6a2aaed311d5b19e0311c08e9d76dba521def91463298661b92`
  (115,274,306 bytes, 488 entries).
- Idempotency check: the walkthrough contained only the pre-registration for
  this run key; no verdict existed.
- Install: `android install` replaced the debug APK with the 488-entry build
  described in the pre-registration; the game APK was not touched.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 244` ms. Both
  packages were force-stopped first and the log buffer was cleared.
- Log: `tmp/cifi-apk/logs/verify/jni-x20.log`, 14,005 lines, sha256
  `00987a9039a3e6bd19ec7ac928980d31b0d762708e25cfd04a4dbdc5a027e3e0`.
- Instrumentation: line 398 reports `variant=jni-x20`, `preload=true`,
  `trace=true`, `jniTrace=true`, `dumpVmImage=true`; every other experiment
  switch is off. `jni: patched 36 slots in [0x7b51e19000, 0x7b51e1a000)
  prot=1` at 23:26:58.112, and `PairIP JNI trace: installed=true` before
  `libmain constructor` at 23:26:58.113.
- JNI result: 1,053 `IsSameObject` records. Every one of the 529 records at
  `caller=+0x702e4` is `0x0 0x0 -> 1`; no `class=` line exists at that site.
  The other 524 records are 468 at `+0x76cf0`, 52 at `+0x7aa80`, 3 at
  `+0x71eb8`, and 1 at `+0x79e84`. The last `+0x702e4` record is at
  23:26:59.057; the append dump follows in the same millisecond and the crash
  at 23:26:59.075.
- Crash: unchanged; signal 11 in `__memmove_aarch64_nt+140`, caller frame
  `split_config.arm64_v8a.apk +0x514`. `vm[0x540]` still holds
  `bbb9eb6daed20ed0` / `015370f87525d57b`.
- Rings: `state pos=44258`, tail `state[44257] id=0x872a21c sel=0x31bb`;
  `builtin pos=3944`, `builtin[3917] id=0x40b0007e pc=0x1f813
  lr=+0x6adc4`, tail `builtin[3943] id=0x9a0c0031 pc=0x40a73`;
  `vminsn[1579] fetch=0x1f811 next=0x1f813 op=0x7e`; `image write ring
  pos=2211`.
- Image: `/tmp/opencode/jni-x20-image.bin`, sha256
  `d474671d048b9fb877ec4f19e6d99bd6284f4e74cd346b250b9b12f9293a2cdd`.
  Diff vs `jni-trace-image.bin` is 30 ranges / 53 bytes; vs
  `apk-cd-map-image.bin` 35 / 81; vs the pristine asset 79 / 368. The five
  globals and both reader slots are byte-identical.
- Static follow-up: the dispatcher table at `+0x103ec` maps opcode `0x7e`
  to `0x6ff60`; the only direct `bl +0x31de4` is at `0x6adc0`, and `0x6ff60`
  does not call `+0x31de4` directly. The `0x1f813` builtin entry is therefore
  a nested or preceding helper event, not yet ordered against the `0x1f811`
  dispatcher entry.
- Verdict: the pre-registered prediction is **FALSIFIED** and the run is
  valid. `[state+0x20]` is null at every observed check; there is no Java
  object to name. The null branch is taken and the crash still follows.
- Findings updated: yes, commit `165b279`.

## Planned run: seq-7e

- Purpose: order the final dispatcher entry at `fetch=0x1f811 op=0x7e`
  (jump-table target `0x6ff60`) against the builtin entry
  `id=0x40b0007e pc=0x1f813` from the only direct `bl +0x31de4` at
  `0x6adc0`. The `jni-x20` rings have no common sequence, so their relative
  order is unknown.
- Change from the frozen baseline: a shared atomic `g_trace_seq` increments
  in both klB-filtered hooks and is stored in `VmRingEntry`/`BuiltinRingEntry`
  and printed as `seq=` on the existing ring lines. Two targeted `seqev D` /
  `seqev B` lines log the exact final events with the context pointer, image,
  fetch/next pc, cursor, link-register offset, and eight context qwords. No
  game APK is modified; the probe is diagnostic only and must be removed with
  the trace hooks before merge.
- Prediction: both `seqev` lines appear exactly once, share the same image
  pointer, and their sequence gap is at most 64. `seq(B) > seq(D)` means the
  builtin is nested after the dispatcher entry; `seq(B) < seq(D)` means it is
  the fetch/prep step before the dispatcher. Either way the final
  instruction is the same `0x7e` at `0x1f811` and `0x6ff60` is its handler.
- Falsification: either line is absent or duplicated, the image pointers
  differ, or the sequence gap exceeds 64. Then the builtin `pc=0x1f813`
  correlation is coincidental and the handler mapping must be re-derived.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=seq-7e`, code commit `9003ed9`, APK sha256
  `32dc845833094484cf3c05bab82bfebc4038d8cd575a4b4e39b1561425b984fa`
  (115,274,306 bytes, 488 entries), package `com.bepinex.android.debug` on
  SM-S931N (Android 17/API 37, `user`, `ro.debuggable=0`).
- Build note: the ignored Frida gadget (`libaudiopolicy.so`) and its 120-byte
  `libaudiopolicy.config.so` were moved out of `jniLibs` for this build and
  restored afterwards. The APK has 488 entries and differs from the installed
  baseline only in `lib/arm64-v8a/libpairipshield.so` (479,264 vs 477,744
  bytes).
- Command:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --es pairip_variant seq-7e
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/seq-7e.log
```

- Status: pre-registered; ready to run.

### seq-7e (2026-10-27 23:48)

- Run key: `variant=seq-7e`, code commit `9003ed9`, APK sha256
  `32dc845833094484cf3c05bab82bfebc4038d8cd575a4b4e39b1561425b984fa`
  (115,274,306 bytes, 488 entries; `libpairipshield.so` 479,264 bytes).
- Idempotency check: the walkthrough contained only the pre-registration for
  this run key; no verdict existed.
- Install: `android install` sent a 904.94 KB delta patch; the game APK was
  not modified, repacked, or re-signed.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 320` ms. Both
  packages were force-stopped first and the log buffer was cleared.
- Harness caveat: the log contains two launches, pid 1277 at 23:47:05 and
  pid 1664 at 23:47:24. The OS replayed the activity after the first crash,
  so one command produced two cold launches. Each launch has exactly one
  `seqev B` and one `seqev D` with the same ordering, so the verdict holds.
- Log: `tmp/cifi-apk/logs/verify/seq-7e.log`, 22,972 lines, sha256
  `64e3c2c6978d02995994fbb32e75adf1e21ca04f0a8fe763b257b463e5145433`.
- Instrumentation: line 561 reports `variant=seq-7e`, `preload=true`,
  `trace=true`, `dumpVmImage=true`; every other experiment switch is off.
- Ordering, launch 1: `seqev B seq=12787` with `id=0x40b0007e pc=0x1f813
  ctx=0x7ff9372b88 img=b400007b1224d010 lr=+0x6adc4`, then `seqev D
  seq=12788` with `state=0x7ff9372bd0`, `fetch=0x1f811 next=0x1f813
  cursor=0xb0 op=0x7e`, same image. Gap 1.
- Ordering, launch 2: `seqev B seq=12797` and `seqev D seq=12798`, same
  fields and image pointer. `vminsn[1579] seq=12798 fetch=0x1f811
  next=0x1f813 op=0x7e` is the same event as `seqev D`.
- Interpretation: the `0x1f813` builtin is the fetch/prep step
  (`+0x31de4` from `0x6adc0`) that precedes the dispatcher entry at
  `0x1f811`; `0x6ff60` is the handler for the final `0x7e`. The dispatcher
  state is the builtin context plus `0x48`.
- Nested event: `builtin[11244] seq=12824 id=0x9a0c0031 pc=0x40a73` runs
  after the dispatcher entry, inside `0x6ff60`, and writes the five globals.
- Crash: unchanged; signal 11 in `__memmove_aarch64_nt+140`, caller frame
  `split_config.arm64_v8a.apk +0x514`. `vm[0x540]` still holds
  `bbb9eb6daed20ed0` / `015370f87525d57b`.
- Rings, launch 2: `state pos=130579` (`state[130578] id=0x872a21c
  sel=0x31bb`), `builtin pos=11245`, `vm pos=1580`, `image write pos=2209`;
  `hash ring pos=0` and `router ring pos=0`.
- Image: `/tmp/opencode/seq-7e-image.bin`, sha256
  `4b992f2adcda9e4d0a6b36dd48d9aad26a7fea8755c4b7d9d4b0cdc50d7f84b6`.
  Diff vs `jni-x20-image.bin` is 34 ranges / 76 bytes; vs the pristine asset
  79 / 368. The five globals and both reader slots are byte-identical.
- Verdict: the pre-registered prediction is **CONFIRMED**. The `0x1f813`
  builtin precedes the `0x1f811` dispatcher entry by 1, both share the image,
  and the handler mapping to `0x6ff60` stands.
- Findings updated: yes, in this commit.

## Planned run: long-unbox

- Purpose: determine whether `klB` unboxes the `java.lang.Long` argument
  (the `libunity.so` split-APK base) through JNI. The current tracer hooks
  `GetObjectArrayElement` but not `GetLongField` or `CallLongMethodA`, so the
  `jni-trace` result cannot distinguish "argument unused" from "argument
  unboxed through an unhooked call".
- Change: add observation-only `GetLongField` and `CallLongMethodA` hooks to
  `pairip_jni_trace` (saved originals, `IsPairIpCaller` filter, receiver and
  return-value logging). No VM code, image, or argument value changes.
- Prediction: at least one `jni: GetLongField java.lang.Long.value:J` or
  `jni: CallLongMethodA java.lang.Long.longValue()J` record from a
  `libpairipcore` caller appears before the reader crash, with the
  `exec-prog` base or a value derived from it; all `seq-7e` invariants
  (crash, five globals, reader slots, ring positions, image diff) stay
  identical.
- Falsification: zero such records from `libpairipcore` callers before the
  crash. Then the argument is not consumed through JNI and the reader-slot
  source is elsewhere.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, `pairip_jni_trace=true`,
  debug launcher.
- Run key: `variant=long-unbox`, code commit `HEAD`, APK sha256 recorded
  after the build; package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Command:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_jni_trace true --es pairip_variant long-unbox
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/long-unbox.log
```

- Status: pre-registered; ready to build and run.

### long-unbox (2026-10-28 00:06)

- Run key: `variant=long-unbox`, code commit `d89fde3` plus the
  `jni_trace.cpp` `GetLongField`/`CallLongMethodA` hooks, committed with this
  record. APK sha256
  `f676f109c165a020feef3b132824556c52d1ed18c7021aea96860429991c9fca`
  (115,274,306 bytes, 488 entries; `libpairipshield.so` 480,736 bytes).
- Idempotency check: the walkthrough contained only the pre-registration for
  this run key; no verdict existed.
- Install: `android install` sent a 904.94 KB delta patch; the game APK was
  not modified, repacked, or re-signed.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 317` ms. Both
  packages were force-stopped first and the log buffer was cleared. One
  launch only (pid 8138 at 00:06:22), so no replay caveat this time.
- Log: `tmp/cifi-apk/logs/verify/long-unbox.log`, 26,235 lines, sha256
  `637f1569772a4ae09c095064ab69793f803f2b2ac4e58bab539ac87813ee9241`.
- Instrumentation: line 1,783 reports `variant=long-unbox`, `jniTrace=true`,
  `trace=true`, `preload=true`, `dumpVmImage=true`; every other experiment
  switch is off. YTw and klB both ran at 00:06:24.
- Argument: `vm-args: [0] java.lang.Long=0x79f72af000`, resolved by maps line
  32 to `r--p 04eec000` of `split_config.arm64_v8a.apk` (the `libunity.so`
  ELF base). Only the ASLR base differs from `seq-7e`.
- `GetLongField`: 0 records. `CallLongMethodA`: 2 calls, both
  `java.io.File.length()J` on `/proc/self/maps` and `/proc/self/status`,
  each returning 0. No `Long.longValue`, no `Long` field read, no method call
  on any `Long` receiver. The VM constructs 421 `java.lang.Long` objects from
  hash-like values but never unboxes them through JNI.
- Crash: unchanged. Signal 11 in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk +0x514`; `vm[0x540]` keeps
  `bbb9eb6daed20ed0` / `015370f87525d57b`; state tail `id=0x872a21c
  sel=0x31bb`; builtin tail `id=0x9a0c0031 pc=0x40a73`; dispatcher tail
  `fetch=0x1f811 op=0x7e`.
- Ring positions match `jni-trace`: builtin 3,699, state 41,366, vm 1,580,
  image write 2,211.
- Image: `klB-image-at-crash.bin` pulled to
  `/tmp/opencode/long-unbox-image-at-crash.bin`, sha256
  `f5d547c5ef9c8ffd471d06caa8e86c329724b181af039694b538f36a8ee234d5`.
  Five globals byte-identical to `seq-7e`, reader slots byte-identical to
  pristine, diff vs pristine 79 ranges / 368 bytes, diff vs `jni-trace` 29
  ranges / 53 bytes (ASLR pointer high bytes only).
- Verdict: the pre-registered prediction is **FALSIFIED**. The `klB`
  argument is not unboxed through JNI; the argument route is closed.
- Findings updated: yes, in this commit.

## Planned run: stack-strings

- Purpose: determine whether `klB` copies the fabricated anti-debug stack
  text into native memory before the reader. `libpairipcore` loads
  `GetStringUTFLength` (`#0x540`), `GetStringUTFChars` (`#0x548`), and
  `ReleaseStringUTFChars` (`#0x550`) at `+0x7dc14`/`+0x7dc30`/`+0x7f190`, in
  the same loop that extracts `StackTraceElement` objects, but the tracer
  never hooks the string readers.
- Change: observation-only `GetStringUTFChars` hook (saved original,
  `IsPairIpCaller` filter, 160-byte text and caller logging). No VM, image,
  stack-spoof, or switch changes.
- Prediction: at least one `jni: GetStringUTFChars` record from a
  `libpairipcore` caller returns a `pairIpUnityLaunchStack` frame or similar
  stack-walk text before the crash; all `long-unbox` invariants stay
  identical.
- Falsification: zero `GetStringUTFChars` records from `libpairipcore`
  callers before the crash, or only unrelated strings. Then stack text is
  not an input.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, `pairip_jni_trace=true`,
  debug launcher.
- Run key: `variant=stack-strings`, code commit `HEAD` plus the hook, APK
  sha256 recorded after the build; package `com.bepinex.android.debug` on
  SM-S931N (Android 17/API 37, `user`, `ro.debuggable=0`).
- Status: run; result below.

### Result

- Build: APK sha256
  `726786777431d86c7689533bca63a19c808e43a2b335da3eb69326c27195e235`
  (115,274,306 bytes, 488 entries; `libpairipshield.so` 481,376 bytes).
  `android install` sent a 904.94 KB delta; the game APK was not modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 228` ms. Both
  packages were force-stopped and the log buffer cleared first. One launch
  only (pid 12999 at 00:16:17).
- Log: `tmp/cifi-apk/logs/verify/stack-strings.log`, 30,053 lines, sha256
  `dac35b7ff6e3ca9a1da3e548cbedca35c295246d610106fc4190ea31e66cfd68`.
- Instrumentation: line 774 reports `variant=stack-strings`,
  `jniTrace=true`, `trace=true`, `preload=true`, `dumpVmImage=true`,
  `stackSpoof=true`; every other experiment switch is off.
- String reads: 48 `GetStringUTFChars` records, all from
  `caller=0x7a9013f814` (`libpairipcore+0x73814`). They are two copies of
  the 24 fabricated frames, for example
  `com.pairip.VMRunner.executeVM(Native Method)`,
  `com.pairip.VMRunner.invoke(VMRunner.java:69)`,
  `com.unity3d.player.NativeLoader.load(Native Method)`, and
  `com.unity3d.player.UnityPlayerActivity$c2020060322V3.onCreate(SourceFile:0)`.
  The VM writes the pointer and length into the VM image at hash-derived
  byte offsets (`[x19]` is the image base; `+0x73830` pointer,
  `+0x7383c` length; `w23=0x44feb` is the image size).
- Crash: unchanged. Signal 11 in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk +0x514`; `vm[0x540]` keeps
  `bbb9eb6daed20ed0` / `015370f87525d57b`; state tail `id=0x872a21c
  sel=0x31bb reader=0xf9372668`; builtin tail `id=0x9a0c0031 pc=0x40a73`.
- Image: `/tmp/opencode/stack-strings-image.bin`, sha256
  `89512e884c333b75fe9dfa1a062a009aab41e87c7bb68239079dd1e5292b064c`.
  Five globals byte-identical to `long-unbox`; reader slots byte-identical
  to pristine; diff vs pristine 79 ranges / 368 bytes; diff vs `long-unbox`
  30 ranges / 56 bytes (pointer-like values at unaligned offsets, low/mid
  and high bytes, plus small counters).
- Ring counters: builtin 3,548, state 39,572, vm 1,580, image write 2,209;
  final `seq=5,127`. These are 151 builtins / 1,794 state events lower than
  `long-unbox` (3,699 / 41,366, `seq=5,278`), so the VM path length varies
  run to run. The final tails and image are identical; the counters are not
  an invariant.
- Verdict: the pre-registered observation is **CONFIRMED** — the VM reads
  the fabricated stack text and writes it into its image. The crash is
  unchanged, so the text does not populate the reader slots by itself. The
  "ring positions identical" clause did not hold; counters are
  path-length-dependent.
- Findings updated: yes, in this commit.

## Planned run: stack-len-plus-one

- Idempotency check: searched this walkthrough for
  `variant=stack-len-plus-one`; no prior entry. The `stack-strings` run
  already has a verdict, but it did not change the frame text.
- Purpose: test whether the per-frame UTF-8 length stored by the
  stack-string handler at `+0x7383c` is consumed by the failing path.
- Change: in `pairIpUnityLaunchStack()`, change the `NativeLoader` method
  name from `load` to `loade` (one extra UTF-8 byte; 51 -> 52). No other
  spoof frame, switch, hook, or baseline change.
- Ruled-out check: this is not a repeat of `long-unbox` (JNI `Long`
  unboxing) or `stack-strings` (observe `GetStringUTFChars`). It changes
  the fabricated frame length, which no prior run varied.
- Prediction: the `GetStringUTFChars` log shows
  `com.unity3d.player.NativeLoader.loade(Native Method)` with length 52, and
  at least one of the five globals, the reader slots, the tails, or the
  crash image differs from `stack-strings`; the runtime length is an input
  to the failing path.
- Falsification: the new frame appears with length 52, but the five
  globals, reader slots, state/builtin tails, crash signature, and image
  diff (apart from ASLR pointer bytes) are byte-identical to
  `stack-strings`. Then the per-frame length is not consumed by the failing
  path and the stack-content route is closed.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, `pairip_jni_trace=true`,
  `stackSpoof=true`, `contextSeed=true`, debug launcher.
- Run key: `variant=stack-len-plus-one`, code commit `HEAD` plus the
  one-frame change, APK sha256 recorded after the build; package
  `com.bepinex.android.debug` on SM-S931N (Android 17/API 37, `user`,
  `ro.debuggable=0`).
- Command:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_jni_trace true --es pairip_variant stack-len-plus-one
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/stack-len-plus-one.log
```

- Status: run; result below.

### Result

- Build: APK sha256
  `98bf9c7c2f059302e547e728579d383788d96a0eb346fb23c47a0132228acf6f`
  (131,054,961 bytes). `android install` sent a 15.99 MB delta (87.21%
  savings); the game APK was not modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 224` ms. Both
  packages were force-stopped and the log buffer cleared first. One launch
  only (pid 24902 at 00:39:39).
- Log: `tmp/cifi-apk/logs/verify/stack-len-plus-one.log`, 25,867 lines,
  sha256 `0c99a19332368b22e121dfa25043e118f7ef7fbf0c15fb64da62d2b2bea013c3`.
- Change observed: `com.unity3d.player.NativeLoader.loade(Native Method)`
  (52 UTF-8 bytes) appears twice, once per stack walk, at
  `GetStringUTFChars` `caller=0x7a8cbc0814` (`libpairipcore+0x73814`). The
  run still has 48 `GetStringUTFChars` records, the same count as
  `stack-strings`.
- Crash: unchanged. Signal 11 in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk +0x514`; `vm[0x540]` keeps
  `bbb9eb6daed20ed0` / `015370f87525d57b`; state tail `id=0x872a21c
  sel=0x31bb reader=0xf9372668`; builtin tail `id=0x9a0c0031 pc=0x40a73`;
  dispatcher tail `fetch=0x1f811 op=0x7e`, next `0x1f813`.
- Image: `/tmp/opencode/stack-len-plus-one-image.bin`, sha256
  `28b923f54e2c5b02baf08ef2e5d0928936409df057b5eb66b84fee3121f77c9d`.
  Five globals byte-identical to `stack-strings`/`long-unbox`/`seq-7e`;
  reader slots `0x3f874`/`0x1ba5c` byte-identical to pristine and not
  touched by any traced image write; diff vs pristine 78 ranges / 367
  bytes; diff vs `stack-strings` 30 ranges / 51 bytes, but **zero**
  new-only offsets: every difference already varies across the older runs.
  The non-pointer `image+0xae3` counter is `0x21a`, inside the observed
  run-to-run range `0x215..0x21a`.
- Ring counters: builtin 3,729, state 41,720, vm 1,580, image write 2,211;
  final `seq=5,308`. They differ from `stack-strings` (3,548 / 39,572 /
  1,580 / 2,209, `seq=5,127`) as in earlier identical-input runs, so the
  difference is path-length variance, not an effect of the length.
- Verdict: the pre-registered prediction is **FALSIFIED**. The 52-byte
  frame reaches the VM, but no deterministic image, global, reader, or tail
  difference follows. The literal image-is-byte-identical clause fails only
  on the nondeterministic `0xae3` counter; with zero new-only offsets the
  falsification holds in substance. The per-frame runtime length is not
  consumed by the failing path, and the stack-content route is closed. The
  one-character spoof change was reverted after the run; the tree returns
  to the pre-experiment code state.
- Findings updated: yes, in this commit.

## Planned run: selector-gate

- Idempotency check: searched this walkthrough for
  `variant=selector-gate`; no prior entry. The two adjacent probes
  (`+0x32c20` router, `+0x3dc0c/+0x3dc50` hash) are different blocks and have
  verdicts; this run does not repeat either.
- Purpose: test the static claim that the string-hash selector gate at
  `libpairipcore.so+0x38420` produces the `sel=0x31bb` state selector seen at
  the crash, and capture the gate inputs (interned string, computed hash,
  expected word at `image+w23`, expected hash, candidates, hashed image
  range) to decide the next experiment.
- Change: add two debug-only observation hooks, both gated by
  `pairip_trace`, with no VM, image, spoof, or switch changes.
  `+0x383f4` (`ldr w9, [sp, #0x518]`) stashes the 16-bit hash-range length
  `w26` before the loop at `+0x38404` consumes it. `+0x38420`
  (`ldrsw x8, [x8, w23, uxtw]`) records `x19`/`strlen(x19)`, the string
  bytes, `x12` (FNV), the sign-extended word at `image+w23`, `x25`, the XOR
  of the two, `w29`/`w27` (equal/not-equal candidates), the hashed range
  (`x9 - image`, length), and the current VM `fetch`/`op`. The callback
  computes `eq` and `sel` exactly as `+0x3842c`/`+0x38430`; neither the
  `csel` nor the branch is hooked. Both shims preserve all registers, NZCV,
  and SP; the tail branches use dead scratch registers (`x11` at `+0x383f4`,
  `x9` at `+0x38420`).
- Ruled-out check: this is not a repeat of the `+0x32c20` router probe (which
  hooks the dispatcher return path, not the `+0x383f4` block) or the
  `+0x3dc0c` hash probe (a skipped block). It is observation-only, not a
  spoof or input change.
- Prediction: the hooks install (`rc=0`, non-null trampolines), at least one
  gate record is emitted before the crash, and the **last** gate record's
  `sel` equals the state-ring tail `sel=0x31bb`. The record has a non-empty
  interned string and a valid `len`/`off` image range. The recorded `eq` and
  string content are the new information; no crash-signature change is
  predicted.
- Falsification: zero gate records before the crash, the last gate record's
  `sel` differs from `0x31bb`, or either hook fails to install. Then the
  `+0x38420` gate is not the producer of the crash selector and the route is
  closed; move to the reader-slot writer without further gate probes.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, `pairip_jni_trace=true`,
  `stackSpoof=true`, `contextSeed=true`, debug launcher.
- Run key: `variant=selector-gate`, code commit `HEAD` plus the two hook
  shims and callbacks, committed with this record; APK sha256 recorded after
  the build; package `com.bepinex.android.debug` on SM-S931N
  (Android 17/API 37, `user`, `ro.debuggable=0`).
- Command:

```bash
android install launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bepinex.android.debug
adb shell am force-stop com.OctocubeGamesCompany.CIFI
adb logcat -c
adb shell am start -W \
  -n com.bepinex.android.debug/com.bepinex.android.BootstrapActivity \
  --es target_package com.OctocubeGamesCompany.CIFI \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
  --ez pairip_jni_trace true --es pairip_variant selector-gate
sleep 25
adb logcat -d -v threadtime > tmp/cifi-apk/logs/verify/selector-gate.log
```

- Status: pre-registered; ready to run.

### Result (2026-10-28 01:15)

- Build/install: debug APK `131,239,292` bytes, sha256
  `711c602db877bc65836f88951f785c4d9929bcd2b2a84a07a7e06e971a5affaf`;
  `libpairipshield.so` `486,088` bytes, 490 entries. `android install` sent a
  1.29 MB delta (98.97% savings); the game APK was not modified.
- Launch: `Status: ok`, `LaunchState: COLD`, `TotalTime: 221` ms, one launch
  only (pid 10464). Both packages were force-stopped and the log buffer was
  cleared first. Log `tmp/cifi-apk/logs/verify/selector-gate.log`, 22,477
  lines, sha256 `7e853d7b1cd00087abdb4385c801393ef3e2f9bbbd1114b5cd52f5b8588c3124`.
- Hooks: both installed. `gate-len hook rc=0 target=0x7a9b54c3f4
  trampoline=0x7e1e46d250`; `gate hook rc=0 target=0x7a9b54c420
  trampoline=0x7e1e46d26c`; base `0x7a9b514000`. The other observation hooks
  also installed (`rc=0`), so the failure is not an install error.
- Gate ring: `pos=0 eq=0 ne=0`. No `gate:` record and no first-`ne` record
  was ever emitted, so the `+0x383f4`/`+0x38420` block does not run on the
  crashing path at all. The hash ring and router ring are also empty
  (`pos=0`, `eq=0`, `ne=0`); this agrees with the earlier `hash-gate` and
  `state-router-retry` results.
- State tail: `state ring pos=39158`, last
  `state[39157] id=0x872a21c sel=0x31bb reader=0xf9372668`. The carried
  selector is unchanged. Builtin ring `pos=3513`, VM ring `pos=1580`, image
  write ring `pos=2211`; these counters are within the known path-length
  variance and are not invariants.
- Crash: unchanged. Signal 11 in `__memmove_aarch64_nt+140`, caller
  `split_config.arm64_v8a.apk+0x514`; `vm[0x540]`=`0x00000000f9372668`,
  `+0x544`=`0x0000007ff93726f9`, `+0x548`=`0xbbb9eb6daed20ed0`,
  `+0x550`=`0x015370f87525d57b`; `vm[0x7f8]`=`0x31bb`. The append record is
  `src=0x7ff9372630 n=46 dst=0x7ff9372698 lr=0x7a9b552170 off=0x3e170
  fields=0000007a9b5a7ce8,0000000000000000,bbb9eb6daed20ed0,015370f87525d57b
  mapped=0`; the reader object still uses slot A as data and slot B as size.
- Crash image: `/tmp/opencode/selector-gate-image.bin`, sha256
  `952fa36e4d60dfbbc87334f8c65065bbc6d34f7f6befed39e4bb3db688aa0bfa`.
  Diff vs `stack-len-plus-one` is 31 ranges / 58 bytes, but there are **zero**
  new-only differing offsets: every difference already varies across the
  older runs. The five globals and both reader slots are byte-identical to
  every prior image.
- Verdict: the pre-registered prediction is **FALSIFIED**. The hooks install
  but the gate block records no execution; the last gate record does not
  exist and cannot equal `0x31bb`. The `+0x38420` string-hash selector gate is
  not the producer of the crash selector, and the route is closed. No further
  probes at `+0x38420`, `+0x32c20`, `+0x3dc0c`, or `+0x3dc50` are warranted.
- Findings updated: yes, in this commit: the old claim that `+0x32c20`
  produces `sel=0x31bb` is corrected, and the selector-gate falsification is
  recorded.

### 2026-10-28 — static decode: the state ring is not a selector (no run)

- Trigger: selector-gate verdict (zero gate hits) and the review of
  `pairip_state_trace_c`. No device launch; this is a read-only disassembly
  pass.
- Evidence: `/tmp/pairip-analysis/libpairipcore_full.asm`, 5,456,678 bytes,
  sha256 `21647fd96fc093e400a6fce79a873f59e9fc0c34e25a994fb63bbb49603380d1`;
  ring-field code in `launcher/app/src/main/cpp/pairipshield/shield.cpp`
  (`state=regs[10]`, `selected=regs[5]`, `reader=regs[9]`, hook target
  `+0x32c6c`).
- Dispatcher: `+0x31de4` is the interpreter body called from `+0x6adc0`;
  the epilogue is `+0x56c54`. At `+0x32c6c` `w10` is the next state and
  `w9`/`w29` is the carried value. `w5` is not assigned by the dispatcher;
  it is caller residue.
- `+0x32c54` computes `w5 = (w21 ^ csel) % w19`, an image index. The
  `0xe36e031a` handler (`+0x32e1c..+0x32e34`) stores that `w5` to
  `[[sp+0x538]+8]+0xc`, sets `w9=1`, `w10=0x7ae3f9a6`, and re-enters the
  dispatcher. `+0x332b8` sets `w5=-9` on a different branch. The "only at
  `+0x32c54`/`+0x332b8`" shorthand in the checkpoint is too strong; those
  are the two assignments relevant to this path, not the function's only
  `w5` writes.
- Termination: `0x7ae3f9a6` is compared at `+0x532d4..+0x532e4` and branches
  to `+0x56c54`, which returns `[sp+0x540] & 1`. It has no successor.
- JNI: `+0x6a968..+0x6b3c4` (`executeVM`) never references `x5`/`w5`, so
  the ring's `selected` value is not part of the JNI contract.
- Ring interleaving: the ring is global. In the selector-gate log,
  `state[39148] id=0x7ae3f9a6 sel=0x22b39 reader=0x1` is the inner
  interpreter's termination; `state[39149] id=0x81398368 sel=0x31bb
  reader=0xf9372668` is the outer interpreter resuming. Adjacent entries are
  not a transition. The earlier "0x81398368 entered from 0x7ae3f9a6" claim
  is withdrawn.
- Reader: `+0x3dc88` loads the descriptor at `[sp+0x538]`, stores `w29` to
  `[sp+0x540]`, loads `x16` (reader object) and `w9` (descriptor `+0x10`,
  observed `0xb0`), then reads the image base from `[x16]` and the size from
  `[x16+8]`. The append helper call at `+0x3e16c`/`+0x3e170` consumes the
  image-backed string view. The append record's non-canonical base
  (`bbb9...`) is the missing writer, not a selector effect.
- Verdict: `sel=0x31bb` has no producer to find; it is not a state selector.
  The producer route is closed. The next experiment tests the only untested
  identity combination, then moves to the reader writer if that is
  falsified.
- Findings updated: yes, in this commit: corrected the `0x81398368` entry
  claim and appended the static map to
  [`cifi-pairip-findings.md`](cifi-pairip-findings.md).
