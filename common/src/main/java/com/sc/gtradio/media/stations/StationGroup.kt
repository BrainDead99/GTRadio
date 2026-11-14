package com.sc.gtradio.media.stations

import GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaMetadataCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.FileUtils
import com.sc.gtradio.common.FileUtils.listSimpleFiles
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.common.SimpleFileInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

enum class RawSaMode {
    NONE,
    MOBILE_OSW,
    PC_STREAM
}

@UnstableApi
class StationGroup(val folderDoc: DocumentFile, private val context: Context) {
    var isValid: Boolean = false
        private set

    var groupType: StationGroupType = StationGroupType.None
    var groupName: String = ""
    lateinit var mediaItem: MediaBrowserCompat.MediaItem
    var stations: ArrayList<MediaBrowserCompat.MediaItem> = ArrayList()

    private val stationDocs = HashMap<String, DocumentFile>()
    private var advertsDoc: DocumentFile? = null
    private var newsDoc: DocumentFile? = null
    private var weatherDoc: DocumentFile? = null

    // Raw GTA SA state
    private var rawSaMode: RawSaMode = RawSaMode.NONE
    private val rawSaStationArchives = HashMap<String, SaStreamArchive>()
    private val rawSaStationMetas = HashMap<String, SaStationMeta>()
    private var rawSaAdvertsArchive: SaStreamArchive? = null

    // Raw GTA IV / EFLC RPF state
    private var isGtaIvRpfGroup: Boolean = false
    private val gtaIvStationArchives = HashMap<String, GtaIvRpfArchive>()
    private var gtaIvExtraTracksArchive: GtaIvRpfArchive? = null
    private var gtaIvAdvertsArchive: GtaIvRpfArchive? = null
    private var gtaIvNewsArchive: GtaIvRpfArchive? = null
    private var gtaIvWeatherArchive: GtaIvRpfArchive? = null

    init {
        val simpleFiles = try {
            folderDoc.listSimpleFiles(context)
        } catch (e: Exception) {
            GTRadioLogger.log("StationGroup: listSimpleFiles error on ${folderDoc.name}", e)
            emptyArray()
        }

        val detectedRpfFiles = detectGtaIvRpfs(simpleFiles)
        val detectedSaMode = if (detectedRpfFiles.isEmpty()) detectRawSaStreams(simpleFiles) else RawSaMode.NONE

        when {
            detectedRpfFiles.isNotEmpty() -> {
                setupGtaIvRpfGroup(simpleFiles, detectedRpfFiles)
            }
            detectedSaMode != RawSaMode.NONE -> {
                rawSaMode = detectedSaMode
                setupRawSaGroup(simpleFiles, detectedSaMode)
            }
            else -> {
                setupExtractedFolderGroup(simpleFiles)
            }
        }
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
        groupType = StationGroupType.Gen3
        groupName = "GTA IV (RPF Audio)"

        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = allFiles.find { !it.isDirectory && it.name.uppercase().contains("LOGO") }?.uri
            ?: themePack?.groupLogoUri
        val logoBitmap = DynamicThemeManager.loadBitmap(context, logoUri, 512)
            ?: FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

        // Shared RPF archives (Adverts, News, Weather, ExtraTracks)
        for (f in allFiles) {
            if (f.isDirectory) continue
            when (f.name.lowercase()) {
                "radio_extratracks.rpf" -> gtaIvExtraTracksArchive = GtaIvRpfArchive(context, f.name, f.uri)
                "radio_adverts.rpf" -> gtaIvAdvertsArchive = GtaIvRpfArchive(context, f.name, f.uri)
                "radio_news.rpf" -> gtaIvNewsArchive = GtaIvRpfArchive(context, f.name, f.uri)
                "radio_weather.rpf" -> gtaIvWeatherArchive = GtaIvRpfArchive(context, f.name, f.uri)
            }
        }

        val sortedStations = stationRpfFiles.sortedBy {
            GtaIvMetadataRepository.stationNamesByRpf[it.name.lowercase()] ?: it.name
        }

        val groupMediaId = UUID.randomUUID().toString()
        for (stFile in sortedStations) {
            val stDisplayName = GtaIvMetadataRepository.stationNamesByRpf[stFile.name.lowercase()] ?: stFile.name
            val stMediaId = UUID.randomUUID().toString()

            val iconUri = themePack?.stationIconUris?.get(DynamicThemeManager.normalizeStationKey(stDisplayName))
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

            stations.add(MediaBrowserCompat.MediaItem(meta.description, FLAG_PLAYABLE))
            gtaIvStationArchives[stMediaId] = GtaIvRpfArchive(context, stFile.name, stFile.uri)
        }

        if (stations.isNotEmpty()) {
            val groupMeta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, groupMediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
                .build()
            mediaItem = MediaBrowserCompat.MediaItem(groupMeta.description, FLAG_BROWSABLE)
            isValid = true
        }
    }

