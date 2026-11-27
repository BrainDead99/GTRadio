package com.sc.gtradio.media.rpf3

import android.content.Context
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.artwork.AlbumArtDownloader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.HashMap

data class Rpf3TrackMeta(
    val internalName: String,
    val title: String,
    val artist: String,
    val isTalkShow: Boolean = false
)

object Rpf3MetadataRepository {
    @Volatile
    private var loaded = false
    private val hashToName = HashMap<Int, String>(80000)
    private val trackMetaMap = HashMap<String, Rpf3TrackMeta>(600)
    private val trackIdMetaMapInternal = HashMap<Int, Rpf3TrackMeta>(60)

    val stationNamesByRpf: Map<String, String> = linkedMapOf(
        "radio_afro_beat.rpf" to "IF99 - International Funk",
        "radio_babylon.rpf" to "Tuff Gong Radio",
        "radio_beat_95.rpf" to "The Beat 102.7",
        "radio_bobby_konders.rpf" to "Massive B Soundsystem 96.9",
        "radio_classical_ambient.rpf" to "The Journey",
        "radio_dance_mix.rpf" to "Electro-Choc",
        "radio_dance_rock.rpf" to "Radio Broker",
        "radio_fusion_fm.rpf" to "Fusion FM",
        "radio_hardcore.rpf" to "Liberty City Hardcore",
        "radio_jazz_nation.rpf" to "JNR - Jazz Nation Radio 108.5",
        "radio_k109_the_studio.rpf" to "K109 The Studio",
        "radio_lazlow.rpf" to "Integrity 2.0",
        "radio_liberty_rock.rpf" to "Liberty Rock Radio 97.8",
        "radio_ny_classics.rpf" to "Classics 104.1",
        "radio_plr.rpf" to "Public Liberty Radio",
        "radio_san_juan_sounds.rpf" to "San Juan Sounds",
        "radio_the_vibe.rpf" to "The Vibe 98.8",
        "radio_vladivostok.rpf" to "Vladivostok FM",
        "radio_wktt.rpf" to "WKTT Talk Radio",
        "radio_vcfm.rpf" to "Vice City FM",
        "radio_ramjamfm.rpf" to "RamJam FM",
        "radio_meditation.rpf" to "Self-Actualization FM",
        "radio_vice.rpf" to "Vice City FM",
        "radio_ramjam.rpf" to "RamJam FM",
        "radio_self.rpf" to "Self-Actualization FM"
    )

    val eflcVirtualStationsEp1: Map<String, String> = linkedMapOf(
        "eflc_lchc" to "Liberty City Hardcore",
        "eflc_beat" to "The Beat 102.7"
    )

    val eflcVirtualStationsEp2: Map<String, String> = linkedMapOf(
        "eflc_electro_choc" to "Electro-Choc",
        "eflc_san_juan" to "San Juan Sounds",
        "eflc_vladivostok" to "Vladivostok FM"
    )

    val beatExtraTracks = setOf(
        "WHEREMY", "GETAWAY", "DIRTYNY", "PRICEONHEAD", "WETEMUP",
        "ITHOUGHT", "STICKEM", "WHATSTHEPROBLEM", "WARIS", "NICKNAME", "ANYBODYCAN"
    )

    val massiveBExtraTracks = setOf(
        "BADDERDEN", "SETITOFF", "REALMCKOY", "RAISEITUP",
        "DRIVER", "CALLPON", "YOUTHDEM", "ALLABOUTWEED", "NOFRAID",
        "CHURCH", "BULLETPROOF", "DAORDER", "LASTNIGHT", "BRRRT"
    )

    val electroChocExtraTracks = setOf(
        "SIGNALFAILURE", "TURNTORED", "MAKEIT", "OPTIMO", "TITS",
        "TESTAROSSA", "WATERSNAZARETH", "ANDDOWN", "BAGRAIDERS",
        "BRAINLEECH", "LETYOURBODY", "DEVILINUS", "NOPRESSURE"
    )

