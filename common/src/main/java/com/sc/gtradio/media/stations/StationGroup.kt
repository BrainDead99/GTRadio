package com.sc.gtradio.media.stations

import java.util.Locale
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

    var isRpf3Group: Boolean = false
        private set
    private val rpf3StationArchives = HashMap<String, Rpf3Archive>()
    private val rpf3StationKeys = HashMap<String, String>()
    private var rpf3ExtraTracksArchive: Rpf3Archive? = null
    private var rpf3Ep1SfxArchive: Rpf3Archive? = null
    private var rpf3Ep2SfxArchive: Rpf3Archive? = null
    private var rpf3AdvertsArchive: Rpf3Archive? = null
    private var rpf3NewsArchive: Rpf3Archive? = null
    private var rpf3WeatherArchive: Rpf3Archive? = null

    var isRpf7Group: Boolean = false
        private set
    private val rpf7StationArchives = HashMap<String, Rpf7Archive>()
    private val rpf7StationKeys = HashMap<String, String>()
    private var rpf7AdvertsArchive: Rpf7Archive? = null
    private var rpf7NewsArchive: Rpf7Archive? = null
    var mediaId: String = ""
    var isValid: Boolean = true
    var isGen2RawGroup: Boolean = false
    var rawContainerType: Gen2ContainerType? = null
    private val rawStreamUris = HashMap<String, Uri>()
    private val archiveCache = HashMap<String, Gen2StreamArchive>()

    companion object {
        private val GEN2_STREAM_CODES = setOf("CH", "CO", "CR", "DS", "HC", "MH", "MR", "NJ", "RE", "RG", "TK", "ADVERTS")
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
        val detectedRpf7Files = detectRpf7Rpfs(rpfScanFiles)
        val detectedRpfFiles = if (detectedRpf7Files.isEmpty()) detectRpf3Rpfs(rpfScanFiles) else emptyList()
        val detectedGen1Pair = if (detectedRpf7Files.isEmpty() && detectedRpfFiles.isEmpty()) detectGen1RawFiles(rpfScanFiles) else null
        if (detectedRpf7Files.isNotEmpty()) {
            setupRpf7Group(rpfScanFiles, detectedRpf7Files)
        } else if (detectedRpfFiles.isNotEmpty()) {
            setupRpf3Group(rpfScanFiles, detectedRpfFiles)
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

        if (!detectGen2RawStreams(simpleFiles)) {
            setupDataFromJson(folderDoc, simpleFiles)
        }

        mediaItem = getMediaItem(folderDoc, simpleFiles, groupName)
        stationList = if (isGen2RawGroup || isRpf3Group || isGen1RawGroup || isRpf7Group) {
            getGen2RawStationsList()
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
            isXorAdf = (gen1GameCode == 1),
            stationInfo = stInfo,
            player = player,
            context = ctx,
            adsEnabled = adsEnabled,
            weatherChatterEnabled = weatherChatterEnabled,
            newsReportsEnabled = newsReportsEnabled
        )
    }

    private fun detectRpf3Rpfs(files: Array<SimpleFileInfo>): List<SimpleFileInfo> {
        val knownStationRpfs = Rpf3MetadataRepository.stationNamesByRpf.keys
        val matched = files.filter { !it.isDirectory && knownStationRpfs.contains(it.name.lowercase()) }
        if (matched.isNotEmpty()) {
            GTRadioLogger.log("detectRpf3Rpfs: Detected ${matched.size} GTA IV/EFLC RPF stations in '${folderDoc.name}'")
        }
        return matched
    }

    private fun setupRpf3Group(allFiles: Array<SimpleFileInfo>, stationRpfFiles: List<SimpleFileInfo>) {
        Rpf3MetadataRepository.ensureLoaded(context)
        isRpf3Group = true
        generation = 3

        // Automatic First-Startup Key Discovery for GTA IV / EFLC
        if (!Rpf3KeyManager.isUnlocked(context)) {
            tryAutoImportRpf3Keys()
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
                "radio_extratracks.rpf" -> rpf3ExtraTracksArchive = Rpf3Archive(context, f.name, f.uri)
                "ep1_sfx.rpf" -> rpf3Ep1SfxArchive = Rpf3Archive(context, f.name, f.uri)
                "ep2_sfx.rpf" -> rpf3Ep2SfxArchive = Rpf3Archive(context, f.name, f.uri)
                "radio_adverts.rpf" -> rpf3AdvertsArchive = Rpf3Archive(context, f.name, f.uri)
                "radio_news.rpf" -> rpf3NewsArchive = Rpf3Archive(context, f.name, f.uri)
                "radio_weather.rpf" -> rpf3WeatherArchive = Rpf3Archive(context, f.name, f.uri)
            }
        }

        data class PendingRpfStation(val stationKey: String, val displayName: String, val archive: Rpf3Archive)
        val pendingStations = ArrayList<PendingRpfStation>()
        val presentDisplayNames = HashSet<String>()

        for (stFile in stationRpfFiles) {
            val stKey = stFile.name.lowercase()
            val stDisplayName = Rpf3MetadataRepository.stationNamesByRpf[stKey] ?: stFile.name
            val arch = Rpf3Archive(context, stFile.name, stFile.uri)
            pendingStations.add(PendingRpfStation(stKey, stDisplayName, arch))
            presentDisplayNames.add(stDisplayName)
        }

        if (isEflc && rpf3Ep1SfxArchive != null) {
            for ((vKey, vDisplayName) in Rpf3MetadataRepository.eflcVirtualStationsEp1) {
                if (vDisplayName !in presentDisplayNames) {
                    pendingStations.add(PendingRpfStation(vKey, vDisplayName, rpf3Ep1SfxArchive!!))
                    presentDisplayNames.add(vDisplayName)
                }
            }
        }

        if (isEflc && rpf3Ep2SfxArchive != null) {
            for ((vKey, vDisplayName) in Rpf3MetadataRepository.eflcVirtualStationsEp2) {
                if (vDisplayName !in presentDisplayNames) {
                    pendingStations.add(PendingRpfStation(vKey, vDisplayName, rpf3Ep2SfxArchive!!))
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
            rpf3StationArchives[stMediaId] = item.archive
            rpf3StationArchives[stDisplayName] = item.archive
            rpf3StationKeys[stMediaId] = item.stationKey
            rpf3StationKeys[stDisplayName] = item.stationKey
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

    fun createRpf3Station(
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
        val arch = rpf3StationArchives[stMediaId]
            ?: rpf3StationArchives[titleKey]
            ?: rpf3StationArchives.values.first()
        val stKey = rpf3StationKeys[stMediaId]
            ?: rpf3StationKeys[titleKey]
            ?: arch.rpfName.lowercase()
        return Rpf3RadioStation(
            stationGroupId = stationGroupId,
            mediaId = stMediaId,
            mediaItem = stMediaItem,
            stationKey = stKey,
            stationArchive = arch,
            extraTracksArchive = rpf3ExtraTracksArchive,
            ep1SfxArchive = rpf3Ep1SfxArchive,
            ep2SfxArchive = rpf3Ep2SfxArchive,
            advertsArchive = rpf3AdvertsArchive,
            newsArchive = rpf3NewsArchive,
            weatherArchive = rpf3WeatherArchive,
            player = player,
            context = ctx,
            adsEnabled = adsEnabled,
            weatherChatterEnabled = weatherChatterEnabled,
            newsReportsEnabled = newsReportsEnabled
        )
    }

    private fun detectRpf7Rpfs(files: Array<SimpleFileInfo>): List<SimpleFileInfo> {
        val knownStationRpfs = Rpf7MetadataRepository.stationInfoByRpf.keys
        val matched = files.filter { !it.isDirectory && knownStationRpfs.contains(it.name.lowercase()) }
        if (matched.isNotEmpty()) {
            GTRadioLogger.log("detectRpf7Rpfs: Detected ${matched.size} GTA V RPF stations in '${folderDoc.name}'")
        }
        return matched
    }

    private fun detectIsEnhanced(allFiles: Array<SimpleFileInfo>, stationRpfFiles: List<SimpleFileInfo>): Boolean {
        val folderName = folderDoc.name?.lowercase(Locale.ROOT) ?: ""
        if (folderName.contains("enhanced") || folderName.contains("gen9")) return true

        for (f in allFiles) {
            val name = f.name.lowercase(Locale.ROOT)
            if (name == "gta5_enhanced.exe" || name.contains("enhanced.exe")) return true
        }

        // Check RPF size thresholds (Enhanced archives are compressed with MP3 and significantly smaller)
        for (f in allFiles) {
            val name = f.name.lowercase(Locale.ROOT)
            if (name == "radio_01_class_rock.rpf" || name == "radio_02_pop.rpf") {
                val sz = try {
                    context.contentResolver.openFileDescriptor(f.uri, "r")?.use { it.statSize } ?: -1L
                } catch (_: Exception) { -1L }
                if (sz in 1L until 300L * 1024L * 1024L) return true
            } else if (name == "radio_adverts.rpf") {
                val sz = try {
                    context.contentResolver.openFileDescriptor(f.uri, "r")?.use { it.statSize } ?: -1L
                } catch (_: Exception) { -1L }
                if (sz in 1L until 115L * 1024L * 1024L) return true
            }
        }

        return false
    }

    private fun setupRpf7Group(allFiles: Array<SimpleFileInfo>, stationRpfFiles: List<SimpleFileInfo>) {
        isRpf7Group = true
        generation = 3
        val isEnhanced = detectIsEnhanced(allFiles, stationRpfFiles)
        groupName = if (isEnhanced) "GTA V Enhanced (RPF Audio)" else "GTA V (RPF Audio)"
        val idPrefix = if (isEnhanced) "gtav_enh_" else "gtav_"
        mediaId = "$idPrefix${UUID.randomUUID()}"

        // Automatic First-Startup Key Discovery
        if (!Rpf7KeyManager.isUnlocked(context)) {
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
                "radio_adverts.rpf" -> rpf7AdvertsArchive = Rpf7Archive(context, f.name, f.uri)
                "radio_news.rpf" -> rpf7NewsArchive = Rpf7Archive(context, f.name, f.uri)
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
            val info = Rpf7MetadataRepository.getStationInfo(stKey)
            val stDisplayName = info?.stationName ?: stFile.name
            val stMediaId = "$idPrefix${UUID.randomUUID()}"

            val arch = Rpf7Archive(context, stFile.name, stFile.uri)

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
            rpf7StationArchives[stMediaId] = arch
            rpf7StationArchives[stDisplayName] = arch
            rpf7StationKeys[stMediaId] = stKey
            rpf7StationKeys[stDisplayName] = stKey
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

    private fun tryAutoImportRpf3Keys() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // (a) Check saved Settings key folder URI
        val savedKeyUri = prefs.getString("game_key_folder_uri", null)
            ?: prefs.getString("gtav_key_folder_uri", null)
        if (!savedKeyUri.isNullOrBlank()) {
            try {
                DocumentFile.fromTreeUri(context, Uri.parse(savedKeyUri))?.let { keyDoc ->
                    if (keyDoc.isDirectory) {
                        val res = Rpf3KeyManager.importFromFolder(context, keyDoc)
                        if (res is Rpf3KeyManager.ImportResult.Success) {
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
            val res = Rpf3KeyManager.importFromFolder(context, folderDoc)
            if (res is Rpf3KeyManager.ImportResult.Success) {
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
                    val res1 = Rpf3KeyManager.importFromFolder(context, p1)
                    if (res1 is Rpf3KeyManager.ImportResult.Success) {
                        GTRadioLogger.log("StationGroup[GTA IV]: Successfully unlocked keys from parent folder: ${p1.uri}")
                        return
                    }
                    p1.parentFile?.let { p2 ->
                        if (p2.isDirectory) {
                            val res2 = Rpf3KeyManager.importFromFolder(context, p2)
                            if (res2 is Rpf3KeyManager.ImportResult.Success) {
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
                        val res = Rpf7KeyManager.importFromFolder(context, keyDoc, allowMagicDownload = true)
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
            val res = Rpf7KeyManager.importFromFolder(context, folderDoc, allowMagicDownload = true)
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
                    val res = Rpf7KeyManager.importFromFolder(context, parentDoc, allowMagicDownload = true)
                    if (res is ImportResult.Success) {
                        GTRadioLogger.log("StationGroup[GTA V]: Successfully unlocked keys from parent folder: ${parentDoc.uri}")
                        return
                    }
                    parentDoc.parentFile?.let { grandDoc ->
                        if (grandDoc.isDirectory) {
                            val res2 = Rpf7KeyManager.importFromFolder(context, grandDoc, allowMagicDownload = true)
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

    fun createRpf7Station(
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
        val arch = rpf7StationArchives[stMediaId]
            ?: rpf7StationArchives[titleKey]
            ?: rpf7StationArchives.values.first()
        val stKey = rpf7StationKeys[stMediaId]
            ?: rpf7StationKeys[titleKey]
            ?: arch.rpfName.lowercase()
        return Rpf7RadioStation(
            stationGroupId = stationGroupId,
            mediaId = stMediaId,
            mediaItem = stMediaItem,
            stationKey = stKey,
            stationArchive = arch,
            advertsArchive = rpf7AdvertsArchive,
            newsArchive = rpf7NewsArchive,
            player = player,
            context = ctx,
            adsEnabled = adsEnabled,
            weatherChatterEnabled = weatherChatterEnabled,
            newsReportsEnabled = newsReportsEnabled
        )
    }

    private fun detectGen2RawStreams(files: Array<SimpleFileInfo>): Boolean {
        val oswMatches = HashMap<String, Uri>()
        val pcMatches = HashMap<String, Uri>()

        for (f in files) {
            if (f.isDirectory) continue
            val cleanName = f.name.trim().uppercase()
            if (cleanName.endsWith(".OSW")) {
                val code = cleanName.removeSuffix(".OSW")
                if (code in GEN2_STREAM_CODES) {
                    oswMatches[code] = f.uri
                }
            } else if (!cleanName.contains(".")) {
                if (cleanName in GEN2_STREAM_CODES) {
                    pcMatches[cleanName] = f.uri
                }
            } else if (cleanName.endsWith(".WAV") || cleanName.endsWith(".OGG")) {
                val code = cleanName.substringBeforeLast(".")
                if (code in GEN2_STREAM_CODES) {
                    pcMatches[code] = f.uri
                }
            }
        }

        val radioOswCount = oswMatches.keys.count { it != "ADVERTS" }
        val radioPcCount = pcMatches.keys.count { it != "ADVERTS" }

        if (radioOswCount >= 1) {
            isGen2RawGroup = true
            rawContainerType = Gen2ContainerType.MOBILE_OSW
            rawStreamUris.putAll(oswMatches)
            generation = 2
            groupName = "GTA San Andreas (Mobile OSW)"
            GTRadioLogger.log("detectGen2RawStreams: Detected Mobile OSW in '${folderDoc.name}' (${oswMatches.keys})")
            return true
        } else if (radioPcCount >= 1) {
            isGen2RawGroup = true
            rawContainerType = Gen2ContainerType.PC_STREAM
            rawStreamUris.putAll(pcMatches)
            generation = 2
            groupName = "GTA San Andreas (PC Streams)"
            GTRadioLogger.log("detectGen2RawStreams: Detected PC Streams in '${folderDoc.name}' (${pcMatches.keys})")
            return true
        }
        return false
    }

    @Synchronized
    fun getOrCreateArchive(streamCode: String): Gen2StreamArchive? {
        val code = streamCode.uppercase()
        archiveCache[code]?.let { return it }
        val uri = rawStreamUris[code] ?: return null
        val type = rawContainerType ?: return null
        val arch = Gen2StreamArchive(context, code, uri, type)
        archiveCache[code] = arch
        return arch
    }

    private fun getGen2RawStationsList(): ArrayList<MediaBrowserCompat.MediaItem> {
        val list = ArrayList<MediaBrowserCompat.MediaItem>()
        Gen2MetadataRepository.ensureLoaded(context)

        for ((code, _) in rawStreamUris) {
            if (code == "ADVERTS") continue
            val stMeta = Gen2MetadataRepository.getByStreamCode(context, code)
            val stName = stMeta?.stationName ?: Gen2MetadataRepository.defaultStations[code]?.first ?: code
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
