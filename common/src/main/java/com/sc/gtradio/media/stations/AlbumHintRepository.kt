package com.sc.gtradio.media.stations

import android.content.Context
import android.os.SystemClock
import com.sc.gtradio.common.GTRadioLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class AlbumHint(
    val station: String,
    val artist: String,
    val title: String,
    val officialAlbum: String,
    val year: Int,
    val status: String,
    val hintArtist: String,
    val hintAlbum: String
) {
    val effectiveArtist: String
        get() = hintArtist.ifBlank { artist }

    val effectiveAlbum: String
        get() = hintAlbum.ifBlank { officialAlbum }
}

object AlbumHintRepository {

    private val STOP_WORDS_KEY = setOf("the", "a", "an", "in", "of", "on", "at", "to")

    @Volatile
    private var loaded = false

    private val hintMap = ConcurrentHashMap<String, AlbumHint>()
    private val allHints = ArrayList<AlbumHint>()

    private val DIACRITICS_REGEX = Regex("\\p{M}+")
    private val EXS_REGEX = Regex("\\bex['’]?s\\b", RegexOption.IGNORE_CASE)
    private val COKANE_REGEX = Regex("\\bcokane\\b", RegexOption.IGNORE_CASE)
    private val RABBITT_REGEX = Regex("\\brabbitt\\b", RegexOption.IGNORE_CASE)
    private val WILLIE_REGEX = Regex("\\bwillie\\b", RegexOption.IGNORE_CASE)
    private val WRECKX_REGEX = Regex("\\bwreckx\\b", RegexOption.IGNORE_CASE)
    private val FUCK_REGEX = Regex("\\bf\\*\\*k\\b", RegexOption.IGNORE_CASE)
    private val ING_REGEX = Regex("in['’]g?\\b", RegexOption.IGNORE_CASE)
    private val RUNIN_REGEX = Regex("\\brunin\\b", RegexOption.IGNORE_CASE)
    private val RUNNIN_REGEX = Regex("\\brunnin\\b", RegexOption.IGNORE_CASE)
    private val SWINGIN_REGEX = Regex("\\bswingin\\b", RegexOption.IGNORE_CASE)
    private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val PARENS_REGEX = Regex("\\(.*?\\)")
    private val BRACKETS_REGEX = Regex("\\[.*?\\]")
    private val DIGITS_REGEX = Regex("\\b\\d+\\b")
    private val ARTIST_SPLIT_REGEX = Regex("\\b(featuring|feat\\.?|ft\\.?|with|meets|vs\\.?|and)\\b|[&/,+;]", RegexOption.IGNORE_CASE)

    private fun stripDiacritics(str: String): String {
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(nfd, "")
    }

    fun normalizeKey(input: String): String {
        if (input.isBlank()) return ""
        var s = stripDiacritics(input).lowercase(Locale.ROOT)
        s = s.replace("$", "s")
        s = s.replace("&", " and ")
        s = s.replace("+", " and ")
        s = EXS_REGEX.replace(s, "exes")
        s = COKANE_REGEX.replace(s, "cocaine")
        s = RABBITT_REGEX.replace(s, "rabbit")
        s = WILLIE_REGEX.replace(s, "willi")
        s = WRECKX_REGEX.replace(s, "wrecks")
        s = FUCK_REGEX.replace(s, "fuck")
        s = ING_REGEX.replace(s, "ing")
        s = RUNIN_REGEX.replace(s, "running")
        s = RUNNIN_REGEX.replace(s, "running")
        s = SWINGIN_REGEX.replace(s, "swinging")
        s = NON_ALPHANUMERIC_REGEX.replace(s, " ")
        s = WHITESPACE_REGEX.replace(s, " ").trim()
        if (s.startsWith("the ")) {
            s = s.substring(4).trim()
        }
        return s
    }

    fun cleanTitleNoParens(s: String): String {
        return BRACKETS_REGEX.replace(PARENS_REGEX.replace(s, ""), "").trim()
    }

    fun stripStopWords(s: String): String {
        val words = s.split(" ")
        return words.filter { it !in STOP_WORDS_KEY }.joinToString(" ")
    }

    fun stripDigits(s: String): String {
        return WHITESPACE_REGEX.replace(DIGITS_REGEX.replace(s, ""), " ").trim()
    }

    fun extractPrimaryArtist(artist: String): String {
        val a = stripDiacritics(artist).lowercase(Locale.ROOT)
        val parts = ARTIST_SPLIT_REGEX.split(a)
        val first = if (parts.isNotEmpty()) parts[0] else a
        var res = WHITESPACE_REGEX.replace(NON_ALPHANUMERIC_REGEX.replace(first, " "), " ").trim()
        if (res.startsWith("the ")) {
            res = res.substring(4).trim()
        }
        if (res.endsWith(" band")) {
            res = res.substring(0, res.length - 5).trim()
        }
        return res
    }

