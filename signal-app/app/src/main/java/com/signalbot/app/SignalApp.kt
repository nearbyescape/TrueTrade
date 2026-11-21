package com.signalbot.app

import android.app.Application
import com.signalbot.app.work.Notifier
import com.signalbot.app.work.ScanScheduler

class SignalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        ScanScheduler.ensureScheduled(this)
    }
}
