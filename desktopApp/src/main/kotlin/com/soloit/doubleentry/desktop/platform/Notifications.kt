package com.soloit.doubleentry.desktop.platform

import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

/**
 * Desktop notifications through the system tray: on Windows 10 and 11 they appear as toast
 * notifications in the notification center. [onClick] runs when the user clicks one.
 */
class Notifications(private val onClick: () -> Unit) {
    private var icon: TrayIcon? = null
    private var removal: TimerTask? = null
    private val timer = Timer("notifications", true)

    val supported: Boolean get() = SystemTray.isSupported()

    @Synchronized
    fun show(title: String, text: String) {
        if (!supported) return
        val tray = SystemTray.getSystemTray()
        val current = icon ?: TrayIcon(Toolkit.getDefaultToolkit().getImage(javaClass.getResource("/icon.png")), "DoubleEntry").apply {
            isImageAutoSize = true
            addActionListener { onClick() }
            tray.add(this)
            icon = this
        }
        current.displayMessage(title, text, TrayIcon.MessageType.INFO)
        // The tray icon is only there to send the notification; it leaves again after a while.
        removal?.cancel()
        removal = timer.schedule(60_000) { remove() }
    }

    @Synchronized
    fun remove() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }
}
