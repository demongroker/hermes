package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/** Metered connections must never auto-download; that rule is the whole point of this class. */
class NetworkPolicyTest {

  @Test
  fun unmeteredAutoDownloads() {
    assertEquals(NetworkDecision.AUTO_DOWNLOAD, NetworkPolicy.decide(unmetered = true))
  }

  @Test
  fun meteredAlwaysAsksFirst() {
    assertEquals(NetworkDecision.ASK_FIRST, NetworkPolicy.decide(unmetered = false))
  }

  @Test
  fun unknownNetworkAsksFirst() {
    assertEquals(NetworkDecision.ASK_FIRST, NetworkPolicy.decide(unmetered = null))
  }
}
