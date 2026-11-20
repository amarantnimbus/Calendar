package org.fossify.calendar.helpers

/**
 * Lets a save that finishes in the background tell the calendar screen that is open to redraw.
 * The main screen sets the listener while it is in front and clears it when it leaves.
 */
object RefreshBus {
    @Volatile
    var listener: (() -> Unit)? = null

    fun changed() {
        listener?.invoke()
    }
}
