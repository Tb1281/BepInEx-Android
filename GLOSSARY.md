# BepInEx Android

BepInEx Android hosts the BepInEx IL2CPP modding framework inside Unity Android games. This glossary fixes the vocabulary used by release, verification, and distribution work.

## Release and distribution

**Release**:
A GitHub Release whose assets are built from a tagged commit. A release is not a git commit, and no APK is committed to the repository.
_Avoid_: build, artifact drop

**Sideload**:
Installing an APK directly on a device without Google Play.
_Avoid_: store install, Play release

**Stable release**:
A release that is build-verified, release-verified, and device-verified, with no open release blocker.
_Avoid_: production release, final build

**Pre-release**:
A release candidate or rehearsal build published for testing but not claimed stable.
_Avoid_: beta, nightly

## Verification

**Build-verified**:
The artifact was produced from the pinned sources by CI, with hashes, provenance, and Sigstore attestation checked.
_Avoid_: verified (unqualified), trusted build

**Release-verified**:
The published release is tied to an existing tag on `main`, uses the expected version and signing certificate, and passed the release preflight.
_Avoid_: signed release

**Device-verified**:
The release artifact has been run on the recorded device, OS, Unity, and game matrix with the stated result.
_Avoid_: tested, works

**Verified tag** (`verified`):
A moving git tag that marks the most recent commit that passed `verify-provenance`. It is not a release tag and does not imply device-verified.
_Avoid_: release tag, stable tag

## Source and signing

**Release key**:
The private signing key used for distributed APKs. It is stored in the `release` GitHub Environment and an offline backup, never in git or a release asset.
_Avoid_: debug key, keystore (unqualified)

**Debug key**:
The local or CI key used only for debug builds. It is never accepted for a release.
_Avoid_: release key

**Source pin**:
A text file in `provenance/*.env` that fixes an upstream dependency to an immutable commit or package hash.
_Avoid_: version, latest

**Patch queue**:
The ordered patches in `patches/` replayed onto the pinned BepInEx commit. Skipping one ships a missing adaptation.
_Avoid_: fork, diff
