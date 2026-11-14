package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fail-closed install gate: every refusal path has its own case. */
class UpdateGateTest {

  private val pin = "aa".repeat(32)
  private val other = "bb".repeat(32)
  private val hash = "cc".repeat(32)
  private val big = 20L * 1024 * 1024

  private fun decide(
    expectedSha: String? = "$hash  app.apk",
    actualSha: String? = hash,
    size: Long = big,
    pins: List<String> = listOf(pin),
    downloaded: List<String> = listOf(pin),
    installed: List<String> = listOf(pin),
  ) = UpdateGate.decide(expectedSha, actualSha, size, pins, downloaded, installed)

  @Test
  fun matchingEverythingIsAllowed() {
    assertEquals(GateResult.Allowed, decide())
  }

  @Test
  fun missingPublishedHashIsRefused() {
    assertEquals(GateResult.Blocked(Refusal.HASH_MISSING), decide(expectedSha = null))
    assertEquals(GateResult.Blocked(Refusal.HASH_MISSING), decide(expectedSha = "   "))
  }

  @Test
  fun hashMismatchIsRefused() {
    assertEquals(GateResult.Blocked(Refusal.HASH_MISMATCH), decide(actualSha = other))
    assertEquals(GateResult.Blocked(Refusal.HASH_MISMATCH), decide(actualSha = null))
  }

  @Test
  fun truncatedFileIsRefusedEvenWithAGoodHash() {
    assertEquals(GateResult.Blocked(Refusal.TRUNCATED), decide(size = 1024))
  }

  @Test
  fun appWithoutAPinIsRefused() {
    assertEquals(GateResult.Blocked(Refusal.NO_PIN), decide(pins = emptyList()))
    assertEquals(GateResult.Blocked(Refusal.NO_PIN), decide(pins = listOf("")))
  }

  @Test
  fun downloadWithoutCertificatesIsRefused() {
    assertEquals(GateResult.Blocked(Refusal.CERT_MISSING), decide(downloaded = emptyList()))
  }

  @Test
  fun certificateThatDoesNotMatchThePinIsRefused() {
    assertEquals(GateResult.Blocked(Refusal.CERT_MISMATCH), decide(downloaded = listOf(other)))
  }

  @Test
  fun certificateThatDoesNotMatchTheInstalledAppIsRefused() {
    assertEquals(
      GateResult.Blocked(Refusal.INSTALLED_CERT_MISSING),
      decide(installed = listOf(other)),
    )
    assertEquals(
      GateResult.Blocked(Refusal.INSTALLED_CERT_MISSING),
      decide(installed = emptyList()),
    )
  }

  @Test
  fun colonsAndCaseAndSha256sumOutputAreNormalised() {
    val colonised = pin.chunked(2).joinToString(":").uppercase()
    assertEquals(GateResult.Allowed, decide(pins = listOf(colonised), downloaded = listOf(pin)))
  }
}
