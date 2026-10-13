package org.fossify.calendar.helpers

import org.fossify.calendar.R

/**
 * Monochrome icons that can be attached to an event to mark special days (doctor, trip...).
 * The choice travels inside the event description as a hidden marker "[[IC:key]]",
 * so it is synchronized with CalDAV like the rest of the event.
 */
object DayIcons {
    data class Icon(val key: String, val drawable: Int, val labelRes: Int)

    val all = listOf(
        Icon("period", R.drawable.ic_day_period, R.string.icon_period),
        Icon("doctor", R.drawable.ic_day_doctor, R.string.icon_doctor),
        Icon("dentist", R.drawable.ic_day_dentist, R.string.icon_dentist),
        Icon("trip", R.drawable.ic_day_trip, R.string.icon_trip),
        Icon("vacation", R.drawable.ic_day_vacation, R.string.icon_vacation),
        Icon("vet", R.drawable.ic_day_vet, R.string.icon_vet),
        Icon("important", R.drawable.ic_day_important, R.string.icon_important),
        Icon("daisy", R.drawable.ic_day_daisy, R.string.icon_daisy),
        Icon("car", R.drawable.ic_day_car, R.string.icon_car),
        Icon("motorbike", R.drawable.ic_day_motorbike, R.string.icon_motorbike),
        Icon("leaf", R.drawable.ic_day_leaf, R.string.icon_leaf),
        Icon("meeting", R.drawable.ic_day_meeting, R.string.icon_meeting),
        Icon("shopping", R.drawable.ic_day_shopping, R.string.icon_shopping),
        Icon("home", R.drawable.ic_day_home, R.string.icon_home),
        Icon("phone", R.drawable.ic_day_phone, R.string.icon_phone),
        Icon("cake", R.drawable.ic_day_cake, R.string.icon_cake)
    )

    private val regex = Regex("\\n?\\[\\[IC:([a-z]+)\\]\\]")

    fun byKey(key: String?): Icon? = all.firstOrNull { it.key == key }

    fun marker(key: String) = "[[IC:$key]]"

    fun keyOf(description: String): String? = regex.find(description)?.groupValues?.get(1)

    fun iconOf(description: String): Icon? = byKey(keyOf(description))

    // hidden flags that travel in the description too: pending event "[[PD]]" and event color "[[CL:rrggbb]]"
    private val extraRegex = Regex("\\n?\\[\\[(?:PD|CL:[0-9A-Fa-f]{6})\\]\\]")
    private val colorRegex = Regex("\\[\\[CL:([0-9A-Fa-f]{6})\\]\\]")
    const val PENDING_MARKER = "[[PD]]"

    /** Fixed, valid date of pending events (1 January 2000): servers and calendar providers accept it. */
    const val PENDING_START_TS = 946684800L
    const val PENDING_END_TS = 946688400L

    fun isPending(description: String) = description.contains(PENDING_MARKER)

    fun colorOf(description: String): Int? {
        val hex = colorRegex.find(description)?.groupValues?.get(1) ?: return null
        return (0xFF000000 or hex.toLong(16)).toInt()
    }

    fun strip(description: String): String = extraRegex.replace(regex.replace(description, ""), "").trimEnd()

    /** The description as the person wrote it, without the hidden markers (icon and project). */
    fun visibleText(description: String): String = strip(FacturaScripts.stripMarker(description))

    fun withIcon(description: String, key: String?, pending: Boolean = false, color: Int = 0): String {
        var result = strip(description)
        if (key != null) {
            result += "\n${marker(key)}"
        }
        if (pending) {
            result += "\n$PENDING_MARKER"
        }
        if (color != 0) {
            result += "\n[[CL:%06X]]".format(color and 0xFFFFFF)
        }
        return result
    }
}
