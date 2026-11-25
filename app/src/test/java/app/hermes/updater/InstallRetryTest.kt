package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallRetryTest {
  @Test
  fun oneAutomaticRetryOnlyForFailures() {
    assertTrue(InstallRetry.shouldRetry(InstallPhase.FAILED, 0))
    assertTrue(InstallRetry.shouldRetry(InstallPhase.TIMED_OUT, 0))
    assertTrue(InstallRetry.shouldRetry(InstallPhase.FAILED, 1))
    assertFalse(InstallRetry.shouldRetry(InstallPhase.FAILED, 2))
    assertFalse(InstallRetry.shouldRetry(InstallPhase.CANCELLED, 0))
    assertFalse(InstallRetry.shouldRetry(InstallPhase.SUCCEEDED, 0))
  }

  @Test
  fun exhaustedFailureShowsTapToRetry() {
    assertEquals(InstallRetry.TAP_TO_RETRY, InstallStateMachine.message(InstallPhase.FAILED))
    assertEquals(InstallRetry.TAP_TO_RETRY, InstallStateMachine.message(InstallPhase.TIMED_OUT))
    assertEquals(
      FlowState.Failed(InstallRetry.TAP_TO_RETRY),
      InstallStateMachine.flowState(InstallPhase.TIMED_OUT),
    )
  }
}
