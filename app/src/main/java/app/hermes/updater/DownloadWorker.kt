package app.hermes.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Downloads and installs one app's update **in the background**.
 *
 * Why a worker and not a coroutine in the activity: a download started from
 * `rememberCoroutineScope()` dies with the Activity, so switching apps killed it at 3% and the
 * owner saw no notification at all. WorkManager keeps the download alive and `setForeground`
 * puts a real progress notification in the status bar (Android 14+ requires the data-sync type).
 *
 * Progress is published to [states] so the UI can show the same state whether the work started
 * this session or is still finishing from a previous one.
 */
class DownloadWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val id = inputData.getString(KEY_APP_ID) ?: return Result.failure()
    val app = AppCatalog.apps.firstOrNull { it.id == id } ?: return Result.failure()
    val ctx = applicationContext
    val client = GitHubClient()
    val installer = ApkInstaller(ctx)

    setForeground(foregroundInfo(app.displayName, "Waiting for connection", -1))
    publish(id, FlowState.Downloading(-1))

    val release = runCatching { client.latestRelease(app) }.getOrNull()
    if (release == null) {
      publish(id, FlowState.Failed("Could not read the latest release."))
      return Result.retry()
    }

    // The flow callback is non-suspend (GitHubClient.download is blocking), so the progress
    // notification is driven from the shared state flow instead of from inside the callback.
    val notifier = kotlinx.coroutines.launch {
      states.collect { map ->
        val st = map[id] ?: return@collect
        val (text, pct) = when (st) {
          is FlowState.Downloading -> "Downloading ${app.displayName}" to st.percent
          FlowState.Verifying -> "Verifying ${app.displayName}" to -1
          FlowState.Installing -> "Installing ${app.displayName}" to -1
          is FlowState.Blocked -> st.message to -1
          is FlowState.Failed -> st.message to -1
          FlowState.Idle -> "Idle" to -1
        }
        runCatching { setForeground(foregroundInfo(app.displayName, text, pct)) }
      }
    }
    val flow = UpdateFlow(ctx, client, installer)
    flow.run(app, release) { state -> publish(id, state) }
    notifier.cancel()
    return Result.success()
  }

  private fun foregroundInfo(name: String, text: String, pct: Int): ForegroundInfo {
    val ctx = applicationContext
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Download and install progress"
      },
    )
    val builder = NotificationCompat.Builder(ctx, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(name)
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setProgress(100, pct.coerceIn(0, 100), pct < 0)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(NOTIFICATION_ID, builder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(NOTIFICATION_ID, builder.build())
    }
  }

  private fun publish(id: String, state: FlowState) {
    val next = _states.value.toMutableMap()
    next[id] = state
    _states.value = next
  }

  companion object {
    const val KEY_APP_ID = "app_id"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7

    /** Live progress, keyed by app id — survives the Activity, shared with the UI. */
    private val _states = MutableStateFlow<Map<String, FlowState>>(emptyMap())
    val states = _states.asStateFlow()

    fun enqueue(ctx: Context, appId: String) {
      val request = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(Data.Builder().putString(KEY_APP_ID, appId).build())
        .build()
      WorkManager.getInstance(ctx).enqueueUniqueWork(
        "hermes-download-$appId",
        androidx.work.ExistingWorkPolicy.KEEP,
        request,
      )
    }

    /** Launches an installed app straight from Hermes, or null when it has no launcher entry. */
    fun launchIntent(ctx: Context, packageName: String): android.content.Intent? = runCatching {
      ctx.packageManager.getLaunchIntentForPackage(packageName)
    }.getOrNull()

    fun isInstalled(ctx: Context, packageName: String): Boolean = runCatching {
      ctx.packageManager.getPackageInfo(packageName, 0)
      true
    }.getOrDefault(false)
  }
}
