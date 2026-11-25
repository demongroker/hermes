package app.hermes.updater

/**
 * Pure install state machine — deliberately free of Android types so every transition is
 * unit-testable.
 *
 * Why it exists: after Hermes hands an APK to the system installer the old code had no way to
 * learn the outcome, so the card stayed on "Opening the system installer…" and the top button
 * stayed "Updating n…" forever. Now the installer's own result (session callback, package
 * receiver, or the timeout fallback) feeds this machine, and the UI can never be left hanging.
 */
enum class InstallPhase {
  /** Nothing is happening. */
  IDLE,

  /** The APK has been verified and is being handed to Android. */
  OPENING,

  /** The system installer / confirm dialog is in front of the user. No result yet. */
  WAITING_FOR_USER,

  /** The package manager confirms the install. */
  SUCCEEDED,

  /** `STATUS_FAILURE_ABORTED` — the user backed out of the confirm dialog. */
  CANCELLED,

  /** Any other installer failure (`STATUS_FAILURE`, `STATUS_FAILURE_BLOCKED`, …). */
  FAILED,

  /** No result arrived within [InstallStateMachine.TIMEOUT_MS]. */
  TIMED_OUT,
}

/** Events that move an install forward. */
sealed interface InstallEvent {
  /** Hermes handed the APK over (session committed or installer activity started). */
  data object Handled : InstallEvent

  /** The system reported success. */
  data object Succeeded : InstallEvent

  /** The user backed out. */
  data object Aborted : InstallEvent

  /** The installer reported a failure. */
  data object Failed : InstallEvent

  /** The two-minute fallback fired. */
  data object Timeout : InstallEvent
}

object InstallStateMachine {

  /** How long to wait for the installer before deciding it produced no result. */
  const val TIMEOUT_MS = 2 * 60 * 1000L

  val initial: InstallPhase get() = InstallPhase.IDLE

  /**
   * Did the package manager already install what we were aiming at? Used before declaring a
   * timeout a failure: the system installer can report success late (or the user can confirm
   * from the recents screen after the callback was missed), and the app really is updated.
   */
  fun satisfiedByInstalled(target: Long?, installed: Long?): Boolean =
    target != null && installed != null && installed >= target

  fun isTerminal(phase: InstallPhase): Boolean = when (phase) {
    InstallPhase.SUCCEEDED, InstallPhase.CANCELLED,
    InstallPhase.FAILED, InstallPhase.TIMED_OUT,
    -> true
    else -> false
  }

  /**
   * Apply one event. Unknown events are ignored, so a late broadcast from an earlier attempt
   * cannot corrupt a newer install.
   */
  fun reduce(phase: InstallPhase, event: InstallEvent): InstallPhase = when (event) {
    // A fresh hand-off always moves to waiting, including a retry after a terminal phase.
    InstallEvent.Handled -> InstallPhase.WAITING_FOR_USER
    InstallEvent.Succeeded -> InstallPhase.SUCCEEDED
    InstallEvent.Aborted -> InstallPhase.CANCELLED
    InstallEvent.Failed -> InstallPhase.FAILED
    // The timeout is only meaningful while something is actually waiting; otherwise it is a
    // stale alarm from an attempt that already finished.
    InstallEvent.Timeout ->
      if (phase == InstallPhase.WAITING_FOR_USER || phase == InstallPhase.OPENING) {
        InstallPhase.TIMED_OUT
      } else {
        phase
      }
  }

  /** The card's status line for a phase; null when the phase is not worth a line of its own. */
  fun message(phase: InstallPhase): String? = when (phase) {
    InstallPhase.CANCELLED -> "Install cancelled"
    InstallPhase.FAILED -> "Install failed"
    InstallPhase.TIMED_OUT -> "Install didn't finish — tap Install to try again"
    else -> null
  }

  /**
   * The [FlowState] the UI should show. Success is [FlowState.Idle]: the card goes back to
   * normal and the next resume/recheck reads the newly installed version.
   */
  fun flowState(phase: InstallPhase): FlowState = when (phase) {
    InstallPhase.SUCCEEDED -> FlowState.Idle
    InstallPhase.CANCELLED -> FlowState.Failed("Install cancelled")
    InstallPhase.FAILED -> FlowState.Failed("Install failed")
    // The row goes back to a plain Install button so it can never sit on "Working…".
    InstallPhase.TIMED_OUT -> FlowState.Idle
    else -> FlowState.Installing
  }
}

/**
 * Maps a `PackageInstaller` status int onto an [InstallEvent].
 *
 * `STATUS_PENDING_USER_ACTION` is not a result — the system is about to ask the user — so it
 * maps to [InstallEvent.Handled] and the phase simply stays [InstallPhase.WAITING_FOR_USER].
 */
object InstallStatus {
  fun eventFor(status: Int): InstallEvent = when (status) {
    android.content.pm.PackageInstaller.STATUS_SUCCESS -> InstallEvent.Succeeded
    android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvent.Aborted
    android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallEvent.Handled
    else -> InstallEvent.Failed
  }
}
