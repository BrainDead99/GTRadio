package com.sc.gtradio.media.core
import com.sc.gtradio.media.rpf3.Rpf3KeyManager
import com.sc.gtradio.media.rpf7.Rpf7KeyManager


import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sc.gtradio.common.GTRadioLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent SAF library and station index cache.
 *
 * Stores a lightweight JSON representation of discovered station groups so cold starts can
 * hydrate station groups and MediaItem lists in < 200 ms without full SAF traversal.
 */
object LibraryIndexCache {

    private const val CACHE_FILE_NAME = "library_index_cache.json"
    const val CACHE_VERSION = 2

    // ── Data classes ────────────────────────────────────────────────────

    data class CachedLibraryIndex(
        val cacheVersion: Int,
        val rootUri: String,
        val audioDirUri: String,
        val themeDirUri: String?,
        val keyFingerprint: String,
        val groups: List<CachedGroupDescriptor>
    )

    data class CachedSimpleFile(
        val uri: String,
        val name: String,
        val id: String,
        val isDirectory: Boolean = false
    )

    data class CachedGroupDescriptor(
        val groupId: String,
        val groupName: String,
        val folderUri: String,
        val formatType: String,
        val generation: Int,
        val gameCode: Int = 0,
        val rawContainerType: String? = null,
        val rawStreamUris: Map<String, String> = emptyMap(),
        val sharedUris: Map<String, String> = emptyMap(),
        val logoUri: String? = null,
        val stations: List<CachedStationDescriptor>,
        val folderFingerprint: String = ""
    )

    data class CachedStationDescriptor(
        val mediaId: String,
        val name: String,
        val fileUri: String = "",
        val archiveKey: String = "",
        val rpfName: String = "",
        val iconUri: String? = null,
        val extras: Map<String, String> = emptyMap(),
        val innerDirFilter: String? = null,
        val allowedEntryNames: List<String>? = null,
        val looseAwcFiles: List<CachedSimpleFile>? = null
    )

    data class MediaItemGroupProxy(
        val mediaItem: MediaItem,
        val stationList: ArrayList<MediaItem>,
        val mediaId: String,
        val groupName: String
    )

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns a fingerprint of the current RPF3 / RPF7 key states so
     * the cache can be invalidated when keys are imported or cleared.
     */
    fun computeKeyFingerprint(context: Context): String {
        val rpf3State = if (Rpf3KeyManager.isUnlocked(context)) "unlocked" else "locked"
        val rpf7State = if (Rpf7KeyManager.isUnlocked(context)) "unlocked" else "locked"
        return "rpf3=$rpf3State|rpf7=$rpf7State"
    }

