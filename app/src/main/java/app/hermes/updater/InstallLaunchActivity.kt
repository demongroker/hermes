package app.hermes.updater

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import java.io.File

/** Foreground user gesture that opens the cached APK in the system installer. */
class InstallLaunchActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
    val apk = packageName?.let { pkg ->
      File(cacheDir, "updates").listFiles { f ->
        f.name.startsWith("$pkg-") && f.name.endsWith(".apk")
      }?.maxByOrNull { it.lastModified() }
    }
    val opened = apk != null && ApkInstaller(this).installManually(apk)
    if (!opened) {
      val message = when {
        packageName == null -> "InstallLaunchActivity: missing package name"
        apk == null -> "APK not found in cache/updates for $packageName"
        else -> "Could not open the system installer for ${apk.name}"
      }
      setContentView(TextView(this).apply {
        text = message + "\n\nCopy this text and send it to Fury."
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
      })
      return
    }
    finish()
  }

  companion object {
    const val EXTRA_PACKAGE_NAME = "package_name"
  }
}
