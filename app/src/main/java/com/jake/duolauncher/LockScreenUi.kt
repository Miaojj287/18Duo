package com.jake.duolauncher

import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.rememberGraphicsLayer
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * iPhone Duo Lock Screen, aligned with the owner's iOS 26 recording (round 11): the Home wallpaper
 * (same image and crop as Home, [DuoPanelWallpaper]), the date line over a big clock, and at the
 * bottom one Liquid-Glass slab carrying the accessory widget row, the flashlight + camera (in the
 * side column on the cover and the unfolded panel, as the owner asked in round 8) and the home
 * indicator. Swipe up anywhere: the slab lifts 1:1 with the finger, the clock lifts with it and
 * fades within 0.28 H, the wallpaper zooms towards the hand-off scale; past the threshold the slab
 * keeps going at the release speed while the system takes over the unlock. The same composable
 * draws the customisation editor's preview ([editor] non-null).
 */
@Composable
internal fun LockScreen(
    config: LockScreenConfig,
    torchOn: Boolean,
    torchAvailable: Boolean,
    cancelledRequests: Int,
    onFirstFrame: () -> Unit,
    onUnlock: () -> Unit,
    onFlashlight: () -> Unit,
    onCamera: () -> Unit,
    /** Long-press on the lock screen (live mode): ask to unlock, then open the editor. */
    onEditRequest: () -> Unit,
    /** The released slab has cleared the panel (or its exit spring ended): Home may take over now. */
    onSlabCleared: () -> Unit = {},
    /** Round 13: bumped when the keyguard went away without the swipe (fingerprint): the slab rides out by itself. */
    exitRequests: Int = 0,
    editor: LockScreenEditorState? = null,
) {
    val backdrop = rememberHazeState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    /** Upward travel of the slab in dp (finger-driven, then springs). */
    val travel = remember { Animatable(0f) }
    var committed by remember { mutableStateOf(false) }
    val editing = editor != null
    LaunchedEffect(Unit) { withFrameNanos { }; onFirstFrame() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("lock-screen")) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        val geometry = remember(widthDp, heightDp) { lockScreenGeometry(widthDp, heightDp) }
        val slab = remember(geometry) { lockSlabLayout(widthDp, heightDp, geometry) }
        val slabTop = if (config.position == LockWidgetPosition.BOTTOM) slab.slabTop
            else lockSlabTopWithoutWidgets(geometry, slab, widthDp, heightDp)
        val exitTravel = LockSwipe.exitTravel(heightDp)
        /** The slab rides out (SwiftUI .smooth) carrying [velocityUp]; Home may take over near its end. */
        suspend fun exit(spec: IosSpring, velocityUp: Float) {
            var cleared = false
            travel.animateTo(exitTravel, LauncherMotion.springSpec(spec), velocityUp) {
                if (!cleared && LockSwipe.slabCleared(value, exitTravel)) { cleared = true; onSlabCleared() }
            }
            if (!cleared) onSlabCleared()
        }
        LaunchedEffect(exitRequests) {
            if (exitRequests == 0 || committed || editing) return@LaunchedEffect
            committed = true
            exit(LockSwipe.autoExitSpring, 0f)
        }
        LaunchedEffect(cancelledRequests) {
            if (cancelledRequests == 0) return@LaunchedEffect
            committed = false
            travel.animateTo(0f, if (LauncherMotion.reduced) spring(1f, 900f) else LauncherMotion.springSpec(LockSwipe.cancelSpring))
        }
        val dragState = rememberDraggableState { delta ->
            // Re-checked inside: an automatic (fingerprint) exit may commit before a queued snap runs.
            if (!committed) scope.launch { if (!committed) travel.snapTo(travel.value - delta / density.density) }
        }
        val drag = if (editing) Modifier else Modifier.draggable(dragState, Orientation.Vertical, enabled = !committed,
            onDragStopped = { velocityPx ->
                // An automatic exit (fingerprint) already owns the slab; its commit cancelled this drag.
                if (committed) return@draggable
                val velocityUp = -velocityPx / density.density
                if (LockSwipe.shouldUnlock(travel.value, velocityUp, heightDp)) {
                    committed = true
                    onUnlock()
                    // SwiftUI .smooth, carrying the release velocity: the slab clears the top edge.
                    exit(LockSwipe.exitSpring, velocityUp)
                } else {
                    travel.animateTo(0f, LauncherMotion.springSpec(LockSwipe.cancelSpring), velocityUp)
                }
            })
        val longPress = if (editing) Modifier else Modifier.pointerInput(onEditRequest) {
            detectTapGestures(onLongPress = { if (!committed) onEditRequest() })
        }
        Box(Modifier.fillMaxSize().then(drag).then(longPress).semantics {
            contentDescription = if (editing) uxText("锁屏预览", "Lock Screen preview") else uxText("锁屏。向上轻扫以解锁。", "Lock Screen. Swipe up to unlock.")
            if (!editing) onClick(uxText("解锁", "Unlock")) { committed = true; onUnlock(); true }
        }) {
            DuoPanelWallpaper(Modifier.fillMaxSize().hazeSource(backdrop)) {
                if (LauncherMotion.reduced) 1f else LockSwipe.wallpaperScale(travel.value, heightDp)
            }
            // The editor dims the wallpaper a little (iOS: the customisation screen).
            if (editing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f)))
            // Round 12.2 (iOS 26 recording): the clock, date and under-date row ride up with the finger and
            // are cut off at the status bar line; Reduce Motion dissolves them in place instead.
            val clockLayer = Modifier.fillMaxSize().graphicsLayer {
                if (!LauncherMotion.reduced) translationY = LockSwipe.contentOffset(travel.value, heightDp).dp.toPx()
                alpha = if (LauncherMotion.reduced) LockSwipe.clockAlpha(travel.value, heightDp) else 1f
            }
            val clockClip = Modifier.fillMaxSize().drawWithContent {
                val top = LockSwipe.clockClipTop(heightDp).dp.toPx()
                clipRect(top = top) { this@drawWithContent.drawContent() }
            }
            Box(clockClip) { Box(clockLayer) {
                LockClock(geometry, config, editor?.clockBounds, backdrop)
                if (config.position == LockWidgetPosition.UNDER_DATE)
                    LockWidgetRow(config, slab, lockUnderDateRowLeft(geometry, slab, config.align),
                        lockUnderDateRowCenterY(geometry, slab, config.stretch), editor)
            } }
            val corners = rememberLockSlabCorners(slab.cornerRadius)
            val shape = remember(corners) { LockSlabGlass.shape(corners) }
            Box(Modifier.fillMaxSize().graphicsLayer {
                if (!LauncherMotion.reduced) translationY = LockSwipe.contentOffset(travel.value, heightDp).dp.toPx()
                alpha = LockSwipe.slabAlpha(travel.value, heightDp)
            }) {
                // The slab: full width, from just above its content to the panel's bottom edge. Its glass
                // materialises as the drag begins (at rest the block sits plainly on the wallpaper, f001);
                // the iOS 26 editor shows no slab either (round 12.4, the owner's two editor screenshots).
                Box(Modifier.offset { IntOffset(0, slabTop.dp.roundToPx()) }.fillMaxWidth().height((heightDp - slabTop).dp)
                    .graphicsLayer { alpha = if (editing) 0f else LockSwipe.slabMaterialAlpha(travel.value, heightDp) }
                    .then(LockSlabGlass.modifier(shape, backdrop)).testTag("lock-slab"))
                if (config.position == LockWidgetPosition.BOTTOM)
                    LockWidgetRow(config, slab, slab.widgetRowLeft, slab.widgetRowCenterY, editor)
                if (torchAvailable) LockControl(geometry.flashlight, geometry.controlDiameter, active = torchOn,
                    label = uxText("手电筒", "Flashlight"), tag = "lock-flashlight", backdrop = backdrop, enabled = !editing,
                    onActivate = onFlashlight) { color -> drawFlashlightGlyph(color, torchOn) }
                LockControl(geometry.camera, geometry.controlDiameter, active = false,
                    label = uxText("相机", "Camera"), tag = "lock-camera", backdrop = backdrop, enabled = !editing,
                    onActivate = onCamera) { color -> drawCameraGlyph(color) }
                LockHomeBar(slab)
            }
            if (editor != null) LockScreenEditorOverlay(editor, geometry, slab, widthDp, heightDp)
        }
    }
}

