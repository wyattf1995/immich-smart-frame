package com.wyattfleming.touchframe.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wyattfleming.touchframe.R
import kotlin.math.pow

/**
 * The one visual system for FrameOS Touch's native screens ("gallery wall"): stone and mat
 * surfaces with a graphite moulding rail by day, warm amber on black by night for bedrooms.
 * Photos/Art media and the shared Weather canvas keep their own artwork.
 */
internal data class TouchPalette(
    val wall: Int,
    val mat: Int,
    val matRaised: Int,
    val ink: Int,
    val inkMuted: Int,
    /** Dividers and panel edges. */
    val line: Int,
    /** Control boundaries (buttons, steppers, segmented tracks): at least 3:1 against mat and wall. */
    val stroke: Int,
    val moulding: Int,
    val mouldingInk: Int,
    val mouldingMuted: Int,
    val mouldingLine: Int,
    val railSelected: Int,
    val railSelectedInk: Int,
    val railSelectedStroke: Int,
    /** Fill for selected/primary controls; by night a dim fill outlined by [accentStroke] instead of a bright block. */
    val accent: Int,
    val onAccent: Int,
    val accentStroke: Int,
    val accentSoft: Int,
    val accentInk: Int,
    /** Today's calendar cell: a tint by day, the dim selection fill by night, always with an accent outline. */
    val todayFill: Int,
    /** Secondary text inside today's tinted cell or column ("2 earlier", "Free"); ≥ 4.5:1 on [todayFill]. */
    val todayMutedInk: Int,
    /**
     * Past calendar days stay present but read as inactive (text ≥ 4.5:1): a greyed panel by day,
     * an outlined box on the black wall by night, always with lighter type than upcoming days.
     */
    val pastFill: Int,
    val pastLine: Int,
    val pastInk: Int,
    val pending: Int,
    val pendingInk: Int,
    val alert: Int,
    val alertInk: Int,
    val disabledInk: Int,
    val notice: Int,
    val noticeInk: Int,
    val noticeStroke: Int,
    val letterbox: Int,
    val scrim: Int,
    /** Translucent ink laid over a control while it is pressed (color feedback, no motion). */
    val pressed: Int,
) {
    companion object {
        val DAY = TouchPalette(
            wall = 0xFFE4E1D9.toInt(), mat = 0xFFF6F4EF.toInt(), matRaised = 0xFFFFFFFF.toInt(),
            ink = 0xFF1D2321.toInt(), inkMuted = 0xFF4F5752.toInt(), line = 0xFFCDC8BD.toInt(), stroke = 0xFF827C70.toInt(),
            moulding = 0xFF27302C.toInt(), mouldingInk = 0xFFE7E3D9.toInt(), mouldingMuted = 0xFFAEB5AF.toInt(),
            mouldingLine = 0xFF4A554F.toInt(), railSelected = 0xFFF6F4EF.toInt(), railSelectedInk = 0xFF101722.toInt(),
            railSelectedStroke = 0xFFF6F4EF.toInt(),
            accent = 0xFF2E5E50.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentStroke = 0xFF2E5E50.toInt(),
            accentSoft = 0xFFDCE6E0.toInt(), accentInk = 0xFF24493F.toInt(), todayFill = 0xFFDCE6E0.toInt(), todayMutedInk = 0xFF4F5752.toInt(),
            pastFill = 0xFFD6D3CB.toInt(), pastLine = 0xFFC6C1B6.toInt(), pastInk = 0xFF545B57.toInt(),
            pending = 0xFFF2E3C4.toInt(), pendingInk = 0xFF6B4500.toInt(), alert = 0xFFF4DCD6.toInt(), alertInk = 0xFF8E2A1C.toInt(),
            disabledInk = 0xFF5A625D.toInt(), notice = 0xFF27302C.toInt(), noticeInk = 0xFFF6F4EF.toInt(),
            noticeStroke = 0xFF27302C.toInt(), letterbox = 0xFF1B1F1D.toInt(), scrim = 0x8C101211.toInt(), pressed = 0x241D2321,
        )

        /** Bedside night: dim warm amber on black (ink luminance ~0.32), outlined rather than filled selections. */
        val NIGHT = TouchPalette(
            wall = 0xFF000000.toInt(), mat = 0xFF0F0D0A.toInt(), matRaised = 0xFF17140F.toInt(),
            ink = 0xFFB39668.toInt(), inkMuted = 0xFF8E7A58.toInt(), line = 0xFF2A251C.toInt(), stroke = 0xFF7A6849.toInt(),
            moulding = 0xFF070605.toInt(), mouldingInk = 0xFFA68C61.toInt(), mouldingMuted = 0xFF7E6C4E.toInt(),
            mouldingLine = 0xFF2A251C.toInt(), railSelected = 0xFF2A2318.toInt(), railSelectedInk = 0xFFB39668.toInt(),
            railSelectedStroke = 0xFFB39668.toInt(),
            accent = 0xFF3A2F1E.toInt(), onAccent = 0xFFC9AA74.toInt(), accentStroke = 0xFFB39668.toInt(),
            accentSoft = 0xFF231D14.toInt(), accentInk = 0xFFC9AA74.toInt(), todayFill = 0xFF3A2F1E.toInt(), todayMutedInk = 0xFFB39668.toInt(),
            pastFill = 0xFF000000.toInt(), pastLine = 0xFF3A3226.toInt(), pastInk = 0xFF8A785A.toInt(),
            pending = 0xFF2B2210.toInt(), pendingInk = 0xFFD1A453.toInt(), alert = 0xFF2E1611.toInt(), alertInk = 0xFFD98C78.toInt(),
            disabledInk = 0xFF8E7A58.toInt(), notice = 0xFF17140F.toInt(), noticeInk = 0xFFB39668.toInt(),
            noticeStroke = 0xFF7A6849.toInt(), letterbox = 0xFF000000.toInt(), scrim = 0x99000000.toInt(), pressed = 0x33B39668,
        )

        fun forNight(night: Boolean): TouchPalette = if (night) NIGHT else DAY

        /** WCAG contrast ratio for opaque sRGB colors. */
        fun contrastRatio(foreground: Int, background: Int): Double {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            fun luminance(color: Int): Double = 0.2126 * linear((color ushr 16) and 0xFF) +
                0.7152 * linear((color ushr 8) and 0xFF) + 0.0722 * linear(color and 0xFF)
            val first = luminance(foreground)
            val second = luminance(background)
            return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
        }
    }
}

