package com.jake.duolauncher

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wizard step 4: choose the wallpaper — the iPhone Duo dunes (default) or a photo — preview it on both
 * panels with exactly the crops 18Duo uses, and apply it to 18Duo and (sync on by default) to the
 * system Home + Lock screen, so the lock screen and Home show the same picture.
 */
@Composable
internal fun WallpaperStepRow(status: SetupStepStatus, backgrounds: LauncherBackgroundController?) {
    val context = LocalContext.current
    // Unlike the system steps it depends on nothing else: it can also be opened ahead of its turn.
    var opened by rememberSaveable { mutableStateOf(false) }
    val expanded = status == SetupStepStatus.CURRENT || (opened && status != SetupStepStatus.DONE)
    Column(Modifier.fillMaxWidth().testTag("wizard-step-4")) {
        Row(Modifier.fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, IosDimIndication, enabled = status == SetupStepStatus.UPCOMING,
                role = Role.Button) { opened = !opened }
            .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            StepBadge(SetupStep.WALLPAPER.ordinal + 1, status, IosSymbols.photo, IosUi.teal)
            Text(uxText("设置壁纸", "Choose a Wallpaper"), Modifier.weight(1f).padding(start = 14.dp), fontSize = 17.sp,
                lineHeight = 22.sp, fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
                color = if (status == SetupStepStatus.UPCOMING && !expanded) iosSecondaryText() else iosLabel())
            if (status == SetupStepStatus.UPCOMING) IosDisclosureChevron(expanded)
            if (status == SetupStepStatus.DONE) Text(stringResource(R.string.wizard_step_done), fontSize = 15.sp,
                color = iosSecondaryText(), modifier = Modifier.padding(start = 8.dp))
        }
        AnimatedVisibility(expanded && backgrounds != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            if (backgrounds != null) WallpaperStepBody(backgrounds, context)
        }
    }
}

