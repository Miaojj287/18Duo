package com.jake.duolauncher

/**
 * iOS widget context menu rows. Opening the menu changes no provider ID, layout coordinate or
 * resize value; only the chosen row acts.
 */
internal fun widgetContextSections(
    canResize: Boolean, canConfigure: Boolean,
    onResize: () -> Unit, onConfigure: () -> Unit, onReplace: () -> Unit,
    onRemove: () -> Unit, onEditHome: () -> Unit,
): List<List<IosMenuAction>> = listOf(
    buildList {
        if (canConfigure) add(IosMenuAction(uxText("编辑小组件", "Edit Widget"), IosSymbols.sliders, "widget-context-settings", onClick = onConfigure))
        add(IosMenuAction(uxText("尺寸", "Size"), IosSymbols.resize, "widget-context-resize", enabled = canResize, onClick = onResize))
        add(IosMenuAction(uxText("替换小组件", "Replace Widget"), IosSymbols.swap, "widget-context-replace", onClick = onReplace))
        add(IosMenuAction(uxText("编辑主屏幕", "Edit Home Screen"), IosSymbols.editHomeScreen, "widget-context-edit-home", onClick = onEditHome))
    },
    listOf(IosMenuAction(uxText("移除小组件", "Remove Widget"), IosSymbols.minusCircle, "widget-context-remove",
        destructive = true, onClick = onRemove)),
)
