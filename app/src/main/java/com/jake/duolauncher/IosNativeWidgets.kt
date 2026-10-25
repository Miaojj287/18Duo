package com.jake.duolauncher

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min

// =======================================================================================
// 18Duo's own iOS widgets: registry, ids/keys, shared card + typography, entry point.
//
// Every widget is one [IosWidgetSpec] = one iOS widget kind at one family, e.g. Clock ▸
// "World Clock" ▸ Medium. Specs live next to their renderers, one file per app
// (ClockWidgets.kt, CalendarWidgets.kt, WeatherWidgets.kt, BatteryWidgets.kt, NotesWidgets.kt,
// RemindersWidgets.kt, MapsWidgets.kt, …), each
// exposing `internal fun <app>WidgetSpecs(): List<IosWidgetSpec>`, and are listed ONCE in
// [iosWidgetSpecs]. Adding a widget = adding a spec there; nothing else needs to change:
// Home/Today rendering, the gallery (featured + per-app size pages), the size menu, resize,
// persistence and backups all read the registry.
//
// Persistence: placements keep the spec's numeric [IosWidgetSpec.id] AND its readable
// [IosWidgetSpec.key] ("native:<widget>:<family>"); the key wins on load. Never renumber an
// id or rename a key once shipped. Id blocks (all inside NATIVE_WIDGET_ID_RANGE):
//   -100 … -119  legacy Core.16/17 widgets (battery, clock, calendar, weather, photos)
//   -120 … -139  Clock          -140 … -159  Calendar
//   -160 … -179  Weather
//   -180 … -199  Battery large -180; Notes -184 / -185; Reminders -187 / -188; Maps -190 / -191
//                (-181 … -183, -186, -189, -192 … -199 spare)
// =======================================================================================

/** iOS widget families on the Duo grid, with the iPhone point size each is designed at. */
enum class NativeWidgetSize(val key: String, val spanX: Int, val spanY: Int, val widthPt: Float, val heightPt: Float) {
    SMALL("small", 2, 2, 158f, 158f), MEDIUM("medium", 4, 2, 338f, 158f), LARGE("large", 4, 4, 338f, 354f);

    val span get() = WidgetSpan(spanX, spanY)
    val label: String get() = when (this) {
        SMALL -> uxText("小", "Small")
        MEDIUM -> uxText("中", "Medium")
        LARGE -> uxText("大", "Large")
    }
}

/** The app a widget belongs to: its gallery row and size pages, row icon and tap destination. */
enum class NativeWidgetKind(val key: String) {
    BATTERY("battery"), CLOCK("clock"), CALENDAR("calendar"), WEATHER("weather"), PHOTOS("photos"),
    NOTES("notes"), REMINDERS("reminders"), MAPS("maps");

    val label: String get() = when (this) {
        BATTERY -> uxText("电池", "Batteries")
        CLOCK -> uxText("时钟", "Clock")
        CALENDAR -> uxText("日历", "Calendar")
        WEATHER -> uxText("天气", "Weather")
        PHOTOS -> uxText("照片", "Photos")
        NOTES -> uxText("备忘录", "Notes")
        REMINDERS -> uxText("提醒事项", "Reminders")
        MAPS -> uxText("地图", "Maps")
    }
}

/**
 * One 18Duo widget (an iOS widget kind at one family).
 *
 * @param id reserved negative id from the app's block (see the file header); persisted.
 * @param widget kind within the app ("city", "worldclock", "upnext" …). Specs sharing
 *   [app] + [widget] are the sizes of ONE widget: the context menu's size row and resize
 *   switch between them, and the gallery shows them as consecutive pages. Families of one
 *   widget must be contiguous (small+medium, medium+large, small+medium+large).
 * @param featured position in the gallery's 精选 section (lower first); null = not featured.
 * @param key persisted identity; only legacy specs override the default.
 * @param opens tap destination (iOS: the widget's app). The default tap is attached to
 *   [IosWidgetContext.modifier]; leave null when the widget handles taps itself.
 * @param render draws the widget, normally `IosWidgetCard(modifier, family) { s -> … }`.
 */
