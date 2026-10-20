# Tagged releases only

Status: accepted (2026-10-20)

`sync-upstream` advanced the BepInEx pin in the working tree, built an APK, and created a stable GitHub Release before opening the pin-bump PR. The release tag pointed at the default-branch HEAD, which did not contain the pin used for the build, so the tag could not reproduce the artifact.

Only a tag matching `vMAJOR.MINOR.PATCH` on a commit already merged to `main` may create a stable release. The tag commit must be covered by a green `verify-provenance` run. CI derives `versionCode` and `versionName` from the tag, rejects a non-monotonic version, and `ship-release` uses `gh release create --verify-tag` so it cannot create a tag implicitly. `sync-upstream` produces artifacts only; it never publishes a stable release.

Consequences:

- Upstream sync output is reviewed and merged before any release.
- A release can always be traced from tag to source to artifact.
- The release trigger is `v[0-9]*`, so the moving `verified` tag cannot trigger a release.
