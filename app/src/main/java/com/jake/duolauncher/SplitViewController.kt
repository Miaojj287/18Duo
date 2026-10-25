package com.jake.duolauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * What the Split View picker (the half that is not the dragged app) tells the controller. Written on
 * the main thread by [SplitViewPickerActivity], read by the controller's worker.
 */
internal object SplitViewBus {
    @Volatile var token = 0
    @Volatile var resumed = false
    /** The picker's opening transition has finished (Activity.onEnterAnimationComplete). */
    @Volatile var shown = false
    /** The picker's window is one half of the panel, on [pickerSide]. */
    @Volatile var inSplit = false
    @Volatile var pickerSide: SplitSide? = null
    /** Set by the controller when a route failed: the picker closes itself. */
    @Volatile var abandon = 0
    private val listeners = mutableSetOf<() -> Unit>()

    fun publish(token: Int, resumed: Boolean, inSplit: Boolean, side: SplitSide?) {
        if (token != this.token) return
        this.resumed = resumed; this.inSplit = inSplit; this.pickerSide = side
    }

    fun shown(token: Int) { if (token == this.token) shown = true }

    fun clear(token: Int) { this.token = token; resumed = false; shown = false; inSplit = false; pickerSide = null }

    @Synchronized fun listen(listener: () -> Unit) { listeners += listener }
    @Synchronized fun forget(listener: () -> Unit) { listeners -= listener }
    fun abandon(token: Int) { abandon = token; synchronized(this) { listeners.toList() }.forEach { it() } }
}

/** How a split request ended. [side] is where the app really is, when known. */
internal data class SplitOutcome(val ok: Boolean, val route: String, val side: SplitSide?)

/**
 * Puts the app the finger dragged into split screen on the chosen side, by the best route this phone
 * offers (see docs/uiux-core17-r8-split.md for what works where):
 *
 * 1. **Local shell** ([SplitViewShell]): the 18Duo picker opens full screen over the app (under the
 *    stage, which still shows the card), then WMShell moves the app's task to the chosen side; the
 *    picker, now the top full-screen task, becomes the other half. Exact side, verified.
 * 2. **Accessibility toggle** (`GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN`): the system splits the app in
 *    front with its own chooser on the other half. The side is the system's choice.
 *
 * `FLAG_ACTIVITY_LAUNCH_ADJACENT` and `ActivityOptions` were tried and do not enter split from a
 * full-screen app on Android 16 AOSP (the launch-adjacent root only exists while split is active).
 *
 * All binder calls and the shell run on one worker thread; [enter]'s callback runs there too.
 */
