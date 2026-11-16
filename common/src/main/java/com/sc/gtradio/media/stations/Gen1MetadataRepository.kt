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
            Gen1TrackCue(mmss(0, 8), mmss(3, 32), "Non più andrai farfallone amoroso", "Wolfgang Amadeus Mozart"),
            Gen1TrackCue(mmss(5, 37), mmss(7, 28), "O mio babbino caro", "Giacomo Puccini"),
            Gen1TrackCue(mmss(7, 45), mmss(10, 33), "Libiamo ne' lieti calici", "Giuseppe Verdi"),
            Gen1TrackCue(mmss(12, 41), mmss(16, 4), "Chi mi frena in tal momento", "Gaetano Donizetti"),
            Gen1TrackCue(mmss(16, 17), mmss(19, 3), "La donna è mobile", "Giuseppe Verdi"),
            Gen1TrackCue(mmss(19, 18), mmss(20, 35), "Finch'han del vino", "Wolfgang Amadeus Mozart")
        )
    )

    // --- PART 2: GTA Vice City (.adf) Timestamp Cue Sheets ---
    private val viceCityCuesByFile: Map<String, List<Gen1TrackCue>> = mapOf(
        "flash.adf" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(3, 52), "Out of Touch", "Hall & Oates"),
            Gen1TrackCue(mmss(3, 59), mmss(7, 11), "Dance Hall Days", "Wang Chung"),
            Gen1TrackCue(mmss(7, 19), mmss(11, 21), "Billie Jean", "Michael Jackson"),
            Gen1TrackCue(mmss(11, 36), mmss(15, 23), "Self Control", "Laura Branigan"),
            Gen1TrackCue(mmss(15, 30), mmss(18, 52), "Call Me", "Go West"),
            Gen1TrackCue(mmss(19, 3), mmss(22, 36), "Kiss the Dirt (Falling Down the Mountain)", "INXS"),
            Gen1TrackCue(mmss(24, 46), mmss(28, 4), "Run to You", "Bryan Adams"),
            Gen1TrackCue(mmss(28, 4), mmss(30, 46), "Four Little Diamonds", "Electric Light Orchestra"),
            Gen1TrackCue(mmss(30, 55), mmss(34, 24), "Owner of a Lonely Heart", "Yes"),
            Gen1TrackCue(mmss(34, 36), mmss(38, 15), "Video Killed the Radio Star", "The Buggles"),
            Gen1TrackCue(mmss(38, 22), mmss(41, 38), "Japanese Boy", "Aneka"),
            Gen1TrackCue(mmss(43, 29), mmss(46, 35), "Life's What You Make It", "Talk Talk"),
            Gen1TrackCue(mmss(46, 49), mmss(50, 0), "Your Love", "The Outfield"),
            Gen1TrackCue(mmss(50, 14), mmss(53, 46), "Steppin' Out", "Joe Jackson"),
            Gen1TrackCue(mmss(53, 49), mmss(57, 5), "One Thing Leads to Another", "The Fixx"),
            Gen1TrackCue(mmss(57, 8), mmss(59, 45), "Running with the Night", "Lionel Richie")
        ),
        "vrock.adf" to listOf(
            Gen1TrackCue(mmss(0, 10), mmss(3, 37), "You've Got Another Thing Comin'", "Judas Priest"),
            Gen1TrackCue(mmss(3, 45), mmss(7, 34), "Too Young to Fall in Love", "Mötley Crüe"),
            Gen1TrackCue(mmss(7, 39), mmss(11, 20), "Peace Sells", "Megadeth"),
            Gen1TrackCue(mmss(11, 29), mmss(14, 21), "Dangerous Bastard", "Love Fist"),
            Gen1TrackCue(mmss(17, 40), mmss(21, 14), "Turn Up the Radio", "Autograph"),
            Gen1TrackCue(mmss(21, 20), mmss(24, 22), "I Wanna Rock", "Twisted Sister"),
            Gen1TrackCue(mmss(24, 31), mmss(28, 32), "Bark at the Moon", "Ozzy Osbourne"),
            Gen1TrackCue(mmss(28, 39), mmss(32, 7), "Madhouse", "Anthrax"),
            Gen1TrackCue(mmss(32, 20), mmss(35, 45), "2 Minutes to Midnight", "Iron Maiden"),
            Gen1TrackCue(mmss(39, 11), mmss(42, 22), "Raining Blood", "Slayer"),
            Gen1TrackCue(mmss(42, 25), mmss(45, 43), "Cumin' Atcha Live", "Tesla"),
            Gen1TrackCue(mmss(45, 46), mmss(48, 36), "Fist Fury", "Love Fist"),
            Gen1TrackCue(mmss(48, 46), mmss(51, 53), "Yankee Rose", "David Lee Roth"),
            Gen1TrackCue(mmss(54, 51), mmss(59, 8), "Cum On Feel the Noize", "Quiet Riot"),
            Gen1TrackCue(mmss(59, 18), mmss(62, 8), "God Blessed Video", "Alcatrazz"),
            Gen1TrackCue(mmss(62, 17), mmss(64, 39), "Working for the Weekend", "Loverboy"),
            Gen1TrackCue(mmss(64, 51), mmss(68, 23), "She Sells Sanctuary", "The Cult")
        ),
        "wave.adf" to listOf(
            Gen1TrackCue(mmss(0, 14), mmss(3, 29), "Two Tribes", "Frankie Goes to Hollywood"),
            Gen1TrackCue(mmss(3, 39), mmss(6, 56), "Love Missile F1-11", "Sigue Sigue Sputnik"),
            Gen1TrackCue(mmss(6, 56), mmss(9, 59), "Cars", "Gary Numan"),
            Gen1TrackCue(mmss(10, 12), mmss(13, 47), "(Keep Feeling) Fascination", "The Human League"),
            Gen1TrackCue(mmss(15, 52), mmss(18, 22), "Atomic", "Blondie"),
            Gen1TrackCue(mmss(18, 22), mmss(21, 29), "99 Luftballons", "Nena"),
            Gen1TrackCue(mmss(21, 38), mmss(24, 59), "Kids in America", "Kim Wilde"),
            Gen1TrackCue(mmss(25, 5), mmss(27, 47), "Pale Shelter", "Tears for Fears"),
            Gen1TrackCue(mmss(30, 40), mmss(33, 55), "Sunglasses at Night", "Corey Hart"),
            Gen1TrackCue(mmss(33, 55), mmss(37, 19), "Poison Arrow", "ABC"),
            Gen1TrackCue(mmss(37, 30), mmss(40, 59), "I Ran (So Far Away)", "A Flock of Seagulls"),
            Gen1TrackCue(mmss(41, 11), mmss(44, 19), "Love My Way", "The Psychedelic Furs"),
            Gen1TrackCue(mmss(46, 13), mmss(50, 19), "Obsession", "Animotion"),
            Gen1TrackCue(mmss(50, 31), mmss(54, 29), "Gold", "Spandau Ballet"),
            Gen1TrackCue(mmss(54, 40), mmss(58, 23), "Hyperactive!", "Thomas Dolby"),
            Gen1TrackCue(mmss(58, 31), mmss(62, 29), "Never Say Never", "Romeo Void")
        ),
        "emotion.adf" to listOf(
            Gen1TrackCue(mmss(0, 6), mmss(4, 28), "Waiting for a Girl Like You", "Foreigner"),
            Gen1TrackCue(mmss(4, 33), mmss(8, 4), "Wow", "Kate Bush"),
            Gen1TrackCue(mmss(8, 26), mmss(12, 12), "Tempted", "Squeeze"),
            Gen1TrackCue(mmss(12, 15), mmss(15, 39), "Keep On Loving You", "REO Speedwagon"),
            Gen1TrackCue(mmss(18, 1), mmss(22, 18), "(I Just) Died in Your Arms", "Cutting Crew"),
            Gen1TrackCue(mmss(22, 25), mmss(26, 39), "More Than This", "Roxy Music"),
            Gen1TrackCue(mmss(26, 52), mmss(30, 56), "Africa", "Toto"),
            Gen1TrackCue(mmss(31, 2), mmss(35, 36), "Broken Wings", "Mr. Mister"),
            Gen1TrackCue(mmss(37, 52), mmss(41, 28), "Missing You", "John Waite"),
            Gen1TrackCue(mmss(41, 41), mmss(45, 23), "Crockett's Theme", "Jan Hammer"),
            Gen1TrackCue(mmss(48, 18), mmss(52, 24), "Sister Christian", "Night Ranger"),
            Gen1TrackCue(mmss(52, 39), mmss(56, 20), "Never Too Much", "Luther Vandross")
        ),
        "fever.adf" to listOf(
            Gen1TrackCue(mmss(0, 8), mmss(3, 45), "And the Beat Goes On", "The Whispers"),
            Gen1TrackCue(mmss(3, 45), mmss(7, 13), "Act Like You Know", "Fat Larry's Band"),
            Gen1TrackCue(mmss(7, 18), mmss(10, 56), "Get Down Saturday Night", "Oliver Cheatham"),
            Gen1TrackCue(mmss(11, 2), mmss(14, 32), "Automatic", "The Pointer Sisters"),
            Gen1TrackCue(mmss(16, 59), mmss(21, 18), "I'll Be Good", "René & Angela"),
            Gen1TrackCue(mmss(21, 29), mmss(25, 5), "All Night Long", "Mary Jane Girls"),
            Gen1TrackCue(mmss(25, 12), mmss(29, 3), "Ghetto Life", "Rick James"),
            Gen1TrackCue(mmss(31, 59), mmss(36, 39), "Wanna Be Startin' Somethin'", "Michael Jackson"),
            Gen1TrackCue(mmss(36, 45), mmss(40, 47), "Shame", "Evelyn 'Champagne' King"),
            Gen1TrackCue(mmss(40, 47), mmss(44, 16), "Behind the Groove", "Teena Marie"),
            Gen1TrackCue(mmss(46, 13), mmss(50, 5), "Juicy Fruit", "Mtume"),
            Gen1TrackCue(mmss(50, 10), mmss(53, 48), "Summer Madness", "Kool & the Gang"),
            Gen1TrackCue(mmss(53, 58), mmss(58, 23), "Last Night a D.J. Saved My Life", "Indeep")
        ),
        "wild.adf" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(4, 25), "Pump Me Up", "Trouble Funk"),
            Gen1TrackCue(mmss(4, 34), mmss(9, 17), "One for the Treble (Fresh)", "Davy DMX"),
            Gen1TrackCue(mmss(9, 17), mmss(13, 34), "Clear", "Cybotron"),
            Gen1TrackCue(mmss(13, 45), mmss(18, 38), "Al-Naafiysh (The Soul)", "Hashim"),
            Gen1TrackCue(mmss(18, 38), mmss(23, 39), "Rockit", "Herbie Hancock"),
            Gen1TrackCue(mmss(23, 57), mmss(30, 40), "Looking for the Perfect Beat", "Afrika Bambaataa & the Soul Sonic Force"),
            Gen1TrackCue(mmss(30, 48), mmss(36, 8), "Get It Girl", "2 Live Crew"),
            Gen1TrackCue(mmss(36, 25), mmss(41, 9), "Rock Box", "Run-D.M.C."),
            Gen1TrackCue(mmss(41, 12), mmss(45, 43), "Bassline", "Mantronix"),
            Gen1TrackCue(mmss(45, 48), mmss(50, 16), "The Smurf", "Tyrone Brunson"),
            Gen1TrackCue(mmss(50, 25), mmss(56, 4), "Magic's Wand", "Whodini"),
            Gen1TrackCue(mmss(56, 35), mmss(60, 46), "More Bounce to the Ounce", "Zapp & Roger"),
            Gen1TrackCue(mmss(60, 55), mmss(65, 15), "The Message", "Grandmaster Flash & the Furious Five"),
            Gen1TrackCue(mmss(65, 25), mmss(68, 27), "The Breaks", "Kurtis Blow")
        ),
        "espant.adf" to listOf(
            Gen1TrackCue(mmss(0, 9), mmss(4, 28), "A Gozar Con Mi Combo", "Cachao"),
            Gen1TrackCue(mmss(4, 28), mmss(7, 42), "The Bull is Wrong", "Alpha Banditos"),
            Gen1TrackCue(mmss(7, 54), mmss(12, 7), "Yo Te Miré", "Tres Apenas Como Eso"),
            Gen1TrackCue(mmss(12, 12), mmss(16, 59), "Latin Flute", "Deodato"),
            Gen1TrackCue(mmss(18, 36), mmss(22, 47), "Mama Papa Tu", "Mongo Santamaría"),
            Gen1TrackCue(mmss(22, 50), mmss(28, 4), "Me and You Baby (Picao y Tostao)", "Mongo Santamaría"),
            Gen1TrackCue(mmss(28, 11), mmss(31, 35), "Mambo Mucho Mambo", "Machito and His Afro-Cuban Orchestra"),
            Gen1TrackCue(mmss(31, 45), mmss(35, 52), "La Vida Es Una Lenteja", "Unaesta"),
            Gen1TrackCue(mmss(38, 15), mmss(42, 59), "Expansions", "Lonnie Liston Smith"),
            Gen1TrackCue(mmss(43, 8), mmss(46, 8), "Añunga Ñunga", "Irakere"),
            Gen1TrackCue(mmss(46, 13), mmss(51, 53), "Super Strut", "Deodato"),
            Gen1TrackCue(mmss(52, 0), mmss(55, 19), "Jamay", "Xavier Cugat and His Orchestra"),
            Gen1TrackCue(mmss(55, 34), mmss(59, 24), "Maracaibo Oriental", "Benny Moré"),
            Gen1TrackCue(mmss(59, 35), mmss(61, 38), "Mambo Gozón", "Tito Puente")
        )
    )
    // --- PART 3: GTA: Liberty City Stories (.AT3) Timestamp Cue Sheets ---
    private val lcsCuesByFile: Map<String, List<Gen1TrackCue>> = mapOf(
        "head.at3" to listOf(
            Gen1TrackCue(mmss(0, 14), mmss(3, 19), "Train", "Conor & Jay"),
            Gen1TrackCue(mmss(3, 24), mmss(6, 15), "The One For Me", "Cloud Nineteen"),
            Gen1TrackCue(mmss(8, 12), mmss(11, 36), "Take the Pain", "Purser"),
            Gen1TrackCue(mmss(11, 46), mmss(15, 28), "Free Yourself", "L-Marie feat. Raff"),
            Gen1TrackCue(mmss(17, 20), mmss(20, 36), "Drive", "15 Ways"),
            Gen1TrackCue(mmss(20, 42), mmss(23, 22), "Welcome to the Real World", "Roush"),
            Gen1TrackCue(mmss(23, 28), mmss(26, 25), "Keep Dreaming", "Vanilla Smoothie")
        ),
        "lips.at3" to listOf(
            Gen1TrackCue(mmss(0, 18), mmss(3, 48), "Funk in Time", "Rudy La Fontaine"),
            Gen1TrackCue(mmss(3, 55), mmss(7, 2), "Love is the Feeling", "Sawaar"),
            Gen1TrackCue(mmss(9, 5), mmss(12, 8), "Mine Until Monday", "Sunshine Shine"),
            Gen1TrackCue(mmss(12, 15), mmss(15, 25), "Get Down", "Credit Check"),
            Gen1TrackCue(mmss(17, 22), mmss(20, 45), "Tonight", "Cool Timers"),
            Gen1TrackCue(mmss(20, 52), mmss(24, 12), "Bass", "Nina Barry"),
            Gen1TrackCue(mmss(24, 20), mmss(27, 45), "Into Something (Come on, Get Down)", "The Jackstars")
        ),
        "double.at3" to listOf(
            Gen1TrackCue(mmss(0, 28), mmss(3, 14), "Anvil Chorus (Il trovatore)", "Giuseppe Verdi"),
            Gen1TrackCue(mmss(3, 35), mmss(7, 30), "Tacea la notte placida", "Giuseppe Verdi"),
            Gen1TrackCue(mmss(9, 30), mmss(12, 40), "Vesti la giubba (Pagliacci)", "Ruggero Leoncavallo"),
            Gen1TrackCue(mmss(12, 55), mmss(16, 50), "E amore un ladroncello", "Wolfgang Amadeus Mozart"),
            Gen1TrackCue(mmss(18, 45), mmss(22, 50), "The Marriage of Figaro: Overture", "Wolfgang Amadeus Mozart"),
            Gen1TrackCue(mmss(23, 5), mmss(26, 25), "Va, pensiero (Nabucco)", "Giuseppe Verdi")
        ),
        "kjah.at3" to listOf(
            Gen1TrackCue(mmss(0, 18), mmss(3, 38), "Pick a Sound", "Selah Collins"),
            Gen1TrackCue(mmss(3, 42), mmss(6, 52), "What a Wonderful Feeling", "Errol Bellot"),
            Gen1TrackCue(mmss(6, 56), mmss(10, 18), "Watch How the People Dancing", "Kenny Knots"),
            Gen1TrackCue(mmss(12, 15), mmss(15, 28), "Lean Boot", "Richie Davis"),
            Gen1TrackCue(mmss(15, 32), mmss(19, 0), "Ready for the Dancehall Tonight", "Peter Bouncer"),
            Gen1TrackCue(mmss(19, 5), mmss(22, 18), "Ring My Number", "Kenny Knots"),
            Gen1TrackCue(mmss(22, 22), mmss(25, 35), "Run Come Call Me", "Kenny Knots"),
            Gen1TrackCue(mmss(25, 40), mmss(28, 42), "You Ha Fe Cool", "Richie Davis")
        ),
        "rise.at3" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(4, 38), "Sing It Back (Boris Musical Mix)", "Moloko"),
            Gen1TrackCue(mmss(4, 38), mmss(8, 25), "Free", "Ultra Naté"),
            Gen1TrackCue(mmss(8, 25), mmss(12, 15), "I Believe", "Happy Clappers"),
            Gen1TrackCue(mmss(12, 15), mmss(15, 52), "House Music", "Eddie Amador"),
            Gen1TrackCue(mmss(15, 52), mmss(19, 35), "Feel What You Want", "Kristine W"),
            Gen1TrackCue(mmss(19, 35), mmss(23, 28), "Hideaway (Deep Dish Vocal Remix)", "De'Lacy"),
            Gen1TrackCue(mmss(23, 28), mmss(27, 10), "Spin Spin Sugar (Armand's Dark Garage Mix)", "Sneaker Pimps"),
            Gen1TrackCue(mmss(27, 10), mmss(30, 45), "Plastic Dreams", "Jaydee"),
            Gen1TrackCue(mmss(30, 45), mmss(34, 20), "Altered States", "Ron Trent"),
            Gen1TrackCue(mmss(34, 20), mmss(37, 15), "There Will Come a Day (Half Tab Mix)", "The Absolute"),
            Gen1TrackCue(mmss(37, 15), mmss(39, 50), "Positive Education", "Slam"),
            Gen1TrackCue(mmss(39, 50), mmss(42, 20), "Flash", "Green Velvet")
        ),
        "msx.at3" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(3, 25), "Renegade Snares", "Omni Trio"),
            Gen1TrackCue(mmss(3, 25), mmss(6, 48), "Terrorist", "Renegade"),
            Gen1TrackCue(mmss(6, 48), mmss(10, 12), "Finest Illusion (Legal Mix)", "Foul Play"),
            Gen1TrackCue(mmss(10, 12), mmss(13, 20), "Thru the Vibe (2 on 1 Mix)", "Omni Trio"),
            Gen1TrackCue(mmss(13, 20), mmss(16, 45), "The Helicopter Tune", "Deep Blue"),
            Gen1TrackCue(mmss(16, 45), mmss(20, 10), "Dred Bass", "Dead Dred"),
            Gen1TrackCue(mmss(20, 10), mmss(23, 30), "Soul Promenade", "Omni Trio"),
            Gen1TrackCue(mmss(23, 30), mmss(26, 45), "Living For The Future (FBD Project Remix)", "Omni Trio"),
            Gen1TrackCue(mmss(26, 45), mmss(29, 55), "Disturbance (Tango Remix)", "Hyper-On Experience"),
            Gen1TrackCue(mmss(29, 55), mmss(33, 10), "Cold Fresh Air", "Higher Sense"),
            Gen1TrackCue(mmss(33, 10), mmss(36, 5), "First Contact", "Omni Trio"),
            Gen1TrackCue(mmss(36, 5), mmss(38, 48), "Rolled Into 1 (Photek Remix)", "E-Z Rollers")
        ),
        "flash.at3" to listOf(
            Gen1TrackCue(mmss(0, 14), mmss(4, 25), "I Wanna Rock You", "Giorgio Moroder"),
            Gen1TrackCue(mmss(4, 32), mmss(8, 28), "E=MC2", "Giorgio Moroder"),
            Gen1TrackCue(mmss(8, 35), mmss(13, 5), "From Here to Eternity", "Giorgio Moroder"),
            Gen1TrackCue(mmss(15, 8), mmss(19, 45), "Chase", "Giorgio Moroder"),
            Gen1TrackCue(mmss(19, 52), mmss(24, 18), "First Hand Experience in Second Hand Love", "Giorgio Moroder"),
            Gen1TrackCue(mmss(24, 25), mmss(28, 50), "Knights in White Satin", "Giorgio Moroder")
        ),
        "lcj.at3" to listOf(
            Gen1TrackCue(mmss(0, 20), mmss(3, 45), "All I Need", "Method Man"),
            Gen1TrackCue(mmss(3, 52), mmss(7, 45), "Shook Ones (Part II)", "Mobb Deep"),
            Gen1TrackCue(mmss(7, 52), mmss(11, 35), "Incarcerated Scarfaces", "Raekwon"),
            Gen1TrackCue(mmss(11, 42), mmss(15, 5), "N.O.R.E.", "Noreaga"),
            Gen1TrackCue(mmss(17, 10), mmss(21, 5), "Shut 'Em Down (Remix)", "Onyx feat. Noreaga & Big Pun"),
            Gen1TrackCue(mmss(21, 12), mmss(24, 15), "Beware", "Big Pun"),
            Gen1TrackCue(mmss(24, 20), mmss(27, 55), "Twinz (Deep Cover 98)", "Big Pun & Fat Joe"),
            Gen1TrackCue(mmss(28, 2), mmss(30, 55), "Get At Me Dog", "DMX feat. Sheek Louch"),
            Gen1TrackCue(mmss(33, 0), mmss(36, 45), "Ruff Ryders' Anthem (Remix)", "DMX feat. DJ Clue, Jadakiss, Styles P, Drag-On & Eve"),
            Gen1TrackCue(mmss(36, 52), mmss(40, 35), "Do What Ya Feel", "Redman feat. Method Man"),
            Gen1TrackCue(mmss(40, 42), mmss(44, 0), "Chain Gang Freestyle", "The LOX & Black Rob"),
            Gen1TrackCue(mmss(44, 5), mmss(47, 30), "Chest2Chest Freestyle", "The LOX")
        ),
        "mundo.at3" to listOf(
            Gen1TrackCue(mmss(0, 15), mmss(3, 38), "Raghupati", "Ananda Shankar"),
            Gen1TrackCue(mmss(3, 45), mmss(7, 35), "Dum Maro Dum", "Asha Bhosle"),
            Gen1TrackCue(mmss(7, 42), mmss(11, 25), "Neeve Nanna (Only You Were Mine)", "Vijaya Anand"),
            Gen1TrackCue(mmss(13, 20), mmss(17, 30), "Kidda", "Natacha Atlas"),
            Gen1TrackCue(mmss(17, 38), mmss(21, 45), "Hebeena Hebeena", "Farid El Atrache"),
            Gen1TrackCue(mmss(21, 52), mmss(25, 25), "Aini Bet Ref", "Ahmed Mneimneh"),
            Gen1TrackCue(mmss(27, 20), mmss(30, 40), "Im Nin'alu", "Ofra Haza"),
            Gen1TrackCue(mmss(30, 48), mmss(33, 32), "Ballaa Tsoubou Hul Kahwa", "Samira Tawfik")
        ),
        "lcfr.at3" to listOf(
            Gen1TrackCue(mmss(0, 10), mmss(9, 45), "Heartland Values", "Nurse Bob"),
            Gen1TrackCue(mmss(11, 30), mmss(21, 15), "The Electron Zone", "Steve & Bill"),
            Gen1TrackCue(mmss(23, 0), mmss(31, 45), "Breathing World", "Crow & Isis"),
            Gen1TrackCue(mmss(33, 20), mmss(40, 30), "Coq 'O' Vin", "Richard Goblin"),
            Gen1TrackCue(mmss(42, 0), mmss(50, 10), "Chatterbox", "Lazlow")
        )
    )
    // --- PART 4: GTA: Vice City Stories (.AT3) Timestamp Cue Sheets ---
    private val vcsCuesByFile: Map<String, List<Gen1TrackCue>> = mapOf(
        "flash.at3" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(3, 55), "Gloria", "Laura Branigan"),
            Gen1TrackCue(mmss(4, 6), mmss(7, 50), "Human Touch", "Rick Springfield"),
            Gen1TrackCue(mmss(8, 1), mmss(11, 12), "The One Thing", "INXS"),
            Gen1TrackCue(mmss(11, 17), mmss(15, 22), "Easy Lover", "Philip Bailey & Phil Collins"),
            Gen1TrackCue(mmss(15, 30), mmss(19, 18), "The Warrior", "Scandal feat. Patty Smyth"),
            Gen1TrackCue(mmss(19, 28), mmss(22, 52), "Love Resurrection", "Alison Moyet"),
            Gen1TrackCue(mmss(23, 1), mmss(26, 35), "Games People Play", "The Alan Parsons Project"),
            Gen1TrackCue(mmss(27, 44), mmss(30, 40), "Family Man", "Hall & Oates"),
            Gen1TrackCue(mmss(30, 46), mmss(34, 0), "Love Is a Battlefield", "Pat Benatar"),
            Gen1TrackCue(mmss(34, 6), mmss(37, 18), "Wouldn't It Be Good", "Nik Kershaw"),
            Gen1TrackCue(mmss(37, 23), mmss(40, 55), "Together in Electric Dreams", "Philip Oakey & Giorgio Moroder"),
            Gen1TrackCue(mmss(41, 5), mmss(44, 45), "It's My Life", "Talk Talk"),
            Gen1TrackCue(mmss(44, 55), mmss(48, 15), "Destination Unknown", "Missing Persons"),
            Gen1TrackCue(mmss(48, 25), mmss(51, 55), "Don't Let Go", "Wang Chung"),
            Gen1TrackCue(mmss(52, 5), mmss(55, 45), "Appaloosa", "Gino Vannelli"),
            Gen1TrackCue(mmss(55, 55), mmss(59, 15), "Turn It On Again", "Genesis"),
            Gen1TrackCue(mmss(59, 22), mmss(62, 5), "Living on the Ceiling", "Blancmange"),
            Gen1TrackCue(mmss(62, 12), mmss(64, 50), "Come Back and Stay", "Paul Young")
        ),
        "vrock.at3" to listOf(
            Gen1TrackCue(mmss(0, 32), mmss(4, 22), "Holy Diver", "Dio"),
            Gen1TrackCue(mmss(4, 27), mmss(8, 35), "Queen of the Reich", "Queensrÿche"),
            Gen1TrackCue(mmss(9, 52), mmss(13, 14), "Lick It Up", "KISS"),
            Gen1TrackCue(mmss(13, 19), mmss(16, 55), "Breaking the Chains", "Dokken"),
            Gen1TrackCue(mmss(18, 31), mmss(22, 20), "All I'm Gonna Take", "Autograph"),
            Gen1TrackCue(mmss(23, 45), mmss(27, 55), "Balls to the Wall", "Accept"),
            Gen1TrackCue(mmss(28, 1), mmss(32, 5), "Rock You Like a Hurricane", "Scorpions"),
            Gen1TrackCue(mmss(32, 57), mmss(36, 35), "Long Stick Goes Boom", "Krokus"),
            Gen1TrackCue(mmss(36, 41), mmss(40, 55), "Stranglehold", "Ted Nugent"),
            Gen1TrackCue(mmss(41, 2), mmss(45, 10), "Round and Round", "Ratt"),
            Gen1TrackCue(mmss(46, 15), mmss(50, 25), "Electric Eye", "Judas Priest"),
            Gen1TrackCue(mmss(50, 35), mmss(54, 30), "Looks That Kill", "Mötley Crüe"),
            Gen1TrackCue(mmss(55, 15), mmss(59, 38), "Metal Health (Bang Your Head)", "Quiet Riot")
        ),
        "emotion.at3" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(2, 52), "Only the Lonely", "The Motels"),
            Gen1TrackCue(mmss(2, 57), mmss(7, 55), "I'm Not in Love", "10cc"),
            Gen1TrackCue(mmss(8, 8), mmss(11, 45), "Harden My Heart", "Quarterflash"),
            Gen1TrackCue(mmss(13, 7), mmss(16, 25), "Make Believe", "Toto"),
            Gen1TrackCue(mmss(16, 32), mmss(20, 5), "Fool (If You Think It's Over)", "Elkie Brooks"),
            Gen1TrackCue(mmss(21, 6), mmss(24, 55), "I'm in Love with a German Film Star", "The Passions"),
            Gen1TrackCue(mmss(25, 49), mmss(29, 35), "I Want to Know What Love Is", "Foreigner"),
            Gen1TrackCue(mmss(29, 40), mmss(33, 15), "Never Never", "The Assembly"),
            Gen1TrackCue(mmss(34, 15), mmss(37, 50), "We Belong", "Pat Benatar"),
            Gen1TrackCue(mmss(37, 56), mmss(41, 12), "Private Life", "The Pretenders"),
            Gen1TrackCue(mmss(41, 17), mmss(45, 58), "In the Air Tonight", "Phil Collins"),
            Gen1TrackCue(mmss(46, 7), mmss(49, 55), "Avalon", "Roxy Music"),
            Gen1TrackCue(mmss(50, 49), mmss(54, 15), "Baby Hold On", "Eddie Money"),
            Gen1TrackCue(mmss(54, 22), mmss(58, 35), "Stone Cold", "Rainbow"),
            Gen1TrackCue(mmss(58, 43), mmss(62, 28), "Call to the Heart", "Giuffria"),
            Gen1TrackCue(mmss(62, 35), mmss(65, 40), "Moments in Love", "Art of Noise"),
            Gen1TrackCue(mmss(65, 47), mmss(69, 5), "I Can Dream About You", "Dan Hartman")
        ),
        "wave_eu.at3" to listOf(
            Gen1TrackCue(mmss(0, 10), mmss(3, 45), "Love Action (I Believe in Love)", "The Human League"),
            Gen1TrackCue(mmss(3, 52), mmss(7, 25), "Love on Your Side", "Thompson Twins"),
            Gen1TrackCue(mmss(7, 32), mmss(11, 15), "Everything Counts", "Depeche Mode"),
            Gen1TrackCue(mmss(12, 30), mmss(16, 10), "Heart of Glass", "Blondie"),
            Gen1TrackCue(mmss(16, 18), mmss(19, 55), "Relax", "Frankie Goes to Hollywood"),
            Gen1TrackCue(mmss(20, 5), mmss(23, 35), "(How to Be A) Millionaire", "ABC"),
            Gen1TrackCue(mmss(25, 0), mmss(29, 35), "Blue Monday", "New Order"),
            Gen1TrackCue(mmss(29, 42), mmss(33, 25), "Quiet Life", "Japan"),
            Gen1TrackCue(mmss(33, 32), mmss(37, 20), "Too Shy (Midnight Mix)", "Kajagoogoo"),
            Gen1TrackCue(mmss(38, 30), mmss(42, 15), "Penthouse and Pavement", "Heaven 17"),
            Gen1TrackCue(mmss(42, 55), mmss(46, 18), "Like to Get to Know You Well", "Howard Jones"),
            Gen1TrackCue(mmss(46, 23), mmss(49, 55), "A Forest", "The Cure"),
            Gen1TrackCue(mmss(50, 0), mmss(53, 35), "Space Age Love Song", "A Flock of Seagulls"),
            Gen1TrackCue(mmss(53, 40), mmss(56, 20), "Don't Go", "Yazoo")
        ),
        "wave.at3" to listOf(
            Gen1TrackCue(mmss(0, 10), mmss(3, 45), "Love Action (I Believe in Love)", "The Human League"),
            Gen1TrackCue(mmss(3, 52), mmss(7, 25), "Love on Your Side", "Thompson Twins"),
            Gen1TrackCue(mmss(7, 32), mmss(11, 15), "Everything Counts", "Depeche Mode"),
            Gen1TrackCue(mmss(12, 30), mmss(16, 10), "Heart of Glass", "Blondie"),
            Gen1TrackCue(mmss(16, 18), mmss(19, 55), "Relax", "Frankie Goes to Hollywood"),
            Gen1TrackCue(mmss(20, 5), mmss(23, 35), "(How to Be A) Millionaire", "ABC"),
            Gen1TrackCue(mmss(25, 0), mmss(29, 35), "Blue Monday", "New Order"),
            Gen1TrackCue(mmss(29, 42), mmss(33, 25), "Quiet Life", "Japan"),
            Gen1TrackCue(mmss(33, 32), mmss(37, 20), "Too Shy (Midnight Mix)", "Kajagoogoo"),
            Gen1TrackCue(mmss(38, 30), mmss(42, 10), "Penthouse and Pavement", "Heaven 17"),
            Gen1TrackCue(mmss(42, 15), mmss(45, 50), "Sex (I'm A...)", "Berlin"),
            Gen1TrackCue(mmss(46, 37), mmss(49, 58), "Like to Get to Know You Well", "Howard Jones"),
            Gen1TrackCue(mmss(50, 1), mmss(53, 32), "A Forest", "The Cure"),
            Gen1TrackCue(mmss(53, 36), mmss(57, 15), "Space Age Love Song", "A Flock of Seagulls"),
            Gen1TrackCue(mmss(57, 19), mmss(60, 0), "Don't Go", "Yazoo")
        ),
        "fresh.at3" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(3, 5), "Renegades of Funk", "Afrika Bambaataa & the Soul Sonic Force"),
            Gen1TrackCue(mmss(3, 8), mmss(5, 45), "Pack Jam (Look Out for the OVC)", "The Jonzun Crew"),
            Gen1TrackCue(mmss(5, 47), mmss(8, 35), "It's Like That", "Run-D.M.C."),
            Gen1TrackCue(mmss(8, 39), mmss(11, 55), "Play at Your Own Risk", "Planet Patrol"),
            Gen1TrackCue(mmss(12, 0), mmss(15, 5), "Egypt, Egypt", "The Egyptian Lover"),
            Gen1TrackCue(mmss(15, 9), mmss(17, 50), "Beat Box (Diversion One)", "Art of Noise"),
            Gen1TrackCue(mmss(17, 55), mmss(20, 45), "Boogie Down Bronx", "Man Parrish"),
            Gen1TrackCue(mmss(20, 50), mmss(23, 55), "Request Line", "Rock Master Scott & the Dynamic Three"),
            Gen1TrackCue(mmss(24, 0), mmss(26, 55), "Freak-A-Zoid", "Midnight Star"),
            Gen1TrackCue(mmss(27, 0), mmss(29, 44), "Freaks Come Out at Night", "Whodini")
        ),
        "paradise.at3" to listOf(
            Gen1TrackCue(mmss(0, 1), mmss(2, 55), "I Hear Music in the Streets", "Unlimited Touch"),
            Gen1TrackCue(mmss(2, 58), mmss(6, 54), "Everyway But Loose (Larry Levan Remix)", "Plunky & the Oneness of Juju"),
            Gen1TrackCue(mmss(6, 57), mmss(9, 45), "Can't Fake the Feeling", "Geraldine Hunt"),
            Gen1TrackCue(mmss(9, 49), mmss(12, 38), "Do It to the Music", "Raw Silk"),
            Gen1TrackCue(mmss(12, 42), mmss(15, 22), "Is It In", "Jimmy 'Bo' Horne"),
            Gen1TrackCue(mmss(15, 25), mmss(18, 22), "Together Forever", "Exodus"),
            Gen1TrackCue(mmss(18, 25), mmss(23, 10), "This Time Baby", "Jackie Moore"),
            Gen1TrackCue(mmss(23, 15), mmss(26, 20), "Weekend (Tonight Is Party Time)", "Class Action"),
            Gen1TrackCue(mmss(26, 25), mmss(29, 50), "It Should Have Been You", "Gwen Guthrie"),
            Gen1TrackCue(mmss(29, 55), mmss(32, 32), "You Used to Hold Me So Tight", "Thelma Houston"),
            Gen1TrackCue(mmss(32, 35), mmss(35, 35), "Lost in Music (1984 Bernard Edwards & Nile Rodgers Remix)", "Sister Sledge"),
            Gen1TrackCue(mmss(35, 40), mmss(38, 25), "Love Has Come Around", "Donald Byrd"),
            Gen1TrackCue(mmss(38, 28), mmss(41, 17), "The Glow of Love", "Change feat. Luther Vandross")
        ),
        "vcfl.at3" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(3, 20), "Sexual Healing", "Marvin Gaye"),
            Gen1TrackCue(mmss(3, 22), mmss(6, 22), "Fantasy", "Earth, Wind & Fire"),
            Gen1TrackCue(mmss(7, 27), mmss(10, 38), "It Started with a Kiss", "Hot Chocolate"),
            Gen1TrackCue(mmss(10, 42), mmss(13, 32), "Mary Jane", "Rick James"),
            Gen1TrackCue(mmss(13, 36), mmss(17, 15), "Nightshift", "Commodores"),
            Gen1TrackCue(mmss(18, 20), mmss(21, 15), "Mambo", "Wally Badarou"),
            Gen1TrackCue(mmss(21, 20), mmss(25, 10), "It's Ecstasy When You Lay Down Next to Me", "Barry White"),
            Gen1TrackCue(mmss(26, 15), mmss(29, 45), "You Can't Turn Me Away", "Sylvia Striplin"),
            Gen1TrackCue(mmss(29, 50), mmss(32, 55), "Everybody Loves the Sunshine", "Roy Ayers Ubiquity"),
            Gen1TrackCue(mmss(33, 0), mmss(35, 55), "Risin' to the Top", "Keni Burke"),
            Gen1TrackCue(mmss(36, 0), mmss(38, 54), "Love T.K.O.", "Teddy Pendergrass")
        ),
        "espant.at3" to listOf(
            Gen1TrackCue(mmss(0, 1), mmss(3, 20), "Acid", "Ray Barretto"),
            Gen1TrackCue(mmss(3, 24), mmss(7, 40), "I Like It (I Like It Like That)", "Pete 'El Conde' Rodriguez"),
            Gen1TrackCue(mmss(7, 45), mmss(11, 20), "Oye Cómo Va", "Tito Puente"),
            Gen1TrackCue(mmss(11, 24), mmss(14, 8), "Mi Ritmo Es Bueno", "Bobby Valentín & Frankie Hernández"),
            Gen1TrackCue(mmss(14, 12), mmss(17, 38), "Químbara", "Celia Cruz & Johnny Pacheco"),
            Gen1TrackCue(mmss(17, 41), mmss(21, 0), "Mi Gente", "Héctor Lavoe"),
            Gen1TrackCue(mmss(21, 5), mmss(26, 15), "Revolt / La Libertad Lógico", "Eddie Palmieri & Ismael Quintana"),
            Gen1TrackCue(mmss(26, 20), mmss(30, 32), "El Malo", "Willie Colón & Héctor Lavoe")
        ),
        "vcpr.at3" to listOf(
            Gen1TrackCue(mmss(0, 0), mmss(17, 35), "Pressing Issues", "Maurice Chavez"),
            Gen1TrackCue(mmss(17, 42), mmss(31, 25), "Bait and Switch", "Larry Joe & Bobbie Ray"),
            Gen1TrackCue(mmss(31, 31), mmss(40, 30), "The Time Ranger", "VCPR Vintage Drama"),
            Gen1TrackCue(mmss(40, 36), mmss(53, 20), "New World Order", "Dwayne Thorn"),
            Gen1TrackCue(mmss(53, 25), mmss(64, 59), "Gordon Moorehead Rides Again", "VCPR Vintage Drama")
        )
    )

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
