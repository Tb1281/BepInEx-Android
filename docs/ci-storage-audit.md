# Storage Architecture & Permissions Audit

This document specifies the storage permission model, scoped storage compliance, and security sandboxing within the BepInEx Android launcher.

---

## 1. Storage Permission Overview

Modern Android versions (Android 11+, API 30+) enforce Scoped Storage restrictions. Accessing arbitrary paths on `/sdcard` requires elevated `MANAGE_EXTERNAL_STORAGE` permissions, which introduces app store policy hurdles and user friction.

### Production vs. Test Package Status

- **Production Package (`com.bepinex.android`)**:
  - Legacy default: Relies on `/sdcard/BepInEx_Android/` for framework cores, plugins, configs, and interop data.
  - Declares `MANAGE_EXTERNAL_STORAGE` and `READ/WRITE_EXTERNAL_STORAGE` in `AndroidManifest.xml`.
- **Test Package (`com.bepinex.android.test`)**:
  - Successfully verified on hardware (Android 16/17, API 36/37) running with **zero broad storage permissions**.
  - All framework files, interop assemblies, and plugins reside in app-owned sandbox directories (`getExternalFilesDir(null)`).
  - Full game bootstrap and mod patch application verified under strict permission denial.

---

## 2. Storage Architecture & Directory Layout

```
[ App-Specific Sandbox: /Android/data/com.bepinex.android/files/ ]
        ├── <package_name>/
        │       ├── BepInEx/
        │       │     ├── .extracted-version   (Version stamp: versionCode:versionName)
        │       │     ├── core/                (Framework assemblies; refreshed on version bump)
        │       │     ├── plugins/             (User mod DLLs; preserved across updates)
        │       │     ├── config/              (Plugin configuration files; preserved)
        │       │     └── interop/             (Generated IL2CPP interop assemblies)
        │       └── dotnet/                    (CoreCLR runtime assemblies & native PALs)
        └── logs/                              (Diagnostic logs shared via FileProvider)
```

### Framework Extraction Contract (`FileExtractor.kt`)

1. **Idempotent Extraction with Version Stamping**:
   - Extraction completion is tracked via `.extracted-version`, containing `"$versionCode:$versionName"`.
   - On launcher upgrade, `FileExtractor` detects the stale stamp and purges **only the `core/` directory**, re-extracting `BepInEx.Android.zip`.
   - User mod plugins (`plugins/`), user configs (`config/`), and generated interop assemblies (`interop/`) are strictly preserved.
2. **Configuration Seeding**:
   - `BepInEx.cfg` is extracted only if it does not already exist; existing user configurations are never overwritten.

---

## 3. Storage Access Framework (SAF) Migration Plan

To deprecate `MANAGE_EXTERNAL_STORAGE` permanently across production builds, file import and export operations must transition to the Android Storage Access Framework (SAF):

1. **Modpack Import**:
   - Use `ActivityResultContracts.OpenDocument` to allow users to select ZIP archives from any storage provider.
   - Pass resolved document display name to `ModpackManager.importModpack` to resolve `.zip` archive extension checks.
2. **Modpack Export**:
   - Use `ActivityResultContracts.CreateDocument` to obtain a writable content URI.
   - Stream exported modpack ZIPs directly via `ContentResolver.openOutputStream`.
3. **Log & Crash Dump Sharing**:
   - Share diagnostic ZIPs from `cacheDir` and logcat dumps via `FileProvider` (`androidx.core.content.FileProvider`).
   - Align `<paths>` declarations in `file_paths.xml` with actual runtime log output directories.
4. **Data Migration & Permission Cleanup**:
   - Detect legacy `/sdcard/BepInEx_Android/` data on initial launch and migrate files into the app sandbox.
   - Remove `MANAGE_EXTERNAL_STORAGE` and legacy storage flags from `AndroidManifest.xml`.

---

## 4. Workflow & CI Token Permission Audit

In accordance with least-privilege security principles, GitHub Actions token permissions are explicitly declared:

| Workflow | Granted Permissions | Purpose |
|---|---|---|
| `verify-provenance.yml` | `contents: write` | Pushes rolling `verified` tag upon successful gate execution. |
| `sync-upstream.yml` | `contents: write`, `pull-requests: write`, `issues: write` | Stages commits, opens sync pull requests, and logs rolling CI failure issues. |
| `fetch-runtime.yml` | `issues: write` | Reports runtime drift or download failures to the rolling issue. |
| All other workflows | `contents: read` (default) | Pure read-only checkout and artifact building. |

> [!NOTE]
> All workflows declare job-level permissions to avoid inheriting broad default repository tokens.