@Immutable
internal class IosWidgetSpec(
    val id: Int,
    val app: NativeWidgetKind,
    val widget: String,
    val family: NativeWidgetSize,
    val nameZh: String,
    val nameEn: String,
    val descriptionZh: String,
    val descriptionEn: String,
    val featured: Int? = null,
    val key: String = "native:$widget:${family.key}",
    val opens: ((Context) -> Intent?)? = null,
    val render: @Composable IosWidgetContext.() -> Unit,
) {
    /** Gallery page title (iOS: the widget's display name, e.g. 世界时钟). */
    val name: String get() = uxText(nameZh, nameEn)
    val description: String get() = uxText(descriptionZh, descriptionEn)
    val span: WidgetSpan get() = family.span
    override fun toString() = key
}

/** What a spec's [IosWidgetSpec.render] gets. */
@Stable
internal class IosWidgetContext(
    val spec: IosWidgetSpec,
    /** Gallery preview (and drag ghost from the gallery): sample data is allowed here — and ONLY here. */
    val preview: Boolean,
    /** Placed on Home / Today View and not jiggling: taps may act. False in the gallery. */
    val interactive: Boolean,
    /** Semantics, test tag and, when [IosWidgetSpec.opens] is set, the tap that opens the app. */
    val modifier: Modifier,
    /** [modifier] without the default tap, for widgets that own their taps (Calendar asks for access). */
    val bareModifier: Modifier,
) {
    val family: NativeWidgetSize get() = spec.family
}

/** Every 18Duo widget, in gallery order (per app: widget by widget, small → large). */
internal val iosWidgetSpecs: List<IosWidgetSpec> by lazy {
    clockWidgetSpecs() + calendarWidgetSpecs() + weatherWidgetSpecs() + batteryWidgetSpecs() + photosWidgetSpecs() +
        notesWidgetSpecs() + remindersWidgetSpecs() + mapsWidgetSpecs()
}

private val specsById: Map<Int, IosWidgetSpec> by lazy { iosWidgetSpecs.associateBy { it.id } }
private val specsByKey: Map<String, IosWidgetSpec> by lazy { iosWidgetSpecs.associateBy { it.key } }

/** Registry invariants (unit-tested): unique ids/keys inside the reserved range, contiguous families. */
internal fun iosWidgetRegistryProblems(specs: List<IosWidgetSpec>): List<String> {
    val problems = mutableListOf<String>()
    specs.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { problems += "duplicate id $it" }
    specs.groupBy { it.key }.filterValues { it.size > 1 }.keys.forEach { problems += "duplicate key $it" }
    specs.filter { it.id !in NATIVE_WIDGET_ID_RANGE }.forEach { problems += "id ${it.id} of ${it.key} outside the reserved range" }
    specs.filter { it.key.split(':').size != 3 || !it.key.startsWith("native:") }.forEach { problems += "malformed key ${it.key}" }
    specs.groupBy { it.app to it.widget }.forEach { (group, members) ->
        val ordinals = members.map { it.family.ordinal }.sorted()
        if (ordinals.distinct().size != ordinals.size) problems += "${group.second} repeats a family"
        if (ordinals.last() - ordinals.first() + 1 != ordinals.distinct().size) problems += "${group.second} skips a family"
    }
    return problems
}

const val NATIVE_WIDGET_ID_BASE = -100
/** Reserved for 18Duo's widgets; anything here that no longer resolves is dropped on load. */
val NATIVE_WIDGET_ID_RANGE = -199..NATIVE_WIDGET_ID_BASE

internal fun nativeWidgetSpec(id: Int): IosWidgetSpec? = if (id in NATIVE_WIDGET_ID_RANGE) specsById[id] else null

/** The sizes of [spec]'s widget (itself included), small → large. */
internal fun nativeWidgetSiblings(spec: IosWidgetSpec): List<IosWidgetSpec> =
    iosWidgetSpecs.filter { it.app == spec.app && it.widget == spec.widget }.sortedBy { it.family.ordinal }

