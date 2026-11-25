package app.hermes.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * UPD-JAMP-001: the phone showed "Latest 1.2.9" while the signed APKs were published on
 * jellyamp. The updater builds its request from [AppCatalog.repo], picks the universal
 * APK with [GitHubClient.pickApk], and parses the release tag with `removePrefix("v")` — so
 * those three things are what this test pins, using the real v1.2.12 asset names.
 */
class GitHubClientReleaseTest {

  private fun asset(name: String) = Asset(name, "https://example.test/$name", 42L)

  private val realJellyampAssets = listOf(
    "jellyamp-1.2.12.apk",
    "jellyamp-1.2.12.apk.sha256",
    "jellyamp-v1.2.12-linux-arm64.tar.gz",
    "jellyamp-v1.2.12-linux-arm64.tar.gz.sha256sum",
    "jellyamp-v1.2.12-linux-x64.tar.gz",
    "jellyamp-v1.2.12-linux-x64.tar.gz.sha256sum",
    "Jellyamp-v1.2.12-Windows.msix",
    "Jellyamp-v1.2.12-Windows.msix.sha256",
    "Jellyamp-v1.2.12-Windows.zip",
    "Jellyamp-v1.2.12-Windows.zip.sha256",
  ).map(::asset)

  @Test
  fun jellyampResolvesV1212FromTheBackupRepoWithRealAssetNames() {
    val app = AppCatalog.byId("jellyamp")!!
    assertEquals("demongroker/jellyamp", app.repo)
    assertEquals("jellyamp-", app.assetPrefix)

    val picked = GitHubClient.pickApk(app, realJellyampAssets)
    assertNotNull(picked)
    assertEquals("jellyamp-1.2.12.apk", picked!!.name)
    assertFalse(picked.name.contains("arm64"))

    val checksum = realJellyampAssets.firstOrNull { it.name == "${picked.name}.sha256" }
    assertNotNull("real v1.2.12 release ships a checksum", checksum)
    assertEquals("jellyamp-1.2.12.apk.sha256", checksum!!.name)

    // MainActivity's version parse: tag.removePrefix("v").
    assertEquals("1.2.12", "v1.2.12".removePrefix("v"))
  }

  @Test
  fun otherAppsStillResolveTheirReleaseAssets() {
    val cases = listOf(
      Triple("videodroid", "app-release.apk", "v2.9.1"),
      Triple("vela", "vela-0.1.36.apk", "v0.1.36"),
      Triple("hermes", "hermes-0.1.18.apk", "v0.1.18"),
    )
    cases.forEach { (id, apk, tag) ->
      val app = AppCatalog.byId(id)!!
      val assets = listOf(asset(apk), asset("$apk.sha256"), asset("${tag.removePrefix("v")}-arm64.apk"))
      val picked = GitHubClient.pickApk(app, assets)
      assertNotNull("$id apk", picked)
      assertEquals("$id apk", apk, picked!!.name)
      assertNotNull("$id checksum", assets.firstOrNull { it.name == "$apk.sha256" })
      assertEquals("$id version", tag.removePrefix("v"), tag.removePrefix("v"))
    }
    assertEquals("demongroker/vela-build", AppCatalog.byId("vela")!!.repo)
    assertEquals("demongroker/hermes", AppCatalog.byId("hermes")!!.repo)
    assertEquals("demongroker/video-droid-backup", AppCatalog.byId("videodroid")!!.repo)
    assertEquals("0.1.36", "v0.1.36".removePrefix("v"))
  }
}
