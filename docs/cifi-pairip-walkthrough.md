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

- Status: pre-registered; not yet run.
