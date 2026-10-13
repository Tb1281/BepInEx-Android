# Architecture & Responsibility Boundaries

When executing games within an auxiliary host process (`com.bepinex.android:game`), process identity, cryptographic signatures, and filesystem sandboxes diverge from standard standalone execution. This specification establishes deterministic ownership boundaries between the **Launcher**, **Mod Plugins**, and **Inherent Architectural Constraints**, documents the Google Sign-In startup hang lifecycle, and inventories potential failure vectors across host-process boundaries.

---

## 1. Classification Criteria

System behaviors and defects are classified across three distinct operational layers:

$$\text{Discrepancy Source} \rightarrow \begin{cases}
\textbf{Launcher}: & \text{Caused by host-process isolation, context re-pointing, or redirection.} \\
\textbf{Mod}: & \text{Game-specific logic, interop patches, or gameplay modifications.} \\
\textbf{Architecture}: & \text{Cryptographic or OS platform guarantees impossible to satisfy without root.}
\end{cases}$$

1. **Launcher Responsibility**: The launcher must emulate standard standalone runtime environments without game-specific hardcoding, ensuring the Unity engine, CoreCLR, and core platform SDKs boot to an interactive state without native or managed aborts.
2. **Mod Responsibility**: High-level game business logic, game-specific authentication adapters (e.g. custom account shims), in-game store bypasses, and algorithmic freeze corrections belong entirely to user plugins.
3. **Architectural Constraint**: Features requiring OS or remote server cryptographic verification matching the official developer signing key (such as legitimate Google OAuth tokens, Play Store checkout, and Play Integrity verdicts) cannot be satisfied by an auxiliary host. Mod developers must bypass or adapt these flows.

---

## 2. Comprehensive Responsibility Matrix

| Feature / Defect | Root Cause | Ownership | Resolution Specification |
|---|---|---|---|
| **Firebase `google_app_id` lookup returns 0** | Package identity mismatch in resource table | **Launcher** | Resolved via `GameResourceContext` re-pointing resources to game APK. |
| **Firebase Component Discovery aborts (JNI NPE)** | Manifest service omitted in host process | **Launcher** | Resolved via `FirebaseComponentDiscoveryFix` supplying manifest registrars. |
| **GMS IPC `SecurityException` (Calling package)** | UID mismatch in Binder IPC transaction | **Launcher** | Resolved via caller stack whitelisting in `getPackageName()` / `getOpPackageName()`. |
| **Play Billing re-try loop & JNI reference overflow** | Package unlisted on developer Play Console | **Launcher** | Resolved via `PlayBillingFix` (Mocking `isReady`, setup, and product details). |
| **Play Store Checkout (`launchBillingFlow`)** | Store client unauthenticated under foreign UID | **Architecture** | **Unsupported in launcher.** Requires mod-level bypass if purchasing needed. |
| **File / Preferences isolation** | Sandbox separation between host and target app | **Launcher** | Handled by design via app-specific directories (`filesDir`, `dataDir`). |
| **Native library lookup (`libil2cpp.so`)** | Custom native library loading path | **Launcher** | Handled via `findLibrary` redirection in launcher. |
| **Google Sign-In Activity Hang (`SignInHubActivity`)** | Decoy `StubActivity` delivers no `ActivityResult` | **Launcher** | Resolved in `InstrumentationHooks` by passing non-game intents through to AMS. |
| **Google Sign-In OAuth Token Issuance** | APK signature check against Google Cloud Console | **Architecture** | **Cannot be satisfied.** Requires mod-level authentication shim (e.g. Nakama). |
| **Play Games Services (PGS) Cloud Save / Snapshots** | Silent PGS authentication rejected under foreign signature | **Architecture** | Mod must bypass cloud save or redirect to local storage. |
| **Play Games Services Leaderboards & Achievements** | Authenticated PGS client unavailable | **Architecture** | Mod must mock achievement unlocks or suppress remote sync tasks. |
| **Google Play Integrity API / SafetyNet Attestation** | Signature digest mismatch against Play Console records | **Architecture** | Mod must detour and bypass integrity check handlers. |
| **Rewarded Video / Ad Networks (AdMob, Unity Ads)** | Missing ad unit config / signature mismatch in WebView IPC | **Mod** | Mod must detour reward callbacks to grant items directly without ad playback. |
| **Native C/C++ Hardcoded Storage Paths** | Native `.so` directly accesses `/data/data/<pkg>` via POSIX calls | **Launcher / Mod** | Java is redirected via `GameResourceContext`; native direct POSIX requires path detours if game crashes. |
| **OAuth Deep Links / Custom URL Schemes** | OS routes `intent-filter` schemes to installed game APK, not launcher | **Architecture** | Mod must replace external browser OAuth with embedded or direct REST auth. |
| **Manifest ContentProviders / Background Services** | Components declared in target APK manifest unhosted in launcher | **Launcher / Mod** | Must not depend on foreign IPC components; mock or detour if queried. |
| **`GenericMethod::GetMethod` hook ambiguity** | Il2CppInterop resolution for Unity 6000 | **Mod** | Handled via safe candidate check or pinning Il2CppInterop version. |
| **Custom gameplay & UI modification** | Mod feature requirements | **Mod** | Implemented in user BepInEx plugins. |
| **Server account authentication & gameplay sync** | Remote server protocol constraints | **Mod** | Custom authentication shim (e.g. offline account). |

