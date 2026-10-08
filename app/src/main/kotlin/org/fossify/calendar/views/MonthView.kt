package org.fossify.calendar.views

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import org.fossify.calendar.R
import org.fossify.calendar.extensions.*
import org.fossify.calendar.helpers.COLUMN_COUNT
import org.fossify.calendar.helpers.EVENT_ID
import org.fossify.calendar.helpers.EVENT_OCCURRENCE_TS
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.helpers.IS_TASK_COMPLETED
import org.fossify.calendar.helpers.getActivityToOpen
import org.fossify.calendar.helpers.ROW_COUNT
import org.fossify.calendar.helpers.ShiftHelper
import org.fossify.calendar.helpers.ShiftSchedule
import org.fossify.calendar.models.DayMonthly
import org.fossify.calendar.models.Event
import org.fossify.calendar.models.MonthViewEvent
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.HIGHER_ALPHA
import org.fossify.commons.helpers.LOWER_ALPHA
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.joda.time.DateTime
import org.joda.time.Days
import kotlin.math.max
import kotlin.math.min

// used in the Monthly view fragment, 1 view per screen
class MonthView(context: Context, attrs: AttributeSet, defStyle: Int) : View(context, attrs, defStyle) {
    companion object {
        private const val BG_CORNER_RADIUS = 3f
        private const val EVENT_DOT_COLUMN_COUNT = 3
        private const val EVENT_DOT_ROW_COUNT = 1
    }