    val ramJamExtraTracks = setOf(
        "DONT_FUSS_NOR_FIGHT", "WORRIES_IN_THE_DANCE", "OUTTA_JAMAICA",
        "POLICE_IN_HELICOPTER", "JAMMYS_A_SHINE"
    )

    val eflcLibertyRockEp1Tracks = setOf(
        "00_HIGHWAYSTAR_PH", "CHINAGROVE_PH", "DEADORALIVE_PH", "DRIVINWHEEL_PH",
        "EVERYPICTURETELLS_PH", "FIVETOONE_PH", "FREERIDE_PH", "FUNKNUMBER49_PH",
        "GOTOHELL_PH", "HAIROFTHEDOG_PH", "JANE_PH", "LORDOFTHETHIGHS_PH",
        "RENEGADE_PH", "RUNTOTHEHILLS_PH", "SATURDAYNIGHTSPECIAL_PH", "TOUCHTOOMUCH",
        "WHEELOFSTEEL_PH", "WILDSIDE_PH"
    )

    val eflcRadioBrokerEp1Tracks = setOf(
        "BLOODONTHESTEPS_PH", "BODY_LANGUAGE_PH", "BORDERLINE_PH", "COMMAND_PH",
        "DANCEGIRL_PH", "GETREADYTODIE_PH", "HELLONWHEELS",
        "IWALKALONE_PH", "NOUVEAUAMERICIAN_PH", "RADICALBUSINESS_PH",
        "SHAKEITLOOSE_PH", "THEHUNGER"
    )

    val eflcLchcEp1Tracks = setOf(
        "LCHC_32K_PH", "CALL_FROM_THE_GRAVE", "DROWNED", "FEAR_OF_NAPALM",
        "I_CUM_BLOOD", "SLAUGHTER_OF_THE_SOUL"
    )

    val eflcBeatEp1Tracks = setOf(
        "E1_BEAT_MIX_P1", "E1_BEAT_MIX_P2", "HERE_IN_LIBERTY_CITY",
        "CAR_JACK", "THE_CHASE_IS_ON", "SPIT", "I_HEAR_FOOTSTEPS", "MY_FAVORITE_SONG"
    )

    val eflcWkttEp1Tracks = setOf("7_MARTINSERIOUSSHOW")
    val eflcWkttEp2Tracks = setOf("8_MONO_CONSPIRE_PART_1", "9_MONO_CONSPIRE_PART_2")

    val eflcIntegrityEp2Tracks = setOf(
        "INTEGRITY_MONO_5", "INTEGRITY_MONO_6", "INTEGRITY_MONO_7", "INTEGRITY_MONO_8"
    )

    val eflcK109Ep2Tracks = setOf(
        "ALOVERSHOLIDAY", "ANYLOVE", "BOOGIEOOGIE", "BUSSTOP",
        "DISCOINFERNO", "DOINTHEDOG", "EVERYBODYDANCE", "GREATESTDANCER",
        "INEEDYOU", "MENERGY", "PUTURBODYINIT", "RELIGHTMYFIRE",
        "SHAKEYOURGROOVETHING", "STILLINLOVE", "THEREBUTFORTHEGRACEOFGOD", "YOUNGHEARTSRUNFREE"
    )

    val eflcElectroChocEp2Tracks = setOf(
        "CROOKERS_MIX", "A_CITY_UNDER_SIEGE", "BOXER", "KID_CONGA",
        "KNOCK_YOU_OUT", "STICKIN", "THE_BEST_THING", "YOU_NEVER_KNOW"
    )

    val eflcVladivostokEp2Tracks = setOf("EUROBEAT_MIX")
    val eflcSanJuanEp2Tracks = setOf("SANTIAGO_SOUNDS", "GUALLANDO")

    val trackIdMetaMap: Map<Int, Rpf3TrackMeta>
        get() = trackIdMetaMapInternal

