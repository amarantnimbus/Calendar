package org.fossify.calendar.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.fossify.calendar.R
import org.fossify.calendar.helpers.DayIcons

/** Rows of an event's checklist: drag handle, round check bubble and the text. */
class ChecklistAdapter(
    private val context: Context,
    private val items: MutableList<DayIcons.CheckItem>,
    private val textColor: Int,
    private val onChanged: () -> Unit,
    private val onRemoved: () -> Unit,
    private val onEnter: (Int) -> Unit,
    private val startDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.Holder>() {

    var focusPosition = -1

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    class Holder(
        val row: LinearLayout,
        val handle: ImageView,
        val bubble: ImageView,
        val text: EditText,
        val remove: ImageView
    ) : RecyclerView.ViewHolder(row) {
        var watcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        }
        val handle = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag_handle)
            setColorFilter((textColor and 0x00FFFFFF) or 0x80000000.toInt())
            setPadding(dp(2), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(44))
        }
        val bubble = ImageView(context).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
        val text = EditText(context).apply {
            background = null
            setPadding(dp(6), 0, dp(4), 0)
            setTextColor(textColor)
            textSize = 16f
            hint = context.getString(R.string.checklist_item_hint)
            setHintTextColor((textColor and 0x00FFFFFF) or 0x66000000)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val remove = ImageView(context).apply {
            setImageResource(R.drawable.ic_checklist_x)
            setColorFilter((textColor and 0x00FFFFFF) or 0x99000000.toInt())
            setPadding(dp(11), dp(11), dp(11), dp(11))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
            contentDescription = context.getString(R.string.checklist_remove_item)
        }
        row.addView(handle)
        row.addView(bubble)
        row.addView(text)
        row.addView(remove)
        return Holder(row, handle, bubble, text, remove)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.watcher?.let { holder.text.removeTextChangedListener(it) }
        holder.text.setText(item.text)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos].text = s?.toString() ?: ""
                    onChanged()
                }
            }
        }
        holder.watcher = watcher
        holder.text.addTextChangedListener(watcher)
        holder.text.setOnEditorActionListener { _, _, _ ->
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION && items[pos].text.isNotBlank()) {
                onEnter(pos)
            }
            true
        }

        applyDone(holder, item.done)
        holder.bubble.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                items[pos].done = !items[pos].done
                applyDone(holder, items[pos].done)
                onChanged()
            }
        }
        holder.remove.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                remove(pos)
            }
        }
        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                startDrag(holder)
            }
            false
        }

        if (position == focusPosition) {
            focusPosition = -1
            holder.text.post {
                holder.text.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(holder.text, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun applyDone(holder: Holder, done: Boolean) {
        holder.bubble.setImageDrawable(bubbleDrawable(done))
        holder.text.alpha = if (done) 0.55f else 1f
    }

    private fun bubbleDrawable(done: Boolean) = if (done) {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF5F9E63.toInt())
        }
        val check = ContextCompat.getDrawable(context, R.drawable.ic_check_white)!!.mutate()
        LayerDrawable(arrayOf(circle, check)).apply { setLayerInset(1, dp(2), dp(2), dp(2), dp(2)) }
    } else {
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            setStroke(dp(2), (textColor and 0x00FFFFFF) or 0x99000000.toInt())
        }
    }

    override fun getItemCount() = items.size

    fun move(from: Int, to: Int) {
        if (from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun remove(position: Int) {
        items.removeAt(position)
        notifyItemRemoved(position)
        onChanged()
        onRemoved()
    }
}
