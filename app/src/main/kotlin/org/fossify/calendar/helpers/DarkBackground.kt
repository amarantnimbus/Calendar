package org.fossify.calendar.helpers

import android.content.Context
import android.graphics.Color
import org.fossify.calendar.extensions.config

/**
 * The dark theme background is a lighter gray (#38383C) instead of the almost-black of the library.
 * Pure black (AMOLED) and custom colors are left alone: only the neutral very dark grays are moved.
 */
object DarkBackground {
    const val COLOR = 0xFF38383C.toInt()

    fun apply(context: Context) {
        try {
            val cfg = context.config
            val c = cfg.backgroundColor
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val neutral = r == g && g == b
            if (neutral && r in 0x10..0x2A && c != COLOR) {
                cfg.backgroundColor = COLOR
            }
        } catch (ignored: Exception) {
        }
    }
}
