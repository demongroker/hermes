package app.hermes.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares the APK already installed for a catalog app, so another phone can sideload the exact
 * build that is on this one.
 *
 * The APK lives under `/data/app`, which [FileProvider] cannot expose directly, so it is copied
 * into `cacheDir/shared/` first — the `shared_apk` cache-path declared in
 * `res/xml/file_paths.xml`. Nothing leaves the device except what the user picks in the share
 * sheet, and the copy is an ordinary cache file the OS can reclaim.
 *
 * [share] does real I/O, so call it from `Dispatchers.IO`; it starts the chooser before returning.
 */
class ApkSharer(private val context: Context) {

  /**
   * Copies [app]'s installed APK into the FileProvider cache and fires `ACTION_SEND`.
   * Returns null when the share sheet opened, or a plain-English reason it could not.
   */
  fun share(app: CatalogApp): String? {
    if (!app.shareable) return "${app.displayName} cannot be shared."
    // One call yields both the version (for the file name) and the APK's path. Visibility is
    // already granted by the <queries> block in the manifest for exactly the catalog packages.
    val pkg = runCatching { context.packageManager.getPackageInfo(app.packageName, 0) }
      .getOrNull() ?: return "${app.displayName} is not installed."
    val source = pkg.applicationInfo?.sourceDir?.let(::File)
      ?: return "Could not find ${app.displayName}'s APK."
    if (!source.isFile) return "Could not read ${app.displayName}'s APK."

    val out = File(File(context.cacheDir, SHARED_DIR), apkFileName(app, pkg.versionName))
    return runCatching {
      out.parentFile?.mkdirs()
      source.copyTo(out, overwrite = true)
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", out)
      val send = Intent(Intent.ACTION_SEND).apply {
        type = MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val chooser = Intent.createChooser(send, "Share ${app.displayName}")
      chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      context.startActivity(chooser)
      null
    }.getOrElse { "Could not share ${app.displayName}: ${it.message}" }
  }

  companion object {
    private const val MIME_TYPE = "application/vnd.android.package-archive"
    private const val SHARED_DIR = "shared"

    /**
     * Cache-file name: the display name, plus the installed version so two builds of the same
     * app never overwrite each other's copy.
     */
    fun apkFileName(app: CatalogApp, versionName: String?): String =
      "${app.displayName}-${versionName ?: "installed"}.apk"
  }
}
