package com.jake.duolauncher

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Pure model of the lock screen's customisation ("自定锁屏", iOS 16–26): the clock's font, colour
 * and iOS 26 size handle, the accessory widget row with its 4-unit slot model and its position,
 * and the text form it is persisted in. No Android types: JVM-tested in LockScreenCustomizationTest.
 */

/** Clock faces offered by the 「时间」 sheet (iOS: SF Pro Rounded/Display weights plus condensed/expanded). */
internal enum class LockClockFont(val key: String, val zh: String, val en: String, val weight: Int,
    /** Horizontal scale of the glyphs: the condensed and extended variants of the same face. */
    val widthScale: Float = 1f,
    /** The project's Roboto-Flex digit face (ClockWidgets), used by the StandBy and Clock widgets. */
    val duoDigits: Boolean = false,
) {
    /** iOS 26 editor reference: stems 0.191 of the digit height ≈ Inter Display Bold (0.199; SemiBold 0.171). */
    DEFAULT("default", "默认", "Default", 700),
    /** One step heavier than the default: Inter Display Bold with Android's fake bold (no Black cut bundled). */
    BOLD("bold", "粗体", "Bold", 800),
    LIGHT("light", "细体", "Light", 500),
    DIGITS("digits", "时钟数字", "Clock digits", 600, duoDigits = true),
    CONDENSED("condensed", "紧凑", "Condensed", 600, widthScale = .78f),
    EXPANDED("expanded", "加宽", "Expanded", 700, widthScale = 1.22f);

    companion object { fun fromKey(key: String?): LockClockFont = entries.firstOrNull { it.key == key } ?: DEFAULT }
}

/** The accessory widgets a lock screen can carry (data the launcher already has). */
internal enum class LockWidgetKind(val key: String, val units: Int, val zh: String, val en: String) {
    WEATHER("weather", 2, "天气", "Weather"),
    BATTERY("battery", 1, "电池", "Battery"),
    CALENDAR_DATE("calendar", 1, "日期", "Date"),
    NEXT_EVENT("event", 2, "下一个日程", "Next event"),
    ALARM("alarm", 2, "下一个闹钟", "Next alarm"),
    WORLD_CLOCK("worldclock", 1, "世界时钟", "World clock");

    val circular: Boolean get() = units == 1

    companion object { fun fromKey(key: String?): LockWidgetKind? = entries.firstOrNull { it.key == key } }
}

internal enum class LockWidgetPosition(val key: String, val zh: String, val en: String) {
    /** iOS 26 "小组件位置：底部" — the row rides in the glass slab (the owner's recording). */
    BOTTOM("bottom", "底部", "Bottom"),
    /** iOS 16 style: under the date and clock. */
    UNDER_DATE("under_date", "时间下方", "Below time");

    companion object { fun fromKey(key: String?): LockWidgetPosition = entries.firstOrNull { it.key == key } ?: BOTTOM }
}

/**
 * Round 12 「对齐」: where the time, the date and the under-date widget row sit horizontally. iOS
 * centres the clock on the panel (0.5 W) and offers no control; the owner asked for one ("没办法调整
 * 位置"), so 居中 is the default and the two others anchor the block to the text area's edges
 * ([LockScreenGeometry.textAreaLeft] / [textAreaRight]: inside the widget row's 44 pt inset and
 * clear of the side column).
 */
internal enum class LockTextAlign(val key: String, val zh: String, val en: String) {
    CENTER("center", "居中", "Center"),
    LEFT("left", "靠左", "Left"),
    RIGHT("right", "靠右", "Right");

    companion object { fun fromKey(key: String?): LockTextAlign = entries.firstOrNull { it.key == key } ?: CENTER }
}

