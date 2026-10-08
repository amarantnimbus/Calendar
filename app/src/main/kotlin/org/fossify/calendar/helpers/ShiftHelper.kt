package org.fossify.calendar.helpers

import java.time.LocalDate

/**
 * The kinds of shift a day can have. [code] is the single letter used when the cycle and the
 * manual changes are stored and exported: D = day, N = night, S = off duty ("saliente"), L = free.
 */
enum class Shift(val code: String) {
    DAY("D"),
    NIGHT("N"),
    OFF_DUTY("S"),
    FREE("L");

    companion object {
        fun fromCode(code: String): Shift? {
            return values().firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
        }
    }
}

/**
 * Everything needed to know the shift of any day. It is built once per drawing pass, so the
 * stored text is not parsed again for every day cell.
 */
class ShiftSchedule(
    private val startEpochDay: Long?,
    private val cycle: List<Shift>,
    private val overrides: Map<String, Shift>,
    private val colors: Map<Shift, Int>
) {

    /** Returns the shift of the day with the given code (yyyyMMdd), or null if it is unknown. */
    fun shiftFor(dayCode: String): Shift? {
        overrides[dayCode]?.let { return it }

        val start = startEpochDay ?: return null
        if (cycle.isEmpty()) {
            return null
        }

        val day = ShiftHelper.epochDayFromCode(dayCode) ?: return null
        // floorMod keeps the result positive for days before the start date, so the cycle also
        // repeats towards the past.
        val index = Math.floorMod(day - start, cycle.size.toLong()).toInt()
        return cycle[index]
    }

    /** The color of a shift, or null if it has none (free days are not painted). */
    fun colorFor(shift: Shift): Int? = colors[shift]
}

object ShiftHelper {

    private const val HALF_TRANSPARENT = 128
    private val CYCLE_BLOCK = Regex("^(\\d{1,3})([A-Za-z])$")

    /**
     * Turns text like "2D,2N,2S,4L" into one entry per day of the cycle. Returns null when the
     * text is not valid.
     */
    fun parseCycle(text: String): List<Shift>? {
        val blocks = text.trim().split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
        if (blocks.isEmpty()) {
            return null
        }

        val result = ArrayList<Shift>()
        for (block in blocks) {
            val match = CYCLE_BLOCK.matchEntire(block) ?: return null
            val count = match.groupValues[1].toInt()
            val shift = Shift.fromCode(match.groupValues[2]) ?: return null
            if (count < 1) {
                return null
            }

            repeat(count) { result.add(shift) }
        }

        return if (result.size in 1..366) result else null
    }

    fun epochDayFromCode(dayCode: String): Long? {
        if (dayCode.length != 8) {
            return null
        }

        return try {
            LocalDate.of(
                dayCode.substring(0, 4).toInt(),
                dayCode.substring(4, 6).toInt(),
                dayCode.substring(6, 8).toInt()
            ).toEpochDay()
        } catch (e: Exception) {
            null
        }
    }

    fun parseOverrides(text: String): LinkedHashMap<String, Shift> {
        val result = LinkedHashMap<String, Shift>()
        text.split(";").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val dayCode = parts[0].trim()
                val shift = Shift.fromCode(parts[1])
                if (dayCode.length == 8 && shift != null) {
                    result[dayCode] = shift
                }
            }
        }

        return result
    }

    private fun serializeOverrides(overrides: Map<String, Shift>): String {
        return overrides.entries.joinToString(";") { "${it.key}:${it.value.code}" }
    }

    /** Sets (or, with a null shift, removes) the manual change of a single day. */
    fun setOverride(config: Config, dayCode: String, shift: Shift?) {
        val overrides = parseOverrides(config.shiftOverrides)
        if (shift == null) {
            overrides.remove(dayCode)
        } else {
            overrides[dayCode] = shift
        }

        config.shiftOverrides = serializeOverrides(overrides)
    }

    /** Builds the schedule from the saved settings, or null when shifts are turned off. */
    fun load(config: Config): ShiftSchedule? {
        if (!config.shiftsEnabled) {
            return null
        }

        val colors = mapOf(
            Shift.DAY to config.shiftColorDay,
            Shift.NIGHT to config.shiftColorNight,
            Shift.OFF_DUTY to config.shiftColorOffDuty
        )

        return ShiftSchedule(
            startEpochDay = epochDayFromCode(config.shiftStartDate),
            cycle = parseCycle(config.shiftCycle) ?: emptyList(),
            overrides = parseOverrides(config.shiftOverrides),
            colors = colors
        )
    }

    /** Same color with 50% transparency. */
    fun withHalfTransparency(color: Int): Int {
        return (color and 0x00FFFFFF) or (HALF_TRANSPARENT shl 24)
    }
}
