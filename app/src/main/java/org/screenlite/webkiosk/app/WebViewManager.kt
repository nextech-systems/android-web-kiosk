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

    // Local media cache — downloads playlist files so they survive server outages
    val playlistCache = PlaylistCacheManager(context)

    // Page cache — saves the player HTML and JS/CSS bundles so the player can start offline
    private val pageCache = PageCacheManager(context.filesDir)

    // True while the WebView is loading the player from the local page cache (cold-start offline).
    // Prevents the normal error-handling path from firing again during loadDataWithBaseURL.
    private var isServingFromPageCache = false

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

    /**
     * Receives media URLs from the player JS so they can be pre-downloaded to local storage.
     * Also receives all page asset URLs (JS/CSS) reported by performance.getEntriesByType()
     * after the page finishes loading — used to keep the page cache warm for cold-start offline.
     */
    inner class AndroidCacheBridge {
        @JavascriptInterface
        fun onMediaUrls(urlsJson: String) {
            Log.i("AndroidCacheBridge", "Received media URLs for caching")
            playlistCache.onMediaUrls(urlsJson)
        }

        /**
         * Called from JS after a successful page load with all resource URLs that the
         * browser loaded (scripts, stylesheets, fonts).  We download and cache each one
         * so the player can start from local storage when the server is offline at boot.
         * Injected by the wsMonitorScript 8 seconds after onPageFinished.
         */
        @JavascriptInterface
        fun onPageAssets(urlsJson: String) {
            Log.i("AndroidCacheBridge", "Received page asset URLs for background caching")
            try {
                val arr = org.json.JSONArray(urlsJson)
                val urls = (0 until arr.length()).map { arr.getString(it) }
                val cookies = android.webkit.CookieManager.getInstance()
                    .getCookie(lastLoadedUrl ?: "") ?: ""
                pageCache.cacheAssets(urls, cookies)
            } catch (e: Exception) {
                Log.w("AndroidCacheBridge", "onPageAssets parse error: ${e.message}")
            }
        }
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

                            // Extract all media URLs from the cached playlist in localStorage
                            // and hand them to Android for background pre-download.
                            // This runs after every auth_success so the cache stays in sync
                            // whenever the dashboard makes a playlist change.
                            try {
                                var raw = localStorage.getItem('screenlite_cached_playlist');
                                if (raw && window.AndroidCache) {
                                    var mediaUrls = [];
                                    function collectUrls(obj) {
                                        if (!obj || typeof obj !== 'object') return;
                                        // Player uses: getFileUrl(item.file.path)
                                        // which expands to: origin + '/api/file-delivery/stream/' + path
                                        if (obj.file && typeof obj.file.path === 'string' && obj.file.path.length > 0) {
                                            mediaUrls.push(window.location.origin + '/api/file-delivery/stream/' + obj.file.path);
                                        }
                                        // Also catch bare path fields for other playlist shapes
                                        if (!obj.file && typeof obj.path === 'string' && obj.path.length > 0) {
                                            mediaUrls.push(window.location.origin + '/api/file-delivery/stream/' + obj.path);
                                        }
                                        var keys = Object.keys(obj);
                                        for (var i = 0; i < keys.length; i++) { collectUrls(obj[keys[i]]); }
                                    }
                                    if (Array.isArray(JSON.parse(raw))) {
                                        JSON.parse(raw).forEach(collectUrls);
                                    } else {
                                        collectUrls(JSON.parse(raw));
                                    }
                                    // Deduplicate
                                    var unique = mediaUrls.filter(function(u, i, a) { return a.indexOf(u) === i; });
                                    if (unique.length > 0) {
                                        AndroidCache.onMediaUrls(JSON.stringify(unique));
                                    }
                                }
                            } catch(cacheErr) {}
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
    /**
     * Returns true only when the backend is fully healthy and a polling reload is safe.
     *
     * Performs a real HTTP GET to /api/health rather than a DNS-only lookup.
     * This catches the "nginx up but Node.js backend down" case (502) — DNS resolves
     * fine but a reload would get a 502 and kill the currently-playing content.
     *
     * If /api/health returns HTTP 200 → backend is healthy → reload.
     * If it returns anything else (502, 503, timeout, DNS failure) → skip reload,
     * keep the WebView visible and the player running from the local media cache.
     */
    fun isServerReachable(): Boolean {
        val url = lastLoadedUrl ?: return true
        return try {
            val parsed = java.net.URL(url)
            val port = if (parsed.port != -1) ":${parsed.port}" else ""
            val healthUrl = "${parsed.protocol}://${parsed.host}$port/api/health"
            val conn = java.net.URL(healthUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout   = 5_000
            conn.requestMethod = "GET"
            conn.connect()
            val ok = conn.responseCode == 200
            conn.disconnect()
            if (!ok) Log.w("WebViewManager", "Health check returned ${conn.responseCode} — skipping poll")
            ok
        } catch (e: Exception) {
            Log.w("WebViewManager", "Health check failed — skipping poll: ${e.message}")
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
                addJavascriptInterface(AndroidCacheBridge(), "AndroidCache")
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
     * Kicks off two background operations after a successful page load:
     *  1. Immediately: fetch + cache the player HTML (so cold-boot recovery is always fresh).
     *  2. After 15 s and again after 45 s: inject JS to collect all loaded resource URLs
     *     (JS, CSS, fonts) and hand them to AndroidCache.onPageAssets() for background download.
     *     Two passes are needed because some routes (e.g. PlayerPage, auth API modules) are
     *     lazily imported only AFTER authentication completes, which can take > 8 seconds.
     *     cacheAssets() deduplicates, so running twice is safe.
     */
    private fun scheduleCacheRefresh(view: WebView, pageUrl: String?) {
        val url = pageUrl ?: return
        val cookies = android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
        pageCache.fetchAndCacheHtml(url, cookies)

        val collectScript = """
            (function() {
                try {
                    var urls = performance.getEntriesByType('resource')
                        .filter(function(e) { return e.name.indexOf('/api/') === -1; })
                        .map(function(e) { return e.name; });
                    if (window.AndroidCache) AndroidCache.onPageAssets(JSON.stringify(urls));
                } catch(e) {}
            })();
        """.trimIndent()

        // First pass — catches most static assets and early dynamic imports
        view.postDelayed({ view.evaluateJavascript(collectScript, null) }, 15_000)
        // Second pass — catches lazily-loaded route modules (PlayerPage, API clients, etc.)
        // that only load after authentication and routing complete
        view.postDelayed({ view.evaluateJavascript(collectScript, null) }, 45_000)
    }

    /**
     * Force the WebView visible and dismiss the loading overlay.
     * Called when onPageFinished hasn't fired within the timeout window —
     * e.g. Vite dev-mode pages that keep window.onload pending indefinitely.
     *
     * Must also clear isServingFromPageCache and isSilentReload: if onPageFinished
     * never fires, those flags stay set and corrupt the next polling reload cycle.
     */
    fun forceShow() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Log.w("WebViewManager", "forceShow() — onPageFinished did not fire in time")
            // Do NOT clear isServingFromPageCache here — scripts may still be loading
            // from the local cache after the initial onPageFinished fired.
            // It is cleared by the 10-second postDelayed scheduled in onPageFinished.
            isSilentReload = false
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
                // If the WebView is showing a data: URL (content served from page cache via
                // loadDataWithBaseURL), webView.reload() just re-serves the same cached bytes
                // and never contacts the server to check for recovery.
                // In that case load the original player URL explicitly.
                val currentUrl = webView.url
                if (currentUrl != null && currentUrl.startsWith("data:")) {
                    val targetUrl = lastLoadedUrl
                    Log.i("WebViewManager", "Polling reload — current URL is data:, reloading original: $targetUrl")
                    if (targetUrl != null) webView.loadUrl(targetUrl) else webView.reload()
                } else {
                    webView.reload()
                }
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
                // Do NOT store data: URLs — they come from loadDataWithBaseURL() serving
                // cached HTML and must not overwrite the real player URL, which is needed
                // by isServerReachable(), scheduleCacheRefresh(), and reload().
                if (!url.isNullOrEmpty() && !url.startsWith("data:")) {
                    lastLoadedUrl = url
                }
                if (WS_MONITOR_ENABLED) {
                    view.evaluateJavascript(wsMonitorScript, null)
                }
                if (silentReloadFailed) {
                    // onReceivedHttpError already handled this (e.g. 502 Bad Gateway during a
                    // polling reload). The WebView is INVISIBLE and the snapshot is still shown.
                    // Reset the flag and do nothing — do NOT reveal the error page.
                    silentReloadFailed = false
                    Log.d("WebViewManager", "onPageFinished after HTTP error during silent reload — ignoring")
                } else if (isServingFromPageCache) {
                    // We served the player HTML from local cache (cold-start or polling fallback).
                    //
                    // IMPORTANT: do NOT clear isServingFromPageCache here.
                    // onPageFinished for loadDataWithBaseURL fires when the HTML is parsed —
                    // BEFORE the browser fetches script/style subresources.  If we clear the
                    // flag now, shouldInterceptRequest won't serve those assets from cache and
                    // they will fail with net::ERR_FAILED (server is down).
                    // Schedule the clear for 10 s — by then all scripts will have loaded.
                    view.postDelayed({
                        if (isServingFromPageCache) {
                            isServingFromPageCache = false
                            Log.d("WebViewManager", "isServingFromPageCache cleared — script-serve window expired")
                        }
                    }, 10_000)

                    if (isSilentReload) {
                        // This was a polling reload that fell back to page cache because the
                        // server is still down.  Release the snapshot overlay so the cached
                        // player content is visible — but wait 2 s for React to paint first.
                        isSilentReload = false
                        view.visibility = View.VISIBLE
                        view.postDelayed({
                            onSilentReloadComplete()
                            Log.i("WebViewManager", "Snapshot overlay released — page cache served during silent reload")
                        }, 2000)
                    } else {
                        // Cold-start or initial load fell back to page cache.
                        view.postDelayed({
                            view.visibility = View.VISIBLE
                            onPageLoading(false)
                        }, 1000)
                    }
                    Log.i("WebViewManager", "Player loaded from page cache — running offline")
                } else if (isSilentReload) {
                    isSilentReload = false
                    view.visibility = View.VISIBLE
                    Log.i("WebViewManager", "Silent reload complete — revealing new content shortly")
                    FileLogger.logSilentReloadComplete()
                    // Wait 2 s before removing the snapshot overlay.
                    // onPageFinished fires when the HTML + JS are parsed, but the React app still
                    // needs a render cycle before it paints a frame.  Without this delay the
                    // snapshot disappears and the WebView shows a blank surface briefly.
                    view.postDelayed({
                        onSilentReloadComplete()
                        Log.i("WebViewManager", "Snapshot overlay released — new content displayed")
                    }, 2000)
                    // Refresh the page cache so tonight's assets match what's currently deployed
                    scheduleCacheRefresh(view, url)
                } else {
                    FileLogger.logPageLoaded(url ?: "")
                    view.postDelayed({
                        view.visibility = View.VISIBLE
                        onPageLoading(false)
                    }, 1000)
                    // Warm the page cache for cold-start offline capability
                    scheduleCacheRefresh(view, url)
                }
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 23")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (isOfflineMode) {
                    Log.w("WebViewManager", "Offline cache miss (legacy): $failingUrl — keeping last content")
                } else if (isSilentReload) {
                    // Polling reload failed (e.g. server offline, DNS gone).
                    Log.w("WebViewManager", "Silent reload failed (legacy): $failingUrl code=$errorCode — keeping snapshot")
                    isSilentReload = false
                    view?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                    view?.visibility = View.INVISIBLE
                    onSilentReloadFailed()
                } else if (!isServingFromPageCache) {
                    // Initial load failed — try the page cache before showing the error overlay
                    Log.e("WebViewManager", "Legacy page failed: $failingUrl, code=$errorCode, desc=$description")
                    val cached = pageCache.getCachedHtml(failingUrl ?: "")
                    if (cached != null && view != null) {
                        Log.i("WebViewManager", "Cold-start offline — serving player from page cache (legacy)")
                        isServingFromPageCache = true
                        val (bytes, _) = cached
                        view.loadDataWithBaseURL(failingUrl, String(bytes), "text/html", "UTF-8", null)
                    } else {
                        onPageLoading(false)
                        onError(true)
                    }
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
                        Log.w("WebViewManager", "Silent reload failed: ${request.url} code=${error.errorCode} — keeping snapshot")
                        isSilentReload = false
                        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                        view.visibility = View.INVISIBLE
                        onSilentReloadFailed()
                    } else if (!isServingFromPageCache) {
                        // Initial load failed — try the page cache before showing the error overlay
                        Log.e("WebViewManager", "Main page failed: ${request.url}, code=${error.errorCode}, desc=${error.description}")
                        val failingUrl = request.url.toString()
                        val cached = pageCache.getCachedHtml(failingUrl)
                        if (cached != null) {
                            Log.i("WebViewManager", "Cold-start offline — serving player from page cache")
                            isServingFromPageCache = true
                            val (bytes, contentType) = cached
                            view.loadDataWithBaseURL(failingUrl, String(bytes), "text/html", "UTF-8", null)
                        } else {
                            onPageLoading(false)
                            onError(true)
                        }
                    }
                } else if (!isServingFromPageCache) {
                    // Subresource failures during normal operation are expected when the server
                    // goes down mid-session — just log them; the player handles this gracefully.
                    Log.w("WebViewManager", "Subresource failed: ${request.url}, code=${error.errorCode}, desc=${error.description}")
                }
            }

            // Intercept requests and serve from local cache when available.
            // Two caches work in tandem:
            //   PlaylistCacheManager — serves media files (/api/file-delivery/stream/*)
            //   PageCacheManager     — serves JS/CSS/font assets when loading from cached HTML
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                // ── Media files ────────────────────────────────────────────────
                if (url.contains("/api/file-delivery/stream/")) {
                    val rangeHeader = request.requestHeaders["Range"]
                    val response = playlistCache.serve(url, rangeHeader)
                    if (response != null) {
                        Log.d("WebViewManager", "Cache hit (media): $url")
                        return response
                    }
                    Log.d("WebViewManager", "Cache miss (media — serving live): $url")
                    return super.shouldInterceptRequest(view, request)
                }

                // ── Page assets (JS, CSS, fonts) ───────────────────────────────
                // Only intercept subresource requests when loading from the offline page cache
                // (isServingFromPageCache = true, i.e. cold-start with server down).
                // During normal live polling reloads we let the WebView fetch assets from the
                // network as usual — intercepting them would make the page load faster but
                // onPageFinished would then fire before React renders, causing a black flash
                // when the snapshot overlay is removed.
                if (request.method == "GET" && isServingFromPageCache) {
                    // data: URLs are inline content — not network requests, skip silently
                    if (url.startsWith("data:")) return null
                    val response = pageCache.serveAsset(url)
                    if (response != null) {
                        Log.d("WebViewManager", "Cache hit (asset): $url")
                        return response
                    }
                    // Asset not cached yet — the request will fail (server is down) but the
                    // player will continue working from whatever assets ARE in cache.
                    Log.w("WebViewManager", "Cache miss during offline serve: $url")
                }

                return super.shouldInterceptRequest(view, request)
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
                } else if (request.isForMainFrame && !isServingFromPageCache) {
                    val status = errorResponse.statusCode
                    Log.w("WebViewManager", "HTTP error $status on main frame: ${request.url}")
                    if (status >= 500) {
                        // Server error on initial (non-silent) load — e.g. app restarted
                        // while the backend is down (nginx returns 502).
                        // Try the page cache first; fall back to the retry loop.
                        val failingUrl = request.url.toString()
                        val cached = pageCache.getCachedHtml(failingUrl)
                        if (cached != null) {
                            Log.i("WebViewManager", "Server error $status — serving player from page cache")
                            isServingFromPageCache = true
                            val (bytes, contentType) = cached
                            view.loadDataWithBaseURL(failingUrl, String(bytes), "text/html", "UTF-8", null)
                        } else {
                            onPageLoading(false)
                            onError(true)
                        }
                    }
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