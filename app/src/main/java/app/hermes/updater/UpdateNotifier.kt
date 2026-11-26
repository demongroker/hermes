package app.hermes.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Status-bar notifications for updates, all on one channel named **Updates**.
 *
 * The foreground worker notification is separate (Android requires one for a data-sync
 * foreground service); these are the ones the owner actually reads: ready-to-install with a tap
 * that reopens the installer, updated, failed, and the "N updates available" summary.
 */
object UpdateNotifier {

  const val CHANNEL_ID = "updates"
  const val CHANNEL_NAME = "Updates"
  const val SUMMARY_ID = 1
  const val ACTION_CANCEL_INSTALL = "app.hermes.updater.CANCEL_INSTALL"

  fun ensureChannel(ctx: Context) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Download, install and update results"
      },
    )
  }

  /** True when the owner has not revoked notifications (Android 13+ asks at runtime). */
  fun enabled(ctx: Context): Boolean = runCatching {
    NotificationManagerCompat.from(ctx).areNotificationsEnabled()
  }.getOrDefault(false)

  /** "<App> <version> ready — tap to install"; tapping reopens the system installer. */
  fun ready(ctx: Context, packageName: String, name: String, version: String, apk: File?) {
    if (!enabled(ctx)) return
    ensureChannel(ctx)
    val tap = apk?.takeIf { it.exists() }?.let { file ->
      runCatching {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, "application/vnd.android.package-archive")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PendingIntent.getActivity(ctx, DownloadWorker.notificationId(packageName), intent, pendingFlags())
      }.getOrNull()
    }
    val cancel = PendingIntent.getBroadcast(
      ctx,
      DownloadWorker.notificationId(packageName) + 1000,
      Intent(ctx, CancelInstallReceiver::class.java)
        .setAction(ACTION_CANCEL_INSTALL)
        .putExtra(DownloadWorker.EXTRA_PACKAGE_NAME, packageName),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    post(
      ctx,
      DownloadWorker.notificationId(packageName),
      "$name $version ready — tap to install",
      "Hermes verified the checksum and the signing certificate.",
      tap,
      cancel,
    )
  }

  fun updated(ctx: Context, packageName: String, name: String, version: String) {
    if (!enabled(ctx)) return
    ensureChannel(ctx)
    post(ctx, DownloadWorker.notificationId(packageName), "$name updated to $version", "Installed from its signed release.", null)
  }

  fun failed(ctx: Context, packageName: String, name: String, reason: String, retry: Boolean = false) {
    if (!enabled(ctx)) return
    ensureChannel(ctx)
    val id = DownloadWorker.notificationId(packageName)
    val tap = if (retry) runCatching {
      PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, RetryInstallReceiver::class.java)
          .setAction(RetryInstallReceiver.ACTION)
          .putExtra(RetryInstallReceiver.EXTRA_PACKAGE_NAME, packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
      )
    }.getOrNull() else null
    post(ctx, id, "$name update failed", reason, tap)
  }

  /** Clear every status notification for one package after its install succeeds. */
  fun cancelForPackage(ctx: Context, packageName: String) {
    runCatching {
      NotificationManagerCompat.from(ctx).cancel(DownloadWorker.notificationId(packageName))
    }
  }

  /** The daily background check's summary. */
  fun available(ctx: Context, count: Int, text: String) {
    if (!enabled(ctx)) return
    ensureChannel(ctx)
    post(ctx, SUMMARY_ID, "$count update(s) available", text, null)
  }

  private fun post(
    ctx: Context,
    id: Int,
    title: String,
    text: String,
    tap: PendingIntent?,
    action: PendingIntent? = null,
  ) {
    runCatching {
      val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
      tap?.let { builder.setContentIntent(it) }
      action?.let { builder.addAction(android.R.drawable.ic_delete, "Cancel install", it) }
      NotificationManagerCompat.from(ctx).notify(id, builder.build())
    }
  }

  private fun pendingFlags(): Int =
    PendingIntent.FLAG_UPDATE_CURRENT or
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE
      } else {
        0
      }
}
