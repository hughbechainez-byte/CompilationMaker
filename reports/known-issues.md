# Known Issues

- Instrumentation writes per-run JSON to app-private storage; old entries are retained indefinitely.
- Frame extraction currently still uses multiple seeks through `MediaMetadataRetriever`.
