package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real JellyAmp 1.2.13 release name must keep resolving after the check fixes. */
class JellyAmpReleaseTest {
  @Test
  fun jellyampUsesTheSigningMirrorAndTheUniversalApk() {
    val app = AppCatalog.apps.first { it.displayName == "JellyAmp" }
    assertEquals("demongroker/jellyamp-backup", app.repo)
    assertEquals("jellyamp-", app.assetPrefix)
    val assets = listOf(
      Asset("jellyamp-1.2.13.apk", "https://example.test/jellyamp-1.2.13.apk", 1),
      Asset("jellyamp-1.2.13.apk.sha256", "https://example.test/jellyamp-1.2.13.apk.sha256", 1),
      Asset("jellyamp-v1.2.13-linux-arm64.tar.gz", "https://example.test/arm64", 1),
    )
    val apk = GitHubClient.pickApk(app, assets)
    assertNotNull(apk)
    assertEquals("jellyamp-1.2.13.apk", apk!!.name)
    assertEquals("1.2.13", "v1.2.13".removePrefix("v"))
    assertTrue("app.hermes.updater" != app.packageName)
  }
}
