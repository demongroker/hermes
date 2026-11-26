package app.hermes.updater

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Abandons a pending PackageInstaller session from its notification action. */
class CancelInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val packageName = intent.getStringExtra(DownloadWorker.EXTRA_PACKAGE_NAME) ?: return
    ApkInstaller.cancelInstall(context, packageName)
    InstallCoordinator.apply(packageName, InstallEvent.Aborted)
    UpdateNotifier.cancelForPackage(context, packageName)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancel(packageName.hashCode())
  }
}
