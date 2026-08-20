package com.mobilefork.hermesagent.device

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.mobilefork.hermesagent.R
import com.mobilefork.hermesagent.data.AuthSessionStore
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import com.mobilefork.hermesagent.ui.theme.applyHermesViewTree
import com.mobilefork.hermesagent.ui.theme.applyHermesViewWindowTheme
import com.mobilefork.hermesagent.ui.theme.hermesDp
import com.mobilefork.hermesagent.ui.theme.hermesLocalizedContext
import com.mobilefork.hermesagent.ui.theme.hermesScrollablePage
import com.mobilefork.hermesagent.ui.theme.hermesViewBackdropDrawable
import com.mobilefork.hermesagent.ui.theme.hermesViewButtonDrawable
import com.mobilefork.hermesagent.ui.theme.hermesViewPalette
import com.mobilefork.hermesagent.ui.theme.hermesViewPanelDrawable

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class HermesProviderSetupWebActivity : Activity() {
    private var webView: WebView? = null
    private lateinit var setupUri: Uri
    private lateinit var titleText: TextView
    private lateinit var progressBar: ProgressBar
    private var fallbackShown = false
    private var setupPageTitle = ""
    private val palette by lazy { hermesViewPalette(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.hermesLocalizedContext())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHermesViewWindowTheme(palette)

        val requestedUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val requestedTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
            getString(R.string.hermes_provider_setup_title)
        }
        setupPageTitle = requestedTitle
        setupUri = Uri.parse(requestedUrl)
        if (!canOpen(setupUri)) {
            showFallback(requestedTitle, requestedUrl, getString(R.string.hermes_provider_setup_invalid_url))
            return
        }
        if (HermesNetworkPolicy.isExternalNetworkBlocked(this, requestedUrl)) {
            showFallback(requestedTitle, requestedUrl, getString(R.string.hermes_provider_setup_offline_blocked))
            return
        }

        buildViewer(requestedTitle, setupUri.toString())
    }

    override fun onBackPressed() {
        val currentWebView = webView
        if (currentWebView != null && currentWebView.canGoBack()) {
            currentWebView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        releaseWebView()
        super.onDestroy()
    }

    private fun buildViewer(pageTitle: String, url: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = hermesViewBackdropDrawable(palette)
            setPadding(0, statusBarInsetPx(), 0, 0)
        }

        titleText = TextView(this).apply {
            text = pageTitle
            textSize = 18f
            setTextColor(palette.onBackground)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(hermesDp(20f), hermesDp(16f), hermesDp(20f), hermesDp(6f))
        }
        root.addView(titleText, fullWidthWrapParams())

        val toolbar = LinearLayout(this).apply {
            orientation = providerToolbarOrientation(resources.configuration.screenWidthDp)
            val toolbarPadding = hermesDp(10f)
            setPadding(toolbarPadding, hermesDp(4f), toolbarPadding, toolbarPadding)
            background = hermesViewPanelDrawable(this@HermesProviderSetupWebActivity, palette)
        }
        toolbar.addView(
            toolbarButton(getString(R.string.hermes_provider_setup_back)) {
                webView?.takeIf { it.canGoBack() }?.goBack() ?: finish()
            },
            toolbarButtonParams(toolbar.orientation),
        )
        toolbar.addView(
            toolbarButton(getString(R.string.hermes_provider_setup_browser)) { openExternal(currentUrl()) },
            toolbarButtonParams(toolbar.orientation),
        )
        toolbar.addView(
            toolbarButton(getString(R.string.hermes_provider_setup_copy)) { copyToClipboard(currentUrl()) },
            toolbarButtonParams(toolbar.orientation),
        )
        toolbar.addView(
            toolbarButton(getString(R.string.hermes_provider_setup_close)) { finish() },
            toolbarButtonParams(toolbar.orientation),
        )
        root.addView(toolbar, fullWidthWrapParams())

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(palette.primary)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary)
        }
        root.addView(progressBar, fullWidthWrapParams())

        val currentWebView = runCatching { WebView(this) }.getOrElse { error ->
            showFallback(
                pageTitle,
                url,
                getString(R.string.hermes_provider_setup_webview_unavailable, error::class.java.simpleName),
            )
            return
        }
        webView = currentWebView
        configureWebView(currentWebView)
        root.addView(
            currentWebView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        applyHermesViewTree(titleText, palette)
        titleText.setTextColor(palette.onBackground)
        applyHermesViewTree(toolbar, palette)
        setContentView(root)
        currentWebView.loadUrl(url)
    }

    private fun configureWebView(view: WebView) {
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // OAuth IdPs (Google/OpenAI/xAI/Zhipu) often use popup or multi-step redirects.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Keep third-party cookies available for OAuth sessions inside WebView.
            @Suppress("DEPRECATION")
            setAcceptThirdPartyCookiesCompat(view)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.isIndeterminate = false
                progressBar.progress = newProgress.coerceIn(0, 100)
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val resolvedTitle = title.orEmpty().ifBlank { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
                if (resolvedTitle.isNotBlank()) {
                    titleText.text = resolvedTitle
                }
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return shouldOpenOutside(request.url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return shouldOpenOutside(Uri.parse(url))
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    showLoadFailureFallback(
                        request.url?.toString().orEmpty().ifBlank { currentUrl() },
                        getString(R.string.hermes_provider_setup_webview_load_failed, error.description),
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    showLoadFailureFallback(
                        request.url?.toString().orEmpty().ifBlank { currentUrl() },
                        getString(R.string.hermes_provider_setup_webview_http_error, errorResponse.statusCode),
                    )
                }
            }
        }
    }

    private fun shouldOpenOutside(uri: Uri): Boolean {
        // Capture Hermes OAuth deep links before they leave the WebView.
        if (openHermesAuthCallback(uri)) {
            return true
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme in setOf("http", "https")) {
            return false
        }
        // Other app schemes (intent://, market://, etc.) — try external, then copy.
        openExternal(uri.toString())
        return true
    }

    private fun setAcceptThirdPartyCookiesCompat(view: WebView) {
        runCatching {
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        }
    }

    private fun showFallback(pageTitle: String, url: String, message: String) {
        releaseWebView()
        if (::progressBar.isInitialized) {
            progressBar.visibility = View.GONE
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val panelPadding = hermesDp(20f)
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            background = hermesViewPanelDrawable(this@HermesProviderSetupWebActivity, palette, elevated = true)
        }
        root.addView(TextView(this).apply {
            text = pageTitle
            textSize = 22f
            setTextColor(palette.onBackground)
        }, fullWidthWrapParams())
        root.addView(TextView(this).apply {
            text = "$message\n\n$url"
            textSize = 16f
            setTextColor(palette.onSurface)
            setPadding(0, hermesDp(20f), 0, hermesDp(20f))
        }, fullWidthWrapParams())
        root.addView(toolbarButton(getString(R.string.hermes_provider_setup_open_browser)) { openExternal(url) }, fullWidthWrapParams())
        root.addView(toolbarButton(getString(R.string.hermes_provider_setup_copy_url)) { copyToClipboard(url) }, fullWidthWrapParams())
        root.addView(toolbarButton(getString(R.string.hermes_provider_setup_close)) { finish() }, fullWidthWrapParams())
        applyHermesViewTree(root, palette)
        setContentView(hermesScrollablePage(root, palette, topInsetPx = statusBarInsetPx()))
        if (url.isNotBlank()) {
            copyToClipboard(url, showToast = false)
        }
    }

    private fun showLoadFailureFallback(url: String, message: String) {
        if (fallbackShown) {
            return
        }
        fallbackShown = true
        val targetUrl = url.ifBlank { setupUri.toString() }
        copyToClipboard(targetUrl, showToast = false)
        Toast.makeText(
            this,
            getString(R.string.hermes_provider_setup_load_failed_copied),
            Toast.LENGTH_LONG,
        ).show()
        showFallback(setupPageTitle, targetUrl, message)
    }

    private fun toolbarButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(palette.onPrimary)
            background = hermesViewButtonDrawable(this@HermesProviderSetupWebActivity, palette)
            isAllCaps = false
            minHeight = hermesDp(44f)
            setOnClickListener { onClick() }
        }
    }

    private fun toolbarButtonParams(orientation: Int): LinearLayout.LayoutParams {
        val gap = hermesDp(4f)
        return if (orientation == LinearLayout.VERTICAL) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, gap, 0, gap)
            }
        } else {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(gap, 0, gap, 0)
            }
        }
    }

    private fun fullWidthWrapParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun statusBarInsetPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private fun currentUrl(): String {
        return webView?.url.orEmpty().ifBlank { setupUri.toString() }
    }

    private fun releaseWebView() {
        webView?.let { existing ->
            runCatching { existing.webChromeClient = null }
            runCatching { existing.webViewClient = WebViewClient() }
            runCatching { existing.stopLoading() }
            runCatching { existing.loadUrl("about:blank") }
            runCatching { existing.clearHistory() }
            runCatching { existing.removeAllViews() }
            (existing.parent as? ViewGroup)?.removeView(existing)
            runCatching { existing.destroy() }
        }
        webView = null
    }

    private fun copyToClipboard(url: String, showToast: Boolean = true) {
        val target = url.trim()
        if (target.isBlank()) {
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.hermes_provider_setup_clipboard_label), target))
        if (showToast) {
            Toast.makeText(this, getString(R.string.hermes_provider_setup_url_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternal(url: String) {
        val targetUri = Uri.parse(url.trim())
        if (!canOpen(targetUri)) {
            if (!openHermesAuthCallback(targetUri)) {
                copyToClipboard(url)
            }
            return
        }
        val result = HermesExternalBrowserLauncher.open(
            context = this,
            uri = targetUri,
            title = getString(R.string.hermes_provider_setup_open_page),
            forceChooser = true,
        )
        if (!result.success) {
            copyToClipboard(url)
            Toast.makeText(this, getString(R.string.hermes_provider_setup_no_browser_copied), Toast.LENGTH_LONG).show()
        }
    }

    private fun openHermesAuthCallback(uri: Uri): Boolean {
        if (!AuthSessionStore.isAuthCallback(uri)) {
            return false
        }
        return runCatching {
            // Deliver callback into MainActivity in this task so OAuth exchange runs immediately.
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setClassName(packageName, "com.mobilefork.hermesagent.MainActivity")
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
            finish()
        }.isSuccess
    }

    companion object {
        internal const val EXTRA_URL = "com.mobilefork.hermesagent.PROVIDER_SETUP_URL"
        internal const val EXTRA_TITLE = "com.mobilefork.hermesagent.PROVIDER_SETUP_TITLE"

        fun createIntent(context: Context, uri: Uri, title: String): Intent {
            return Intent(context, HermesProviderSetupWebActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_URL, uri.toString())
                putExtra(
                    EXTRA_TITLE,
                    title.ifBlank { context.hermesLocalizedContext().getString(R.string.hermes_provider_setup_title) },
                )
            }
        }

        fun open(context: Context, uri: Uri, title: String): BrowserLaunchResult {
            if (!canOpen(uri)) {
                return BrowserLaunchResult(success = false, errorName = "UnsupportedScheme")
            }
            val external = HermesExternalBrowserLauncher.open(
                context = context,
                uri = uri,
                title = title,
                forceChooser = true,
            )
            if (external.success) {
                return external
            }
            val appContext = context.applicationContext
            return runCatching {
                appContext.startActivity(createIntent(appContext, uri, title))
                BrowserLaunchResult(success = true)
            }.getOrElse { error ->
                BrowserLaunchResult(success = false, errorName = error::class.java.simpleName)
            }
        }

        fun openInApp(context: Context, uri: Uri, title: String): BrowserLaunchResult {
            if (!canOpen(uri)) {
                return BrowserLaunchResult(success = false, errorName = "UnsupportedScheme")
            }
            val appContext = context.applicationContext
            return runCatching {
                appContext.startActivity(createIntent(appContext, uri, title))
                BrowserLaunchResult(success = true)
            }.getOrElse { error ->
                BrowserLaunchResult(success = false, errorName = error::class.java.simpleName)
            }
        }

        fun canOpen(uri: Uri): Boolean {
            return uri.scheme?.lowercase() in SUPPORTED_URI_SCHEMES && !uri.host.isNullOrBlank()
        }

        private val SUPPORTED_URI_SCHEMES = setOf("http", "https")
    }
}

internal fun providerToolbarOrientation(widthDp: Int): Int {
    return if (widthDp < 480) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
}
