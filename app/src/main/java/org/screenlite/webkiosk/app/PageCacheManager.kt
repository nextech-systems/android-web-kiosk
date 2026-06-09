package org.screenlite.webkiosk.app

import android.util.Log
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Caches the player page HTML and all its static assets (JS, CSS, fonts, icons) to local
 * storage so the player can start from cache on cold boot when the server is offline.
 *
 * STRATEGY
 * While server is UP:
 *   - After every successful page load, fetchAndCacheHtml saves the player HTML to disk.
 *   - After JS executes (~8s), cacheAssets downloads all loaded JS/CSS/font URLs in background.
 *   - shouldInterceptRequest serves JS/CSS/fonts from cache when available (cache-first).
 *
 * Cold start with server DOWN:
 *   - WebViewManager.onReceivedError / onReceivedHttpError calls getCachedHtml.
 *   - If a cached copy exists, WebViewManager calls loadDataWithBaseURL with the cached HTML.
 *   - The WebView then requests script/style/font subresources.
 *   - shouldInterceptRequest calls serveAsset which serves them from local cache.
 *   - Media files are served by PlaylistCacheManager -- the player plays the full playlist.
 *
 * When server comes back:
 *   - Polling loop detects health, silent reload, fresh content, cache updated.
 *
 * WHAT IS CACHED
 *   Player HTML page  -- by path (query params like ?token= are stripped from cache key)
 *   JS/MJS bundles    -- by full URL (content-addressed hashes are fine)
 *   CSS files, fonts (woff, woff2, ttf), static icons and images in HTML
 *
 * WHAT IS NOT CACHED HERE
 *   /api/...                    -- dynamic data (auth, playlist, telemetry)
 *   /api/file-delivery/stream/{id} -- media files (PlaylistCacheManager owns these)
 *   WebSocket frames            -- not intercepted by shouldInterceptRequest
 *
 * Cache location: filesDir/page_cache/ -- persists across reboots, cleared only by
 * app uninstall or Clear App Data.
 */
class PageCacheManager(cacheRoot: File) {

    companion object {
        private const val TAG = "PageCache"

        // File extensions we cache. Covers everything a React/Vite SPA needs to run offline.
        private val CACHEABLE_ASSET_EXTENSIONS = setOf(
            "js", "mjs", "css",
            "woff", "woff2", "ttf", "otf", "eot",
            "svg", "ico", "png", "jpg", "jpeg", "webp", "gif"
        )
    }

    private val cacheDir = File(cacheRoot, "page_cache").also { it.mkdirs() }

    // Background executor -- 2 threads so we can download multiple assets in parallel
    private val executor = Executors.newFixedThreadPool(2)
    // Guards against queuing the same URL more than once
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    // -----------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------

