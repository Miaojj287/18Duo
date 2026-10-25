package com.jake.duolauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Visible state of the one-time "re-enable the service for live app cards" notice. */
internal enum class SnapshotNotice { HIDDEN, SHOWN }

/** Pure decision: show once, only when the service runs without the capability and was not dismissed. */
internal fun snapshotNotice(serviceEnabled: Boolean, checked: Boolean, granted: Boolean, dismissed: Boolean): SnapshotNotice =
    if (serviceEnabled && checked && !granted && !dismissed) SnapshotNotice.SHOWN else SnapshotNotice.HIDDEN

/**
 * Whether the navigation service may take the gesture-start screenshot. Android grants the
 * capability declared in the service XML when the user enables the service; a service enabled
 * by an older 18Duo (before the capability existed) can keep running without it on some
 * systems until it is switched off and on again. Everything degrades to icon cards meanwhile.
 */
internal object SnapshotCapability {
    private const val PREFS = "navigation_gestures"
    @Volatile var granted = false
        private set

    fun update(context: Context, value: Boolean) {
        granted = value
        context.getSharedPreferences(PREFS, 0).edit()
            .putBoolean("snapshot_checked", true).putBoolean("snapshot_capability", value).apply()
    }

    fun notice(context: Context): SnapshotNotice {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return snapshotNotice(SystemShadeAccessibilityService.isEnabled(context), prefs.getBoolean("snapshot_checked", false),
            prefs.getBoolean("snapshot_capability", true), prefs.getBoolean("snapshot_notice_dismissed", false))
    }

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("snapshot_notice_dismissed", true).apply()
    }
}

/** One quiet settings row, never a dialog: the switcher works without snapshots, just less like iOS. */
@Composable
internal fun SnapshotCapabilityNotice() {
    val context = LocalContext.current
    var notice by remember { mutableStateOf(SnapshotCapability.notice(context)) }
    if (notice == SnapshotNotice.HIDDEN) return
    IosInsetGroup(footer = uxText(
        "App 切换器需要「截取屏幕」能力才能显示各应用的实时画面。在无障碍设置中将「18Duo 导航手势」关闭再打开一次即可；截图只保存在本机（不备份、不上传）。",
        "The app switcher needs the screenshot capability to show live app cards. Turn \"18Duo navigation\" off and on again in Accessibility settings; captures stay on this phone (never backed up or sent).")) {
        IosRow(uxText("重新开启导航服务以显示应用画面", "Re-enable navigation for live app cards"), icon = IosSymbols.iphone,
            tint = IosUi.indigo, tag = "snapshot-capability-notice", onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        IosSeparator()
        IosRow(uxText("不再提示", "Don’t show again"), icon = IosSymbols.xmark, tint = IosUi.grey, chevron = false,
            tag = "snapshot-capability-dismiss", onClick = {
                SnapshotCapability.dismiss(context); notice = SnapshotNotice.HIDDEN
            })
    }
}
