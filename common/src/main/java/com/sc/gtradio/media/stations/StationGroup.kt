package com.sc.gtradio.media.stations

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.documentfile.provider.DocumentFile
import com.sc.gtradio.common.FileUtils
import com.sc.gtradio.common.FileUtils.listSimpleFiles
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

        fun scanRootDirectory(rootDoc: DocumentFile?, context: Context): ArrayList<StationGroup> {
            val results = ArrayList<StationGroup>()
            if (rootDoc == null || !rootDoc.isDirectory) return results

            // 1. Check if the selected folder itself is a StationGroup (Raw PC, Mobile .osw, or has stationGroupInfo.json)
            val directGroup = StationGroup(rootDoc, context)
            if (directGroup.generation in 1..3 && directGroup.stationList.isNotEmpty()) {
                results.add(directGroup)
            }

            // 2. Check immediate subdirectories (and 1 level deeper e.g. audio/streams)
            val children = rootDoc.listFiles().filter { it.isDirectory }
            for (subDir in children) {
                val g = StationGroup(subDir, context)
                if (g.generation in 1..3 && g.stationList.isNotEmpty()) {
                    results.add(g)
                } else {
                    val nested = subDir.listFiles().filter { it.isDirectory }
                    for (deepDir in nested) {
                        val dg = StationGroup(deepDir, context)
                        if (dg.generation in 1..3 && dg.stationList.isNotEmpty()) {
                            results.add(dg)
                        }
                    }
                }
            }

            results.sortBy { it.groupName }
            return results
        }
    }

    init {
        val simpleFiles = try {
            folderDoc.listSimpleFiles(context)
        } catch (_: Exception) {
            emptyArray()
        }

        if (!detectRawSaStreams(simpleFiles)) {
            setupDataFromJson(folderDoc)
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
            val upper = f.name.uppercase()
            if (upper.endsWith(".OSW")) {
                val code = upper.removeSuffix(".OSW")
                if (code in SA_STREAM_CODES) {
                    oswMatches[code] = f.uri
                }
            } else if (!upper.contains(".")) {
                if (upper in SA_STREAM_CODES) {
                    pcMatches[upper] = f.uri
                }
            } else if (upper.endsWith(".WAV") || upper.endsWith(".OGG")) {
                val code = upper.substringBeforeLast(".")
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
            groupName = "GTA San Andreas (Mobile)"
            return true
        } else if (radioPcCount >= 1) {
            isRawSaGroup = true
            rawContainerType = SaContainerType.PC_STREAM
            rawStreamUris.putAll(pcMatches)
            generation = 2
            groupName = "GTA San Andreas (PC)"
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
            val stMeta = SaMetadataRepository.getByStreamCode(context, code) ?: continue
            val stName = stMeta.stationName
            val mediaId = "${folderDoc.uri}#SA_RAW#$code"

            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stName)
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
        val logoUri = simpleFiles.find { x -> !x.isDirectory && x.name.uppercase().contains("LOGO") }?.uri
        val logoBitmap = FileUtils.getBitmapFromUri(context.contentResolver, logoUri)

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

    private fun setupDataFromJson(folderDoc: DocumentFile) {
        val jsonInfo = folderDoc.findFile("stationGroupInfo.json") ?: return
        var jsonString = ""
        try {
            val stringBuilder = StringBuilder()
            context.contentResolver.openInputStream(jsonInfo.uri)?.use { inputStream ->
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
            e.printStackTrace()
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
