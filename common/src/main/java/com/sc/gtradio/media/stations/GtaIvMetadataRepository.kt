package com.sc.gtradio.media.stations

import android.content.Context
import com.sc.gtradio.common.GTRadioLogger

data class GtaIvTrackMeta(
    val internalName: String,
    val title: String,
    val artist: String,
    val isTalkShow: Boolean = false
)

object GtaIvMetadataRepository {
    private var loaded = false
    private val hashToName = HashMap<Int, String>(80000)
    private val trackMetaMap = HashMap<String, GtaIvTrackMeta>(600)

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
        "ITHOUGHT", "STICKEM", "WHATSTHEPROBLEM", "WARIS", "NICKNAME", "DAORDER"
    )

    val massiveBExtraTracks = setOf(
        "BADDERDEN", "SETITOFF", "REALMCKOY", "RAISEITUP", "BRAINLEECH",
        "DRIVER", "CALLPON", "YOUTHDEM", "ALLABOUTWEED", "NOFRAID",
        "ANYBODYCAN", "CHURCH", "BULLETPROOF"
    )

    val electroChocExtraTracks = setOf(
        "SIGNALFAILURE", "TURNTORED", "MAKEIT", "OPTIMO", "TITS",
        "TESTAROSSA", "WATERSNAZARETH", "ANDDOWN", "BAGRAIDERS",
        "BRRRT", "LETYOURBODY", "LASTNIGHT", "DEVILINUS", "NOPRESSURE"
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

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            context.assets.open("gta_iv_hashes.txt").bufferedReader().useLines { lines ->
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
            GTRadioLogger.log("GtaIvMetadataRepository: Loaded ${hashToName.size} RPF hashes")
        } catch (e: Exception) {
            GTRadioLogger.log("GtaIvMetadataRepository: Failed loading gta_iv_hashes.txt", e)
        }
        initTrackMetadata()
    }

    fun resolveHash(context: Context, hash: Int): String {
        ensureLoaded(context)
        return hashToName[hash] ?: String.format("0x%08X", hash)
    }

    fun getTrackMeta(context: Context, rawTrackName: String, stationName: String = ""): GtaIvTrackMeta? {
        ensureLoaded(context)
        val cleanKey = rawTrackName.uppercase().trim()
        if (cleanKey == "MAMA" && stationName.uppercase().contains("VLADIVOSTOK")) {
            return GtaIvTrackMeta("MAMA", "Mama", "Basta")
        }
        if (cleanKey == "COCAINE" && stationName.uppercase().contains("BROKER")) {
            return GtaIvTrackMeta("COCAINE", "Cocaine", "Cheeseburger")
        }
        return trackMetaMap[cleanKey]
    }

    fun getAllMusicSongs(context: Context): List<Pair<String, String>> {
        ensureLoaded(context)
        return trackMetaMap.values
            .filter { !it.isTalkShow && it.artist.isNotBlank() && it.title.isNotBlank() }
            .map { Pair(it.artist, it.title) }
            .distinct()
    }

    private fun addSong(key: String, title: String, artist: String) {
        trackMetaMap[key.uppercase()] = GtaIvTrackMeta(key.uppercase(), title, artist, false)
    }

    private fun addShow(key: String, title: String, host: String) {
        trackMetaMap[key.uppercase()] = GtaIvTrackMeta(key.uppercase(), title, host, true)
    }

    private fun initTrackMetadata() {
        // IF99 - International Funk
        addSong("ACHANCEFORPEACE", "A Chance for Peace", "Lonnie Liston Smith")
        addSong("GALAXY", "Galaxy", "War")
        addSong("GIVETHEPEOPLE", "Give the People What They Want", "The O'Jays")
        addSong("HOMEISWHERE", "Home Is Where the Hatred Is", "Gil Scott-Heron")
        addSong("JUSTKISSEDMYBABY", "Just Kissed My Baby", "The Meters")
        addSong("LIVINITUP", "Livin' It Up", "Mandrill")
        addSong("NEWBELL", "New Bell", "Manu Dibango")
        addSong("ZOMBIE", "Zombie", "Fela Kuti")
        addSong("SORROWTEARSBLOOD", "Sorrow, Tears & Blood", "Fela Kuti")
        addSong("TRUTHDONTDIE", "Truth Don Die", "Femi Kuti")
        addSong("WHOISHEANDWHATIS", "Who Is He and What Is He to You", "Creative Source")
        addSong("CRYINFORLOVE", "You Can't Hide Love", "Hummingbird")

        // Tuff Gong Radio
        addSong("CHASEDEM", "Chase Dem", "Stephen Marley")
        addSong("CONCRETEJUNGLE", "Concrete Jungle", "Bob Marley & The Wailers")
        addSong("PIMPERSPARADISE", "Pimper's Paradise", "Bob Marley & The Wailers")
        addSong("RATRACE", "Rat Race", "Bob Marley & The Wailers")
        addSong("REBELMUSIC", "Rebel Music (3 O'Clock Roadblock)", "Bob Marley & The Wailers")
        addSong("SATISFYMYSOUL", "Satisfy My Soul", "Bob Marley & The Wailers")
        addSong("SOMUCHTROUBLE", "So Much Trouble in the World", "Bob Marley & The Wailers")
        addSong("STANDUPJAMROCKREMIX", "Stand Up Jamrock (Ashley Beedle Remix)", "Bob Marley & The Wailers & Damian Marley")
        addSong("WAKEUPANDLIVE", "Wake Up and Live", "Bob Marley & The Wailers")

        // The Journey
        addSong("ARRIVAL", "5:23 (Maiden Voyage)", "Global Communication")
        addSong("ARAINBOWINCURVEDAIR", "A Rainbow in Curved Air", "Terry Riley")
        addSong("RESOLVE", "Arrival", "Steve Roach")
        addSong("COMMUNIQUE", "Communiqué: 'Approach Spiral'", "Michael Shrieve")
        addSong("OXYGENE", "Oxygène (Part IV)", "Jean-Michel Jarre")
        addSong("PRUITIGOE", "Pruit Igoe", "Philip Glass")
        addSong("REMOTEVIEWING", "Remote Viewing", "Tangerine Dream")
        addSong("OOFPLEASURE", "The Oh of Pleasure", "Ray Lynch")
        addSong("ZTWIG", "Selected Ambient Works Vol. II CD2 TRK5 (Z Twig)", "Aphex Twin")
        addSong("EIGHTMAIDENVOYAGE", "8:07", "Global Communication")

        // Radio Broker (GTA IV + TLAD)
        addSong("ARMINARM", "Arm in Arm (Shy Child Remix)", "The Boggs")
        addSong("COCAINE", "Cocaine", "Eric Clapton")
        addSong("DISNEYLAND", "Disneyland, Pt 1", "Get Shakes")
        addSong("GETINNOCUOUS", "Get Innocuous!", "LCD Soundsystem")
        addSong("HOMICIDE", "Homicide", "The Prairie Cartel")
        addSong("INSIDETHECAGE", "Inside the Cage (David Gilmour Girls Remix)", "Juliette and the Licks")
        addSong("MAYDAY", "Mayday", "Unkle feat. The Duke Spirit")
        addSong("NOSEXFORBEN", "No Sex for Ben", "The Rapture")
        addSong("ONEHORSERACE", "One Horse Race", "Tom Vek")
        addSong("TEENAGER", "Pony", "Teenager")
        addSong("RAGINGINTHEPLAGUE", "Raging in the Plague Age", "Les Savy Fav")
        addSong("RIOTINTHECITY", "Riot in the City", "White Light Parade")
        addSong("SLEEPISIMPOSSIBLE", "Sleep Is Impossible", "Deluka")
        addSong("STRANGETIMES", "Strange Times", "The Black Keys")
        addSong("THETEACHER", "Take It With a Kiss", "The Pistolas")
        addSong("VAGABOND", "Vagabond", "Greenskeepers")
        addSong("WRAPITUP", "Wrap It Up", "Whitey")
        addSong("YADNUS", "Yadnus (Still Going to the Roadhouse Mix)", "!!! (Chk Chk Chk)")
        addSong("ONANDON", "On and On", "Ralph Myerz")
        addSong("BLOODONTHESTEPS_PH", "Blood on the Steps", "The Yelling")
        addSong("BODY_LANGUAGE_PH", "Body Language", "Monotonix")
        addSong("BORDERLINE_PH", "Borderline", "Freeland")
        addSong("COMMAND_PH", "Command", "Foxylane")
        addSong("DANCEGIRL_PH", "Dance Girl (GTA Mix)", "Game Rebellion")
        addSong("GETREADYTODIE_PH", "Get Ready to Die", "Magic Dirt")
        addSong("HELLONWHEELS", "Hell on Wheels", "Kill Memory Crash")
        addSong("I_HEAR_FOOTSTEPS", "I Hear Footsteps", "Consequence")
        addSong("IWALKALONE_PH", "I Walk Alone", "The Jane Shermans")
        addSong("MY_FAVORITE_SONG", "My Favorite Song", "Talib Kweli")
        addSong("NOUVEAUAMERICIAN_PH", "Nouveau Americain", "Brazilian Girls")
        addSong("RADICALBUSINESS_PH", "Radical Businessman", "Japanther")
        addSong("SHAKEITLOOSE_PH", "Shake It Loose", "Blonde Acid Cult")
        addSong("THEHUNGER", "The Hunger (Blood in My Mouth)", "Kreeps")

        // Fusion FM
        addSong("THEEDGE", "The Edge", "David McCallum")
        addSong("FUNKINTHEHOLE", "Funk in the Hole", "Roy Ayers")
        addSong("HEAVYTUNE", "Heavy Tune", "Gong")
        addSong("HOLYTHURSDAY", "Holy Thursday", "David Axelrod")
        addSong("KNUCKLEHEAD", "Knucklehead", "Grover Washington, Jr.")
        addSong("POKUSA", "Pokusa", "Aleksander Maliszewski")
        addSong("RASINS", "Raisins", "Ryo Kawasaki")
        addSong("SNEAKININTHEBACK", "Sneakin' in the Back", "Tom Scott & The L.A. Express")
        addSong("STRATUS", "Stratus", "Billy Cobham")
        addSong("STOMP", "Stomp", "Marc Moulin")

        // Liberty City Hardcore (LCHC - GTA IV + TLAD)
        addSong("ALLYOURBOYZ", "All Your Boyz", "Maximum Penalty")
        addSong("BACKTOBACK", "Back to Back", "Underdog")
        addSong("INJUSTICESYSTEM", "Injustice System!", "Sick of It All")
        addSong("ITSTHELIMIT", "It's the Limit", "Cro-Mags")
        addSong("JUSTCANTHATE", "Just Can't Hate Enough", "Sheer Terror")
        addSong("RIGHTBRIGADE", "Right Brigade", "Bad Brains")
        addSong("TELLTALE", "Tell Tale", "Killing Time")
        addSong("VICTIMINPAIN", "Victim in Pain", "Agnostic Front")
        addSong("ENFORCER", "Enforcer", "Leeway")
        addSong("DAYINTHELIFE", "A Day in the Life", "Murphy's Law")
        addShow("LCHC_32K_PH", "LCHC Extreme Metal Mix", "Max Cavalera")
        addSong("CALL_FROM_THE_GRAVE", "Call from the Grave", "Bathory")
        addSong("DROWNED", "Drowned", "Entombed")
        addSong("FEAR_OF_NAPALM", "Fear of Napalm", "Terrorizer")
        addSong("I_CUM_BLOOD", "I Cum Blood", "Cannibal Corpse")
        addSong("SLAUGHTER_OF_THE_SOUL", "Slaughter of the Soul", "At the Gates")
        addSong("SPIT", "Spit", "Saigon")

        // JNR - Jazz Nation Radio 108.5
        addSong("APRILINPARIS", "April in Paris", "Count Basie")
        addSong("GIANTSTEPS", "Giant Steps", "John Coltrane")
        addSong("LETSGETLOST", "Let's Get Lost", "Chet Baker")
        addSong("MOANIN", "Moanin'", "Art Blakey and the Jazz Messengers")
        addSong("MORE", "More Than You Know", "Miles Davis")
        addSong("NIGHTANDDAY", "Night and Day", "Charlie Parker")
        addSong("SNAPCRACKLE", "Snap Crackle", "Roy Haynes")
        addSong("STTHOMAS", "St. Thomas", "Sonny Rollins")
        addSong("TAKINTHEATRAIN", "Take the 'A' Train", "Duke Ellington")
        addSong("WHISPERNOT", "Whisper Not", "Dizzy Gillespie")

        // K109 The Studio (GTA IV + TBoGT)
        addSong("BURNINGLOVE", "Burning Love Breakdown", "Peter Brown")
        addSong("CANTLIVEWITHOUTYOURLOVE", "Can't Live Without Your Love", "Tamiko Jones")
        addSong("DANCER", "Dancer", "Gino Soccio")
        addSong("GETONUP", "Get On Up and Do It Again", "Suzy Q")
        addSong("ONAJOURNEY", "On a Journey", "Electrik Funk")
        addSong("STANDINGINTHERAIN", "Standing in the Rain", "Don Ray")
        addSong("SUPERNATURE", "Supernature", "Cerrone")
        addSong("TILLYOUSURRENDER", "Till You Surrender", "Rainbow Brown")
        addSong("UNDERWATER", "Underwater", "Harry Thumann")
        addSong("WALKTHENIGHT", "Walk the Night", "Skatt Bros.")
        addSong("ALOVERSHOLIDAY", "A Lover's Holiday", "Change")
        addSong("ANYLOVE", "Any Love", "Rufus & Chaka Khan")
        addSong("BOOGIEOOGIE", "Boogie Oogie Oogie", "A Taste of Honey")
        addSong("BUSSTOP", "Bus Stop", "The Fatback Band")
        addSong("DISCOINFERNO", "Disco Inferno", "The Trammps")
        addSong("DOINTHEDOG", "Doin' the Dog", "Creme D'Cocoa")
        addSong("EVERYBODYDANCE", "Everybody Dance", "Chic")
        addSong("GREATESTDANCER", "He's the Greatest Dancer", "Sister Sledge")
        addSong("INEEDYOU", "I Need You", "Sylvester")
        addSong("MENERGY", "Menergy", "Patrick Cowley")
        addSong("PUTURBODYINIT", "Put Your Body in It", "Stephanie Mills")
        addSong("RELIGHTMYFIRE", "Relight My Fire", "Dan Hartman")
        addSong("SHAKEYOURGROOVETHING", "Shake Your Groove Thing", "Peaches & Herb")
        addSong("STILLINLOVE", "Still in Love", "Rose Royce")
        addSong("THEREBUTFORTHEGRACEOFGOD", "There But for the Grace of God Go I", "Machine")
        addSong("YOUNGHEARTSRUNFREE", "Young Hearts Run Free", "Candi Staton")

        // Liberty Rock Radio 97.8 (GTA IV + TLAD)
        addSong("1979", "1979", "The Smashing Pumpkins")
        addSong("CRY", "Cry", "Godley & Creme")
        addSong("DOMINION", "Dominion / Mother Russia", "The Sisters of Mercy")
        addSong("EDGEOFSEVENTEEN", "Edge of Seventeen", "Stevie Nicks")
        addSong("EVILWOMAN", "Evil Woman", "Electric Light Orchestra")
        addSong("FASCINATION", "Fascination", "David Bowie")
        addSong("STREETKIDS", "Street Kids", "Elton John")
        addSong("GOODBYEHORSES", "Goodbye Horses", "Q Lazzarus")
        addSong("HEAVENANDHELL", "Heaven and Hell", "Black Sabbath")
        addSong("HERSTRUT", "Her Strut", "Bob Seger & The Silver Bullet Band")
        addSong("IWANNABEYOURDOG", "I Wanna Be Your Dog", "The Stooges")
        addSong("JAILBREAK", "Jailbreak", "Thin Lizzy")
        addSong("MAMA", "Mama", "Genesis")
        addSong("NEWYORKGROOVE", "New York Groove", "Hello")
        addSong("ONEVISION", "One Vision", "Queen")
        addSong("REMEDY", "Remedy", "The Black Crowes")
        addSong("ROCKYMOUNTAINWAY", "Rocky Mountain Way", "Joe Walsh")
        addSong("STRAIGHTON", "Straight On", "Heart")
        addSong("THESEEKER", "The Seeker", "The Who")
        addSong("THUG", "Thug", "ZZ Top")
        addSong("TURNYOUINSIDEOUT", "Turn You Inside-Out", "R.E.M.")
        addSong("00_HIGHWAYSTAR_PH", "Highway Star", "Deep Purple")
        addSong("CHINAGROVE_PH", "China Grove", "The Doobie Brothers")
        addSong("DEADORALIVE_PH", "Wanted Dead or Alive", "Bon Jovi")
        addSong("DRIVINWHEEL_PH", "Drivin' Wheel", "Foghat")
        addSong("EVERYPICTURETELLS_PH", "Every Picture Tells a Story", "Rod Stewart")
        addSong("FIVETOONE_PH", "Five to One", "The Doors")
        addSong("FREERIDE_PH", "Free Ride", "The Edgar Winter Group")
        addSong("FUNKNUMBER49_PH", "Funk #49", "James Gang")
        addSong("GOTOHELL_PH", "Go to Hell", "Alice Cooper")
        addSong("HAIROFTHEDOG_PH", "Hair of the Dog", "Nazareth")
        addSong("JANE_PH", "Jane", "Jefferson Starship")
        addSong("LORDOFTHETHIGHS_PH", "Lord of the Thighs", "Aerosmith")
        addSong("RENEGADE_PH", "Renegade", "Styx")
        addSong("RUNTOTHEHILLS_PH", "Run to the Hills", "Iron Maiden")
        addSong("SATURDAYNIGHTSPECIAL_PH", "Saturday Night Special", "Lynyrd Skynyrd")
        addSong("TOUCHTOOMUCH", "Touch Too Much", "AC/DC")
        addSong("WHEELOFSTEEL_PH", "Wheels of Steel", "Saxon")
        addSong("WILDSIDE_PH", "Wild Side", "Mötley Crüe")

        // San Juan Sounds (GTA IV + TBoGT)
        addSong("ATREVETETETE", "Atrévete-Te-Te", "Calle 13")
        addSong("IMPACTO", "Impacto", "Daddy Yankee")
        addSong("MALDADES", "Maldades", "Hector El Father")
        addSong("PONMELA", "Pónmela", "Voltio feat. Jowell & Randy")
        addSong("SALIOELSOL", "Salió El Sol", "Don Omar")
        addSong("SEXYMOVIMIENTO", "Sexy Movimiento", "Wisin & Yandel")
        addSong("SIENTEELBOOM", "Siente El Boom (Remix)", "Tito El Bambino")
        addSong("VENBAILALO", "Ven Báilalo", "Angel & Khriz")
        addShow("SANTIAGO_SOUNDS", "San Juan Sounds Mix", "Henry Santos Jeter")
        addSong("GUALLANDO", "Guallando", "Fulanito")

        // The Vibe 98.8
        addSong("BECAUSEOFYOU", "Because of You", "Ne-Yo")
        addSong("BUMPNGRIND", "Bump n' Grind", "R. Kelly")
        addSong("COD", "C.O.D. (I'll Deliver)", "Mtume")
        addSong("CRITICIZE", "Criticize", "Alexander O'Neal")
        addSong("DAYLIGHT", "Daylight", "Ramp")
        addSong("FOOTSTEPS", "Footsteps in the Dark", "The Isley Brothers")
        addSong("FREEKNYOU", "Freek'n You", "Jodeci")
        addSong("GETITSHAWTY", "Get It Shawty", "Lloyd")
        addSong("GOLDEN", "Golden", "Jill Scott")
        addSong("HANGINONASTRING", "Hangin' on a String (Contemplating)", "Loose Ends")
        addSong("HAVEYOUEVERLOVED", "Have You Ever Loved Somebody", "Freddie Jackson")
        addSong("INMYBED", "In My Bed (So So Def Mix)", "Dru Hill")
        addSong("INNERCITYBLUES", "Inner City Blues (Make Me Wanna Holler)", "Marvin Gaye")
        addSong("INSIDEMYLOVE", "Inside My Love", "Minnie Riperton")
        addSong("ITSONLYLOVE", "It's Only Love Doing Its Thing", "Barry White")
        addSong("IWANTYOU", "I Want You", "C.J.")
        addSong("JUSTBEGOODTOME", "Just Be Good to Me", "The S.O.S. Band")
        addSong("PONY", "Pony", "Ginuwine")
        addSong("YOU", "You", "Raheem DeVaughn")

        // Vladivostok FM (GTA IV + TBoGT)
        addSong("GNISVOULINIU", "Gni Svoyu Liniyu", "Splean")
        addSong("KINO", "Gruppa Krovi", "Kino")
        addSong("KINGRING", "King Ring", "Seryoga")
        addSong("KVARTIRA", "Kvartira", "Zveri")
        addSong("LIBERTYCITY", "Liberty City: The Invasion", "Seryoga")
        addSong("NIKOGONE", "Nikogo Ne Zhalko", "Leningrad")
        addSong("OTEBE", "O Tebe", "Ranetki")
        addSong("REP", "Rap", "Dolphin")
        addSong("SHVAYNE", "Schweine", "Glukoza")
        addSong("WILD_HORSES", "Wild Dances", "Ruslana")
        addSong("ZELENOGLAZOE_TAKSI", "Zelenoglazoe Taksi (Club Remix)", "Oleg Kvasha")
        addSong("ZHDAT", "Zhdat", "Marakesh")
        addShow("EUROBEAT_MIX", "Vladivostok FM Club Mix", "Paul Oakenfold")

        // Extra Tracks (The Beat 102.7, Massive B, Electro-Choc, RamJam FM)
        addSong("WHEREMY", "Where's My Money", "Busta Rhymes")
        addSong("GETAWAY", "Get Away", "Mobb Deep")
        addSong("DIRTYNY", "Dirty New Yorker", "Mobb Deep")
        addSong("PRICEONHEAD", "Price on Your Head", "Johnny Polygon")
        addSong("WETEMUP", "Wet 'Em Up", "Tru-Life")
        addSong("ITHOUGHT", "I Thought", "Red Café")
        addSong("STICKEM", "Stick'm", "Red Café")
        addSong("WHATSTHEPROBLEM", "What's the Problem", "Styles P")
        addSong("WARIS", "Anybody Can Get It", "Uncle Murda")
        addSong("NICKNAME", "Nickname", "Qadir")
        addSong("DAORDER", "Getaway Driver", "Maino")
        addShow("E1_BEAT_MIX_P1", "The Beat 102.7 Mix", "Funkmaster Flex")
        addShow("E1_BEAT_MIX_P2", "The Beat 102.7 Mix", "Statik Selektah")
        addSong("HERE_IN_LIBERTY_CITY", "Here in Liberty City", "Termanology")
        addSong("CAR_JACK", "Car Jack", "Freeway")
        addSong("THE_CHASE_IS_ON", "The Chase Is On", "Skyzoo")

        addSong("BADDERDEN", "Badder Den Dem", "Burro Banton")
        addSong("SETITOFF", "Set It Off", "Choppa Chop")
        addSong("REALMCKOY", "Real McKoy", "Mavado")
        addSong("RAISEITUP", "Raise It Up", "Jabba")
        addSong("BRAINLEECH", "Brain Leech (Bugged Mind Remix)", "Alex Gopher")
        addSong("DRIVER", "Driver A", "Buju Banton")
        addSong("CALLPON", "Call Pon Dem", "Chezidek")
        addSong("YOUTHDEM", "Youth Dem Cold", "Richie Spice")
        addSong("ALLABOUTWEED", "All About da Weed", "Chuck Fenda")
        addSong("NOFRAID", "Last Night", "Mavado")
        addSong("ANYBODYCAN", "Anybody Can Get It", "Uncle Murda")
        addSong("CHURCH", "Church Heathen", "Shaggy")
        addSong("BULLETPROOF", "Bullet Proof Skin", "Bounty Killer")

        addSong("SIGNALFAILURE", "Signal Failure", "Padded Cell")
        addSong("TURNTORED", "Turn to Red", "Killing Joke")
        addSong("MAKEIT", "Make It Happen", "Playgroup")
        addSong("OPTIMO", "Optimo", "Liquid Liquid")
        addSong("TITS", "No Pressure (Deadmau5 Remix)", "One + One")
        addSong("TESTAROSSA", "Testarossa Autodrive (SebastiAn Remix)", "Kavinsky")
        addSong("WATERSNAZARETH", "Waters of Nazareth", "Justice")
        addSong("ANDDOWN", "& Down", "Boys Noize")
        addSong("BAGRAIDERS", "Fun Punch", "Bag Raiders")
        addSong("BRRRT", "Brrr", "Modeselektor")
        addSong("LETYOURBODY", "Let Your Body Learn", "Nitzer Ebb")
        addSong("LASTNIGHT", "Theicked", "Chris Lake")
        addSong("DEVILINUS", "The Devil in Us (Dub)", "Make Model")
        addSong("NOPRESSURE", "No Pressure", "One + One")
        addShow("CROOKERS_MIX", "Electro-Choc Mix", "Crookers")
        addSong("A_CITY_UNDER_SIEGE", "A City Under Siege", "Boy 8-Bit")
        addSong("BOXER", "Boxer", "Crookers feat. Nic Sarno")
        addSong("KID_CONGA", "Jump Up", "Major Lazer feat. Leftside & Supahype")
        addSong("KNOCK_YOU_OUT", "Knock You Out (Andy George Remix)", "Miike Snow")
        addSong("STICKIN", "Stickin", "SonicC")
        addSong("THE_BEST_THING", "The Best Thing", "Hook N Sling")
        addSong("YOU_NEVER_KNOW", "You Never Know (Maral Salmassi Re-Edit)", "Maral Salmassi")

        // Vice City FM (EFLC)
        addSong("BUFFALOSTANCE", "Buffalo Stance", "Neneh Cherry")
        addSong("BREAKOUT", "Breakout", "Swing Out Sister")
        addSong("CESTLAVIE", "C'est La Vie", "Robbie Nevil")
        addSong("CUDDLYTOY", "Cuddly Toy", "Roachford")
        addSong("DIVINEEMOTIONS", "Divine Emotions", "Narada Michael Walden")
        addSong("FINDTHETIME", "Find the Time", "Five Star")
        addSong("HEARTANDSOUL", "Heart and Soul", "T'Pau")
        addSong("HISTORY", "History", "Mai Tai")
        addSong("ICANTWAIT", "I Can't Wait", "Nu Shooz")
        addSong("IDONTWANTALOVER", "I Don't Want a Lover", "Texas")
        addSong("KAYLEIGH", "Kayleigh", "Marillion")
        addSong("LABOUROFLOVE", "Labour of Love", "Hue and Cry")
        addSong("LOVECHANGES", "Love Changes (Everything)", "Climie Fisher")
        addSong("MANEATER", "Maneater", "Hall & Oates")
        addSong("MISFIT", "Misfit", "Curiosity Killed the Cat")
        addSong("PEOPLEHOLDON", "People Hold On", "Coldcut feat. Lisa Stansfield")
        addSong("POLITICSOFDANCING", "The Politics of Dancing", "Re-Flex")
        addSong("SOMETHINGABOUTYOU", "Something About You", "Level 42")
        addSong("STAYWITHMETONIGHT", "Stay with Me Tonight", "Jeffrey Osborne")
        addSong("TEARDROPS", "Teardrops", "Womack & Womack")
        addSong("THELOOK", "The Look", "Roxette")
        addSong("VOICESCARRY", "Voices Carry", "'Til Tuesday")
        addSong("WAITINGFORASTAR", "Waiting for a Star to Fall", "Boy Meets Girl")
        addSong("WHENLOVEBREAKSDOWN", "When Love Breaks Down", "Prefab Sprout")
        addSong("WISHINGIWASLUCKY", "Wishing I Was Lucky", "Wet Wet Wet")
        addSong("WISHINGWELL", "Wishing Well", "Terence Trent D'Arby")
        addSong("WOODBEEZ", "Wood Beez (Pray Like Aretha Franklin)", "Scritti Politti")
        addSong("YOURETHEVOICE", "You're the Voice", "John Farnham")

        // RamJam FM (EFLC)
        addShow("RAMJAM_MIX", "RamJam FM Mix", "David Rodigan")
        addSong("DONT_FUSS_NOR_FIGHT", "Don't Fuss Nor Fight", "Barrington Levy")
        addSong("WORRIES_IN_THE_DANCE", "Worries in the Dance", "Frankie Paul")
        addSong("OUTTA_JAMAICA", "Outta Jamaica", "Ini Kamoze")
        addSong("POLICE_IN_HELICOPTER", "Police in Helicopter", "John Holt")
        addSong("JAMMYS_A_SHINE", "Jammy's A Shine", "Super Cat")

        // Self-Actualization FM (EFLC)
        addSong("ARTIFACTS", "Artifacts & Prophecies", "The Orb")
        addSong("BIKE", "Bike", "Autechre")
        addSong("COSMOGONICMYTH", "A Cosmogonic Myth", "Larry Fast")
        addSong("GOFORWARD", "Go Forward (Love Bubble Mix)", "Chilled by Nature")
        addSong("MOONBATHING", "Moonbathing", "Tom Middleton")
        addSong("ORB", "Little Fluffy Clouds", "The Orb")
        addSong("SKYGAZER", "Skygazer (3002 Remix)", "Pete Namlook & Klaus Schulze feat. Bill Laswell")
        addSong("V8PSYCHEDELICBRUNCH", "V8 Psychedelic Brunch", "Pete Namlook")

        // Continuous DJ Mixes (GTA IV)
        addShow("BEAT", "The Beat 102.7 Mix", "Mister Cee & DJ Green Lantern")
        addShow("MASSIVEB_MIX", "Massive B Soundsystem Mix", "Bobby Konders")
        addShow("FK", "Electro-Choc Mix", "François K")
        addShow("CLASSICS_MIX", "Classics 104.1 Mix", "DJ Premier")

        // Talk Radio Shows (WKTT, PLR, Integrity 2.0 - GTA IV + EFLC)
        addShow("1_UNJUST_1", "Just or Unjust", "Judge Grady")
        addShow("4_UNJUST_2", "Just or Unjust", "Judge Grady")
        addShow("2_FIZZ_1", "Fizz!", "Jane Labrador & Marcel")
        addShow("5_FIZZ_2", "Fizz!", "Jane Labrador & Marcel")
        addShow("3_CONSERVATIVE_1", "The Richard Bastion Show", "Richard Bastion")
        addShow("6_CONSERVATIVE_3", "The Richard Bastion Show", "Richard Bastion")
        addShow("7_MARTINSERIOUSSHOW", "The Martin Serious Show", "Martin Serious")
        addShow("8_MONO_CONSPIRE_PART_1", "Conspire (Part 1)", "John Smith")
        addShow("9_MONO_CONSPIRE_PART_2", "Conspire (Part 2)", "John Smith")
        addShow("PACEMAKER", "Pacemaker", "Ryan McFallon")
        addShow("INTELLAGENDA", "Intelligent Agenda", "Mike Riley")
        addShow("THE_SEANCE", "The Séance", "Beatrix Fontaine")
        addShow("INTEGRITY_01", "Integrity 2.0", "Lazlow")
        addShow("INTEGRITY_02", "Integrity 2.0", "Lazlow")
        addShow("INTEGRITY_03", "Integrity 2.0", "Lazlow")
        addShow("INTEGRITY_04", "Integrity 2.0", "Lazlow")
        addShow("INTEGRITY_MONO_5", "Integrity 2.0", "Lazlow & Jorge")
        addShow("INTEGRITY_MONO_6", "Integrity 2.0", "Lazlow & Jorge")
        addShow("INTEGRITY_MONO_7", "Integrity 2.0", "Lazlow & Jorge")
        addShow("INTEGRITY_MONO_8", "Integrity 2.0", "Lazlow & Jorge")
    }
}
