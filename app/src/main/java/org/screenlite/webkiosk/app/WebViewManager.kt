package org.screenlite.webkiosk.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.*
import android.webkit.WebView.setWebContentsDebuggingEnabled
import androidx.annotation.RequiresApi
import org.screenlite.webkiosk.components.RotatedWebView
import org.screenlite.webkiosk.data.Rotation
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient

class WebViewManager(
    private val context: Context,
    private val onError: (Boolean) -> Unit,
    private val onPageLoading: (Boolean) -> Unit
) {
    private var currentWebView: WebView? = null
    private var isOfflineMode = false

    fun setOfflineMode(offline: Boolean) {
        val wasOffline = isOfflineMode
        isOfflineMode = offline
        currentWebView?.settings?.cacheMode = if (offline) {
            WebSettings.LOAD_CACHE_ONLY
        } else {
            WebSettings.LOAD_DEFAULT
        }
        // Only reload when genuinely returning from an offline state.
        // Do NOT reload on the normal startup onAvailable ping (wasOffline = false).
        if (!offline && wasOffline) {
            Log.i("WebViewManager", "Back online — reloading for fresh content")
            currentWebView?.reload()
        }
        Log.i("WebViewManager", "Offline mode: $offline (wasOffline=$wasOffline)")
    }
    fun createWebView(rotation: Rotation = Rotation.ROTATION_0): WebView {
        val webView = RotatedWebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            appliedRotation = rotation.degrees.toFloat()

            // Start invisible — the WebView's SurfaceView punches through Compose overlays,
            // so we keep it hidden until onPageFinished fires and content is ready.
            visibility = View.INVISIBLE

            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()

            configureWebViewSettings()
            setupWebViewListeners()
            setupRendererCrashHandler()
        }

        currentWebView = webView
        return webView
    }

    private fun WebView.setupRendererCrashHandler() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(this, object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    Log.e("WebViewManager", "Renderer unresponsive — terminating to recover")
                    renderer?.terminate()
                }
                override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    Log.i("WebViewManager", "Renderer responsive again")
                }
            })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configureWebViewSettings() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Strip WebView markers so player pages don't detect and restrict playback.
            // Removes "Version/4.0 " and "; wv" which identify the Android WebView.
            userAgentString = userAgentString
                .replace("Version/4.0 ", "")
                .replace("; wv)", ")")
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            setWebContentsDebuggingEnabled(true)

            // 0 = let the WebView use the page's own viewport meta (e.g. width=device-width).
            // Do NOT calculate from density — on 2x screens (100/2.0)=50 would scale content
            // to 50% and show it in the top-left with white space on the right and bottom.
            setInitialScale(0)
            // Fit the initial content to the WebView width so nothing clips off-screen.
            loadWithOverviewMode = true

            displayZoomControls = false
            builtInZoomControls = false
            setSupportZoom(false)

            textZoom = 100
            minimumFontSize = 1
            minimumLogicalFontSize = 1
            useWideViewPort = true
        }
    }

    /**
     * Force the WebView visible and dismiss the loading overlay.
     * Called when onPageFinished hasn't fired within the timeout window —
     * e.g. Vite dev-mode pages that keep window.onload pending indefinitely.
     */
    fun forceShow() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Log.w("WebViewManager", "forceShow() — onPageFinished did not fire in time")
            currentWebView?.visibility = android.view.View.VISIBLE
            onPageLoading(false)
        }
    }

    fun updateRotation(rotation: Rotation) {
        currentWebView?.let { webView ->
            if (webView is RotatedWebView) {
                webView.appliedRotation = rotation.degrees.toFloat()
            }
            // ViewportMetaInjector intentionally NOT called here — it overrides the player page's
            // own viewport/layout and can collapse the content area in SPAs. Rotation is handled
            // entirely via RotatedWebView.appliedRotation (a canvas transform), not JS injection.
        }
    }

    private fun WebView.setupWebViewListeners() {
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.visibility = View.INVISIBLE
                onPageLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                Log.d("WebViewManager", "onPageFinished: $url")
                // Do NOT inject viewport here — it overrides the player page's own layout
                // and can collapse the content area in complex SPAs.
                // Viewport injection only happens on rotation via updateRotation().
                view.postDelayed({
                    view.visibility = View.VISIBLE
                    onPageLoading(false)
                }, 1000)
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 23")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (isOfflineMode) {
                    Log.w("WebViewManager", "Offline cache miss (legacy): $failingUrl — keeping last content")
                } else {
                    Log.e("WebViewManager", "Legacy page failed: $failingUrl, code=$errorCode, desc=$description")
                    onPageLoading(false)
                    onError(true)
                }
                super.onReceivedError(view, errorCode, description, failingUrl)
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    if (isOfflineMode) {
                        Log.w("WebViewManager", "Offline cache miss: ${request.url} — keeping last content")
                    } else {
                        onPageLoading(false)
                        onError(true)
                        Log.e(
                            "WebViewManager",
                            "Main page failed: ${request.url}, code=${error.errorCode}, desc=${error.description}"
                        )
                    }
                } else {
                    Log.w(
                        "WebViewManager",
                        "Subresource failed: ${request.url}, code=${error.errorCode}, desc=${error.description}"
                    )
                }
            }

        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport

                val tempWebView = WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url.toString()
                            this@WebViewManager.currentWebView?.loadUrl(url)
                            return true
                        }
                    }
                }

                transport?.webView = tempWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    "WebViewConsole",
                    "JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                )
                return true
            }
        }
    }
}