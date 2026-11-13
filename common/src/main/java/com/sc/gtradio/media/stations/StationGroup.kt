package com.sc.gtradio.media.stations

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.documentfile.provider.DocumentFile
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.FileUtils
import com.sc.gtradio.common.FileUtils.listSimpleFiles
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.common.SimpleFileInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class StationGroup(val folderDoc: DocumentFile, private val context: Context) {

    var mediaItem: MediaBrowserCompat.MediaItem
    var stationList: ArrayList<MediaBrowserCompat.MediaItem>
    var generation: Int = -1
    var groupName: String = ""

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
            scanRecursive(rootDoc, context, results, depth = 0, maxDepth = 4)
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
        stationList = if (isRawSaGroup) {
            getRawSaStationsList()
        } else {
            getStationsList(folderDoc)
        }
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
            val iconUri = themePack?.stationIconUris?.get(DynamicThemeManager.normalizeStationKey(stName))
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