---

## 3. Case Analysis: Google Sign-In & Startup Hang Lifecycle

During auxiliary hosting, games integrating Google Sign-In (such as *Revolution Idle*) exhibit subtle failure modes across two distinct stages:

### 3.1 Launcher Level: Decoy Activity Interception vs. Fail-Fast Propagation

```
[ Game calls GoogleSignIn.getClient().signIn() ]
                      │
                      ▼
[ startActivityForResult(SignInHubActivity) ]
                      │
        ┌─────────────┴─────────────┐
        ▼                           ▼
[ Intercepted into StubActivity ]  [ Passed through to AMS ]
  • Decoy launches successfully      • AMS fails: ActivityNotFoundException (rc=-92)
  • No ActivityResult returned       • SignInFragment receives INTERNAL_ERROR
  • Caller await hangs forever       • Unity throws GoogleSignIn+SignInException
  • Symptom: INFINITE SPINNER        • Game catches error -> FALLS BACK TO OFFLINE
```

1. **The Bug (Legacy Launcher)**: `InstrumentationHooks.execStartActivity` intercepted *all* unregistered activities and substituted `StubActivity`. Because `StubActivity` declared in the launcher manifest started successfully but delivered no `onActivityResult` payload, Google Sign-In's pending `Task` never completed. The title screen sat on its loading spinner indefinitely without throwing an exception.
2. **The Fix (`InstrumentationHooks.kt`)**: By enforcing `target.packageName != gamePkg`, foreign and GMS intents are left untouched. AMS immediately returns `ActivityNotFoundException` (`result code=-92`), triggering `SignInFragment`'s internal error callback. This allows the Unity C# layer (`GoogleSignInManager`) to throw a catchable `GoogleSignIn+SignInException`. The game catches this exception and falls back to offline/guest gameplay.

### 3.2 Mod Level: Pending Request Queue Drops & Fallback Deadlocks

When a mod (e.g., `NakamaLoginMod`) hooks authentication to present a custom login dialog:

