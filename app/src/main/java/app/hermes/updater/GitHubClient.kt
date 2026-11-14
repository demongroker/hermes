package app.hermes.updater

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One release asset (an APK or its `.sha256`). */
data class Asset(val name: String, val url: String, val sizeBytes: Long)

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
    val json = get("${API}/${app.repo}/releases/latest") ?: return null
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

  /** Fetches a small text asset (the published checksum). */
  fun fetchText(url: String): String? = get(url)

  private fun get(url: String): String? {
    val conn = connectionFactory(url)
    return try {
      conn.instanceFollowRedirects = true
      conn.connectTimeout = 15_000
      conn.readTimeout = 25_000
      conn.setRequestProperty("Accept", "application/vnd.github+json")
      conn.setRequestProperty("User-Agent", "Hermes-Updater")
      tokenProvider()?.takeIf { it.isNotBlank() }?.let {
        conn.setRequestProperty("Authorization", "Bearer $it")
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
