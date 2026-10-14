package com.wyattfleming.touchframe.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wyattfleming.touchframe.R
import com.wyattfleming.touchframe.home.TouchClimate
import com.wyattfleming.touchframe.home.TouchClimateDraft
import com.wyattfleming.touchframe.home.TouchConnection
import com.wyattfleming.touchframe.home.TouchHomeAgenda
import com.wyattfleming.touchframe.home.TouchHomeModel
import com.wyattfleming.touchframe.home.TouchTodoBoard
import com.wyattfleming.touchframe.home.TouchTodoDiscovery
import com.wyattfleming.touchframe.home.TouchTodoItem
import com.wyattfleming.touchframe.home.TouchTodoListRef
import com.wyattfleming.touchframe.home.TouchTodoPresentation
import com.wyattfleming.touchframe.home.TouchTodoSelection
import com.wyattfleming.touchframe.home.TouchTodoState
import com.wyattfleming.touchframe.home.TouchWeatherLabel
import com.wyattfleming.touchframe.ocean.TouchOceanGlance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Every Home Assistant call behind the To do column belongs to the activity; the view only asks. */
internal interface TouchHomeTodoActions {
    fun chooseLists()
    fun saveLists(selection: TouchTodoSelection)
    fun complete(item: TouchTodoItem)
    fun add(list: TouchTodoListRef, summary: String)
}

/**
 * Native Home: the photo of the day with the clock on it, three glances (weather, thermostat,
 * ocean), the household To do list and Coming up. The activity supplies all data; this view makes
 * no Home Assistant or web calls.
 *
 * Structure is built once per layout class/day-night style; data updates refill only the slot
 * whose inputs changed, so a background refresh never interrupts a press on another panel.
 * The thermostat controls, full lists, adding an item and choosing lists open as sheets over Home.
 */
