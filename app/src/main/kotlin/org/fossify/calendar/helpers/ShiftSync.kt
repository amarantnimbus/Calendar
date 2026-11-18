package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.extensions.calDAVHelper
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.extensions.refreshCalDAVCalendars
import org.fossify.calendar.R
import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.TreeMap

/**
 * Keeps the shift settings the same on every phone that uses the same CalDAV calendar.
 *
 * The settings travel inside one hidden event of an already synced CalDAV calendar. The event is
 * never shown in the app (see [isConfigEvent]). The phone where the shifts are edited writes it,
 * and the other phones read it. Nothing has to be set up.
 */
object ShiftSync {
    const val TITLE = "Torns (configuració, no esborrar)"
    private const val HEADER = "FOSSIFY-TORNS-V1"
    private const val NOTE = "Aquest esdeveniment guarda els torns i l'app el mostra amagat. No l'esborris."

    private val KNOWN_KEYS = setOf("start", "cycle", "overrides", "color_day", "color_night", "color_rest", "color_free", "cal_colors")

    const val JUNTS = "Junts"

    /** Finds the calendar called "Junts" so the month view and the new event screen can use it. */
    fun refreshJuntsCalendar(context: Context) {
        val id = try {
            context.eventsHelper.getCalendarsSync().firstOrNull {
                it.title.equals(JUNTS, true) || it.caldavDisplayName.equals(JUNTS, true)
            }?.id ?: -1L
        } catch (e: Exception) {
            -1L
        }
        if (id != context.config.juntsCalendarId) {
            context.config.juntsCalendarId = id
        }
    }

    fun isConfigEvent(event: Event) = event.title == TITLE

    private fun currentValues(context: Context, config: Config = context.config): TreeMap<String, String> {
        val values = TreeMap<String, String>()
        values["start"] = config.shiftStartDate
        values["cycle"] = config.shiftCycle
        values["overrides"] = config.shiftOverrides
        values["color_day"] = config.shiftColorDay.toString()
        values["color_night"] = config.shiftColorNight.toString()
        values["color_rest"] = config.shiftColorOffDuty.toString()
        values["color_free"] = config.shiftColorFree.toString()
        values["cal_colors"] = calendarColors(context)
        return values
    }

    /** Colours of the synced calendars, by name, so every phone paints them the same. */
    private fun calendarColors(context: Context): String {
        return context.eventsHelper.getCalendarsSync()
            .filter { it.caldavCalendarId != 0 }
            .sortedBy { it.caldavDisplayName }
            .joinToString(",") { URLEncoder.encode(it.caldavDisplayName, "UTF-8") + ":" + it.color }
    }

    private fun applyCalendarColors(context: Context, value: String) {
        val wanted = HashMap<String, Int>()
        value.split(",").forEach { part ->
            val index = part.lastIndexOf(':')
            val color = part.substring(index + 1).toIntOrNull()
            if (index > 0 && color != null) {
                wanted[URLDecoder.decode(part.substring(0, index), "UTF-8")] = color
            }
        }

        context.eventsHelper.getCalendarsSync()
            .filter { it.caldavCalendarId != 0 && wanted[it.caldavDisplayName] != null }
            .forEach {
                val color = wanted[it.caldavDisplayName]!!
                if (it.color != color) {
                    it.color = color
                    context.eventsHelper.insertOrUpdateCalendarSync(it)
                }
            }
    }

    private fun canonical(values: Map<String, String>): String {
        return TreeMap(values).entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    private fun parse(description: String): TreeMap<String, String>? {
        val lines = description.lines()
        if (lines.firstOrNull()?.trim() != HEADER) {
            return null
        }

        val values = TreeMap<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf('=')
            if (index > 0) {
                val key = line.substring(0, index)
                if (key in KNOWN_KEYS) {
                    values[key] = line.substring(index + 1).trim()
                }
            }
        }

        return if (values.isEmpty()) null else values
    }

