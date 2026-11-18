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
    val portraitBgUris: List<Uri>,
    val landscapeBgUris: List<Uri>,
    val groupLogoUri: Uri?,
    val stationIconUris: Map<String, Uri>,
    val stationOffIconUris: Map<String, Uri> = emptyMap(),
    val noiseSoundUris: List<Uri> = emptyList(),
    val layoutStyle: String = "gtasa"
)

object DynamicThemeManager {
    private val packCache = HashMap<String, DynamicThemePack>()
    private val bitmapCache = HashMap<String, Bitmap>()
    private var scannedPacks: List<DynamicThemePack>? = null
    private var lastThemeUriString: String? = null

    private var noisePlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var noiseCheckRunnable: Runnable? = null
    private val rng = Random()

    private val stationAliases = mapOf(
        // GTA San Andreas
        "bounce" to "bouncefm",
        "bouncefm" to "bouncefm",
        "csr" to "csr",
        "csr1039" to "csr",
        "kdst" to "kdst",
        "kjah" to "kjah",
        "kjahwest" to "kjah",
        "krose" to "krose",
        "mastersounds" to "mastersounds",
        "mastersounds983" to "mastersounds",
        "playback" to "playbackfm",
        "playbackfm" to "playbackfm",
        "rls" to "radiolossantos",
        "radiolossantos" to "radiolossantos",
        "radiox" to "radiox",
        "sfur" to "sfur",
        "wctr" to "wctr",
        // GTA IV & EFLC
        "classics1041" to "theclassics",
        "classics" to "theclassics",
        "theclassics" to "theclassics",
        "electrochoc" to "electrochoc",
        "fusionfm" to "fusionfm",
        "fusion" to "fusionfm",
        "if99internationalfunk" to "if99",
        "internationalfunk" to "if99",
        "if99" to "if99",
        "integrity20" to "integrity",
        "integrity" to "integrity",
        "jnrjazznationradio1085" to "jnr",
        "jazznationradio1085" to "jnr",
        "jazznationradio" to "jnr",
        "jnr" to "jnr",
        "k109thestudio" to "k109",
        "k109" to "k109",
        "libertycityhardcore" to "lchc",
        "lchc" to "lchc",
        "libertyrockradio978" to "libertyrock",
        "libertyrockradio" to "libertyrock",
        "libertyrock" to "libertyrock",
        "massivebsoundsystem969" to "massiveb",
        "massivebsoundsystem" to "massiveb",
        "massiveb" to "massiveb",
        "publiclibertyradio" to "plr",
        "plr" to "plr",
        "radiobroker" to "radiobroker",
        "broker" to "radiobroker",
        "sanjuansounds" to "sanjuansounds",
        "sanjuan" to "sanjuansounds",
        "thebeat1027" to "thebeat",
        "thebeat" to "thebeat",
        "thejourney" to "thejourney",
        "journey" to "thejourney",
        "thevibe988" to "thevibe",
        "thevibe" to "thevibe",
        "tuffgongradio" to "tuffgong",
        "tuffgong" to "tuffgong",
        "vladivostokfm" to "vladivostok",
        "vladivostok" to "vladivostok",
        "wktttalkradio" to "wktt",
        "wktt" to "wktt",
        "vicecityfm" to "vcfm",
        "vcfm" to "vcfm",
        "ramjamfm" to "ramjamfm",
        "ramjam" to "ramjamfm",
        "selfactualizationfm" to "selfactualizationfm",
        "meditation" to "selfactualizationfm"
    )

