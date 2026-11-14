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
      if (installed != null && !isNewer(latestVersion, installed)) null
      else app.displayName to latestVersion
    }
    if (available.isNotEmpty()) notify(ctx, available)
    return Result.success()
  }

  /** Dotted numeric comparison; anything unparseable counts as "not newer". */
  private fun isNewer(latest: String, installed: String): Boolean {
    val a = latest.split('.').mapNotNull { it.toIntOrNull() }
    val b = installed.split('.').mapNotNull { it.toIntOrNull() }
    if (a.isEmpty() || b.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
      val x = a.getOrElse(i) { 0 }
      val y = b.getOrElse(i) { 0 }
      if (x != y) return x > y
    }
    return false
  }

  private fun notify(ctx: Context, updates: List<Pair<String, String>>) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val text = updates.joinToString(", ") { (name, version) -> "$name $version" }
    nm.notify(
      1,
      NotificationCompat.Builder(ctx, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("${updates.size} update(s) available")
        .setContentText(text)
        .setAutoCancel(true)
        .build(),
    )
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
