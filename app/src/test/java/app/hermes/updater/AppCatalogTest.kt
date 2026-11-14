package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalog is the security boundary: a bad pin here is worse than a missing feature. */
class AppCatalogTest {

  @Test
  fun exactlyFourAppsAreTracked() {
    assertEquals(4, AppCatalog.apps.size)
    assertEquals(
      setOf("com.xerxes.videodroid", "com.demongroker.jellyamp", "app.vela.player", "app.hermes.updater"),
      AppCatalog.apps.map { it.packageName }.toSet(),
    )
  }

  @Test
  fun velaReadsReleasesFromTheSigningMirror() {
    assertEquals("demongroker/vela-build", AppCatalog.byPackage("app.vela.player")?.repo)
  }

  @Test
  fun everyPinIsAFullSha256Digest() {
    AppCatalog.apps.filter { it.certPins.isNotEmpty() }.forEach { app ->
      app.certPins.forEach { pin ->
        assertTrue(
          "${app.displayName} pin must be 64 hex chars, was ${pin.length}",
          pin.length == 64 && pin.all { it in "0123456789abcdef" },
        )
      }
    }
  }

  @Test
  fun hermesIsUnpinnedUntilItsFirstRelease() {
    // Fail-closed: an unpinned app can be listed and checked but never installed.
    assertTrue(AppCatalog.byId("hermes")!!.certPins.isEmpty())
    assertNotNull(AppCatalog.byId("vela"))
  }

  @Test
  fun apkPickingSkipsChecksumsAndArmOnlyBuilds() {
    val assets = listOf(
      Asset("vela-0.1.25-arm64.apk", "https://x/a", 1),
      Asset("vela-0.1.25.apk", "https://x/b", 1),
      Asset("vela-0.1.25.apk.sha256", "https://x/c", 1),
    )
    val picked = GitHubClient.pickApk(AppCatalog.byId("vela")!!, assets)
    assertEquals("vela-0.1.25.apk", picked?.name)
  }
}
