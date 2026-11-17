package com.sc.gtradio.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.FileUtils.listSimpleFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Random

data class DynamicThemePack(
    val seriesName: String,
    val headerTitle: String,
    val bgIntervalMs: Long,
    val bgFadeMs: Long,
    val headerColor: Int,
    val labelColor: Int,
    val activeTextColor: Int,
    val volumeBarColor: Int,
    val headerTypeface: Typeface?,
    val bodyTypeface: Typeface?,
    val groupLogoUri: Uri?,
    val portraitBgUris: List<Uri>,
    val landscapeBgUris: List<Uri>,
    val stationIconUris: Map<String, Uri>,
    val noiseSoundUris: List<Uri>,
    val selectSoundUris: List<Uri>
)

object DynamicThemeManager {
    private val packCache = HashMap<String, DynamicThemePack>()
    private var lastScannedRootUri: String = ""
    private var noisePlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rng = Random()
    private var isNoiseLooping = false
    private var activeNoiseList: List<Uri> = emptyList()

    private val stationKeyAliases = mapOf(
        "BOUNCE" to "BOUNCEFM",
        "BOUNCEFM" to "BOUNCEFM",
        "CSR" to "CSR",
        "CSR1039" to "CSR",
        "KDST" to "KDST",
        "KJAH" to "KJAH",
        "KJAHWEST" to "KJAH",
        "KROSE" to "KROSE",
        "MASTERSOUNDS" to "MASTERSOUNDS",
        "MASTERSOUNDS983" to "MASTERSOUNDS",
        "PLAYBACK" to "PLAYBACKFM",
        "PLAYBACKFM" to "PLAYBACKFM",
        "RADIOX" to "RADIOX",
        "RLS" to "RADIOLOSSANTOS",
        "RADIOLOSSANTOS" to "RADIOLOSSANTOS",
        "SFUR" to "SFUR",
        "WCTR" to "WCTR"
    )

    fun normalizeStationKey(name: String): String {
        val raw = name.uppercase()
            .removeSuffix(".PNG")
            .removeSuffix(".JPG")
            .removeSuffix("_ICON")
            .removePrefix("RADIO_")
            .replace(Regex("[^A-Z0-9]"), "")
        return stationKeyAliases[raw] ?: raw
    }

