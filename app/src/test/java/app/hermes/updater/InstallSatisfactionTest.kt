package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIX-ALL-001 Part A4: no false "failed", and the row never stays stuck. */
class InstallSatisfactionTest {

  @Test
  fun `installed equal to target satisfies the install`() {
    assertTrue(InstallStateMachine.satisfiedByInstalled(113L, 113L))
  }

  @Test
  fun `installed newer than target satisfies the install`() {
    // The owner may already have sideloaded a newer build; that is not a failure.
    assertTrue(InstallStateMachine.satisfiedByInstalled(113L, 114L))
  }

  @Test
  fun `older installed version does not satisfy`() {
    assertFalse(InstallStateMachine.satisfiedByInstalled(113L, 112L))
  }

  @Test
  fun `missing package or unknown target is not satisfaction`() {
    assertFalse(InstallStateMachine.satisfiedByInstalled(113L, null))
    assertFalse(InstallStateMachine.satisfiedByInstalled(null, 113L))
    assertFalse(InstallStateMachine.satisfiedByInstalled(null, null))
  }

  @Test
  fun `timeout offers a retry instead of leaving it working`() {
    val phase = InstallStateMachine.reduce(InstallPhase.WAITING_FOR_USER, InstallEvent.Timeout)
    assertEquals(InstallPhase.TIMED_OUT, phase)
    assertEquals(FlowState.Failed(InstallRetry.TAP_TO_RETRY), InstallStateMachine.flowState(phase))
    assertNotNull(InstallStateMachine.message(phase))
  }

  @Test
  fun `late success after a pending user action wins`() {
    val waiting = InstallStateMachine.reduce(InstallPhase.OPENING, InstallEvent.Handled)
    assertEquals(InstallPhase.WAITING_FOR_USER, waiting)
    val done = InstallStateMachine.reduce(waiting, InstallEvent.Succeeded)
    assertEquals(InstallPhase.SUCCEEDED, done)
    assertEquals(FlowState.Idle, InstallStateMachine.flowState(done))
  }
}