/** The app's first widget at [size] (the one a bare "Clock · Small" means). */
fun nativeWidgetId(kind: NativeWidgetKind, size: NativeWidgetSize): Int =
    iosWidgetSpecs.firstOrNull { it.app == kind && it.family == size }?.id
        ?: error("18Duo has no ${kind.key} widget in ${size.key}")

fun nativeWidgetKind(id: Int): NativeWidgetKind? = nativeWidgetSpec(id)?.app

fun nativeWidgetSize(id: Int): NativeWidgetSize? = nativeWidgetSpec(id)?.family

fun isNativeWidgetId(id: Int): Boolean = nativeWidgetSpec(id) != null

/** Stable textual identity written next to the numeric id, e.g. `native:worldclock:large`. */
fun nativeWidgetKey(id: Int): String? = nativeWidgetSpec(id)?.key

fun parseNativeWidgetKey(key: String?): Int? = key?.trim()?.let { specsByKey[it]?.id }

/**
 * Migration-safe id resolution for persisted placements: the key wins when present (an
 * unknown key means a newer build wrote it, so the placement is skipped rather than shown
 * as a broken host widget); a bare id inside the reserved range must still resolve.
 */
fun resolvePersistedWidgetId(id: Int, nativeKey: String?): Int? = when {
    nativeKey != null -> parseNativeWidgetKey(nativeKey)
    id in NATIVE_WIDGET_ID_RANGE -> id.takeIf(::isNativeWidgetId)
    else -> id
}

fun nativeWidgetSpanConstraints(id: Int): WidgetSpanConstraints? {
    val spec = nativeWidgetSpec(id) ?: return null
    val families = nativeWidgetSiblings(spec).map { it.family }
    return WidgetSpanConstraints(
        preferred = spec.family.span,
        minimum = WidgetSpan(families.minOf { it.spanX }, families.minOf { it.spanY }),
        maximum = WidgetSpan(families.maxOf { it.spanX }, families.maxOf { it.spanY }),
        canResizeHorizontally = families.map { it.spanX }.distinct().size > 1,
        canResizeVertically = families.map { it.spanY }.distinct().size > 1,
    )
}

/** iOS has no 3-wide widgets: a horizontal resize snaps to small or medium width. */
fun nativeWidgetSnapSpanX(spanX: Int): Int = if (spanX >= 3) 4 else 2

/** The family of [available] a free-form [spanX]×[spanY] resize lands on (nearest iOS size). */
internal fun nativeWidgetFamilyForSpan(available: List<NativeWidgetSize>, spanX: Int, spanY: Int): NativeWidgetSize? {
    val wanted = when {
        spanY >= 3 -> NativeWidgetSize.LARGE
        spanX >= 3 -> NativeWidgetSize.MEDIUM
        else -> NativeWidgetSize.SMALL
    }
    return available.minByOrNull { abs(it.ordinal - wanted.ordinal) * 2 + if (it.ordinal > wanted.ordinal) 1 else 0 }
}

/** The sibling of [id]'s widget at the iOS size nearest to [spanX]×[spanY]; host ids pass through. */
fun nativeWidgetIdForSpan(id: Int, spanX: Int, spanY: Int): Int {
    val spec = nativeWidgetSpec(id) ?: return id
    val siblings = nativeWidgetSiblings(spec)
    val family = nativeWidgetFamilyForSpan(siblings.map { it.family }, spanX, spanY) ?: return id
    return siblings.first { it.family == family }.id
}

/** "时钟 · 世界时钟 · 中": app, widget (when it differs from the app) and size. */
fun nativeWidgetLabel(id: Int): String? {
    val spec = nativeWidgetSpec(id) ?: return null
    val parts = listOf(spec.app.label, spec.name).distinct() + spec.family.label
    return parts.joinToString(" · ")
}

