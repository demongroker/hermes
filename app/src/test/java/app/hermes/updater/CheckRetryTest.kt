package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckRetryTest {
  @Test
  fun backsOffTwiceForTransientFailures() {
    assertEquals(500L, CheckRetry.backoffMs(1))
    assertEquals(1000L, CheckRetry.backoffMs(2))
    var calls = 0
    val delays = mutableListOf<Long>()
    val result = CheckRetry.run(
      sleep = { delays.add(it) },
      retryable = { it is ReleaseResult.Offline },
    ) {
      calls++
      if (calls < 3) ReleaseResult.Offline("IOException") else ReleaseResult.Missing("done")
    }
    assertTrue(result is ReleaseResult.Missing)
    assertEquals(3, calls)
    assertEquals(listOf(500L, 1000L), delays)
  }

  @Test
  fun permanentErrorsAreNotRetried() {
    var calls = 0
    CheckRetry.run(sleep = { throw AssertionError("no delay") }, retryable = CheckRetry::isRetryable) {
      calls++
      ReleaseResult.HttpError(404)
    }
    assertEquals(1, calls)
    assertFalse(CheckRetry.isRetryable(ReleaseResult.HttpError(404)))
    assertTrue(CheckRetry.isRetryable(ReleaseResult.HttpError(503)))
    assertTrue(CheckRetry.isRetryable(ReleaseResult.HttpError(429)))
    assertTrue(CheckRetry.isRetryable(ReleaseResult.Offline("SocketTimeoutException")))
    assertFalse(CheckRetry.isRetryable(ReleaseResult.Missing("no assets")))
  }

  @Test
  fun errorTextNeverCarriesSecretsOrUrls() {
    assertEquals("GitHub replied HTTP 504", ReleaseResult.HttpError(504).message)
    assertTrue(ReleaseResult.Offline("SocketTimeoutException").message.startsWith("Connection lost"))
  }
}
