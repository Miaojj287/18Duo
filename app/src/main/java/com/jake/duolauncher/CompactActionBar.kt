package com.jake.duolauncher

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Static glass rows for an app inside an open folder (the folder itself uses [IosContextMenuHost]). */
@Composable
internal fun CompactAppActionBar(onInfo: () -> Unit, onRemove: () -> Unit, tag: String) {
    GlassMenu(width = 220.dp, tag = tag) {
        IosMenuRows(listOf(
            listOf(IosMenuAction(uxText("App 信息", "App Info"), IosSymbols.info, "$tag-info", onClick = onInfo)),
            listOf(IosMenuAction(uxText("移出文件夹", "Remove from Folder"), IosSymbols.folderMinus, "$tag-remove",
                destructive = true, onClick = onRemove)),
        ), reversed = false)
    }
}