    private fun detectRawSaStreams(files: Array<SimpleFileInfo>): RawSaMode {
        val stationCodes = setOf("CH", "CO", "CR", "DS", "HC", "MH", "MR", "NJ", "RE", "RG", "TK", "ADVERTS")
        val oswMatches = files.filter { !it.isDirectory && it.name.lowercase().endsWith(".osw") }
            .map { it.name.substringBeforeLast(".").uppercase() }
            .filter { stationCodes.contains(it) }

        if (oswMatches.size >= 3) {
            GTRadioLogger.log("detectRawSaStreams: Detected Mobile OSW in '${folderDoc.name}' ($oswMatches)")
            return RawSaMode.MOBILE_OSW
        }

        val pcMatches = files.filter { !it.isDirectory && !it.name.contains(".") }
            .map { it.name.uppercase() }
            .filter { stationCodes.contains(it) }

        if (pcMatches.size >= 3) {
            GTRadioLogger.log("detectRawSaStreams: Detected PC Streams in '${folderDoc.name}' ($pcMatches)")
            return RawSaMode.PC_STREAM
        }
        return RawSaMode.NONE
    }

    private fun setupRawSaGroup(simpleFiles: Array<SimpleFileInfo>, mode: RawSaMode) {
        SaMetadataRepository.ensureLoaded(context)
        groupType = StationGroupType.Gen2
        groupName = if (mode == RawSaMode.MOBILE_OSW) {
            "GTA San Andreas (Mobile OSW)"
        } else {
            "GTA San Andreas (PC Streams)"
        }

        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = simpleFiles.find { !it.isDirectory && it.name.uppercase().contains("LOGO") }?.uri
            ?: themePack?.groupLogoUri
        val logoBitmap = DynamicThemeManager.loadBitmap(context, logoUri, 512)
            ?: FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

        val containerType = if (mode == RawSaMode.MOBILE_OSW) SaContainerType.MOBILE_OSW else SaContainerType.PC_STREAM
        val fileByCode = HashMap<String, SimpleFileInfo>()
        for (f in simpleFiles) {
            if (f.isDirectory) continue
            if (mode == RawSaMode.MOBILE_OSW && f.name.lowercase().endsWith(".osw")) {
                val code = f.name.substringBeforeLast(".").uppercase()
                if (!f.name.lowercase().endsWith(".idx.osw")) {
                    fileByCode[code] = f
                }
            } else if (mode == RawSaMode.PC_STREAM && !f.name.contains(".")) {
                fileByCode[f.name.uppercase()] = f
            }
        }

        fileByCode["ADVERTS"]?.let { adFile ->
            rawSaAdvertsArchive = SaStreamArchive(context, "ADVERTS", adFile.uri, containerType)
        }

        val orderedCodes = listOf("DS", "NJ", "CR", "RE", "CO", "RG", "CH", "MH", "MR", "HC", "TK")
        val groupMediaId = UUID.randomUUID().toString()

        for (code in orderedCodes) {
            val fInfo = fileByCode[code] ?: continue
            val stMeta = SaMetadataRepository.getByStreamCode(context, code) ?: continue
            val stName = stMeta.stationName
            val mediaId = UUID.randomUUID().toString()

            val iconUri = themePack?.stationIconUris?.get(DynamicThemeManager.normalizeStationKey(stName))
            val iconBmp = DynamicThemeManager.loadBitmap(context, iconUri, 512) ?: logoBitmap

            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, iconBmp)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, iconBmp)
                .build()

