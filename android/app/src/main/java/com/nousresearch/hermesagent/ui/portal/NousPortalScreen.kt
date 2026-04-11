package com.nousresearch.hermesagent.ui.portal

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val DEFAULT_NOUS_PORTAL_URL = "https://portal.nousresearch.com"

data class NousPortalUiState(
    val portalUrl: String = DEFAULT_NOUS_PORTAL_URL,
    val loggedIn: Boolean = false,
    val inferenceUrl: String = "",
    val status: String = "Loading Nous Portal…",
)

class NousPortalViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NousPortalUiState())
    val uiState: StateFlow<NousPortalUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = runCatching {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(getApplication()))
                }
                val payload = Python.getInstance()
                    .getModule("hermes_android.nous_portal_bridge")
                    .callAttr("read_nous_portal_state_json")
                    .toString()
                val json = JSONObject(payload)
                NousPortalUiState(
                    portalUrl = json.optString("portal_url").ifBlank { DEFAULT_NOUS_PORTAL_URL },
                    loggedIn = json.optBoolean("logged_in", false),
                    inferenceUrl = json.optString("inference_url").orEmpty(),
                    status = if (json.optBoolean("logged_in", false)) {
                        "Signed in to Nous Portal"
                    } else {
                        "Browsing Nous Portal"
                    },
                )
            }.getOrElse { error ->
                NousPortalUiState(
                    portalUrl = DEFAULT_NOUS_PORTAL_URL,
                    status = "Using default Nous Portal URL (${error.message ?: error.javaClass.simpleName})",
                )
            }
        }
    }
}

@Composable
fun NousPortalScreen(
    modifier: Modifier = Modifier,
    viewModel: NousPortalViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.portalUrl) {
        isLoading = true
        pageError = null
    }

    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Nous Portal", style = MaterialTheme.typography.headlineSmall)
                Text(uiState.status, style = MaterialTheme.typography.bodyMedium)
                Text(uiState.portalUrl, style = MaterialTheme.typography.bodySmall)
                if (uiState.inferenceUrl.isNotBlank()) {
                    Text("Inference: ${uiState.inferenceUrl}", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = viewModel::refresh) {
                        Text("Refresh")
                    }
                    Button(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.portalUrl))
                        context.startActivity(intent)
                    }) {
                        Text("Open externally")
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (!pageError.isNullOrBlank()) {
                    Text(
                        text = pageError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { androidContext ->
                        WebView(androidContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean = false

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    pageError = null
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame != false) {
                                        isLoading = false
                                        pageError = error?.description?.toString() ?: "Failed to load Nous Portal"
                                    }
                                }
                            }
                            loadUrl(uiState.portalUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url != uiState.portalUrl) {
                            webView.loadUrl(uiState.portalUrl)
                        }
                    },
                )
            }
        }
    }
}
