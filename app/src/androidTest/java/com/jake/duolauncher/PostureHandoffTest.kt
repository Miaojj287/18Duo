package com.jake.duolauncher

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unfold/fold page handoff, checked on EVERY frame Home draws (fix wave "fixfinal").
 *
 * iPhone Duo: the cover's page becomes the right half of the inner spread ([Today | P1] from Today
 * or P1, [P(k−1) | Pk] from Pk, [Pn | App Library] from the App Library) and folding shows the
 * page that was on the right half. Core.17 remapped the pager one frame late, so unfolding from
 * the last Home first drew [Pn | App Library] and folding first drew the spread's left page.
 *
 * Postures are switched with `wm size` (cover 1168×1712 ↔ inner 2364×1672 at density 440), so this
 * runs only on the QA emulator. It deliberately uses a plain [ActivityScenario], not the Compose test
 * rule: the rule's virtual frame clock lets a View traversal run before the posture recomposition,
 * a frame ordering that never happens with the real Choreographer clock.
 */
@RunWith(AndroidJUnit4::class)
class PostureHandoffTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var originalSize: String? = null
    private var savedLayout: HomeLayout? = null
    private var todayWasHidden = false

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun <T> onMain(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun model(activity: MainActivity) = ViewModelProvider(activity)[LauncherModel::class.java]

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("Timed out waiting for $what", SystemClock.uptimeMillis() < end)
            Thread.sleep(50)
        }
    }

    private fun composeRoot(view: View): RootForTest? = when (view) {
        is RootForTest -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { composeRoot(view.getChildAt(it)) }
        else -> null
    }

    private fun nodes(activity: MainActivity): List<SemanticsNode> {
        val all = ArrayList<SemanticsNode>()
        fun visit(node: SemanticsNode) { all += node; node.children.forEach(::visit) }
        composeRoot(activity.window.decorView)?.let { visit(it.semanticsOwner.unmergedRootSemanticsNode) }
        return all
    }

    private fun tagged(activity: MainActivity, tag: String) =
        nodes(activity).filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun click(activity: MainActivity, tag: String) =
        tagged(activity, tag).firstOrNull()?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke()

    @Before fun setUp() {
        assumeTrue("Postures are emulated with `wm size` on the QA emulator only", emulator)
        originalSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        // A HOME intent: the default launcher-category intent would open Settings on top of Home.
        scenario = ActivityScenario.launch(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
            .setClassName(instrumentation.targetContext, MainActivity::class.java.name))
        waitFor("Home", 15_000) { onMain { tagged(it, "launcher-root").isNotEmpty() } }
        onMain { click(it, "setup-explore"); click(it, "settings-close") }
        waitFor("apps") { onMain { !model(it).state.value.loading } }
        // Three Home pages (one app on each) so first, middle, last Home and the App Library differ.
        onMain { activity ->
            val model = model(activity)
            savedLayout = model.state.value.layout
            val apps = model.state.value.apps
            assertTrue("Needs three installed apps", apps.size >= 3)
            val slots = MutableList<String?>(3 * HOME_CELLS) { null }
            slots[0] = apps[0].id; slots[HOME_CELLS] = apps[1].id; slots[2 * HOME_CELLS] = apps[2].id
            model.restoreLayout(HomeLayout(slots = slots, dock = (0 until 4).map { apps[it % apps.size].id }))
            todayWasHidden = activity.corePreferences.widgetPageMode == "HIDE"
            if (todayWasHidden) activity.corePreferences.setWidgetPage(true)
        }
        waitFor("three Home pages") { onMain { model(it).state.value.homePages == 3 } }
    }

    @After fun tearDown() {
        if (!emulator) return
        shell(originalSize?.let { "wm size $it" } ?: "wm size reset")
        onMain { activity ->
            savedLayout?.let(model(activity)::restoreLayout)
            activity.corePreferences.setWidgetPage(!todayWasHidden)
        }
        scenario.close()
    }

    /** One drawn frame: window size and where each logical page is (Today −1, App Library = homes). */
    private data class Frame(val width: Float, val height: Float, val pages: Map<Int, Rect>) {
        val spread get() = width > height
        fun pageAt(x: Float): Int? = pages.filter { (_, r) -> x >= r.left && x < r.right }
            .minByOrNull { (_, r) -> kotlin.math.abs(r.center.x - x) }?.key
        /** Cover: the page in view. Spread: left and right halves (Apple grids 7.8–41.7 / 49.4–83.4 %W). */
        val inView: List<Int?> get() = if (spread) listOf(pageAt(width * .25f), pageAt(width * .66f))
            else listOf(pageAt(width * .40f))
    }

    private fun sample(activity: MainActivity): Frame {
        val homes = model(activity).state.value.homePages
        val pages = HashMap<Int, Rect>()
        for (node in nodes(activity)) {
            val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: continue
            val page = if (tag == "library-page") homes
                else tag.removePrefix("home-page-").takeIf { it != tag }?.toIntOrNull() ?: continue
            if (node.boundsInRoot.width > 0f) pages[page] = node.boundsInRoot
        }
        val decor = activity.window.decorView
        return Frame(decor.width.toFloat(), decor.height.toFloat(), pages)
    }

    private fun current(): Frame = onMain(::sample)

    private fun setPosture(spread: Boolean) {
        shell(if (spread) "wm size 2364x1672" else "wm size 1168x1712")
        waitFor("posture spread=$spread") { current().spread == spread }
        // The arrival spring, the settle and any (wrong) late page move all happen inside this window.
        Thread.sleep(2_000)
    }

    /** Pages to [target]: the page in view on the cover, the right half on the spread. */
    private fun pageTo(target: Int) {
        repeat(12) {
            val frame = current()
            val now = frame.inView.last()
            if (now == target) return
            val w = frame.width.toInt(); val y = (frame.height * .75f).toInt()
            if (now == null || now < target) shell("input swipe ${w * 85 / 100} $y ${w * 20 / 100} $y 300")
            else shell("input swipe ${w * 15 / 100} $y ${w * 80 / 100} $y 300")
            Thread.sleep(1_500)
        }
        assertEquals("Could not page to $target", target, current().inView.last())
    }

    /** Switches posture while sampling every frame Home draws; each frame in the new posture must
     * already show [expected] (no intermediate page, no scroll across pages). */
    private fun changePostureExpecting(spread: Boolean, expected: List<Int>, label: String) {
        val frames = java.util.Collections.synchronizedList(mutableListOf<Frame>())
        val listener = onMain { activity ->
            ViewTreeObserver.OnPreDrawListener { frames += sample(activity); true }
                .also(activity.window.decorView.viewTreeObserver::addOnPreDrawListener)
        }
        try {
            setPosture(spread)
        } finally {
            onMain { it.window.decorView.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        val after = frames.filter { it.spread == spread }
        assertTrue("$label: no frame drawn in the new posture", after.isNotEmpty())
        after.forEachIndexed { index, frame ->
            assertEquals("$label: frame ${index + 1}/${after.size} in the new posture", expected, frame.inView)
        }
    }

    @Test fun unfoldPutsTheCoverPageOnTheRightHalfAndFoldKeepsItFromTheFirstFrame() {
        setPosture(spread = false)
        val homes = onMain { model(it).state.value.homePages }
        // Cover page -> spread [left, right] -> cover page after folding back.
        val cases = listOf(
            Triple(-1, listOf(-1, 0), 0),                                // Today -> [Today | P1] -> P1
            Triple(0, listOf(-1, 0), 0),                                 // P1 -> [Today | P1] -> P1
            Triple(1, listOf(0, 1), 1),                                  // P2 -> [P1 | P2] -> P2
            Triple(homes - 1, listOf(homes - 2, homes - 1), homes - 1),  // last Home -> [P2 | P3] -> P3
            Triple(homes, listOf(homes - 1, homes), homes),              // App Library -> [P3 | Library] -> Library
        )
        for ((cover, spread, back) in cases) {
            pageTo(cover)
            changePostureExpecting(true, spread, "unfold from $cover")
            changePostureExpecting(false, listOf(back), "fold from $spread")
        }
    }

    @Test fun withTodayHiddenTheCoverPageStillBecomesTheRightHalf() {
        onMain { it.corePreferences.setWidgetPage(false) }
        setPosture(spread = false)
        val homes = onMain { model(it).state.value.homePages }
        pageTo(1)
        changePostureExpecting(true, listOf(0, 1), "unfold from P2 without Today")
        changePostureExpecting(false, listOf(1), "fold [P1 | P2] without Today")
        pageTo(homes)
        changePostureExpecting(true, listOf(homes - 1, homes), "unfold from the App Library without Today")
        changePostureExpecting(false, listOf(homes), "fold [P3 | App Library] without Today")
    }
}
