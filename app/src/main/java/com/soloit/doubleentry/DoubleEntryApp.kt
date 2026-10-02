package com.soloit.doubleentry

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.soloit.doubleentry.system.InvoiceReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DoubleEntryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        InvoiceReminders.createChannel(this)
        container.start()

        // Realtime + periodic sync only run while the app is visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.sync.onForeground()
                container.appLock.onForeground()
                container.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.sync.onBackground()
                container.appLock.onBackground()
            }
        })
    }
}
