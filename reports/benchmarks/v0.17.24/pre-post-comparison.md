# v0.17.24 topology adjudication: pre/post comparison

## Decision

Accept the v0.17.24 OCR/state-timeline iteration and retain it as the new scanner baseline. The exact published APK reached 10/10 sequential transitions with no false positives, no rejected transitions, complete candidate coverage, and no candidate timeout. The primary fixture still fails overall because the default Gradual style padded and merged the first two requested windows, producing 9 exported clips and a 473.059-second sync-expanded output instead of 10 clips and 400 seconds.

The v0.17.24 release contains the already-shipped progress-aware candidate-deadline repair from v0.17.23 plus the new topology repair. The timeout result is therefore not attributed to topology alone.

## Exact-artifact methodology

- Release workflow: GitHub Actions run `29398169927`, successful; tag `v0.17.24`; commit `dc218dc013ee77b3cf5d6c2f6eb8aff4ebbf4856`.
- APK under test: exact GitHub asset `CompilationMaker-v0.17.24.apk`, SHA-256 `684127A92CE25E6265A90CE4809589111BA05BC841E7D22D1EF427B512D77D99`; HTTP 200; signer SHA-256 `4de9e2ef90cedac53118a377c1d7ccf9462f6a1ab5ad7a54f9c9b35005e5c8d8`.
- Test APK SHA-256: `0600DFC65A35396A1E5DD6FD773BE95EE59F451660973519CCE8451FE9F3278D`.
- Device: `sdk_gphone64_x86_64`, Android API 35, `emulator-5554`.
- Video A SHA-256: `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`.
- Host-only Video B SHA-256: `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`. Video B was removed from the emulator before the run and was never passed to the scanner.
- The test closed `MainActivity` immediately after starting processing. Launcher resumed; WorkManager `SystemForegroundService` remained foreground with its persistent notification until terminal success.

## Pre/post/delta

`v0.17.23` is the equivalent Activity-closed pre-topology baseline. `v0.17.22` is the required distribution/background baseline, and `v0.17.21` is the required best prior semantic baseline. Terminal runtime includes instrumentation wait/copy/assertion; throughput uses scanner wall time from the report.

| Field | v0.17.21 semantic baseline | v0.17.22 background baseline | v0.17.23 pre-topology | v0.17.24 post-topology | Delta v24-v23 |
| --- | --- | --- | --- | --- | --- |
| App version | 0.17.21 | 0.17.22 | 0.17.23 | 0.17.24 | +1 patch |
| Commit SHA | `845cbd6` | `f6c96ef02b39f123451cd563fd257880c8bf9679` | `4b1701a2213b5f503322fb6afba695532a82f3f9` | `dc218dc013ee77b3cf5d6c2f6eb8aff4ebbf4856` | changed |
| APK SHA-256 | `1B9B...02B10` | `CC58...62931` | `24C0...D197` | `6841...7D99` | changed |
| Test APK SHA-256 | `30A8...E72A` | `0600...278D` | `0600...278D` | `0600...278D` | none |
| Device / API | emulator / 35 | emulator / 35 | emulator / 35 | emulator / 35 | none |
| Fixture A / B hashes | same canonical pair | same canonical pair | same canonical pair | same canonical pair | none |
| Activity methodology | closure evidence not retained | closed | closed | closed | equivalent |
| Foreground service | not retained | verified | verified | verified | equivalent |
| Terminal wall-clock runtime | 515.464 s | 649.353 s | 438.614 s | 556.466 s | +117.852 s |
| Scanner wall time | 481.899 s | 626.967 s | 416.463 s | 522.754 s | +106.291 s |
| Video/scanner-wall throughput | 7.471x | 5.743x | 8.645x | 6.888x | -1.757x |
| Coarse checkpoint count | 61 | 61 | 61 | 61 | 0 |
| Complete candidate count | 22 | 22 | 22 | 14 | -8 |
| False `6/9` state-status oscillations | not serialized | not serialized | 15 | 0 | -15 |
| Wrong/unstable true-`6/9` checkpoints | not serialized | not serialized | 14 | 0 | -14 |
| Recursive probe count | not serialized | not serialized | not serialized | 30 | newly preserved |
| Semantic leaf / mark count | 6 | 1 | 7 | 10 | +3 |
| OCR call count | 617 | 560 | 625 | 556 | -69 |
| Candidate timeout count | not serialized | 7 | 0 | 0 | 0 |
| Accepted transitions | 6 | 1 | 7 | 10 | +3 |
| Rejected transitions | 1 | 7 | 0 | 0 | 0 |
| True positives | 6 | 1 | 7 | 10 | +3 |
| False positives | 0 | 0 | 0 | 0 | 0 |
| False negatives | 4 | 9 | 3 | 0 | -3 |
| Precision | 100% | 100% | 100% | 100% | 0 pp |
| Recall | 60% | 10% | 70% | 100% | +30 pp |
| Candidate coverage of expected marks | complete | complete | complete | complete | retained |
| Exported clip count | 6 | 1 | 6 | 9 | +3, but gate requires 10 |
| Output duration | 286.601 s | 46.784 s | 330.601 s | 473.059 s | +142.458 s; gate requires 400 s |
| Output size | 2,775,864 B | 404,143 B | 3,144,919 B | 4,565,178 B | +1,420,259 B |
| Video B duration / size | 400.000 s / 4,495,038 B | same | same | same | none |
| Full-frame 2 fps SSIM | not available | not available | not available | mean 0.987709; p5 0.972179; 0 failures below 0.90 outside exclusions | newly measured |
| Audio comparison | not available | not available | not available | undefined: both decoded mono 16 kHz tracks are all-zero PCM | inconclusive / gate red |
| First unresolved causal failure | incomplete state timeline | fixed candidate deadline | ML Kit `6/9` state corruption | Gradual padding/merge distorts exact clip plan; sync-sample muxing expands boundaries | moved downstream |

