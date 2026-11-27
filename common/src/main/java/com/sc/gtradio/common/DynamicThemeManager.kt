package com.sc.gtradio.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.FileUtils.listSimpleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    val layoutStyle: String = "grid",
    val hasThemeJson: Boolean = true,
    val bleedStatusBar: Boolean = true,
    val accentColor: Int = volumeBarColor,
    val metadataTypeface: Typeface? = null,
    val metadataStrokeColor: Int = Color.BLACK,
    val metadataStrokeWidthDp: Float = 0f,
    val sfxMode: String = "tuning_noise",
    val clickSoundUris: List<Uri> = emptyList(),
    val autoArcAbove: Int = 18,
    val showSettingsPanel: Boolean = false,
    val hideArtOnNonSong: Boolean = false,
    val matchAliases: List<String> = emptyList()
) {
    val hasFullThemeUi: Boolean
        get() = hasThemeJson && (portraitBgUris.isNotEmpty() || landscapeBgUris.isNotEmpty())
}

object DynamicThemeManager {
    private val packCache = HashMap<String, DynamicThemePack>()
    private val iconCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val wallpaperCacheMaxKb = minOf(16 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())
    private val wallpaperCache = object : LruCache<String, Bitmap>(wallpaperCacheMaxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun getCachedWallpaper(key: String): Bitmap? = synchronized(wallpaperCache) {
        val b = wallpaperCache.get(key)
        if (b != null && !b.isRecycled) b else null
    }

    fun putCachedWallpaper(key: String, bitmap: Bitmap) {
        synchronized(wallpaperCache) {
            wallpaperCache.put(key, bitmap)
        }
    }

    private val scanLock = Any()
    private var scannedPacks: List<DynamicThemePack>? = null
    private var lastScannedThemeUri: String? = null
    private var lastThemeUriString: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
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
        if (prefs.contains("enable_dynamic_theme_ui")) {
            return prefs.getBoolean("enable_dynamic_theme_ui", true)
        }
        return prefs.getBoolean("dynamic_theme_enabled", true)
    }

    fun invalidateCache() {
        synchronized(packCache) {
            packCache.clear()
        }
        synchronized(iconCache) {
            iconCache.evictAll()
        }
        synchronized(wallpaperCache) {
            wallpaperCache.evictAll()
        }
        releaseClickSound()
        scannedPacks = null
        lastScannedThemeUri = null
    }

    fun refreshIfNeeded(context: Context, themeDirUriStr: String? = null) {
        if (scannedPacks != null && packCache.isNotEmpty()) return
        try {
            val rootDoc = if (!themeDirUriStr.isNullOrBlank()) {
                try {
                    val doc = DocumentFile.fromTreeUri(context, Uri.parse(themeDirUriStr))
                    if (doc != null && doc.isDirectory && doc.canRead()) doc else null
                } catch (_: Exception) { null }
            } else null
            val packs = scanAllThemePacks(context, rootDoc, forceRefresh = false)
            synchronized(packCache) {
                scannedPacks = packs
                for (pack in packs) {
                    packCache[pack.seriesName] = pack
                }
            }
            GTRadioLogger.log("DynamicThemeManager: Pre-warmed ${packs.size} theme pack(s)")
        } catch (e: Exception) {
            GTRadioLogger.log("DynamicThemeManager: refreshIfNeeded failed", e)
        }
    }

    fun clearCache() {
        invalidateCache()
    }

    fun forceRescan(context: Context? = null) {
        invalidateCache()
        if (context != null) {
            try {
                scannedPacks = scanAllThemePacks(context, null, forceRefresh = true)
            } catch (_: Exception) {}
        }
    }

    fun normalizeStationKey(raw: String): String {
        val withoutExt = raw.lowercase().trim()
            .replace(Regex("\\.(png|jpg|jpeg|webp|bmp|gif|svg)$"), "")
        val compact = withoutExt.replace(Regex("[^a-z0-9]"), "")
        if (compact == "off" || compact == "officon" || compact == "radiooff" || compact == "radioofficon") {
            return "radiooff"
        }
        stationAliases[compact]?.let { return it }
        val base = withoutExt
            .replace(Regex("(_on_icon|_off_icon|_icon|_on|_off|logo|radio_)"), "")
            .replace(Regex("[^a-z0-9]"), "")
        if (base == "off" || base == "radiooff") {
            return "radiooff"
        }
        stationAliases[base]?.let { return it }
        val noTrailingDigits = base.replace(Regex("[0-9]+$"), "")
        if (noTrailingDigits.isNotBlank()) {
            stationAliases[noTrailingDigits]?.let { return it }
        }
        return base
    }

