# v0.17.26 post-release comparison

The exact GitHub v0.17.26 APK was installed on the API 35 `sdk_gphone64_x86_64` emulator and exercised with Video A only. Video B remained host-side comparison data and was never scanner input.

| Metric | v0.17.25 pre-change | v0.17.26 post-release | Delta / interpretation |
|---|---:|---:|---|
| Coarse checkpoints | 61 | 61 | unchanged |
| Candidates | 14 | 14 | unchanged |
| Recursive probes | 30 | 30 | unchanged |
| Strict confirmed transitions | 8 | 6 | lower because the 15-minute deadline arrived during confirmation |
| OCR timeout evidence | 5 | 7 | two additional timeout observations; processing continued |
| Output / scan report | no / no | no / no | v0.17.26 stopped in `REFINING` before export |
| Primary-fixture result | FAIL before export (`Infinity`) | FAIL at deadline (`REFINING`) | serialization crash removed; confirmation budget remains unresolved |
| Video B SSIM/audio comparison | unavailable | unavailable | no output artifact exists to compare |

The post-release run provides no output artifact, so SSIM, audio correlation, duration, and clip-boundary comparison against Video B are intentionally `null` rather than estimated. The next implementation decision is to address confirmation-budget exhaustion and resumable candidate confirmation; no further iteration is started in this paused turn.
