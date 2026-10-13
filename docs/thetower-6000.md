# Technical Case Study: Unity 6000 & Process Hosting

This document details the architectural investigation and resolution of hosting identity conflicts encountered when running modern Unity games under BepInEx Android, specifically *The Tower* (Unity `6000.3.15f1`, `com.TechTreeGames.TheTower`).

---

## 1. Executive Summary

When running under the BepInEx launcher, games are hosted inside an auxiliary launcher process (`com.bepinex.android:game`). This isolation creates identity and filesystem discrepancies with third-party SDKs (such as Firebase and Google Play Billing).

Through targeted context shims and native interception, *The Tower* achieved 100% in-game playability with BepInEx mods active on modern Android devices (Android 16/17, arm64-v8a).

---

## 2. Problem Breakdown & Layered Resolutions

```
[ Game Launch ]
       │
       ├── Layer 1: Firebase Resource Lookup Failures
       │            └── Resolved: GameResourceContext
       │
       ├── Layer 2: Firebase Component Discovery & JNI Abort
       │            └── Resolved: FirebaseComponentDiscoveryFix
       │
       ├── Layer 3: GMS Binder IPC & File System Collisions
       │            └── Resolved: wrapApplication + Package Whitelisting
       │
       └── Layer 4: Play Billing Re-try Loop & JNI Reference Overflow
                    └── Resolved: PlayBillingFix (Pine Mocking)
       │
[ Fully Playable In-Game ]
```

### Layer 1: Firebase Configuration Lookup
- **Symptom**: Game startup halted at `Loading... 0%` with `Firebase.FirebaseApp.CreateAndTrack: Failed to read Firebase options from the app's resources`.
- **Root Cause**: Firebase calls `context.getResources().getIdentifier("google_app_id", "string", context.getPackageName())`. Because `getPackageName()` returned `com.bepinex.android`, resource lookups hit the launcher APK table instead of the game APK table, returning `0`.
- **Resolution**: Implemented `GameResourceContext` and hooked `Activity.attachBaseContext` to return the game APK's resources and identifiers.

### Layer 2: Component Discovery & JNI Aborts
- **Symptom**: Process aborted with `JNI DETECTED ERROR: NullPointerException: Firebase Messaging component is not present`.
- **Root Cause**: Firebase component registrars are declared under `<service com.google.firebase.components.ComponentDiscoveryService>` in the game's manifest. In the host process, this service is never invoked, leaving the registrar list empty.
- **Resolution**: Implemented `FirebaseComponentDiscoveryFix` via dynamic hooking of `MetadataRegistrarNameRetriever.retrieve`. When the registrar list is empty, it parses and supplies the 21 registrar classes declared in the game APK's manifest.

### Layer 3: Application Context & GMS Security Exceptions
- **Symptom**: GMS Binder IPC failed with `SecurityException: Unknown calling package name`, triggering process termination (`SIGKILL`).
- **Root Cause**: Google Play Services (GMS) validated calling UID against `getPackageName()`. When returning the game package name across IPC, the UID mismatch failed validation.
- **Resolution**:
  - Implemented `GameResourceContext.wrapApplication` to wrap `Application.mBase`, resolving `ClassCastException` in Firebase initialization.
  - Added caller stack whitelisting:
    ```kotlin
    // Inspects call stack depth 2..15
    if (className.startsWith("com.unity3d.") || className.startsWith(gamePkg)) {
        return gamePkg
    }
    return storageContext.packageName // Returns launcher package for GMS / OS IPC
    ```
  - `getOpPackageName()` strictly returns `storageContext.opPackageName` to ensure UID alignment.

### Layer 4: Google Play Billing & JNI Global Reference Overflow
- **Symptom**: Game halted at `Loading... 80%` and aborted with:
  ```
  DEBUG: Abort message: 'JNI ERROR (app bug): global reference table overflow (max=51200)'
  ```
- **Root Cause**:
  1. Unity IAP / Google Play Billing Client (`BillingClientImpl.queryProductDetailsAsync`) queried the Play Store for in-app products under the launcher's package name.
  2. Because the launcher package is not registered on the developer's Play Console, queries failed.
  3. The game's C# `Purchaser` script entered an unbounded frame-by-frame retry loop, creating dozens of `QueryProductDetailsParams$Product` and `Class` JNI objects per frame without releasing them.
  4. The 51,200 JNI global reference limit in ART was exceeded, causing an immediate `SIGABRT`.
- **Resolution (`PlayBillingFix`)**:
  - Intercepted `BillingClientImpl` using Pine:
    - `isReady()`: Always returns `true`.
    - `startConnection()`: Automatically dispatches `onBillingSetupFinished(BillingResult.OK)`.
    - `queryProductDetailsAsync()`: Generates mock `ProductDetails` JSON objects ($0.00 USD) and dispatches `onProductDetailsResponse(BillingResult.OK, mockList)`.
    - `querySkuDetailsAsync()`: Dispatches mock `SkuDetails`.
    - `queryPurchasesAsync()`: Dispatches `emptyList()`.
- **Specification Scope & Limitation**:
  - **Purpose**: Prevent crash-on-boot and JNI reference exhaustion during Unity IAP initialization.
  - **Limitation**: Does **not** mock checkout transactions (`launchBillingFlow`). Attempting real in-app purchases inside the game remains unsupported without dedicated mod-level payment bypasses.

---

## 3. Compatibility Matrix

| Game | Unity Version | Status | Notes |
|---|---|---|---|
| *The Tower* | `6000.3.15f1` | **Playable** | Verified with active mods, full gameplay (Wave 2+), and UI interaction. |
| *Revolution Idle* | `2022.3.62f3` | **Playable** | Verified full chainloader bootstrap and plugin execution. |
| *Unlock!* | `6000.0.68f1` | **Playable** | Verified main menu reachability and asset loading. |

---

## 4. Key Diagnostic Takeaways

1. **Check Logcat for Unity Exceptions**: When Unity intercepts C# exceptions internally, CoreCLR stdout/stderr remain silent (0 bytes). Always inspect logcat:
   ```bash
   adb logcat | grep -E '\[Error:Unity\]|\[Error:Il2CppInterop\]'
   ```
2. **Native Code Integrity**: The launcher only modifies the program header table of `libil2cpp.so` to expand the BSS segment for trampolines; no executable instructions in the original game binary are altered.
3. **Inspect Actual Manifest Elements**: Component discovery metadata lives inside `<service>` definitions rather than `<application>` tags.
