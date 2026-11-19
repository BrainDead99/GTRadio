package com.sc.gtradio.media.stations

import java.util.UUID

import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE

import GTRadioPlayer

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.FileUtils
import com.sc.gtradio.common.FileUtils.listSimpleFiles
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.common.SimpleFileInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class StationGroup(val folderDoc: DocumentFile, private val context: Context) {

    lateinit var mediaItem: MediaBrowserCompat.MediaItem
    var stationList: ArrayList<MediaBrowserCompat.MediaItem> = ArrayList()
    var generation: Int = -1
    var groupName: String = ""

    var isGen1RawGroup: Boolean = false
        private set
    private val gen1StationFiles = HashMap<String, SimpleFileInfo>()
    private var gen1GameCode: Int = 0

    var isGtaIvRpfGroup: Boolean = false
        private set
    private val gtaIvStationArchives = HashMap<String, GtaIvRpfArchive>()
    private val gtaIvStationKeys = HashMap<String, String>()
    private var gtaIvExtraTracksArchive: GtaIvRpfArchive? = null
    private var gtaIvEp1SfxArchive: GtaIvRpfArchive? = null
    private var gtaIvEp2SfxArchive: GtaIvRpfArchive? = null
    private var gtaIvAdvertsArchive: GtaIvRpfArchive? = null
    private var gtaIvNewsArchive: GtaIvRpfArchive? = null
    private var gtaIvWeatherArchive: GtaIvRpfArchive? = null

    var isGtaVRpfGroup: Boolean = false
        private set
    private val gtaVStationArchives = HashMap<String, GtaVRpfArchive>()
    private val gtaVStationKeys = HashMap<String, String>()
    private var gtaVAdvertsArchive: GtaVRpfArchive? = null
    private var gtaVNewsArchive: GtaVRpfArchive? = null
    var mediaId: String = ""
    var isValid: Boolean = true
    var isRawSaGroup: Boolean = false
    var rawContainerType: SaContainerType? = null
    private val rawStreamUris = HashMap<String, Uri>()
    private val archiveCache = HashMap<String, SaStreamArchive>()

    companion object {
        private val SA_STREAM_CODES = setOf("CH", "CO", "CR", "DS", "HC", "MH", "MR", "NJ", "RE", "RG", "TK", "ADVERTS")
        private val SKIP_DIRS = setOf(
            "SFX", "CONFIG", "ANIM", "TEXT", "MODELS", "DATA", "MOVIES",
            "DYNAMIC THEME", "BACKGROUND", "FONT", "ICON", "SONGS",
            "DJ CHATTER", "ANNOUNCER", "IDS", "ADVERTS", "WEATHER",
            "MORNING", "EVENING", "NIGHT", "OTHER", "FOG", "RAIN", "STORM", "SUN"
        )

        fun scanRootDirectory(rootDoc: DocumentFile?, context: Context): ArrayList<StationGroup> {
            GTRadioLogger.init(context)
            val results = ArrayList<StationGroup>()
            if (rootDoc == null || !rootDoc.isDirectory) {
                GTRadioLogger.log("scanRootDirectory: rootDoc is null or not a directory (${rootDoc?.uri})")
                return results
            }

            GTRadioLogger.log("scanRootDirectory: Starting scan at '${rootDoc.name}' (${rootDoc.uri})")
            scanRecursive(rootDoc, context, results, depth = 0, maxDepth = 7)
            results.sortBy { it.groupName }
            GTRadioLogger.log("scanRootDirectory: Finished scan. Found ${results.size} station group(s): ${results.map { it.groupName }}")
            return results
        }

        private fun scanRecursive(
            dir: DocumentFile,
            context: Context,
            results: ArrayList<StationGroup>,
            depth: Int,
            maxDepth: Int
        ) {
            if (depth > maxDepth) return
            val dirNameUpper = (dir.name ?: "").uppercase()
            if (depth > 0 && dirNameUpper in SKIP_DIRS) return

            val candidate = StationGroup(dir, context)
            if (candidate.generation in 1..3 && candidate.stationList.isNotEmpty()) {
                GTRadioLogger.log("scanRecursive[d=$depth]: Found valid group '${candidate.groupName}' with ${candidate.stationList.size} stations at '${dir.name}'")
                results.add(candidate)
                // Do not descend into a valid station group's own station subfolders
                return
            }

            if (depth == maxDepth) return

            val children = try {
                dir.listFiles().filter { it.isDirectory }
            } catch (e: Exception) {
                GTRadioLogger.log("scanRecursive[d=$depth]: Failed listing children of '${dir.name}'", e)
                emptyList()
            }

            for (child in children) {
                val cName = (child.name ?: "").uppercase()
                if (cName in SKIP_DIRS || cName.startsWith(".")) continue
                scanRecursive(child, context, results, depth + 1, maxDepth)
            }
        }
    }

    
init {
        val rpfScanFiles = try {
            folderDoc.listSimpleFiles(context)
        } catch (e: Exception) {
            emptyArray()
        }
        val detectedGtaVRpfFiles = detectGtaVRpfs(rpfScanFiles)
        val detectedRpfFiles = if (detectedGtaVRpfFiles.isEmpty()) detectGtaIvRpfs(rpfScanFiles) else emptyList()
        val detectedGen1Pair = if (detectedGtaVRpfFiles.isEmpty() && detectedRpfFiles.isEmpty()) detectGen1RawFiles(rpfScanFiles) else null
        if (detectedGtaVRpfFiles.isNotEmpty()) {
            setupGtaVRpfGroup(rpfScanFiles, detectedGtaVRpfFiles)
        } else if (detectedRpfFiles.isNotEmpty()) {
            setupGtaIvRpfGroup(rpfScanFiles, detectedRpfFiles)
        } else if (detectedGen1Pair != null) {
            setupGen1RawGroup(rpfScanFiles, detectedGen1Pair.first, detectedGen1Pair.second)
        } else {

        val simpleFiles = try {
            folderDoc.listSimpleFiles(context)
        } catch (e: Exception) {
            GTRadioLogger.log("StationGroup.init: listSimpleFiles failed on '${folderDoc.name}', falling back to listFiles()", e)
            try {
                folderDoc.listFiles().map {
                    SimpleFileInfo(it.uri, it.name ?: "", it.uri.lastPathSegment ?: "", it.isDirectory)
                }.toTypedArray()
            } catch (_: Exception) {
                emptyArray()
            }
        }

        if (!detectRawSaStreams(simpleFiles)) {
            setupDataFromJson(folderDoc, simpleFiles)
        }

        mediaItem = getMediaItem(folderDoc, simpleFiles, groupName)
        stationList = if (isRawSaGroup || isGtaIvRpfGroup || isGen1RawGroup || isGtaVRpfGroup) {
            getRawSaStationsList()
        } else {
            getStationsList(folderDoc)
        }
    
        }
    }

    private val gen1At3Archives = HashMap<String, At3AudioArchive>()

    private fun detectGen1RawFiles(files: Array<SimpleFileInfo>): Pair<Int, List<SimpleFileInfo>>? {
        val vcKeys = Gen1MetadataRepository.viceCityStationsByFile.keys
        val vcMatched = files.filter { !it.isDirectory && vcKeys.contains(it.name.lowercase()) }
        if (vcMatched.size >= 2) {
            GTRadioLogger.log("detectGen1RawFiles: Detected ${vcMatched.size} GTA Vice City (.adf) stations in '${folderDoc.name}'")
            return Pair(1, vcMatched)
        }
        val gta3Keys = Gen1MetadataRepository.gta3StationsByFile.keys
        val gta3Matched = files.filter { !it.isDirectory && gta3Keys.contains(it.name.lowercase()) }
        if (gta3Matched.size >= 2) {
            GTRadioLogger.log("detectGen1RawFiles: Detected ${gta3Matched.size} GTA III (.wav) stations in '${folderDoc.name}'")
            return Pair(2, gta3Matched)
        }
        val lowerNames = files.filter { !it.isDirectory }.map { it.name.lowercase() }.toSet()
        val isLcsFolder = lowerNames.any { it in setOf("lcfr.at3", "lcj.at3", "mundo.at3", "double.at3", "kjah.at3", "lips.at3", "head.at3", "msx.at3", "rise.at3") } ||
            folderDoc.name?.uppercase()?.contains("LCS") == true
        if (isLcsFolder) {
            val lcsKeys = Gen1MetadataRepository.lcsStationsByFile.keys
            val lcsMatched = files.filter { !it.isDirectory && lcsKeys.contains(it.name.lowercase()) }
            if (lcsMatched.size >= 2) {
                GTRadioLogger.log("detectGen1RawFiles: Detected ${lcsMatched.size} GTA: Liberty City Stories (.AT3) stations in '${folderDoc.name}'")
                return Pair(3, lcsMatched)
            }
        }
        val vcsKeys = Gen1MetadataRepository.vcsStationsByFile.keys
        val vcsMatched = files.filter { !it.isDirectory && vcsKeys.contains(it.name.lowercase()) }
        if (vcsMatched.size >= 2) {
            GTRadioLogger.log("detectGen1RawFiles: Detected ${vcsMatched.size} GTA: Vice City Stories (.AT3) stations in '${folderDoc.name}'")
            return Pair(4, vcsMatched)
        }
        return null
    }

    private fun setupGen1RawGroup(allFiles: Array<SimpleFileInfo>, gameCode: Int, stationFiles: List<SimpleFileInfo>) {
        isGen1RawGroup = true
        gen1GameCode = gameCode
        generation = 1
        groupName = when (gameCode) {
            1 -> "GTA Vice City (ADF Audio)"
            2 -> "GTA III (WAV Audio)"
            3 -> "GTA: Liberty City Stories (AT3 Audio)"
            4 -> "GTA: Vice City Stories (AT3 Audio)"
            else -> "GTA Radio (Gen1 Audio)"
        }
        mediaId = UUID.randomUUID().toString()

        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = allFiles.find { !it.isDirectory && it.name.uppercase().contains("LOGO") }?.uri
            ?: themePack?.groupLogoUri
        val logoBitmap = DynamicThemeManager.loadBitmap(context, logoUri, 512)
            ?: FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

        val sortedFiles = stationFiles.sortedBy {
            Gen1MetadataRepository.getStationInfo(it.name, gameCode)?.displayName ?: it.name
        }

        for (stFile in sortedFiles) {
            val info = Gen1MetadataRepository.getStationInfo(stFile.name, gameCode)
            val stDisplayName = info?.displayName ?: stFile.name
            val stMediaId = UUID.randomUUID().toString()

            val iconUri = themePack?.let { DynamicThemeManager.resolveStationIconUri(it.stationIconUris, stDisplayName) }
            val iconBmp = DynamicThemeManager.loadBitmap(context, iconUri, 512) ?: logoBitmap

            val meta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, stMediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stDisplayName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stDisplayName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, iconBmp)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, iconBmp)
                .build()

            stationList.add(MediaBrowserCompat.MediaItem(meta.description, FLAG_PLAYABLE))
            gen1StationFiles[stMediaId] = stFile
            gen1StationFiles[stDisplayName] = stFile
        }

        if (stationList.isNotEmpty()) {
            val groupMeta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
                .build()
            mediaItem = MediaBrowserCompat.MediaItem(groupMeta.description, FLAG_BROWSABLE)
            isValid = true
        }
    }

    fun createGen1RawStation(
        stationGroupId: String,
        stMediaId: String,
        stMediaItem: MediaBrowserCompat.MediaItem,
        player: GTRadioPlayer,
        ctx: Context,
        adsEnabled: Boolean,
        weatherChatterEnabled: Boolean,
        newsReportsEnabled: Boolean
    ): RadioStation {
        val titleKey = stMediaItem.description.title?.toString() ?: ""
        val fileInfo = gen1StationFiles[stMediaId]
            ?: gen1StationFiles[titleKey]
            ?: gen1StationFiles.values.first()
        val stInfo = Gen1MetadataRepository.getStationInfo(fileInfo.name, gen1GameCode)

        if (gen1GameCode == 3 || gen1GameCode == 4) {
            val arch = gen1At3Archives.getOrPut(fileInfo.name.lowercase()) {
                At3AudioArchive(ctx, fileInfo.name, fileInfo.uri)
            }
            return Gen1At3RadioStation(
                stationGroupId = stationGroupId,
                mediaId = stMediaId,
                mediaItem = stMediaItem,
                archive = arch,
                stationInfo = stInfo,
                isVcs = (gen1GameCode == 4),
                player = player,
                context = ctx,
                adsEnabled = adsEnabled,
                weatherChatterEnabled = weatherChatterEnabled,
                newsReportsEnabled = newsReportsEnabled
            )
        }

        return Gen1RawRadioStation(
            stationGroupId = stationGroupId,
            mediaId = stMediaId,
            mediaItem = stMediaItem,
            fileName = fileInfo.name,
            sourceUri = fileInfo.uri,
            isVcAdf = (gen1GameCode == 1),
            stationInfo = stInfo,
            player = player,
            context = ctx,
            adsEnabled = adsEnabled,
            weatherChatterEnabled = weatherChatterEnabled,
            newsReportsEnabled = newsReportsEnabled
        )
    }

    private fun detectGtaIvRpfs(files: Array<SimpleFileInfo>): List<SimpleFileInfo> {
        val knownStationRpfs = GtaIvMetadataRepository.stationNamesByRpf.keys
        val matched = files.filter { !it.isDirectory && knownStationRpfs.contains(it.name.lowercase()) }
        if (matched.isNotEmpty()) {
            GTRadioLogger.log("detectGtaIvRpfs: Detected ${matched.size} GTA IV/EFLC RPF stations in '${folderDoc.name}'")
        }
        return matched
    }

    private fun setupGtaIvRpfGroup(allFiles: Array<SimpleFileInfo>, stationRpfFiles: List<SimpleFileInfo>) {
        GtaIvMetadataRepository.ensureLoaded(context)
        isGtaIvRpfGroup = true
        generation = 3

        // Automatic First-Startup Key Discovery for GTA IV / EFLC
        if (!GtaIvKeyManager.isUnlocked(context)) {
            tryAutoImportGtaIvKeys()
        }
        val isEflc = stationRpfFiles.any { it.name.lowercase() in setOf("radio_vcfm.rpf", "radio_ramjamfm.rpf", "radio_meditation.rpf") } ||
            allFiles.any { it.name.lowercase() in setOf("ep1_sfx.rpf", "ep2_sfx.rpf") } ||
            folderDoc.name?.uppercase()?.contains("EFLC") == true
        groupName = if (isEflc) "GTA: Episodes from Liberty City (RPF Audio)" else "GTA IV (RPF Audio)"
        mediaId = UUID.randomUUID().toString()

        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = allFiles.find { !it.isDirectory && it.name.uppercase().contains("LOGO") }?.uri
            ?: themePack?.groupLogoUri
        val logoBitmap = DynamicThemeManager.loadBitmap(context, logoUri, 512)
            ?: FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

        for (f in allFiles) {
            if (f.isDirectory) continue
            when (f.name.lowercase()) {
                "radio_extratracks.rpf" -> gtaIvExtraTracksArchive = GtaIvRpfArchive(context, f.name, f.uri)
                "ep1_sfx.rpf" -> gtaIvEp1SfxArchive = GtaIvRpfArchive(context, f.name, f.uri)
                "ep2_sfx.rpf" -> gtaIvEp2SfxArchive = GtaIvRpfArchive(context, f.name, f.uri)
                "radio_adverts.rpf" -> gtaIvAdvertsArchive = GtaIvRpfArchive(context, f.name, f.uri)
                "radio_news.rpf" -> gtaIvNewsArchive = GtaIvRpfArchive(context, f.name, f.uri)
                "radio_weather.rpf" -> gtaIvWeatherArchive = GtaIvRpfArchive(context, f.name, f.uri)
            }
        }

        data class PendingRpfStation(val stationKey: String, val displayName: String, val archive: GtaIvRpfArchive)
        val pendingStations = ArrayList<PendingRpfStation>()
        val presentDisplayNames = HashSet<String>()

        for (stFile in stationRpfFiles) {
            val stKey = stFile.name.lowercase()
            val stDisplayName = GtaIvMetadataRepository.stationNamesByRpf[stKey] ?: stFile.name
            val arch = GtaIvRpfArchive(context, stFile.name, stFile.uri)
            pendingStations.add(PendingRpfStation(stKey, stDisplayName, arch))
            presentDisplayNames.add(stDisplayName)
        }

        if (isEflc && gtaIvEp1SfxArchive != null) {
            for ((vKey, vDisplayName) in GtaIvMetadataRepository.eflcVirtualStationsEp1) {
                if (vDisplayName !in presentDisplayNames) {
                    pendingStations.add(PendingRpfStation(vKey, vDisplayName, gtaIvEp1SfxArchive!!))
                    presentDisplayNames.add(vDisplayName)
                }
            }
        }

        if (isEflc && gtaIvEp2SfxArchive != null) {
            for ((vKey, vDisplayName) in GtaIvMetadataRepository.eflcVirtualStationsEp2) {
                if (vDisplayName !in presentDisplayNames) {
                    pendingStations.add(PendingRpfStation(vKey, vDisplayName, gtaIvEp2SfxArchive!!))
                    presentDisplayNames.add(vDisplayName)
                }
            }
        }

        pendingStations.sortBy { it.displayName }

        for (item in pendingStations) {
            val stDisplayName = item.displayName
            val stMediaId = UUID.randomUUID().toString()

            val iconUri = themePack?.let { DynamicThemeManager.resolveStationIconUri(it.stationIconUris, stDisplayName) }
            val iconBmp = DynamicThemeManager.loadBitmap(context, iconUri, 512) ?: logoBitmap

            val meta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, stMediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stDisplayName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stDisplayName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, iconBmp)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, iconBmp)
                .build()

            stationList.add(MediaBrowserCompat.MediaItem(meta.description, FLAG_PLAYABLE))
            gtaIvStationArchives[stMediaId] = item.archive
            gtaIvStationArchives[stDisplayName] = item.archive
            gtaIvStationKeys[stMediaId] = item.stationKey
            gtaIvStationKeys[stDisplayName] = item.stationKey
        }

        if (stationList.isNotEmpty()) {
            val groupMeta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
                .build()
            mediaItem = MediaBrowserCompat.MediaItem(groupMeta.description, FLAG_BROWSABLE)
            isValid = true
        }
    }

    fun createGtaIvRpfStation(
        stationGroupId: String,
        stMediaId: String,
        stMediaItem: MediaBrowserCompat.MediaItem,
        player: GTRadioPlayer,
        ctx: Context,
        adsEnabled: Boolean,
        weatherChatterEnabled: Boolean,
        newsReportsEnabled: Boolean
    ): RadioStation {
        val titleKey = stMediaItem.description.title?.toString() ?: ""
        val arch = gtaIvStationArchives[stMediaId]
            ?: gtaIvStationArchives[titleKey]
            ?: gtaIvStationArchives.values.first()
        val stKey = gtaIvStationKeys[stMediaId]
            ?: gtaIvStationKeys[titleKey]
            ?: arch.rpfName.lowercase()
        return GtaIvRpfRadioStation(
            stationGroupId = stationGroupId,
            mediaId = stMediaId,
            mediaItem = stMediaItem,
            stationKey = stKey,
            stationArchive = arch,
            extraTracksArchive = gtaIvExtraTracksArchive,
            ep1SfxArchive = gtaIvEp1SfxArchive,
            ep2SfxArchive = gtaIvEp2SfxArchive,
            advertsArchive = gtaIvAdvertsArchive,
            newsArchive = gtaIvNewsArchive,
            weatherArchive = gtaIvWeatherArchive,
            player = player,
            context = ctx,
            adsEnabled = adsEnabled,
            weatherChatterEnabled = weatherChatterEnabled,
            newsReportsEnabled = newsReportsEnabled
        )
    }

    private fun detectGtaVRpfs(files: Array<SimpleFileInfo>): List<SimpleFileInfo> {
        val knownStationRpfs = GtaVMetadataRepository.stationInfoByRpf.keys
        val matched = files.filter { !it.isDirectory && knownStationRpfs.contains(it.name.lowercase()) }
        if (matched.isNotEmpty()) {
            GTRadioLogger.log("detectGtaVRpfs: Detected ${matched.size} GTA V RPF stations in '${folderDoc.name}'")
        }
        return matched
    }

    private fun setupGtaVRpfGroup(allFiles: Array<SimpleFileInfo>, stationRpfFiles: List<SimpleFileInfo>) {
        isGtaVRpfGroup = true
        generation = 3
        groupName = "GTA V (RPF Audio)"
        mediaId = UUID.randomUUID().toString()

        // Automatic First-Startup Key Discovery
        if (!GtaVKeyManager.isUnlocked(context)) {
            tryAutoImportKeys()
        }

        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = allFiles.find { !it.isDirectory && it.name.uppercase().contains("LOGO") }?.uri
            ?: themePack?.groupLogoUri
        val logoBitmap = DynamicThemeManager.loadBitmap(context, logoUri, 512)
            ?: FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

        for (f in allFiles) {
            if (f.isDirectory) continue
            when (f.name.lowercase()) {
                "radio_adverts.rpf" -> gtaVAdvertsArchive = GtaVRpfArchive(context, f.name, f.uri)
                "radio_news.rpf" -> gtaVNewsArchive = GtaVRpfArchive(context, f.name, f.uri)
            }
        }

        // Sort in official station order (RADIO_01 through RADIO_18)
        val sortedStationFiles = stationRpfFiles.sortedBy { stFile ->
            val lower = stFile.name.lowercase()
            val orderMatch = Regex("radio_(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            orderMatch ?: 999
        }

        for (stFile in sortedStationFiles) {
            val stKey = stFile.name.lowercase()
            val info = GtaVMetadataRepository.getStationInfo(stKey)
            val stDisplayName = info?.stationName ?: stFile.name
            val stMediaId = UUID.randomUUID().toString()

            val arch = GtaVRpfArchive(context, stFile.name, stFile.uri)

            val iconUri = themePack?.let { DynamicThemeManager.resolveStationIconUri(it.stationIconUris, stDisplayName) }
            val iconBmp = DynamicThemeManager.loadBitmap(context, iconUri, 512) ?: logoBitmap

            val meta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, stMediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stDisplayName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stDisplayName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, iconBmp)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, iconBmp)
                .build()

            stationList.add(MediaBrowserCompat.MediaItem(meta.description, FLAG_PLAYABLE))
            gtaVStationArchives[stMediaId] = arch
            gtaVStationArchives[stDisplayName] = arch
            gtaVStationKeys[stMediaId] = stKey
            gtaVStationKeys[stDisplayName] = stKey
        }

        if (stationList.isNotEmpty()) {
            val groupMeta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
                .build()
            mediaItem = MediaBrowserCompat.MediaItem(groupMeta.description, FLAG_BROWSABLE)
            isValid = true
        }
    }

    private fun tryAutoImportGtaIvKeys() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // (a) Check saved Settings key folder URI
        val savedKeyUri = prefs.getString("game_key_folder_uri", null)
            ?: prefs.getString("gtav_key_folder_uri", null)
        if (!savedKeyUri.isNullOrBlank()) {
            try {
                DocumentFile.fromTreeUri(context, Uri.parse(savedKeyUri))?.let { keyDoc ->
                    if (keyDoc.isDirectory) {
                        val res = GtaIvKeyManager.importFromFolder(context, keyDoc)
                        if (res is GtaIvKeyManager.ImportResult.Success) {
                            GTRadioLogger.log("StationGroup[GTA IV]: Successfully unlocked keys from saved settings folder: ${keyDoc.uri}")
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                GTRadioLogger.log("StationGroup[GTA IV]: Auto-import from saved key folder failed", e)
            }
        }

        // (b) Check GTA IV / EFLC folder itself
        try {
            val res = GtaIvKeyManager.importFromFolder(context, folderDoc)
            if (res is GtaIvKeyManager.ImportResult.Success) {
                GTRadioLogger.log("StationGroup[GTA IV]: Successfully unlocked keys from audio folder: ${folderDoc.uri}")
                return
            }
        } catch (e: Exception) {
            GTRadioLogger.log("StationGroup[GTA IV]: Auto-import from audio folder failed", e)
        }

        // (c) Check parent and parent's parent (for nested pc/audio/sfx layouts)
        try {
            folderDoc.parentFile?.let { p1 ->
                if (p1.isDirectory) {
                    val res1 = GtaIvKeyManager.importFromFolder(context, p1)
                    if (res1 is GtaIvKeyManager.ImportResult.Success) {
                        GTRadioLogger.log("StationGroup[GTA IV]: Successfully unlocked keys from parent folder: ${p1.uri}")
                        return
                    }
                    p1.parentFile?.let { p2 ->
                        if (p2.isDirectory) {
                            val res2 = GtaIvKeyManager.importFromFolder(context, p2)
                            if (res2 is GtaIvKeyManager.ImportResult.Success) {
                                GTRadioLogger.log("StationGroup[GTA IV]: Successfully unlocked keys from grandparent folder: ${p2.uri}")
                                return
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            GTRadioLogger.log("StationGroup[GTA IV]: Auto-import from parent folders failed", e)
        }
    }

    private fun tryAutoImportKeys() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // (a) Check saved Settings key folder URI
        val savedKeyUri = prefs.getString("game_key_folder_uri", null)
            ?: prefs.getString("gtav_key_folder_uri", null)
        if (!savedKeyUri.isNullOrBlank()) {
            try {
                DocumentFile.fromTreeUri(context, Uri.parse(savedKeyUri))?.let { keyDoc ->
                    if (keyDoc.isDirectory) {
                        val res = GtaVKeyManager.importFromFolder(context, keyDoc, allowMagicDownload = true)
                        if (res is ImportResult.Success) {
                            GTRadioLogger.log("StationGroup[GTA V]: Successfully unlocked keys from saved settings folder: ${keyDoc.uri}")
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                GTRadioLogger.log("StationGroup[GTA V]: Auto-import from saved key folder failed", e)
            }
        }

        // (b) Check GTA V audio folder itself (e.g. where GTA5.exe or .dat files live alongside RADIO_*.rpf)
        try {
            val res = GtaVKeyManager.importFromFolder(context, folderDoc, allowMagicDownload = true)
            if (res is ImportResult.Success) {
                GTRadioLogger.log("StationGroup[GTA V]: Successfully unlocked keys from audio folder: ${folderDoc.uri}")
                return
            }
        } catch (e: Exception) {
            GTRadioLogger.log("StationGroup[GTA V]: Auto-import from audio folder failed", e)
        }

        // (c) Check parent/root library folder (and grandparent folder for nested layouts)
        try {
            folderDoc.parentFile?.let { parentDoc ->
                if (parentDoc.isDirectory) {
                    val res = GtaVKeyManager.importFromFolder(context, parentDoc, allowMagicDownload = true)
                    if (res is ImportResult.Success) {
                        GTRadioLogger.log("StationGroup[GTA V]: Successfully unlocked keys from parent folder: ${parentDoc.uri}")
                        return
                    }
                    parentDoc.parentFile?.let { grandDoc ->
                        if (grandDoc.isDirectory) {
                            val res2 = GtaVKeyManager.importFromFolder(context, grandDoc, allowMagicDownload = true)
                            if (res2 is ImportResult.Success) {
                                GTRadioLogger.log("StationGroup[GTA V]: Successfully unlocked keys from grandparent folder: ${grandDoc.uri}")
                                return
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            GTRadioLogger.log("StationGroup[GTA V]: Auto-import from parent folder failed", e)
        }
    }

    fun createGtaVRpfStation(
        stationGroupId: String,
        stMediaId: String,
        stMediaItem: MediaBrowserCompat.MediaItem,
        player: GTRadioPlayer,
        ctx: Context,
        adsEnabled: Boolean,
        weatherChatterEnabled: Boolean,
        newsReportsEnabled: Boolean
    ): RadioStation {
        val titleKey = stMediaItem.description.title?.toString() ?: ""
        val arch = gtaVStationArchives[stMediaId]
            ?: gtaVStationArchives[titleKey]
            ?: gtaVStationArchives.values.first()
        val stKey = gtaVStationKeys[stMediaId]
            ?: gtaVStationKeys[titleKey]
            ?: arch.rpfName.lowercase()
        return GtaVRpfRadioStation(
            stationGroupId = stationGroupId,
            mediaId = stMediaId,
            mediaItem = stMediaItem,
            stationKey = stKey,
            stationArchive = arch,
            advertsArchive = gtaVAdvertsArchive,
            newsArchive = gtaVNewsArchive,
            player = player,
            context = ctx,
            adsEnabled = adsEnabled,
            weatherChatterEnabled = weatherChatterEnabled,
            newsReportsEnabled = newsReportsEnabled
        )
    }

    private fun detectRawSaStreams(files: Array<SimpleFileInfo>): Boolean {
        val oswMatches = HashMap<String, Uri>()
        val pcMatches = HashMap<String, Uri>()

        for (f in files) {
            if (f.isDirectory) continue
            val cleanName = f.name.trim().uppercase()
            if (cleanName.endsWith(".OSW")) {
                val code = cleanName.removeSuffix(".OSW")
                if (code in SA_STREAM_CODES) {
                    oswMatches[code] = f.uri
                }
            } else if (!cleanName.contains(".")) {
                if (cleanName in SA_STREAM_CODES) {
                    pcMatches[cleanName] = f.uri
                }
            } else if (cleanName.endsWith(".WAV") || cleanName.endsWith(".OGG")) {
                val code = cleanName.substringBeforeLast(".")
                if (code in SA_STREAM_CODES) {
                    pcMatches[code] = f.uri
                }
            }
        }

        val radioOswCount = oswMatches.keys.count { it != "ADVERTS" }
        val radioPcCount = pcMatches.keys.count { it != "ADVERTS" }

        if (radioOswCount >= 1) {
            isRawSaGroup = true
            rawContainerType = SaContainerType.MOBILE_OSW
            rawStreamUris.putAll(oswMatches)
            generation = 2
            groupName = "GTA San Andreas (Mobile OSW)"
            GTRadioLogger.log("detectRawSaStreams: Detected Mobile OSW in '${folderDoc.name}' (${oswMatches.keys})")
            return true
        } else if (radioPcCount >= 1) {
            isRawSaGroup = true
            rawContainerType = SaContainerType.PC_STREAM
            rawStreamUris.putAll(pcMatches)
            generation = 2
            groupName = "GTA San Andreas (PC Streams)"
            GTRadioLogger.log("detectRawSaStreams: Detected PC Streams in '${folderDoc.name}' (${pcMatches.keys})")
            return true
        }
        return false
    }

    @Synchronized
    fun getOrCreateArchive(streamCode: String): SaStreamArchive? {
        val code = streamCode.uppercase()
        archiveCache[code]?.let { return it }
        val uri = rawStreamUris[code] ?: return null
        val type = rawContainerType ?: return null
        val arch = SaStreamArchive(context, code, uri, type)
        archiveCache[code] = arch
        return arch
    }

    private fun getRawSaStationsList(): ArrayList<MediaBrowserCompat.MediaItem> {
        val list = ArrayList<MediaBrowserCompat.MediaItem>()
        SaMetadataRepository.ensureLoaded(context)

        for ((code, _) in rawStreamUris) {
            if (code == "ADVERTS") continue
            val stMeta = SaMetadataRepository.getByStreamCode(context, code)
            val stName = stMeta?.stationName ?: SaMetadataRepository.defaultStations[code]?.first ?: code
            val mediaId = "${folderDoc.uri}#SA_RAW#$code"

            val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
            val iconUri = themePack?.let { DynamicThemeManager.resolveStationIconUri(it.stationIconUris, stName) }
            val iconBmp = DynamicThemeManager.loadBitmap(context, iconUri, 512)
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, iconBmp)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, iconBmp)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, stName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, groupName)
                .build()
            list.add(MediaBrowserCompat.MediaItem(metadata.description, FLAG_PLAYABLE))
        }
        list.sortBy { it.description.title.toString() }
        return list
    }

    private fun getMediaItem(folderDoc: DocumentFile, simpleFiles: Array<SimpleFileInfo>, groupName: String): MediaBrowserCompat.MediaItem {
        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = simpleFiles.find { x -> !x.isDirectory && x.name.uppercase().contains("LOGO") }?.uri ?: themePack?.groupLogoUri
        val logoBitmap = DynamicThemeManager.loadBitmap(context, logoUri, 512) ?: FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, folderDoc.uri.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, groupName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, groupName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "")
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, "")
            .build()
        return MediaBrowserCompat.MediaItem(
            metadata.description,
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun setupDataFromJson(folderDoc: DocumentFile, simpleFiles: Array<SimpleFileInfo>) {
        val jsonUri = simpleFiles.find { !it.isDirectory && it.name.equals("stationGroupInfo.json", ignoreCase = true) }?.uri
            ?: folderDoc.findFile("stationGroupInfo.json")?.uri
            ?: return

        var jsonString = ""
        try {
            val stringBuilder = StringBuilder()
            context.contentResolver.openInputStream(jsonUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        stringBuilder.append(line)
                        line = reader.readLine()
                    }
                }
            }
            jsonString = stringBuilder.toString()
        } catch (e: Exception) {
            GTRadioLogger.log("setupDataFromJson: Error reading stationGroupInfo.json in '${folderDoc.name}'", e)
        }

        if (jsonString.isBlank()) return
        val jsonObj = JSONObject(jsonString)
        generation = try { jsonObj.getInt("generation") } catch (_: Exception) { -1 }
        groupName = try { jsonObj.getString("name") } catch (_: Exception) { folderDoc.name ?: "" }
    }

    private fun getStationsList(folderDoc: DocumentFile): ArrayList<MediaBrowserCompat.MediaItem> {
        val list = ArrayList<MediaBrowserCompat.MediaItem>()
        if (generation !in 1..3) return list

        val subDirs = folderDoc.listFiles().filter { x -> x.isDirectory }
        for (subDir in subDirs) {
            if (generation in 2..3 && subDir.uri.lastPathSegment?.uppercase()?.contains("ADVERTS") == true) continue
            if (generation == 3 && subDir.uri.lastPathSegment?.uppercase()?.contains("NEWS") == true) continue
            if (generation == 3 && subDir.uri.lastPathSegment?.uppercase()?.contains("WEATHER") == true) continue

            val stationName = subDir.name ?: ""
            val logoUri = subDir.listSimpleFiles(context).find { x -> !x.isDirectory && x.name.uppercase().contains("LOGO") }?.uri
            val logoBitmap = FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

            if (stationName.isNotBlank()) {
                val metadata = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, subDir.uri.toString())
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stationName)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, stationName)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, groupName)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, groupName)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, groupName)
                    .build()
                val item = MediaBrowserCompat.MediaItem(metadata.description, FLAG_PLAYABLE)
                list.add(item)
            }
        }
        list.sortBy { x -> x.description.title.toString() }
        return list
    }

    fun getStationIndex(stationMediaId: String): Int {
        return stationList.indexOfFirst { x -> x.mediaId == stationMediaId }
    }

    fun getPlaybackActionsForStation(stationIndex: Int): Long {
        return getPlaybackActions((stationIndex < stationList.lastIndex), (stationIndex > 0))
    }

    private fun getPlaybackActions(hasNext: Boolean = false, hasPrevious: Boolean = false): Long {
        if (hasNext && hasPrevious) {
            return PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        } else if (hasNext) {
            return PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        } else if (hasPrevious) {
            return PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        return PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
    }
}
