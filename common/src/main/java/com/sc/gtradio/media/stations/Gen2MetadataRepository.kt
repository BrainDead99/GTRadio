package com.sc.gtradio.media.stations

import android.content.Context
import android.graphics.Bitmap
import com.sc.gtradio.common.GTRadioLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

data class Gen2TrackMeta(
    val trackNumber: Int,
    val rawTitle: String,
    val cleanTitle: String,
    val artist: String
)

data class Gen2SongGroup(
    val cleanTitle: String,
    val artist: String,
    val midTrack: Int,
    val cleanIntroTrack: Int?,
    val cleanOutroTrack: Int?,
    val djIntroTracks: List<Int>,
    val djOutroTracks: List<Int>
)

class Gen2StationMeta(
    val streamCode: String,
    val stationName: String,
    val defaultArtist: String,
    val tracks: Map<Int, Gen2TrackMeta>
) {
    val idTracks = ArrayList<Int>()
    val djMorningTracks = ArrayList<Int>()
    val djEveningTracks = ArrayList<Int>()
    val djNightTracks = ArrayList<Int>()
    val djOtherTracks = ArrayList<Int>()
    val djCallerStoryTracks = ArrayList<Int>()
    val djWeatherTracks = ArrayList<Int>()
    val djWeatherSunTracks = ArrayList<Int>()
    val djWeatherFogTracks = ArrayList<Int>()
    val djWeatherRainTracks = ArrayList<Int>()
    val djWeatherStormTracks = ArrayList<Int>()
    val songGroups = ArrayList<Gen2SongGroup>()
}

object Gen2MetadataRepository {
    private var loaded = false
    private val byStreamCode = HashMap<String, Gen2StationMeta>()
    private val byStationName = HashMap<String, Gen2StationMeta>()
    private val albumNameCache = ConcurrentHashMap<String, String>()

