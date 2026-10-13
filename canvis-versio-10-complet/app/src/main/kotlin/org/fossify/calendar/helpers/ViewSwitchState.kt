package org.fossify.calendar.helpers

import org.joda.time.DateTime
import java.util.concurrent.CopyOnWriteArrayList

/** Remembers the day opened in the month view so a pinch can jump to it in the day view. */
object ViewSwitchState {
    @Volatile
    var selectedDayCode: String? = null

    /** Year screens register here so a pinch can find the month under the center of the fingers. */
    val yearFocusResolvers = CopyOnWriteArrayList<(Float, Float) -> DateTime?>()
}
