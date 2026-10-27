# Handoff: CIFI PairIP klB crash - session 2 (2026-10-27 19:25 KST)

Purpose: continue the hosted CIFI PairIP investigation on another machine.
This is a delta snapshot. The living record is
[`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md); the append-only
run log is [`docs/cifi-pairip-walkthrough.md`](../cifi-pairip-walkthrough.md).
The earlier cross-machine snapshot is
[`docs/handoffs/2026-10-27-cifi-pairip.md`](2026-10-27-cifi-pairip.md); read it
for the full crash and harness background, then read the current findings.

## State to pull

- Branch: `test/feat-cifi`. Pre-handoff tip: `53b4842` (docs-only). The
  handoff and findings update are committed on top of that; use `git log` for
  the exact hash after pulling.
- Working tree: clean after this handoff. No code changes in this session,
  and no new device run.
- Last accepted device run: `process-name-spoof`, code commit `defa262`,
  APK sha256
  `9aa20aa3e3481fb3e1cde83729df34bb17b145d9ef5a1ce2eb411970c576b892`
  (115,104,390 bytes), installed `lastUpdateTime=2026-10-27 17:53:23`,
  package `com.bepinex.android.debug` on SM-S931N (Android 17/API 37, `user`,
  `ro.debuggable=0`).
- Baseline flags: `pairip_preload=true`, `pairip_trace=true`,
  `pairip_dump_vm=true`, debug launcher, all other experiments off.
- Frozen crash: SIGSEGV in `__memmove_aarch64_nt+140`, caller
  `libpairipcore.so+0x8d514`; reader slots `bbb9eb6daed20ed0` /
  `015370f87525d57b`; state tail `id=0x872a21c sel=0x31bb
  reader=0xf93727d8`; builtin tail `id=0x9a0c0031 pc=0x40a73`.
- Latest artifacts (gitignored, so re-capture on the new machine):
  `tmp/cifi-apk/logs/verify/process-name-spoof.log` (sha256
  `f4916b3246da0545514f1988abfa212b2957af9fe3d0a4d091d02e11268ec45d`),
  crash image `tmp/cifi-apk/vmrun/klB-image-at-crash-pns.bin` (sha256
  `a4db6ad23e750685d31c33c308ac34ae9b16b98544fdb16636ae92165e391158`),
  pristine asset `tmp/cifi-apk/vm-assets/klB1wZSojiVvDvCN` (282,603 bytes,
  sha256 `112b6e59...`).

## What changed this session

No device experiment. Static disassembly and frame reconciliation only; the
details and exact addresses are in
[`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md), section
"Builtin id provenance (static follow-up, 2026-10-27 19:20)".

Three facts matter for the next agent:

1. The high 16 bits of the builtin id are inert. `+0x31de4` masks `w1` with
   `0xffff` at entry and only tests `w1 & 0xfffc`; the original `x1` is not
   spilled, so the high half is gone once `w1` is reused.
   All 128 logged ids have a low half in `0x04..0xb5`, while the high half is
   a block tag (`0x0`, `0x34e1`, `0x52d8`, `0x40b0`, `0x40c`, `0x1124`,
   `0x9a0c`). `0x9a0c0031` is opcode `0x31` with an irrelevant `0x9a0c` tag.
   Do not spend another run chasing the full 32-bit value.
2. The slot is `[0x6a968.sp + 0x44]`, which is at dump offset `0x6ac` after
   the VM-dump frame mapping (`arg = 0x6a968.sp + 0x78`, arg block at dump
   `0x6e0`). The crash-time value there is `0x7a`, but the entry value was
   `0x9a0c0031`; the slot is rewritten after entry while the reader is being
   built. The crash-time value is not the value that selected the builtin.
3. The only static writer at `+0x6ad70` produces the `0..0xb9` selector. It
   is consistent with the low half but not with the tag. The tag writer is
   still unknown and is lower priority because the builtin ignores the tag.

Also corrected in the record: "stock computes the same five globals" is an
inference from the static FNV input, not a stock-side capture. The final five
globals are written by the `0x7e` handler at fetch `0x1f811`, which runs
immediately before the crashing `0x31` call.

## Proven and not proven

Proven on the hosted path:

- Every logged/ringed `+0x31de4` call enters at `+0x6adb4` with
  `lr=+0x6adc4`. The trace does not hook `+0x5ef50`, so it cannot say
  whether the `+0x6add8` path ran.
- The reader is unconditional for the effective opcode `0x31`: state
  `0x629d5907` trampolines via `+0x384ac` to `0x0872a21c`, which branches
  directly at `+0x34d74` to `+0x3dc88`.
- The image slots `image+0x3f874` / `image+0x1ba5c` are byte-identical to the
  pristine asset at the crash; no hosted write touches them.
- The pristine pair (`0x31c1b` / `0x3b302`) is also non-pointer asset bytes,
  so the shipped image cannot satisfy the reader without a runtime write or a
  different branch.
- Environment changes (maps content, uid/gid, process name, offset-0 APK
  mapping) do not move the five globals or the crash.

Not proven:

- Whether stock's final five globals are the same. The FNV input is static,
  but the `0x7e` handler at `0x1f811` has no stock-side capture.
- Whether stock patches `0x3f874` / `0x1ba5c` before the reader, or never
  reaches state `0x872a21c` / the `0x31` call.
