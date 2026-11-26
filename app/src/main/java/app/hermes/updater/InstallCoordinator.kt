package app.hermes.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One install phase per catalog app, shared between whoever starts an install (the download
 * worker) and whoever renders it (the activity). The worker waits on [awaitTerminal]; the UI
 * collects [phases] so a card can never stay stuck on "Opening the system installer…".
 */
object InstallCoordinator {

  private val _phases = MutableStateFlow<Map<String, InstallPhase>>(emptyMap())
  val phases = _phases.asStateFlow()

  /** One APK at a time may be in Android's system installer, process-wide. */
  private val installGate = Mutex()
  suspend fun <T> withInstallGate(block: suspend () -> T): T = installGate.withLock { block() }

  fun phase(appId: String): InstallPhase = _phases.value[appId] ?: InstallPhase.IDLE

  /** Call just before handing the APK to Android. */
  fun begin(appId: String) = apply(appId, InstallEvent.Handled)

  fun apply(appId: String, event: InstallEvent) {
    val next = InstallStateMachine.reduce(phase(appId), event)
    if (next == phase(appId)) return
    _phases.value = _phases.value.toMutableMap().apply { put(appId, next) }
  }

  /**
   * High-priority "tap to install" heads-up while the system confirm dialog is up, so the
   * owner doesn't miss it if Hermes is backgrounded.
   */
  fun notifyConfirmNeeded(context: Context, packageName: String, confirm: Intent? = null) {
    runCatching {
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nm.createNotificationChannel(
          android.app.NotificationChannel(
            "install_confirm",
            "Install confirmation",
            android.app.NotificationManager.IMPORTANCE_HIGH,
          ),
        )
      }
      val app = catalogByPackage(packageName)
      val text = "${app?.displayName ?: packageName}: tap to finish installing"
      // Always launch a foreground activity on tap: a broadcast receiver cannot start the
      // installer from the background on modern Android.
      val pi = android.app.PendingIntent.getActivity(
        context,
        packageName.hashCode(),
        Intent(context, InstallLaunchActivity::class.java)
          .putExtra(InstallLaunchActivity.EXTRA_PACKAGE_NAME, packageName)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
      )
      val notification = androidx.core.app.NotificationCompat.Builder(context, "install_confirm")
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Install ready")
        .setContentText(text)
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pi)
        .addAction(android.R.drawable.stat_sys_download_done, "Install", pi)
        .setAutoCancel(true)
        .build()
      nm.notify(packageName.hashCode(), notification)
    }
  }

  /** Back to idle, e.g. after the UI has shown the outcome and rechecked the versions. */
  fun clear(appId: String) {
    _phases.value = _phases.value.toMutableMap().apply { remove(appId) }
  }

  /**
   * Waits for a terminal phase, then applies [InstallEvent.Timeout] when nothing arrived in
   * time. Returns the phase that ended the wait, so the caller can report it.
   */
  suspend fun awaitTerminal(
    appId: String,
    timeoutMs: Long = InstallStateMachine.TIMEOUT_MS,
    satisfied: (() -> Boolean)? = null,
  ): InstallPhase {
    val settled = withTimeoutOrNull(timeoutMs) {
      phases.first { map -> InstallStateMachine.isTerminal(map[appId] ?: InstallPhase.IDLE) }
      phase(appId)
    } ?: InstallPhase.TIMED_OUT
    if (settled == InstallPhase.TIMED_OUT && !InstallStateMachine.isTerminal(phase(appId))) {
      // A1: the installer may have succeeded without telling us. Check the package manager
      // before reporting anything, so a working update is never announced as a failure.
      val ok = runCatching { satisfied?.invoke() ?: false }.getOrDefault(false)
      apply(appId, if (ok) InstallEvent.Succeeded else InstallEvent.Timeout)
    }
    return phase(appId)
  }
}

/** Result receiver for the `PackageInstaller.Session.commit` PendingIntent. */
class InstallStatusReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
    val status = intent.getIntExtra(
      android.content.pm.PackageInstaller.EXTRA_STATUS,
      android.content.pm.PackageInstaller.STATUS_FAILURE,
    )
    if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
      // A2: the confirm screen is waiting. Bring it up even if we are in the background,
      // and leave a high-priority way back in.
      val confirm = intent.getParcelableExtra<Intent>("android.content.pm.extra.INTENT")
      if (confirm != null) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirm) }
      }
      InstallCoordinator.notifyConfirmNeeded(context, packageName, confirm)
    }
    ApkInstaller.clearSession(packageName)
    InstallCoordinator.apply(packageName, InstallStatus.eventFor(status))
  }

  companion object {
    const val ACTION = "app.hermes.updater.INSTALL_STATUS"
    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_APP_ID = EXTRA_PACKAGE_NAME // legacy alias
  }
}

/**
 * Safety net for installs that went out through `ACTION_VIEW` (and for a missed session
 * callback): Android broadcasts `PACKAGE_ADDED` / `PACKAGE_REPLACED` once a package is really
 * installed, which is the outcome Hermes cares about.
 */
class InstallEventsReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_PACKAGE_ADDED && intent.action != Intent.ACTION_PACKAGE_REPLACED) {
      return
    }
    val packageName = intent.data?.schemeSpecificPart ?: return
    InstallCoordinator.apply(packageName, InstallEvent.Succeeded)
    UpdateNotifier.cancelForPackage(context, packageName)
  }
}
