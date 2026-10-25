package com.jake.duolauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.isTraversalGroup

/** iOS-sized text styles in Inter (Latin/digits) with SF-like tracking; see [withDuoFonts]. */
internal val LauncherTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
).withDuoFonts()

/**
 * A bounded, dedicated task surface styled as an iOS 26 large sheet: anchored to the bottom with a
 * 34dp top radius on phones, a floating 32dp card on wide screens. It rises on a critically
 * damped spring, and on phones it can be pulled down by its grabber/toolbar to dismiss (iOS).
 */
@Composable
internal fun LauncherPanel(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val expanded = LocalConfiguration.current.screenWidthDp >= 600
    val reduced = LauncherMotion.reduced
    var entered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    val pull = remember { Animatable(0f) }
    // The window's own dim cannot follow a finger: it is replaced by a scrim drawn here that
    // un-dims as the sheet is pulled down (iOS), at the window's original strength.
    var baseDim by remember { mutableFloatStateOf(-1f) }
    var sheetHeightPx by remember { mutableFloatStateOf(1f) }
    val close = {
        if (!closing) {
            closing = true
            entered = false
            scope.launch { if (!reduced) delay(260); currentDismiss() }
        }
        Unit
    }
    DisposableEffect(Unit) {
        IosBanner.panels++
        onDispose { IosBanner.panels = (IosBanner.panels - 1).coerceAtLeast(0) }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = LocalDuoPalette.current.dark
        val density = LocalDensity.current
        SideEffect {
            // Compose Dialog also updates bar appearance from the system theme. Apply our
            // user-selected theme after that update, including when it differs from the system.
            view.post {
                val window = (view.parent as? DialogWindowProvider)?.window ?: (view as? DialogWindowProvider)?.window
                window?.let {
                    if (baseDim < 0f) {
                        val dims = it.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
                        baseDim = if (dims) it.attributes.dimAmount else 0f
                        if (dims) it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    }
                    WindowCompat.getInsetsController(it, view).apply {
                        // The status bar now sits over Android's dimmed Home, not over the sheet.
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = !dark
                    }
                    it.isNavigationBarContrastEnforced = false
                    it.setTitle(uxText("18Duo 菜单", "18Duo menu"))
                }
            }
        }
        LaunchedEffect(Unit) { entered = true }
        val progress by animateFloatAsState(if (entered) 1f else 0f,
            // iOS sheet: presented on SwiftUI's `.smooth`, dismissed a little faster.
            if (reduced) tween(0) else if (entered) LauncherMotion.sheetPresent else LauncherMotion.sheetDismiss,
            label = "sheet arrival")
        val sheetShape = if (expanded) RoundedCornerShape(32.dp)
            else RoundedCornerShape(topStart = IosUi.sheetRadius, topEnd = IosUi.sheetRadius)
        val sheetDrag = remember(expanded) {
            // iOS: the sheet follows the finger (rubber band upward), and the release hands its
            // velocity to the spring that either carries the sheet off or brings it back.
            var tracking = false
            var raw = 0f
            if (expanded) null else IosSheetDrag(
                onDrag = { delta ->
                    if (!closing) {
                        if (!tracking) {
                            tracking = true
                            raw = if (pull.value >= 0f) pull.value else IosScrollPhysics.rubberBandInverse(pull.value, sheetHeightPx)
                        }
                        raw += delta
                        val visible = SheetPullMath.visibleOffset(raw, sheetHeightPx)
                        scope.launch { pull.snapTo(visible) }
                    }
                },
                onRelease = { velocity ->
                    tracking = false
                    if (!closing && SheetPullMath.dismisses(pull.value, velocity, sheetHeightPx, density.density)) {
                        closing = true
                        scope.launch {
                            if (!reduced) pull.animateTo(sheetHeightPx, LauncherMotion.sheetDismiss,
                                initialVelocity = velocity.coerceAtLeast(0f))
                            currentDismiss()
                        }
                    } else if (!closing) scope.launch {
                        pull.animateTo(0f, LauncherMotion.sheetReturn, initialVelocity = velocity)
                    }
                })
        }
        Box(Modifier.fillMaxSize(), contentAlignment = if (expanded) Alignment.Center else Alignment.BottomCenter) {
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Color.Black, alpha = baseDim.coerceAtLeast(0f) *
                    SheetPullMath.backdropFraction(progress, pull.value, sheetHeightPx))
            })
            Box(modifier.semantics { paneTitle = uxText("18Duo 菜单", "18Duo menu"); isTraversalGroup = true }
                .then(if (expanded) Modifier.widthIn(max = 640.dp).fillMaxWidth(.9f).fillMaxHeight(.9f)
                    else Modifier.fillMaxWidth().statusBarsPadding().padding(top = 22.dp).fillMaxHeight())
                .onSizeChanged { sheetHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    // Both sheets rise from the bottom edge (iPhone sheet / iPad form sheet); the
                    // cover's also follows a pull. Reduce Motion presents in place (progress snaps).
                    translationY = if (expanded) SheetPullMath.formSheetTranslation(progress, size.height)
                        else (1f - progress) * size.height + pull.value
                }
                .iosSheetSurface(sheetShape)) {
                // Glass inside the opaque sheet must not sample Home (another window) — it would tint
                // toolbar circles with wallpaper colours. Without a backdrop it draws its fallback fill.
                CompositionLocalProvider(LocalPanelClose provides close, LocalIosSheetDrag provides sheetDrag,
                    LocalContentColor provides iosLabel(), LocalDuoGlassBackdrop provides null) {
                    Box(Modifier.navigationBarsPadding()) { content() }
                }
            }
            IosBannerHost(insidePanel = true, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

/** Legacy names kept for callers; both now draw the iOS inset-grouped look. */
@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) =
    IosInsetGroup(title = title, content = content)

@Composable
internal fun SettingsSeparator() = IosSeparator()

internal val LocalPanelClose = staticCompositionLocalOf<(() -> Unit)?> { null }

internal fun uxText(chinese: String, english: String): String =
    if (java.util.Locale.getDefault().language == "zh") chinese else english
