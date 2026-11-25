package app.hermes.updater

import app.hermes.updater.ui.CatalogCopy

/**
 * When the installed version already equals the published version, any leftover installer
 * progress state is stale — the card must go idle even if a callback was missed.
 */
object UpdateCardSync {
  fun isSettled(installed: String, latest: String?): Boolean =
    latest != null && installed == latest &&
      installed.isNotBlank() && installed != CatalogCopy.NOT_INSTALLED
}
