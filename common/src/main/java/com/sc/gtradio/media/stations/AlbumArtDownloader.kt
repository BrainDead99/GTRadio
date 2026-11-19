package com.sc.gtradio.media.stations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import com.sc.gtradio.common.GTRadioLogger
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class CandidateResult(
    val source: String,
    val albumName: String,
    val year: Int,
    val score: Int,
    val artUrl: String
)

object AlbumArtDownloader {

    @Volatile
    var appContext: Context? = null

    private val albumNameCache = ConcurrentHashMap<String, String>()
    private val memoryArtCache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val yearRegex = Regex("^(\\d{4})")
    private val compilationPenaltyRegex = Regex(
        "\\b(greatest hits|best of|essential|anthology|collection|ultimate|remaster|deluxe|expanded|anniversary|reissue|remix|mixes|karaoke|tribute|grand theft auto|included in the game|soundtrack|ost|various artists|now that's what i call|number 1|number one|hits of the|classic rock|reunion|box set|complete|singles|live at|live in|from the beginning)\\b|gold:",
        RegexOption.IGNORE_CASE
    )

    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "in", "to", "and", "or", "for", "on", "with",
        "by", "at", "from", "is", "it", "that", "this", "my", "your", "me", "you"
    )

    private val SKIP_PHRASES = listOf(
        "continuous mix",
        "dj mix",
        "megamix",
        "talk show",
        "news",
        "(part ",
        "episode "
    )

    @Volatile
    private var lastItunesTime = 0L

    @Volatile
    private var lastDeezerTime = 0L

    @Volatile
    private var lastMusicBrainzTime = 0L

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun cacheKey(artist: String, songTitle: String): String {
        return "${artist.lowercase(Locale.ROOT)}_${songTitle.lowercase(Locale.ROOT)}".replace(Regex("[^a-z0-9_]+"), "")
    }

    fun shouldSkip(artist: String, songTitle: String): Boolean {
        if (artist.isBlank() || songTitle.isBlank()) return true
        val lowerTitle = songTitle.lowercase(Locale.ROOT)
        val lowerArtist = artist.lowercase(Locale.ROOT)
        for (phrase in SKIP_PHRASES) {
            if (lowerTitle.contains(phrase) || lowerArtist.contains(phrase)) return true
        }
        return false
    }

    private fun stripDiacritics(str: String): String {
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        return Regex("\\p{M}+").replace(nfd, "")
    }

    fun normalizeText(input: String): String {
        var s = stripDiacritics(input).lowercase(Locale.ROOT)
        s = s.replace(Regex("\\s*\\([^)]*(?:remix|radio edit|clean|explicit|version|mix)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("\\[[^]]*(?:remix|radio edit|clean|explicit|version|mix)[^]]*\\]", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("\\b(featuring|feat\\.?|ft\\.?)\\b.*", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("\\b(radio edit|clean|explicit)\\b", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("\\b(and|&)\\b", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("[^a-z0-9\\s]"), " ")
        s = s.trim()
        if (s.startsWith("the ")) {
            s = s.substring(4).trim()
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun extractPrimaryArtist(artist: String): String {
        val a = stripDiacritics(artist).lowercase(Locale.ROOT)
        val splitRegex = Regex("\\b(featuring|feat\\.?|ft\\.?|with|meets|vs\\.?|and)\\b|[&/,+,;]", RegexOption.IGNORE_CASE)
        val parts = splitRegex.split(a)
        val first = if (parts.isNotEmpty()) parts[0] else a
        var res = first.replace(Regex("[^a-z0-9\\s]"), " ").trim()
        if (res.startsWith("the ")) {
            res = res.substring(4).trim()
        }
        return res.replace(Regex("\\s+"), " ").trim()
    }

    fun isArtistMatch(targetArtist: String, candidateArtist: String): Pair<Boolean, Int> {
        val normTarget = normalizeText(targetArtist)
        val normCand = normalizeText(candidateArtist)

        val alphaTarget = normTarget.replace(" ", "")
        val alphaCand = normCand.replace(" ", "")
        if (alphaTarget.isNotEmpty() && alphaTarget == alphaCand) {
            return Pair(true, 50)
        }

        val primTarget = extractPrimaryArtist(targetArtist)
        val primCand = extractPrimaryArtist(candidateArtist)
        val alphaPrimTarget = primTarget.replace(" ", "")
        val alphaPrimCand = primCand.replace(" ", "")

        if (alphaPrimTarget.isNotEmpty() && alphaPrimTarget == alphaPrimCand) {
            return Pair(true, 50)
        }

        if (normTarget == normCand || (primTarget.isNotEmpty() && primTarget == primCand)) {
            return Pair(true, 50)
        }

        // Hard reject Various Artists unless specifically intended
        if (normCand.contains("various artists") || primCand == "various artists" || alphaCand.contains("variousartists")) {
            return Pair(false, -90)
        }

        // Substring containment of primary artist >= 3 chars
        if (primTarget.length >= 3 && (normCand.contains(primTarget) || normTarget.contains(primCand) || alphaCand.contains(alphaPrimTarget))) {
            return Pair(true, 40)
        }

        // Token overlap >= 50%
        val targetTokens = primTarget.split(" ").filter { it.length >= 2 }
        val candTokens = normCand.split(" ").filter { it.length >= 2 }
        if (targetTokens.isNotEmpty() && candTokens.isNotEmpty()) {
            val overlap = targetTokens.count { candTokens.contains(it) }
            val ratio = overlap.toDouble() / targetTokens.size
            if (ratio >= 0.5) {
                val score = if (ratio >= 0.8) 45 else 35
                return Pair(true, score)
            }
        }

        return Pair(false, 0)
    }

    fun isTrackMatch(targetTitle: String, candidateTitle: String): Pair<Boolean, Int> {
        val normTarget = normalizeText(targetTitle)
        val normCand = normalizeText(candidateTitle)

        val alphaTarget = normTarget.replace(" ", "")
        val alphaCand = normCand.replace(" ", "")

        if (alphaTarget.isNotEmpty() && alphaTarget == alphaCand) {
            return Pair(true, 30)
        }

        if (normTarget == normCand) {
            return Pair(true, 30)
        }

        if (normTarget.length >= 3 && (normCand.contains(normTarget) || normTarget.contains(normCand) || alphaCand.contains(alphaTarget))) {
            return Pair(true, 25)
        }

        val targetWords = normTarget.split(" ").filter { it.length >= 2 && !STOP_WORDS.contains(it) }
        val candWords = normCand.split(" ").filter { it.length >= 2 && !STOP_WORDS.contains(it) }

        if (targetWords.isEmpty()) {
            val match = normTarget.isNotEmpty() && (normCand == normTarget || normCand.contains(normTarget))
            return Pair(match, if (match) 20 else 0)
        }

        if (candWords.isNotEmpty()) {
            val overlap = targetWords.count { candWords.contains(it) }
            val ratio = overlap.toDouble() / targetWords.size
            if (ratio >= 0.5) {
                val score = if (ratio >= 0.8) 25 else 15
                return Pair(true, score)
            }
        }

        return Pair(false, 0)
    }

    fun cleanCollectionName(rawCol: String): String {
        return rawCol
            .replace(Regex("\\s*\\([^)]*(?:Remaster|Deluxe|Expanded|Edition|Anniversary|Reissue|Bonus Track)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[[^]]*(?:Remaster|Deluxe|Expanded|Edition|Anniversary|Reissue|Bonus Track)[^]]*\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*(?:Single|EP)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanTrackTitleForQuery(songTitle: String): String {
        val noParens = songTitle.replace(Regex("\\(.*?\\)"), "").trim()
        return noParens.ifBlank { songTitle.trim() }
    }

    fun calculateCandidateScore(
        artistScore: Int,
        trackScore: Int,
        year: Int,
        preferredYear: Int,
        maxYear: Int,
        collectionName: String
    ): Int {
        var score = artistScore + trackScore

        if (year in 1900..preferredYear) {
            score += 35
        } else if (year in 1900..maxYear) {
            score += 20
        } else if (year > maxYear && year < 9000) {
            score -= 15
        }

        if (compilationPenaltyRegex.containsMatchIn(collectionName)) {
            score -= 75
        } else {
            val isSingleOrEp = collectionName.endsWith(" - Single", ignoreCase = true) ||
                    collectionName.endsWith(" - EP", ignoreCase = true) ||
                    collectionName.contains("(Single)", ignoreCase = true) ||
                    collectionName.contains("(EP)", ignoreCase = true)
            if (!isSingleOrEp) {
                score += 10
            }
        }

        return score
    }

    fun saveCompressedCover(dir: File, key: String, imageBytes: ByteArray, albumName: String): Boolean {
        if (imageBytes.isEmpty()) return false
        val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return false
        return try {
            val width = bmp.width
            val height = bmp.height
            val finalBmp: Bitmap = if (width > 300 || height > 300) {
                val maxDim = maxOf(width, height)
                val scale = 300f / maxDim
                val targetW = (width * scale).toInt().coerceAtLeast(1)
                val targetH = (height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            } else {
                bmp
            }

            val jpgFile = File(dir, "$key.jpg")
            val txtFile = File(dir, "$key.txt")
            FileOutputStream(jpgFile).use { out ->
                finalBmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (albumName.isNotBlank()) {
                txtFile.writeText(albumName)
                albumNameCache[key] = albumName
            }
            synchronized(memoryArtCache) {
                memoryArtCache.put(key, finalBmp)
            }
            if (finalBmp !== bmp) {
                bmp.recycle()
            }
            true
        } catch (e: Exception) {
            GTRadioLogger.log("AlbumArtDownloader: Error compressing cover for $key", e)
            try { bmp.recycle() } catch (_: Exception) {}
            false
        }
    }

    private fun altCacheKey(artist: String, songTitle: String): String {
        val raw = "${artist.lowercase(Locale.ROOT).trim()}_${songTitle.lowercase(Locale.ROOT).trim()}"
        return raw.replace(Regex("[^a-z0-9_]"), "_").take(80)
    }

    fun getCachedAlbumArt(context: Context, artist: String, songTitle: String): Bitmap? {
        val key = cacheKey(artist, songTitle)
        synchronized(memoryArtCache) {
            memoryArtCache.get(key)?.let { return it }
        }
        val dir = File(context.filesDir, "era_album_art")
        var f = File(dir, "$key.jpg")
        if (!f.exists() || f.length() == 0L) {
            val altKey = altCacheKey(artist, songTitle)
            val altF = File(dir, "$altKey.jpg")
            if (altF.exists() && altF.length() > 0L) {
                f = altF
            }
        }
        if (f.exists() && f.length() > 0) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) {
                synchronized(memoryArtCache) {
                    memoryArtCache.put(key, bmp)
                }
                return bmp
            }
        }
        return null
    }

    fun getCachedAlbumName(context: Context, artist: String, songTitle: String): String? {
        val key = cacheKey(artist, songTitle)
        albumNameCache[key]?.let { return it }
        val dir = File(context.filesDir, "era_album_art")
        var f = File(dir, "$key.txt")
        if (!f.exists() || f.length() == 0L) {
            val altKey = altCacheKey(artist, songTitle)
            val altF = File(dir, "$altKey.txt")
            if (altF.exists() && altF.length() > 0L) {
                f = altF
            }
        }
        if (f.exists()) {
            val txt = f.readText().trim()
            if (txt.isNotBlank()) {
                albumNameCache[key] = txt
                return txt
            }
        }
        return null
    }

    private fun queryItunes(
        artist: String,
        cleanSong: String,
        preferredYear: Int,
        maxYear: Int
    ): CandidateResult? {
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastItunesTime
        if (diff < 350) {
            SystemClock.sleep(350 - diff)
        }
        lastItunesTime = SystemClock.elapsedRealtime()

        val q = URLEncoder.encode("$artist $cleanSong", "UTF-8")
        val urlStr = "https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=15"

        var responseCode: Int
        var body: String?

        fun doRequest(): Pair<Int, String?> {
            return try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "GTRadio/1.1 (Android)")
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                val code = conn.responseCode
                val content = if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else null
                conn.disconnect()
                Pair(code, content)
            } catch (_: Exception) {
                Pair(-1, null)
            }
        }

        val first = doRequest()
        responseCode = first.first
        body = first.second

        if (responseCode == 403 || responseCode == 429) {
            GTRadioLogger.logVerbose("ART") { "iTunes rate limited ($responseCode), backing off 1500ms..." }
            SystemClock.sleep(1500)
            val second = doRequest()
            responseCode = second.first
            body = second.second
        }

        if (responseCode != 200 || body.isNullOrBlank()) {
            GTRadioLogger.logVerbose("ART") { "iTunes HTTP $responseCode for '$artist - $cleanSong'" }
            return null
        }

        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        var bestScore = -9999
        var bestResult: CandidateResult? = null

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val candArtist = item.optString("artistName", "")
            val candTrack = item.optString("trackName", "")
            val collectionName = item.optString("collectionName", "")
            val relDate = item.optString("releaseDate", "")
            val year = yearRegex.find(relDate)?.groupValues?.get(1)?.toIntOrNull() ?: 9999

            val (artistMatches, artistScore) = isArtistMatch(artist, candArtist)
            if (!artistMatches) continue

            val (trackMatches, trackScore) = isTrackMatch(cleanSong, candTrack)
            if (!trackMatches) continue

            val score = calculateCandidateScore(
                artistScore = artistScore,
                trackScore = trackScore,
                year = year,
                preferredYear = preferredYear,
                maxYear = maxYear,
                collectionName = collectionName
            )

            if (score > bestScore) {
                val art100 = item.optString("artworkUrl100", "")
                if (art100.isNotBlank()) {
                    val artUrl = art100.replace("100x100bb.jpg", "300x300bb.jpg")
                        .replace("100x100bb", "300x300bb")
                    bestScore = score
                    bestResult = CandidateResult(
                        source = "iTunes",
                        albumName = cleanCollectionName(collectionName),
                        year = year,
                        score = score,
                        artUrl = artUrl
                    )
                }
            }
        }

        return if (bestScore >= 40) bestResult else null
    }

    private fun queryDeezer(
        artist: String,
        cleanSong: String,
        preferredYear: Int,
        maxYear: Int
    ): CandidateResult? {
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastDeezerTime
        if (diff < 350) {
            SystemClock.sleep(350 - diff)
        }
        lastDeezerTime = SystemClock.elapsedRealtime()

        val q = URLEncoder.encode("$artist $cleanSong", "UTF-8")
        val urlStr = "https://api.deezer.com/search?q=$q&limit=10"

        val body = try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "GTRadio/1.1 (Android)")
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode != 200) {
                GTRadioLogger.logVerbose("ART") { "Deezer HTTP ${conn.responseCode} for '$artist - $cleanSong'" }
                conn.disconnect()
                return null
            }
            val res = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            res
        } catch (_: Exception) {
            return null
        }

        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        val data = json.optJSONArray("data") ?: return null
        if (data.length() == 0) return null

        var bestScore = -9999
        var bestResult: CandidateResult? = null

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val candTrack = item.optString("title", "")
            val artistObj = item.optJSONObject("artist")
            val candArtist = artistObj?.optString("name", "") ?: ""
            val albumObj = item.optJSONObject("album")
            val collectionName = albumObj?.optString("title", "") ?: ""

            val (artistMatches, artistScore) = isArtistMatch(artist, candArtist)
            if (!artistMatches) continue

            val (trackMatches, trackScore) = isTrackMatch(cleanSong, candTrack)
            if (!trackMatches) continue

            val year = 0
            val score = calculateCandidateScore(
                artistScore = artistScore,
                trackScore = trackScore,
                year = year,
                preferredYear = preferredYear,
                maxYear = maxYear,
                collectionName = collectionName
            )

            if (score > bestScore) {
                val artUrl = albumObj?.optString("cover_medium", "")?.ifBlank {
                    albumObj.optString("cover_big", "")
                } ?: ""
                if (artUrl.isNotBlank()) {
                    bestScore = score
                    bestResult = CandidateResult(
                        source = "Deezer",
                        albumName = cleanCollectionName(collectionName),
                        year = year,
                        score = score,
                        artUrl = artUrl
                    )
                }
            }
        }

        return if (bestScore >= 40) bestResult else null
    }

    private fun queryMusicBrainz(
        artist: String,
        cleanSong: String,
        preferredYear: Int,
        maxYear: Int
    ): CandidateResult? {
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastMusicBrainzTime
        if (diff < 1000) {
            SystemClock.sleep(1000 - diff)
        }
        lastMusicBrainzTime = SystemClock.elapsedRealtime()

        val q = URLEncoder.encode("recording:\"$cleanSong\" AND artist:\"$artist\" AND date:[1950 TO $maxYear]", "UTF-8")
        val urlStr = "https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=5"

        val body = try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "GTRadio/1.1 (Android)")
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val res = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            res
        } catch (_: Exception) {
            return null
        }

        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        val recordings = json.optJSONArray("recordings") ?: return null
        if (recordings.length() == 0) return null

        var bestScore = -9999
        var bestResult: CandidateResult? = null

        for (i in 0 until recordings.length()) {
            val rec = recordings.optJSONObject(i) ?: continue
            val recTitle = rec.optString("title", "")
            val artistCredit = rec.optJSONArray("artist-credit")
            val recArtist = if (artistCredit != null && artistCredit.length() > 0) {
                artistCredit.optJSONObject(0)?.optString("name", "") ?: ""
            } else ""

            val (artistMatches, artistScore) = isArtistMatch(artist, recArtist.ifBlank { artist })
            if (!artistMatches) continue

            val (trackMatches, trackScore) = isTrackMatch(cleanSong, recTitle)
            if (!trackMatches) continue

            val releases = rec.optJSONArray("releases") ?: continue
            for (j in 0 until releases.length()) {
                val rel = releases.optJSONObject(j) ?: continue
                val relTitle = rel.optString("title", "")
                val relDate = rel.optString("date", "")
                val year = yearRegex.find(relDate)?.groupValues?.get(1)?.toIntOrNull() ?: 9999
                val mbid = rel.optString("id", "")
                if (mbid.isBlank()) continue

                val score = calculateCandidateScore(
                    artistScore = artistScore,
                    trackScore = trackScore,
                    year = year,
                    preferredYear = preferredYear,
                    maxYear = maxYear,
                    collectionName = relTitle
                )

                if (score > bestScore) {
                    val caaUrl = "https://coverartarchive.org/release/$mbid/front-250"
                    bestScore = score
                    bestResult = CandidateResult(
                        source = "MusicBrainz",
                        albumName = cleanCollectionName(relTitle),
                        year = year,
                        score = score,
                        artUrl = caaUrl
                    )
                }
            }
        }

        return if (bestScore >= 40) bestResult else null
    }

    private fun downloadAndSave(candidate: CandidateResult, dir: File, key: String): Boolean {
        return try {
            val conn = (URL(candidate.artUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "GTRadio/1.1 (Android)")
                instanceFollowRedirects = true
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return false
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.isEmpty()) return false

            saveCompressedCover(dir, key, bytes, candidate.albumName)
        } catch (e: Exception) {
            GTRadioLogger.log("AlbumArtDownloader: Download failed from ${candidate.source} for ${candidate.albumName}", e)
            false
        }
    }

    fun fetchEraAccurateArtSync(
        context: Context,
        artist: String,
        songTitle: String,
        preferredYear: Int = 2004,
        maxYear: Int = 2004
    ): Boolean {
        if (appContext == null) appContext = context.applicationContext

        if (shouldSkip(artist, songTitle)) {
            return false
        }

        val key = cacheKey(artist, songTitle)
        val dir = File(context.filesDir, "era_album_art").apply { mkdirs() }
        val imgFile = File(dir, "$key.jpg")
        if (imgFile.exists() && imgFile.length() > 0) {
            return true
        }

        val cleanSong = cleanTrackTitleForQuery(songTitle)

        // 1. iTunes
        val itunesCand = queryItunes(artist, cleanSong, preferredYear, maxYear)
        if (itunesCand != null && downloadAndSave(itunesCand, dir, key)) {
            GTRadioLogger.logVerbose("ART") {
                "Match [iTunes]: '$artist - $cleanSong' -> '${itunesCand.albumName}' (year=${itunesCand.year}, score=${itunesCand.score})"
            }
            return true
        }

        // 2. Deezer fallback
        val deezerCand = queryDeezer(artist, cleanSong, preferredYear, maxYear)
        if (deezerCand != null && downloadAndSave(deezerCand, dir, key)) {
            GTRadioLogger.logVerbose("ART") {
                "Match [Deezer]: '$artist - $cleanSong' -> '${deezerCand.albumName}' (score=${deezerCand.score})"
            }
            return true
        }

        // 3. MusicBrainz fallback
        val mbCand = queryMusicBrainz(artist, cleanSong, preferredYear, maxYear)
        if (mbCand != null && downloadAndSave(mbCand, dir, key)) {
            GTRadioLogger.logVerbose("ART") {
                "Match [MusicBrainz]: '$artist - $cleanSong' -> '${mbCand.albumName}' (year=${mbCand.year}, score=${mbCand.score})"
            }
            return true
        }

        GTRadioLogger.logVerbose("ART") {
            "No match [NONE]: '$artist - $cleanSong' [preferredYear=$preferredYear, maxYear=$maxYear]"
        }
        return false
    }

    fun fetchEraAccurateArtAsync(
        context: Context,
        artist: String,
        songTitle: String,
        preferredYear: Int = 2004,
        maxYear: Int = 2004,
        onDone: (() -> Unit)? = null
    ) {
        if (appContext == null) appContext = context.applicationContext

        val key = cacheKey(artist, songTitle)
        val dir = File(context.filesDir, "era_album_art").apply { mkdirs() }
        val imgFile = File(dir, "$key.jpg")
        if (imgFile.exists() && imgFile.length() > 0) return

        bgExecutor.submit {
            try {
                val success = fetchEraAccurateArtSync(context, artist, songTitle, preferredYear, maxYear)
                if (success) {
                    onDone?.invoke()
                }
            } catch (_: Exception) {}
        }
    }

    fun getAlbumArtCacheStats(context: Context): Pair<Int, Long> {
        val dir = File(context.filesDir, "era_album_art")
        if (!dir.exists() || !dir.isDirectory) return Pair(0, 0L)
        val files = dir.listFiles() ?: return Pair(0, 0L)
        var count = 0
        var bytes = 0L
        for (f in files) {
            if (f.isFile) {
                count++
                bytes += f.length()
            }
        }
        return Pair(count, bytes)
    }

    fun clearAlbumArtCache(context: Context): Pair<Int, Long> {
        val stats = getAlbumArtCacheStats(context)
        val dir = File(context.filesDir, "era_album_art")
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        }
        albumNameCache.clear()
        synchronized(memoryArtCache) {
            memoryArtCache.evictAll()
        }
        Gen2MetadataRepository.clearMemoryCache()
        Rpf7MetadataRepository.clearMemoryCache()
        Gen1MetadataRepository.clearMemoryCache()
        GTRadioLogger.logVerbose("ART") {
            "Album art cache cleared: ${stats.first} files, ${stats.second} bytes deleted"
        }
        return stats
    }
}
