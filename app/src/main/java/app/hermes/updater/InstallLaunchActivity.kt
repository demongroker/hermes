package app.hermes.updater

import android.app.Activity
import android.os.Bundle
import java.io.File

/** Foreground user gesture that opens the cached APK in the system installer. */
class InstallLaunchActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
    if (packageName != null) {
      val apk = File(cacheDir, "updates").listFiles { f ->
        f.name.startsWith("$packageName-") && f.name.endsWith(".apk")
      }?.maxByOrNull { it.lastModified() }
      if (apk != null) ApkInstaller(this).installManually(apk)
    }
    finish()
  }

  companion object {
    const val EXTRA_PACKAGE_NAME = "package_name"
  }
}
