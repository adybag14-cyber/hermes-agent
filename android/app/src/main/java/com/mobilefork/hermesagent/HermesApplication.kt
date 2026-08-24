package com.mobilefork.hermesagent

import android.app.Application
import android.system.Os
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HermesApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val localRuntimeAutoStarter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MainActivityLocalRuntimeAutoStarter(
            loadSelectedBackend = {
                BackendKind.fromPersistedValue(
                    AppSettingsStore(applicationContext).load().onDeviceBackend,
                )
            },
            captureSelectionGeneration =
                LocalModelRuntimeSelectionAuthority::currentGeneration,
            launchAsync = { action ->
                applicationScope.launch {
                    action()
                }
            },
            ensureStarted = { expectedBackend, selectionGeneration ->
                HermesRuntimeManager.ensureExpectedLocalBackendStarted(
                    applicationContext,
                    expectedBackend,
                    selectionGeneration,
                )
            },
        )
    }

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
