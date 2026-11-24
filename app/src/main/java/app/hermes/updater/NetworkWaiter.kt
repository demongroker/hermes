package app.hermes.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for a usable network instead of giving up.
 *
 * Why: a download that hit a dead connection used to park the card on "Connection lost" until
 * WorkManager's backoff happened to retry. Now the worker waits on a [ConnectivityManager]
 * default-network callback, resumes the partial file (GitHubClient sends `Range: bytes=<done>-`)
 * and keeps going the moment the network is back.
 */
object NetworkWaiter {

  /** True only when a network with real internet capability is up. */
  fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  /** Emits whenever the default network changes; never completes on its own. */
  private fun changes(ctx: Context): Flow<Boolean> = callbackFlow {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (cm == null) {
      trySend(isOnline(ctx))
      awaitClose { }
      return@callbackFlow
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        trySend(true)
      }

      override fun onLost(network: Network) {
        trySend(isOnline(ctx))
      }

      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
      }
    }
    runCatching { cm.registerDefaultNetworkCallback(callback) }
    trySend(isOnline(ctx))
    awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
  }

  /**
   * Suspends until the device has internet again, or [timeoutMs] passes. Returns whether a
   * network is usable right now, so the caller can decide between resuming and handing the
   * retry back to WorkManager.
   */
  suspend fun awaitOnline(ctx: Context, timeoutMs: Long): Boolean {
    if (isOnline(ctx)) return true
    val online = withTimeoutOrNull(timeoutMs) { changes(ctx).first { it } } ?: false
    return online || isOnline(ctx)
  }
}