    private fun registerHintKeys(artist: String, title: String, hint: AlbumHint) {
        val normA = normalizeKey(artist)
        val primA = normalizeKey(extractPrimaryArtist(artist))
        val normT = normalizeKey(title)
        val cleanT = normalizeKey(cleanTitleNoParens(title))
        val tNoStop = stripStopWords(normT)
        val cleanTNoStop = stripStopWords(cleanT)
        val cleanTNoDigits = stripDigits(cleanT)

        val keys = listOf(
            "$normA|$normT",
            "$normA|$cleanT",
            "$primA|$normT",
            "$primA|$cleanT",
            "$normA|$tNoStop",
            "$normA|$cleanTNoStop",
            "$primA|$tNoStop",
            "$primA|$cleanTNoStop",
            "$primA|$cleanTNoDigits",
            "${normA.replace(" ", "")}|${normT.replace(" ", "")}",
            "${primA.replace(" ", "")}|${cleanT.replace(" ", "")}",
            "${normA.replace(" ", "")}|${cleanT.replace(" ", "")}",
            "${primA.replace(" ", "")}|${cleanTNoStop.replace(" ", "")}"
        )
        for (k in keys) {
            if (k.isNotBlank() && !hintMap.containsKey(k)) {
                hintMap[k] = hint
            }
        }
    }

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded && hintMap.isNotEmpty()) return
        val startTimeMs = SystemClock.elapsedRealtime()
        loaded = true

        val assetFiles = try {
            val list = context.assets.list("")
            val filtered = list?.filter { it.startsWith("album_hints_") && it.endsWith(".tsv") }?.sorted()
            if (!filtered.isNullOrEmpty()) filtered else listOf("album_hints_gen2.tsv", "album_hints_rpf3.tsv", "album_hints_rpf7.tsv")
        } catch (_: Exception) {
            listOf("album_hints_gen2.tsv", "album_hints_rpf3.tsv", "album_hints_rpf7.tsv")
        }

        for (filename in assetFiles) {
            try {
                context.assets.open(filename).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim()
                            if (trimmed.isNotBlank() && !trimmed.startsWith("station|")) {
                                val parts = trimmed.split("|")
                                if (parts.size >= 8) {
                                    val hint = AlbumHint(
                                        station = parts[0].trim(),
                                        artist = parts[1].trim(),
                                        title = parts[2].trim(),
                                        officialAlbum = parts[3].trim(),
                                        year = parts[4].trim().toIntOrNull() ?: 0,
                                        status = parts[5].trim(),
                                        hintArtist = parts[6].trim(),
                                        hintAlbum = parts[7].trim()
                                    )
                                    synchronized(allHints) {
                                        allHints.add(hint)
                                    }
                                    registerHintKeys(hint.artist, hint.title, hint)
                                    if (hint.hintArtist.isNotBlank()) {
                                        registerHintKeys(hint.hintArtist, hint.title, hint)
                                    }
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                }
                GTRadioLogger.logVerbose("ART") { "AlbumHintRepository: Loaded hints from $filename" }
            } catch (e: Exception) {
                GTRadioLogger.log("AlbumHintRepository: Failed to load $filename", e)
            }
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startTimeMs
        GTRadioLogger.log("AlbumHintRepository: Loaded ${allHints.size} hints (${hintMap.size} keys) in ${elapsedMs}ms")
    }

    fun findHint(context: Context, artist: String, songTitle: String): AlbumHint? {
        if (artist.isBlank() || songTitle.isBlank()) return null
        ensureLoaded(context)

        val normA = normalizeKey(artist)
        val primA = normalizeKey(extractPrimaryArtist(artist))
        val normT = normalizeKey(songTitle)
        val cleanT = normalizeKey(cleanTitleNoParens(songTitle))
        val tNoStop = stripStopWords(normT)
        val cleanTNoStop = stripStopWords(cleanT)
        val cleanTNoDigits = stripDigits(cleanT)

        val candidateKeys = listOf(
            "$normA|$normT",
            "$normA|$cleanT",
            "$primA|$normT",
            "$primA|$cleanT",
            "$normA|$tNoStop",
            "$normA|$cleanTNoStop",
            "$primA|$tNoStop",
            "$primA|$cleanTNoStop",
            "$primA|$cleanTNoDigits",
            "${normA.replace(" ", "")}|${normT.replace(" ", "")}",
            "${primA.replace(" ", "")}|${cleanT.replace(" ", "")}",
            "${normA.replace(" ", "")}|${cleanT.replace(" ", "")}",
            "${primA.replace(" ", "")}|${cleanTNoStop.replace(" ", "")}"
        )

        for (k in candidateKeys) {
            hintMap[k]?.let { return it }
        }

        // Secondary fallback: iterate allHints checking isArtistMatch & isTrackMatch
        synchronized(allHints) {
            for (hint in allHints) {
                val (artMatch1, _) = AlbumArtDownloader.isArtistMatch(hint.artist, artist)
                val (artMatch2, _) = AlbumArtDownloader.isArtistMatch(hint.effectiveArtist, artist)
                if (artMatch1 || artMatch2) {
                    val (trackMatch, _) = AlbumArtDownloader.isTrackMatch(hint.title, songTitle)
                    if (trackMatch) {
                        return hint
                    }
                }
            }
        }

        return null
    }

    fun clearMemoryCache() {
        hintMap.clear()
        synchronized(allHints) {
            allHints.clear()
        }
        loaded = false
    }
}
