package app.hermes.updater

/**
 * Automatic retry for release checks. Pure JVM code: the delay is injected so the policy is
 * unit-testable without Android or real network calls.
 */
object CheckRetry {
  const val MAX_ATTEMPTS = 3

  fun backoffMs(failedAttempt: Int): Long = 500L shl (failedAttempt - 1)

  /** A failed check must never start a download: the card shows one state or the other. */
  fun canStartDownload(result: ReleaseResult): Boolean = result is ReleaseResult.Ok

  fun isRetryable(result: ReleaseResult): Boolean = when (result) {
    is ReleaseResult.Ok -> false
    is ReleaseResult.Offline -> true
    is ReleaseResult.HttpError -> result.code == 429 || result.code in 500..599
    is ReleaseResult.Missing -> false
  }

  fun <T> run(
    maxAttempts: Int = MAX_ATTEMPTS,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    retryable: (T) -> Boolean,
    check: () -> T,
  ): T {
    require(maxAttempts > 0)
    var attempt = 0
    while (true) {
      attempt++
      val result = check()
      if (attempt >= maxAttempts || !retryable(result)) return result
      sleep(backoffMs(attempt))
    }
  }

  fun latestRelease(
    client: GitHubClient,
    app: CatalogApp,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
  ): ReleaseResult = run(sleep = sleep, retryable = ::isRetryable) { client.latestReleaseResult(app) }
}
