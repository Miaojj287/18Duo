package com.jake.duolauncher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Line glyphs drawn after the SF Symbols used by iOS Home Screen menus (regular weight on a
 * 24-unit canvas). Material icons read as Android in these menus; these are original
 * drawings that only follow the SF shapes' conventions (round caps, 1.7-unit strokes).
 */
internal object IosSymbols {
    private const val STROKE = 1.7f

    private fun symbol(name: String, fillBlock: (PathBuilder.() -> Unit)? = null, strokeBlock: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = strokeBlock)
            if (fillBlock != null) path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero, pathBuilder = fillBlock)
        }.build()

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
        moveTo(l + radius, t); lineTo(r - radius, t); quadTo(r, t, r, t + radius)
        lineTo(r, b - radius); quadTo(r, b, r - radius, b)
        lineTo(l + radius, b); quadTo(l, b, l, b - radius)
        lineTo(l, t + radius); quadTo(l, t, l + radius, t); close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.folder() {
        moveTo(3f, 8f); quadTo(3f, 5.5f, 5.5f, 5.5f); lineTo(9.2f, 5.5f); lineTo(11.2f, 7.6f)
        lineTo(18.5f, 7.6f); quadTo(21f, 7.6f, 21f, 10.1f); lineTo(21f, 17f); quadTo(21f, 19.5f, 18.5f, 19.5f)
        lineTo(5.5f, 19.5f); quadTo(3f, 19.5f, 3f, 17f); close()
    }

    /** square.and.arrow.up — 分享 App. */
    val share: ImageVector by lazy {
        symbol("ios.share") {
            moveTo(12f, 3.2f); lineTo(12f, 14.2f)
            moveTo(8.4f, 6.6f); lineTo(12f, 3f); lineTo(15.6f, 6.6f)
            moveTo(8.8f, 9.6f); lineTo(7f, 9.6f); quadTo(5f, 9.6f, 5f, 11.6f); lineTo(5f, 18.8f)
            quadTo(5f, 20.8f, 7f, 20.8f); lineTo(17f, 20.8f); quadTo(19f, 20.8f, 19f, 18.8f); lineTo(19f, 11.6f)
            quadTo(19f, 9.6f, 17f, 9.6f); lineTo(15.2f, 9.6f)
        }
    }

    /** apps.iphone — 编辑主屏幕. */
    val editHomeScreen: ImageVector by lazy {
        symbol("ios.apps.iphone", fillBlock = {
            roundRect(8.6f, 6.4f, 11.2f, 9f, .8f); roundRect(12.8f, 6.4f, 15.4f, 9f, .8f)
            roundRect(8.6f, 10.6f, 11.2f, 13.2f, .8f); roundRect(12.8f, 10.6f, 15.4f, 13.2f, .8f)
        }) { roundRect(6f, 2.6f, 18f, 21.4f, 3f) }
    }

    /** info.circle — App 信息. */
    val info: ImageVector by lazy {
        symbol("ios.info.circle", fillBlock = { circle(12f, 7.9f, 1.15f) }) {
            circle(12f, 12f, 9f)
            moveTo(10.4f, 10.9f); lineTo(12.1f, 10.9f); lineTo(12.1f, 16.6f)
            moveTo(10.3f, 16.6f); lineTo(13.9f, 16.6f)
        }
    }

    /** minus.circle — 移除. */
    val minusCircle: ImageVector by lazy {
        symbol("ios.minus.circle") { circle(12f, 12f, 9f); moveTo(8f, 12f); lineTo(16f, 12f) }
    }

    /** plus.app — 添加到主屏幕. */
    val addToHome: ImageVector by lazy {
        symbol("ios.plus.app") {
            roundRect(3.5f, 3.5f, 20.5f, 20.5f, 5f)
            moveTo(12f, 8f); lineTo(12f, 16f); moveTo(8f, 12f); lineTo(16f, 12f)
        }
    }

    /** folder.badge.plus — 创建文件夹. */
    val folderPlus: ImageVector by lazy {
        symbol("ios.folder.badge.plus") {
            folder(); moveTo(12f, 10.8f); lineTo(12f, 16.4f); moveTo(9.2f, 13.6f); lineTo(14.8f, 13.6f)
        }
    }

    /** folder.badge.minus — 解散文件夹 / 移出文件夹. */
    val folderMinus: ImageVector by lazy {
        symbol("ios.folder.badge.minus") { folder(); moveTo(9.2f, 13.6f); lineTo(14.8f, 13.6f) }
    }

    /** pencil — 重新命名. */
    val pencil: ImageVector by lazy {
        symbol("ios.pencil") {
            moveTo(15.6f, 4.6f); lineTo(19.4f, 8.4f); lineTo(8.6f, 19.2f); lineTo(4.2f, 19.8f)
            lineTo(4.8f, 15.4f); close(); moveTo(13.6f, 6.6f); lineTo(17.4f, 10.4f)
        }
    }

    /** square.grid.2x2 — 添加小组件. */
    val widgets: ImageVector by lazy {
        symbol("ios.square.grid.2x2") {
            roundRect(3.5f, 3.5f, 10.5f, 10.5f, 2.2f); roundRect(13.5f, 3.5f, 20.5f, 10.5f, 2.2f)
            roundRect(3.5f, 13.5f, 10.5f, 20.5f, 2.2f); roundRect(13.5f, 13.5f, 20.5f, 20.5f, 2.2f)
        }
    }

    /** paintbrush — 自定义. */
    val paintbrush: ImageVector by lazy {
        symbol("ios.paintbrush") {
            moveTo(20f, 4f); lineTo(12.2f, 12.6f)
            moveTo(11.6f, 12.4f); curveTo(9.4f, 11.9f, 7.4f, 13.5f, 7.4f, 15.6f)
            curveTo(7.4f, 17.3f, 6.3f, 18.5f, 4.6f, 19.3f); curveTo(7.6f, 20.7f, 12.6f, 20.1f, 13f, 15.2f)
            curveTo(13.1f, 14f, 12.6f, 13f, 11.6f, 12.4f); close()
        }
    }

    /** photo — 编辑墙纸. */
    val photo: ImageVector by lazy {
        symbol("ios.photo", fillBlock = { circle(15.8f, 9.4f, 1.5f) }) {
            roundRect(3f, 5f, 21f, 19f, 3f)
            moveTo(3.6f, 16.6f); lineTo(8.6f, 11.8f); lineTo(12.6f, 15.6f); lineTo(15f, 13.4f); lineTo(20.4f, 17.6f)
        }
    }

    /** arrow.up.left.and.arrow.down.right — 小组件尺寸. */
    val resize: ImageVector by lazy {
        symbol("ios.arrow.up.left.and.arrow.down.right") {
            moveTo(5f, 5f); lineTo(10.5f, 10.5f); moveTo(13.5f, 13.5f); lineTo(19f, 19f)
            moveTo(5f, 10.2f); lineTo(5f, 5f); lineTo(10.2f, 5f)
            moveTo(13.8f, 19f); lineTo(19f, 19f); lineTo(19f, 13.8f)
        }
    }

    /** slider.horizontal.3 — 编辑小组件. */
    val sliders: ImageVector by lazy {
        symbol("ios.slider.horizontal.3") {
            moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
            moveTo(9f, 5f); lineTo(9f, 9f); moveTo(15.5f, 10f); lineTo(15.5f, 14f); moveTo(8f, 15f); lineTo(8f, 19f)
        }
    }

    /** arrow.left.arrow.right — 替换小组件. */
    val swap: ImageVector by lazy {
        symbol("ios.arrow.left.arrow.right") {
            moveTo(4.5f, 8.5f); lineTo(18.5f, 8.5f); moveTo(15.4f, 5.4f); lineTo(18.5f, 8.5f); lineTo(15.4f, 11.6f)
            moveTo(19.5f, 15.5f); lineTo(5.5f, 15.5f); moveTo(8.6f, 12.4f); lineTo(5.5f, 15.5f); lineTo(8.6f, 18.6f)
        }
    }

    // ---- Navigation, controls and Settings glyphs (semibold weight, 2.1-unit strokes). ----
    // Used on 29dp Settings tiles, glass toolbar circles and search fields in place of Material icons.

    private const val BOLD = 2.1f

    private fun bold(name: String, fillBlock: (PathBuilder.() -> Unit)? = null, strokeBlock: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = BOLD,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = strokeBlock)
            if (fillBlock != null) path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero, pathBuilder = fillBlock)
        }.build()

    private fun filled(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero, pathBuilder = block)
        }.build()

    /** magnifyingglass. */
    val magnifyingGlass: ImageVector by lazy {
        bold("ios.magnifyingglass") { circle(10.4f, 10.4f, 6.4f); moveTo(15.1f, 15.1f); lineTo(20.2f, 20.2f) }
    }

    /** xmark — close / cancel (iOS 26 toolbar). */
    val xmark: ImageVector by lazy {
        bold("ios.xmark") { moveTo(6.6f, 6.6f); lineTo(17.4f, 17.4f); moveTo(17.4f, 6.6f); lineTo(6.6f, 17.4f) }
    }

    /** checkmark — Done / selected row. */
    val checkmark: ImageVector by lazy {
        bold("ios.checkmark") { moveTo(4.8f, 12.8f); lineTo(9.6f, 17.6f); lineTo(19.4f, 6.6f) }
    }

    /** chevron.left — back. */
    val chevronLeft: ImageVector by lazy {
        bold("ios.chevron.left") { moveTo(15.2f, 4.6f); lineTo(7.8f, 12f); lineTo(15.2f, 19.4f) }
    }

    /** chevron.right — disclosure indicator. */
    val chevronRight: ImageVector by lazy {
        bold("ios.chevron.right") { moveTo(8.8f, 4.6f); lineTo(16.2f, 12f); lineTo(8.8f, 19.4f) }
    }

    /** chevron.down — disclosure group. */
    val chevronDown: ImageVector by lazy {
        bold("ios.chevron.down") { moveTo(4.8f, 8.6f); lineTo(12f, 15.8f); lineTo(19.2f, 8.6f) }
    }

    /** plus. */
    val plus: ImageVector by lazy {
        bold("ios.plus") { moveTo(12f, 4.6f); lineTo(12f, 19.4f); moveTo(4.6f, 12f); lineTo(19.4f, 12f) }
    }

    /** gearshape — Settings. Eight teeth around a ring, built from polar points. */
    val gear: ImageVector by lazy {
        bold("ios.gearshape") {
            val teeth = 8
            for (i in 0 until teeth) {
                val a = Math.toRadians(i * 360.0 / teeth)
                fun p(r: Double, da: Double) = Pair(12f + (r * kotlin.math.cos(a + Math.toRadians(da))).toFloat(),
                    12f + (r * kotlin.math.sin(a + Math.toRadians(da))).toFloat())
                val pts = listOf(p(7.0, -17.0), p(9.4, -10.0), p(9.4, 10.0), p(7.0, 17.0))
                if (i == 0) moveTo(pts[0].first, pts[0].second) else lineTo(pts[0].first, pts[0].second)
                pts.drop(1).forEach { lineTo(it.first, it.second) }
            }
            close(); circle(12f, 12f, 3.1f)
        }
    }

    /** house. */
    val house: ImageVector by lazy {
        bold("ios.house") {
            moveTo(3.6f, 11.4f); lineTo(12f, 4.2f); lineTo(20.4f, 11.4f)
            moveTo(6.2f, 9.6f); lineTo(6.2f, 18.6f); quadTo(6.2f, 20f, 7.6f, 20f); lineTo(16.4f, 20f); quadTo(17.8f, 20f, 17.8f, 18.6f)
            lineTo(17.8f, 9.6f); moveTo(10.2f, 20f); lineTo(10.2f, 15.2f); lineTo(13.8f, 15.2f); lineTo(13.8f, 20f)
        }
    }

    /** sparkles — motion / animation. */
    val sparkles: ImageVector by lazy {
        filled("ios.sparkles") {
            moveTo(10f, 5.2f); quadTo(10.7f, 12.1f, 17.4f, 13f); quadTo(10.7f, 13.9f, 10f, 20.8f)
            quadTo(9.3f, 13.9f, 2.6f, 13f); quadTo(9.3f, 12.1f, 10f, 5.2f); close()
            moveTo(18.2f, 2.8f); quadTo(18.5f, 5.4f, 21f, 5.8f); quadTo(18.5f, 6.2f, 18.2f, 8.8f)
            quadTo(17.9f, 6.2f, 15.4f, 5.8f); quadTo(17.9f, 5.4f, 18.2f, 2.8f); close()
        }
    }

    /** wifi — three arcs and a dot, centred low like SF. */
    val wifi: ImageVector by lazy {
        bold("ios.wifi", fillBlock = { circle(12f, 18.6f, 1.6f) }) {
            listOf(4.2f, 8.2f, 12.2f).forEach { r ->
                val d = r * .7071f
                moveTo(12f - d, 18.6f - d); arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f + d, y1 = 18.6f - d)
            }
        }
    }

    /** moon.fill — night / StandBy. */
    val moon: ImageVector by lazy {
        filled("ios.moon.fill") {
            moveTo(13.4f, 3.4f); arcTo(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 20.6f, y1 = 14.8f)
            arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 13.4f, y1 = 3.4f); close()
        }
    }

    /** lock. */
    val lock: ImageVector by lazy {
        bold("ios.lock") {
            roundRect(5.2f, 10.6f, 18.8f, 20.6f, 2.4f)
            moveTo(8.2f, 10.6f); lineTo(8.2f, 7.8f); arcTo(3.8f, 3.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15.8f, y1 = 7.8f)
            lineTo(15.8f, 10.6f)
        }
    }

    /** bell. */
    val bell: ImageVector by lazy {
        bold("ios.bell") {
            moveTo(12f, 3.8f); curveTo(8.7f, 3.8f, 6.7f, 6.3f, 6.7f, 9.5f); lineTo(6.7f, 13.5f); lineTo(4.8f, 16.6f)
            lineTo(19.2f, 16.6f); lineTo(17.3f, 13.5f); lineTo(17.3f, 9.5f); curveTo(17.3f, 6.3f, 15.3f, 3.8f, 12f, 3.8f); close()
            moveTo(9.8f, 19.2f); quadTo(12f, 21.2f, 14.2f, 19.2f)
        }
    }

    /** arrow.uturn.backward — undo. */
    val undo: ImageVector by lazy {
        bold("ios.arrow.uturn.backward") {
            moveTo(8.8f, 5.2f); lineTo(4.4f, 9.6f); lineTo(8.8f, 14f)
            moveTo(4.6f, 9.6f); lineTo(14.4f, 9.6f); curveTo(17.6f, 9.6f, 19.8f, 11.9f, 19.8f, 14.8f)
            curveTo(19.8f, 17.7f, 17.6f, 20f, 14.4f, 20f); lineTo(11f, 20f)
        }
    }

    /** arrow.clockwise — restore / reopen. */
    val arrowClockwise: ImageVector by lazy {
        bold("ios.arrow.clockwise") {
            moveTo(19.4f, 12.6f); arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 16.8f, y1 = 6.4f)
            moveTo(17.6f, 2.8f); lineTo(17.6f, 7.2f); lineTo(13.2f, 7.2f)
        }
    }

    /** questionmark.circle — help. */
    val questionCircle: ImageVector by lazy {
        bold("ios.questionmark.circle", fillBlock = { circle(12f, 16.9f, 1.15f) }) {
            circle(12f, 12f, 9f)
            moveTo(9.5f, 9.5f); curveTo(9.5f, 8f, 10.6f, 7f, 12f, 7f); curveTo(13.5f, 7f, 14.6f, 8f, 14.6f, 9.4f)
            curveTo(14.6f, 11.3f, 12f, 11.6f, 12f, 13.6f)
        }
    }

    /** textformat — show app names. */
    val textFormat: ImageVector by lazy {
        bold("ios.textformat") {
            moveTo(2.8f, 18.4f); lineTo(7.6f, 5.6f); lineTo(12.4f, 18.4f); moveTo(4.6f, 14f); lineTo(10.6f, 14f)
            circle(17.2f, 15.4f, 2.8f); moveTo(20f, 12.2f); lineTo(20f, 18.4f)
        }
    }

    /** square.and.arrow.down — save a backup. */
    val save: ImageVector by lazy {
        bold("ios.square.and.arrow.down") {
            moveTo(8.8f, 9.6f); lineTo(7f, 9.6f); quadTo(5f, 9.6f, 5f, 11.6f); lineTo(5f, 18.8f)
            quadTo(5f, 20.8f, 7f, 20.8f); lineTo(17f, 20.8f); quadTo(19f, 20.8f, 19f, 18.8f); lineTo(19f, 11.6f)
            quadTo(19f, 9.6f, 17f, 9.6f); lineTo(15.2f, 9.6f)
            moveTo(12f, 3.2f); lineTo(12f, 14.4f); moveTo(8.4f, 10.8f); lineTo(12f, 14.4f); lineTo(15.6f, 10.8f)
        }
    }

    /** clock. */
    val clock: ImageVector by lazy {
        bold("ios.clock") { circle(12f, 12f, 9f); moveTo(12f, 6.8f); lineTo(12f, 12f); lineTo(15.4f, 14.2f) }
    }

    /** clock.arrow.circlepath — history / recents. */
    val history: ImageVector by lazy {
        bold("ios.clock.arrow.circlepath") {
            moveTo(4.4f, 13.4f); arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 6.4f, y1 = 6.6f)
            moveTo(6.6f, 3.4f); lineTo(6.6f, 6.8f); lineTo(3.2f, 6.8f)
            moveTo(12.2f, 8f); lineTo(12.2f, 12.4f); lineTo(15f, 14.2f)
        }
    }

    /** arrow.up — swipe up from the bottom. */
    val swipeUp: ImageVector by lazy {
        bold("ios.arrow.up.to.line") {
            moveTo(12f, 18.4f); lineTo(12f, 4.8f); moveTo(6.6f, 10.2f); lineTo(12f, 4.8f); lineTo(17.4f, 10.2f)
            moveTo(6f, 20.4f); lineTo(18f, 20.4f)
        }
    }

    /** arrow.down — swipe down from the top. */
    val swipeDown: ImageVector by lazy {
        bold("ios.arrow.down.to.line") {
            moveTo(12f, 5.6f); lineTo(12f, 19.2f); moveTo(6.6f, 13.8f); lineTo(12f, 19.2f); lineTo(17.4f, 13.8f)
            moveTo(6f, 3.6f); lineTo(18f, 3.6f)
        }
    }

    /** accessibility — the navigation service. */
    val accessibility: ImageVector by lazy {
        bold("ios.accessibility", fillBlock = { circle(12f, 7.4f, 1.5f) }) {
            circle(12f, 12f, 9.2f)
            moveTo(7.6f, 10.1f); lineTo(16.4f, 10.1f); moveTo(12f, 10.1f); lineTo(12f, 13.8f)
            moveTo(12f, 13.8f); lineTo(9.6f, 18f); moveTo(12f, 13.8f); lineTo(14.4f, 18f)
        }
    }

    /** iphone.landscape with a clock dot — StandBy. */
    val standby: ImageVector by lazy {
        bold("ios.standby", fillBlock = { circle(12f, 12f, 1.2f) }) {
            roundRect(2.6f, 6.4f, 21.4f, 17.6f, 3f)
            moveTo(12f, 12f); lineTo(12f, 9.4f)
        }
    }

    /** checklist — setup wizard. */
    val checklist: ImageVector by lazy {
        bold("ios.checklist") {
            moveTo(3.8f, 6.8f); lineTo(5.4f, 8.4f); lineTo(8.2f, 5.2f); moveTo(11.4f, 6.8f); lineTo(20.2f, 6.8f)
            moveTo(3.8f, 16.4f); lineTo(5.4f, 18f); lineTo(8.2f, 14.8f); moveTo(11.4f, 16.4f); lineTo(20.2f, 16.4f)
        }
    }

    /** person.crop.circle — app history access. */
    val person: ImageVector by lazy {
        bold("ios.person") {
            circle(12f, 8.2f, 3.6f)
            moveTo(4.8f, 20f); curveTo(4.8f, 15.8f, 8f, 13.6f, 12f, 13.6f); curveTo(16f, 13.6f, 19.2f, 15.8f, 19.2f, 20f)
        }
    }

    /** cable.connector — USB helper. */
    val cable: ImageVector by lazy {
        bold("ios.cable.connector") {
            roundRect(7.6f, 3.4f, 16.4f, 11.6f, 1.8f); moveTo(10.4f, 6.2f); lineTo(10.4f, 8f); moveTo(13.6f, 6.2f); lineTo(13.6f, 8f)
            moveTo(12f, 11.6f); lineTo(12f, 20.8f)
        }
    }

    /** safari — search the web. */
    val compass: ImageVector by lazy {
        bold("ios.safari", fillBlock = {
            moveTo(15.8f, 8.2f); lineTo(13.3f, 13.3f); lineTo(8.2f, 15.8f); lineTo(10.7f, 10.7f); close()
        }) { circle(12f, 12f, 9f) }
    }

    /** trash. */
    val trash: ImageVector by lazy {
        bold("ios.trash") {
            moveTo(4.4f, 6.6f); lineTo(19.6f, 6.6f)
            moveTo(9.4f, 6.6f); lineTo(9.4f, 4.9f); quadTo(9.4f, 4f, 10.3f, 4f); lineTo(13.7f, 4f); quadTo(14.6f, 4f, 14.6f, 4.9f); lineTo(14.6f, 6.6f)
            moveTo(6.4f, 6.6f); lineTo(7.3f, 19f); quadTo(7.4f, 20.6f, 9f, 20.6f); lineTo(15f, 20.6f); quadTo(16.6f, 20.6f, 16.7f, 19f); lineTo(17.6f, 6.6f)
            moveTo(10.2f, 10.2f); lineTo(10.2f, 16.8f); moveTo(13.8f, 10.2f); lineTo(13.8f, 16.8f)
        }
    }

    /** bolt.fill — charging. */
    val bolt: ImageVector by lazy {
        filled("ios.bolt.fill") {
            moveTo(13.8f, 2.4f); lineTo(5.2f, 13.6f); lineTo(11f, 13.6f); lineTo(10.2f, 21.6f); lineTo(18.8f, 10.2f)
            lineTo(13f, 10.2f); close()
        }
    }

    /** iphone. */
    val iphone: ImageVector by lazy {
        bold("ios.iphone") { roundRect(6.4f, 2.6f, 17.6f, 21.4f, 2.8f); moveTo(10.6f, 5.2f); lineTo(13.4f, 5.2f) }
    }

    /** cloud.fill — weather. */
    val cloud: ImageVector by lazy {
        filled("ios.cloud.fill") {
            moveTo(7f, 18.6f); lineTo(17.2f, 18.6f); curveTo(19.6f, 18.6f, 21.2f, 16.9f, 21.2f, 14.8f)
            curveTo(21.2f, 12.7f, 19.6f, 11.1f, 17.5f, 11.1f); curveTo(17f, 8.1f, 14.7f, 6f, 11.8f, 6f)
            curveTo(8.9f, 6f, 6.6f, 8.2f, 6.4f, 11.1f); curveTo(4.4f, 11.4f, 2.8f, 13f, 2.8f, 14.9f)
            curveTo(2.8f, 17f, 4.5f, 18.6f, 7f, 18.6f); close()
        }
    }

    /** calendar. */
    val calendar: ImageVector by lazy {
        bold("ios.calendar") {
            roundRect(3.4f, 5f, 20.6f, 20.6f, 3f); moveTo(3.4f, 9.6f); lineTo(20.6f, 9.6f)
            moveTo(8f, 3f); lineTo(8f, 6.6f); moveTo(16f, 3f); lineTo(16f, 6.6f)
        }
    }

    /** battery.100. */
    val battery: ImageVector by lazy {
        bold("ios.battery.100", fillBlock = { roundRect(4.8f, 9.8f, 16.8f, 14.2f, 1f) }) {
            roundRect(2.6f, 7.6f, 19f, 16.4f, 2.6f); moveTo(21.2f, 10.6f); lineTo(21.2f, 13.4f)
        }
    }

    /** airplane — status rail in Airplane Mode. */
    val airplane: ImageVector by lazy {
        filled("ios.airplane") {
            moveTo(21f, 12f); curveTo(21f, 11.2f, 20.2f, 10.6f, 19.4f, 10.6f); lineTo(14.6f, 10.6f); lineTo(9.8f, 3.6f)
            lineTo(7.8f, 3.6f); lineTo(10.4f, 10.6f); lineTo(6.2f, 10.6f); lineTo(4.6f, 8.4f); lineTo(3f, 8.4f); lineTo(4f, 12f)
            lineTo(3f, 15.6f); lineTo(4.6f, 15.6f); lineTo(6.2f, 13.4f); lineTo(10.4f, 13.4f); lineTo(7.8f, 20.4f); lineTo(9.8f, 20.4f)
            lineTo(14.6f, 13.4f); lineTo(19.4f, 13.4f); curveTo(20.2f, 13.4f, 21f, 12.8f, 21f, 12f); close()
        }
    }

    /** cellularbars — four rounded bars. */
    val cellularBars: ImageVector by lazy {
        filled("ios.cellularbars") {
            listOf(5f, 8.6f, 12.2f, 15.8f).forEachIndexed { i, h ->
                val l = 2.6f + i * 5f
                roundRect(l, 20f - h, l + 3.6f, 20f, 1.1f)
            }
        }
    }

    /** location.fill — location in use. */
    val location: ImageVector by lazy {
        filled("ios.location.fill") { moveTo(20.4f, 3.6f); lineTo(3.6f, 10.9f); lineTo(11.3f, 12.7f); lineTo(13.1f, 20.4f); close() }
    }

    /** faceid / touchid frame — "要求指纹" (the owner proof iOS names Face ID): corner brackets around a face. */
    val ownerAuth: ImageVector by lazy {
        symbol("ios.faceid") {
            moveTo(3.5f, 8.2f); lineTo(3.5f, 5.6f); quadTo(3.5f, 3.5f, 5.6f, 3.5f); lineTo(8.2f, 3.5f)
            moveTo(15.8f, 3.5f); lineTo(18.4f, 3.5f); quadTo(20.5f, 3.5f, 20.5f, 5.6f); lineTo(20.5f, 8.2f)
            moveTo(20.5f, 15.8f); lineTo(20.5f, 18.4f); quadTo(20.5f, 20.5f, 18.4f, 20.5f); lineTo(15.8f, 20.5f)
            moveTo(8.2f, 20.5f); lineTo(5.6f, 20.5f); quadTo(3.5f, 20.5f, 3.5f, 18.4f); lineTo(3.5f, 15.8f)
            moveTo(8.6f, 9f); lineTo(8.6f, 10.4f)
            moveTo(15.4f, 9f); lineTo(15.4f, 10.4f)
            moveTo(12.2f, 9f); lineTo(12.2f, 13f); lineTo(11.2f, 13f)
            moveTo(9f, 15.6f); quadTo(12f, 17.8f, 15f, 15.6f)
        }
    }

    /** The same frame struck through — "不要求…". */
    val ownerAuthOff: ImageVector by lazy {
        symbol("ios.faceid.slash") {
            moveTo(3.5f, 8.2f); lineTo(3.5f, 5.6f); quadTo(3.5f, 3.5f, 5.6f, 3.5f); lineTo(8.2f, 3.5f)
            moveTo(15.8f, 3.5f); lineTo(18.4f, 3.5f); quadTo(20.5f, 3.5f, 20.5f, 5.6f); lineTo(20.5f, 8.2f)
            moveTo(20.5f, 15.8f); lineTo(20.5f, 18.4f); quadTo(20.5f, 20.5f, 18.4f, 20.5f); lineTo(15.8f, 20.5f)
            moveTo(8.2f, 20.5f); lineTo(5.6f, 20.5f); quadTo(3.5f, 20.5f, 3.5f, 18.4f); lineTo(3.5f, 15.8f)
            moveTo(9f, 15.6f); quadTo(12f, 17.8f, 15f, 15.6f)
            moveTo(4.5f, 4.5f); lineTo(19.5f, 19.5f)
        }
    }

    /** app.dashed — "obscured app appearance" in the Hide sheet. */
    val appDashed: ImageVector by lazy {
        symbol("ios.app.dashed") {
            moveTo(4f, 9f); lineTo(4f, 7f); quadTo(4f, 4f, 7f, 4f); lineTo(9f, 4f)
            moveTo(15f, 4f); lineTo(17f, 4f); quadTo(20f, 4f, 20f, 7f); lineTo(20f, 9f)
            moveTo(20f, 15f); lineTo(20f, 17f); quadTo(20f, 20f, 17f, 20f); lineTo(15f, 20f)
            moveTo(9f, 20f); lineTo(7f, 20f); quadTo(4f, 20f, 4f, 17f); lineTo(4f, 15f)
            moveTo(11f, 4f); lineTo(13f, 4f)
            moveTo(20f, 11f); lineTo(20f, 13f)
            moveTo(11f, 20f); lineTo(13f, 20f)
            moveTo(4f, 11f); lineTo(4f, 13f)
        }
    }

    /** eye.slash — "隐藏 App". */
    val eyeSlash: ImageVector by lazy {
        symbol("ios.eye.slash") {
            moveTo(2.8f, 12f); quadTo(7f, 5.6f, 12f, 5.6f); quadTo(17f, 5.6f, 21.2f, 12f)
            quadTo(17f, 18.4f, 12f, 18.4f); quadTo(7f, 18.4f, 2.8f, 12f); close()
            circle(12f, 12f, 3.1f)
            moveTo(4.6f, 4.2f); lineTo(19.4f, 19.8f)
        }
    }
}
