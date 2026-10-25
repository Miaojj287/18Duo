package com.jake.duolauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STANDBY_PREFS = "standby"
private const val PREF_FACE = "face_v17"
private fun variantKey(face: StandbyFace) = "variant_v17_${face.key}"

/**
 * iPhone Duo StandBy on the cover in the tent pose: swipe left/right between faces, up/down between each
 * face's options, page dots and the close control appear on touch, Night Mode turns everything red in a dark
 * room. Foreground only; the OS owns panel power and lock routing.
 *
 * StandBy is always a landscape canvas. When its window is not landscape at StandBy's side (the platform ignored the
 * orientation request, the lock-screen window, the frames around a rotation) the canvas is turned to fill the
 * window upright instead of being laid out sideways (interaction review S1).
 */
@Composable
internal fun StandbyScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    /** `Surface.ROTATION_*` of the side StandBy faces (null: whichever landscape side the window has). */
    target: Int? = null,
    /** False while the face is only a veil lifting off Home (exit): no input, window overrides released. */
    active: Boolean = true,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    StandbyWindowEffects(view, activity, active)
    val configuration = LocalConfiguration.current
    val displayRotation = remember(view, configuration) { view.display?.rotation ?: Surface.ROTATION_0 }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val turns = standbyQuarterTurns(target, displayRotation, maxHeight > maxWidth)
        val sideways = turns % 2 == 1
        Box(Modifier.wrapContentSize(unbounded = true)
            .requiredSize(if (sideways) maxHeight else maxWidth, if (sideways) maxWidth else maxHeight)
            .graphicsLayer { rotationZ = turns * 90f }) {
            StandbyCanvas(onExit, activity, active, turns)
        }
    }
}

