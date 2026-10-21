# Release Runbook

Target process for Release Contract v1 (ADR-0016 through ADR-0018). Signing,
versioning, tag release, preflight, consumer verification, manual stable
promotion, and the repository protections are implemented. Apply the settings
with `scripts/apply_repo_settings.sh`; the script is the source of truth.

## Release invariants

- A release is a GitHub Release asset, never a git commit.
- A stable release requires build-verified, release-verified, and device-verified.
- Only `vMAJOR.MINOR.PATCH` and `vMAJOR.MINOR.PATCH-<label>` tags on `main` create releases. A pre-release label may end in a decimal ordinal, as in `v0.0.0-rc1`.
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
   permission or when a release secret name is missing. It also sets the
   default workflow token read-only, and allows Actions to create pull requests
   for the sync path. No workflow approves pull requests, and the required
   approval count is zero, so the approval capability does not satisfy a review
   gate. Re-run it after changing a protected branch or environment policy.

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
- The default workflow token is read-only. Actions is allowed to create pull
  requests because `sync-upstream` opens the pin-bump PR; no workflow approves
  them, and the required approval count is zero. Release jobs opt in to the
  write scopes they need.

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
tag implicitly. The build aligns with `zipalign`, re-signs with `apksigner`,
and verifies the APK signer against `provenance/release-cert.sha256` before the
ship job can run. Every tag release starts as a GitHub pre-release.

## Verify the release

```bash
export GITHUB_REPOSITORY=Tb1281/BepInEx-Android
scripts/verify_release_assets.sh vX.Y.Z
```

The script downloads what a user downloads and checks the complete asset set,
the checksum manifest, the Sigstore attestations for the APK, SBOM, and
provenance, the APK signer certificate against the repository trust anchor
`provenance/release-cert.sha256`, the published certificate and fingerprint
against the same anchor, and the GitHub Release state. It needs `gh`,
`apksigner`, and `openssl` on `PATH`; `verify-release.yml` installs
`build-tools;35.0.0` and runs this automatically after `ship-release` publishes.
Releases created outside that path can be checked with a manual
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
`docs/progress.md`. The workflow enforces only the stable tag, remote `verified`
coverage, the full asset set, the certificate/fingerprint, and the recorded
device-verification confirmation. The Pine license review and the device matrix
are a maintainer checklist, not an automated gate: complete them before
promotion, but a green workflow does not prove them.

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
- **Malicious or compromised**: delete the release assets, publish an advisory, and rotate the key. See [Key rotation](#key-rotation).
- **Key loss**: existing users cannot update. Follow [Key rotation](#key-rotation).

## Key rotation

Rotate when the release key is lost or believed compromised. Rotation changes
the signing identity, so Android cannot update an existing install in place;
plan for a reinstall, and never describe rotation as preserving app data.

1. Generate a new RSA-4096 key and certificate outside the repository:

   ```bash
   keytool -genkeypair -v -keystore release-new.p12 -storetype PKCS12 \
     -alias bepinex-release -keyalg RSA -keysize 4096 -validity 10000
   ```

   Use a new password. Keep the keystore and password files offline; GitHub
   secrets are write-only and cannot be downloaded later.
2. Register the four secrets in the `release` environment:
   `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
   and `RELEASE_KEY_PASSWORD`. Base64-encode the keystore without line breaks.
3. Commit the new public certificate as `provenance/release-cert.pem` and its
   SHA-256 fingerprint as `provenance/release-cert.sha256` in the same pull
   request, then run `scripts/apply_repo_settings.sh --apply` so the environment
   still has all four secret names.
4. Bump the application ID (for example, to `com.bepinex.android.v2`) in the
   launcher and every reference in the release path. The old key cannot update
   in place, so users must uninstall the old package and install the new one;
   app data is not migrated.
5. Cut a pre-release (`vX.Y.Z-rc1`) and verify it with
   `scripts/verify_release_assets.sh`. Keep the old releases and tags available
   for existing users.
6. Publish an advisory in the release notes and a tracking issue with the
   uninstall/reinstall steps, the new application ID, and the new certificate
   fingerprint.
7. Back up the new keystore and passwords offline. Rehearse the whole path on
   the rc, including the reinstall and consumer verification, before cutting a
   stable release.

## Rehearsal record (2026-10-20)

`v0.0.0-rc1` was cut from verified `main` at
`c55bb667923542909cb02d0f356dad00c211ad41` and published as a pre-release with
all nine assets: https://github.com/Tb1281/BepInEx-Android/releases/tag/v0.0.0-rc1.
The `release-tag` run
[39514214797](https://github.com/Tb1281/BepInEx-Android/actions/runs/39514214797)
passed, including the align-then-sign gate, and explicitly dispatched the
consumer verifier. Run
[39515310557](https://github.com/Tb1281/BepInEx-Android/actions/runs/39515310557)
passed the checksum, Sigstore bundle, signer fingerprint, and pre-release-state
checks. The local verifier also passed.

Promotion was exercised with `device_verification=recorded`; run
[39515478824](https://github.com/Tb1281/BepInEx-Android/actions/runs/39515478824)
correctly rejected the pre-release because it is not a stable
`vMAJOR.MINOR.PATCH` tag. The generated failure issue was closed as an expected
rejection. The rollback drill kept the release pre-release, appended an advisory
to the release notes, and confirmed the tag remains; the release stays available
for consumer verification.

The first attempt (`release-tag` run 39455631443) exposed two release-path
defects, both fixed before the successful run: `zipalign` ran after Gradle's
signature and published an unsigned APK (PR #21), and a release created with
`GITHUB_TOKEN` did not trigger the `release: published` verifier (PR #21). The
failed release and tag were withdrawn. A third fix grants `actions: write` to
the `release-tag` ship job so the explicit verifier dispatch is permitted
(PR #22). Stable promotion remains blocked by the Pine license review.

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
