package org.fossify.calendar.helpers

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.calendar.R
import org.fossify.calendar.extensions.config
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ColorPickerDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setFillWithStroke
import org.fossify.commons.extensions.setupDialogStuff

/** The ten colours offered for events and tasks. They can be changed in Settings. */
object EventPalette {
    val DEFAULTS = intArrayOf(
        0xFFE57373.toInt(), 0xFFF2A65A.toInt(), 0xFFF4D35E.toInt(), 0xFF8FCF94.toInt(), 0xFF5DBBA8.toInt(),
        0xFF64B5F6.toInt(), 0xFF7986CB.toInt(), 0xFFBA8FD6.toInt(), 0xFFF28FB1.toInt(), 0xFFA1A1A1.toInt()
    )

    fun colors(context: Context): IntArray {
        val saved = context.config.eventPaletteRaw
        val parts = saved.split(",").mapNotNull { it.trim().toLongOrNull()?.toInt() }
        return if (parts.size == DEFAULTS.size) parts.toIntArray() else DEFAULTS.copyOf()
    }

    fun setColor(context: Context, index: Int, color: Int) {
        val list = colors(context)
        if (index !in list.indices) return
        list[index] = color or 0xFF000000.toInt()
        context.config.eventPaletteRaw = list.joinToString(",")
    }

    fun reset(context: Context) {
        context.config.eventPaletteRaw = ""
    }

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun buildGrid(activity: BaseSimpleActivity, current: Int?, onTap: (Int, Int) -> Unit): GridLayout {
        val bg = activity.getProperBackgroundColor()
        val text = activity.getProperTextColor()
        val grid = GridLayout(activity).apply {
            columnCount = 5
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
        }
        colors(activity).forEachIndexed { index, color ->
            val dot = ImageView(activity).apply {
                setFillWithStroke(color, if (current != null && (current or 0xFF000000.toInt()) == color) text else bg)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(activity, 48)
                    height = dp(activity, 48)
                    setMargins(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4))
                }
                setOnClickListener { onTap(index, color) }
            }
            grid.addView(dot)
        }
        return grid
    }

    /** The picker used when editing an event or task. Gives 0 for "the calendar's colour". */
    fun showPicker(activity: BaseSimpleActivity, current: Int, onPicked: (Int) -> Unit) {
        var dialog: AlertDialog? = null
        val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(buildGrid(activity, current) { _, color ->
            onPicked(color)
            dialog?.dismiss()
        })
        holder.addView(TextView(activity).apply {
            text = activity.getString(R.string.event_color_default)
            setTextColor(activity.getProperPrimaryColor())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            setOnClickListener {
                onPicked(0)
                dialog?.dismiss()
            }
        })
        activity.getAlertDialogBuilder().apply {
            activity.setupDialogStuff(view = holder, dialog = this) { alertDialog -> dialog = alertDialog }
        }
    }

    /** Settings: tap a colour to change it, or restore the defaults. */
    fun showEditor(activity: BaseSimpleActivity) {
        var dialog: AlertDialog? = null
        val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val gridHolder = LinearLayout(activity)
        holder.addView(gridHolder)

        fun fill() {
            gridHolder.removeAllViews()
            gridHolder.addView(buildGrid(activity, null) { index, color ->
                ColorPickerDialog(activity = activity, color = color) { positive, newColor ->
                    if (positive) {
                        setColor(activity, index, newColor)
                        fill()
                    }
                }
            })
        }
        fill()

        holder.addView(TextView(activity).apply {
            text = activity.getString(R.string.event_colors_reset)
            setTextColor(activity.getProperPrimaryColor())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            setOnClickListener {
                reset(activity)
                fill()
            }
        })
        activity.getAlertDialogBuilder().apply {
            activity.setupDialogStuff(view = holder, dialog = this, titleId = R.string.event_colors) { alertDialog -> dialog = alertDialog }
        }
    }
}