@Composable
private fun StandbyCanvas(onExit: () -> Unit, activity: Activity?, active: Boolean, turns: Int) {
    val context = LocalContext.current
    val view = LocalView.current
    val locale = LocalConfiguration.current.locales[0]
    val use24h = android.text.format.DateFormat.is24HourFormat(context)
    // Read only in draw/semantics scopes below: a tick redraws a face, it never recomposes this screen (P2-8d).
    val clock = rememberStandbyClock()
    // Round 22: Night Mode is always automatic (red on black in the dark); its setting was removed.
    val nightMode = StandbyNightMode.AUTO
    val detectedNight = if (nightMode == StandbyNightMode.AUTO) rememberStandbyNight(context, clock) else false
    val night = standbyNightEnabled(nightMode, detectedNight)
    StandbyNightBrightness(activity, night && active)
    val prefs = remember(context) { context.getSharedPreferences(STANDBY_PREFS, Context.MODE_PRIVATE) }
    val photos = rememberStandbyPhotos()
    val weather = rememberStandbyWeather()
    val faces = StandbyFace.entries
    val pager = rememberPagerState(initialPage = StandbyFace.of(prefs.getString(PREF_FACE, null)).ordinal) { faces.size }
    val variants = faces.map { face ->
        rememberPagerState(initialPage = prefs.getInt(variantKey(face), 0).coerceAtLeast(0)) {
            StandbyStyles.variants(face, photos.size)
        }
    }
    LaunchedEffect(pager.settledPage) { prefs.edit().putString(PREF_FACE, faces[pager.settledPage].key).apply() }
    faces.forEachIndexed { index, face ->
        LaunchedEffect(variants[index].settledPage) {
            // Photos load asynchronously; don't let the interim single page overwrite the saved choice.
            if (face != StandbyFace.PHOTO || photos.isNotEmpty())
                prefs.edit().putInt(variantKey(face), variants[index].settledPage).apply()
        }
    }
    BackHandler(enabled = active, onBack = onExit)

    // Chrome (page dots, close) shows on entry and on any touch, then fades like iOS.
    var chromeStamp by remember { mutableLongStateOf(0L) }
    var chromeVisible by remember { mutableStateOf(true) }
    val scrolling = pager.isScrollInProgress || variants.any { it.isScrollInProgress }
    LaunchedEffect(chromeStamp, scrolling) {
        chromeVisible = true
        if (!scrolling) { delay(STANDBY_CHROME_MILLIS); chromeVisible = false }
    }
    // Opacity only, so it stays (as a dissolve) under Reduce Motion: quick in, slower out.
    val chromeAlpha by animateFloatAsState(if (chromeVisible) 1f else 0f,
        if (chromeVisible) LauncherMotion.dissolveIn else LauncherMotion.springSpec(LauncherMotion.smoothSpring, .002f),
        label = "standby-chrome")

    // Entry: the faces fade and settle in from black (SwiftUI .smooth) once Home's veil and the rotation are done;
    // Reduce Motion keeps the dissolve but drops the zoom. Read in layers only, so the animation never recomposes.
    val entry = remember { Animatable(if (LauncherMotion.animationsOff) 1f else 0f) }
    LaunchedEffect(Unit) {
        entry.animateTo(1f, if (LauncherMotion.reduced) LauncherMotion.dissolveIn
            else LauncherMotion.springSpec(LauncherMotion.smoothSpring, .001f))
    }

    val density = LocalDensity.current
    var cutouts by remember { mutableStateOf(emptyList<Rect>()) }
    val corners = rememberDisplayCorners(turns)
    val label = stringResource(R.string.standby_title)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("standby-root")
        .semantics { contentDescription = label }
        .onGloballyPositioned { coordinates -> standbyCutouts(view, coordinates).let { if (it != cutouts) cutouts = it } }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                chromeStamp = SystemClock.uptimeMillis()
            }
        }) {
        val margin = with(density) { 6.dp.toPx() }
        val orbit = with(density) { 2.dp.toPx() }
        val frame = remember(clock, locale, use24h, cutouts, margin, orbit) {
            StandbyFrame(clock, locale, use24h, cutouts, cutoutMargin = margin, shiftAmplitude = orbit)
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = entry.value
            val scale = if (LauncherMotion.reduced) 1f else .92f + .08f * entry.value
            scaleX = scale; scaleY = scale
        }) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("standby-pager"),
                pageSpacing = 10.dp, beyondViewportPageCount = 1, userScrollEnabled = active) { page ->
                val face = faces[page]
                val vertical = variants[page]
                Box(Modifier.fillMaxSize().standbyCard(pager, page, corners).testTag("standby-view-$page")) {
                    VerticalPager(state = vertical, modifier = Modifier.fillMaxSize(), pageSpacing = 10.dp,
                        userScrollEnabled = active && !night && vertical.pageCount > 1) { variant ->
                        Box(Modifier.fillMaxSize().standbyCard(vertical, variant, corners)) {
                            StandbyFaceContent(face, variant, frame, night, photos, weather)
                        }
                    }
                }
            }
        }

        val face = faces[pager.currentPage.coerceIn(0, faces.lastIndex)]
        val faceVariants = variants[pager.currentPage.coerceIn(0, faces.lastIndex)]
        val background = StandbyStyles.background(face, faceVariants.currentPage, night)
        val chromeInk = if (night) StandbyStyles.nightRed else Color.White
        val glass = if (night) StandbyStyles.nightRed.copy(alpha = .18f)
            else if (background.luminance() > .5f) Color(0xFF787880).copy(alpha = .42f) else Color(0xFF8E8E93).copy(alpha = .30f)
        val enabled = chromeVisible && active
        // Page dots: glass capsule centred at 94.6 % H (video 3.4 s / 5.8 s).
        Row(Modifier.align(Alignment.TopCenter)
            .offset(y = maxHeight * StandbyGeometry.DOTS_Y - 9.dp)
            .graphicsLayer { alpha = chromeAlpha }
            .height(18.dp).clip(RoundedCornerShape(9.dp)).background(glass).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            faces.indices.forEach { index ->
                val pageLabel = stringResource(R.string.standby_page, index + 1, faces.size)
                Box(Modifier.size(width = 14.4.dp, height = 18.dp)
                    .clickable(enabled = enabled, interactionSource = null, indication = null, role = Role.Tab) {
                        chromeStamp = SystemClock.uptimeMillis()
                        scope.launch { pager.animateScrollToPage(index) }
                    }
                    .testTag("standby-page-$index").semantics { contentDescription = pageLabel },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(5.5.dp).background(chromeInk.copy(alpha = if (index == pager.currentPage) 1f else .45f), CircleShape))
                }
            }
        }
        // Option dots for the vertical swipe, on the short edge without the camera.
        if (faceVariants.pageCount > 1 && !night) {
            val cutoutRight = standbyCutoutOnRight(cutouts, with(density) { maxWidth.toPx() })
            Column(Modifier.align(if (cutoutRight == true) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 10.dp).graphicsLayer { alpha = chromeAlpha }
                .width(14.dp).clip(RoundedCornerShape(7.dp)).background(glass).padding(vertical = 4.dp)
                .testTag("standby-variant-dots"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                repeat(faceVariants.pageCount) { index ->
                    Box(Modifier.padding(vertical = 4.dp).size(5.5.dp)
                        .background(chromeInk.copy(alpha = if (index == faceVariants.currentPage) 1f else .45f), CircleShape))
                }
            }
        }
        // Close: a small glass "xmark" in the top trailing corner, inset by the display corner curve.
        val inset = with(density) { corners.topRight.toDp() } * .29f + 10.dp
        val closeLabel = stringResource(R.string.standby_exit)
        Box(Modifier.align(Alignment.TopEnd).padding(top = inset, end = inset)
            .graphicsLayer { alpha = chromeAlpha }
            .size(30.dp).clip(CircleShape).background(glass)
            .clickable(enabled = enabled, role = Role.Button, onClick = onExit)
            .testTag("standby-exit").semantics { contentDescription = closeLabel }) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension * .17f
                val c = center
                drawLine(chromeInk, c + Offset(-r, -r), c + Offset(r, r), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(chromeInk, c + Offset(-r, r), c + Offset(r, -r), 1.8.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun StandbyFaceContent(face: StandbyFace, variant: Int, frame: StandbyFrame, night: Boolean,
    photos: List<ImageBitmap>, weather: StandbyWeather? = null) {
    when (face) {
        StandbyFace.WIDGETS -> StandbyWidgetsFace(frame, weather, night)
        StandbyFace.WEATHER -> StandbyWeatherFace(frame, weather, night)
        StandbyFace.DIGITAL -> StandbyDigitalFace(frame, StandbyStyles.digital(variant, night))
        StandbyFace.CALENDAR -> StandbyCalendarFace(frame, StandbyStyles.calendar(variant, night))
        StandbyFace.ANALOG -> StandbyAnalogFace(frame, StandbyStyles.analog(variant, night))
        StandbyFace.PHOTO -> StandbyPhotoFace(frame, photos.getOrNull(Math.floorMod(variant, photos.size.coerceAtLeast(1))), night)
    }
}

/**
 * While a page moves it reads as a separate card with a black gap (video 2.5 s).
 * At rest each corner matches its own physical radius, including the cover's nearly square hinge-side corners.
 */
private fun Modifier.standbyCard(state: PagerState, page: Int, corners: StandbyCorners): Modifier =
    graphicsLayer {
        val offset = abs((state.currentPage - page) + state.currentPageOffsetFraction)
        val moving = (offset * 8f).coerceIn(0f, 1f)
        val cardRadius = 28.dp.toPx()
        fun radius(physical: Int): Dp =
            (physical + (maxOf(physical.toFloat(), cardRadius) - physical) * moving).toDp()
        clip = corners.topLeft > 0 || corners.topRight > 0 || corners.bottomRight > 0 ||
            corners.bottomLeft > 0 || moving > 0f
        shape = AbsoluteRoundedCornerShape(radius(corners.topLeft), radius(corners.topRight),
            radius(corners.bottomRight), radius(corners.bottomLeft))
    }

@Composable
private fun rememberDisplayCorners(turns: Int): StandbyCorners {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration.orientation, configuration.screenWidthDp, turns) {
        val insets = context.getSystemService(android.view.WindowManager::class.java)?.currentWindowMetrics?.windowInsets
        StandbyCorners(
            insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0,
            insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0,
            insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
            insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
        ).forCanvas(turns)
    }
}

/** Real cutout rectangles mapped into this layout's space (also through RotatedStandbyScreen's rotation). */
private fun standbyCutouts(view: View, coordinates: LayoutCoordinates): List<Rect> {
    val rects = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
    if (rects.isEmpty() || !coordinates.isAttached) return emptyList()
    return rects.map { r ->
        val corners = listOf(Offset(r.left.toFloat(), r.top.toFloat()), Offset(r.right.toFloat(), r.top.toFloat()),
            Offset(r.left.toFloat(), r.bottom.toFloat()), Offset(r.right.toFloat(), r.bottom.toFloat()))
            .map(coordinates::windowToLocal)
        Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }
}

/** Ambient light → Night Mode; the light sensor reports on change only, so the last reading is re-fed every second. */
@Composable
internal fun rememberStandbyNight(context: Context, clock: StandbyClock): Boolean {
    val sensors = remember(context) { context.getSystemService(SensorManager::class.java) }
    val light = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_LIGHT) }
    if (light == null) {
        // Derived: the clock's every tick is read here, but this composition only hears about a changed answer.
        val byClock by remember(clock) { derivedStateOf { standbyNightByClock(clock.now.hour) } }
        return byClock
    }
    // Starts in the mode of a StandBy shown minutes ago; the sensor's first reading (delivered on registration)
    // then decides at once instead of after the 3 s hysteresis (code review P2-5).
    val detector = remember { StandbyNightDetector(initial = StandbyNightMemory.recall(SystemClock.elapsedRealtime())) }
    var lux by remember { mutableFloatStateOf(Float.NaN) }
    var night by remember { mutableStateOf(detector.night) }
    DisposableEffect(sensors, light) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                lux = event.values.firstOrNull() ?: return
                night = detector.update(lux, SystemClock.elapsedRealtime())
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, light, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensors.unregisterListener(listener) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(detector, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1_000L)
                if (!lux.isNaN()) night = detector.update(lux, SystemClock.elapsedRealtime())
            }
        }
    }
    SideEffect { StandbyNightMemory.remember(night, SystemClock.elapsedRealtime()) }
    return night
}

