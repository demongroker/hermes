package app.hermes.updater

import app.hermes.updater.ui.CatalogCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "which rows are updatable" decision is pure data, and both the header count and the
 * enqueue list come from it — these tests are the contract "Update all" runs on.
 */
class UpdatePlanTest {

  private fun row(
    id: String = "vela",
    installed: String = "0.1.6",
    latest: String? = "0.1.7",
    pinned: Boolean = true,
    failed: Boolean = false,
    available: Boolean = true,
  ) = UpdateRow(
    app = CatalogApp(
      id = id,
      displayName = id,
      packageName = "pkg.$id",
      repo = "o/$id",
      assetPrefix = null,
      certPins = if (pinned) listOf("a".repeat(64)) else emptyList(),
    ),
    installed = installed,
    latest = latest,
    available = available,
    pinned = pinned,
    failed = failed,
  )

  @Test
  fun realUpdateIsPending() {
    assertTrue(UpdatePlanner.isUpdate(row()))
    assertEquals(listOf("vela"), UpdatePlanner.pendingIds(listOf(row())))
  }

  @Test
  fun upToDateIsNotPending() {
    assertFalse(UpdatePlanner.isUpdate(row(installed = "0.1.7", latest = "0.1.7")))
  }

  @Test
  fun downgradeIsNotPending() {
    assertFalse(UpdatePlanner.isUpdate(row(installed = "0.1.8", latest = "0.1.7")))
  }

  @Test
  fun notInstalledIsAnInstallNotAnUpdate() {
    // A card with nothing installed offers "Install" per-card; it must stay out of the batch.
    val missing = row(installed = CatalogCopy.NOT_INSTALLED, latest = "0.1.7")
    assertFalse(UpdatePlanner.isUpdate(missing))
    assertTrue(UpdatePlanner.pending(listOf(missing)).isEmpty())
  }

  @Test
  fun blankInstalledIsNotPending() {
    assertFalse(UpdatePlanner.isUpdate(row(installed = "   ")))
    assertFalse(UpdatePlanner.isUpdate(row(installed = "")))
  }

  @Test
  fun failedCheckIsNotPending() {
    assertFalse(UpdatePlanner.isUpdate(row(latest = null, failed = true)))
  }

  @Test
  fun missingLatestIsNotPending() {
    assertFalse(UpdatePlanner.isUpdate(row(latest = null)))
  }

  @Test
  fun unpinnedRowIsNotPending() {
    // Fail-closed: an app with no signing pin can never be installed, so never batch it.
    assertFalse(UpdatePlanner.isUpdate(row(pinned = false)))
  }

  @Test
  fun availableFlagIsIgnoredInFavourOfTheRawFacts() {
    // A stale `available` flag must not put a row in the batch: the raw versions decide.
    val stale = row(installed = "0.1.7", latest = "0.1.7", available = true)
    assertFalse(UpdatePlanner.isUpdate(stale))
    val wrong = row(installed = "0.1.5", latest = "0.1.7", available = false)
    assertTrue(UpdatePlanner.isUpdate(wrong))
  }

  @Test
  fun batchKeepsListOrderAndSkipsTheRest() {
    val rows = listOf(
      row(id = "videodroid", installed = "2.7.0", latest = "2.7.1"),
      row(id = "jellyamp", installed = "1.2.8", latest = "1.2.8"),
      row(id = "vela", installed = "0.1.29", latest = "0.1.30"),
      row(id = "hermes", installed = "0.1.6", latest = null, failed = true),
    )
    assertEquals(listOf("videodroid", "vela"), UpdatePlanner.pendingIds(rows))
    assertEquals(listOf("videodroid", "vela"), UpdatePlanner.batch(rows).map { it.appId })
  }

  @Test
  fun emptyListProducesAnEmptyBatch() {
    assertTrue(UpdatePlanner.pending(emptyList()).isEmpty())
    assertTrue(UpdatePlanner.batch(emptyList()).isEmpty())
  }

  @Test
  fun countShownIsExactlyWhatGetsEnqueued() {
    val rows = listOf(
      row(id = "a", installed = "1.0.0", latest = "1.0.1"),
      row(id = "b", installed = "1.0.0", latest = "1.0.0"),
      row(id = "c", installed = "1.0.0", latest = "1.0.2"),
    )
    val ids = UpdatePlanner.pendingIds(rows)
    val batch = UpdatePlanner.batch(rows)
    assertEquals(ids.size, batch.size)
    assertEquals(ids, batch.map { it.appId })
  }

  @Test
  fun everyBatchItemUsesThePerAppName() {
    val batch = UpdatePlanner.batch(
      listOf(row(id = "videodroid"), row(id = "jellyamp"), row(id = "vela")),
    )
    assertEquals(
      listOf(
        "hermes-download-videodroid",
        "hermes-download-jellyamp",
        "hermes-download-vela",
      ),
      batch.map { it.workName },
    )
    batch.forEach { item ->
      assertEquals(UpdatePlanner.workName(item.appId), item.workName)
    }
  }

  @Test
  fun batchWorkNamesAreUniqueSoParallelismCannotCollapse() {
    val ids = AppCatalog.apps.map { it.id }
    val names = UpdatePlanner.batch(ids.map { row(id = it) }).map { it.workName }
    assertEquals(ids.size, names.size)
    assertEquals(names.size, names.toSet().size)
  }

  @Test
  fun workNameFormatIsTheOneTheSingleAppPathUses() {
    assertEquals("hermes-download-hera", UpdatePlanner.workName("hera"))
    assertTrue(UpdatePlanner.workName("x").startsWith(UpdatePlanner.WORK_PREFIX))
  }
}
