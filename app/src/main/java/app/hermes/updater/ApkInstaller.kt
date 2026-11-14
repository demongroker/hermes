package app.hermes.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** Installed version + signer of a catalog app. */
data class InstalledInfo(val versionName: String?, val certs: List<String>)

/**
 * Android side of the gate: reads the numbers, then hands the decision to [UpdateGate].
 *
 * Order matters and is enforced in [install]: hash first (cheap, catches a corrupt or
 * truncated transfer), then the catalog pin, then the installed app's signer. Nothing reaches
 * the system installer unless all three agree.
 */
class ApkInstaller(private val context: Context) {

  fun installedInfo(packageName: String): InstalledInfo? = runCatching {
    val pm = context.packageManager
    val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    InstalledInfo(info.versionName, info.signingInfo?.apkContentsSigners?.map { certDigest(it.toByteArray()) }.orEmpty())
  }.getOrNull()

  fun sha256(file: File): String? = runCatching {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        md.update(buffer, 0, read)
      }
    }
    md.digest().joinToString("") { "%02x".format(it) }
  }.getOrNull()

  fun certDigest(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

  private fun apkCerts(file: File): List<String> = runCatching {
    val info = context.packageManager
      .getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
      ?: return emptyList()
    info.signingInfo?.apkContentsSigners?.map { certDigest(it.toByteArray()) }.orEmpty()
  }.getOrDefault(emptyList())

  /** Runs the full gate; returns the decision so the caller can explain a refusal. */
  fun verify(app: CatalogApp, file: File, expectedSha256: String?): GateResult =
    UpdateGate.decide(
      expectedSha256 = expectedSha256,
      actualSha256 = sha256(file),
      sizeBytes = file.length(),
      pinnedCerts = app.certPins,
      downloadedCerts = apkCerts(file),
      installedCerts = installedInfo(app.packageName)?.certs.orEmpty(),
    )

  /** Only called after [verify] returned [GateResult.Allowed]. */
  fun install(file: File): Boolean = runCatching {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
  }.getOrDefault(false)
}
