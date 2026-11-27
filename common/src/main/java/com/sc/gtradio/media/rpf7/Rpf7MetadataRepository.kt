package com.sc.gtradio.media.rpf7

import android.content.Context
import android.graphics.Bitmap
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.artwork.AlbumArtDownloader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Rpf7StationInfo(
    val rpfName: String,
    val stationName: String,
    val djName: String,
    val isTalk: Boolean = false,
    val isContinuousMix: Boolean = false
)

data class Rpf7TrackMeta(
    val internalName: String,
    val title: String,
    val artist: String,
    val isTalkShow: Boolean = false,
    val isContinuousMix: Boolean = false
)

object Rpf7MetadataRepository {

    private val albumNameCache = ConcurrentHashMap<String, String>()
    private val remasterBanRegex = Regex("remaster|deluxe|expanded|anniversary|reissue|greatest hits|best of|anthology|edition|live", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("^(\\d{4})")

    val stationInfoByRpf: Map<String, Rpf7StationInfo> = linkedMapOf(
        "radio_01_class_rock.rpf" to Rpf7StationInfo("radio_01_class_rock.rpf", "Los Santos Rock Radio", "Kenny Loggins", isTalk = false, isContinuousMix = false),
        "radio_02_pop.rpf" to Rpf7StationInfo("radio_02_pop.rpf", "Non-Stop-Pop FM", "Cara Delevingne", isTalk = false, isContinuousMix = false),
        "radio_03_hiphop_new.rpf" to Rpf7StationInfo("radio_03_hiphop_new.rpf", "Radio Los Santos", "Big Boy", isTalk = false, isContinuousMix = false),
        "radio_04_punk.rpf" to Rpf7StationInfo("radio_04_punk.rpf", "Channel X", "Keith Morris", isTalk = false, isContinuousMix = false),
        "radio_05_talk_01.rpf" to Rpf7StationInfo("radio_05_talk_01.rpf", "West Coast Talk Radio", "WCTR Talk Radio", isTalk = true, isContinuousMix = false),
        "radio_06_country.rpf" to Rpf7StationInfo("radio_06_country.rpf", "Rebel Radio", "Jesco White", isTalk = false, isContinuousMix = false),
        "radio_07_dance_01.rpf" to Rpf7StationInfo("radio_07_dance_01.rpf", "Soulwax FM", "Soulwax", isTalk = false, isContinuousMix = true),
        "radio_08_mexican.rpf" to Rpf7StationInfo("radio_08_mexican.rpf", "East Los FM", "Don Cheto & Camilo Lara", isTalk = false, isContinuousMix = true),
        "radio_09_hiphop_old.rpf" to Rpf7StationInfo("radio_09_hiphop_old.rpf", "West Coast Classics", "DJ Pooh", isTalk = false, isContinuousMix = false),
        "radio_11_talk_02.rpf" to Rpf7StationInfo("radio_11_talk_02.rpf", "Blaine County Radio", "Blaine County Talk Radio", isTalk = true, isContinuousMix = false),
        "radio_12_reggae.rpf" to Rpf7StationInfo("radio_12_reggae.rpf", "Blue Ark", "Lee \"Scratch\" Perry", isTalk = false, isContinuousMix = false),
        "radio_13_jazz.rpf" to Rpf7StationInfo("radio_13_jazz.rpf", "Worldwide FM", "Gilles Peterson", isTalk = false, isContinuousMix = true),
        "radio_14_dance_02.rpf" to Rpf7StationInfo("radio_14_dance_02.rpf", "FlyLo FM", "Flying Lotus", isTalk = false, isContinuousMix = true),
        "radio_15_motown.rpf" to Rpf7StationInfo("radio_15_motown.rpf", "The Lowdown 91.1", "Pam Grier", isTalk = false, isContinuousMix = false),
        "radio_16_silverlake.rpf" to Rpf7StationInfo("radio_16_silverlake.rpf", "Radio Mirror Park", "Twin Shadow", isTalk = false, isContinuousMix = false),
        "radio_17_funk.rpf" to Rpf7StationInfo("radio_17_funk.rpf", "Space 103.2", "Bootsy Collins", isTalk = false, isContinuousMix = false),
        "radio_18_90s_rock.rpf" to Rpf7StationInfo("radio_18_90s_rock.rpf", "Vinewood Boulevard Radio", "Nate Williams & Stephen Pope", isTalk = false, isContinuousMix = false),
        "radio_20_thelab.rpf" to Rpf7StationInfo("radio_20_thelab.rpf", "The Lab", "The Alchemist & Oh No", isTalk = false, isContinuousMix = true)
    )

    @Volatile
    private var loaded = false
    private val trackMetaMap = HashMap<String, Rpf7TrackMeta>(500)
    private val trackIdMetaMapInternal = HashMap<Int, Rpf7TrackMeta>(150)

    val trackIdMetaMap: Map<Int, Rpf7TrackMeta>
        get() {
            ensureLoaded()
            return trackIdMetaMapInternal
        }

    @Synchronized
    fun ensureLoaded(context: Context? = AlbumArtDownloader.appContext) {
        if (loaded && trackMetaMap.isNotEmpty()) return
        val ctx = context ?: AlbumArtDownloader.appContext ?: return
        loaded = true
        try {
            ctx.assets.open("metadata/rpf7_tracks.tsv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine() // skip header
                    while (true) {
                        line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        val parts = line.split('\t')
                        if (parts.size >= 5) {
                            val key = parts[0].uppercase(Locale.ROOT)
                            val title = parts[1]
                            val artist = parts[2]
                            val isTalk = parts[3].toBoolean()
                            val isMix = parts[4].toBoolean()
                            trackMetaMap[key] = Rpf7TrackMeta(key, title, artist, isTalkShow = isTalk, isContinuousMix = isMix)
                        }
                    }
                }
            }
            ctx.assets.open("metadata/rpf7_trackids.tsv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine() // skip header
                    while (true) {
                        line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        val parts = line.split('\t')
                        if (parts.size >= 3) {
                            val tid = parts[0].toIntOrNull() ?: continue
                            val title = parts[1]
                            val artist = parts[2]
                            trackIdMetaMapInternal[tid] = Rpf7TrackMeta(tid.toString(), title, artist, isTalkShow = false, isContinuousMix = false)
                        }
                    }
                }
            }
            GTRadioLogger.log("Rpf7MetadataRepository: Loaded ${trackMetaMap.size} tracks & ${trackIdMetaMapInternal.size} track IDs")
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf7MetadataRepository: Failed to load metadata/rpf7_*.tsv", e)
        }
    }

    fun getTrackByTrackId(trackId: Int, context: Context? = null): Rpf7TrackMeta? {
        ensureLoaded(context)
        return trackIdMetaMapInternal[trackId]
    }

    fun getAllMusicSongs(context: Context? = null): List<Pair<String, String>> {
        ensureLoaded(context)
        val list = trackMetaMap.values
            .filter { !it.isTalkShow && !it.isContinuousMix && it.artist.isNotBlank() && it.title.isNotBlank() }
            .map { Pair(it.artist, it.title) }
            .toMutableList()
        list.addAll(
            trackIdMetaMapInternal.values
                .filter { it.artist.isNotBlank() && it.title.isNotBlank() }
                .map { Pair(it.artist, it.title) }
        )
        return list.distinct()
    }

    fun getStationInfo(rpfName: String): Rpf7StationInfo? {
        val lower = rpfName.lowercase(Locale.ROOT)
        return stationInfoByRpf[lower] ?: stationInfoByRpf["${lower}.rpf"]
    }

    fun isContinuousMix(rpfName: String): Boolean {
        return getStationInfo(rpfName)?.isContinuousMix == true
    }

    fun getTrackMeta(internalName: String, fallbackStationTitle: String = "", context: Context? = null): Rpf7TrackMeta {
        ensureLoaded(context)
        val cleanStem = internalName.removeSuffix(".awc").uppercase(Locale.ROOT)
        val direct = trackMetaMap[cleanStem]
        if (direct != null) return direct

        // Fallback: title-case formatting from snake_case
        val formattedTitle = formatSnakeCase(internalName)
        val defaultArtist = fallbackStationTitle.ifBlank { "Radio" }
        return Rpf7TrackMeta(cleanStem, formattedTitle, defaultArtist, isTalkShow = false, isContinuousMix = false)
    }

    fun formatSnakeCase(name: String): String {
        val clean = name.removeSuffix(".awc").replace('_', ' ').trim()
        return clean.split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    // ========================================================================
    // Era-Accurate Album Art Caching & Fetching (Cutoff: maxYear = 2013)
    // ========================================================================
    // Artwork & Metadata
    // ========================================================================

    fun clearMemoryCache() {
        albumNameCache.clear()
    }

    private fun cacheKey(artist: String, songTitle: String): String {
        return AlbumArtDownloader.cacheKey(artist, songTitle)
    }

    fun getCachedAlbumArt(context: Context, artist: String, songTitle: String): Bitmap? {
        return AlbumArtDownloader.getCachedAlbumArt(context, artist, songTitle)
    }

    fun getCachedAlbumName(context: Context, artist: String, songTitle: String): String? {
        return AlbumArtDownloader.getCachedAlbumName(context, artist, songTitle)
    }

    fun getCachedAlbumTitle(context: Context, artist: String, songTitle: String): String? {
        return AlbumArtDownloader.getCachedAlbumTitle(context, artist, songTitle)
    }

    fun fetchEraAccurateArtAsync(
        context: Context,
        artist: String,
        songTitle: String,
        maxYear: Int = 2021,
        onDone: (() -> Unit)? = null
    ) {
        val effMaxYear = if (maxYear <= 2013) 2021 else maxYear
        AlbumArtDownloader.fetchEraAccurateArtAsync(
            context = context,
            artist = artist,
            songTitle = songTitle,
            preferredYear = 2013,
            maxYear = effMaxYear,
            onDone = onDone
        )
    }

    fun fetchEraAccurateArtSync(
        context: Context,
        artist: String,
        songTitle: String,
        maxYear: Int = 2021
    ): Boolean {
        val effMaxYear = if (maxYear <= 2013) 2021 else maxYear
        return AlbumArtDownloader.fetchEraAccurateArtSync(
            context = context,
            artist = artist,
            songTitle = songTitle,
            preferredYear = 2013,
            maxYear = effMaxYear
        )
    }
}
