package com.jake.duolauncher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private class StepSpec(
    val step: SetupStep, val icon: ImageVector, val tint: Color, val title: Int, val why: Int, val action: Int,
    val guide: Int, val images: List<Pair<Int, Int?>>,
)

private val stepSpecs = listOf(
    StepSpec(SetupStep.DEFAULT_HOME, IosSymbols.house, IosUi.blue, R.string.wizard_step1_title, R.string.wizard_step1_why,
        R.string.wizard_step1_action, R.string.wizard_step1_guide, listOf(R.drawable.setup_guide_1_1 to null)),
    StepSpec(SetupStep.NAVIGATION_SERVICE, IosSymbols.accessibility, IosUi.green, R.string.wizard_step2_title, R.string.wizard_step2_why,
        R.string.wizard_step2_action, R.string.wizard_step2_guide,
        listOf(R.drawable.setup_guide_2_1 to R.string.wizard_step2_caption1, R.drawable.setup_guide_2_2 to R.string.wizard_step2_caption2)),
    StepSpec(SetupStep.FULLSCREEN_GESTURES, IosSymbols.swipeUp, IosUi.indigo, R.string.wizard_step3_title, R.string.wizard_step3_why,
        R.string.wizard_step3_action, R.string.wizard_step3_guide, listOf(R.drawable.setup_guide_3_1 to null)),
)

/** HyperOS step 3: the wireless-debugging grant is the way in, not an optional extra. */
private val grantGestureSpec = StepSpec(SetupStep.FULLSCREEN_GESTURES, IosSymbols.swipeUp, IosUi.indigo, R.string.wizard_step3_title,
    R.string.wizard_step3_grant_why, R.string.wizard_step3_grant_action, R.string.wizard_step3_grant_guide,
    listOf(R.drawable.setup_guide_3_2 to null, R.drawable.setup_guide_3_3 to null, R.drawable.setup_guide_3_4 to null))

/**
 * Step-by-step first-run wizard, laid out like an iOS setup sheet: centred bold title, a short
 * progress strip, the steps as one inset-grouped list (crisp opaque cells — the Core.16 cards were
 * glass inside a glass sheet and read as muddy over Home), a filled capsule button for the current
 * step and "Set Up Later" as a plain button at the bottom. It stays open until every step is done
 * (then shows a short success state and dismisses itself) or until the user explicitly skips.
 * [state] is re-read by MainActivity.onResume, so coming back from system Settings advances the step.
 */
@Composable
internal fun SetupWizardSheet(state: SetupWizardState, onMakeDefault: () -> Unit, onAccessibility: () -> Unit,
    onGestures: () -> Unit, onHidePill: () -> Unit, onSkip: () -> Unit, onComplete: () -> Unit,
    backgrounds: LauncherBackgroundController? = null) {
    var celebrated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.complete) {
        if (state.complete && !celebrated) { celebrated = true; delay(1200); onComplete() }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        // Like iOS setup sheets (modal-in-presentation) it has no grabber: it is left with a button.
        Spacer(Modifier.height(12.dp))
        val scroll = rememberScrollState()
        // Cards fade at the viewport edges (iOS 26) instead of having their corners cut square.
        Column(Modifier.weight(1f).iosScrollEdgeFade(fadeTop = { scroll.value > 0 }, fadeBottom = { scroll.canScrollForward })
            .verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 12.dp)) {
            WizardHeader(state)
            val context = LocalContext.current
            val grantGestures = remember(context) { systemGesturesNeedGrant(context) }
            if (state.complete) SuccessCard() else IosInsetGroup {
                stepSpecs.map { if (grantGestures && it.step == SetupStep.FULLSCREEN_GESTURES) grantGestureSpec else it }
                    .forEachIndexed { index, spec ->
                    if (index > 0) IosSeparator(inset = 16.dp + 30.dp + 14.dp)
                    StepRow(spec, state.status(spec.step), state, onMakeDefault, onAccessibility, onGestures, onHidePill)
                }
                IosSeparator(inset = 16.dp + 30.dp + 14.dp)
                WallpaperStepRow(state.status(SetupStep.WALLPAPER), backgrounds)
            }
        }
        // Instrumentation tests dismiss first-run with this tag; keep it as the explicit exit.
        IosPlainButton(stringResource(if (state.complete) R.string.editor_done else R.string.wizard_skip), onSkip,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp, top = 2.dp), tag = "setup-explore",
            bold = state.complete)
    }
}

@Composable
private fun WizardHeader(state: SetupWizardState) {
    val stepNumber = (state.current?.ordinal ?: (SetupStep.entries.size - 1)) + 1
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(IosUi.blue, ContinuousRoundedShape(15.dp)), contentAlignment = Alignment.Center) {
            Icon(IosSymbols.editHomeScreen, null, Modifier.size(38.dp), tint = Color.White)
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.wizard_title), Modifier.semantics { heading() }, textAlign = TextAlign.Center,
            style = TextStyle(fontFamily = DuoFonts.display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp,
                letterSpacing = duoTracking(30f)), color = iosLabel())
        Text(if (state.complete) stringResource(R.string.wizard_done)
            else stringResource(R.string.wizard_progress, stepNumber, SetupStep.entries.size),
            Modifier.padding(top = 4.dp).testTag("wizard-progress"), fontSize = 15.sp, color = iosSecondaryText(),
            textAlign = TextAlign.Center)
        Row(Modifier.width(180.dp).padding(top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SetupStep.entries.forEach { step ->
                val filled by animateFloatAsState(if (state.done(step)) 1f else 0f,
                    if (LauncherMotion.reduced) tween(0) else LauncherMotion.wizardSegment, label = "segment")
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(iosFillColor())) {
                    Box(Modifier.fillMaxWidth(filled).fillMaxHeight().background(IosUi.green))
                }
            }
        }
    }
}