/** Gallery / placement handle for one spec (kept for the gallery and LauncherScreen call sites). */
internal data class NativeWidgetEntry(val spec: IosWidgetSpec) {
    val id get() = spec.id
    val kind get() = spec.app
    val size get() = spec.family
    val label get() = spec.app.label
    val span get() = spec.family.span
}

internal fun nativeWidgetCatalog(): List<NativeWidgetEntry> = iosWidgetSpecs.map(::NativeWidgetEntry)

// =======================================================================================
// Shared rendering. Content is laid out in iPhone POINTS for the spec's family (small 158,
// medium 338×158, large 338×354), scaled uniformly to the card, so one renderer draws the
// gallery preview, the drag ghost and the placed widget at Apple's proportions. Colours come
// from [widgetColors]: opaque white / graphite cards that follow the system appearance (or the
// user's Light / Dark / Clear choice) exactly like iOS widgets.
// =======================================================================================

internal val LocalWidgetColors = staticCompositionLocalOf { widgetColors(WidgetTone.DARK) }
private val ClearTextShadow = Shadow(Color.Black.copy(alpha = .35f), Offset(0f, 1f), 4f)

/** Converts iPhone points of the widget design into this card's dp / sp. */
@Stable
internal class WidgetScale(val unit: Float, private val density: Density) {
    fun pt(value: Float): Dp = (value * unit).dp
    fun sp(value: Float): TextUnit = with(density) { (value * unit).dp.toSp() }
    fun px(value: Float): Float = with(density) { (value * unit).dp.toPx() }
}

/** Points → dp factor for a [family] card of [widthDp]×[heightDp] (uniform, never stretched). */
internal fun widgetPointScale(family: NativeWidgetSize, widthDp: Float, heightDp: Float): Float =
    min(widthDp / family.widthPt, heightDp / family.heightPt).coerceAtLeast(.01f)

/** Forces one tone (review galleries / tests); null = the user's style. */
internal val LocalWidgetToneOverride = staticCompositionLocalOf<WidgetTone?> { null }

/** The tone Home Screen widgets draw in right now: the user's style resolved against Light/Dark. */
@Composable
internal fun currentWidgetTone(): WidgetTone {
    LocalWidgetToneOverride.current?.let { return it }
    val style = (androidx.activity.compose.LocalActivity.current as? MainActivity)?.corePreferences?.widgetStyle ?: WidgetStyle.AUTO
    return resolveWidgetTone(style, LocalDuoPalette.current.dark)
}

/** Gallery previews sit on the sheet's own grouped background; a soft shadow + hairline separate them (iOS). */
private val LocalWidgetPreview = staticCompositionLocalOf { false }

/**
 * The iOS widget card: continuous corners, opaque systemBackground (Light/Dark) or the Clear
 * Liquid Glass veil, and content in iPhone points for [family]. [background] replaces the card
 * fill (Weather's sky, a photo); Clear still turns it to glass when it passes null.
 */
@Composable
internal fun IosWidgetCard(modifier: Modifier, family: NativeWidgetSize,
    background: (@Composable BoxScope.(WidgetScale) -> Unit)? = null,
    content: @Composable BoxScope.(WidgetScale) -> Unit) {
    val colors = LocalWidgetColors.current
    val preview = LocalWidgetPreview.current
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val scale = remember(maxWidth, maxHeight, density, family) {
            WidgetScale(widgetPointScale(family, maxWidth.value, maxHeight.value), density)
        }
        val shape = ContinuousRoundedShape(nativeWidgetCornerRadius(maxWidth.value, maxHeight.value).dp)
        val surface = when {
            background != null -> Modifier
            // Clear: frosted Liquid Glass under one uniform veil, so sky and mountain band read alike.
            colors.clear -> Modifier.duoGlass(DuoGlassRole.WidgetFrame, shape, frost = colors.background)
            else -> Modifier.background(colors.background, shape)
        }
        val lift = if (preview && !colors.clear) Modifier.shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = .12f),
            spotColor = Color.Black.copy(alpha = .18f)).border(.5.dp, colors.dialRim, shape) else Modifier
        Box(Modifier.fillMaxSize().then(lift).clip(shape).then(surface)) {
            background?.invoke(this, scale)
            ProvideTextStyle(if (colors.clear) TextStyle(shadow = ClearTextShadow) else TextStyle()) { content(scale) }
        }
    }
}

