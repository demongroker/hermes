package app.hermes.updater

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Optional GitHub token, stored encrypted.
 *
 * The token only raises the API rate limit; Hermes works without one. It is never logged and
 * never included in an exception message — [describe] exists so the UI can say whether one is
 * set without printing it.
 */
class TokenStore(private val context: Context) {

  private val prefs: SharedPreferences? by lazy {
    runCatching {
      val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
      EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    }.getOrNull()
  }

  fun token(): String? = prefs?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

  fun setToken(value: String?) {
    val p = prefs ?: return
    p.edit().apply {
      if (value.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value.trim())
    }.apply()
  }

  /** UI-safe summary: whether a token is stored, never the token. */
  fun describe(): String = if (token() == null) "no token (rate-limited)" else "token stored"

  private companion object {
    const val PREFS_NAME = "hermes_secure"
    const val KEY_TOKEN = "github_token"
  }
}
