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
        val paint = Paint().apply { textSize = context.resources.getDimension(R.dimen.month_text_size) }
        val titleHeight = (paint.fontMetrics.bottom - paint.fontMetrics.top) +
            2 * context.resources.getDimension(org.fossify.commons.R.dimen.medium_margin)
        val width = (widthDp * density).toInt().coerceAtLeast(200)
        val height = (heightDp * density - titleHeight).toInt().coerceAtLeast(200)
        return Pair(width, height)
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

        val monthView = MonthView(context, null)
        val bitmap = monthView.renderForWidget(
            weeks, context.config.widgetTextColor, context.config.widgetBgColor, info, days, width, height
        )
        views.setImageViewBitmap(R.id.widget_month_image, bitmap)

        views.setViewVisibility(R.id.band_spacer, if (context.config.highlightWeekends) View.GONE else View.VISIBLE)
        val res = context.resources
        val packageName = context.packageName
        for (week in 0..5) {
            val rowId = res.getIdentifier("week_row_$week", "id", packageName)
            views.setViewVisibility(rowId, if (week < weeks) View.VISIBLE else View.GONE)
            val weekNumId = res.getIdentifier("week_num_$week", "id", packageName)
            views.setViewVisibility(weekNumId, if (context.config.showWeekNumbers) View.VISIBLE else View.GONE)
        }
        for (i in days.indices) {
            val id = res.getIdentifier("day_$i", "id", packageName)
            setupDayOpenIntent(context, views, id, days[i].code)
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
