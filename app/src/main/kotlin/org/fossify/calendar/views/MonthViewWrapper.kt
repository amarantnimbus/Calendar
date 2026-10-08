package org.fossify.calendar.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import org.fossify.calendar.R
import org.fossify.calendar.databinding.MonthViewBackgroundBinding
import org.fossify.calendar.databinding.MonthViewBinding
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.getWeekNumberWidth
import org.fossify.calendar.extensions.launchNewEventIntent
import org.fossify.calendar.extensions.launchNewTaskIntent
import org.fossify.calendar.helpers.COLUMN_COUNT
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.helpers.ROW_COUNT
import org.fossify.calendar.helpers.Shift
import org.fossify.calendar.helpers.ShiftHelper
import org.fossify.calendar.helpers.TYPE_EVENT
import org.fossify.calendar.helpers.TYPE_SHIFT
import org.fossify.calendar.helpers.TYPE_TASK
import org.fossify.calendar.models.DayMonthly
import org.fossify.commons.compose.extensions.getActivity
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.models.RadioItem

// used in the Monthly view fragment, 1 view per screen
class MonthViewWrapper(
    context: Context,
    attrs: AttributeSet,
    defStyle: Int
) : FrameLayout(context, attrs, defStyle) {
    companion object {
        private const val BACK_TO_CYCLE = 100
    }

    private var dayWidth = 0f
    private var dayHeight = 0f
    private var weekDaysLetterHeight = 0
    private var horizontalOffset = 0
    private var wereViewsAdded = false
    private var expandedIndex = -1
    private var isMonthDayView = true
    private var days = ArrayList<DayMonthly>()
    private var inflater: LayoutInflater
    private var binding: MonthViewBinding
    private var dayClickCallback: ((day: DayMonthly) -> Unit)? = null

    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    init {
        val normalTextSize =
            resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_text_size).toFloat()
        weekDaysLetterHeight = 2 * normalTextSize.toInt()

        inflater = LayoutInflater.from(context)
        binding = MonthViewBinding.inflate(inflater, this, true)
        setupHorizontalOffset()

        onGlobalLayout {
            if (!wereViewsAdded && days.isNotEmpty()) {
                measureSizes()
                addClickableBackgrounds()
                binding.monthView.updateDays(days, isMonthDayView)
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        measureSizes()
        var index = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is MonthView) {
                //ignore the MonthView layout
                continue
            }

            // the month view knows where every day cell is, also when a day is open under its week
            val cell = binding.monthView.getCellRect(index % COLUMN_COUNT, index / COLUMN_COUNT)
            index++

            child.measure(
                MeasureSpec.makeMeasureSpec(cell.width().toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cell.height().toInt(), MeasureSpec.EXACTLY)
            )

            child.layout(
                cell.left.toInt(),
                cell.top.toInt(),
                cell.left.toInt() + child.measuredWidth,
                cell.top.toInt() + child.measuredHeight
            )
        }
    }

    fun updateDays(
        newDays: ArrayList<DayMonthly>,
        addEvents: Boolean,
        callback: ((DayMonthly) -> Unit)? = null
    ) {
        setupHorizontalOffset()
        measureSizes()
        dayClickCallback = callback
        days = newDays
        if (dayWidth != 0f && dayHeight != 0f) {
            addClickableBackgrounds()
        }

        isMonthDayView = !addEvents
        binding.monthView.updateDays(days, isMonthDayView)
    }

    private fun setupHorizontalOffset() {
        horizontalOffset = context.getWeekNumberWidth()
    }

    private fun measureSizes() {
        dayWidth = (width - horizontalOffset) / COLUMN_COUNT.toFloat()
        dayHeight = (height - weekDaysLetterHeight) / ROW_COUNT.toFloat()
    }

    private fun addClickableBackgrounds() {
        removeAllViews()
        binding = MonthViewBinding.inflate(inflater, this, true)
        wereViewsAdded = true
        if (expandedIndex >= 0) {
            // keep the open day when the events are reloaded
            binding.monthView.expandDay(expandedIndex % COLUMN_COUNT, expandedIndex / COLUMN_COUNT)
        }
        days.forEachIndexed { index, day ->
            addViewBackground(index % COLUMN_COUNT, index / COLUMN_COUNT, day)
        }

    }

    private fun addViewBackground(viewX: Int, viewY: Int, day: DayMonthly) {

        MonthViewBackgroundBinding.inflate(inflater, this, false).root.apply {
            if (isMonthDayView) {
                background = null
            }
            //Accessible label composed by day and month
            contentDescription = "${day.value} ${
                Formatter.getMonthName(
                    context,
                    Formatter.getDateTimeFromCode(day.code).monthOfYear
                )
            }"

            setOnClickListener {
                if (!isMonthDayView && context.config.expandDayInMonthView) {
                    // first tap opens the day under its week, a second tap opens the full day
                    if (binding.monthView.isDaySelected(viewX, viewY)) {
                        dayClickCallback?.invoke(day)
                    } else {
                        expandedIndex = viewY * COLUMN_COUNT + viewX
                        binding.monthView.expandDay(viewX, viewY)
                        requestLayout()
                    }
                    return@setOnClickListener
                }

                dayClickCallback?.invoke(day)

                if (isMonthDayView) {
                    binding.monthView.updateCurrentlySelectedDay(viewX, viewY)
                }
            }

            setOnLongClickListener {
                val items = arrayListOf(RadioItem(TYPE_EVENT, context.getString(R.string.event)))
                if (context.config.allowCreatingTasks) {
                    items.add(RadioItem(TYPE_TASK, context.getString(R.string.task)))
                }

                if (context.config.shiftsEnabled) {
                    items.add(RadioItem(TYPE_SHIFT, context.getString(R.string.change_shift)))
                }

                if (items.size == 1) {
                    context.launchNewEventIntent(day.code)
                } else {
                    RadioGroupDialog(context.getActivity(), items) {
                        when (it) {
                            TYPE_EVENT -> context.launchNewEventIntent(day.code)
                            TYPE_TASK -> context.launchNewTaskIntent(day.code)
                            TYPE_SHIFT -> showShiftDialog(day)
                            else -> Unit
                        }
                    }
                }
                true
            }

            addView(this)
        }
    }

    // lets the user change the shift of one day, or go back to what the cycle says
    private fun showShiftDialog(day: DayMonthly) {
        val items = ArrayList<RadioItem>()
        Shift.values().forEach { shift ->
            items.add(RadioItem(shift.ordinal, getShiftName(shift)))
        }
        items.add(RadioItem(BACK_TO_CYCLE, context.getString(R.string.shift_back_to_cycle)))

        RadioGroupDialog(context.getActivity(), items) {
            val shift = Shift.values().getOrNull(it as Int)
            ShiftHelper.setOverride(context.config, day.code, shift)
            binding.monthView.invalidate()
        }
    }

    private fun getShiftName(shift: Shift): String {
        val nameId = when (shift) {
            Shift.DAY -> R.string.shift_day
            Shift.NIGHT -> R.string.shift_night
            Shift.OFF_DUTY -> R.string.shift_off_duty
            Shift.FREE -> R.string.shift_free
        }

        return context.getString(nameId)
    }

    fun togglePrintMode() {
        binding.monthView.togglePrintMode()
    }
}