    /**
     * Background-fetch the player page HTML and save it to local cache.
     * Safe to call from the main thread -- runs entirely on a background thread.
     * pageUrl is the full URL including token query param.
     * cookies is the raw Cookie header string from CookieManager.
     */
    fun fetchAndCacheHtml(pageUrl: String, cookies: String) {
        executor.execute {
            try {
                val conn = openGet(pageUrl, cookies)
                val status = conn.responseCode
                if (status == 200) {
                    val contentType = conn.contentType ?: "text/html"
                    val bytes = conn.inputStream.readBytes()
                    conn.disconnect()
                    writeFile(htmlBodyFile(pageUrl), bytes)
                    writeFile(htmlTypeFile(pageUrl), contentType.toByteArray())
                    Log.i(TAG, "HTML cached: ${short(pageUrl)} (${bytes.size / 1024} KB)")
                } else {
                    conn.disconnect()
                    Log.w(TAG, "HTML cache skipped -- server returned $status for ${short(pageUrl)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTML cache failed for ${short(pageUrl)}: ${e.message}")
            }
        }
    }

    /**
     * Returns the cached HTML content for a player page URL, matched by path only
     * (the ?token=... query param is ignored so the cache survives token rotations).
     * Returns null if no cached copy exists.
     */
    fun getCachedHtml(pageUrl: String): Pair<ByteArray, String>? {
        val body = htmlBodyFile(pageUrl)
        val type = htmlTypeFile(pageUrl)
        if (!body.exists()) {
            Log.d(TAG, "No cached HTML for ${short(pageUrl)}")
            return null
        }
        val contentType = if (type.exists()) type.readText() else "text/html"
        Log.i(TAG, "Cache hit (HTML): ${short(pageUrl)} (${body.length() / 1024} KB)")
        return Pair(body.readBytes(), contentType)
    }

    /**
     * Enqueue background download of a list of asset URLs (JS, CSS, fonts, etc.).
     * Already-cached URLs are skipped. In-flight downloads are deduplicated.
     * Called from the JavascriptInterface after JS reports all loaded resource URLs.
     */
    fun cacheAssets(urls: List<String>, cookies: String) {
        var queued = 0
        for (url in urls) {
            if (!isAssetUrl(url)) continue
            if (assetBodyFile(url).exists()) continue
            if (inFlight.putIfAbsent(url, true) != null) continue
            queued++
            executor.execute {
                try {
                    downloadAsset(url, cookies)
                } finally {
                    inFlight.remove(url)
                }
            }
        }
        if (queued > 0) Log.i(TAG, "Queued $queued asset(s) for background caching")
    }

    /**
     * Returns a cached WebResourceResponse for a JS/CSS/font asset URL, or null if the
     * asset is not in cache (WebView will fetch it from the network normally).
     *
     * HTML pages always return null -- they are served via loadDataWithBaseURL by
     * WebViewManager, not through shouldInterceptRequest.
     */
    fun serveAsset(url: String): WebResourceResponse? {
        if (!isAssetUrl(url)) return null
        val body = assetBodyFile(url)
        if (!body.exists()) return null
        val type = assetTypeFile(url)
        val contentType = if (type.exists()) type.readText().split(";").first().trim()
                          else inferMimeType(url)
        Log.d(TAG, "Serving asset from cache: ${short(url)}")
        return WebResourceResponse(
            contentType,
            "UTF-8",
            200, "OK",
            mapOf("Cache-Control" to "no-store"),
            FileInputStream(body)
        )
    }

    // -----------------------------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------------------------

    private fun downloadAsset(url: String, cookies: String) {
        try {
            val conn = openGet(url, cookies)
            val status = conn.responseCode
            if (status == 200) {
                val contentType = conn.contentType ?: inferMimeType(url)
                val bytes = conn.inputStream.readBytes()
                conn.disconnect()
                writeFile(assetBodyFile(url), bytes)
                writeFile(assetTypeFile(url), contentType.toByteArray())
                Log.d(TAG, "Asset cached: ${short(url)} (${bytes.size / 1024} KB)")
            } else {
                conn.disconnect()
                Log.w(TAG, "Asset skip -- HTTP $status: ${short(url)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset download failed for ${short(url)}: ${e.message}")
        }
    }

    private fun openGet(url: String, cookies: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (cookies.isNotBlank()) conn.setRequestProperty("Cookie", cookies)
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 30_000
        conn.instanceFollowRedirects = true
        conn.connect()
        return conn
    }

    private fun writeFile(file: File, bytes: ByteArray) {
        val tmp = File(file.parent, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }

    private fun isAssetUrl(url: String): Boolean {
        if (url.contains("/api/")) return false
        if (url.contains("hot-update")) return false
        val path = try {
            URL(url).path
        } catch (e: Exception) {
            return false
        }
        val ext = path.substringAfterLast(".").lowercase().take(10)
        return ext in CACHEABLE_ASSET_EXTENSIONS
    }

    // Cache key for player HTML: use path only (no query string) so that token rotations
    // and re-deploys don't leave orphaned entries or cause cache misses.
    private fun htmlKey(url: String): String {
        val path = try {
            URL(url).path
        } catch (e: Exception) {
            url
        }
        return "html_${path.hashCode().toString(16)}"
    }

    // Cache key for assets: full URL hash (content-addressed JS bundles are fine).
    private fun assetKey(url: String): String {
        val hint = try {
            URL(url).path.substringAfterLast("/").take(24)
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        } catch (e: Exception) {
            ""
        }
        return "asset_${url.hashCode().toString(16)}_$hint"
    }

    private fun htmlBodyFile(url: String) = File(cacheDir, "${htmlKey(url)}.body")
    private fun htmlTypeFile(url: String) = File(cacheDir, "${htmlKey(url)}.type")
    private fun assetBodyFile(url: String) = File(cacheDir, "${assetKey(url)}.body")
    private fun assetTypeFile(url: String) = File(cacheDir, "${assetKey(url)}.type")

    private fun inferMimeType(url: String): String {
        val ext = try {
            URL(url).path.substringAfterLast(".").lowercase()
        } catch (e: Exception) {
            ""
        }
        return when (ext) {
            "js", "mjs" -> "application/javascript"
            "css"       -> "text/css"
            "woff"      -> "font/woff"
            "woff2"     -> "font/woff2"
            "ttf"       -> "font/ttf"
            "otf"       -> "font/otf"
            "svg"       -> "image/svg+xml"
            "png"       -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp"      -> "image/webp"
            "gif"       -> "image/gif"
            "ico"       -> "image/x-icon"
            else        -> "application/octet-stream"
        }
    }

    private fun short(url: String) =
        url.substringAfter("//").substringAfter("/").take(55)
}
