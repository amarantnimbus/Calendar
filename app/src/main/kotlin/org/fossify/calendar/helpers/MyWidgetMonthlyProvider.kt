package org.fossify.calendar.helpers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.fossify.calendar.views.MonthView
import org.fossify.calendar.R
import org.fossify.calendar.activities.SplashActivity
import org.fossify.calendar.extensions.*
import org.fossify.calendar.interfaces.MonthlyCalendar
import org.fossify.calendar.models.DayMonthly
import org.fossify.calendar.models.Event
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.joda.time.DateTime
import org.joda.time.DateTimeConstants

class MyWidgetMonthlyProvider : AppWidgetProvider() {
    private val PREV = "prev"
    private val NEXT = "next"
    private val GO_TO_TODAY = "go_to_today"
    private val NEW_EVENT = "new_event"

    companion object {
        private var targetDate = DateTime.now().withDayOfMonth(1)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        performUpdate(context)
    }

    private fun performUpdate(context: Context) {
        MonthlyCalendarImpl(monthlyCalendar, context).getMonth(targetDate)
    }

    private fun getComponentName(context: Context) = ComponentName(context, MyWidgetMonthlyProvider::class.java)

    private fun setupIntent(context: Context, views: RemoteViews, action: String, id: Int) {
        Intent(context, MyWidgetMonthlyProvider::class.java).apply {
            this.action = action
            val pendingIntent = PendingIntent.getBroadcast(context, 0, this, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(id, pendingIntent)
        }
    }

    private fun setupAppOpenIntent(context: Context, views: RemoteViews, id: Int, dayCode: String) {
        (context.getLaunchIntent() ?: Intent(context, SplashActivity::class.java)).apply {
            putExtra(DAY_CODE, dayCode)
            putExtra(VIEW_TO_OPEN, MONTHLY_VIEW)
            val pendingIntent = PendingIntent.getActivity(context, Integer.parseInt(dayCode.substring(0, 6)), this, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(id, pendingIntent)
        }
    }

    private fun setupDayOpenIntent(context: Context, views: RemoteViews, id: Int, dayCode: String) {
        (context.getLaunchIntent() ?: Intent(context, SplashActivity::class.java)).apply {
            putExtra(DAY_CODE, dayCode)
            putExtra(VIEW_TO_OPEN, DAILY_VIEW)
            val pendingIntent = PendingIntent.getActivity(context, Integer.parseInt(dayCode), this, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(id, pendingIntent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PREV -> getPrevMonth(context)
            NEXT -> getNextMonth(context)
            GO_TO_TODAY -> goToToday(context)
            NEW_EVENT -> context.launchNewEventOrTaskActivity()
            else -> super.onReceive(context, intent)
        }
    }

    private fun getPrevMonth(context: Context) {
        targetDate = targetDate!!.minusMonths(1)
        MonthlyCalendarImpl(monthlyCalendar, context).getMonth(targetDate!!)
    }

    private fun getNextMonth(context: Context) {
        targetDate = targetDate!!.plusMonths(1)
        MonthlyCalendarImpl(monthlyCalendar, context).getMonth(targetDate!!)
    }

    private fun goToToday(context: Context) {
        targetDate = DateTime.now().withDayOfMonth(1)
        MonthlyCalendarImpl(monthlyCalendar, context).getMonth(targetDate!!)
    }

    // number of week rows the month needs: 4, 5 or 6
    private fun weeksOf(days: List<DayMonthly>): Int {
        val last = days.indexOfLast { it.isThisMonth }
        return ((last / 7) + 1).coerceIn(4, 6)
    }

    // size of the area under the title row, in pixels
    private fun gridSize(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val options: Bundle = appWidgetManager.getAppWidgetOptions(widgetId)
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val widthDp = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250
        )
        val heightDp = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250
        )
        // the title is drawn with the font size of Settings (+3 sp), so its height changes with it
        val paint = Paint().apply {
            textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, context.getWidgetFontSize() + 3f, context.resources.displayMetrics
            )
        }
        val titleHeight = (paint.fontMetrics.bottom - paint.fontMetrics.top) +
            2 * context.resources.getDimension(org.fossify.commons.R.dimen.medium_margin)
        val width = (widthDp * density).toInt().coerceAtLeast(200)
        val height = (heightDp * density - titleHeight).toInt().coerceAtLeast(200)
        context.config.widgetDebug = "giny: ${widthDp}x${heightDp} dp (${landscape.let { if (it) "horitzontal" else "vertical" }}), " +
            "títol ${titleHeight.toInt()} px, dibuix ${width}x$height px, densitat $density"
        return Pair(width, height)
    }

    private val iconCache = HashMap<String, android.graphics.Bitmap>()

    private fun iconBitmap(context: Context, resId: Int, color: Int, sizePx: Int): android.graphics.Bitmap {
        val key = "$resId:$color:$sizePx"
        return iconCache.getOrPut(key) {
            val drawable = context.resources.getColoredDrawableWithColor(resId, color).mutate()
            val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(android.graphics.Canvas(bmp))
            bmp
        }
    }

    private fun updateGrid(context: Context, views: RemoteViews, appWidgetManager: AppWidgetManager, widgetId: Int, days: ArrayList<DayMonthly>) {
        val weeks = weeksOf(days)
        val (width, height) = gridSize(context, appWidgetManager, widgetId)

        val info = HashMap<Long, Pair<String, Int>>()
        try {
            context.calendarsDB.getCalendars().forEach { cal ->
                val id = cal.id ?: return@forEach
                info[id] = Pair(cal.caldavDisplayName.ifEmpty { cal.title }, cal.color)
            }
        } catch (ignored: Exception) {
        }

        val data = MonthView(context, null).widgetData(
            weeks, context.config.widgetTextColor, context.config.widgetBgColor, info, days
        )

        val res = context.resources
        val dm = res.displayMetrics
        val packageName = context.packageName
        val dp = dm.density

        // sizes follow the font size chosen in Settings; they are real sp sizes, so nothing is stretched
        val numberSp = context.getWidgetFontSize() * 0.4f
        val pillSp = context.getWidgetFontSize() * 0.33f
        val numberPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, numberSp, dm)
        val pillPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, pillSp, dm)
        val iconPx = (numberPx * 0.95f).toInt().coerceAtLeast(8)

