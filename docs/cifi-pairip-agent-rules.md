# Agent rules for the CIFI PairIP investigation

Binding rules for any agent working on the CIFI hosted-launch
investigation (issue #56 and tickets #57–#64). These rules say
*how* to work. *What* is known, what is ruled out, and what to
try next live in the findings doc — never in a chat prompt and
never copied into this file as a snapshot.

This document lives on its own branch so that writing it does
not disturb the agent working on `test/feat-cifi`. Merging it,
and where it ultimately lives, is the maintainer's decision.

## Precedence

If sources conflict, stop and report the conflict instead of
silently choosing one:

1. An explicit instruction from the user in the current session.
2. The acceptance text of issues #56–#64.
3. These rules.
4. `docs/cifi-pairip-findings.md` and
   `docs/pairip-isolation-harness.md` (facts and baseline).

Chat history, a previous prompt, and a previous agent's summary
are never a source of truth. They are claims to verify against
the sources above.

## 1. Source of truth and document ownership

| Document | Owns | Rule |
| --- | --- | --- |
| Issues #56–#64 | Spec and acceptance criteria | Read the issue text; do not restate it in new docs. |
| `docs/cifi-pairip-findings.md` | Proven facts, ruled-out items, open questions, current state, next move | The living record. Update it when a fact changes. |
| `docs/cifi-pairip-walkthrough.md` | Append-only run log | One entry per run; see §6. Create it if missing. |
| `docs/pairip-isolation-harness.md` | Frozen baseline and cold-launch definition (#57) | Do not change the baseline without a new verification entry. |
| `docs/handoffs/*.md` | Point-in-time snapshots | Read only. Never edit a snapshot. |
| `docs/adr/*.md` | Mechanism decisions (#60) | Write an ADR only after a mechanism is proven. |
| This file | How agents must work | Amend only by a committed change to this file, with the reason recorded. |

Next moves, ruled-out items, and findings must not live in a
chat prompt, in an agent's private notes, or in `/tmp/`. If a
fact exists only in chat or in `/tmp/`, it does not exist for
the next agent until it is written into the findings doc or
the walkthrough and committed.

## 2. Session start — every agent switch

Run this protocol first, every time, before any code change or
device run — even if the previous agent said it was already done.

### Phase 0: state sync (parallel, read-only only)

Use background tasks and subagents for the independent
read-only workstreams below. Each subagent must be read-only:
no code edits, no commits, no installs, no launches, no logcat
captures, no device interaction. Each returns evidence with
exact references (file path + line, issue number, commit hash,
sha256). The coordinator merges the results and writes the
walkthrough pre-flight entry. No experiment starts until
Phase 0 is merged.

- **Git:** `git fetch origin`; current branch and tip;
  `git status` (staged, unstaged, untracked); diff stat;
  compare the working branch with the other remote PairIP
  branches. List every uncommitted file and preserve it.
  Never check out a clean tree over uncommitted work. Record
  the tip hash as observed; never assume a tip recorded in an
  earlier session is still current.
- **Issues:** read #56 and #57–#64, including all comments.
  Record each issue's state, labels, acceptance text, and
  newest comment or handoff.
- **Docs:** the findings doc, the harness doc, every handoff
  doc, the walkthrough if it exists, plus `GLOSSARY.md` and
  any ADR. Note which doc is newest and any contradictions
  between them.
- **Inventory:** every file and artifact the findings doc
  references, as actually present on this machine. Record
  existence and sha256 for each. Anything referenced but
  absent is UNVERIFIABLE, not absent from history. Verify
  installed APK hashes against the values in the findings doc;
  if they differ, stop and report.
- **Claim verification:** treat every claim from a previous
  summary or handoff as unverified. Cross-check each material
  claim against the sources above and classify it
  CONFIRMED / CONTRADICTED / UNVERIFIABLE. If an artifact is
  missing, a hash differs, or the tip moved, stop and report
  exactly what differs. Do not re-derive missing evidence
  silently.

### Phase 1: verify and analyze (still before any experiment)

Reconcile every contradiction found in Phase 0, updating the
findings doc only for facts that actually changed. Freeze the
harness baseline from the harness doc. Load the ruled-out list
and the open questions from the findings doc and the
walkthrough verdicts. Then choose exactly ONE next
discriminating step, derived only from the current open
questions: the step with the highest discriminating power per
confounding variable, changing one variable against the frozen
baseline, with a falsifiable expected outcome stated before
the run. Record why that step was chosen and why the others
were deferred. If no open question remains and the success
criteria are met, do not invent work; report the completion
check instead.

## 3. Idempotency

Running the session-start protocol again, on the same state,
must converge to the same state. Check before acting, and cite
existing evidence instead of redoing it:

- State sync never mutates the repo, the docs, or the device.
- Git: if the branch already exists, do not create it. If the
  working tree is clean, do not commit. If a commit already
  contains a change, do not re-commit it. If a change is
  already pushed, do not push it again.
- Files: create a file only if it does not exist. Append only;
  never rewrite, reorder, or delete earlier entries. Change a
  line in a living doc only when its fact changed, and change
  only that line.
- Walkthrough: before adding an entry, search for the same
  run key (see §6). If a verdict for that run key already
  exists, do not run it again; cite the existing entry.
- Device: before installing, check the installed APK's
  version/hash and skip the install if it is identical.
  Do not launch a run whose run key already has a verdict.
- Findings: if a fact is already recorded correctly, leave
  it alone. One row per fact, one verdict per run.
- A re-run with no state change must end with a
  "no-op: state unchanged" report, not with new entries, new
  commits, or new runs.

## 4. Evidence discipline

- Verify with real evidence (commit hashes, log excerpts with
  file paths, sha256 digests, device measurements) before
  asserting a fact. Do not guess, and do not present a
  hypothesis as a finding.
- A step that merely moves the crash to a later stage is not
  success. Success is Unity initialization and the title
  screen, then the acceptance criteria of #62–#64.
- Determinism across hosted runs alone is not evidence of an
  environment input: instruction-derived values are also
  deterministic in the hosted process. State what a result
  does and does not prove.
- Instrumentation is not neutral. Any result obtained with
  tracing on must be confirmed with tracing off before it is
  treated as the behaviour of the uninstrumented path.
- If a run contradicts the findings doc, the contradiction
  itself is the finding: record it before proceeding.
- Use the vocabulary in `GLOSSARY.md`: "hosted launch" and
  "bare launch"; PairIP is "the integrity runtime". Avoid
  "stock launch", "vanilla launch", "anti-cheat", "DRM",
  and "anti-tamper".

## 5. Hard constraints

From #56 and the handoff; verify against the issue text:

- No root. The device is a production `user` build
  (`ro.debuggable=0`). The unhosted game process cannot be
  inspected; infer its behaviour statically and from hosted
  traces.
- Never modify, repack, re-sign, or reinstall the installed
  game APK. Keep the Play-installed copy.
- Non-PairIP games must remain unaffected. All experiment
  switches stay off by default and are gated to PairIP
  packages.
- Do not patch `memmove`, fake a reader pointer in a shipping
  path, forge signatures, extract keys, or bypass
  `LicenseClient`. Do not pre-seed image offsets or forge a
  reader pointer. Diagnostic interventions (including
  fake-CD and reader-guard work) must not ship as the fix.
- Before merge, remove debug-only instrumentation,
  `pairip_allow_nondebug_experiments`, the Frida gadget,
  `DiagnosticApp`, sleeps, and stale experiment declarations,
  per #64. Debug-only extras must be inert in a
  non-debuggable build.

## 6. Experiments and the walkthrough

- One variable at a time against the frozen baseline.
- Every experiment gets one walkthrough entry BEFORE it runs
  and one verdict AFTER, in this format:

  ```markdown
  ## <date> <time> — <short name>
  Run key: <commit + exact command + toggles + build variant>
  Idempotency check: <existing entry searched, result>
  Hypothesis: <what this run discriminates, in one sentence>
  Ruled-out check: <which ruled-out items were checked, and
    why this run is not a repeat of one>
  Starting state: <commit hash, uncommitted files, build variant>
  Command: <exact cold-launch command, all extras, --el usage>
  Expected discriminator: <what outcome proves which branch>
  Log / artifact: <exact log file path and sha256 if applicable>
  Observed: <crash signature or title screen / progress>
  Verdict: PROVEN / RULED OUT / INCONCLUSIVE, with the evidence
  Findings updated: yes/no, and the commit that updated the
    findings doc
  ```

- A ruled-out experiment may be re-run only when the
  walkthrough entry states the new evidence justifying it,
  and the run gets a new run key reflecting what actually
  changed. "It might work this time" is not justification.
- Experiment code stays behind debug-only flags, separable
  from shared launcher flow. Do not commit experiment code
  until the setup it depends on is understood and the harness
  is verified.
- Only after a real mechanism is proven: apply a minimal
  gated fix, run three cold launches, verify no regression
  for the non-PairIP titles, then update the findings doc,
  the ADR (#60), and tickets #61–#64.

## 7. Device discipline

- Use the Android CLI skill (`@android-cli`): read its
  `SKILL.md` and `references/interact.md` before using
  `android layout` or `android screen`. Use
  `android install --apks=<apk>` (delta install) for
  installs, never `adb install`. Use `android info` to
  confirm the connected device before any install.
  `android layout` / `android screen` verify the title
  screen once the crash is cleared; they are not crash
  diagnosis. Do not use `android run`: launches go through
  the frozen harness launch command.
- Cold launch, as defined in the harness doc: force-stop
  the launcher packages and the game, confirm no process
  remains, clear logcat, start `BootstrapActivity` with the
  exact intent, wait the fixed interval before capturing.
  Pass `pairip_delay_ms` with `--el`, because `--ez`
  truncates it. `BootstrapActivity` is `singleTop`: do not
  launch while a `:game` process from another session is
  running.
- The phone, the `:game` process, logcat, installs, and the
  branch are single shared resources. All device work is
  serialized by the coordinator: one cold launch at a time,
  one variable at a time, force-stop between runs. No
  parallel installs, launches, or logcat captures, and no
  parallel subagent may touch the device or the working
  tree.

## 8. Parallelism

Parallel work is for read-only analysis only (see Phase 0).
Subagents report evidence back to the coordinator; the
coordinator is the sole writer to the walkthrough, the
findings doc, git, and the device. A subagent that needs a
device result waits for the coordinator; it does not run its
own launch.

## 9. Branch, commit, and push discipline

- Work only on `test/feat-cifi`. Do not create new branches,
  worktrees, or tickets, and do not merge, without explicit
  maintainer approval. (This rules document is the deliberate
  exception: it was written on a separate branch so as not to
  disturb work in progress on `test/feat-cifi`.)
- Commit locally; do not push without explicit approval.
- Keep commits scoped: code, docs, and scratch-ignore
  changes go in separate commits. Never commit pulled APKs,
  device logs, or analysis scratch: the repo-local `tmp/`
  workspace is gitignored, and only digests belong in the
  repository.
- Do not touch `main`. Do not stash, clean, or rewrite
  another agent's working tree.

## 10. Reporting

- Report in English, ending with a one-paragraph Korean
  summary. Lead with the discriminating result, not the
  procedure.
- Each report states: the single step chosen and why, the
  exact discriminating result, the walkthrough entry and
  findings update (or "no-op: state unchanged"), and the
  single next open question derived from the updated
  findings doc.
- Never claim a step is verified until the evidence in §4
  supports it, and never present a new inference as what
  happened in an earlier session.

## Appendix: agent-switch command

Paste this verbatim at the start of every new agent session.
It is intentionally short: all content lives in the repo docs
above, not in the command.

```text
@implement-spec @android-cli

Continue the CIFI hosted-launch work from the repo, not from chat history.
Goal: CIFI (com.OctocubeGamesCompany.CIFI) launches with the BepInEx
Android launcher without a crash — title screen, chainloader complete,
interop/ generated, no libpairipcore SIGSEGV across three cold launches,
no regressions for The Tower / Revolution Idle / Unlock!.

This command is idempotent: re-running it on unchanged state must
not duplicate entries, commits, installs, or runs. Check before
you act; cite existing evidence instead of redoing it.

1. Sync state first, in parallel read-only subagents (no edits,
   no commits, no installs, no launches, no device interaction):
   git (fetch, branch test/feat-cifi, tip, status — preserve
   uncommitted files); issues #56-#64; docs/cifi-pairip-findings.md;
   docs/cifi-pairip-walkthrough.md (create if missing, never rewrite);
   docs/pairip-isolation-harness.md; docs/handoffs/ (snapshots only).
   Follow docs/cifi-pairip-agent-rules.md. Verify every claim from
   the last summary/handoff against those sources; classify
   CONFIRMED / CONTRADICTED / UNVERIFIABLE. If a referenced
   artifact is missing or a hash differs, stop and report.

2. Derive exactly ONE next step from the open questions in the
   findings doc: one variable against the frozen baseline, with
   a falsifiable outcome stated before the run. Check the
   walkthrough run key first: if that run already has a verdict,
   do not repeat it — cite it.

3. Use the android-cli skill: android install (delta) for
   installs, android info to confirm the device; android
   layout/screen only to verify the title screen. Device work
   is serialized: one cold launch at a time, force-stop between
   runs, --el for pairip_delay_ms.

4. Before and after the run, update the walkthrough and the
   findings doc in the repo: append-only, change only facts
   that changed, commit locally. Do not push, merge, or create
   branches/tickets without approval. Do not put next moves or
   ruled-out items in chat or in this command — they live only
   in the repo docs above.

Hard constraints: no root; never modify/repack/reinstall the
game APK; non-PairIP games unaffected; no shipping diagnostic
bypasses; remove debug-only extras before merge per #64.

Report in English, end with a one-paragraph Korean summary.
If nothing changed, report "no-op: state unchanged".
```
