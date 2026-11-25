package app.hermes.updater.ui

import app.hermes.updater.FlowState

/** Semantic tone of a status line — the UI maps it to a colour, never the copy itself. */
enum class Tone { NEUTRAL, GOOD, WARN, BAD, BUSY }

data class StatusLine(val text: String, val tone: Tone)

/**
 * Pure copy for one catalog card. No Android, no Compose — so the wording and the state
 * machine are unit-testable, and the fail-closed message can never be styled away.
 *
 * Precedence is deliberate: a refusal outranks every progress and version message, because
 * "install refused" is the one thing the user must not miss.
 */
object CatalogCopy {

  const val NOT_INSTALLED = "not installed"

  fun status(
    pinned: Boolean,
    installed: String,
    latest: String?,
    failed: Boolean,
    available: Boolean,
    state: FlowState,
    checkError: String? = null,
  ): StatusLine = when {
    !pinned -> StatusLine("No signing pin — installs refused (fail closed)", Tone.BAD)
    state is FlowState.Downloading ->
      StatusLine(if (state.percent >= 0) "Downloading ${state.percent}%" else "Downloading…", Tone.BUSY)
    state is FlowState.Verifying -> StatusLine("Verifying checksum and signature…", Tone.BUSY)
    state is FlowState.Installing -> StatusLine("Opening the system installer…", Tone.BUSY)
    state is FlowState.WaitingForNetwork -> StatusLine(state.message, Tone.WARN)
    state is FlowState.Blocked -> StatusLine(state.message, Tone.BAD)
    state is FlowState.Failed -> StatusLine(state.message, Tone.BAD)
    row_missing(installed) && latest != null -> StatusLine("Not installed", Tone.WARN)
    available -> StatusLine("Update available", Tone.WARN)
    latest == null && !failed -> StatusLine("Checking…", Tone.NEUTRAL)
    failed -> StatusLine(checkError?.let { "Release check failed — $it" } ?: "Release check failed — tap Retry", Tone.BAD)
    else -> StatusLine("Up to date", Tone.GOOD)
  }

  /**
   * The card's single primary action, or null when there is nothing to do.
   * Installing a missing app is offered too — it goes through the identical fail-closed gate.
   */
  fun action(
    running: Boolean,
    pinned: Boolean,
    installed: String,
    latest: String?,
    failed: Boolean,
    available: Boolean,
  ): String? {
    if (!pinned) return null
    if (running) return "Working…"
    if (available) return "Update"
    if (row_missing(installed) && latest != null) return "Install"
    if (failed) return "Retry"
    return null
  }

  /** True while a download/verify/install is in flight — the action must be disabled then. */
  fun isRunning(state: FlowState): Boolean =
    state is FlowState.Downloading || state is FlowState.Verifying ||
      state is FlowState.Installing

  fun progressPercent(state: FlowState): Int? =
    (state as? FlowState.Downloading)?.percent?.takeIf { it >= 0 }

  fun installedLabel(installed: String): String =
    if (row_missing(installed)) "Not installed" else "Installed $installed"

  fun latestLabel(latest: String?, failed: Boolean): String = when {
    latest != null -> "Latest $latest"
    failed -> "Latest: check failed"
    else -> "Latest: …"
  }

  private fun row_missing(installed: String): Boolean =
    installed == NOT_INSTALLED || installed.isBlank()
}