            stations.add(MediaBrowserCompat.MediaItem(metadata.description, FLAG_PLAYABLE))
            rawSaStationArchives[mediaId] = SaStreamArchive(context, code, fInfo.uri, containerType)
            rawSaStationMetas[mediaId] = stMeta
        }

        if (stations.isNotEmpty()) {
            val groupMeta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, groupMediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, groupName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, groupName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
                .build()
            mediaItem = MediaBrowserCompat.MediaItem(groupMeta.description, FLAG_BROWSABLE)
            isValid = true
        }
    }

    private fun setupExtractedFolderGroup(simpleFiles: Array<SimpleFileInfo>) {
        val groupInfoUri = simpleFiles.find { x -> !x.isDirectory && x.name.equals("stationGroupInfo.json", true) }?.uri
        if (groupInfoUri == null) {
            isValid = false
            return
        }

        try {
            val inputStream = context.contentResolver.openInputStream(groupInfoUri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            val jsonObj = JSONObject(sb.toString())
            groupName = jsonObj.getString("groupName")
            groupType = StationGroupType.fromInt(jsonObj.getInt("groupType"))
        } catch (e: Exception) {
            isValid = false
            return
        }

        if (groupType == StationGroupType.None || groupName.isBlank()) {
            isValid = false
            return
        }

        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = simpleFiles.find { x -> !x.isDirectory && x.name.uppercase().contains("LOGO") }?.uri
            ?: themePack?.groupLogoUri
        val logoBitmap = DynamicThemeManager.loadBitmap(context, logoUri, 512)
            ?: FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

        val subDirs = folderDoc.listFiles().filter { x -> x.isDirectory }
        for (subDir in subDirs) {
            val subName = subDir.name ?: continue
            when (subName.uppercase()) {
                "ADVERTS" -> advertsDoc = subDir
                "NEWS" -> newsDoc = subDir
                "WEATHER" -> weatherDoc = subDir
                else -> {
                    val subFiles = subDir.listSimpleFiles(context)
                    val stLogoUri = subFiles.find { x -> !x.isDirectory && x.name.uppercase().contains("LOGO") }?.uri
                        ?: themePack?.stationIconUris?.get(DynamicThemeManager.normalizeStationKey(subName))
                    val stLogoBmp: Bitmap? = FileUtils.getBitmapFromUri(context.contentResolver, stLogoUri)
                        ?: DynamicThemeManager.loadBitmap(context, stLogoUri, 512)
                        ?: logoBitmap

                    val stMediaId = UUID.randomUUID().toString()
                    val metadata = MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, stMediaId)
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, subName)
                        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, subName)
                        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, groupName)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupName)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, stLogoBmp)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, stLogoBmp)
                        .build()

                    stations.add(MediaBrowserCompat.MediaItem(metadata.description, FLAG_PLAYABLE))
                    stationDocs[stMediaId] = subDir
                }
            }
        }

        if (stations.isEmpty()) {
            isValid = false
            return
        }

        val groupMediaId = UUID.randomUUID().toString()
        val groupMetadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, groupMediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, groupName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, groupName)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
            .build()

        mediaItem = MediaBrowserCompat.MediaItem(groupMetadata.description, FLAG_BROWSABLE)
        isValid = true
    }

    fun createRadioStations(
        player: GTRadioPlayer,
        adsEnabled: Boolean,
        weatherChatterEnabled: Boolean,
        newsReportsEnabled: Boolean
    ): List<RadioStation> {
        if (!isValid) return emptyList()

        if (isGtaIvRpfGroup) {
            return stations.mapNotNull { stItem ->
                val stId = stItem.mediaId ?: return@mapNotNull null
                val arch = gtaIvStationArchives[stId] ?: return@mapNotNull null
                GtaIvRpfRadioStation(
                    stationGroupId = mediaItem.mediaId!!,
                    mediaId = stId,
                    mediaItem = stItem,
                    stationArchive = arch,
                    extraTracksArchive = gtaIvExtraTracksArchive,
                    advertsArchive = gtaIvAdvertsArchive,
                    newsArchive = gtaIvNewsArchive,
                    weatherArchive = gtaIvWeatherArchive,
                    player = player,
                    context = context,
                    adsEnabled = adsEnabled,
                    weatherChatterEnabled = weatherChatterEnabled,
                    newsReportsEnabled = newsReportsEnabled
                )
            }
        }

        if (rawSaMode != RawSaMode.NONE) {
            return stations.mapNotNull { stItem ->
                val stId = stItem.mediaId ?: return@mapNotNull null
                val arch = rawSaStationArchives[stId] ?: return@mapNotNull null
                val meta = rawSaStationMetas[stId] ?: return@mapNotNull null
                SaRawRadioStation(
                    stationGroupId = mediaItem.mediaId!!,
                    mediaId = stId,
                    mediaItem = stItem,
                    stationArchive = arch,
                    advertsArchive = rawSaAdvertsArchive,
                    stationMeta = meta,
                    player = player,
                    context = context,
                    adsEnabled = adsEnabled,
                    _weatherChatterEnabled = weatherChatterEnabled
                )
            }
        }

        return stations.mapNotNull { stItem ->
            val stId = stItem.mediaId ?: return@mapNotNull null
            val stDoc = stationDocs[stId] ?: return@mapNotNull null
            when (groupType) {
                StationGroupType.Gen1 -> Gen1RadioStation(
                    mediaItem.mediaId!!, stId, stItem, stDoc, player, context
                )
                StationGroupType.Gen2 -> Gen2RadioStation(
                    mediaItem.mediaId!!, stId, stItem, stDoc, advertsDoc ?: folderDoc,
                    player, context, adsEnabled, weatherChatterEnabled
                )
                StationGroupType.Gen3 -> Gen3RadioStation(
                    mediaItem.mediaId!!, stId, stItem, stDoc,
                    advertsDoc ?: folderDoc, newsDoc ?: folderDoc, weatherDoc ?: folderDoc,
                    player, context, adsEnabled, weatherChatterEnabled, newsReportsEnabled
                )
                else -> null
            }
        }
    }

    companion object {
        private val skipFolderNames = setOf(
            "DYNAMIC THEME", "CONFIG", "SONGS", "DJ CHATTER", "IDS",
            "ANNOUNCER", "ADVERTS", "NEWS", "WEATHER", "MORNING",
            "AFTERNOON", "EVENING", "NIGHT", "OTHER", "TALK SHOWS"
        )

        fun scanRootDirectory(rootDoc: DocumentFile, context: Context): List<StationGroup> {
            val found = ArrayList<StationGroup>()
            val visitedUris = HashSet<String>()
            GTRadioLogger.log("scanRootDirectory: Starting scan at '${rootDoc.name}' (${rootDoc.uri})")
            scanRecursive(rootDoc, context, depth = 0, maxDepth = 6, found = found, visited = visitedUris)
            GTRadioLogger.log("scanRootDirectory: Finished scan. Found ${found.size} station group(s): ${found.map { it.groupName }}")
            return found
        }

        private fun scanRecursive(
            dir: DocumentFile,
            context: Context,
            depth: Int,
            maxDepth: Int,
            found: MutableList<StationGroup>,
            visited: MutableSet<String>
        ) {
            if (depth > maxDepth) return
            val uriStr = dir.uri.toString()
            if (!visited.add(uriStr)) return

            val dirNameUpper = (dir.name ?: "").uppercase().trim()
            if (depth > 0 && skipFolderNames.contains(dirNameUpper)) return

            val candidate = StationGroup(dir, context)
            if (candidate.isValid) {
                GTRadioLogger.log("scanRecursive[d=$depth]: Found valid group '${candidate.groupName}' with ${candidate.stations.size} stations at '${dir.name}'")
                found.add(candidate)
                return
            }

            val subDirs = try {
                dir.listFiles().filter { it.isDirectory }
            } catch (e: Exception) {
                emptyList()
            }

            for (sub in subDirs) {
                val subUpper = (sub.name ?: "").uppercase().trim()
                if (skipFolderNames.contains(subUpper) || subUpper.startsWith(".")) continue
                scanRecursive(sub, context, depth + 1, maxDepth, found, visited)
            }
        }
    }
}
