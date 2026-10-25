package com.jake.duolauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale

/**
 * Settings → "锁屏": the on/off switch (default off), HyperOS permissions and what to expect.
 * Round 12: while the switch is off, a card at the top guides the owner through turning it on (the
 * 默认桌面 check and the two HyperOS app-ops as a ✓/✗ checklist); under the switch, the last launch
 * decision from the diagnostics ring ("上次：13:41 显示").
 */
@Composable
internal fun LockScreenSettingsGroup(activity: MainActivity) {
    val context = LocalContext.current
    val enabled = LockScreenController.enabled.value
    val zh = Locale.getDefault().language == "zh"
    var home by remember { mutableStateOf(LockScreenController.isDefaultHome(context)) }
    var permissions by remember { mutableStateOf(LockScreenController.permissionsSnapshot) }
    // Re-read the role and the HyperOS ops every time Settings comes back to the front (the owner
    // returns from HyperOS's permission editor or the Home chooser).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            home = LockScreenController.isDefaultHome(context)
            permissions = LockScreenPermissions.read(context).also { LockScreenController.permissionsSnapshot = it }
        }
    }
    val title = uxText("18Duo 锁屏", "18Duo Lock Screen")
    val groupTitle = uxText("锁屏", "Lock Screen")
    val checklist = lockSetupChecklist(home, permissions)
    if (!enabled) LockScreenGuideCard(activity, groupTitle, checklist, context)
    val footer = buildString {
        append(uxText(
            "熄屏后在系统锁屏之上显示 iPhone Duo 样式的锁屏（与桌面同一壁纸）。上滑由系统验证密码、指纹或人脸；" +
                "从熄屏按电源键指纹可直接解锁进入桌面。亮屏状态下系统不会在第三方锁屏上监听指纹，需先上滑。" +
                "返回手势随时回到系统锁屏（含紧急呼叫）。开机后的第一次解锁始终是系统锁屏。",
            "After the screen turns off, an iPhone Duo-style Lock Screen (your Home wallpaper) shows over the system " +
                "lock screen. Swiping up lets the system check your PIN, fingerprint or face; a fingerprint on the power " +
                "key from a dark screen still goes straight Home. While the screen is on, the system does not listen " +
                "for a fingerprint over a third-party lock screen, so swipe up first. Back always returns to the " +
                "system lock screen (with emergency call). The first unlock after a restart is always the system's."))
        if (!home) append(uxText("\n需要将 18Duo 设为默认桌面。", "\nRequires 18Duo as the default Home app."))
        permissions.settingsNote(zh)?.let { append('\n').append(it) }
        if (LockScreenController.autoDisabled.value) append(uxText(
            "\n系统连续两次没有显示 18Duo 锁屏，已自动关闭。请在 HyperOS 中允许「锁屏显示」和「后台弹出界面」后重新打开。",
            "\nThe system did not show the 18Duo Lock Screen twice in a row, so it was turned off. Allow “Show on lock " +
                "screen” and background pop-ups in HyperOS, then turn it on again."))
    }
    IosInsetGroup(if (enabled) groupTitle else null, footer = footer) {
        // The last launch decision, from the diagnostics ring (adb logcat | grep 18DuoLockScreen has all of them).
        val last = lockLaunchSettingsLine(LockScreenController.lastEvent.value, zh)
        IosToggleRow(title, enabled, { LockScreenPreferences(context).setEnabled(it) }, detail = last,
            icon = IosSymbols.lock, tint = IosUi.blue, switchTag = "settings-lock-screen", contentDescription = title)
        IosSeparator()
        // Round 11: the customisation editor (fonts, colour, size handle, accessory widgets), no keyguard needed.
        IosRow(uxText("自定锁屏", "Customize Lock Screen"), icon = IosSymbols.clock, tint = IosUi.indigo,
            detail = uxText("时间字体、颜色、对齐与大小，锁屏小组件。锁屏上长按也可进入。", "Time font, colour, alignment and size; Lock Screen widgets. Also by long-pressing the Lock Screen."),
            tag = "settings-lock-screen-customize", onClick = {
                activity.startActivity(Intent(activity, LockScreenActivity::class.java)
                    .putExtra(LockScreenActivity.EXTRA_EDIT, true))
            })
        if (permissions.hyperOs) {
            IosSeparator()
            val marks = "${permissions.showWhenLocked.mark} ${uxText("锁屏显示", "Lock screen")} · " +
                "${permissions.backgroundStart.mark} ${uxText("后台弹出界面", "Background pop-ups")}"
            IosRow(uxText("开启系统锁屏显示权限", "Allow on the lock screen"), icon = IosSymbols.lock, tint = IosUi.red,
                detail = uxText("在 HyperOS 中允许「锁屏显示」与「后台弹出界面」。当前：$marks",
                    "In HyperOS, allow “Show on lock screen” and background pop-ups. Now: $marks"),
                tag = "lock-screen-permissions", onClick = activity::openLockClockPermissions)
        }
    }
}

