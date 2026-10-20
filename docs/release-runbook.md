# Release Runbook

Target process for Release Contract v1 (ADR-0016 through ADR-0018). Signing,
versioning, tag release, preflight, consumer verification, manual stable
promotion, and the repository protections are implemented. Apply the settings
with `scripts/apply_repo_settings.sh`; the script is the source of truth.

## Release invariants

- A release is a GitHub Release asset, never a git commit.
- A stable release requires build-verified, release-verified, and device-verified.
- Only `vMAJOR.MINOR.PATCH` and `vMAJOR.MINOR.PATCH-<label>.<number>` tags on `main` create releases.
- The private release key never enters git or a release asset.
- `sync-upstream` artifacts are not releases.

## One-time setup

1. The release key is already generated (2026-10-20). Keep the keystore and password files outside the repo; never commit or upload them.
2. Register the following secrets in the `release` GitHub Environment:
   - `RELEASE_KEYSTORE_BASE64`
   - `RELEASE_KEYSTORE_PASSWORD`
   - `RELEASE_KEY_ALIAS`
   - `RELEASE_KEY_PASSWORD`
3. Back up the keystore and password files offline. GitHub secrets are write-only and cannot be downloaded later.
4. Commit the public certificate and fingerprint as `provenance/release-cert.pem` and `provenance/release-cert.sha256`.
5. Apply `scripts/apply_repo_settings.sh` to protect `main`, create the
   `release-tags` ruleset, and constrain the `release` environment:

   ```bash
   GH_TOKEN=... GITHUB_REPOSITORY=Tb1281/BepInEx-Android \
     ./scripts/apply_repo_settings.sh --dry-run
   GH_TOKEN=... GITHUB_REPOSITORY=Tb1281/BepInEx-Android \
     ./scripts/apply_repo_settings.sh --apply
   ```

   The script is idempotent and fails closed before any mutation without admin
   permission. It also requires the four secret names above to exist, sets the
   default workflow token read-only, and disables Actions approval of pull
   requests. Re-run it after changing a protected branch or environment policy.

The current key was generated on 2026-10-20. Its SHA-256 certificate fingerprint is `b2fe00a6a80d6e69beee4eea8e8063dc74cca6759c0fdecc2c322bf56066db73`. Treat the keystore as unrecoverable if the offline backup is lost.

## Repository settings

`scripts/apply_repo_settings.sh` defines the protections the release path
depends on:

- `main` requires a pull request, the `provenance` and `patch-queue` checks,
  up-to-date branches, linear history, and conversation resolution. Force
  pushes and deletions are blocked, administrators are included, and the
  required approval count is zero so a solo maintainer can still merge. There
  are no push restrictions.
- The `release-tags` ruleset covers `refs/tags/v*` but excludes the moving
  `refs/tags/verified`. Creation, update, and deletion are restricted; only
  repository admins and maintainers can bypass. The `verified` tag stays
  movable by `verify-provenance`.
- The `release` environment accepts deployments only from the `main` branch and
  `v*` tags. Its four release-key secret names must exist; their values are
  never read or printed. The environment is the only place the release key is
  available, and only the tag build declares it.
- The default workflow token is read-only, and Actions cannot approve pull
  requests. Release jobs opt in to the write scopes they need.

Run `--dry-run` first; `--apply` is safe to repeat. Audit with:

```bash
gh api repos/Tb1281/BepInEx-Android/branches/main/protection
gh api repos/Tb1281/BepInEx-Android/rulesets
gh api repos/Tb1281/BepInEx-Android/environments/release
```

## One-time upgrade from the old package

The first contract release installs as `com.bepinex.android` signed by the release key. Uninstall `com.bepinex.android.release` and any old debug install at `com.bepinex.android` once before installing it. Android treats the old signatures as different identities, so an in-place update is not possible; app data is not migrated. Later releases signed by the same key install over the previous release.

## Pre-release checklist

