# Release identity and signing

Status: accepted (2026-10-20)

Release APKs were signed with the Android debug keystore and shipped under `com.bepinex.android.release`. The debug key is not a controlled identity: CI can generate a different key per runner, users cannot update across a key change, and a public debug key lets anyone sign an update for the same package.

Distributed APKs are signed with a dedicated RSA-4096 release key stored in the `release` GitHub Environment and an offline backup. The release applicationId is `com.bepinex.android`; debug builds use `com.bepinex.android.debug`. The release build fails closed when the release key is absent. The public certificate and SHA-256 fingerprint are committed and published; the private key and passwords never enter git or a release asset.

Consequences:

- Existing installs of `com.bepinex.android.release` must be uninstalled once before installing the new release.
- Losing the key prevents updates; leaking it lets an attacker sign updates. Backup and a documented rotation procedure are part of the release contract.
- The `.release` suffix and debug signing are removed from the shipping path.