/** The user's chosen wallpaper photo first, then the bundled Duo wallpaper; never sample imagery. */
@Composable
internal fun rememberStandbyPhotos(): List<ImageBitmap> {
    val context = LocalContext.current.applicationContext
    val revision = LauncherBackgroundCache.revision.intValue
    val photos by produceState(emptyList<ImageBitmap>(), context, revision) {
        value = withContext(Dispatchers.IO) {
            listOfNotNull(runCatching { loadLauncherBackground(context) }.getOrNull(),
                runCatching { bundledDefaultWallpaper(context) }.getOrNull()).map { it.asImageBitmap() }
        }
    }
    return photos
}

/**
 * Lit while on external power (iOS: a charging iPhone keeps StandBy on); on battery the user's screen timeout dims,
 * turns off and locks as usual (code review P1-3). System bars hidden like iOS StandBy, re-hidden whenever the
 * window regains focus (after the shade, a dialog), and restored on exit.
 */
@Composable
internal fun StandbyWindowEffects(view: View, activity: Activity?, active: Boolean) {
    val keepOn = active && standbyKeepsScreenOn(rememberExternalPower(view.context))
    DisposableEffect(view, keepOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val statusVisible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val observer = window.decorView.viewTreeObserver
        val refocus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        observer.addOnWindowFocusChangeListener(refocus)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(refocus)
            else window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(refocus)
            if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

/** External power (any plug, also when charging is paused at full), followed live from the sticky battery broadcast. */
@Composable
private fun rememberExternalPower(context: Context): Boolean {
    val app = context.applicationContext
    var plugged by remember(app) { mutableStateOf(standbyOnExternalPower(app)) }
    DisposableEffect(app) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                plugged = standbyExternalPower(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { app.unregisterReceiver(receiver) }
    }
    return plugged
}

/** Night Mode also lowers this window's brightness (public per-window override; released on exit). */
@Composable
internal fun StandbyNightBrightness(activity: Activity?, night: Boolean) {
    DisposableEffect(activity, night) {
        val window = activity?.window
        if (window == null || !night) return@DisposableEffect onDispose { }
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = .03f }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The locked clock Activity: its window may stay portrait while the hinge is a landscape stand; StandbyScreen
 * turns its canvas to the stand's side ([rotation] ±90°) instead of re-laying out every page. */
@Composable
internal fun RotatedStandbyScreen(rotation: Float, onExit: () -> Unit) {
    StandbyScreen(onExit, target = if (rotation < 0f) Surface.ROTATION_270 else Surface.ROTATION_90)
}