    fun isDynamicThemeEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("dynamic_theme_enabled", true)
    }

    fun invalidateCache() {
        packCache.clear()
        scannedPacks = null
    }

    fun clearCache() {
        invalidateCache()
    }

    fun normalizeStationKey(raw: String): String {
        val base = raw.lowercase()
            .substringBeforeLast(".")
            .replace(Regex("(_on_icon|_off_icon|_icon|_on|_off|logo|radio_)"), "")
            .replace(Regex("[^a-z0-9]"), "")
        return stationAliases[base] ?: base
    }

    fun findNoisePackForSeries(context: Context, seriesName: String?): DynamicThemePack? {
        if (seriesName.isNullOrBlank()) return null
        val exact = findThemeForSeries(context, seriesName)
        return if (exact != null && exact.noiseSoundUris.isNotEmpty()) exact else null
    }

    fun findThemeForSeries(context: Context, seriesName: String, libraryRootDoc: DocumentFile? = null): DynamicThemePack? {
        if (seriesName.isBlank() || !isDynamicThemeEnabled(context)) return null

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentThemeUri = prefs.getString("dynamic_theme_uri", "") ?: ""
        if (currentThemeUri != lastThemeUriString) {
            invalidateCache()
            lastThemeUriString = currentThemeUri
        }

        packCache[seriesName]?.let { return it }

        val packs = scannedPacks ?: scanAllThemePacks(context, libraryRootDoc).also { scannedPacks = it }
        val matched = packs.firstOrNull { matchesSeries(seriesName, it.seriesName) }
        if (matched != null) {
            packCache[seriesName] = matched
        }
        return matched
    }

    private fun matchesSeries(groupSeriesName: String, packSeriesName: String): Boolean {
        val g = groupSeriesName.uppercase()
        val p = packSeriesName.uppercase()
        val gNorm = g.replace(Regex("[^A-Z0-9]"), "")
        val pNorm = p.replace(Regex("[^A-Z0-9]"), "")
        if (gNorm == pNorm) return true

        val gIsSa = g.contains("SAN ANDREAS") || g.contains("GTA SA") || gNorm.contains("GTASA")
        val pIsSa = p.contains("SAN ANDREAS") || p.contains("GTA SA") || pNorm.contains("GTASA")
        if (gIsSa && pIsSa) return true

        val gIsEflc = g.contains("EPISODES") || g.contains("EFLC") || g.contains("TLAD") || g.contains("TBOGT")
        val pIsEflc = p.contains("EPISODES") || p.contains("EFLC") || p.contains("TLAD") || p.contains("TBOGT")
        if (gIsEflc || pIsEflc) return gIsEflc && pIsEflc

        val gIsIv = g.contains("GTA IV") || g.contains("GTA 4") || gNorm.contains("GTAIV")
        val pIsIv = p.contains("GTA IV") || p.contains("GTA 4") || pNorm.contains("GTAIV")
        if (gIsIv && pIsIv) return true

        val gIsVcs = g.contains("VICE CITY STORIES") || g.contains("VCS")
        val pIsVcs = p.contains("VICE CITY STORIES") || p.contains("VCS")
        if (gIsVcs || pIsVcs) return gIsVcs && pIsVcs

        val gIsVc = g.contains("VICE CITY") || g.contains("GTA VC")
        val pIsVc = p.contains("VICE CITY") || p.contains("GTA VC")
        if (gIsVc && pIsVc) return true

        val gIsLcs = g.contains("LIBERTY CITY STORIES") || g.contains("LCS")
        val pIsLcs = p.contains("LIBERTY CITY STORIES") || p.contains("LCS")
        if (gIsLcs || pIsLcs) return gIsLcs && pIsLcs

        val gIs3 = g.contains("GTA III") || g.contains("GTA 3")
        val pIs3 = p.contains("GTA III") || p.contains("GTA 3")
        if (gIs3 && pIs3) return true

        return false
    }

    private fun scanAllThemePacks(context: Context, libraryRootDoc: DocumentFile?): List<DynamicThemePack> {
        val results = ArrayList<DynamicThemePack>()
        val seenNames = HashSet<String>()
        val roots = findDynamicThemeRoots(context, libraryRootDoc)

        for (root in roots) {
            val children = try { root.listFiles() } catch (_: Exception) { emptyArray() }
            for (dir in children) {
                if (!dir.isDirectory) continue
                val dName = dir.name ?: continue
                if (dName.startsWith(".") || dName.equals("HD Radio Icons", ignoreCase = true)) continue
                if (!seenNames.add(dName.uppercase())) continue
                loadSingleThemePack(context, dir)?.let { results.add(it) }
            }
        }
        return results
    }

    private fun findDynamicThemeRoots(context: Context, libraryRootDoc: DocumentFile?): List<DocumentFile> {
        val list = ArrayList<DocumentFile>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val customUri = prefs.getString("dynamic_theme_uri", "") ?: ""
        if (customUri.isNotBlank()) {
            try {
                DocumentFile.fromTreeUri(context, Uri.parse(customUri))?.let { doc ->
                    list.add(doc)
                    doc.findFile("Dynamic Theme")?.let { if (it.isDirectory) list.add(it) }
                }
            } catch (_: Exception) {}
        }

        val sdPaths = listOf(
            "/storage/emulated/0/GTRadio/Dynamic Theme",
            "/sdcard/GTRadio/Dynamic Theme"
        )
        for (p in sdPaths) {
            try {
                val f = File(p)
                if (f.exists() && f.isDirectory && f.canRead()) {
                    list.add(DocumentFile.fromFile(f))
                }
            } catch (_: Exception) {}
        }

        if (libraryRootDoc != null) {
            try {
                libraryRootDoc.findFile("Dynamic Theme")?.let { if (it.isDirectory) list.add(it) }
            } catch (_: Exception) {}
        }

        return list
    }

    private fun loadSingleThemePack(context: Context, themeDir: DocumentFile): DynamicThemePack? {
        val folderName = themeDir.name ?: return null
        val simpleChildren = try { themeDir.listSimpleFiles(context) } catch (_: Exception) { emptyArray() }
        val jsonInfo = simpleChildren.firstOrNull { !it.isDirectory && it.name.equals("theme.json", ignoreCase = true) }
            ?: return null

        val jsonText = try {
            context.contentResolver.openInputStream(jsonInfo.uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        if (jsonText.isBlank()) return null

        val cleanJson = if (jsonText.trim().endsWith("}")) jsonText else (jsonText.trim() + "
}")
        val obj = try { JSONObject(cleanJson) } catch (_: Exception) { JSONObject() }

        val seriesName = obj.optString("seriesName", folderName)
        val isGta4Folder = seriesName.uppercase().contains("GTA IV") || folderName.uppercase().contains("GTA IV")
        val defaultHeaderTitle = if (isGta4Folder) "Liberty City Radio" else "$folderName Radio"
        var headerTitle = obj.optString("headerTitle", defaultHeaderTitle)
        if (isGta4Folder && headerTitle.equals("GTA IV Radio", ignoreCase = true)) {
            headerTitle = "Liberty City Radio"
        }

        val bgIntervalSec = obj.optLong("backgroundIntervalSeconds", obj.optLong("backgroundIntervalSec", 8L)).coerceAtLeast(2L)
        val bgFadeMs = obj.optLong("backgroundFadeMs", obj.optLong("crossfadeDurationMs", 1000L)).coerceAtLeast(150L)

        var headerFontName = obj.optString("headerFont", obj.optString("headerFontFile", if (isGta4Folder) "din1451alt G.ttf" else "diploma.ttf"))
        var bodyFontName = obj.optString("bodyFont", obj.optString("uiFontFile", if (isGta4Folder) "din1451alt.ttf" else "BankGothic Bold.ttf"))
        if (isGta4Folder && headerFontName.equals("diploma.ttf", ignoreCase = true)) {
            headerFontName = "din1451alt G.ttf"
            bodyFontName = "din1451alt.ttf"
        }

        val defaultGold = "#D49A2A"
        val defaultGrey = "#8E8E8E"
        val headerColorHex = obj.optString("headerColor", obj.optString("headerTextColor", if (isGta4Folder) defaultGold else "#D0E2F2"))
        val labelColorHex = obj.optString("labelColor", obj.optString("labelTextColor", if (isGta4Folder) defaultGrey else "#6E849B"))
        val activeColorHex = obj.optString("activeTextColor", obj.optString("valueTextColor", if (isGta4Folder) defaultGold else "#B8CFE6"))
        val volColorHex = obj.optString("volumeBarColor", obj.optString("volumeActiveColor", if (isGta4Folder) "#9E9E9E" else "#B8CFE6"))

        val headerColor = parseColorSafe(if (isGta4Folder && headerColorHex == "#D0E2F2") defaultGold else headerColorHex, Color.parseColor("#D0E2F2"))
        val labelColor = parseColorSafe(if (isGta4Folder && labelColorHex == "#6E849B") defaultGrey else labelColorHex, Color.parseColor("#6E849B"))
        val activeTextColor = parseColorSafe(if (isGta4Folder && activeColorHex == "#B8CFE6") defaultGold else activeColorHex, Color.parseColor("#B8CFE6"))
        val volumeBarColor = parseColorSafe(if (isGta4Folder && volColorHex == "#B8CFE6") "#9E9E9E" else volColorHex, Color.parseColor("#B8CFE6"))

        val portraitBgs = ArrayList<Uri>()
        val landscapeBgs = ArrayList<Uri>()
        val onIcons = HashMap<String, Uri>()
        val offIcons = HashMap<String, Uri>()
        val noiseUris = ArrayList<Uri>()
        var groupLogoUri: Uri? = null
        var headerTf: Typeface? = null
        var bodyTf: Typeface? = null

        for (c in simpleChildren) {
            if (!c.isDirectory && c.name.lowercase().startsWith("logo.")) {
                groupLogoUri = c.uri
            }
        }

        val subDirs = try { themeDir.listFiles().filter { it.isDirectory } } catch (_: Exception) { emptyList() }
        for (sub in subDirs) {
            when (sub.name?.lowercase()) {
                "background", "backgrounds" -> {
                    for (bgSub in sub.listFiles()) {
                        if (!bgSub.isDirectory) continue
                        when (bgSub.name?.lowercase()) {
                            "portrait", "potrait" -> {
                                bgSub.listSimpleFiles(context)
                                    .filter { !it.isDirectory && !it.name.startsWith(".") }
                                    .sortedBy { it.name.lowercase() }
                                    .forEach { portraitBgs.add(it.uri) }
                            }
                            "landscape" -> {
                                bgSub.listSimpleFiles(context)
                                    .filter { !it.isDirectory && !it.name.startsWith(".") }
                                    .sortedBy { it.name.lowercase() }
                                    .forEach { landscapeBgs.add(it.uri) }
                            }
                        }
                    }
                }
                "icon", "icons" -> {
                    // 1. Check flat icons in icon/
                    for (ic in sub.listSimpleFiles(context)) {
                        if (!ic.isDirectory && !ic.name.startsWith(".")) {
                            val key = normalizeStationKey(ic.name)
                            onIcons[key] = ic.uri
                        }
                    }
                    // 2. Check icon/on and icon/off subdirectories
                    for (icSub in sub.listFiles()) {
                        if (!icSub.isDirectory) continue
                        val subLower = icSub.name?.lowercase() ?: ""
                        if (subLower == "on") {
                            for (ic in icSub.listSimpleFiles(context)) {
                                if (!ic.isDirectory && !ic.name.startsWith(".")) {
                                    onIcons[normalizeStationKey(ic.name)] = ic.uri
                                }
                            }
                        } else if (subLower == "off") {
                            for (ic in icSub.listSimpleFiles(context)) {
                                if (!ic.isDirectory && !ic.name.startsWith(".")) {
                                    offIcons[normalizeStationKey(ic.name)] = ic.uri
                                }
                            }
                        }
                    }
                }
                "font", "fonts" -> {
                    val fontCacheDir = File(context.cacheDir, "theme_fonts_${folderName.hashCode()}").apply { mkdirs() }
                    val fontFiles = sub.listSimpleFiles(context).filter { !it.isDirectory && it.name.lowercase().endsWith(".ttf") }
                    for (ff in fontFiles) {
                        val cachedFile = copyUriToCache(context, ff.uri, File(fontCacheDir, ff.name)) ?: continue
                        try {
                            val tf = Typeface.createFromFile(cachedFile)
                            val fLower = ff.name.lowercase()
                            if (ff.name.equals(headerFontName, ignoreCase = true) ||
                                (isGta4Folder && fLower.contains("din") && fLower.contains("g.ttf")) ||
                                (!isGta4Folder && fLower.contains("diploma"))
                            ) {
                                headerTf = tf
                            }
                            if (ff.name.equals(bodyFontName, ignoreCase = true) ||
                                (isGta4Folder && fLower == "din1451alt.ttf") ||
                                (!isGta4Folder && fLower.contains("bankgothic"))
                            ) {
                                bodyTf = tf
                            }
                        } catch (_: Exception) {}
                    }
                    if (headerTf == null && bodyTf != null) headerTf = bodyTf
                    if (bodyTf == null && headerTf != null) bodyTf = headerTf
                }
                "sounds", "sound" -> {
                    sub.listSimpleFiles(context)
                        .filter { !it.isDirectory && !it.name.startsWith(".") }
                        .sortedBy { it.name.lowercase() }
                        .forEach { snd ->
                            val sLower = snd.name.lowercase()
                            if (sLower.contains("noise") || sLower.contains("static") || sLower.endsWith(".wav") || sLower.endsWith(".mp3") || sLower.endsWith(".ogg")) {
                                noiseUris.add(snd.uri)
                            }
                        }
                }
            }
        }

        if (onIcons.isEmpty() && offIcons.isNotEmpty()) {
            onIcons.putAll(offIcons)
        }

        val layoutStyle = obj.optString(
            "layoutStyle",
            if (isGta4Folder || offIcons.isNotEmpty()) "gta4" else "gtasa"
        )

        GTRadioLogger.log("DynamicThemeManager: Loaded theme pack '$seriesName' (style=$layoutStyle, onIcons=${onIcons.size}, offIcons=${offIcons.size}, bgP=${portraitBgs.size}, sounds=${noiseUris.size})")

        return DynamicThemePack(
            seriesName = seriesName,
            headerTitle = headerTitle,
            bgIntervalMs = bgIntervalSec * 1000L,
            bgFadeMs = bgFadeMs,
            headerColor = headerColor,
            labelColor = labelColor,
            activeTextColor = activeTextColor,
            volumeBarColor = volumeBarColor,
            headerTypeface = headerTf,
            bodyTypeface = bodyTf,
            portraitBgUris = portraitBgs,
            landscapeBgUris = landscapeBgs,
            groupLogoUri = groupLogoUri,
            stationIconUris = onIcons,
            stationOffIconUris = offIcons,
            noiseSoundUris = noiseUris,
            layoutStyle = layoutStyle
        )
    }

    fun loadBitmap(context: Context, uri: Uri?, maxDim: Int = 1080): Bitmap? {
        if (uri == null) return null
        val key = "${uri}_$maxDim"
        synchronized(bitmapCache) {
            bitmapCache[key]?.let { if (!it.isRecycled) return it }
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null) {
                    synchronized(bitmapCache) {
                        if (bitmapCache.size > 45) bitmapCache.clear()
                        bitmapCache[key] = bmp
                    }
                }
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getBitmap(context: Context, uri: Uri?, maxDim: Int = 1080): Bitmap? = loadBitmap(context, uri, maxDim)

    fun isPlayingTuningNoise(): Boolean = noisePlayer != null

    fun stopTuningNoise() {
        noiseCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        noiseCheckRunnable = null
        try {
            noisePlayer?.stop()
        } catch (_: Exception) {}
        try {
            noisePlayer?.release()
        } catch (_: Exception) {}
        noisePlayer = null
    }

    fun playTuningNoiseUntilReady(
        context: Context,
        pack: DynamicThemePack,
        isRadioReady: () -> Boolean,
        onComplete: () -> Unit
    ) {
        stopTuningNoise()
        if (pack.noiseSoundUris.isEmpty()) {
            onComplete()
            return
        }

        val chosenUri = pack.noiseSoundUris[rng.nextInt(pack.noiseSoundUris.size)]
        val startTime = System.currentTimeMillis()
        val minNoiseMs = 550L
        val maxNoiseMs = 2800L

        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(context, chosenUri)
            mp.isLooping = true
            mp.setVolume(0.85f, 0.85f)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            noisePlayer = mp
        } catch (_: Exception) {
            stopTuningNoise()
            onComplete()
            return
        }

        val checkTask = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                if ((elapsed >= minNoiseMs && isRadioReady()) || elapsed >= maxNoiseMs) {
                    stopTuningNoise()
                    onComplete()
                } else {
                    mainHandler.postDelayed(this, 80L)
                }
            }
        }
        noiseCheckRunnable = checkTask
        mainHandler.postDelayed(checkTask, 120L)
    }

    private fun copyUriToCache(context: Context, uri: Uri, dest: File): File? {
        if (dest.exists() && dest.length() > 0L) return dest
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            if (dest.exists() && dest.length() > 0L) dest else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int {
        return try { Color.parseColor(hex.trim()) } catch (_: Exception) { fallback }
    }
}
