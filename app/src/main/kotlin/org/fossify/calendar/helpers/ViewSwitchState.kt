package org.fossify.calendar.helpers

/** Remembers the day opened in the month view so a pinch can jump to it in the day view. */
object ViewSwitchState {
    @Volatile
    var selectedDayCode: String? = null
}