/** Current time that is right the instant the screen comes back (timers sleep with the CPU). */
@Composable
private fun rememberLockNow(): LocalDateTime {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { now = LocalDateTime.now() }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_050L - System.currentTimeMillis() % 60_000L)
            now = LocalDateTime.now()
        }
    }
    return now
}

/** Where the clock's and the date's ink was last drawn (dp): the editor's outlines and size handle sit on them. */
@Stable
internal class LockClockBounds {
    var rect by mutableStateOf(Rect.Zero)
    var dateRect by mutableStateOf(Rect.Zero)
}

/**
 * The lunar date of [date] (round 12.4, iOS 「中国农历」): ICU's Chinese calendar gives the cycle year,
 * month, leap flag and day; [LunarDate] names them. Null off a Chinese locale or if ICU fails.
 */
internal fun lunarDateOf(date: java.time.LocalDate, locale: Locale): String? {
    if (locale.language != "zh") return null
    return runCatching {
        val cal = android.icu.util.ChineseCalendar()
        cal.time = java.util.Date.from(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
        LunarDate.text(cal.get(android.icu.util.Calendar.YEAR), cal.get(android.icu.util.Calendar.MONTH) + 1,
            cal.get(android.icu.util.ChineseCalendar.IS_LEAP_MONTH) == 1, cal.get(android.icu.util.Calendar.DAY_OF_MONTH))
    }.getOrNull()
}

/** The typeface for a clock face (the project's SF stand-ins, or the Clock widgets' digit face). */
internal fun lockClockTypeface(context: Context, font: LockClockFont): Typeface =
    if (font.duoDigits) runCatching { ResourcesCompat.getFont(context, R.font.duo_clock_digits) }.getOrNull()
        ?: StandbyFonts.sans(font.weight)
    else StandbyFonts.sans(font.weight)

/** The time in the chosen face, colour and size; date line above it. */
@Composable
private fun LockClock(geometry: LockScreenGeometry, config: LockScreenConfig, bounds: LockClockBounds?, backdrop: HazeState) {
    val now = rememberLockNow()
    val is24 = rememberIs24HourFormat()
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val time = lockTimeText(now.hour, now.minute, is24)
    val date = lockDateText(now.toLocalDate(), locale, if (config.lunar) lunarDateOf(now.toLocalDate(), locale) else null)
    val typeface = remember(config.font) { lockClockTypeface(context, config.font) }
    val condensedFace = remember { lockClockTypeface(context, LockClockFont.DIGITS) }
    val clockPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG) }
    val datePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = StandbyFonts.sans(600); color = Color.White.copy(alpha = .92f).toArgb()
        }
    }
    // Round 12 「对齐」: one anchor for the time, the date and the under-date row (0.5 W when centred).
    val anchor = remember(geometry, config.align) { lockTextAnchor(geometry, config.align) }
    val paintAlign = when (config.align) {
        LockTextAlign.CENTER -> Paint.Align.CENTER
        LockTextAlign.LEFT -> Paint.Align.LEFT
        LockTextAlign.RIGHT -> Paint.Align.RIGHT
    }
    // Round 13: the Glass clock samples the wallpaper under it on screen (it rides up with the swipe).
    val glassText = remember { LockGlassText() }
    glassText.layer = rememberGraphicsLayer()
    DisposableEffect(glassText) { onDispose { glassText.release() } }
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { if (it.isAttached) origin = it.positionOnScreen() }
        .testTag("lock-clock").semantics { contentDescription = "$time, $date" }) {
        val shadow = 1.dp.toPx()
        clockPaint.color = config.color
        // iOS 26 size handle: the digits grow in height up to 2.2×, in width by less, never past the panel.
        val heightScale = LockClockSize.heightScale(config.stretch)
        val digitHeight = geometry.clockDigitHeight * heightScale
        /** Puts [face] on the paint at the digit height, unscaled; returns the time's natural width (dp). */
        fun natural(face: Typeface, digits: Boolean, fakeBold: Boolean): Float {
            clockPaint.typeface = face
            clockPaint.textScaleX = 1f
            clockPaint.isFakeBoldText = fakeBold
            StandbyTextMetrics.sizeForCap(clockPaint, digitHeight.dp.toPx(), "4")
            // Inter's digits are wider than SF Pro's: a slight negative tracking keeps Apple's width.
            clockPaint.letterSpacing = if (digits) 0f else -.015f
            return clockPaint.measureText(time) / density
        }
        val fakeBold = config.font.weight > 700
        // Measured at the stretched size; the size handle's width scale is relative to the *default* width
        // (round 12: feeding the stretched width into the cap made the tallest clock narrower than the default).
        val chosen = natural(typeface, config.font.duoDigits, fakeBold)
        clockPaint.textAlign = paintAlign
        datePaint.textAlign = paintAlign
        val defaultNatural = chosen / heightScale
        // The clock may grow to the text area's edge (the side column on the cover), never past it.
        val target = defaultNatural * config.font.widthScale *
            LockClockSize.fittedWidthScale(config.stretch, defaultNatural * config.font.widthScale, anchor.maxWidth)
        var scaleX = target / chosen
        // Round 13: a stretched clock is drawn by the face that needs the least uneven scale (LockClockFace).
        if (!config.font.duoDigits && config.stretch > 0f) {
            val condensedScaleX = target / natural(condensedFace, digits = true, fakeBold = false)
            if (LockClockFace.preferCondensed(scaleX, condensedScaleX)) scaleX = condensedScaleX
            else natural(typeface, digits = false, fakeBold = fakeBold) // restore the chosen face
        }
        clockPaint.textScaleX = scaleX
        clockPaint.setShadowLayer(8.dp.toPx(), 0f, shadow, Color.Black.copy(alpha = .16f).toArgb())
        StandbyTextMetrics.sizeForCap(datePaint, geometry.dateCapHeight.dp.toPx(), "H")
        datePaint.letterSpacing = duoTrackingEm(geometry.dateCapHeight / .727f)
        datePaint.setShadowLayer(4.dp.toPx(), 0f, shadow, Color.Black.copy(alpha = .16f).toArgb())
        val x = anchor.x.dp.toPx()
        val baseline = (geometry.clockTop + digitHeight).dp.toPx()
        drawContext.canvas.nativeCanvas.apply {
            drawText(date, x, (geometry.dateCenterY + geometry.dateCapHeight / 2f).dp.toPx(), datePaint)
        }
        val glass = config.glass && glassText.draw(this, time, clockPaint.apply { clearShadowLayer() }, x, baseline, origin, backdrop)
        if (!glass) {
            clockPaint.setShadowLayer(8.dp.toPx(), 0f, shadow, Color.Black.copy(alpha = .16f).toArgb())
            drawContext.canvas.nativeCanvas.drawText(time, x, baseline, clockPaint)
        }
        if (bounds != null) {
            val w = clockPaint.measureText(time) / density
            val left = anchor.left(w)
            val rect = Rect(left, geometry.clockTop, left + w, geometry.clockTop + digitHeight)
            if (rect != bounds.rect) bounds.rect = rect
            val dw = datePaint.measureText(date) / density
            val dateLeft = anchor.left(dw)
            val dateRect = Rect(dateLeft, geometry.dateCenterY - geometry.dateCapHeight / 2f, dateLeft + dw,
                geometry.dateCenterY + geometry.dateCapHeight / 2f)
            if (dateRect != bounds.dateRect) bounds.dateRect = dateRect
        }
    }
}