/**
 * iOS 26's dark widget platter as it reads on the owner's iPhone Duo (round 21, 1.webp / 4.webp):
 * not the flat #1C1C1E of earlier releases but a soft top-lit ramp, measured (34, 34, 34) at the
 * top edge to (12, 12, 12) at the bottom on the Calendar and Reminders cards. Pass as
 * [IosWidgetCard]'s background; null in Light and Clear, which keep their own surfaces.
 */
internal fun darkWidgetPlatter(colors: WidgetColors): (@Composable BoxScope.(WidgetScale) -> Unit)? =
    if (colors.clear || colors.background.luminance() > .5f) null
    else { _ -> Box(Modifier.fillMaxSize().background(DarkPlatterBrush)) }

private val DarkPlatterBrush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF222222), Color(0xFF0C0C0C)))

/**
 * Side (dp) of a SMALL widget on the grid that holds a card of [widthDp]×[heightDp]: medium
 * cards are a small high, large cards are about two smalls plus a gap (iOS 158 of 354 pt).
 */
internal fun widgetSmallSide(widthDp: Float, heightDp: Float): Float = when {
    widthDp > heightDp * 1.6f -> heightDp
    // A large card is ≥ 2 smalls wide; a small one is square. The two never overlap in size
    // (the biggest small, 170 dp, is far below the smallest large, ~300 dp).
    min(widthDp, heightDp) > 230f -> min(widthDp / NativeWidgetSize.LARGE.widthPt, heightDp / NativeWidgetSize.LARGE.heightPt) *
        NativeWidgetSize.SMALL.heightPt
    else -> min(widthDp, heightDp)
}

/**
 * Corner radius (dp) of a native widget card: every iOS family shares the small widget's
 * radius, 0.17 × its side on iPhone Duo (BRIEF, measured on hig/grid-outer.png) — ≈ 24 dp on
 * the cover's 143 dp card, matching the Home slot clip ([WIDGET_SLOT_CORNER_DP]).
 */
internal fun nativeWidgetCornerRadius(widthDp: Float, heightDp: Float): Float =
    (widgetSmallSide(widthDp, heightDp) * .17f).coerceAtLeast(12f)

/**
 * The name iOS prints under a Home Screen widget: its app's name ("Weather", "Calendar" on the
 * iPhone Duo Home Screen), for 18Duo's widgets and Android providers alike; null when unknown.
 */
