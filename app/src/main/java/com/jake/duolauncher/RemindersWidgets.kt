package com.jake.duolauncher

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

// =======================================================================================
// Reminders ▸ List (iOS 26 提醒事项 ▸ 列表), small and medium. Measured on the owner's iPhone Duo
// (evidence/r21/ref/1.webp, small card 466 px = 158 pt): "提醒事项" 15 pt bold in system blue,
// the open count 22 pt bold at the right, "没有提醒事项" 13 pt grey below, on the iOS 26 dark platter.
//
// Data: Android has no shared reminders store. HyperOS keeps to-dos inside Xiaomi Notes
// (com.miui.todo.provider), behind the same signature|privileged ACCESS_NOTE permission as notes
// (see NotesWidgets.kt), and CalendarContract has no tasks. A placed widget therefore shows iOS's
// empty list (0, 没有提醒事项) and opens Xiaomi Notes' to-do list on tap (else Google Tasks, else
// the calendar); sample reminders appear ONLY in the gallery preview.
// =======================================================================================

private const val REMINDERS_LIST_ZH = "查看列表中的提醒事项。Android 没有可读取的提醒事项来源，放置后显示为空，轻点打开待办。"
private const val REMINDERS_LIST_EN = "Keep track of the reminders in a list. Android has no reminders source apps can read, " +
    "so the placed widget stays empty and opens your to-do app when tapped."

internal fun remindersWidgetSpecs(): List<IosWidgetSpec> = listOf(
    IosWidgetSpec(-187, NativeWidgetKind.REMINDERS, "reminders", NativeWidgetSize.SMALL, "列表", "List",
        REMINDERS_LIST_ZH, REMINDERS_LIST_EN, featured = 26, opens = ::remindersAppIntent) { RemindersWidget(this) },
    IosWidgetSpec(-188, NativeWidgetKind.REMINDERS, "reminders", NativeWidgetSize.MEDIUM, "列表", "List",
        REMINDERS_LIST_ZH, REMINDERS_LIST_EN, opens = ::remindersAppIntent) { RemindersWidget(this) },
)

/** Xiaomi Notes' to-do list (its own `com.miui.todo.action.VIEW`), else Google Tasks, else the calendar. */
internal fun remindersAppIntent(context: Context): Intent? {
    val pm = context.packageManager
    val todo = Intent("com.miui.todo.action.VIEW").setPackage("com.miui.notes")
    if (runCatching { pm.resolveActivity(todo, 0) }.getOrNull() != null) return todo
    return listOf("com.google.android.apps.tasks", "com.miui.notes").firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        ?: calendarAppIntent()
}

/** Gallery-only sample list (iOS previews widgets with sample reminders too). Never shown on a placed widget. */
internal fun sampleReminders(): List<String> =
    listOf(uxText("买牛奶", "Buy milk"), uxText("给妈妈打电话", "Call Mom"), uxText("预约体检", "Book a checkup"))

/*
 * Points on the card (1.webp): title ink from 18.2 pt, centred on 24.7; the count's ink ends
 * 17.2 pt from the right edge, centred on 24.5; the message centred on 50.5. Sample rows (preview
 * only) continue at that line, 21 pt apart, each after iOS's empty completion ring.
 */
private const val REM_LEFT = 17.8f
private const val REM_TITLE_CENTER = 24.7f
private const val REM_COUNT_CENTER = 24.5f
private const val REM_COUNT_RIGHT = 16.4f
private const val REM_FIRST_ROW = 50.5f
private const val REM_ROW_PITCH = 21f

@Composable
private fun RemindersWidget(scope: IosWidgetContext) {
    val colors = LocalWidgetColors.current
    val items = if (scope.preview) sampleReminders() else emptyList()
    val blue = when {
        colors.clear -> Color.White
        colors.background.luminanceValue() > .5f -> Color(0xFF007AFF)
        else -> Color(0xFF0A84FF)
    }
    IosWidgetCard(scope.modifier, scope.family, background = darkWidgetPlatter(colors)) { s ->
        val width = scope.family.widthPt
        WidgetText(uxText("提醒事项", "Reminders"), s, 15f, Modifier.align(Alignment.TopStart)
            .offset(s.pt(REM_LEFT), s.pt(REM_TITLE_CENTER - 9f)).width(s.pt(width - 2 * REM_LEFT - 30f)),
            weight = FontWeight.Bold, color = blue, lineHeight = 18f, tracking = 0f)
        WidgetText("${items.size}", s, 22f, Modifier.align(Alignment.TopStart)
            .offset(s.pt(width - REM_COUNT_RIGHT - 40f), s.pt(REM_COUNT_CENTER - 13f)).width(s.pt(40f)),
            weight = FontWeight.Bold, lineHeight = 26f, align = TextAlign.End, tracking = 0f)
        if (items.isEmpty()) ReminderLine(uxText("没有提醒事项", "No Reminders"), s, REM_LEFT, REM_FIRST_ROW, width - 2 * REM_LEFT,
            colors.secondary)
        else ReminderRows(items, s, width, colors)
    }
}

@Composable
private fun BoxScope.ReminderLine(text: String, s: WidgetScale, left: Float, center: Float, width: Float, color: Color) {
    WidgetText(text, s, 13f, Modifier.align(Alignment.TopStart).offset(s.pt(left), s.pt(center - 9f)).width(s.pt(width)),
        color = color, lineHeight = 18f, tracking = 0f)
}

/** Preview rows: an empty ring then the reminder, as many as fit above the bottom margin. */
@Composable
private fun BoxScope.ReminderRows(items: List<String>, s: WidgetScale, width: Float, colors: WidgetColors) {
    val fits = ((158f - 16f - REM_FIRST_ROW) / REM_ROW_PITCH).toInt() + 1
    val shown = items.take(fits)
    Canvas(Modifier.align(Alignment.TopStart).size(s.pt(width), s.pt(158f))) {
        val px = s.px(1f)
        shown.indices.forEach { i ->
            drawCircle(colors.tertiary, 6f * px, Offset((REM_LEFT + 6.6f) * px, (REM_FIRST_ROW + i * REM_ROW_PITCH) * px),
                style = Stroke(1.3f * px))
        }
    }
    shown.forEachIndexed { i, item ->
        ReminderLine(item, s, REM_LEFT + 19f, REM_FIRST_ROW + i * REM_ROW_PITCH, width - 2 * REM_LEFT - 19f, colors.primary)
    }
}

private fun Color.luminanceValue(): Float = .2126f * red + .7152f * green + .0722f * blue
