package com.jake.duolauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 26 "隐藏“App”？" sheet (reference: TidBITS, "iOS 26's Confusing Hidden Folder Drag Behavior", 2026-06):
 * the app icon with an obscured-app badge, a bold leading title, what hiding does, two explained points and
 * one blue "隐藏 App" capsule; a glass ✕ closes it. A bottom sheet on the narrow cover screen, a centred form
 * sheet on the unfolded inner screen. It rises on the sheet spring and can be cancelled mid-flight.
 *
 * The second point differs from iOS on purpose: a launcher cannot silence another app's notifications, so the
 * sheet says so instead of promising it.
 */
@Composable
internal fun HideAppSheetHost(app: AppEntry?, onHide: (AppEntry) -> Unit, onDismiss: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<AppEntry?>(null) }
    LaunchedEffect(app) {
        if (app != null) {
            shown = app
            progress.animateTo(1f, if (LauncherMotion.reduced) LauncherMotion.dissolveIn else LauncherMotion.springSpec(HIDE_SHEET_IN, .001f))
        } else if (shown != null) {
            progress.animateTo(0f, if (LauncherMotion.reduced) LauncherMotion.dissolveOut else LauncherMotion.springSpec(HIDE_SHEET_OUT, .001f))
            shown = null
        }
    }
    val current = shown ?: return
    BackHandler(app != null) { onDismiss() }
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag("hide-app-overlay")) {
        val wide = maxWidth.value >= LIBRARY_WIDE_MIN_WIDTH
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = .28f))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() })
        val shape = RoundedCornerShape(if (wide) 34.dp else 38.dp)
        val sheetHeightGuess = 560.dp
        Column(Modifier.align(if (wide) Alignment.Center else Alignment.BottomCenter)
            .then(if (wide) Modifier.width(540.dp) else Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                .navigationBarsPadding())
            .graphicsLayer {
                val p = progress.value
                if (LauncherMotion.reduced) alpha = p.coerceIn(0f, 1f)
                else if (wide) {
                    // Form sheet: rises a little and settles, like an iPad sheet.
                    translationY = (1f - p) * size.height * .35f; alpha = (p * 3f).coerceIn(0f, 1f)
                } else translationY = (1f - p) * (size.height + sheetHeightGuess.toPx() * .1f)
            }
            .clip(shape).iosSheetSurface(shape)
            .clickable(remember { MutableInteractionSource() }, null) { }
            .testTag("hide-app-sheet")) {
            Box(Modifier.fillMaxWidth().padding(top = 14.dp, end = 14.dp)) {
                IosGlassCircleButton(IosSymbols.xmark, uxText("关闭", "Close"), onDismiss, Modifier.align(Alignment.CenterEnd),
                    tag = "hide-app-close")
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)) {
                Box(Modifier.size(84.dp)) {
                    Image(current.icon.asImageBitmap(), null, Modifier.size(76.dp).clip(homeIconShape(76f)))
                    Box(Modifier.align(Alignment.BottomEnd).size(28.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE5E5EA)), contentAlignment = Alignment.Center) {
                        Icon(IosSymbols.appDashed, null, Modifier.size(20.dp), tint = Color(0xFF8E8E93))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(uxText("隐藏“${current.label}”？", "Hide “${current.label}”?"), Modifier.semantics { heading() },
                    color = iosLabel(), fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val proof = if (AppPrivacy.method(context) == AppPrivacy.Method.PASSCODE) uxText("锁屏密码", "your screen lock")
                    else uxText("${AppPrivacy.methodName(context)}或锁屏密码", "${AppPrivacy.methodName(context)} or your screen lock")
                Text(uxText("此 App 将不再显示在主屏幕和搜索中。需要验证${proof}才能显示或打开它。",
                    "This app will no longer appear on the Home Screen or in Search. You’ll need $proof to reveal or open it."),
                    color = iosLabel(), fontSize = 17.sp, lineHeight = 23.sp)
                Spacer(Modifier.height(22.dp))
                HideSheetPoint(IosSymbols.appDashed, uxText("隐藏在 App 资源库中", "Kept in App Library"),
                    uxText("此 App 只会出现在 App 资源库底部、需要验证才能打开的“已隐藏”文件夹中。",
                        "The app appears only in the locked Hidden folder at the bottom of App Library."))
                Spacer(Modifier.height(18.dp))
                HideSheetPoint(IosSymbols.bell, uxText("通知照常送达", "Notifications Still Arrive"),
                    uxText("主屏幕无法替其他 App 关闭通知。如需静音，请在系统设置中关闭此 App 的通知。",
                        "A Home Screen can’t silence another app. Turn its notifications off in Settings if you need to."))
                Spacer(Modifier.height(24.dp))
            }
            IosButton(uxText("隐藏 App", "Hide App"), { onHide(current) },
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), tag = "hide-app-confirm")
        }
    }
}

@Composable
private fun HideSheetPoint(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.offset(y = 2.dp).size(28.dp), tint = IosUi.blue)
        Column(Modifier.widthIn(max = 460.dp)) {
            Text(title, color = iosLabel(), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = iosSecondaryText(), fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}

/** Sheet presentation (UIKit's sheet spring is critically damped, ≈ 0.5 s); dismissal a touch quicker. */
private val HIDE_SHEET_IN = IosSpring(.5f, 1f)
private val HIDE_SHEET_OUT = IosSpring(.4f, 1f)