    private fun apply(context: Context, values: Map<String, String>) {
        val config = context.config
        values["start"]?.let { config.shiftStartDate = it }
        values["cycle"]?.let { if (ShiftHelper.parseCycle(it) != null) config.shiftCycle = it }
        values["overrides"]?.let { config.shiftOverrides = it }
        values["color_day"]?.toIntOrNull()?.let { config.shiftColorDay = it }
        values["color_night"]?.toIntOrNull()?.let { config.shiftColorNight = it }
        values["color_rest"]?.toIntOrNull()?.let { config.shiftColorOffDuty = it }
        values["color_free"]?.toIntOrNull()?.let { config.shiftColorFree = it }
        values["cal_colors"]?.let { applyCalendarColors(context, it) }
    }

    /**
     * Must run on a background thread. Returns true when settings coming from the other phone were
     * applied here, so the screen should be refreshed.
     */
    fun sync(context: Context): Boolean {
        val config = context.config
        refreshJuntsCalendar(context)
        if (!config.caldavSync) {
            return false
        }

        return try {
            val remoteEvent = context.eventsDB.getEventsWithTitle(TITLE).maxByOrNull { it.lastUpdated }
            val remoteValues = remoteEvent?.let { parse(it.description) }
            val remoteText = remoteValues?.let { canonical(it) }
            val localText = canonical(currentValues(context))
            val syncedText = config.shiftSyncedPayload

            if (!config.canEditShifts) {
                // this phone only reads: what is on the server is what is shown, and nothing is written
                if (remoteValues == null) {
                    false
                } else {
                    val local = currentValues(context)
                    val differs = remoteValues.any { (key, value) -> key != "cal_colors" && local[key] != value }
                    if (remoteText != syncedText || differs) {
                        applyRemote(context, remoteValues)
                        true
                    } else {
                        false
                    }
                }
            } else if (syncedText.isEmpty()) {
                // first time on this phone: what is already on the server wins
                if (remoteValues != null) {
                    applyRemote(context, remoteValues)
                    true
                } else {
                    if (config.shiftsEnabled) {
                        push(context, remoteEvent, localText)
                    }
                    false
                }
            } else if (localText != syncedText) {
                // changed here: this phone is the one that edits the shifts
                push(context, remoteEvent, localText)
                false
            } else if (remoteText != null && remoteText != syncedText) {
                applyRemote(context, remoteValues)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            config.shiftLastError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200)
            false
        }
    }

    /** Same as [sync], and when something was written asks the server to receive it (Tailscale on and off by itself). */
    fun syncAndPush(context: Context) {
        val before = context.config.shiftLastPush
        sync(context)
        if (context.config.canEditShifts && context.config.shiftLastPush != before) {
            TailscaleSync.pushChanges(context)
        }
    }

