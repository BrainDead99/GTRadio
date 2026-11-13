package com.sc.gtradio.media.stations

import GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.preference.PreferenceManager
import java.util.*

@UnstableApi class SaRawRadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaBrowserCompat.MediaItem,
    private val stationArchive: SaStreamArchive,
    private val advertsArchive: SaStreamArchive?,
    private val stationMeta: SaStationMeta,
    private val player: GTRadioPlayer,
    private val context: Context,
    override var adsEnabled: Boolean,
    private var _weatherChatterEnabled: Boolean
) : RadioStation {

    override var newsReportsEnabled: Boolean = false
    override var onMetadataChanged: ((MediaMetadataCompat) -> Unit)? = null

    override var weatherChatterEnabled: Boolean
        get() = _weatherChatterEnabled
        set(enabled) {
            if (enabled != _weatherChatterEnabled) {
                _weatherChatterEnabled = enabled
                randomizeDJTracks()
            }
        }

    override var stationName: String = stationMeta.stationName
    private val stationTitle: String = (mediaItem.description.title ?: stationMeta.stationName).toString()
    private val groupTitle: String = (mediaItem.description.subtitle ?: "GTA San Andreas").toString()
    private val stationLogo: Bitmap? = mediaItem.description.iconBitmap
    private val isWctr: Boolean = stationMeta.streamCode == "TK" || stationTitle.uppercase().contains("WCTR")
    private val mainHandler = Handler(Looper.getMainLooper())

    private var _currentMetadata: MediaMetadataCompat = buildStationMetadata(stationTitle, "", stationLogo)
    override val metadata: MediaMetadataCompat
        get() = _currentMetadata

    private var advertTracks: IntArray = IntArray(0)
    private var announcerTracks: IntArray = stationMeta.idTracks.toIntArray()
    private var currentDjTracks: IntArray = IntArray(0)
    private var songs: Array<SaSongGroup> = stationMeta.songGroups.toTypedArray()

    private var advertIdx = 0
    private var announcerIdx = 0
    private var djIdx = 0
    private var songIdx = 0

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var songsSinceLastBreak = 0
    private var activeListener: Player.Listener? = null
    private val rng = Random()

    // Real-time radio state tracking
    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongGroup: SaSongGroup? = null
    private var lastStoppedTimeMs: Long = 0L
    private var stoppedWindowIndex: Int = 0
    private var stoppedPositionMs: Long = 0L
    private val durationCache = HashMap<Uri, Long>()
    private val episodeRegex = Regex("\\s*(?:-\\s*)?(?:Episode|Teaser)\\s*#?\\d+.*$", RegexOption.IGNORE_CASE)

    init {
        if (advertsArchive != null) {
            val adMeta = SaMetadataRepository.getByStreamCode(context, "ADVERTS")
            advertTracks = (adMeta?.tracks?.keys ?: advertsArchive.getTrackNumbers()).toIntArray()
        }
        randomizeAll()
    }

    private fun isMetadataEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("show_song_metadata_enabled", true)
    }

    private fun isAlbumArtEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("download_album_art_enabled", true)
    }

    private fun buildStationMetadata(
        trackTitle: String,
        trackSubtitle: String,
        art: Bitmap?,
        isSongOrShow: Boolean = false,
        cleanArtist: String = "",
        cleanAlbum: String = ""
    ): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaItem.mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, trackTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, trackSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, cleanArtist.ifBlank { trackSubtitle })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, cleanAlbum)
            .putString(MediaMetadataCompat.METADATA_KEY_WRITER, stationTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "$stationTitle • $groupTitle")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, if (isSongOrShow) 1L else 0L)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            .build()
    }

    private fun setStationOnlyMetadata() {
        _currentMetadata = buildStationMetadata(stationTitle, "", stationLogo)
        onMetadataChanged?.invoke(_currentMetadata)
    }

    private fun resolveWctrHost(cleanShowTitle: String, rawTitle: String): String {
        val upper = cleanShowTitle.uppercase()
        return when {
            upper.contains("AREA 53") -> "Marvin Trill"
            upper.contains("ENTERTAINING AMERICA") -> {
                if (rawTitle.uppercase().contains("EPISODE #1") || rawTitle.uppercase().contains("EPISODE 1")) "Billy Dexter" else "Lazlow"
            }
            upper.contains("GARDENING WITH MAURICE") -> "Maurice"
            upper.contains("I SAY") -> "Peyton Phillips & Mary Phillips"
            upper.contains("LONELY HEARTS") -> "Christy MacDonald"
            upper.contains("TIGHT END ZONE") -> "Derrick Thackery"
            upper.contains("WILD TRAVELER") -> "James Pedeaston"
            else -> ""
        }
    }

    private fun applySongMetadata(song: SaSongGroup) {
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }

        if (isWctr) {
            val cleanShow = episodeRegex.replace(song.cleanTitle, "").trim().ifBlank { song.cleanTitle }
            if (cleanShow.uppercase().startsWith("NEWS")) {
                _currentMetadata = buildStationMetadata("News", stationTitle, stationLogo, isSongOrShow = true, cleanArtist = stationTitle, cleanAlbum = "")
            } else {
                val host = resolveWctrHost(cleanShow, song.cleanTitle)
                val subtitle = if (host.isNotBlank()) "$host • $stationTitle" else stationTitle
                _currentMetadata = buildStationMetadata(cleanShow, subtitle, stationLogo, isSongOrShow = true, cleanArtist = host.ifBlank { stationTitle }, cleanAlbum = "")
            }
            onMetadataChanged?.invoke(_currentMetadata)
            return
        }

        var art: Bitmap? = stationLogo
        if (isAlbumArtEnabled()) {
            val cached = SaMetadataRepository.getCachedAlbumArt(context, song.artist, song.cleanTitle)
            if (cached != null) {
                art = cached
            } else {
                SaMetadataRepository.fetchEraAccurateArtAsync(context, song.artist, song.cleanTitle) {
                    mainHandler.post {
                        if (_playing && lastSegment == RadioSegmentType.Song && currentSongGroup == song) {
                            val newArt = SaMetadataRepository.getCachedAlbumArt(context, song.artist, song.cleanTitle) ?: stationLogo
                            val sub = buildSongSubtitle(song)
                            val alb = SaMetadataRepository.getCachedAlbumName(context, song.artist, song.cleanTitle) ?: ""
                            _currentMetadata = buildStationMetadata(song.cleanTitle, sub, newArt, isSongOrShow = true, cleanArtist = song.artist, cleanAlbum = alb)
                            onMetadataChanged?.invoke(_currentMetadata)
                        }
                    }
                }
            }
        }

        val subtitle = buildSongSubtitle(song)
        val eraAlbum = SaMetadataRepository.getCachedAlbumName(context, song.artist, song.cleanTitle) ?: ""
        _currentMetadata = buildStationMetadata(song.cleanTitle, subtitle, art, isSongOrShow = true, cleanArtist = song.artist, cleanAlbum = eraAlbum)
        onMetadataChanged?.invoke(_currentMetadata)
    }

    private fun buildSongSubtitle(song: SaSongGroup): String {
        val eraAlbum = SaMetadataRepository.getCachedAlbumName(context, song.artist, song.cleanTitle)
        return if (!eraAlbum.isNullOrBlank()) {
            "${song.artist} • $eraAlbum"
        } else {
            "${song.artist} • $stationTitle"
        }
    }

    private fun refreshCurrentSegmentMetadata() {
        if (lastSegment == RadioSegmentType.Song && currentSongGroup != null) {
            applySongMetadata(currentSongGroup!!)
        } else {
            setStationOnlyMetadata()
        }
    }

    private fun randomizeAll() {
        advertTracks.shuffle()
        announcerTracks.shuffle()
        songs.shuffle()
        randomizeDJTracks()
    }

    private fun randomizeDJTracks() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val list = ArrayList<Int>(stationMeta.djOtherTracks)
        if (hour in 3..11) list.addAll(stationMeta.djMorningTracks)
        else if (hour in 17..22) list.addAll(stationMeta.djEveningTracks)
        else if (hour < 3 || hour >= 23) list.addAll(stationMeta.djNightTracks)

        if (weatherChatterEnabled) {
            list.addAll(stationMeta.djWeatherTracks)
        }
        currentDjTracks = list.toIntArray()
        currentDjTracks.shuffle()
        djIdx = 0
    }

    private fun nextFrom(arr: IntArray, idxSetter: (Int) -> Unit, curIdx: Int): Int {
        var i = curIdx
        if (i >= arr.size) {
            arr.shuffle()
            i = 0
        }
        val v = arr[i]
        idxSetter(i + 1)
        return v
    }

    private fun getFileDurationMs(uri: Uri): Long {
        durationCache[uri]?.let { return it }
        var dur = 30_000L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 30_000L
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        durationCache[uri] = dur
        return dur
    }

    override fun stop() {
        if (_playing) {
            lastStoppedTimeMs = System.currentTimeMillis()
            stoppedWindowIndex = player.getCurrentWindowIndex().coerceAtLeast(0)
            stoppedPositionMs = player.getCurrentTrackPosition().coerceAtLeast(0L)
        }
        _playing = false
        if (activeListener != null) {
            player.removeListener(activeListener!!)
            activeListener = null
        }
        player.stop()
    }

    override fun play() {
        _playing = true
        player.radioPlaybackState = PlaybackStateCompat.STATE_PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

        if (lastStoppedTimeMs > 0L && currentSegmentUris.isNotEmpty()) {
            val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
            resumeFromRealTime(elapsedMs)
        } else {
            pickNextSegment()
            stoppedWindowIndex = 0
            stoppedPositionMs = 0L
            resumeFromRealTime(rng.nextInt(45_000).toLong())
        }
    }

    private fun resumeFromRealTime(rawElapsedMs: Long) {
        var remainingMs = if (rawElapsedMs > 600_000L) (rawElapsedMs % 600_000L) else rawElapsedMs
        var winIdx = stoppedWindowIndex.coerceIn(0, (currentSegmentUris.size - 1).coerceAtLeast(0))
        var posMs = stoppedPositionMs.coerceAtLeast(0L)

        var safety = 0
        while (safety < 40 && currentSegmentUris.isNotEmpty()) {
            safety++
            for (i in winIdx until currentSegmentUris.size) {
                val uri = currentSegmentUris[i]
                val durMs = getFileDurationMs(uri)
                val timeLeftMs = (durMs - posMs).coerceAtLeast(0L)
                if (remainingMs < timeLeftMs) {
                    refreshCurrentSegmentMetadata()
                    startPlayback(currentSegmentUris, i, posMs + remainingMs)
                    return
                } else {
                    remainingMs -= timeLeftMs
                    posMs = 0L
                }
            }
            pickNextSegment()
            winIdx = 0
            posMs = 0L
        }

        if (currentSegmentUris.isNotEmpty()) {
            refreshCurrentSegmentMetadata()
            startPlayback(currentSegmentUris, 0, 0L)
        }
    }

    private fun playNext() {
        if (!_playing) return
        pickNextSegment()
        if (currentSegmentUris.isNotEmpty()) {
            refreshCurrentSegmentMetadata()
            startPlayback(currentSegmentUris, 0, 0L)
        }
    }

    private fun pickNextSegment() {
        var attempts = 0
        while (attempts < 15) {
            attempts++
            when (lastSegment) {
                RadioSegmentType.None -> lastSegment = RadioSegmentType.fromInt(rng.nextInt(5))
                RadioSegmentType.Commercial -> {
                    if (announcerTracks.isNotEmpty() && rng.nextInt(20) < 17) {
                        if (prepareAnnouncerSegment()) return
                    } else if (prepareSongSegment(true)) return
                }
                RadioSegmentType.Announcer -> {
                    if (rng.nextInt(10) < 7 || currentDjTracks.isEmpty()) {
                        if (prepareSongSegment(true)) return
                    } else if (prepareDjSegment()) return
                }
                RadioSegmentType.DJChatter -> {
                    if (songsSinceLastBreak == 0) {
                        if (prepareSongSegment()) return
                    } else {
                        val threshold = rng.nextInt(3) + 3
                        if (adsEnabled && advertTracks.isNotEmpty() && threshold <= songsSinceLastBreak) {
                            if (prepareAdvertSegment()) return
                        } else if (prepareSongSegment()) return
                    }
                }
                RadioSegmentType.Song -> {
                    val threshold = rng.nextInt(3) + 3
                    if (adsEnabled && advertTracks.isNotEmpty() && threshold <= songsSinceLastBreak) {
                        if (prepareAdvertSegment()) return
                    } else {
                        val choice = if (currentDjTracks.isEmpty() && announcerTracks.isEmpty()) 0
                        else if (currentDjTracks.isEmpty()) rng.nextInt(2)
                        else if (announcerTracks.isEmpty()) (if (rng.nextInt(2) == 0) 0 else 2)
                        else rng.nextInt(3)
                        when (choice) {
                            0 -> if (prepareSongSegment()) return
                            1 -> if (prepareAnnouncerSegment()) return
                            else -> if (prepareDjSegment()) return
                        }
                    }
                }
                else -> lastSegment = RadioSegmentType.fromInt(rng.nextInt(5) + 1)
            }
        }
    }

    private fun prepareAnnouncerSegment(): Boolean {
        lastSegment = RadioSegmentType.Announcer
        if (announcerTracks.isEmpty()) return false
        val tr = nextFrom(announcerTracks, { announcerIdx = it }, announcerIdx)
        val uri = stationArchive.materializeTrackUri(tr) ?: return false
        currentSongGroup = null
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareDjSegment(): Boolean {
        lastSegment = RadioSegmentType.DJChatter
        if (currentDjTracks.isEmpty()) return false
        val tr = nextFrom(currentDjTracks, { djIdx = it }, djIdx)
        val uri = stationArchive.materializeTrackUri(tr) ?: return false
        currentSongGroup = null
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareAdvertSegment(): Boolean {
        lastSegment = RadioSegmentType.Commercial
        songsSinceLastBreak = 0
        if (!adsEnabled || advertsArchive == null || advertTracks.isEmpty()) return false
        val tr = nextFrom(advertTracks, { advertIdx = it }, advertIdx)
        val uri = advertsArchive.materializeTrackUri(tr) ?: return false
        currentSongGroup = null
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareSongSegment(requireDjIntro: Boolean = false): Boolean {
        songsSinceLastBreak += 1
        lastSegment = RadioSegmentType.Song
        if (songs.isEmpty()) return false
        if (songIdx >= songs.size) {
            songs.shuffle()
            songIdx = 0
        }
        val song = songs[songIdx++]
        val mainUri = stationArchive.materializeTrackUri(song.midTrack) ?: return false

        val introTrack = if (requireDjIntro && song.djIntroTracks.isNotEmpty()) {
            song.djIntroTracks[rng.nextInt(song.djIntroTracks.size)]
        } else if (rng.nextInt(11) < 6 && song.djIntroTracks.isNotEmpty()) {
            song.djIntroTracks[rng.nextInt(song.djIntroTracks.size)]
        } else {
            song.cleanIntroTrack
        }

        val outroTrack = if (rng.nextInt(11) < 6 && song.djOutroTracks.isNotEmpty()) {
            song.djOutroTracks[rng.nextInt(song.djOutroTracks.size)]
        } else {
            song.cleanOutroTrack
        }

        val list = ArrayList<Uri>(3)
        if (introTrack != null) stationArchive.materializeTrackUri(introTrack)?.let { list.add(it) }
        list.add(mainUri)
        if (outroTrack != null) stationArchive.materializeTrackUri(outroTrack)?.let { list.add(it) }

        currentSongGroup = song
        currentSegmentUris = list
        return true
    }

    private fun startPlayback(uris: List<Uri>, startWindowIndex: Int, startPositionMs: Long) {
        if (activeListener != null) {
            player.removeListener(activeListener!!)
            activeListener = null
        }

        val factory = ProgressiveMediaSource.Factory(
            DefaultDataSourceFactory(context, Util.getUserAgent(context, context.packageName))
        )
        val concatSource = ConcatenatingMediaSource(true)
        for (u in uris) {
            concatSource.addMediaSource(factory.createMediaSource(MediaItem.fromUri(u)))
        }
        player.setMediaSource(concatSource)
        if (startWindowIndex > 0 || startPositionMs > 0L) {
            player.seekTo(startWindowIndex, startPositionMs)
        }

        activeListener = object : Player.Listener {
            private var alreadyHandled = false
            override fun onPlaybackStateChanged(state: Int) {
                if (!alreadyHandled && state == Player.STATE_ENDED) {
                    alreadyHandled = true
                    player.removeListener(this)
                    playNext()
                }
            }
        }
        player.addListener(activeListener!!)
        player.prepare()
        player.play()
    }
}
