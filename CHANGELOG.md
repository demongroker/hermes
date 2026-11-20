# Changelog

## 0.1.6

### Changed — the dark theme is no longer black-on-black

- **Card surface lifted `#1B2740` → `#22314F`** and every card now carries a **1 dp `#2A3A57`
  edge**, so rows read as separate panels on an AMOLED screen instead of one dark mass. Same
  tokens as Vela, VideoDroid and JellyAmp (`design-tokens-navy-gold.md`).
- **Danger red `#F85149` → `#FF7469`.** The lighter card dropped the refusal colour to 3.87:1;
  it is now 4.91:1. Every text pair on the new card was measured, not assumed — body 11.00:1,
  muted 6.33:1, gold 6.16:1, success 5.10:1, warning 5.13:1.
- Light mode is unchanged: primary stays the darkened gold `#7A5F14` (6.04:1 on white).

## 0.1.5

### Fixed — updates died when you left the app

- **The download ran in the Activity's coroutine scope**, so switching apps stopped it mid-flight
  (you saw Vela die at 3%) and there was **no status-bar notification** because nothing was
  foregrounded. Downloads now run in `DownloadWorker` (WorkManager + `setForeground`), which
  keeps running in the background and posts a real progress notification you can watch and
  dismiss. Progress is published through a shared state flow, so the card still shows live
  percentage even if the download started before you reopened Hermes.
- Added the `FOREGROUND_SERVICE_DATA_SYNC` permission for Android 14+, and declared the
  `dataSync` foreground service type on WorkManager's `SystemForegroundService` so the release
  lint gate accepts the worker's `setForeground()` call.

### Added — launch an app from Hermes

- Every **installed** app now has an **Open** button alongside its update action, so you can
  jump straight into Vela/VideoDroid/JellyAmp without leaving Hermes. It disappears while that
  app is updating.

## 0.1.4
- **New look: navy + gold.** A real theme (`HermesTheme`) replaces the stock Material purple —
  dark and light schemes, one type scale, 12 dp cards / 8 dp controls. These tokens are the
  reference for Vela, VideoDroid and JellyAmp.
- **Catalog cards rebuilt.** App name, installed vs latest on one line, a colour-coded status
  line (green up-to-date, amber update, red refusal, gold in-flight), and a full-width primary
  action. A download now shows a real progress bar instead of text alone.
- **Install a missing app.** Previously a card with nothing installed offered no button at all;
  it now offers **Install**, through the identical fail-closed gate.
- **Check now.** Manual re-check in the header, with the last-checked time shown next to the
  status. No polling was added — the daily WorkManager check is unchanged.
- **Signing pins moved to a dialog.** The pin prefixes were a permanent third line on the main
  screen; they are now one tap away under "Pins", still explicit about fail-closed.
- **Metered prompt explains the guarantee** (checksum + certificate verified before install).
- Card copy and its single action are now a pure, unit-tested state machine (`CatalogCopy`),
  including the rule that a refusal outranks every progress and version message.

## 0.1.3
- Fixed: the "Hermes" title drew under the status bar (edge-to-edge on targetSdk 36) —
  the layout now applies safe-drawing insets.
- Fixed: blank screen while checking — the app list renders immediately from local package
  info; each app's latest version fills in as its own call returns, with a progress counter
  and a per-row "check failed" state plus a Retry button.
- New: proper app icon — navy adaptive icon with a gold H (and monochrome layer for
  Android 13+ themed icons), replacing the generic system download arrow.

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