/** Day/night boundary shared by every native destination (night is 19:00–06:59 local). */
internal object TouchDayNight {
    fun isNight(hour: Int): Boolean = hour < 7 || hour >= 19
}

/**
 * Text sizes in sp and spacing in dp. `Wall` is the 1920×1080 class, `Panel` the 1280×800 class;
 * both panels are mdpi, so these are also the on-glass pixel sizes at default font scale.
 */
internal data class TouchScale(
    val clock: Float,
    val clockSuffix: Float,
    val display: Float,
    val title: Float,
    val headline: Float,
    val body: Float,
    val label: Float,
    val caption: Float,
    val rail: Float,
    val target: Int,
    val stepper: Int,
    val setpoint: Float,
    val pad: Int,
    val gap: Int,
    val radius: Int,
    val radiusSmall: Int,
) {
    companion object {
        val WALL = TouchScale(
            clock = 176f, clockSuffix = 44f, display = 76f, title = 40f, headline = 32f, body = 26f,
            label = 22f, caption = 20f, rail = 18f, target = 72, stepper = 104, setpoint = 104f,
            pad = 48, gap = 32, radius = 20, radiusSmall = 14,
        )
        val PANEL = TouchScale(
            clock = 104f, clockSuffix = 32f, display = 56f, title = 32f, headline = 26f, body = 22f,
            label = 20f, caption = 18f, rail = 15f, target = 72, stepper = 84, setpoint = 80f,
            pad = 32, gap = 24, radius = 20, radiusSmall = 14,
        )

        /**
         * The screen, not the content area, decides the class: a docked notice or the top bar
         * shortens content, and must never drop the 1920×1080 frame into 1280×800 type.
         */
        fun forScreenHeightDp(screenHeightDp: Float): TouchScale = if (screenHeightDp >= WALL_MIN_SCREEN_HEIGHT_DP) WALL else PANEL

        fun forScreen(configuration: android.content.res.Configuration): TouchScale =
            forScreenHeightDp(configuration.screenHeightDp.toFloat())

        const val WALL_MIN_SCREEN_HEIGHT_DP = 1000f
    }
}