class TouchHomeView private constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    private val onNavigate: (String) -> Unit,
    private val onSignIn: () -> Unit,
    private val onClimateMode: (String) -> Unit,
    private val onClimateTarget: (Double) -> Unit,
) : FrameLayout(context, attrs, defStyleAttr) {
    constructor(context: Context) : this(context, null, 0, {}, {}, {}, {})

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0, {}, {}, {}, {})

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        this(context, attrs, defStyleAttr, {}, {}, {}, {})

    constructor(
        context: Context,
        onNavigate: (String) -> Unit,
        onSignIn: () -> Unit,
        onClimateMode: (String) -> Unit,
        onClimateTarget: (Double) -> Unit,
    ) : this(context, null, 0, onNavigate, onSignIn, onClimateMode, onClimateTarget)

    /** Retries the Home refresh from the offline banner. */
    var onRetry: (() -> Unit)? = null

    internal var todoActions: TouchHomeTodoActions? = null

    private var model: TouchHomeModel? = null
    private var destinations: List<TouchDestination> = TouchDestinationOrder.defaults
    private var nowMillis: Long = System.currentTimeMillis()
    private var night = TouchDayNight.isNight(hourAt(nowMillis))
    private var scale = TouchScale.PANEL
    private var locationLabel: String? = null
    private val draft = TouchClimateDraft()
    private var climateBusy = false
    private var climateResult: ClimateResult? = null
    private var todo = TouchTodoBoard.notChosen()
    /** Checked but not sent: Undo is offered until the delay runs out, then the item is completed. */
    private val pendingDone = LinkedHashMap<String, Pair<TouchTodoItem, Runnable>>()
    /** Sent to Home Assistant; hidden until a refresh no longer lists it, or shown again on failure. */
    private val committing = HashMap<String, Long>()
    private var photo: Bitmap? = null
    private var ocean: TouchOceanGlance? = null
    private var oceanLoading = true
    private var sheet = Sheet.NONE
    private var discovery: TouchTodoDiscovery? = null
    private val pickerChoice = LinkedHashMap<String, Role>()
    private var sheetList: TouchTodoListRef? = null
    private var clockView: TextView? = null
    private var suffixView: TextView? = null
    private var dateView: TextView? = null
    private var captionView: TextView? = null
    private var photoView: ImageView? = null
    private var glanceSlot: FrameLayout? = null
    private var bannerSlot: FrameLayout? = null
    private var todoSlot: FrameLayout? = null
    private var comingSlot: FrameLayout? = null
    private var columnRule: View? = null
    private var sheetLayer: FrameLayout? = null
    private var sheetSlot: FrameLayout? = null
    private var renderedKeys = HashMap<String, Any?>()
    private var renderedDay: java.time.LocalDate? = null
    private var scrollView: ScrollView? = null
    private var scrollPosition = 0
    private val layoutTransition = TouchHomeLayoutTransition()
    private var layoutRebuildPosted = false

    private enum class ClimateResult { CONFIRMED, FAILED }
    private enum class Sheet { NONE, THERMOSTAT, TASKS, GROCERIES, ADD, PICKER }
    private enum class Role(val label: String) { HIDE("Hide"), TASKS("To do"), GROCERIES("Groceries") }

    init {
        isFocusable = true
        rebuild()
    }

    /** Remote mode order decides which panels may open their destination; Home remains first. */
    fun setDestinations(remoteOrder: List<TouchDestination>) {
        val normalized = TouchDestinationOrder.normalize(remoteOrder)
        if (destinations == normalized) return
        destinations = normalized
        refillSlots(force = true)
    }

    /** Optional configured place name shown under the weather glance (display text only). */
    fun setWeatherLocation(label: String?) {
        if (locationLabel == label) return
        locationLabel = label
        refillSlots()
    }

    /** Renders the newest caller-owned snapshot without clearing an active draft. */
    fun render(model: TouchHomeModel) {
        if (this.model == model) return
        val previousEntity = this.model?.climate?.entityId
        this.model = model
        draft.onRefreshed(previousEntity, model.climate)
        refillSlots()
    }

    /** The lists' newest state. An item sent as done stays hidden until Home Assistant stops listing it. */
    internal fun renderTodo(board: TouchTodoBoard) {
        val listed = (board.tasks + board.groceries).map(TouchTodoItem::key).toSet()
        val now = System.currentTimeMillis()
        if (board.state == TouchTodoState.READY) committing.entries.removeAll { (key, sentAt) -> key !in listed || now - sentAt > COMMIT_HIDE_MILLIS }
        if (board.selection != todo.selection) {
            pendingDone.values.forEach { removeCallbacks(it.second) }
            pendingDone.clear()
        }
        todo = board
        refillSlots()
    }

    /** Home Assistant refused or never answered: the item comes back so it can be checked again. */
    internal fun onTodoCompleteFailed(item: TouchTodoItem) {
        committing.remove(item.key)
        refillSlots()
    }

    /** The day's photo, decoded by the caller; null shows the plain panel. The previous bitmap is released after the swap. */
    fun setPhoto(bitmap: Bitmap?) {
        if (photo === bitmap) return
        val previous = photo
        photo = bitmap
        photoView?.setImageBitmap(bitmap)
        if (previous != null && previous !== bitmap && !previous.isRecycled) previous.recycle()
    }

    /** Pixel size the photo panel shows, for sampling the decode; zero until laid out. */
    fun photoTargetSize(): Pair<Int, Int> = photoView?.let { it.width to it.height } ?: (0 to 0)

    internal fun setOcean(glance: TouchOceanGlance?, loading: Boolean) {
        if (ocean == glance && oceanLoading == loading) return
        ocean = glance
        oceanLoading = loading
        refillSlots()
    }

    /** Opens the list chooser with what Home Assistant reported; a null discovery shows "Finding your lists". */
    internal fun showListPicker(found: TouchTodoDiscovery?) {
        discovery = found
        if (sheet != Sheet.PICKER) {
            pickerChoice.clear()
            todo.selection.taskLists.forEach { pickerChoice[it.entityId] = Role.TASKS }
            todo.selection.groceries?.let { pickerChoice[it.entityId] = Role.GROCERIES }
        }
        openSheet(Sheet.PICKER)
    }

    /** Fills the chooser once Home Assistant answers; ignored if it was closed meanwhile. */
    internal fun updateListPicker(found: TouchTodoDiscovery) {
        if (sheet != Sheet.PICKER) return
        discovery = found
        refillSlots()
    }

    /** Back closes an open sheet first. */
    fun closeSheet(): Boolean {
        if (sheet == Sheet.NONE) return false
        hideKeyboard()
        sheet = Sheet.NONE
        discovery = null
        sheetList = null
        refillSlots()
        return true
    }

    /** Lets the activity reflect a real command in flight without inventing an outcome. */
    fun setClimateBusy(busy: Boolean) {
        if (climateBusy == busy) return
        climateBusy = busy
        if (busy) climateResult = null
        refillSlots()
    }

    /** A confirmed command returns Home to Home Assistant's value; a failure keeps the draft to retry. */
    fun onClimateCommandFinished(confirmed: Boolean) {
        climateBusy = false
        climateResult = if (confirmed) ClimateResult.CONFIRMED else ClimateResult.FAILED
        draft.onCommandFinished(confirmed, model?.climate)
        removeCallbacks(revertDraft)
        removeCallbacks(settleResult)
        if (confirmed) postDelayed(settleResult, CONFIRMATION_MILLIS)
        refillSlots()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(revertDraft)
        removeCallbacks(settleResult)
        // A check that was still waiting for Undo is sent rather than silently forgotten.
        pendingDone.values.toList().forEach { (item, runnable) -> removeCallbacks(runnable); commit(item) }
        super.onDetachedFromWindow()
    }

    /** Updates the retained clock nodes; only a day/night transition rebuilds the structure. */
    fun tick(nowMillis: Long) {
        this.nowMillis = nowMillis
        val nextNight = TouchDayNight.isNight(hourAt(nowMillis))
        if (nextNight != night) {
            night = nextNight
            rebuild()
        } else {
            clockView?.text = timeText()
            suffixView?.text = suffixText()
            dateView?.text = dateText()
            if (dayAt(nowMillis) != renderedDay) refillSlots()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        requestLayoutClass(isLargeLandscape())
        if (TouchScale.forScreen(resources.configuration) != scale) post { if (TouchScale.forScreen(resources.configuration) != scale) rebuild() }
    }

    /** Defers a consumed Activity configuration change until the content has a layout pass. */
    fun refreshLayoutForConfiguration(configuration: Configuration) {
        // Top navigation takes no width; it takes the bar's height from the content instead.
        val contentWidthDp = TouchHomeLayout.contentWidthDp(configuration.screenWidthDp.toFloat(), 0f)
        val contentHeightDp = configuration.screenHeightDp - TouchShellLayout.barHeightDpFor(configuration.screenWidthDp)
        requestLayoutClass(TouchHomeLayout.isLargeLandscape(contentWidthDp, contentHeightDp.toFloat()))
        requestLayout()
    }

    private fun requestLayoutClass(large: Boolean) {
        if (!layoutTransition.request(large) || layoutRebuildPosted) return
        layoutRebuildPosted = true
        post {
            layoutRebuildPosted = false
            layoutTransition.consume()?.let(::rebuild)
        }
    }

    private fun kit() = TouchKit(context, TouchPalette.forNight(night), scale)

    private val wall get() = scale == TouchScale.WALL

    private fun rebuild(large: Boolean = isLargeLandscape()) {
        layoutTransition.markRendered(large)
        scale = TouchScale.forScreen(resources.configuration)
        scrollPosition = scrollView?.scrollY ?: scrollPosition
        hideKeyboard()
        removeAllViews()
        renderedKeys = HashMap()
        val kit = kit()
        setBackgroundColor(kit.palette.wall)
        if (large) addView(landscape(kit), LayoutParams(MATCH, MATCH)) else addView(compact(kit), LayoutParams(MATCH, MATCH))
        val layer = FrameLayout(context).apply {
            setBackgroundColor(kit.palette.scrim)
            visibility = View.GONE
            isClickable = true
            contentDescription = "Close"
            setOnClickListener { closeSheet() }
        }
        val slot = FrameLayout(context).apply { isClickable = true }
        layer.addView(slot, LayoutParams(WRAP, WRAP, Gravity.CENTER))
        sheetLayer = layer
        sheetSlot = slot
        addView(layer, LayoutParams(MATCH, MATCH))
        refillSlots(force = true)
    }

    /** Both frames: the photo panel on the left, the board on the right. */
    private fun landscape(kit: TouchKit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        scrollView = null
        addView(photoPanel(kit), LinearLayout.LayoutParams(kit.dp(if (wall) PHOTO_WALL_DP else PHOTO_PANEL_DP), MATCH))
        addView(board(kit), LinearLayout.LayoutParams(0, MATCH, 1f))
    }

    /** Narrow or portrait content (not either frame): no photo, the board scrolls. */
    private fun compact(kit: TouchKit): View = ScrollView(context).apply {
        isFillViewport = true
        scrollView = this
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.palette.mat)
        }
        column.addView(clockBlock(kit, onPhoto = false).apply {
            setPadding(kit.dp(scale.pad), kit.dp(scale.gap), kit.dp(scale.pad), 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(board(kit, scrolling = true), LinearLayout.LayoutParams(MATCH, WRAP))
        addView(column, LayoutParams(MATCH, WRAP))
        post { scrollTo(0, scrollPosition.coerceAtLeast(0)) }
    }

    private fun photoPanel(kit: TouchKit): View = FrameLayout(context).apply {
        val p = kit.palette
        setBackgroundColor(p.letterbox)
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Night keeps the photo but dims and warms it to the amber palette so the wall never glows.
            if (night) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0.35f)
                postConcat(ColorMatrix(floatArrayOf(0.3f, 0f, 0f, 0f, 0f, 0f, 0.25f, 0f, 0f, 0f, 0f, 0f, 0.17f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            })
            photo?.takeIf { !it.isRecycled }?.let { setImageBitmap(it) }
        }
        photoView = image
        addView(image, LayoutParams(MATCH, MATCH))
        // Shading under the clock and the status line keeps both legible on any photo.
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                if (night) intArrayOf(0xE0000000.toInt(), 0x66000000, 0x00000000, 0x00000000)
                else intArrayOf(0xC70A0C0B.toInt(), 0x700A0C0B, 0x000A0C0B, 0x000A0C0B))
        }, LayoutParams(MATCH, MATCH))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x730A0C0B, 0x000A0C0B))
        }, LayoutParams(MATCH, kit.dp(140), Gravity.TOP))
        val caption = kit.text("", scale.caption, TouchFonts.Face.TEXT_MEDIUM, if (night) p.inkMuted else 0xE0FFFFFF.toInt(), maxLines = 1)
        captionView = caption
        addView(caption, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            setMargins(kit.dp(if (wall) 32 else 20), kit.dp(if (wall) 24 else 16), kit.dp(24), 0)
        })
        addView(clockBlock(kit, onPhoto = true), LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(kit.dp(if (wall) 44 else 28), 0, kit.dp(24), kit.dp(if (wall) 40 else 26))
        })
    }

    private fun clockBlock(kit: TouchKit, onPhoto: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = kit.palette
        val ink = if (onPhoto && !night) 0xFFFFFFFF.toInt() else p.ink
        val time = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            val clockText = kit.text(timeText(), scale.clock, TouchFonts.Face.DISPLAY, ink).apply {
                letterSpacing = -0.02f
                fontFeatureSettings = "tnum"
            }
            val suffix = kit.text(suffixText(), scale.clockSuffix, TouchFonts.Face.TEXT_BOLD, if (onPhoto && !night) ink else p.inkMuted)
            clockView = clockText
            suffixView = suffix
            addView(clockText)
            addView(suffix, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(14) })
        }
        addView(time)
        val date = kit.text(dateText(), scale.headline, TouchFonts.Face.TEXT_MEDIUM, if (onPhoto && !night) 0xEBFFFFFF.toInt() else p.inkMuted)
        dateView = date
        addView(date, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(10) })
    }

    private fun board(kit: TouchKit, scrolling: Boolean = false): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(kit.palette.mat)
        if (wall) setPadding(kit.dp(48), kit.dp(40), kit.dp(48), kit.dp(36)) else setPadding(kit.dp(28), kit.dp(24), kit.dp(28), kit.dp(20))
        glanceSlot = FrameLayout(context).also { addView(it, LinearLayout.LayoutParams(MATCH, kit.dp(if (wall) 132 else 100))) }
        bannerSlot = FrameLayout(context).also { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        val columns = LinearLayout(context).apply { orientation = if (scrolling) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val todoFrame = FrameLayout(context).apply {
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) post { refillSlots() }
            }
        }
        val comingFrame = FrameLayout(context).apply {
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) post { refillSlots() }
            }
        }
        val rule = View(context).apply { setBackgroundColor(kit.palette.line) }
        todoSlot = todoFrame
        comingSlot = comingFrame
        columnRule = rule
        val gap = kit.dp(if (wall) 48 else 28) / 2
        if (scrolling) {
            columns.addView(todoFrame, LinearLayout.LayoutParams(MATCH, WRAP))
            columns.addView(rule, LinearLayout.LayoutParams(MATCH, kit.dp(1)).apply { setMargins(0, gap, 0, gap) })
            columns.addView(comingFrame, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(columns, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(scale.gap) })
        } else {
            columns.addView(todoFrame, LinearLayout.LayoutParams(0, MATCH, 1f))
            columns.addView(rule, LinearLayout.LayoutParams(kit.dp(1), MATCH).apply { setMargins(gap, 0, gap, 0) })
            columns.addView(comingFrame, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(columns, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(if (wall) 40 else 24) })
        }
    }

    /** Refills only the slots whose visible inputs changed. */
    private fun refillSlots(force: Boolean = false) {
        val kit = kit()
        val snapshot = model
        val connection = snapshot?.connection
        val today = dayAt(nowMillis)
        renderedDay = today
        val signIn = connection == TouchConnection.SIGN_IN_REQUIRED
        captionView?.text = statusText(snapshot)
        val lines = comingLineBudget(kit)
        val rows = todoRowBudget(kit)
        fill("glance", listOf(snapshot?.weatherText, snapshot?.temperatureText, snapshot?.climate, connection, snapshot?.lastUpdatedEpochMillis,
            locationLabel, destinations, ocean, oceanLoading), force, glanceSlot) { glances(kit, snapshot) }
        fill("banner", listOf(connection, snapshot?.lastUpdatedEpochMillis), force, bannerSlot) { banner(kit, snapshot) }
        fill("todo", listOf(todo, pendingDone.keys.toList(), committing.keys.toSet(), today, signIn, rows), force, todoSlot) {
            if (signIn) connectPanel(kit) else todoColumn(kit, rows)
        }
        fill("coming", listOf(snapshot?.events, connection, today, destinations, lines, signIn), force, comingSlot) {
            if (signIn) null else coming(kit, snapshot, lines)
        }
        comingSlot?.visibility = if (signIn) View.GONE else View.VISIBLE
        columnRule?.visibility = if (signIn) View.GONE else View.VISIBLE
        val sheetInputs: Any? = when (sheet) {
            Sheet.NONE -> null
            Sheet.THERMOSTAT -> listOf(snapshot?.climate, connection, climateBusy, climateResult, draft.target, draft.dirty)
            Sheet.TASKS, Sheet.GROCERIES -> listOf(todo, pendingDone.keys.toList(), committing.keys.toSet(), today)
            // Typed text lives in the field; only reopening rebuilds it.
            Sheet.ADD -> sheetList
            Sheet.PICKER -> listOf(discovery, pickerChoice.toMap())
        }
        fill("sheet", listOf(sheet, sheetInputs, sheetList), force, sheetSlot) { sheetContent(kit, snapshot) }
        sheetLayer?.visibility = if (sheet == Sheet.NONE) View.GONE else View.VISIBLE
    }

    /** Lines that fit in Coming up; before the first layout pass, a conservative default. */
    private fun comingLineBudget(kit: TouchKit): Int {
        val slotHeight = comingSlot?.height ?: 0
        if (slotHeight <= 0 || scrollView != null) return if (wall) 11 else 8
        val header = kit.dp(HEADER_DP)
        val overflow = kit.dp(if (wall) 64 else 56)
        val line = kit.dp((scale.body * 1.25f + 12).toInt())
        return ((slotHeight - header - overflow) / line).coerceAtLeast(2)
    }

    /** Task rows that fit above the Groceries line; one is kept for "N more" when needed. */
    private fun todoRowBudget(kit: TouchKit): Int {
        val slotHeight = todoSlot?.height ?: 0
        if (slotHeight <= 0 || scrollView != null) return if (wall) 6 else 4
        val groceries = if (todo.selection.groceries != null) kit.dp(if (wall) 124 else 92) else 0
        return ((slotHeight - kit.dp(HEADER_DP + 10) - groceries) / kit.dp(rowHeightDp())).coerceIn(1, 12)
    }

    private fun rowHeightDp() = if (wall) 76 else 62

    private fun fill(key: String, inputs: Any?, force: Boolean, slot: FrameLayout?, build: () -> View?) {
        slot ?: return
        if (!force && renderedKeys.containsKey(key) && renderedKeys[key] == inputs) return
        renderedKeys[key] = inputs
        slot.removeAllViews()
        val view = build() ?: return
        // A sheet sizes itself; columns fill their slot unless the compact layout scrolls.
        if (slot === sheetSlot) slot.addView(view) else slot.addView(view, LayoutParams(MATCH, if (scrollView != null) WRAP else MATCH))
    }

    // ---------- Glances ----------

    private fun glances(kit: TouchKit, snapshot: TouchHomeModel?): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val gap = kit.dp(if (wall) 24 else 16)
        val tiles = listOfNotNull(weatherTile(kit, snapshot) to 1.05f, thermostatTile(kit, snapshot) to 1f,
            (if (TouchDestination.OCEAN in destinations) oceanTile(kit) to 1.15f else null))
        tiles.forEachIndexed { index, (tile, weight) ->
            addView(tile, LinearLayout.LayoutParams(0, MATCH, weight).apply { if (index > 0) marginStart = gap })
        }
    }

    private fun weatherTile(kit: TouchKit, snapshot: TouchHomeModel?): View {
        val weatherState = snapshot?.weatherText.orEmpty()
        val condition = TouchWeatherLabel.condition(weatherState)
        val temperature = snapshot?.temperatureText?.takeIf { it.isNotBlank() && !it.equals("Unavailable", true) }
        val signIn = snapshot?.connection == TouchConnection.SIGN_IN_REQUIRED && condition == null
        val sub = when {
            snapshot?.connection == TouchConnection.OFFLINE -> snapshot.lastUpdatedEpochMillis?.let { "As of ${timeAt(it)}" }
            signIn -> "Sign in to see it"
            condition == null && snapshot != null && snapshot.connection != TouchConnection.REFRESHING -> "Unavailable"
            else -> locationLabel
        }
        val canOpen = TouchDestination.WEATHER in destinations
        return tile(kit, if (condition == null) R.drawable.ic_touch_weather else glyphIcon(weatherState), condition ?: "Weather",
            temperature?.takeIf { condition != null } ?: "—", sub,
            listOfNotNull("Weather", temperature, condition, locationLabel).joinToString(", ") + if (canOpen) ", open Weather" else "",
            if (canOpen) ({ onNavigate(TouchDestination.WEATHER.name.lowercase(Locale.ROOT)) }) else null)
    }

    private fun thermostatTile(kit: TouchKit, snapshot: TouchHomeModel?): View {
        val climate = snapshot?.climate
        val sub = climate?.let(::tileMode) ?: when (snapshot?.connection) {
            TouchConnection.SIGN_IN_REQUIRED -> "Sign in to adjust"
            TouchConnection.OFFLINE -> "Reconnect to adjust"
            TouchConnection.REFRESHING, null -> "Loading…"
            else -> "Unavailable"
        }
        val inside = climate?.currentTemperature?.let(::formatTemperature)
        return tile(kit, R.drawable.ic_touch_thermostat, "Inside", inside ?: "—", sub,
            listOfNotNull("Thermostat", inside?.let { "inside $it" }, sub).joinToString(", ") + ", open thermostat controls") {
            openSheet(Sheet.THERMOSTAT)
        }
    }

    private fun oceanTile(kit: TouchKit): View {
        val glance = ocean
        val sub = glance?.nextTide ?: if (oceanLoading && glance == null) "Loading…" else "Unavailable"
        val water = glance?.waterF?.let { "$it°" }
        return tile(kit, R.drawable.ic_touch_ocean, glance?.let { "${it.place} water" } ?: "Ocean", water ?: "—", sub,
            listOfNotNull(glance?.place ?: "Ocean", water?.let { "water $it" }, sub).joinToString(", ") + ", open Ocean") {
            onNavigate(TouchDestination.OCEAN.name.lowercase(Locale.ROOT))
        }
    }

    /** A glance is a control: label row with a chevron, then the number and one short status. */
    private fun tile(kit: TouchKit, iconRes: Int, label: String, number: String, sub: String?, description: String, onClick: (() -> Unit)?): View =
        LinearLayout(context).apply {
            val p = kit.palette
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val base = kit.rounded(p.matRaised, scale.radius, p.line, 2)
            background = if (onClick != null) kit.pressable(base, scale.radius) else base
            if (wall) setPadding(kit.dp(24), 0, kit.dp(20), 0) else setPadding(kit.dp(16), 0, kit.dp(12), 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(kit.icon(iconRes, p.accentInk, (scale.label + 6).toInt()))
                addView(kit.text(label, scale.label, TouchFonts.Face.TEXT_MEDIUM, p.inkMuted, maxLines = 1),
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(10) })
                if (onClick != null) addView(kit.icon(R.drawable.ic_touch_chevron_right, p.inkMuted, if (wall) 24 else 20))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = true
                addView(kit.text(number, if (wall) 56f else 40f, TouchFonts.Face.DISPLAY, p.ink, maxLines = 1).apply { fontFeatureSettings = "tnum" })
                sub?.let {
                    addView(kit.text(it, scale.label, TouchFonts.Face.TEXT, p.ink, maxLines = 1),
                        LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(if (wall) 14 else 10) })
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(if (wall) 6 else 2) })
            contentDescription = description
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    private fun tileMode(climate: TouchClimate): String = when {
        climate.mode.equals("off", true) -> "Off"
        climate.targetTemperatureLow != null && climate.targetTemperatureHigh != null ->
            "${formatTemperature(climate.targetTemperatureLow)}–${formatTemperature(climate.targetTemperatureHigh)}"
        climate.mode == "heat" -> climate.targetTemperature?.let { "Heat to ${formatTemperature(it)}" } ?: "Heat"
        climate.mode == "cool" -> climate.targetTemperature?.let { "Cool to ${formatTemperature(it)}" } ?: "Cool"
        else -> modeLabel(climate.mode)
    }

    private fun banner(kit: TouchKit, snapshot: TouchHomeModel?): View? {
        if (snapshot?.connection != TouchConnection.OFFLINE) return null
        val p = kit.palette
        val message = snapshot?.lastUpdatedEpochMillis?.let { "Can’t reach Home Assistant. Showing what was saved at ${timeAt(it)}." }
            ?: "Can’t reach Home Assistant. Check the frame’s Wi‑Fi, then try again."
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = kit.rounded(p.pending, scale.radius)
            minimumHeight = kit.dp(scale.target + 20)
            setPadding(kit.dp(24), kit.dp(10), kit.dp(10), kit.dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(kit.icon(R.drawable.ic_touch_offline, p.pendingInk, 32))
            addView(kit.text(message, scale.body, TouchFonts.Face.TEXT_MEDIUM, p.pendingInk), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = kit.dp(18)
                marginEnd = kit.dp(18)
            })
            addView(kit.button("Try again", TouchKit.Emphasis.QUIET, quietInk = p.pendingInk) { onRetry?.invoke() })
        }
        return FrameLayout(context).apply {
            setPadding(0, kit.dp(if (wall) 24 else 16), 0, 0)
            addView(row, LayoutParams(MATCH, WRAP))
        }
    }

    /** Signed out: one clear call to action instead of two empty columns. Photos and Art don't need it. */
    private fun connectPanel(kit: TouchKit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val p = kit.palette
        addView(kit.icon(R.drawable.ic_touch_user, p.inkMuted, 64))
        addView(kit.text("Connect this frame to Home Assistant", scale.headline, TouchFonts.Face.DISPLAY_BOLD).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(16) })
        addView(kit.text("Sign in once to show your lists, calendar, thermostat and weather here. Photos and Art keep working without it.",
            scale.body, color = p.inkMuted).apply {
            gravity = Gravity.CENTER
            maxWidth = kit.dp(680)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(12) })
        addView(kit.button("Sign in", TouchKit.Emphasis.PRIMARY) { onSignIn() }, LinearLayout.LayoutParams(WRAP, kit.dp(scale.target)).apply { topMargin = kit.dp(20) })
    }

    // ---------- To do ----------

    private fun columnHeader(kit: TouchKit, title: String, count: String?, vararg trailing: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(HEADER_DP)
        addView(kit.text(title, scale.title, TouchFonts.Face.DISPLAY_BOLD, maxLines = 1))
        addView(kit.text(count.orEmpty(), scale.label, TouchFonts.Face.TEXT, kit.palette.inkMuted, maxLines = 1),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(14) })
        trailing.forEachIndexed { index, view -> addView(view, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (index > 0) marginStart = kit.dp(12) }) }
    }

    /** Text link in the column's accent ink (Calendar ›, Lists ›): a 56dp target without a box. */
    private fun link(kit: TouchKit, label: String, description: String, action: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(56)
        setPadding(kit.dp(8), 0, kit.dp(4), 0)
        background = kit.pressable(kit.rounded(0x00000000, scale.radiusSmall), scale.radiusSmall)
        addView(kit.text(label, scale.label, TouchFonts.Face.TEXT_BOLD, kit.palette.accentInk))
        addView(kit.icon(R.drawable.ic_touch_chevron_right, kit.palette.accentInk, 24), LinearLayout.LayoutParams(kit.dp(24), kit.dp(24)).apply { marginStart = kit.dp(4) })
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    /** "+ Add" pill: outlined, 56dp tall, on the header's right. */
    private fun addPill(kit: TouchKit, list: TouchTodoListRef): View = LinearLayout(context).apply {
        val p = kit.palette
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(56)
        setPadding(kit.dp(16), 0, kit.dp(22), 0)
        background = kit.pressable(kit.rounded(p.matRaised, 28, p.stroke, 2), 28)
        addView(kit.icon(R.drawable.ic_touch_plus, p.ink, 26))
        addView(kit.text("Add", scale.label, TouchFonts.Face.TEXT_BOLD, p.ink), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(8) })
        contentDescription = "Add to ${list.name}"
        isClickable = true
        isFocusable = true
        setOnClickListener { openAdd(list) }
    }

    private fun todoColumn(kit: TouchKit, rowBudget: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val selection = todo.selection
        val firstList = selection.taskLists.firstOrNull()
        val visible = visibleTasks()
        val ready = todo.state == TouchTodoState.READY || (todo.state == TouchTodoState.OFFLINE && todo.items.isNotEmpty())
        val count = if (ready && firstList != null) TouchTodoPresentation.remaining(visible.count { it.key !in pendingDone }) else null
        val trailing = buildList {
            if (selection.chosen) add(link(kit, "Lists", "Choose which lists this frame shows") { todoActions?.chooseLists() })
            if (ready && firstList != null && todo.state == TouchTodoState.READY) add(addPill(kit, firstList))
        }
        addView(columnHeader(kit, "To do", count, *trailing.toTypedArray()), LinearLayout.LayoutParams(MATCH, WRAP))
        when {
            todo.state == TouchTodoState.NOT_CHOSEN -> addView(stateBlock(kit, "Choose the lists to show here",
                "Lists come from Home Assistant\u2019s to-do lists, such as Todoist or Google Tasks. Pick which ones this frame shows.",
                "Choose lists") { todoActions?.chooseLists() })
            todo.state == TouchTodoState.SIGN_IN_REQUIRED -> addView(stateBlock(kit, "Sign in to see your lists",
                "Your to-do lists come from Home Assistant.", "Sign in", onSignIn))
            todo.state == TouchTodoState.LIST_MISSING -> addView(stateBlock(kit, "A chosen list isn’t in Home Assistant",
                "It may have been renamed or removed. Choose the lists again.", "Choose lists") { todoActions?.chooseLists() })
            todo.state == TouchTodoState.LOADING && todo.items.isEmpty() -> skeletonRows(kit, 3).forEach { addView(it) }
            !ready -> addView(stateBlock(kit, "Lists aren’t available right now",
                "Home Assistant didn’t answer. They’ll come back when the frame reconnects.", "Try again") { onRetry?.invoke() })
            firstList == null -> Unit
            visible.isEmpty() -> addView(stateBlock(kit, "Nothing left to do", "Add a task on your phone, or tap Add.", null, null))
            else -> {
                val shown = if (visible.size > rowBudget) visible.take((rowBudget - 1).coerceAtLeast(1)) else visible
                val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                shown.forEach { rows.addView(taskRow(kit, it), LinearLayout.LayoutParams(MATCH, WRAP)) }
                if (visible.size > shown.size) rows.addView(link(kit, "${visible.size - shown.size} more", "Show all of ${firstList.name}") {
                    sheetList = firstList
                    openSheet(Sheet.TASKS)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(4) })
                addView(rows, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(10) })
            }
        }
        selection.groceries?.takeIf { ready }?.let { list ->
            addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(groceryTile(kit, list), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(16) })
        }
    }

    /** Open tasks in display order, without anything already sent as done. */
    private fun visibleTasks(): List<TouchTodoItem> = TouchTodoPresentation.ordered(todo.tasks).filter { it.key !in committing }

    private fun visibleGroceries(): List<TouchTodoItem> = todo.groceries.filter { it.key !in committing }

    private fun stateBlock(kit: TouchKit, title: String, body: String, action: String?, onAction: (() -> Unit)?): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, kit.dp(24), 0, 0)
        addView(kit.text(title, scale.headline, TouchFonts.Face.TEXT_MEDIUM))
        addView(kit.text(body, scale.body, color = kit.palette.inkMuted).apply { setLineSpacing(0f, 1.2f); maxWidth = kit.dp(560) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(10) })
        if (action != null && onAction != null) addView(kit.button(action, TouchKit.Emphasis.PRIMARY) { onAction() },
            LinearLayout.LayoutParams(WRAP, kit.dp(scale.target)).apply { topMargin = kit.dp(18) })
    }

    /** The circle and the text are one target; a checked row offers Undo until it is sent. */
    private fun taskRow(kit: TouchKit, item: TouchTodoItem): View = LinearLayout(context).apply {
        val p = kit.palette
        val pending = item.key in pendingDone
        orientation = LinearLayout.VERTICAL
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = kit.dp(rowHeightDp())
            val dot = kit.dp(if (wall) 40 else 34)
            addView(FrameLayout(context).apply {
                background = if (pending) kit.oval(p.accent, p.accentStroke, 3) else kit.oval(0x00000000, p.stroke, 3)
                if (pending) addView(kit.icon(R.drawable.ic_touch_check, p.onAccent, (dot * 0.6f / resources.displayMetrics.density).toInt()),
                    LayoutParams((dot * 0.6f).toInt(), (dot * 0.6f).toInt(), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dot, dot))
            addView(kit.text(item.summary, scale.body, TouchFonts.Face.TEXT, if (pending) p.inkMuted else p.ink, maxLines = 1).apply {
                if (pending) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(if (wall) 18 else 14); marginEnd = kit.dp(12) })
            val due = TouchTodoPresentation.due(item.due, dayAt(nowMillis))
            when {
                pending -> addView(undoButton(kit, item))
                due.tone == TouchTodoPresentation.Tone.LATE -> addView(kit.text(due.text, scale.label, TouchFonts.Face.TEXT_BOLD, p.alertInk).apply {
                    background = kit.rounded(p.alert, 10)
                    setPadding(kit.dp(12), kit.dp(4), kit.dp(12), kit.dp(4))
                })
                due.tone == TouchTodoPresentation.Tone.TODAY -> addView(kit.text(due.text, scale.label, TouchFonts.Face.TEXT_BOLD, p.accentInk))
                due.tone == TouchTodoPresentation.Tone.PLAIN -> addView(kit.text(due.text, scale.label, TouchFonts.Face.TEXT, p.inkMuted))
                else -> Unit
            }
            if (!pending) {
                background = kit.pressable(kit.rounded(0x00000000, scale.radiusSmall), scale.radiusSmall)
                isClickable = true
                isFocusable = true
                contentDescription = listOf("Mark done", item.summary, due.text).filter(String::isNotBlank).joinToString(", ")
                setOnClickListener { markDone(item) }
            }
        }
        addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(kit.hairline(), LinearLayout.LayoutParams(MATCH, kit.dp(1)))
    }

    private fun undoButton(kit: TouchKit, item: TouchTodoItem): View = kit.text("Undo", scale.label, TouchFonts.Face.TEXT_BOLD, kit.palette.ink).apply {
        gravity = Gravity.CENTER
        minHeight = kit.dp(if (wall) 52 else 46)
        setPadding(kit.dp(20), 0, kit.dp(20), 0)
        background = kit.pressable(kit.rounded(kit.palette.matRaised, scale.radiusSmall, kit.palette.stroke, 2), scale.radiusSmall)
        contentDescription = "Undo, keep ${item.summary}"
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        isClickable = true
        isFocusable = true
        setOnClickListener { undo(item) }
    }

    private fun groceryTile(kit: TouchKit, list: TouchTodoListRef): View = LinearLayout(context).apply {
        val p = kit.palette
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = kit.pressable(kit.rounded(p.matRaised, scale.radius, p.line, 2), scale.radius)
        if (wall) setPadding(kit.dp(22), kit.dp(18), kit.dp(20), kit.dp(18)) else setPadding(kit.dp(16), kit.dp(12), kit.dp(14), kit.dp(12))
        val preview = TouchTodoPresentation.preview(visibleGroceries())
        addView(kit.icon(R.drawable.ic_touch_bag, p.accentInk, if (wall) 40 else 30))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.text(list.name, if (wall) scale.headline else scale.label, TouchFonts.Face.TEXT_MEDIUM, maxLines = 1))
            addView(kit.text(preview, if (wall) scale.label else scale.caption, TouchFonts.Face.TEXT, p.inkMuted, maxLines = 1),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(2) })
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(16); marginEnd = kit.dp(12) })
        addView(kit.icon(R.drawable.ic_touch_chevron_right, p.inkMuted, 26))
        contentDescription = "${list.name}: $preview. Open the list"
        isClickable = true
        isFocusable = true
        setOnClickListener {
            sheetList = list
            openSheet(Sheet.GROCERIES)
        }
    }

    private fun markDone(item: TouchTodoItem) {
        if (item.key in pendingDone || todo.state != TouchTodoState.READY) return
        val send = Runnable { commit(item) }
        pendingDone[item.key] = item to send
        postDelayed(send, UNDO_MILLIS)
        refillSlots()
    }

    private fun undo(item: TouchTodoItem) {
        pendingDone.remove(item.key)?.let { removeCallbacks(it.second) }
        refillSlots()
    }

    private fun commit(item: TouchTodoItem) {
        pendingDone.remove(item.key) ?: return
        committing[item.key] = System.currentTimeMillis()
        todoActions?.complete(item)
        refillSlots()
    }

    // ---------- Sheets ----------

    private fun openSheet(next: Sheet) {
        sheet = next
        refillSlots()
    }

    private fun openAdd(list: TouchTodoListRef) {
        sheetList = list
        openSheet(Sheet.ADD)
    }

    private fun sheetContent(kit: TouchKit, snapshot: TouchHomeModel?): View? = when (sheet) {
        Sheet.NONE -> null
        Sheet.THERMOSTAT -> sheetFrame(kit, "Thermostat", snapshot?.climate?.currentTemperature?.let { "Inside ${formatTemperature(it)}" },
            if (wall) 900 else 720, if (wall) 640 else 560, thermostat(kit, snapshot))
        Sheet.TASKS, Sheet.GROCERIES -> sheetList?.let { listSheet(kit, it) }
        Sheet.ADD -> sheetList?.let { addSheet(kit, it) }
        Sheet.PICKER -> pickerSheet(kit)
    }

    /** Mat card with a title row and a Close control; the body fills the rest. */
    private fun sheetFrame(kit: TouchKit, title: String, detail: String?, widthDp: Int, heightDp: Int?, body: View, vararg actions: View): View =
        LinearLayout(context).apply {
            val p = kit.palette
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(p.mat, scale.radius, p.stroke, 2)
            val pad = kit.dp(if (wall) 32 else 24)
            setPadding(pad, kit.dp(16), kit.dp(16), pad)
            layoutParams = LayoutParams(kit.dp(widthDp), heightDp?.let(kit::dp) ?: WRAP)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(kit.text(title, scale.title, TouchFonts.Face.DISPLAY_BOLD, maxLines = 1), LinearLayout.LayoutParams(0, WRAP, 1f))
                detail?.let { addView(kit.text(it, scale.body, color = p.inkMuted, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = kit.dp(16) }) }
                actions.forEach { addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = kit.dp(12) }) }
                addView(kit.iconButton(R.drawable.ic_touch_close, "Close") { closeSheet() })
            })
            addView(body, LinearLayout.LayoutParams(MATCH, if (heightDp != null) 0 else WRAP, if (heightDp != null) 1f else 0f).apply {
                topMargin = kit.dp(8)
                marginEnd = kit.dp(16)
            })
        }

    private fun listSheet(kit: TouchKit, list: TouchTodoListRef): View {
        val items = if (sheet == Sheet.GROCERIES) visibleGroceries() else visibleTasks().filter { it.entityId == list.entityId }
        val body: View = if (items.isEmpty()) stateBlock(kit, "Nothing on this list", "Add something here or on your phone.", null, null)
        else ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                items.forEach { addView(taskRow(kit, it), LinearLayout.LayoutParams(MATCH, WRAP)) }
            })
        }
        val add = if (todo.state == TouchTodoState.READY) arrayOf(addPill(kit, list)) else emptyArray()
        return sheetFrame(kit, list.name, TouchTodoPresentation.remaining(items.count { it.key !in pendingDone }),
            if (wall) 900 else 720, if (wall) 820 else 640, body, *add)
    }

    /** One field and two buttons, near the top so the on-screen keyboard never covers them. */
    private fun addSheet(kit: TouchKit, list: TouchTodoListRef): View {
        val p = kit.palette
        val field = EditText(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scale.body)
            typeface = TouchFonts.get(context, TouchFonts.Face.TEXT)
            setTextColor(p.ink)
            setHintTextColor(p.inkMuted)
            hint = if (list == todo.selection.groceries) "Milk, batteries, dog food…" else "What needs doing?"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(com.wyattfleming.touchframe.home.TouchTodoParser.MAX_SUMMARY_CHARS))
            background = kit.rounded(p.matRaised, scale.radiusSmall, p.stroke, 2)
            setPadding(kit.dp(20), 0, kit.dp(20), 0)
            minHeight = kit.dp(scale.target)
        }
        fun submit() {
            val text = field.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            hideKeyboard()
            todoActions?.add(list, text)
            closeSheet()
        }
        field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(field, LinearLayout.LayoutParams(MATCH, kit.dp(scale.target)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(kit.button("Cancel") { closeSheet() }, LinearLayout.LayoutParams(WRAP, kit.dp(scale.target)).apply { marginEnd = kit.dp(12) })
                addView(kit.button("Add", TouchKit.Emphasis.PRIMARY, iconRes = R.drawable.ic_touch_plus) { submit() }, LinearLayout.LayoutParams(WRAP, kit.dp(scale.target)))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(20) })
        }
        val frame = sheetFrame(kit, "Add to ${list.name}", null, if (wall) 900 else 720, null, body)
        (frame.layoutParams as LayoutParams).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = kit.dp(if (wall) 72 else 40) }
        sheetSlot?.let { slot ->
            (slot.layoutParams as? LayoutParams)?.let { it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; it.topMargin = kit.dp(if (wall) 72 else 40) }
            slot.requestLayout()
        }
        field.post {
            field.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        return frame
    }

    private fun pickerSheet(kit: TouchKit): View {
        val p = kit.palette
        val found = discovery
        val body: View = when {
            found == null -> stateBlock(kit, "Finding your lists…", "Asking Home Assistant which to-do lists it has.", null, null)
            found.state == TouchTodoState.SIGN_IN_REQUIRED -> stateBlock(kit, "Sign in first", "Your to-do lists come from Home Assistant.", "Sign in") { closeSheet(); onSignIn() }
            found.state != TouchTodoState.READY -> stateBlock(kit, "Couldn’t reach Home Assistant",
                "Check the frame’s Wi‑Fi, then try again.", "Try again") { showListPicker(null); todoActions?.chooseLists() }
            found.lists.isEmpty() -> stateBlock(kit, "Home Assistant has no to-do lists yet",
                "Add a to-do integration in Home Assistant, such as Todoist or Google Tasks (Settings, Devices & services). Its lists appear here.", null, null)
            else -> ScrollView(context).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kit.text("Pick what each list is on this frame. Lists set to Hide never appear on the wall.", scale.body, color = p.inkMuted),
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = kit.dp(8) })
                    found.lists.forEach { list ->
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = kit.dp(rowHeightDp() + 8)
                            addView(kit.text(list.name, scale.body, TouchFonts.Face.TEXT_MEDIUM, maxLines = 1), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = kit.dp(16) })
                            addView(kit.segmented(Role.entries.map { it.label to it }, pickerChoice[list.entityId] ?: Role.HIDE, true, scale.label) { role ->
                                if (role == Role.GROCERIES) pickerChoice.entries.filter { it.value == Role.GROCERIES }.forEach { it.setValue(Role.HIDE) }
                                pickerChoice[list.entityId] = role
                                refillSlots()
                            }, LinearLayout.LayoutParams(WRAP, kit.dp(scale.target)))
                        }, LinearLayout.LayoutParams(MATCH, WRAP))
                        addView(kit.hairline(), LinearLayout.LayoutParams(MATCH, kit.dp(1)))
                    }
                })
            }
        }
        val save = if (found?.state == TouchTodoState.READY && found.lists.isNotEmpty()) arrayOf<View>(kit.button("Save", TouchKit.Emphasis.PRIMARY) {
            val lists = found.lists
            val tasks = lists.filter { pickerChoice[it.entityId] == Role.TASKS }
            val groceries = lists.firstOrNull { pickerChoice[it.entityId] == Role.GROCERIES }
            todoActions?.saveLists(TouchTodoSelection.of(tasks, groceries))
            closeSheet()
        }) else emptyArray()
        return sheetFrame(kit, "Lists on this frame", null, if (wall) 1080 else 860, if (found?.lists?.isNotEmpty() == true) (if (wall) 820 else 640) else null, body, *save)
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
        sheetSlot?.let { slot ->
            (slot.layoutParams as? LayoutParams)?.let { it.gravity = Gravity.CENTER; it.topMargin = 0 }
            slot.requestLayout()
        }
    }

    // ---------- Coming up ----------

    private fun coming(kit: TouchKit, snapshot: TouchHomeModel?, maxLines: Int): View {
        val p = kit.palette
        val canOpen = TouchDestination.CALENDAR in destinations
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val calendarLink = if (canOpen) arrayOf(link(kit, "Calendar", "Coming up, open Calendar") {
                onNavigate(TouchDestination.CALENDAR.name.lowercase(Locale.ROOT))
            }) else emptyArray()
            addView(columnHeader(kit, "Coming up", null, *calendarLink), LinearLayout.LayoutParams(MATCH, WRAP))
            val events = snapshot?.events.orEmpty()
            var overflow = 0
            when {
                snapshot == null || (snapshot.connection == TouchConnection.REFRESHING && snapshot.lastUpdatedEpochMillis == null) ->
                    skeletonRows(kit, 4).forEach { addView(it) }
                events.isEmpty() && snapshot.connection == TouchConnection.OFFLINE ->
                    addView(note(kit, "Events will appear when the frame reconnects."))
                events.isEmpty() -> addView(note(kit, "Nothing on the calendar for the next 7 days."))
                else -> TouchHomeAgenda.plan(events, nowMillis, ZoneId.systemDefault(), maxLines).also { plan ->
                    overflow = plan.hiddenCount
                }.sections.forEach { section ->
                    addView(kit.text(section.label, scale.label, TouchFonts.Face.TEXT_BOLD, if (section.isToday) p.accentInk else p.inkMuted),
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(if (wall) 18 else 12) })
                    // Today carries a heavier accent rule so it stays distinct when accent and ink converge at night.
                    addView(View(context).apply { setBackgroundColor(if (section.isToday) p.accentStroke else p.line) },
                        LinearLayout.LayoutParams(MATCH, kit.dp(if (section.isToday) 3 else 1)).apply { topMargin = kit.dp(6) })
                    section.rows.forEach { row ->
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            contentDescription = "${section.label}, ${row.timeLabel}, ${row.title}"
                            addView(kit.text(row.timeLabel, scale.body, TouchFonts.Face.TEXT, p.inkMuted).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                fontFeatureSettings = "tnum"
                            }, LinearLayout.LayoutParams(kit.dp((scale.body * 5.4f).toInt()), WRAP))
                            addView(kit.text(row.title, scale.body, maxLines = 1).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }, LinearLayout.LayoutParams(0, WRAP, 1f))
                        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(10) })
                    }
                }
            }
            if (overflow > 0) {
                addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
                val label = "$overflow more ${if (overflow == 1) "event" else "events"} this week"
                addView(if (canOpen) link(kit, label, "$label, open Calendar") { onNavigate(TouchDestination.CALENDAR.name.lowercase(Locale.ROOT)) }
                else kit.text(label, scale.label, TouchFonts.Face.TEXT_BOLD, p.inkMuted), LinearLayout.LayoutParams(WRAP, WRAP))
            }
        }
    }

    // ---------- Thermostat (opened from its glance) ----------

    private fun thermostat(kit: TouchKit, snapshot: TouchHomeModel?): View {
        val climate = snapshot?.climate
        val connection = snapshot?.connection
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (climate == null) {
                addView(when {
                    snapshot == null || (connection == TouchConnection.REFRESHING && snapshot.lastUpdatedEpochMillis == null) -> skeletonRows(kit, 2).let { rows ->
                        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; rows.forEach(::addView) }
                    }
                    connection == TouchConnection.SIGN_IN_REQUIRED -> note(kit, "Thermostat controls appear here after you sign in.")
                    connection == TouchConnection.OFFLINE -> note(kit, "Thermostat controls return when the frame reconnects.")
                    else -> note(kit, "This thermostat isn’t reporting a temperature range, so it can’t be adjusted here.")
                })
                return@apply
            }
            val canSetMode = !climateBusy && connection == TouchConnection.CONNECTED && climate.canSetMode
            if (!climate.canSetTarget) {
                addView(readOnlyThermostat(kit, climate, canSetMode), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(12) })
                return@apply
            }
            val canSetTarget = !climateBusy && connection == TouchConnection.CONNECTED
            val shown = draft.target ?: climate.targetTemperature
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val stepper = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(stepButton(kit, R.drawable.ic_touch_minus, "Lower setpoint", canSetTarget) { step(climate, -1) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val big = when {
                        shown != null -> formatTemperature(shown)
                        climate.mode.equals("off", true) -> "Off"
                        else -> "—"
                    }
                    addView(kit.text(big, scale.setpoint, TouchFonts.Face.DISPLAY_BOLD, if (draft.dirty) kit.palette.pendingInk else kit.palette.ink).apply {
                        gravity = Gravity.CENTER
                        fontFeatureSettings = "tnum"
                        contentDescription = if (draft.dirty) "New setpoint $big, not sent yet" else "Setpoint $big"
                    })
                    // Two lines are always reserved so the stepper and modes never move between states.
                    addView(FrameLayout(context).apply {
                        minimumHeight = kit.dp((scale.caption * 1.35f + scale.label * 1.5f + 6).toInt())
                        addView(setpointState(kit, climate, connection, shown), LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
                    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(8) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(stepButton(kit, R.drawable.ic_touch_plus, "Raise setpoint", canSetTarget) { step(climate, 1) })
            }
            body.addView(stepper, LinearLayout.LayoutParams(MATCH, WRAP))
            // The slot keeps its height in every state so the stepper never jumps.
            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val target = draft.target
            if (draft.dirty && !climateBusy && target != null) {
                actions.addView(kit.button("Cancel") {
                    removeCallbacks(revertDraft)
                    draft.reset(climate.targetTemperature)
                    climateResult = null
                    refillSlots()
                }, LinearLayout.LayoutParams(0, kit.dp(scale.target), 1f).apply { marginEnd = kit.dp(12) })
                val failed = climateResult == ClimateResult.FAILED
                actions.addView(kit.button(if (failed) "Try again" else "Set to ${formatTemperature(target)}", TouchKit.Emphasis.PRIMARY,
                    iconRes = if (failed) R.drawable.ic_touch_refresh else null, enabled = canSetTarget) {
                    onClimateTarget(target)
                }, LinearLayout.LayoutParams(0, kit.dp(scale.target), 1.4f))
            }
            body.addView(actions, LinearLayout.LayoutParams(MATCH, kit.dp(scale.target)).apply { topMargin = kit.dp(16) })
            val modes = climate.modes.distinct()
            if (modes.isNotEmpty()) {
                val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                modes.chunked(MODES_PER_ROW).forEachIndexed { index, chunk ->
                    rows.addView(kit.segmented(chunk.map { modeLabel(it) to it }, climate.mode.takeIf { it in chunk }, canSetMode, scale.label) { mode ->
                        onClimateMode(mode)
                    }, LinearLayout.LayoutParams(MATCH, kit.dp(scale.target)).apply { if (index > 0) topMargin = kit.dp(8) })
                }
                body.addView(rows, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(scale.gap) })
            }
            // Stepper, action slot and modes stay together as one centred block.
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(12) })
        }
    }

    /** A readable state is useful, but this frame never invents a target for an unsupported thermostat. */
    private fun readOnlyThermostat(kit: TouchKit, climate: TouchClimate, canSetMode: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        climate.targetTemperature?.let { addView(kit.text("Set to ${formatTemperature(it)}", scale.setpoint, TouchFonts.Face.DISPLAY_BOLD).apply { gravity = Gravity.CENTER }) }
        if (climate.targetTemperatureLow != null && climate.targetTemperatureHigh != null) {
            addView(kit.text("Range ${formatTemperature(climate.targetTemperatureLow)}–${formatTemperature(climate.targetTemperatureHigh)}", scale.body, TouchFonts.Face.TEXT_MEDIUM).apply { gravity = Gravity.CENTER })
        }
        addView(note(kit, if (climate.targetTemperatureLow != null) {
            "This thermostat reports a heat/cool range. This frame won’t change it."
        } else {
            "This thermostat isn’t reporting a single setpoint, so it can’t be adjusted here."
        }))
        val modes = climate.modes.distinct()
        if (climate.canSetMode && modes.isNotEmpty()) {
            val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            modes.chunked(MODES_PER_ROW).forEachIndexed { index, chunk ->
                rows.addView(kit.segmented(chunk.map { modeLabel(it) to it }, climate.mode.takeIf { it in chunk }, canSetMode, scale.label) { mode ->
                    onClimateMode(mode)
                }, LinearLayout.LayoutParams(MATCH, kit.dp(scale.target)).apply { if (index > 0) topMargin = kit.dp(8) })
            }
            addView(rows, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(scale.gap) })
        }
    }

    private fun setpointState(kit: TouchKit, climate: TouchClimate, connection: TouchConnection?, shown: Double?): View = when {
        climateBusy -> kit.chip("Sending…", TouchKit.Tone.PENDING)
        climateResult == ClimateResult.FAILED && draft.dirty && shown != null -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(kit.chip("Couldn’t set to ${formatTemperature(shown)}", TouchKit.Tone.ERROR))
            // The command path reports only confirmed or not, so the reason is stated at that level.
            addView(kit.text("Home Assistant didn’t confirm it.", scale.caption, color = kit.palette.inkMuted),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(6) })
        }
        climateResult == ClimateResult.FAILED -> kit.chip("Couldn’t confirm the change", TouchKit.Tone.ERROR)
        draft.dirty -> kit.chip("Not sent yet", TouchKit.Tone.PENDING)
        climateResult == ClimateResult.CONFIRMED && shown != null -> kit.chip("Set to ${formatTemperature(shown)}", TouchKit.Tone.OK)
        connection != TouchConnection.CONNECTED -> kit.text("Reconnect to change", scale.label, color = kit.palette.inkMuted)
        else -> kit.text(modeSentence(climate, shown), scale.label, color = kit.palette.inkMuted)
    }

    private fun stepButton(kit: TouchKit, iconRes: Int, description: String, enabled: Boolean, action: () -> Unit): View {
        val p = kit.palette
        val size = kit.dp(scale.stepper)
        return FrameLayout(context).apply {
            contentDescription = description
            isClickable = enabled
            isFocusable = enabled
            isEnabled = enabled
            val base = if (enabled) kit.oval(p.matRaised, p.stroke) else GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(kit.dp(2), p.disabledInk, kit.dp(6).toFloat(), kit.dp(5).toFloat())
            }
            background = if (enabled) android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(p.pressed), base, kit.oval(0xFFFFFFFF.toInt(), null)) else base
            addView(kit.icon(iconRes, if (enabled) p.ink else p.disabledInk, (scale.stepper * 0.4f).toInt()), LayoutParams(
                kit.dp((scale.stepper * 0.4f).toInt()), kit.dp((scale.stepper * 0.4f).toInt()), Gravity.CENTER))
            if (enabled) setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun step(climate: TouchClimate, direction: Int) {
        draft.step(climate, direction) ?: return
        climateResult = null
        removeCallbacks(revertDraft)
        if (draft.dirty) postDelayed(revertDraft, DRAFT_TIMEOUT_MILLIS)
        refillSlots()
    }

    /** A change nobody sent within 30 s is abandoned; an unattended frame must show the real setpoint. */
    private val revertDraft = Runnable {
        if (!draft.dirty || climateBusy) return@Runnable
        draft.reset(model?.climate?.targetTemperature)
        climateResult = null
        refillSlots()
    }

    private val settleResult = Runnable {
        if (climateResult != ClimateResult.CONFIRMED) return@Runnable
        climateResult = null
        refillSlots()
    }

    /** Freshness for the photo panel's top line; the offline banner and sign-in panel say the rest. */
    private fun statusText(snapshot: TouchHomeModel?): String {
        val updated = snapshot?.lastUpdatedEpochMillis
        return when (snapshot?.connection) {
            TouchConnection.CONNECTED -> updated?.let { "Updated ${timeAt(it)}" } ?: "Connected"
            TouchConnection.REFRESHING -> updated?.let { "Refreshing, last updated ${timeAt(it)}" } ?: "Loading home data…"
            TouchConnection.OFFLINE -> updated?.let { "Offline, saved ${timeAt(it)}" } ?: "Offline"
            TouchConnection.SIGN_IN_REQUIRED -> ""
            null -> "Loading home data…"
        }
    }

    private fun note(kit: TouchKit, message: String): View =
        kit.text(message, scale.body, color = kit.palette.inkMuted).apply { setPadding(0, kit.dp(24), 0, 0) }

    /** Loading placeholders shaped like the rows they stand in for (first launch only). */
    private fun skeletonRows(kit: TouchKit, count: Int): List<View> = (0 until count).map { index ->
        View(context).apply {
            background = kit.rounded(kit.palette.line, 8)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(kit.dp(if (index % 2 == 0) 420 else 300), kit.dp((scale.body * 0.9f).toInt())).apply {
                topMargin = kit.dp(if (index == 0) 28 else 18)
            }
        }
    }

    private fun glyphIcon(state: String): Int = when (TouchWeatherLabel.glyph(state)) {
        TouchWeatherLabel.Glyph.SUN -> R.drawable.ic_touch_sun
        TouchWeatherLabel.Glyph.MOON -> R.drawable.ic_touch_moon
        TouchWeatherLabel.Glyph.CLOUD -> R.drawable.ic_touch_cloud
        TouchWeatherLabel.Glyph.RAIN -> R.drawable.ic_touch_rain
        TouchWeatherLabel.Glyph.SNOW -> R.drawable.ic_touch_snow
        TouchWeatherLabel.Glyph.STORM -> R.drawable.ic_touch_storm
        TouchWeatherLabel.Glyph.NONE -> R.drawable.ic_touch_weather
    }

    private fun isLargeLandscape(): Boolean {
        val density = resources.displayMetrics.density
        val widthDp = if (width > 0) width / density else resources.configuration.screenWidthDp.toFloat()
        val heightDp = if (height > 0) height / density else resources.configuration.screenHeightDp.toFloat()
        return TouchHomeLayout.isLargeLandscape(widthDp, heightDp)
    }

    private fun is24Hour(): Boolean = DateFormat.is24HourFormat(context)

    private fun timeText(): String = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (is24Hour()) "H:mm" else "h:mm", Locale.getDefault()))

    private fun suffixText(): String = if (is24Hour()) "" else Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("a", Locale.getDefault()))

    private fun dateText(): String = Instant.ofEpochMilli(nowMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))

    private fun timeAt(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (is24Hour()) "H:mm" else "h:mm a", Locale.getDefault()))

    private fun hourAt(epochMillis: Long): Int = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).hour

    private fun dayAt(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun modeLabel(mode: String): String = when (mode) {
        "heat_cool" -> "Heat & cool"
        "fan_only" -> "Fan"
        else -> mode.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun modeSentence(climate: TouchClimate, target: Double?): String {
        val value = target?.let { formatTemperature(it) }
        return when (climate.mode) {
            "heat" -> value?.let { "Heats to $it" } ?: "Heat"
            "cool" -> value?.let { "Cools to $it" } ?: "Cool"
            "off" -> "Thermostat is off"
            else -> modeLabel(climate.mode)
        }
    }

    /** Thermostat values read as "72°"; the unit (°F/°C) is the household's own and is shown nowhere else. */
    private fun formatTemperature(value: Double): String =
        (if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.getDefault(), value)) + "°"

    private companion object {
        const val MODES_PER_ROW = 4
        const val DRAFT_TIMEOUT_MILLIS = 30_000L
        const val CONFIRMATION_MILLIS = 5_000L
        /** How long a checked item can be taken back before it is sent to Home Assistant. */
        const val UNDO_MILLIS = 5_000L
        /** A sent item Home Assistant still lists after this long is shown again rather than hidden forever. */
        const val COMMIT_HIDE_MILLIS = 120_000L
        const val PHOTO_WALL_DP = 700
        const val PHOTO_PANEL_DP = 392
        const val HEADER_DP = 56
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

internal object TouchHomeLayout {
    const val LARGE_LANDSCAPE_DP = 900f

    fun contentWidthDp(screenWidthDp: Float, railWidthDp: Float): Float =
        (screenWidthDp - railWidthDp).coerceAtLeast(0f)

    fun isLargeLandscape(widthDp: Float, heightDp: Float): Boolean =
        widthDp >= LARGE_LANDSCAPE_DP && (heightDp <= 0f || widthDp >= heightDp)

    fun needsTransition(previous: Boolean?, next: Boolean): Boolean = previous != next
}

internal class TouchHomeLayoutTransition {
    private var rendered: Boolean? = null
    private var requested: Boolean? = null

    fun markRendered(value: Boolean) {
        rendered = value
        if (requested == value) requested = null
    }

    fun request(next: Boolean): Boolean {
        if (!TouchHomeLayout.needsTransition(rendered, next)) {
            requested = null
            return false
        }
        if (requested == next) return false
        requested = next
        return true
    }

    fun consume(): Boolean? {
        val next = requested ?: return null
        requested = null
        if (!TouchHomeLayout.needsTransition(rendered, next)) return null
        rendered = next
        return next
    }
}
