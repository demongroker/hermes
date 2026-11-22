package app.hermes.updater

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One release asset (an APK or its `.sha256`). */
data class Asset(val name: String, val url: String, val sizeBytes: Long)

/**
 * Outcome of one download attempt.
 *
 * The split matters: an [Offline] failure leaves a usable partial file that the next attempt
 * resumes from, while an [HttpError] means the server answered and retrying cannot help.
 */
sealed interface DownloadResult {
  /** The whole body is on disk. */
  data object Ok : DownloadResult

  /** Connection dropped or timed out — transient, resume the partial file. */
  data class Offline(val detail: String?) : DownloadResult

  /** The server answered with a status we cannot use (404, 403, 5xx…). */
  data class HttpError(val code: Int) : DownloadResult

  /** The URL itself was refused (anything but https). */
  data class Rejected(val reason: String) : DownloadResult
}

/** True when the failure is a network problem that a retry may fix. */
val DownloadResult.transient: Boolean get() = this is DownloadResult.Offline

/** The latest release of one catalog app. */
data class LatestRelease(
  val appId: String,
  val tag: String,
  val body: String,
  val apk: Asset,
  val sha256Asset: Asset?,
)

/**
 * GitHub Releases client for the catalog.
 *
 * Works with no token at all (60 requests/hour per IP is plenty for a daily check); a token
 * raises the limit and, if the user stored one, is read from [TokenStore] and only ever put in
 * a request header. It is never logged, and never put in an exception message.
 */
class GitHubClient(
  private val tokenProvider: () -> String? = { null },
  private val connectionFactory: (String) -> HttpURLConnection = { url ->
    (URL(url).openConnection() as HttpURLConnection)
  },
) {

  fun latestRelease(app: CatalogApp): LatestRelease? {
    val json = get("${API}/${app.repo}/releases/latest", auth = true) ?: return null
    val release = JSONObject(json)
    val tag = release.optString("tag_name").ifBlank { return null }
    val assets = release.optJSONArray("assets") ?: return null

    val candidates = (0 until assets.length()).mapNotNull { i ->
      val a = assets.optJSONObject(i) ?: return@mapNotNull null
      val name = a.optString("name")
      val url = a.optString("browser_download_url")
      if (name.isBlank() || !url.startsWith("https://")) return@mapNotNull null
      Asset(name, url, a.optLong("size", 0L))
    }

    val apk = pickApk(app, candidates) ?: return null
    return LatestRelease(
      appId = app.id,
      tag = tag,
      body = release.optString("body"),
      apk = apk,
      sha256Asset = candidates.firstOrNull { it.name == "${apk.name}.sha256" },
    )
  }

  /**
   * Fetches a small text asset (the published checksum). Public assets are fetched WITHOUT
   * the token: HttpURLConnection re-sends request headers on redirect, and asset downloads
   * redirect to the CDN — the token must not travel there.
   */
  fun fetchText(url: String): String? = get(url, auth = false)

  /**
   * Streams a release asset to [dest] (caller passes a `.part` file). HTTPS only, no token,
   * progress in percent (-1 when the total is unknown).
   *
   * **Resumable**: when [dest] already holds bytes from an earlier attempt, the request asks for
   * `Range: bytes=<done>-`. A `206` means the server honoured it and the body is appended; a `200`
   * means it did not, so the file is truncated and restarted — appending there would corrupt it.
   */
  fun download(url: String, dest: java.io.File, onProgress: (Int) -> Unit): DownloadResult {
    if (!url.startsWith("https://")) return DownloadResult.Rejected("not an https URL")
    val resumeFrom = if (dest.exists()) dest.length() else 0L
    val conn = connectionFactory(url)
    return try {
      conn.instanceFollowRedirects = true
      conn.connectTimeout = 15_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("User-Agent", "Hermes-Updater")
      if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
      val code = conn.responseCode
      if (code != 200 && code != 206) return DownloadResult.HttpError(code)
      val append = code == 206 && resumeFrom > 0
      val total = conn.contentLengthLong.let { len ->
        if (len > 0) (if (append) resumeFrom + len else len) else -1L
      }
      dest.parentFile?.mkdirs()
      conn.inputStream.use { input ->
        java.io.FileOutputStream(dest, append).use { out ->
          val buffer = ByteArray(1 shl 16)
          var done = if (append) resumeFrom else 0L
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
            done += read
            onProgress(if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1)
          }
        }
      }
      DownloadResult.Ok
    } catch (e: Throwable) {
      DownloadResult.Offline(e.javaClass.simpleName)
    } finally {
      runCatching { conn.disconnect() }
    }
  }

  private fun get(url: String, auth: Boolean): String? {
    val conn = connectionFactory(url)
    return try {
      conn.instanceFollowRedirects = true
      conn.connectTimeout = 15_000
      conn.readTimeout = 25_000
      conn.setRequestProperty("Accept", "application/vnd.github+json")
      conn.setRequestProperty("User-Agent", "Hermes-Updater")
      if (auth) {
        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
          conn.setRequestProperty("Authorization", "Bearer $it")
        }
      }
      if (conn.responseCode != 200) return null
      conn.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
      null
    } finally {
      runCatching { conn.disconnect() }
    }
  }

  companion object {
    private const val API = "https://api.github.com/repos"

    /**
     * The universal APK for this app: the asset whose name starts with the app's prefix, ends
     * in `.apk`, and is not the checksum. arm64-only builds are skipped on purpose — Hermes
     * only installs what runs everywhere.
     */
    fun pickApk(app: CatalogApp, assets: List<Asset>): Asset? {
      val apks = assets.filter { it.name.endsWith(".apk") && !it.name.endsWith(".apk.sha256") }
      val prefixed = apks.filter { app.assetPrefix == null || it.name.startsWith(app.assetPrefix) }
      return prefixed.firstOrNull { !it.name.contains("arm64") }
        ?: prefixed.firstOrNull()
        ?: apks.firstOrNull { !it.name.contains("arm64") }
    }
  }
}
