# Most recent Android QA

- Result: **PENDING TAG** — code for v0.17.30 (versionCode 62) is on `master`.
- App/version (code): 0.17.30 (versionCode 62).
- Previous published PASS: v0.17.28 (versionCode 60) — 89.011 s end-to-end on Video A API 35.
- Changes in this increment (speed):
  - Coarse checkpoints parallelized across thermal-aware decoder/OCR lanes (same lane budget as refinement).
  - Coarse frame seeks use `OPTION_CLOSEST_SYNC` (keyframe) instead of `OPTION_CLOSEST`; refinement still uses closest for PTS accuracy.
  - Fast / Monotonic Turbo / Quick coarse target width unified at 384px (was 480 for Fast).
  - Parallel refinement already enabled for Fast/Turbo from 0.17.29.
- First unresolved causal failure: release tag `v0.17.30` not yet created; GitHub Actions has not built the signed APK. Create and push tag `v0.17.30` on the current master tip to publish the APK, then update `app-update.json` after the asset exists.