internal class SplitViewController(private val service: AccessibilityService) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "DuoSplit").apply { isDaemon = true } }
    private var shell: SplitViewShell? = null
    private var family: SplitShellCommands.Family? = null
    private var warmedTask: SplitShellCommands.TaskLine? = null
    private var warmedFor: String? = null
    private var warmedAt = 0L
    /** A failed connect (no Wi‑Fi, untrusted network) is not retried on every hover. */
    private var openFailedAt = 0L

    /**
     * The panes just came in: connect the shell and find the app's task while the finger is still
     * down, so the release does not wait for it. Harmless if never followed by [enter].
     */
    fun prewarm(packageName: String, userId: Int) = submit {
        val key = "$packageName:$userId"
        if (warmedFor == key && SystemClock.uptimeMillis() - warmedAt < 8_000L && shell != null) return@submit
        warmedFor = key; warmedAt = SystemClock.uptimeMillis(); warmedTask = null
        if (!routeAllows("shell")) return@submit
        val s = openShell() ?: return@submit
        if (family == null) family = s.run(SplitShellCommands.HELP)?.let(SplitShellCommands::family)
        warmedTask = s.run(SplitShellCommands.TASKS)?.let { SplitShellCommands.taskOf(SplitShellCommands.parseTasks(it), packageName, userId) }
    }

    /** Ends a prewarmed session the user did not use (the card went back to the centre or Home). */
    fun cool() = submit { closeShellLater() }

    fun enter(packageName: String, userId: Int, side: SplitSide, panelWidthPx: Int, done: (SplitOutcome) -> Unit) = submit {
        val outcome = runCatching { enterNow(packageName, userId, side, panelWidthPx) }
            .onFailure { Log.w(TAG, "split failed", it) }.getOrNull() ?: SplitOutcome(false, "error", null)
        Log.i(TAG, "split ${if (outcome.ok) "ok" else "failed"} via ${outcome.route} side=${outcome.side}")
        done(outcome)
    }

    fun stop() { worker.shutdownNow(); runCatching { shell?.close() }; shell = null }

    // ---- worker thread ----

    private fun enterNow(packageName: String, userId: Int, side: SplitSide, panelWidthPx: Int): SplitOutcome {
        if (routeAllows("shell")) shellSplit(packageName, userId, side, panelWidthPx)?.let { return it }
        if (routeAllows("toggle")) {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            return SplitOutcome(ok, "toggle", null)
        }
        return SplitOutcome(false, "none", null)
    }

    /** Null when the shell is not available (the caller tries the next route). */
    private fun shellSplit(packageName: String, userId: Int, side: SplitSide, panelWidthPx: Int): SplitOutcome? {
        val s = openShell() ?: return null
        val fam = family ?: s.run(SplitShellCommands.HELP)?.let(SplitShellCommands::family)?.also { family = it } ?: return null
        val key = "$packageName:$userId"
        val task = warmedTask?.takeIf { warmedFor == key }
            ?: s.run(SplitShellCommands.TASKS)?.let { SplitShellCommands.taskOf(SplitShellCommands.parseTasks(it), packageName, userId) }
            ?: return null
        warmedTask = null
        val token = SplitViewBus.token + 1
        SplitViewBus.clear(token)
        // The picker goes on top full screen first: WMShell makes the top full-screen task the other half.
        val picker = Intent(service, SplitViewPickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(SplitViewPickerActivity.EXTRA_TOKEN, token)
            .putExtra(SplitViewPickerActivity.EXTRA_EXCLUDE, packageName)
        try { service.startActivity(picker) } catch (e: Exception) { Log.w(TAG, "picker refused", e); return null }
        if (!await(PICKER_TIMEOUT) { SplitViewBus.resumed }) { abandon(token); return SplitOutcome(false, "shell", null) }
        // WMShell dismisses a split entered while the picker's own opening transition is still running
        // ("removed the last task in a stage outside of a proper transition"): wait for it to finish.
        await(PICKER_TIMEOUT) { SplitViewBus.shown }
        Thread.sleep(TRANSITION_MARGIN)
        val attempts = SplitShellCommands.moves(fam, task.id, side).let { it + it.first() }
        for (command in attempts) {
            s.run(command) ?: break
            // Entered and still split a moment later (a split dismissed at once does not count).
            if (await(SPLIT_TIMEOUT) { SplitViewBus.inSplit } && !await(HOLD_CHECK) { !SplitViewBus.inSplit }) {
                // The picker knows its half; the app is on the other one. Turn the pair round if the
                // system remembered the opposite side-stage position.
                if (SplitViewBus.pickerSide == side) {
                    s.run(SplitShellCommands.swap(fam))
                    await(SPLIT_TIMEOUT) { SplitViewBus.pickerSide == side.other }
                }
                val actual = SplitViewBus.pickerSide?.other ?: s.run(SplitShellCommands.TASKS)
                    ?.let { SplitShellCommands.taskOf(SplitShellCommands.parseTasks(it), packageName, userId) }
                    ?.let { SplitShellCommands.sideOf(it, panelWidthPx) }
                closeShellLater()
                return SplitOutcome(true, "shell:${fam.name.lowercase()}", actual)
            }
        }
        abandon(token)
        closeShellLater()
        return SplitOutcome(false, "shell", null)
    }

    private fun openShell(): SplitViewShell? {
        shell?.let { return it }
        if (openFailedAt != 0L && SystemClock.uptimeMillis() - openFailedAt < RETRY_AFTER) return null
        val s = SplitViewShell(service)
        if (!s.open()) { openFailedAt = SystemClock.uptimeMillis(); return null }
        openFailedAt = 0L
        return s.also { shell = it }
    }

    private fun abandon(token: Int) { SplitViewBus.abandon(token) }

    private fun closeShellLater() {
        // Kept a moment so a second split right after does not reconnect; then wireless debugging is restored.
        val s = shell ?: return
        val at = SystemClock.uptimeMillis()
        warmedAt = at
        Thread {
            Thread.sleep(KEEP_ALIVE)
            submit { if (shell === s && warmedAt == at) { s.close(); shell = null } }
        }.apply { isDaemon = true }.start()
    }

    private fun routeAllows(route: String): Boolean {
        val forced = SplitViewSettings.forcedRoute(service)
        return forced.isEmpty() || forced == route
    }

    private inline fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25L)
        }
        return condition()
    }

    private fun submit(block: () -> Unit) {
        try { worker.execute(block) } catch (_: RejectedExecutionException) { /* stopped */ }
    }

    private companion object {
        const val TAG = "DuoSplit"
        const val PICKER_TIMEOUT = 1_500L
        const val SPLIT_TIMEOUT = 1_400L
        const val KEEP_ALIVE = 10_000L
        const val TRANSITION_MARGIN = 120L
        const val HOLD_CHECK = 350L
        const val RETRY_AFTER = 60_000L
    }
}
