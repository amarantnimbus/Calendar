package org.fossify.calendar.helpers

/**
 * The background of a task does not use the color of its calendar: red while it is pending and
 * green once it is done. Pills in the month grid use the stronger pair, the cards (open day,
 * day view, list widget) the softer one.
 */
object TaskColors {
    val PILL_PENDING = 0xFFC0504D.toInt()
    val PILL_DONE = 0xFF5F9E63.toInt()
    val CARD_PENDING = 0xFFD98880.toInt()
    val CARD_DONE = 0xFF8FCF94.toInt()

    fun pill(done: Boolean) = if (done) PILL_DONE else PILL_PENDING

    fun card(done: Boolean) = if (done) CARD_DONE else CARD_PENDING
}
