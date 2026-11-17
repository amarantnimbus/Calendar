package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.models.DayMonthly

/**
 * Days that get the leaf icon on their own: when a day is marked with the drop icon ("period"),
 * the leaf shows from 15 days later (day 1 drop, leaf from day 16) for 10 days in a row.
 * Nothing is saved: the days are worked out every time from the drop marks. If a new drop is
 * marked, the leaves count from the newest one.
 */
object PeriodCycle {
    const val DROP_KEY = "period"
    const val LEAF_KEY = "leaf"
    private const val LEAF_FROM = 15L
    private const val LEAF_DAYS = 10L

    /** Day codes (yyyyMMdd) among [days] that show the leaf. Must run on a background thread. */
    fun leafDays(context: Context, days: List<DayMonthly>): Set<String> {
        if (days.isEmpty()) {
            return emptySet()
        }

        return try {
            val first = Formatter.getDateTimeFromCode(days.first().code).minusDays((LEAF_FROM + LEAF_DAYS).toInt())
            val last = Formatter.getDateTimeFromCode(days.last().code).plusDays(1)
            val drops = context.eventsDB.getOneTimeEventsOrTasksFromTo(last.seconds(), first.seconds())
                .filter { DayIcons.keyOf(it.description) == DROP_KEY }
                .mapNotNull { ShiftHelper.epochDayFromCode(Formatter.getDayCodeFromTS(it.startTS)) }
                .sorted()
            if (drops.isEmpty()) {
                return emptySet()
            }

            val result = HashSet<String>()
            for (day in days) {
                val epoch = ShiftHelper.epochDayFromCode(day.code) ?: continue
                val drop = drops.lastOrNull { it <= epoch } ?: continue
                val diff = epoch - drop
                if (diff >= LEAF_FROM && diff < LEAF_FROM + LEAF_DAYS) {
                    result.add(day.code)
                }
            }
            result
        } catch (e: Exception) {
            emptySet()
        }
    }
}
