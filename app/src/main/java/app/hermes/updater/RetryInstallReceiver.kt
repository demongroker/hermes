package app.hermes.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Tap to retry" on a failed install notification: re-enqueue exactly that app. */
class RetryInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
    DownloadWorker.enqueue(context.applicationContext, packageName)
  }

  companion object {
    const val ACTION = "app.hermes.updater.RETRY_INSTALL"
    const val EXTRA_PACKAGE_NAME = "package_name"
  }
}
