package app.hermes.updater

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The install machine is what stops a card hanging on "Opening the system installer…". */
class InstallStateMachineTest {

  @Test
  fun handOffWaitsForTheUser() {
    assertEquals(
      InstallPhase.WAITING_FOR_USER,
      InstallStateMachine.reduce(InstallPhase.IDLE, InstallEvent.Handled),
    )
    assertEquals(
      InstallPhase.WAITING_FOR_USER,
      InstallStateMachine.reduce(InstallPhase.OPENING, InstallEvent.Handled),
    )
  }

  @Test
  fun successCancelAndFailureAreTerminal() {
    assertEquals(
      InstallPhase.SUCCEEDED,
      InstallStateMachine.reduce(InstallPhase.WAITING_FOR_USER, InstallEvent.Succeeded),
    )
    assertEquals(
      InstallPhase.CANCELLED,
      InstallStateMachine.reduce(InstallPhase.WAITING_FOR_USER, InstallEvent.Aborted),
    )
    assertEquals(
      InstallPhase.FAILED,
      InstallStateMachine.reduce(InstallPhase.WAITING_FOR_USER, InstallEvent.Failed),
    )
  }

  @Test
  fun timeoutOnlyCountsWhileWaiting() {
    assertEquals(
      InstallPhase.TIMED_OUT,
      InstallStateMachine.reduce(InstallPhase.WAITING_FOR_USER, InstallEvent.Timeout),
    )
    assertEquals(
      InstallPhase.TIMED_OUT,
      InstallStateMachine.reduce(InstallPhase.OPENING, InstallEvent.Timeout),
    )
    assertEquals(InstallPhase.IDLE, InstallStateMachine.reduce(InstallPhase.IDLE, InstallEvent.Timeout))
    assertEquals(
      InstallPhase.SUCCEEDED,
      InstallStateMachine.reduce(InstallPhase.SUCCEEDED, InstallEvent.Timeout),
    )
  }

  @Test
  fun aRetryAfterATerminalPhaseStartsWaitingAgain() {
    assertEquals(
      InstallPhase.WAITING_FOR_USER,
      InstallStateMachine.reduce(InstallPhase.CANCELLED, InstallEvent.Handled),
    )
  }

  @Test
  fun terminalPhasesAreRecognised() {
    assertTrue(InstallStateMachine.isTerminal(InstallPhase.SUCCEEDED))
    assertTrue(InstallStateMachine.isTerminal(InstallPhase.CANCELLED))
    assertTrue(InstallStateMachine.isTerminal(InstallPhase.FAILED))
    assertTrue(InstallStateMachine.isTerminal(InstallPhase.TIMED_OUT))
    assertFalse(InstallStateMachine.isTerminal(InstallPhase.IDLE))
    assertFalse(InstallStateMachine.isTerminal(InstallPhase.WAITING_FOR_USER))
  }

  @Test
  fun theCardNeverSaysWorkingAfterACancelOrFailure() {
    assertTrue(InstallStateMachine.flowState(InstallPhase.SUCCEEDED) is FlowState.Idle)
    val cancelled = InstallStateMachine.flowState(InstallPhase.CANCELLED)
    assertTrue(cancelled is FlowState.Failed)
    assertEquals("Install cancelled", (cancelled as FlowState.Failed).message)
    // After the automatic retry is spent the card offers a tappable retry.
    val timedOut = InstallStateMachine.flowState(InstallPhase.TIMED_OUT)
    assertTrue(timedOut is FlowState.Failed)
    assertEquals(InstallRetry.TAP_TO_RETRY, (timedOut as FlowState.Failed).message)
    assertEquals(
      InstallRetry.TAP_TO_RETRY,
      InstallStateMachine.message(InstallPhase.TIMED_OUT),
    )
    assertNull(InstallStateMachine.message(InstallPhase.SUCCEEDED))
    assertEquals("Install cancelled", InstallStateMachine.message(InstallPhase.CANCELLED))
  }

  @Test
  fun sessionStatusesMapToEvents() {
    assertEquals(InstallEvent.Succeeded, InstallStatus.eventFor(PackageInstaller.STATUS_SUCCESS))
    assertEquals(InstallEvent.Aborted, InstallStatus.eventFor(PackageInstaller.STATUS_FAILURE_ABORTED))
    assertEquals(InstallEvent.Handled, InstallStatus.eventFor(PackageInstaller.STATUS_PENDING_USER_ACTION))
    assertEquals(InstallEvent.Failed, InstallStatus.eventFor(PackageInstaller.STATUS_FAILURE))
  }
}