    /** Something readable about the hidden event and the last exchanges, for the settings row. */
    fun statusText(context: Context): String {
        val config = context.config
        val sb = StringBuilder()
        try {
            val events = context.eventsDB.getEventsWithTitle(TITLE)
            val newest = events.maxByOrNull { it.lastUpdated }
            sb.append(context.getString(R.string.shift_status_event, if (newest == null) context.getString(R.string.shift_status_no) else context.getString(R.string.shift_status_yes)))
            if (events.size > 1) {
                sb.append("\n").append(context.getString(R.string.shift_status_duplicates, events.size))
            }
            if (newest != null) {
                sb.append("\n").append(context.getString(R.string.shift_status_event_date, formatTime(newest.lastUpdated)))
            }
        } catch (e: Exception) {
            sb.append(e.javaClass.simpleName)
        }
        sb.append("\n").append(context.getString(R.string.shift_status_push, formatTime(config.shiftLastPush)))
        sb.append("\n").append(context.getString(R.string.shift_status_pull, formatTime(config.shiftLastPull)))
        if (config.shiftLastError.isNotEmpty()) {
            sb.append("\n").append(context.getString(R.string.shift_status_error, config.shiftLastError))
        }
        return sb.toString()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) {
            return "—"
        }
        return DateTime(ms).toString("dd/MM HH:mm:ss")
    }

    /**
     * Must run on a background thread. Forces the exchange: turns Tailscale on if needed, asks the
     * server for news, applies what came and pushes what changed, and returns a text with the result.
     */
    fun forceSync(context: Context): String {
        val app = context.applicationContext
        val config = app.config
        val report = StringBuilder()
        if (!config.caldavSync) {
            return app.getString(R.string.shift_force_no_caldav)
        }

        config.shiftLastError = ""
        val installed = TailscaleSync.isInstalled(app)
        val wasActive = installed && TailscaleSync.isActive(app)
        try {
            if (installed && !wasActive) {
                TailscaleSync.connect(app)
                val ok = TailscaleSync.waitUntil(25_000L) { TailscaleSync.isActive(app) }
                report.append(app.getString(if (ok) R.string.shift_force_vpn_ok else R.string.shift_force_vpn_fail)).append("\n")
                if (ok) {
                    Thread.sleep(2000)
                }
            }

            app.refreshCalDAVCalendars(config.caldavSyncedCalendarIds, false)
            TailscaleSync.waitForCalendarSync(90_000L)

            val pulledBefore = config.shiftLastPull
            val pushedBefore = config.shiftLastPush
            val applied = sync(app)
            if (config.canEditShifts && config.shiftLastPush != pushedBefore) {
                app.refreshCalDAVCalendars(config.caldavSyncedCalendarIds, false)
                TailscaleSync.waitForCalendarSync(60_000L)
            }

            report.append(
                app.getString(
                    when {
                        applied || config.shiftLastPull != pulledBefore -> R.string.shift_force_applied
                        config.shiftLastPush != pushedBefore -> R.string.shift_force_pushed
                        else -> R.string.shift_force_nothing
                    }
                )
            ).append("\n\n")
        } catch (e: Exception) {
            config.shiftLastError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200)
        } finally {
            if (installed && !wasActive) {
                TailscaleSync.disconnect(app)
            }
        }

        report.append(statusText(app))
        return report.toString()
    }

    private fun applyRemote(context: Context, values: Map<String, String>) {
        val config = context.config
        apply(context, values)
        if (!config.shiftsEnabled) {
            config.shiftsEnabled = true
        }
        config.shiftSyncedPayload = canonical(values)
        config.shiftLastPull = System.currentTimeMillis()
    }

    private fun push(context: Context, existing: Event?, text: String) {
        val description = "$HEADER\n$text\n\n$NOTE"
        if (existing != null) {
            existing.description = description
            existing.lastUpdated = System.currentTimeMillis()
            context.eventsHelper.updateEvent(
                existing,
                updateAtCalDAV = true,
                showToasts = false,
                enableCalendar = false
            )
            context.config.shiftSyncedPayload = text
            context.config.shiftLastPush = System.currentTimeMillis()
            return
        }

        val config = context.config
        val writable = context.calDAVHelper
            .getCalDAVCalendars(config.caldavSyncedCalendarIds, false)
            .filter { it.canWrite() }
        val chosen = writable.firstOrNull { it.id == config.lastUsedCaldavCalendarId } ?: writable.firstOrNull() ?: return
        val localCalendar = context.eventsHelper.getCalendarWithCalDAVCalendarId(chosen.id) ?: return

        val start = DateTime(2000, 1, 1, 0, 0).seconds()
        val event = Event(
            id = null,
            startTS = start,
            endTS = start + 60,
            title = TITLE,
            description = description,
            timeZone = DateTimeZone.getDefault().id,
            calendarId = localCalendar.id!!,
            lastUpdated = System.currentTimeMillis(),
            source = "$CALDAV-${chosen.id}"
        )
        context.eventsHelper.insertEvent(
            event,
            addToCalDAV = true,
            showToasts = false,
            enableCalendar = false
        )
        config.shiftSyncedPayload = text
        config.shiftLastPush = System.currentTimeMillis()
    }
}