/** iOS slot model: four units in the row, a circular widget takes one, a rectangular one two. */
internal object LockWidgetSlots {
    const val UNITS = 4
    fun used(widgets: List<LockWidgetKind>): Int = widgets.sumOf { it.units }
    fun canAdd(widgets: List<LockWidgetKind>, kind: LockWidgetKind): Boolean = used(widgets) + kind.units <= UNITS
    fun add(widgets: List<LockWidgetKind>, kind: LockWidgetKind): List<LockWidgetKind> =
        if (canAdd(widgets, kind)) widgets + kind else widgets
    fun remove(widgets: List<LockWidgetKind>, index: Int): List<LockWidgetKind> =
        if (index in widgets.indices) widgets.filterIndexed { i, _ -> i != index } else widgets
    fun move(widgets: List<LockWidgetKind>, from: Int, to: Int): List<LockWidgetKind> {
        if (from !in widgets.indices || to !in widgets.indices || from == to) return widgets
        val list = widgets.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }
    /** Which slot (0..3) each widget starts at, in order. */
    fun startUnits(widgets: List<LockWidgetKind>): List<Int> {
        var unit = 0
        return widgets.map { val start = unit; unit += it.units; start }
    }
    /** The default row of the owner's recording: weather (rectangular), a ring, the battery ring. */
    val DEFAULT = listOf(LockWidgetKind.WEATHER, LockWidgetKind.CALENDAR_DATE, LockWidgetKind.BATTERY)
}

/**
 * iOS 26 size handle at the clock's bottom-right corner: dragging it stretches the time. The glyphs
 * grow in height up to [MAX_HEIGHT_SCALE] (the owner's recording shows ≈ 2.7× the default digit
 * height at 1320×2868, f001: 570 px digits vs ≈ 210 px default) and in width by less, never past the
 * panel; [stretch] is the persisted 0..1 position of the handle.
 */
internal object LockClockSize {
    const val MAX_HEIGHT_SCALE = 2.2f
    const val MAX_WIDTH_SCALE = 1.35f
    fun heightScale(stretch: Float): Float = 1f + (MAX_HEIGHT_SCALE - 1f) * stretch.coerceIn(0f, 1f)
    fun widthScale(stretch: Float): Float = 1f + (MAX_WIDTH_SCALE - 1f) * stretch.coerceIn(0f, 1f)
    /** The handle's drag: [dragDp] of downward travel over one default digit height moves it to the end. */
    fun stretchAfterDrag(stretch: Float, dragDp: Float, digitHeightDp: Float): Float =
        if (digitHeightDp <= 0f) stretch else (stretch + dragDp / (digitHeightDp * (MAX_HEIGHT_SCALE - 1f))).coerceIn(0f, 1f)
    /** Pixels of handle travel along its outward diagonal: the larger of the two directions, signed by their sum. */
    fun handleTravel(dx: Float, dy: Float): Float {
        val along = (dx + dy) / kotlin.math.sqrt(2f)
        val dominant = if (abs(dx) > abs(dy)) dx else dy
        return if (abs(dominant) > abs(along)) dominant else along
    }
    /** Horizontal scale that keeps the time inside [maxWidthDp] when its natural width is [naturalWidthDp]. */
    fun fittedWidthScale(stretch: Float, naturalWidthDp: Float, maxWidthDp: Float): Float {
        val wanted = widthScale(stretch)
        if (naturalWidthDp <= 0f || maxWidthDp <= 0f) return wanted
        return minOf(wanted, maxWidthDp / naturalWidthDp).coerceAtLeast(.5f)
    }
}

/**
 * Round 13 (owner: the lock clock's font looks strange). A static face can only be stretched by scaling it
 * unevenly (textScaleX = width ÷ height): Inter at the owner's 0.77 stretch is squeezed to ≈ 0.66, its stems
 * thin below its bars (37 vs 47 px on the Core.29.3 screenshot) and the colon turns into 37 × 67 px ovals.
 * iOS 26 grows SF Pro's height axis instead (r13/3.webp: stems 0.092 of the digit height, round colon). The
 * condensed digit face (Roboto Flex wdth 48) is already tall and narrow, so whichever face needs a scale
 * closer to 1 draws: Inter up to ≈ 0.28 stretch, the digit face above.
 */
internal object LockClockFace {
    fun preferCondensed(chosenScaleX: Float, condensedScaleX: Float): Boolean =
        condensedScaleX > 0f && chosenScaleX > 0f &&
            kotlin.math.abs(kotlin.math.ln(condensedScaleX)) < kotlin.math.abs(kotlin.math.ln(chosenScaleX))
}

