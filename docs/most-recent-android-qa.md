# Most recent Android QA

- Result: **PENDING TAG** — code for v0.17.29 (versionCode 61) is on `master` with cleaner UI sections and faster Fast/Turbo scan defaults (parallel refinement + 480px coarse frames). Full Video A release QA has not been re-run yet on a signed APK.
- App/version (code): 0.17.29 (versionCode 61).
- Previous published PASS: v0.17.28 (versionCode 60) — 89.011 s end-to-end on Video A API 35.
- Changes in this increment:
  - UI: numbered Source / ROI / Scan / Build sections, Diagnostics moved lower, tighter spacing, bold primary Build button.
  - Speed: Fast and Monotonic Turbo now use the same thermal-aware parallel refinement lanes as Quick Mode, and coarse target width 480px (Quick remains 384px; Precise stays 640px).
- First unresolved causal failure: release tag `v0.17.29` not yet created; GitHub Actions has not built the signed APK. Create and push tag `v0.17.29` on the current master tip to publish the APK, then update `app-update.json` after the asset exists.