internal fun widgetAppName(id: Int, context: Context, manager: android.appwidget.AppWidgetManager): String? {
    nativeWidgetSpec(id)?.let { return it.app.label }
    if (id < 0) return null
    return runCatching {
        val info = manager.getAppWidgetInfo(id) ?: return null
        val apps = context.getSystemService(android.content.pm.LauncherApps::class.java)
        apps.getApplicationInfo(info.provider.packageName, 0, info.profile).loadLabel(context.packageManager).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Opens [intent] as a new task (iOS: tapping a widget opens its app). */
internal fun openWidgetApp(context: Context, intent: Intent?) {
    if (intent != null) runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Opens a widget's app the way an icon does, zooming out of [tile]; false if it is not a launcher app. */
internal fun interface WidgetAppOpener { fun open(intent: Intent, tile: WidgetTile?): Boolean }

/**
 * The launcher app a widget's [intent] opens (all of 18Duo's widgets open an app's main screen):
 * its exact component, else the personal app of the package the intent resolves to; null for a
 * chooser or an app that is not on the launcher (the caller then starts the intent as it is).
 */
internal fun resolveWidgetApp(context: Context, intent: Intent, apps: List<AppEntry>): AppEntry? {
    val personal = apps.filter { it.isPersonal && it.available }
    intent.component?.let { component -> personal.firstOrNull { it.component == component }?.let { return it } }
    val pkg = intent.component?.packageName ?: intent.`package` ?: runCatching {
        context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it != "android" } ?: return null
    return personal.firstOrNull { it.packageName == pkg }
}

/** An Android widget of [packageName] was tapped in [tile] (it may be starting its app itself). */
internal fun interface ProviderWidgetTap { fun tapped(packageName: String, tile: WidgetTile) }
internal val LocalProviderWidgetTap = staticCompositionLocalOf<ProviderWidgetTap?> { null }

/** A provider widget's tap waiting to see whether Home is left: [art] arrives a frame later. */
internal class PendingProviderTap(val app: AppEntry, val tile: WidgetTile, val window: androidx.compose.ui.geometry.Rect,
    val at: Long) {
    @Volatile var art: android.graphics.Bitmap? = null
}

/** Home paused this soon after a provider widget's tap: the tap started the app. */
internal const val PROVIDER_TAP_LAUNCH_MS = 800L

/** A short, still tap anywhere in the widget, observed on the Initial pass without consuming anything. */
internal suspend fun PointerInputScope.detectProviderWidgetTap(onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (event.changes.size > 1 || (change.position - down.position).getDistance() > slop) return@awaitEachGesture
            if (!change.pressed) {
                if (change.uptimeMillis - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis) onTap()
                return@awaitEachGesture
            }
        }
    }
}

/** Provided by Home: launches through the icon pipeline (zoom, launch origin, return flight). */
internal val LocalWidgetAppOpener = staticCompositionLocalOf<WidgetAppOpener?> { null }
/** The card the widget being composed sits in (see [WidgetTile]). */
internal val LocalWidgetTile = staticCompositionLocalOf<WidgetTile?> { null }

/**
 * Tapping a widget opens its app like its icon would (iOS): the app zooms out of the widget and a
 * swipe home shrinks it back into the widget. Before round 9 the widget started the app directly,
 * so Android slid it in from the side and the swipe home had nowhere to land.
 */
@Composable
internal fun rememberWidgetAppOpener(): (Intent?) -> Unit {
    val context = LocalContext.current
    val tile = LocalWidgetTile.current
    val opener = LocalWidgetAppOpener.current
    return remember(context, tile, opener) { { intent ->
        if (intent != null && opener?.open(intent, tile) != true) openWidgetApp(context, intent)
    } }
}

/**
 * iOS widgets never dim while touched. The default [IosDimIndication] would also be what the
 * long-press PixelCopy records for the lifted context-menu preview (the finger is still down),
 * leaving a 32 % ghost of the card over the sharp wallpaper (final verification R2).
 */
internal fun Modifier.widgetTap(enabled: Boolean, onTap: () -> Unit): Modifier =
    if (!enabled) this else clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onTap)

/** Re-evaluates every second, aligned to the wall clock, so second hands tick in step. */
@Composable
internal fun rememberTickingTime(): ZonedDateTime {
    val time = produceState(ZonedDateTime.now()) {
        while (true) {
            val now = ZonedDateTime.now()
            value = now
            delay(1000L - now.nano / 1_000_000L)
        }
    }
    return time.value
}

/** Re-evaluates on every wall-clock minute (digital faces, event lists): no per-second recomposition. */
@Composable
internal fun rememberMinuteTime(): ZonedDateTime {
    val time = produceState(ZonedDateTime.now()) {
        while (true) {
            val now = ZonedDateTime.now()
            value = now
            delay(60_000L - (now.second * 1000L + now.nano / 1_000_000L))
        }
    }
    return time.value
}

/**
 * SF Pro text in widget points: Inter Text below 20 pt, Inter Display above (Apple's optical
 * switch), with SF's size-dependent tracking unless [tracking] (em) overrides it.
 */