    @Synchronized
    fun ensureLoaded(context: Context? = AlbumArtDownloader.appContext) {
        if (loaded && trackMetaMap.isNotEmpty()) return
        val ctx = context ?: AlbumArtDownloader.appContext ?: return
        loaded = true
        try {
            ctx.assets.open("rpf3_hashes.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        val h = line.substring(0, eq).toUIntOrNull()?.toInt()
                        val name = line.substring(eq + 1).trim()
                        if (h != null && name.isNotEmpty()) {
                            hashToName[h] = name
                        }
                    }
                }
            }
            GTRadioLogger.log("Rpf3MetadataRepository: Loaded ${hashToName.size} RPF hashes")
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf3MetadataRepository: Failed loading rpf3_hashes.txt", e)
        }

        try {
            ctx.assets.open("metadata/rpf3_tracks.tsv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine() // skip header
                    while (true) {
                        line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        val parts = line.split('\t')
                        if (parts.size >= 4) {
                            val key = parts[0].uppercase()
                            val title = parts[1]
                            val artist = parts[2]
                            val isTalk = parts[3].toBoolean()
                            trackMetaMap[key] = Rpf3TrackMeta(key, title, artist, isTalk)
                        }
                    }
                }
            }
            ctx.assets.open("metadata/rpf3_trackids.tsv").use { inputStream ->
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
                            trackIdMetaMapInternal[tid] = Rpf3TrackMeta(tid.toString(), title, artist, false)
                        }
                    }
                }
            }
            GTRadioLogger.log("Rpf3MetadataRepository: Loaded ${trackMetaMap.size} tracks & ${trackIdMetaMapInternal.size} track IDs")
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf3MetadataRepository: Failed loading metadata/rpf3_*.tsv", e)
        }
    }

    fun resolveHash(context: Context? = null, hash: Int): String {
        ensureLoaded(context)
        return hashToName[hash] ?: String.format("0x%08X", hash)
    }

    fun getTrackMeta(context: Context? = null, rawTrackName: String, stationName: String = ""): Rpf3TrackMeta? {
        ensureLoaded(context)
        val cleanKey = rawTrackName.uppercase().trim()
        if (cleanKey == "MAMA" && stationName.uppercase().contains("VLADIVOSTOK")) {
            return Rpf3TrackMeta("MAMA", "Mama", "Basta")
        }
        if (cleanKey == "COCAINE" && stationName.uppercase().contains("BROKER")) {
            return Rpf3TrackMeta("COCAINE", "Cocaine", "Cheeseburger")
        }
        if (cleanKey == "LASTNIGHT" && stationName.uppercase().contains("ELECTRO")) {
            return Rpf3TrackMeta("LASTNIGHT", "I Thought Inside Out (Original Mix)", "Chris Lake vs. Deadmau5")
        }
        if (cleanKey == "ITHOUGHT" && stationName.uppercase().contains("ELECTRO")) {
            return Rpf3TrackMeta("ITHOUGHT", "I Thought Inside Out (Original Mix)", "Chris Lake vs. Deadmau5")
        }
        return trackMetaMap[cleanKey]
    }

    fun isLoaded(): Boolean = loaded && trackMetaMap.isNotEmpty()

    fun getTrackByTrackIdIfLoaded(trackId: Int): Rpf3TrackMeta? {
        if (trackId in setOf(0, 1, 153)) return null
        return trackIdMetaMapInternal[trackId]
    }

    fun getTrackByTrackId(trackId: Int, context: Context? = null): Rpf3TrackMeta? {
        if (trackId in setOf(0, 1, 153)) return null
        ensureLoaded(context)
        return trackIdMetaMapInternal[trackId]
    }

    fun getAllMusicSongs(context: Context? = null): List<Pair<String, String>> {
        ensureLoaded(context)
        val list = trackMetaMap.values
            .filter { !it.isTalkShow && it.artist.isNotBlank() && it.title.isNotBlank() }
            .map { Pair(it.artist, it.title) }
            .toMutableList()
        list.addAll(
            trackIdMetaMapInternal.values
                .filter { it.artist.isNotBlank() && it.title.isNotBlank() }
                .map { Pair(it.artist, it.title) }
        )
        return list.distinct()
    }
}
