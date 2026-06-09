package org.screenlite.webkiosk.app

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Downloads and serves playlist media files (videos, images) from local storage.
 *
 * Flow:
 *   1. JS reads the cached playlist from localStorage after auth_success and calls
 *      AndroidCache.onMediaUrls(json) with all media URLs.
 *   2. PlaylistCacheManager downloads each file to internal storage in the background,
 *      using the WebView's auth cookies so protected endpoints work.
 *   3. shouldInterceptRequest in WebViewManager checks this cache first — if the file
 *      is local, it's served directly even when the server is completely offline.
 *
 * Cache location : <filesDir>/media_cache/
 * Max cache size : MAX_CACHE_BYTES (default 2 GB) — oldest files evicted first.
 */
class PlaylistCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "PlaylistCache"
        private const val CACHE_DIR = "media_cache"
        private const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB
    }

    private val cacheDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
    // Thread pool — 2 parallel downloads at most so we don't saturate the network
    private val executor = Executors.newFixedThreadPool(2)
    // URLs currently being downloaded — prevents duplicate requests
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called from the AndroidCacheBridge JavascriptInterface.
     * [urlsJson] is a JSON array of fully-qualified media URLs.
     */
    fun onMediaUrls(urlsJson: String) {
        try {
            val arr = JSONArray(urlsJson)
            val count = arr.length()
            Log.i(TAG, "Received $count media URL(s) to pre-cache")
            for (i in 0 until count) {
                enqueueDownload(arr.getString(i))
            }
            evictIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "onMediaUrls parse error: ${e.message}")
        }
    }

    /**
     * Returns a WebResourceResponse serving the file from local cache,
     * or null if it isn't cached yet (WebView will fetch from network normally).
     *
     * Handles HTTP Range headers so video seeking works correctly.
     */
    fun serve(url: String, rangeHeader: String?): WebResourceResponse? {
        val file = cacheFile(url)
        if (!file.exists()) return null

        val mime = mimeFor(url)
        val size = file.length()

        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            serveRange(file, mime, size, rangeHeader)
        } else {
            val headers = mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to size.toString(),
                "Cache-Control" to "no-store"
            )
            Log.d(TAG, "Serving full: ${file.name} ($mime, ${size / 1024}KB)")
            WebResourceResponse(mime, "UTF-8", 200, "OK", headers, FileInputStream(file))
        }
    }

    fun isCached(url: String): Boolean = cacheFile(url).exists()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun enqueueDownload(url: String) {
        val file = cacheFile(url)
        if (file.exists()) {
            Log.d(TAG, "Already cached: ${file.name}")
            return
        }
        if (inFlight.putIfAbsent(url, true) != null) return  // already queued

        executor.execute {
            try {
                download(url, file)
                Log.i(TAG, "Cached ✓ ${file.name} (${file.length() / 1024}KB)")
            } catch (e: Exception) {
                Log.w(TAG, "Download failed [$url]: ${e.message}")
                file.delete()
            } finally {
                inFlight.remove(url)
            }
        }
    }

    private fun download(url: String, dest: File) {
        // Copy the WebView's auth cookies so protected endpoints accept our request
        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            setRequestProperty("Cookie", cookies)
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Accept", "*/*")
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            connect()
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP ${conn.responseCode}")
        }
        // Write to a temp file then rename — avoids serving a partial file if we crash mid-download
        val tmp = File(dest.parent, "${dest.name}.tmp")
        try {
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            }
            tmp.renameTo(dest)
        } finally {
            if (tmp.exists()) tmp.delete()
            conn.disconnect()
        }
    }

    private fun serveRange(file: File, mime: String, size: Long, rangeHeader: String): WebResourceResponse? {
        return try {
            // Parse "bytes=start-end"
            val spec = rangeHeader.removePrefix("bytes=")
            val dash = spec.indexOf('-')
            val start = if (dash > 0) spec.substring(0, dash).toLong() else 0L
            val end = if (dash < spec.length - 1) spec.substring(dash + 1).toLong() else size - 1L
            val length = end - start + 1

            val raf = RandomAccessFile(file, "r")
            raf.seek(start)

            val stream = object : java.io.InputStream() {
                private var remaining = length
                override fun read(): Int {
                    if (remaining <= 0) { raf.close(); return -1 }
                    remaining--
                    return raf.read()
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) { raf.close(); return -1 }
                    val toRead = minOf(len.toLong(), remaining).toInt()
                    val n = raf.read(b, off, toRead)
                    if (n > 0) remaining -= n
                    return n
                }
                override fun close() { try { raf.close() } catch (_: Exception) {} }
            }

            val headers = mapOf(
                "Content-Range"  to "bytes $start-$end/$size",
                "Accept-Ranges"  to "bytes",
                "Content-Length" to length.toString(),
                "Cache-Control"  to "no-store"
            )
            Log.d(TAG, "Serving range $start-$end of ${file.name}")
            WebResourceResponse(mime, "UTF-8", 206, "Partial Content", headers, stream)
        } catch (e: Exception) {
            Log.w(TAG, "Range serve error: ${e.message}")
            null
        }
    }

    /**
     * Delete oldest cached files until total cache size is below MAX_CACHE_BYTES.
     */
    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        val totalBytes = files.sumOf { it.length() }
        if (totalBytes <= MAX_CACHE_BYTES) return

        Log.i(TAG, "Cache size ${totalBytes / 1024 / 1024}MB — evicting oldest files")
        var freed = 0L
        val target = totalBytes - MAX_CACHE_BYTES
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (freed >= target) return
            freed += file.length()
            file.delete()
            Log.d(TAG, "Evicted ${file.name}")
        }
    }

    private fun cacheFile(url: String): File {
        // Stable filename: hash of the full URL + original extension for readability
        val hash = url.hashCode().toString(16)
        val ext = URL(url).path.substringAfterLast(".").lowercase().take(5)
        val safeName = if (ext.matches(Regex("[a-z0-9]+"))) "${hash}.$ext" else hash
        return File(cacheDir, safeName)
    }

    private fun mimeFor(url: String): String {
        val ext = URL(url).path.substringAfterLast(".").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