    private var textPaint: Paint
    private var eventTitlePaint: TextPaint
    private var gridPaint: Paint
    private var circleStrokePaint: Paint
    private var plusTextPaint: Paint
    private var eventDotPaint: Paint
    private var config = context.config
    private var dayWidth = 0f
    private var dayHeight = 0f
    private var primaryColor = 0
    private var textColor = 0
    private var weekendsTextColor = 0
    private var weekDaysLetterHeight = 0
    private var eventTitleHeight = 0
    private var currDayOfWeek = 0
    private var smallPadding = 0
    private var maxEventsPerDay = 0
    private var horizontalOffset = 0
    private var showWeekNumbers = false
    private var dimPastEvents = true
    private var dimCompletedTasks = true
    private var highlightWeekends = false
    private var isPrintVersion = false
    private var isMonthDayView = false
    private var allEvents = ArrayList<MonthViewEvent>()
    private var bgRectF = RectF()
    private var dayTextRect = Rect()
    private var dayLetters = ArrayList<String>()
    private var days = ArrayList<DayMonthly>()
    private var dayVerticalOffsets = SparseIntArray()
    private var selectedDayCoords = Point(-1, -1)
    private var selectedDayIndex = -1
    private var panelHeight = 0f
    private var panelEvents = ArrayList<Event>()
    private var panelHiddenCount = 0
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val panelRect = RectF()
    private val panelFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairline = max(1f, 0.6f * context.resources.displayMetrics.density)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hairline
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFD32F2F.toInt()
    }
    private val shiftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    init {
        primaryColor = context.getProperPrimaryColor()
        textColor = context.getProperTextColor()
        weekendsTextColor = config.highlightWeekendsColor
        showWeekNumbers = config.showWeekNumbers
        dimPastEvents = config.dimPastEvents
        dimCompletedTasks = config.dimCompletedTasks
        highlightWeekends = config.highlightWeekends

        smallPadding = resources.displayMetrics.density.toInt()
        val normalTextSize = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_text_size)
        weekDaysLetterHeight = normalTextSize * 2

        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = normalTextSize.toFloat()
            textAlign = Paint.Align.CENTER
            typeface = FontHelper.getTypeface(context)
        }

        eventDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        plusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            alpha = 175
            textSize = normalTextSize.toFloat()
            textAlign = Paint.Align.CENTER
            typeface = FontHelper.getTypeface(context)
        }

        gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.adjustAlpha(LOWER_ALPHA)
        }

        circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.getDimension(R.dimen.circle_stroke_width)
            color = primaryColor
        }

        val smallerTextSize = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.smaller_text_size)
        eventTitleHeight = smallerTextSize
        eventTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = smallerTextSize.toFloat()
            textAlign = Paint.Align.LEFT
            typeface = FontHelper.getTypeface(context)
        }

        initWeekDayLetters()
        setupCurrentDayOfWeekIndex()
    }

    fun updateDays(newDays: ArrayList<DayMonthly>, isMonthDayView: Boolean) {
        this.isMonthDayView = isMonthDayView
        days = newDays
        showWeekNumbers = config.showWeekNumbers
        horizontalOffset = context.getWeekNumberWidth()
        initWeekDayLetters()
        setupCurrentDayOfWeekIndex()
        groupAllEvents()
        invalidate()
    }

    private fun groupAllEvents() {
        days.forEach { day ->
            val dayIndexOnMonthView = day.indexOnMonthView

            day.dayEvents.forEach { event ->
                // make sure we properly handle events lasting multiple days and repeating ones
                val validDayEvent = isDayValid(event, day.code)
                val lastEvent = allEvents.lastOrNull { it.id == event.id }
                val notYetAddedOrIsRepeatingEvent = lastEvent == null || lastEvent.endTS <= event.startTS

                // handle overlapping repeating events e.g. an event that lasts 3 days, but repeats every 2 days has a one day overlap
                val canOverlap = event.endTS - event.startTS > event.repeatInterval
                val shouldAddEvent = notYetAddedOrIsRepeatingEvent || canOverlap && (lastEvent.startTS < event.startTS)

                if (shouldAddEvent && !validDayEvent) {
                    val daysCnt = getEventLastingDaysCount(event)

                    val monthViewEvent = MonthViewEvent(
                        id = event.id!!,
                        title = event.title,
                        startTS = event.startTS,
                        endTS = event.endTS,
                        color = event.color,
                        startDayIndex = dayIndexOnMonthView,
                        daysCnt = daysCnt,
                        originalStartDayIndex = dayIndexOnMonthView,
                        isAllDay = event.getIsAllDay(),
                        isPastEvent = event.isPastEvent,
                        isTask = event.isTask(),
                        isTaskCompleted = event.isTaskCompleted(),
                        isAttendeeInviteDeclined = event.isAttendeeInviteDeclined(),
                        isEventCanceled = event.isEventCanceled()
                    )
                    allEvents.add(monthViewEvent)
                }
            }
        }

        allEvents = allEvents.asSequence().sortedWith(
            compareBy({ -it.daysCnt }, { !it.isAllDay }, { it.startTS }, { it.endTS }, { it.startDayIndex }, { it.title })
        ).toMutableList() as ArrayList<MonthViewEvent>
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dayVerticalOffsets.clear()
        measureDaySize(canvas)

        val schedule = ShiftHelper.load(config)
        drawShiftBackgrounds(canvas, schedule)

        if (config.showGrid && !isMonthDayView) {
            drawGrid(canvas)
        }

        addWeekDayLetters(canvas)
        if (showWeekNumbers && days.isNotEmpty()) {
            addWeekNumbers(canvas)
        }

        var curId = 0
        for (y in 0 until ROW_COUNT) {
            for (x in 0 until COLUMN_COUNT) {
                val day = days.getOrNull(curId)
                if (day != null) {
                    val dayNumber = day.value.toString()
                    val textPaint = getTextPaint(day)
                    textPaint.getTextBounds(dayNumber, 0, dayNumber.length, dayTextRect)
                    dayVerticalOffsets.put(day.indexOnMonthView, dayVerticalOffsets[day.indexOnMonthView] + weekDaysLetterHeight)
                    val verticalOffset = dayVerticalOffsets[day.indexOnMonthView]
                    val xPos = x * dayWidth + horizontalOffset
                    val yPos = rowOrigin(y) + verticalOffset
                    val textY = yPos + textPaint.textSize
                    val xPosCenter = xPos + dayWidth / 2

                    val isDaySelected = selectedDayCoords.x != -1 && x == selectedDayCoords.x && y == selectedDayCoords.y
                    val isExpandedDay = isExpandEnabled() && selectedDayIndex == curId
                    var textShiftY = 0f
                    var openCircleBottom = 0f
                    if (!isMonthDayView) {
                        val isTodayMarked = day.isToday && !isPrintVersion
                        val isOpenDay = isExpandedDay
                        if (isTodayMarked || isOpenDay) {
                            val ts = textPaint.textSize
                            val baseRadius = max(textPaint.measureText(dayNumber), ts * 0.75f) / 2
                            val centerY = textY - dayTextRect.height() / 2
                            if (isOpenDay) {
                                // open day: bigger circle, number and pills move down.
                                // Color of the shift, or red when the open day is today.
                                textShiftY = ts * 0.3f
                                val radius = baseRadius + ts * 0.37f
                                val shiftColor = schedule?.let { sch -> sch.shiftFor(day.code)?.let { sch.colorFor(it) } }
                                val fillColor = if (isTodayMarked) {
                                    0xFFD32F2F.toInt()
                                } else {
                                    (shiftColor ?: Color.WHITE) or 0xFF000000.toInt()
                                }
                                val isFreeDay = shiftColor == null && !isTodayMarked && schedule != null
                                shiftPaint.color = fillColor
                                canvas.drawCircle(xPosCenter, centerY + textShiftY, radius, shiftPaint)
                                markPaint.color = if (isFreeDay) Color.BLACK else outlineColor()
                                canvas.drawCircle(xPosCenter, centerY + textShiftY, radius, markPaint)
                                textPaint.color = if (isTodayMarked) Color.WHITE else fillColor.getContrastColor()
                                openCircleBottom = centerY + textShiftY + radius - yPos
                            } else {
                                // today, not open: small red circle adapted to one or two digits
                                canvas.drawCircle(xPosCenter, centerY, baseRadius + ts * 0.16f, todayPaint)
                                textPaint.color = Color.WHITE
                            }
                        }
                    } else if (isDaySelected) {
                        canvas.drawCircle(
                            xPosCenter,
                            textY - dayTextRect.height() / 2,
                            textPaint.textSize * 0.8f,
                            circleStrokePaint
                        )
                        if (day.isToday) {
                            textPaint.color = textColor
                        }
                    } else if (day.isToday && !isPrintVersion) {
                        canvas.drawCircle(
                            xPosCenter,
                            textY - dayTextRect.height() / 2,
                            textPaint.textSize * 0.8f,
                            getCirclePaint(day)
                        )
                    }

                    // mark days with a dot for each event
                    if (isMonthDayView && !isDaySelected && !day.isToday && day.dayEvents.isNotEmpty()) {
                        val height = dayTextRect.height() * 1.25f
                        val eventCount = day.dayEvents.size
                        val dotRadius = textPaint.textSize * 0.2f
                        val stepSize = dotRadius * 2.5f
                        val columnCount = EVENT_DOT_COLUMN_COUNT

                        val dayEventsSorted = day.dayEvents
                            .asSequence()
                            .sortedWith(
                                comparator = compareBy({ it.startTS }, { it.endTS }, { it.title })
                            )
                            .distinctBy { it.color }

                        var xDot: Float
                        var yDot = yPos + height + textPaint.textSize / 2
                        var indexInRow: Int

                        val dotCount = dayEventsSorted.count()
                        for ((index, event) in dayEventsSorted.withIndex()) {
                            indexInRow = index % columnCount
                            xDot = xPosCenter + stepSize * (indexInRow - (min(dotCount, columnCount)) / 2)
                            if (dotCount % 2 == 0) { // center even number of dots
                                xDot += stepSize / 2
                            }

                            if (index > 0 && indexInRow == 0) { // next row of dots
                                yDot += stepSize
                            }

                            // Always show a + sign if the event count exceeds columnCount.
                            if (eventCount - 1 != index && index >= columnCount * EVENT_DOT_ROW_COUNT - 1) {
                                plusTextPaint.textSize = stepSize * 1.5f
                                canvas.drawText("+", xDot, yDot + dotRadius * 1.2f, plusTextPaint)
                                break
                            } else {
                                val paint = eventDotPaint.apply { color = event.color }
                                canvas.drawCircle(xDot, yDot, dotRadius, paint)
                            }
                        }
                    }

                    canvas.drawText(dayNumber, xPosCenter, textY + textShiftY, textPaint)
                    // the open day leaves a larger gap so the event pills do not cover its big circle
                    val gapFactor = 2f
                    val nextOffset = if (isMonthDayView) {
                        verticalOffset + textPaint.textSize * gapFactor
                    } else {
                        // event pills start below the colored day band
                        verticalOffset + max(bandHeight(), openCircleBottom) + eventTitleHeight + smallPadding * 2
                    }
                    dayVerticalOffsets.put(day.indexOnMonthView, nextOffset.toInt())
                }
                curId++
            }
        }

        if (!isMonthDayView) {
            for (event in allEvents) {
                drawEvent(event, canvas)
            }
        }

        drawPanel(canvas)
    }

    private val OPEN_FREE_DAY_COLOR = 0xFF55555A.toInt()

    // height of the colored band behind the day number
    private fun bandHeight() = textPaint.textSize * 1.4f

    // paints the whole cell of each day with the color of its shift, at 50% transparency
    private fun drawShiftBackgrounds(canvas: Canvas, schedule: ShiftSchedule?) {
        if (schedule == null) {
            return
        }

        var curId = 0
        for (y in 0 until ROW_COUNT) {
            for (x in 0 until COLUMN_COUNT) {
                val day = days.getOrNull(curId)
                if (day != null) {
                    val shift = schedule.shiftFor(day.code)
                    val shiftColor = if (shift != null) schedule.colorFor(shift) else null
                    val isFreeOpenDay = shiftColor == null && !day.isToday && isExpandEnabled() && selectedDayIndex == curId
                    val left = x * dayWidth + horizontalOffset
                    val top = rowOrigin(y) + weekDaysLetterHeight
                    val bottom = if (config.shiftFullCell) top + dayHeight else top + bandHeight()
                    if (shiftColor != null) {
                        shiftPaint.color = ShiftHelper.withHalfTransparency(shiftColor)
                        canvas.drawRect(left, top, left + dayWidth, bottom, shiftPaint)
                    } else if (isFreeOpenDay) {
                        // open day without shift: dark grey band
                        shiftPaint.color = OPEN_FREE_DAY_COLOR
                        canvas.drawRect(left, top, left + dayWidth, bottom, shiftPaint)
                    }

                    // days with an event of the shared "Junts" calendar get a fine outline
                    val juntsId = config.juntsCalendarId
                    if (juntsId != -1L && day.dayEvents.any { it.calendarId == juntsId }) {
                        markPaint.color = outlineColor()
                        canvas.drawRect(left + hairline, top + hairline, left + dayWidth - hairline, bottom - hairline, markPaint)
                    }
                }
                curId++
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        // vertical lines
        for (i in 0 until COLUMN_COUNT) {
            var lineX = i * dayWidth
            if (showWeekNumbers) {
                lineX += horizontalOffset
            }
            canvas.drawLine(lineX, 0f, lineX, canvas.height.toFloat(), gridPaint)
        }

        // horizontal lines
        canvas.drawLine(0f, 0f, canvas.width.toFloat(), 0f, gridPaint)
        for (i in 0 until ROW_COUNT) {
            val lineY = rowOrigin(i) + weekDaysLetterHeight
            canvas.drawLine(0f, lineY, canvas.width.toFloat(), lineY, gridPaint)
        }
        canvas.drawLine(0f, canvas.height.toFloat(), canvas.width.toFloat(), canvas.height.toFloat(), gridPaint)
    }

    private fun addWeekDayLetters(canvas: Canvas) {
        for (i in 0 until COLUMN_COUNT) {
            val xPos = horizontalOffset + (i + 1) * dayWidth - dayWidth / 2
            var weekDayLetterPaint = textPaint
            if (i == currDayOfWeek && !isPrintVersion) {
                weekDayLetterPaint = getColoredPaint(primaryColor)
            } else if (highlightWeekends && context.isWeekendIndex(i)) {
                weekDayLetterPaint = getColoredPaint(weekendsTextColor)
            }
            canvas.drawText(dayLetters[i], xPos, weekDaysLetterHeight * 0.7f, weekDayLetterPaint)
        }
    }

    private fun addWeekNumbers(canvas: Canvas) {
        val weekNumberPaint = Paint(textPaint)

        for (i in 0 until ROW_COUNT) {
            val weekDays = days.subList(i * 7, i * 7 + 7)
            weekNumberPaint.color = if (weekDays.any { it.isToday && !isPrintVersion }) primaryColor else textColor

            // fourth day of the week determines the week of the year number
            val weekOfYear = days.getOrNull(i * 7 + 3)?.weekOfYear ?: 1
            val id = "$weekOfYear:"
            val horizontalMarginFactor = 0.5f
            val xPos = horizontalOffset * horizontalMarginFactor
            val yPos = rowOrigin(i) + weekDaysLetterHeight
            canvas.drawText(id, xPos, yPos + textPaint.textSize, weekNumberPaint)
        }
    }

    private fun measureDaySize(canvas: Canvas) {
        measureDaySize(canvas.width, canvas.height)
    }

    private fun measureDaySize(viewWidth: Int, viewHeight: Int) {
        updatePanel(viewHeight)
        dayWidth = (viewWidth - horizontalOffset) / 7f
        dayHeight = (viewHeight - weekDaysLetterHeight - panelHeight) / ROW_COUNT.toFloat()
        val availableHeightForEvents = dayHeight.toInt() - weekDaysLetterHeight
        maxEventsPerDay = availableHeightForEvents / eventTitleHeight
    }

    private fun isExpandEnabled() = config.expandDayInMonthView && !isMonthDayView && !isPrintVersion

    private fun cardHeight() = eventTitleHeight * 2.6f
    private fun cardGap() = smallPadding * 12f

    // decides which events go in the panel under the selected week and how tall the panel is
    private fun updatePanel(viewHeight: Int) {
        panelEvents = ArrayList()
        panelHiddenCount = 0
        panelHeight = 0f
        val day = days.getOrNull(selectedDayIndex)
        if (!isExpandEnabled() || day == null || day.dayEvents.isEmpty()) {
            return
        }

        val sorted = day.dayEvents.sortedWith(compareBy({ !it.getIsAllDay() }, { it.startTS }, { it.endTS }, { it.title }))
        val maxPanel = (viewHeight - weekDaysLetterHeight) * 0.45f
        val step = cardHeight() + cardGap()
        val maxCards = max(1, ((maxPanel - cardGap()) / step).toInt())
        val visible = if (sorted.size <= maxCards) sorted.size else max(1, maxCards - 1)
        panelEvents.addAll(sorted.take(visible))
        panelHiddenCount = sorted.size - visible
        val rows = visible + if (panelHiddenCount > 0) 1 else 0
        panelHeight = cardGap() + rows * step - (if (panelHiddenCount > 0) cardHeight() - eventTitleHeight else 0f)
    }

    private fun expandedRow(): Int = if (panelHeight > 0f) selectedDayIndex / 7 else -1

    // top of a week row (without the header with the week day letters)
    private fun rowOrigin(row: Int): Float {
        val extra = if (expandedRow() in 0 until row) panelHeight else 0f
        return row * dayHeight + extra
    }

    /** Area of one day cell, used by the wrapper to place the clickable views. */
    fun getCellRect(x: Int, y: Int): RectF {
        measureDaySize(width, height)
        val left = x * dayWidth + horizontalOffset
        val top = rowOrigin(y) + weekDaysLetterHeight
        return RectF(left, top, left + dayWidth, top + dayHeight)
    }

    // black frames in light themes, white ones in dark themes
    private fun outlineColor() = if (Color.luminance(textColor) > 0.5f) Color.WHITE else Color.BLACK

    fun isDaySelected(x: Int, y: Int) = selectedDayIndex == y * 7 + x

    fun expandDay(x: Int, y: Int) {
        selectedDayIndex = y * 7 + x
        invalidate()
    }

    private fun openEvent(event: Event) {
        Intent(context, getActivityToOpen(event.isTask())).apply {
            putExtra(EVENT_ID, event.id)
            putExtra(EVENT_OCCURRENCE_TS, event.startTS)
            putExtra(IS_TASK_COMPLETED, event.isTaskCompleted())
            context.startActivity(this)
        }
    }

    // the panel starts right under the week row of the selected day
    private fun panelTop() = (expandedRow() + 1) * dayHeight + weekDaysLetterHeight

    private fun cardTop(index: Int) = panelTop() + cardGap() + index * (cardHeight() + cardGap())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (panelHeight <= 0f || selectedDayIndex < 0) {
            return super.onTouchEvent(event)
        }

        val inPanel = event.y >= panelTop() && event.y <= panelTop() + panelHeight
        if (!inPanel) {
            return super.onTouchEvent(event)
        }

        if (event.action == MotionEvent.ACTION_UP) {
            for (i in panelEvents.indices) {
                val top = cardTop(i)
                if (event.y >= top && event.y <= top + cardHeight() + cardGap() / 2) {
                    openEvent(panelEvents[i])
                    break
                }
            }
        }
        return true
    }

    private fun drawPanel(canvas: Canvas) {
        if (panelHeight <= 0f) {
            return
        }

        val left = horizontalOffset + smallPadding * 4f
        val right = width - smallPadding * 4f
        val cardH = cardHeight()
        val titlePaint = Paint(eventTitlePaint).apply { color = textColor }
        val timePaint = Paint(eventTitlePaint).apply { color = textColor.adjustAlpha(MEDIUM_ALPHA) }

        panelEvents.forEachIndexed { index, ev ->
            val top = cardTop(index)
            panelRect.set(left, top, right, top + cardH)
            // very thin gray frame around the card, no fill
            val frameWidth = hairline
            panelFramePaint.color = outlineColor()
            panelFramePaint.strokeWidth = frameWidth
            panelRect.set(left + frameWidth / 2, top + frameWidth / 2, right - frameWidth / 2, top + cardH - frameWidth / 2)
            canvas.drawRoundRect(panelRect, BG_CORNER_RADIUS, BG_CORNER_RADIUS, panelFramePaint)

            // colored bar on the left
            panelPaint.color = ev.color
            panelRect.set(left, top, left + smallPadding * 7.5f, top + cardH)
            canvas.drawRoundRect(panelRect, BG_CORNER_RADIUS, BG_CORNER_RADIUS, panelPaint)

            val textLeft = left + smallPadding * 14f
            var textRight = right - smallPadding * 8f
            val centerY = top + cardH / 2

            if (ev.isTask()) {
                val iconSize = eventTitleHeight
                val iconColor = if (ev.isTaskCompleted()) ev.color else textColor.adjustAlpha(MEDIUM_ALPHA)
                val icon = resources.getColoredDrawableWithColor(R.drawable.ic_task_vector, iconColor).mutate()
                val iconLeft = (right - smallPadding * 8f - iconSize).toInt()
                icon.setBounds(iconLeft, (centerY - iconSize / 2).toInt(), iconLeft + iconSize, (centerY + iconSize / 2).toInt())
                icon.draw(canvas)
                textRight = iconLeft - smallPadding * 4f
            }

            val timeText = if (ev.getIsAllDay()) "" else Formatter.getTimeFromTS(context, ev.startTS)
            if (timeText.isNotEmpty()) {
                canvas.drawText(timeText, textLeft, centerY - eventTitleHeight * 0.2f, timePaint)
            }

            val titleY = if (timeText.isNotEmpty()) centerY + eventTitleHeight * 0.95f else centerY + eventTitleHeight * 0.35f
            val title = TextUtils.ellipsize(ev.title, eventTitlePaint, textRight - textLeft, TextUtils.TruncateAt.END)
            titlePaint.isStrikeThruText = ev.isTask() && ev.isTaskCompleted()
            canvas.drawText(title, 0, title.length, textLeft, titleY, titlePaint)
        }

        if (panelHiddenCount > 0) {
            val top = cardTop(panelEvents.size)
            val morePaint = Paint(timePaint).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("+$panelHiddenCount", (left + right) / 2, top + eventTitleHeight, morePaint)
        }
    }

    private fun drawEvent(event: MonthViewEvent, canvas: Canvas) {
        var verticalOffset = 0
        for (i in 0 until min(event.daysCnt, 7 - event.startDayIndex % 7)) {
            verticalOffset = max(verticalOffset, dayVerticalOffsets[event.startDayIndex + i])
        }
        val xPos = event.startDayIndex % 7 * dayWidth + horizontalOffset
        val yPos = rowOrigin(event.startDayIndex / 7)
        val xPosCenter = xPos + dayWidth / 2

        if (verticalOffset - eventTitleHeight * 2 > dayHeight) {
            val paint = getTextPaint(days[event.startDayIndex])
            paint.color = textColor
            canvas.drawText("...", xPosCenter, yPos + verticalOffset - eventTitleHeight / 2, paint)
            return
        }

        // event background rectangle
        val backgroundY = yPos + verticalOffset
        val bgLeft = xPos + smallPadding
        val bgTop = backgroundY + smallPadding - eventTitleHeight
        var bgRight = xPos - smallPadding + dayWidth * event.daysCnt
        val bgBottom = backgroundY + smallPadding * 2
        if (bgRight > canvas.width.toFloat()) {
            bgRight = canvas.width.toFloat() - smallPadding
            val newStartDayIndex = (event.startDayIndex / 7 + 1) * 7
            if (newStartDayIndex < 42) {
                val newEvent = event.copy(startDayIndex = newStartDayIndex, daysCnt = event.daysCnt - (newStartDayIndex - event.startDayIndex))
                drawEvent(newEvent, canvas)
            }
        }

        bgRectF.set(bgLeft, bgTop, bgRight, bgBottom)
        canvas.drawRoundRect(bgRectF, BG_CORNER_RADIUS, BG_CORNER_RADIUS, getEventBackgroundColor(event))

        val specificEventTitlePaint = getEventTitlePaint(event)
        var taskIconWidth = 0
        if (event.isTask) {
            val taskIcon = resources.getColoredDrawableWithColor(R.drawable.ic_task_vector, specificEventTitlePaint.color).mutate()
            val taskIconY = yPos.toInt() + verticalOffset - eventTitleHeight + smallPadding * 2
            taskIcon.setBounds(xPos.toInt() + smallPadding * 2, taskIconY, xPos.toInt() + eventTitleHeight + smallPadding * 2, taskIconY + eventTitleHeight)
            taskIcon.draw(canvas)
            taskIconWidth += eventTitleHeight + smallPadding
        }

        drawEventTitle(event, canvas, xPos + taskIconWidth, yPos + verticalOffset, bgRight - bgLeft - smallPadding - taskIconWidth, specificEventTitlePaint)

        for (i in 0 until min(event.daysCnt, 7 - event.startDayIndex % 7)) {
            dayVerticalOffsets.put(event.startDayIndex + i, verticalOffset + eventTitleHeight + smallPadding * 2)
        }
    }

    private fun drawEventTitle(event: MonthViewEvent, canvas: Canvas, x: Float, y: Float, availableWidth: Float, paint: Paint) {
        val ellipsized = TextUtils.ellipsize(event.title, eventTitlePaint, availableWidth - smallPadding, TextUtils.TruncateAt.END)
        canvas.drawText(event.title, 0, ellipsized.length, x + smallPadding * 2, y, paint)
    }

    private fun getTextPaint(startDay: DayMonthly): Paint {
        var paintColor = when {
            !isPrintVersion && startDay.isToday -> primaryColor.getContrastColor()
            highlightWeekends && startDay.isWeekend -> weekendsTextColor
            else -> textColor
        }

        if (!startDay.isThisMonth) {
            paintColor = paintColor.adjustAlpha(MEDIUM_ALPHA)
        }

        return getColoredPaint(paintColor)
    }

    private fun getColoredPaint(color: Int): Paint {
        val curPaint = Paint(textPaint)
        curPaint.color = color
        return curPaint
    }

    private fun getEventBackgroundColor(event: MonthViewEvent): Paint {
        var paintColor = event.color

        val adjustAlpha = when {
            event.isTask -> dimCompletedTasks && event.isTaskCompleted
            else -> dimPastEvents && event.isPastEvent && !isPrintVersion
        }

        if (adjustAlpha) {
            paintColor = paintColor.adjustAlpha(MEDIUM_ALPHA)
        }

        return getColoredPaint(paintColor)
    }

    private fun getEventTitlePaint(event: MonthViewEvent): Paint {
        var paintColor = event.color.getContrastColor()
        val adjustAlpha = when {
            event.isTask -> dimCompletedTasks && event.isTaskCompleted
            else -> dimPastEvents && event.isPastEvent && !isPrintVersion
        }

        if (adjustAlpha) {
            paintColor = paintColor.adjustAlpha(HIGHER_ALPHA)
        }

        val curPaint = Paint(eventTitlePaint)
        curPaint.color = paintColor
        curPaint.isStrikeThruText = event.shouldStrikeThrough()
        return curPaint
    }

    private fun getCirclePaint(day: DayMonthly): Paint {
        val curPaint = Paint(textPaint)
        var paintColor = primaryColor
        if (!day.isThisMonth) {
            paintColor = paintColor.adjustAlpha(MEDIUM_ALPHA)
        }
        curPaint.color = paintColor
        return curPaint
    }

    private fun initWeekDayLetters() {
        dayLetters = context.withFirstDayOfWeekToFront(
            context.resources.getStringArray(org.fossify.commons.R.array.week_days_short).toList()
        )
    }

    private fun setupCurrentDayOfWeekIndex() {
        if (days.firstOrNull { it.isToday && it.isThisMonth } == null) {
            currDayOfWeek = -1
            return
        }

        currDayOfWeek = context.getProperDayIndexInWeek(DateTime())
    }

    // take into account cases when an event starts on the previous screen, subtract those days
    private fun getEventLastingDaysCount(event: Event): Int {
        val startDateTime = Formatter.getDateTimeFromTS(event.startTS)
        val endDateTime = Formatter.getDateTimeFromTS(event.endTS)
        val code = days.first().code
        val screenStartDateTime = Formatter.getDateTimeFromCode(code).toLocalDate()
        var eventStartDateTime = Formatter.getDateTimeFromTS(startDateTime.seconds()).toLocalDate()
        val eventEndDateTime = Formatter.getDateTimeFromTS(endDateTime.seconds()).toLocalDate()
        val diff = Days.daysBetween(screenStartDateTime, eventStartDateTime).days
        if (diff < 0) {
            eventStartDateTime = screenStartDateTime
        }

        val isMidnight = Formatter.getDateTimeFromTS(endDateTime.seconds()) == Formatter.getDateTimeFromTS(endDateTime.seconds()).withTimeAtStartOfDay()
        val numDays = Days.daysBetween(eventStartDateTime, eventEndDateTime).days
        val daysCnt = if (numDays == 1 && isMidnight) 0 else numDays
        return daysCnt + 1
    }

    private fun isDayValid(event: Event, code: String): Boolean {
        val date = Formatter.getDateTimeFromCode(code)
        return event.startTS != event.endTS && Formatter.getDateTimeFromTS(event.endTS) == Formatter.getDateTimeFromTS(date.seconds()).withTimeAtStartOfDay()
    }

    fun togglePrintMode() {
        isPrintVersion = !isPrintVersion
        textColor = if (isPrintVersion) {
            resources.getColor(org.fossify.commons.R.color.theme_light_text_color, null)
        } else {
            context.getProperTextColor()
        }

        textPaint.color = textColor
        gridPaint.color = textColor.adjustAlpha(LOWER_ALPHA)
        invalidate()
        initWeekDayLetters()
    }

    fun updateCurrentlySelectedDay(x: Int, y: Int) {
        selectedDayCoords = Point(x, y)
        invalidate()
    }
}