    fun isDynamicThemeEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("enable_dynamic_theme_ui", true)
    }

    @Synchronized
    fun findNoisePackForSeries(context: Context, seriesName: String?): DynamicThemePack? {
        if (seriesName.isNullOrBlank()) return null
        val exact = findThemeForSeries(context, seriesName)
        return if (exact != null && exact.noiseSoundUris.isNotEmpty()) exact else null
    }

    fun findThemeForSeries(context: Context, seriesGroupName: String): DynamicThemePack? {
        if (!isDynamicThemeEnabled(context) || seriesGroupName.isBlank()) return null
        ensureThemesScanned(context)

        val normTarget = seriesGroupName.uppercase()
        for ((key, pack) in packCache) {
            if (normTarget.contains(key) || key.contains(normTarget) ||
                (normTarget.contains("SAN ANDREAS") && key.contains("SAN ANDREAS"))
            ) {
                return pack
            }
        }
        return null
    }

    @Synchronized
    fun forceRescan(context: Context) {
        lastScannedRootUri = ""
        packCache.clear()
        ensureThemesScanned(context)
    }

    @Synchronized
    fun ensureThemesScanned(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val themeUriStr = prefs.getString("dynamic_theme_uri", "") ?: ""
        val libUriStr = prefs.getString("radio_folders_uri", "") ?: ""
        val combinedKey = "$themeUriStr|$libUriStr"
        if (combinedKey == lastScannedRootUri && packCache.isNotEmpty()) return
        lastScannedRootUri = combinedKey
        packCache.clear()

        // 1. Check explicit Dynamic Theme URI if user selected one in Settings
        if (themeUriStr.isNotBlank()) {
            try {
                val doc = DocumentFile.fromTreeUri(context, Uri.parse(themeUriStr))
                if (doc != null && doc.isDirectory) {
                    scanThemeRootDoc(context, doc)
                }
            } catch (e: Exception) {
                GTRadioLogger.log("DynamicThemeManager: Error scanning explicit theme URI", e)
            }
        }

        // 2. Check inside Library URI for a "Dynamic Theme" folder
        if (packCache.isEmpty() && libUriStr.isNotBlank()) {
            try {
                val libDoc = DocumentFile.fromTreeUri(context, Uri.parse(libUriStr))
                if (libDoc != null && libDoc.isDirectory) {
                    val dtDoc = findDynamicThemeFolder(libDoc, depth = 0)
                    if (dtDoc != null) {
                        scanThemeRootDoc(context, dtDoc)
                    }
                }
            } catch (e: Exception) {
                GTRadioLogger.log("DynamicThemeManager: Error scanning library URI for Dynamic Theme", e)
            }
        }

        // 3. Fallback: check standard direct sdcard paths if accessible
        if (packCache.isEmpty()) {
            val candidates = listOf(
                File("/sdcard/GTRadio/Dynamic Theme"),
                File("/sdcard/GTA SA RADIO PROJECT/GTRadio/Dynamic Theme")
            )
            for (dir in candidates) {
                if (dir.exists() && dir.isDirectory) {
                    val doc = DocumentFile.fromFile(dir)
                    scanThemeRootDoc(context, doc)
                    if (packCache.isNotEmpty()) break
                }
            }
        }
    }

    private fun findDynamicThemeFolder(dir: DocumentFile, depth: Int): DocumentFile? {
        if (depth > 2) return null
        val name = (dir.name ?: "").trim()
        if (name.equals("Dynamic Theme", ignoreCase = true)) return dir
        val subDirs = try { dir.listFiles().filter { it.isDirectory } } catch (_: Exception) { emptyList() }
        for (sub in subDirs) {
            if ((sub.name ?: "").trim().equals("Dynamic Theme", ignoreCase = true)) return sub
        }
        for (sub in subDirs) {
            val found = findDynamicThemeFolder(sub, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun scanThemeRootDoc(context: Context, dtRoot: DocumentFile) {
        val children = try { dtRoot.listFiles().filter { it.isDirectory } } catch (_: Exception) { emptyList() }
        // If user selected the series folder directly (e.g. "GTA San Andreas"), load it directly
        val hasDirectAssets = children.any {
            val n = (it.name ?: "").lowercase()
            n == "background" || n == "font" || n == "icon" || n == "sounds"
        }
        if (hasDirectAssets) {
            loadSeriesThemePack(context, dtRoot)?.let { packCache[it.seriesName.uppercase()] = it }
            return
        }

        for (seriesDir in children) {
            loadSeriesThemePack(context, seriesDir)?.let { pack ->
                packCache[pack.seriesName.uppercase()] = pack
                GTRadioLogger.log("DynamicThemeManager: Loaded theme pack '${pack.seriesName}' (icons=${pack.stationIconUris.size}, bgP=${pack.portraitBgUris.size}, sounds=${pack.noiseSoundUris.size})")
            }
        }
    }

    private fun loadSeriesThemePack(context: Context, seriesDir: DocumentFile): DynamicThemePack? {
        val folderName = seriesDir.name ?: return null
        val files = try { seriesDir.listSimpleFiles(context) } catch (_: Exception) { emptyArray() }

        var headerTitle = if (folderName.uppercase().contains("SAN ANDREAS")) "San Andreas Radio" else "$folderName Radio"
        var bgIntervalMs = 8000L
        var bgFadeMs = 1000L
        var headerColor = Color.parseColor("#D0E2F2")
        var labelColor = Color.parseColor("#6E849B")
        var activeTextColor = Color.parseColor("#B8CFE6")
        var volumeBarColor = Color.parseColor("#B8CFE6")
        var headerFontName = "diploma.ttf"
        var bodyFontName = "BankGothic Bold.ttf"

        // Read or auto-create theme.json
        val configInfo = files.find { !it.isDirectory && it.name.equals("theme.json", ignoreCase = true) }
        if (configInfo != null) {
            try {
                val jsonTxt = context.contentResolver.openInputStream(configInfo.uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (jsonTxt.isNotBlank()) {
                    val obj = JSONObject(jsonTxt)
                    headerTitle = obj.optString("headerTitle", headerTitle)
                    bgIntervalMs = (obj.optLong("backgroundIntervalSeconds", 8L) * 1000L).coerceAtLeast(3000L)
                    bgFadeMs = obj.optLong("backgroundFadeMs", 1000L).coerceAtLeast(200L)
                    headerFontName = obj.optString("headerFont", headerFontName)
                    bodyFontName = obj.optString("bodyFont", bodyFontName)
                    headerColor = runCatching { Color.parseColor(obj.optString("headerColor", "#D0E2F2")) }.getOrDefault(headerColor)
                    labelColor = runCatching { Color.parseColor(obj.optString("labelColor", "#6E849B")) }.getOrDefault(labelColor)
                    activeTextColor = runCatching { Color.parseColor(obj.optString("activeTextColor", "#B8CFE6")) }.getOrDefault(activeTextColor)
                    volumeBarColor = runCatching { Color.parseColor(obj.optString("volumeBarColor", "#B8CFE6")) }.getOrDefault(volumeBarColor)
                }
            } catch (e: Exception) {
                GTRadioLogger.log("DynamicThemeManager: Failed parsing theme.json in $folderName", e)
            }
        } else {
            // Try to auto-generate default theme.json for user convenience
            try {
                val newDoc = seriesDir.createFile("application/json", "theme.json")
                if (newDoc != null) {
                    val defaultJson = JSONObject().apply {
                        put("seriesName", folderName)
                        put("headerTitle", headerTitle)
                        put("backgroundIntervalSeconds", 8)
                        put("backgroundFadeMs", 1000)
                        put("headerFont", "diploma.ttf")
                        put("bodyFont", "BankGothic Bold.ttf")
                        put("headerColor", "#D0E2F2")
                        put("labelColor", "#6E849B")
                        put("activeTextColor", "#B8CFE6")
                        put("volumeBarColor", "#B8CFE6")
                    }
                    context.contentResolver.openOutputStream(newDoc.uri)?.use {
                        it.write(defaultJson.toString(2).toByteArray())
                    }
                }
            } catch (_: Exception) {}
        }

        val groupLogoUri = files.find { !it.isDirectory && it.name.lowercase().startsWith("logo.") }?.uri

        var headerTf: Typeface? = null
        var bodyTf: Typeface? = null
        val portraitBgs = ArrayList<Uri>()
        val landscapeBgs = ArrayList<Uri>()
        val stationIcons = HashMap<String, Uri>()
        val noiseSounds = ArrayList<Uri>()
        val selectSounds = ArrayList<Uri>()

        val subDirs = try { seriesDir.listFiles().filter { it.isDirectory } } catch (_: Exception) { emptyList() }
        for (sub in subDirs) {
            when ((sub.name ?: "").lowercase().trim()) {
                "font", "fonts" -> {
                    val fontFiles = try { sub.listSimpleFiles(context) } catch (_: Exception) { emptyArray() }
                    for (ff in fontFiles) {
                        if (ff.isDirectory) continue
                        val lower = ff.name.lowercase()
                        if (lower.equals(headerFontName.lowercase(), ignoreCase = true) || lower.contains("diploma")) {
                            headerTf = loadTypefaceFromUri(context, ff.uri, "hdr_${ff.name}")
                        } else if (lower.equals(bodyFontName.lowercase(), ignoreCase = true) || lower.contains("bankgothic")) {
                            bodyTf = loadTypefaceFromUri(context, ff.uri, "body_${ff.name}")
                        }
                    }
                }
                "background", "backgrounds" -> {
                    val bgSubDirs = try { sub.listFiles().filter { it.isDirectory } } catch (_: Exception) { emptyList() }
                    for (bSub in bgSubDirs) {
                        val bName = (bSub.name ?: "").lowercase().trim()
                        val imgs = try {
                            bSub.listSimpleFiles(context)
                                .filter { !it.isDirectory && (it.name.lowercase().endsWith(".png") || it.name.lowercase().endsWith(".jpg") || it.name.lowercase().endsWith(".jpeg")) }
                                .sortedBy { it.name.lowercase() }
                                .map { it.uri }
                        } catch (_: Exception) { emptyList() }

                        if (bName == "portrait" || bName == "potrait") {
                            portraitBgs.addAll(imgs)
                        } else if (bName == "landscape") {
                            landscapeBgs.addAll(imgs)
                        }
                    }
                }
                "icon", "icons" -> {
                    val iconFiles = try { sub.listSimpleFiles(context) } catch (_: Exception) { emptyArray() }
                    for (ic in iconFiles) {
                        if (ic.isDirectory) continue
                        val key = normalizeStationKey(ic.name)
                        stationIcons[key] = ic.uri
                    }
                }
                "sounds", "sound" -> {
                    val sndFiles = try { sub.listSimpleFiles(context) } catch (_: Exception) { emptyArray() }
                    for (sf in sndFiles.sortedBy { it.name.lowercase() }) {
                        if (sf.isDirectory) continue
                        val lower = sf.name.lowercase()
                        if (lower.contains("noise")) {
                            noiseSounds.add(sf.uri)
                        } else if (lower.contains("select")) {
                            selectSounds.add(sf.uri)
                        }
                    }
                }
            }
        }

        return DynamicThemePack(
            seriesName = folderName,
            headerTitle = headerTitle,
            bgIntervalMs = bgIntervalMs,
            bgFadeMs = bgFadeMs,
            headerColor = headerColor,
            labelColor = labelColor,
            activeTextColor = activeTextColor,
            volumeBarColor = volumeBarColor,
            headerTypeface = headerTf,
            bodyTypeface = bodyTf,
            groupLogoUri = groupLogoUri,
            portraitBgUris = portraitBgs,
            landscapeBgUris = landscapeBgs,
            stationIconUris = stationIcons,
            noiseSoundUris = noiseSounds,
            selectSoundUris = selectSounds
        )
    }

    private fun loadTypefaceFromUri(context: Context, uri: Uri, cacheName: String): Typeface? {
        return try {
            val dir = File(context.cacheDir, "theme_fonts").apply { mkdirs() }
            val outFile = File(dir, cacheName.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
            if (!outFile.exists() || outFile.length() == 0L) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
            Typeface.createFromFile(outFile)
        } catch (e: Exception) {
            GTRadioLogger.log("DynamicThemeManager: Failed loading font $cacheName", e)
            null
        }
    }

    fun loadBitmap(context: Context, uri: Uri?, maxDim: Int = 1080): Bitmap? {
        if (uri == null) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        } catch (_: Exception) {
            null
        }
    }

    fun playTuningNoiseUntilReady(
        context: Context,
        pack: DynamicThemePack?,
        isRadioReady: () -> Boolean,
        onComplete: () -> Unit
    ) {
        val sounds = pack?.noiseSoundUris ?: emptyList()
        if (!isDynamicThemeEnabled(context) || sounds.isEmpty()) {
            onComplete()
            return
        }
        stopTuningNoise()
        isNoiseLooping = true
        activeNoiseList = sounds
        playNoiseCycle(context.applicationContext, isRadioReady, onComplete)
    }

    private fun playNoiseCycle(
        appContext: Context,
        isRadioReady: () -> Boolean,
        onComplete: () -> Unit
    ) {
        if (!isNoiseLooping || activeNoiseList.isEmpty()) {
            onComplete()
            return
        }
        val uri = activeNoiseList[rng.nextInt(activeNoiseList.size)]
        try {
            noisePlayer?.release()
            noisePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(appContext, uri)
                setVolume(0.85f, 0.85f)
                setOnCompletionListener {
                    if (!isNoiseLooping) {
                        onComplete()
                    } else if (isRadioReady()) {
                        // Noise finished its full clip and radio is ready: cut to live broadcast
                        stopTuningNoise()
                        onComplete()
                    } else {
                        // Radio is still loading: loop with a freshly picked random noise clip
                        playNoiseCycle(appContext, isRadioReady, onComplete)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    stopTuningNoise()
                    onComplete()
                    true
                }
                prepare()
                start()
            }
        } catch (_: Exception) {
            stopTuningNoise()
            onComplete()
        }
    }

    fun isPlayingTuningNoise(): Boolean = isNoiseLooping

    fun stopTuningNoise() {
        isNoiseLooping = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            noisePlayer?.stop()
        } catch (_: Exception) {}
        try {
            noisePlayer?.release()
        } catch (_: Exception) {}
        noisePlayer = null
    }
}
