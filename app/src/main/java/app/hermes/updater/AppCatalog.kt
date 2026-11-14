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
)

object AppCatalog {

  private const val VIDEODROID_PIN =
    "98c61591c05d6fb988c1cbcfc7fbd6bb6b39e062a5443db504855bcf12e29483"
  private const val JELLYAMP_PIN =
    "775d5032067a6686fbdaac577b29fef9e6d025e1cb4c35411b3879d849178d14"
  private const val VELA_PIN =
    "3f6aaaf0049ee820cecd478e43cda23041a6179371bfa870a196c6390fdb4aa3"

  val apps: List<CatalogApp> = listOf(
    CatalogApp(
      id = "videodroid",
      displayName = "VideoDroid",
      packageName = "com.xerxes.videodroid",
      repo = "demongroker/video-droid",
      assetPrefix = "VideoDroid-",
      certPins = listOf(VIDEODROID_PIN),
    ),
    CatalogApp(
      id = "jellyamp",
      displayName = "JellyAmp",
      packageName = "com.demongroker.jellyamp",
      repo = "demongroker/jellyamp",
      assetPrefix = "jellyamp-",
      certPins = listOf(JELLYAMP_PIN),
    ),
    CatalogApp(
      id = "vela",
      displayName = "Vela",
      packageName = "app.vela.player",
      // Signed builds are published by the mirror repo, not the source repo.
      repo = "demongroker/vela-build",
      assetPrefix = "vela-",
      certPins = listOf(VELA_PIN),
    ),
    CatalogApp(
      id = "hermes",
      displayName = "Hermes",
      packageName = "app.hermes.updater",
      repo = "demongroker/hermes",
      assetPrefix = "hermes-",
      // Pinned by the release workflow (see .github/workflows/release.yml notes).
      certPins = emptyList(),
    ),
  )

  fun byId(id: String): CatalogApp? = apps.firstOrNull { it.id == id }

  fun byPackage(packageName: String): CatalogApp? = apps.firstOrNull { it.packageName == packageName }
}
