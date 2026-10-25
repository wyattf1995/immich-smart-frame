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

    private var renderedDay: LocalDate? = null
    private var renderedTodayKey: List<TouchCalendarWeekPresentation.EventTiming> = emptyList()

    init {
        isFillViewport = true
        // A soft fade at the scroll edges says the list continues (Agenda and long day lists).
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((40 * resources.displayMetrics.density).toInt())
        addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    fun render(next: TouchCalendarState) {
        if (next.selectedDate != state.selectedDate || next.range != state.range || next.events != state.events) selectedDetailsPage = 0
        state = next
        rebuild()
    }
    fun setNight(value: Boolean) { if (night != value) { night = value; rebuild() } }

    /** Called every second by the activity clock: rebuild at midnight and when one of today's events starts or ends. */
    fun tick(nowMillis: Long) {
        val previousDay = renderedDay
        val today = LocalDate.now()
        if (previousDay != null && today != previousDay && state.selectedDate == previousDay) {
            // The frame was showing "today" when the date changed: follow it rather than grey it.
            renderedDay = today
            request(state.mode, today, today, true)
            return
        }
        if (today != renderedDay || todayKey(nowMillis) != renderedTodayKey) rebuild()
    }

    private fun todayKey(nowMillis: Long) = state.events.filter { it.occursOn(LocalDate.now(), ZoneId.systemDefault()) }
        .map { TouchCalendarWeekPresentation.timing(it, nowMillis) }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val wasWide = lastWideHeader ?: return
        if (isWideHeader() != wasWide || TouchScale.forScreen(resources.configuration) != lastScale) post { rebuild() }
    }

    private var lastScale: TouchScale? = null

    private fun rebuild() {
        renderedDay = LocalDate.now()
        renderedTodayKey = todayKey(System.currentTimeMillis())
        val kit = TouchKit(context, TouchPalette.forNight(night), TouchScale.forScreen(resources.configuration))
        lastScale = kit.scale
        val scale = kit.scale
        setBackgroundColor(kit.palette.wall)
        body.removeAllViews()
        val pad = kit.dp(scale.pad)
        body.setPadding(pad, (pad * 0.6f).toInt(), pad, (pad * 0.5f).toInt())
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
            state.connection == TouchCalendarConnection.CONNECTED && state.partial -> banner(kit, com.wyattfleming.frameui.R.drawable.ic_touch_alert,
                "Some calendars couldn’t be refreshed. Their events may be missing.") { refresh() }
        }
        val skeleton = state.loading && noEvents
        when (state.mode) {
            TouchCalendarMode.AGENDA -> agenda(kit, skeleton)
            TouchCalendarMode.WEEK -> {
                val days = TouchCalendarWeekPresentation.days(state.range, state.events, ZoneId.systemDefault())
                week(kit, days, skeleton)
                if (skeleton) detailSkeleton(kit) else selectedDetails(kit, days.firstOrNull { it.date == state.selectedDate }?.events.orEmpty(), wideHeader)
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
        // Signed out, navigation has nothing to load: the controls are drawn disabled instead of looking live.
        val live = state.connection != TouchCalendarConnection.SIGN_IN_REQUIRED
        val segmented = kit.segmented(modes, state.mode, enabled = live, sizeSp = TouchCalendarPresentation.CONTROL_TEXT_SP) { mode ->
            request(mode, state.anchor, state.selectedDate, true)
        }
        val period = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.iconButton(R.drawable.ic_touch_chevron_left, TouchCalendarPresentation.contentDescriptionFor("Previous"), enabled = live) {
                request(state.mode, previousAnchor(), state.selectedDate, true)
            })
            addView(kit.button("Today", enabled = live, sizeSp = TouchCalendarPresentation.CONTROL_TEXT_SP) {
                request(state.mode, LocalDate.now(), LocalDate.now(), true)
            }, LinearLayout.LayoutParams(WRAP, kit.dp(kit.scale.target)).apply { marginStart = kit.dp(12); marginEnd = kit.dp(12) })
            addView(kit.iconButton(R.drawable.ic_touch_chevron_right, TouchCalendarPresentation.contentDescriptionFor("Next"), enabled = live) {
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
        minimumHeight = kit.dp(if (wall) 330 else 228)
        val inset = kit.dp(if (wall) 14 else 10)
        setPadding(inset, inset, inset, inset)
        val selected = day.date == state.selectedDate
        val today = day.date == LocalDate.now()
        val past = TouchCalendarDayStyle.toneFor(day.date, LocalDate.now(), inMonth = true) == TouchCalendarDayTone.PAST
        val fill = when {
            today -> p.todayFill
            past -> p.pastFill
            else -> p.mat
        }
        val base = kit.rounded(fill, kit.scale.radiusSmall, when {
            selected -> p.accentStroke
            past -> p.pastLine
            else -> p.line
        }, if (selected) 3 else 1)
        background = kit.pressable(base, kit.scale.radiusSmall)
        isClickable = true
        isFocusable = true
        isSelected = selected
        contentDescription = "${if (today) "Today, " else if (past) "Past, " else ""}${spoken(day.date)}, ${countText(day.events.size)}"
        setOnClickListener { request(state.mode, state.anchor, day.date, false) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.text(if (today) "Today" else day.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())), if (wall) 22f else 20f,
                if (past) TouchFonts.Face.TEXT_MEDIUM else TouchFonts.Face.TEXT_BOLD, when {
                    today -> p.accentInk
                    past -> p.pastInk
                    else -> p.inkMuted
                }), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(dateBadge(kit, day.date.dayOfMonth.toString(), today, if (wall) 34f else 30f, if (wall) 54 else 46).apply {
                if (past) {
                    setTextColor(p.pastInk)
                    typeface = TouchFonts.get(context, TouchFonts.Face.DISPLAY)
                }
            })
        })
        when {
            skeleton -> repeat(2) { index ->
                addView(View(context).apply { background = kit.rounded(p.line, 8) },
                    LinearLayout.LayoutParams(if (index == 0) MATCH else kit.dp(70), kit.dp(18)).apply { topMargin = kit.dp(14) })
            }
            day.preview.isEmpty() -> addView(kit.text("Free", if (wall) 21f else 19f, color = when {
                today -> p.todayMutedInk
                past -> p.pastInk
                else -> p.inkMuted
            }), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(12) })
            else -> {
                // Today shows what is still ahead: finished events become a count, the current one reads "Now".
                val column = if (today) TouchCalendarWeekPresentation.todayColumn(day, System.currentTimeMillis(), ZoneId.systemDefault())
                    else TouchCalendarWeekPresentation.TodayColumn(0, day.preview, day.overflowCount)
                if (column.earlierCount > 0) addView(kit.text("${column.earlierCount} earlier", if (wall) 20f else 18f, TouchFonts.Face.TEXT_MEDIUM, p.todayMutedInk),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(10) })
                if (column.earlierCount > 0 && column.preview.isEmpty()) addView(kit.text("Nothing else today", if (wall) 21f else 19f,
                    color = p.todayMutedInk), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(8) })
                val limit = if (!wall && bannerShown()) 2 else column.preview.size
                column.preview.take(limit).forEach { preview -> addView(weekEvent(kit, preview, wall, past), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(10) }) }
                val overflow = column.overflowCount + (column.preview.size - limit)
                if (overflow > 0) {
                    addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                    addView(kit.text("$overflow more", if (wall) 21f else 19f, TouchFonts.Face.TEXT_BOLD, if (past) p.pastInk else p.accentInk),
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(8) })
                }
            }
        }
    }

    private fun weekEvent(kit: TouchKit, preview: TouchCalendarWeekPresentation.EventPreview, wall: Boolean, past: Boolean) = LinearLayout(context).apply {
        val p = kit.palette
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val ink = if (past) p.pastInk else p.ink
        if (preview.isTimed) {
            background = kit.startRule(if (past) p.pastInk else p.accentStroke, 4)
            setPadding(kit.dp(10), kit.dp(1), 0, kit.dp(1))
        } else {
            background = if (past) kit.rounded(0x00000000, 8, p.pastLine) else kit.rounded(p.accentSoft, 8)
            setPadding(kit.dp(8), kit.dp(4), kit.dp(8), kit.dp(4))
        }
        addView(kit.text(preview.timeLabel, if (wall) 20f else 18f, if (past) TouchFonts.Face.TEXT_MEDIUM else TouchFonts.Face.TEXT_BOLD,
            if (preview.timeLabel == "Now") p.accentInk else ink))
        addView(kit.text(preview.event.title, if (wall) 22f else 19f, color = ink, maxLines = if (wall) 3 else 2))
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
        // Only whole rows under the top bar: two columns of three on the 1280 panel (two each with a
        // banner, when day columns also trim to two previews), the verified 12 elsewhere.
        val pageSize = when {
            !wide -> TouchCalendarWeekPresentation.DETAILS_PER_PAGE
            kit.scale == TouchScale.WALL -> if (bannerShown()) 8 else 10
            bannerShown() -> 4
            else -> 6
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

    /** Loading placeholders shaped like the selected-day rows, so the lower half is never blank. */
    private fun detailSkeleton(kit: TouchKit) {
        listOf(260, 420, 340).forEachIndexed { index, width ->
            body.addView(View(context).apply {
                background = kit.rounded(kit.palette.line, 8)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(kit.dp(width), kit.dp((kit.scale.body * 0.9f).toInt())).apply {
                topMargin = kit.dp(if (index == 0) 26 else 18)
            })
        }
    }

    private fun eventRow(kit: TouchKit, event: TouchCalendarEvent) = LinearLayout(context).apply {
        val p = kit.palette
        orientation = LinearLayout.VERTICAL
        val timing = if (state.selectedDate.isBefore(LocalDate.now())) TouchCalendarWeekPresentation.EventTiming.ENDED
            else if (state.selectedDate == LocalDate.now()) TouchCalendarWeekPresentation.timing(event, System.currentTimeMillis())
            else TouchCalendarWeekPresentation.EventTiming.UPCOMING
        val ended = timing == TouchCalendarWeekPresentation.EventTiming.ENDED
        val time = if (timing == TouchCalendarWeekPresentation.EventTiming.NOW) "Now" else timeLabel(event)
        contentDescription = "$time, ${event.title}"
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(kit.dp(4), kit.dp(10), kit.dp(4), kit.dp(10))
            addView(kit.text(time, kit.scale.body, if (ended) TouchFonts.Face.TEXT_MEDIUM else TouchFonts.Face.TEXT_BOLD, when {
                ended -> p.pastInk
                timing == TouchCalendarWeekPresentation.EventTiming.NOW -> p.accentInk
                else -> p.ink
            }).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(kit.dp((kit.scale.body * 5.6f).toInt()), WRAP))
            addView(kit.text(event.title, kit.scale.body, color = if (ended) p.pastInk else p.ink).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
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
        val weeks = TouchCalendarRenderPlan.monthWeeks(state.range)
        val cellHeight = TouchCalendarMonthLayout.cellHeightDp(
            viewportHeightDp = if (height > 0) height / resources.displayMetrics.density else 0f,
            weeks = weeks,
            wall = wall,
        )
        repeat(weeks) { weekIndex ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) { index ->
                line.addView(dayCell(kit, date, date.month == month, skeleton, wall), LinearLayout.LayoutParams(0, kit.dp(cellHeight), 1f).apply {
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
        val tone = TouchCalendarDayStyle.toneFor(date, LocalDate.now(), inMonth)
        val inset = if (tone == TouchCalendarDayTone.TODAY) 2 else 0
        setPadding(kit.dp((if (wall) 12 else 10) - inset), kit.dp(6 - inset), kit.dp((if (wall) 12 else 10) - inset), kit.dp(6 - inset))
        val events = state.events.filter { it.occursOn(date, ZoneId.systemDefault()) }
            .sortedWith(compareBy({ !it.allDay }, { it.startEpochMillis }))
        // Past days stay as greyed, disabled-looking panels; today is tinted and outlined; upcoming days are light panels.
        val base = when (tone) {
            TouchCalendarDayTone.TODAY -> kit.rounded(p.todayFill, 8, p.accentStroke, 3)
            TouchCalendarDayTone.PAST -> if (inMonth) kit.rounded(p.pastFill, 8, p.pastLine) else kit.rounded(0x00000000, 8)
            TouchCalendarDayTone.OUTSIDE_MONTH -> kit.rounded(0x00000000, 8)
            TouchCalendarDayTone.UPCOMING -> kit.rounded(p.mat, 8, p.line)
        }
        background = kit.pressable(base, 8)
        isClickable = true
        isFocusable = true
        val prefix = when (tone) {
            TouchCalendarDayTone.TODAY -> "Today, "
            TouchCalendarDayTone.PAST -> "Past, "
            else -> ""
        }
        contentDescription = "$prefix${spoken(date)}, ${countText(events.size)}, open week"
        // A day in Month opens its week with that day's complete, paged list.
        setOnClickListener { request(TouchCalendarMode.WEEK, date, date, true) }
        val receded = tone == TouchCalendarDayTone.PAST || tone == TouchCalendarDayTone.OUTSIDE_MONTH
        val recededInk = if (tone == TouchCalendarDayTone.PAST) p.pastInk else p.inkMuted
        if (tone == TouchCalendarDayTone.TODAY) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dateBadge(kit, date.dayOfMonth.toString(), true, 22f, 32).apply { minWidth = kit.dp(44) })
                addView(kit.text("Today", if (wall) 20f else 18f, TouchFonts.Face.TEXT_BOLD, p.accentInk).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(8) })
            })
        } else {
            addView(kit.text(date.dayOfMonth.toString(), 24f, if (receded) TouchFonts.Face.DISPLAY else TouchFonts.Face.DISPLAY_BOLD,
                if (receded) recededInk else p.ink))
        }
        if (skeleton || !inMonth) return@apply
        val limit = if (wall) 2 else 1
        events.take(limit).forEach { event ->
            addView(kit.text(event.title, if (wall) 20f else 18f, color = if (receded) recededInk else p.ink, maxLines = 1),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(2) })
        }
        if (events.size > limit) addView(kit.text("${events.size - limit} more", if (wall) 20f else 18f, TouchFonts.Face.TEXT_BOLD,
            if (receded) recededInk else p.accentInk))
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
            val past = current.isBefore(LocalDate.now())
            val dayInk = if (past) p.pastInk else p.ink
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
                    addView(kit.text(dayName(current), kit.scale.label, TouchFonts.Face.TEXT_BOLD, when {
                        today -> p.accentInk
                        past -> p.pastInk
                        else -> p.inkMuted
                    }))
                    addView(kit.text(current.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())), kit.scale.headline,
                        if (past) TouchFonts.Face.DISPLAY else TouchFonts.Face.DISPLAY_BOLD, dayInk))
                }, LinearLayout.LayoutParams(if (wide) kit.dp(200) else MATCH, WRAP))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    when {
                        skeleton -> addView(View(context).apply { background = kit.rounded(p.line, 8) }, LinearLayout.LayoutParams(kit.dp(320), kit.dp(20)).apply { topMargin = kit.dp(6) })
                        events.isEmpty() -> addView(kit.text("Free", kit.scale.body, color = if (past) p.pastInk else p.inkMuted))
                        else -> events.forEach { event ->
                            val timing = if (today) TouchCalendarWeekPresentation.timing(event, System.currentTimeMillis()) else null
                            val ended = past || timing == TouchCalendarWeekPresentation.EventTiming.ENDED
                            val ink = if (ended) p.pastInk else p.ink
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                val now = timing == TouchCalendarWeekPresentation.EventTiming.NOW
                                addView(kit.text(if (now) "Now" else timeLabel(event), kit.scale.body, if (ended) TouchFonts.Face.TEXT_MEDIUM else TouchFonts.Face.TEXT_BOLD,
                                    if (now) p.accentInk else ink).apply { fontFeatureSettings = "tnum" },
                                    LinearLayout.LayoutParams(kit.dp((kit.scale.body * 5.6f).toInt()), WRAP))
                                addView(kit.text(event.title, kit.scale.body, color = ink), LinearLayout.LayoutParams(0, WRAP, 1f))
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

    private fun bannerShown() = (state.connection == TouchCalendarConnection.OFFLINE && state.events.isNotEmpty()) ||
        (state.connection == TouchCalendarConnection.CONNECTED && state.partial)

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

/** Month cells shrink so a five- or six-week month fits one glance, within readable bounds. */
internal object TouchCalendarMonthLayout {
    /** Space Month needs besides the grid: padding, header row, weekday row and the tap hint (dp), as measured in the reviewed mockup. */
    const val PANEL_CHROME_DP = 210f
    const val WALL_CHROME_DP = 260f
    const val ROW_GAP_DP = 6f

    fun cellHeightDp(viewportHeightDp: Float, weeks: Int, wall: Boolean): Int {
        val max = if (wall) 140 else 100
        val min = if (wall) 104 else 72
        if (viewportHeightDp <= 0f || weeks <= 0) return max
        val fitted = (viewportHeightDp - (if (wall) WALL_CHROME_DP else PANEL_CHROME_DP)) / weeks - ROW_GAP_DP
        return fitted.toInt().coerceIn(min, max)
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