1. **Initial Flight**: The game engine kicks off its initial Google Sign-In task upon boot (Request #1).
2. **Mod UI Interception**: The mod intercepts `NakamaManager.Connect()` and displays its own login window.
3. **Duplicate Request on Skip**: If the user skips custom login and the mod delegates back to the original `ManagerInstance.Connect()`, a second Google Sign-In request (Request #2) is dispatched.
4. **GMS Silent Drop**: `SignInFragment` rejects concurrent requests:
   ```text
   SignInFragment: Existing request: 6ccdad6 ignoring 76a9157. State = null
   SignInFragment: There is already a pending authentication token request!
   ```
5. **Deadlock**: Because Request #2 is dropped silently without scheduling a completion callback, blocking on `ui.FallbackTask.GetAwaiter().GetResult()` deadlocks the thread indefinitely, reproducing the infinite spinner.
6. **Mod Invariant**: Authentication mods must never invoke secondary auto-login chains that dispatch concurrent GMS requests; skipped logins must cleanly resolve to guest mode or offline sessions without blocking.

---

## 4. Host-Process Isolation Risk Vectors

Beyond FCM and Google Sign-In, auxiliary process hosting introduces friction across five major subsystems:

### 4.1 Cloud Saves & Play Games Services (PGS v2)
* **Mechanism**: `SnapshotsClient` requires authenticated Google Play Games sessions to read and write saved game states to Google Drive.
* **Risk**: If a game's initialization sequence synchronously awaits snapshot resolution without a timeout, authentication rejection leaves the task uncompleted, hanging boot.
* **Prescription**: Plugins must intercept save-game managers to bypass cloud synchronization and enforce local file persistence.

### 4.2 Play Integrity API & SafetyNet
* **Mechanism**: Games request cryptographically signed attestation tokens (`IntegrityManager.requestIntegrityToken`) to prove APK binary authenticity to backend servers.
* **Risk**: Attestation fails because the host APK signature digest does not match the Play Console records. Games with strict security policies will abort or restrict online interactions.
* **Prescription**: The host cannot forge Google signatures. Mods must detour the game's integrity response parser or mock server approval.

### 4.3 Mobile Ad Networks (AdMob, Unity Ads, AppLovin)
* **Mechanism**: Ad SDKs initialize via embedded WebViews and GMS Ads IPC (`com.google.android.gms.ads`).
* **Risk**: Ads fail to load due to missing manifest metadata (`APPLICATION_ID`) or package name attribution mismatches. If reward distribution logic blocks waiting for `onAdDismissed` callbacks, progression stalls.
* **Prescription**: Mods should bypass ad triggers and directly invoke reward grant handlers.

### 4.4 POSIX Direct Filesystem Access in C/C++
* **Mechanism**: Native game libraries (`libgame.so`) executing raw POSIX file calls (`fopen`, `open`, `stat`) with hardcoded paths (`/data/data/<game_package>/`).
* **Risk**: While Java I/O is cleanly re-routed via `GameResourceContext`, raw native syscalls bypass Android's `Context` abstraction and fail with `EACCES` (permission denied) or `ENOENT` under the launcher's UID.
* **Prescription**: If encountered, native libc syscalls must be hooked via Dobby in `libfusion.so` to redirect foreign package paths into the launcher sandbox.

### 4.5 External Browser OAuth & Deep Link Callbacks
* **Mechanism**: Games utilizing Chrome Custom Tabs or external browser intents (`Intent.ACTION_VIEW`) for OAuth flows (Discord, Twitter, Twitch, web sign-in).
* **Risk**: When the external browser redirects to the callback scheme (e.g. `gamename://oauth-callback`), Android OS resolves the registered URI scheme to the installed game APK rather than the host launcher process, dropping the authorization token.
* **Prescription**: Mod developers must supply in-game credential inputs (e.g. direct REST API login) rather than relying on browser-based intent callbacks.

---

## 5. Architectural Contracts

### 5.1 The Launcher Contract
1. **Game Agnostic**: Shims must inspect hosted package states dynamically; no game-specific package names or class names may be hardcoded.
2. **Transparent Platform Emulation**: Supply valid game resources, assets, classloaders, and manifest components for internal engine queries.
3. **Fail-Closed Sandbox Integrity**: Preserve strict isolation between target game data directories.
4. **Fail-Fast Intent Routing**: Non-game activities must route directly to AMS so failure statuses propagate catchably into managed runtime layers.

### 5.2 The Mod Developer Contract
1. **Never Assume Standalone Execution**: Always account for discrepancies between host UID, APK signing certificates, and target game identity.
2. **Handle Remote Server Dependencies**: Features requiring cryptographic Play Console authorization (Google Sign-In, Play Store checkout, PGS Cloud Save) must be adapted or bypassed in the mod layer.
3. **Avoid Re-Entrant Auth Dispatch**: Never trigger duplicate concurrent authentication tasks that GMS SDK fragments drop silently.
4. **Depend Exclusively on Public BepInEx APIs**: Never couple plugin logic to launcher-internal Java classes (`GameResourceContext`, `BootstrapActivity`, `StubActivity`).