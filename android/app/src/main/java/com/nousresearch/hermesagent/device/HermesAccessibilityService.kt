package com.nousresearch.hermesagent.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class HermesAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        HermesAccessibilityController.bind(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        DeviceStateWriter.write(applicationContext)
    }

    override fun onInterrupt() {
        // No-op for now.
    }

    override fun onDestroy() {
        HermesAccessibilityController.unbind(this)
        super.onDestroy()
    }
}
