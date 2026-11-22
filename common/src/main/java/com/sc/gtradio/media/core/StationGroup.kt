package com.sc.gtradio.media.core
import com.sc.gtradio.media.gen1.*
import com.sc.gtradio.media.gen2.*
import com.sc.gtradio.media.rpf3.*
import com.sc.gtradio.media.rpf7.*
import com.sc.gtradio.media.artwork.*


import java.util.Locale
import java.util.UUID

import com.sc.gtradio.media.GTRadioPlayer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

class StationGroup private constructor(
    val folderDoc: DocumentFile,
    private val context: Context,
    private val skipScan: Boolean
) {
    constructor(folderDoc: DocumentFile, context: Context) : this(folderDoc, context, false)

    lateinit var mediaItem: MediaItem
    var stationList: ArrayList<MediaItem> = ArrayList()
    var generation: Int = -1
    var groupName: String = ""

    var isGen1RawGroup: Boolean = false
        internal set
    internal val gen1StationFiles = HashMap<String, SimpleFileInfo>()
    internal var gen1GameCode: Int = 0

    var isRpf3Group: Boolean = false
        internal set
    internal val rpf3StationArchives = HashMap<String, Rpf3Archive>()
    internal val rpf3StationKeys = HashMap<String, String>()
    internal var rpf3ExtraTracksArchive: Rpf3Archive? = null
    internal var rpf3Ep1SfxArchive: Rpf3Archive? = null
    internal var rpf3Ep2SfxArchive: Rpf3Archive? = null
    internal var rpf3AdvertsArchive: Rpf3Archive? = null
    internal var rpf3NewsArchive: Rpf3Archive? = null
    internal var rpf3WeatherArchive: Rpf3Archive? = null

    var isRpf7Group: Boolean = false
        internal set
    internal val rpf7StationArchives = HashMap<String, Rpf7Archive>()
    internal val rpf7StationKeys = HashMap<String, String>()
    internal var rpf7AdvertsArchive: Rpf7Archive? = null
    internal var rpf7NewsArchive: Rpf7Archive? = null
    var mediaId: String = ""
    var isValid: Boolean = true
    var isGen2RawGroup: Boolean = false
    var rawContainerType: Gen2ContainerType? = null
    internal val rawStreamUris = HashMap<String, Uri>()
    private val archiveCache = HashMap<String, Gen2StreamArchive>()

    companion object {
        private val GEN2_STREAM_CODES = setOf("CH", "CO", "CR", "DS", "HC", "MH", "MR", "NJ", "RE", "RG", "TK", "ADVERTS")
        private val SKIP_DIRS = setOf(
            "SFX", "CONFIG", "ANIM", "TEXT", "MODELS", "DATA", "MOVIES",
            "DYNAMIC THEME", "HD RADIO ICONS", "BACKGROUND", "BACKGROUNDS",
            "FONT", "FONTS", "ICON", "ICONS", "SOUNDS", "SOUND",
            "SONGS", "DJ CHATTER", "ANNOUNCER", "IDS", "ADVERTS", "WEATHER",
            "MORNING", "EVENING", "NIGHT", "OTHER", "FOG", "RAIN", "STORM", "SUN"
        )

        fun scanRootDirectory(rootDoc: DocumentFile?, context: Context): ArrayList<StationGroup> {
            GTRadioLogger.init(context)
            val scanStart = System.currentTimeMillis()
            val threadName = Thread.currentThread().name
            val results = ArrayList<StationGroup>()
            if (rootDoc == null || !rootDoc.isDirectory) {
                GTRadioLogger.log("scanRootDirectory: rootDoc is null or not a directory (${rootDoc?.uri})")
                return results
            }

            GTRadioLogger.log("scanRootDirectory: Starting scan at '${rootDoc.name}' (${rootDoc.uri})")

            val rootChildren = try {
                rootDoc.listFiles().filter { it.isDirectory }
            } catch (e: Exception) {
                GTRadioLogger.log("scanRootDirectory: Failed listing children of '${rootDoc.name}'", e)
                emptyList()
            }

            val audioDir = rootChildren.firstOrNull { it.name.equals("Audio", ignoreCase = true) }
            if (audioDir != null) {
                GTRadioLogger.log("scanRootDirectory: Found direct 'Audio' directory at '${audioDir.name}', scanning strictly inside Audio/")
                scanAudioDirectory(audioDir, context, results)
            } else {
                GTRadioLogger.log("scanRootDirectory: No 'Audio' directory in root, running backward compatibility scan")
                val rootCandidate = StationGroup(rootDoc, context)
                if (rootCandidate.generation in 1..3 && rootCandidate.stationList.isNotEmpty()) {
                    GTRadioLogger.log("scanRootDirectory: Root '${rootDoc.name}' is itself a valid station group '${rootCandidate.groupName}'")
                    results.add(rootCandidate)
                } else {
                    scanRecursive(rootDoc, context, results, depth = 0, maxDepth = 2)
                }
            }

            results.sortBy { it.groupName }
            val scanElapsed = System.currentTimeMillis() - scanStart
            GTRadioLogger.logVerbose("SCAN") {
                "scanRootDirectory complete: ${scanElapsed}ms on thread '$threadName', found ${results.size} group(s)"
            }
            GTRadioLogger.log("scanRootDirectory: Finished scan. Found ${results.size} station group(s): ${results.map { it.groupName }}")
            return results
        }

        private fun scanAudioDirectory(
            audioDir: DocumentFile,
            context: Context,
            results: ArrayList<StationGroup>
        ) {
            val children = try {
                audioDir.listFiles().filter { it.isDirectory }
            } catch (e: Exception) {
                GTRadioLogger.log("scanAudioDirectory: Failed listing children of '${audioDir.name}'", e)
                emptyList()
            }

            for (child in children) {
                val cName = (child.name ?: "").uppercase()
                if (cName.startsWith(".") || cName in SKIP_DIRS) continue

                val candidate = StationGroup(child, context)
                if (candidate.generation in 1..3 && candidate.stationList.isNotEmpty()) {
                    GTRadioLogger.log("scanAudioDirectory: Found valid group '${candidate.groupName}' at '${child.name}'")
                    results.add(candidate)
                    continue
                }

                // Check max 1 extra level ONLY if child contains an audio or streams subfolder
                val subDirs = try {
                    child.listFiles().filter { it.isDirectory }
                } catch (_: Exception) {
                    emptyList()
                }

                val hasAudioOrStreams = subDirs.any {
                    val sName = it.name?.uppercase() ?: ""
                    sName == "AUDIO" || sName == "STREAMS"
                }

                if (hasAudioOrStreams) {
                    for (sub in subDirs) {
                        val sName = (sub.name ?: "").uppercase()
                        if (sName.startsWith(".") || sName in SKIP_DIRS) continue
                        if (sName == "AUDIO" || sName == "STREAMS") {
                            val subCandidate = StationGroup(sub, context)
                            if (subCandidate.generation in 1..3 && subCandidate.stationList.isNotEmpty()) {
                                GTRadioLogger.log("scanAudioDirectory: Found valid group '${subCandidate.groupName}' at '${child.name}/${sub.name}'")
                                results.add(subCandidate)
                            } else if (sName == "AUDIO") {
                                val subStreams = try {
                                    sub.listFiles().find { it.isDirectory && it.name?.equals("streams", ignoreCase = true) == true }
                                } catch (_: Exception) { null }
                                if (subStreams != null) {
                                    val streamsCandidate = StationGroup(subStreams, context)
                                    if (streamsCandidate.generation in 1..3 && streamsCandidate.stationList.isNotEmpty()) {
                                        GTRadioLogger.log("scanAudioDirectory: Found valid group '${streamsCandidate.groupName}' at '${child.name}/audio/streams'")
                                        results.add(streamsCandidate)
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
            if (depth > 0 && (dirNameUpper in SKIP_DIRS || dirNameUpper.startsWith("."))) return

            val folderStart = System.currentTimeMillis()
            val candidate = StationGroup(dir, context)
            val folderElapsed = System.currentTimeMillis() - folderStart
            if (folderElapsed > 50L) {
                GTRadioLogger.logVerbose("SCAN") {
                    "Slow folder scan: '${dir.name}' took ${folderElapsed}ms [d=$depth]"
                }
            }
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

        fun fromCachedDescriptor(desc: LibraryIndexCache.CachedGroupDescriptor, context: Context): StationGroup {
            val doc = if (desc.folderUri.isNotBlank()) {
                try {
                    DocumentFile.fromTreeUri(context, Uri.parse(desc.folderUri))
                } catch (_: Exception) { null }
            } else null
            val resolvedDoc = doc ?: DocumentFile.fromFile(context.filesDir)
            val group = StationGroup(resolvedDoc, context, skipScan = true)
            group.generation = desc.generation
            group.groupName = desc.groupName
            group.mediaId = desc.groupId
            group.isValid = true

            group.isGen1RawGroup = (desc.formatType == "GEN1_RAW")
            group.gen1GameCode = desc.gameCode

            group.isGen2RawGroup = (desc.formatType == "GEN2_RAW")
            group.rawContainerType = desc.rawContainerType?.let { runCatching { Gen2ContainerType.valueOf(it) }.getOrNull() }
            desc.rawStreamUris.forEach { (code, u) -> group.rawStreamUris[code] = Uri.parse(u) }

            group.isRpf3Group = (desc.formatType == "RPF3")
            group.isRpf7Group = (desc.formatType == "RPF7")

            if (group.isRpf3Group) {
                desc.sharedUris["extra_tracks"]?.let { group.rpf3ExtraTracksArchive = Rpf3Archive(context, "radio_extratracks.rpf", Uri.parse(it)) }
                desc.sharedUris["ep1_sfx"]?.let { group.rpf3Ep1SfxArchive = Rpf3Archive(context, "ep1_sfx.rpf", Uri.parse(it)) }
                desc.sharedUris["ep2_sfx"]?.let { group.rpf3Ep2SfxArchive = Rpf3Archive(context, "ep2_sfx.rpf", Uri.parse(it)) }
                desc.sharedUris["adverts"]?.let { group.rpf3AdvertsArchive = Rpf3Archive(context, "radio_adverts.rpf", Uri.parse(it)) }
                desc.sharedUris["news"]?.let { group.rpf3NewsArchive = Rpf3Archive(context, "radio_news.rpf", Uri.parse(it)) }
                desc.sharedUris["weather"]?.let { group.rpf3WeatherArchive = Rpf3Archive(context, "radio_weather.rpf", Uri.parse(it)) }
            } else if (group.isRpf7Group) {
                desc.sharedUris["adverts"]?.let { group.rpf7AdvertsArchive = Rpf7Archive(context, "radio_adverts.rpf", Uri.parse(it)) }
                desc.sharedUris["news"]?.let { group.rpf7NewsArchive = Rpf7Archive(context, "radio_news.rpf", Uri.parse(it)) }
            }

            val sList = ArrayList<MediaItem>(desc.stations.size)
            for (st in desc.stations) {
                val extras = Bundle().apply {
                    putString("media_id", st.mediaId)
                    putString("group_name", desc.groupName)
                    for ((k, v) in st.extras) {
                        putString(k, v)
                    }
                }
                val icon = st.iconUri?.let { Uri.parse(it) }
                val stMeta = MediaMetadata.Builder()
                    .setTitle(st.name)
                    .setDisplayTitle(st.name)
                    .setSubtitle(desc.groupName)
                    .setArtist(desc.groupName)
                    .setAlbumTitle(desc.groupName)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .apply { if (icon != null) setArtworkUri(icon) }
                    .setExtras(extras)
                    .build()
                val item = MediaItem.Builder()
                    .setMediaId(st.mediaId)
                    .setMediaMetadata(stMeta)
                    .build()
                sList.add(item)

                if (group.isGen1RawGroup && st.fileUri.isNotBlank()) {
                    val uri = Uri.parse(st.fileUri)
                    val sfi = SimpleFileInfo(uri, st.name, uri.lastPathSegment ?: "", false)
                    group.gen1StationFiles[st.mediaId] = sfi
                    group.gen1StationFiles[st.name] = sfi
                } else if (group.isRpf3Group && st.fileUri.isNotBlank()) {
                    val rName = st.rpfName.ifBlank { st.archiveKey }
                    val arch = when {
                        rName.equals("ep1_sfx.rpf", ignoreCase = true) -> group.rpf3Ep1SfxArchive ?: Rpf3Archive(context, rName, Uri.parse(st.fileUri))
                        rName.equals("ep2_sfx.rpf", ignoreCase = true) -> group.rpf3Ep2SfxArchive ?: Rpf3Archive(context, rName, Uri.parse(st.fileUri))
                        else -> Rpf3Archive(context, rName, Uri.parse(st.fileUri))
                    }
                    group.rpf3StationArchives[st.mediaId] = arch
                    group.rpf3StationArchives[st.name] = arch
                    group.rpf3StationKeys[st.mediaId] = st.archiveKey.ifBlank { rName.lowercase() }
                    group.rpf3StationKeys[st.name] = st.archiveKey.ifBlank { rName.lowercase() }
                } else if (group.isRpf7Group && st.fileUri.isNotBlank()) {
                    val rName = st.rpfName.ifBlank { st.archiveKey }
                    val arch = Rpf7Archive(context, rName, Uri.parse(st.fileUri))
                    group.rpf7StationArchives[st.mediaId] = arch
                    group.rpf7StationArchives[st.name] = arch
                    group.rpf7StationKeys[st.mediaId] = st.archiveKey.ifBlank { rName.lowercase() }
                    group.rpf7StationKeys[st.name] = st.archiveKey.ifBlank { rName.lowercase() }
                }
            }
            group.stationList = sList

            val logo = desc.logoUri?.let { Uri.parse(it) }
            val gMeta = MediaMetadata.Builder()
                .setTitle(desc.groupName)
                .setDisplayTitle(desc.groupName)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .apply { if (logo != null) setArtworkUri(logo) }
                .setExtras(Bundle().apply { putString("media_id", desc.groupId) })
                .build()
            group.mediaItem = MediaItem.Builder()
                .setMediaId(desc.groupId)
                .setMediaMetadata(gMeta)
                .build()

            return group
        }
    }

    
init {
        if (!skipScan && folderDoc != null) {
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
            1 -> "GTA Vice City (PC)"
            2 -> "GTA III (PC)"
            3 -> "GTA: Liberty City Stories (PSP)"
            4 -> "GTA: Vice City Stories (PSP)"
            else -> "GTA Radio (Gen1)"
        }
        mediaId = UUID.randomUUID().toString()

        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = allFiles.find { !it.isDirectory && it.name.uppercase().contains("LOGO") }?.uri
            ?: themePack?.groupLogoUri

        val sortedFiles = stationFiles.sortedBy {
            Gen1MetadataRepository.getStationInfo(it.name, gameCode)?.displayName ?: it.name
        }

        for (stFile in sortedFiles) {
            val info = Gen1MetadataRepository.getStationInfo(stFile.name, gameCode)
            val stDisplayName = info?.displayName ?: stFile.name
            val stMediaId = UUID.randomUUID().toString()

            val iconUri = themePack?.let { DynamicThemeManager.resolveStationIconUri(it.stationIconUris, stDisplayName) }

            val meta = MediaMetadata.Builder()
                .setTitle(stDisplayName)
                .setDisplayTitle(stDisplayName)
                .setSubtitle(groupName)
                .setArtist(groupName)
                .setAlbumTitle(groupName)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .apply {
                    if (iconUri != null) setArtworkUri(iconUri)
                }
                .setExtras(Bundle().apply {
                    putString("media_id", stMediaId)
                    putString("group_name", groupName)
                })
                .build()

            stationList.add(MediaItem.Builder().setMediaId(stMediaId).setMediaMetadata(meta).build())
            gen1StationFiles[stMediaId] = stFile
            gen1StationFiles[stDisplayName] = stFile
        }

        if (stationList.isNotEmpty()) {
            val groupMeta = MediaMetadata.Builder()
                .setTitle(groupName)
                .setDisplayTitle(groupName)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .apply {
                    if (logoUri != null) setArtworkUri(logoUri)
                }
                .setExtras(Bundle().apply {
                    putString("media_id", mediaId)
                })
                .build()
            mediaItem = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(groupMeta).build()
            isValid = true
        }
    }

    fun createGen1RawStation(
        stationGroupId: String,
        stMediaId: String,
        stMediaItem: MediaItem,
        player: GTRadioPlayer,
        ctx: Context,
        adsEnabled: Boolean,
        weatherChatterEnabled: Boolean,
        newsReportsEnabled: Boolean
    ): RadioStation {
        val titleKey = stMediaItem.mediaMetadata.title?.toString() ?: ""
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
        isRpf3Group = true
        generation = 3

        // Automatic First-Startup Key Discovery for GTA IV / EFLC
        if (!Rpf3KeyManager.isUnlocked(context)) {
            tryAutoImportRpf3Keys()
        }
        val isEflc = stationRpfFiles.any { it.name.lowercase() in setOf("radio_vcfm.rpf", "radio_ramjamfm.rpf", "radio_meditation.rpf") } ||
            allFiles.any { it.name.lowercase() in setOf("ep1_sfx.rpf", "ep2_sfx.rpf") } ||
            folderDoc.name?.uppercase()?.contains("EFLC") == true
        groupName = if (isEflc) "GTA: Episodes from Liberty City (PC)" else "GTA IV (PC)"
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

            val meta = MediaMetadata.Builder()
                .setTitle(stDisplayName)
                .setDisplayTitle(stDisplayName)
                .setSubtitle(groupName)
                .setArtist(groupName)
                .setAlbumTitle(groupName)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .apply {
                    if (iconUri != null) setArtworkUri(iconUri)
                }
                .setExtras(Bundle().apply {
                    putString("media_id", stMediaId)
                    putString("group_name", groupName)
                })
                .build()

            stationList.add(MediaItem.Builder().setMediaId(stMediaId).setMediaMetadata(meta).build())
            rpf3StationArchives[stMediaId] = item.archive
            rpf3StationArchives[stDisplayName] = item.archive
            rpf3StationKeys[stMediaId] = item.stationKey
            rpf3StationKeys[stDisplayName] = item.stationKey
        }

        if (stationList.isNotEmpty()) {
            val groupMeta = MediaMetadata.Builder()
                .setTitle(groupName)
                .setDisplayTitle(groupName)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .apply {
                    if (logoUri != null) setArtworkUri(logoUri)
                }
                .setExtras(Bundle().apply {
                    putString("media_id", mediaId)
                })
                .build()
            mediaItem = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(groupMeta).build()
            isValid = true
        }
    }

    fun createRpf3Station(
        stationGroupId: String,
        stMediaId: String,
        stMediaItem: MediaItem,
        player: GTRadioPlayer,
        ctx: Context,
        adsEnabled: Boolean,
        weatherChatterEnabled: Boolean,
        newsReportsEnabled: Boolean
    ): RadioStation {
        val titleKey = stMediaItem.mediaMetadata.title?.toString() ?: ""
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
        groupName = if (isEnhanced) "GTA V Enhanced (PC)" else "GTA V (PC)"
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

            val meta = MediaMetadata.Builder()
                .setTitle(stDisplayName)
                .setDisplayTitle(stDisplayName)
                .setSubtitle(groupName)
                .setArtist(groupName)
                .setAlbumTitle(groupName)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .apply {
                    if (iconUri != null) setArtworkUri(iconUri)
                }
                .setExtras(Bundle().apply {
                    putString("media_id", stMediaId)
                    putString("group_name", groupName)
                })
                .build()

            stationList.add(MediaItem.Builder().setMediaId(stMediaId).setMediaMetadata(meta).build())
            rpf7StationArchives[stMediaId] = arch
            rpf7StationArchives[stDisplayName] = arch
            rpf7StationKeys[stMediaId] = stKey
            rpf7StationKeys[stDisplayName] = stKey
        }

        if (stationList.isNotEmpty()) {
            val groupMeta = MediaMetadata.Builder()
                .setTitle(groupName)
                .setDisplayTitle(groupName)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .apply {
                    if (logoUri != null) setArtworkUri(logoUri)
                }
                .setExtras(Bundle().apply {
                    putString("media_id", mediaId)
                })
                .build()
            mediaItem = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(groupMeta).build()
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
        stMediaItem: MediaItem,
        player: GTRadioPlayer,
        ctx: Context,
        adsEnabled: Boolean,
        weatherChatterEnabled: Boolean,
        newsReportsEnabled: Boolean
    ): RadioStation {
        val titleKey = stMediaItem.mediaMetadata.title?.toString() ?: ""
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
            groupName = "GTA San Andreas (Mobile)"
            GTRadioLogger.log("detectGen2RawStreams: Detected Mobile OSW in '${folderDoc.name}' (${oswMatches.keys})")
            return true
        } else if (radioPcCount >= 1) {
            isGen2RawGroup = true
            rawContainerType = Gen2ContainerType.PC_STREAM
            rawStreamUris.putAll(pcMatches)
            generation = 2
            groupName = "GTA San Andreas (PC)"
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

    private fun getGen2RawStationsList(): ArrayList<MediaItem> {
        val list = ArrayList<MediaItem>()

        for ((code, _) in rawStreamUris) {
            if (code == "ADVERTS") continue
            val stName = Gen2MetadataRepository.defaultStations[code]?.first ?: code
            val mediaId = "${folderDoc.uri}#SA_RAW#$code"

            val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
            val iconUri = themePack?.let { DynamicThemeManager.resolveStationIconUri(it.stationIconUris, stName) }
            val metadata = MediaMetadata.Builder()
                .setTitle(stName)
                .setDisplayTitle(stName)
                .setSubtitle(groupName)
                .setArtist(groupName)
                .setAlbumTitle(groupName)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .apply {
                    if (iconUri != null) setArtworkUri(iconUri)
                }
                .setExtras(Bundle().apply {
                    putString("media_id", mediaId)
                    putString("group_name", groupName)
                })
                .build()
            list.add(MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build())
        }
        list.sortBy { it.mediaMetadata.title?.toString() ?: "" }
        return list
    }

    private fun getMediaItem(folderDoc: DocumentFile, simpleFiles: Array<SimpleFileInfo>, groupName: String): MediaItem {
        val themePack = DynamicThemeManager.findThemeForSeries(context, groupName)
        val logoUri = simpleFiles.find { x -> !x.isDirectory && x.name.uppercase().contains("LOGO") }?.uri ?: themePack?.groupLogoUri

        val metadata = MediaMetadata.Builder()
            .setTitle(groupName)
            .setDisplayTitle(groupName)
            .setIsPlayable(false)
            .setIsBrowsable(true)
            .apply {
                if (logoUri != null) setArtworkUri(logoUri)
            }
            .setExtras(Bundle().apply {
                putString("media_id", folderDoc.uri.toString())
            })
            .build()
        return MediaItem.Builder()
            .setMediaId(folderDoc.uri.toString())
            .setMediaMetadata(metadata)
            .build()
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

    private fun getStationsList(folderDoc: DocumentFile): ArrayList<MediaItem> {
        val list = ArrayList<MediaItem>()
        if (generation !in 1..3) return list

        val subDirs = folderDoc.listFiles().filter { x -> x.isDirectory }
        for (subDir in subDirs) {
            if (generation in 2..3 && subDir.uri.lastPathSegment?.uppercase()?.contains("ADVERTS") == true) continue
            if (generation == 3 && subDir.uri.lastPathSegment?.uppercase()?.contains("NEWS") == true) continue
            if (generation == 3 && subDir.uri.lastPathSegment?.uppercase()?.contains("WEATHER") == true) continue

            val stationName = subDir.name ?: ""
            val logoUri = subDir.listSimpleFiles(context).find { x -> !x.isDirectory && x.name.uppercase().contains("LOGO") }?.uri

            if (stationName.isNotBlank()) {
                val metadata = MediaMetadata.Builder()
                    .setTitle(stationName)
                    .setDisplayTitle(stationName)
                    .setSubtitle(groupName)
                    .setArtist(groupName)
                    .setAlbumTitle(groupName)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .apply {
                        if (logoUri != null) setArtworkUri(logoUri)
                    }
                    .setExtras(Bundle().apply {
                        putString("media_id", subDir.uri.toString())
                        putString("group_name", groupName)
                    })
                    .build()
                val item = MediaItem.Builder()
                    .setMediaId(subDir.uri.toString())
                    .setMediaMetadata(metadata)
                    .build()
                list.add(item)
            }
        }
        list.sortBy { x -> x.mediaMetadata.title?.toString() ?: "" }
        return list
    }

    fun getStationIndex(stationMediaId: String): Int {
        return stationList.indexOfFirst { x -> x.mediaId == stationMediaId }
    }

    fun toCachedDescriptor(): LibraryIndexCache.CachedGroupDescriptor {
        val format = when {
            isGen1RawGroup -> "GEN1_RAW"
            isGen2RawGroup -> "GEN2_RAW"
            isRpf3Group -> "RPF3"
            isRpf7Group -> "RPF7"
            else -> when (generation) {
                1 -> "GEN1_FOLDER"
                2 -> "GEN2_FOLDER"
                3 -> "GEN3_FOLDER"
                else -> "GEN2_FOLDER"
            }
        }
        val shared = HashMap<String, String>()
        rpf3ExtraTracksArchive?.sourceUri?.let { shared["extra_tracks"] = it.toString() }
        rpf3Ep1SfxArchive?.sourceUri?.let { shared["ep1_sfx"] = it.toString() }
        rpf3Ep2SfxArchive?.sourceUri?.let { shared["ep2_sfx"] = it.toString() }
        rpf3AdvertsArchive?.sourceUri?.let { shared["adverts"] = it.toString() }
        rpf3NewsArchive?.sourceUri?.let { shared["news"] = it.toString() }
        rpf3WeatherArchive?.sourceUri?.let { shared["weather"] = it.toString() }
        rpf7AdvertsArchive?.sourceUri?.let { shared["adverts"] = it.toString() }
        rpf7NewsArchive?.sourceUri?.let { shared["news"] = it.toString() }

        val cachedStations = stationList.map { st ->
            val meta = st.mediaMetadata
            val exMap = HashMap<String, String>()
            meta.extras?.let { b ->
                for (k in b.keySet()) {
                    b.getString(k)?.let { exMap[k] = it }
                }
            }
            var fUri = ""
            var archKey = ""
            var rpf = ""
            if (isRpf3Group) {
                val a = rpf3StationArchives[st.mediaId]
                fUri = a?.sourceUri?.toString() ?: ""
                rpf = a?.rpfName ?: ""
                archKey = rpf3StationKeys[st.mediaId] ?: ""
            } else if (isRpf7Group) {
                val a = rpf7StationArchives[st.mediaId]
                fUri = a?.sourceUri?.toString() ?: ""
                rpf = a?.rpfName ?: ""
                archKey = rpf7StationKeys[st.mediaId] ?: ""
            } else if (isGen1RawGroup) {
                val sfi = gen1StationFiles[st.mediaId]
                fUri = sfi?.uri?.toString() ?: ""
                archKey = sfi?.name ?: ""
            } else if (isGen2RawGroup) {
                val code = st.mediaId.substringAfter("#SA_RAW#")
                fUri = rawStreamUris[code]?.toString() ?: ""
                archKey = code
            } else {
                fUri = st.mediaId
            }
            LibraryIndexCache.CachedStationDescriptor(
                mediaId = st.mediaId,
                name = meta.title?.toString() ?: "",
                fileUri = fUri,
                archiveKey = archKey,
                rpfName = rpf,
                iconUri = meta.artworkUri?.toString(),
                extras = exMap
            )
        }

        return LibraryIndexCache.CachedGroupDescriptor(
            groupId = mediaItem.mediaId,
            groupName = groupName,
            folderUri = folderDoc.uri.toString(),
            formatType = format,
            generation = generation,
            gameCode = gen1GameCode,
            rawContainerType = rawContainerType?.name,
            rawStreamUris = rawStreamUris.mapValues { it.value.toString() },
            sharedUris = shared,
            logoUri = if (::mediaItem.isInitialized) mediaItem.mediaMetadata.artworkUri?.toString() else null,
            stations = cachedStations
        )
    }
}