    private val suffixRegex = Regex("\\s*\\((?:Mid|Intro(?:\\s*DJ\\s*#?\\d+)?|Outro(?:\\s*DJ\\s*#?\\d+)?)\\)\\s*$", RegexOption.IGNORE_CASE)
    private val wctrEpisodeRegex = Regex("\\s*-\\s*(?:Episode|Teaser)(?:\\s*#?\\d+)?.*$", RegexOption.IGNORE_CASE)
    private val newsRegex = Regex("^News(?:\\s*-.*)?$", RegexOption.IGNORE_CASE)
    private val remasterBanRegex = Regex("remaster|deluxe|expanded|anniversary|reissue|greatest hits|best of|anthology|edition|live", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("^(\\d{4})")

    fun cleanTrackTitle(rawTitle: String, isWctr: Boolean = false): String {
        val noSeg = suffixRegex.replace(rawTitle, "").trim()
        if (isWctr) {
            if (newsRegex.matches(noSeg)) {
                return "WCTR News"
            }
            val cleaned = wctrEpisodeRegex.replace(noSeg, "").trim()
            if (cleaned.isNotBlank()) {
                return cleaned
            }
        }
        return noSeg
    }

    // Built-in fallback station definitions so raw streams always appear even if ini fails
    val defaultStations = mapOf(
        "CH" to Pair("Playback FM", "Forth Right MC"),
        "CO" to Pair("KROSE", "Mary-Beth Maybell"),
        "CR" to Pair("K-DST", "Tommy 'The Nightmare' Smith"),
        "DS" to Pair("Bounce FM", "The Funktipus"),
        "HC" to Pair("SFUR", "Hans Oberlander"),
        "MH" to Pair("Radio Los Santos", "Julio G"),
        "MR" to Pair("Radio X", "Sage"),
        "NJ" to Pair("CSR", "Philip 'PM' Michaels"),
        "RE" to Pair("K-JAH", "Marshall Peters & Johnny Lawton"),
        "RG" to Pair("Master Sounds", "Johnny 'The Love Giant' Parkinson"),
        "TK" to Pair("WCTR", "WCTR Talk Radio"),
        "ADVERTS" to Pair("Adverts", "San Andreas Adverts")
    )

    @Synchronized
    fun ensureLoaded(context: Context) {
        GTRadioLogger.init(context)
        AlbumArtDownloader.init(context)
        if (loaded && byStreamCode.isNotEmpty()) return
        loaded = true

        // 1. Seed default stations first so station list never fails
        for ((code, info) in defaultStations) {
            val st = Gen2StationMeta(code, info.first, info.second, emptyMap())
            byStreamCode[code] = st
            byStationName[info.first.uppercase()] = st
        }

        // 2. Load detailed track metadata from bundled assets/gen2_metadata.ini
        try {
            context.assets.open("gen2_metadata.ini").use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    parseIni(reader)
                }
            }
            GTRadioLogger.log("Gen2MetadataRepository: Loaded gen2_metadata.ini (${byStreamCode.size} streams)")
        } catch (e: Exception) {
            GTRadioLogger.log("Gen2MetadataRepository: Failed to open assets/gen2_metadata.ini", e)
        }
    }

    private fun parseIni(reader: BufferedReader) {
        var curStream = ""
        var curStation = ""
        var curDefaultArtist = ""
        var curTracks = HashMap<Int, Pair<String, String>>()

        fun flushSection() {
            if (curStream.isNotBlank() && curStation.isNotBlank()) {
                val isWctr = curStream.equals("TK", ignoreCase = true) || curStation.equals("WCTR", ignoreCase = true)
                val metaMap = HashMap<Int, Gen2TrackMeta>()
                for ((num, pair) in curTracks) {
                    val rawT = pair.first
                    val cleanT = cleanTrackTitle(rawT, isWctr)
                    val art = pair.second.ifBlank { curDefaultArtist }
                    metaMap[num] = Gen2TrackMeta(num, rawT, cleanT, art)
                }
                val stMeta = Gen2StationMeta(curStream.uppercase(), curStation, curDefaultArtist, metaMap)
                classifyTracks(stMeta)
                byStreamCode[curStream.uppercase()] = stMeta
                byStationName[curStation.uppercase()] = stMeta
            }
        }

        var line: String? = reader.readLine()
        while (line != null) {
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                flushSection()
                curStream = ""
                curStation = ""
                curDefaultArtist = ""
                curTracks = HashMap()
            } else if (t.contains("=") && !t.startsWith("#")) {
                val parts = t.split("=", limit = 2)
                val k = parts[0].trim()
                val v = parts[1].trim().trim('"').trim('\'')
                when {
                    k == "stream" -> curStream = v
                    k == "station" -> curStation = v
                    k == "defaultartist" -> curDefaultArtist = v
                    k.startsWith("track") -> {
                        val dot = k.indexOf('.')
                        if (dot > 5) {
                            val num = k.substring(5, dot).toIntOrNull()
                            val field = k.substring(dot + 1)
                            if (num != null) {
                                val existing = curTracks[num] ?: Pair("", "")
                                if (field == "title") curTracks[num] = Pair(v, existing.second)
                                else if (field == "artist") curTracks[num] = Pair(existing.first, v)
                            }
                        }
                    }
                }
            }
            line = reader.readLine()
        }
        flushSection()
    }

    private fun classifyTracks(st: Gen2StationMeta) {
        val isWctr = st.streamCode == "TK" || st.stationName.uppercase() == "WCTR"
        val songBuckets = LinkedHashMap<String, Triple<MutableList<Int>, MutableList<Int>, MutableList<Int>>>()
        val cleanIntros = HashMap<String, Int>()
        val cleanOutros = HashMap<String, Int>()
        val songArtists = HashMap<String, String>()
        val songCleanTitles = HashMap<String, String>()

        for ((num, tr) in st.tracks.entries.sortedBy { it.key }) {
            val rt = tr.rawTitle
            val u = rt.uppercase()
            when {
                u.contains("(ID)") -> st.idTracks.add(num)
                u.contains("(ATMOSPHERE)") -> {
                    when {
                        u.contains("MORNING") -> st.djMorningTracks.add(num)
                        u.contains("EVENING") -> st.djEveningTracks.add(num)
                        u.contains("NIGHT") -> st.djNightTracks.add(num)
                        u.contains("FOG") || u.contains("RAIN") || u.contains("SUN") || u.contains("STORM") || u.contains("WEATHER") -> {
                            st.djWeatherTracks.add(num)
                            when {
                                u.contains("SUN") -> st.djWeatherSunTracks.add(num)
                                u.contains("FOG") -> st.djWeatherFogTracks.add(num)
                                u.contains("RAIN") -> st.djWeatherRainTracks.add(num)
                                u.contains("STORM") -> st.djWeatherStormTracks.add(num)
                            }
                        }
                        else -> st.djOtherTracks.add(num)
                    }
                }
                !isWctr && (u.contains("(CALLER)") || u.contains("(STORY)") || u.contains("(BRIDGE ANNOUNCE")) -> {
                    st.djCallerStoryTracks.add(num)
                }
                !isWctr && u.contains("(DJ)") -> {
                    st.djOtherTracks.add(num)
                }
                u.contains("(MID)") || u.contains("(INTRO") || u.contains("(OUTRO") || isWctr -> {
                    // Group by episode/song identity using rawTitle stripped of (Mid)/(Intro)/(Outro)
                    // so that separate talk show episodes remain distinct groups while sharing the clean show title.
                    val groupKey = suffixRegex.replace(rt, "").trim()
                    songArtists[groupKey] = tr.artist
                    songCleanTitles[groupKey] = tr.cleanTitle
                    val bucket = songBuckets.getOrPut(groupKey) { Triple(mutableListOf(), mutableListOf(), mutableListOf()) }
                    when {
                        u.contains("(INTRO DJ") -> bucket.first.add(num)
                        u.contains("(INTRO)") -> cleanIntros[groupKey] = num
                        u.contains("(OUTRO DJ") -> bucket.third.add(num)
                        u.contains("(OUTRO)") -> cleanOutros[groupKey] = num
                        else -> bucket.second.add(num)
                    }
                }
            }
        }

        for ((groupKey, triple) in songBuckets) {
            val mid = triple.second.firstOrNull() ?: continue
            st.songGroups.add(
                Gen2SongGroup(
                    cleanTitle = songCleanTitles[groupKey] ?: groupKey,
                    artist = songArtists[groupKey] ?: st.defaultArtist,
                    midTrack = mid,
                    cleanIntroTrack = cleanIntros[groupKey],
                    cleanOutroTrack = cleanOutros[groupKey],
                    djIntroTracks = triple.first,
                    djOutroTracks = triple.third
                )
            )
        }
    }

    fun getByStreamCode(context: Context, code: String): Gen2StationMeta? {
        ensureLoaded(context)
        return byStreamCode[code.uppercase()]
    }

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
        maxYear: Int = 2004,
        onDone: (() -> Unit)? = null
    ) {
        val prefYear = if (maxYear in 2008..2010) 2009 else 1993
        AlbumArtDownloader.fetchEraAccurateArtAsync(
            context = context,
            artist = artist,
            songTitle = songTitle,
            preferredYear = prefYear,
            maxYear = maxYear,
            onDone = onDone
        )
    }

    fun fetchEraAccurateArtSync(
        context: Context,
        artist: String,
        songTitle: String,
        maxYear: Int = 2004
    ): Boolean {
        val prefYear = if (maxYear in 2008..2010) 2009 else 1993
        return AlbumArtDownloader.fetchEraAccurateArtSync(
            context = context,
            artist = artist,
            songTitle = songTitle,
            preferredYear = prefYear,
            maxYear = maxYear
        )
    }
}
