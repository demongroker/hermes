package app.hermes.updater

/** What to do with an available update on the current connection. */
enum class NetworkDecision {
  /** Unmetered (Wi-Fi/Ethernet/VPN): download without asking. */
  AUTO_DOWNLOAD,

  /** Metered (5G/LTE/hotspot): always ask first, then download on the user's tap. */
  ASK_FIRST,
}

object NetworkPolicy {

  /**
   * Pure so the rule is testable: metered never auto-downloads.
   *
   * `unmetered` is the caller's reading of ConnectivityManager; `null` (no network) is not
   * an auto-download either — it asks, and the UI explains there is nothing to download yet.
   */
  fun decide(unmetered: Boolean?): NetworkDecision =
    if (unmetered == true) NetworkDecision.AUTO_DOWNLOAD else NetworkDecision.ASK_FIRST
}
