package app.hermes.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One install phase per catalog app, shared between whoever starts an install (the download
 * worker) and whoever renders it (the activity). The worker waits on [awaitTerminal]; the UI
 * collects [phases] so a card can never stay stuck on "Opening the system installer…".
 */
object InstallCoordinator {

  private val _phases = MutableStateFlow<Map<String, InstallPhase>>(emptyMap())
  val phases = _phases.asStateFlow()

  fun phase(appId: String): InstallPhase = _phases.value[appId] ?: InstallPhase.IDLE

  /** Call just before handing the APK to Android. */
  fun begin(appId: String) = apply(appId, InstallEvent.Handled)

  fun apply(appId: String, event: InstallEvent) {
    val next = InstallStateMachine.reduce(phase(appId), event)
    if (next == phase(appId)) return
    _phases.value = _phases.value.toMutableMap().apply { put(appId, next) }
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
  ): InstallPhase {
    val settled = withTimeoutOrNull(timeoutMs) {
      phases.first { map -> InstallStateMachine.isTerminal(map[appId] ?: InstallPhase.IDLE) }
      phase(appId)
    } ?: InstallPhase.TIMED_OUT
    if (settled == InstallPhase.TIMED_OUT && !InstallStateMachine.isTerminal(phase(appId))) {
      apply(appId, InstallEvent.Timeout)
    }
    return phase(appId)
  }
}

/** Result receiver for the `PackageInstaller.Session.commit` PendingIntent. */
class InstallStatusReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val appId = intent.getStringExtra(EXTRA_APP_ID) ?: return
    val status = intent.getIntExtra(
      android.content.pm.PackageInstaller.EXTRA_STATUS,
      android.content.pm.PackageInstaller.STATUS_FAILURE,
    )
    InstallCoordinator.apply(appId, InstallStatus.eventFor(status))
  }

  companion object {
    const val ACTION = "app.hermes.updater.INSTALL_STATUS"
    const val EXTRA_APP_ID = "app_id"
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
    val appId = AppCatalog.apps.firstOrNull { it.packageName == packageName }?.id ?: return
    InstallCoordinator.apply(appId, InstallEvent.Succeeded)
  }
}
