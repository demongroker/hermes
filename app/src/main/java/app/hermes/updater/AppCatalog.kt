package app.hermes.updater

/**
 * The apps Hermes updates, and the signing identity each one must have.
 *
 * Every `certPins` entry is the SHA-256 digest of a certificate that the downloaded APK must
 * carry. A pin is never derived from the download itself — it was read once from a published
 * release, and it is the only thing stopping a hijacked release from being installed.
 *
 * A pin-list is fail-closed: an app with no pins can be listed but never installed.
 */
data class CatalogApp(
  val id: String,
  val displayName: String,
  val packageName: String,
  val repo: String,
  /** Asset name prefix used to tell this app's APK apart from others in a release. */
  val assetPrefix: String?,
  val certPins: List<String>,
  /**
   * Whether the installed APK may be offered to other apps through the share sheet. Only the
   * three third-party apps Hermes tracks — never Hermes itself.
   */
  val shareable: Boolean = false,
)

object AppCatalog {

  private const val VIDEODROID_PIN =
    "98c61591c05d6fb988c1cbcfc7fbd6bb6b39e062a5443db504855bcf12e29483"
  private const val JELLYAMP_PIN =
    "775d5032067a6686fbdaac577b29fef9e6d025e1cb4c35411b3879d849178d14"
  private const val HERMES_PIN =
    "241d8ec55894b9b9c2f6ea4e088dae8c4b8220249090d610b63d01029d1bb03c"

  private const val VELA_PIN =
    "3f6aaaf0049ee820cecd478e43cda23041a6179371bfa870a196c6390fdb4aa3"

  private const val HERMES_MONITOR_PIN =
    "c2f2214bd66bea25e640746741f726c019481228d4aca387123c8d4812ae54d9"

  val apps: List<CatalogApp> = listOf(
    CatalogApp(
      id = "videodroid",
      displayName = "VideoDroid",
      packageName = "com.xerxes.videodroid",
      repo = "demongroker/video-droid-backup",
      assetPrefix = "VideoDroid-",
      certPins = listOf(VIDEODROID_PIN),
      shareable = true,
    ),
    CatalogApp(
      id = "jellyamp",
      displayName = "JellyAmp",
      packageName = "com.demongroker.jellyamp",
      // Signed APK releases are published on the source repo again (1.2.14+);
      // the backup mirror has no releases, so /releases/latest returns 404 there.
      repo = "demongroker/jellyamp",
      assetPrefix = "jellyamp-",
      certPins = listOf(JELLYAMP_PIN),
      shareable = true,
    ),
    CatalogApp(
      id = "vela",
      displayName = "Vela",
      packageName = "app.vela.player",
      // Signed builds are published by the mirror repo, not the source repo.
      repo = "demongroker/vela-build",
      assetPrefix = "vela-",
      certPins = listOf(VELA_PIN),
      shareable = true,
    ),
    CatalogApp(
      id = "hermes",
      displayName = "Hermes",
      packageName = "app.hermes.updater",
      repo = "demongroker/hermes",
      assetPrefix = "hermes-",
      // Pinned from the first signed release (v0.1.0). Hermes can now update itself.
      certPins = listOf(HERMES_PIN),
    ),
    CatalogApp(
      id = "hermes-monitor",
      displayName = "Hermes Monitor",
      packageName = "com.demongroker.hermesmonitor",
      repo = "demongroker/hermes-monitor-android",
      assetPrefix = "hermes-monitor-",
      // Pinned from the first signed release (v1.0.0). Like Hermes itself, it is not
      // shareable — it is ours, not a third-party app.
      certPins = listOf(HERMES_MONITOR_PIN),
    ),
  )

  fun byId(id: String): CatalogApp? = apps.firstOrNull { it.id == id }

  fun byPackage(packageName: String): CatalogApp? = apps.firstOrNull { it.packageName == packageName }
}


/** Lookups used by the install gate and notifications; the package name is the canonical key. */
fun catalogById(id: String): CatalogApp? = AppCatalog.apps.firstOrNull { it.id == id }
fun catalogByPackage(packageName: String): CatalogApp? =
  AppCatalog.apps.firstOrNull { it.packageName == packageName }
