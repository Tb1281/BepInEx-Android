# Architecture & Responsibility Boundaries

Games run inside an auxiliary host process, so identity, signatures, and
sandboxes diverge from standalone execution.

## 1. Classification

| Layer | Meaning |
|---|---|
| Launcher | Caused by host-process isolation, context re-pointing, or redirection. |
| Mod | Game logic, interop patches, gameplay modifications. |
| Architecture | OS or remote cryptographic guarantees unsatisfiable without the official signing key. |

## 2. Responsibility Matrix (summary)

Launcher owns boot to an interactive state without game-specific hardcoding:
game resource re-pointing (`GameResourceContext`), Firebase manifest registrar
supply, GMS caller-stack whitelisting, billing-init mocking (`PlayBillingFix`),
sandbox directories, native library redirection, and fail-fast intent routing
(non-game intents pass through so errors are catchable, never hung).

Mods own game logic: store-SDK shims, auth fallbacks, cloud-save and ad-reward
handling, gameplay and UI changes.

Architecture constraints (cannot be satisfied by the host; mods must adapt or
bypass): real Google OAuth token issuance, Play Store checkout
(`launchBillingFlow`), Play Integrity/SafetyNet verdicts, authenticated Play
Games cloud save and leaderboards, and OS-routed OAuth deep links.

Shared boundary: Java I/O is redirected by the launcher; native POSIX paths
hardcoded in game libraries need Dobby path detours if they crash. Foreign
manifest components must be mocked or detoured, never depended on.

## 3. Google Sign-In Hang Lifecycle

Legacy interception rewrote every unregistered activity into a stub that
returned no `ActivityResult`, hanging sign-in on a spinner forever. The fix
passes non-game intents through, so the system returns a catchable error and
the game falls back to offline/guest mode.

Mod invariant: never dispatch concurrent GMS auth requests (the SDK silently
drops the second one) and never block a thread awaiting a dropped request.
Skipped custom logins must resolve to guest or offline sessions.

## 4. Contracts

Launcher: stay game-agnostic (no hardcoded package or class names), emulate
platform state transparently, preserve sandbox isolation, fail fast on
foreign intents.

Mod developers: never assume standalone execution (UID, signature, and
package all differ), adapt remote-server dependencies locally, avoid
re-entrant auth dispatch, and depend only on public BepInEx APIs, never on
launcher-internal classes.
