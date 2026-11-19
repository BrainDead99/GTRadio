package com.sc.gtradio.media.stations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sc.gtradio.common.GTRadioLogger
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class GtaVStationInfo(
    val rpfName: String,
    val stationName: String,
    val djName: String,
    val isTalk: Boolean = false,
    val isContinuousMix: Boolean = false
)

data class GtaVTrackMeta(
    val internalName: String,
    val title: String,
    val artist: String,
    val isTalkShow: Boolean = false,
    val isContinuousMix: Boolean = false
)

object GtaVMetadataRepository {

    private val albumNameCache = ConcurrentHashMap<String, String>()
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val remasterBanRegex = Regex("remaster|deluxe|expanded|anniversary|reissue|greatest hits|best of|anthology|edition|live", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("^(\\d{4})")

    val stationInfoByRpf: Map<String, GtaVStationInfo> = linkedMapOf(
        "radio_01_class_rock.rpf" to GtaVStationInfo("radio_01_class_rock.rpf", "Los Santos Rock Radio", "Kenny Loggins", isTalk = false, isContinuousMix = false),
        "radio_02_pop.rpf" to GtaVStationInfo("radio_02_pop.rpf", "Non-Stop-Pop FM", "Cara Delevingne", isTalk = false, isContinuousMix = false),
        "radio_03_hiphop_new.rpf" to GtaVStationInfo("radio_03_hiphop_new.rpf", "Radio Los Santos", "Big Boy", isTalk = false, isContinuousMix = false),
        "radio_04_punk.rpf" to GtaVStationInfo("radio_04_punk.rpf", "Channel X", "Keith Morris", isTalk = false, isContinuousMix = false),
        "radio_05_talk_01.rpf" to GtaVStationInfo("radio_05_talk_01.rpf", "West Coast Talk Radio", "WCTR Talk Radio", isTalk = true, isContinuousMix = false),
        "radio_06_country.rpf" to GtaVStationInfo("radio_06_country.rpf", "Rebel Radio", "Jesco White", isTalk = false, isContinuousMix = false),
        "radio_07_dance_01.rpf" to GtaVStationInfo("radio_07_dance_01.rpf", "Soulwax FM", "Soulwax", isTalk = false, isContinuousMix = true),
        "radio_08_mexican.rpf" to GtaVStationInfo("radio_08_mexican.rpf", "East Los FM", "Don Cheto & Camilo Lara", isTalk = false, isContinuousMix = true),
        "radio_09_hiphop_old.rpf" to GtaVStationInfo("radio_09_hiphop_old.rpf", "West Coast Classics", "DJ Pooh", isTalk = false, isContinuousMix = false),
        "radio_11_talk_02.rpf" to GtaVStationInfo("radio_11_talk_02.rpf", "Blaine County Radio", "Blaine County Talk Radio", isTalk = true, isContinuousMix = false),
        "radio_12_reggae.rpf" to GtaVStationInfo("radio_12_reggae.rpf", "Blue Ark", "Lee \"Scratch\" Perry", isTalk = false, isContinuousMix = false),
        "radio_13_jazz.rpf" to GtaVStationInfo("radio_13_jazz.rpf", "Worldwide FM", "Gilles Peterson", isTalk = false, isContinuousMix = true),
        "radio_14_dance_02.rpf" to GtaVStationInfo("radio_14_dance_02.rpf", "FlyLo FM", "Flying Lotus", isTalk = false, isContinuousMix = true),
        "radio_15_motown.rpf" to GtaVStationInfo("radio_15_motown.rpf", "The Lowdown 91.1", "Pam Grier", isTalk = false, isContinuousMix = false),
        "radio_16_silverlake.rpf" to GtaVStationInfo("radio_16_silverlake.rpf", "Radio Mirror Park", "Twin Shadow", isTalk = false, isContinuousMix = false),
        "radio_17_funk.rpf" to GtaVStationInfo("radio_17_funk.rpf", "Space 103.2", "Bootsy Collins", isTalk = false, isContinuousMix = false),
        "radio_18_90s_rock.rpf" to GtaVStationInfo("radio_18_90s_rock.rpf", "Vinewood Boulevard Radio", "Nate Williams & Stephen Pope", isTalk = false, isContinuousMix = false)
    )

    private val trackMetaMap: Map<String, GtaVTrackMeta> = buildMap {
        fun song(key: String, title: String, artist: String) {
            val upper = key.uppercase(Locale.ROOT)
            put(upper, GtaVTrackMeta(upper, title, artist, isTalkShow = false, isContinuousMix = false))
        }

        fun talk(key: String, title: String, host: String) {
            val upper = key.uppercase(Locale.ROOT)
            put(upper, GtaVTrackMeta(upper, title, host, isTalkShow = true, isContinuousMix = false))
        }

        fun mix(key: String, title: String, artist: String) {
            val upper = key.uppercase(Locale.ROOT)
            put(upper, GtaVTrackMeta(upper, title, artist, isTalkShow = false, isContinuousMix = true))
        }

        // ====================================================================
        // RADIO_01_CLASS_ROCK.rpf - Los Santos Rock Radio
        // ====================================================================
        song("all_the_things_she_said", "All the Things She Said", "Simple Minds")
        song("baker_street", "Baker Street", "Gerry Rafferty")
        song("big_log", "Big Log", "Robert Plant")
        song("black_velvet", "Black Velvet", "Alannah Myles")
        song("burning_heart", "Burning Heart", "Survivor")
        song("carry_on_my_wayward_sun", "Carry On Wayward Son", "Kansas")
        song("cats_in_the_cradle", "Cat's in the Cradle", "Harry Chapin")
        song("circle_in_the_sand", "Circle in the Sand", "Belinda Carlisle")
        song("coming_on_strong", "Coming On Strong", "Broken English")
        song("danger_zone", "Danger Zone", "Kenny Loggins")
        song("dirty_white_boy", "Dirty White Boy", "Foreigner")
        song("fortunate_son", "Fortunate Son", "Creedence Clearwater Revival")
        song("gimme_all_your_lovin", "Gimme All Your Lovin'", "ZZ Top")
        song("heartbeat", "Heartbeat", "Don Johnson")
        song("higher_love", "Higher Love", "Steve Winwood")
        song("hollywood_nights", "Hollywood Nights", "Bob Seger & The Silver Bullet Band")
        song("i_cant_wait", "I Can't Wait", "Stevie Nicks")
        song("i_dont_care_anymore", "I Don't Care Anymore", "Phil Collins")
        song("i_wouldnt_want_to_be", "I Wouldn't Want to Be Like You", "The Alan Parsons Project")
        song("if_you_leave_me_now", "If You Leave Me Now", "Chicago")
        song("im_free", "I'm Free (Heaven Helps the Man)", "Kenny Loggins")
        song("lonely_is_the_night", "Lonely Is the Night", "Billy Squier")
        song("mississippi_queen", "Mississippi Queen", "Mountain")
        song("night_moves", "Night Moves", "Bob Seger & The Silver Bullet Band")
        song("ogdens_nut_gone_flake", "Ogdens' Nut Gone Flake", "Small Faces")
        song("peace_of_mind", "Peace of Mind", "Boston")
        song("photograph", "Photograph", "Def Leppard")
        song("radio_ga_ga", "Radio Ga Ga", "Queen")
        song("rain", "Rain", "The Cult")
        song("rockin_me", "Rock'n Me", "Steve Miller Band")
        song("roundabout", "Roundabout", "Yes")
        song("saturday_nights_alright", "Saturday Night's Alright for Fighting", "Elton John")
        song("shadows_of_the_night", "Shadows of the Night", "Pat Benatar")
        song("the_breakup_song", "The Breakup Song (They Don't Write 'Em)", "Greg Kihn Band")
        song("thirty_days_in_the_hole", "30 Days in the Hole", "Humble Pie")
        song("too_late_for_goodbyes", "Too Late for Goodbyes", "Julian Lennon")
        song("we_built_this_city", "We Built This City", "Starship")
        song("what_a_fool_believes", "What a Fool Believes", "The Doobie Brothers")

        // ====================================================================
        // RADIO_02_POP.rpf - Non-Stop-Pop FM
        // ====================================================================
        song("adult_education", "Adult Education", "Daryl Hall & John Oates")
        song("alright", "Alright", "Jamiroquai")
        song("anthem", "Anthem", "N-Joi")
        song("applause", "Applause", "Lady Gaga")
        song("bad_girls", "Bad Girls", "M.I.A.")
        song("cooler_than_me", "Cooler Than Me", "Mike Posner")
        song("days_go_by", "Days Go By", "Dirty Vegas")
        song("dont_wanna_fall_in_love", "Don't Wanna Fall in Love", "Jane Child")
        song("everything_she_wants", "Everything She Wants", "Wham!")
        song("feel_good_inc", "Feel Good Inc.", "Gorillaz feat. De La Soul")
        song("gimme_more", "Gimme More", "Britney Spears")
        song("glamorous", "Glamorous", "Fergie feat. Ludacris")
        song("i_want_it_that_way", "I Want It That Way", "Backstreet Boys")
        song("kids", "Kids", "Robbie Williams & Kylie Minogue")
        song("lady_hear_me_tonight", "Lady (Hear Me Tonight)", "Modjo")
        song("lets_go_all_the_way", "Let's Go All the Way", "Sly Fox")
        song("living_in_a_box", "Living in a Box", "Living in a Box")
        song("me_and_you", "Me & U", "Cassie")
        song("meet_me_halfway", "Meet Me Halfway", "The Black Eyed Peas")
        song("midnight_city", "Midnight City", "M83")
        song("moves_like_jagger", "Moves Like Jagger", "Maroon 5 feat. Christina Aguilera")
        song("music_sounds_better_with_you", "Music Sounds Better with You", "Stardust")
        song("new_sensation", "New Sensation", "INXS")
        song("on_our_own", "On Our Own", "Bobby Brown")
        song("one_thing", "1 Thing", "Amerie")
        song("only_girl_in_the_world", "Only Girl (In the World)", "Rihanna")
        song("promises_promises", "Promises, Promises", "Naked Eyes")
        song("pure_shores", "Pure Shores", "All Saints")
        song("rythm_of_the_night", "The Rhythm of the Night", "Corona")
        song("scandalous", "Scandalous", "Mis-Teeq")
        song("send_me_an_angel", "Send Me an Angel", "Real Life")
        song("six_underground", "6 Underground", "Sneaker Pimps")
        song("smalltown_boy", "Smalltown Boy", "Bronski Beat")
        song("something_got_me_started_remix", "Something Got Me Started (Hurley's House Mix)", "Simply Red")
        song("tape_loop", "Tape Loop", "Morcheeba")
        song("tell_to_my_heart", "Tell It to My Heart", "Taylor Dayne")
        song("tennis_court", "Tennis Court", "Lorde")
        song("the_time_is_now", "The Time Is Now", "Moloko")
        song("wait", "Wait", "Blow Monkeys feat. Kym Mazelle")
        song("west_end_girls", "West End Girls", "Pet Shop Boys")
        song("with_every_heartbeat", "With Every Heartbeat", "Robyn with Kleerup")
        song("work", "Work (Freemasons Remix)", "Kelly Rowland")

        // ====================================================================
        // RADIO_03_HIPHOP_NEW.rpf - Radio Los Santos
        // ====================================================================
        song("adhd", "A.D.H.D", "Kendrick Lamar")
        song("ali_bomaye", "Ali Bomaye", "The Game feat. 2 Chainz & Rick Ross")
        song("bad_news", "Bad News", "Danny Brown")
        song("bassheads", "Bassheads", "Gangrene")
        song("bugatti", "Bugatti", "Ace Hood feat. Future & Rick Ross")
        song("collard_greens", "Collard Greens", "Schoolboy Q feat. Kendrick Lamar")
        song("do_it_big", "Do It Big", "Problem & Iamsu! feat. Bad Lucc")
        song("easily", "Easily", "Chuck Inglish feat. Ab-Soul & Mac Miller")
        song("everyday", "Everyday", "Trouble feat. Gucci Mane")
        song("hold_up", "Hold Up", "Marion Band$ feat. Nipsey Hussle")
        song("hood_gone_love_it", "Hood Gone Love It", "Jay Rock feat. Kendrick Lamar")
        song("how_it_was", "How It Was", "DJ Esco feat. Future")
        song("hunnid_stax", "Hunnid Stax", "Ab-Soul feat. Schoolboy Q")
        song("i_cant_wait_scooter", "I Can't Wait", "Young Scooter feat. Trinidad James")
        song("illuminate", "ILLuminate", "Ab-Soul feat. Kendrick Lamar")
        song("im_a_real_one", "I'm a Real 1", "YG")
        song("kush_coma", "Kush Coma", "Danny Brown feat. A$AP Rocky & ZelooperZ")
        song("life_of_a_mack", "Life of a Mack", "100s")
        song("millions", "Millions", "T dot illy")
        song("r_cali", "R-Cali", "A$AP Rocky")
        song("relaxin", "Relaxin", "G-Side feat. G-Mane")
        song("say_that_then", "Say That Then", "Freddie Gibbs")
        song("sellin_dope", "Sellin' Dope", "Future")
        song("slow_down", "Slow Down", "Clyde Carson feat. The Team")
        song("smokin_and_ridin", "Smokin' and Ridin'", "BJ the Chicago Kid feat. Freddie Gibbs & Problem")
        song("still_livin", "Still Livin'", "Freddie Gibbs")
        song("swimming_pools", "Swimming Pools (Drank)", "Kendrick Lamar")
        song("too_hood", "Too Hood", "Gucci Mane feat. Ciara")
        song("upper_echelon", "Upper Echelon", "Travis Scott feat. T.I. & 2 Chainz")
        song("work_ferg", "Work", "A$AP Ferg")
        song("work_young_scooter", "Work", "Young Scooter feat. Gucci Mane")

        // ====================================================================
        // RADIO_04_PUNK.rpf - Channel X
        // ====================================================================
        song("amoeba", "Amoeba", "Adolescents")
        song("blown_away", "Blown Away", "Youth Brigade")
        song("bored_of_you", "Bored of You", "Agent Orange")
        song("dont_need_society", "Don't Need Society", "D.R.I.")
        song("dont_push_me_around", "Don't Push Me Around", "The Zeros")
        song("john_wayne", "John Wayne Was a Nazi", "MDC")
        song("lexicon_devil", "Lexicon Devil", "The Germs")
        song("life_of_crime", "Life of Crime", "The Weirdos")
        song("linda_blair", "Linda Blair", "Redd Kross")
        song("los_angeles", "Los Angeles", "X")
        song("my_war", "My War", "Black Flag")
        song("pervert", "Pervert", "Descendents")
        song("rock_house", "Rock House", "Circle Jerks")
        song("silent_majority", "Silent Majority", "T.S.O.L.")
        song("subliminal", "Subliminal", "Suicidal Tendencies")
        song("the_enemy", "The Enemy", "D.O.A.")
        song("the_mouth_dont_stop", "The Mouth Don't Stop (The Trouble with Women Is)", "Fear")
        song("whats_next", "What's Next", "OFF!")

        // ====================================================================
        // RADIO_05_TALK_01.rpf - West Coast Talk Radio
        // ====================================================================
        talk("mono_chakra_attack_part_1", "Chakra Attack (Part 1)", "Dr. Ray De Angelo Harris & Cheryl")
        talk("mono_dchakra_attack_part_2", "Chakra Attack (Part 2)", "Dr. Ray De Angelo Harris & Cheryl")
        talk("mono_chattersphere", "Chattersphere", "Lazlow & Michele Makes")
        talk("mono_fernando_show_1", "The Fernando Show", "Fernando Martinez & Jo")

        // ====================================================================
        // RADIO_06_COUNTRY.rpf - Rebel Radio
        // ====================================================================
        song("are_you_sure_hank", "Are You Sure Hank Done It This Way", "Waylon Jennings")
        song("cant_hardly_stand", "Can't Hardly Stand It", "Charlie Feathers")
        song("convoy", "Convoy", "C.W. McCall")
        song("crazy_arms", "Crazy Arms", "Ray Price")
        song("dippin_snuff", "Dippin' Snuff", "Wharton Falcon")
        song("divorce", "D-I-V-O-R-C-E", "Tammy Wynette")
        song("get_outta_my_car", "Get Outta My Car", "Mojo Nixon")
        song("get_with_it", "Get With It", "Charlie Feathers")
        song("highway_man", "Highwayman", "The Highwaymen")
        song("i_aint_living_long_like_this", "I Ain't Living Long Like This", "Waylon Jennings")
        song("if_want_to_get_heaven", "If You Wanna Get to Heaven", "Ozark Mountain Daredevils")
        song("it_dont_hurt_anymore", "It Don't Hurt Anymore", "Hank Thompson")
        song("it_wont_be_long_hating_you", "It Won't Be Long (And I'll Be Hating You)", "Johnny Paycheck")
        song("she_made_toothpicks_out_of_me", "She Made Toothpicks of the Timber of My Heart", "Homer and Jethro")
        song("the_general_lee", "The General Lee", "Johnny Cash")
        song("whiskey_river", "Whiskey River", "Willie Nelson")
        song("you_took_all_the_ramblin_out", "You Took All the Ramblin' Out of Me", "Jerry Reed")

        // ====================================================================
        // RADIO_07_DANCE_01.rpf - Soulwax FM
        // ====================================================================
        mix("soulwax_fm_final_mix_32k", "Soulwax FM Continuous Mix", "Soulwax")

        // ====================================================================
        // RADIO_08_MEXICAN.rpf - East Los FM
        // ====================================================================
        mix("mex_final_mix_32", "East Los FM Continuous Mix", "Don Cheto & Camilo Lara")

        // ====================================================================
        // RADIO_09_HIPHOP_OLD.rpf - West Coast Classics
        // ====================================================================
        song("afro_puffs", "Afro Puffs", "The Lady of Rage feat. Snoop Dogg")
        song("ambitionz_az_a_ridah", "Ambitionz Az a Ridah", "2Pac")
        song("appetite_for_destruction", "Appetite for Destruction", "N.W.A")
        song("ballad_of_a_menace", "Ballad of a Menace", "CPO")
        song("bow_down", "Bow Down", "Westside Connection")
        song("c_walk", "C-Walk", "Kurupt")
        song("captain_save_a_hoe", "Captain Save a Hoe", "E-40 feat. The Click")
        song("dollaz_and_sense", "Dollaz + Sense", "DJ Quik")
        song("first_of_the_month", "1st of Tha Month", "Bone Thugs-n-Harmony")
        song("gangsta_gangsta", "Gangsta Gangsta", "N.W.A")
        song("gin_and_juice", "Gin and Juice", "Snoop Dogg")
        song("i_got_five_on_it", "I Got 5 on It", "Luniz")
        song("late_night_hype", "Late Night Hype", "Compton's Most Wanted")
        song("like_a_piano", "Playaz Club", "Rappin' 4-Tay")
        song("mind_playing_tricks", "Mind Playing Tricks on Me", "Geto Boys")
        song("no_more_questions", "No More ?'s", "Eazy-E")
        song("nothin_but_the_cavi_hit", "Nothin' But the Cavi Hit", "Mack 10 & Tha Dogg Pound")
        song("servin_em_heat", "Servin 'Em Heat", "South Central Cartel")
        song("sherm_stick", "Sherm Stick", "Jayo Felony")
        song("so_you_want_to_be_a_gangster", "So You Want to Be a Gangster", "Too \$hort")
        song("still_d_r_e", "Still D.R.E.", "Dr. Dre feat. Snoop Dogg")
        song("straight_up_menace", "Straight Up Menace", "MC Eiht")
        song("the_murda_show", "The Murda Show", "Spice 1 feat. MC Eiht")
        song("the_next_episode", "The Next Episode", "Dr. Dre feat. Snoop Dogg")
        song("this_dj", "This D.J.", "Warren G")
        song("we_roll_deep", "We Roll Deep", "The Conscious Daughters")
        song("what_would_you_do", "What Would You Do?", "Tha Dogg Pound")
        song("what_you_wanna_do", "What You Wanna Do?", "Kausion")
        song("you_know_how_we_do", "You Know How We Do It", "Ice Cube")

        // ====================================================================
        // RADIO_11_TALK_02.rpf - Blaine County Radio
        // ====================================================================
        talk("mono_bcr_community_hour", "Blaine County Radio Community Hour", "Ronald Jakowski")
        talk("mono_beyond_insemination", "Beyond Insemination (Part 1)", "Duane Earl")
        talk("mono_zbeyond_insemination_part_2", "Beyond Insemination (Part 2)", "Duane Earl")
        talk("mono_bless_your_heart", "Bless Your Heart", "Bobby June & Ricky")

        // ====================================================================
        // RADIO_12_REGGAE.rpf - Blue Ark
        // ====================================================================
        song("addi_truth", "Addi Truth", "Vybz Kartel")
        song("chapter3", "Chapter 3", "Joe Gibbs & The Professionals")
        song("crazy_girl", "Crazy Girl", "Half Pint")
        song("disco_devil", "Disco Devil", "Lee \"Scratch\" Perry & The Full Experience")
        song("grumblin_dub", "Grumblin' Dub", "The Upsetters")
        song("gun_shot_a_fire", "Gun Shot A Fire", "Konshens")
        song("i_am_a_madman", "I Am a Madman", "Lee \"Scratch\" Perry")
        song("kingston_be_wise", "Kingston Be Wise", "Protoje")
        song("kingston_town", "Kingston Town", "Busy Signal feat. Damian Marley")
        song("loyals", "Loyals (Royals Remix)", "Demarco")
        song("money_come_money_go", "Money Come and Money Go", "Lee \"Scratch\" Perry")
        song("money_in_my_pocket", "Money in My Pocket", "Dennis Brown")
        song("mr_money_man", "Mr. Money Man", "Danny Hensworth")
        song("night_nurse", "Night Nurse", "Gregory Isaacs")
        song("nobody_move_get_hurt", "Nobody Move, Nobody Get Hurt", "Yellowman")
        song("odd_ras", "Odd Ras", "Chronixx")
        song("psycho", "Psycho", "Tommy Lee Sparta")
        song("roast_fish_and_cornbread", "Roast Fish & Cornbread", "Lee \"Scratch\" Perry")
        song("sons_of_slaves", "Sons of Slaves", "Junior Delgado")
        song("topic_of_the_day", "Topic of the Day", "I-Octane")
        song("we_never_fear_dem", "We Never Fear Dem", "Vybz Kartel feat. Popcaan")

        // ====================================================================
        // RADIO_13_JAZZ.rpf - Worldwide FM
        // ====================================================================
        mix("wwfm_p1", "Worldwide FM Mix (Part 1)", "Gilles Peterson")
        mix("wwfm_p2", "Worldwide FM Mix (Part 2)", "Gilles Peterson")
        mix("wwfm_p3", "Worldwide FM Mix (Part 3)", "Gilles Peterson")
        mix("wwfm_p4", "Worldwide FM Mix (Part 4)", "Gilles Peterson")

        // ====================================================================
        // RADIO_14_DANCE_02.rpf - FlyLo FM
        // ====================================================================
        mix("flylo_part1", "FlyLo FM Continuous Mix (Part 1)", "Flying Lotus")
        mix("flylo_part2", "FlyLo FM Continuous Mix (Part 2)", "Flying Lotus")

        // ====================================================================
        // RADIO_15_MOTOWN.rpf - The Lowdown 91.1
        // ====================================================================
        song("ashleys_roachclip", "Ashley's Roachclip", "The Soul Searchers")
        song("bouncy_lady", "Bouncy Lady", "Pleasure")
        song("california_soul", "California Soul", "Marlena Shaw")
        song("changin", "Changin'", "Brass Construction")
        song("climax", "Climax", "Ohio Players")
        song("cruisin", "Cruisin'", "Smokey Robinson")
        song("do_it_til_youre_satisfied", "Do It ('Til You're Satisfied)", "B.T. Express")
        song("funny_feeling", "Funny Feeling", "The Delfonics")
        song("hercules", "Hercules", "Aaron Neville")
        song("i_believe_in_miracles", "I Believe in Miracles", "Jackson Sisters")
        song("i_get_lifted", "I Get Lifted", "George McCrae")
        song("magic_mountain", "Magic Mountain", "War")
        song("o_o_h_child", "O-o-h Child", "The Five Stairsteps")
        song("ready_or_not", "Ready or Not Here I Come (Can't Hide from Love)", "The Delfonics")
        song("rubber_band", "Rubber Band", "The Trammps")
        song("smiling_faces", "Smiling Faces Sometimes", "The Undisputed Truth")
        song("stories", "Stories", "The Chakachas")
        song("superman_lover", "Superman Lover", "Johnny \"Guitar\" Watson")
        song("the_cisco_kid", "The Cisco Kid", "War")
        song("viva_tirado", "Viva Tirado", "El Chicano")

        // ====================================================================
        // RADIO_16_SILVERLAKE.rpf - Radio Mirror Park
        // ====================================================================
        song("always", "Always", "Panama")
        song("boogie_in_zero_gravity", "Boogie in Zero Gravity", "Black Strobe")
        song("change_of_coast", "Change of Coast", "Neon Indian")
        song("colours", "Colours", "Age of Consent")
        song("crystalfilm", "Crystalfilm", "Little Dragon")
        song("dark_matter", "Dark Matter", "The Feathers")
        song("do_you_believe", "Do You Believe", "Poolside")
        song("dont_come_close", "Don't Come Close", "Yeasayer")
        song("feel_the_same", "Feel the Same", "Battle Tapes")
        song("flutes", "Flutes", "Hot Chip")
        song("forget", "Forget", "Twin Shadow")
        song("from_nowhere", "From Nowhere (Baardsen Remix)", "Dan Croll")
        song("heart_in_the_pipes", "Heart in the Pipes (KAUF Remix)", "Tony Castles")
        song("heartbreak", "Heartbreak", "Age of Consent")
        song("high_pressure", "High Pressure Dave", "HEALTH")
        song("hold_on_holy_ghost", "Hold On", "Holy Ghost!")
        song("in_real_life", "In Real Life", "The New Division")
        song("jasmine", "Jasmine", "Jai Paul")
        song("little_white_lie", "Little White Lie", "Living Days")
        song("living_in_america", "Living in America", "DOM")
        song("lucky_boy", "Lucky Boy (Outlines Remix)", "DJ Mehdi")
        song("mesmerized", "Mesmerised", "Scenic")
        song("new_beat", "New Beat", "Toro y Moi")
        song("nowhere_to_go", "Nowhere to Go", "Nite Jewel")
        song("o_n_e", "O.N.E.", "Yeasayer")
        song("old_love", "Old Love / New Love", "Twin Shadow")
        song("one_girl_one_boy", "One Girl / One Boy", "!!! (Chk Chk Chk)")
        song("pharaohs", "Pharaohs & Pyramids", "Cut Copy")
        song("polish_girl", "Polish Girl", "Neon Indian")
        song("psychic_city", "Psychic City (Classixx Remix)", "YACHT")
        song("shine_a_light", "Shine a Light (Flight Facilities Remix)", "The C90s")
        song("shooting_holes", "Shooting Holes", "Twin Shadow")
        song("sleepwalking", "Sleepwalking", "The Chain Gang of 1974")
        song("so_many_details", "So Many Details", "Toro y Moi")
        song("sometimes", "Sometimes", "Miami Horror")
        song("strangers_in_the_wind", "Strangers in the Wind", "Cut Copy")
        song("the_drummer", "The Drummer", "Niki & The Dove")
        song("the_set_up", "The Set Up", "Favored Nations")
        song("truly_alive", "Truly Alive", "Mitzi")
        song("when_youre_out", "When You're Out", "KAUF")

        // ====================================================================
        // RADIO_17_FUNK.rpf - Space 103.2
        // ====================================================================
        song("back_and_forth", "Back and Forth", "Cameo")
        song("cant_hold_back", "Can't Hold Back (Your Lovin')", "Kano")
        song("cutie_pie", "Cutie Pie", "One Way")
        song("do_it_roger", "Do It Roger", "Roger")
        song("flashback", "Flashback", "Imagination")
        song("flashlight", "Flash Light", "Parliament")
        song("funkasize_you", "Funkasize You", "Sho-Nuff")
        song("give_it_to_me_baby", "Give It to Me Baby", "Rick James")
        song("gotta_get_my_hands_on_some_money", "Gotta Get My Hands (On Some Money)", "Fatback Band")
        song("haboglabotribin", "Haboglabotribin'", "Bernard Wright")
        song("heart_beat", "Heartbeat", "Taana Gardner")
        song("heartbreaker", "Heartbreaker (Part I & Part II)", "Zapp")
        song("im_in_love", "I'm in Love", "Evelyn \"Champagne\" King")
        song("joystick", "Joystick", "Dazz Band")
        song("mothership_connection", "Mothership Connection (Star Child)", "Parliament")
        song("nights_feel_like", "Nights (Feel Like Getting Down)", "Billy Ocean")
        song("party_all_the_time", "Party All the Time", "Eddie Murphy")
        song("skeletons", "Skeletons", "Stevie Wonder")
        song("tonight", "Tonight", "Kleeer")
        song("walking_into_sunshine", "Walking into Sunshine", "Central Line")
        song("youre_the_one_for_me", "You're the One for Me", "D-Train")

        // ====================================================================
        // RADIO_18_90S_ROCK.rpf - Vinewood Boulevard Radio
        // ====================================================================
        song("answer_to_yourself", "Answer to Yourself", "The Soft Pack")
        song("black_grease", "Black Grease", "The Black Angels")
        song("california_girls", "California Grrls", "Shark?")
        song("cocaine", "Cocaine", "FIDLAR")
        song("crawling_after_you", "Crawling After You", "Bass Drum of Death")
        song("diddy_wah_diddy", "Diddy Wah Diddy", "Ty Segall Band")
        song("fall_in_line", "Fall in Line", "Mind Spiders")
        song("fire_doesnt_burn_itself", "Fire Doesn't Burn Itself", "Sam Flax")
        song("gone_for_good", "Gone for Good", "Nobunny")
        song("hysteria", "Hysteria", "Ceremony")
        song("next_stop", "Next Stop", "Bleached")
        song("nine_is_god", "Nine Is God", "Wavves")
        song("sixpack", "Sixpack", "JEFF the Brotherhood")
        song("sleepwalker", "Sleepwalker", "Moon Duo")
        song("the_dream", "The Dream", "Thee Oh Sees")
        song("this_mystic_decade", "This Mystic Decade", "Hot Snakes")
        song("turn_it_around", "Turn It Around", "The Men")
        song("used_blood", "Used Blood", "METZ")
        song("wet_blanket", "Wet Blanket", "METZ")
        song("who_needs_you", "Who Needs You", "The Orwells")
    }

    fun getAllMusicSongs(): List<Pair<String, String>> {
        return trackMetaMap.values
            .filter { !it.isTalkShow && !it.isContinuousMix }
            .map { Pair(it.artist, it.title) }
            .distinct()
    }

    fun getStationInfo(rpfName: String): GtaVStationInfo? {
        val lower = rpfName.lowercase(Locale.ROOT)
        return stationInfoByRpf[lower] ?: stationInfoByRpf["${lower}.rpf"]
    }

    fun getTrackMeta(internalName: String, fallbackStationTitle: String = ""): GtaVTrackMeta {
        val cleanStem = internalName.removeSuffix(".awc").uppercase(Locale.ROOT)
        val direct = trackMetaMap[cleanStem]
        if (direct != null) return direct

        // Fallback: title-case formatting from snake_case
        val formattedTitle = formatSnakeCase(internalName)
        val defaultArtist = fallbackStationTitle.ifBlank { "GTA V Radio" }
        return GtaVTrackMeta(cleanStem, formattedTitle, defaultArtist, isTalkShow = false, isContinuousMix = false)
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

    private fun cacheKey(artist: String, songTitle: String): String {
        val raw = "${artist.lowercase(Locale.ROOT).trim()}_${songTitle.lowercase(Locale.ROOT).trim()}"
        return raw.replace(Regex("[^a-z0-9_]"), "_").take(80)
    }

    fun getCachedAlbumArt(context: Context, artist: String, songTitle: String): Bitmap? {
        val key = cacheKey(artist, songTitle)
        val f = File(File(context.filesDir, "era_album_art"), "$key.jpg")
        return if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
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

    fun fetchEraAccurateArtAsync(
        context: Context,
        artist: String,
        songTitle: String,
        maxYear: Int = 2013,
        onDone: (() -> Unit)? = null
    ) {
        val key = cacheKey(artist, songTitle)
        val dir = File(context.filesDir, "era_album_art").apply { mkdirs() }
        val imgFile = File(dir, "$key.jpg")
        if (imgFile.exists() && imgFile.length() > 0) return

        bgExecutor.submit {
            try {
                val success = fetchFromItunesEraFiltered(artist, songTitle, dir, key, maxYear)
                if (success) onDone?.invoke()
            } catch (_: Exception) {}
        }
    }

    fun fetchEraAccurateArtSync(
        context: Context,
        artist: String,
        songTitle: String,
        maxYear: Int = 2013
    ): Boolean {
        val key = cacheKey(artist, songTitle)
        val dir = File(context.filesDir, "era_album_art").apply { mkdirs() }
        val imgFile = File(dir, "$key.jpg")
        if (imgFile.exists() && imgFile.length() > 0) return true
        return fetchFromItunesEraFiltered(artist, songTitle, dir, key, maxYear)
    }

    private fun fetchFromItunesEraFiltered(
        artist: String,
        songTitle: String,
        dir: File,
        key: String,
        maxYear: Int
    ): Boolean {
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
                if (year <= maxYear) score += 50 else score -= 50
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
            GTRadioLogger.log("GtaVMetadataRepository: iTunes art fetch error for $artist - $songTitle", e)
        }
        return false
    }
}
