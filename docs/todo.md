# Project Roadmap & Task Tracking

## Operational Principles

1. **Human-gated dependency bumps**: never auto-bump Runtime, MonoMod, or Il2CppInterop pins.
2. **Decouple detection from upgrading**: scheduled jobs report drift, never bump versions.
3. **Fail-closed verification**: missing manifests or contract mismatches halt the build.
4. **Test the verifiers**: every gate ships fixtures proving it fails on bad input.

---

## Active & Planned Phases

### Phase 1: MonoMod Pin Upgrade & Validation [COMPLETE]

- [x] Apply Android MonoMod patch against tracked upstream tip; build with pinned .NET SDK.
- [x] On-device vanilla run: PASS — Snapdragon 8 Gen 3 / Android 15+, Unity 6000 title (The Tower). Chainloader reached `startup complete`; interop generation complete; crash signatures zero.
- [x] On-device modpack run: PASS — same device class and game. Harmony patch applied and changed game behaviour; crash signatures zero.
- [x] Deterministic-zip re-verification: PASS on boot/extraction path. Rebuilt bytes behave identically across runs; post-boot native crashes exonerated as pre-existing by A/B.
- [x] Public-tree baseline (2026-10-13): rebuilt-from-pins bytes boot vanilla on-device — The Tower, Unity 6000.3.15f1: `Chainloader startup complete`, 29,197 methods restored, export stub resolved (1 hop), crash signatures zero, game process alive past 4 minutes.
- [x] Cpp2IL closure migration (2026-10-13): unrestorable `development.1452` pin moved to published `pre-release.21`, interop renamed `1.5.3-android.1`. Forced interop regeneration on-device: 29,197 methods, `startup complete`, zero signatures.

### Phase 2: Upstream Pin Drift Monitoring [COMPLETE]

- [x] Unified drift check (`scripts/check_upstream_pins.sh`) across all `provenance/*.env` pins.
- [x] Drift check wired into the sync-upstream workflow with rolling failure notices.

### Phase 3: Declarative `.env` Pin Migration [COMPLETE]

- [x] All upstream deps (BepInEx, Pine, dobby, MonoMod, Il2CppInterop, .NET runtime) pinned as text in `provenance/*.env`; submodule gitlinks removed.
- [x] Post-clone pin assertions, nested Pine submodule pin, scratch-clone patch replay, workflow retargeting, and full local end-to-end rebuild verified.

### Phase 4: Storage Access Framework (SAF) Migration [COMPLETE]

- [x] App-specific storage only; storage permissions removed (verified in APK and on device).
- [x] Modpack import/export via SAF pickers verified end to end; FileProvider paths and share grants fixed.
- [x] Legacy shared-storage tree abandoned without migration (documented, accepted data cost).

### Phase 5: Build Provenance & Attestation [IN PROGRESS]

- [x] Build provenance attested per producer run; shipping path gated with fixtures.
- [x] Public-era: Sigstore/Rekor attestation and consumer-side attestation verification on releases.

### Phase 6: ARM64 Inline Hook Space Guard & Logging Alignment [COMPLETE]

- [x] Thunk resolution plus space guards plus hook exclusions (see ADR 7/8/10/11); Unity log listening off; BootstrapActivity config-change handling.
- [x] Verified on-device on Unity 2022.3 and Unity 6000 titles: clean chainloader startup.

### Phase 7: Release Build (R8 Minification) [COMPLETE]

- [x] Minify plus shrink enabled on release; PAL keep rules; gates pointed at the release artifact and run after the build.
- [x] Non-debuggable PAL abort fixed via FindClass fallback (see ADR 15); release boots on both verified titles.

---

## Next Steps

### Priority 0: Release Contract v1 [IN PROGRESS]

- [x] Release contract documented: GitHub Releases only, stable requires build/release/device verification, tag-only releases (ADR-0016 through ADR-0018).
- [x] Release key generated and registered in the `release` GitHub Environment (2026-10-20).
- [ ] Offline backup of the release key confirmed by the maintainer.
- [x] Release signing and application-ID migration; release build fails closed without the release key.
- [x] Tag-derived `versionCode`/`versionName`, monotonic version check, and `v[0-9]*` trigger.
- [x] Release preflight: tag on `main`, covered by `verified`, secrets present, `gh release create --verify-tag`.
- [x] `sync-upstream` stops publishing stable releases; it produces artifacts only.
- [x] Release notes and assets: install/upgrade, known issues, certificate fingerprint, GPL/LGPL notices, source offer.
- [x] Repository protections applied: `main` required checks, the `release-tags` ruleset excluding `verified`, the `release` environment restricted to `main`/`v*`, and the default workflow token read-only with Actions PR creation for the sync path (`scripts/apply_repo_settings.sh --apply`, audited 2026-10-20).
- [x] `v0.0.0-rc1` end-to-end rehearsal and rollback runbook (pre-release verified with nine assets; promotion rejected; advisory retained; 2026-10-20).
- [x] Pine license review — closed by replacing Pine with LSPlant (LGPL-3.0); see the ADR superseding [ADR-0019](adr/0019-retain-gpl-and-pine.md).

### Priority 1: Trust

- [x] Sigstore/Rekor attestation on all release artifacts (2026-10-14): repo is public and every producer attests officially — verified same-day across runtime, hook engines, managed framework, sync APK, and shipping APK (`Attestation signed using certificate from Public Good Sigstore instance`; consumer gate reports `Sigstore bundle` on all 12 inputs, no fallbacks). The release SBOM is attested alongside the APK; sync artifacts carry the SBOM in the CI artifact without attestation.
- [x] Egress `block` rollout (2026-10-14): all nine jobs block with explicit allow-lists — `verify-provenance:provenance`, `:patch-queue`, `build-bepinex:build`, `build-native-deps:build`, `fetch-runtime:fetch-runtime`, `sync-upstream:sync`, `build-launcher-apk:apk`, `release-tag:build`, and `ship-release:ship`. Each build job graduated after a green blocked run; the two release jobs' lists are validated by the tag rehearsal. Sigstore (fulcio/rekor, plus TUF hosts on the consumer side) is included from measurement so attestations stay official on the public repo. Rollback per job is one line to `audit`.
- [ ] Formal SLSA 3 level once public.

### Priority 2: CI/CD

- [x] Release automation: tag-based general release via the shared shipping path; sync-path builds are CI artifacts only. The `v0.0.0-rc1` rehearsal exercised the full path and rollback; stable promotion remains blocked by the Pine review.
- [ ] Test matrix: expand beyond the Unity 6000 / 2022.3 titles already verified.
- [ ] Keep short artifact retention and no-cache policy under quota; revisit only with quota headroom.

### Priority 3: APK Size

- Decision pending on the Pine keep-rule: Pine ships a keep rule matching every class with native methods, which keeps the whole tree and largely neuters shrinking (release stays near 60 MB instead of the low-50s floor). Options: vendor Pine's Java API (drop the rule, keep pinned native) or accept current size. The under-40 MB target is dropped — the floor is the pinned runtime itself, and trimming framework bytes is forbidden by design.
- License review open: Pine is Anti 996 v1.0 (not MIT); GPL-3.0 compatibility of linking it is undecided. Tracked with the vendoring decision above; see `launcher/THIRDPARTY_NATIVE.md`.
