package org.screenlite.webkiosk.components

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.screenlite.webkiosk.app.WebViewManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import org.screenlite.webkiosk.data.KioskSettingsFactory
import org.screenlite.webkiosk.data.Rotation

// Polling intervals:
//   WS connected    → 30 min safety-net reload (catches any edge-case WS drift)
//   WS disconnected → 5 min active fallback reload
private const val POLL_INTERVAL_WS_CONNECTED_MS    = 30 * 60 * 1000L
private const val POLL_INTERVAL_WS_DISCONNECTED_MS =  5 * 60 * 1000L

private const val TAG = "WebViewComponent"

@Composable
fun WebViewComponent(
    url: String,
    activity: Activity,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var hasLoadedPage by remember { mutableStateOf(false) }
    // Once true, never goes back to false — prevents the black error/loading overlay
    // from appearing after the first successful load (e.g. when server temporarily goes offline).
    var hadSuccessfulLoad by remember { mutableStateOf(false) }
    var snapshotBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var rotation: Rotation by remember { mutableStateOf(Rotation.ROTATION_0) }
    var retryCount by remember { mutableIntStateOf(0) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    // Track WebSocket health — drives polling interval
    val webViewManager = remember {
        WebViewManager(
            activity,
            onError = { err ->
                Log.e(TAG, "WebView error: $err")
                hasError = err
                if (err) {
                    hasLoadedPage = false
                }
            },
            onPageLoading = { loading ->
                isLoading = loading
                Log.d(TAG, "Page loading=$loading")
                if (!loading && !hasError) {
                    hasLoadedPage = true
                    hadSuccessfulLoad = true
                    Log.d(TAG, "Page loaded successfully")
                }
            }
        ).also { manager ->
            manager.onSilentReloadComplete = {
                snapshotBitmap = null
                Log.i(TAG, "Snapshot overlay removed — new content is live")
            }
            manager.onSilentReloadFailed = {
                // Network is down during a polling reload — keep the snapshot on screen.
                // Do NOT clear snapshotBitmap; do NOT set hasError.
                // The next polling cycle will try again automatically.
                Log.w(TAG, "Silent reload failed — keeping snapshot until server is reachable again")
            }
        }
    }
    val isWebSocketConnected by webViewManager.isWebSocketConnected.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val kioskSettings = KioskSettingsFactory.get(context)
        kioskSettings.getRotation().collect { newRotation ->
            Log.d(TAG, "Rotation updated: $newRotation")
            rotation = newRotation
            webViewManager.updateRotation(newRotation)
        }
    }

    // Polling: reload the player periodically as a fallback for missed WebSocket updates.
    // Interval adapts to WebSocket health:
    //   WS connected    → 30 min (safety net only — WS pushes changes in real time)
    //   WS disconnected → 5 min  (active fallback — catches any missed playlist changes)
    // To disable WS monitoring entirely and always use 5 min, set WS_MONITOR_ENABLED=false
    // in WebViewManager — no changes needed here.
    // Re-key on isWebSocketConnected so the timer resets immediately when WS drops,
    // rather than waiting out the remainder of a 30-min window.
    LaunchedEffect(hasLoadedPage, isWebSocketConnected) {
        if (hasLoadedPage) {
            while (true) {
                val pollInterval = if (isWebSocketConnected) {
                    Log.d(TAG, "WS connected — next poll in 30 min")
                    POLL_INTERVAL_WS_CONNECTED_MS
                } else {
                    Log.d(TAG, "WS disconnected — next poll in 5 min (fallback)")
                    POLL_INTERVAL_WS_DISCONNECTED_MS
                }
                delay(pollInterval)
                if (!hasError) {
                    // DNS check on IO thread — if the server can't be resolved (offline,
                    // DDNS down, etc.) we skip the reload entirely so the currently playing
                    // content is never disturbed.
                    val reachable = withContext(Dispatchers.IO) {
                        webViewManager.isServerReachable()
                    }
                    if (reachable) {
                        Log.i(TAG, "Polling reload — capturing snapshot and reloading silently")
                        snapshotBitmap = webViewManager.captureSnapshot()
                        webViewManager.reload()
                    } else {
                        Log.i(TAG, "Polling skipped — server unreachable, content continues playing")
                    }
                }
            }
        }
    }

    // Fallback: if onPageFinished hasn't fired within 20 seconds (e.g. Vite dev-mode
    // pages keep window.onload pending), force the WebView visible so content shows.
    LaunchedEffect(isLoading, hasLoadedPage) {
        if (isLoading && !hasLoadedPage) {
            delay(20_000)
            if (isLoading && !hasLoadedPage && !hasError) {
                Log.w(TAG, "Load timeout — forcing WebView visible")
                webViewManager.forceShow()
            }
        }
    }

    LaunchedEffect(hasError, retryTrigger) {
        // Only do the exponential-backoff WebView recreation if we have NEVER
        // successfully loaded a page. After a successful load, the polling loop
        // handles recovery — we must not recreate the WebView on every poll failure.
        if (hasError && !hasLoadedPage && !hadSuccessfulLoad) {
            retryCount++
            val delayTime = (1000L * (1 shl (retryCount - 1))).coerceAtMost(30_000L)
            Log.d(TAG, "Retry #$retryCount in ${delayTime}ms (trigger=$retryTrigger)")
            delay(delayTime)
            retryTrigger++
        } else if (!hasError) {
            if (retryCount > 0) Log.d(TAG, "Reset retry count (error cleared)")
            retryCount = 0
        }
    }

    DisposableEffect(Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            // ConnectivityManager callbacks fire on a background ConnectivityThread.
            // WebView methods and Compose state writes MUST run on the main thread —
            // failure to do so causes a fatal "WebView called on wrong thread" crash.
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    Log.d(TAG, "Network available — going online")
                    webViewManager.setOfflineMode(false)
                    if (hasError) {
                        hasError = false
                        retryTrigger++
                        Log.d(TAG, "Recovered from error, retryTrigger=$retryTrigger")
                    }
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    Log.d(TAG, "Network lost — switching to cache-only mode")
                    webViewManager.setOfflineMode(true)
                    // If we never successfully loaded a page, still show the retry UI
                    if (!hasLoadedPage) {
                        Log.e(TAG, "Connection lost before page loaded — will retry when network returns")
                        hasError = true
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            @Suppress("DEPRECATION")
            cm.registerNetworkCallback(networkRequest, callback)
        }

        onDispose {
            Log.d(TAG, "Unregistering network callback")
            cm.unregisterNetworkCallback(callback)
        }
    }
    // Explicit Box so the loading/error overlay is guaranteed to stack on top of the WebView.
    Box(modifier = modifier) {
        key(retryTrigger) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Log.d(TAG, "Creating WebView (rotation=$rotation)")
                    webViewManager.createWebView(rotation)
                },
                update = { webView ->
                    if (webView.url != url) {
                        Log.d(TAG, "Loading new URL: $url")
                        webView.loadUrl(url)
                    } else if (retryTrigger > 0 && !hasLoadedPage) {
                        Log.d(TAG, "Retry triggered, reloading WebView")
                        webView.reload()
                    }
                })
        }

        // Snapshot overlay: shown during silent reloads so the screen never goes blank.
        // The old frame stays visible until the new content is fully loaded.
        snapshotBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }

        // Only show the black overlay on initial load failures — never after a successful
        // load. Once the player has displayed content, keep it (or the snapshot) visible
        // even when the server is temporarily unreachable.
        when {
            hasError && !hadSuccessfulLoad -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Log.w(TAG, "Showing connection error UI")
                Text("Connection error\nRetrying...", color = Color.White)
            }

            isLoading && !hadSuccessfulLoad -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Log.d(TAG, "Showing loading UI")
                Text("Loading...", color = Color.White)
            }
        }
    }
}