- The exact site that writes the id tag. It is inert, so this is not on the
  critical path.

## Open fork

The reader block is deterministic given the image, the bytecode pc, and the
five globals. In hosted the slots are asset ciphertext, so the reader crashes.
Stock must therefore do one of:

1. write/relocate the selected slots before the reader;
2. compute different five globals that select a different pair; or
3. take a different branch before the `0x31` call.

The environment branch for (2) is closed. The hosted image-write ring found no
writer for (1). That leaves a stock-only initializer or a different entry
path. The next experiment must discriminate those, not re-test maps/status/
name or the router/hash blocks.

## Candidate next runs (pre-register exactly one)

Do not run any of these without writing the pre-registration into the
walkthrough first, with commit, APK sha256, exact command, and a falsifiable
outcome. Use `android install` (delta), not `adb install`; use `--el` for
`pairip_delay_ms`.

A. Clean native-order probe. Frozen baseline plus
   `--ez pairip_patch_unity_init true`. This patches the game's `libunity`
   `DT_INIT` to return (the launcher's existing option) and changes one
   native-order variable. Falsification: same crash signature, reader slots,
   five globals, state/builtin tails, and image diff. This is the cheapest
   remaining one-variable test; it does not prove stock's slot initialization.
   Key suggestion: `patch-unity-init`.
   Command:

   ```bash
   OUTDIR=tmp/cifi-apk/logs/verify WAIT_S=25 \
     tmp/cifi-apk/run_variant.sh patch-unity-init \
     --ez pairip_preload true --ez pairip_trace true --ez pairip_dump_vm true \
     --ez pairip_patch_unity_init true
   ```

B. Targeted reader-slot probe. Add a debug-only hook at the first load in
   `+0x3e030` (or at `+0x3dc88`) that records the two slot addresses, their
   current 8-byte values, and the bytecode pc before the reader calls the ZIP
   parser. This proves whether the hosted slot is ever valid at the moment of
   use and ties the value to the state that selected it. It does not observe
   stock, so it cannot by itself distinguish (1) from (3). Prefer this only if
   A is falsified and a slot-value timeline is needed.

C. Stock-side capture. There is no root and the installed game is not
   debuggable, so do not modify, repack, re-sign, or reinstall the game APK.
   A stock capture needs a legitimate debuggable copy or a vendor-provided
   trace; otherwise treat stock behavior as an inference and say so.

Recommended order: A, then B if A is falsified. If both fail, the remaining
evidence points at a stock-only native initializer; in that case inspect the
`JNI_OnLoad` / `executeVM` entry path and the Java-side `VmDecryptor` /
`VMRunner` contract rather than iterating on the klB bytecode state.

## Constraints (unchanged)

- No root; production `user` build. Never modify, repack, re-sign, or reinstall
  the installed game APK.
- Do not patch `memmove`, fake the CD reader pointer in a shipping path, forge
  signatures, extract keys, or bypass `LicenseClient`.
- One variable per run against the frozen baseline; force-stop between runs;
  record key, commit, APK sha256, command, window, artifacts, and verdict.
- Non-PairIP targets must be unaffected; experiment switches default off.
- Diagnostics are not fixes. Before merge remove the debug switches,
  `pairip_allow_nondebug_experiments`, Frida gadget / DiagnosticApp / sleeps,
  and stale `pairip_desc_trace` / `pairip_branch_trace` declarations.
- Update the findings doc and walkthrough before/after each run. Commit docs
  locally; do not push without explicit approval unless the user asks for a
  handoff push (this request does).

## Suggested skills

Call the Skill tool for:

- `diagnosing-bugs` - continue the falsifiable one-variable loop and keep the
  evidence/verdict discipline.
- `android-cli` - install, launch, force-stop, and capture logs on the device
  with the `android` CLI if the local helper scripts are unavailable.
- `handoff` - if the work moves machines again; write a delta and reference
  this file and the findings doc instead of duplicating them.
- `code-review` - before merging a real fix, once a run passes three cold
  launches and the non-PairIP regression checks.

## Quick orientation

- Findings: [`docs/cifi-pairip-findings.md`](../cifi-pairip-findings.md)
- Run log: [`docs/cifi-pairip-walkthrough.md`](../cifi-pairip-walkthrough.md)
- Earlier snapshot:
  [`docs/handoffs/2026-10-27-cifi-pairip.md`](2026-10-27-cifi-pairip.md)
- Hosted launcher entry:
  `launcher/app/src/main/java/com/bepinex/android/BootstrapActivity.kt`
  (`:game` activity, preload flow, `patchUnityInit` around line 1595).
- Experiment switches:
  `launcher/app/src/main/java/com/bepinex/android/hooks/PairIpExperiment.kt`.
- Probes/rings:
  `launcher/app/src/main/cpp/pairipshield/shield.cpp` and
  `launcher/app/src/main/cpp/pairipshield/trace_hook.S`.
- Disassembly: `tmp/cifi-apk/analysis/libpairipcore.asm`,
  `/tmp/opencode/fn_6a968.asm`, `/tmp/opencode/fn_31de4.asm`.
- Reader emulator: `/tmp/opencode/emu_crash.py` (accepts `IMG`; seed `0xb0`,
  cursor `0x40a73`).
- Cold-launch helper: `tmp/cifi-apk/run_variant.sh`.