- [ ] `main` is green on `verify-provenance`.
- [ ] The release commit is covered by the `verified` tag.
- [ ] The tag is a valid release tag; a stable release's version is above the highest stable tag on the remote, and a pre-release base is above the last stable base.
- [ ] Changelog and known issues are updated. The Tower RTDB issue is listed if still open.
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

The `release-tag` preflight must pass before the build: tag format, `main`
ancestry, `verified` coverage, monotonic version, duplicate stable-release
rejection, release-key secrets, and the key certificate fingerprint.
`ship-release` uses `gh release create --verify-tag`, so it cannot create the
tag implicitly. Every tag release starts as a GitHub pre-release.

## Verify the release

```bash
export GITHUB_REPOSITORY=Tb1281/BepInEx-Android
scripts/verify_release_assets.sh vX.Y.Z
```

The script downloads what a user downloads and checks the complete asset set,
the checksum manifest, the Sigstore bundle, the APK signer certificate against
the repository trust anchor `provenance/release-cert.sha256`, the published
certificate and fingerprint against the same anchor, and the GitHub Release
state. It needs `gh`, `apksigner`, and `openssl` on `PATH`; `verify-release.yml`
installs `build-tools;35.0.0` and runs this automatically after `ship-release`
publishes. Releases created outside that path can be checked with a manual
`workflow_dispatch`.

A normal run accepts a pre-release. Use `--require-stable` only after
promotion. For a manual second opinion:

```bash
gh attestation verify app-release-aligned.apk --repo Tb1281/BepInEx-Android
apksigner verify --print-certs app-release-aligned.apk
```

Compare the certificate SHA-256 with `release-cert.sha256`, with the
fingerprint in the release notes, and with `provenance/release-cert.sha256` in
the repository.

## Promote the release

Device verification is recorded in the release issue using the matrix in
`docs/progress.md`. Promotion is blocked while the Pine license review or any
matrix row is unresolved.

```bash
gh workflow run promote-release.yml \
  -f tag=vX.Y.Z \
  -f device_verification=recorded
gh run watch --workflow=promote-release.yml
```

The workflow has no release key and cannot build. It installs the pinned
build-tools for `apksigner`, runs the `--verify-only` preflight, requires a
stable tag and the explicit device-verification confirmation,
consumer-verifies the pre-release, clears the pre-release flag, and verifies
again with `--require-stable`. If the post-check fails it restores the
pre-release flag and fails the run.

Confirm afterwards:

```bash
gh release view vX.Y.Z --json isPrerelease --jq .isPrerelease       # false
scripts/verify_release_assets.sh --require-stable vX.Y.Z
```

## Rollback

- **Bad but not malicious**: mark the release pre-release, publish an advisory in the release notes and a tracking issue, and cut a fixed patch release. Do not delete the tag.
  ```bash
  gh release edit vX.Y.Z --prerelease
  ```
- **Malicious or compromised**: delete the release assets, publish an advisory, and rotate the key. Rotation requires a new applicationId or every user uninstalling and reinstalling.
- **Key loss**: existing users cannot update. Publish the recovery procedure (new applicationId or reinstall).

## Known issues

- **The Tower**: the production Firebase RTDB path still aborts in `DeleteInternal` on the pinned Firebase C++ SDK. See `docs/release-crash-investigation.md`. Publish as a known issue until resolved.
- **Pine license**: the GPL-3.0 launcher and Anti-996 Pine compatibility review is unresolved. Stable promotion is blocked until it closes.

## Assets expected on a release

- `app-release-aligned.apk`
- `app-release-aligned.apk.sha256`
- `sbom.spdx.json`
- `provenance.json`
- `apk.attestation.json`
- `LICENSE`
- `THIRDPARTY_NATIVE.md`
- `release-cert.pem`
- `release-cert.sha256`

`scripts/verify_release_assets.sh` requires all nine and fails if any is missing.
