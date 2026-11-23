package com.sc.gtradio.common

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Printer
import androidx.preference.PreferenceManager
import com.sc.gtradio.media.rpf3.Rpf3KeyManager
import com.sc.gtradio.media.rpf7.Rpf7KeyManager
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object GTRadioLogger {
    private const val TAG = "GTRadioDiag"
    private const val PREF_VERBOSE_LOGGING = "verbose_logging_enabled"
    private const val PREF_LAST_EXIT_TIMESTAMP = "last_logged_exit_timestamp"
    private const val RING_BUFFER_CAPACITY = 1200
    private const val ART_BUFFER_CAPACITY = 150
    private const val SESSION_LOG_FILE = "last_session.log"
    private const val MAX_SESSION_LOG_BYTES = 64 * 1024L
    private const val MAX_SESSION_LOG_LINES = 300

    private val crashHandlerInstalled = AtomicBoolean(false)

    private val memoryLogs = ArrayDeque<String>(RING_BUFFER_CAPACITY)
    private val artMemoryLogs = ArrayDeque<String>(ART_BUFFER_CAPACITY)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var appContext: Context? = null
    private var previousSessionTail: String = ""

    private val sessionLogExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastSessionFlushUptime = 0L
    @Volatile private var sessionFlushPending = false
    private val sessionFlushLock = Any()

    @Volatile
    private var isVerboseCached: Boolean? = null

    @Volatile
    private var isCheckingExitReasons = false

    private var stallWatchdogInstalled = false
    private var dispatchStartTime = 0L
    private var lastDispatchedMessage = ""

    private val mainThreadPrinter = Printer { x ->
        if (x.startsWith(">>>>>")) {
            dispatchStartTime = SystemClock.uptimeMillis()
            lastDispatchedMessage = x
        } else if (x.startsWith("<<<<<")) {
            val start = dispatchStartTime
            if (start > 0L) {
                val duration = SystemClock.uptimeMillis() - start
                if (duration > 250L) {
                    val targetInfo = lastDispatchedMessage.removePrefix(">>>>> Dispatching to ").take(160)
                    log("[ANR-STALL] Main thread dispatch exceeded threshold: ${duration}ms (target: $targetInfo)")
                }
                dispatchStartTime = 0L
            }
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx

        if (crashHandlerInstalled.compareAndSet(false, true)) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val ts = fmt.format(Date())
                    val trace = Log.getStackTraceString(throwable)
                    val line = "$ts | [FATAL-CRASH] Thread: ${thread.name}\n$trace\n"
                    val sessionFile = File(ctx.filesDir, SESSION_LOG_FILE)
                    FileOutputStream(sessionFile, true).use { fos ->
                        fos.write(line.toByteArray(Charsets.UTF_8))
                    }
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        loadPreviousSessionLog(ctx)
        try {
            isVerboseCached = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_VERBOSE_LOGGING, false)
        } catch (_: Exception) {}
        syncMainThreadWatchdog()
        checkHistoricalExitReasons(ctx)
    }

    private fun loadPreviousSessionLog(ctx: Context) {
        try {
            val sessionFile = File(ctx.filesDir, SESSION_LOG_FILE)
            if (sessionFile.exists() && sessionFile.length() > 0L) {
                previousSessionTail = sessionFile.readText(Charsets.UTF_8).trim()
            }
        } catch (_: Exception) {}
    }

    fun isVerboseEnabled(context: Context? = null): Boolean {
        val cached = isVerboseCached
        if (cached != null) return cached
        val ctx = context ?: appContext ?: return false
        return try {
            val enabled = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_VERBOSE_LOGGING, false)
            isVerboseCached = enabled
            enabled
        } catch (_: Exception) {
            false
        }
    }

    fun setVerboseEnabled(enabled: Boolean) {
        isVerboseCached = enabled
        syncMainThreadWatchdog()
        if (enabled) {
            appContext?.let { checkHistoricalExitReasons(it) }
        }
    }

    fun syncMainThreadWatchdog() {
        val mainLooper = Looper.getMainLooper() ?: return
        val enabled = isVerboseEnabled()
        val action = Runnable {
            if (enabled && !stallWatchdogInstalled) {
                mainLooper.setMessageLogging(mainThreadPrinter)
                stallWatchdogInstalled = true
            } else if (!enabled && stallWatchdogInstalled) {
                mainLooper.setMessageLogging(null)
                stallWatchdogInstalled = false
            }
        }
        if (Looper.myLooper() == mainLooper) {
            action.run()
        } else {
            Handler(mainLooper).post(action)
        }
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
        if (message.startsWith("[ART]")) {
            while (artMemoryLogs.size >= ART_BUFFER_CAPACITY) {
                artMemoryLogs.removeFirst()
            }
            artMemoryLogs.addLast(fullMsg)
        } else {
            while (memoryLogs.size >= RING_BUFFER_CAPACITY) {
                memoryLogs.removeFirst()
            }
            memoryLogs.addLast(fullMsg)
        }
        val lineToAppend = fullMsg + "\n"
        sessionLogExecutor.execute {
            appendToDisk(lineToAppend)
        }
        val isCritical = error != null ||
            message.contains("ERROR", ignoreCase = true) ||
            message.contains("CRASH", ignoreCase = true) ||
            message.contains("ANR", ignoreCase = true)
        scheduleSessionLogFlush(isCritical)
    }

    private fun scheduleSessionLogFlush(forceImmediate: Boolean) {
        val now = SystemClock.uptimeMillis()
        synchronized(sessionFlushLock) {
            if (forceImmediate) {
                lastSessionFlushUptime = now
                sessionFlushPending = false
                sessionLogExecutor.execute { flushSessionLogToDisk() }
            } else {
                if (now - lastSessionFlushUptime >= 3000L) {
                    lastSessionFlushUptime = now
                    sessionFlushPending = false
                    sessionLogExecutor.execute { flushSessionLogToDisk() }
                } else if (!sessionFlushPending) {
                    sessionFlushPending = true
                    val delay = 3000L - (now - lastSessionFlushUptime)
                    sessionLogExecutor.schedule({
                        synchronized(sessionFlushLock) {
                            sessionFlushPending = false
                            lastSessionFlushUptime = SystemClock.uptimeMillis()
                        }
                        flushSessionLogToDisk()
                    }, delay, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun flushSessionLogToDisk() {
        val ctx = appContext ?: return
        val linesSnapshot: List<String>
        synchronized(this) {
            val startIdx = (memoryLogs.size - MAX_SESSION_LOG_LINES).coerceAtLeast(0)
            linesSnapshot = memoryLogs.toList().drop(startIdx)
        }
        if (linesSnapshot.isEmpty()) return

        var content = linesSnapshot.joinToString("\n")
        var bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SESSION_LOG_BYTES) {
            val cut = (bytes.size - MAX_SESSION_LOG_BYTES).toInt()
            var start = cut
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) {
                start++
            }
            if (start < bytes.size - 1) start++
            content = String(bytes, start, bytes.size - start, Charsets.UTF_8)
        }

        try {
            val targetFile = File(ctx.filesDir, SESSION_LOG_FILE)
            val tempFile = File(ctx.filesDir, "$SESSION_LOG_FILE.tmp")
            FileOutputStream(tempFile, false).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            if (tempFile.exists()) {
                tempFile.renameTo(targetFile)
            }
        } catch (_: Exception) {}
    }

    fun logVerbose(category: String, message: String) {
        if (!isVerboseEnabled()) return
        log("[$category] $message")
    }

    inline fun logVerbose(category: String, supplier: () -> String) {
        if (!isVerboseEnabled()) return
        log("[$category] ${supplier()}")
    }

    fun e(message: String, error: Throwable? = null) {
        log("[ERROR] $message", error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        log("[$tag] [ERROR] $message", error)
    }

    fun w(message: String, error: Throwable? = null) {
        log("[WARN] $message", error)
    }

    fun i(message: String) {
        log("[INFO] $message")
    }

    fun d(message: String) {
        log("[DEBUG] $message")
    }

    fun checkHistoricalExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (isCheckingExitReasons) return
        isCheckingExitReasons = true

        val ctx = context.applicationContext ?: context
        Executors.newSingleThreadExecutor().submit {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@submit
                val exitReasons = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
                if (exitReasons.isNullOrEmpty()) return@submit

                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val lastLoggedTimestamp = prefs.getLong(PREF_LAST_EXIT_TIMESTAMP, 0L)
                var maxTimestamp = lastLoggedTimestamp

                for (exitInfo in exitReasons) {
                    val ts = exitInfo.timestamp
                    if (ts <= lastLoggedTimestamp) continue
                    if (ts > maxTimestamp) maxTimestamp = ts

                    val reason = exitInfo.reason
                    val isAnr = reason == ApplicationExitInfo.REASON_ANR
                    val isCrash = reason == ApplicationExitInfo.REASON_CRASH ||
                        reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        reason == ApplicationExitInfo.REASON_LOW_MEMORY

                    if (isAnr || isCrash) {
                        val tag = if (isAnr) "ANR" else "CRASH"
                        val reasonLabel = when (reason) {
                            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
                            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
                            ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
                            else -> "REASON_$reason"
                        }

                        val traceLines = ArrayList<String>()
                        try {
                            exitInfo.traceInputStream?.bufferedReader()?.use { reader ->
                                var capturingMain = false
                                val mainLines = ArrayList<String>()
                                val firstLines = ArrayList<String>()
                                for (line in reader.lineSequence()) {
                                    if (firstLines.size < 60) firstLines.add(line)
                                    if (line.contains("\"main\"") && (line.contains("prio=") || line.contains("tid="))) {
                                        capturingMain = true
                                    }
                                    if (capturingMain) {
                                        mainLines.add(line)
                                        if (mainLines.size >= 50 || (mainLines.size > 1 && line.isBlank())) {
                                            capturingMain = false
                                        }
                                    }
                                }
                                if (mainLines.isNotEmpty()) {
                                    traceLines.addAll(mainLines.take(50))
                                } else {
                                    traceLines.addAll(firstLines.take(50))
                                }
                            }
                        } catch (_: Exception) {}

                        val traceExcerpt = if (traceLines.isNotEmpty()) {
                            "\n--- Trace Excerpt ---\n" + traceLines.joinToString("\n")
                        } else {
                            " (no trace stream)"
                        }

                        log("[$tag] Historical process exit detected: $reasonLabel (status=${exitInfo.status}, desc=${exitInfo.description ?: "none"}, time=$ts)$traceExcerpt")
                    }
                }

                if (maxTimestamp > lastLoggedTimestamp) {
                    prefs.edit().putLong(PREF_LAST_EXIT_TIMESTAMP, maxTimestamp).apply()
                }
            } catch (_: Exception) {
            } finally {
                isCheckingExitReasons = false
            }
        }
    }

    private fun trimFileIfNeeded(file: File, maxBytes: Long = 256_000L, keepBytes: Long = 128_000L) {
        if (!file.exists() || file.length() <= maxBytes) return
        try {
            val totalLen = file.length()
            val readOffset = totalLen - keepBytes
            val tailBytes = ByteArray(keepBytes.toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(readOffset)
                raf.readFully(tailBytes)
            }
            var startIdx = 0
            while (startIdx < tailBytes.size && tailBytes[startIdx] != '\n'.code.toByte()) {
                startIdx++
            }
            if (startIdx < tailBytes.size - 1) {
                startIdx++
            }
            val trimmed = String(tailBytes, startIdx, tailBytes.size - startIdx, Charsets.UTF_8)
            file.writeText("--- Log rotated (retained recent ${trimmed.length / 1024} KB) ---\n$trimmed")
        } catch (_: Exception) {
            try {
                file.writeText("--- Log rotated ---\n")
            } catch (_: Exception) {}
        }
    }

    private fun appendToDisk(line: String) {
        try {
            appContext?.let { ctx ->
                val internalFile = File(ctx.filesDir, "gtradio_debug.log")
                trimFileIfNeeded(internalFile)
                FileOutputStream(internalFile, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            }
        } catch (_: Exception) {}

        try {
            val pubDir = File("/sdcard/GTA SA RADIO PROJECT")
            if (pubDir.exists() && pubDir.canWrite()) {
                val pubFile = File(pubDir, "gtradio_debug.log")
                trimFileIfNeeded(pubFile)
                FileOutputStream(pubFile, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun getLogsText(): String {
        if (memoryLogs.isEmpty()) return "No logs recorded yet."
        return memoryLogs.reversed().joinToString("\n")
    }

    private fun getDirSizeAndCount(dir: File): Pair<Long, Int> {
        if (!dir.exists() || !dir.isDirectory) return Pair(0L, 0)
        var totalBytes = 0L
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                totalBytes += file.length()
                count++
            }
        }
        return Pair(totalBytes, count)
    }

    private fun formatSize(bytes: Long): String {
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    fun buildDiagnosticReport(context: Context): String {
        val ctx = context.applicationContext ?: context
        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("      GTRADIO DIAGNOSTIC REPORT\n")
        sb.append("========================================\n")

        // App Version
        try {
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            sb.append("App Version: ${pInfo.versionName} (build $vCode)\n")
        } catch (_: Exception) {
            sb.append("App Version: Unknown\n")
        }

        // System / Hardware
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")

        // Diagnostics / Verbose Mode
        val verbose = isVerboseEnabled(ctx)
        sb.append("Verbose Logging: ${if (verbose) "ENABLED" else "DISABLED"}\n")

        // Dynamic Theme
        try {
            val themeEnabled = DynamicThemeManager.isDynamicThemeEnabled(ctx)
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val themeUri = prefs.getString("dynamic_theme_uri", null) ?: "Default (None selected)"
            sb.append("Dynamic Theme: ${if (themeEnabled) "ENABLED" else "DISABLED"}\n")
            sb.append("Dynamic Theme Path: $themeUri\n")
        } catch (_: Exception) {}

        // Game Keys Status (NEVER print key values!)
        try {
            val ivLabel = Rpf3KeyManager.getStatusLabel(ctx)
            sb.append("GTA IV / EFLC Keys: $ivLabel\n")

            val vSummary = Rpf7KeyManager.getKeyStatusSummary(ctx)
            sb.append("GTA V Keys: $vSummary\n")
        } catch (_: Exception) {}

        // Caches on Disk
        sb.append("--- Storage & Cache Usage ---\n")
        try {
            val artDir = File(ctx.filesDir, "era_album_art")
            val (artBytes, artCount) = getDirSizeAndCount(artDir)
            sb.append("Album Art (era_album_art): $artCount file(s), ${formatSize(artBytes)}\n")

            val at3Dir = File(ctx.cacheDir, "at3_tracks")
            val (at3Bytes, at3Count) = getDirSizeAndCount(at3Dir)
            sb.append("AT3 Audio (at3_tracks): $at3Count file(s), ${formatSize(at3Bytes)}\n")

            val gen2Dir = File(ctx.cacheDir, "gen2_stream_tracks")
            val (gen2Bytes, gen2Count) = getDirSizeAndCount(gen2Dir)
            sb.append("Gen2 Streams (gen2_stream_tracks): $gen2Count file(s), ${formatSize(gen2Bytes)}\n")

            val rpf3Dir = File(ctx.cacheDir, "rpf3_tracks")
            val (rpf3Bytes, rpf3Count) = getDirSizeAndCount(rpf3Dir)
            sb.append("RPF3 Audio (rpf3_tracks): $rpf3Count file(s), ${formatSize(rpf3Bytes)}\n")

            val rpf7Dir = File(ctx.cacheDir, "rpf7_tracks")
            val (rpf7Bytes, rpf7Count) = getDirSizeAndCount(rpf7Dir)
            sb.append("RPF7 Audio (rpf7_tracks): $rpf7Count file(s), ${formatSize(rpf7Bytes)}\n")
        } catch (_: Exception) {}

        val mainCount: Int
        val mainText: String
        val artCount: Int
        val artText: String
        synchronized(this) {
            mainCount = memoryLogs.size
            mainText = if (memoryLogs.isEmpty()) "No logs recorded yet." else memoryLogs.reversed().joinToString("\n")
            artCount = artMemoryLogs.size
            artText = if (artMemoryLogs.isEmpty()) "" else artMemoryLogs.reversed().joinToString("\n")
        }

        sb.append("========================================\n")
        sb.append("Diagnostic Event Log (newest first, $mainCount lines):\n")
        sb.append("========================================\n")
        sb.append(mainText)
        sb.append("\n")

        if (artCount > 0) {
            sb.append("\n========================================\n")
            sb.append("Recent Album Art Log (latest $artCount lines):\n")
            sb.append("========================================\n")
            sb.append(artText)
            sb.append("\n")
        }

        if (previousSessionTail.isNotBlank()) {
            sb.append("\n========================================\n")
            sb.append("=== Previous Session Log (Before Last Exit) ===\n")
            sb.append("========================================\n")
            sb.append(previousSessionTail)
            sb.append("\n")
        }

        return sb.toString()
    }

    fun getDiagnosticReport(context: Context? = appContext): String {
        val ctx = context ?: appContext ?: return "No context available"
        return buildDiagnosticReport(ctx)
    }

    @Synchronized
    fun clearLogs(context: Context? = appContext) {
        clear(context)
    }

    @Synchronized
    fun clear(context: Context? = appContext) {
        memoryLogs.clear()
        artMemoryLogs.clear()
        previousSessionTail = ""
        sessionLogExecutor.execute {
            try {
                context?.let {
                    File(it.filesDir, "gtradio_debug.log").delete()
                    File(it.filesDir, SESSION_LOG_FILE).delete()
                }
                File("/sdcard/GTA SA RADIO PROJECT/gtradio_debug.log").delete()
            } catch (_: Exception) {}
        }
        log("Logs cleared by user.")
    }
}
