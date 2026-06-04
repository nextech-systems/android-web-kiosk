package org.screenlite.webkiosk.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes key kiosk events to a rolling log file on disk so they survive
 * logcat buffer rotation. Pull the log any time with:
 *   adb pull /sdcard/Android/data/org.screenlite.webkiosk/files/kiosk.log
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_SIZE_BYTES = 2 * 1024 * 1024L // 2 MB rolling cap
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.getExternalFilesDir(null), "kiosk.log")
        log("--- FileLogger started (file: ${logFile?.absolutePath}) ---")
    }

    fun log(message: String, level: String = "I") {
        val line = "${fmt.format(Date())} [$level] $message"
        Log.i(TAG, line)
        try {
            val file = logFile ?: return
            // Roll the file if it exceeds the size cap
            if (file.exists() && file.length() > MAX_SIZE_BYTES) {
                val backup = File(file.parent, "kiosk.log.bak")
                backup.delete()
                file.renameTo(backup)
            }
            FileWriter(file, true).use { it.write(line + "\n") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }

    fun logWhiteScreen(reason: String) = log("WHITE SCREEN: $reason", "E")
    fun logPageLoaded(url: String) = log("Page loaded: $url")
    fun logPollingReload() = log("Polling reload triggered")
    fun logSilentReloadComplete() = log("Silent reload complete")
    fun logSilentReloadTimeout() = log("Silent reload TIMEOUT — forcing visible", "E")
    fun logRendererCrash() = log("WebView renderer CRASH — recovering", "E")
    fun logNetworkChange(online: Boolean) = log("Network: ${if (online) "ONLINE" else "OFFLINE"}")
}
