package app.hermes.updater

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guards for the batch wiring. WorkManager itself cannot run on the JVM, so the
 * pure part is covered by [UpdatePlanTest]; these pin the facts that keep the wiring honest:
 * the batch hands ONE request to the existing worker and that worker walks it in order (one
 * installer at a time), the header count and the enqueue list come from one call, and each app
 * keeps its own notification id.
 */
class UpdateBatchTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  @Test
  fun batchIsOneRequestWalkedSequentiallyByTheSameWorker() {
    val worker = File(root, "app/src/main/java/app/hermes/updater/DownloadWorker.kt").readText()
    assertTrue("the batch must enqueue the existing worker", worker.contains("fun enqueueAll("))
    assertTrue(
      "the whole batch goes as one work request, so installs cannot overlap",
      worker.contains("enqueue(ctx, appIds, BATCH_WORK_NAME)"),
    )
    assertTrue(
      "the worker walks the list in order instead of fanning out",
      worker.contains("for (id in ids)"),
    )
    assertTrue(
      "the batch carries every app id in one payload",
      worker.contains("putStringArray(KEY_APP_IDS"),
    )
    assertFalse(
      "parallel fan-out would run two installers at once",
      worker.contains("appIds.forEach { enqueue(ctx, it) }"),
    )
    assertTrue(
      "the single-app path still uses the planner's name, not a second copy of the format",
      worker.contains("UpdatePlanner.workName(appId)"),
    )
    assertFalse(
      "the old inline name format must be gone",
      worker.contains("\"hermes-download-\$appId\""),
    )
  }

  @Test
  fun eachAppKeepsItsOwnNotificationId() {
    val worker = File(root, "app/src/main/java/app/hermes/updater/DownloadWorker.kt").readText()
    assertTrue(worker.contains("fun notificationId(appId: String): Int"))
    assertTrue(worker.contains("NOTIFICATION_ID_BASE +"))
    assertFalse("one shared id would let parallel downloads overwrite each other", worker.contains("NOTIFICATION_ID,"))
  }

  @Test
  fun headerShowsThePlannerCountAndEnqueuesTheSameList() {
    val main = File(root, "app/src/main/java/app/hermes/updater/MainActivity.kt").readText()
    assertTrue(main.contains("val pending = UpdatePlanner.pending(rows)"))
    assertTrue(main.contains("DownloadWorker.enqueueAll(applicationContext, batch.map { it.app.id })"))
    assertTrue(main.contains("\"Update all (\${pending.size})\""))
    assertTrue(main.contains("\"Updating \${pending.size}…\""))
  }

  @Test
  fun meteredBatchAsksOnceForTheWholeBatch() {
    val main = File(root, "app/src/main/java/app/hermes/updater/MainActivity.kt").readText()
    assertTrue(main.contains("confirmBatch = batch"))
    assertTrue(main.contains("fun startBatch("))
    assertTrue(main.contains("batch.joinToString { it.app.displayName }"))
    // One dialog, not one per app: the batch is confirmed as a single list.
    assertFalse(main.contains("confirmRow = batch"))
  }
}
