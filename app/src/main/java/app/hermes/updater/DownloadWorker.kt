package app.hermes.updater

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads and installs updates **in the background**, one app at a time.
 *
 * Why a worker and not a coroutine in the activity: a download started from
 * `rememberCoroutineScope()` dies with the Activity, so switching apps killed it at 3% and the
 * owner saw no notification at all. WorkManager keeps the download alive and `setForeground`
 * puts a real progress notification in the status bar (Android 14+ requires the data-sync type).
 *
 * The batch path hands over a list of app ids and this worker walks it **sequentially**: the next
 * app only starts after the previous one reached a terminal state, so "Update all" can never run
 * two installers at once or double as a parallel download storm.
 *
 * Progress is published to [states] so the UI can show the same state whether the work started
 * this session or is still finishing from a previous one.
 */
class DownloadWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val ids = inputData.getStringArray(KEY_APP_IDS)?.toList()
      ?: listOfNotNull(inputData.getString(KEY_APP_ID))
    if (ids.isEmpty()) return Result.failure()

    val ctx = applicationContext
    val client = GitHubClient()
    val installer = ApkInstaller(ctx)

    var allOk = true
    for (id in ids) {
      if (!runOne(ctx, client, installer, id)) allOk = false
    }
    return if (allOk) Result.success() else Result.retry()
  }

  /**
   * One app end to end: read the release, download (resuming a partial file after a drop),
   * verify, hand to the installer, then wait for the real outcome.
   *
   * @return true when this app reached a finished state (including cancelled/failed installs);
   *         false when the network is gone and WorkManager should run this again later.
   */
  private suspend fun runOne(
    ctx: Context,
    client: GitHubClient,
    installer: ApkInstaller,
    appId: String,
  ): Boolean {
    val app = AppCatalog.apps.firstOrNull { it.id == appId } ?: return false
    publish(appId, FlowState.Downloading(-1))

    // Drives the foreground progress notification from the shared state flow. Deliberately NOT
    // `coroutineScope { launch { ... } }`: coroutineScope waits for its children, and a
    // never-completing `states.collect` would block doWork forever.
    val notifier = GlobalScope.launch {
      states.collect { map ->
        val st = map[appId] ?: return@collect
        val text = when (st) {
          is FlowState.Downloading -> "Downloading ${app.displayName}"
          FlowState.Verifying -> "Verifying ${app.displayName}"
          FlowState.Installing -> {
            UpdateNotifier.ready(
              ctx,
              app.id,
              app.displayName,
              latestTag.value?.removePrefix("v") ?: "",
              apkFile(app.id, latestTag.value ?: ""),
            )
            "Opening the installer for ${app.displayName}"
          }
          is FlowState.WaitingForNetwork -> "Waiting for a connection"
          is FlowState.Blocked -> st.message
          is FlowState.Failed -> st.message
          FlowState.Idle -> "Idle"
        }
        val pct = (st as? FlowState.Downloading)?.percent ?: -1
        runCatching { setForeground(foregroundInfo(app.id, app.displayName, text, pct)) }
      }
    }

    var attempts = 0
    try {
      while (attempts < MAX_ATTEMPTS) {
        attempts++
        val release = runCatching { client.latestRelease(app) }.getOrNull()
        if (release == null) {
          publish(appId, FlowState.WaitingForNetwork(WAITING_MESSAGE))
          // Wait for the network instead of parking on "Connection lost".
          if (!NetworkWaiter.awaitOnline(ctx, ATTEMPT_WAIT_MS)) return false
          continue
        }
        latestTag.value = release.tag

        val done = UpdateFlow(ctx, client, installer).run(app, release) { publish(appId, it) }
        if (!done) {
          // Dropped mid-download: the partial file is kept and resumes with Range on retry.
          publish(appId, FlowState.WaitingForNetwork(WAITING_MESSAGE))
          if (!NetworkWaiter.awaitOnline(ctx, ATTEMPT_WAIT_MS)) return false
          continue
        }

        // Handed to the system installer: now wait for its actual result (success, the user
        // backing out, a failure, or the timeout fallback) before touching the next app.
        val apkFile = java.io.File(java.io.File(ctx.cacheDir, "updates"), "${app.id}-${release.tag}.apk")
        val target = installer.archiveVersionCode(apkFile)
        val phase = InstallCoordinator.awaitTerminal(
          appId,
          InstallStateMachine.TIMEOUT_MS,
        ) {
          InstallStateMachine.satisfiedByInstalled(target, installer.installedVersionCode(app.packageName))
        }
        return reportOutcome(ctx, app, release.tag, phase)
      }
      return false
    } finally {
      notifier.cancel()
    }
  }

  private fun reportOutcome(
    ctx: Context,
    app: CatalogApp,
    tag: String,
    phase: InstallPhase,
  ): Boolean {
    val version = tag.removePrefix("v")
    val message = InstallStateMachine.message(phase)
    return when (phase) {
      InstallPhase.SUCCEEDED -> {
        UpdateNotifier.updated(ctx, app.id, app.displayName, version)
        publish(app.id, FlowState.Idle)
        InstallCoordinator.clear(app.id)
        true
      }
      InstallPhase.CANCELLED -> {
        UpdateNotifier.failed(ctx, app.id, app.displayName, "Install cancelled")
        publish(app.id, FlowState.Failed("Install cancelled"))
        InstallCoordinator.clear(app.id)
        true
      }
      InstallPhase.FAILED -> {
        UpdateNotifier.failed(ctx, app.id, app.displayName, message ?: "Install failed")
        publish(app.id, FlowState.Failed(message ?: "Install failed"))
        InstallCoordinator.clear(app.id)
        true
      }
      InstallPhase.TIMED_OUT -> {
        UpdateNotifier.failed(ctx, app.id, app.displayName, message ?: "Install timed out")
        // The card resets to Install; the reason is kept for the UI's snackbar.
        publish(app.id, FlowState.Idle)
        InstallCoordinator.clear(app.id)
        true
      }
      else -> {
        // Still waiting for the installer: leave the state alone and let WorkManager retry.
        false
      }
    }
  }

  private fun apkFile(appId: String, tag: String): File =
    File(File(applicationContext.cacheDir, "updates"), "$appId-$tag.apk")

  private fun foregroundInfo(appId: String, name: String, text: String, pct: Int): ForegroundInfo {
    val ctx = applicationContext
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, UpdateNotifier.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
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
      ForegroundInfo(notificationId(appId), builder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(notificationId(appId), builder.build())
    }
  }

  private fun publish(id: String, state: FlowState) {
    _states.value = _states.value.toMutableMap().apply { put(id, state) }
  }

  companion object {
    const val KEY_APP_ID = "app_id"
    const val KEY_APP_IDS = "app_ids"

    /** Matches the worker's own backoff; the UI copy just says what is happening. */
    private const val WAITING_MESSAGE = "Connection lost — resuming when it is back"

    /** Per-attempt wait for the network to return before handing the retry to WorkManager. */
    private const val ATTEMPT_WAIT_MS = 5 * 60 * 1000L
    private const val MAX_ATTEMPTS = 3

    private const val CHANNEL = "updates"
    private const val BATCH_WORK_NAME = "hermes-download-batch"
    private const val NOTIFICATION_ID_BASE = 7

    private val latestTag = MutableStateFlow<String?>(null)

    /** Live progress, keyed by app id — survives the Activity, shared with the UI. */
    private val _states = MutableStateFlow<Map<String, FlowState>>(emptyMap())
    val states = _states.asStateFlow()

    /** Single-app path. Keeps the exact unique name the batch path uses per app. */
    fun enqueue(ctx: Context, appId: String) {
      enqueue(ctx, listOf(appId), UpdatePlanner.workName(appId))
    }

    /**
     * Batch path: ONE request carrying every app id, so [DownloadWorker] can walk them in order
     * instead of firing parallel downloads and parallel installers.
     */
    fun enqueueAll(ctx: Context, appIds: List<String>) {
      if (appIds.isEmpty()) return
      enqueue(ctx, appIds, BATCH_WORK_NAME)
    }

    private fun enqueue(ctx: Context, appIds: List<String>, uniqueName: String) {
      val request = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(
          Data.Builder().putStringArray(KEY_APP_IDS, appIds.toTypedArray()).build(),
        )
        // Wait for a connection instead of failing while offline: a queued update survives the
        // network dropping, and the retry after a mid-download drop resumes the partial file.
        .setConstraints(
          Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()
      WorkManager.getInstance(ctx).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    /** A distinct notification id per app. */
    fun notificationId(appId: String): Int {
      val index = AppCatalog.apps.indexOfFirst { it.id == appId }
      return NOTIFICATION_ID_BASE + if (index >= 0) index else appId.hashCode().mod(1000)
    }

    fun launchIntent(ctx: Context, packageName: String): android.content.Intent? = runCatching {
      ctx.packageManager.getLaunchIntentForPackage(packageName)
    }.getOrNull()

    fun isInstalled(ctx: Context, packageName: String): Boolean = runCatching {
      ctx.packageManager.getPackageInfo(packageName, 0)
      true
    }.getOrDefault(false)
  }
}