    fun resolveStationIconUri(iconMap: Map<String, Uri>, stationName: String): Uri? {
        if (iconMap.isEmpty() || stationName.isBlank()) return null
        val norm = normalizeStationKey(stationName)
        if (norm.isBlank()) return null
        iconMap[norm]?.let { return it }
        val normNoDigits = norm.replace(Regex("[0-9]+$"), "")
        if (normNoDigits.length >= 3) {
            for ((k, u) in iconMap) {
                val kNoDigits = k.replace(Regex("[0-9]+$"), "")
                if (kNoDigits == normNoDigits) return u
            }
        }
        return iconMap.entries
            .filter { (k, _) -> k.length >= 3 && (norm.contains(k) || k.contains(norm)) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    fun findNoisePackForSeries(context: Context, seriesName: String?): DynamicThemePack? {
        if (seriesName.isNullOrBlank() || !isDynamicThemeEnabled(context)) return null
        val exact = findThemeForSeries(context, seriesName) ?: return null
        if (!exact.hasThemeJson ||
            !exact.sfxMode.equals("tuning_noise", ignoreCase = true) ||
            exact.noiseSoundUris.isEmpty()
        ) {
            return null
        }
        return exact
    }

    private data class SeriesTokens(
        val fullTokens: List<String>,
        val coreTokens: List<String>
    )

    private val NON_ALNUM_REGEX = Regex("[^A-Z0-9]+")
    private val GEN_NOISE_REGEX = Regex("^GEN[0-9]+$")
    private val ALPHA_DIGIT_BOUNDARY_REGEX = Regex("(?<=[A-Z])(?=[0-9])|(?<=[0-9])(?=[A-Z])")
    private val GENERIC_QUALIFIER_TOKENS = setOf(
        "PC", "MOBILE", "ANDROID", "IOS", "PSP", "PS2", "PS3", "PS4", "PS5",
        "XBOX", "CONSOLE", "ENHANCED", "LEGACY", "DEFINITIVE", "COMPLETE",
        "REMASTERED", "EDITION", "AUDIO", "RADIO", "THEME", "PACK"
    )

    private fun romanTokenToNumber(token: String): String {
        if (token.isEmpty()) return token
        for (ch in token) {
            if (ch != 'I' && ch != 'V') return token
        }
        var total = 0
        var prev = 0
        for (i in token.length - 1 downTo 0) {
            val v = if (token[i] == 'V') 5 else 1
            if (v < prev) total -= v else total += v
            prev = v
        }
        val canonical = when (total) {
            1 -> "I"
            2 -> "II"
            3 -> "III"
            4 -> "IV"
            5 -> "V"
            6 -> "VI"
            else -> null
        }
        return if (canonical == token) total.toString() else token
    }

    private fun tokenizeSeriesName(raw: String): SeriesTokens {
        val rawTokens = raw.uppercase(Locale.ROOT)
            .split(NON_ALNUM_REGEX)
            .filter { it.isNotEmpty() }

        val fullTokens = ArrayList<String>(rawTokens.size + 2)
        for (tok in rawTokens) {
            if (tok == "GTRADIO" || GEN_NOISE_REGEX.matches(tok)) continue
            if (tok in GENERIC_QUALIFIER_TOKENS) {
                fullTokens.add(tok)
                continue
            }
            val splitParts = tok.split(ALPHA_DIGIT_BOUNDARY_REGEX)
            for (part in splitParts) {
                if (part.isNotEmpty()) {
                    fullTokens.add(romanTokenToNumber(part))
                }
            }
        }
        val coreTokens = fullTokens.filterNot { it in GENERIC_QUALIFIER_TOKENS }
        return SeriesTokens(fullTokens = fullTokens, coreTokens = coreTokens)
    }

    private fun findBestThemeMatch(packs: List<DynamicThemePack>, seriesName: String): DynamicThemePack? {
        if (packs.isEmpty()) return null
        val groupTokens = tokenizeSeriesName(seriesName)
        if (groupTokens.fullTokens.isEmpty()) return null

        val packTokensList = packs.map { pack ->
            val primary = tokenizeSeriesName(pack.seriesName)
            val aliases = pack.matchAliases.map { tokenizeSeriesName(it) }
            Triple(pack, primary, aliases)
        }

        // Pass 1: Exact specificity (all normalized tokens match)
        for ((pack, primary, aliases) in packTokensList) {
            if (primary.fullTokens == groupTokens.fullTokens ||
                aliases.any { it.fullTokens == groupTokens.fullTokens }
            ) {
                return pack
            }
        }

        // Pass 2: Core token equality (ignoring platform/edition qualifiers)
        if (groupTokens.coreTokens.isNotEmpty()) {
            val groupFullSet = groupTokens.fullTokens.toSet()
            var bestPack: DynamicThemePack? = null
            var bestShared = -1
            var bestUnmatched = Int.MAX_VALUE

            for ((pack, primary, aliases) in packTokensList) {
                val matchingTokens = when {
                    primary.coreTokens == groupTokens.coreTokens -> primary
                    else -> aliases.firstOrNull { it.coreTokens == groupTokens.coreTokens }
                } ?: continue

                val shared = matchingTokens.fullTokens.count { it in groupFullSet }
                val unmatched = matchingTokens.fullTokens.size - shared
                if (unmatched < bestUnmatched || (unmatched == bestUnmatched && shared > bestShared)) {
                    bestPack = pack
                    bestShared = shared
                    bestUnmatched = unmatched
                }
            }
            return bestPack
        }

        return null
    }

    fun getCachedThemeForSeries(seriesName: String): DynamicThemePack? {
        if (seriesName.isBlank()) return null
        synchronized(packCache) {
            packCache[seriesName]?.let { return it }
        }
        val packs = scannedPacks ?: return null
        val matched = findBestThemeMatch(packs, seriesName)
        if (matched != null) {
            synchronized(packCache) {
                packCache[seriesName] = matched
            }
        }
        return matched
    }

    fun findThemeForSeries(context: Context, seriesName: String, libraryRootDoc: DocumentFile? = null): DynamicThemePack? {
        if (seriesName.isBlank() || !isDynamicThemeEnabled(context)) return null

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentThemeUri = prefs.getString("dynamic_theme_uri", "") ?: ""
        val currentLibUri = prefs.getString("radio_folders_uri", "") ?: ""
        val combinedKey = "$currentThemeUri|$currentLibUri"
        if (combinedKey != lastThemeUriString) {
            invalidateCache()
            lastThemeUriString = combinedKey
        }

        synchronized(packCache) {
            packCache[seriesName]?.let { return it }
        }

        val packs = scannedPacks ?: scanAllThemePacks(context, libraryRootDoc).also { scannedPacks = it }
        val matched = findBestThemeMatch(packs, seriesName)
        if (matched != null) {
            synchronized(packCache) {
                packCache[seriesName] = matched
            }
        }
        return matched
    }

    fun scanAllThemePacks(
        context: Context,
        libraryRootDoc: DocumentFile?,
        forceRefresh: Boolean = false
    ): List<DynamicThemePack> = synchronized(scanLock) {
        val root = resolveDynamicThemeRoot(context, libraryRootDoc)
        val currentUriStr = root?.uri?.toString() ?: ""

        if (!forceRefresh && scannedPacks != null && currentUriStr.isNotBlank() && currentUriStr == lastScannedThemeUri) {
            return scannedPacks!!
        }

        if (forceRefresh) {
            scannedPacks = null
            synchronized(packCache) {
                packCache.clear()
            }
        } else if (scannedPacks != null && currentUriStr == lastScannedThemeUri) {
            return scannedPacks!!
        }

        val results = ArrayList<DynamicThemePack>()
        if (root == null) {
            scannedPacks = results
            lastScannedThemeUri = currentUriStr
            return results
        }

        val seenNames = HashSet<String>()
        val children = runCatching { root.listSimpleFiles(context) }.getOrElse { emptyArray() }
        for (dir in children) {
            if (!dir.isDirectory) continue
            val dName = dir.name
            if (dName.startsWith(".") || dName.equals("HD Radio Icons", ignoreCase = true)) continue
            if (!seenNames.add(dName.uppercase())) continue
            val dirDoc = runCatching { DocumentFile.fromTreeUri(context, dir.uri) }.getOrNull() ?: continue
            loadSingleThemePack(context, dirDoc)?.let { pack ->
                results.add(pack)
                synchronized(packCache) {
                    packCache[pack.seriesName] = pack
                }
            }
        }
        scannedPacks = results
        lastScannedThemeUri = currentUriStr
        results
    }

    fun resolveDynamicThemeRoot(context: Context, libraryRootDoc: DocumentFile? = null): DocumentFile? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // 1. Explicit user override from "dynamic_theme_uri" (if set and still persisted/valid)
        val customUri = prefs.getString("dynamic_theme_uri", "") ?: ""
        if (customUri.isNotBlank()) {
            try {
                val doc = DocumentFile.fromTreeUri(context, Uri.parse(customUri))
                if (doc != null && doc.isDirectory && doc.canRead()) {
                    // If "dynamic_theme_uri" points to parent GTRadio root instead of Dynamic Theme, look for child "Dynamic Theme"
                    val dtChild = runCatching {
                        doc.listSimpleFiles(context).firstOrNull { it.isDirectory && it.name.equals("Dynamic Theme", ignoreCase = true) }
                            ?.let { DocumentFile.fromTreeUri(context, it.uri) }
                    }.getOrNull()
                    val resolved = dtChild ?: doc
                    GTRadioLogger.log("DynamicThemeManager: Resolved root from user override dynamic_theme_uri: ${resolved.uri}")
                    return resolved
                }
            } catch (e: Exception) {
                GTRadioLogger.log("DynamicThemeManager: Failed to access custom dynamic_theme_uri", e)
            }
        }

        // 2. Auto-discover "Dynamic Theme" directly inside selected Library root (radio_folders_uri)
        val libDoc = libraryRootDoc ?: run {
            val libUriStr = prefs.getString("radio_folders_uri", "") ?: ""
            if (libUriStr.isNotBlank()) {
                try {
                    val d = DocumentFile.fromTreeUri(context, Uri.parse(libUriStr))
                    if (d != null && d.isDirectory && d.canRead()) d else null
                } catch (_: Exception) { null }
            } else null
        }
        if (libDoc != null) {
            val dtChild = runCatching {
                libDoc.listSimpleFiles(context).firstOrNull { it.isDirectory && it.name.equals("Dynamic Theme", ignoreCase = true) }
                    ?.let { DocumentFile.fromTreeUri(context, it.uri) }
            }.getOrNull()
            if (dtChild != null && dtChild.canRead()) {
                GTRadioLogger.log("DynamicThemeManager: Auto-discovered Dynamic Theme inside library root: ${dtChild.uri}")
                return dtChild
            }
        }

        // 3. Fallback to legacy /storage/emulated/0/GTRadio/Dynamic Theme only if readable
        val legacyPaths = listOf(
            "/storage/emulated/0/GTRadio/Dynamic Theme",
            "/sdcard/GTRadio/Dynamic Theme"
        )
        for (p in legacyPaths) {
            try {
                val f = File(p)
                if (f.exists() && f.isDirectory && f.canRead()) {
                    val doc = DocumentFile.fromFile(f)
                    GTRadioLogger.log("DynamicThemeManager: Resolved root from legacy filesystem path: $p")
                    return doc
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun findDynamicThemeRoots(context: Context, libraryRootDoc: DocumentFile? = null): List<DocumentFile> {
        val root = resolveDynamicThemeRoot(context, libraryRootDoc)
        return if (root != null) listOf(root) else emptyList()
    }

    private fun loadSingleThemePack(context: Context, themeDir: DocumentFile): DynamicThemePack? {
        val folderName = themeDir.name ?: return null
        val simpleChildren = runCatching { themeDir.listSimpleFiles(context) }.getOrElse { emptyArray() }
        val jsonInfo = simpleChildren.firstOrNull { !it.isDirectory && it.name.equals("theme.json", ignoreCase = true) }
            ?: return null

        val jsonText = runCatching {
            context.contentResolver.openInputStream(jsonInfo.uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }.getOrElse { "" }
        if (jsonText.isBlank()) return null

        val cleanJson = if (jsonText.trim().endsWith("}")) jsonText else (jsonText.trim() + "}")
        val obj = try { JSONObject(cleanJson) } catch (_: Exception) { JSONObject() }

        val seriesName = obj.optString("seriesName", folderName)
        val headerTitle = obj.optString("headerTitle", "$folderName Radio")

        val matchAliases = ArrayList<String>()
        val aliasesArr = obj.optJSONArray("matchAliases") ?: obj.optJSONArray("match_aliases")
        if (aliasesArr != null) {
            for (i in 0 until aliasesArr.length()) {
                val alias = aliasesArr.optString(i, "").trim()
                if (alias.isNotBlank()) {
                    matchAliases.add(alias)
                }
            }
        }

        val bgIntervalSec = obj.optLong("backgroundIntervalSeconds", obj.optLong("backgroundIntervalSec", 8L)).coerceAtLeast(2L)
        val bgFadeMs = obj.optLong("backgroundFadeMs", obj.optLong("crossfadeDurationMs", 1000L)).coerceAtLeast(150L)

        val headerFontName = optStringAny(obj, "", "headerFont", "headerFontFile")
        val bodyFontName = optStringAny(obj, "", "bodyFont", "uiFontFile")

        val headerColorHex = obj.optString("headerColor", obj.optString("headerTextColor", "#D0E2F2"))
        val labelColorHex = obj.optString("labelColor", obj.optString("labelTextColor", "#6E849B"))
        val activeColorHex = obj.optString("activeTextColor", obj.optString("valueTextColor", "#B8CFE6"))
        val volColorHex = obj.optString("volumeBarColor", obj.optString("volumeActiveColor", "#B8CFE6"))

        val headerColor = parseColorSafe(headerColorHex, Color.parseColor("#D0E2F2"))
        val labelColor = parseColorSafe(labelColorHex, Color.parseColor("#6E849B"))
        val activeTextColor = parseColorSafe(activeColorHex, Color.parseColor("#B8CFE6"))
        val volumeBarColor = parseColorSafe(volColorHex, Color.parseColor("#B8CFE6"))

        val metadataFontName = optStringAny(obj, "", "metadataFont", "centerMetadataFont")
        val bleedStatusBar = optBooleanAny(obj, true, "bleedStatusBar", "bleed_status_bar", "hide_top_inset")
        val accentColorHex = optStringAny(obj, "", "accentColor", "accent_color")
        val accentColor = if (accentColorHex.isNotBlank()) parseColorSafe(accentColorHex, volumeBarColor) else volumeBarColor
        val metadataStrokeHex = optStringAny(obj, "", "metadataStrokeColor", "metadata_stroke_color")
        val metadataStrokeColor = if (metadataStrokeHex.isNotBlank()) parseColorSafe(metadataStrokeHex, Color.BLACK) else Color.BLACK
        val metadataStrokeWidthDp = optFloatAny(obj, 0f, "metadataStrokeWidthDp", "metadata_stroke_width_dp").coerceAtLeast(0f)
        val autoArcAbove = optIntAny(obj, 18, "autoArcAbove", "auto_arc_above").coerceAtLeast(1)
        val showSettingsPanel = optBooleanAny(obj, false, "showSettingsPanel", "show_settings_panel")
        val hideArtOnNonSong = optBooleanAny(obj, false, "hideArtOnNonSong", "hide_art_on_non_song")

        val portraitBgs = ArrayList<Uri>()
        val landscapeBgs = ArrayList<Uri>()
        val onIcons = HashMap<String, Uri>()
        val offIcons = HashMap<String, Uri>()
        val noiseUris = ArrayList<Uri>()
        val clickUris = ArrayList<Uri>()
        var groupLogoUri: Uri? = null
        var headerTf: Typeface? = null
        var bodyTf: Typeface? = null
        var metadataTf: Typeface? = null

        for (c in simpleChildren) {
            if (!c.isDirectory && c.name.lowercase().startsWith("logo.")) {
                groupLogoUri = c.uri
            }
        }

        val subDirs = simpleChildren.filter { it.isDirectory }
        for (sub in subDirs) {
            val subDoc = runCatching { DocumentFile.fromTreeUri(context, sub.uri) }.getOrNull() ?: continue
            val subChildren = runCatching { subDoc.listSimpleFiles(context) }.getOrElse { emptyArray() }
            when (sub.name.lowercase()) {
                "background", "backgrounds" -> {
                    for (bgSub in subChildren.filter { it.isDirectory }) {
                        val bgSubDoc = runCatching { DocumentFile.fromTreeUri(context, bgSub.uri) }.getOrNull() ?: continue
                        val bgFiles = runCatching { bgSubDoc.listSimpleFiles(context) }.getOrElse { emptyArray() }
                        when (bgSub.name.lowercase()) {
                            "portrait", "potrait" -> {
                                bgFiles
                                    .filter { !it.isDirectory && !it.name.startsWith(".") }
                                    .sortedBy { it.name.lowercase() }
                                    .forEach { portraitBgs.add(it.uri) }
                            }
                            "landscape" -> {
                                bgFiles
                                    .filter { !it.isDirectory && !it.name.startsWith(".") }
                                    .sortedBy { it.name.lowercase() }
                                    .forEach { landscapeBgs.add(it.uri) }
                            }
                        }
                    }
                }
                "icon", "icons" -> {
                    // 1. Check flat icons in icon/
                    for (ic in subChildren.filter { !it.isDirectory && !it.name.startsWith(".") }) {
                        val key = normalizeStationKey(ic.name)
                        onIcons[key] = ic.uri
                    }
                    // 2. Check icon/on and icon/off subdirectories
                    for (icSub in subChildren.filter { it.isDirectory }) {
                        val subLower = icSub.name.lowercase()
                        val icSubDoc = runCatching { DocumentFile.fromTreeUri(context, icSub.uri) }.getOrNull() ?: continue
                        val icFiles = runCatching { icSubDoc.listSimpleFiles(context) }.getOrElse { emptyArray() }
                        if (subLower == "on") {
                            for (ic in icFiles.filter { !it.isDirectory && !it.name.startsWith(".") }) {
                                onIcons[normalizeStationKey(ic.name)] = ic.uri
                            }
                        } else if (subLower == "off") {
                            for (ic in icFiles.filter { !it.isDirectory && !it.name.startsWith(".") }) {
                                offIcons[normalizeStationKey(ic.name)] = ic.uri
                            }
                        }
                    }
                }
                "font", "fonts" -> {
                    val fontCacheDir = File(context.cacheDir, "theme_fonts_${folderName.hashCode()}").apply { mkdirs() }
                    val fontFiles = subChildren.filter {
                        val fLower = it.name.lowercase()
                        !it.isDirectory && (fLower.endsWith(".ttf") || fLower.endsWith(".otf"))
                    }
                    for (ff in fontFiles) {
                        val cachedFile = copyUriToCache(context, ff.uri, File(fontCacheDir, ff.name)) ?: continue
                        try {
                            val tf = Typeface.createFromFile(cachedFile)
                            val fLower = ff.name.lowercase()
                            val matchesHeader = if (headerFontName.isNotBlank()) {
                                ff.name.equals(headerFontName, ignoreCase = true)
                            } else {
                                fLower.contains("diploma") || (fLower.contains("din") && fLower.contains("g.ttf")) || fLower.contains("pricedown")
                            }
                            if (matchesHeader && headerTf == null) {
                                headerTf = tf
                            }
                            val matchesBody = if (bodyFontName.isNotBlank()) {
                                ff.name.equals(bodyFontName, ignoreCase = true)
                            } else {
                                fLower.contains("bankgothic") || fLower == "din1451alt.ttf"
                            }
                            if (matchesBody && bodyTf == null) {
                                bodyTf = tf
                            }
                            if (metadataFontName.isNotBlank() && ff.name.equals(metadataFontName, ignoreCase = true)) {
                                metadataTf = tf
                            }
                        } catch (_: Exception) {}
                    }
                    if (headerTf == null && bodyTf != null) headerTf = bodyTf
                    if (bodyTf == null && headerTf != null) bodyTf = headerTf
                    if (metadataTf == null) metadataTf = bodyTf
                }
                "sounds", "sound" -> {
                    subChildren
                        .filter { !it.isDirectory && !it.name.startsWith(".") }
                        .sortedBy { it.name.lowercase() }
                        .forEach { snd ->
                            val sLower = snd.name.lowercase()
                            if (sLower.endsWith(".wav") || sLower.endsWith(".ogg") || sLower.endsWith(".mp3")) {
                                clickUris.add(snd.uri)
                            }
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

        val layoutStyle = optStringAny(
            obj,
            if (offIcons.isNotEmpty()) "strip" else "grid",
            "layoutStyle",
            "layout_style"
        )
        val defaultSfxMode = if (
            layoutStyle.equals("wheel", ignoreCase = true) ||
            layoutStyle.equals("wheel_arc", ignoreCase = true)
        ) {
            "click"
        } else {
            "tuning_noise"
        }
        val rawSfxMode = optStringAny(obj, defaultSfxMode, "sfxMode", "sfx_mode").lowercase().trim()
        val sfxMode = when (rawSfxMode) {
            "tuning_noise", "click", "none" -> rawSfxMode
            else -> defaultSfxMode
        }

        GTRadioLogger.log("DynamicThemeManager: Loaded theme pack '$seriesName' (style=$layoutStyle, sfx=$sfxMode, bleed=$bleedStatusBar, onIcons=${onIcons.size}, offIcons=${offIcons.size}, bgP=${portraitBgs.size}, sounds=${noiseUris.size})")

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
            layoutStyle = layoutStyle,
            hasThemeJson = true,
            bleedStatusBar = bleedStatusBar,
            accentColor = accentColor,
            metadataTypeface = metadataTf,
            metadataStrokeColor = metadataStrokeColor,
            metadataStrokeWidthDp = metadataStrokeWidthDp,
            sfxMode = sfxMode,
            clickSoundUris = clickUris,
            autoArcAbove = autoArcAbove,
            showSettingsPanel = showSettingsPanel,
            hideArtOnNonSong = hideArtOnNonSong,
            matchAliases = matchAliases
        )
    }

    fun getCachedBitmap(uri: Uri?, maxDim: Int = 512): Bitmap? {
        if (uri == null) return null
        val key = "${uri}_$maxDim"
        synchronized(iconCache) {
            iconCache.get(key)?.let { if (!it.isRecycled) return it }
        }
        return null
    }

    fun loadBitmap(context: Context, uri: Uri?, maxDim: Int = 1080): Bitmap? {
        if (uri == null) return null
        val key = "${uri}_$maxDim"
        synchronized(iconCache) {
            iconCache.get(key)?.let { if (!it.isRecycled) return it }
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
                    synchronized(iconCache) {
                        iconCache.put(key, bmp)
                    }
                }
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadDownsampledBackground(
        context: Context,
        uri: Uri?,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        val cacheKey = "${uri}_${reqWidth}x${reqHeight}"
        getCachedWallpaper(cacheKey)?.let { return@withContext it }

        try {
            // Pass 1: Decode image dimensions only
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val rawWidth = options.outWidth
            val rawHeight = options.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return@withContext null

            // Power-of-2 inSampleSize calculation
            var inSampleSize = 1
            while ((rawWidth / (inSampleSize * 2)) >= reqWidth && (rawHeight / (inSampleSize * 2)) >= reqHeight) {
                inSampleSize *= 2
            }

            // Pass 2: Decode downsampled with RGB_565 config for opaque wallpaper efficiency
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inDither = true
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }

            if (bitmap != null) {
                putCachedWallpaper(cacheKey, bitmap)
            }
            bitmap
        } catch (e: Exception) {
            GTRadioLogger.log("DynamicThemeManager: Error loading wallpaper from $uri", e)
            null
        }
    }

    private val tuneGeneration = AtomicInteger(0)
    @Volatile private var isPlayingNoise = false
    private var noisePlayer: MediaPlayer? = null
    private var tuningStartTime = 0L
    private var noiseWatchdogRunnable: Runnable? = null
    private const val SAFETY_LOOP_CAP_MS = 6500L
    private const val WATCHDOG_MAX_MS = 7000L

    fun getBitmap(context: Context, uri: Uri?, maxDim: Int = 1080): Bitmap? = loadBitmap(context, uri, maxDim)

    fun isPlayingTuningNoise(): Boolean = isPlayingNoise

    fun stopTuningNoise(trigger: String = "stopped") {
        tuneGeneration.incrementAndGet()
        stopTuningNoiseInternal(trigger)
    }

    private fun stopTuningNoiseInternal(trigger: String) {
        val durationMs = if (tuningStartTime > 0L) SystemClock.uptimeMillis() - tuningStartTime else 0L
        if (noisePlayer != null || isPlayingNoise) {
            GTRadioLogger.logVerbose("TUNE") {
                "Stopped tuning static noise: trigger='$trigger', duration=${durationMs}ms"
            }
        }
        tuningStartTime = 0L
        isPlayingNoise = false
        noiseWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        noiseWatchdogRunnable = null
        val mp = noisePlayer
        noisePlayer = null
        if (mp != null) {
            try {
                mp.stop()
            } catch (_: Exception) {}
            try {
                mp.release()
            } catch (_: Exception) {}
        }
    }

    fun playTuningNoiseUntilReady(
        context: Context,
        pack: DynamicThemePack,
        isRadioReady: () -> Boolean,
        onComplete: () -> Unit
    ) {
        val myGen = tuneGeneration.incrementAndGet()
        stopTuningNoiseInternal("new_tune_request")

        if (!pack.sfxMode.equals("tuning_noise", ignoreCase = true) ||
            pack.noiseSoundUris.isEmpty() ||
            !pack.hasThemeJson ||
            !isDynamicThemeEnabled(context)
        ) {
            onComplete()
            return
        }

        val chosenUri = pack.noiseSoundUris[rng.nextInt(pack.noiseSoundUris.size)]
        val startTime = SystemClock.uptimeMillis()
        tuningStartTime = startTime
        isPlayingNoise = true
        var loopCount = 0

        GTRadioLogger.logVerbose("TUNE") {
            "Starting tuning static noise [gen=$myGen] for pack '${pack.seriesName}', chosen URI: $chosenUri"
        }

        val mp = MediaPlayer()
        noisePlayer = mp

        // Safety watchdog runnable (7000L ms max)
        val watchdogRunnable = Runnable {
            if (myGen == tuneGeneration.get()) {
                val elapsed = SystemClock.uptimeMillis() - startTime
                GTRadioLogger.log("[TUNE] Safety watchdog triggered after ${elapsed}ms (loops=$loopCount). Forcing completion.")
                stopTuningNoiseInternal("safety_watchdog")
                mainHandler.post { onComplete() }
            }
        }
        noiseWatchdogRunnable = watchdogRunnable
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_MAX_MS)

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(context, chosenUri)
            mp.isLooping = false
            mp.setVolume(0.85f, 0.85f)

            mp.setOnPreparedListener { player ->
                if (myGen != tuneGeneration.get()) {
                    try { player.release() } catch (_: Exception) {}
                    return@setOnPreparedListener
                }
                try {
                    player.start()
                } catch (e: Exception) {
                    GTRadioLogger.log("[TUNE] MediaPlayer start failed", e)
                    stopTuningNoiseInternal("start_error")
                    mainHandler.post { onComplete() }
                }
            }

            mp.setOnCompletionListener { player ->
                if (myGen != tuneGeneration.get()) {
                    try { player.release() } catch (_: Exception) {}
                    return@setOnCompletionListener
                }
                val totalElapsed = SystemClock.uptimeMillis() - startTime
                val ready = isRadioReady()
                val safetyExceeded = totalElapsed >= SAFETY_LOOP_CAP_MS

                if (ready || safetyExceeded) {
                    val reason = if (safetyExceeded && !ready) "safety_timeout" else "clip_finished_ready"
                    GTRadioLogger.log("[TUNE] Tuning static completed: reason='$reason', elapsed=${totalElapsed}ms, loops=$loopCount")
                    stopTuningNoiseInternal(reason)
                    mainHandler.post { onComplete() }
                } else {
                    // Station not ready yet -> loop another pass
                    loopCount++
                    GTRadioLogger.log("[TUNE] Tuning static loop #$loopCount: radio not ready yet (${totalElapsed}ms elapsed), looping...")
                    try {
                        player.seekTo(0)
                        player.start()
                    } catch (e: Exception) {
                        GTRadioLogger.log("[TUNE] MediaPlayer loop restart failed", e)
                        stopTuningNoiseInternal("loop_restart_error")
                        mainHandler.post { onComplete() }
                    }
                }
            }

            mp.setOnErrorListener { player, what, extra ->
                GTRadioLogger.log("[TUNE] MediaPlayer error: what=$what, extra=$extra (gen=$myGen)")
                if (myGen == tuneGeneration.get()) {
                    stopTuningNoiseInternal("media_player_error")
                    mainHandler.post { onComplete() }
                } else {
                    try { player.release() } catch (_: Exception) {}
                }
                true
            }

            mp.prepareAsync()
        } catch (e: Exception) {
            GTRadioLogger.log("[TUNE] MediaPlayer setup failed", e)
            stopTuningNoiseInternal("setup_exception")
            onComplete()
        }
    }

    private val clickLock = Any()
    private val clickIoExecutor = Executors.newSingleThreadExecutor()
    private var clickSoundPool: SoundPool? = null
    private var loadingClickUri: Uri? = null
    private var loadedClickUri: Uri? = null
    private var loadedClickSoundId: Int = 0
    @Volatile private var clickSoundReady: Boolean = false
    @Volatile private var pendingClickPlay: Boolean = false

    fun preloadClickSound(context: Context, pack: DynamicThemePack?) {
        preloadClickSoundInternal(context.applicationContext, pack, playWhenLoaded = false)
    }

    fun playClickSound(context: Context, pack: DynamicThemePack?) {
        val appCtx = context.applicationContext
        if (pack == null ||
            !pack.sfxMode.equals("click", ignoreCase = true) ||
            pack.clickSoundUris.isEmpty() ||
            !isDynamicThemeEnabled(appCtx)
        ) {
            return
        }
        val targetUri = pack.clickSoundUris.first()
        var poolToPlay: SoundPool? = null
        var soundIdToPlay = 0
        var needPreload = false
        synchronized(clickLock) {
            val pool = clickSoundPool
            if (pool != null && loadedClickUri == targetUri) {
                if (clickSoundReady && loadedClickSoundId != 0) {
                    poolToPlay = pool
                    soundIdToPlay = loadedClickSoundId
                } else {
                    pendingClickPlay = true
                }
            } else if (loadingClickUri == targetUri) {
                pendingClickPlay = true
            } else {
                needPreload = true
            }
        }
        if (poolToPlay != null && soundIdToPlay != 0) {
            poolToPlay?.play(soundIdToPlay, 0.9f, 0.9f, 1, 0, 1.0f)
            return
        }
        if (needPreload) {
            preloadClickSoundInternal(appCtx, pack, playWhenLoaded = true)
        }
    }

    fun releaseClickSound() {
        val oldPool: SoundPool?
        synchronized(clickLock) {
            oldPool = clickSoundPool
            clickSoundPool = null
            loadingClickUri = null
            loadedClickUri = null
            loadedClickSoundId = 0
            clickSoundReady = false
            pendingClickPlay = false
        }
        oldPool?.let { runCatching { it.release() } }
    }

    private fun preloadClickSoundInternal(appCtx: Context, pack: DynamicThemePack?, playWhenLoaded: Boolean) {
        if (pack == null ||
            !pack.sfxMode.equals("click", ignoreCase = true) ||
            pack.clickSoundUris.isEmpty() ||
            !isDynamicThemeEnabled(appCtx)
        ) {
            return
        }
        val targetUri = pack.clickSoundUris.first()
        synchronized(clickLock) {
            val existing = clickSoundPool
            if (existing != null && loadedClickUri == targetUri) {
                if (playWhenLoaded) {
                    if (clickSoundReady && loadedClickSoundId != 0) {
                        existing.play(loadedClickSoundId, 0.9f, 0.9f, 1, 0, 1.0f)
                    } else {
                        pendingClickPlay = true
                    }
                }
                return
            }
            if (loadingClickUri == targetUri) {
                if (playWhenLoaded) {
                    pendingClickPlay = true
                }
                return
            }
            loadingClickUri = targetUri
            pendingClickPlay = playWhenLoaded
        }

        clickIoExecutor.execute {
            try {
                val ext = resolveClickExtension(appCtx, targetUri)
                val cacheDir = File(appCtx.cacheDir, "theme_click_sfx").apply { mkdirs() }
                val destFile = File(cacheDir, "click_${targetUri.toString().hashCode()}$ext")
                val cached = copyUriToCache(appCtx, targetUri, destFile)
                if (cached == null) {
                    synchronized(clickLock) {
                        if (loadingClickUri == targetUri) {
                            loadingClickUri = null
                            pendingClickPlay = false
                        }
                    }
                    return@execute
                }

                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val newPool = SoundPool.Builder()
                    .setMaxStreams(3)
                    .setAudioAttributes(attrs)
                    .build()

                newPool.setOnLoadCompleteListener { sp, sampleId, status ->
                    if (status != 0) return@setOnLoadCompleteListener
                    var playNow = false
                    synchronized(clickLock) {
                        if (clickSoundPool === sp && loadedClickSoundId == sampleId) {
                            clickSoundReady = true
                            playNow = pendingClickPlay
                            pendingClickPlay = false
                        }
                    }
                    if (playNow) {
                        sp.play(sampleId, 0.9f, 0.9f, 1, 0, 1.0f)
                    }
                }

                var oldPool: SoundPool? = null
                var sampleId = 0
                var shouldAbort = false
                synchronized(clickLock) {
                    if (loadingClickUri != targetUri) {
                        shouldAbort = true
                    } else {
                        oldPool = clickSoundPool
                        clickSoundPool = newPool
                        loadedClickUri = targetUri
                        loadingClickUri = null
                        clickSoundReady = false
                        sampleId = newPool.load(cached.absolutePath, 1)
                        loadedClickSoundId = sampleId
                    }
                }
                if (shouldAbort || sampleId == 0) {
                    runCatching { newPool.release() }
                    if (!shouldAbort && sampleId == 0) {
                        synchronized(clickLock) {
                            if (clickSoundPool === newPool) {
                                clickSoundPool = null
                                loadedClickUri = null
                                loadedClickSoundId = 0
                                clickSoundReady = false
                                pendingClickPlay = false
                            }
                        }
                    }
                }
                oldPool?.let { runCatching { it.release() } }
            } catch (e: Exception) {
                synchronized(clickLock) {
                    if (loadingClickUri == targetUri) {
                        loadingClickUri = null
                        pendingClickPlay = false
                    }
                }
                GTRadioLogger.log("DynamicThemeManager: Failed loading click SFX", e)
            }
        }
    }

    private fun resolveClickExtension(context: Context, uri: Uri): String {
        val candidate = (DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "").lowercase()
        return when {
            candidate.endsWith(".ogg") -> ".ogg"
            candidate.endsWith(".mp3") -> ".mp3"
            else -> ".wav"
        }
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

    private fun optStringAny(obj: JSONObject, defaultValue: String, vararg keys: String): String {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val value = obj.optString(k, "")
                if (value.isNotBlank()) return value
            }
        }
        return defaultValue
    }

    private fun optBooleanAny(obj: JSONObject, defaultValue: Boolean, vararg keys: String): Boolean {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                return obj.optBoolean(k, defaultValue)
            }
        }
        return defaultValue
    }

    private fun optIntAny(obj: JSONObject, defaultValue: Int, vararg keys: String): Int {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                return obj.optInt(k, defaultValue)
            }
        }
        return defaultValue
    }

    private fun optFloatAny(obj: JSONObject, defaultValue: Float, vararg keys: String): Float {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                return obj.optDouble(k, defaultValue.toDouble()).toFloat()
            }
        }
        return defaultValue
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int {
        return try { Color.parseColor(hex.trim()) } catch (_: Exception) { fallback }
    }
}
