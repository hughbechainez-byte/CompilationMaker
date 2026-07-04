# Phase 14.3 - Preview-First Compilation

## Faster processing
- Compilation now renders to app cache first and stops before copying to shared phone storage.
- Saving to phone storage only happens after approval, so iteration is faster when checking transitions.
- Muxing uses a larger direct buffer and less frequent export progress logging to reduce overhead.

## Preview before save
- Added a generated compilation preview panel.
- Preview does not autoplay.
- Added Play/Pause, scrubber, Save, and Discard controls.
- Save copies the reviewed compilation into phone storage.

## Transition mode
- Added a Transitions picker.
- Instant cuts keep the shortest cut boundaries.
- Gradual transitions add extra edge padding and merge very close segment gaps, giving the decoder more natural context around joins and reducing hard jump artifacts without re-encoding.

## Limit
- Gradual mode is not a true crossfade because the app still uses fast stream copy/remuxing rather than full video re-encoding.
