package com.nousresearch.hermesagent

import android.app.Application
import com.nousresearch.hermesagent.backend.HermesRuntimeService
import com.nousresearch.hermesagent.data.DeviceCapabilityStore
import com.nousresearch.hermesagent.device.DeviceStateWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope

class HermesApplication : Application() {
    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        instance = this

        appScope.launch(Dispatchers.IO) {
            if (DeviceCapabilityStore(this@HermesApplication).load().backgroundPersistenceEnabled) {
                HermesRuntimeService.start(this@HermesApplication)
            }
            DeviceStateWriter.write(this@HermesApplication)
        }
    }

    companion object {
        lateinit var instance: HermesApplication
            private set
    }
}
