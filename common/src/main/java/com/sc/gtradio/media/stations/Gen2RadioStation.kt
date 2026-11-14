package com.sc.gtradio.media.stations

import GTRadioPlayer
import android.content.Context
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
import androidx.preference.PreferenceManager
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

    private var djMorningFiles: Array<Uri> = emptyArray()
    private var djEveningFiles: Array<Uri> = emptyArray()
    private var djNightFiles: Array<Uri> = emptyArray()
    private var djOtherFiles: Array<Uri> = emptyArray()
    private var djWeatherFogFiles: Array<Uri> = emptyArray()
    private var djWeatherRainFiles: Array<Uri> = emptyArray()
    private var djWeatherSunFiles: Array<Uri> = emptyArray()
    private var djWeatherStormFiles: Array<Uri> = emptyArray()

    private val rng = Random()
    private val advertQueue = RandomizedDualHeadQueue<Uri>(emptyList(), rng)
    private val announcerQueue = RandomizedDualHeadQueue<Uri>(emptyList(), rng)
    private val djTalkQueue = RandomizedDualHeadQueue<Uri>(emptyList(), rng)
    private val timeOfDayQueue = RandomizedDualHeadQueue<Uri>(emptyList(), rng)
    private val weatherQueue = RandomizedDualHeadQueue<Uri>(emptyList(), rng)
    private val songQueue = RandomizedDualHeadQueue<Song>(emptyList(), rng)

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var songsSinceLastBreak = 0
    private var consecutiveSongs = 0
    private var interludeChainCount = 0
    private var lastDjSubCategory = -1
    private var activeHourBucket = -1
    private var activeListener: Player.Listener? = null

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
        randomizeDJFiles()
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
        var rawAlbum = ""
        var art: Bitmap? = stationLogo

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val extractedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val extractedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
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
        announcerQueue.reset(folder.listFiles().map { x -> x.uri })
    }

    private fun setupAdvertFiles(folder: DocumentFile) {
        advertQueue.reset(folder.listFiles().map { x -> x.uri })
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
        songQueue.reset(folder.listFiles().map { x -> Song(x) })
    }

    private fun getHourBucket(): Int {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 4..11 -> 0
            hour in 12..20 -> 1
            else -> 2
        }
    }

    private fun randomizeDJFiles() {
        djTalkQueue.reset(djOtherFiles.toList())
        val bucket = getHourBucket()
        activeHourBucket = bucket
        val todList = when (bucket) {
            0 -> djMorningFiles.toList()
            1 -> djEveningFiles.toList().ifEmpty { djMorningFiles.toList() }
            else -> djNightFiles.toList().ifEmpty { djEveningFiles.toList() }
        }
        timeOfDayQueue.reset(todList)

        if (weatherChatterEnabled) {
            val wList = ArrayList<Uri>()
            wList.addAll(djWeatherFogFiles)
            wList.addAll(djWeatherStormFiles)
            wList.addAll(djWeatherRainFiles)
            wList.addAll(djWeatherSunFiles)
            weatherQueue.reset(wList)
        } else {
            weatherQueue.reset(emptyList())
        }
    }

    private fun hasAnyDjContent(): Boolean {
        return djTalkQueue.isNotEmpty() || timeOfDayQueue.isNotEmpty() || weatherQueue.isNotEmpty()
    }

    private fun pickNextDjUri(): Uri? {
        if (getHourBucket() != activeHourBucket) {
            randomizeDJFiles()
        }
        val available = ArrayList<Pair<Int, Int>>()
        if (djTalkQueue.isNotEmpty()) available.add(Pair(0, 62))
        if (timeOfDayQueue.isNotEmpty()) available.add(Pair(1, 22))
        if (weatherChatterEnabled && weatherQueue.isNotEmpty()) available.add(Pair(2, 16))
        if (available.isEmpty()) return null

        val filtered = if (available.size > 1) {
            available.filter { it.first != lastDjSubCategory }.ifEmpty { available }
        } else {
            available
        }

        val totalWeight = filtered.sumOf { it.second }
        var roll = rng.nextInt(totalWeight)
        var chosenCat = filtered.first().first
        for ((cat, weight) in filtered) {
            if (roll < weight) {
                chosenCat = cat
                break
            }
            roll -= weight
        }
        lastDjSubCategory = chosenCat

        return when (chosenCat) {
            0 -> djTalkQueue.next()
            1 -> timeOfDayQueue.next()
            2 -> weatherQueue.next()
            else -> djTalkQueue.next()
        }
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
                    val roll = rng.nextInt(10)
                    if (interludeChainCount < 2 && announcerQueue.isNotEmpty() && roll < 6) {
                        if (prepareAnnouncerSegment()) return
                    } else if (interludeChainCount < 2 && hasAnyDjContent() && roll < 8) {
                        if (prepareDjSegment()) return
                    } else {
                        if (prepareSongSegment(requireDjIntro = true)) return
                    }
                }
                RadioSegmentType.Announcer -> {
                    if (interludeChainCount < 2 && hasAnyDjContent() && rng.nextInt(100) < 55) {
                        if (prepareDjSegment()) return
                    } else {
                        if (prepareSongSegment(requireDjIntro = true)) return
                    }
                }
                RadioSegmentType.DJChatter -> {
                    val adThreshold = rng.nextInt(3) + 2
                    if (adsEnabled && advertQueue.isNotEmpty() && songsSinceLastBreak >= adThreshold && interludeChainCount < 2) {
                        if (prepareAdvertSegment()) return
                    } else if (interludeChainCount < 2 && announcerQueue.isNotEmpty() && rng.nextInt(100) < 25) {
                        if (prepareAnnouncerSegment()) return
                    } else {
                        if (prepareSongSegment()) return
                    }
                }
                RadioSegmentType.Song -> {
                    val adThreshold = rng.nextInt(3) + 2
                    if (isWctr && adsEnabled && advertQueue.isNotEmpty()) {
                        if (rng.nextInt(10) < 6 && prepareAdvertSegment()) return
                        if (announcerQueue.isNotEmpty() && prepareAnnouncerSegment()) return
                    }
                    if (adsEnabled && advertQueue.isNotEmpty() && songsSinceLastBreak >= adThreshold) {
                        if (prepareAdvertSegment()) return
                    } else {
                        val allowBackToBackSong = consecutiveSongs < 2 && rng.nextInt(100) < 20
                        if (allowBackToBackSong || (!hasAnyDjContent() && announcerQueue.isEmpty())) {
                            if (prepareSongSegment()) return
                        } else if (hasAnyDjContent() && announcerQueue.isNotEmpty()) {
                            if (rng.nextInt(100) < 60) {
                                if (prepareDjSegment()) return
                            } else {
                                if (prepareAnnouncerSegment()) return
                            }
                        } else if (hasAnyDjContent()) {
                            if (prepareDjSegment()) return
                        } else {
                            if (prepareAnnouncerSegment()) return
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
        if (announcerQueue.isEmpty()) return false
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongMainUri = null
        currentSegmentUris = listOf(announcerQueue.next())
        return true
    }

    private fun prepareDjSegment(): Boolean {
        lastSegment = RadioSegmentType.DJChatter
        val uri = pickNextDjUri() ?: return false
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongMainUri = null
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareAdvertSegment(): Boolean {
        lastSegment = RadioSegmentType.Commercial
        songsSinceLastBreak = 0
        if (!adsEnabled || advertQueue.isEmpty()) return false
        val list = ArrayList<Uri>(2)
        list.add(advertQueue.next())
        if (advertQueue.size() > 2 && rng.nextInt(100) < 25) {
            list.add(advertQueue.next())
        }
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongMainUri = null
        currentSegmentUris = list
        return true
    }

    private fun prepareSongSegment(requireDjIntro: Boolean = false, requireDjOutro: Boolean = false): Boolean {
        songsSinceLastBreak += 1
        consecutiveSongs += 1
        interludeChainCount = 0
        lastSegment = RadioSegmentType.Song
        if (songQueue.isEmpty()) return false

        val song = songQueue.next()
        val mainUri = song.mainSongUri ?: return false

        val introToUse = if (requireDjIntro && song.djIntroUris.isNotEmpty()) {
            song.djIntroUris[rng.nextInt(song.djIntroUris.size)]
        } else if (rng.nextInt(100) < 65 && song.djIntroUris.isNotEmpty()) {
            song.djIntroUris[rng.nextInt(song.djIntroUris.size)]
        } else {
            song.mainIntroUri
        }

        val outroToUse = if (requireDjOutro && song.djOutroUris.isNotEmpty()) {
            song.djOutroUris[rng.nextInt(song.djOutroUris.size)]
        } else if (rng.nextInt(100) < 60 && song.djOutroUris.isNotEmpty()) {
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
