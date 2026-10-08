package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.extensions.calDAVHelper
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
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

    private val KNOWN_KEYS = setOf("start", "cycle", "overrides", "color_day", "color_night", "color_rest")

    fun isConfigEvent(event: Event) = event.title == TITLE

    private fun currentValues(config: Config): TreeMap<String, String> {
        val values = TreeMap<String, String>()
        values["start"] = config.shiftStartDate
        values["cycle"] = config.shiftCycle
        values["overrides"] = config.shiftOverrides
        values["color_day"] = config.shiftColorDay.toString()
        values["color_night"] = config.shiftColorNight.toString()
        values["color_rest"] = config.shiftColorOffDuty.toString()
        return values
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

    private fun apply(config: Config, values: Map<String, String>) {
        values["start"]?.let { config.shiftStartDate = it }
        values["cycle"]?.let { if (ShiftHelper.parseCycle(it) != null) config.shiftCycle = it }
        values["overrides"]?.let { config.shiftOverrides = it }
        values["color_day"]?.toIntOrNull()?.let { config.shiftColorDay = it }
        values["color_night"]?.toIntOrNull()?.let { config.shiftColorNight = it }
        values["color_rest"]?.toIntOrNull()?.let { config.shiftColorOffDuty = it }
    }

    /**
     * Must run on a background thread. Returns true when settings coming from the other phone were
     * applied here, so the screen should be refreshed.
     */
    fun sync(context: Context): Boolean {
        val config = context.config
        if (!config.caldavSync) {
            return false
        }

        return try {
            val remoteEvent = context.eventsDB.getEventsWithTitle(TITLE).maxByOrNull { it.lastUpdated }
            val remoteValues = remoteEvent?.let { parse(it.description) }
            val remoteText = remoteValues?.let { canonical(it) }
            val localText = canonical(currentValues(config))
            val syncedText = config.shiftSyncedPayload

            if (syncedText.isEmpty()) {
                // first time on this phone: what is already on the server wins
                if (remoteValues != null) {
                    applyRemote(config, remoteValues)
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
                applyRemote(config, remoteValues)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun applyRemote(config: Config, values: Map<String, String>) {
        apply(config, values)
        if (!config.shiftsEnabled) {
            config.shiftsEnabled = true
        }
        config.shiftSyncedPayload = canonical(currentValues(config))
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
    }
}
