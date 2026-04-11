package com.nousresearch.hermesagent.ui.boot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.hermesagent.ui.shell.AppShellScreen

@Composable
fun BootScreen(viewModel: BootViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    AppShellScreen(
        bootUiState = uiState,
        onRetryHermes = viewModel::refresh,
    )
}
