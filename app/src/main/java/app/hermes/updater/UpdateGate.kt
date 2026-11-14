package app.hermes.updater

/** Why an install was refused, in words the UI can show. */
enum class Refusal {
  NO_PIN,
  HASH_MISSING,
  HASH_MISMATCH,
  CERT_MISSING,
  CERT_MISMATCH,
  INSTALLED_CERT_MISSING,
  TRUNCATED,
}

sealed interface GateResult {
  data object Allowed : GateResult
  data class Blocked(val reason: Refusal) : GateResult
}

/**
 * The install gate. Everything here is pure so the rules can be tested exhaustively; the
 * Android side only reads hashes and certificates and passes them in.
 *
 * Fail closed on every axis:
 *  - no expected hash         -> HASH_MISSING
 *  - hash differs             -> HASH_MISMATCH
 *  - the file is too small    -> TRUNCATED
 *  - no catalog pin           -> NO_PIN
 *  - download carries no cert -> CERT_MISSING
 *  - downloaded cert does not match the pin            -> CERT_MISMATCH
 *  - downloaded cert does not match the installed app  -> INSTALLED_CERT_MISSING
 */
object UpdateGate {

  /** Anything under this is a truncated download, whatever the hash says. */
  const val MIN_APK_BYTES = 1L * 1024 * 1024

  fun decide(
    expectedSha256: String?,
    actualSha256: String?,
    sizeBytes: Long,
    pinnedCerts: List<String>,
    downloadedCerts: List<String>,
    installedCerts: List<String>,
  ): GateResult {
    val expected = normalise(expectedSha256)
    if (expected.isEmpty()) return GateResult.Blocked(Refusal.HASH_MISSING)
    if (sizeBytes < MIN_APK_BYTES) return GateResult.Blocked(Refusal.TRUNCATED)
    val actual = normalise(actualSha256)
    if (actual != expected) return GateResult.Blocked(Refusal.HASH_MISMATCH)

    val pins = pinnedCerts.map(::normalise).filter { it.isNotEmpty() }.toSet()
    if (pins.isEmpty()) return GateResult.Blocked(Refusal.NO_PIN)

    val downloaded = downloadedCerts.map(::normalise).filter { it.isNotEmpty() }.toSet()
    if (downloaded.isEmpty()) return GateResult.Blocked(Refusal.CERT_MISSING)
    if (downloaded.none { it in pins }) return GateResult.Blocked(Refusal.CERT_MISMATCH)

    val installed = installedCerts.map(::normalise).filter { it.isNotEmpty() }.toSet()
    if (installed.isEmpty()) return GateResult.Blocked(Refusal.INSTALLED_CERT_MISSING)
    if (downloaded.none { it in installed }) return GateResult.Blocked(Refusal.INSTALLED_CERT_MISSING)

    return GateResult.Allowed
  }

  /** Hashes and digests are compared as lowercase hex, first token only (`sha256sum` output). */
  fun normalise(value: String?): String =
    value?.trim()?.lowercase()?.substringBefore(' ')?.replace(":", "")?.removePrefix("sha256:") ?: ""
}
