package com.jake.duolauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class IosAlertRole { Default, Cancel, Destructive }

@Immutable
internal data class IosAlertAction(val label: String, val tag: String, val role: IosAlertRole = IosAlertRole.Default,
    val onClick: () -> Unit)

@Immutable
internal data class IosAlertRequest(val key: Any, val title: String, val message: String?, val actions: List<IosAlertAction>)

private val alertInSpring get() = LauncherMotion.alertIn
private val alertOutSpring get() = LauncherMotion.alertOut

/**
 * iOS 26 alert on Liquid Glass: leading-aligned bold title, message, capsule buttons (side by
 * side for two, stacked otherwise; Cancel last). Replaces Material AlertDialog in Home editing.
 * It zooms down from 1.08 and fades in, like UIAlertController.
 */
@Composable
internal fun IosAlertHost(request: IosAlertRequest?, onDismiss: () -> Unit, opaque: Boolean = false) {
    val retained = remember { arrayOfNulls<IosAlertRequest>(1) }
    var visibleKey by remember { mutableStateOf<Any?>(null) }
    val progress = remember { Animatable(0f) }
    val reduced = rememberReduceMotion()
    val key = request?.key
    LaunchedEffect(key) {
        if (key != null) {
            if (visibleKey != key) progress.snapTo(0f)
            visibleKey = key
            // Reduce Motion: fade only (no 1.08 → 1 zoom), as UIAlertController does.
            progress.animateTo(1f, if (reduced) LauncherMotion.dissolveIn else alertInSpring)
        } else if (visibleKey != null) {
            progress.animateTo(0f, if (reduced) LauncherMotion.dissolveOut else alertOutSpring)
            visibleKey = null
        }
    }
    SideEffect { if (request != null) retained[0] = request }
    val alert = request ?: retained[0]?.takeIf { visibleKey != null } ?: return
    val live = request != null
    if (live) BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()
        .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
        .background(Color.Black.copy(alpha = .22f))
        // Modal: every touch on the dimmed screen belongs to the alert (nothing beneath is lifted).
        .overlayInputShield(live)
        .then(if (live) Modifier.clickable(interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = {}) else Modifier)
        .testTag("ios-alert-overlay"), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(34.dp)
        Column(Modifier.fillMaxWidth(.86f).wrapContentWidth().widthIn(max = 300.dp).fillMaxWidth()
            .graphicsLayer {
                val p = progress.value
                val scale = if (LauncherMotion.reduced) 1f else 1.08f - .08f * p
                scaleX = scale; scaleY = scale
                alpha = p.coerceIn(0f, 1f)
            }
            .duoGlass(DuoGlassRole.Elevated, shape)
            .background((if (LocalDuoPalette.current.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)).copy(alpha = if (opaque) .97f else .80f), shape)
            .semantics { paneTitle = alert.title }
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 16.dp)
            .testTag("ios-alert")) {
            CompositionLocalProvider(LocalContentColor provides duoGlassContentColor(DuoGlassRole.Elevated)) {
                Text(alert.title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = LocalContentColor.current)
                alert.message?.let {
                    Spacer(Modifier.height(6.dp))
                    // Long messages (layout restore review) scroll instead of pushing the buttons away.
                    Text(it, Modifier.heightIn(max = 300.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        fontSize = 14.sp, lineHeight = 19.sp, color = LocalContentColor.current.copy(alpha = .78f))
                }
                Spacer(Modifier.height(18.dp))
                val ordered = alert.actions.sortedBy { if (it.role == IosAlertRole.Cancel) 1 else 0 }
                // Two short choices sit side by side; longer labels stack full width (iOS 26).
                val sideBySide = ordered.size == 2 && ordered.all { action ->
                    action.label.fold(0) { width, ch -> width + if (ch.code > 0x2E80) 2 else 1 } <= 14
                }
                if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Side by side, Cancel on the leading side as in iOS 26.
                    ordered.reversed().forEach { AlertCapsule(it, live, Modifier.weight(1f)) }
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ordered.forEach { AlertCapsule(it, live, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun AlertCapsule(action: IosAlertAction, enabled: Boolean, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val content = LocalContentColor.current
    // iOS 26 alert capsules use the label colour (not tint blue); only a destructive choice is red.
    val color = when (action.role) {
        IosAlertRole.Destructive -> DestructiveRed
        IosAlertRole.Cancel, IosAlertRole.Default -> content
    }
    Box(modifier.heightIn(min = 46.dp)
        .graphicsLayer { val s = if (pressed) .97f else 1f; scaleX = s; scaleY = s }
        .background(content.copy(alpha = if (pressed) .16f else .09f), CircleShape)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
            onClick = action.onClick)
        .testTag(action.tag), contentAlignment = Alignment.Center) {
        Text(action.label, color = color, fontSize = 17.sp,
            fontWeight = if (action.role == IosAlertRole.Destructive) FontWeight.Medium else FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
    }
}
