package com.mobilefork.hermesagent.ui.boot

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.R
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.shell.AppShellScreen
import com.mobilefork.hermesagent.ui.theme.HermesBackdrop
import com.mobilefork.hermesagent.ui.theme.HermesTheme
import com.mobilefork.hermesagent.ui.theme.HermesThemeConfig

@Composable
fun BootScreen(onFirstFrame: () -> Unit = {}) {
    val appContext = LocalContext.current.applicationContext
    val startupSettings = remember(appContext) { AppSettingsStore(appContext).load() }
    var shellVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        // The static startup frame has now been submitted. Queueing local-model work here keeps
        // multi-gigabyte verification off the cold-frame path; the callback itself only hands
        // ownership to the process-scoped application worker.
        onFirstFrame()
        shellVisible = true
    }
    if (!shellVisible) {
        StartupFirstFrame(startupSettings)
        return
    }
    val viewModel: BootViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.refresh()
    }
    AppShellScreen(
        bootUiState = uiState,
        onRetryHermes = viewModel::refresh,
    )
}

@Composable
private fun StartupFirstFrame(settings: AppSettings) {
    HermesTheme(
        config = HermesThemeConfig(
            primaryHex = settings.themePrimaryHex,
            secondaryHex = settings.themeSecondaryHex,
            backgroundHex = settings.themeBackgroundHex,
            surfaceHex = settings.themeSurfaceHex,
            surfaceVariantHex = settings.themeSurfaceVariantHex,
            cardShape = settings.themeCardShape,
            fontScale = settings.uiFontScale,
        ),
    ) {
        HermesBackdrop(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.hermes_agent_fork_logo),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp),
                )
            }
        }
    }
}
