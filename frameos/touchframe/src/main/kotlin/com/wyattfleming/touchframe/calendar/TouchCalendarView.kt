package com.wyattfleming.touchframe.calendar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read-only native calendar. The activity supplies real range results and owns network lifecycle. */
class TouchCalendarView(context: Context, private val onStateRequest: (TouchCalendarMode, LocalDate, LocalDate) -> Unit, private val onSignIn: () -> Unit) : ScrollView(context) {
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var state = TouchCalendarState(TouchCalendarMode.AGENDA, LocalDate.now(), LocalDate.now(), TouchCalendarRange.forMode(TouchCalendarMode.AGENDA, LocalDate.now()), emptyList(), TouchCalendarConnection.OFFLINE, null)
    private var night = false
    init { addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    fun render(next: TouchCalendarState) { state = next; rebuild() }
    fun setNight(value: Boolean) { if (night != value) { night = value; rebuild() } }
    private fun rebuild() {
        val ink = if (night) Color.rgb(241, 238, 230) else Color.rgb(34, 38, 42)
        val paper = if (night) Color.rgb(27, 31, 34) else Color.rgb(247, 244, 237)
        val accent = if (night) Color.rgb(153, 209, 187) else Color.rgb(21, 107, 88)
        setBackgroundColor(paper); body.removeAllViews(); body.setPadding(dp(28), dp(22), dp(28), dp(32))
        body.addView(text("Calendar", 36f, ink, bold = true))
        body.addView(row(listOf("Agenda" to TouchCalendarMode.AGENDA, "Week" to TouchCalendarMode.WEEK, "Month" to TouchCalendarMode.MONTH), ink, accent) { _, mode -> request(requireNotNull(mode), state.anchor, state.selectedDate) })
        body.addView(row(listOf("‹" to null, "Today" to null, "›" to null), ink, accent) { label, _ ->
            when (label) { "‹" -> request(state.mode, previousAnchor(), state.selectedDate); "›" -> request(state.mode, nextAnchor(), state.selectedDate); else -> request(state.mode, LocalDate.now(), LocalDate.now()) }
        })
        body.addView(text(title(), 26f, ink, bold = true))
        if (state.loading) body.addView(text("Updating schedule…", 18f, ink))
        when (state.connection) {
            TouchCalendarConnection.SIGN_IN_REQUIRED -> body.addView(Button(context).apply { text = "Sign in to Home Assistant"; minHeight = dp(56); setOnClickListener { onSignIn() } })
            TouchCalendarConnection.OFFLINE -> body.addView(text(if (state.events.isEmpty()) "Calendar is unavailable." else "Offline · showing the last available schedule.", 18f, ink))
            TouchCalendarConnection.CONNECTED -> if (state.partial) body.addView(text("Some calendars could not be refreshed.", 18f, ink))
        }
        when (state.mode) {
            TouchCalendarMode.AGENDA -> agenda(state.range.start, state.range.endExclusive, ink, accent)
            TouchCalendarMode.WEEK -> week(ink, accent)
            TouchCalendarMode.MONTH -> month(ink, accent)
        }
        body.addView(text("${state.selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))}", 24f, ink, bold = true))
        val selected = state.events.filter { it.occursOn(state.selectedDate, ZoneId.systemDefault()) }
        if (selected.isEmpty()) body.addView(text("No events selected", 18f, ink)) else selected.forEach { event -> body.addView(eventRow(event, ink)) }
    }
    private fun agenda(start: LocalDate, end: LocalDate, ink: Int, accent: Int) { var date = start; while (date < end) { body.addView(dayButton(date, ink, accent)); state.events.filter { it.occursOn(date, ZoneId.systemDefault()) }.forEach { body.addView(eventRow(it, ink)) }; date = date.plusDays(1) } }
    private fun week(ink: Int, accent: Int) { agenda(state.range.start, state.range.endExclusive, ink, accent) }
    private fun month(ink: Int, accent: Int) {
        val headers = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("M","T","W","T","F","S","S").forEach { headers.addView(text(it, 16f, ink, true), LinearLayout.LayoutParams(0, dp(28), 1f)) }; body.addView(headers)
        var date = state.range.start; val weeks = (state.range.days / 7).toInt()
        repeat(weeks) { val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; repeat(7) { val current = date; line.addView(dayCell(current, ink, accent), LinearLayout.LayoutParams(0, dp(64), 1f)); date = date.plusDays(1) }; body.addView(line) }
    }
    private fun dayButton(date: LocalDate, ink: Int, accent: Int) = Button(context).apply { text = date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())) + " · " + state.events.count { it.occursOn(date, ZoneId.systemDefault()) }; minHeight = dp(56); setTextColor(if (date == state.selectedDate) Color.WHITE else ink); setOnClickListener { request(state.mode, state.anchor, date) }; background = background(accent, date == state.selectedDate) }
    private fun dayCell(date: LocalDate, ink: Int, accent: Int) = TextView(context).apply { text = if (state.events.any { it.occursOn(date, ZoneId.systemDefault()) }) "${date.dayOfMonth} •" else date.dayOfMonth.toString(); textSize = 18f; gravity = Gravity.CENTER; setTextColor(if (date == state.selectedDate) Color.WHITE else ink); isClickable = true; contentDescription = date.toString(); background = background(accent, date == state.selectedDate); setOnClickListener { request(state.mode, state.anchor, date) } }
    private fun eventRow(event: TouchCalendarEvent, ink: Int) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); addView(text(event.title, 20f, ink, true)); addView(text(if (event.allDay) "All day" else Instant.ofEpochMilli(event.startEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())), 17f, ink)) }
    private fun row(items: List<Pair<String, TouchCalendarMode?>>, ink: Int, accent: Int, action: (String, TouchCalendarMode?) -> Unit) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; items.forEach { (label, mode) -> addView(Button(context).apply { text = label; minHeight = dp(56); setTextColor(ink); background = background(accent, mode == state.mode); setOnClickListener { action(label, mode) } }, LinearLayout.LayoutParams(0, dp(56), 1f)) } }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply { text = value; textSize = size; setTextColor(color); setPadding(0, dp(8), 0, dp(8)); if (bold) setTypeface(typeface, 1) }
    private fun background(accent: Int, selected: Boolean) = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (selected) accent else Color.TRANSPARENT); setStroke(dp(1), accent) }
    private fun request(mode: TouchCalendarMode, anchor: LocalDate, selected: LocalDate) = onStateRequest(mode, anchor, selected)
    private fun previousAnchor() = when (state.mode) { TouchCalendarMode.AGENDA -> state.anchor.minusDays(7); TouchCalendarMode.WEEK -> state.anchor.minusWeeks(1); TouchCalendarMode.MONTH -> state.anchor.minusMonths(1) }
    private fun nextAnchor() = when (state.mode) { TouchCalendarMode.AGENDA -> state.anchor.plusDays(7); TouchCalendarMode.WEEK -> state.anchor.plusWeeks(1); TouchCalendarMode.MONTH -> state.anchor.plusMonths(1) }
    private fun title() = when (state.mode) { TouchCalendarMode.MONTH -> state.anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())); else -> "${state.range.start} – ${state.range.endExclusive.minusDays(1)}" }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
