package org.fossify.calendar.views

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout
import org.fossify.calendar.R
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor

/**
 * Top bar of the main screen: month text on the left, icons on the right (magnifier first).
 * Tapping the magnifier covers the bar with a search field.
 */
class CalendarTopBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppBarLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val toolbar = Toolbar(context)
    private val titleView = TextView(context)
    private val searchRow = LinearLayout(context)
    private val searchBack = ImageView(context)
    private val searchField = EditText(context)
    private val searchClear = ImageView(context)
    private val holder = FrameLayout(context)

    var isSearchOpen = false
        private set

    var onSearchTextChangedListener: ((String) -> Unit)? = null
    var onTitleClickListener: (() -> Unit)? = null
    var onNavigateBackClickListener: (() -> Unit)? = null
    private var forceArrow = false

    init {
        elevation = 0f
        stateListAnimator = null
        addView(holder, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        toolbar.setContentInsetsRelative(0, 0)
        val barHeight = (56 * density).toInt()
        holder.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))

        titleView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        toolbar.addView(titleView, Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        titleView.setOnClickListener { onTitleClickListener?.invoke() }
        searchRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isClickable = true
        }
        val pad = (14 * density).toInt()
        searchBack.apply {
            setImageResource(R.drawable.ic_arrow_back_bar)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { closeSearch() }
        }
        searchField.apply {
            background = null
            hint = context.getString(org.fossify.commons.R.string.search)
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchClear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                    if (isSearchOpen) onSearchTextChangedListener?.invoke(s?.toString().orEmpty())
                }
            })
        }
        searchClear.apply {
            setImageResource(R.drawable.ic_close_bar)
            setPadding(pad, pad, pad, pad)
            visibility = View.INVISIBLE
            setOnClickListener { searchField.setText("") }
        }
        val size = (48 * density).toInt()
        searchRow.addView(searchBack, LinearLayout.LayoutParams(size, size))
        searchRow.addView(searchField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        searchRow.addView(searchClear, LinearLayout.LayoutParams(size, size))
        holder.addView(searchRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            v.setPadding(0, top, 0, 0)
            insets
        }
        updateColors()
    }

    fun requireToolbar(): Toolbar = toolbar

    fun setTitle(text: String) {
        titleView.text = text
    }

    fun setupMenu() {}

    fun toggleHideOnScroll(hide: Boolean) {}

    fun updateHintText(text: String) {
        searchField.hint = text
    }

    fun getCurrentQuery(): String = searchField.text?.toString().orEmpty()

    fun updateColors() {
        val bg = context.getProperBackgroundColor()
        val fg = context.getProperTextColor()
        setBackgroundColor(bg)
        titleView.setTextColor(fg)
        searchRow.setBackgroundColor(bg)
        searchField.setTextColor(fg)
        searchField.setHintTextColor(Color.argb(140, Color.red(fg), Color.green(fg), Color.blue(fg)))
        searchBack.applyColorFilter(fg)
        searchClear.applyColorFilter(fg)
        toolbar.overflowIcon?.applyColorFilter(fg)
        toolbar.navigationIcon?.applyColorFilter(fg)
        for (i in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(i).icon?.let { icon ->
                // the Tailscale icon keeps its own colour
                if (toolbar.menu.getItem(i).itemId != R.id.tailscale) icon.mutate().applyColorFilter(fg)
            }
        }
    }

    fun toggleForceArrowBackIcon(force: Boolean) {
        forceArrow = force
        if (force) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back_bar)
            toolbar.navigationIcon?.applyColorFilter(context.getProperTextColor())
            toolbar.setNavigationOnClickListener { onNavigateBackClickListener?.invoke() }
        } else {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
        }
    }

    fun openSearch() {
        if (isSearchOpen) return
        isSearchOpen = true
        searchRow.visibility = View.VISIBLE
        searchField.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
    }

    fun closeSearch() {
        if (!isSearchOpen) return
        isSearchOpen = false
        searchField.setText("")
        searchRow.visibility = View.GONE
        searchField.clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
    }
}