/** The accessory widget row: each widget at its slot; the editor adds the ⊖ badges and the add pill. */
@Composable
private fun LockWidgetRow(config: LockScreenConfig, slab: LockSlabLayout, rowLeft: Float, centerY: Float,
    editor: LockScreenEditorState?) {
    val starts = LockWidgetSlots.startUnits(config.widgets)
    val keys = lockWidgetKeys(config.widgets)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    config.widgets.forEachIndexed { index, kind ->
        androidx.compose.runtime.key(keys[index]) {
            val slotLeft = slab.slotLeft(rowLeft, starts[index])
            val lifted = editor != null && editor.dragKey == keys[index]
            // Neighbours glide into their new slots; the lifted widget rides the finger (iOS reorder).
            val glide by androidx.compose.animation.core.animateFloatAsState(slotLeft,
                androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 520f), label = "lock-widget-slot")
            val lift by androidx.compose.animation.core.animateFloatAsState(if (lifted) 1f else 0f,
                androidx.compose.animation.core.spring(dampingRatio = .7f, stiffness = 700f), label = "lock-widget-lift")
            val left = if (lifted) slotLeft + editor!!.dragOffsetDp else glide
            val width = slab.widgetWidth(kind)
            Box(Modifier.offset { IntOffset(left.dp.roundToPx(), (centerY - slab.unit / 2f).dp.roundToPx()) }
                .size(width.dp, slab.unit.dp).testTag("lock-widget-$index")
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer { val sc = 1f + .08f * lift; scaleX = sc; scaleY = sc; alpha = 1f - .1f * lift }
                .then(if (editor != null) editor.widgetDragModifier(keys[index], slab.pitch) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                    .pointerInput(editor) { detectTapGestures { editor.sheet = "widgets" } } else Modifier)) {
                LockAccessoryWidget(kind, slab.unit, preview = editor != null, Modifier.fillMaxSize())
                if (editor != null && !lifted) LockWidgetRemoveBadge(index, editor)
            }
        }
    }
    if (editor != null) LockWidgetAddPill(config, slab, rowLeft, centerY, editor)
}