        val showLetters = !context.config.highlightWeekends
        val rowUnits = weeks + if (showLetters) 0.5f else 0f
        val cellH = height / rowUnits
        val cellW = width / (7f + if (data.showWeekNumbers) 0.5f else 0f)
        val bandPx = numberPx * 1.25f
        val pillRowPx = pillPx * 1.25f + 0.5f * dp
        // an estimate (fonts differ a little between phones): it errs on the safe side
        val room = cellH * 0.93f - bandPx - 2 * dp
        val fitPills = Math.floor((room / pillRowPx).toDouble()).toInt().coerceAtLeast(0)

        views.setViewVisibility(R.id.band_spacer, if (showLetters) View.VISIBLE else View.GONE)
        if (showLetters) {
            val letters = RemoteViews(packageName, R.layout.widget_letters)
            letters.setViewVisibility(R.id.letters_offset, if (data.showWeekNumbers) View.VISIBLE else View.GONE)
            data.letters.forEachIndexed { i, (text, color) ->
                val id = res.getIdentifier("letter_$i", "id", packageName)
                letters.setTextViewText(id, text)
                letters.setTextColor(id, color)
                letters.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, pillSp)
            }
            views.removeAllViews(R.id.band_spacer)
            views.addView(R.id.band_spacer, letters)
        }

        for (week in 0..5) {
            val rowId = res.getIdentifier("week_row_$week", "id", packageName)
            views.setViewVisibility(rowId, if (week < weeks) View.VISIBLE else View.GONE)
            val weekNumId = res.getIdentifier("week_num_$week", "id", packageName)
            views.setViewVisibility(weekNumId, if (data.showWeekNumbers) View.VISIBLE else View.GONE)
            views.removeAllViews(weekNumId)
            if (data.showWeekNumbers && week < data.weekNumbers.size) {
                val num = RemoteViews(packageName, R.layout.widget_week_num)
                num.setTextViewText(R.id.week_num_text, data.weekNumbers[week].first)
                num.setTextColor(R.id.week_num_text, data.weekNumbers[week].second)
                num.setTextViewTextSize(R.id.week_num_text, android.util.TypedValue.COMPLEX_UNIT_SP, pillSp)
                views.addView(weekNumId, num)
            }
        }

        val fullCell = context.config.shiftFullCell
        for (i in 0 until 42) {
            val id = res.getIdentifier("day_$i", "id", packageName)
            val dayCode = days.getOrNull(i)?.code ?: continue
            setupDayOpenIntent(context, views, id, dayCode)
            views.removeAllViews(id)
            val day = data.days.getOrNull(i) ?: continue

            val cell = RemoteViews(packageName, R.layout.widget_day_cell)
            val number = android.text.SpannableString(day.number)
            if (day.bold) {
                number.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, number.length, 0)
            }
            cell.setTextViewText(R.id.cell_number, number)
            cell.setTextColor(R.id.cell_number, day.numberColor)
            cell.setTextViewTextSize(R.id.cell_number, android.util.TypedValue.COMPLEX_UNIT_SP, numberSp)

            if (day.bandColor != null) {
                cell.setInt(if (fullCell) R.id.cell_root else R.id.cell_band, "setBackgroundColor", day.bandColor)
            }
            if (data.showGrid) {
                cell.setViewVisibility(R.id.cell_grid_bottom, View.VISIBLE)
                cell.setInt(R.id.cell_grid_bottom, "setBackgroundColor", data.gridColor)
            }
            cell.setViewVisibility(R.id.cell_today_line, if (day.isToday) View.VISIBLE else View.INVISIBLE)

            // only the icons that fit next to the number
            val numberWidth = numberPx * 0.62f * day.number.length
            val maxIcons = Math.floor(((cellW - 4 * dp - numberWidth) / (iconPx + dp)).toDouble()).toInt().coerceAtLeast(0)
            val iconIds = intArrayOf(R.id.cell_icon_a, R.id.cell_icon_b, R.id.cell_icon_c)
            day.icons.take(maxIcons).forEachIndexed { n, icon ->
                cell.setViewVisibility(iconIds[n], View.VISIBLE)
                cell.setImageViewBitmap(iconIds[n], iconBitmap(context, icon.resId, icon.color, iconPx))
            }

            val tooMany = day.pills.size > fitPills
            val shown = if (tooMany) (fitPills - 1).coerceAtLeast(0) else day.pills.size
            day.pills.take(shown).forEach { pill ->
                val v = RemoteViews(packageName, R.layout.widget_pill)
                val sb = android.text.SpannableStringBuilder()
                if (pill.isTask) sb.append(if (pill.strike) "\u2611 " else "\u2610 ")
                if (pill.initial != null) {
                    val start = sb.length
                    sb.append(pill.initial).append(' ')
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, 0)
                }
                val titleStart = sb.length
                sb.append(pill.title)
                if (pill.strike) {
                    sb.setSpan(android.text.style.StrikethroughSpan(), titleStart, sb.length, 0)
                }
                v.setTextViewText(R.id.pill_text, sb)
                v.setTextColor(R.id.pill_text, pill.textColor)
                v.setTextViewTextSize(R.id.pill_text, android.util.TypedValue.COMPLEX_UNIT_SP, pillSp)
                v.setInt(R.id.pill_bg, "setColorFilter", pill.fillRgb or 0xFF000000.toInt())
                v.setInt(R.id.pill_bg, "setImageAlpha", pill.alpha)
                cell.addView(R.id.cell_pills, v)
            }
            if (tooMany) {
                val more = RemoteViews(packageName, R.layout.widget_pill_more)
                more.setTextColor(R.id.pill_more, context.config.widgetTextColor)
                more.setTextViewTextSize(R.id.pill_more, android.util.TypedValue.COMPLEX_UNIT_SP, pillSp)
                cell.addView(R.id.cell_pills, more)
            }

            views.addView(id, cell)
        }
    }

    private val monthlyCalendar = object : MonthlyCalendar {
        override fun updateMonthlyCalendar(context: Context, month: String, days: ArrayList<DayMonthly>, checkedEvents: Boolean, currTargetDate: DateTime) {
            val largerFontSize = context.getWidgetFontSize() + 3f
            val textColor = context.config.widgetTextColor
            val resources = context.resources

            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            appWidgetManager.getAppWidgetIds(getComponentName(context)).forEach {
                val views = RemoteViews(context.packageName, R.layout.fragment_month_widget)
                views.setText(R.id.top_value, month)

                views.applyColorFilter(R.id.widget_month_background, context.config.widgetBgColor)

                views.setTextColor(R.id.top_value, textColor)
                views.setTextSize(R.id.top_value, largerFontSize)

                var bmp = resources.getColoredBitmap(org.fossify.commons.R.drawable.ic_chevron_left_vector, textColor)
                views.setImageViewBitmap(R.id.top_left_arrow, bmp)

                bmp = resources.getColoredBitmap(org.fossify.commons.R.drawable.ic_chevron_right_vector, textColor)
                views.setImageViewBitmap(R.id.top_right_arrow, bmp)

                bmp = resources.getColoredBitmap(R.drawable.ic_today_vector, textColor)
                views.setImageViewBitmap(R.id.top_go_to_today, bmp)

                bmp = resources.getColoredBitmap(org.fossify.commons.R.drawable.ic_plus_vector, textColor)
                views.setImageViewBitmap(R.id.top_new_event, bmp)

                val shouldGoToTodayBeVisible = currTargetDate.withTime(0, 0, 0, 0) != DateTime.now().withDayOfMonth(1).withTime(0, 0, 0, 0)
                views.setVisibleIf(R.id.top_go_to_today, shouldGoToTodayBeVisible)

                updateGrid(context, views, appWidgetManager, it, days)
                setupIntent(context, views, PREV, R.id.top_left_arrow)
                setupIntent(context, views, NEXT, R.id.top_right_arrow)
                setupIntent(context, views, GO_TO_TODAY, R.id.top_go_to_today)
                setupIntent(context, views, NEW_EVENT, R.id.top_new_event)

                val monthCode = days.firstOrNull { it.code.substring(6) == "01" }?.code ?: Formatter.getTodayCode()
                setupAppOpenIntent(context, views, R.id.top_value, monthCode)

                try {
                    appWidgetManager.updateAppWidget(it, views)
                } catch (ignored: RuntimeException) {
                }
            }
        }
    }

    // redraw when the widget is resized
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        performUpdate(context)
    }
}
