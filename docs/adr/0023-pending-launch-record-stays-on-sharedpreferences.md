# The pending-launch record stays on SharedPreferences

Status: accepted (2026-10-23). Revisit if the record grows or startup changes shape — see below.

Current Android guidance recommends DataStore over SharedPreferences, and
the launcher already follows it for settings (`AppSettings` is a
Preferences DataStore). The one remaining first-party SharedPreferences
file is `crash_diagnostics`, which holds the pending-launch record used by
the crash-recovery flow (ADR-0022). This was reviewed when ADR-0022 added
the launched-modpack-names key to that record, and the decision is to keep
it on SharedPreferences deliberately rather than migrate it for
consistency alone:

- The record is tiny and single-purpose: target package, launch time,
  launched modpack names. It is not settings and never grows a schema.
- It is read synchronously during startup, before any UI coroutine scope
  exists, to decide whether the previous launch ended abnormally and the
  recovery dialog should be offered. DataStore's first read is
  asynchronous (`Flow`), so migrating makes this decision coroutine-bound
  for no functional gain.
- It is written immediately before the launcher hands control to the
  hosted game process (`markLaunch`), on a path where the write must be
  durable before the process can die. The synchronous SharedPreferences
  API expresses that directly.

Revisit this decision if any of the following happens: more state accretes
onto the pending-launch record, startup is restructured around
asynchronous initialization (making the DataStore read free), or
SharedPreferences is formally deprecated. The `fusion/` references to
SharedPreferences are unrelated — they are interop shims for the hosted
game's own preferences APIs, not launcher storage.
