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
        Icon("flower", R.drawable.ic_day_flower, R.string.icon_flower)
    )

    private val regex = Regex("\\n?\\[\\[IC:([a-z]+)\\]\\]")

    fun byKey(key: String?): Icon? = all.firstOrNull { it.key == key }

    fun marker(key: String) = "[[IC:$key]]"

    fun keyOf(description: String): String? = regex.find(description)?.groupValues?.get(1)

    fun iconOf(description: String): Icon? = byKey(keyOf(description))

    fun strip(description: String): String = regex.replace(description, "").trimEnd()

    /** The description as the person wrote it, without the hidden markers (icon and project). */
    fun visibleText(description: String): String = strip(FacturaScripts.stripMarker(description))

    fun withIcon(description: String, key: String?): String {
        val clean = strip(description)
        return if (key == null) clean else "$clean\n${marker(key)}"
    }
}
