package app.hermes.updater

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every refusal must be explainable in plain English — no silent no-ops. */
class RefusalTextTest {

  @Test
  fun everyRefusalHasNonEmptyMessage() {
    for (refusal in Refusal.entries) {
      val msg = refusal.message()
      assertTrue("blank message for $refusal", msg.isNotBlank())
    }
  }

  @Test
  fun mismatchMessagesSayWhy() {
    assertTrue(Refusal.HASH_MISMATCH.message().contains("Checksum"))
    assertTrue(Refusal.CERT_MISMATCH.message().contains("certificate"))
    assertTrue(Refusal.INSTALLED_CERT_MISSING.message().contains("installed app"))
    assertTrue(Refusal.TRUNCATED.message().contains("truncated"))
  }
}