/** Bundled Red Hat Display/Text (SIL OFL 1.1, `assets/fonts/OFL.txt`); falls back to system sans. */
internal object TouchFonts {
    enum class Face(val asset: String, val fallbackStyle: Int) {
        TEXT("fonts/RedHatText-Regular.ttf", Typeface.NORMAL),
        TEXT_MEDIUM("fonts/RedHatText-Medium.ttf", Typeface.NORMAL),
        TEXT_BOLD("fonts/RedHatText-Bold.ttf", Typeface.BOLD),
        DISPLAY("fonts/RedHatDisplay-Medium.ttf", Typeface.NORMAL),
        DISPLAY_BOLD("fonts/RedHatDisplay-Bold.ttf", Typeface.BOLD),
    }

    private val cache = HashMap<Face, Typeface>()

    fun get(context: Context, face: Face): Typeface = synchronized(cache) {
        cache.getOrPut(face) {
            runCatching { Typeface.createFromAsset(context.applicationContext.assets, face.asset) }
                .getOrElse { Typeface.create("sans-serif", face.fallbackStyle) }
        }
    }
}

private const val SEGMENT_RADIUS_DP = 8

/** Small programmatic component kit; every native destination builds controls through it. */
internal class TouchKit(private val context: Context, val palette: TouchPalette, val scale: TouchScale) {
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun text(
        value: CharSequence,
        sizeSp: Float,
        face: TouchFonts.Face = TouchFonts.Face.TEXT,
        color: Int = palette.ink,
        maxLines: Int = Int.MAX_VALUE,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = TouchFonts.get(context, face)
        setTextColor(color)
        includeFontPadding = false
        if (maxLines != Int.MAX_VALUE) {
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    fun icon(resource: Int, color: Int, sizeDp: Int): ImageView = ImageView(context).apply {
        setImageDrawable(tinted(resource, color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun tinted(resource: Int, color: Int): Drawable? = context.getDrawable(resource)?.mutate()?.apply { setTint(color) }

    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1, dashed: Boolean = false) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (stroke != null) {
            if (dashed) setStroke(dp(strokeDp), stroke, dp(6).toFloat(), dp(4).toFloat()) else setStroke(dp(strokeDp), stroke)
        }
    }

    fun oval(fill: Int, stroke: Int?, strokeDp: Int = 2) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    /** Mat panel: the primary container on the stone wall. */
    fun panel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.mat, scale.radius, palette.line)
        val inset = dp((scale.pad * 0.6f).toInt())
        setPadding(inset, inset, inset, inset)
    }

    /** Pressed feedback is a color change (ripple masked to the control), never a scale/translate. */
    fun pressable(base: Drawable, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(palette.pressed), base, rounded(0xFFFFFFFF.toInt(), radiusDp))

    enum class Emphasis { PRIMARY, SECONDARY, QUIET }

    /** Text button; disabled uses dashed outline + explicit ink (not alpha) so it stays legible. */
    fun button(
        label: String,
        emphasis: Emphasis = Emphasis.SECONDARY,
        iconRes: Int? = null,
        enabled: Boolean = true,
        sizeSp: Float = scale.label,
        quietInk: Int? = null,
        action: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(scale.target)
        minimumWidth = dp(scale.target)
        setPadding(dp(22), 0, dp(22), 0)
        isClickable = enabled
        isFocusable = enabled
        isEnabled = enabled
        contentDescription = label
        val ink = when {
            !enabled -> palette.disabledInk
            emphasis == Emphasis.PRIMARY -> palette.onAccent
            emphasis == Emphasis.QUIET && quietInk != null -> quietInk
            else -> palette.ink
        }
        val base = when {
            !enabled -> rounded(0x00000000, scale.radiusSmall, palette.disabledInk, 2, dashed = true)
            emphasis == Emphasis.PRIMARY -> rounded(palette.accent, scale.radiusSmall, palette.accentStroke, 2)
            emphasis == Emphasis.QUIET -> rounded(0x00000000, scale.radiusSmall, quietInk ?: palette.stroke, 2)
            else -> rounded(palette.matRaised, scale.radiusSmall, palette.stroke, 2)
        }
        background = if (enabled) pressable(base, scale.radiusSmall) else base
        if (iconRes != null) addView(icon(iconRes, ink, (sizeSp * 1.3f).toInt()).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
        })
        addView(text(label, sizeSp, TouchFonts.Face.TEXT_BOLD, ink, maxLines = 1).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        if (enabled) setOnClickListener { action() }
    }

    /** Square icon-only control with a spoken description. */
    fun iconButton(iconRes: Int, description: String, enabled: Boolean = true, action: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(scale.target)
            minimumWidth = dp(scale.target)
            contentDescription = description
            isClickable = enabled
            isFocusable = enabled
            isEnabled = enabled
            val base = rounded(if (enabled) palette.matRaised else 0x00000000, scale.radiusSmall,
                if (enabled) palette.stroke else palette.disabledInk, 2, dashed = !enabled)
            background = if (enabled) pressable(base, scale.radiusSmall) else base
            addView(icon(iconRes, if (enabled) palette.ink else palette.disabledInk, 30))
            if (enabled) setOnClickListener { action() }
        }

    /** Segmented choice: selected segment is filled accent and announced as selected. */
    fun <T> segmented(
        options: List<Pair<String, T>>,
        selected: T?,
        enabled: Boolean,
        sizeSp: Float,
        onSelect: (T) -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(if (enabled) palette.matRaised else 0x00000000, scale.radiusSmall,
            if (enabled) palette.stroke else palette.disabledInk, 2, dashed = !enabled)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        options.forEachIndexed { index, (label, value) ->
            val isSelected = value == selected
            val segment = text(label, sizeSp, if (isSelected) TouchFonts.Face.TEXT_BOLD else TouchFonts.Face.TEXT_MEDIUM,
                when {
                    !enabled -> palette.disabledInk
                    isSelected -> palette.onAccent
                    else -> palette.ink
                }, maxLines = 1).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0)
                minHeight = dp(scale.target - 8)
                this.isSelected = isSelected
                isClickable = enabled && !isSelected
                isFocusable = enabled
                contentDescription = label
                val base = when {
                    isSelected && enabled -> rounded(palette.accent, SEGMENT_RADIUS_DP, palette.accentStroke, 2)
                    isSelected -> rounded(0x00000000, SEGMENT_RADIUS_DP, palette.disabledInk, 2)
                    else -> rounded(0x00000000, SEGMENT_RADIUS_DP)
                }
                background = if (enabled && !isSelected) pressable(base, SEGMENT_RADIUS_DP) else base
                if (enabled && !isSelected) setOnClickListener { onSelect(value) }
            }
            addView(segment, LinearLayout.LayoutParams(0, dp(scale.target - 8), 1f).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
    }

    enum class Tone { PENDING, OK, ERROR }

    /** Small state chip (Not sent yet, Sending…, Set to 72°, Couldn't confirm). Icon + text, not color alone. */
    fun chip(label: String, tone: Tone): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val (fill, ink, iconRes) = when (tone) {
            Tone.PENDING -> Triple(palette.pending, palette.pendingInk, null)
            Tone.OK -> Triple(palette.accentSoft, palette.accentInk, R.drawable.ic_touch_check)
            Tone.ERROR -> Triple(palette.alert, palette.alertInk, R.drawable.ic_touch_alert)
        }
        background = rounded(fill, 999)
        setPadding(dp(14), dp(5), dp(16), dp(5))
        if (iconRes != null) addView(icon(iconRes, ink, 22).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        })
        addView(text(label, scale.caption, TouchFonts.Face.TEXT_BOLD, ink))
    }

    /** A solid rule along the start edge (timed events, Today markers): structure without a full border. */
    fun startRule(color: Int, widthDp: Int): Drawable = StartRuleDrawable(color, dp(widthDp))

    /** Hairline used between list groups. */
    fun hairline(): View = View(context).apply { setBackgroundColor(palette.line) }
}

private class StartRuleDrawable(color: Int, private val widthPx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), (bounds.left + widthPx).toFloat(), bounds.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Required by Drawable on API 23", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