@Composable
private fun StepRow(spec: StepSpec, status: SetupStepStatus, state: SetupWizardState, onMakeDefault: () -> Unit,
    onAccessibility: () -> Unit, onGestures: () -> Unit, onHidePill: () -> Unit) {
    val expanded = status == SetupStepStatus.CURRENT
    var guideOpen by rememberSaveable(spec.step) { mutableStateOf(false) }
    val index = spec.step.ordinal + 1
    val action = when (spec.step) {
        SetupStep.DEFAULT_HOME -> onMakeDefault
        SetupStep.NAVIGATION_SERVICE -> onAccessibility
        SetupStep.FULLSCREEN_GESTURES -> onGestures
        SetupStep.WALLPAPER -> ({})
    }
    Column(Modifier.fillMaxWidth().testTag("wizard-step-$index")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            StepBadge(index, status, spec.icon, spec.tint)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(spec.title), fontSize = 17.sp, lineHeight = 22.sp,
                    fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (status == SetupStepStatus.UPCOMING) iosSecondaryText() else iosLabel())
            }
            if (status == SetupStepStatus.DONE) Text(stringResource(R.string.wizard_step_done), fontSize = 15.sp,
                color = iosSecondaryText(), modifier = Modifier.padding(start = 8.dp))
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 16.dp + 30.dp + 14.dp, end = 16.dp, bottom = 14.dp)) {
                Text(stringResource(spec.why), fontSize = 15.sp, lineHeight = 20.sp, color = iosSecondaryText())
                IosButton(stringResource(spec.action), action, Modifier.fillMaxWidth().padding(top = 12.dp), tag = "wizard-action-$index")
                GuideToggle(guideOpen, { guideOpen = !guideOpen }, "wizard-guide-$index")
                AnimatedVisibility(guideOpen) { GuideBody(spec.guide, spec.images) }
                if (spec.step == SetupStep.FULLSCREEN_GESTURES && spec !== grantGestureSpec) OptionalPill(state, onHidePill)
            }
        }
    }
}

/** Settings-style tile for the step; a green checkmark once done, the step number while upcoming. */
@Composable
internal fun StepBadge(index: Int, status: SetupStepStatus, icon: ImageVector, tint: Color) {
    val shape = ContinuousRoundedShape(7.dp)
    when (status) {
        SetupStepStatus.DONE -> Box(Modifier.size(30.dp).background(IosUi.green, shape), contentAlignment = Alignment.Center) {
            Icon(IosSymbols.checkmark, null, Modifier.size(18.dp), tint = Color.White)
        }
        SetupStepStatus.CURRENT -> Box(Modifier.size(30.dp).background(tint, shape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = Color.White)
        }
        SetupStepStatus.UPCOMING -> Box(Modifier.size(30.dp).background(iosFillColor(), shape), contentAlignment = Alignment.Center) {
            Text("$index", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = iosSecondaryText())
        }
    }
}

@Composable
private fun GuideToggle(open: Boolean, onToggle: () -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(ContinuousRoundedShape(10.dp))
        .clickable(remember { MutableInteractionSource() }, IosDimIndication, role = Role.Button, onClick = onToggle)
        .padding(vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.wizard_guide), Modifier.weight(1f), fontSize = 15.sp, color = iosAccent())
        IosDisclosureChevron(open)
    }
}

@Composable
private fun GuideBody(guide: Int, images: List<Pair<Int, Int?>>) {
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stringResource(guide).split('\n').forEachIndexed { i, line ->
            Row(verticalAlignment = Alignment.Top) {
                Text("${i + 1}.", Modifier.width(22.dp), fontSize = 14.sp, lineHeight = 19.sp, color = iosSecondaryText())
                Text(line, fontSize = 14.sp, lineHeight = 19.sp, color = iosLabel())
            }
        }
        images.forEach { (drawable, caption) ->
            Image(painterResource(drawable), null, Modifier.fillMaxWidth().clip(ContinuousRoundedShape(14.dp)),
                contentScale = ContentScale.FillWidth)
            caption?.let { Text(stringResource(it), fontSize = 12.sp, color = iosSecondaryText()) }
        }
    }
}

@Composable
private fun OptionalPill(state: SetupWizardState, onHidePill: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(iosGroupedBackground(), ContinuousRoundedShape(12.dp))
        .padding(12.dp)) {
        Text(stringResource(R.string.wizard_step3_optional_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = iosLabel())
        Text(stringResource(R.string.wizard_step3_optional_why), fontSize = 13.sp, lineHeight = 18.sp, color = iosSecondaryText(),
            modifier = Modifier.padding(top = 2.dp))
        IosButton(stringResource(R.string.wizard_step3_optional_action), onHidePill, Modifier.fillMaxWidth().padding(top = 10.dp),
            filled = false, enabled = state.navigationService, tag = "wizard-hide-pill")
        GuideToggle(open, { open = !open }, "wizard-guide-pill")
        AnimatedVisibility(open) {
            GuideBody(R.string.wizard_step3_optional_guide, listOf(R.drawable.setup_guide_3_2 to null, R.drawable.setup_guide_3_3 to null, R.drawable.setup_guide_3_4 to null))
        }
    }
}

@Composable
private fun SuccessCard() {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).background(iosGroupFill(), ContinuousRoundedShape(IosUi.groupRadius))
        .padding(28.dp).testTag("wizard-success"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(IosUi.green, CircleShape), contentAlignment = Alignment.Center) {
            Icon(IosSymbols.checkmark, null, Modifier.size(30.dp), tint = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.wizard_done), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = iosLabel())
        Text(stringResource(R.string.wizard_done_detail), fontSize = 15.sp, color = iosSecondaryText(), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp))
    }
}
