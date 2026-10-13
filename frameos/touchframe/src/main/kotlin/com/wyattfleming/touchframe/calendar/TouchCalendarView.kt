package com.wyattfleming.touchframe.calendar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wyattfleming.touchframe.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read-only native calendar. The activity supplies real range results and owns network lifecycle. */
@SuppressLint("ViewConstructor")
class TouchCalendarView(
    context: Context,
    private val onStateRequest: (TouchCalendarMode, LocalDate, LocalDate, Boolean) -> Unit,
    private val onSignIn: () -> Unit,
) : ScrollView(context) {
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var state = TouchCalendarState(
        TouchCalendarWeekPresentation.DEFAULT_MODE,
        LocalDate.now(),
        LocalDate.now(),
        TouchCalendarRange.forMode(TouchCalendarWeekPresentation.DEFAULT_MODE, LocalDate.now()),
        emptyList(),
        TouchCalendarConnection.OFFLINE,
        null,
    )
    private var night = false

    init {
        addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    fun render(next: TouchCalendarState) { state = next; rebuild() }
    fun setNight(value: Boolean) { if (night != value) { night = value; rebuild() } }

    private fun rebuild() {
        val ink = if (night) Color.rgb(241, 238, 230) else Color.rgb(34, 38, 42)
        val paper = if (night) Color.rgb(27, 31, 34) else Color.rgb(247, 244, 237)
        val accent = if (night) Color.rgb(153, 209, 187) else Color.rgb(21, 107, 88)
        setBackgroundColor(paper)
        body.removeAllViews()
        body.setPadding(dp(28), dp(12), dp(28), dp(24))
        body.addView(text("Calendar", 32f, ink, bold = true))
        body.addView(row(listOf("Agenda" to TouchCalendarMode.AGENDA, "Week" to TouchCalendarMode.WEEK, "Month" to TouchCalendarMode.MONTH), ink, accent) { _, mode ->
            request(requireNotNull(mode), state.anchor, state.selectedDate, true)
        })
        body.addView(row(listOf("Previous" to null, "Today" to null, "Next" to null), ink, accent) { label, _ ->
            when (label) {
                "Previous" -> request(state.mode, previousAnchor(), state.selectedDate, true)
                "Next" -> request(state.mode, nextAnchor(), state.selectedDate, true)
                else -> request(state.mode, LocalDate.now(), LocalDate.now(), true)
            }
        })
        body.addView(text(title(), 24f, ink, bold = true))
        if (state.loading) body.addView(text("Updating schedule…", 18f, ink))
        when (state.connection) {
            TouchCalendarConnection.SIGN_IN_REQUIRED -> body.addView(Button(context).apply {
                text = context.getString(R.string.touch_calendar_sign_in)
                textSize = TouchCalendarPresentation.CONTROL_TEXT_SP
                isAllCaps = false
                minHeight = dp(56)
                setOnClickListener { onSignIn() }
            })
            TouchCalendarConnection.OFFLINE -> body.addView(text(if (state.events.isEmpty()) "Calendar is unavailable." else "Offline · showing the last available schedule.", 18f, ink))
            TouchCalendarConnection.CONNECTED -> if (state.partial) body.addView(text("Some calendars could not be refreshed.", 18f, ink))
        }
        when (state.mode) {
            TouchCalendarMode.AGENDA -> agenda(state.range.start, state.range.endExclusive, ink, accent)
            TouchCalendarMode.WEEK -> {
                val days = weekDays()
                week(days, ink, accent)
                selectedWeekDetails(days, ink)
            }
            TouchCalendarMode.MONTH -> {
                month(ink, accent)
                selectedDetails(state.events.filter { it.occursOn(state.selectedDate, ZoneId.systemDefault()) }, ink)
            }
        }
    }

    private fun agenda(start: LocalDate, end: LocalDate, ink: Int, accent: Int) {
        var date = start
        while (date < end) {
            body.addView(dayButton(date, ink, accent))
            state.events.filter { it.occursOn(date, ZoneId.systemDefault()) }.forEach { body.addView(eventRow(it, ink)) }
            date = date.plusDays(1)
        }
    }

    private fun weekDays() = TouchCalendarWeekPresentation.days(state.range, state.events, ZoneId.systemDefault())

    private fun week(days: List<TouchCalendarWeekPresentation.Day>, ink: Int, accent: Int) {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        days.forEach { day ->
            strip.addView(weekDayColumn(day, ink, accent), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        body.addView(strip)
    }

    private fun weekDayColumn(day: TouchCalendarWeekPresentation.Day, ink: Int, accent: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        minimumHeight = dp(236)
        setPadding(dp(5), dp(7), dp(5), dp(7))
        val selected = day.date == state.selectedDate
        val today = day.date == LocalDate.now()
        background = dayBackground(accent, selected, today)
        isClickable = true
        isFocusable = true
        contentDescription = "${day.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))}, ${day.events.size} events"
        setOnClickListener { request(state.mode, state.anchor, day.date, false) }
        addView(weekHeader(day.date, "EEE", 20f, ink, true))
        addView(weekHeader(day.date, "MMM d", 17f, ink, false))
        if (day.preview.isEmpty()) {
            addView(text("No events", 16f, ink).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0) })
        } else {
            day.preview.forEach { preview -> addView(weekEvent(preview, ink)) }
            if (day.overflowCount > 0) addView(text("+${day.overflowCount} more", 17f, ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        }
    }

    private fun weekHeader(date: LocalDate, pattern: String, size: Float, ink: Int, bold: Boolean) = text(
        date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())), size, ink, bold,
    ).apply {
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
    }

    private fun weekEvent(preview: TouchCalendarWeekPresentation.EventPreview, ink: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(3), dp(8), dp(3), 0)
        addView(text(preview.event.title, 18f, ink, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = preview.event.title
            setPadding(0, 0, 0, 0)
        })
        addView(text(preview.timeLabel, 16.5f, ink).apply { setPadding(0, 0, 0, 0) })
    }

    private fun selectedWeekDetails(days: List<TouchCalendarWeekPresentation.Day>, ink: Int) {
        val selected = days.firstOrNull { it.date == state.selectedDate }
        selectedDetails(selected?.events.orEmpty(), ink)
    }

    private fun selectedDetails(events: List<TouchCalendarEvent>, ink: Int) {
        body.addView(text(state.selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())), 24f, ink, bold = true))
        if (events.isEmpty()) body.addView(text("No events selected", 18f, ink)) else events.forEach { body.addView(eventRow(it, ink)) }
    }

    private fun month(ink: Int, accent: Int) {
        val headers = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("M", "T", "W", "T", "F", "S", "S").forEach {
            headers.addView(weekdayHeader(it, ink), LinearLayout.LayoutParams(0, dp(TouchCalendarPresentation.WEEKDAY_HEADER_MIN_HEIGHT_DP), 1f))
        }
        body.addView(headers)
        var date = state.range.start
        repeat(TouchCalendarRenderPlan.monthWeeks(state.range)) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                val current = date
                line.addView(dayCell(current, ink, accent), LinearLayout.LayoutParams(0, dp(64), 1f))
                date = date.plusDays(1)
            }
            body.addView(line)
        }
    }

    private fun dayButton(date: LocalDate, ink: Int, accent: Int) = Button(context).apply {
        text = context.getString(R.string.touch_calendar_day_summary, date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())), state.events.count { it.occursOn(date, ZoneId.systemDefault()) })
        textSize = TouchCalendarPresentation.DAY_LABEL_TEXT_SP
        isAllCaps = false
        minHeight = dp(56)
        setTextColor(if (date == state.selectedDate) selectedInk() else ink)
        setOnClickListener { request(state.mode, state.anchor, date, false) }
        background = background(accent, date == state.selectedDate)
    }

    private fun dayCell(date: LocalDate, ink: Int, accent: Int) = TextView(context).apply {
        text = if (state.events.any { it.occursOn(date, ZoneId.systemDefault()) }) context.getString(R.string.touch_calendar_day_with_events, date.dayOfMonth) else context.getString(R.string.touch_calendar_day_number, date.dayOfMonth)
        textSize = TouchCalendarPresentation.DAY_LABEL_TEXT_SP
        gravity = Gravity.CENTER
        setTextColor(if (date == state.selectedDate) selectedInk() else ink)
        isClickable = true
        contentDescription = date.toString()
        background = background(accent, date == state.selectedDate)
        setOnClickListener { request(state.mode, state.anchor, date, false) }
    }

    private fun eventRow(event: TouchCalendarEvent, ink: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(text(event.title, 20f, ink, true))
        addView(text(if (event.allDay) "All day" else Instant.ofEpochMilli(event.startEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())), 17f, ink))
    }

    private fun row(items: List<Pair<String, TouchCalendarMode?>>, ink: Int, accent: Int, action: (String, TouchCalendarMode?) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEach { (label, mode) ->
            addView(Button(context).apply {
                text = label
                textSize = TouchCalendarPresentation.CONTROL_TEXT_SP
                isAllCaps = false
                contentDescription = TouchCalendarPresentation.contentDescriptionFor(label)
                minHeight = dp(56)
                setTextColor(if (mode == state.mode) selectedInk() else ink)
                background = background(accent, mode == state.mode)
                setOnClickListener { action(label, mode) }
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
    }

    private fun weekdayHeader(value: String, color: Int) = TextView(context).apply {
        text = value
        textSize = TouchCalendarPresentation.WEEKDAY_HEADER_TEXT_SP
        gravity = Gravity.CENTER
        minHeight = dp(TouchCalendarPresentation.WEEKDAY_HEADER_MIN_HEIGHT_DP)
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(8), 0, dp(8))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun selectedInk() = if (night) Color.rgb(18, 31, 28) else Color.WHITE
    private fun background(accent: Int, selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (selected) accent else Color.TRANSPARENT)
        setStroke(dp(1), accent)
    }
    private fun dayBackground(accent: Int, selected: Boolean, today: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (selected) accent else if (today) Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.TRANSPARENT)
        setStroke(dp(if (today || selected) 2 else 1), accent)
    }
    private fun request(mode: TouchCalendarMode, anchor: LocalDate, selected: LocalDate, refresh: Boolean) = onStateRequest(mode, anchor, selected, refresh)
    private fun previousAnchor() = when (state.mode) {
        TouchCalendarMode.AGENDA -> state.anchor.minusDays(7)
        TouchCalendarMode.WEEK -> state.anchor.minusWeeks(1)
        TouchCalendarMode.MONTH -> state.anchor.minusMonths(1)
    }
    private fun nextAnchor() = when (state.mode) {
        TouchCalendarMode.AGENDA -> state.anchor.plusDays(7)
        TouchCalendarMode.WEEK -> state.anchor.plusWeeks(1)
        TouchCalendarMode.MONTH -> state.anchor.plusMonths(1)
    }
    private fun title() = when (state.mode) {
        TouchCalendarMode.MONTH -> state.anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        else -> "${state.range.start} – ${state.range.endExclusive.minusDays(1)}"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

internal object TouchCalendarPresentation {
    const val CONTROL_TEXT_SP = 22f
    const val DAY_LABEL_TEXT_SP = 20f
    const val WEEKDAY_HEADER_TEXT_SP = 20f
    const val WEEKDAY_HEADER_MIN_HEIGHT_DP = 48

    fun contentDescriptionFor(label: String): String = when (label) {
        "Previous" -> "Previous calendar period"
        "Next" -> "Next calendar period"
        else -> label
    }
}
