package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.extensions.calDAVHelper
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.extensions.refreshCalDAVCalendars
import org.fossify.calendar.R
import org.fossify.calendar.models.CalDAVCalendar
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

    // the colours of the shifts are not shared: each phone keeps its own
    private val KNOWN_KEYS = setOf("start", "cycle", "overrides", "restarts", "cal_colors")

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
        if (config.shiftRestarts.isNotEmpty()) {
            values["restarts"] = config.shiftRestarts
        }
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
        values["restarts"]?.let { config.shiftRestarts = it }
        values["cal_colors"]?.let { applyCalendarColors(context, it) }
    }

    /** All the copies of the hidden event this phone knows of. */
    private fun configEvents(context: Context): List<Event> = context.eventsDB.getEventsWithTitle(TITLE)

    /**
     * Picks the right copy when there are several: the ones in the shared calendar first, then the
     * one furthest in the future (the current ones are at 2099, the old ones at 2000), then the newest.
     */
    private fun pickConfigEvent(context: Context, events: List<Event>): Event? {
        if (events.isEmpty()) {
            return null
        }
        val junts = context.config.juntsCalendarId
        val inJunts = events.filter { it.calendarId == junts }
        val pool = if (junts > 0 && inJunts.isNotEmpty()) inJunts else events
        return pool.sortedWith(compareByDescending<Event> { it.startTS }.thenByDescending { it.lastUpdated }).first()
    }

    /** The writable synced calendar where the hidden event should live: "Junts" first. */
    private fun chooseCalendar(context: Context): CalDAVCalendar? {
        val config = context.config
        val writable = context.calDAVHelper
            .getCalDAVCalendars(config.caldavSyncedCalendarIds, false)
            .filter { it.canWrite() }
        return writable.firstOrNull { context.eventsHelper.getCalendarWithCalDAVCalendarId(it.id)?.id == config.juntsCalendarId }
            ?: writable.firstOrNull { it.id == config.lastUsedCaldavCalendarId }
            ?: writable.firstOrNull()
    }

    /** True when the copy on this phone should be tidied up by the phone that edits the shifts. */
    private fun needsCleanup(context: Context, events: List<Event>, picked: Event): Boolean {
        if (events.size > 1 || picked.startTS < FAR_START_LIMIT) {
            return true
        }
        val junts = context.config.juntsCalendarId
        if (junts > 0 && picked.calendarId != junts) {
            val chosen = chooseCalendar(context)
            return chosen != null && context.eventsHelper.getCalendarWithCalDAVCalendarId(chosen.id)?.id == junts
        }
        return false
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
            val allCopies = configEvents(context)
            val remoteEvent = pickConfigEvent(context, allCopies)
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
            } else if (remoteEvent == null && config.shiftsEnabled) {
                // the event is gone (it did not survive a sync): write it again
                push(context, null, localText)
                false
            } else if (remoteEvent != null && needsCleanup(context, allCopies, remoteEvent)) {
                // old or duplicated copies: leave one, in the shared calendar
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
            val events = configEvents(context)
            val newest = pickConfigEvent(context, events)
            sb.append(context.getString(R.string.shift_status_event, if (newest == null) context.getString(R.string.shift_status_no) else context.getString(R.string.shift_status_yes)))
            if (events.size > 1) {
                sb.append("\n").append(context.getString(R.string.shift_status_duplicates, events.size))
            }
            if (newest != null) {
                val calName = context.eventsHelper.getCalendarsSync().firstOrNull { it.id == newest.calendarId }
                    ?.let { it.caldavDisplayName.ifEmpty { it.title } } ?: "?"
                sb.append("\n").append(context.getString(R.string.shift_status_calendar, calName))
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

    private fun hasConfigEvent(context: Context) = try {
        context.eventsDB.getEventsWithTitle(TITLE).isNotEmpty()
    } catch (e: Exception) {
        false
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

            val startedAt = System.currentTimeMillis()
            val applied = sync(app)
            if (config.canEditShifts && config.shiftLastPush >= startedAt) {
                app.refreshCalDAVCalendars(config.caldavSyncedCalendarIds, false)
                TailscaleSync.waitForCalendarSync(60_000L)
            }

            report.append(
                app.getString(
                    when {
                        !config.canEditShifts && !hasConfigEvent(app) -> R.string.shift_force_missing
                        applied || config.shiftLastPull >= startedAt -> R.string.shift_force_applied
                        config.shiftLastPush >= startedAt -> R.string.shift_force_pushed
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
        val config = context.config
        val chosen = chooseCalendar(context)
        val copies = configEvents(context)

        var current = existing
        if (current != null && chosen != null) {
            val chosenLocalId = context.eventsHelper.getCalendarWithCalDAVCalendarId(chosen.id)?.id
            if (chosenLocalId != null && current.calendarId != chosenLocalId && config.juntsCalendarId == chosenLocalId) {
                // it lives in a calendar the other phone may not receive: make a new one in the shared calendar
                current = null
            }
        }

        // only one copy stays: the others (old tests, duplicates) are deleted here and on the server
        copies.filter { it.id != null && it.id != current?.id }.forEach {
            try {
                context.eventsHelper.deleteEvent(it.id!!, true)
            } catch (e: Exception) {
                config.shiftLastError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200)
            }
        }

        if (current != null) {
            current.description = description
            current.lastUpdated = System.currentTimeMillis()
            if (current.startTS < FAR_START_LIMIT) {
                // far in the future, so no "ignore old events" setting of the sync app can skip it
                val start = farStart()
                current.startTS = start
                current.endTS = start + 60
            }
            context.eventsHelper.updateEvent(
                current,
                updateAtCalDAV = true,
                showToasts = false,
                enableCalendar = false
            )
            config.shiftSyncedPayload = text
            config.shiftLastPush = System.currentTimeMillis()
            return
        }

        if (chosen == null) {
            config.shiftLastError = context.getString(R.string.shift_error_no_calendar)
            return
        }
        val localCalendar = context.eventsHelper.getCalendarWithCalDAVCalendarId(chosen.id) ?: return

        val start = farStart()
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

        if (configEvents(context).isEmpty()) {
            // not kept: do not pretend it was sent, so the next sync tries again
            config.shiftLastError = context.getString(R.string.shift_error_not_saved)
            return
        }
        config.shiftSyncedPayload = text
        config.shiftLastPush = System.currentTimeMillis()
    }

    private fun farStart() = DateTime(2099, 1, 1, 0, 0).seconds()
    private val FAR_START_LIMIT = DateTime(2090, 1, 1, 0, 0).seconds()
}
