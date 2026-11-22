package app.hermes.updater

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for the 0.1.5 fixes.
 *
 * Both bugs were invisible to unit tests and obvious on a phone: a download bound to the
 * Activity scope (killed by backgrounding) and a missing foreground-service permission.
 * These tests pin the two source facts that keep them fixed.
 */
class BackgroundDownloadTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  @Test
  fun downloadsRunInAWorkerNotTheActivityScope() {
    val main = File(root, "app/src/main/java/app/hermes/updater/MainActivity.kt").readText()
    assertTrue("startFlow must hand the download to WorkManager", main.contains("DownloadWorker.enqueue("))
    assertFalse(
      "the download must not be launched in the Activity coroutine scope",
      main.contains("scope.launch {\n        val release"),
    )
  }

  @Test
  fun dataSyncForegroundPermissionIsDeclared() {
    val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
    assertTrue(manifest.contains("FOREGROUND_SERVICE_DATA_SYNC"))
  }

  @Test
  fun openActionOnlyForInstalledApps() {
    val main = File(root, "app/src/main/java/app/hermes/updater/MainActivity.kt").readText()
    // The Open action is an icon button now; its contentDescription still carries the label.
    assertTrue(
      "Open must stay gated on the app being installed",
      main.contains("contentDescription = \"Open \${row.app.displayName}\""),
    )
    assertTrue(main.contains("row.installed != CatalogCopy.NOT_INSTALLED"))
  }

  @Test
  fun workerPublishesProgress() {
    val worker = File(root, "app/src/main/java/app/hermes/updater/DownloadWorker.kt").readText()
    assertTrue(worker.contains("setForeground("))
    assertTrue(worker.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
    assertTrue(worker.contains("MutableStateFlow"))
  }
}
