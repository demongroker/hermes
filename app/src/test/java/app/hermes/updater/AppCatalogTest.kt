package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalog is the security boundary: a bad pin here is worse than a missing feature. */
class AppCatalogTest {

  @Test
  fun exactlyFiveAppsAreTracked() {
    assertEquals(5, AppCatalog.apps.size)
    assertEquals(
      setOf(
        "com.xerxes.videodroid",
        "com.demongroker.jellyamp",
        "app.vela.player",
        "app.hermes.updater",
        "com.demongroker.hermesmonitor",
      ),
      AppCatalog.apps.map { it.packageName }.toSet(),
    )
  }

  @Test
  fun hermesMonitorIsPinnedFromItsFirstSignedRelease() {
    val monitor = AppCatalog.byId("hermes-monitor")!!
    assertEquals("demongroker/hermes-monitor-android", monitor.repo)
    assertEquals(1, monitor.certPins.size)
    assertEquals(64, monitor.certPins.single().length)
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
  fun hermesIsPinnedFromItsFirstSignedRelease() {
    // Hermes is now pinned like the rest: v0.1.0's certificate digest.
    val hermes = AppCatalog.byId("hermes")!!
    assertEquals(1, hermes.certPins.size)
    assertEquals(64, hermes.certPins.single().length)
    assertNotNull(AppCatalog.byId("vela"))
  }

  @Test
  fun everyAppIsPinned() {
    // No app may ship unpinned: an empty pin list means installs are refused.
    AppCatalog.apps.forEach { app ->
      assertTrue("${app.displayName} has no signing pin", app.certPins.isNotEmpty())
    }
  }

  @Test
  fun onlyTheThirdPartyAppsAreShareable() {
    // Hermes offers the other three apps' APKs to other phones, never its own.
    assertEquals(
      setOf("videodroid", "jellyamp", "vela"),
      AppCatalog.apps.filter { it.shareable }.map { it.id }.toSet(),
    )
    val hermes = AppCatalog.byId("hermes")!!
    assertFalse(hermes.shareable)
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
