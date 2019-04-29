package ru.cleverpumpkin.calendar

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.collection.ArrayMap
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.cleverpumpkin.calendar.adapter.CalendarAdapter
import ru.cleverpumpkin.calendar.adapter.CalendarItemsGenerator
import ru.cleverpumpkin.calendar.adapter.item.DateItem
import ru.cleverpumpkin.calendar.adapter.item.MonthItem
import ru.cleverpumpkin.calendar.adapter.manager.AdapterDataManager
import ru.cleverpumpkin.calendar.adapter.manager.CalendarAdapterDataManager
import ru.cleverpumpkin.calendar.decorations.GridDividerItemDecoration
import ru.cleverpumpkin.calendar.extension.getColorInt
import ru.cleverpumpkin.calendar.selection.*
import ru.cleverpumpkin.calendar.utils.CalendarAttributesReader
import ru.cleverpumpkin.calendar.utils.DateInfoProvider
import ru.cleverpumpkin.calendar.utils.DisplayedDatesRangeProvider
import java.util.*

/**
 * This class represents a Calendar Widget that allow displaying calendar grid, selecting dates,
 * displaying color indicators for the specific dates and handling date selection with a custom action.
 *
 * The Calendar must be initialized with the [setupCalendar] method where you can specify
 * parameters for the calendar.
 *
 * The Calendar UI open for customization.
 * Using XML attributes you can define grid divider color, date cell selectors etc.
 * Using standard [RecyclerView.ItemDecoration] you can define special drawing for the calendar items.
 *
 * This class overrides [onSaveInstanceState] and [onRestoreInstanceState], so it is able
 * to save and restore its state.
 */
class CalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = R.attr.calendarViewStyle

) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * This interface represents a colored indicator for the specific date that will be displayed
     * on the Calendar.
     */
    interface DateIndicator {
        val date: CalendarDate
        val color: Int
    }

    companion object {
        private const val DAYS_IN_WEEK = 7
        private const val MAX_RECYCLED_DAY_VIEWS = 90
        private const val MAX_RECYCLED_EMPTY_VIEWS = 20
        private const val MONTHS_PER_PAGE = 6

        private const val BUNDLE_SUPER_STATE = "ru.cleverpumpkin.calendar.super_state"
        private const val BUNDLE_DISPLAYED_DATE = "ru.cleverpumpkin.calendar.displayed_date"
        private const val BUNDLE_DISPLAY_DATE_RANGE = "ru.cleverpumpkin.calendar.display_date_range"
        private const val BUNDLE_LIMIT_DATE_RANGE = "ru.cleverpumpkin.calendar.limit_date_range"
        private const val BUNDLE_SELECTION_MODE = "ru.cleverpumpkin.calendar.selection_mode"
        private const val BUNDLE_FIRST_DAY_OF_WEEK = "ru.cleverpumpkin.calendar.first_day_of_week"
        private const val BUNDLE_SHOW_YEAR_SELECTION_VIEW =
            "ru.cleverpumpkin.calendar.show_year_selection_view"
    }

    /**
     * This enum class represents available selection modes for dates selecting
     */
    enum class SelectionMode {
        /**
         * Selection is unavailable. No dates will be selectable.
         */
        NONE,

        /**
         * Only one date can be selected at a time.
         */
        SINGLE,

        /**
         * A number of dates can be selected. Pressing an already selected date will unselect it.
         */
        MULTIPLE,

        /**
         * Allows you to select a date range. Previous selected range is cleared when you select another one.
         */
        RANGE
    }

    private val calendarStyles = CalendarStyles(context)

    private val yearSelectionView: YearSelectionView
    private val daysBarView: DaysBarView
    private val recyclerView: RecyclerView
    private val calendarAdapter: CalendarAdapter

    /**
     * Internal flag, that indicates whether the Calendar has been initialized
     * with [setupCalendar] method or not.
     */
    private var hasBeenInitializedWithSetup = false

    private var displayedDatesRange = DatesRange.emptyRange()
    private var minMaxDatesRange = NullableDatesRange()
    private var dateSelectionStrategy: DateSelectionStrategy = NoDateSelectionStrategy()
    private val displayedYearUpdateListener = DisplayedYearUpdateListener()
    private val dateInfoProvider: DateInfoProvider = DefaultDateInfoProvider()
    private val adapterDataManager: AdapterDataManager
    private lateinit var calendarItemsGenerator: CalendarItemsGenerator

    private var selectionMode: SelectionMode = SelectionMode.NONE
        set(value) {
            field = value

            dateSelectionStrategy = when (value) {
                SelectionMode.NONE -> {
                    NoDateSelectionStrategy()
                }

                SelectionMode.SINGLE -> {
                    SingleDateSelectionStrategy(adapterDataManager, dateInfoProvider)
                }

                SelectionMode.MULTIPLE -> {
                    MultipleDateSelectionStrategy(adapterDataManager, dateInfoProvider)
                }

                SelectionMode.RANGE -> {
                    RangeDateSelectionStrategy(adapterDataManager, dateInfoProvider)
                }
            }
        }

    private var showYearSelectionView = true
        set(value) {
            field = value
            recyclerView.removeOnScrollListener(displayedYearUpdateListener)

            if (showYearSelectionView) {
                yearSelectionView.visibility = View.VISIBLE
                recyclerView.addOnScrollListener(displayedYearUpdateListener)
            } else {
                yearSelectionView.visibility = View.GONE
            }
        }

    private val defaultFirstDayOfWeek: Int
        get() = Calendar.getInstance().firstDayOfWeek

    /**
     * The first day of the week, e.g [Calendar.SUNDAY], [Calendar.MONDAY], etc.
     */
    var firstDayOfWeek: Int = defaultFirstDayOfWeek
        private set(value) {
            field = value
            daysBarView.setupDaysBarView(firstDayOfWeek)
            calendarItemsGenerator = CalendarItemsGenerator(firstDayOfWeek)
        }

    /**
     * Grouped by date indicators that will be displayed on the Calendar.
     */
    private val groupedDatesIndicators = ArrayMap<CalendarDate, MutableList<DateIndicator>>()

    /**
     * List of indicators that will be displayed on the Calendar.
     */
    var datesIndicators: List<DateIndicator> = emptyList()
        set(value) {
            field = value
            groupedDatesIndicators.clear()
            value.groupByTo(groupedDatesIndicators) { it.date }
            recyclerView.adapter?.notifyDataSetChanged()
        }

    /**
     * Listener that will be notified when a date cell is clicked.
     */
    var onDateClickListener: ((CalendarDate) -> Unit)? = null

    /**
     * Listener that will be notified when a date cell is long clicked.
     */
    var onDateLongClickListener: ((CalendarDate) -> Unit)? = null

    /**
     * Listener that will be notified when a year view is clicked.
     */
    var onYearClickListener: ((Int) -> Unit)? = null
        set(value) {
            field = value
            yearSelectionView.onYearClickListener = value
        }

    /**
     * Date selection filter that indicates whether a date available for selection or not.
     */
    var dateSelectionFilter: ((CalendarDate) -> Boolean)? = null

    /**
     * Returns selected dates according to the [selectionMode].
     *
     * When selection mode is:
     * [SelectionMode.NONE] returns empty list
     * [SelectionMode.SINGLE] returns list with a single selected date
     * [SelectionMode.MULTIPLE] returns all selected dates in order they were added
     * [SelectionMode.RANGE] returns all dates in selected range
     */
    val selectedDates: List<CalendarDate>
        get() = dateSelectionStrategy.getSelectedDates()

    /**
     * Returns selected date or null according to the [selectionMode].
     */
    val selectedDate: CalendarDate?
        get() = dateSelectionStrategy.getSelectedDates()
            .firstOrNull()

    /**
     * Sets the grid color for this view.
     */
    fun setGridColor(@ColorInt color: Int) {
        calendarStyles.gridColor = color
        updateGridDividerItemDecoration()
    }

    /**
     * Sets the grid color for this view.
     */
    fun setGridColorRes(@ColorRes colorRes: Int) {
        setGridColor(getColorInt(colorRes))
    }

    /**
     * Sets the year selection bar background color.
     */
    fun setYearSelectionBarBackgroundColor(@ColorInt color: Int) {
        calendarStyles.yearSelectionBackground = color
        yearSelectionView.applyStyle(calendarStyles)
    }

    /**
     * Sets the year selection bar background color resource.
     */
    fun setYearSelectionBackgroundColorRes(@ColorRes colorRes: Int) {
        setYearSelectionBarBackgroundColor(getColorInt(colorRes))
    }

    /**
     * Sets the year selection bar arrows color.
     */
    fun setYearSelectionBarArrowsColor(@ColorInt color: Int) {
        calendarStyles.yearSelectionArrowsColor = color
        yearSelectionView.applyStyle(calendarStyles)
    }

    /**
     * Sets the year selection bar arrows color resource.
     */
    fun setYearSelectionBarArrowsColorRes(@ColorRes colorRes: Int) {
        setYearSelectionBarArrowsColor(getColorInt(colorRes))
    }

    /**
     * Sets the year selection bar text color.
     */
    fun setYearSelectionBarTextColor(@ColorInt color: Int) {
        calendarStyles.yearSelectionTextColor = color
        yearSelectionView.applyStyle(calendarStyles)
    }

    /**
     * Sets the year selection bar text color resource.
     */
    fun setYearSelectionBarTextColorRes(@ColorRes colorRes: Int) {
        setYearSelectionBarTextColor(getColorInt(colorRes))
    }

    /**
     * Sets the days of week bar background color.
     */
    fun setDaysBarBackgroundColor(@ColorInt color: Int) {
        calendarStyles.daysBarBackground = color
        daysBarView.applyStyle(calendarStyles)
    }

    /**
     * Sets the days of week bar background color resource.
     */
    fun setDaysBarBackgroundColorRes(@ColorRes colorRes: Int) {
        setDaysBarBackgroundColor(getColorInt(colorRes))
    }

    /**
     * Sets the days of week bar text color.
     */
    fun setDaysBarTextColor(@ColorInt color: Int) {
        calendarStyles.daysBarTextColor = color
        daysBarView.applyStyle(calendarStyles)
    }

    /**
     * Sets the days of week bar text color resources.
     */
    fun setDaysBarTextColorRes(@ColorRes colorRes: Int) {
        setDaysBarTextColor(getColorInt(colorRes))
    }

    /**
     * Sets the month name text color.
     */
    fun setMonthTextColor(@ColorInt color: Int) {
        calendarStyles.monthTextColor = color
        calendarAdapter.notifyDataSetChanged()
    }

    /**
     * Sets the month name text color resource.
     */
    fun setMonthTextColorRes(@ColorRes colorRes: Int) {
        setMonthTextColor(getColorInt(colorRes))
    }

    /**
     * Sets a date cell background drawable resource.
     */
    fun setDateCellBackgroundDrawableRes(@DrawableRes drawableRes: Int) {
        calendarStyles.dateCellBackgroundColorRes = drawableRes
        calendarAdapter.notifyDataSetChanged()
    }

    /**
     * Sets a date cell text color resource.
     */
    fun setDateCellTextColorRes(@ColorRes colorRes: Int) {
        calendarStyles.dateTextColorRes = colorRes
        calendarAdapter.notifyDataSetChanged()
    }

    /**
     * Init block where we read custom attributes and setting up internal views
     */
    init {
        LayoutInflater.from(context).inflate(R.layout.view_calendar, this, true)

        yearSelectionView = findViewById(R.id.year_selection_view)
        daysBarView = findViewById(R.id.days_bar_view)
        recyclerView = findViewById(R.id.recycler_view)

        if (attrs != null) {
            CalendarAttributesReader.readAttributes(
                context = context,
                attrs = attrs,
                defStyleAttr = defStyleAttr,
                destCalendarStyles = calendarStyles
            )
        }

        calendarAdapter = CalendarAdapter(
            style = calendarStyles,
            dateInfoProvider = dateInfoProvider,
            onDateClickListener = { date, longClick ->
                if (longClick) {
                    onDateLongClickListener?.invoke(date)
                } else {
                    dateSelectionStrategy.onDateSelected(date)
                    onDateClickListener?.invoke(date)
                }
            }
        )

        adapterDataManager = CalendarAdapterDataManager(calendarAdapter)

        daysBarView.applyStyle(calendarStyles)
        yearSelectionView.applyStyle(calendarStyles)

        yearSelectionView.onYearChangeListener = { displayedDate ->
            moveToDate(displayedDate)
        }

        setupRecyclerView(recyclerView)
    }

    private fun setupRecyclerView(recyclerView: RecyclerView) {

        val gridLayoutManager = object : GridLayoutManager(context, DAYS_IN_WEEK) {
            override fun onRestoreInstanceState(state: Parcelable?) {
                if (hasBeenInitializedWithSetup.not()) {
                    super.onRestoreInstanceState(state)
                }
            }
        }

        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (recyclerView.adapter?.getItemViewType(position)) {
                    CalendarAdapter.MONTH_VIEW_TYPE -> DAYS_IN_WEEK
                    else -> 1
                }
            }
        }

        recyclerView.run {
            adapter = calendarAdapter
            layoutManager = gridLayoutManager
            itemAnimator = null

            recycledViewPool.setMaxRecycledViews(
                CalendarAdapter.DATE_VIEW_TYPE,
                MAX_RECYCLED_DAY_VIEWS
            )

            recycledViewPool.setMaxRecycledViews(
                CalendarAdapter.EMPTY_VIEW_TYPE,
                MAX_RECYCLED_EMPTY_VIEWS
            )

            setHasFixedSize(true)
            updateGridDividerItemDecoration()

            addOnScrollListener(CalendarItemsGenerationListener())
        }
    }

    /**
     * Method for the initial calendar set up. All parameters have default values.
     *
     * [initialDate] the date that will be displayed initially.
     * Default value - today date.
     *
     * [minDate] minimum date for the Calendar grid, inclusive.
     * If null, the Calendar will display all available dates before [initialDate]
     * Default value - null.
     *
     * [maxDate] maximum date for the Calendar grid, inclusive.
     * If null, the Calendar will display all available dates after [initialDate]
     * Default value - null.
     *
     * [selectionMode] mode for dates selecting.
     * Default value - [SelectionMode.NONE].
     *
     * When selection mode is:
     * [SelectionMode.SINGLE], [selectedDates] can contains only single date.
     * [SelectionMode.MULTIPLE], [selectedDates] can contains multiple date.
     * [SelectionMode.RANGE], [selectedDates] can contains two dates that represent selected range.
     *
     * [selectedDates] list of the initially selected dates.
     * Default value - empty list.
     *
     * [firstDayOfWeek] the first day of the week: [Calendar.SUNDAY], [Calendar.MONDAY], etc.
     * Default value - null. If null, the Calendar will be initialized with the [defaultFirstDayOfWeek].
     *
     * [showYearSelectionView] flag that indicates whether year selection view will be displayed or not.
     * Default value - true.
     */
    fun setupCalendar(
        initialDate: CalendarDate = CalendarDate.today,
        minDate: CalendarDate? = null,
        maxDate: CalendarDate? = null,
        selectionMode: SelectionMode = SelectionMode.NONE,
        selectedDates: List<CalendarDate> = emptyList(),
        firstDayOfWeek: Int = defaultFirstDayOfWeek,
        showYearSelectionView: Boolean = true
    ) {
        if (minDate != null && maxDate != null && minDate > maxDate) {
            throw IllegalArgumentException("minDate must be before maxDate: $minDate, maxDate: $maxDate")
        }

        if (firstDayOfWeek < Calendar.SUNDAY || firstDayOfWeek > Calendar.SATURDAY) {
            throw IllegalArgumentException("Incorrect value of firstDayOfWeek: $firstDayOfWeek")
        }

        this.selectionMode = selectionMode
        this.firstDayOfWeek = firstDayOfWeek
        this.showYearSelectionView = showYearSelectionView
        minMaxDatesRange = NullableDatesRange(dateFrom = minDate, dateTo = maxDate)

        yearSelectionView.setupYearSelectionView(
            displayedDate = initialDate,
            minMaxDatesRange = minMaxDatesRange
        )

        updateSelectedDatesInternal(selectedDates)

        displayedDatesRange = DisplayedDatesRangeProvider.getDisplayedDatesRange(
            initialDate = initialDate,
            minDate = minDate,
            maxDate = maxDate
        )

        generateCalendarItems(displayedDatesRange)
        moveToDate(initialDate)

        hasBeenInitializedWithSetup = true
    }

    /**
     * Method for fast moving to the specific calendar date.
     * If [date] is out of min-max date boundaries, moving won't be performed.
     */
    fun moveToDate(date: CalendarDate) {
        val (minDate, maxDate) = minMaxDatesRange

        if ((minDate != null && date < minDate.monthBeginning()) ||
            (maxDate != null && date > maxDate.monthEnd())) {
            return
        }

        val (displayDatesFrom, displayDatesTo) = displayedDatesRange

        if (date.isBetween(dateFrom = displayDatesFrom, dateTo = displayDatesTo).not()) {
            displayedDatesRange = DisplayedDatesRangeProvider.getDisplayedDatesRange(
                initialDate = date,
                minDate = minDate,
                maxDate = maxDate
            )

            generateCalendarItems(displayedDatesRange)
        }

        val dateMonthPosition = calendarAdapter.findMonthPosition(date)
        if (dateMonthPosition != -1) {
            val gridLayoutManager = recyclerView.layoutManager as GridLayoutManager
            gridLayoutManager.scrollToPositionWithOffset(dateMonthPosition, 0)
            recyclerView.stopScroll()
        }
    }

    /**
     * Add custom [RecyclerView.ItemDecoration] that will be used for the Calendar view decoration.
     */
    fun addCustomItemDecoration(itemDecoration: RecyclerView.ItemDecoration) {
        recyclerView.addItemDecoration(itemDecoration)
    }

    /**
     * Remove specific [RecyclerView.ItemDecoration] that previously was added.
     */
    fun removeCustomItemDecoration(itemDecoration: RecyclerView.ItemDecoration) {
        recyclerView.removeItemDecoration(itemDecoration)
    }

    /**
     * Returns list of indicators for the specific date.
     */
    fun getDateIndicators(date: CalendarDate): List<DateIndicator> {
        return groupedDatesIndicators[date] ?: emptyList()
    }

    /**
     * Update currently selected dates.
     */
    fun updateSelectedDates(selectedDates: List<CalendarDate>) {
        dateSelectionStrategy.clear()
        updateSelectedDatesInternal(selectedDates)
    }

    private fun updateSelectedDatesInternal(selectedDates: List<CalendarDate>) {
        if (selectedDates.isEmpty()) {
            return
        }

        when {
            selectionMode == SelectionMode.NONE -> {
                throw IllegalArgumentException(
                    "You cannot define selected dates when the SelectionMode is NONE"
                )
            }

            selectionMode == SelectionMode.SINGLE && selectedDates.size > 1 -> {
                throw IllegalArgumentException(
                    "You cannot define more than one selected dates when the SelectionMode is SINGLE"
                )
            }

            selectionMode == SelectionMode.RANGE && selectedDates.size != 2 -> {
                throw IllegalArgumentException(
                    "You must define two selected dates (start and end) when the SelectionMode is RANGE"
                )
            }
        }

        selectedDates.forEach { date ->
            if (dateInfoProvider.isDateOutOfRange(date).not() &&
                dateInfoProvider.isDateSelectable(date)
            ) {
                dateSelectionStrategy.onDateSelected(date)
            }
        }
    }

    private fun updateGridDividerItemDecoration() {
        val divider = GridDividerItemDecoration(context, calendarStyles)

        if (recyclerView.itemDecorationCount > 0) {
            recyclerView.removeItemDecorationAt(0)
        }

        recyclerView.addItemDecoration(divider, 0)
    }

    private fun generateCalendarItems(datesRange: DatesRange) {
        if (datesRange.isEmptyRange) {
            return
        }

        val calendarItems = calendarItemsGenerator.generateCalendarItems(
            dateFrom = datesRange.dateFrom,
            dateTo = datesRange.dateTo
        )

        calendarAdapter.setCalendarItems(calendarItems)
    }

    private fun generatePrevCalendarItems() {
        val minDate = minMaxDatesRange.dateFrom
        if (minDate != null && minDate.monthsBetween(displayedDatesRange.dateFrom) == 0) {
            return
        }

        val generateDatesFrom: CalendarDate
        val generateDatesTo = displayedDatesRange.dateFrom.minusMonths(1)

        generateDatesFrom = if (minDate != null) {
            val monthBetween = minDate.monthsBetween(generateDatesTo)

            if (monthBetween > MONTHS_PER_PAGE) {
                generateDatesTo.minusMonths(MONTHS_PER_PAGE)
            } else {
                generateDatesTo.minusMonths(monthBetween)
            }

        } else {
            generateDatesTo.minusMonths(MONTHS_PER_PAGE)
        }

        val calendarItems = calendarItemsGenerator.generateCalendarItems(
            dateFrom = generateDatesFrom,
            dateTo = generateDatesTo
        )

        calendarAdapter.addPrevCalendarItems(calendarItems)
        displayedDatesRange = displayedDatesRange.copy(dateFrom = generateDatesFrom)
    }

    private fun generateNextCalendarItems() {
        val maxDate = minMaxDatesRange.dateTo
        if (maxDate != null && displayedDatesRange.dateTo.monthsBetween(maxDate) == 0) {
            return
        }

        val generateDatesFrom = displayedDatesRange.dateTo.plusMonths(1)
        val generateDatesTo: CalendarDate

        generateDatesTo = if (maxDate != null) {
            val monthBetween = generateDatesFrom.monthsBetween(maxDate)

            if (monthBetween > MONTHS_PER_PAGE) {
                generateDatesFrom.plusMonths(MONTHS_PER_PAGE)
            } else {
                generateDatesFrom.plusMonths(monthBetween)
            }
        } else {
            generateDatesFrom.plusMonths(MONTHS_PER_PAGE)
        }

        val calendarItems = calendarItemsGenerator.generateCalendarItems(
            dateFrom = generateDatesFrom,
            dateTo = generateDatesTo
        )

        calendarAdapter.addNextCalendarItems(calendarItems)
        displayedDatesRange = displayedDatesRange.copy(dateTo = generateDatesTo)
    }

    /**
     * Save internal Calendar state: displayed dates, min-max dates,
     * selection mode, selected dates, first day of week and view super state.
     */
    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()

        return Bundle().apply {
            putString(BUNDLE_SELECTION_MODE, selectionMode.name)
            putParcelable(BUNDLE_DISPLAY_DATE_RANGE, displayedDatesRange)
            putParcelable(BUNDLE_LIMIT_DATE_RANGE, minMaxDatesRange)
            putParcelable(BUNDLE_SUPER_STATE, superState)
            putParcelable(BUNDLE_DISPLAYED_DATE, yearSelectionView.displayedDate)
            putBoolean(BUNDLE_SHOW_YEAR_SELECTION_VIEW, showYearSelectionView)
            putInt(BUNDLE_FIRST_DAY_OF_WEEK, firstDayOfWeek)
            dateSelectionStrategy.saveSelectedDates(this)
        }
    }

    /**
     * Restore internal calendar state.
     *
     * Note: If Calendar was initialized with [setupCalendar] method before [onRestoreInstanceState],
     * restoring of internal calendar state won't be performed, because new state already set up.
     */
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            val superState: Parcelable? = state.getParcelable(BUNDLE_SUPER_STATE)
            super.onRestoreInstanceState(superState)

            if (hasBeenInitializedWithSetup.not()) {
                val modeName = state.getString(BUNDLE_SELECTION_MODE, SelectionMode.NONE.name)
                selectionMode = SelectionMode.valueOf(modeName)

                displayedDatesRange = state.getParcelable(BUNDLE_DISPLAY_DATE_RANGE)
                        ?: displayedDatesRange

                minMaxDatesRange = state.getParcelable(BUNDLE_LIMIT_DATE_RANGE)
                        ?: minMaxDatesRange

                showYearSelectionView = state.getBoolean(BUNDLE_SHOW_YEAR_SELECTION_VIEW)
                firstDayOfWeek = state.getInt(BUNDLE_FIRST_DAY_OF_WEEK)
                dateSelectionStrategy.restoreSelectedDates(state)

                val displayedDate: CalendarDate? = state.getParcelable(BUNDLE_DISPLAYED_DATE)
                if (displayedDate != null) {
                    yearSelectionView.setupYearSelectionView(
                        displayedDate = displayedDate,
                        minMaxDatesRange = minMaxDatesRange
                    )
                }

                generateCalendarItems(displayedDatesRange)
            }
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private inner class DefaultDateInfoProvider : DateInfoProvider {
        private val todayCalendarDate = CalendarDate.today

        override fun isToday(date: CalendarDate): Boolean {
            return date == todayCalendarDate
        }

        override fun isDateSelected(date: CalendarDate): Boolean {
            return dateSelectionStrategy.isDateSelected(date)
        }

        override fun isDateOutOfRange(date: CalendarDate): Boolean {
            return minMaxDatesRange.isDateOutOfRange(date)
        }

        override fun isDateSelectable(date: CalendarDate): Boolean {
            return dateSelectionFilter?.invoke(date) ?: true
        }

        override fun isWeekend(date: CalendarDate): Boolean {
            return date.dayOfWeek == Calendar.SUNDAY || date.dayOfWeek == Calendar.SATURDAY
        }

        override fun getDateIndicators(date: CalendarDate): List<DateIndicator> {
            return this@CalendarView.getDateIndicators(date)
        }
    }

    private inner class CalendarItemsGenerationListener : RecyclerView.OnScrollListener() {

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)

            val lastChildIndex = recyclerView.layoutManager?.childCount ?: return
            val lastChild = recyclerView.layoutManager?.getChildAt(lastChildIndex - 1) ?: return
            val lastChildAdapterPosition = recyclerView.getChildAdapterPosition(lastChild) + 1

            if (recyclerView.adapter?.itemCount == lastChildAdapterPosition) {
                recyclerView.post { generateNextCalendarItems() }
            }

            val firstChild = recyclerView.layoutManager?.getChildAt(0) ?: return
            val firstChildAdapterPosition = recyclerView.getChildAdapterPosition(firstChild)

            if (firstChildAdapterPosition == 0) {
                recyclerView.post { generatePrevCalendarItems() }
            }
        }
    }

    private inner class DisplayedYearUpdateListener : RecyclerView.OnScrollListener() {

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)

            val firstChild = recyclerView.layoutManager?.getChildAt(0) ?: return
            val firstChildAdapterPosition = recyclerView.getChildAdapterPosition(firstChild)

            val calendarItem = calendarAdapter.getCalendarItemAt(firstChildAdapterPosition)
            if (calendarItem is DateItem) {
                yearSelectionView.displayedDate = calendarItem.date
            } else if (calendarItem is MonthItem) {
                yearSelectionView.displayedDate = calendarItem.date
            }
        }
    }

}