package com.wyattfleming.touchframe.calendar

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.wyattfleming.touchframe.R
import com.wyattfleming.touchframe.ui.TouchFonts
import com.wyattfleming.touchframe.ui.TouchKit
import com.wyattfleming.touchframe.ui.TouchPalette
import com.wyattfleming.touchframe.ui.TouchScale
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
    private var lastWideHeader: Boolean? = null
    private var selectedDetailsPage = 0

    init {
        isFillViewport = true
        addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    fun render(next: TouchCalendarState) {
        if (next.selectedDate != state.selectedDate || next.range != state.range || next.events != state.events) selectedDetailsPage = 0
        state = next
        rebuild()
    }
    fun setNight(value: Boolean) { if (night != value) { night = value; rebuild() } }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val wasWide = lastWideHeader ?: return
        if (isWideHeader() != wasWide || (height != oldHeight && scaleFor(height) != scaleFor(oldHeight))) post { rebuild() }
    }

    private fun scaleFor(heightPx: Int) = TouchScale.forContentHeightDp(
        if (heightPx > 0) heightPx / resources.displayMetrics.density else resources.configuration.screenHeightDp.toFloat(),
    )

    private fun rebuild() {
        val kit = TouchKit(context, TouchPalette.forNight(night), scaleFor(height))
        val scale = kit.scale
        setBackgroundColor(kit.palette.wall)
        body.removeAllViews()
        val pad = kit.dp(scale.pad)
        body.setPadding(pad, (pad * 0.8f).toInt(), pad, (pad * 0.7f).toInt())
        val wideHeader = isWideHeader()
        lastWideHeader = wideHeader
        calendarHeader(kit, wideHeader)
        if (state.connection == TouchCalendarConnection.SIGN_IN_REQUIRED) {
            emptyPanel(kit, R.drawable.ic_touch_calendar, context.getString(R.string.touch_calendar_sign_in),
                "Events come from the calendars set up for this frame in Home Assistant.", "Sign in", TouchKit.Emphasis.PRIMARY, onSignIn)
            return
        }
        val noEvents = state.events.isEmpty()
        when {
            state.connection == TouchCalendarConnection.OFFLINE && noEvents && !state.loading -> {
                emptyPanel(kit, R.drawable.ic_touch_offline, "Calendar isn’t available right now",
                    "Home Assistant didn’t answer. Check the frame’s Wi‑Fi, then try again.", "Try again", TouchKit.Emphasis.SECONDARY) { refresh() }
                return
            }
            state.connection == TouchCalendarConnection.OFFLINE -> banner(kit, R.drawable.ic_touch_offline,
                state.updatedEpochMillis?.let { "Can’t reach Home Assistant. Showing the schedule saved at ${time(it)}." }
                    ?: "Can’t reach Home Assistant. Showing the last available schedule.") { refresh() }
            state.connection == TouchCalendarConnection.CONNECTED && state.partial -> banner(kit, R.drawable.ic_touch_alert,
                "Some calendars couldn’t be refreshed. Their events may be missing.") { refresh() }
        }
        val skeleton = state.loading && noEvents
        when (state.mode) {
            TouchCalendarMode.AGENDA -> agenda(kit, skeleton)
            TouchCalendarMode.WEEK -> {
                val days = TouchCalendarWeekPresentation.days(state.range, state.events, ZoneId.systemDefault())
                week(kit, days, skeleton)
                if (!skeleton) selectedDetails(kit, days.firstOrNull { it.date == state.selectedDate }?.events.orEmpty(), wideHeader)
            }
            TouchCalendarMode.MONTH -> month(kit, skeleton)
        }
    }

    private fun calendarHeader(kit: TouchKit, wide: Boolean) {
        val p = kit.palette
        val modes = listOf("Agenda" to TouchCalendarMode.AGENDA, "Week" to TouchCalendarMode.WEEK, "Month" to TouchCalendarMode.MONTH)
        val titleBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.text(title(), kit.scale.title, TouchFonts.Face.DISPLAY_BOLD, maxLines = 1))
            addView(kit.text(freshness(), kit.scale.caption, color = p.inkMuted), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(2) })
        }
        val segmented = kit.segmented(modes, state.mode, enabled = true, sizeSp = TouchCalendarPresentation.CONTROL_TEXT_SP) { mode ->
            request(mode, state.anchor, state.selectedDate, true)
        }
        val period = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.iconButton(R.drawable.ic_touch_chevron_left, TouchCalendarPresentation.contentDescriptionFor("Previous")) {
                request(state.mode, previousAnchor(), state.selectedDate, true)
            })
            addView(kit.button("Today", sizeSp = TouchCalendarPresentation.CONTROL_TEXT_SP) {
                request(state.mode, LocalDate.now(), LocalDate.now(), true)
            }, LinearLayout.LayoutParams(WRAP, kit.dp(kit.scale.target)).apply { marginStart = kit.dp(12); marginEnd = kit.dp(12) })
            addView(kit.iconButton(R.drawable.ic_touch_chevron_right, TouchCalendarPresentation.contentDescriptionFor("Next")) {
                request(state.mode, nextAnchor(), state.selectedDate, true)
            })
        }
        if (wide) {
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleBlock, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = kit.dp(16) })
                addView(segmented, LinearLayout.LayoutParams(WRAP, kit.dp(kit.scale.target)).apply { marginEnd = kit.dp(16) })
                addView(period)
            })
        } else {
            body.addView(titleBlock)
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(segmented, LinearLayout.LayoutParams(0, kit.dp(kit.scale.target), 1f).apply { marginEnd = kit.dp(12) })
                addView(period)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(12) })
        }
    }

    private fun banner(kit: TouchKit, iconRes: Int, message: String, retry: () -> Unit) {
        val p = kit.palette
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = kit.rounded(p.pending, kit.scale.radius)
            minimumHeight = kit.dp(kit.scale.target + 20)
            setPadding(kit.dp(24), kit.dp(10), kit.dp(10), kit.dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(kit.icon(iconRes, p.pendingInk, 32))
            addView(kit.text(message, kit.scale.body, TouchFonts.Face.TEXT_MEDIUM, p.pendingInk), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = kit.dp(18)
                marginEnd = kit.dp(18)
            })
            addView(kit.button("Try again", TouchKit.Emphasis.QUIET, quietInk = p.pendingInk) { retry() })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(16) })
    }

    private fun emptyPanel(kit: TouchKit, iconRes: Int, title: String, detail: String, action: String, emphasis: TouchKit.Emphasis, onAction: () -> Unit) {
        val p = kit.palette
        body.addView(kit.panel().apply {
            gravity = Gravity.CENTER
            minimumHeight = kit.dp(360)
            addView(kit.icon(iconRes, p.inkMuted, 64))
            addView(kit.text(title, kit.scale.headline, TouchFonts.Face.DISPLAY_BOLD).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(16) })
            addView(kit.text(detail, kit.scale.body, color = p.inkMuted).apply { gravity = Gravity.CENTER; maxWidth = kit.dp(680) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(10) })
            addView(kit.button(action, emphasis) { onAction() }, LinearLayout.LayoutParams(WRAP, kit.dp(kit.scale.target)).apply { topMargin = kit.dp(20) })
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(18) })
    }

    private fun week(kit: TouchKit, days: List<TouchCalendarWeekPresentation.Day>, skeleton: Boolean) {
        val wall = kit.scale == TouchScale.WALL
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        days.forEachIndexed { index, day ->
            strip.addView(weekDayColumn(kit, day, skeleton, wall), LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                if (index > 0) marginStart = kit.dp(8)
            })
        }
        body.addView(strip, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(18) })
    }

    private fun weekDayColumn(kit: TouchKit, day: TouchCalendarWeekPresentation.Day, skeleton: Boolean, wall: Boolean) = LinearLayout(context).apply {
        val p = kit.palette
        orientation = LinearLayout.VERTICAL
        minimumHeight = kit.dp(if (wall) 380 else 280)
        val inset = kit.dp(if (wall) 14 else 10)
        setPadding(inset, inset, inset, inset)
        val selected = day.date == state.selectedDate
        val today = day.date == LocalDate.now()
        val base = kit.rounded(p.mat, kit.scale.radiusSmall, if (selected) p.accentStroke else p.line, if (selected) 3 else 1)
        background = kit.pressable(base, kit.scale.radiusSmall)
        isClickable = true
        isFocusable = true
        isSelected = selected
        contentDescription = "${spoken(day.date)}, ${countText(day.events.size)}"
        setOnClickListener { request(state.mode, state.anchor, day.date, false) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.text(day.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())), if (wall) 22f else 20f,
                TouchFonts.Face.TEXT_BOLD, if (today) p.accentInk else p.inkMuted), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(dateBadge(kit, day.date.dayOfMonth.toString(), today, if (wall) 34f else 30f, if (wall) 54 else 46))
        })
        when {
            skeleton -> repeat(2) { index ->
                addView(View(context).apply { background = kit.rounded(p.line, 8) },
                    LinearLayout.LayoutParams(if (index == 0) MATCH else kit.dp(70), kit.dp(18)).apply { topMargin = kit.dp(14) })
            }
            day.preview.isEmpty() -> addView(kit.text("Free", if (wall) 21f else 19f, color = p.inkMuted), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(12) })
            else -> {
                day.preview.forEach { preview -> addView(weekEvent(kit, preview, wall), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(10) }) }
                if (day.overflowCount > 0) {
                    addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                    addView(kit.text("${day.overflowCount} more", if (wall) 21f else 19f, TouchFonts.Face.TEXT_BOLD, p.accentInk),
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(8) })
                }
            }
        }
    }

    private fun weekEvent(kit: TouchKit, preview: TouchCalendarWeekPresentation.EventPreview, wall: Boolean) = LinearLayout(context).apply {
        val p = kit.palette
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (preview.isTimed) {
            background = kit.startRule(p.accentStroke, 4)
            setPadding(kit.dp(10), kit.dp(1), 0, kit.dp(1))
        } else {
            background = kit.rounded(p.accentSoft, 8)
            setPadding(kit.dp(8), kit.dp(4), kit.dp(8), kit.dp(4))
        }
        addView(kit.text(preview.timeLabel, if (wall) 20f else 18f, TouchFonts.Face.TEXT_BOLD))
        addView(kit.text(preview.event.title, if (wall) 22f else 19f, maxLines = if (wall) 3 else 2))
    }

    private fun dateBadge(kit: TouchKit, text: String, today: Boolean, sizeSp: Float, diameterDp: Int) = kit.text(text, sizeSp, TouchFonts.Face.DISPLAY_BOLD,
        if (today) kit.palette.onAccent else kit.palette.ink).apply {
        gravity = Gravity.CENTER
        minWidth = kit.dp(diameterDp)
        minHeight = kit.dp(diameterDp)
        if (today) background = kit.rounded(kit.palette.accent, diameterDp / 2, kit.palette.accentStroke, 2)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun selectedDetails(kit: TouchKit, events: List<TouchCalendarEvent>, wide: Boolean) {
        val p = kit.palette
        // Only whole rows: two columns of four on the 1280 panel (three with a banner), the verified 12 elsewhere.
        val banner = state.connection == TouchCalendarConnection.OFFLINE || state.partial
        val pageSize = when {
            !wide -> TouchCalendarWeekPresentation.DETAILS_PER_PAGE
            kit.scale == TouchScale.WALL -> TouchCalendarWeekPresentation.DETAILS_PER_PAGE
            banner -> 6
            else -> 8
        }
        val page = TouchCalendarWeekPresentation.detailsPage(events, selectedDetailsPage, pageSize)
        selectedDetailsPage = page.page
        val heading = if (state.selectedDate == LocalDate.now()) "Today" else
            state.selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.text(heading, kit.scale.headline, TouchFonts.Face.DISPLAY_BOLD, maxLines = 1))
            addView(kit.text(if (page.totalEvents == 0) "" else countText(page.totalEvents), kit.scale.label, color = p.inkMuted),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(14) })
            if (page.pageCount > 1) {
                addView(kit.text("${page.firstEventNumber}–${page.lastEventNumber} of ${page.totalEvents}", kit.scale.label, TouchFonts.Face.TEXT_MEDIUM, p.inkMuted))
                addView(kit.iconButton(R.drawable.ic_touch_chevron_left, "Earlier events", enabled = page.page > 0) {
                    selectedDetailsPage = (selectedDetailsPage - 1).coerceAtLeast(0)
                    rebuild()
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(14) })
                addView(kit.iconButton(R.drawable.ic_touch_chevron_right, "Later events", enabled = page.page < page.pageCount - 1) {
                    selectedDetailsPage = (selectedDetailsPage + 1).coerceAtMost(page.pageCount - 1)
                    rebuild()
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(12) })
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(22) })
        if (page.events.isEmpty()) {
            body.addView(kit.text("Nothing scheduled.", kit.scale.body, color = p.inkMuted), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(12) })
            return
        }
        val rows = page.events.map { eventRow(kit, it) }
        if (wide && rows.size > 1) {
            // Column-major: read down the first column, then the second, so times never zigzag.
            val split = (rows.size + 1) / 2
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(rows.take(split), rows.drop(split)).forEachIndexed { index, column ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        column.forEach { addView(it) }
                    }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (index > 0) marginStart = kit.dp(kit.scale.gap) })
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(6) })
        } else {
            rows.forEach { body.addView(it) }
        }
    }

    private fun eventRow(kit: TouchKit, event: TouchCalendarEvent) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val time = timeLabel(event)
        contentDescription = "$time, ${event.title}"
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(kit.dp(4), kit.dp(10), kit.dp(4), kit.dp(10))
            addView(kit.text(time, kit.scale.body, TouchFonts.Face.TEXT_BOLD).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(kit.dp((kit.scale.body * 5.6f).toInt()), WRAP))
            addView(kit.text(event.title, kit.scale.body).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        addView(kit.hairline(), LinearLayout.LayoutParams(MATCH, kit.dp(1)))
    }

    private fun month(kit: TouchKit, skeleton: Boolean) {
        val p = kit.palette
        val wall = kit.scale == TouchScale.WALL
        val headers = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
            headers.addView(kit.text(it, TouchCalendarPresentation.WEEKDAY_HEADER_TEXT_SP, TouchFonts.Face.TEXT_BOLD, p.inkMuted).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setPadding(kit.dp(10), 0, 0, kit.dp(6))
                minHeight = kit.dp(TouchCalendarPresentation.WEEKDAY_HEADER_MIN_HEIGHT_DP)
            }, LinearLayout.LayoutParams(0, kit.dp(TouchCalendarPresentation.WEEKDAY_HEADER_MIN_HEIGHT_DP), 1f))
        }
        body.addView(headers, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(10) })
        var date = state.range.start
        val month = state.anchor.month
        repeat(TouchCalendarRenderPlan.monthWeeks(state.range)) { weekIndex ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) { index ->
                line.addView(dayCell(kit, date, date.month == month, skeleton, wall), LinearLayout.LayoutParams(0, kit.dp(if (wall) 160 else 112), 1f).apply {
                    if (index > 0) marginStart = kit.dp(6)
                })
                date = date.plusDays(1)
            }
            body.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (weekIndex > 0) topMargin = kit.dp(6) })
        }
        body.addView(kit.text("Tap a day to open that week with the day’s full list.", kit.scale.caption, color = p.inkMuted),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(12) })
    }

    private fun dayCell(kit: TouchKit, date: LocalDate, inMonth: Boolean, skeleton: Boolean, wall: Boolean) = LinearLayout(context).apply {
        val p = kit.palette
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(if (wall) 12 else 10), kit.dp(6), kit.dp(if (wall) 12 else 10), kit.dp(6))
        val events = state.events.filter { it.occursOn(date, ZoneId.systemDefault()) }
            .sortedWith(compareBy({ !it.allDay }, { it.startEpochMillis }))
        background = kit.pressable(kit.rounded(if (inMonth) p.mat else 0x00000000, 8, p.line), 8)
        isClickable = true
        isFocusable = true
        contentDescription = "${spoken(date)}, ${countText(events.size)}, open week"
        // A day in Month opens its week with that day's complete, paged list.
        setOnClickListener { request(TouchCalendarMode.WEEK, date, date, true) }
        val today = date == LocalDate.now()
        addView(if (today) dateBadge(kit, date.dayOfMonth.toString(), true, 22f, 32).apply { minWidth = kit.dp(44) }
            else kit.text(date.dayOfMonth.toString(), 24f, if (inMonth) TouchFonts.Face.DISPLAY_BOLD else TouchFonts.Face.DISPLAY, if (inMonth) p.ink else p.inkMuted))
        if (skeleton || !inMonth) return@apply
        val limit = if (wall) 2 else 1
        events.take(limit).forEach { event ->
            addView(kit.text(event.title, if (wall) 20f else 18f, maxLines = 1), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(2) })
        }
        if (events.size > limit) addView(kit.text("${events.size - limit} more", if (wall) 20f else 18f, TouchFonts.Face.TEXT_BOLD, p.accentInk))
    }

    private fun agenda(kit: TouchKit, skeleton: Boolean) {
        val p = kit.palette
        val wide = isWideHeader()
        var date = state.range.start
        while (date < state.range.endExclusive) {
            val current = date
            val events = state.events.filter { it.occursOn(current, ZoneId.systemDefault()) }
                .sortedWith(compareBy({ !it.allDay }, { it.startEpochMillis }, { it.title }))
            val today = current == LocalDate.now()
            body.addView(LinearLayout(context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                setPadding(0, kit.dp(14), 0, kit.dp(14))
                isClickable = true
                isFocusable = true
                contentDescription = "${spoken(current)}, ${countText(events.size)}, open week"
                background = kit.pressable(android.graphics.drawable.ColorDrawable(0x00000000), 8)
                setOnClickListener { request(TouchCalendarMode.WEEK, current, current, true) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    if (today) {
                        background = kit.startRule(p.accentStroke, 4)
                        setPadding(kit.dp(14), 0, 0, 0)
                    }
                    addView(kit.text(dayName(current), kit.scale.label, TouchFonts.Face.TEXT_BOLD, if (today) p.accentInk else p.inkMuted))
                    addView(kit.text(current.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())), kit.scale.headline, TouchFonts.Face.DISPLAY_BOLD))
                }, LinearLayout.LayoutParams(if (wide) kit.dp(200) else MATCH, WRAP))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    when {
                        skeleton -> addView(View(context).apply { background = kit.rounded(p.line, 8) }, LinearLayout.LayoutParams(kit.dp(320), kit.dp(20)).apply { topMargin = kit.dp(6) })
                        events.isEmpty() -> addView(kit.text("Free", kit.scale.body, color = p.inkMuted))
                        else -> events.forEach { event ->
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                addView(kit.text(timeLabel(event), kit.scale.body, TouchFonts.Face.TEXT_BOLD).apply { fontFeatureSettings = "tnum" },
                                    LinearLayout.LayoutParams(kit.dp((kit.scale.body * 5.6f).toInt()), WRAP))
                                addView(kit.text(event.title, kit.scale.body), LinearLayout.LayoutParams(0, WRAP, 1f))
                            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = kit.dp(8) })
                        }
                    }
                }, LinearLayout.LayoutParams(if (wide) 0 else MATCH, WRAP, if (wide) 1f else 0f).apply {
                    if (wide) marginStart = kit.dp(kit.scale.gap) else topMargin = kit.dp(8)
                })
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            body.addView(kit.hairline(), LinearLayout.LayoutParams(MATCH, kit.dp(1)))
            date = date.plusDays(1)
        }
    }

    private fun refresh() = request(state.mode, state.anchor, state.selectedDate, true)
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
        else -> weekRangeTitle()
    }
    private fun weekRangeTitle(): String {
        val locale = Locale.getDefault()
        val start = state.range.start
        val end = state.range.endExclusive.minusDays(1)
        return when {
            start.year != end.year -> "${start.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))} – ${end.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))}"
            start.month != end.month -> "${start.format(DateTimeFormatter.ofPattern("MMMM d", locale))} – ${end.format(DateTimeFormatter.ofPattern("MMMM d", locale))}"
            else -> "${start.format(DateTimeFormatter.ofPattern("MMMM d", locale))} – ${end.dayOfMonth}"
        }
    }
    private fun freshness(): String = when {
        state.loading && state.events.isEmpty() -> "Loading events…"
        state.connection == TouchCalendarConnection.OFFLINE -> state.updatedEpochMillis?.let { "Saved ${time(it)}" } ?: "Not updated yet"
        state.loading -> state.updatedEpochMillis?.let { "Refreshing. Updated ${time(it)}" } ?: "Refreshing…"
        else -> state.updatedEpochMillis?.let { "Updated ${time(it)}" } ?: "Not updated yet"
    }
    private fun dayName(date: LocalDate): String = when (date) {
        LocalDate.now() -> "Today"
        LocalDate.now().plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
    }
    private fun spoken(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    private fun countText(count: Int) = when (count) {
        0 -> "no events"
        1 -> "1 event"
        else -> "$count events"
    }
    private fun timeLabel(event: TouchCalendarEvent) = if (event.allDay) "All day" else time(event.startEpochMillis)
    private fun time(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a", Locale.getDefault()))
    private fun isWideHeader(): Boolean {
        val available = if (width > 0) width else resources.displayMetrics.widthPixels
        return available >= (900 * resources.displayMetrics.density + .5f).toInt()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
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