    /**
     * Loads the cache from disk. Returns `null` when the file is missing,
     * unreadable, or when [CACHE_VERSION], [currentRootUri] or key fingerprint
     * do not match the stored values.
     */
    fun loadCache(context: Context, currentRootUri: String, validateFolders: Boolean = true): CachedLibraryIndex? {
        val file = cacheFile(context)
        if (!file.exists()) {
            return null
        }

        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))

            val storedVersion = json.optInt("cacheVersion", -1)
            if (storedVersion != CACHE_VERSION) {
                GTRadioLogger.log("[SCAN] LibraryIndexCache: cache miss – version mismatch (stored=$storedVersion, expected=$CACHE_VERSION)")
                return null
            }

            val storedRootUri = json.optString("rootUri", "")
            if (storedRootUri != currentRootUri) {
                GTRadioLogger.log("[SCAN] LibraryIndexCache: cache miss – rootUri mismatch")
                return null
            }

            val storedFingerprint = json.optString("keyFingerprint", "")
            val currentFingerprint = computeKeyFingerprint(context)
            if (storedFingerprint != currentFingerprint) {
                GTRadioLogger.log("[SCAN] LibraryIndexCache: cache miss – keyFingerprint mismatch (stored=$storedFingerprint, current=$currentFingerprint)")
                return null
            }

            val groupsArray = json.optJSONArray("groups") ?: JSONArray()
            val groups = ArrayList<CachedGroupDescriptor>(groupsArray.length())
            for (i in 0 until groupsArray.length()) {
                groups.add(parseGroupDescriptor(groupsArray.getJSONObject(i)))
            }

            if (validateFolders) {
                for (g in groups) {
                    if (g.folderFingerprint.isNotEmpty() && g.folderUri.isNotBlank()) {
                        val currentGroupFp = StationGroup.computeFolderFingerprintForUri(context, g.folderUri)
                        if (currentGroupFp != null && currentGroupFp != g.folderFingerprint) {
                            GTRadioLogger.log("[SCAN] LibraryIndexCache: cache miss – folder contents changed for '${g.groupName}'")
                            return null
                        }
                    }
                }
            }

            val rawTheme = json.opt("themeDirUri")
            val themeDirUri = if (rawTheme == null || rawTheme == JSONObject.NULL) null else rawTheme.toString()

            val index = CachedLibraryIndex(
                cacheVersion = storedVersion,
                rootUri = storedRootUri,
                audioDirUri = json.optString("audioDirUri", ""),
                themeDirUri = themeDirUri,
                keyFingerprint = storedFingerprint,
                groups = groups
            )
            index
        } catch (e: Exception) {
            GTRadioLogger.log("[SCAN] LibraryIndexCache: cache miss – failed to parse", e)
            null
        }
    }

    /**
     * Serialises the current list of [StationGroup]s to the cache file.
     */
    fun saveCache(
        context: Context,
        rootUri: String,
        groups: List<StationGroup>,
        audioDirUri: String = "",
        themeDirUri: String? = null
    ) {
        try {
            val fingerprint = computeKeyFingerprint(context)
            val root = JSONObject()
            root.put("cacheVersion", CACHE_VERSION)
            root.put("rootUri", rootUri)
            root.put("audioDirUri", audioDirUri)
            root.put("themeDirUri", themeDirUri ?: JSONObject.NULL)
            root.put("keyFingerprint", fingerprint)

            val groupsArray = JSONArray()
            for (group in groups) {
                groupsArray.put(serialiseGroup(group.toCachedDescriptor()))
            }
            root.put("groups", groupsArray)

            val file = cacheFile(context)
            file.writeText(root.toString(), Charsets.UTF_8)
            GTRadioLogger.log("[SCAN] LibraryIndexCache: saved ${groups.size} group(s) to cache")
        } catch (e: Exception) {
            GTRadioLogger.log("[SCAN] LibraryIndexCache: failed to save cache", e)
        }
    }

    /**
     * Deletes the cache file.
     */
    fun invalidate(context: Context) {
        val file = cacheFile(context)
        if (file.exists()) {
            file.delete()
            GTRadioLogger.log("[SCAN] LibraryIndexCache: cache invalidated")
        }
    }

    /**
     * Hydrates full [StationGroup] objects from cache descriptors without SAF folder traversal.
     */
    fun hydrateGroups(cache: CachedLibraryIndex, context: Context): ArrayList<StationGroup> {
        val groups = ArrayList<StationGroup>(cache.groups.size)
        for (desc in cache.groups) {
            try {
                groups.add(StationGroup.fromCachedDescriptor(desc, context))
            } catch (e: Exception) {
                GTRadioLogger.log("[SCAN] LibraryIndexCache: failed to hydrate group '${desc.groupName}'", e)
            }
        }
        return groups
    }

    /**
     * Builds lightweight proxy objects from cached descriptors.
     */
    fun hydrateMediaItems(cache: CachedLibraryIndex): Pair<ArrayList<MediaItemGroupProxy>, ArrayList<MediaItem>> {
        val proxies = ArrayList<MediaItemGroupProxy>(cache.groups.size)
        val allStations = ArrayList<MediaItem>()

        for (group in cache.groups) {
            val groupExtras = Bundle().apply {
                putString("media_id", group.groupId)
            }
            val groupMeta = MediaMetadata.Builder()
                .setTitle(group.groupName)
                .setDisplayTitle(group.groupName)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .apply {
                    group.logoUri?.let { setArtworkUri(Uri.parse(it)) }
                }
                .setExtras(groupExtras)
                .build()
            val groupMediaItem = MediaItem.Builder()
                .setMediaId(group.groupId)
                .setMediaMetadata(groupMeta)
                .build()

            val stationItems = ArrayList<MediaItem>(group.stations.size)
            for (st in group.stations) {
                val stExtras = Bundle().apply {
                    putString("media_id", st.mediaId)
                    putString("group_name", group.groupName)
                    for ((k, v) in st.extras) {
                        putString(k, v)
                    }
                }
                val artworkUri = st.iconUri?.let { Uri.parse(it) }
                val stMeta = MediaMetadata.Builder()
                    .setTitle(st.name)
                    .setDisplayTitle(st.name)
                    .setSubtitle(group.groupName)
                    .setArtist(group.groupName)
                    .setAlbumTitle(group.groupName)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .apply {
                        if (artworkUri != null) setArtworkUri(artworkUri)
                    }
                    .setExtras(stExtras)
                    .build()
                val stItem = MediaItem.Builder()
                    .setMediaId(st.mediaId)
                    .setMediaMetadata(stMeta)
                    .build()
                stationItems.add(stItem)
                allStations.add(stItem)
            }

            proxies.add(
                MediaItemGroupProxy(
                    mediaItem = groupMediaItem,
                    stationList = stationItems,
                    mediaId = group.groupId,
                    groupName = group.groupName
                )
            )
        }

        return Pair(proxies, allStations)
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private fun cacheFile(context: Context): File =
        File(context.filesDir, CACHE_FILE_NAME)

    // -- Serialisation ----------------------------------------------------

    private fun serialiseGroup(desc: CachedGroupDescriptor): JSONObject {
        val obj = JSONObject()
        obj.put("groupId", desc.groupId)
        obj.put("groupName", desc.groupName)
        obj.put("folderUri", desc.folderUri)
        obj.put("formatType", desc.formatType)
        obj.put("generation", desc.generation)
        obj.put("gameCode", desc.gameCode)
        obj.put("rawContainerType", desc.rawContainerType ?: JSONObject.NULL)
        obj.put("logoUri", desc.logoUri ?: JSONObject.NULL)
        obj.put("folderFingerprint", desc.folderFingerprint)

        val rawStreamsObj = JSONObject()
        for ((k, v) in desc.rawStreamUris) {
            rawStreamsObj.put(k, v)
        }
        obj.put("rawStreamUris", rawStreamsObj)

        val sharedObj = JSONObject()
        for ((k, v) in desc.sharedUris) {
            sharedObj.put(k, v)
        }
        obj.put("sharedUris", sharedObj)

        val stationsArray = JSONArray()
        for (st in desc.stations) {
            stationsArray.put(serialiseStation(st))
        }
        obj.put("stations", stationsArray)
        return obj
    }

    private fun serialiseStation(st: CachedStationDescriptor): JSONObject {
        val obj = JSONObject()
        obj.put("mediaId", st.mediaId)
        obj.put("name", st.name)
        obj.put("fileUri", st.fileUri)
        obj.put("archiveKey", st.archiveKey)
        obj.put("rpfName", st.rpfName)
        obj.put("iconUri", st.iconUri ?: JSONObject.NULL)
        obj.put("innerDirFilter", st.innerDirFilter ?: JSONObject.NULL)

        if (!st.allowedEntryNames.isNullOrEmpty()) {
            val arr = JSONArray()
            for (n in st.allowedEntryNames) arr.put(n)
            obj.put("allowedEntryNames", arr)
        }

        if (!st.looseAwcFiles.isNullOrEmpty()) {
            val arr = JSONArray()
            for (f in st.looseAwcFiles) {
                val fObj = JSONObject()
                fObj.put("uri", f.uri)
                fObj.put("name", f.name)
                fObj.put("id", f.id)
                fObj.put("isDirectory", f.isDirectory)
                arr.put(fObj)
            }
            obj.put("looseAwcFiles", arr)
        }

        val extrasMap = JSONObject()
        for ((k, v) in st.extras) {
            extrasMap.put(k, v)
        }
        obj.put("extras", extrasMap)
        return obj
    }

    // -- Deserialisation --------------------------------------------------

    private fun parseGroupDescriptor(json: JSONObject): CachedGroupDescriptor {
        val stationsArray = json.optJSONArray("stations") ?: JSONArray()
        val stations = ArrayList<CachedStationDescriptor>(stationsArray.length())
        for (i in 0 until stationsArray.length()) {
            stations.add(parseStationDescriptor(stationsArray.getJSONObject(i)))
        }

        val rawContainerType = json.opt("rawContainerType")
        val containerTypeStr = if (rawContainerType == null || rawContainerType == JSONObject.NULL) null else rawContainerType.toString()

        val rawLogo = json.opt("logoUri")
        val logoUri = if (rawLogo == null || rawLogo == JSONObject.NULL) null else rawLogo.toString()

        val rawStreams = HashMap<String, String>()
        val rawStreamsJson = json.optJSONObject("rawStreamUris")
        if (rawStreamsJson != null) {
            val keys = rawStreamsJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                rawStreams[k] = rawStreamsJson.optString(k)
            }
        }

        val sharedUris = HashMap<String, String>()
        val sharedJson = json.optJSONObject("sharedUris")
        if (sharedJson != null) {
            val keys = sharedJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                sharedUris[k] = sharedJson.optString(k)
            }
        }

        return CachedGroupDescriptor(
            groupId = json.getString("groupId"),
            groupName = json.getString("groupName"),
            folderUri = json.optString("folderUri", ""),
            formatType = json.optString("formatType", "GEN2_FOLDER"),
            generation = json.optInt("generation", -1),
            gameCode = json.optInt("gameCode", 0),
            rawContainerType = containerTypeStr,
            rawStreamUris = rawStreams,
            sharedUris = sharedUris,
            logoUri = logoUri,
            stations = stations,
            folderFingerprint = json.optString("folderFingerprint", "")
        )
    }

    private fun parseStationDescriptor(json: JSONObject): CachedStationDescriptor {
        val extrasJson = json.optJSONObject("extras")
        val extras = HashMap<String, String>()
        if (extrasJson != null) {
            val keys = extrasJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = extrasJson.optString(k)
                if (v.isNotEmpty()) {
                    extras[k] = v
                }
            }
        }
        val rawIcon = json.opt("iconUri")
        val iconUri: String? = if (rawIcon == null || rawIcon == JSONObject.NULL) null else rawIcon.toString()
        val rawInnerDir = json.opt("innerDirFilter")
        val innerDirFilter: String? = if (rawInnerDir == null || rawInnerDir == JSONObject.NULL) null else rawInnerDir.toString()

        val allowedArray = json.optJSONArray("allowedEntryNames")
        val allowedEntryNames = if (allowedArray != null && allowedArray.length() > 0) {
            ArrayList<String>(allowedArray.length()).apply {
                for (i in 0 until allowedArray.length()) {
                    val s = allowedArray.optString(i)
                    if (s.isNotEmpty()) add(s)
                }
            }
        } else null

        val looseArray = json.optJSONArray("looseAwcFiles")
        val looseAwcFiles = if (looseArray != null && looseArray.length() > 0) {
            ArrayList<CachedSimpleFile>(looseArray.length()).apply {
                for (i in 0 until looseArray.length()) {
                    val fObj = looseArray.optJSONObject(i) ?: continue
                    val u = fObj.optString("uri", "")
                    val n = fObj.optString("name", "")
                    val id = fObj.optString("id", "")
                    val isDir = fObj.optBoolean("isDirectory", false)
                    if (u.isNotEmpty() && n.isNotEmpty()) {
                        add(CachedSimpleFile(uri = u, name = n, id = id, isDirectory = isDir))
                    }
                }
            }
        } else null

        return CachedStationDescriptor(
            mediaId = json.getString("mediaId"),
            name = json.getString("name"),
            fileUri = json.optString("fileUri", ""),
            archiveKey = json.optString("archiveKey", ""),
            rpfName = json.optString("rpfName", ""),
            iconUri = iconUri,
            extras = extras,
            innerDirFilter = innerDirFilter,
            allowedEntryNames = allowedEntryNames,
            looseAwcFiles = looseAwcFiles
        )
    }
}
