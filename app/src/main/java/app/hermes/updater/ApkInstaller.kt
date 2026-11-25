package app.hermes.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
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

  /**
   * Installed version + signer, or null when the package is not installed at all. Null is the
   * gate's "nothing to be incompatible with" signal: a missing package must be distinguishable
   * from one that is present but whose signer cannot be read (that returns an empty cert list).
   */
  fun installedInfo(packageName: String): InstalledInfo? {
    val info = try {
      context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (e: PackageManager.NameNotFoundException) {
      return null
    }
    return runCatching {
      InstalledInfo(info.versionName, info.signingInfo?.apkContentsSigners?.map { certDigest(it.toByteArray()) }.orEmpty())
    }.getOrDefault(InstalledInfo(info.versionName, emptyList()))
  }

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
      installedCerts = installedInfo(app.packageName)?.certs,
    )

  /**
   * Only called after [verify] returned [GateResult.Allowed].
   *
   * Prefers a real [PackageInstaller] session: unlike `ACTION_VIEW` it reports the outcome —
   * success, `STATUS_FAILURE_ABORTED` (the owner backed out), or a failure — which is what
   * keeps the card from hanging on "Opening the system installer…". Falls back to the old
   * intent on any refusal (old Android, or a package installer that will not take a session).
   */
  /** A2: the "install unknown apps" permission is a hard prerequisite on 26+. */
  fun canInstallApps(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

  /** Opens Settings so the owner can grant the updater the unknown-sources permission. */
  fun openInstallPermissions() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    runCatching {
      context.startActivity(
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
          .setData(android.net.Uri.parse("package:" + context.packageName))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }
  }

  /** versionCode of the downloaded APK, read from its manifest without installing it. */
  fun archiveVersionCode(file: File): Long? = runCatching {
    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
  }.getOrNull()

  /** versionCode currently installed for [packageName], or null when absent. */
  fun installedVersionCode(packageName: String): Long? = runCatching {
    val info = context.packageManager.getPackageInfo(packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
  }.getOrNull()

  /** A3 fallback: open the downloaded APK ourselves through the FileProvider. */
  fun installManually(file: File): Boolean = installViaIntent(file)

  fun install(appId: String, file: File): Boolean {
    if (!canInstallApps()) {
      openInstallPermissions()
      return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val viaSession = runCatching { installViaSession(appId, file) }.getOrDefault(false)
      if (viaSession) return true
    }
    return installViaIntent(file)
  }

  private fun installViaSession(appId: String, file: File): Boolean {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sessionId = installer.createSession(params)
    val session = installer.openSession(sessionId)
    try {
      session.openWrite("apk", 0, file.length()).use { out ->
        file.inputStream().use { input -> input.copyTo(out) }
        session.fsync(out)
      }
      val intent = Intent(context, InstallStatusReceiver::class.java)
        .setAction(InstallStatusReceiver.ACTION)
        .putExtra(InstallStatusReceiver.EXTRA_APP_ID, appId)
      val pending = PendingIntent.getBroadcast(
        context,
        sessionId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
      )
      session.commit(pending.intentSender)
      return true
    } finally {
      session.close()
    }
  }

  private fun installViaIntent(file: File): Boolean = runCatching {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
  }.getOrDefault(false)
}