/** iOS lock-screen colour row: white first, then Apple's swatches; the slider adds any hue. */
internal object LockClockColors {
    const val WHITE = 0xFFFFFFFF.toInt()
    val swatches: List<Int> = listOf(
        WHITE, 0xFFE5E5EA.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(), 0xFFFF375F.toInt(),
        0xFFBF5AF2.toInt(), 0xFF0A84FF.toInt(), 0xFF64D2FF.toInt(), 0xFF34C759.toInt(), 0xFF8E8E93.toInt(),
    )
    /** Hue 0..360 and vibrancy 0..1 (iOS: the slider's saturation) to an opaque ARGB colour. */
    fun fromHue(hue: Float, vibrancy: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = vibrancy.coerceIn(0f, 1f)
        val c = s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = 1f - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f); h < 120f -> Triple(x, c, 0f); h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c); h < 300f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
    /** Hue (0..360) and vibrancy (0..1) of an ARGB colour, for the slider's position. */
    fun hueAndVibrancy(argb: Int): Pair<Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f; val g = ((argb shr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val s = if (max == 0f) 0f else d / max
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return ((h + 360f) % 360f) to s
    }
}

/** Everything the lock screen is customised with; persisted as one JSON string in `duo18_lock_screen`. */
internal data class LockScreenConfig(
    val font: LockClockFont = LockClockFont.DEFAULT,
    val color: Int = LockClockColors.WHITE,
    /** Round 13: iOS 26's "Glass" clock (the default there and here); a colour swatch turns it off. */
    val glass: Boolean = true,
    /** Position of the size handle, 0 = the default clock, 1 = the tallest. */
    val stretch: Float = 0f,
    val widgets: List<LockWidgetKind> = LockWidgetSlots.DEFAULT,
    val position: LockWidgetPosition = LockWidgetPosition.BOTTOM,
    /** Round 12: horizontal alignment of the time, the date and the under-date row. */
    val align: LockTextAlign = LockTextAlign.CENTER,
    /** Round 12.4: the Chinese lunar date after the date line (iOS 「中国农历」); only drawn in a Chinese locale. */
    val lunar: Boolean = true,
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"font\":\"").append(font.key).append("\",")
        append("\"align\":\"").append(align.key).append("\",")
        append("\"lunar\":").append(lunar.toString()).append(',')
        append("\"glass\":").append(glass.toString()).append(',')
        append("\"color\":\"").append(String.format("%08X", color)).append("\",")
        append("\"stretch\":").append(stretch.coerceIn(0f, 1f).toString()).append(',')
        append("\"position\":\"").append(position.key).append("\",")
        append("\"widgets\":[").append(widgets.joinToString(",") { "\"${it.key}\"" }).append(']')
        append('}')
    }

    companion object {
        val DEFAULT = LockScreenConfig()

        /** Tolerant reader: an unknown or damaged value falls back to the default for that field only. */
        fun fromJson(json: String?): LockScreenConfig {
            if (json.isNullOrBlank()) return DEFAULT
            fun string(key: String): String? =
                Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
            fun number(key: String): Float? =
                Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull()
            val widgetList = Regex("\"widgets\"\\s*:\\s*\\[([^\\]]*)\\]").find(json)?.groupValues?.get(1)
            val widgets = widgetList?.let { list ->
                Regex("\"([^\"]*)\"").findAll(list).mapNotNull { LockWidgetKind.fromKey(it.groupValues[1]) }.toList()
            }
            // A persisted row never exceeds the four units; drop the tail if it somehow does.
            val fitted = (widgets ?: LockWidgetSlots.DEFAULT).fold(emptyList<LockWidgetKind>()) { acc, k -> LockWidgetSlots.add(acc, k) }
            val color = string("color")?.toLongOrNull(16)?.toInt() ?: LockClockColors.WHITE
            return LockScreenConfig(
                font = LockClockFont.fromKey(string("font")),
                color = color or (0xFF shl 24),
                stretch = number("stretch")?.coerceIn(0f, 1f) ?: 0f,
                widgets = if (widgetList == null) LockWidgetSlots.DEFAULT else fitted,
                position = LockWidgetPosition.fromKey(string("position")),
                align = LockTextAlign.fromKey(string("align")),
                glass = Regex("\"glass\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1) != "false",
                lunar = Regex("\"lunar\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1) != "false",
            )
        }
    }
}
