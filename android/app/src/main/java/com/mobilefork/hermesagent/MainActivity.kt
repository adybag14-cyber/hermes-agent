package com.mobilefork.hermesagent

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.mobilefork.hermesagent.auth.AuthRuntimeApplier
import com.mobilefork.hermesagent.auth.OpenRouterOAuthClient
import com.mobilefork.hermesagent.data.AuthSessionStore
import com.mobilefork.hermesagent.device.DeviceStateWriter
import com.mobilefork.hermesagent.device.HermesCrashLogStore
import com.mobilefork.hermesagent.device.HermesLauncherShortcutBridge
import com.mobilefork.hermesagent.ui.boot.BootScreen
import com.mobilefork.hermesagent.ui.theme.hermesViewBackdropDrawable
import com.mobilefork.hermesagent.ui.theme.hermesViewPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val startupPalette = hermesViewPalette(this)
        window.setBackgroundDrawable(hermesViewBackdropDrawable(startupPalette))
        val startupStatusBarStyle = if (startupPalette.lightCanvas) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        val startupNavigationBarStyle = if (startupPalette.lightCanvas) {
            // API 24-25 cannot draw dark navigation icons. Keep AndroidX's legacy dark scrim
            // instead of placing always-light icons directly over a transparent light canvas.
            SystemBarStyle.light(Color.TRANSPARENT, LEGACY_LIGHT_NAVIGATION_BAR_SCRIM)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = startupStatusBarStyle,
            navigationBarStyle = startupNavigationBarStyle,
        )
        super.onCreate(savedInstanceState)
        HermesCrashLogStore.install(applicationContext)
        registerShizukuStateRefresh()
        handleAuthCallback(intent)
        handleShortcutIntent(intent)
        setContent {
            BootScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
        handleShortcutIntent(intent)
    }

    private fun handleAuthCallback(intent: Intent?) {
        val callbackUri = intent?.data ?: return
        val store = AuthSessionStore(applicationContext)
        val pending = store.loadPendingRequest()
        if (OpenRouterOAuthClient.isOpenRouterCallback(callbackUri, pending)) {
            lifecycleScope.launch(Dispatchers.IO) {
                val session = OpenRouterOAuthClient.exchangeCallbackForSession(callbackUri, requireNotNull(pending))
                store.clearPendingRequest()
                store.saveSession(session)
                if (session.signedIn) {
                    AuthRuntimeApplier.apply(applicationContext, session)
                }
            }
            return
        }
        val session = store.consumeAuthCallback(callbackUri) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            AuthRuntimeApplier.apply(applicationContext, session)
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (!HermesLauncherShortcutBridge.isShortcutIntent(intent)) {
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            HermesLauncherShortcutBridge.handleShortcutIntentJson(applicationContext, intent)
        }
    }

    private fun registerShizukuStateRefresh() {
        if (shizukuRefreshRegistered) {
            return
        }
        val appContext = applicationContext
        val refresh = {
            Thread {
                DeviceStateWriter.write(appContext)
            }.apply {
                name = "HermesDeviceStateRefresh"
                isDaemon = true
                start()
            }
        }
        val received = Shizuku.OnBinderReceivedListener { refresh() }
        val dead = Shizuku.OnBinderDeadListener { refresh() }
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(received)
            Shizuku.addBinderDeadListener(dead)
            shizukuBinderReceivedListener = received
            shizukuBinderDeadListener = dead
            shizukuRefreshRegistered = true
        }
    }

    companion object {
        @Volatile
        private var shizukuRefreshRegistered = false
        private var shizukuBinderReceivedListener: Shizuku.OnBinderReceivedListener? = null
        private var shizukuBinderDeadListener: Shizuku.OnBinderDeadListener? = null
        private val LEGACY_LIGHT_NAVIGATION_BAR_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