/**
 * Round Lock Screen control: clear Liquid Glass ([LockControlGlass]), white glyph; on (flashlight lit) it turns white with a
 * dark glyph. Press and hold to activate (iOS), the button swells while held; a plain tap does nothing.
 */
@Composable
private fun LockControl(spot: LockControlSpot, diameter: Float, active: Boolean, label: String, tag: String,
    backdrop: HazeState, enabled: Boolean, onActivate: () -> Unit, glyph: DrawScope.(Color) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val swell = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val pressSpring = spring<Float>(.62f, 420f)
    Box(Modifier
        .offset { IntOffset(((spot.x - diameter / 2f).dp.toPx()).roundToInt(), ((spot.y - diameter / 2f).dp.toPx()).roundToInt()) }
        .size(diameter.dp)
        .graphicsLayer { scaleX = swell.value; scaleY = swell.value }
        .testTag(tag)
        .semantics {
            contentDescription = label; role = Role.Button
            if (tag == "lock-flashlight") stateDescription = if (active) uxText("开", "On") else uxText("关", "Off")
            if (enabled) onClick(label) { onActivate(); true }
        }
        .then(if (!enabled) Modifier else Modifier.pointerInput(onActivate) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                if (!LauncherMotion.reduced) scope.launch { swell.animateTo(1.18f, pressSpring) }
                val released = withTimeoutOrNull(LOCK_CONTROL_HOLD_MS) { waitForUpOrCancellation(); true }
                if (released == null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onActivate()
                    waitForUpOrCancellation()
                }
                scope.launch { swell.animateTo(1f, pressSpring) }
            }
        })
        .then(LockControlGlass.modifier(backdrop)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (active) drawCircle(Color.White)
            glyph(if (active) Color.Black else Color.White)
        }
    }
}

