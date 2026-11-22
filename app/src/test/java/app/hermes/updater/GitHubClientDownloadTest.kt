package app.hermes.updater

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The download is resumable: a dropped connection must keep the partial file and the retry must
 * continue from it. The two cases that matter are a `206` (server honoured `Range` → append) and
 * a `200` (server ignored it → truncate, or the file would be corrupted).
 */
class GitHubClientDownloadTest {

  @get:Rule
  val tmp: TemporaryFolder = TemporaryFolder()

  /** Records the request headers so a test can assert on the `Range` it sent. */
  private class FakeHttp(
    private val code: Int,
    private val body: ByteArray,
    private val seen: MutableMap<String, String> = mutableMapOf(),
  ) : HttpURLConnection(URL("https://example.test/asset.apk")) {
    override fun getResponseCode(): Int = code
    override fun getContentLengthLong(): Long = body.size.toLong()
    override fun setRequestProperty(key: String, value: String) {
      seen[key] = value
    }

    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy(): Boolean = false
  }

  private fun clientReturning(code: Int, body: ByteArray, seen: MutableMap<String, String>) =
    GitHubClient(connectionFactory = { FakeHttp(code, body, seen) })

  @Test
  fun a206ResponseIsAppendedToThePartialFile() {
    val dest = tmp.newFile("vela-0.1.31.apk.part")
    val first = ByteArray(4096) { 1 }
    dest.writeBytes(first)
    val seen = mutableMapOf<String, String>()

    val result = clientReturning(206, ByteArray(1024) { 2 }, seen)
      .download("https://example.test/asset.apk", dest) {}

    assertEquals(DownloadResult.Ok, result)
    assertEquals("bytes=4096-", seen["Range"])
    val bytes = dest.readBytes()
    assertEquals(4096 + 1024, bytes.size)
    assertArrayEquals(first, bytes.copyOfRange(0, 4096))
    assertArrayEquals(ByteArray(1024) { 2 }, bytes.copyOfRange(4096, bytes.size))
  }

  @Test
  fun a200ResponseTruncatesInsteadOfAppending() {
    val dest = tmp.newFile("vela-0.1.31.apk.part")
    dest.writeBytes(ByteArray(4096) { 1 })
    val body = ByteArray(2048) { 3 }

    val result = clientReturning(200, body, mutableMapOf())
      .download("https://example.test/asset.apk", dest) {}

    assertEquals(DownloadResult.Ok, result)
    assertArrayEquals(body, dest.readBytes())
  }

  @Test
  fun aFreshDownloadSendsNoRangeHeader() {
    val dest = tmp.newFile("fresh.apk.part")
    val seen = mutableMapOf<String, String>()

    clientReturning(200, ByteArray(8) { 9 }, seen)
      .download("https://example.test/asset.apk", dest) {}

    assertNull(seen["Range"])
  }

  @Test
  fun httpErrorsArePermanentAndNotTransient() {
    val dest = tmp.newFile("missing.apk.part")

    val result = clientReturning(404, ByteArray(0), mutableMapOf())
      .download("https://example.test/asset.apk", dest) {}

    assertTrue(result is DownloadResult.HttpError)
    assertFalse(result.transient)
    assertEquals(404, (result as DownloadResult.HttpError).code)
  }

  @Test
  fun ioFailuresAreTransientAndKeepThePartialFile() {
    val dest = tmp.newFile("dropped.apk.part")
    dest.writeBytes(ByteArray(10) { 1 })
    val factory: (String) -> HttpURLConnection = {
      object : HttpURLConnection(URL("https://example.test/asset.apk")) {
        override fun getResponseCode(): Int = 200
        override fun getInputStream(): InputStream = throw IOException("reset")
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
      }
    }

    val result = GitHubClient(connectionFactory = factory)
      .download("https://example.test/asset.apk", dest) {}

    assertTrue(result is DownloadResult.Offline)
    assertTrue(result.transient)
    // Kept on purpose: the retry sends Range and resumes from here.
    assertEquals(10L, dest.length())
  }

  @Test
  fun nonHttpsUrlsAreRejectedBeforeAnyConnection() {
    val dest = tmp.newFile("plain.apk.part")
    var opened = false
    val factory: (String) -> HttpURLConnection = {
      opened = true
      FakeHttp(200, ByteArray(4), mutableMapOf())
    }

    val result = GitHubClient(connectionFactory = factory)
      .download("http://example.test/asset.apk", dest) {}

    assertTrue(result is DownloadResult.Rejected)
    assertFalse(result.transient)
    assertFalse(opened)
  }

  @Test
  fun progressIsReportedAgainstTheWholeFileWhenResuming() {
    val dest = tmp.newFile("progress.apk.part")
    dest.writeBytes(ByteArray(50) { 1 })
    val percents = mutableListOf<Int>()

    clientReturning(206, ByteArray(50) { 2 }, mutableMapOf())
      .download("https://example.test/asset.apk", dest) { percents.add(it) }

    assertTrue(percents.isNotEmpty())
    assertEquals(100, percents.last())
    assertTrue("progress must be monotonic: $percents", percents.zipWithNext().all { (a, b) -> a <= b })
  }

  @Test
  fun anUnknownTotalReportsMinusOne() {
    val dest = tmp.newFile("unknown.apk.part")
    val factory: (String) -> HttpURLConnection = {
      object : HttpURLConnection(URL("https://example.test/asset.apk")) {
        override fun getResponseCode(): Int = 200
        override fun getContentLengthLong(): Long = -1L
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(16) { 4 })
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
      }
    }
    val percents = mutableListOf<Int>()

    GitHubClient(connectionFactory = factory)
      .download("https://example.test/asset.apk", dest) { percents.add(it) }

    assertEquals(listOf(-1), percents.distinct())
  }
}
