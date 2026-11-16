package com.sc.gtradio.media.stations

data class Gen1StationInfo(
    val fileNameKey: String,
    val displayName: String,
    val hostName: String,
    val genre: String,
    val isTalkRadio: Boolean = false,
    val maxYear: Int = 2001
)

object Gen1MetadataRepository {

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
}
