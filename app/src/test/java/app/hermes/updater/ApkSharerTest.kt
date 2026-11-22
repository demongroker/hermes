package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared file's name carries the installed version, so two builds of the same app never
 * overwrite each other's copy in the cache. Pure naming only — the copy itself needs a device.
 */
class ApkSharerTest {

  private val app = AppCatalog.byId("videodroid")!!

  @Test
  fun fileNameCarriesTheDisplayNameAndInstalledVersion() {
    assertEquals("VideoDroid-2.5.8.apk", ApkSharer.apkFileName(app, "2.5.8"))
  }

  @Test
  fun missingVersionStillProducesAUsableName() {
    assertEquals("VideoDroid-installed.apk", ApkSharer.apkFileName(app, null))
  }

  @Test
  fun everyShareableAppNameIsAValidFileName() {
    AppCatalog.apps.filter { it.shareable }.forEach { catalogApp ->
      val name = ApkSharer.apkFileName(catalogApp, "1.0.0")
      assertEquals("${catalogApp.displayName}-1.0.0.apk", name)
      assertTrue(name.endsWith(".apk") && !name.contains("/"))
    }
  }
}
