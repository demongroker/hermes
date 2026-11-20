package app.hermes.updater

import app.hermes.updater.ui.CatalogCopy
import app.hermes.updater.ui.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card's copy and its single action are a state machine, so they are pinned here.
 * A refusal must outrank every progress/version message — that is the fail-closed contract
 * showing up in the UI, not just in the installer.
 */
class CatalogCopyTest {

  private fun status(
    pinned: Boolean = true,
    installed: String = "1.0.0",
    latest: String? = "1.0.1",
    failed: Boolean = false,
    available: Boolean = true,
    state: FlowState = FlowState.Idle,
  ) = CatalogCopy.status(pinned, installed, latest, failed, available, state)

  @Test
  fun unpinnedRefusalOutranksEverything() {
    val s = status(pinned = false, state = FlowState.Downloading(80))
    assertTrue(s.text.contains("refused"))
    assertEquals(Tone.BAD, s.tone)
    assertNull(
      CatalogCopy.action(false, false, "1.0.0", "1.0.1", false, true),
    )
  }

  @Test
  fun downloadingShowsPercentWhenKnown() {
    val s = status(state = FlowState.Downloading(42))
    assertEquals("Downloading 42%", s.text)
    assertEquals(Tone.BUSY, s.tone)
    assertEquals(42, CatalogCopy.progressPercent(FlowState.Downloading(42)))
  }

  @Test
  fun downloadingUnknownTotalHasNoPercentAndNoBar() {
    val s = status(state = FlowState.Downloading(-1))
    assertEquals("Downloading…", s.text)
    assertNull(CatalogCopy.progressPercent(FlowState.Downloading(-1)))
  }

  @Test
  fun verifyingAndInstallingAreBusy() {
    assertEquals(Tone.BUSY, status(state = FlowState.Verifying).tone)
    assertEquals(Tone.BUSY, status(state = FlowState.Installing).tone)
    assertTrue(CatalogCopy.isRunning(FlowState.Verifying))
    assertTrue(CatalogCopy.isRunning(FlowState.Installing))
    assertTrue(!CatalogCopy.isRunning(FlowState.Idle))
  }

  @Test
  fun blockedAndFailedKeepTheirExactMessage() {
    assertEquals("Checksum mismatch", status(state = FlowState.Blocked("Checksum mismatch")).text)
    assertEquals("Offline", status(state = FlowState.Failed("Offline")).text)
    assertEquals(Tone.BAD, status(state = FlowState.Failed("Offline")).tone)
  }

  @Test
  fun missingAppOffersInstallNotUpdate() {
    val s = status(installed = CatalogCopy.NOT_INSTALLED, available = false)
    assertEquals("Not installed", s.text)
    assertEquals(Tone.WARN, s.tone)
    assertEquals(
      "Install",
      CatalogCopy.action(false, true, CatalogCopy.NOT_INSTALLED, "1.0.1", false, false),
    )
  }

  @Test
  fun missingAppWithNoReleaseYetOffersNothing() {
    assertNull(
      CatalogCopy.action(false, true, CatalogCopy.NOT_INSTALLED, null, false, false),
    )
  }

  @Test
  fun updateAvailableOffersUpdate() {
    assertEquals("Update available", status().text)
    assertEquals("Update", CatalogCopy.action(false, true, "1.0.0", "1.0.1", false, true))
  }

  @Test
  fun checkFailureOffersRetry() {
    val s = status(latest = null, failed = true, available = false)
    assertTrue(s.text.contains("Retry"))
    assertEquals(Tone.BAD, s.tone)
    assertEquals("Retry", CatalogCopy.action(false, true, "1.0.0", null, true, false))
  }

  @Test
  fun upToDateIsGoodAndOffersNothing() {
    val s = status(available = false)
    assertEquals("Up to date", s.text)
    assertEquals(Tone.GOOD, s.tone)
    assertNull(CatalogCopy.action(false, true, "1.0.0", "1.0.0", false, false))
  }

  @Test
  fun runningActionIsWorkingAndDisabledByIsRunning() {
    assertEquals("Working…", CatalogCopy.action(true, true, "1.0.0", "1.0.1", false, true))
    assertTrue(CatalogCopy.isRunning(FlowState.Downloading(10)))
  }

  @Test
  fun versionLabelsAreReadable() {
    assertEquals("Not installed", CatalogCopy.installedLabel(CatalogCopy.NOT_INSTALLED))
    assertEquals("Installed 2.5.8", CatalogCopy.installedLabel("2.5.8"))
    assertEquals("Latest 2.5.9", CatalogCopy.latestLabel("2.5.9", false))
    assertEquals("Latest: check failed", CatalogCopy.latestLabel(null, true))
    assertEquals("Latest: …", CatalogCopy.latestLabel(null, false))
  }
}
