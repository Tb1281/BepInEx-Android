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
  while `image+0x266f2` is in the observed loop ranges; dump at counter `1`.
- Frozen baseline: `variant=default`, `pairip_preload=true`,
  `pairip_trace=true`, `pairip_dump_vm=true`, debug launcher.
- Run key: `variant=fnv-input`, code commit `TBD`, APK sha256 `TBD`.
- Command:

```bash
OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25 \
  tmp/cifi-apk/run_variant.sh fnv-input \
  --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true
```

- Status: planned; no verdict yet.

## Verdict log

Append one entry per completed run; never rewrite an earlier verdict.
