package org.screenlite.webkiosk.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.*
import android.webkit.JavascriptInterface
import android.webkit.WebView.setWebContentsDebuggingEnabled
import androidx.annotation.RequiresApi
import org.screenlite.webkiosk.app.FileLogger
import org.screenlite.webkiosk.components.RotatedWebView
import org.screenlite.webkiosk.data.Rotation
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

class WebViewManager(
    private val context: Context,
    private val onError: (Boolean) -> Unit,
    private val onPageLoading: (Boolean) -> Unit
) {
    companion object {
        // Set to false to disable WebSocket monitoring entirely and always use
        // the 5-minute polling fallback — no other code changes needed.
        private const val WS_MONITOR_ENABLED = true
    }

    private var currentWebView: WebView? = null
    private var isOfflineMode = false
    private var isSilentReload = false
    private var silentReloadFailed = false  // set by onReceivedHttpError; guards onPageFinished
    private var lastLoadedUrl: String? = null

    // WebSocket connection state — observed by WebViewComponent to tune polling interval.
    // true  = WS healthy → poll every 30 min (safety net only)
    // false = WS down    → poll every 5 min (active fallback)
    private val _isWebSocketConnected = MutableStateFlow(false)
    val isWebSocketConnected: StateFlow<Boolean> = _isWebSocketConnected

    // Called when a silent reload completes — lets WebViewComponent remove the snapshot overlay
    var onSilentReloadComplete: () -> Unit = {}

    // Called when a silent (polling) reload fails due to network loss.
    // WebViewComponent should keep the snapshot visible rather than showing an error screen.
    var onSilentReloadFailed: () -> Unit = {}

    /**
     * JavaScript interface injected into the WebView so the player page's WebSocket
     * activity can be observed from Android.  The injected JS wraps window.WebSocket
     * and calls these methods on open/close/error.
     *
     * All methods are called on the JS thread — state updates are safe because
     * MutableStateFlow is thread-safe.
     */
    inner class WebSocketBridge {
        @JavascriptInterface
        fun onOpen(url: String) {
            Log.i("WebSocketBridge", "WebSocket connected: $url")
            FileLogger.log("WebSocket OPEN: $url")
            _isWebSocketConnected.value = true
        }

        @JavascriptInterface
        fun onClose(url: String, code: Int, reason: String) {
            Log.w("WebSocketBridge", "WebSocket closed: $url code=$code reason=$reason")
            FileLogger.log("WebSocket CLOSED: $url code=$code reason=$reason", "W")
            _isWebSocketConnected.value = false
        }

        @JavascriptInterface
        fun onError(url: String) {
            Log.e("WebSocketBridge", "WebSocket error: $url")
            FileLogger.log("WebSocket ERROR: $url", "E")
            _isWebSocketConnected.value = false
        }
    }

    /**
     * Provides device info to JavaScript so it can be included in the telemetry
     * message sent to the server after auth_success.
     */
    inner class DeviceInfoBridge {
        @JavascriptInterface
        fun getSoftwareVersion(): String = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }

        @JavascriptInterface
        fun getPlatform(): String = "Android ${Build.VERSION.RELEASE} (${Build.MODEL})"

        @JavascriptInterface
        fun getTimezone(): String = java.util.TimeZone.getDefault().id

        @JavascriptInterface
        fun getLocalIpAddress(): String = try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: ""
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun getHostname(): String =
            // User-visible device name (set in Settings → About → Device name)
            Settings.Global.getString(context.contentResolver, "device_name")
                ?: Build.MODEL

        @JavascriptInterface
        fun getMacAddress(): String = try {
            // MAC address is restricted on Android 6+ for non-system apps;
            // return empty string rather than the anonymised 02:00:00:00:00:00
            val wlan = NetworkInterface.getByName("wlan0")
            val bytes = wlan?.hardwareAddress
            if (bytes != null && bytes.size == 6) {
                bytes.joinToString(":") { "%02X".format(it) }
            } else ""
        } catch (e: Exception) { "" }
    }

    /** Injected after every page load to intercept WebSocket lifecycle events. */
    private val wsMonitorScript = """
        (function() {
            if (window._androidWsMonitor) return;
            window._androidWsMonitor = true;
            var NativeWS = window.WebSocket;
            window.WebSocket = function(url, protocols) {
                var ws = protocols ? new NativeWS(url, protocols) : new NativeWS(url);
                ws.addEventListener('open', function() {
                    try { AndroidWS.onOpen(url); } catch(e) {}
                });
                ws.addEventListener('close', function(e) {
                    try { AndroidWS.onClose(url, e.code || 0, e.reason || ''); } catch(e2) {}
                });
                ws.addEventListener('error', function() {
                    try { AndroidWS.onError(url); } catch(e) {}
                });
                ws.addEventListener('message', function(event) {
                    try {
                        var msg = JSON.parse(event.data);
                        if (msg.type === 'auth_success') {
                            // Send device telemetry immediately after authentication
                            var telemetry = {
                                type: 'telemetry',
                                data: {
                                    softwareVersion: AndroidDevice.getSoftwareVersion(),
                                    platform:        AndroidDevice.getPlatform(),
                                    timezone:        AndroidDevice.getTimezone(),
                                    localIpAddress:  AndroidDevice.getLocalIpAddress(),
                                    publicIpAddress: '',
                                    hostname:        AndroidDevice.getHostname(),
                                    macAddress:      AndroidDevice.getMacAddress()
                                }
                            };
                            ws.send(JSON.stringify(telemetry));
                        }
                    } catch(e) {}
                });
                return ws;
            };
            window.WebSocket.CONNECTING = NativeWS.CONNECTING;
            window.WebSocket.OPEN      = NativeWS.OPEN;
            window.WebSocket.CLOSING   = NativeWS.CLOSING;
            window.WebSocket.CLOSED    = NativeWS.CLOSED;
            window.WebSocket.prototype = NativeWS.prototype;
        })();
    """.trimIndent()

    /**
     * Returns true if the server hosting the player page can be resolved via DNS.
     * Called on a background thread (Dispatchers.IO) before each polling reload
     * so that we never disturb playing content when the server is unreachable.
     *
     * Uses a plain DNS lookup (no HTTP request) — fast and zero server load.
     * If DNS resolution fails the server is definitely unreachable; skip the reload.
     */
    fun isServerReachable(): Boolean {
        val url = lastLoadedUrl ?: return true  // no URL yet → assume reachable
        return try {
            val hostname = java.net.URL(url).host
            // getByName throws UnknownHostException when DNS fails (e.g. DDNS down)
            java.net.InetAddress.getByName(hostname)
            true
        } catch (e: Exception) {
            Log.w("WebViewManager", "Server DNS check failed — skipping poll: ${e.message}")
            false
        }
    }

    /**
     * Captures the current WebView frame as a bitmap so it can be shown as an
     * overlay while a silent reload happens behind it.
     */
    fun captureSnapshot(): Bitmap? {
        val webView = currentWebView ?: return null
        // Don't capture when WebView is hidden — it would produce a black bitmap
        // (e.g. after a previous 502 failure) and replace the last good snapshot with black.
        if (webView.visibility != View.VISIBLE) {
            Log.w("WebViewManager", "Snapshot capture skipped — WebView not visible, keeping existing snapshot")
            return null
        }
        return try {
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.w("WebViewManager", "Snapshot capture failed: ${e.message}")
            null
        }
    }

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
            if (WS_MONITOR_ENABLED) {
                addJavascriptInterface(WebSocketBridge(), "AndroidWS")
                addJavascriptInterface(DeviceInfoBridge(), "AndroidDevice")
            }
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
                    FileLogger.logRendererCrash()
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

    /**
     * Reload the current page, bypassing the cache so fresh playlist/content
     * changes from the server are always fetched.
     */
    fun reload() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            currentWebView?.let { webView ->
                Log.i("WebViewManager", "Polling reload — clearing cache and reloading (silent)")
                FileLogger.logPollingReload()
                isSilentReload = true
                _isWebSocketConnected.value = false  // will be restored once page reconnects
                webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webView.reload()
                webView.postDelayed({
                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                }, 3000)
                // Safety net: if onPageFinished never fires after the silent reload
                // (e.g. Vite dev mode, network blip), force the WebView visible after 30s
                // so the screen doesn't stay on a frozen snapshot or go white.
                // If the WebView is INVISIBLE at this point, it means onReceivedError already
                // hid it (network failure) — keep the snapshot up rather than revealing the
                // Chrome error page.
                webView.postDelayed({
                    if (isSilentReload) {
                        FileLogger.logSilentReloadTimeout()
                        isSilentReload = false
                        if (webView.visibility == View.INVISIBLE) {
                            // Network failure already handled — snapshot stays visible
                            Log.w("WebViewManager", "Silent reload timeout — WebView hidden (network failure), keeping snapshot")
                            onSilentReloadFailed()
                        } else {
                            webView.visibility = View.VISIBLE
                            onSilentReloadComplete()
                        }
                    }
                }, 30_000)
            }
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
                if (isSilentReload) {
                    // Keep content visible during background polling reloads —
                    // no black flash, no loading overlay.
                    Log.d("WebViewManager", "onPageStarted (silent): $url")
                } else {
                    view.visibility = View.INVISIBLE
                    onPageLoading(true)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                Log.d("WebViewManager", "onPageFinished: $url")
                // Inject WebSocket monitor so Android can track connection health.
                // The script is idempotent (_androidWsMonitor guard) so repeated
                // calls on silent reloads are safe.
                lastLoadedUrl = url
                if (WS_MONITOR_ENABLED) {
                    view.evaluateJavascript(wsMonitorScript, null)
                }
                if (silentReloadFailed) {
                    // onReceivedHttpError already handled this (e.g. 502 Bad Gateway during a
                    // polling reload). The WebView is INVISIBLE and the snapshot is still shown.
                    // Reset the flag and do nothing — do NOT reveal the error page.
                    silentReloadFailed = false
                    Log.d("WebViewManager", "onPageFinished after HTTP error during silent reload — ignoring")
                } else if (isSilentReload) {
                    isSilentReload = false
                    view.visibility = View.VISIBLE
                    Log.i("WebViewManager", "Silent reload complete — new content displayed")
                    FileLogger.logSilentReloadComplete()
                    onSilentReloadComplete()
                } else {
                    FileLogger.logPageLoaded(url ?: "")
                    view.postDelayed({
                        view.visibility = View.VISIBLE
                        onPageLoading(false)
                    }, 1000)
                }
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 23")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (isOfflineMode) {
                    Log.w("WebViewManager", "Offline cache miss (legacy): $failingUrl — keeping last content")
                } else if (isSilentReload) {
                    // Polling reload failed (e.g. server offline, DNS gone).
                    // Hide the WebView so Chrome's error page isn't visible behind the snapshot.
                    // Do NOT call onError() — the snapshot stays on screen until the next poll succeeds.
                    Log.w("WebViewManager", "Silent reload failed (legacy): $failingUrl code=$errorCode — keeping snapshot")
                    isSilentReload = false
                    view?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                    view?.visibility = View.INVISIBLE
                    onSilentReloadFailed()
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
                    } else if (isSilentReload) {
                        // Polling reload failed (e.g. server offline, DNS gone).
                        // Hide the WebView so Chrome's error page isn't visible behind the snapshot.
                        // Do NOT call onError() — the snapshot stays on screen until the next poll succeeds.
                        Log.w("WebViewManager", "Silent reload failed: ${request.url} code=${error.errorCode} — keeping snapshot")
                        isSilentReload = false
                        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                        view.visibility = View.INVISIBLE
                        onSilentReloadFailed()
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

            // Catches HTTP-level errors (4xx/5xx) — e.g. 502 Bad Gateway when the Node.js
            // backend is down but nginx is still running. Unlike onReceivedError (network
            // errors), this fires when the server responds with an error status code.
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame && isSilentReload) {
                    val status = errorResponse.statusCode
                    Log.w("WebViewManager", "Silent reload HTTP error $status: ${request.url} — keeping snapshot")
                    isSilentReload = false
                    silentReloadFailed = true   // tells onPageFinished not to reveal the error page
                    view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    view.visibility = View.INVISIBLE
                    onSilentReloadFailed()
                } else if (request.isForMainFrame) {
                    Log.w("WebViewManager", "HTTP error ${errorResponse.statusCode} on main frame: ${request.url}")
                    // Not a silent reload — log only, don't disrupt normal load/error flow
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