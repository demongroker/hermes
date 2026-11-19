package app.hermes.updater

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** UI state of one app's update flow. */
sealed interface FlowState {
  data object Idle : FlowState
  data class Downloading(val percent: Int) : FlowState // -1 = unknown total
  data object Verifying : FlowState
  data class Blocked(val message: String) : FlowState
  data object Installing : FlowState
  data class Failed(val message: String) : FlowState
}

/** Plain-English refusal wording — every gate refusal is explainable. */
fun Refusal.message(): String = when (this) {
  Refusal.NO_PIN -> "No signing pin for this app — install refused."
  Refusal.HASH_MISSING -> "No published checksum — install refused."
  Refusal.HASH_MISMATCH -> "Checksum mismatch — the download is corrupt or was tampered with."
  Refusal.CERT_MISSING -> "The APK carries no readable signing certificate — install refused."
  Refusal.CERT_MISMATCH -> "Signed by an unknown certificate, not the pinned developer — install refused."
  Refusal.INSTALLED_CERT_MISSING -> "Signature does not match the installed app — an update must continue the same app."
  Refusal.TRUNCATED -> "File too small — truncated download, install refused."
}

/**
 * Download → verify → install, single-flight. Nothing reaches the system installer unless
 * [ApkInstaller.verify] (the full fail-closed gate) returned Allowed. Unverified files are
 * deleted on refusal; the APK lives in the app's cache dir, shared via FileProvider only.
 */
class UpdateFlow(
  private val context: Context,
  private val client: GitHubClient,
  private val installer: ApkInstaller,
) {
  private val busy = AtomicBoolean(false)

  val isBusy: Boolean get() = busy.get()

  /**
   * Runs the whole flow on the calling dispatcher (use IO). [onState] is safe to call from
   * any thread (Compose snapshot state). Returns when the installer intent was fired or the
   * flow was refused/failed.
   */
  suspend fun run(app: CatalogApp, release: LatestRelease, onState: (FlowState) -> Unit) {
    if (!busy.compareAndSet(false, true)) {
      onState(FlowState.Failed("Another download is already running."))
      return
    }
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    val dest = File(dir, "${app.id}-${release.tag}.apk")
    val part = File(dir, "${dest.name}.part")
    try {
      onState(FlowState.Downloading(-1))
      val ok = client.download(release.apk.url, part) { pct -> onState(FlowState.Downloading(pct)) }
      if (!ok) {
        part.delete()
        onState(FlowState.Failed("Download failed — check the connection and try again."))
        return
      }
      part.renameTo(dest)
      onState(FlowState.Verifying)
      val expected = release.sha256Asset?.let { client.fetchText(it.url) }
      when (val result = installer.verify(app, dest, expected)) {
        is GateResult.Blocked -> {
          dest.delete()
          onState(FlowState.Blocked(result.reason.message()))
        }
        GateResult.Allowed -> {
          onState(FlowState.Installing)
          if (!installer.install(dest)) {
            onState(FlowState.Failed("Could not open the system installer."))
          }
        }
      }
    } catch (t: Throwable) {
      part.delete()
      dest.delete()
      onState(FlowState.Failed("Update failed: ${t.javaClass.simpleName}"))
    } finally {
      busy.set(false)
    }
  }
}
