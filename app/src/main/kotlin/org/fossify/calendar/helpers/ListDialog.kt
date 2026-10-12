package org.fossify.calendar.helpers

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff

/** A list of tappable lines in the theme colors (plain dialogs can show light text on a light background). */
fun BaseSimpleActivity.showItemsDialog(
    titleId: Int,
    items: List<CharSequence>,
    emptyText: String? = null,
    positiveTextId: Int? = null,
    onPositive: (() -> Unit)? = null,
    onItem: (Int) -> Unit
) {
    val textColor = getProperTextColor()
    val density = resources.displayMetrics.density
    val side = (24 * density).toInt()
    val vertical = (12 * density).toInt()
    val holder = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(side, (8 * density).toInt(), side, (8 * density).toInt())
    }

    var dialog: AlertDialog? = null
    if (items.isEmpty() && emptyText != null) {
        holder.addView(TextView(this).apply {
            text = emptyText
            setTextColor(textColor)
            textSize = 16f
            setPadding(0, vertical, 0, vertical)
        })
    }

    items.forEachIndexed { index, line ->
        holder.addView(TextView(this).apply {
            text = line
            setTextColor(textColor)
            textSize = 16f
            maxLines = 2
            setPadding(0, vertical, 0, vertical)
            setOnClickListener {
                dialog?.dismiss()
                onItem(index)
            }
        })
    }

    val scroll = ScrollView(this).apply { addView(holder) }
    getAlertDialogBuilder().apply {
        if (positiveTextId != null) {
            setPositiveButton(positiveTextId) { _, _ -> onPositive?.invoke() }
        }
        setNegativeButton(org.fossify.commons.R.string.cancel, null)
        setupDialogStuff(view = scroll, dialog = this, titleId = titleId) { alertDialog ->
            dialog = alertDialog
        }
    }
}

/** A scrollable block of text in the theme colors. */
fun BaseSimpleActivity.showTextDialog(titleId: Int, message: CharSequence) {
    val density = resources.displayMetrics.density
    val side = (24 * density).toInt()
    val textView = TextView(this).apply {
        text = message
        setTextColor(getProperTextColor())
        textSize = 15f
        setLineSpacing(0f, 1.15f)
        setPadding(side, (8 * density).toInt(), side, (8 * density).toInt())
    }

    val scroll = ScrollView(this).apply { addView(textView) }
    getAlertDialogBuilder().apply {
        setPositiveButton(org.fossify.commons.R.string.ok, null)
        setupDialogStuff(view = scroll, dialog = this, titleId = titleId)
    }
}
