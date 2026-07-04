# Phase 13 - Sync-Safe Transition Export

## Cause
- Transition distortion was caused by exporting compressed video segments that started after the nearest keyframe.
- The muxer sought to the previous sync frame, then skipped samples before the requested cut start.
- When the first written frame after a section jump was a predicted frame, the decoder was missing reference frames and could briefly show blocky distortion or smeared frames.

## Fix
- Export segments are now aligned back to the previous video sync sample before muxing.
- Overlapping aligned segments are merged again after sync alignment.
- Transition padding increased from 500 ms to 1.5 seconds to reduce fragile cut edges around OCR-detected transitions.

## Result
- Section jumps should start on decodable video boundaries instead of partial GOP boundaries.
- The tradeoff is that a segment can begin slightly earlier than the exact requested timestamp, usually by less than one GOP.
