package com.sc.gtradio.media.stations

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class Gen1StationInfo(
    val fileNameKey: String,
    val displayName: String,
    val hostName: String,
    val genre: String,
    val isTalkRadio: Boolean = false,
    val maxYear: Int = 2001
)

data class Gen1TrackCue(
    val startSec: Int,
    val endSec: Int,
    val title: String,
    val artist: String
)

object Gen1MetadataRepository {

    private val artCache = LruCache<String, Bitmap>(48)
    private val failedKeys = HashSet<String>()
    private val artExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun mmss(m: Int, s: Int): Int = m * 60 + s

    val gta3StationsByFile: Map<String, Gen1StationInfo> = linkedMapOf(
        "head.wav" to Gen1StationInfo("head.wav", "Head Radio", "Michael Hunt", "Pop & Rock", false, 2001),
        "lips.wav" to Gen1StationInfo("lips.wav", "Lips 106", "Andee", "Pop", false, 2001),
        "class.wav" to Gen1StationInfo("class.wav", "Double Clef FM", "Morgan Merryweather", "Classical Opera", false, 2001),
        "kjah.wav" to Gen1StationInfo("kjah.wav", "K-JAH", "Horace 'The Pacifist' Walsh", "Dub & Reggae", false, 2001),
        "rise.wav" to Gen1StationInfo("rise.wav", "Rise FM", "Andre The Accelerator", "Trance & Dance", false, 2001),
        "msx.wav" to Gen1StationInfo("msx.wav", "MSX FM", "MC Codebreaker & DJ Timecode", "Drum & Bass", false, 2001),
        "game.wav" to Gen1StationInfo("game.wav", "Game Radio FM", "Stretch Armstrong & Lord Sear", "Underground Hip-Hop", false, 2001),
        "flash.wav" to Gen1StationInfo("flash.wav", "Flashback 95.6", "Toni", "80s Pop & Synth", false, 2001),
        "chat.wav" to Gen1StationInfo("chat.wav", "Chatterbox FM", "Lazlow", "Talk Radio", true, 2001)
    )

    val viceCityStationsByFile: Map<String, Gen1StationInfo> = linkedMapOf(
        "vrock.adf" to Gen1StationInfo("vrock.adf", "V-Rock", "Lazlow", "80s Hard Rock & Heavy Metal", false, 1986),
        "wave.adf" to Gen1StationInfo("wave.adf", "Wave 103", "Adam First & Trish Camden", "New Wave & Synth-Pop", false, 1986),
        "emotion.adf" to Gen1StationInfo("emotion.adf", "Emotion 98.3", "Fernando Martinez", "Power Ballads & Soft Rock", false, 1986),
        "flash.adf" to Gen1StationInfo("flash.adf", "Flash FM", "Toni", "80s Pop", false, 1986),
        "wild.adf" to Gen1StationInfo("wild.adf", "Wildstyle Pirate Radio", "Mr. Magic", "Old School Hip-Hop & Electro", false, 1986),
        "fever.adf" to Gen1StationInfo("fever.adf", "Fever 105", "Oliver 'Ladykiller' Biscuit", "Disco, Soul & R&B", false, 1986),
        "espant.adf" to Gen1StationInfo("espant.adf", "Radio Espantoso", "Pepe", "Latin Jazz & Mambo", false, 1986),
        "kchat.adf" to Gen1StationInfo("kchat.adf", "K-Chat", "Amy Sheckenhausen", "Celebrity Talk Radio", true, 1986),
        "vcpr.adf" to Gen1StationInfo("vcpr.adf", "VCPR", "Maurice Chavez", "Public Talk Radio", true, 1986)
    )

    val lcsStationsByFile: Map<String, Gen1StationInfo> = linkedMapOf(
        "head.at3" to Gen1StationInfo("head.at3", "Head Radio", "Michael Hunt", "Pop & Soft Rock", false, 1998),
        "lips.at3" to Gen1StationInfo("lips.at3", "Lips 106", "Cliff Lane & Andee", "Pop", false, 1998),
        "double.at3" to Gen1StationInfo("double.at3", "Double Clef FM", "Sergio Boccino", "Classical & Opera", false, 1998),
        "kjah.at3" to Gen1StationInfo("kjah.at3", "K-JAH", "Natalie Walsh Davis", "Dancehall & Reggae", false, 1998),
        "rise.at3" to Gen1StationInfo("rise.at3", "Rise FM", "Boy Sanchez", "House & Dance", false, 1998),
        "msx.at3" to Gen1StationInfo("msx.at3", "MSX 98", "MC Codebreaker & DJ Timecode", "Drum & Bass", false, 1998),
        "flash.at3" to Gen1StationInfo("flash.at3", "Flashback FM", "Reni Wassulmaier", "Italo-Disco", false, 1998),
        "lcj.at3" to Gen1StationInfo("lcj.at3", "The Liberty Jam", "DJ Clue", "East Coast Hip-Hop", false, 1998),
        "mundo.at3" to Gen1StationInfo("mundo.at3", "Radio Del Mundo", "Panja Gavaskar", "World & Eastern", false, 1998),
        "lcfr.at3" to Gen1StationInfo("lcfr.at3", "LCFR", "Lazlow, Nurse Bob & Crow", "Liberty City Free Radio", true, 1998)
    )

