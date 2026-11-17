package org.fossify.calendar.helpers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.commons.helpers.ensureBackgroundThread

/** Shared look of the cards of the day view and of the lists: flat, soft background, no frame. */
object EventCards {
    /** The same softer tone the open-day cards of the month view use. */
    fun soft(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1] * 0.72f
        return ColorUtils.HSLToColor(hsl) or 0xFF000000.toInt()
    }

    fun background(color: Int, isTask: Boolean, done: Boolean): Int =
        if (isTask) TaskColors.card(done) else soft(color)

    fun readableOn(color: Int): Int {
        val c = color or 0xFF000000.toInt()
        return if (ColorUtils.calculateContrast(Color.WHITE, c) >= ColorUtils.calculateContrast(Color.BLACK, c)) Color.WHITE else Color.BLACK
    }

    /** Initial of every calendar, loaded off the main thread: callback runs on the calling thread's post. */
    fun loadInitials(context: Context, done: (Map<Long, String>) -> Unit) {
        val app = context.applicationContext
        ensureBackgroundThread {
            val map = HashMap<Long, String>()
            app.calendarsDB.getCalendars().forEach { cal ->
                val id = cal.id ?: return@forEach
                val name = cal.caldavDisplayName.ifEmpty { cal.title }
                if (name.isNotEmpty()) map[id] = name.substring(0, 1).uppercase()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(map) }
        }
    }
}

/** Round task mark: empty circle while pending, filled circle with a check when done. */
class TaskMarkDrawable(private val done: Boolean, private val color: Int, private val checkColor: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val w = maxOf(1f, size * 0.11f)
        val r = size / 2f - w / 2f
        if (done) {
            fill.color = color
            canvas.drawCircle(cx, cy, r + w / 2f, fill)
            stroke.color = checkColor
            stroke.strokeWidth = maxOf(1.2f, size * 0.13f)
            path.reset()
            path.moveTo(cx - size * 0.24f, cy + size * 0.02f)
            path.lineTo(cx - size * 0.06f, cy + size * 0.20f)
            path.lineTo(cx + size * 0.26f, cy - size * 0.18f)
            canvas.drawPath(path, stroke)
        } else {
            stroke.color = color
            stroke.strokeWidth = w
            canvas.drawCircle(cx, cy, r, stroke)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Calendar icon of the event screen: a round bubble in the calendar color with its initial. */
class CalendarBubbleDrawable(private val color: Int, private val initial: String) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        fill.color = color or 0xFF000000.toInt()
        canvas.drawCircle(cx, cy, size / 2f, fill)
        text.color = EventCards.readableOn(color)
        text.textSize = size * 0.55f
        val y = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(initial, cx, y, text)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Task icon of the event screen: circle with a check; colors inverted (filled) while the task is on. */
class TaskToggleDrawable(private val active: Boolean, private val fg: Int, private val bg: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val w = maxOf(1.5f, size * 0.09f)
        val r = size / 2f - w / 2f
        if (active) {
            fill.color = fg
            canvas.drawCircle(cx, cy, r + w / 2f, fill)
        } else {
            stroke.color = fg
            stroke.strokeWidth = w
            canvas.drawCircle(cx, cy, r, stroke)
        }
        stroke.color = if (active) bg else fg
        stroke.strokeWidth = maxOf(1.8f, size * 0.10f)
        path.reset()
        path.moveTo(cx - size * 0.22f, cy + size * 0.02f)
        path.lineTo(cx - size * 0.06f, cy + size * 0.18f)
        path.lineTo(cx + size * 0.23f, cy - size * 0.16f)
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
