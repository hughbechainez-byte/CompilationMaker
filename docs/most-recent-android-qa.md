# Most recent Android QA

- Result: **PENDING TAG** — code for v0.17.31 (versionCode 63) is on `master`.
- App/version (code): 0.17.31 (versionCode 63).
- Previous published PASS: v0.17.28 (versionCode 60).
- Changes:
  - **Speed (Fast / Pixel-class):** parallel lane budget up to 6 when cool + ≥8 cores; Fast coarse width 320px; coarse OCR starts raw-only (aggressive only on miss).
  - **Auto-delete source** after verified successful export (best-effort; requires delete permission on the document URI).
  - **Batch cleanup:** secondary launcher activity `CleanupActivity` lists originals that still exist and have a recorded compilation; multi-select delete.
- Tag `v0.17.31` to publish signed APK.
