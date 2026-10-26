package com.galleryflows

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlin.concurrent.thread

class App : Application() {
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Scan.init(this)
        Thumbs.init(this)
        Lock.locked = Prefs.appLock
        thread { Trash.purgeOld(this) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
                // last screen left the foreground (home, other app…): lock again, unless it's our own unlock prompt
                if (--started == 0 && !Lock.prompting) Lock.locked = Prefs.appLock
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
