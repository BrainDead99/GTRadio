package com.sc.gtradio.media.stations

import GTRadioPlayer
import android.content.Context
import androidx.preference.PreferenceManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.sc.gtradio.common.FileUtils.listSimpleFiles
import java.util.*

@UnstableApi class Gen2RadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaBrowserCompat.MediaItem,
    baseStationFolderDoc: DocumentFile,
    advertsFolderDoc: DocumentFile,
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
                randomizeDJFiles()
            }
        }

    private val stationTitle: String = (mediaItem.description.title ?: "").toString()
    private val groupTitle: String = (mediaItem.description.subtitle ?: "").toString()
    private val stationLogo: Bitmap? = mediaItem.description.iconBitmap
    private val isWctr: Boolean = stationTitle.uppercase().contains("WCTR")

    private var _currentMetadata: MediaMetadataCompat = buildStationMetadata(
        trackTitle = stationTitle,
        trackSubtitle = "",
        art = stationLogo
    )

    override val metadata: MediaMetadataCompat
        get() = _currentMetadata

    override var stationName: String = ""

    private var advertFiles: Array<Uri> = emptyArray()
    private var announcerFiles: Array<Uri> = emptyArray()
    private var djMorningFiles: Array<Uri> = emptyArray()
    private var djEveningFiles: Array<Uri> = emptyArray()
    private var djNightFiles: Array<Uri> = emptyArray()
    private var djOtherFiles: Array<Uri> = emptyArray()
    private var djWeatherFogFiles: Array<Uri> = emptyArray()
    private var djWeatherRainFiles: Array<Uri> = emptyArray()
    private var djWeatherSunFiles: Array<Uri> = emptyArray()
    private var djWeatherStormFiles: Array<Uri> = emptyArray()
    private var songs: Array<Song> = emptyArray()
    private var currentDJFiles: Array<Uri> = emptyArray()

    private var advertIterator: Iterator<Uri>? = null
    private var announcerIterator: Iterator<Uri>? = null
    private var djIterator: Iterator<Uri>? = null
    private var songIterator: Iterator<Song>? = null

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var songsSinceLastBreak = 0
    private var activeListener: Player.Listener? = null
    private val rng = Random()

    // Real-time radio state tracking
    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongMainUri: Uri? = null
    private var currentSongFolderName: String = ""
    private var lastStoppedTimeMs: Long = 0L
    private var stoppedWindowIndex: Int = 0
    private var stoppedPositionMs: Long = 0L
    private val durationCache = HashMap<Uri, Long>()

    private val suffixRegex = Regex("\\s*\\((?:Mid|Intro(?:\\s*DJ\\s*#?\\d+)?|Outro(?:\\s*DJ\\s*#?\\d+)?)\\)\\s*$", RegexOption.IGNORE_CASE)
    private val episodeRegex = Regex("\\s*(?:-\\s*)?(?:Episode|Teaser)\\s*#?\\d+.*$", RegexOption.IGNORE_CASE)
    private val stationSuffixRegex = Regex("\\s*\\([^)]*\\)\\s*$")

    init {
        stationName = baseStationFolderDoc.name ?: ""
        val subDirs = baseStationFolderDoc.listFiles().filter { x -> x.isDirectory }
        for (subDir in subDirs) {
            when (subDir.name?.uppercase()) {
                "ANNOUNCER", "IDS" -> setupAnnouncerFiles(subDir)
                "DJ CHATTER" -> setupDJFiles(subDir)
                "SONGS" -> setupSongs(subDir)
            }
        }
        setupAdvertFiles(advertsFolderDoc)
        randomizeAll()
    }

    private fun getFileDurationMs(uri: Uri): Long {
        durationCache[uri]?.let { return it }
        var dur = 30_000L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val parsed = durStr?.toLongOrNull()
            if (parsed != null && parsed > 0L) {
                dur = parsed
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        durationCache[uri] = dur
        return dur
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
        _currentMetadata = buildStationMetadata(
            trackTitle = stationTitle,
            trackSubtitle = "",
            art = stationLogo
        )
        onMetadataChanged?.invoke(_currentMetadata)
    }

    private fun resolveWctrHost(cleanShowTitle: String, rawTitle: String, rawArtist: String?): String {
        val upper = cleanShowTitle.uppercase()
        return when {
            upper.contains("AREA 53") -> "Marvin Trill"
            upper.contains("ENTERTAINING AMERICA") -> {
                if (rawTitle.uppercase().contains("EPISODE #1") || rawTitle.uppercase().contains("EPISODE 1")) {
                    "Billy Dexter"
                } else {
                    "Lazlow"
                }
            }
            upper.contains("GARDENING WITH MAURICE") -> "Maurice"
            upper.contains("I SAY") -> "Peyton Phillips & Mary Phillips"
            upper.contains("LONELY HEARTS") -> "Christy MacDonald"
            upper.contains("TIGHT END ZONE") -> "Derrick Thackery"
            upper.contains("WILD TRAVELER") -> "James Pedeaston"
            else -> {
                val cleaned = stationSuffixRegex.replace(rawArtist ?: "", "").trim()
                if (cleaned.isNotBlank() && !cleaned.equals("WCTR Talk Radio", ignoreCase = true)) cleaned else ""
            }
        }
    }

    private fun updateSongMetadataFromUri(uri: Uri, fallbackTitle: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("show_song_metadata_enabled", true)) {
            setStationOnlyMetadata()
            return
        }
        val allowAlbumArt = prefs.getBoolean("download_album_art_enabled", true)
        var rawTitle = fallbackTitle
        var rawArtist: String? = null
        var art: Bitmap? = stationLogo

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val extractedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val extractedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durStr?.toLongOrNull()?.let { if (it > 0L) durationCache[uri] = it }

            if (!extractedTitle.isNullOrBlank()) {
                rawTitle = extractedTitle
            }
            if (!extractedArtist.isNullOrBlank()) {
                rawArtist = extractedArtist
            }
            if (!isWctr && allowAlbumArt) {
                val picBytes = retriever.embeddedPicture
                if (picBytes != null && picBytes.isNotEmpty()) {
                    art = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size) ?: stationLogo
                }
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        var rawAlbum: String = ""
        try {
            val retr2 = MediaMetadataRetriever()
            retr2.setDataSource(context, uri)
            rawAlbum = retr2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            retr2.release()
        } catch (_: Exception) {}

        if (isWctr) {
            val noSeg = suffixRegex.replace(rawTitle, "").trim()
            val cleanShow = episodeRegex.replace(noSeg, "").trim().ifBlank { noSeg }
            if (cleanShow.uppercase().startsWith("NEWS")) {
                _currentMetadata = buildStationMetadata(
                    trackTitle = "News",
                    trackSubtitle = stationTitle,
                    art = stationLogo,
                    isSongOrShow = true,
                    cleanArtist = stationTitle,
                    cleanAlbum = ""
                )
            } else {
                val host = resolveWctrHost(cleanShow, rawTitle, rawArtist)
                val subtitle = if (host.isNotBlank()) "$host • $stationTitle" else stationTitle
                _currentMetadata = buildStationMetadata(
                    trackTitle = cleanShow,
                    trackSubtitle = subtitle,
                    art = stationLogo,
                    isSongOrShow = true,
                    cleanArtist = host.ifBlank { stationTitle },
                    cleanAlbum = ""
                )
            }
        } else {
            val cleanTitle = suffixRegex.replace(rawTitle, "").trim()
            val artistStr = rawArtist ?: ""
            val cachedAlb = SaMetadataRepository.getCachedAlbumName(context, artistStr, cleanTitle)
            val finalAlbum = if (!cachedAlb.isNullOrBlank()) cachedAlb else if (!rawAlbum.startsWith("GTA", ignoreCase = true)) rawAlbum else ""
            val subtitle = if (artistStr.isNotBlank()) "$artistStr • $stationTitle" else stationTitle
            _currentMetadata = buildStationMetadata(
                trackTitle = cleanTitle,
                trackSubtitle = subtitle,
                art = art,
                isSongOrShow = true,
                cleanArtist = artistStr,
                cleanAlbum = finalAlbum
            )
        }
        onMetadataChanged?.invoke(_currentMetadata)
    }

    private fun refreshCurrentSegmentMetadata() {
        if (lastSegment == RadioSegmentType.Song && currentSongMainUri != null) {
            updateSongMetadataFromUri(currentSongMainUri!!, currentSongFolderName)
        } else {
            setStationOnlyMetadata()
        }
    }

    private fun setupAnnouncerFiles(folder: DocumentFile) {
        announcerFiles = folder.listFiles().map { x -> x.uri }.toTypedArray()
    }

    private fun setupAdvertFiles(folder: DocumentFile) {
        advertFiles = folder.listFiles().map { x -> x.uri }.toTypedArray()
    }

    private fun setupDJFiles(folder: DocumentFile) {
        val djFolders = folder.listFiles()
        djMorningFiles = (djFolders.firstOrNull { x -> x.name?.uppercase() == "MORNING" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
        djEveningFiles = (djFolders.firstOrNull { x -> x.name?.uppercase() == "EVENING" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
        djNightFiles = (djFolders.firstOrNull { x -> x.name?.uppercase() == "NIGHT" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
        djOtherFiles = (djFolders.firstOrNull { x -> x.name?.uppercase() == "OTHER" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()

        val weatherFolder = folder.listFiles().firstOrNull { x -> x.name?.uppercase() == "WEATHER" }
        val weatherFolders = weatherFolder?.listFiles()
        if (weatherFolder != null && weatherFolders?.isNotEmpty() == true) {
            djWeatherFogFiles = (weatherFolders.firstOrNull { x -> x.name?.uppercase() == "FOG" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
            djWeatherRainFiles = (weatherFolders.firstOrNull { x -> x.name?.uppercase() == "RAIN" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
            djWeatherSunFiles = (weatherFolders.firstOrNull { x -> x.name?.uppercase() == "SUN" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
            djWeatherStormFiles = (weatherFolders.firstOrNull { x -> x.name?.uppercase() == "STORM" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
        }
    }

    private fun setupSongs(folder: DocumentFile) {
        songs = folder.listFiles().map { x -> Song(x) }.toTypedArray()
    }

    private fun randomizeAll() {
        randomizeAnnouncerFiles()
        randomizeAdvertFiles()
        randomizeDJFiles()
        randomizeSongFolders()
    }

    private fun randomizeAnnouncerFiles() {
        announcerFiles.shuffle()
        announcerIterator = announcerFiles.iterator()
    }

    private fun randomizeAdvertFiles() {
        advertFiles.shuffle()
        advertIterator = advertFiles.iterator()
    }

    private fun randomizeDJFiles() {
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        var arrToConcat: Array<Uri> = emptyArray()
        if (currentHour in 3..11) {
            djMorningFiles.shuffle()
            arrToConcat = djMorningFiles
        } else if (currentHour in 17..22) {
            djEveningFiles.shuffle()
            arrToConcat = djEveningFiles
        } else if (currentHour < 3 || currentHour >= 23) {
            djNightFiles.shuffle()
            arrToConcat = djNightFiles
        }
        djOtherFiles.shuffle()
        currentDJFiles = djOtherFiles.plus(arrToConcat)

        if (weatherChatterEnabled) {
            djWeatherFogFiles.shuffle()
            djWeatherRainFiles.shuffle()
            djWeatherStormFiles.shuffle()
            djWeatherSunFiles.shuffle()
            currentDJFiles = currentDJFiles.plus(djWeatherFogFiles).plus(djWeatherStormFiles).plus(djWeatherRainFiles).plus(djWeatherSunFiles)
        }
        currentDJFiles.shuffle()
        djIterator = currentDJFiles.iterator()
    }

    private fun randomizeSongFolders() {
        songs.shuffle()
        songIterator = songs.iterator()
    }

    private fun getNextAdvertFile(): Uri {
        if (!advertIterator!!.hasNext()) randomizeAdvertFiles()
        return advertIterator!!.next()
    }

    private fun getNextAnnouncerFile(): Uri {
        if (!announcerIterator!!.hasNext()) randomizeAnnouncerFiles()
        return announcerIterator!!.next()
    }

    private fun getNextDjFile(): Uri {
        if (!djIterator!!.hasNext()) randomizeDJFiles()
        return djIterator!!.next()
    }

    private fun getNextSongFolder(): Song {
        if (!songIterator!!.hasNext()) randomizeSongFolders()
        return songIterator!!.next()
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
            val initialOffsetMs = rng.nextInt(45_000).toLong()
            resumeFromRealTime(initialOffsetMs)
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
                    val targetPosMs = posMs + remainingMs
                    refreshCurrentSegmentMetadata()
                    startPlayback(currentSegmentUris, i, targetPosMs)
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
                RadioSegmentType.None -> {
                    lastSegment = RadioSegmentType.fromInt(rng.nextInt(5))
                }
                RadioSegmentType.Commercial -> {
                    val roll = rng.nextInt(20)
                    if (announcerFiles.isNotEmpty() && roll < 17) {
                        if (prepareAnnouncerSegment()) return
                    } else {
                        if (prepareSongSegment(requireDjIntro = true)) return
                    }
                }
                RadioSegmentType.Announcer -> {
                    if (rng.nextInt(10) < 7 || currentDJFiles.isEmpty()) {
                        if (prepareSongSegment(requireDjIntro = true)) return
                    } else {
                        if (prepareDjSegment()) return
                    }
                }
                RadioSegmentType.DJChatter -> {
                    if (songsSinceLastBreak == 0) {
                        if (prepareSongSegment()) return
                    } else {
                        val numberOfSongsBeforeBreak = rng.nextInt(3) + 3
                        if (adsEnabled && advertFiles.isNotEmpty() && numberOfSongsBeforeBreak <= songsSinceLastBreak) {
                            if (prepareAdvertSegment()) return
                        } else {
                            if (prepareSongSegment()) return
                        }
                    }
                }
                RadioSegmentType.Song -> {
                    val numberOfSongsBeforeBreak = rng.nextInt(3) + 3
                    if (adsEnabled && advertFiles.isNotEmpty() && numberOfSongsBeforeBreak <= songsSinceLastBreak) {
                        if (prepareAdvertSegment()) return
                    } else {
                        val nextThing = if (currentDJFiles.isEmpty() && announcerFiles.isEmpty()) 0
                        else if (currentDJFiles.isEmpty()) rng.nextInt(2)
                        else if (announcerFiles.isEmpty()) (if (rng.nextInt(2) == 0) 0 else 2)
                        else rng.nextInt(3)
                        when (nextThing) {
                            0 -> if (prepareSongSegment()) return
                            1 -> if (prepareAnnouncerSegment()) return
                            else -> if (prepareDjSegment()) return
                        }
                    }
                }
                else -> {
                    lastSegment = RadioSegmentType.fromInt(rng.nextInt(5) + 1)
                }
            }
        }
    }

    private fun prepareAnnouncerSegment(): Boolean {
        lastSegment = RadioSegmentType.Announcer
        if (announcerFiles.isEmpty()) return false
        currentSongMainUri = null
        currentSegmentUris = listOf(getNextAnnouncerFile())
        return true
    }

    private fun prepareDjSegment(): Boolean {
        lastSegment = RadioSegmentType.DJChatter
        if (currentDJFiles.isEmpty()) return false
        currentSongMainUri = null
        currentSegmentUris = listOf(getNextDjFile())
        return true
    }

    private fun prepareAdvertSegment(): Boolean {
        lastSegment = RadioSegmentType.Commercial
        songsSinceLastBreak = 0
        if (!adsEnabled || advertFiles.isEmpty()) return false
        currentSongMainUri = null
        currentSegmentUris = listOf(getNextAdvertFile())
        return true
    }

    private fun prepareSongSegment(requireDjIntro: Boolean = false, requireDjOutro: Boolean = false): Boolean {
        songsSinceLastBreak += 1
        lastSegment = RadioSegmentType.Song
        if (songs.isEmpty()) return false

        val song = getNextSongFolder()
        val mainUri = song.mainSongUri ?: return false

        val introToUse = if (requireDjIntro && song.djIntroUris.isNotEmpty()) {
            song.djIntroUris[rng.nextInt(song.djIntroUris.size)]
        } else if (rng.nextInt(11) < 6 && song.djIntroUris.isNotEmpty()) {
            song.djIntroUris[rng.nextInt(song.djIntroUris.size)]
        } else {
            song.mainIntroUri
        }

        val outroToUse = if (requireDjOutro && song.djOutroUris.isNotEmpty()) {
            song.djOutroUris[rng.nextInt(song.djOutroUris.size)]
        } else if (rng.nextInt(11) < 6 && song.djOutroUris.isNotEmpty()) {
            song.djOutroUris[rng.nextInt(song.djOutroUris.size)]
        } else {
            song.mainOutroUri
        }

        val list = ArrayList<Uri>(3)
        if (introToUse != null) list.add(introToUse)
        list.add(mainUri)
        if (outroToUse != null) list.add(outroToUse)

        currentSongMainUri = mainUri
        currentSongFolderName = song.folderName
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

    private inner class Song(songFolderDoc: DocumentFile?) {
        val folderName: String = songFolderDoc?.name ?: stationTitle
        val songFiles = songFolderDoc?.listSimpleFiles(context.applicationContext)

        val mainSongUri: Uri? = (songFiles ?: emptyArray()).find { x -> !x.name.uppercase().contains("(INTRO") && !x.name.uppercase().contains("(OUTRO") }?.uri
        val mainIntroUri: Uri? = (songFiles ?: emptyArray()).find { x -> x.name.uppercase().contains("(INTRO)") }?.uri
        val mainOutroUri: Uri? = (songFiles ?: emptyArray()).find { x -> x.name.uppercase().contains("(OUTRO)") }?.uri
        val djIntroUris: Array<Uri> = (songFiles ?: emptyArray()).filter { x -> x.name.uppercase().contains("(INTRO DJ") }.map { x -> x.uri }.toTypedArray()
        val djOutroUris: Array<Uri> = (songFiles ?: emptyArray()).filter { x -> x.name.uppercase().contains("(OUTRO DJ") }.map { x -> x.uri }.toTypedArray()
    }
}
