package com.sc.gtradio.common

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GTRadioLogger {
    private const val TAG = "GTRadioDiag"
    private val memoryLogs = ArrayDeque<String>(400)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun log(message: String, error: Throwable? = null) {
        val ts = fmt.format(Date())
        val fullMsg = if (error != null) {
            "$ts | $message | ERROR: ${error.javaClass.simpleName}: ${error.message}\n${Log.getStackTraceString(error)}"
        } else {
            "$ts | $message"
        }
        Log.d(TAG, fullMsg)
        if (memoryLogs.size >= 400) {
            memoryLogs.removeFirst()
        }
        memoryLogs.addLast(fullMsg)
        appendToDisk(fullMsg + "\n")
    }

    private fun appendToDisk(line: String) {
        try {
            appContext?.let { ctx ->
                val internalFile = File(ctx.filesDir, "gtradio_debug.log")
                if (internalFile.exists() && internalFile.length() > 256_000L) {
                    internalFile.writeText("--- Log rotated ---\n")
                }
                FileOutputStream(internalFile, true).use { it.write(line.toByteArray()) }
            }
        } catch (_: Exception) {}

        try {
            val pubDir = File("/sdcard/GTA SA RADIO PROJECT")
            if (pubDir.exists() && pubDir.canWrite()) {
                val pubFile = File(pubDir, "gtradio_debug.log")
                if (pubFile.exists() && pubFile.length() > 256_000L) {
                    pubFile.writeText("--- Log rotated ---\n")
                }
                FileOutputStream(pubFile, true).use { it.write(line.toByteArray()) }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun getLogsText(): String {
        if (memoryLogs.isEmpty()) return "No logs recorded yet."
        return memoryLogs.joinToString("\n")
    }

    @Synchronized
    fun clear(context: Context? = appContext) {
        memoryLogs.clear()
        try {
            context?.let { File(it.filesDir, "gtradio_debug.log").delete() }
            File("/sdcard/GTA SA RADIO PROJECT/gtradio_debug.log").delete()
        } catch (_: Exception) {}
        log("Logs cleared by user.")
    }
}
