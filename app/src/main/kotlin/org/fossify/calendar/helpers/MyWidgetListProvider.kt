package org.fossify.calendar.helpers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import org.fossify.calendar.R
import org.fossify.calendar.activities.SplashActivity
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.getWidgetFontSize
import org.fossify.calendar.extensions.launchNewEventOrTaskActivity
import org.fossify.calendar.extensions.widgetsDB
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.models.Event
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import org.joda.time.DateTime

class MyWidgetListProvider : AppWidgetProvider() {
    private val NEW_EVENT = "new_event"
    private val LAUNCH_CAL = "launch_cal"
    private val GO_TO_TODAY = "go_to_today"

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        performUpdate(context)
    }

    private fun performUpdate(context: Context) {
        val fontSize = context.getWidgetFontSize()
        val textColor = context.config.widgetTextColor

        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        ensureBackgroundThread {
            appWidgetManager.getAppWidgetIds(getComponentName(context)).forEach {
                val widget = context.widgetsDB.getWidgetWithWidgetId(it)

                val headerVisibility = if (widget?.header == true) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

                val views = RemoteViews(context.packageName, R.layout.widget_event_list).apply {
                    applyColorFilter(R.id.widget_event_list_background, context.config.widgetBgColor)
                    setTextColor(R.id.widget_event_list_empty, textColor)
                    setTextSize(R.id.widget_event_list_empty, fontSize)

                    setTextColor(R.id.widget_event_list_today, textColor)
                    setTextSize(R.id.widget_event_list_today, fontSize)

                    setViewVisibility(R.id.widget_header_include, headerVisibility)
                }

                views.setImageViewBitmap(
                    R.id.widget_event_new_event, context.resources.getColoredBitmap(
                        resourceId = org.fossify.commons.R.drawable.ic_plus_vector,
                        newColor = textColor
                    )
                )
                setupIntent(context, views, NEW_EVENT, R.id.widget_event_new_event)
                setupIntent(context, views, LAUNCH_CAL, R.id.widget_event_list_today)

                views.setImageViewBitmap(R.id.widget_event_go_to_today, context.resources.getColoredBitmap(R.drawable.ic_today_vector, textColor))
                setupIntent(context, views, GO_TO_TODAY, R.id.widget_event_go_to_today)

                fillList(context, views, appWidgetManager, it, widget?.period ?: 0, widget?.header == true)

                appWidgetManager.updateAppWidget(it, views)
            }
        }
    }

    private class Card(val event: Event)
    private class DayHead(val code: String, val title: String, val isPast: Boolean)

    // black or white, whichever reads better on this colour
    private fun readableOn(color: Int): Int {
        val c = color or 0xFF000000.toInt()
        return if (androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE, c) >=
            androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.BLACK, c)
        ) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    }

    /**
     * Fills the list with plain views (no scrolling): every event is a card, line 1 = initial, time and title,
     * line 2 = the comment. Only what fits in the height of the widget is shown, then a "...".
     */
    private fun fillList(context: Context, views: RemoteViews, manager: AppWidgetManager, widgetId: Int, period: Int, header: Boolean) {
        val dm = context.resources.displayMetrics
        val dp = dm.density
        val options = manager.getAppWidgetOptions(widgetId)
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val heightDp = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250
        )
        val headerPx = if (header) 46 * dp else 0f
        val available = heightDp * dp - headerPx - 12 * dp

        val config = context.config
        val textColor = config.widgetTextColor
        val bgColor = config.widgetBgColor or 0xFF000000.toInt()
        val fs = context.getWidgetFontSize()
        val sp1 = fs * 0.85f
        val sp2 = fs * 0.7f
        val px1 = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp1, dm)
        val px2 = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp2, dm)
        val spDay = fs * 0.8f
        val pxDay = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, spDay, dm)

        val now = DateTime()
        val fromTS = now.seconds() - config.displayPastEvents * 60
        val toTS = when (period) {
            0 -> now.plusYears(1).seconds()
            EVENT_PERIOD_TODAY -> now.withTime(23, 59, 59, 999).seconds()
            else -> now.plusSeconds(period).seconds()
        }

        val names = HashMap<Long, String>()
        try {
            context.calendarsDB.getCalendars().forEach { cal ->
                val id = cal.id ?: return@forEach
                names[id] = cal.caldavDisplayName.ifEmpty { cal.title }
            }
        } catch (ignored: Exception) {
        }

        var events = ArrayList<Event>()
        context.eventsHelper.getEventsSync(fromTS, toTS, applyTypeFilter = true) { events = ArrayList(it) }
        val replaceDescription = config.replaceDescription
        val sorted = events.sortedWith(compareBy<Event> { e ->
            if (e.getIsAllDay()) Formatter.getDayStartTS(Formatter.getDayCodeFromTS(e.startTS)) - 1 else e.startTS
        }.thenBy { e ->
            if (e.getIsAllDay()) Formatter.getDayEndTS(Formatter.getDayCodeFromTS(e.endTS)) else e.endTS
        }.thenBy { e -> e.title }.thenBy { e -> if (replaceDescription) e.location else e.description })

        val items = ArrayList<Any>()
        var prevCode = ""
        val nowTs = getNowSeconds()
        val todayTitle = Formatter.getDayTitle(context, Formatter.getDayCodeFromTS(nowTs))
        sorted.forEach { e ->
            val code = Formatter.getDayCodeFromTS(e.startTS)
            if (code != prevCode) {
                val title = Formatter.getDateDayTitle(code)
                val isToday = Formatter.getDayTitle(context, code) == todayTitle
                items.add(DayHead(code, title, !isToday && e.startTS < nowTs))
                prevCode = code
            }
            items.add(Card(e))
        }

        views.setViewVisibility(R.id.widget_event_list_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
        views.removeAllViews(R.id.widget_event_list)

        val moreHeight = pxDay * 1.4f + 4 * dp
        var used = 0f
        var shownAll = true
        val opacity = (100 - config.eventTransparency.coerceIn(0, 100)) / 100f
        val pkg = context.packageName

        for ((index, item) in items.withIndex()) {
            val hasMore = index < items.size - 1
            when (item) {
                is DayHead -> {
                    val h = pxDay * 1.35f + 6 * dp
                    // a day title alone at the bottom is useless: it needs room for a card too
                    if (used + h + px1 * 1.3f + 8 * dp + (if (hasMore) moreHeight else 0f) > available) {
                        shownAll = false
                        break
                    }
                    val v = RemoteViews(pkg, R.layout.widget_list_day)
                    val col = if (config.dimPastEvents && item.isPast) textColor.adjustAlpha(0.5f) else textColor
                    v.setTextViewText(R.id.day_title, item.title)
                    v.setTextColor(R.id.day_title, col)
                    v.setTextViewTextSize(R.id.day_title, android.util.TypedValue.COMPLEX_UNIT_SP, spDay)
                    v.setInt(R.id.day_line, "setBackgroundColor", col.adjustAlpha(0.45f))
                    openDayIntent(context, v, R.id.day_holder, item.code)
                    views.addView(R.id.widget_event_list, v)
                    used += h
                }
                is Card -> {
                    val e = item.event
                    val comment = FormatMarks.plain(DayIcons.visibleText(if (replaceDescription) e.location else e.description))
                        .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                    val h = px1 * 1.3f + (if (comment.isNotEmpty()) px2 * 1.3f else 0f) + 4 * dp + 2 * dp
                    val rest = if (hasMore) moreHeight else 0f
                    if (used + h + rest > available) {
                        shownAll = false
                        break
                    }

                    val isTask = e.isTask() || e.isTaskMarked()
                    val done = e.isTaskCompleted() || e.isTaskMarkedDone()
                    val strike = done || e.isAttendeeInviteDeclined() || e.isEventCanceled()
                    val dim = if (isTask) config.dimCompletedTasks && done else config.dimPastEvents && e.isPastEvent
                    val base = if (dim) {
                        androidx.core.graphics.ColorUtils.blendARGB(e.color or 0xFF000000.toInt(), bgColor, 0.45f)
                    } else e.color or 0xFF000000.toInt()
                    val shown = androidx.core.graphics.ColorUtils.blendARGB(bgColor, base, opacity)
                    val ink = readableOn(shown)

                    val sb = android.text.SpannableStringBuilder()
                    if (isTask) sb.append(if (done) "\u2611 " else "\u2610 ")
                    val initial = names[e.calendarId]?.takeIf { it.isNotEmpty() }?.substring(0, 1)?.uppercase()
                    if (initial != null) {
                        val st = sb.length
                        sb.append(initial).append(' ')
                        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length, 0)
                    }
                    if (!e.getIsAllDay()) {
                        sb.append(Formatter.getTimeFromTS(context, e.startTS)).append(' ')
                    }
                    val titleStart = sb.length
                    sb.append(e.title)
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), titleStart, sb.length, 0)
                    if (strike) sb.setSpan(android.text.style.StrikethroughSpan(), titleStart, sb.length, 0)

                    val v = RemoteViews(pkg, R.layout.widget_list_card)
                    v.setTextViewText(R.id.card_line1, sb)
                    v.setTextColor(R.id.card_line1, ink)
                    v.setTextViewTextSize(R.id.card_line1, android.util.TypedValue.COMPLEX_UNIT_SP, sp1)
                    if (comment.isNotEmpty()) {
                        v.setViewVisibility(R.id.card_line2, View.VISIBLE)
                        v.setTextViewText(R.id.card_line2, comment)
                        v.setTextColor(R.id.card_line2, ink)
                        v.setTextViewTextSize(R.id.card_line2, android.util.TypedValue.COMPLEX_UNIT_SP, sp2)
                    }
                    v.setInt(R.id.card_bg, "setColorFilter", base)
                    v.setInt(R.id.card_bg, "setImageAlpha", (opacity * 255).toInt())
                    openEventIntent(context, v, R.id.card_holder, e, isTask)
                    views.addView(R.id.widget_event_list, v)
                    used += h
                }
            }
        }

        if (!shownAll) {
            val more = RemoteViews(pkg, R.layout.widget_list_more)
            more.setTextColor(R.id.list_more, textColor)
            more.setTextViewTextSize(R.id.list_more, android.util.TypedValue.COMPLEX_UNIT_SP, spDay)
            setupIntent(context, more, LAUNCH_CAL, R.id.list_more)
            views.addView(R.id.widget_event_list, more)
        }
    }

    private fun openEventIntent(context: Context, views: RemoteViews, id: Int, event: Event, isTask: Boolean) {
        (context.getLaunchIntent() ?: Intent(context, SplashActivity::class.java)).apply {
            putExtra(EVENT_ID, event.id)
            putExtra(EVENT_OCCURRENCE_TS, event.startTS)
            putExtra(IS_TASK, isTask)
            val code = ((event.id ?: 0L) * 31 + event.startTS).toInt()
            views.setOnClickPendingIntent(id, PendingIntent.getActivity(context, code, this, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    private fun openDayIntent(context: Context, views: RemoteViews, id: Int, dayCode: String) {
        (context.getLaunchIntent() ?: Intent(context, SplashActivity::class.java)).apply {
            putExtra(DAY_CODE, dayCode)
            putExtra(VIEW_TO_OPEN, context.config.listWidgetViewToOpen)
            val code = dayCode.toIntOrNull() ?: 0
            views.setOnClickPendingIntent(id, PendingIntent.getActivity(context, code, this, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    private fun getComponentName(context: Context) = ComponentName(context, MyWidgetListProvider::class.java)

    private fun setupIntent(context: Context, views: RemoteViews, action: String, id: Int) {
        Intent(context, MyWidgetListProvider::class.java).apply {
            this.action = action
            val pendingIntent = PendingIntent.getBroadcast(context, 0, this, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(id, pendingIntent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NEW_EVENT -> context.launchNewEventOrTaskActivity()
            LAUNCH_CAL -> launchCalenderInDefaultView(context)
            GO_TO_TODAY -> goToToday(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDeleted(context: Context?, appWidgetIds: IntArray?) {
        super.onDeleted(context, appWidgetIds)
        ensureBackgroundThread {
            appWidgetIds?.forEach {
                context?.widgetsDB?.deleteWidgetId(it)
            }
        }
    }

    private fun launchCalenderInDefaultView(context: Context) {
        (context.getLaunchIntent() ?: Intent(context, SplashActivity::class.java)).apply {
            putExtra(DAY_CODE, Formatter.getDayCodeFromDateTime(DateTime()))
            putExtra(VIEW_TO_OPEN, context.config.listWidgetViewToOpen)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(this)
        }
    }

    private fun goToToday(context: Context) {
        performUpdate(context)
    }
}