    val vcsStationsByFile: Map<String, Gen1StationInfo> = linkedMapOf(
        "vrock.at3" to Gen1StationInfo("vrock.at3", "V-Rock", "Couzin Ed & Lazlow", "80s Hard Rock & Heavy Metal", false, 1984),
        "wave_eu.at3" to Gen1StationInfo("wave_eu.at3", "Wave 103", "Adam First & Trish Camden", "New Wave & Synth-Pop", false, 1984),
        "wave.at3" to Gen1StationInfo("wave.at3", "Wave 103", "Adam First & Trish Camden", "New Wave & Synth-Pop", false, 1984),
        "emotion.at3" to Gen1StationInfo("emotion.at3", "Emotion 98.3", "Lionel Makepeace", "Power Ballads & Soft Rock", false, 1984),
        "flash.at3" to Gen1StationInfo("flash.at3", "Flash FM", "Teri & Toni", "80s Pop", false, 1984),
        "fresh.at3" to Gen1StationInfo("fresh.at3", "Fresh 105 FM", "Luke", "Old School Hip-Hop & Electro", false, 1984),
        "paradise.at3" to Gen1StationInfo("paradise.at3", "Paradise FM", "Leslie 'Big Lez'", "Disco & Post-Disco", false, 1984),
        "vcfl.at3" to Gen1StationInfo("vcfl.at3", "VCFL", "Tina Jane", "Soul, R&B & Quiet Storm", false, 1984),
        "espant.at3" to Gen1StationInfo("espant.at3", "Radio Espantoso", "Hector Hernandez", "Latin Jazz & Salsa", false, 1984),
        "vcpr.at3" to Gen1StationInfo("vcpr.at3", "VCPR", "Maurice Chavez", "Pressing Issues Talk Radio", true, 1984)
    )

