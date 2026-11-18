package org.fossify.calendar.helpers

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.CalendarContract
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.refreshCalDAVCalendars
import java.util.concurrent.atomic.AtomicInteger

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

    private val pushJobs = AtomicInteger(0)
    @Volatile
    private var turnedOnByUs = false

    /**
     * Must run on a background thread. Used after a change made on this phone (save, delete, shift):
     * turns Tailscale on if it was off, asks the calendar accounts to sync so the change reaches the
     * server, waits for it and turns Tailscale off again if it was this who turned it on.
     * Without connection nothing is lost: the change stays on the phone and goes with the next sync.
     */
    fun pushChanges(context: Context) {
        val app = context.applicationContext
        try {
            if (!app.config.caldavSync || !isInstalled(app)) {
                return
            }

            pushJobs.incrementAndGet()
            try {
                if (!isActive(app)) {
                    turnedOnByUs = true
                    connect(app)
                    val connected = waitUntil(25_000L) { isActive(app) }
                    if (!connected) {
                        return
                    }
                    Thread.sleep(2000)
                }

                app.refreshCalDAVCalendars(app.config.caldavSyncedCalendarIds, false)
                waitForCalendarSync(60_000L)
            } finally {
                if (pushJobs.decrementAndGet() == 0 && turnedOnByUs) {
                    turnedOnByUs = false
                    disconnect(app)
                }
            }
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
