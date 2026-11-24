package app.hermes.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Daily update check. One periodic job, minimum interval 24 h — Hermes does not poll.
 *
 * The check only WARNS: it never downloads on its own unless the connection is unmetered (the
 * network rule lives in [NetworkPolicy], and the download itself is a user action).
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    val catalog = AppCatalog.apps
    val client = GitHubClient()
    val installer = ApkInstaller(ctx)
    val available = catalog.mapNotNull { app ->
      val latest = runCatching { client.latestRelease(app) }.getOrNull() ?: return@mapNotNull null
      val installed = installer.installedInfo(app.packageName)?.versionName
      val latestVersion = latest.tag.removePrefix("v")
      if (installed != null && !Versions.isNewer(latestVersion, installed)) null
      else app.displayName to latestVersion
    }
    if (available.isNotEmpty()) {
      UpdateNotifier.available(
        ctx,
        available.size,
        available.joinToString(", ") { (name, version) -> "$name $version" },
      )
    }
    return Result.success()
  }

  companion object {
    private const val CHANNEL = "updates"
    private const val WORK_NAME = "hermes-daily-update-check"

    /** Idempotent: enqueueing again keeps the existing schedule. */
    fun schedule(ctx: Context) {
      val request = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
      WorkManager.getInstance(ctx)
        .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