/** The iPhone home indicator inside the slab (recording: 0.354 W × 4.3 pt, 12 pt from the bottom). */
@Composable
private fun LockHomeBar(slab: LockSlabLayout) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * LockSlabSpec.HOME_BAR_WIDTH
        val h = slab.homeBarHeight.dp.toPx()
        val bottom = size.height - slab.homeBarBottomInset.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = .92f), Offset(size.width / 2f - w / 2f, bottom - h), Size(w, h), CornerRadius(h / 2f))
    }
}

/** SF Symbols "flashlight.off.fill" / "flashlight.on.fill" in a 50 pt control (glyph ≈ 0.46 D tall). */
private fun DrawScope.drawFlashlightGlyph(color: Color, on: Boolean) {
    val d = size.minDimension
    val cx = size.width / 2f
    val top = size.height / 2f - d * .23f
    val head = d * .26f
    val body = d * .15f
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        // Lens cap, the flared neck and the handle.
        addRoundRect(RoundRect(cx - head / 2f, top, cx + head / 2f, top + d * .07f, CornerRadius(d * .02f)))
        moveTo(cx - head / 2f, top + d * .085f); lineTo(cx + head / 2f, top + d * .085f)
        lineTo(cx + body / 2f, top + d * .19f); lineTo(cx - body / 2f, top + d * .19f); close()
        addRoundRect(RoundRect(cx - body / 2f, top + d * .2f, cx + body / 2f, top + d * .46f, CornerRadius(d * .03f)))
        // The switch is a hole in the handle.
        addOval(Rect(Offset(cx, top + d * .29f), d * .035f))
    }
    drawPath(path, color)
    if (on) {
        // "on.fill": three short rays above the lens.
        val y = top - d * .05f
        val ray = d * .055f
        for (i in -1..1) {
            val x = cx + i * d * .1f
            drawLine(color, Offset(x, y), Offset(x + i * ray * .45f, y - ray), strokeWidth = d * .035f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** SF Symbols "camera.fill" (glyph ≈ 0.44 D wide). */
private fun DrawScope.drawCameraGlyph(color: Color) {
    val d = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f + d * .015f
    val w = d * .44f
    val h = d * .32f
    val body = Path().apply {
        addRoundRect(RoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, CornerRadius(d * .055f)))
        // The viewfinder bump on top (same winding: it merges with the body).
        addRoundRect(RoundRect(cx - w * .19f, cy - h / 2f - d * .045f, cx + w * .19f, cy - h / 2f + d * .02f, CornerRadius(d * .02f)))
    }
    val lens = Path().apply { addOval(Rect(Offset(cx, cy + d * .01f), d * .085f)) }
    drawPath(Path.combine(PathOperation.Difference, body, lens), color)
    drawCircle(color, d * .045f, Offset(cx, cy + d * .01f))
}
