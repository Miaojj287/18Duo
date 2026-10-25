package com.jake.duolauncher

import android.app.Application

class DuoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        migrateBrandPreferences(this)
        uiResources = resources
        // Round 22: the 18Duo lock screen was removed (the system keyguard is used). Switch off what an
        // older build left on, so nothing puts itself over the keyguard any more.
        LockScreenPreferences(this).let { if (it.enabled) it.setEnabled(false) }
        // Round 18: a restarted process gets Home's last frame back for the navigation overlay.
        HomeSnapshotDisk.init(this)
        // Cold start: the 2364×1712 default wallpaper used to be decoded on the main thread during
        // Home's first composition. Start it now; the composition's call waits only for the rest.
        Thread({ runCatching { bundledDefaultWallpaper(this) } }, "duo-wallpaper-decode").start()
    }
    companion object {
        internal var uiResources: android.content.res.Resources? = null
            private set
    }
}
