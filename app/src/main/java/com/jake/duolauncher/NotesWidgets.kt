package com.jake.duolauncher

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// =======================================================================================
// Notes ▸ Folder (iOS 26 备忘录 ▸ 文件夹), small and medium. Measured on the owner's iPhone Duo
// (evidence/r21/ref/1.webp, small card 458 px = 158 pt; 4.webp agrees): a yellow header band with
// the folder outline and the folder's name, a dotted rule, then the latest note — title (white),
// its first line and its date (grey, "星期一").
//
// Data: HyperOS's Notes app (com.miui.notes 3.2.7.9 on the owner's phone) keeps notes behind its
// `notes` / `com.miui.todo.provider` providers, and the only permission it declares for them is
// com.miui.notes.permission.ACCESS_NOTE, protection signature|privileged (dumpsys package, round 21):
// no third-party app can be granted it, and Android has no shared notes store. A placed widget
// therefore shows iOS's empty folder ("没有备忘录") and opens the Notes app on tap; sample notes
// appear ONLY in the gallery preview.
// =======================================================================================

private const val NOTES_FOLDER_ZH = "快速访问某个文件夹中的备忘录。小米笔记不向其他 App 公开备忘录，放置后显示为空，轻点打开笔记。"
private const val NOTES_FOLDER_EN = "Quickly access the notes in a folder. Xiaomi Notes doesn’t share notes with other apps, " +
    "so the placed widget stays empty and opens Notes when tapped."

internal fun notesWidgetSpecs(): List<IosWidgetSpec> = listOf(
    IosWidgetSpec(-184, NativeWidgetKind.NOTES, "notes", NativeWidgetSize.SMALL, "文件夹", "Folder",
        NOTES_FOLDER_ZH, NOTES_FOLDER_EN, featured = 24, opens = ::notesAppIntent) { NotesWidget(this) },
    IosWidgetSpec(-185, NativeWidgetKind.NOTES, "notes", NativeWidgetSize.MEDIUM, "文件夹", "Folder",
        NOTES_FOLDER_ZH, NOTES_FOLDER_EN, opens = ::notesAppIntent) { NotesWidget(this) },
)

/** Notes apps in the order a Notes widget prefers them (HyperOS first). */
internal val NOTES_PACKAGES = listOf("com.miui.notes", "com.google.android.keep", "com.samsung.android.app.notes",
    "com.huawei.notepad", "com.coloros.note", "com.oneplus.note")

/** The installed Notes app's main screen; null when none is installed. */
internal fun notesAppIntent(context: Context): Intent? =
    NOTES_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

// Model + pure helpers (unit-tested) -----------------------------------------------------

internal data class NoteSummary(val title: String, val firstLine: String?, val modified: ZonedDateTime)

/** A note's title and first line as iOS shows them: its first two non-blank lines, trimmed. */
internal fun noteSummaryText(body: String): Pair<String, String?>? {
    val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).toList()
    return lines.firstOrNull()?.let { it to lines.getOrNull(1) }
}

/**
 * iOS Notes' date line: the time today ("上午9:41" / "9:41 AM" / "09:41"), 昨天 / Yesterday, the
 * weekday within the past week (星期一 / Monday), else the date ("2026/10/12" / "10/12/26").
 */
internal fun noteDateLabel(modified: ZonedDateTime, now: ZonedDateTime, use24h: Boolean, locale: Locale = Locale.getDefault()): String {
    val day = modified.withZoneSameInstant(now.zone).toLocalDate()
    val today = now.toLocalDate()
    val zh = locale.language == "zh"
    return when {
        day == today -> clockTimeParts(modified.withZoneSameInstant(now.zone), use24h, locale).let { (t, m) ->
            when { m == null -> t; zh -> "$m$t"; else -> "$t $m" }
        }
        day == today.minusDays(1) -> uxText("昨天", "Yesterday")
        day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        zh -> day.format(DateTimeFormatter.ofPattern("yyyy/M/d"))
        else -> day.format(DateTimeFormatter.ofPattern("M/d/yy"))
    }
}

/** Gallery-only sample folder (iOS previews widgets with sample notes too). Never shown on a placed widget. */
internal fun sampleNotes(now: ZonedDateTime): List<NoteSummary> = listOf(
    NoteSummary(uxText("周末计划", "Weekend plans"), uxText("去公园散步，再买点花", "Walk in the park, pick up flowers"),
        now.minusDays(1).withHour(20).withMinute(15)),
    NoteSummary(uxText("购物清单", "Groceries"), uxText("牛奶、鸡蛋、面包", "Milk, eggs, bread"), now.minusDays(3).withHour(9)),
)

// Rendering --------------------------------------------------------------------------------

/*
 * Points on the 158-pt card (1.webp): band 0–43.5 pt, yellow (252, 223, 0) → (251, 188, 0); folder
 * outline 15.9–34.5 × 12.8–27.6; "备忘录" 15 pt bold from 48.3, centred on 21.4; dots Ø1.7 every
 * 5.08 pt on 49.5; body (51, 51, 53) → (41, 40, 43); note title / first line / date 13 pt from
 * 16.9, centred on 70.1 / 88.0 / 134.7.
 */
