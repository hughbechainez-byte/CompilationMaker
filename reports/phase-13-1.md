# Phase 13.1 - Update Manifest Fetch Fix

## Cause
- The app only fetched `app-update.json` through the GitHub contents API.
- The parser expected a contents API wrapper with `encoding` and `content`, and failed silently if the response shape differed or if raw JSON was needed.
- Forced update checks also skipped display if the same latest version had already been marked as notified.

## Fix
- The updater now fetches the raw GitHub manifest first.
- It falls back to the GitHub contents API if the raw endpoint fails.
- Manifest parsing now accepts both raw JSON and base64-wrapped GitHub contents responses.
- Manual forced checks can re-display the available update even if the version was previously notified.