@Composable
private fun WallpaperStepBody(backgrounds: LauncherBackgroundController, context: android.content.Context) {
    val revision = LauncherBackgroundCache.revision.intValue
    val committed = remember(revision) { cachedLauncherBackground(context) }
    var choice by rememberSaveable { mutableStateOf(if (backgrounds.photoSelected || backgrounds.previewPending) "photo" else "dunes") }
    var sync by remember { mutableStateOf(WallpaperSync.enabled(context)) }
    var applying by rememberSaveable { mutableStateOf(false) }
    val photo: Bitmap? = backgrounds.previewBitmap ?: committed
    // The picker was cancelled with no photo to show: the choice falls back to the dunes.
    val photoShown = choice == "photo" && (photo != null || backgrounds.pickerPending || backgrounds.loading)
    val dunes = remember(context) { bundledDefaultWallpaper(context.applicationContext) }
    val image = if (photoShown) photo else dunes
    val syncStatus = WallpaperSync.status.value
    LaunchedEffect(applying, syncStatus) {
        if (!applying || syncStatus == WallpaperSyncStatus.WORKING) return@LaunchedEffect
        applying = false
        when (syncStatus) {
            WallpaperSyncStatus.DONE -> IosBanner.show(uxText("壁纸已同步到系统桌面和锁屏", "Wallpaper set on the system Home and Lock Screen"))
            WallpaperSyncStatus.UNSUPPORTED, WallpaperSyncStatus.FAILED -> IosBanner.show(uxText(
                "18Duo 已使用这张壁纸；系统不允许修改桌面/锁屏壁纸，可稍后在系统设置中更换。",
                "18Duo uses this wallpaper; the system refused to change its Home/Lock wallpaper."), long = true)
            else -> Unit
        }
        SetupExperience.markWallpaperStepDone(context)
    }
    Column(Modifier.padding(start = 16.dp + 30.dp + 14.dp, end = 16.dp, bottom = 14.dp)) {
        Text(uxText("18Duo 桌面、系统桌面和锁屏会使用同一张壁纸，外屏和内屏各按 iPhone Duo 的方式裁切，解锁时从锁屏无缝衔接到桌面。",
            "18Duo, the system Home Screen and the Lock Screen share one wallpaper, cropped for the cover and the inner " +
                "screen as on iPhone Duo, so unlocking flows straight from the Lock Screen into Home."),
            fontSize = 15.sp, lineHeight = 20.sp, color = iosSecondaryText())
        WallpaperPanelsPreview(image, bundled = !photoShown || photo == null, Modifier.padding(top = 12.dp))
        IosChipRow(listOf(
            IosChipItem("dunes", uxText("沙丘", "Dunes"), "wizard-wallpaper-dunes") { BundledDunesThumb() },
            IosChipItem("photo", uxText("照片", "Photos"), "wizard-wallpaper-photo") { IosChipGlyph(IosSymbols.photo, IosUi.teal) },
        ), selectedKey = if (photoShown) "photo" else "dunes", onSelect = { item ->
            choice = item.key
            if (item.key == "photo") backgrounds.choosePhoto() else if (backgrounds.previewPending) backgrounds.cancelPreview()
        }, modifier = Modifier.padding(top = 6.dp), tileSize = 56.dp)
        IosToggleRow(uxText("同时设为系统桌面和锁屏壁纸", "Also Use for System Home & Lock Screen"), sync,
            { sync = it; WallpaperSync.setEnabled(context, it, syncNow = false) },
            Modifier.padding(top = 4.dp).clip(ContinuousRoundedShape(12.dp)).background(iosGroupedBackground()),
            tag = "wizard-wallpaper-sync")
        IosButton(uxText("使用这张壁纸", "Use This Wallpaper"), {
            val previewReady = backgrounds.previewPending && backgrounds.previewBitmap != null
            when {
                photoShown && previewReady -> backgrounds.applyPreview()
                !photoShown && backgrounds.photoSelected -> backgrounds.reset()
                sync -> WallpaperSync.syncNow(context, force = true)
            }
            // Committing above already asked for the system write when sync is on (WallpaperSync.status).
            if (sync && WallpaperSync.status.value == WallpaperSyncStatus.WORKING) applying = true
            else SetupExperience.markWallpaperStepDone(context)
        }, Modifier.fillMaxWidth().padding(top = 12.dp), tag = "wizard-action-4",
            enabled = !backgrounds.loading && !backgrounds.pickerPending && !applying)
        if (applying || backgrounds.loading) Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            IosActivityIndicator(color = iosSecondaryText())
        }
        backgrounds.errorMessage?.let { Text(it, fontSize = 13.sp, color = iosSecondaryText(), modifier = Modifier.padding(top = 6.dp)) }
    }
}

/**
 * The chosen picture on a cover and an inner panel side by side, at their real aspect ratios and with
 * the same crop function Home draws with ([wallpaperSourceCrop]: dunes right-anchored, photos centred).
 */
@Composable
internal fun WallpaperPanelsPreview(image: Bitmap?, bundled: Boolean, modifier: Modifier = Modifier) {
    val bitmap = remember(image) { image?.takeUnless { it.isRecycled }?.asImageBitmap() }
    Row(modifier.fillMaxWidth().testTag("wizard-wallpaper-preview"), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom) {
        PanelThumb(bitmap, bundled, XIAOMI_COVER_PANEL, uxText("外屏", "Cover"), Modifier.weight(XIAOMI_COVER_PANEL.aspect))
        PanelThumb(bitmap, bundled, XIAOMI_INNER_PANEL, uxText("内屏", "Inner"), Modifier.weight(XIAOMI_INNER_PANEL.aspect))
    }
}

@Composable
private fun PanelThumb(image: ImageBitmap?, bundled: Boolean, panel: PanelPixels, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = ContinuousRoundedShape(12.dp)
        Box(Modifier.fillMaxWidth().aspectRatio(panel.aspect).clip(shape).background(Color.Black)
            .border(3.dp, Color.Black, shape).padding(3.dp).clip(ContinuousRoundedShape(9.dp))) {
            Spacer(Modifier.fillMaxSize().drawBehind {
                if (image == null) { drawDunes(); return@drawBehind }
                val crop = wallpaperSourceCrop(image.width, image.height, panel.width, panel.height, rightAnchored = bundled)
                drawImage(image, IntOffset(crop.x, crop.y), IntSize(crop.width, crop.height),
                    dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
            })
        }
        Text(label, fontSize = 12.sp, color = iosSecondaryText(), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}
