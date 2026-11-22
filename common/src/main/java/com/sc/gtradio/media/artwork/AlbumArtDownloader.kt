package com.sc.gtradio.media.artwork
import com.sc.gtradio.media.gen1.Gen1MetadataRepository
import com.sc.gtradio.media.gen2.Gen2MetadataRepository
import com.sc.gtradio.media.rpf7.Rpf7MetadataRepository


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
import java.util.concurrent.atomic.AtomicBoolean

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

    private val bulkScanCancelled = AtomicBoolean(false)
    private val bulkScanRunning = AtomicBoolean(false)

    fun isBulkScanRunning(): Boolean = bulkScanRunning.get()

    fun cancelBulkScan() {
        bulkScanCancelled.set(true)
    }

    fun setBulkScanRunning(running: Boolean) {
        bulkScanRunning.set(running)
        if (running) {
            bulkScanCancelled.set(false)
        }
    }

    fun isBulkScanCancelled(): Boolean = bulkScanCancelled.get()

    private val yearRegex = Regex("^(\\d{4})")
    private val hardPenaltyRegex = Regex(
        "\\b(greatest hits|best of|essential|anthology|collection|gold|ultimate|box set|various artists|soundtrack|ost|karaoke|tribute|ambient translations|lullaby|relaxing|healing|piano version|instrumental|hits of the|now that's what i call|classic rock|grand theft auto|included in the game|number 1|number one|singles|live at|live in|reunion|from the beginning)\\b|gold:",
        RegexOption.IGNORE_CASE
    )
    private val tieBreakerPenaltyRegex = Regex(
        "\\b(remaster|deluxe|expanded|anniversary|reissue|bonus track|edition)\\b",
        RegexOption.IGNORE_CASE
    )
    private val CACHE_KEY_REGEX = Regex("[^\\p{L}\\p{N}_]+")
    private val DIACRITICS_REGEX = Regex("\\p{M}+")
    private val PAREN_REMIX_REGEX = Regex("\\s*\\([^)]*(?:remix|radio edit|clean|explicit|version|mix)[^)]*\\)", RegexOption.IGNORE_CASE)
    private val BRACKET_REMIX_REGEX = Regex("\\[[^]]*(?:remix|radio edit|clean|explicit|version|mix)[^]]*\\]", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL_REGEX = Regex("\\b(featuring|feat\\.?|ft\\.?)\\b.*", RegexOption.IGNORE_CASE)
    private val EDIT_WORDS_REGEX = Regex("\\b(radio edit|clean|explicit)\\b", RegexOption.IGNORE_CASE)
    private val AND_REGEX = Regex("\\b(and|&)\\b", RegexOption.IGNORE_CASE)
    private val NON_ALPHANUMERIC_SPACE_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val ARTIST_SPLIT_REGEX = Regex("\\b(featuring|feat\\.?|ft\\.?|with|meets|vs\\.?|and)\\b|[&/,+,;]", RegexOption.IGNORE_CASE)
    private val PAREN_EDITION_REGEX = Regex("\\s*\\([^)]*(?:Remaster|Deluxe|Expanded|Edition|Anniversary|Reissue|Bonus Track)[^)]*\\)", RegexOption.IGNORE_CASE)
    private val BRACKET_EDITION_REGEX = Regex("\\s*\\[[^]]*(?:Remaster|Deluxe|Expanded|Edition|Anniversary|Reissue|Bonus Track)[^]]*\\]", RegexOption.IGNORE_CASE)
    private val DASH_DELUXE_REGEX = Regex("\\s*-\\s*(?:Deluxe Edition|Deluxe Version|Remastered|Remaster)\\s*$", RegexOption.IGNORE_CASE)
    private val DASH_SINGLE_EP_REGEX = Regex("\\s*-\\s*(?:Single|EP)\\s*$", RegexOption.IGNORE_CASE)
    private val PARENS_ANY_REGEX = Regex("\\(.*?\\)")
    private val ALT_CACHE_KEY_REGEX = Regex("[^\\p{L}\\p{N}_]")
    private val SPAM_ARTIST_WORDS = setOf(
        "relaxing", "healing", "lullaby", "karaoke", "tribute", "soundtrack", "ambient"
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
        return CACHE_KEY_REGEX.replace("${artist.lowercase(Locale.ROOT)}_${songTitle.lowercase(Locale.ROOT)}", "")
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
        return DIACRITICS_REGEX.replace(nfd, "")
    }

    fun normalizeText(input: String): String {
        var s = stripDiacritics(input).lowercase(Locale.ROOT)
        s = PAREN_REMIX_REGEX.replace(s, " ")
        s = BRACKET_REMIX_REGEX.replace(s, " ")
        s = FEAT_TAIL_REGEX.replace(s, " ")
        s = EDIT_WORDS_REGEX.replace(s, " ")
        s = AND_REGEX.replace(s, " ")
        s = NON_ALPHANUMERIC_SPACE_REGEX.replace(s, " ")
        s = s.trim()
        if (s.startsWith("the ")) {
            s = s.substring(4).trim()
        }
        return WHITESPACE_REGEX.replace(s, " ").trim()
    }

    fun extractPrimaryArtist(artist: String): String {
        val a = stripDiacritics(artist).lowercase(Locale.ROOT)
        val parts = ARTIST_SPLIT_REGEX.split(a)
        val first = if (parts.isNotEmpty()) parts[0] else a
        var res = NON_ALPHANUMERIC_SPACE_REGEX.replace(first, " ").trim()
        if (res.startsWith("the ")) {
            res = res.substring(4).trim()
        }
        return WHITESPACE_REGEX.replace(res, " ").trim()
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

        // Reject candidates whose artistName contains generic spam words unless present in query artist
        val spamInCand = SPAM_ARTIST_WORDS.any { spam ->
            normCand.contains(spam) && !normTarget.contains(spam)
        }
        if (spamInCand) {
            return Pair(false, -90)
        }

        // Token overlap >= 60% of primary artist tokens matched as whole words
        val targetTokens = primTarget.split(" ").map { it.trim() }.filter { it.length >= 2 }
        val candTokens = normCand.split(" ").map { it.trim() }.filter { it.length >= 2 }
        if (targetTokens.isNotEmpty() && candTokens.isNotEmpty()) {
            val candSet = candTokens.toSet()
            val overlap = targetTokens.count { candSet.contains(it) }
            val ratio = overlap.toDouble() / targetTokens.size
            val candRatio = overlap.toDouble() / candTokens.size

            if (ratio >= 0.6) {
                if (targetTokens.size == 1 && candTokens.size > 1) {
                    return Pair(false, 0)
                }
                if (candTokens.size == 1 && targetTokens.size > 1) {
                    return Pair(false, 0)
                }
                val score = if (ratio >= 0.8 && candRatio >= 0.8) 45 else 35
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
        return DASH_SINGLE_EP_REGEX.replace(
            DASH_DELUXE_REGEX.replace(
                BRACKET_EDITION_REGEX.replace(
                    PAREN_EDITION_REGEX.replace(rawCol, ""),
                    ""
                ),
                ""
            ),
            ""
        ).trim()
    }

    private fun cleanTrackTitleForQuery(songTitle: String): String {
        val noParens = PARENS_ANY_REGEX.replace(songTitle, "").trim()
        return noParens.ifBlank { songTitle.trim() }
    }

    fun calculateAlbumCandidateScore(
        artistScore: Int,
        albumMatches: Boolean,
        albumMatchScore: Int,
        year: Int,
        hint: AlbumHint,
        collectionName: String
    ): Int {
        var score = artistScore
        if (albumMatches) {
            score += 60 + albumMatchScore
        }

        // Nearest-Year Tie-Breaking
        if (hint.year in 1900..2100) {
            if (year in 1900..2100) {
                val diff = Math.abs(year - hint.year)
                val yearBonus = (30 - (diff / 2)).coerceIn(0, 30)
                score += yearBonus
            } else {
                score += 15
            }
        }

        val isOstCompExempt = (hint.status == "CANONICAL_OST_COMP" && albumMatches)
        if (hardPenaltyRegex.containsMatchIn(collectionName)) {
            if (!isOstCompExempt) {
                score -= 80
            }
        } else if (tieBreakerPenaltyRegex.containsMatchIn(collectionName)) {
            score -= 15
        }

        return score
    }

    fun calculateCandidateScore(
        artistScore: Int,
        trackScore: Int,
        year: Int,
        preferredYear: Int,
        maxYear: Int,
        collectionName: String,
        hint: AlbumHint? = null
    ): Int {
        var score = artistScore + trackScore

        if (hint != null && hint.year in 1900..2100) {
            if (year in 1900..2100) {
                val diff = Math.abs(year - hint.year)
                val yearBonus = (30 - (diff / 2)).coerceIn(0, 30)
                score += yearBonus
            } else {
                score += 15
            }
        } else {
            if (year in 1900..preferredYear) {
                score += 35
            } else if (year in 1900..maxYear) {
                score += 20
            } else if (year > maxYear && year < 9000) {
                score -= 15
            }
        }

        val cleanExp = hint?.let { cleanCollectionName(it.officialAlbum) } ?: ""
        val cleanCand = cleanCollectionName(collectionName)
        val matchesExpectedAlbum = cleanExp.isNotBlank() && isTrackMatch(cleanExp, cleanCand).first
        val isOstCompExempt = hint?.status == "CANONICAL_OST_COMP" && matchesExpectedAlbum

        if (hardPenaltyRegex.containsMatchIn(collectionName)) {
            if (!isOstCompExempt) {
                score -= 80
            }
        } else if (tieBreakerPenaltyRegex.containsMatchIn(collectionName)) {
            score -= 15
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
                val cleanAlbum = cleanCollectionName(albumName)
                txtFile.writeText(cleanAlbum)
                albumNameCache[key] = cleanAlbum
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
        return ALT_CACHE_KEY_REGEX.replace(raw, "_").take(80)
    }

    fun getMemoryCachedAlbumArt(artist: String, songTitle: String): Bitmap? {
        val key = cacheKey(artist, songTitle)
        synchronized(memoryArtCache) {
            return memoryArtCache.get(key)
        }
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
        // Clean offline fallback if no $key.txt exists yet
        val hint = AlbumHintRepository.findHint(context, artist, songTitle)
        if (hint != null && hint.officialAlbum.isNotBlank()) {
            albumNameCache[key] = hint.officialAlbum
            return hint.officialAlbum
        }
        return null
    }

    fun getCachedAlbumTitle(context: Context, artist: String, songTitle: String): String? {
        return getCachedAlbumName(context, artist, songTitle)
    }

    private fun queryItunesAlbum(
        hint: AlbumHint,
        originalArtist: String
    ): CandidateResult? {
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastItunesTime
        if (diff < 350) {
            SystemClock.sleep(350 - diff)
        }
        lastItunesTime = SystemClock.elapsedRealtime()

        val q = URLEncoder.encode("${hint.effectiveArtist} ${hint.effectiveAlbum}", "UTF-8")
        val urlStr = "https://itunes.apple.com/search?term=$q&media=music&entity=album&limit=10"

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
            GTRadioLogger.logVerbose("ART") { "iTunes HTTP $responseCode for album '${hint.effectiveArtist} - ${hint.effectiveAlbum}'" }
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
            val candAlbum = item.optString("collectionName", "")
            val relDate = item.optString("releaseDate", "")
            val year = yearRegex.find(relDate)?.groupValues?.get(1)?.toIntOrNull() ?: 9999

            val (m1, s1) = isArtistMatch(originalArtist, candArtist)
            val (m2, s2) = isArtistMatch(hint.effectiveArtist, candArtist)
            if (!m1 && !m2) continue
            val artistScore = maxOf(s1, s2)

            val cleanExp1 = cleanCollectionName(hint.officialAlbum)
            val cleanExp2 = cleanCollectionName(hint.effectiveAlbum)
            val cleanCand = cleanCollectionName(candAlbum)
            val (albMatch1, albScore1) = isTrackMatch(cleanExp1, cleanCand)
            val (albMatch2, albScore2) = isTrackMatch(cleanExp2, cleanCand)
            val albMatches = albMatch1 || albMatch2
            val albScore = maxOf(albScore1, albScore2)
            if (!albMatches) continue

            val score = calculateAlbumCandidateScore(
                artistScore = artistScore,
                albumMatches = albMatches,
                albumMatchScore = albScore,
                year = year,
                hint = hint,
                collectionName = candAlbum
            )

            if (score > bestScore) {
                val art100 = item.optString("artworkUrl100", "")
                if (art100.isNotBlank()) {
                    val artUrl = art100.replace("100x100bb.jpg", "300x300bb.jpg")
                        .replace("100x100bb", "300x300bb")
                    bestScore = score
                    bestResult = CandidateResult(
                        source = "iTunes (Album)",
                        albumName = hint.officialAlbum,
                        year = year,
                        score = score,
                        artUrl = artUrl
                    )
                }
            }
        }

        return if (bestScore >= 40) bestResult else null
    }

    private fun queryDeezerAlbum(
        hint: AlbumHint,
        originalArtist: String
    ): CandidateResult? {
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastDeezerTime
        if (diff < 350) {
            SystemClock.sleep(350 - diff)
        }
        lastDeezerTime = SystemClock.elapsedRealtime()

        val q = URLEncoder.encode("${hint.effectiveArtist} ${hint.effectiveAlbum}", "UTF-8")
        val urlStr = "https://api.deezer.com/search/album?q=$q&limit=10"

        val body = try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "GTRadio/1.1 (Android)")
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode != 200) {
                GTRadioLogger.logVerbose("ART") { "Deezer HTTP ${conn.responseCode} for album '${hint.effectiveArtist} - ${hint.effectiveAlbum}'" }
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
            val candAlbum = item.optString("title", "")
            val artistObj = item.optJSONObject("artist")
            val candArtist = artistObj?.optString("name", "") ?: ""

            val (m1, s1) = isArtistMatch(originalArtist, candArtist)
            val (m2, s2) = isArtistMatch(hint.effectiveArtist, candArtist)
            if (!m1 && !m2) continue
            val artistScore = maxOf(s1, s2)

            val cleanExp1 = cleanCollectionName(hint.officialAlbum)
            val cleanExp2 = cleanCollectionName(hint.effectiveAlbum)
            val cleanCand = cleanCollectionName(candAlbum)
            val (albMatch1, albScore1) = isTrackMatch(cleanExp1, cleanCand)
            val (albMatch2, albScore2) = isTrackMatch(cleanExp2, cleanCand)
            val albMatches = albMatch1 || albMatch2
            val albScore = maxOf(albScore1, albScore2)
            if (!albMatches) continue

            val year = 0
            val score = calculateAlbumCandidateScore(
                artistScore = artistScore,
                albumMatches = albMatches,
                albumMatchScore = albScore,
                year = year,
                hint = hint,
                collectionName = candAlbum
            )

            if (score > bestScore) {
                val artUrl = item.optString("cover_medium", "").ifBlank {
                    item.optString("cover_big", "")
                }
                if (artUrl.isNotBlank()) {
                    bestScore = score
                    bestResult = CandidateResult(
                        source = "Deezer (Album)",
                        albumName = hint.officialAlbum,
                        year = year,
                        score = score,
                        artUrl = artUrl
                    )
                }
            }
        }

        return if (bestScore >= 40) bestResult else null
    }

    private fun queryItunes(
        artist: String,
        cleanSong: String,
        preferredYear: Int,
        maxYear: Int,
        hint: AlbumHint? = null
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

            val (m1, s1) = isArtistMatch(artist, candArtist)
            val (m2, s2) = if (hint != null) isArtistMatch(hint.effectiveArtist, candArtist) else Pair(false, 0)
            if (!m1 && !m2) continue
            val artistScore = maxOf(s1, s2)

            val (trackMatches, trackScore) = isTrackMatch(cleanSong, candTrack)
            if (!trackMatches) continue

            val score = calculateCandidateScore(
                artistScore = artistScore,
                trackScore = trackScore,
                year = year,
                preferredYear = preferredYear,
                maxYear = maxYear,
                collectionName = collectionName,
                hint = hint
            )

            if (score > bestScore) {
                val art100 = item.optString("artworkUrl100", "")
                if (art100.isNotBlank()) {
                    val artUrl = art100.replace("100x100bb.jpg", "300x300bb.jpg")
                        .replace("100x100bb", "300x300bb")
                    bestScore = score
                    bestResult = CandidateResult(
                        source = "iTunes",
                        albumName = hint?.officialAlbum?.ifBlank { null } ?: cleanCollectionName(collectionName),
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
        maxYear: Int,
        hint: AlbumHint? = null
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

            val (m1, s1) = isArtistMatch(artist, candArtist)
            val (m2, s2) = if (hint != null) isArtistMatch(hint.effectiveArtist, candArtist) else Pair(false, 0)
            if (!m1 && !m2) continue
            val artistScore = maxOf(s1, s2)

            val (trackMatches, trackScore) = isTrackMatch(cleanSong, candTrack)
            if (!trackMatches) continue

            val year = 0
            val score = calculateCandidateScore(
                artistScore = artistScore,
                trackScore = trackScore,
                year = year,
                preferredYear = preferredYear,
                maxYear = maxYear,
                collectionName = collectionName,
                hint = hint
            )

            if (score > bestScore) {
                val artUrl = albumObj?.optString("cover_medium", "")?.ifBlank {
                    albumObj.optString("cover_big", "")
                } ?: ""
                if (artUrl.isNotBlank()) {
                    bestScore = score
                    bestResult = CandidateResult(
                        source = "Deezer",
                        albumName = hint?.officialAlbum?.ifBlank { null } ?: cleanCollectionName(collectionName),
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
        maxYear: Int,
        hint: AlbumHint? = null
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

            val (m1, s1) = isArtistMatch(artist, recArtist.ifBlank { artist })
            val (m2, s2) = if (hint != null) isArtistMatch(hint.effectiveArtist, recArtist.ifBlank { artist }) else Pair(false, 0)
            if (!m1 && !m2) continue
            val artistScore = maxOf(s1, s2)

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
                    collectionName = relTitle,
                    hint = hint
                )

                if (score > bestScore) {
                    val caaUrl = "https://coverartarchive.org/release/$mbid/front-250"
                    bestScore = score
                    bestResult = CandidateResult(
                        source = "MusicBrainz",
                        albumName = hint?.officialAlbum?.ifBlank { null } ?: cleanCollectionName(relTitle),
                        year = year,
                        score = score,
                        artUrl = caaUrl
                    )
                }
            }
        }

        return if (bestScore >= 40) bestResult else null
    }

    private fun downloadAndSave(
        candidate: CandidateResult,
        dir: File,
        key: String,
        overrideAlbumName: String? = null
    ): Boolean {
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

            val albumNameToSave = if (!overrideAlbumName.isNullOrBlank()) overrideAlbumName else candidate.albumName
            saveCompressedCover(dir, key, bytes, albumNameToSave)
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

        val hint = AlbumHintRepository.findHint(context, artist, songTitle)
        if (hint?.status == "NO_ART") {
            GTRadioLogger.log("[ART] Instant skip (NO_ART): $artist - $songTitle")
            return false
        }

        val key = cacheKey(artist, songTitle)
        val dir = File(context.filesDir, "era_album_art").apply { mkdirs() }
        val imgFile = File(dir, "$key.jpg")
        if (imgFile.exists() && imgFile.length() > 0) {
            if (hint != null && hint.officialAlbum.isNotBlank()) {
                val txtFile = File(dir, "$key.txt")
                if (!txtFile.exists() || txtFile.length() == 0L) {
                    txtFile.writeText(hint.officialAlbum)
                    albumNameCache[key] = hint.officialAlbum
                }
            }
            return true
        }

        val overrideAlbumName = hint?.officialAlbum?.ifBlank { null }
        val searchArtist = hint?.effectiveArtist?.ifBlank { artist } ?: artist

        // ==========================================
        // Pass 1: Album-Guided Search (when hint != null)
        // ==========================================
        if (hint != null) {
            val itunesAlbumCand = queryItunesAlbum(hint, artist)
            if (itunesAlbumCand != null && downloadAndSave(itunesAlbumCand, dir, key, overrideAlbumName)) {
                GTRadioLogger.logVerbose("ART") {
                    "Match [Pass 1 Album - iTunes]: '$artist - $songTitle' -> '${overrideAlbumName ?: itunesAlbumCand.albumName}' (year=${itunesAlbumCand.year}, score=${itunesAlbumCand.score})"
                }
                return true
            }

            val deezerAlbumCand = queryDeezerAlbum(hint, artist)
            if (deezerAlbumCand != null && downloadAndSave(deezerAlbumCand, dir, key, overrideAlbumName)) {
                GTRadioLogger.logVerbose("ART") {
                    "Match [Pass 1 Album - Deezer]: '$artist - $songTitle' -> '${overrideAlbumName ?: deezerAlbumCand.albumName}' (score=${deezerAlbumCand.score})"
                }
                return true
            }
        }

        // ==========================================
        // Pass 2: Song Fallback (existing search chain)
        // ==========================================
        val cleanSong = cleanTrackTitleForQuery(songTitle)

        // 1. iTunes Song search
        val itunesCand = queryItunes(searchArtist, cleanSong, preferredYear, maxYear, hint)
        if (itunesCand != null && downloadAndSave(itunesCand, dir, key, overrideAlbumName)) {
            GTRadioLogger.logVerbose("ART") {
                "Match [Pass 2 Song - iTunes]: '$artist - $cleanSong' -> '${overrideAlbumName ?: itunesCand.albumName}' (year=${itunesCand.year}, score=${itunesCand.score})"
            }
            return true
        }

        // 2. Deezer Song search
        val deezerCand = queryDeezer(searchArtist, cleanSong, preferredYear, maxYear, hint)
        if (deezerCand != null && downloadAndSave(deezerCand, dir, key, overrideAlbumName)) {
            GTRadioLogger.logVerbose("ART") {
                "Match [Pass 2 Song - Deezer]: '$artist - $cleanSong' -> '${overrideAlbumName ?: deezerCand.albumName}' (score=${deezerCand.score})"
            }
            return true
        }

        // 3. MusicBrainz fallback
        val mbCand = queryMusicBrainz(searchArtist, cleanSong, preferredYear, maxYear, hint)
        if (mbCand != null && downloadAndSave(mbCand, dir, key, overrideAlbumName)) {
            GTRadioLogger.logVerbose("ART") {
                "Match [Pass 2 Song - MusicBrainz]: '$artist - $cleanSong' -> '${overrideAlbumName ?: mbCand.albumName}' (year=${mbCand.year}, score=${mbCand.score})"
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
        cancelBulkScan()
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
