# Storage Architecture & Permissions Audit

## 1. Permission Model

Android 11+ Scoped Storage restricts arbitrary `/sdcard` access; broad
`MANAGE_EXTERNAL_STORAGE` adds policy friction. The launcher therefore keeps
all framework, plugin, config, and interop data in app-owned sandbox
directories and runs with zero broad storage permissions.

## 2. Directory Layout

```
[ App sandbox: /Android/data/com.bepinex.android/files/ ]
    <package_name>/
        BepInEx/
            .extracted-version  (versionCode:versionName stamp)
            core/               (framework; refreshed on version bump)
            plugins/            (user DLLs; preserved)
            config/             (plugin configs; preserved)
            interop/            (generated IL2CPP assemblies; preserved)
        dotnet/                 (CoreCLR assemblies and native PALs)
    logs/                       (diagnostics shared via FileProvider)
```

`FileExtractor.kt` is idempotent: on upgrade it purges only `core/` and
re-extracts `BepInEx.Android.zip`; plugins, configs, and interop survive.
`BepInEx.cfg` is seeded only when absent and never overwritten.

## 3. Storage Access Framework Plan

No broad storage permission is declared. Imports use
`ActivityResultContracts.OpenDocument`, exports use
`ActivityResultContracts.CreateDocument` streamed via `ContentResolver`, and
log sharing goes through `FileProvider`. A legacy `/sdcard/BepInEx_Android`
tree is never read; the sandbox starts fresh and the orphaned bytes are left
alone (accepted data cost, not a regression).

## 4. CI Token Permissions (least privilege)

| Job | Permissions | Purpose |
|---|---|---|
| `verify-provenance:provenance` | `contents: write` | Moves the `verified` tag on success. |
| `verify-provenance:patch-queue` | Repo default (`read`) | Patch replay and managed build. |
| `sync-upstream:sync` | `contents`, `pull-requests`, `issues`, `attestations` write; `id-token` write | Pins, sync PRs, releases, failure notices, attestation. |
| `fetch-runtime` | `contents: read`; `issues`, `attestations` write; `id-token` write | Runtime pack and drift notices. |
| `build-bepinex`, `build-native-deps`, `build-launcher-apk` | `contents: read`; `attestations`, `id-token` write | Build inputs and attest them. |

All jobs declare permissions explicitly; none inherit broad defaults.
