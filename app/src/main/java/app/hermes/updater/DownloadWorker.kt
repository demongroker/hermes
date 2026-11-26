package app.hermes.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    val ids = inputData.getStringArray(KEY_PACKAGE_NAMES)?.toList()
      ?: inputData.getStringArray(KEY_APP_IDS)?.toList()
      ?: listOfNotNull(inputData.getString(KEY_PACKAGE_NAME), inputData.getString(KEY_APP_ID))
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
    val app = catalogById(appId) ?: catalogByPackage(appId) ?: return false
    // The package name is the canonical key for jobs and notifications; app.id is the
    // canonical key for UI state.
    val pkg = app.packageName
    cancelled.remove(app.id)
    var tag = ""
    publish(app.id, FlowState.Downloading(-1))

    // Drives the foreground progress notification from the shared state flow. Deliberately NOT
    // `coroutineScope { launch { ... } }`: coroutineScope waits for its children, and a
    // never-completing `states.collect` would block doWork forever.
    val notifier = GlobalScope.launch {
      states.collect { map ->
        val st = map[app.id] ?: return@collect
        val text = when (st) {
          is FlowState.Downloading -> "Downloading ${app.displayName}"
          FlowState.Verifying -> "Verifying ${app.displayName}"
          FlowState.Installing -> {
            UpdateNotifier.ready(
              ctx,
              pkg,
              app.displayName,
              tag.removePrefix("v"),
              apkFile(pkg, tag),
            )
            "Opening the installer for ${app.displayName}"
          }
          is FlowState.WaitingForNetwork -> "Waiting for a connection"
          is FlowState.Blocked -> st.message
          is FlowState.Failed -> st.message
          FlowState.Idle -> "Idle"
        }
        val pct = (st as? FlowState.Downloading)?.percent ?: -1
        runCatching { setForeground(foregroundInfo(pkg, app.displayName, text, pct)) }
      }
    }

    var attempts = 0
    try {
      while (attempts < MAX_ATTEMPTS) {
        attempts++
        val result = CheckRetry.latestRelease(client, app)
        if (!CheckRetry.canStartDownload(result)) {
          // Real error text on the card; the retry policy above already tried the check.
          publish(app.id, FlowState.Failed(result.message))
          return false
        }
        val release = (result as ReleaseResult.Ok).release
        tag = release.tag

        var finalPhase = InstallPhase.IDLE
        val done = UpdateFlow(ctx, client, installer).run(
          app,
          release,
          { publish(app.id, it) },
          { isStopped || cancelled.contains(app.id) },
        ) { dest ->
          // Downloads may run in parallel; only the installer hand-off and its result wait
          // are serialized, so Android never sees two concurrent install confirmations.
          InstallCoordinator.withInstallGate {
            var installAttempt = 0
            var phase = InstallPhase.FAILED
            do {
              installAttempt++
              InstallCoordinator.begin(pkg)
              phase = if (installer.install(pkg, dest)) {
                val target = installer.archiveVersionCode(dest)
                InstallCoordinator.awaitTerminal(pkg, InstallStateMachine.TIMEOUT_MS) {
                  InstallStateMachine.satisfiedByInstalled(target, installer.installedVersionCode(pkg))
                }
              } else {
                InstallCoordinator.apply(pkg, InstallEvent.Failed)
                InstallPhase.FAILED
              }
            } while (InstallRetry.shouldRetry(phase, installAttempt))
            finalPhase = phase
            phase
          }
        }
        if (!done) {
          // Dropped mid-download: keep the partial file and let WorkManager resume it.
          publish(app.id, FlowState.WaitingForNetwork(WAITING_MESSAGE))
          if (!NetworkWaiter.awaitOnline(ctx, ATTEMPT_WAIT_MS)) return false
          continue
        }
        return reportOutcome(ctx, app, tag, finalPhase)
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
    val pkg = app.packageName
    val version = tag.removePrefix("v")
    val message = InstallStateMachine.message(phase) ?: "Install failed"
    return when (phase) {
      InstallPhase.SUCCEEDED -> {
        UpdateNotifier.cancelForPackage(ctx, pkg)
        UpdateNotifier.updated(ctx, pkg, app.displayName, version)
        publish(app.id, FlowState.Idle)
        InstallCoordinator.clear(pkg)
        true
      }
      InstallPhase.CANCELLED -> {
        UpdateNotifier.failed(ctx, pkg, app.displayName, "Install cancelled", retry = false)
        publish(app.id, FlowState.Failed("Install cancelled"))
        InstallCoordinator.clear(pkg)
        true
      }
      InstallPhase.FAILED, InstallPhase.TIMED_OUT -> {
        // The single automatic retry already ran inside the install gate. The notification
        // and the card now offer a manual retry keyed by this package.
        UpdateNotifier.failed(ctx, pkg, app.displayName, message, retry = true)
        publish(app.id, FlowState.Failed(message))
        InstallCoordinator.clear(pkg)
        true
      }
      else -> false
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
    val cancelIntent = Intent(ctx, CancelDownloadReceiver::class.java).apply {
      action = ACTION_CANCEL_DOWNLOAD
      putExtra(EXTRA_PACKAGE_NAME, appId)
    }
    val cancel = PendingIntent.getBroadcast(
      ctx,
      notificationId(appId),
      cancelIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = NotificationCompat.Builder(ctx, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(name)
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setProgress(100, pct.coerceIn(0, 100), pct < 0)
      .addAction(android.R.drawable.ic_delete, "Cancel", cancel)
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
    const val KEY_PACKAGE_NAME = "package_name"
    const val KEY_PACKAGE_NAMES = "package_names"
    // Legacy keys are read only, for work queued by an older build.
    const val KEY_APP_ID = "app_id"
    const val KEY_APP_IDS = "app_ids"

    const val ACTION_CANCEL_DOWNLOAD = "app.hermes.updater.CANCEL_DOWNLOAD"
    const val EXTRA_PACKAGE_NAME = "package_name"

    /** Matches the worker's own backoff; the UI copy just says what is happening. */
    private const val WAITING_MESSAGE = "Connection lost — resuming when it is back"

    /** Per-attempt wait for the network to return before handing the retry to WorkManager. */
    private const val ATTEMPT_WAIT_MS = 5 * 60 * 1000L
    private const val MAX_ATTEMPTS = 3

    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private const val CHANNEL = "updates"
    private const val BATCH_WORK_NAME = "hermes-download-batch"
    private const val NOTIFICATION_ID_BASE = 7

    /** Live progress, keyed by app id — survives the Activity, shared with the UI. */
    private val _states = MutableStateFlow<Map<String, FlowState>>(emptyMap())
    val states = _states.asStateFlow()

    /** Single-app path. Keeps the exact unique name the batch path uses per app. */
    fun enqueue(ctx: Context, packageName: String) {
      enqueue(ctx, listOf(packageName), UpdatePlanner.workName(packageName), ExistingWorkPolicy.REPLACE)
    }

    /**
     * Batch path: ONE request carrying every app id, so [DownloadWorker] can walk them in order
     * instead of firing parallel downloads and parallel installers.
     */
    fun enqueueAll(ctx: Context, packageNames: List<String>) {
      if (packageNames.isEmpty()) return
      enqueue(ctx, packageNames, BATCH_WORK_NAME, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(ctx: Context, appIds: List<String>, uniqueName: String, policy: ExistingWorkPolicy) {
      val request = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(
          Data.Builder().putStringArray(KEY_PACKAGE_NAMES, appIds.toTypedArray()).build(),
        )
        // Wait for a connection instead of failing while offline: a queued update survives the
        // network dropping, and the retry after a mid-download drop resumes the partial file.
        .setConstraints(
          Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()
      WorkManager.getInstance(ctx).enqueueUniqueWork(uniqueName, policy, request)
    }

    /** A distinct notification id per app. */
    fun notificationId(packageName: String): Int {
      val index = AppCatalog.apps.indexOfFirst { it.packageName == packageName }
      return NOTIFICATION_ID_BASE + if (index >= 0) index else packageName.hashCode().mod(1000)
    }

    /** Cancel a running download and clear its card state. */
    fun cancel(ctx: Context, packageName: String) {
      val app = catalogByPackage(packageName)
      if (app != null) cancelled.add(app.id)
      val wm = WorkManager.getInstance(ctx)
      wm.cancelUniqueWork(UpdatePlanner.workName(packageName))
      // The batch worker has one shared name; cancelling it stops every app in that batch.
      wm.cancelUniqueWork(BATCH_WORK_NAME)
      clear(catalogByPackage(packageName)?.id ?: packageName)
    }

    /** Clear one stale state (e.g. a check that failed while a card looked busy). */
    fun clear(appId: String) {
      _states.value = _states.value.toMutableMap().apply { remove(appId) }
    }

    /** Drop non-terminal states left behind by a killed worker after a reboot/relaunch. */
    fun clearStale() {
      _states.value = _states.value.filterValues {
        it is FlowState.Idle || it is FlowState.Failed || it is FlowState.Blocked
      }
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
