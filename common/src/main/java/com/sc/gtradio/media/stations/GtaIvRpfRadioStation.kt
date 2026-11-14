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

data class GtaIvSongItem(
    val archive: GtaIvRpfArchive,
    val trackName: String,
    val introNames: List<String> = emptyList()
)

@UnstableApi class GtaIvRpfRadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaBrowserCompat.MediaItem,
    private val stationArchive: GtaIvRpfArchive,
    private val extraTracksArchive: GtaIvRpfArchive?,
    private val advertsArchive: GtaIvRpfArchive?,
    private val newsArchive: GtaIvRpfArchive?,
    private val weatherArchive: GtaIvRpfArchive?,
    private val player: GTRadioPlayer,
    private val context: Context,
    override var adsEnabled: Boolean,
    override var weatherChatterEnabled: Boolean,
    override var newsReportsEnabled: Boolean
) : RadioStation {

    override var onMetadataChanged: ((MediaMetadataCompat) -> Unit)? = null
    override var stationName: String = (mediaItem.description.title ?: stationArchive.rpfName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.description.subtitle ?: "GTA IV").toString()
    private val stationLogo: Bitmap? = mediaItem.description.iconBitmap
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rng = Random()

    private var _currentMetadata: MediaMetadataCompat = buildStationMetadata(stationTitle, "", stationLogo)
    override val metadata: MediaMetadataCompat
        get() = _currentMetadata

    private var initialized = false
    private var isTalkRadio = false

    private val idQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)
    private val soloQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)
    private val generalDjQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)
    private val morningDjList = ArrayList<String>()
    private val afternoonDjList = ArrayList<String>()
    private val eveningDjList = ArrayList<String>()
    private val nightDjList = ArrayList<String>()
    private val timeOfDayDjQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)

    private val toAdQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)
    private val toNewsQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)
    private val toWeatherQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)

    private val advertQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)
    private val newsQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)
    private val weatherQueue = RandomizedDualHeadQueue<String>(emptyList(), rng)
    private val songQueue = RandomizedDualHeadQueue<GtaIvSongItem>(emptyList(), rng)

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var songsSinceLastBreak = 0
    private var consecutiveSongs = 0
    private var interludeChainCount = 0
    private var activeHourBucket = -1
    private var activeListener: Player.Listener? = null

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: GtaIvSongItem? = null
    private var lastStoppedTimeMs: Long = 0L
    private var stoppedWindowIndex: Int = 0
    private var stoppedPositionMs: Long = 0L
    private val durationCache = HashMap<Uri, Long>()

    @Synchronized
    private fun ensureStationIndexed() {
        if (initialized) return
        initialized = true

        val rpfLower = stationArchive.rpfName.lowercase()
        isTalkRadio = rpfLower.contains("wktt") || rpfLower.contains("plr") || rpfLower.contains("lazlow")

        val ids = ArrayList<String>()
        val solos = ArrayList<String>()
        val generals = ArrayList<String>()
        val toAds = ArrayList<String>()
        val toNews = ArrayList<String>()
        val toWeather = ArrayList<String>()
        val introMap = HashMap<String, MutableList<String>>()

        // 1. Collect wave-bank sub-tracks (TIME, GENERAL, TO, INTRO)
        for (sub in stationArchive.getBankSubTracks()) {
            val sName = sub.subName.uppercase()
            when {
                sName.startsWith("MORNING_") -> morningDjList.add(sName)
                sName.startsWith("AFTERNOON_") -> afternoonDjList.add(sName)
                sName.startsWith("EVENING_") -> eveningDjList.add(sName)
                sName.startsWith("NIGHT_") -> nightDjList.add(sName)
                sName.startsWith("TO_AD") -> toAds.add(sName)
                sName.startsWith("TO_NEWS") -> toNews.add(sName)
                sName.startsWith("TO_WEATHER") -> toWeather.add(sName)
                sub.parentEntryName == "GENERAL" || sName.startsWith("GENERAL") -> generals.add(sName)
                sub.parentEntryName == "INTRO" -> {
                    val baseSong = sName.replace(Regex("_\\d+$"), "")
                    introMap.getOrPut(baseSong) { ArrayList() }.add(sName)
                }
            }
        }

        // 2. Collect top-level MultiC entries (Songs, Talk Shows, IDs, Solos)
        val songItems = ArrayList<GtaIvSongItem>()
        for (entry in stationArchive.getFileEntries()) {
            if (entry.isWaveBank) continue
            val eName = entry.name.uppercase()
            when {
                eName.startsWith("ID_") -> ids.add(eName)
                eName.startsWith("SOLO_") -> solos.add(eName)
                eName.startsWith("MORNING_") -> morningDjList.add(eName)
                eName.startsWith("AFTERNOON_") -> afternoonDjList.add(eName)
                eName.startsWith("EVENING_") -> eveningDjList.add(eName)
                eName.startsWith("NIGHT_") -> nightDjList.add(eName)
                eName.startsWith("TO_AD") -> toAds.add(eName)
                eName.startsWith("TO_NEWS") -> toNews.add(eName)
                eName.startsWith("TO_WEATHER") -> toWeather.add(eName)
                else -> {
                    val intros = introMap[eName] ?: emptyList()
                    songItems.add(GtaIvSongItem(stationArchive, eName, intros))
                }
            }
        }

        // 3. Attach extra tracks from radio_extratracks.rpf for The Beat 102.7, Massive B, and Electro-Choc
        if (extraTracksArchive != null) {
            val extraSet = when {
                rpfLower.contains("beat_95") -> GtaIvMetadataRepository.beatExtraTracks
                rpfLower.contains("bobby_konders") -> GtaIvMetadataRepository.massiveBExtraTracks
                rpfLower.contains("dance_mix") -> GtaIvMetadataRepository.electroChocExtraTracks
                else -> emptySet()
            }
            for (extraName in extraSet) {
                if (extraTracksArchive.hasEntry(extraName)) {
                    songItems.add(GtaIvSongItem(extraTracksArchive, extraName, emptyList()))
                }
            }
        }

        idQueue.reset(ids)
        soloQueue.reset(solos)
        generalDjQueue.reset(generals)
        toAdQueue.reset(toAds)
        toNewsQueue.reset(toNews)
        toWeatherQueue.reset(toWeather)
        songQueue.reset(songItems)
        rebuildTimeOfDayQueue()

        advertsArchive?.let { arch ->
            advertQueue.reset(arch.getFileEntries().filter { !it.isWaveBank }.map { it.name })
        }
        newsArchive?.let { arch ->
            newsQueue.reset(arch.getFileEntries().filter { !it.isWaveBank }.map { it.name })
        }
        weatherArchive?.let { arch ->
            weatherQueue.reset(arch.getFileEntries().filter { !it.isWaveBank }.map { it.name })
        }
    }

    private fun getHourBucket(): Int {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 4..11 -> 0 // Morning
            hour in 12..16 -> 1 // Afternoon
            hour in 17..22 -> 2 // Evening
            else -> 3 // Night
        }
    }

    private fun rebuildTimeOfDayQueue() {
        val b = getHourBucket()
        activeHourBucket = b
        val list = when (b) {
            0 -> morningDjList
            1 -> afternoonDjList.ifEmpty { morningDjList }
            2 -> eveningDjList.ifEmpty { afternoonDjList }
            else -> nightDjList.ifEmpty { eveningDjList }
        }
        timeOfDayDjQueue.reset(list)
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

    private fun applySongOrShowMetadata(item: GtaIvSongItem) {
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }

        val meta = GtaIvMetadataRepository.getTrackMeta(context, item.trackName, stationTitle)
        val displayTitle = meta?.title ?: item.trackName.replace('_', ' ')
        val displayArtist = meta?.artist ?: stationTitle
        val isTalk = isTalkRadio || (meta?.isTalkShow == true)

        if (isTalk) {
            val subtitle = if (displayArtist.isNotBlank() && displayArtist != stationTitle) "$displayArtist • $stationTitle" else stationTitle
            _currentMetadata = buildStationMetadata(
                trackTitle = displayTitle,
                trackSubtitle = subtitle,
                art = stationLogo,
                isSongOrShow = true,
                cleanArtist = displayArtist,
                cleanAlbum = ""
            )
            onMetadataChanged?.invoke(_currentMetadata)
            return
        }

        var art: Bitmap? = stationLogo
        if (isAlbumArtEnabled()) {
            val cached = SaMetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle)
            if (cached != null) {
                art = cached
            } else {
                SaMetadataRepository.fetchEraAccurateArtAsync(context, displayArtist, displayTitle, 2008) {
                    mainHandler.post {
                        if (_playing && lastSegment == RadioSegmentType.Song && currentSongItem == item) {
                            val newArt = SaMetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle) ?: stationLogo
                            val alb = SaMetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
                            val sub = if (alb.isNotBlank()) "$displayArtist • $alb" else "$displayArtist • $stationTitle"
                            _currentMetadata = buildStationMetadata(displayTitle, sub, newArt, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = alb)
                            onMetadataChanged?.invoke(_currentMetadata)
                        }
                    }
                }
            }
        }

        val eraAlbum = SaMetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
        val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
        _currentMetadata = buildStationMetadata(displayTitle, subtitle, art, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = eraAlbum)
        onMetadataChanged?.invoke(_currentMetadata)
    }

    private fun refreshCurrentSegmentMetadata() {
        if (lastSegment == RadioSegmentType.Song && currentSongItem != null) {
            applySongOrShowMetadata(currentSongItem!!)
        } else {
            setStationOnlyMetadata()
        }
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
        ensureStationIndexed()
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
        while (safety < 30 && currentSegmentUris.isNotEmpty()) {
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

    private fun hasAnyDjChatter(): Boolean {
        return generalDjQueue.isNotEmpty() || timeOfDayDjQueue.isNotEmpty()
    }

    private fun hasAnyAnnouncerOrSolo(): Boolean {
        return idQueue.isNotEmpty() || soloQueue.isNotEmpty()
    }

    private fun pickNextSegment() {
        var attempts = 0
        while (attempts < 15) {
            attempts++
            when (lastSegment) {
                RadioSegmentType.None -> lastSegment = RadioSegmentType.Song
                RadioSegmentType.Commercial, RadioSegmentType.NewsReport -> {
                    val roll = rng.nextInt(10)
                    if (interludeChainCount < 2 && hasAnyAnnouncerOrSolo() && (isTalkRadio || roll < 6)) {
                        if (prepareAnnouncerOrSoloSegment()) return
                    } else if (interludeChainCount < 2 && hasAnyDjChatter() && roll < 8) {
                        if (prepareDjOrWeatherSegment()) return
                    } else if (prepareSongSegment(requireDjIntro = true)) return
                }
                RadioSegmentType.Announcer -> {
                    if (interludeChainCount < 2 && hasAnyDjChatter() && rng.nextInt(100) < 55) {
                        if (prepareDjOrWeatherSegment()) return
                    } else if (prepareSongSegment(requireDjIntro = true)) return
                }
                RadioSegmentType.DJChatter, RadioSegmentType.WeatherReport -> {
                    val adThreshold = rng.nextInt(3) + 2
                    if ((adsEnabled || newsReportsEnabled) && songsSinceLastBreak >= adThreshold && interludeChainCount < 2) {
                        if (prepareAdvertOrNewsSegment()) return
                    } else if (interludeChainCount < 2 && hasAnyAnnouncerOrSolo() && rng.nextInt(100) < 25) {
                        if (prepareAnnouncerOrSoloSegment()) return
                    } else if (prepareSongSegment(false)) return
                }
                RadioSegmentType.Song -> {
                    val adThreshold = rng.nextInt(3) + 2
                    if (isTalkRadio || ((adsEnabled || newsReportsEnabled) && songsSinceLastBreak >= adThreshold)) {
                        if (prepareAdvertOrNewsSegment()) return
                    }
                    val allowBackToBack = consecutiveSongs < 2 && rng.nextInt(100) < 20
                    if (allowBackToBack || (!hasAnyDjChatter() && !hasAnyAnnouncerOrSolo())) {
                        if (prepareSongSegment(false)) return
                    } else if (hasAnyDjChatter() && hasAnyAnnouncerOrSolo()) {
                        if (rng.nextInt(100) < 60) {
                            if (prepareDjOrWeatherSegment()) return
                        } else {
                            if (prepareAnnouncerOrSoloSegment()) return
                        }
                    } else if (hasAnyDjChatter()) {
                        if (prepareDjOrWeatherSegment()) return
                    } else if (hasAnyAnnouncerOrSolo()) {
                        if (prepareAnnouncerOrSoloSegment()) return
                    } else if (prepareSongSegment(false)) return
                }
            }
            lastSegment = RadioSegmentType.Song
        }
    }

    private fun prepareAnnouncerOrSoloSegment(): Boolean {
        lastSegment = RadioSegmentType.Announcer
        val useId = if (idQueue.isNotEmpty() && soloQueue.isNotEmpty()) {
            isTalkRadio || rng.nextBoolean()
        } else {
            idQueue.isNotEmpty()
        }
        val trackName = if (useId && idQueue.isNotEmpty()) idQueue.next()
        else if (soloQueue.isNotEmpty()) soloQueue.next()
        else return false

        val uri = stationArchive.materializeAudioUri(trackName) ?: return false
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongItem = null
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareDjOrWeatherSegment(): Boolean {
        if (weatherChatterEnabled && weatherArchive != null && weatherQueue.isNotEmpty() && rng.nextInt(100) < 22) {
            return prepareWeatherSegment()
        }
        lastSegment = RadioSegmentType.DJChatter
        if (getHourBucket() != activeHourBucket) rebuildTimeOfDayQueue()

        val useTod = timeOfDayDjQueue.isNotEmpty() && (generalDjQueue.isEmpty() || rng.nextInt(100) < 35)
        val trackName = if (useTod) timeOfDayDjQueue.next()
        else if (generalDjQueue.isNotEmpty()) generalDjQueue.next()
        else return false

        val uri = stationArchive.materializeAudioUri(trackName) ?: return false
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongItem = null
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareWeatherSegment(): Boolean {
        lastSegment = RadioSegmentType.WeatherReport
        val wArch = weatherArchive ?: return false
        if (!weatherChatterEnabled || weatherQueue.isEmpty()) return false
        val wUri = wArch.materializeAudioUri(weatherQueue.next()) ?: return false

        val list = ArrayList<Uri>(2)
        if (toWeatherQueue.isNotEmpty() && rng.nextBoolean()) {
            stationArchive.materializeAudioUri(toWeatherQueue.next())?.let { list.add(it) }
        }
        list.add(wUri)
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongItem = null
        currentSegmentUris = list
        return true
    }

    private fun prepareAdvertOrNewsSegment(): Boolean {
        songsSinceLastBreak = 0
        val canAd = adsEnabled && advertsArchive != null && advertQueue.isNotEmpty()
        val canNews = newsReportsEnabled && newsArchive != null && newsQueue.isNotEmpty()
        if (!canAd && !canNews) return false

        val playAd = if (canAd && canNews) (rng.nextInt(10) < 7) else canAd
        if (playAd) {
            lastSegment = RadioSegmentType.Commercial
            val adUri = advertsArchive!!.materializeAudioUri(advertQueue.next()) ?: return false
            val list = ArrayList<Uri>(2)
            if (toAdQueue.isNotEmpty() && rng.nextBoolean()) {
                stationArchive.materializeAudioUri(toAdQueue.next())?.let { list.add(it) }
            }
            list.add(adUri)
            consecutiveSongs = 0
            interludeChainCount += 1
            currentSongItem = null
            currentSegmentUris = list
            return true
        } else {
            lastSegment = RadioSegmentType.NewsReport
            val newsUri = newsArchive!!.materializeAudioUri(newsQueue.next()) ?: return false
            val list = ArrayList<Uri>(2)
            if (toNewsQueue.isNotEmpty() && rng.nextBoolean()) {
                stationArchive.materializeAudioUri(toNewsQueue.next())?.let { list.add(it) }
            }
            list.add(newsUri)
            consecutiveSongs = 0
            interludeChainCount += 1
            currentSongItem = null
            currentSegmentUris = list
            return true
        }
    }

    private fun prepareSongSegment(requireDjIntro: Boolean): Boolean {
        if (songQueue.isEmpty()) return false
        songsSinceLastBreak += 1
        consecutiveSongs += 1
        interludeChainCount = 0
        lastSegment = RadioSegmentType.Song

        val item = songQueue.next()
        val mainUri = item.archive.materializeAudioUri(item.trackName) ?: return false
        val list = ArrayList<Uri>(2)

        if (item.introNames.isNotEmpty() && (requireDjIntro || rng.nextInt(100) < 60)) {
            val chosenIntro = item.introNames[rng.nextInt(item.introNames.size)]
            stationArchive.materializeAudioUri(chosenIntro)?.let { list.add(it) }
        }
        list.add(mainUri)

        currentSongItem = item
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
