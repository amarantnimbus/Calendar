package org.fossify.calendar.helpers

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * Format marks of the project log texts: *bold*, #italic# and _underline_.
 * The text is stored with the raw marks; they are converted only when they open and close
 * attached to a word, so nom_cognom@correu.com or plànol_1.pdf are left as they are.
 */
object FormatMarks {
    private val regex = Regex("(?<![\\p{L}\\p{N}])([*#_])(?=\\S)(.+?)(?<=\\S)\\1(?![\\p{L}\\p{N}])")

    /** The text without the marks (the words stay). */
    fun plain(text: String): String = regex.replace(text) { it.groupValues[2] }

    /** The text with the marks converted to bold / italic / underline. */
    fun toSpannable(text: String): CharSequence {
        val out = SpannableStringBuilder()
        var last = 0
        for (m in regex.findAll(text)) {
            out.append(text, last, m.range.first)
            val start = out.length
            out.append(m.groupValues[2])
            val span: Any = when (m.groupValues[1]) {
                "*" -> StyleSpan(Typeface.BOLD)
                "#" -> StyleSpan(Typeface.ITALIC)
                else -> UnderlineSpan()
            }
            out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return out
    }
}
