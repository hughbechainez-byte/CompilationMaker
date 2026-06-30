# Architecture notes

- Single-activity app with embedded extraction/compilation engine class (`VideoCompilationEngine`).
- Engine currently uses `MediaMetadataRetriever` frame extraction and ML Kit OCR in ROI.
- Export currently uses `MediaExtractor` + `MediaMuxer` concatenation.
