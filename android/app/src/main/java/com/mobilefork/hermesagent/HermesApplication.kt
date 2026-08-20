package com.mobilefork.hermesagent

import android.app.Application
import android.system.Os
import java.io.File

class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Establish immutable Android identity before any Chaquopy module can
        // be imported. Several Python safety policies must be correct even
        // during the short interval between Python.start and runtime setup.
        Os.setenv("HERMES_ANDROID_BOOTSTRAP", "1", true)
        Os.setenv("HERMES_HOME", File(filesDir, "hermes-home").absolutePath, true)
        instance = this
    }

    companion object {
        lateinit var instance: HermesApplication
            private set
    }
}
