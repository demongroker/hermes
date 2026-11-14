# Changelog

## 0.1.1
- Hermes can now update itself (signer pinned).

## 0.1.0
- Catalog for VideoDroid, JellyAmp, Vela and Hermes.
- Fail-closed install gate: published `.sha256` → catalog signing pin → installed app's signer → size.
- Metered connections always ask before downloading; unmetered may download automatically.
- Daily WorkManager check (24 h minimum, no other polling) with one notification.
