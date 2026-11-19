# Changelog

## 0.1.2
- **Hermes can now actually install updates.** The download → verify → install flow is wired
  to the UI: an Update button per app, progress, plain-English gate refusals, and the system
  installer only after the full fail-closed gate passes. (0.1.1 checked and notified only.)
- Metered connections ask before downloading (NetworkPolicy); unmetered downloads on tap.
  Single-flight: one download at a time; partial files deleted on failure/refusal.
- Fixed: FileProvider was never declared — the installer intent would have crashed. Added
  with a paths file scoped to cache/updates only.
- Fixed: the GitHub token is no longer sent on asset fetches (HttpURLConnection re-sends
  headers on redirect to the CDN); API calls keep it.
- Fixed: "update available" now uses numeric version comparison (a downgrade no longer
  shows as an update); shared Versions logic with the daily worker.
- New tests: VersionsTest, RefusalTextTest.

## 0.1.1
- Hermes can now update itself (signer pinned).

## 0.1.0
- Catalog for VideoDroid, JellyAmp, Vela and Hermes.
- Fail-closed install gate: published `.sha256` → catalog signing pin → installed app's signer → size.
- Metered connections always ask before downloading; unmetered may download automatically.
- Daily WorkManager check (24 h minimum, no other polling) with one notification.
