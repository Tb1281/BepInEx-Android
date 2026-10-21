# Retain GPL-3.0 and Pine until the license review closes

Status: accepted (2026-10-21)

The launcher and native injection code (`libmain`, `libfusion`) are GPL-3.0. Pine is linked into the same APK as `libpine.so` and its Java API, and is licensed Anti 996 v1.0. Its conditions are not among the additional terms GPL-3.0 permits, so whether the combination is compatible remains an open legal review. Two alternatives were considered: replacing Pine with LSPlant (LGPL-3.0), or relicensing the project away from GPL-3.0.

Neither alternative is adopted now. The launcher contains direct ports of GPL-3.0 FusionCore classes, and its UI references an unlicensed NextBep tree, so relicensing the whole launcher would require permission or clean-room rewrites and would not remove Anti-996's own obligations. LSPlant migration would replace Pine's Java hook API with a JNI layer across the launcher's hook sites and require a new device test matrix; it remains the documented fallback if the review finds the combination incompatible.

Decision: keep the current GPL-3.0 + Pine + Dobby stack, do not relicense, and defer stable promotion. Publish pre-releases only until the Pine license review closes. This decision makes no code or dependency change.

Consequences:

- `v0.0.0-rc1` and future `rc` tags remain the distribution channel; stable tags are not cut.
- The Pine review stays an open blocker in `docs/todo.md` and `docs/release-runbook.md`.
- Dobby is unaffected: it is Apache-2.0 and is a separate native inline-hook layer.
- If the review finds the combination incompatible, the fallback is LSPlant (LGPL-3.0) with Dobby retained. Relicensing separately still requires a copyright audit and permission for the FusionCore ports.
- Revisit this ADR when the review closes or Pine's author grants a GPL-compatible exception.
