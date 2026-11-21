package com.sc.gtradio.media.stations

import com.sc.gtradio.media.GTRadioPlayer

data class TracklistTrack(
    val id: String,
    val title: String,
    val artist: String,
    val stationName: String,
    val maxYear: Int
)

interface TracklistCapableStation : RadioStation {
    fun supportsTracklist(): Boolean
    fun getCleanTracks(): List<TracklistTrack>
    fun playCleanTrack(
        trackId: String,
        player: GTRadioPlayer,
        onTrackFinished: () -> Unit,
        onMetadataReady: (title: String, artist: String, album: String, maxYear: Int) -> Unit
    )
    fun stopCleanTrackPlayback()
}

object TracklistContract {
    const val ROOT_ID = "tracklist_root"
    const val GROUP_PREFIX = "tracklist_group::"
    const val STATION_PREFIX = "tracklist_station::"
    const val SONG_PREFIX = "tracklist_song::"
    const val ACTION_TOGGLE_SHUFFLE = "com.sc.gtradio.ACTION_TOGGLE_SHUFFLE"
    const val ACTION_CYCLE_REPEAT = "com.sc.gtradio.ACTION_CYCLE_REPEAT"
    const val REPEAT_MODE_NONE = 0
    const val REPEAT_MODE_ONE = 1
    const val REPEAT_MODE_ALL = 2
    const val EXTRA_PLAYBACK_MODE = "gtradio_playback_mode"
    const val EXTRA_SHUFFLE_ENABLED = "gtradio_shuffle_enabled"
    const val EXTRA_REPEAT_MODE = "gtradio_repeat_mode"
    const val EXTRA_STATION_ID = "gtradio_station_id"
    const val EXTRA_STATION_NAME = "station_name"
    const val EXTRA_STATION_TRACK_COUNT = "station_track_count"
    const val EXTRA_TRACK_ID = "gtradio_track_id"
    const val EXTRA_TRACK_TITLE = "track_title"
    const val EXTRA_TRACK_ARTIST = "track_artist"
    const val EXTRA_TRACK_ALBUM = "track_album"
    const val EXTRA_TRACK_MAX_YEAR = "track_max_year"
    const val EXTRA_TRACK_COUNT = "gtradio_track_count"
    const val EXTRA_TRACK_INDEX = "gtradio_track_index"
}
