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
import androidx.core.graphics.ColorUtils
import org.fossify.calendar.helpers.COLUMN_COUNT
import org.fossify.calendar.helpers.DayIcons
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
import org.fossify.commons.helpers.ensureBackgroundThread
import org.joda.time.DateTime
import org.joda.time.Days
import kotlin.math.max
import kotlin.math.min

// used in the Monthly view fragment, 1 view per screen
class MonthView(context: Context, attrs: AttributeSet?, defStyle: Int) : View(context, attrs, defStyle) {
    companion object {
        private const val BG_CORNER_RADIUS = 3f
        private const val EVENT_DOT_COLUMN_COUNT = 3
        private const val EVENT_DOT_ROW_COUNT = 1
        private const val TEXT_SCALE = 0.8f
        private const val VISIBLE_ROWS = 5
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
    private var headerHeightFull = 0
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
    private var forcedRows = 0
    private var bgOverride: Int? = null
    private var allEvents = ArrayList<MonthViewEvent>()
    private var bgRectF = RectF()
    private var dayTextRect = Rect()
    private var dayLetters = ArrayList<String>()
    private var days = ArrayList<DayMonthly>()
    private var dayVerticalOffsets = SparseIntArray()
    private var selectedDayCoords = Point(-1, -1)
    private var selectedDayIndex = -1
    private var rowShift = 0
    private var wantShift = false
    private var userMovedRows = false
    private var panelHeight = 0f
    private var panelEvents = ArrayList<Event>()
    private var panelHiddenCount = 0
    private var calendarInfo = HashMap<Long, Pair<String, Int>>()
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
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = max(1.5f, 1.2f * context.resources.displayMetrics.density)
    }

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

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
        headerHeightFull = normalTextSize * 2
        weekDaysLetterHeight = if (highlightWeekends) 0 else headerHeightFull

        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = normalTextSize * TEXT_SCALE
            textAlign = Paint.Align.CENTER
            typeface = FontHelper.getTypeface(context)
        }

        eventDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        plusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            alpha = 175
            textSize = normalTextSize * TEXT_SCALE
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
        eventTitleHeight = (smallerTextSize * TEXT_SCALE).toInt()
        eventTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = smallerTextSize * TEXT_SCALE
            textAlign = Paint.Align.LEFT
            typeface = FontHelper.getTypeface(context)
        }

        initWeekDayLetters()
        setupCurrentDayOfWeekIndex()
        loadCalendarInfo()
    }

    private fun loadCalendarInfo() {
        ensureBackgroundThread {
            val info = HashMap<Long, Pair<String, Int>>()
            context.calendarsDB.getCalendars().forEach { cal ->
                val id = cal.id ?: return@forEach
                val name = cal.caldavDisplayName.ifEmpty { cal.title }
                info[id] = Pair(name, cal.color)
            }
            post {
                calendarInfo = info
                invalidate()
            }
        }
    }

    fun updateDays(newDays: ArrayList<DayMonthly>, isMonthDayView: Boolean) {
        this.isMonthDayView = isMonthDayView
        days = newDays
        if (!userMovedRows && canShiftRows()) {
            // when today is in the sixth week, start with that week in view
            val todayIndex = days.indexOfFirst { it.isToday && it.isThisMonth }
            wantShift = todayIndex >= VISIBLE_ROWS * 7
        }
        rowShift = if (wantShift && canShiftRows()) 1 else 0
        showWeekNumbers = config.showWeekNumbers
        highlightWeekends = config.highlightWeekends
        weekDaysLetterHeight = if (highlightWeekends) 0 else headerHeightFull
        horizontalOffset = context.getWeekNumberWidth()
        initWeekDayLetters()
        setupCurrentDayOfWeekIndex()
        groupAllEvents()
        invalidate()
    }

    private fun groupAllEvents() {
        allEvents = ArrayList()
        val lastMonthIndex = days.indexOfLast { it.isThisMonth }
        days.forEach { day ->
            // days of other months stay empty in the month view
            if (!isMonthDayView && !day.isThisMonth) {
                return@forEach
            }

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
                    var daysCnt = getEventLastingDaysCount(event)
                    if (!isMonthDayView) {
                        // the event may have started on a hidden day of the previous month
                        val screenStart = Formatter.getDateTimeFromCode(days.first().code).toLocalDate()
                        val eventStart = Formatter.getDateTimeFromTS(event.startTS).toLocalDate()
                        val startIndex = max(0, Days.daysBetween(screenStart, eventStart).days)
                        daysCnt = max(1, daysCnt - max(0, dayIndexOnMonthView - startIndex))
                        if (lastMonthIndex >= 0) {
                            daysCnt = min(daysCnt, lastMonthIndex - dayIndexOnMonthView + 1)
                        }
                    }

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
                        isTask = event.isTask() || event.isTaskMarked(),
                        isTaskCompleted = event.isTaskCompleted() || event.isTaskMarkedDone(),
                        isAttendeeInviteDeclined = event.isAttendeeInviteDeclined(),
                        isEventCanceled = event.isEventCanceled(),
                        calendarId = event.calendarId
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

        if (weekDaysLetterHeight > 0) {
            addWeekDayLetters(canvas)
        }
        if (showWeekNumbers && days.isNotEmpty()) {
            addWeekNumbers(canvas)
        }

        var curId = 0
        for (y in 0 until ROW_COUNT) {
            for (x in 0 until COLUMN_COUNT) {
                val day = days.getOrNull(curId)
                if (day != null && (isMonthDayView || (isRowVisible(y) && day.isThisMonth))) {
                    val dayNumber = day.value.toString()
                    val textPaint = getTextPaint(day)
                    if (day.isWeekend && !isPrintVersion) {
                        textPaint.typeface = Typeface.create(textPaint.typeface, Typeface.BOLD)
                    }
                    textPaint.getTextBounds(dayNumber, 0, dayNumber.length, dayTextRect)
                    dayVerticalOffsets.put(day.indexOnMonthView, dayVerticalOffsets[day.indexOnMonthView] + weekDaysLetterHeight)
                    val verticalOffset = dayVerticalOffsets[day.indexOnMonthView]
                    val xPos = x * dayWidth + horizontalOffset
                    val yPos = rowOrigin(y) + verticalOffset
                    val textY = yPos + textPaint.textSize
                    val xPosCenter = xPos + dayWidth / 2

                    val isDaySelected = selectedDayCoords.x != -1 && x == selectedDayCoords.x && y == selectedDayCoords.y
                    val isExpandedDay = isExpandEnabled() && selectedDayIndex == curId
                    var numberCenterX = xPosCenter
                    var numberBaseline = textY
                    if (!isMonthDayView) {
                        val ts = textPaint.textSize
                        val band = bandHeight()
                        val shiftColor = schedule?.let { sch -> sch.shiftFor(day.code)?.let { sch.colorFor(it) } }
                        val sidePad = smallPadding * 3f

                        // the number sits at the far left of the cell
                        textPaint.textAlign = Paint.Align.LEFT
                        numberCenterX = xPos + sidePad
                        val centerY = yPos + (band - lineThickness()) / 2
                        numberBaseline = centerY + dayTextRect.height() / 2f
                        textPaint.color = when {
                            shiftColor != null -> {
                                val base = if (highlightWeekends && day.isWeekend) weekendsTextColor else shiftColor.getContrastColor()
                                if (day.isThisMonth) base else base.adjustAlpha(MEDIUM_ALPHA)
                            }
                            else -> textPaint.color
                        }
                        if (day.isToday && !isPrintVersion && shiftColor == null) {
                            textPaint.color = if (highlightWeekends && day.isWeekend) weekendsTextColor else textColor
                        }

                        // icons at the far right: together, own calendar, other calendar
                        val slots = dayMarksFor(day)
                        val iconSize = ts * 0.9f
                        val slotGap = ts * 0.12f
                        val iconColor = shiftColor?.getContrastColor() ?: textColor
                        var iconRight = xPos + dayWidth - sidePad
                        // only the icons that fit next to the number are drawn, so they never overlap it
                        val present = listOfNotNull(
                            slots.other?.let { Pair(0, it) },
                            slots.own?.let { Pair(1, it) },
                            if (slots.junts) Pair(2, -1) else null
                        )
                        val room = dayWidth - sidePad * 2 - textPaint.measureText(dayNumber) - ts * 0.3f
                        val maxIcons = max(0, (room / (iconSize + slotGap)).toInt())
                        present.take(maxIcons).forEach { (slot, res) ->
                            val iconLeft = iconRight - iconSize
                            val color = if (slot == 0) iconColor.adjustAlpha(0.55f) else iconColor
                            val resId = if (res == -1) R.drawable.ic_junts_people else res
                            val icon = resources.getColoredDrawableWithColor(resId, color).mutate()
                            val iconTop = (centerY - iconSize / 2f).toInt()
                            icon.setBounds(iconLeft.toInt(), iconTop, (iconLeft + iconSize).toInt(), iconTop + iconSize.toInt())
                            icon.draw(canvas)
                            iconRight = iconLeft - slotGap
                        }

                        // line under the number: red today, black the open day
                        val lineColor = when {
                            day.isToday && !isPrintVersion -> 0xFFD32F2F.toInt()
                            isExpandedDay -> outlineColor()
                            else -> null
                        }
                        if (lineColor != null) {
                            shiftPaint.color = lineColor
                            canvas.drawRect(xPos, yPos + band - lineThickness(), xPos + dayWidth, yPos + band, shiftPaint)
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

                    if (isMonthDayView) {
                        canvas.drawText(dayNumber, xPosCenter, textY, textPaint)
                    } else {
                        canvas.drawText(dayNumber, numberCenterX, numberBaseline, textPaint)
                    }

                    val nextOffset = if (isMonthDayView) {
                        verticalOffset + textPaint.textSize * 2f
                    } else {
                        // event pills start below the day band
                        verticalOffset + bandHeight() + eventTitleHeight + smallPadding * 2
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
            drawRowHint(canvas)
        }

        drawPanel(canvas)
    }

    // ---- helpers for the day band and the day icons

    private fun hasJuntsEvent(day: DayMonthly): Boolean {
        val juntsId = config.juntsCalendarId
        return juntsId != -1L && day.dayEvents.any { it.calendarId == juntsId }
    }

    // the colored band behind the day number is as tall as a circle that fits two digits
    private fun circleDiameter(): Float {
        val ts = textPaint.textSize
        return max(textPaint.measureText("88"), ts * 0.75f) + ts * 0.32f
    }

    private fun lineThickness() = max(2f, 2f * resources.displayMetrics.density)

    private fun darken(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = max(0f, hsl[2] - 0.12f)
        return ColorUtils.HSLToColor(hsl) or 0xFF000000.toInt()
    }

    private fun desaturate(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1] * 0.72f
        return ColorUtils.HSLToColor(hsl) or 0xFF000000.toInt()
    }

    // a solid, lighter version of the color for events that are already over (no transparency)
    // black or white, whichever reads better on this (already blended) color
    private fun readableOn(color: Int): Int {
        val c = color or 0xFF000000.toInt()
        return if (ColorUtils.calculateContrast(Color.WHITE, c) >= ColorUtils.calculateContrast(Color.BLACK, c)) Color.WHITE else Color.BLACK
    }

    private fun solidDim(color: Int): Int {
        return ColorUtils.blendARGB(color or 0xFF000000.toInt(), bgColor() or 0xFF000000.toInt(), 0.45f)
    }

    // how much of the event color shows (1 = solid), from the transparency setting
    private fun bgColor() = bgOverride ?: config.backgroundColor

    private fun opacity() = (100 - config.eventTransparency.coerceIn(0, 100)) / 100f

    // the color the eye sees for a fill painted with the current opacity
    private fun shownColor(fill: Int): Int {
        return ColorUtils.blendARGB(bgColor() or 0xFF000000.toInt(), fill or 0xFF000000.toInt(), opacity())
    }

    // the cards of the open day have their own transparency
    private fun panelOpacity() = (100 - config.panelTransparency.coerceIn(0, 100)) / 100f

    private fun withPanelOpacity(fill: Int): Int {
        return (fill and 0x00FFFFFF) or ((panelOpacity() * 255).toInt() shl 24)
    }

    private fun withOpacity(fill: Int): Int {
        return (fill and 0x00FFFFFF) or ((opacity() * 255).toInt() shl 24)
    }

    private fun pillBaseColor(event: MonthViewEvent): Int {
        val dim = when {
            event.isTask -> dimCompletedTasks && event.isTaskCompleted
            else -> dimPastEvents && event.isPastEvent && !isPrintVersion
        }
        return if (dim) solidDim(event.color) else event.color or 0xFF000000.toInt()
    }

    /** What is drawn at the right of the day number: together, own calendar icon, other calendar icon. */
    private class DayMarks(val junts: Boolean, val own: Int?, val other: Int?)

    private fun dayMarksFor(day: DayMonthly): DayMarks {
        val withIcon = day.dayEvents
            .sortedWith(compareBy({ it.startTS }, { it.endTS }))
            .mapNotNull { event -> DayIcons.iconOf(event.description)?.let { icon -> Pair(event, icon) } }

        val ownId = config.ownCalendarId
        val own: DayIcons.Icon?
        val other: DayIcons.Icon?
        if (ownId != -1L) {
            own = withIcon.firstOrNull { it.first.calendarId == ownId }?.second
            other = withIcon.firstOrNull { it.first.calendarId != ownId }?.second
        } else {
            own = withIcon.firstOrNull()?.second
            other = null
        }

        return DayMarks(hasJuntsEvent(day), own?.drawable, other?.drawable)
    }

    // ---- rows: five rows are always shown, in a six week month the sixth one is reached by dragging up

    private fun canShiftRows() = forcedRows == 0 && !isMonthDayView && days.size > VISIBLE_ROWS * 7 && days[VISIBLE_ROWS * 7].isThisMonth

    private fun visibleRows() = if (forcedRows > 0) forcedRows else VISIBLE_ROWS

    private fun isRowVisible(row: Int) = isMonthDayView || (row >= rowShift && row < rowShift + visibleRows())

    fun hasHiddenRow() = canShiftRows()

    fun isShifted() = rowShift > 0

    fun setRowShift(shift: Boolean) {
        userMovedRows = true
        wantShift = shift
        val newShift = if (shift && canShiftRows()) 1 else 0
        if (newShift != rowShift) {
            rowShift = newShift
            // an open day that is no longer visible is closed
            if (selectedDayIndex >= 0 && !isRowVisible(selectedDayIndex / 7)) {
                selectedDayIndex = -1
            }
            invalidate()
        }
    }

    private fun drawRowHint(canvas: Canvas) {
        if (!canShiftRows()) {
            return
        }

        val barWidth = dayWidth * 0.7f
        val barHeight = max(3f, 3f * resources.displayMetrics.density)
        val cx = horizontalOffset + (width - horizontalOffset) / 2f
        val y = if (rowShift == 0) height - barHeight * 1.6f else weekDaysLetterHeight + barHeight * 0.6f
        shiftPaint.color = textColor.adjustAlpha(0.35f)
        canvas.drawRoundRect(cx - barWidth / 2, y, cx + barWidth / 2, y + barHeight, barHeight / 2, barHeight / 2, shiftPaint)
    }

    // height of the colored band behind the day number
    private fun bandHeight() = circleDiameter()

    // paints the band (or the whole cell) of each day with the solid color of its shift
    private fun drawShiftBackgrounds(canvas: Canvas, schedule: ShiftSchedule?) {
        if (schedule == null) {
            return
        }

        var curId = 0
        for (y in 0 until ROW_COUNT) {
            for (x in 0 until COLUMN_COUNT) {
                val day = days.getOrNull(curId)
                if (day != null && isRowVisible(y) && (isMonthDayView || day.isThisMonth)) {
                    val shift = schedule.shiftFor(day.code)
                    val shiftColor = if (shift != null) schedule.colorFor(shift) else null
                    val left = x * dayWidth + horizontalOffset
                    val top = rowOrigin(y) + weekDaysLetterHeight
                    val bottom = if (config.shiftFullCell) top + dayHeight else top + bandHeight()
                    if (shiftColor != null) {
                        shiftPaint.color = shiftColor or 0xFF000000.toInt()
                        canvas.drawRect(left, top, left + dayWidth, bottom, shiftPaint)
                        if (config.shiftFullCell && bgOverride == null && !isPrintVersion) {
                            // thin black frame around the fully painted cell (not in the widget)
                            val half = framePaint.strokeWidth / 2f
                            canvas.drawRect(left + half, top + half, left + dayWidth - half, bottom - half, framePaint)
                        }
                    }
                }
                curId++
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        // only horizontal lines: they divide the weeks
        canvas.drawLine(0f, 0f, canvas.width.toFloat(), 0f, gridPaint)
        for (i in 0 until ROW_COUNT) {
            if (!isRowVisible(i)) {
                continue
            }
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
            if (!isRowVisible(i)) {
                continue
            }
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
        if (forcedRows > 0) {
            // widget: the week number column is half a cell and the letters band is half a row
            horizontalOffset = if (showWeekNumbers) (viewWidth / 15f).toInt() else 0
            weekDaysLetterHeight = if (highlightWeekends) 0 else (viewHeight / (forcedRows + 0.5f) * 0.5f).toInt()
        }
        updatePanel(viewHeight)
        dayWidth = (viewWidth - horizontalOffset) / 7f
        val rows = if (isMonthDayView) ROW_COUNT else visibleRows()
        dayHeight = (viewHeight - weekDaysLetterHeight - panelHeight) / rows.toFloat()
        val availableHeightForEvents = dayHeight.toInt() - weekDaysLetterHeight
        maxEventsPerDay = availableHeightForEvents / eventTitleHeight
    }

    private fun isExpandEnabled() = config.expandDayInMonthView && !isMonthDayView && !isPrintVersion && forcedRows == 0

    private fun panelTextSize() = eventTitlePaint.textSize * 1.2f
    private fun cardHeight() = panelTextSize() * 2.3f

    // the space between the cards is the same as the one at the sides
    private fun cardGap() = smallPadding * 4f

    // decides which events go in the panel under the selected week and how tall the panel is
    private fun updatePanel(viewHeight: Int) {
        panelEvents = ArrayList()
        panelHiddenCount = 0
        panelHeight = 0f
        val day = days.getOrNull(selectedDayIndex)
        if (!isExpandEnabled() || day == null || day.dayEvents.isEmpty() || !isRowVisible(selectedDayIndex / 7)) {
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

    private fun firstShownRow() = if (isMonthDayView) 0 else rowShift

    // top of a week row (without the header with the week day letters)
    private fun rowOrigin(row: Int): Float {
        val extra = if (expandedRow() in firstShownRow() until row) panelHeight else 0f
        return (row - firstShownRow()) * dayHeight + extra
    }

    /** Area of one day cell, used by the wrapper to place the clickable views. */
    fun getCellRect(x: Int, y: Int): RectF {
        measureDaySize(width, height)
        val day = days.getOrNull(y * 7 + x)
        if (!isMonthDayView && (!isRowVisible(y) || day == null || !day.isThisMonth)) {
            // hidden rows and days of other months cannot be tapped
            return RectF(0f, 0f, 0f, 0f)
        }
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
    private fun panelTop() = (expandedRow() - firstShownRow() + 1) * dayHeight + weekDaysLetterHeight

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

    // first non-empty line of the comment, without the hidden markers and the format marks
    private fun firstCommentLine(description: String): String {
        val visible = org.fossify.calendar.helpers.FormatMarks.plain(
            org.fossify.calendar.helpers.DayIcons.visibleText(description)
        )
        return visible.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    }

    private fun drawPanel(canvas: Canvas) {
        if (panelHeight <= 0f) {
            return
        }

        val left = horizontalOffset + smallPadding * 4f
        val right = width - smallPadding * 4f
        val cardH = cardHeight()
        val size = panelTextSize()
        val timePaint = Paint(eventTitlePaint).apply { textSize = size }
        val titlePaint = Paint(eventTitlePaint).apply {
            textSize = size
            typeface = Typeface.create(eventTitlePaint.typeface, Typeface.BOLD)
        }

        panelEvents.forEachIndexed { index, ev ->
            val top = cardTop(index)
            // solid card in the color of the event, a bit less saturated, no outline
            val taskCard = ev.isTask() || ev.isTaskMarked()
            val doneCard = ev.isTaskCompleted() || ev.isTaskMarkedDone()
            // finished tasks get the same lighter color as in the month cell
            val baseColor = if (taskCard && doneCard && dimCompletedTasks) solidDim(ev.color) else ev.color
            val fill = desaturate(baseColor)
            panelPaint.color = withPanelOpacity(fill)
            panelRect.set(left, top, right, top + cardH)
            canvas.drawRoundRect(panelRect, BG_CORNER_RADIUS * 2, BG_CORNER_RADIUS * 2, panelPaint)

            val textColorOnCard = ColorUtils.blendARGB(bgColor() or 0xFF000000.toInt(), fill or 0xFF000000.toInt(), panelOpacity()).let { readableOn(it) }
            val isTaskCard = ev.isTask() || ev.isTaskMarked()
            val isDoneCard = ev.isTaskCompleted() || ev.isTaskMarkedDone()
            timePaint.color = textColorOnCard
            titlePaint.color = textColorOnCard

            val textLeft = left + smallPadding * 6f
            val textRight = right - smallPadding * 6f
            val centerY = top + cardH / 2
            val iconSize = eventTitleHeight
            val slotWidth = iconSize + smallPadding * 2f

            // the task symbol goes first; events keep the same empty slot so everything lines up
            if (isTaskCard) {
                val iconColor = if (isDoneCard) textColorOnCard else textColorOnCard.adjustAlpha(MEDIUM_ALPHA)
                val icon = resources.getColoredDrawableWithColor(R.drawable.ic_task_vector, iconColor).mutate()
                val iconLeft = textLeft.toInt()
                icon.setBounds(iconLeft, (centerY - iconSize / 2).toInt(), iconLeft + iconSize, (centerY + iconSize / 2).toInt())
                icon.draw(canvas)
            }

            val timeText = if (ev.getIsAllDay()) "" else Formatter.getTimeFromTS(context, ev.startTS) + "  "
            val timeWidth = if (timeText.isEmpty()) 0f else timePaint.measureText(timeText)
            val textY = centerY + size * 0.35f

            // the initial of the calendar, in bold
            val initial = calendarInfo[ev.calendarId]?.first?.takeIf { it.isNotEmpty() }?.substring(0, 1)?.uppercase()
            var contentLeft = textLeft + slotWidth
            if (initial != null) {
                titlePaint.isStrikeThruText = false
                canvas.drawText(initial, contentLeft, textY, titlePaint)
                contentLeft += titlePaint.measureText("$initial ")
            }

            if (timeText.isNotEmpty()) {
                canvas.drawText(timeText, contentLeft, textY, timePaint)
            }

            val titleLeft = contentLeft + timeWidth
            val titleSpace = max(0f, textRight - titleLeft)
            val title = TextUtils.ellipsize(ev.title, TextPaint(titlePaint), titleSpace, TextUtils.TruncateAt.END)
            titlePaint.isStrikeThruText = isTaskCard && isDoneCard
            canvas.drawText(title, 0, title.length, titleLeft, textY, titlePaint)

            // first line of the comment, on the same line, only if the whole title fits
            val titleFits = title.length == ev.title.length
            if (titleFits) {
                val comment = firstCommentLine(ev.description)
                if (comment.isNotEmpty()) {
                    val used = titlePaint.measureText(ev.title)
                    val commentLeft = titleLeft + used
                    val commentSpace = textRight - commentLeft
                    if (commentSpace > 0f) {
                        val commentPaint = Paint(timePaint).apply { color = textColorOnCard.adjustAlpha(MEDIUM_ALPHA) }
                        val text = TextUtils.ellipsize(" \u00B7 $comment", TextPaint(commentPaint), commentSpace, TextUtils.TruncateAt.END)
                        canvas.drawText(text, 0, text.length, commentLeft, textY, commentPaint)
                    }
                }
            }
        }

        if (panelHiddenCount > 0) {
            val top = cardTop(panelEvents.size)
            val morePaint = Paint(eventTitlePaint).apply {
                textAlign = Paint.Align.CENTER
                color = textColor.adjustAlpha(MEDIUM_ALPHA)
            }
            canvas.drawText("+$panelHiddenCount", (left + right) / 2, top + eventTitleHeight, morePaint)
        }
    }

    private fun drawEvent(event: MonthViewEvent, canvas: Canvas) {
        if (!isRowVisible(event.startDayIndex / 7)) {
            // this part of the event is in a row that is not shown
            val nextIndex = (event.startDayIndex / 7 + 1) * 7
            if (nextIndex < 42 && event.daysCnt > nextIndex - event.startDayIndex) {
                drawEvent(event.copy(startDayIndex = nextIndex, daysCnt = event.daysCnt - (nextIndex - event.startDayIndex)), canvas)
            }
            return
        }

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
        if (bgRight > canvas.width.toFloat()) {
            bgRight = canvas.width.toFloat() - smallPadding
            val newStartDayIndex = (event.startDayIndex / 7 + 1) * 7
            if (newStartDayIndex < 42) {
                val newEvent = event.copy(startDayIndex = newStartDayIndex, daysCnt = event.daysCnt - (newStartDayIndex - event.startDayIndex))
                drawEvent(newEvent, canvas)
            }
        }

        val specificEventTitlePaint = getEventTitlePaint(event)
        val taskIconWidth = if (event.isTask) eventTitleHeight + smallPadding else 0
        val initialPaint = Paint(specificEventTitlePaint).apply {
            typeface = Typeface.create(specificEventTitlePaint.typeface, Typeface.BOLD)
        }
        val initial = calendarInitial(event)
        val initialWidth = if (initial != null) initialPaint.measureText("$initial ") else 0f
        val textWidth = bgRight - bgLeft - smallPadding * 2 - taskIconWidth
        val lineStep = eventTitleHeight + smallPadding
        val fullLines = if (config.monthFullText) wrapTitle(event.title, textWidth - initialWidth, textWidth) else null
        val extraHeight = if (fullLines == null) 0 else (fullLines.size - 1) * lineStep
        val bgBottom = backgroundY + smallPadding * 2 + extraHeight

        bgRectF.set(bgLeft, bgTop, bgRight, bgBottom)
        canvas.drawRoundRect(bgRectF, BG_CORNER_RADIUS, BG_CORNER_RADIUS, getEventBackgroundColor(event))

        if (event.isTask) {
            val taskIcon = resources.getColoredDrawableWithColor(R.drawable.ic_task_vector, specificEventTitlePaint.color).mutate()
            val taskIconY = yPos.toInt() + verticalOffset - eventTitleHeight + smallPadding * 2
            taskIcon.setBounds(xPos.toInt() + smallPadding * 2, taskIconY, xPos.toInt() + eventTitleHeight + smallPadding * 2, taskIconY + eventTitleHeight)
            taskIcon.draw(canvas)
        }

        // the initial of the calendar, in bold, black or white depending on the pill
        val textX = xPos + taskIconWidth + smallPadding * 2
        val baseY = yPos + verticalOffset
        if (initial != null) {
            canvas.drawText(initial, textX, baseY, initialPaint)
        }

        if (fullLines != null) {
            fullLines.forEachIndexed { index, line ->
                val lineX = if (index == 0) textX + initialWidth else textX
                canvas.drawText(line, lineX, baseY + index * lineStep, specificEventTitlePaint)
            }
        } else {
            val ellipsized = TextUtils.ellipsize(event.title, eventTitlePaint, max(0f, textWidth - initialWidth), TextUtils.TruncateAt.END)
            canvas.drawText(ellipsized, 0, ellipsized.length, textX + initialWidth, baseY, specificEventTitlePaint)
        }

        for (i in 0 until min(event.daysCnt, 7 - event.startDayIndex % 7)) {
            dayVerticalOffsets.put(event.startDayIndex + i, verticalOffset + eventTitleHeight + smallPadding * 2 + extraHeight)
        }
    }

    private fun calendarInitial(event: MonthViewEvent): String? {
        val name = calendarInfo[event.calendarId]?.first ?: return null
        return if (name.isEmpty()) null else name.substring(0, 1).uppercase()
    }

    // greedy word wrap of a title: the first line is shorter because the initial takes space
    private fun wrapTitle(title: String, firstWidth: Float, width: Float): List<String> {
        val lines = ArrayList<String>()
        var current = ""
        var limit = max(firstWidth, 1f)
        val fullWidth = max(width, 1f)
        title.split(" ").filter { it.isNotEmpty() }.forEach { word ->
            var rest = word
            val trial = if (current.isEmpty()) rest else "$current $rest"
            if (eventTitlePaint.measureText(trial) <= limit) {
                current = trial
                return@forEach
            }

            if (current.isNotEmpty()) {
                lines.add(current)
                current = ""
                limit = fullWidth
            }

            // a word longer than a whole line is cut
            while (eventTitlePaint.measureText(rest) > limit) {
                val count = max(1, eventTitlePaint.breakText(rest, true, limit, null))
                lines.add(rest.substring(0, count))
                rest = rest.substring(count)
                limit = fullWidth
            }
            current = rest
        }
        if (current.isNotEmpty() || lines.isEmpty()) {
            lines.add(current)
        }
        return lines
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
        return getColoredPaint(withOpacity(pillBaseColor(event)))
    }

    private fun getEventTitlePaint(event: MonthViewEvent): Paint {
        val paintColor = readableOn(shownColor(pillBaseColor(event)))

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

    /** Draws this month into a bitmap, used by the home screen widget. */
    fun renderForWidget(
        rowCount: Int,
        widgetTextColor: Int,
        widgetBgColor: Int,
        info: HashMap<Long, Pair<String, Int>>,
        newDays: ArrayList<DayMonthly>,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        forcedRows = rowCount
        bgOverride = widgetBgColor
        textColor = widgetTextColor
        weekendsTextColor = config.highlightWeekendsColor
        // the widget follows the font size chosen in Settings
        val medium = context.getWidgetMediumFontSize()
        val scale = (context.getWidgetFontSize() / medium).coerceIn(0.6f, 1.6f)
        textPaint.textSize = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_text_size) * TEXT_SCALE * scale
        plusTextPaint.textSize = textPaint.textSize
        eventTitlePaint.textSize = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.smaller_text_size) * TEXT_SCALE * scale
        eventTitleHeight = eventTitlePaint.textSize.toInt()
        textPaint.color = textColor
        plusTextPaint.color = textColor
        plusTextPaint.alpha = 175
        eventTitlePaint.color = textColor
        gridPaint.color = textColor.adjustAlpha(LOWER_ALPHA)
        calendarInfo = info
        updateDays(newDays, false)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        measure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY)
        )
        layout(0, 0, widthPx, heightPx)
        draw(Canvas(bitmap))
        return bitmap
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