    // --- PART 1: GTA III (.wav) Timestamp Cue Sheets ---
    private val gta3CuesByFile: Map<String, List<Gen1TrackCue>> = mapOf(
        "head.wav" to listOf(
            Gen1TrackCue(mmss(0, 13), mmss(2, 41), "Stripe Summer", "Dil-Don't"),
            Gen1TrackCue(mmss(3, 19), mmss(6, 11), "Good Thing", "Whatever"),
            Gen1TrackCue(mmss(6, 11), mmss(9, 31), "Fade Away", "Craig Gray"),
            Gen1TrackCue(mmss(9, 31), mmss(12, 21), "Change", "Conor & Jay"),
            Gen1TrackCue(mmss(13, 4), mmss(16, 8), "See Through You", "Frankie Fame"),
            Gen1TrackCue(mmss(16, 8), mmss(18, 56), "Electronic Go Go", "Scatwerk"),
            Gen1TrackCue(mmss(19, 34), mmss(21, 51), "Life Is But A Mere Supply", "Dezma")
        ),
        "lips.wav" to listOf(
            Gen1TrackCue(mmss(1, 14), mmss(3, 27), "Bump To The Music", "Fatamarse"),
            Gen1TrackCue(mmss(5, 33), mmss(8, 50), "Feels Like I Just Can't Take No More", "April's in Paris"),
            Gen1TrackCue(mmss(8, 50), mmss(11, 48), "Forever", "Lucy"),
            Gen1TrackCue(mmss(11, 48), mmss(15, 38), "Pray It Goes Ok?", "Boyz 2 Girls"),
            Gen1TrackCue(mmss(15, 38), mmss(17, 39), "Grand Theft Auto (Joyride)", "Da Shootaz"),
            Gen1TrackCue(mmss(17, 39), mmss(19, 56), "Rubber Tip", "Funky BJs")
        ),
        "flash.wav" to listOf(
            Gen1TrackCue(mmss(0, 3), mmss(3, 19), "Rush Rush", "Debbie Harry"),
            Gen1TrackCue(mmss(4, 4), mmss(7, 24), "Shake It Up", "Elizabeth Daily"),
            Gen1TrackCue(mmss(8, 46), mmss(11, 17), "Scarface (Push It to the Limit)", "Paul Engemann"),
            Gen1TrackCue(mmss(11, 17), mmss(14, 53), "She's on Fire", "Amy Holland"),
            Gen1TrackCue(mmss(15, 36), mmss(18, 22), "I'm Hot Tonight", "Elizabeth Daily")
        ),
        "kjah.wav" to listOf(
            Gen1TrackCue(mmss(0, 5), mmss(4, 0), "Dance of the Vampires", "Scientist"),
            Gen1TrackCue(mmss(4, 0), mmss(8, 41), "Your Teeth in My Neck", "Scientist"),
            Gen1TrackCue(mmss(8, 41), mmss(12, 14), "The Corpse Rises", "Scientist"),
            Gen1TrackCue(mmss(12, 14), mmss(16, 34), "The Mummy's Shroud", "Scientist"),
            Gen1TrackCue(mmss(16, 34), mmss(19, 4), "Plague of Zombies", "Scientist")
        ),
        "rise.wav" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(1, 34), "Shake (Revolt Clogrock Remix)", "Chris Walsh & Dave Beran"),
            Gen1TrackCue(mmss(1, 34), mmss(6, 47), "Deep Time", "Shiver"),
            Gen1TrackCue(mmss(6, 47), mmss(9, 17), "Innerbattle", "R.R.D.S."),
            Gen1TrackCue(mmss(9, 17), mmss(13, 54), "Score (Original Mix)", "Slyder"),
            Gen1TrackCue(mmss(13, 54), mmss(20, 47), "Neo (The One)", "Slyder"),
            Gen1TrackCue(mmss(20, 47), mmss(26, 8), "Shake (Revolt Clogrock Remix)", "Chris Walsh & Dave Beran")
        ),
        "msx.wav" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(2, 51), "Quagmire", "Calyx"),
            Gen1TrackCue(mmss(2, 51), mmss(5, 54), "Get Wild", "Rascal & Klone"),
            Gen1TrackCue(mmss(5, 54), mmss(8, 2), "Judgement Day", "Ryme Tyme"),
            Gen1TrackCue(mmss(8, 2), mmss(9, 29), "Force", "Hex"),
            Gen1TrackCue(mmss(9, 29), mmss(10, 49), "First Contact", "Omni Trio"),
            Gen1TrackCue(mmss(10, 49), mmss(12, 38), "Spectre", "Aquasky"),
            Gen1TrackCue(mmss(12, 38), mmss(17, 28), "Winner Takes All", "Rascal & Klone"),
            Gen1TrackCue(mmss(17, 28), mmss(18, 11), "T Minus", "Ryme Tyme"),
            Gen1TrackCue(mmss(18, 11), mmss(19, 37), "Spasm", "nCode"),
            Gen1TrackCue(mmss(19, 37), mmss(23, 11), "Monolith", "D.Kay"),
            Gen1TrackCue(mmss(23, 11), mmss(25, 0), "Iceberg", "Dom & Ryme Tyme")
        ),
        "game.wav" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(0, 27), "Scary Movies (Instrumental)", "Bad Meets Evil"),
            Gen1TrackCue(mmss(0, 27), mmss(2, 42), "We're Live (Danger)", "Royce Da 5'9\""),
            Gen1TrackCue(mmss(2, 42), mmss(3, 48), "Nature Freestyle", "Nature"),
            Gen1TrackCue(mmss(3, 48), mmss(5, 14), "JoJo Pellegrino Freestyle", "JoJo Pellegrino"),
            Gen1TrackCue(mmss(5, 14), mmss(7, 35), "Spit Game", "Pretty Ugly & Royce Da 5'9\""),
            Gen1TrackCue(mmss(7, 35), mmss(9, 55), "I'm the King", "Royce Da 5'9\""),
            Gen1TrackCue(mmss(10, 51), mmss(12, 50), "By a Stranger", "Black Rob"),
            Gen1TrackCue(mmss(12, 50), mmss(15, 15), "Rising to the Top", "Agallah feat. Sean Price")
        ),
        "class.wav" to listOf(
            Gen1TrackCue(mmss(0, 8), mmss(3, 41), "Non più andrai farfallone amoroso", "Wolfgang Amadeus Mozart"),
            Gen1TrackCue(mmss(3, 58), mmss(6, 50), "O mio babbino caro", "Giacomo Puccini"),
            Gen1TrackCue(mmss(6, 58), mmss(10, 12), "Libiamo ne' lieti calici", "Giuseppe Verdi"),
            Gen1TrackCue(mmss(10, 20), mmss(14, 5), "Chi mi frena in tal momento", "Gaetano Donizetti"),
            Gen1TrackCue(mmss(16, 5), mmss(19, 18), "La donna è mobile", "Giuseppe Verdi"),
            Gen1TrackCue(mmss(19, 25), mmss(20, 35), "Finch'han del vino", "Wolfgang Amadeus Mozart")
        )
    )

    private val viceCityCuesByFile: Map<String, List<Gen1TrackCue>> = emptyMap()
    private val lcsCuesByFile: Map<String, List<Gen1TrackCue>> = emptyMap()
    private val vcsCuesByFile: Map<String, List<Gen1TrackCue>> = emptyMap()

    fun getStationInfo(fileName: String, gameCode: Int = 0): Gen1StationInfo? {
        val lower = fileName.lowercase().trim()
        return when (gameCode) {
            1 -> viceCityStationsByFile[lower]
            2 -> gta3StationsByFile[lower]
            3 -> lcsStationsByFile[lower]
            4 -> vcsStationsByFile[lower]
            else -> viceCityStationsByFile[lower] ?: gta3StationsByFile[lower] ?: lcsStationsByFile[lower] ?: vcsStationsByFile[lower]
        }
    }

    fun findCueAt(fileName: String, gameCode: Int, posSec: Int): Gen1TrackCue? {
        val lower = fileName.lowercase().trim()
        val list = when (gameCode) {
            1 -> viceCityCuesByFile[lower]
            2 -> gta3CuesByFile[lower]
            3 -> lcsCuesByFile[lower]
            4 -> vcsCuesByFile[lower]
            else -> gta3CuesByFile[lower] ?: viceCityCuesByFile[lower] ?: lcsCuesByFile[lower] ?: vcsCuesByFile[lower]
        } ?: return null
        return list.firstOrNull { posSec >= it.startSec && posSec < it.endSec }
    }

    fun getCachedAlbumArt(artist: String, title: String): Bitmap? {
        val key = "${artist.lowercase().trim()}|${title.lowercase().trim()}"
        return synchronized(artCache) { artCache.get(key) }
    }

    fun fetchAlbumArtAsync(artist: String, title: String, maxYear: Int, onResult: (Bitmap?) -> Unit) {
        val key = "${artist.lowercase().trim()}|${title.lowercase().trim()}"
        synchronized(artCache) {
            val cached = artCache.get(key)
            if (cached != null) {
                mainHandler.post { onResult(cached) }
                return
            }
            if (failedKeys.contains(key)) {
                mainHandler.post { onResult(null) }
                return
            }
        }

        artExecutor.submit {
            var bmp: Bitmap? = null
            try {
                val query = URLEncoder.encode("$artist $title", "UTF-8")
                val url = URL("https://itunes.apple.com/search?term=$query&media=music&entity=song&limit=8")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    val results = JSONObject(json).optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        var chosenArtUrl: String? = null
                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val relDate = item.optString("releaseDate", "")
                            val year = relDate.take(4).toIntOrNull() ?: 9999
                            val art100 = item.optString("artworkUrl100", "")
                            if (art100.isNotBlank() && (maxYear <= 0 || year <= maxYear + 2)) {
                                chosenArtUrl = art100.replace("100x100bb", "600x600bb")
                                break
                            }
                        }
                        if (chosenArtUrl == null) {
                            val fallbackArt = results.getJSONObject(0).optString("artworkUrl100", "")
                            if (fallbackArt.isNotBlank()) {
                                chosenArtUrl = fallbackArt.replace("100x100bb", "600x600bb")
                            }
                        }
                        if (chosenArtUrl != null) {
                            val artConn = (URL(chosenArtUrl).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 5000
                                readTimeout = 5000
                            }
                            if (artConn.responseCode == 200) {
                                bmp = BitmapFactory.decodeStream(artConn.inputStream)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            synchronized(artCache) {
                if (bmp != null) {
                    artCache.put(key, bmp)
                } else {
                    failedKeys.add(key)
                }
            }
            mainHandler.post { onResult(bmp) }
        }
    }
}
