package org.fossify.calendar.adapters

import android.view.Menu
import android.view.View
import android.view.ViewGroup
import org.fossify.calendar.R
import org.fossify.calendar.activities.SimpleActivity
import org.fossify.calendar.databinding.EventListItemBinding
import org.fossify.calendar.dialogs.DeleteEventDialog
import org.fossify.calendar.extensions.*
import org.fossify.calendar.helpers.EventCards
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.helpers.TaskMarkDrawable
import org.fossify.calendar.models.Event
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView

class DayEventsAdapter(activity: SimpleActivity, val events: ArrayList<Event>, recyclerView: MyRecyclerView, var dayCode: String, itemClick: (Any) -> Unit) :
    MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    private val allDayString = resources.getString(R.string.all_day)
    private val displayDescription = activity.config.displayDescription
    private val replaceDescriptionWithLocation = activity.config.replaceDescription
    private val dimPastEvents = activity.config.dimPastEvents
    private val dimCompletedTasks = activity.config.dimCompletedTasks
    private var isPrintVersion = false
    private val mediumMargin = activity.resources.getDimension(org.fossify.commons.R.dimen.medium_margin).toInt()

    private var initials: Map<Long, String> = emptyMap()

    init {
        setupDragListener(true)
        EventCards.loadInitials(activity) {
            initials = it
            notifyDataSetChanged()
        }
    }

    override fun getActionMenuId() = R.menu.cab_day

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        when (id) {
            R.id.cab_share -> shareEvents()
            R.id.cab_delete -> askConfirmDelete()
        }
    }

    override fun getSelectableItemCount() = events.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = events.getOrNull(position)?.id?.toInt()

    override fun getItemKeyPosition(key: Int) = events.indexOfFirst { it.id?.toInt() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(
            view = EventListItemBinding.inflate(activity.layoutInflater, parent, false).root
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]
        holder.bindView(event, allowSingleClick = true, allowLongClick = true) { itemView, _ ->
            setupView(itemView, event)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = events.size

    fun togglePrintMode() {
        isPrintVersion = !isPrintVersion
        textColor = if (isPrintVersion) {
            resources.getColor(org.fossify.commons.R.color.theme_light_text_color)
        } else {
            activity.getProperTextColor()
        }

        notifyDataSetChanged()
    }

    private fun setupView(view: View, event: Event) {
        EventListItemBinding.bind(view).apply {
            eventItemHolder.isSelected = selectedKeys.contains(event.id?.toInt())
            val taskLike = event.isTask() || event.isTaskMarked()
            val taskDone = event.isTaskCompleted() || event.isTaskMarkedDone()
            val dimCard = !taskLike && dimPastEvents && event.isPastEvent && !isPrintVersion
            val baseCard = EventCards.background(event.color, taskLike, taskDone)
            val cardColor = if (dimCard) androidx.core.graphics.ColorUtils.blendARGB(baseCard, activity.getProperBackgroundColor() or 0xFF000000.toInt(), 0.55f) else baseCard
            eventItemHolder.setBackgroundColor(cardColor)
            val onCard = EventCards.readableOn(baseCard)
            eventItemTitle.text = event.title
            eventItemInitial.text = initials[event.calendarId] ?: ""
                        eventItemTime.text = if (event.getIsAllDay()) allDayString else Formatter.getTimeFromTS(activity, event.startTS)
            if (event.startTS != event.endTS) {
                val startDayCode = Formatter.getDayCodeFromTS(event.startTS)
                val endDayCode = Formatter.getDayCodeFromTS(event.endTS)
                val startDate = Formatter.getDayTitle(activity, startDayCode, false)
                val endDate = Formatter.getDayTitle(activity, endDayCode, false)
                val startDayString = if (startDayCode != dayCode) " ($startDate)" else ""
                if (!event.getIsAllDay()) {
                    val endTimeString = Formatter.getTimeFromTS(activity, event.endTS)
                    val endDayString = if (endDayCode != dayCode) " ($endDate)" else ""
                    eventItemTime.text = "${eventItemTime.text}$startDayString - $endTimeString$endDayString"
                } else {
                    val endDayString = if (endDayCode != dayCode) " - ($endDate)" else ""
                    eventItemTime.text = "${eventItemTime.text}$startDayString$endDayString"
                }
            }

            eventItemDescription.text = if (replaceDescriptionWithLocation) event.location else (org.fossify.calendar.helpers.DayIcons.progressOf(event.description)?.let { "${it.first}/${it.second}  " } ?: "") + org.fossify.calendar.helpers.DayIcons.visibleText(event.description).replace("\n", " ")
            eventItemDescription.beVisibleIf(displayDescription && eventItemDescription.text.isNotEmpty())
            var newTextColor = onCard


            eventItemTime.setTextColor(newTextColor)
            eventItemTitle.setTextColor(newTextColor)
            eventItemDescription.setTextColor(newTextColor)
            eventItemInitial.setTextColor(newTextColor)
            eventItemTaskImage.setImageDrawable(TaskMarkDrawable(taskDone, onCard, cardColor))
            eventItemTaskImage.visibility = if (taskLike) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun shareEvents() = activity.shareEvents(selectedKeys.distinct().map { it.toLong() })

    private fun askConfirmDelete() {
        val eventIds = selectedKeys.map { it.toLong() }.toMutableList()
        val eventsToDelete = events.filter { selectedKeys.contains(it.id?.toInt()) }
        val timestamps = eventsToDelete.map { it.startTS }
        val positions = getSelectedItemPositions()

        val hasRepeatableEvent = eventsToDelete.any { it.repeatInterval > 0 }
        DeleteEventDialog(activity, eventIds, hasRepeatableEvent) { it ->
            events.removeAll(eventsToDelete)

            ensureBackgroundThread {
                val nonRepeatingEventIDs = eventsToDelete.asSequence().filter { it.repeatInterval == 0 }.mapNotNull { it.id }.toMutableList()
                activity.eventsHelper.deleteEvents(nonRepeatingEventIDs, true)

                val repeatingEventIDs = eventsToDelete.asSequence().filter { it.repeatInterval != 0 }.mapNotNull { it.id }.toList()
                activity.handleEventDeleting(repeatingEventIDs, timestamps, it)
                activity.runOnUiThread {
                    removeSelectedItems(positions)
                }
            }
        }
    }
}
