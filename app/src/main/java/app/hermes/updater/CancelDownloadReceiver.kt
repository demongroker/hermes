package app.hermes.updater

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Cancels one background download from its notification action. */
class CancelDownloadReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val packageName = intent.getStringExtra(DownloadWorker.EXTRA_PACKAGE_NAME) ?: return
    DownloadWorker.cancel(context, packageName)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancel(DownloadWorker.notificationId(packageName))
  }
}
