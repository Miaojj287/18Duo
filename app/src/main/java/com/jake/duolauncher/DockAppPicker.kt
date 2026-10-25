package com.jake.duolauncher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Corner shape of row [index] in an inset-grouped list of [count] rows (only the ends are rounded). */
internal fun groupedRowCorners(index: Int, count: Int): Pair<Boolean, Boolean> =
    (index == 0) to (index == count - 1)

/**
 * Choose an app for a Dock position (was a Material sheet with OutlinedTextField and an "+" icon
 * list). Now an iOS sheet: glass close button, search bar, an inset-grouped list whose rows carry
 * the app icon and a blue ⊕, and a red "leave empty" row when the position holds an app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DockAppPicker(apps: List<AppEntry>, dockSlot: Int?, onSelect: (AppEntry) -> Unit, onClear: () -> Unit,
    onLongClick: (AppEntry) -> Unit, canSelect: (AppEntry) -> Boolean = { true }, blockedHint: String? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    val trimmed = query.trim()
    val filtered = remember(apps, trimmed) {
        if (trimmed.isEmpty()) alphabeticalSections(apps) { SearchKeys.of(it.label) }.flatMap { it.second }
        else rankMatches(trimmed, apps, { SearchKeys.of(it.label) }).map { it.item }
    }
    val close = LocalPanelClose.current
    val title = if (dockSlot == null) launcherFormat(R.string.dock_picker_title_apps)
        else launcherFormat(R.string.dock_picker_title, dockSlot + 1)
    IosSheetScaffold(title, Modifier.fillMaxHeight(), onCancel = close, cancelLabel = uxText("关闭", "Close"),
        cancelTag = "dock-picker-close", onDone = null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding()) {
            IosSearchField(query, { query = it }, launcherText("Search apps"), tag = "search-field")
            if (dockSlot != null) IosInsetGroup {
                IosRow(launcherText("Leave this position empty"), titleColor = IosUi.red, chevron = false, onClick = onClear,
                    tag = "dock-leave-empty")
            }
            if (blockedHint != null) IosFooter(blockedHint, Modifier.testTag("dock-full-guidance"))
            Spacer(Modifier.height(18.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (filtered.isEmpty()) item {
                    Text(launcherText("No apps found"), Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        color = iosSecondaryText(), fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                itemsIndexed(filtered, key = { _, app -> app.id }) { index, app ->
                    val enabled = canSelect(app)
                    val (top, bottom) = groupedRowCorners(index, filtered.size)
                    val r = IosUi.groupRadius
                    val shape = ContinuousRoundedShape(androidx.compose.foundation.shape.CornerSize(if (top) r else 0.dp),
                        androidx.compose.foundation.shape.CornerSize(if (top) r else 0.dp),
                        androidx.compose.foundation.shape.CornerSize(if (bottom) r else 0.dp),
                        androidx.compose.foundation.shape.CornerSize(if (bottom) r else 0.dp))
                    Column(Modifier.fillMaxWidth().clip(shape).background(iosGroupFill())) {
                        Row(Modifier.fillMaxWidth().testTag("picker-app-${app.id}")
                            .combinedClickable(remember { MutableInteractionSource() }, IosRowHighlight(iosRowHighlightColor()),
                                enabled = enabled, role = Role.Button, onClick = { onSelect(app) }, onLongClick = { onLongClick(app) })
                            .alpha(if (enabled) 1f else .4f)
                            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Image(app.icon.asImageBitmap(), null, Modifier.size(36.dp).clip(homeIconShape(36f)))
                            Text(app.label, Modifier.padding(start = 14.dp).weight(1f), fontSize = 17.sp, color = iosLabel(),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (dockSlot != null && enabled) Box(Modifier.size(24.dp).background(IosUi.green, CircleShape)
                                .semantics { contentDescription = launcherFormat(R.string.dock_choose_app, app.label) },
                                contentAlignment = Alignment.Center) {
                                Icon(IosSymbols.plus, null, Modifier.size(15.dp), tint = Color.White)
                            }
                        }
                        if (!bottom) IosSeparator(inset = 16.dp + 36.dp + 14.dp)
                    }
                }
            }
        }
    }
}
