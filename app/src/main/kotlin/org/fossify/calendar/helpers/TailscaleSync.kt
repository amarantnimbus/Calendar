package org.fossify.calendar.helpers

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.CalendarContract

/**
 * Turns the Tailscale VPN on and off through the broadcast receiver the Tailscale app exposes,
 * and tells whether the calendar accounts are still synchronizing.
 */
object TailscaleSync {
    private const val PACKAGE = "com.tailscale.ipn"
    private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"
    private const val ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT_VPN"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** A VPN is the active network (when Tailscale is on, it is). */
    fun isActive(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) {
            false
        }
    }

    fun connect(context: Context) = send(context, ACTION_CONNECT)

    fun disconnect(context: Context) = send(context, ACTION_DISCONNECT)

    private fun send(context: Context, action: String) {
        try {
            val intent = Intent(action).setClassName(PACKAGE, RECEIVER)
            context.sendBroadcast(intent)
        } catch (ignored: Exception) {
        }
    }

    /** Blocks (call it from a background thread) until the condition holds or the time is over. */
    fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) {
                return true
            }
            Thread.sleep(500)
        }
        return condition()
    }

    private fun isCalendarSyncRunning(): Boolean {
        return try {
            ContentResolver.getCurrentSyncs().any { it.authority == CalendarContract.AUTHORITY }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Blocks until the calendar synchronization that was just requested has run and stopped.
     * If no synchronization is ever seen, gives up after a few seconds.
     */
    fun waitForCalendarSync(timeoutMs: Long = 120_000L) {
        val start = System.currentTimeMillis()
        var seen = false
        var idleSeconds = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(1000)
            if (isCalendarSyncRunning()) {
                seen = true
                idleSeconds = 0
            } else {
                idleSeconds++
            }

            if ((seen && idleSeconds >= 4) || (!seen && idleSeconds >= 10)) {
                break
            }
        }
    }
}