/**
 * "打开 18Duo 锁屏": the round-11 inset-group card that replaces the owner's "exit app" idea — one
 * headline, the checklist (✓ / ✗ / ？ per step, each row opens the place to fix it) and the blue action.
 */
@Composable
private fun LockScreenGuideCard(activity: MainActivity, groupTitle: String, checklist: List<LockSetupItem>,
    context: android.content.Context) {
    val ready = lockSetupReady(checklist)
    IosInsetGroup(groupTitle, modifier = Modifier.testTag("lock-screen-guide")) {
        IosRow(uxText("打开 18Duo 锁屏", "Turn on the 18Duo Lock Screen"), icon = IosSymbols.lock, tint = IosUi.blue, chevron = false,
            detail = uxText("熄屏后，锁屏会是 iPhone Duo 的样子：居中的时间、小组件、玻璃上滑解锁。先确认下面几项，再打开。",
                "After the screen turns off, the Lock Screen looks like the iPhone Duo's: centred time, widgets, glass swipe to unlock. Confirm the steps below, then turn it on."))
        checklist.forEach { item ->
            IosSeparator()
            val tint = when (item.state) {
                LockOpState.ALLOWED -> IosUi.green
                LockOpState.DENIED -> IosUi.red
                LockOpState.DEFAULT, LockOpState.UNKNOWN -> IosUi.orange
            }
            val hint = when (item.state) {
                LockOpState.ALLOWED -> null
                LockOpState.DENIED -> if (item.step == LockSetupStep.DEFAULT_HOME) uxText("点击在系统设置中选择 18Duo。", "Tap to choose 18Duo in system settings.")
                    else uxText("点击前往 HyperOS 权限页允许。", "Tap to allow it in HyperOS permissions.")
                LockOpState.DEFAULT, LockOpState.UNKNOWN -> uxText("18Duo 读不到这项权限的状态，请点击前往确认。", "18Duo cannot read this permission; tap to confirm it.")
            }
            IosRow(item.title, detail = hint, chevron = item.state != LockOpState.ALLOWED,
                tag = "lock-screen-guide-${item.step.name.lowercase()}",
                onClick = when (item.step) {
                    LockSetupStep.DEFAULT_HOME -> ({ openHomeChooser(activity) })
                    else -> activity::openLockClockPermissions
                },
                trailing = {
                    Icon(if (item.state.ok) IosSymbols.checkmark else if (item.state == LockOpState.DENIED) IosSymbols.xmark else IosSymbols.questionCircle,
                        null, Modifier.padding(start = 8.dp).size(18.dp), tint = tint)
                })
        }
        IosSeparator()
        IosRow(if (ready) uxText("现在打开", "Turn On Now") else uxText("仍然打开", "Turn On Anyway"), chevron = false,
            titleColor = IosUi.blue, tag = "lock-screen-guide-enable",
            detail = if (ready) null else uxText("有未确认的步骤时系统可能拦下 18Duo 锁屏；连续两次未显示会自动关闭。",
                "With a step unconfirmed the system may block the 18Duo Lock Screen; it turns itself off after two unseen launches."),
            onClick = { LockScreenPreferences(context).setEnabled(true) })
    }
}

/** The system's Home chooser (the role request is unreliable on some OEMs). */
private fun openHomeChooser(activity: MainActivity) {
    try { activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    catch (_: ActivityNotFoundException) {
        runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    }
}
