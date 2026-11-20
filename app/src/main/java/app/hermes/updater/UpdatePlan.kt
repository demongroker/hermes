package app.hermes.updater

import app.hermes.updater.ui.CatalogCopy

/**
 * One row of the list: what is installed, what is published, and the pins that matter.
 *
 * Extracted from the Activity so the "which rows are updatable" decision is pure data — no
 * Compose, no Context, no network — and can be unit-tested directly.
 */
data class UpdateRow(
  val app: CatalogApp,
  val installed: String,
  val latest: String?,
  val available: Boolean,
  val pinned: Boolean,
  val failed: Boolean = false,
)

/** One app a batch tap will enqueue: the catalog id and the unique work name it maps to. */
data class BatchItem(val appId: String, val workName: String)

/**
 * The batch-update decision, as pure functions.
 *
 * [isUpdate] is the single source of truth for "this card has a real update": the header count
 * and the batch's enqueue list both come from [pending], so the number shown and the number
 * enqueued cannot drift apart.
 *
 * A card with nothing installed is an **install**, not an update — [isUpdate] returns false for
 * it, so "Update all" never quietly installs an app the owner never asked for.
 */
object UpdatePlanner {

  /** Prefix of the unique WorkManager name for one app's download. */
  const val WORK_PREFIX = "hermes-download-"

  /**
   * The unique-work name for one app. Shared by the single-app and batch paths on purpose: two
   * different formats would let one of them double-enqueue an app the other had already queued.
   */
  fun workName(appId: String): String = "$WORK_PREFIX$appId"

  /**
   * True when this card has a real update to fetch: it is pinned (otherwise the install gate
   * refuses it), a release was read, something is installed, and the release is strictly newer.
   */
  fun isUpdate(row: UpdateRow): Boolean =
    row.pinned &&
      !row.failed &&
      row.installed.isNotBlank() &&
      row.installed != CatalogCopy.NOT_INSTALLED &&
      row.latest != null &&
      Versions.isNewer(row.latest, row.installed)

  /** The rows a batch tap will enqueue, in catalog order. */
  fun pending(rows: List<UpdateRow>): List<UpdateRow> = rows.filter(::isUpdate)

  fun pendingIds(rows: List<UpdateRow>): List<String> = pending(rows).map { it.app.id }

  /**
   * The batch as WorkManager names: one unique name per pending app. Distinct names are what let
   * the apps download in parallel, and reusing the single-app name is what keeps each app
   * single-flight under ExistingWorkPolicy.KEEP.
   */
  fun batch(rows: List<UpdateRow>): List<BatchItem> =
    pending(rows).map { BatchItem(it.app.id, workName(it.app.id)) }
}
