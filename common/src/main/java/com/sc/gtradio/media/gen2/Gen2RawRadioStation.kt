package com.sc.gtradio.media.gen2
import com.sc.gtradio.media.core.*


import com.sc.gtradio.media.*
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import kotlinx.coroutines.*
import java.io.File
import java.util.*

@UnstableApi class Gen2RawRadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaItem,
    private val stationArchive: Gen2StreamArchive,
    private val advertsArchive: Gen2StreamArchive?,
    private val stationMeta: Gen2StationMeta,
    private val player: GTRadioPlayer,
    private val context: Context,
    override var adsEnabled: Boolean,
    private var _weatherChatterEnabled: Boolean
) : RadioStation, TracklistCapableStation {

    private val stationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeDecodeJob: Job? = null
    private var seamlessPrepareJob: Job? = null
    private var activeCleanJob: Job? = null
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val playGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    override val supportsSeamlessPrepare: Boolean get() = true

    override fun supportsTracklist(): Boolean {
        return !isWctr && stationMeta.streamCode != "ADVERTS" && stationMeta.songGroups.isNotEmpty()
    }

    override fun getCleanTracks(): List<TracklistTrack> {
        if (!supportsTracklist()) return emptyList()
        return stationMeta.songGroups.map { sg ->
            TracklistTrack(
                id = sg.midTrack.toString(),
                title = sg.cleanTitle,
                artist = sg.artist,
                stationName = stationTitle,
                maxYear = 2004
            )
        }.distinctBy { it.id }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    override fun playCleanTrack(
        trackId: String,
        player: GTRadioPlayer,
        onTrackFinished: () -> Unit,
        onMetadataReady: (title: String, artist: String, album: String, maxYear: Int) -> Unit
    ) {
        val sg = stationMeta.songGroups.find { it.midTrack.toString() == trackId || it.cleanTitle.equals(trackId, ignoreCase = true) }
            ?: return
        val token = cleanPlayGeneration.incrementAndGet()

        activeCleanJob?.cancel()
        activeCleanJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                val album = Gen2MetadataRepository.getCachedAlbumName(context, sg.artist, sg.cleanTitle) ?: groupTitle
                withContext(Dispatchers.Main.immediate) {
                    if (token == cleanPlayGeneration.get()) {
                        onMetadataReady(sg.cleanTitle, sg.artist, album, 2004)
                    }
                }
                val uris = ArrayList<Uri>()
                if (sg.cleanIntroTrack != null && sg.cleanIntroTrack > 0) {
                    stationArchive.materializeTrackUri(sg.cleanIntroTrack)?.let { uris.add(it) }
                }
                stationArchive.materializeTrackUri(sg.midTrack)?.let { uris.add(it) }
                if (sg.cleanOutroTrack != null && sg.cleanOutroTrack > 0) {
                    stationArchive.materializeTrackUri(sg.cleanOutroTrack)?.let { uris.add(it) }
                }
                val durations = uris.map { readAudioDurationMs(context, it) }
                withContext(Dispatchers.Main.immediate) {
                    if (token == cleanPlayGeneration.get()) {
                        if (uris.isNotEmpty()) {
                            player.playCleanQueue(uris, durations) {
                                if (token == cleanPlayGeneration.get()) {
                                    onTrackFinished()
                                }
                            }
                        } else {
                            onTrackFinished()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    GTRadioLogger.log("Gen2RawRadioStation: Error materializing clean track", e)
                }
                withContext(Dispatchers.Main.immediate) {
                    onTrackFinished()
                }
            }
        }
    }

    override fun stopCleanTrackPlayback() {
        cleanPlayGeneration.incrementAndGet()
        activeCleanJob?.cancel()
        activeCleanJob = null
        player.stopCleanQueue()
    }

    override var newsReportsEnabled: Boolean = false
    override var onMetadataChanged: ((MediaMetadata) -> Unit)? = null

    override var weatherChatterEnabled: Boolean
        get() = _weatherChatterEnabled
        set(enabled) {
            if (enabled != _weatherChatterEnabled) {
                _weatherChatterEnabled = enabled
                rebuildDjQueues()
            }
        }

    override var stationName: String = stationMeta.stationName
    private val stationTitle: String = (mediaItem.mediaMetadata.title ?: stationMeta.stationName).toString()
    private val groupTitle: String = (mediaItem.mediaMetadata.subtitle ?: "GTA San Andreas").toString()
    private val stationLogo: Bitmap? by lazy { mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.getCachedBitmap(it, 512) ?: DynamicThemeManager.loadBitmap(context, it, 512) } }
    private val isWctr: Boolean = stationMeta.streamCode == "TK" || stationTitle.uppercase().contains("WCTR")
    private val mainHandler = Handler(Looper.getMainLooper())

    private var _currentMetadata: MediaMetadata = buildStationMetadata(stationTitle, "", stationLogo)
    override val metadata: MediaMetadata
        get() = _currentMetadata

    private val rng = Random()
    private val advertQueue = RandomizedDualHeadQueue<Int>(emptyList(), rng)
    private val announcerQueue = RandomizedDualHeadQueue<Int>(stationMeta.idTracks, rng)
    private val djTalkQueue = RandomizedDualHeadQueue<Int>(stationMeta.djOtherTracks, rng)
    private val callerStoryQueue = RandomizedDualHeadQueue<Int>(stationMeta.djCallerStoryTracks, rng)
    private val timeOfDayQueue = RandomizedDualHeadQueue<Int>(emptyList(), rng)
    private val weatherSunQueue = RandomizedDualHeadQueue<Int>(stationMeta.djWeatherSunTracks, rng)
    private val weatherFogQueue = RandomizedDualHeadQueue<Int>(stationMeta.djWeatherFogTracks, rng)
    private val weatherRainQueue = RandomizedDualHeadQueue<Int>(stationMeta.djWeatherRainTracks, rng)
    private val weatherStormQueue = RandomizedDualHeadQueue<Int>(stationMeta.djWeatherStormTracks, rng)
    private val weatherQueue = RandomizedDualHeadQueue<Int>(stationMeta.djWeatherTracks, rng)
    private val songQueue = RandomizedDualHeadQueue<Gen2SongGroup>(stationMeta.songGroups, rng)

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var songsSinceLastBreak = 0
    private var consecutiveSongs = 0
    private var interludeChainCount = 0
    private var lastDjSubCategory = -1
    private var activeTodBucket: TimeOfDayBucket? = null
    private var activeListener: Player.Listener? = null

    // Real-time radio state tracking
    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongGroup: Gen2SongGroup? = null
    private var currentSongMainUri: Uri? = null
    private var currentSongWindowIndices: Set<Int> = emptySet()
    private var lastStoppedTimeMs: Long = 0L
    private var stoppedWindowIndex: Int = 0
    private var stoppedPositionMs: Long = 0L
    private val durationCache = HashMap<Uri, Long>()
    private val episodeRegex = Regex("\\s*(?:-\\s*)?(?:Episode|Teaser)\\s*#?\\d+.*$", RegexOption.IGNORE_CASE)

    init {
        if (advertsArchive != null) {
            val adMeta = Gen2MetadataRepository.getByStreamCode(context, "ADVERTS")
            val adTrackList = (adMeta?.tracks?.keys ?: advertsArchive.getTrackNumbers()).toList()
            advertQueue.reset(adTrackList)
        }
        rebuildDjQueues()
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
    ): MediaMetadata {
        return buildStationMediaMetadata(
            mediaId = mediaItem.mediaId,
            title = trackTitle,
            subtitle = trackSubtitle,
            artist = cleanArtist.ifBlank { trackSubtitle },
            albumArtist = cleanAlbum,
            stationTitle = stationTitle,
            groupTitle = groupTitle,
            art = art,
            isSong = isSongOrShow,
            durationMs = -1L
        )
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

    private fun applySongMetadata(song: Gen2SongGroup) {
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }

        if (isWctr) {
            val cleanShow = if (song.cleanTitle.contains("News", ignoreCase = true)) {
                "WCTR News"
            } else {
                episodeRegex.replace(song.cleanTitle, "").trim().ifBlank { song.cleanTitle }
            }
            if (cleanShow.uppercase().contains("NEWS")) {
                _currentMetadata = buildStationMetadata("WCTR News", stationTitle, stationLogo, isSongOrShow = true, cleanArtist = stationTitle, cleanAlbum = "")
            } else {
                val rawMidTitle = stationMeta.tracks[song.midTrack]?.rawTitle ?: song.cleanTitle
                val host = resolveWctrHost(cleanShow, rawMidTitle)
                val subtitle = if (host.isNotBlank()) "$host • $stationTitle" else stationTitle
                _currentMetadata = buildStationMetadata(cleanShow, subtitle, stationLogo, isSongOrShow = true, cleanArtist = host.ifBlank { stationTitle }, cleanAlbum = "")
            }
            onMetadataChanged?.invoke(_currentMetadata)
            return
        }

        var art: Bitmap? = stationLogo
        if (isAlbumArtEnabled()) {
            val cached = Gen2MetadataRepository.getCachedAlbumArt(context, song.artist, song.cleanTitle)
            if (cached != null) {
                art = cached
            } else {
                Gen2MetadataRepository.fetchEraAccurateArtAsync(context, song.artist, song.cleanTitle) {
                    mainHandler.post {
                        if (_playing && lastSegment == RadioSegmentType.Song && currentSongGroup == song) {
                            val newArt = Gen2MetadataRepository.getCachedAlbumArt(context, song.artist, song.cleanTitle) ?: stationLogo
                            val sub = buildSongSubtitle(song)
                            val alb = Gen2MetadataRepository.getCachedAlbumName(context, song.artist, song.cleanTitle) ?: ""
                            _currentMetadata = buildStationMetadata(song.cleanTitle, sub, newArt, isSongOrShow = true, cleanArtist = song.artist, cleanAlbum = alb)
                            onMetadataChanged?.invoke(_currentMetadata)
                        }
                    }
                }
            }
        }

        val subtitle = buildSongSubtitle(song)
        val eraAlbum = Gen2MetadataRepository.getCachedAlbumName(context, song.artist, song.cleanTitle) ?: ""
        _currentMetadata = buildStationMetadata(song.cleanTitle, subtitle, art, isSongOrShow = true, cleanArtist = song.artist, cleanAlbum = eraAlbum)
        onMetadataChanged?.invoke(_currentMetadata)
    }

    private fun buildSongSubtitle(song: Gen2SongGroup): String {
        val eraAlbum = Gen2MetadataRepository.getCachedAlbumName(context, song.artist, song.cleanTitle)
        return if (!eraAlbum.isNullOrBlank()) {
            "${song.artist} • $eraAlbum"
        } else {
            "${song.artist} • $stationTitle"
        }
    }

    private var isStationActive = false

    private fun updateMetadataForWindowIndex(winIdx: Int) {
        if (lastSegment == RadioSegmentType.Song && currentSongGroup != null) {
            if (winIdx in currentSongWindowIndices) {
                applySongMetadata(currentSongGroup!!)
            } else {
                setStationOnlyMetadata()
            }
        } else {
            setStationOnlyMetadata()
        }
    }

    private fun refreshCurrentSegmentMetadata() {
        updateMetadataForWindowIndex(player.exoPlayer.currentMediaItemIndex)
    }

    private fun rebuildDjQueues() {
        val bucket = BroadcastAtmosphereClock.getTimeOfDay(context, SeriesEra.GEN2)
        activeTodBucket = bucket
        val todTracks = when (bucket) {
            TimeOfDayBucket.MORNING -> stationMeta.djMorningTracks
            TimeOfDayBucket.EVENING -> stationMeta.djEveningTracks.ifEmpty { stationMeta.djMorningTracks }
            TimeOfDayBucket.NIGHT -> stationMeta.djNightTracks.ifEmpty { stationMeta.djEveningTracks }
            else -> emptyList()
        }
        timeOfDayQueue.reset(todTracks)
        if (weatherChatterEnabled) {
            weatherSunQueue.reset(stationMeta.djWeatherSunTracks)
            weatherFogQueue.reset(stationMeta.djWeatherFogTracks)
            weatherRainQueue.reset(stationMeta.djWeatherRainTracks)
            weatherStormQueue.reset(stationMeta.djWeatherStormTracks)
            weatherQueue.reset(stationMeta.djWeatherTracks)
        } else {
            weatherSunQueue.reset(emptyList())
            weatherFogQueue.reset(emptyList())
            weatherRainQueue.reset(emptyList())
            weatherStormQueue.reset(emptyList())
            weatherQueue.reset(emptyList())
        }
    }

    private fun hasAnyWeatherTracks(): Boolean {
        return weatherChatterEnabled && (weatherSunQueue.isNotEmpty() || weatherFogQueue.isNotEmpty() ||
                weatherRainQueue.isNotEmpty() || weatherStormQueue.isNotEmpty() || weatherQueue.isNotEmpty())
    }

    private fun hasAnyDjContent(): Boolean {
        return djTalkQueue.isNotEmpty() || callerStoryQueue.isNotEmpty() ||
               timeOfDayQueue.isNotEmpty() || hasAnyWeatherTracks()
    }

    private fun pickNextWeatherTrack(): Int? {
        if (!weatherChatterEnabled) return null
        val targetWeather = BroadcastAtmosphereClock.getWeather(context, SeriesEra.GEN2)
        GTRadioLogger.logVerbose("ATMOS") {
            "[ATMOS] Gen2Raw[$stationTitle]: Choosing weather track for condition $targetWeather"
        }
        val chosenQueue = when (targetWeather) {
            WeatherState.SUN -> weatherSunQueue
            WeatherState.FOG -> weatherFogQueue
            WeatherState.RAIN -> weatherRainQueue
            WeatherState.STORM -> weatherStormQueue
            else -> weatherSunQueue
        }
        return if (chosenQueue.isNotEmpty()) {
            chosenQueue.next()
        } else if (weatherQueue.isNotEmpty()) {
            weatherQueue.next()
        } else null
    }

    private fun pickNextDjTrack(): Int? {
        val currentBucket = BroadcastAtmosphereClock.getTimeOfDay(context, SeriesEra.GEN2)
        if (currentBucket != activeTodBucket) {
            rebuildDjQueues()
        }
        // Build weighted candidate pools:
        // 0 = DJ Banter (38%), 1 = Caller/Story/Bridge (26%), 2 = Time-of-Day (20%), 3 = Weather (16%)
        val available = ArrayList<Pair<Int, Int>>()
        if (djTalkQueue.isNotEmpty()) available.add(Pair(0, 38))
        if (callerStoryQueue.isNotEmpty()) available.add(Pair(1, 26))
        if (timeOfDayQueue.isNotEmpty()) available.add(Pair(2, 20))
        if (hasAnyWeatherTracks()) available.add(Pair(3, 16))
        if (available.isEmpty()) return null

        // Avoid repeating the exact same sub-category twice in a row if another is available
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
            1 -> callerStoryQueue.next()
            2 -> timeOfDayQueue.next()
            3 -> pickNextWeatherTrack() ?: djTalkQueue.next()
            else -> djTalkQueue.next()
        }
    }

    private fun getFileDurationMs(uri: Uri): Long {
        durationCache[uri]?.let { return it }
        val dur = readAudioDurationMs(context, uri).takeIf { it > 0L } ?: 30_000L
        durationCache[uri] = dur
        return dur
    }

    private data class PreparedGen2Playback(
        val uris: List<Uri>,
        val startWindowIndex: Int,
        val startPositionMs: Long,
        val concatSource: ConcatenatingMediaSource
    )

    override fun cancelSeamlessPrepare() {
        playGeneration.incrementAndGet()
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
    }

    override fun prepareForSeamlessTakeOver(
        onReady: (commit: () -> Unit) -> Unit,
        onFailed: () -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { prepareForSeamlessTakeOver(onReady, onFailed) }
            return
        }
        val prepGen = playGeneration.incrementAndGet()
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                if (playGeneration.get() != prepGen || !isActive) return@launch
                val resolved = if (lastStoppedTimeMs > 0L && currentSegmentUris.isNotEmpty() && currentSegmentUris.all { u -> u.path?.let { File(it).exists() } == true }) {
                    val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
                    resolveStartPlaybackArgs(elapsedMs) { playGeneration.get() == prepGen && isActive }
                } else {
                    pickNextSegment()
                    stoppedWindowIndex = 0
                    stoppedPositionMs = 0L
                    resolveStartPlaybackArgs(rng.nextInt(45_000).toLong()) { playGeneration.get() == prepGen && isActive }
                }
                if (playGeneration.get() != prepGen || !isActive) return@launch
                if (resolved == null || resolved.first.isEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        if (playGeneration.get() == prepGen) {
                            seamlessPrepareJob = null
                            onFailed()
                        }
                    }
                    return@launch
                }
                val concatSource = buildConcatSource(resolved.first)
                val prepared = PreparedGen2Playback(resolved.first, resolved.second, resolved.third, concatSource)
                withContext(Dispatchers.Main.immediate) {
                    if (playGeneration.get() != prepGen) return@withContext
                    seamlessPrepareJob = null
                    onReady {
                        if (playGeneration.get() != prepGen) return@onReady
                        val commitGen = playGeneration.incrementAndGet()
                        GTRadioLogger.log("Gen2RawRadioStation[$stationTitle]: seamless commit() (gen=$commitGen)")
                        isStationActive = true
                        _playing = true
                        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
                        player.repeatMode = Player.REPEAT_MODE_OFF
                        player.playWhenReady = false
                        commitPlaybackOnMain(
                            uris = prepared.uris,
                            startWindowIndex = prepared.startWindowIndex,
                            startPositionMs = prepared.startPositionMs,
                            expectedGen = commitGen,
                            preBuiltSource = prepared.concatSource
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                GTRadioLogger.e("Gen2RawRadioStation[$stationTitle]: seamless prepare error", t)
                withContext(Dispatchers.Main.immediate) {
                    if (playGeneration.get() == prepGen) {
                        seamlessPrepareJob = null
                        onFailed()
                    }
                }
            }
        }
    }

    override fun stop() {
        val gen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Gen2RawRadioStation[$stationTitle]: stop() called (gen=$gen)")
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
        activeDecodeJob?.cancel()
        activeDecodeJob = null
        activeCleanJob?.cancel()
        activeCleanJob = null
        if (!isStationActive) return
        isStationActive = false
        _playing = false
        lastStoppedTimeMs = System.currentTimeMillis()
        stoppedWindowIndex = player.exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        stoppedPositionMs = player.exoPlayer.currentPosition.coerceAtLeast(0L)
        if (activeListener != null) {
            player.exoPlayer.removeListener(activeListener!!)
            activeListener = null
        }
        player.stopInternal()
    }

    override fun release() {
        stop()
        stationScope.cancel()
    }

    override fun play() {
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
        val currentGen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Gen2RawRadioStation[$stationTitle]: play() called (gen=$currentGen)")
        isStationActive = true
        _playing = true
        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

        activeDecodeJob?.cancel()
        activeDecodeJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            if (!_playing || playGeneration.get() != currentGen) return@launch
            if (lastStoppedTimeMs > 0L && currentSegmentUris.isNotEmpty() && currentSegmentUris.all { u -> u.path?.let { File(it).exists() } == true }) {
                val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
                resumeFromRealTime(elapsedMs, currentGen)
            } else {
                pickNextSegment()
                stoppedWindowIndex = 0
                stoppedPositionMs = 0L
                resumeFromRealTime(rng.nextInt(45_000).toLong(), currentGen)
            }
        }
    }

    private fun resolveStartPlaybackArgs(
        rawElapsedMs: Long,
        isValid: () -> Boolean
    ): Triple<List<Uri>, Int, Long>? {
        var remainingMs = rawElapsedMs
        for (u in currentSegmentUris) {
            getFileDurationMs(u)
        }
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
                    if (!isValid()) return null
                    return Triple(currentSegmentUris, i, posMs + remainingMs)
                } else {
                    remainingMs -= timeLeftMs
                    posMs = 0L
                }
            }
            pickNextSegment()
            winIdx = 0
            posMs = 0L
            for (u in currentSegmentUris) {
                getFileDurationMs(u)
            }
        }

        if (currentSegmentUris.isNotEmpty() && isValid()) {
            return Triple(currentSegmentUris, 0, 0L)
        }
        return null
    }

    private fun resumeFromRealTime(rawElapsedMs: Long, expectedGen: Int) {
        val resolved = resolveStartPlaybackArgs(rawElapsedMs) { _playing && playGeneration.get() == expectedGen }
            ?: return
        startPlayback(resolved.first, resolved.second, resolved.third, expectedGen)
    }

    private fun playNext() {
        val currentGen = playGeneration.get()
        if (!_playing) return
        activeDecodeJob?.cancel()
        activeDecodeJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            if (!_playing || playGeneration.get() != currentGen) return@launch
            pickNextSegment()
            if (currentSegmentUris.isNotEmpty()) {
                if (!_playing || playGeneration.get() != currentGen) return@launch
                startPlayback(currentSegmentUris, 0, 0L, currentGen)
            }
        }
    }

    private fun pickNextSegment() {
        var attempts = 0
        while (attempts < 15) {
            attempts++
            when (lastSegment) {
                RadioSegmentType.None -> lastSegment = RadioSegmentType.fromInt(rng.nextInt(5))
                RadioSegmentType.Commercial -> {
                    // After commercial: 60% Station ID, 20% DJ/Caller/Weather, 20% Song with DJ intro
                    val roll = rng.nextInt(10)
                    if (interludeChainCount < 2 && announcerQueue.isNotEmpty() && roll < 6) {
                        if (prepareAnnouncerSegment()) return
                    } else if (interludeChainCount < 2 && hasAnyDjContent() && roll < 8) {
                        if (prepareDjSegment()) return
                    } else if (prepareSongSegment(true)) return
                }
                RadioSegmentType.Announcer -> {
                    // After Station ID: if we haven't chained 2 interludes yet, 55% chance to play DJ/Caller/Story/Weather
                    if (interludeChainCount < 2 && hasAnyDjContent() && rng.nextInt(100) < 55) {
                        if (prepareDjSegment()) return
                    } else if (prepareSongSegment(true)) return
                }
                RadioSegmentType.DJChatter -> {
                    // After DJ/Caller/Story/Weather: if we only did 1 interlude, 25% chance for a quick Station ID before the song
                    val adThreshold = rng.nextInt(3) + 2 // 2 to 4 songs
                    if (adsEnabled && advertQueue.isNotEmpty() && songsSinceLastBreak >= adThreshold && interludeChainCount < 2) {
                        if (prepareAdvertSegment()) return
                    } else if (interludeChainCount < 2 && announcerQueue.isNotEmpty() && rng.nextInt(100) < 25) {
                        if (prepareAnnouncerSegment()) return
                    } else if (prepareSongSegment(false)) return
                }
                RadioSegmentType.Song -> {
                    val adThreshold = rng.nextInt(3) + 2 // 2 to 4 songs before commercial break
                    if (isWctr && adsEnabled && advertQueue.isNotEmpty()) {
                        if (rng.nextInt(10) < 6 && prepareAdvertSegment()) return
                        if (announcerQueue.isNotEmpty() && prepareAnnouncerSegment()) return
                    }
                    if (adsEnabled && advertQueue.isNotEmpty() && songsSinceLastBreak >= adThreshold) {
                        if (prepareAdvertSegment()) return
                    } else {
                        // Only allow back-to-back song 20% of the time (and never 3 songs in a row without a break)
                        val allowBackToBackSong = consecutiveSongs < 2 && rng.nextInt(100) < 20
                        if (allowBackToBackSong || (!hasAnyDjContent() && announcerQueue.isEmpty())) {
                            if (prepareSongSegment(false)) return
                        } else if (hasAnyDjContent() && announcerQueue.isNotEmpty()) {
                            // 60% DJ/Caller/Story/Time/Weather, 40% Station ID
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
                else -> lastSegment = RadioSegmentType.fromInt(rng.nextInt(5) + 1)
            }
        }
    }

    private fun prepareAnnouncerSegment(): Boolean {
        lastSegment = RadioSegmentType.Announcer
        if (announcerQueue.isEmpty()) return false
        val tr = announcerQueue.next()
        val uri = stationArchive.materializeTrackUri(tr) ?: return false
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongGroup = null
        currentSongMainUri = null
        currentSongWindowIndices = emptySet()
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareDjSegment(): Boolean {
        lastSegment = RadioSegmentType.DJChatter
        val tr = pickNextDjTrack() ?: return false
        val uri = stationArchive.materializeTrackUri(tr) ?: return false
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongGroup = null
        currentSongMainUri = null
        currentSongWindowIndices = emptySet()
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareAdvertSegment(): Boolean {
        lastSegment = RadioSegmentType.Commercial
        songsSinceLastBreak = 0
        if (!adsEnabled || advertsArchive == null || advertQueue.isEmpty()) return false
        val tr1 = advertQueue.next()
        val uri1 = advertsArchive.materializeTrackUri(tr1) ?: return false
        val list = ArrayList<Uri>(2)
        list.add(uri1)
        // 25% chance to play a 2-ad commercial pod
        if (advertQueue.size() > 2 && rng.nextInt(100) < 25) {
            val tr2 = advertQueue.next()
            advertsArchive.materializeTrackUri(tr2)?.let { list.add(it) }
        }
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongGroup = null
        currentSongMainUri = null
        currentSongWindowIndices = emptySet()
        currentSegmentUris = list
        return true
    }

    private fun prepareSongSegment(requireDjIntro: Boolean = false): Boolean {
        songsSinceLastBreak += 1
        consecutiveSongs += 1
        interludeChainCount = 0
        lastSegment = RadioSegmentType.Song
        if (songQueue.isEmpty()) return false
        val song = songQueue.next()
        val mainUri = stationArchive.materializeTrackUri(song.midTrack) ?: return false

        val introTrack = if (requireDjIntro && song.djIntroTracks.isNotEmpty()) {
            song.djIntroTracks[rng.nextInt(song.djIntroTracks.size)]
        } else if (rng.nextInt(100) < 65 && song.djIntroTracks.isNotEmpty()) {
            song.djIntroTracks[rng.nextInt(song.djIntroTracks.size)]
        } else {
            song.cleanIntroTrack
        }

        val outroTrack = if (rng.nextInt(100) < 60 && song.djOutroTracks.isNotEmpty()) {
            song.djOutroTracks[rng.nextInt(song.djOutroTracks.size)]
        } else {
            song.cleanOutroTrack
        }

        val list = ArrayList<Uri>(3)
        if (introTrack != null) stationArchive.materializeTrackUri(introTrack)?.let { list.add(it) }
        list.add(mainUri)
        if (outroTrack != null) stationArchive.materializeTrackUri(outroTrack)?.let { list.add(it) }

        currentSongGroup = song
        currentSongMainUri = mainUri
        currentSegmentUris = list
        currentSongWindowIndices = list.indices.toSet()
        return true
    }

    private fun buildConcatSource(uris: List<Uri>): ConcatenatingMediaSource {
        val factory = ProgressiveMediaSource.Factory(
            DefaultDataSourceFactory(context, Util.getUserAgent(context, context.packageName))
        )
        val concatSource = ConcatenatingMediaSource(true)
        for (u in uris) {
            concatSource.addMediaSource(factory.createMediaSource(MediaItem.fromUri(u)))
        }
        return concatSource
    }

    private fun startPlayback(uris: List<Uri>, startWindowIndex: Int, startPositionMs: Long, expectedGen: Int = playGeneration.get()) {
        val commitBlock = {
            commitPlaybackOnMain(uris, startWindowIndex, startPositionMs, expectedGen, preBuiltSource = null)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            commitBlock()
        } else {
            mainHandler.post(commitBlock)
        }
    }

    private fun commitPlaybackOnMain(
        uris: List<Uri>,
        startWindowIndex: Int,
        startPositionMs: Long,
        expectedGen: Int,
        preBuiltSource: ConcatenatingMediaSource?
    ) {
        if (!_playing || playGeneration.get() != expectedGen) return

        if (activeListener != null) {
            player.exoPlayer.removeListener(activeListener!!)
            activeListener = null
        }

        val concatSource = preBuiltSource ?: buildConcatSource(uris)
        player.setMediaSource(concatSource)
        if (startWindowIndex > 0 || startPositionMs > 0L) {
            player.exoPlayer.seekTo(startWindowIndex, startPositionMs)
        }
        updateMetadataForWindowIndex(startWindowIndex)

        activeListener = object : Player.Listener {
            private var alreadyHandled = false
            override fun onPlaybackStateChanged(state: Int) {
                if (!alreadyHandled && state == Player.STATE_ENDED) {
                    alreadyHandled = true
                    player.exoPlayer.removeListener(this)
                    if (_playing && playGeneration.get() == expectedGen) {
                        playNext()
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val curIdx = player.exoPlayer.currentMediaItemIndex
                updateMetadataForWindowIndex(curIdx)
            }

            override fun onPlayerError(error: PlaybackException) {
                GTRadioLogger.log("Gen2RawRadioStation[$stationTitle]: ExoPlayer error on track: ${error.message}", error)
                if (!alreadyHandled && _playing && playGeneration.get() == expectedGen) {
                    alreadyHandled = true
                    player.exoPlayer.removeListener(this)
                    mainHandler.post {
                        if (_playing && playGeneration.get() == expectedGen) {
                            playNext()
                        }
                    }
                }
            }
        }
        player.exoPlayer.addListener(activeListener!!)
        if (!_playing || playGeneration.get() != expectedGen) return
        player.prepare()
        player.playInternal()
    }
}