The oscillation count is the number of adjacent changes among `6`, `9`, and `UNSTABLE` inside regions whose host label remains true `6` or true `9`. The wrong/unstable count is the number of checkpoints in those regions that were not the correct stable state.

## Boundary evidence

| Expected transition | v0.17.21 | v0.17.22 | v0.17.23 | v0.17.24 |
| --- | ---: | ---: | ---: | ---: |
| `null -> 1` at 30,000 ms | miss | -235 ms | -235 ms | -235 ms |
| `1 -> 2` at 75,000 ms | -235 ms | miss | -235 ms | -235 ms |
| `2 -> 3` at 255,000 ms | -235 ms | miss | -235 ms | -235 ms |
| `3 -> 4` at 855,000 ms | -235 ms | miss | -235 ms | -235 ms |
| `4 -> 5` at 975,000 ms | -235 ms | miss | -235 ms | -235 ms |
| `5 -> 6` at 1,275,000 ms | -235 ms | miss | -235 ms | -235 ms |
| `6 -> 7` at 1,395,000 ms | miss | miss | miss | -235 ms |
| `7 -> 8` at 1,995,000 ms | -235 ms | miss | -235 ms | -235 ms |
| `8 -> 9` at 2,415,000 ms | miss | miss | miss | -235 ms |
| `9 -> 10` at 3,560,000 ms | miss | miss | miss | -79 ms |

All v0.17.24 marks satisfy the host-only ±500 ms validator tolerance and the implementation-plan ±1,000 ms gate.

## Focused stable-state evidence

The arrows below are `raw ML Kit -> final adjudicated value`; no expected number was supplied to production recognition.

| Host-side region | v0.17.23 votes/state | v0.17.24 raw -> final votes/state |
| --- | --- | --- |
| True `6`, 1,320,000 ms | `6,6,9,9,9` -> unstable under the no-competitor-two rule | `6->6,6->6,9->6,9->6,9->6` -> stable `6` |
| True `6`, 1,380,000 ms | `9,9,9,9,9` -> stable false `9` | five `9->6` topology corrections -> stable `6` |
| True `9`, 2,460,000 ms | `9,6,6,9,9` -> unstable | every raw vote adjudicated to `9` -> stable `9` |
| True `9`, 2,580,000 ms | `6,6,9,6,6` -> stable false `6` | `6->9,6->9,9->9,6->9,6->9` -> stable `9` |
| Final true `9`, 3,540,000 ms | `6,6,9,9,6` -> unstable | every raw vote adjudicated to `9` -> stable `9`; 3,600,000 ms remains stable `10` |

Across the full post-transition true-`9` run from 2,460,000 through 3,540,000 ms, v0.17.23 alternated among unstable, false `6`, and `9`; v0.17.24 classified all 19 checkpoints as stable `9`.

## Media comparison interpretation

The output carries H.264 video and AAC audio. Video duration is 472.620 seconds and audio duration is 473.059 seconds, already failing the 400,000 ±2,000 ms fixture gate. Direct full-frame SSIM nevertheless passes because the small number overlay contributes little to the otherwise matching frame; SSIM alone cannot validate clip timing. Both output and Video B decode to all-zero mono PCM, so normalized audio correlation has zero variance and is undefined rather than a pass.

## Next causal repair

1. Build clip plans from exact `[transition-10s, transition+30s]` windows; UI transition style must not add edge padding or merge a non-overlapping gap.
2. Replace previous-sync-sample muxing with Media3 Transformer composition at the exact requested boundaries.
3. Automate duration, per-frame SSIM, silence-aware audio diagnostics, decode probes, and per-clip comparison, then release and test the exact published APK again.
