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
import android.os.SystemClock
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.FileUtils.listSimpleFiles
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Random
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
    val hasThemeJson: Boolean = true
) {
    val hasFullThemeUi: Boolean
        get() = hasThemeJson && (portraitBgUris.isNotEmpty() || landscapeBgUris.isNotEmpty())
}

object DynamicThemeManager {
    private val packCache = HashMap<String, DynamicThemePack>()
    private val iconCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val scanLock = Any()
    private var scannedPacks: List<DynamicThemePack>? = null
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
        scannedPacks = null
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
            val packs = scanAllThemePacks(context, rootDoc)
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
                scannedPacks = scanAllThemePacks(context, null)
            } catch (_: Exception) {}
        }
    }

    fun normalizeStationKey(raw: String): String {
        val base = raw.lowercase().trim()
            .replace(Regex("\\.(png|jpg|jpeg|webp|bmp|gif|svg)$"), "")
            .replace(Regex("(_on_icon|_off_icon|_icon|_on|_off|logo|radio_)"), "")
            .replace(Regex("[^a-z0-9]"), "")
        stationAliases[base]?.let { return it }
        val noTrailingDigits = base.replace(Regex("[0-9]+$"), "")
        if (noTrailingDigits.isNotBlank()) {
            stationAliases[noTrailingDigits]?.let { return it }
        }
        return base
    }

    fun resolveStationIconUri(iconMap: Map<String, Uri>, stationName: String): Uri? {
        if (iconMap.isEmpty()) return null
        val norm = normalizeStationKey(stationName)
        iconMap[norm]?.let { return it }
        for ((k, u) in iconMap) {
            if (k.length >= 3 && (norm.contains(k) || k.contains(norm))) {
                return u
            }
        }
        return null
    }

    fun findNoisePackForSeries(context: Context, seriesName: String?): DynamicThemePack? {
        if (seriesName.isNullOrBlank() || !isDynamicThemeEnabled(context)) return null
        val exact = findThemeForSeries(context, seriesName) ?: return null
        if (!exact.hasThemeJson || exact.noiseSoundUris.isEmpty()) return null
        return exact
    }

    fun getCachedThemeForSeries(seriesName: String): DynamicThemePack? {
        if (seriesName.isBlank()) return null
        synchronized(packCache) {
            packCache[seriesName]?.let { return it }
        }
        val packs = scannedPacks ?: return null
        val matched = packs.firstOrNull { matchesSeries(seriesName, it.seriesName) }
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
        val matched = packs.firstOrNull { matchesSeries(seriesName, it.seriesName) }
        if (matched != null) {
            synchronized(packCache) {
                packCache[seriesName] = matched
            }
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

        val gIs5 = g.contains("GTA V") || g.contains("GTA 5") || gNorm.contains("GTAV")
        val pIs5 = p.contains("GTA V") || p.contains("GTA 5") || pNorm.contains("GTAV")
        if (gIs5 && pIs5) return true

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

    private fun scanAllThemePacks(context: Context, libraryRootDoc: DocumentFile?): List<DynamicThemePack> = synchronized(scanLock) {
        scannedPacks?.let { return it }
        val results = ArrayList<DynamicThemePack>()
        val seenNames = HashSet<String>()
        val root = resolveDynamicThemeRoot(context, libraryRootDoc) ?: return results

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
                    val fontFiles = subChildren.filter { !it.isDirectory && it.name.lowercase().endsWith(".ttf") }
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
                    subChildren
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
            if (isGta4Folder || offIcons.isNotEmpty()) "strip" else "grid"
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
            layoutStyle = layoutStyle,
            hasThemeJson = true
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

        if (pack.noiseSoundUris.isEmpty() || !pack.hasThemeJson || !isDynamicThemeEnabled(context)) {
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
