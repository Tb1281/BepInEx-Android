# Technical Case Study: Unity 6000 & Process Hosting

## Summary

Games run inside an auxiliary launcher process, so package identity,
signatures, and sandboxes diverge from standalone execution. Four shim layers
below bring modern Unity titles (including Unity 6000 games) to full
in-game playability with mods active. Device dumps, if any, are gitignored
and not part of the repository.

## Layered Resolutions

**Layer 1: Firebase resource lookup.** Firebase resolves `google_app_id` via
`getPackageName()`, which returns the host package, so lookups hit the wrong
resource table and return 0. Fix: `GameResourceContext` re-points resources
and identifiers to the game APK (see `docs/responsibility.md`).

**Layer 2: Firebase component discovery.** Component registrars declared in
the game manifest's discovery service never run in the host process, so
startup aborts with a JNI null error. Fix: `FirebaseComponentDiscoveryFix`
hooks registrar retrieval and supplies the manifest-declared classes parsed
from the game APK.

**Layer 3: GMS Binder IPC and context.** Play Services validates calling UID
against `getPackageName()`; a game name under the host UID fails validation.
Fix: wrap `Application.mBase` to resolve class-cast failures, return the game
package only to game/Unity call frames (stack whitelist), and return the host
identity for OS/GMS IPC (`getOpPackageName` stays host-aligned).

**Layer 4: Play Billing retry loop.** The store client queries products under
the host package, which is unlisted, so per-frame retries leak JNI global
references until ART aborts (limit 51200). Fix: `PlayBillingFix` (via Pine)
mocks `isReady`, connection setup, product/sku queries (empty or mock data),
and purchase queries. Scope: prevents boot crashes only; real checkout
(`launchBillingFlow`) remains unsupported and needs mod-level handling.

## Takeaways

- When CoreCLR stdout is silent, check logcat:
  `adb logcat | grep -E '\[Error:Unity\]|\[Error:Il2CppInterop\]'`.
- Only the `libil2cpp.so` program-header BSS reservation is modified; game
  executable instructions are untouched.
- Discovery metadata lives in manifest `<service>` entries, not
  `<application>` tags; parse the game APK manifest as the source of truth.
- Compatibility is tracked per title in the verification matrix
  (`docs/progress.md`), not by hardcoded package names in the launcher
  (see `docs/responsibility.md`).
