# Sideload-only release contract

Status: accepted (2026-10-20)

The project distributes an optimized APK for manual installation, not a Play Store app. "Verified" was being used for build integrity, release publication, and on-device playability interchangeably.

Releases are GitHub Release assets only; no APK is committed to git and there is no Play Store or AAB track. A stable release requires all three of build-verified (pins, hashes, provenance, attestation), release-verified (tag on `main`, version and signing certificate, release preflight), and device-verified (recorded device/OS/Unity/game matrix). Release notes publish the matrix and known issues. The Tower production RTDB crash remains a known issue until resolved.

Consequences:

- "Verified" without a qualifier is not used in release claims.
- A green CI run alone cannot produce a stable release.
- The release page carries install and upgrade instructions, checksums, certificate fingerprint, known issues, and license/source links.
