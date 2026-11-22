# Changelog

## 0.1.10

### Fixed — a dropped connection no longer throws the download away

- **The partial file is kept.** A dropped connection mid-download used to delete the `.part` file
  and report "Download failed — check the connection and try again", so a 40 MB download that died
  at 80 % started again from zero. The file is now kept and the card reads
  *"Connection lost — the download will resume when it is back"*.
- **The retry resumes instead of restarting.** The next attempt sends `Range: bytes=<done>-`; a
  `206` appends, a `200` (server ignored the range) truncates and restarts rather than corrupting
  the file.
- **WorkManager waits for the network.** Downloads are enqueued with a `NETWORK_CONNECTED`
  constraint and exponential backoff, and a transient drop returns `Result.retry()` — so the
  update completes on its own when the internet comes back, with no tap needed.
- Failures are now classified: a connection drop is transient (retry), an HTTP error such as 404
  is permanent (no pointless retry loop).
- The fail-closed gate is untouched: checksum and signing-certificate verification still runs on
  the complete file before anything is offered to the installer.
- New tests: `GitHubClientDownloadTest` (206 appends, 200 truncates, no `Range` on a fresh
  download, 404 is permanent, an IO failure keeps the partial file, plain http is refused before
  connecting, progress and unknown-total behaviour) and a `CatalogCopy` case for the new state.

### Changed — one compact page

- The five catalog apps now fit on **one screen**. Each card is a single dense row (name, installed
  and latest versions, status only when it is not "Up to date") with the Update action as a compact
  pill and Open/Share as icon actions, instead of five stacked full-width elements per card.

## 0.1.9

### Added — Hermes Monitor is now tracked

- The **Hermes Monitor** Android app (`com.demongroker.hermesmonitor`) appears in the list and is
  update-checked like the other four, pinned from its first signed release (v1.0.0) and reading
  releases from `demongroker/hermes-monitor-android`.
- Like Hermes itself it is **not shareable** — it is ours, not a third-party app, so the shareable
  set stays exactly VideoDroid, JellyAmp and Vela.
- Catalog test updated to five apps, with a new test asserting the Monitor's repo and pin.

## 0.1.8

### Added — Share the app itself

- **Share button on every tracked app card.** Copies the APK already installed on this phone into
  the app's `cacheDir/shared/` and hands it to the Android share sheet as a package archive, so a
  friend can sideload the exact same signed build. Hermes itself is deliberately **not** shareable.
- The `shareable` flag lives on `CatalogApp`, and the share decision, the installed-version file
  name and the not-installed / not-shareable / copy-failure reasons are unit-tested
  (`ApkSharerTest`).
- New `res/xml/file_paths.xml` entry exposes only that `shared/` cache path through the app's
  `FileProvider`; the rest of the cache stays private.

## 0.1.7

### Added — Update all

- **"Update all (n)"** in the header next to Check now, where **n is the number of cards with a
  real update available**. It disappears at zero. While any download is in flight it reads
  **"Updating n…"** and is disabled, so a batch can never be enqueued twice.
- **Parallel downloads.** One `DownloadWorker` per pending app, each under its own unique-work
  name — the same name the per-card button uses, so **single-flight per app is preserved** — and
  each with **its own progress notification** instead of one notification overwriting another.
- **One metered prompt per batch.** On mobile data the confirmation lists the whole batch once;
  you are no longer asked once per app.
- The "which rows are updatable" decision is now a **pure, unit-tested function**
  (`UpdatePlanner`), and the header count and the enqueue list come from the same call, so the
  number you see is the number that gets enqueued. A card with nothing installed is an *install*,
  not an update, and is deliberately left out of the batch.
- New tests: `UpdatePlanTest` (the updatable decision, batch order, unique work names) and
  `UpdateBatchTest` (the batch reuses the per-app worker/name; distinct notification ids).

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