@Composable
internal fun WidgetText(
    text: String, s: WidgetScale, size: Float, modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal, color: Color = LocalWidgetColors.current.primary,
    lineHeight: Float = size * 1.2f, maxLines: Int = 1, tracking: Float? = null,
    align: TextAlign? = null, overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    WidgetText(androidx.compose.ui.text.AnnotatedString(text), s, size, modifier, weight, color, lineHeight, maxLines, tracking,
        align, overflow)
}

/** [WidgetText] with spans, e.g. Apple's small-caps "PM" after an event time ([smallCapsMeridiem]). */
@Composable
internal fun WidgetText(
    text: androidx.compose.ui.text.AnnotatedString, s: WidgetScale, size: Float, modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal, color: Color = LocalWidgetColors.current.primary,
    lineHeight: Float = size * 1.2f, maxLines: Int = 1, tracking: Float? = null,
    align: TextAlign? = null, overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val linePx = with(LocalDensity.current) { s.pt(lineHeight).roundToPx() }
    Text(text, modifier.then(if (maxLines == 1) Modifier.exactLineHeight(linePx) else Modifier), color = color,
        fontSize = s.sp(size), lineHeight = s.sp(lineHeight), fontWeight = weight,
        fontFamily = DuoFonts.forSize(size), letterSpacing = (tracking ?: duoTrackingEm(size)).em,
        maxLines = maxLines, overflow = overflow, textAlign = align, softWrap = maxLines > 1)
}

/**
 * Apple sets AM / PM after a compact time in small caps ("4:00–5:00PM"): the letters at 82 % of
 * the size. Only Latin markers are touched; Chinese 上午 / 下午 stay full size.
 */
internal fun smallCapsMeridiem(text: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var last = 0
        Regex("(?<=\\d)(AM|PM)").findAll(text).forEach { match ->
            append(text.substring(last, match.range.first))
            pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = .82.em))
            append(match.value)
            pop()
            last = match.range.last + 1
        }
        append(text.substring(last))
    }

/**
 * Han text falls back to the system CJK face, whose taller ascent/descent Android adds to the
 * line (fallback line spacing) despite the requested line height: a 13 pt line set to 16 pt
 * measured 18.9 pt on device. Widgets are laid out to the point, so a single line reports
 * exactly [heightPx] (glyphs centred, baselines kept) — Chinese rows then fit like English ones.
 */
private fun Modifier.exactLineHeight(heightPx: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    val height = heightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
    val dy = (height - placeable.height) / 2
    val first = placeable[FirstBaseline]
    val last = placeable[LastBaseline]
    val lines = buildMap<AlignmentLine, Int> {
        if (first != AlignmentLine.Unspecified) put(FirstBaseline, first + dy)
        if (last != AlignmentLine.Unspecified) put(LastBaseline, last + dy)
    }
    layout(placeable.width, height, lines) { placeable.place(0, dy) }
}

/**
 * Placed-widget / preview entry point. Unknown ids draw an empty card rather than crash.
 * [preview] (the widget gallery) shows sample content where a placed widget would need data
 * 18Duo does not have, and never handles taps itself — the gallery adds the widget instead.
 */
@Composable
internal fun NativeWidget(id: Int, modifier: Modifier = Modifier, preview: Boolean = false) {
    val spec = nativeWidgetSpec(id)
    val context = LocalContext.current
    val openApp = rememberWidgetAppOpener()
    // Jiggle mode owns taps (iOS: tapping a jiggling widget does nothing).
    val interactive = !preview && !LocalHomeEditChrome.current.editing
    val label = nativeWidgetLabel(id)
    val described = modifier.semantics { contentDescription = label ?: "widget" }
        .testTag("native-widget-${nativeWidgetKey(id) ?: id}")
    CompositionLocalProvider(LocalWidgetColors provides widgetColors(currentWidgetTone()), LocalWidgetPreview provides preview) {
        if (spec == null) IosWidgetCard(described, NativeWidgetSize.SMALL) { }
        else {
            val opens = spec.opens
            val tapping = described.widgetTap(interactive && opens != null) { openApp(opens?.invoke(context)) }
            spec.render(IosWidgetContext(spec, preview, interactive, tapping, described))
        }
    }
}
