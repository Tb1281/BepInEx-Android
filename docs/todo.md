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
- [ ] Public-era: Sigstore/Rekor attestation and consumer-side attestation verification on releases.

### Phase 6: ARM64 Inline Hook Space Guard & Logging Alignment [COMPLETE]

- [x] Thunk resolution plus space guards plus hook exclusions (see ADR 7/8/10/11); Unity log listening off; BootstrapActivity config-change handling.
- [x] Verified on-device on Unity 2022.3 and Unity 6000 titles: clean chainloader startup.

### Phase 7: Release Build (R8 Minification) [COMPLETE]

- [x] Minify plus shrink enabled on release; PAL keep rules; gates pointed at the release artifact and run after the build.
- [x] Non-debuggable PAL abort fixed via FindClass fallback (see ADR 15); release boots on both verified titles.

---

## Next Steps

### Priority 1: Trust

- [ ] Sigstore/Rekor attestation on all release artifacts (requires public repo); publish SBOM alongside APK.
- [ ] Egress `block` rollout: native-deps, runtime fetch, sync-upstream, then launcher-apk last (widest Gradle egress). Per-job allow-lists measured from `audit` logs; fixture asserts per-job policy fail-closed both ways.
- [ ] Formal SLSA 3 level once public.

### Priority 2: CI/CD

- [ ] Release automation: sync-path releases already publish APK plus SBOM plus attestation bundle; remaining work is tag-based general release via a shipping-path refactor.
- [ ] Test matrix: expand beyond the Unity 6000 / 2022.3 titles already verified.
- [ ] Keep short artifact retention and no-cache policy under quota; revisit only with quota headroom.

### Priority 3: APK Size

- Decision pending on the Pine keep-rule: Pine ships a keep rule matching every class with native methods, which keeps the whole tree and largely neuters shrinking (release stays near 60 MB instead of the low-50s floor). Options: vendor Pine's Java API (drop the rule, keep pinned native) or accept current size. The under-40 MB target is dropped — the floor is the pinned runtime itself, and trimming framework bytes is forbidden by design.
- License review open: Pine is Anti 996 v1.0 (not MIT); GPL-3.0 compatibility of linking it is undecided. Tracked with the vendoring decision above; see `launcher/THIRDPARTY_NATIVE.md`.