private const val NOTES_BAND = 43.5f
private const val NOTES_TEXT_LEFT = 16.5f
private const val NOTES_TITLE_CENTER = 70.05f
private const val NOTES_LINE_CENTER = 87.95f
private const val NOTES_DATE_CENTER = 134.7f
private const val NOTES_TEXT = 13f
private val NotesBand = Brush.verticalGradient(listOf(Color(0xFFFCDF00), Color(0xFFFBBC00)))
private val NotesBodyDark = Brush.verticalGradient(listOf(Color(0xFF333335), Color(0xFF29282B)))

@Composable
private fun NotesWidget(scope: IosWidgetContext) {
    val colors = LocalWidgetColors.current
    val now = rememberMinuteTime()
    val context = androidx.compose.ui.platform.LocalContext.current
    val use24h = remember(context, now.hour) { android.text.format.DateFormat.is24HourFormat(context) }
    // No readable notes store exists (see the file header): samples in the gallery, an empty folder on Home.
    val notes = if (scope.preview) sampleNotes(now) else emptyList()
    val light = !colors.clear && colors.background.luminanceValue() > .5f
    val platter: (@Composable BoxScope.(WidgetScale) -> Unit)? = if (colors.clear) null else { _ ->
        Box(Modifier.fillMaxSize().background(if (light) Brush.verticalGradient(listOf(Color.White, Color.White)) else NotesBodyDark))
    }
    IosWidgetCard(scope.modifier, scope.family, background = platter) { s ->
        val width = scope.family.widthPt
        NotesHeader(s, width, colors)
        val columns = if (scope.family == NativeWidgetSize.SMALL) 1 else 2
        val column = width / columns
        if (notes.isEmpty()) NoteText(uxText("没有备忘录", "No Notes"), s, NOTES_TEXT_LEFT, NOTES_TITLE_CENTER, column - 2 * NOTES_TEXT_LEFT,
            colors.secondary)
        notes.take(columns).forEachIndexed { i, note ->
            val left = NOTES_TEXT_LEFT + i * column
            val w = column - 2 * NOTES_TEXT_LEFT
            NoteText(note.title, s, left, NOTES_TITLE_CENTER, w, colors.primary)
            note.firstLine?.let { NoteText(it, s, left, NOTES_LINE_CENTER, w, colors.secondary) }
            NoteText(noteDateLabel(note.modified, now, use24h), s, left, NOTES_DATE_CENTER, w, colors.secondary)
        }
    }
}

@Composable
private fun BoxScope.NoteText(text: String, s: WidgetScale, left: Float, center: Float, width: Float, color: Color) {
    WidgetText(text, s, NOTES_TEXT, Modifier.align(Alignment.TopStart).offset(s.pt(left), s.pt(center - 9f)).width(s.pt(width)),
        color = color, lineHeight = 18f, tracking = 0f)
}

/** The yellow band (glass-white in Clear), the folder outline, the folder's name and the dotted rule under it. */
@Composable
private fun BoxScope.NotesHeader(s: WidgetScale, width: Float, colors: WidgetColors) {
    Box(Modifier.fillMaxWidth().height(s.pt(NOTES_BAND)).then(
        if (colors.clear) Modifier.background(Color.White.copy(alpha = .16f)) else Modifier.background(NotesBand)))
    val dots = when {
        colors.clear -> Color.White.copy(alpha = .35f)
        colors.background.luminanceValue() > .5f -> Color(0xFFC7C7CC)
        else -> Color(0xFF4F4F51)
    }
    Canvas(Modifier.fillMaxSize()) {
        val px = s.px(1f)
        // folder (1.webp, 4× zoom): outline 16.0–35.0 × 13.3–27.8 pt, 1.4 pt stroke; the tab rises 1.5 pt
        // over the body's top edge up to 21 pt across; the front's edge is a line 19.2–32.0 pt on 18.2 pt.
        val l = 16.7f; val r = 34.3f; val tabTop = 14f; val top = 15.5f; val b = 27.1f; val c = 2.4f
        val folder = Path().apply {
            moveTo(l * px, (tabTop + c) * px); quadraticTo(l * px, tabTop * px, (l + c) * px, tabTop * px)
            lineTo(20.4f * px, tabTop * px); quadraticTo(21f * px, tabTop * px, 21.5f * px, (tabTop + .6f) * px)
            lineTo(22.2f * px, (top - .3f) * px); quadraticTo(22.5f * px, top * px, 23.1f * px, top * px)
            lineTo((r - c) * px, top * px); quadraticTo(r * px, top * px, r * px, (top + c) * px)
            lineTo(r * px, (b - c) * px); quadraticTo(r * px, b * px, (r - c) * px, b * px)
            lineTo((l + c) * px, b * px); quadraticTo(l * px, b * px, l * px, (b - c) * px); close()
            moveTo(19.2f * px, 18.2f * px); lineTo(32f * px, 18.2f * px)
        }
        drawPath(folder, Color.White, style = Stroke(1.4f * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val pitch = 5.08f
        var x = 2.9f
        while (x < width - 2f) {
            drawCircle(dots, .85f * px, Offset(x * px, 49.5f * px)); x += pitch
        }
    }
    WidgetText(uxText("备忘录", "Notes"), s, 15f, Modifier.align(Alignment.TopStart).offset(s.pt(47.9f), s.pt(21.35f - 9f)),
        weight = FontWeight.Bold, color = Color.White, lineHeight = 18f, tracking = 0f)
}

private fun Color.luminanceValue(): Float = .2126f * red + .7152f * green + .0722f * blue
