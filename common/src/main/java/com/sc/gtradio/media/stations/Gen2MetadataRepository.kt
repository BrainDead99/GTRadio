package com.sc.gtradio.media.stations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sc.gtradio.common.GTRadioLogger
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
    val songGroups = ArrayList<Gen2SongGroup>()
}

object Gen2MetadataRepository {
    private var loaded = false
    private val byStreamCode = HashMap<String, Gen2StationMeta>()
    private val byStationName = HashMap<String, Gen2StationMeta>()
    private val albumNameCache = ConcurrentHashMap<String, String>()
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val suffixRegex = Regex("\\s*\\((?:Mid|Intro(?:\\s*DJ\\s*#?\\d+)?|Outro(?:\\s*DJ\\s*#?\\d+)?)\\)\\s*$", RegexOption.IGNORE_CASE)
    private val remasterBanRegex = Regex("remaster|deluxe|expanded|anniversary|reissue|greatest hits|best of|anthology|edition|live", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("^(\\d{4})")

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
                val metaMap = HashMap<Int, Gen2TrackMeta>()
                for ((num, pair) in curTracks) {
                    val rawT = pair.first
                    val cleanT = suffixRegex.replace(rawT, "").trim()
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
                        u.contains("FOG") || u.contains("RAIN") || u.contains("SUN") || u.contains("STORM") || u.contains("WEATHER") -> st.djWeatherTracks.add(num)
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
                    val key = tr.cleanTitle
                    songArtists[key] = tr.artist
                    val bucket = songBuckets.getOrPut(key) { Triple(mutableListOf(), mutableListOf(), mutableListOf()) }
                    when {
                        u.contains("(INTRO DJ") -> bucket.first.add(num)
                        u.contains("(INTRO)") -> cleanIntros[key] = num
                        u.contains("(OUTRO DJ") -> bucket.third.add(num)
                        u.contains("(OUTRO)") -> cleanOutros[key] = num
                        else -> bucket.second.add(num)
                    }
                }
            }
        }

        for ((title, triple) in songBuckets) {
            val mid = triple.second.firstOrNull() ?: continue
            st.songGroups.add(
                Gen2SongGroup(
                    cleanTitle = title,
                    artist = songArtists[title] ?: st.defaultArtist,
                    midTrack = mid,
                    cleanIntroTrack = cleanIntros[title],
                    cleanOutroTrack = cleanOutros[title],
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

    private fun cacheKey(artist: String, songTitle: String): String {
        return "${artist.lowercase()}_${songTitle.lowercase()}".replace(Regex("[^a-z0-9_]+"), "")
    }

    fun getCachedAlbumArt(context: Context, artist: String, songTitle: String): Bitmap? {
        val dir = File(context.filesDir, "era_album_art")
        val f = File(dir, "${cacheKey(artist, songTitle)}.jpg")
        if (f.exists() && f.length() > 0) {
            return BitmapFactory.decodeFile(f.absolutePath)
        }
        return null
    }

    fun getCachedAlbumName(context: Context, artist: String, songTitle: String): String? {
        val key = cacheKey(artist, songTitle)
        albumNameCache[key]?.let { return it }
        val f = File(File(context.filesDir, "era_album_art"), "$key.txt")
        if (f.exists()) {
            val txt = f.readText().trim()
            if (txt.isNotBlank()) {
                albumNameCache[key] = txt
                return txt
            }
        }
        return null
    }

    fun fetchEraAccurateArtAsync(context: Context, artist: String, songTitle: String,
        maxYear: Int = 2004, onDone: (() -> Unit)? = null) {
        val key = cacheKey(artist, songTitle)
        val dir = File(context.filesDir, "era_album_art").apply { mkdirs() }
        val imgFile = File(dir, "$key.jpg")
        if (imgFile.exists() && imgFile.length() > 0) return

        bgExecutor.submit {
            try {
                val success = fetchFromItunesEraFiltered(artist, songTitle, dir, key) ||
                              fetchFromMusicBrainzEraFiltered(artist, songTitle, dir, key)
                if (success) onDone?.invoke()
            } catch (_: Exception) {}
        }
    }

    fun fetchEraAccurateArtSync(context: Context, artist: String, songTitle: String, maxYear: Int = 2004): Boolean {
        val key = cacheKey(artist, songTitle)
        val dir = File(context.filesDir, "era_album_art").apply { mkdirs() }
        val imgFile = File(dir, "$key.jpg")
        if (imgFile.exists() && imgFile.length() > 0) return true
        return fetchFromItunesEraFiltered(artist, songTitle, dir, key) ||
               fetchFromMusicBrainzEraFiltered(artist, songTitle, dir, key)
    }

    private fun fetchFromItunesEraFiltered(artist: String, songTitle: String, dir: File, key: String, maxYear: Int = 2004): Boolean {
        try {
            val cleanSong = songTitle.replace(Regex("\\(.*?\\)"), "").trim()
            val q = URLEncoder.encode("$artist $cleanSong", "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=15")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader().readText()
            val results = JSONObject(body).optJSONArray("results") ?: return false
            if (results.length() == 0) return false

            var bestItem: JSONObject? = null
            var bestScore = -9999

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val colName = item.optString("collectionName", "")
                val relDate = item.optString("releaseDate", "")
                val year = yearRegex.find(relDate)?.groupValues?.get(1)?.toIntOrNull() ?: 9999

                var score = 0
                if (year <= 1993) score += 50
                else if (year <= 2004) score += 30
                else score -= 40

                if (!remasterBanRegex.containsMatchIn(colName)) score += 35 else score -= 35

                if (score > bestScore) {
                    bestScore = score
                    bestItem = item
                }
            }

            val chosen = bestItem ?: return false
            val rawCol = chosen.optString("collectionName", "")
                .replace(Regex("\\s*\\([^)]*(?:Remaster|Deluxe|Expanded|Edition|Anniversary|Reissue)[^)]*\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*-\\s*(?:Single|EP)\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
            val artUrl = chosen.optString("artworkUrl100", "").replace("100x100bb.jpg", "600x600bb.jpg")
            if (artUrl.isBlank()) return false

            val imgBytes = URL(artUrl).openStream().use { it.readBytes() }
            if (imgBytes.isNotEmpty()) {
                FileOutputStream(File(dir, "$key.jpg")).use { it.write(imgBytes) }
                if (rawCol.isNotBlank()) {
                    File(dir, "$key.txt").writeText(rawCol)
                    albumNameCache[key] = rawCol
                }
                return true
            }
        } catch (e: Exception) {
            GTRadioLogger.log("iTunes art fetch error for $artist - $songTitle", e)
        }
        return false
    }

    private fun fetchFromMusicBrainzEraFiltered(artist: String, songTitle: String, dir: File, key: String, maxYear: Int = 2004): Boolean {
        try {
            val cleanSong = songTitle.replace(Regex("\\(.*?\\)"), "").trim()
            val q = URLEncoder.encode("recording:\"$cleanSong\" AND artist:\"$artist\" AND date:[1950 TO 2004]", "UTF-8")
            val url = URL("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=5")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "GTRadioAndroid/1.2 (https://github.com/BrainDead99/GTRadio)")
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode != 200) return false
            val recordings = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("recordings") ?: return false
            for (i in 0 until recordings.length()) {
                val rec = recordings.getJSONObject(i)
                val releases = rec.optJSONArray("releases") ?: continue
                for (j in 0 until releases.length()) {
                    val rel = releases.getJSONObject(j)
                    val title = rel.optString("title", "")
                    if (remasterBanRegex.containsMatchIn(title)) continue
                    val mbid = rel.optString("id", "")
                    if (mbid.isBlank()) continue
                    try {
                        val artConn = (URL("https://coverartarchive.org/release/$mbid/front-500").openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 5000
                            readTimeout = 5000
                        }
                        if (artConn.responseCode == 200) {
                            val bytes = artConn.inputStream.use { it.readBytes() }
                            if (bytes.isNotEmpty()) {
                                FileOutputStream(File(dir, "$key.jpg")).use { it.write(bytes) }
                                if (title.isNotBlank()) {
                                    File(dir, "$key.txt").writeText(title)
                                    albumNameCache[key] = title
                                }
                                return true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return false
    }
}
