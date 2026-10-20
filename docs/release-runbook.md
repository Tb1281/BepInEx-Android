# Release Runbook

Target process for Release Contract v1 (ADR-0016 through ADR-0018). The signing, versioning, and preflight automation lands in the follow-up PRs referenced in the contract PR.

## Release invariants

- A release is a GitHub Release asset, never a git commit.
- A stable release requires build-verified, release-verified, and device-verified.
- Only `vMAJOR.MINOR.PATCH` tags on `main` create releases.
- The private release key never enters git or a release asset.
- `sync-upstream` artifacts are not releases.

## One-time setup

1. Create the release keystore outside the repo with `scripts/create_release_keystore.sh` (follow-up PR).
2. Register the following secrets in the `release` GitHub Environment:
   - `RELEASE_KEYSTORE_BASE64`
   - `RELEASE_KEYSTORE_PASSWORD`
   - `RELEASE_KEY_ALIAS`
   - `RELEASE_KEY_PASSWORD`
3. Back up the keystore and password files offline. GitHub secrets are write-only and cannot be downloaded later.
4. Commit the public certificate and fingerprint as `provenance/release-cert.pem` and `provenance/release-cert.sha256`.
5. Apply `scripts/apply_repo_settings.sh` (follow-up PR) to protect `main`, protect release tags, and constrain the `release` environment.

The current key was generated on 2026-10-20. Its SHA-256 certificate fingerprint is `b2fe00a6a80d6e69beee4eea8e8063dc74cca6759c0fdecc2c322bf56066db73`. Treat the keystore as unrecoverable if the offline backup is lost.

## Pre-release checklist

- [ ] `main` is green on `verify-provenance`.
- [ ] The release commit is covered by the `verified` tag.
- [ ] The tag is `vX.Y.Z` and `versionCode` is greater than the last stable release.
- [ ] Changelog and known issues are updated. The Tower RTDB issue is listed if still open.
- [ ] The Pine license review is closed; it is a stable-release blocker.
- [ ] Release notes include install/upgrade, supported matrix, checksums, certificate fingerprint, and license/source links.
- [ ] Repository protections and the `release` environment policy are applied.

## Cut the release

```bash
git switch main
git pull --ff-only
git tag -a vX.Y.Z -m "BepInEx Android vX.Y.Z"
git push origin vX.Y.Z
gh run watch --workflow=release-tag.yml
```

The `release-tag` preflight must pass before the build: tag format, `main` ancestry, `verified` coverage, monotonic version, and release-key secrets. `ship-release` uses `gh release create --verify-tag`, so it cannot create the tag implicitly.

## Verify the release

```bash
scripts/verify_release_assets.sh vX.Y.Z
gh attestation verify app-release-aligned.apk --repo Tb1281/BepInEx-Android
apksigner verify --print-certs app-release-aligned.apk
```

Compare the certificate SHA-256 with the published fingerprint. Then install on the device matrix and record the results in the release issue. Mark the release stable only after device verification.

## Rollback

- **Bad but not malicious**: edit the release, mark it pre-release, and publish an advisory in the release notes and a tracking issue. Do not delete the tag; publish a fixed patch release.
- **Malicious or compromised**: delete the release assets, publish an advisory, and rotate the key. Rotation requires a new applicationId or every user uninstalling and reinstalling.
- **Key loss**: existing users cannot update. Publish the recovery procedure (new applicationId or reinstall).

## Known issues

- **The Tower**: the production Firebase RTDB path still aborts in `DeleteInternal` on the pinned Firebase C++ SDK. See `docs/release-crash-investigation.md`. Publish as a known issue until resolved.
- **Pine license**: the GPL-3.0 launcher and Anti-996 Pine compatibility review is unresolved. Stable releases are blocked until it closes.

## Assets expected on a stable release

- `app-release-aligned.apk`
- `app-release-aligned.apk.sha256`
- `sbom.spdx.json`
- `provenance.json`
- `apk.attestation.json`
- `LICENSE`
- `THIRDPARTY_NATIVE.md`
- `release-cert.pem` and `release-cert.sha256` (or a link to them)
