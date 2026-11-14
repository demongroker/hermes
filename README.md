# Hermes

One place to see whether the apps on this phone are current, and to install the update safely.

Hermes tracks four apps:

| App | Package | Releases from |
| --- | --- | --- |
| VideoDroid | `com.xerxes.videodroid` | `demongroker/video-droid` |
| JellyAmp | `com.demongroker.jellyamp` | `demongroker/jellyamp` |
| Vela | `app.vela.player` | `demongroker/vela-build` (the signing mirror) |
| Hermes | `app.hermes.updater` | `demongroker/hermes` |

## How an update is accepted

Nothing is installed until all of these agree, in this order:

1. **The published checksum.** Hermes downloads the release's `.sha256` asset and compares it
   with the file it just fetched. No checksum published → refuse.
2. **The signing pin.** Every app in the catalog carries the SHA-256 digest of the certificate
   its releases are signed with, read once from a published APK. A downloaded APK whose signer
   is not pinned is refused — fail closed, including for an app with no pin yet.
3. **The installed app's signer.** The download must also match the certificate of the copy
   already on the phone, so an update can only ever be a continuation of the same app.
4. **Size.** A file under 1 MB is treated as a truncated transfer regardless of its hash.

Only then is the system installer opened. Every refusal is a plain-English message, never a
silent no-op.

## Data

- No analytics, no accounts, no server. Hermes talks to the public GitHub Releases API.
- An optional GitHub token can be stored to raise the API rate limit. It lives in
  `EncryptedSharedPreferences` and is never logged.
- `allowBackup` is off: pins and tokens are not written to a cloud backup.

## Network

Metered connection (5G/LTE/hotspot) → Hermes **always asks first**. Wi-Fi/Ethernet/VPN →
it may download automatically. A daily WorkManager job (minimum interval 24 h, no other
polling) checks for updates and posts one notification.

## Building

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleRelease     # needs HERMES_KEYSTORE + the three password env vars
```

## Licence

MIT — see [LICENSE](LICENSE).
