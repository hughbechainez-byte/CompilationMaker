# Phase 14.1 - First Transition Anchor

## Cause
- The scanner only recorded transitions when it had both a previous number and a different detected number.
- The first visible `1` has no previous number yet, so it initialized scanner state without creating a compilation segment.
- In checkpoint mode, the first sampled number could be later than the first `1`, so the opening transition could be missed or start too late.

## Fix
- Added a dedicated first-`1` anchor before normal transition handling.
- When the scanner first sees any number and the first `1` has not been captured, it searches the preceding scan window for the earliest frame where OCR reads exactly `1`.
- The first-`1` timestamp is refined with a binary search down to roughly 250 ms.
- The normal segment builder then starts the compilation around 10 seconds before that first-`1` timestamp, with existing safety padding preserved.
