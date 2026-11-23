package com.sc.gtradio.media.rpf7
import com.sc.gtradio.media.core.*


import com.sc.gtradio.media.*
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class Rpf7SongItem(
    val archive: Rpf7Archive,
    val trackName: String,
    val introKeys: List<String> = emptyList()
)

data class Rpf7ClipRef(
    val archive: Rpf7Archive,
    val trackKey: String
)

/**
 * Controller for a single GTA V Legacy PC radio station (RPF7 / AWC).
 * Supports:
 * - Dynamic queue rotation via RandomizedDualHeadQueue (RDHQ)
 * - 29-bit Jenkins hash resolution of voice banks (time.awc, to.awc, intro.awc)
 * - Continuous real-time progression with disk cache validation & re-materialization
 * - Chunked streaming & background prefetch for continuous mixes and talk shows
 * - Stale prefetch cancellation via prefetchGeneration counter
 * - Safe error recovery on ExoPlayer playback errors
 * - Preference-aware metadata and era-accurate album art (cutoff <= 2013)
 */
@UnstableApi class Rpf7RadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaItem,
    private val stationKey: String,
    private val stationArchive: Rpf7Archive,
    private val advertsArchive: Rpf7Archive?,
    private val newsArchive: Rpf7Archive?,
    private val player: GTRadioPlayer,
    private val context: Context,
    adsEnabled: Boolean,
    weatherChatterEnabled: Boolean = false,
    newsReportsEnabled: Boolean
) : RadioStation, TracklistCapableStation {

    override var onMetadataChanged: ((MediaMetadata) -> Unit)? = null
    override var stationName: String = (mediaItem.mediaMetadata.title ?: stationArchive.rpfName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.mediaMetadata.subtitle ?: "GTA V").toString()
    private val stationLogo: Bitmap? by lazy { mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.getCachedBitmap(it, 512) ?: DynamicThemeManager.loadBitmap(context, it, 512) } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rng = Random()

    private var _currentMetadata: MediaMetadata = buildStationMetadata(stationTitle, "", stationLogo)
    override val metadata: MediaMetadata
        get() = _currentMetadata

    private var initialized = false
    private var isTalkRadio = false
    private var isContinuousMixStation = false

    private val director by lazy {
        Gen3RadioDirector<Rpf7SongItem, Rpf7ClipRef>(
            context = context,
            era = SeriesEra.GEN3_RPF7,
            stationName = stationTitle,
            isTalkRadio = isTalkRadio,
            rng = rng
        ).also { d ->
            d.adsEnabled = this.adsEnabled
            d.weatherChatterEnabled = this.weatherChatterEnabled
            d.newsReportsEnabled = this.newsReportsEnabled
        }
    }

    override var adsEnabled: Boolean = adsEnabled
        set(value) {
            field = value
            if (!isContinuousMixStation) director.adsEnabled = value
        }
    override var weatherChatterEnabled: Boolean = weatherChatterEnabled
        set(value) {
            field = value
            if (!isContinuousMixStation) director.weatherChatterEnabled = value
        }
    override var newsReportsEnabled: Boolean = newsReportsEnabled
        set(value) {
            field = value
            val noNews = isContinuousMixStation || stationKey.contains("11_talk_02") || stationArchive.rpfName.contains("11_talk_02")
            if (!noNews) director.newsReportsEnabled = value
        }

    private val morningDjList = ArrayList<Rpf7ClipRef>()
    private val afternoonDjList = ArrayList<Rpf7ClipRef>()
    private val eveningDjList = ArrayList<Rpf7ClipRef>()
    private val nightDjList = ArrayList<Rpf7ClipRef>()

    private val allSongItems = ArrayList<Rpf7SongItem>()
    private val stationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeDecodeJob: Job? = null
    private var activeCleanJob: Job? = null
    private var activePollerJob: Job? = null
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var activeListener: Player.Listener? = null

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: Rpf7SongItem? = null
    private var currentMultiChunkNextBlock: Int = 0
    private var currentMultiChunkTotalBlocks: Int = 0
    private var currentMultiChunksPlayedInRow: Int = 0
    private var introItemCount = 0
    private var hasPrefetchedNextSegment: Boolean = false
    private val chunkStartSampleByMediaId = ConcurrentHashMap<String, Long>()
    private val chunkMediaIdByUri = ConcurrentHashMap<Uri, String>()
    private val chunkFileNameByMediaId = ConcurrentHashMap<String, String>()
    private val queuedChunkStartBlocks: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
    private var activeChunkPrefetchJob: Job? = null

    private fun buildChunkMediaId(entryName: String, startBlock: Int): String =
        "rpf7_chunk_${entryName}_$startBlock"

    private data class PlannedOverlayCue(
        val djUri: Uri,
        val djStartMs: Long,
        val introEndMs: Long
    )

    private data class PlannedPreRollCue(
        val djUri: Uri,
        val preRollDelayMs: Long,
        val introEndMs: Long
    )

    private data class PrefetchedBroadcastItem(
        val uris: List<Uri>,
        val pinnedFiles: List<String>,
        val event: NextBroadcastEvent<Rpf7SongItem, Rpf7ClipRef>,
        val songItem: Rpf7SongItem?,
        val segmentType: RadioSegmentType,
        val pendingBreakClips: List<DirectorBreakClip<Rpf7ClipRef>>? = null,
        val plannedOverlayCue: PlannedOverlayCue? = null,
        val plannedPreRollCue: PlannedPreRollCue? = null
    )

    private var prefetchedItem: PrefetchedBroadcastItem? = null
    private var lookAheadJob: Job? = null
    private var activeOverlayJob: Job? = null
    private var plannedOverlayCue: PlannedOverlayCue? = null
    private var plannedPreRollCue: PlannedPreRollCue? = null
    private var isPrefetching = false
    private val activePinnedUris: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val pendingBreakClips = ArrayList<DirectorBreakClip<Rpf7ClipRef>>()

    private fun pinAudioUri(uri: Uri) {
        val uriStr = uri.toString()
        activePinnedUris.add(uriStr)
        Rpf7Archive.pinFile(uriStr)
    }

    private fun unpinAudioUri(uri: Uri) {
        val uriStr = uri.toString()
        activePinnedUris.remove(uriStr)
        Rpf7Archive.unpinFile(uriStr)
    }

    private fun unpinAllActive() {
        for (u in activePinnedUris) {
            Rpf7Archive.unpinFile(u)
        }
        activePinnedUris.clear()
        Rpf7Archive.clearPinnedFiles()
    }

    private var lastStoppedTimeMs: Long = 0L
    private var stoppedWindowIndex: Int = 0
    private var stoppedPositionMs: Long = 0L
    private val durationCache = HashMap<Uri, Long>()

    private var activeMixMarkers: List<Rpf7TrackMarker> = emptyList()
    private var activeMixChunkStartSample: Long = 0L
    private var activeMixSampleRate: Int = 48000
    private var lastDisplayedMixTrackId: Int = -1
    private var mixPollerRunnable: Runnable? = null
    private var savedMixAbsMs: Long = 0L
    private var continuousMixItem: Rpf7SongItem? = null

    @Volatile private var prefetchGeneration = 0
    private val playGeneration = AtomicInteger(0)

    private fun materializeClip(keyOrScoped: String): Uri? {
        stationArchive.materializeAudioUri(keyOrScoped)?.let { return it }
        advertsArchive?.materializeAudioUri(keyOrScoped)?.let { return it }
        newsArchive?.materializeAudioUri(keyOrScoped)?.let { return it }
        return null
    }

    @Synchronized
    private fun ensureStationIndexed() {
        if (initialized) return
        initialized = true
        Rpf7MetadataRepository.ensureLoaded(context)

        val sInfo = Rpf7MetadataRepository.getStationInfo(stationKey)
            ?: Rpf7MetadataRepository.getStationInfo(stationArchive.rpfName)
        isTalkRadio = sInfo?.isTalk ?: (stationKey.contains("talk") || stationArchive.rpfName.contains("talk"))
        isContinuousMixStation = sInfo?.isContinuousMix ?: false

        if (isContinuousMixStation) {
            director.adsEnabled = false
            director.newsReportsEnabled = false
            director.weatherChatterEnabled = false
        }
        val isBlaineCounty = stationKey.contains("11_talk_02") || stationArchive.rpfName.contains("11_talk_02")
        if (isBlaineCounty) {
            director.newsReportsEnabled = false
        }

        stationArchive.ensureIndexed()
        advertsArchive?.ensureIndexed()
        newsArchive?.ensureIndexed()

        val ids = ArrayList<Rpf7ClipRef>()
        val solos = ArrayList<Rpf7ClipRef>()
        val generals = ArrayList<Rpf7ClipRef>()
        val toAds = ArrayList<Rpf7ClipRef>()
        val toNews = ArrayList<Rpf7ClipRef>()
        val toWeather = ArrayList<Rpf7ClipRef>()
        val introMap = HashMap<String, MutableList<String>>()
        val songItems = ArrayList<Rpf7SongItem>()

        // 1. Build lookup tables for 29-bit Jenkins hashes in voice banks
        val timeHashMap = HashMap<Int, String>()
        for (prefix in listOf("morning", "afternoon", "evening", "night")) {
            for (num in 1..15) {
                val pad = String.format(Locale.ROOT, "%02d", num)
                timeHashMap[Rpf7Crypto.jenkHashString("${prefix}_$pad") and 0x1FFFFFFF] = prefix
                timeHashMap[Rpf7Crypto.jenkHashString("${prefix}_$num") and 0x1FFFFFFF] = prefix
            }
        }

        val toHashMap = HashMap<Int, String>()
        for (prefix in listOf("to_ad", "to_news", "to_weather")) {
            for (num in 1..15) {
                val pad = String.format(Locale.ROOT, "%02d", num)
                toHashMap[Rpf7Crypto.jenkHashString("${prefix}_$pad") and 0x1FFFFFFF] = prefix
                toHashMap[Rpf7Crypto.jenkHashString("${prefix}_$num") and 0x1FFFFFFF] = prefix
            }
        }

        // Build intro song hash lookup table (supporting alias mapping for mismatched song stems)
        val introHashMap = HashMap<Int, String>()
        for (entry in stationArchive.getFileEntries()) {
            val stem = entry.name.removeSuffix(".awc").lowercase(Locale.ROOT)
            if (stem in setOf("time", "to", "intro", "general") || STATION_ID_REGEX.matches(stem) || stem.startsWith("mono_solo_")) continue
            val lookupStem = RPF7_INTRO_ALIASES[stem] ?: stem
            for (num in 1..8) {
                val pad = String.format(Locale.ROOT, "%02d", num)
                introHashMap[Rpf7Crypto.jenkHashString("${lookupStem}_$pad") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("intro_${lookupStem}_$pad") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("${lookupStem}_$num") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("intro_${lookupStem}_$num") and 0x1FFFFFFF] = stem
            }
        }

        // 2. Classify voice bank sub-tracks (time.awc, to.awc, intro.awc, general.awc)
        for (sub in stationArchive.getBankSubTracks()) {
            val parent = sub.parentEntryName.lowercase(Locale.ROOT).removeSuffix(".awc")
            val h29 = sub.streamId and 0x1FFFFFFF
            val ref = Rpf7ClipRef(stationArchive, sub.subKey)

            when (parent) {
                "time" -> {
                    when (timeHashMap[h29]) {
                        "morning" -> morningDjList.add(ref)
                        "afternoon" -> afternoonDjList.add(ref)
                        "evening" -> eveningDjList.add(ref)
                        "night" -> nightDjList.add(ref)
                        else -> generals.add(ref) // Fallback unmatched time clips to general DJ chatter
                    }
                }
                "to" -> {
                    when (toHashMap[h29]) {
                        "to_ad" -> toAds.add(ref)
                        "to_news" -> toNews.add(ref)
                        "to_weather" -> toWeather.add(ref)
                        else -> generals.add(ref)
                    }
                }
                "intro" -> {
                    val matchedSong = introHashMap[h29]
                    if (matchedSong != null) {
                        introMap.getOrPut(matchedSong) { ArrayList() }.add(sub.subKey)
                    }
                }
                "general" -> generals.add(ref)
            }
        }

        // 3. Classify top-level MultiChannel entries (using STATION_ID_REGEX to avoid false positives like id_rather_be_with_you)
        for (entry in stationArchive.getFileEntries()) {
            val nameLower = entry.name.lowercase(Locale.ROOT)
            val stem = nameLower.removeSuffix(".awc")
            if (stem in setOf("time", "to", "intro", "general")) continue

            val ref = Rpf7ClipRef(stationArchive, entry.name)
            when {
                STATION_ID_REGEX.matches(stem) -> ids.add(ref)
                stem.startsWith("mono_solo_") -> solos.add(ref)
                else -> {
                    val intros = introMap[stem] ?: emptyList()
                    songItems.add(Rpf7SongItem(stationArchive, entry.name, intros))
                }
            }
        }

        director.idQueue.reset(ids)
        director.soloQueue.reset(solos)
        director.generalDjQueue.reset(generals)
        director.toAdQueue.reset(toAds)
        director.toNewsQueue.reset(toNews)
        director.toWeatherQueue.reset(toWeather)
        director.songQueue.reset(songItems)
        director.morningDjList = morningDjList
        director.eveningDjList = eveningDjList
        director.nightDjList = eveningDjList.ifEmpty { morningDjList }
        director.afternoonDjList = generals.ifEmpty { morningDjList }
        allSongItems.clear()
        allSongItems.addAll(songItems)
        director.rebuildTimeOfDayQueueIfNeeded()

        // 4. Index Adverts and News
        val allAdverts = ArrayList<Rpf7ClipRef>()
        advertsArchive?.getFileEntries()?.forEach {
            allAdverts.add(Rpf7ClipRef(advertsArchive, it.name))
        }
        director.advertQueue.reset(allAdverts)

        val allNews = ArrayList<Rpf7ClipRef>()
        newsArchive?.getFileEntries()?.forEach {
            allNews.add(Rpf7ClipRef(newsArchive, it.name))
        }
        director.newsQueue.reset(allNews)

        GTRadioLogger.log(
            "Rpf7RadioStation[$stationTitle]: Indexed ${songItems.size} songs/shows, " +
                    "${ids.size} IDs, ${solos.size} solos, ${generals.size} general DJ clips, " +
                    "${morningDjList.size + afternoonDjList.size + eveningDjList.size + nightDjList.size} TOD clips, " +
                    "${allAdverts.size} adverts, ${allNews.size} news"
        )
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

    private fun applySongOrShowMetadata(item: Rpf7SongItem) {
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }

        if (activeMixMarkers.isNotEmpty()) {
            updateMixTrackMetadata()
            return
        }

        val meta = Rpf7MetadataRepository.getTrackMeta(item.trackName, stationTitle)
        val displayTitle = meta.title
        val displayArtist = meta.artist
        val isTalk = isTalkRadio || meta.isTalkShow
        val isContinuousMix = isContinuousMixStation() || meta.isContinuousMix

        // Talk shows and Continuous mixes keep the station logo as art (no wrong album cover)
        if (isTalk || isContinuousMix) {
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

        // Regular songs: query era-accurate album art (cutoff <= 2021, preferred <= 2013)
        var art: Bitmap? = stationLogo
        if (isAlbumArtEnabled()) {
            val cached = Rpf7MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle)
            if (cached != null) {
                art = cached
            } else {
                val expectedGen = playGeneration.get()
                Rpf7MetadataRepository.fetchEraAccurateArtAsync(context, displayArtist, displayTitle, maxYear = 2021) {
                    mainHandler.post {
                        if (_playing && playGeneration.get() == expectedGen && lastSegment == RadioSegmentType.Song && currentSongItem == item) {
                            val newArt = Rpf7MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle) ?: stationLogo
                            val alb = Rpf7MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
                            val sub = if (alb.isNotBlank()) "$displayArtist • $alb" else "$displayArtist • $stationTitle"
                            _currentMetadata = buildStationMetadata(
                                trackTitle = displayTitle,
                                trackSubtitle = sub,
                                art = newArt,
                                isSongOrShow = true,
                                cleanArtist = displayArtist,
                                cleanAlbum = alb
                            )
                            onMetadataChanged?.invoke(_currentMetadata)
                        }
                    }
                }
            }
        }

        val eraAlbum = Rpf7MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
        val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
        _currentMetadata = buildStationMetadata(
            trackTitle = displayTitle,
            trackSubtitle = subtitle,
            art = art,
            isSongOrShow = true,
            cleanArtist = displayArtist,
            cleanAlbum = eraAlbum
        )
        onMetadataChanged?.invoke(_currentMetadata)
    }

    private var isStationActive = false

    private fun updateMetadataForWindowIndex(winIdx: Int, expectedGen: Int = playGeneration.get()) {
        if (lastSegment == RadioSegmentType.Song && currentSongItem != null) {
            val isIntro = winIdx < introItemCount
            if (isIntro) {
                stopMixPoller(clearMarkers = false)
                setStationOnlyMetadata()
            } else {
                if (activeMixMarkers.isNotEmpty()) {
                    startMixPoller(expectedGen)
                    updateMixTrackMetadata()
                } else {
                    applySongOrShowMetadata(currentSongItem!!)
                }
            }
        } else {
            stopMixPoller(clearMarkers = true)
            setStationOnlyMetadata()
        }
    }

    private fun refreshCurrentSegmentMetadata() {
        updateMetadataForWindowIndex(player.exoPlayer.currentMediaItemIndex)
    }

    private fun getFileDurationMs(uri: Uri): Long {
        durationCache[uri]?.let { return it }
        val readDur = readAudioDurationMs(context, uri)
        if (readDur > 0L) {
            durationCache[uri] = readDur
            return readDur
        }
        var dur = 30_000L
        try {
            val path = uri.path
            if (path != null) {
                val f = File(path)
                if (f.exists() && f.length() > 0L) {
                    if (f.name.endsWith(".mp3", ignoreCase = true)) {
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(f.absolutePath)
                            val dStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            if (dStr != null) {
                                dur = dStr.toLong()
                            }
                        } catch (_: Exception) {
                            dur = (f.length() * 1000L) / 12000L
                        } finally {
                            try { mmr.release() } catch (_: Exception) {}
                        }
                    } else if (f.length() > 44L) {
                        RandomAccessFile(f, "r").use { raf ->
                            val hdr = ByteArray(44)
                            raf.readFully(hdr)
                            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                            val byteRate = bb.getInt(28)
                            val dataBytes = (f.length() - 44L).coerceAtLeast(0L)
                            if (byteRate > 0) {
                                dur = (dataBytes * 1000L) / byteRate.toLong()
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        durationCache[uri] = dur
        return dur
    }

    override fun stop() {
        val gen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: stop() called (gen=$gen)")
        activeDecodeJob?.cancel()
        activeDecodeJob = null
        activeCleanJob?.cancel()
        activeCleanJob = null
        activeOverlayJob?.cancel()
        activeOverlayJob = null
        plannedOverlayCue = null
        plannedPreRollCue = null
        player.djVoiceOverlayPlayer?.stopOverlay()
        player.resetMusicDuckGain()
        activeChunkPrefetchJob?.cancel()
        activeChunkPrefetchJob = null
        queuedChunkStartBlocks.clear()
        stopMixPoller(clearMarkers = true)
        lookAheadJob?.cancel()
        lookAheadJob = null
        isPrefetching = false
        prefetchedItem?.pinnedFiles?.forEach { Rpf7Archive.unpinFile(it) }
        prefetchedItem = null
        unpinAllActive()
        pendingBreakClips.clear()

        val isContinuousMix = isContinuousMixStation()
        if (isContinuousMix && currentSongItem != null) {
            val exo = player.exoPlayer
            if (exo.playbackState == Player.STATE_READY) {
                val mediaId = exo.currentMediaItem?.mediaId
                val sOff = if (mediaId != null) chunkStartSampleByMediaId[mediaId] else null
                if (sOff != null) {
                    val rawPosMs = exo.currentPosition.coerceAtLeast(0L)
                    val curPosMs = if (exo.duration > 0) rawPosMs.coerceAtMost(exo.duration) else rawPosMs
                    val sRate = activeMixSampleRate.toLong().coerceAtLeast(1L)
                    val currentSample = sOff + (curPosMs * sRate / 1000L)
                    savedMixAbsMs = (currentSample * 1000L) / sRate
                    continuousMixItem = currentSongItem
                    lastStoppedTimeMs = System.currentTimeMillis()
                }
            }
        }

        chunkFileNameByMediaId.values.forEach { Rpf7Archive.unpinFile(it) }
        chunkFileNameByMediaId.clear()
        chunkStartSampleByMediaId.clear()
        chunkMediaIdByUri.clear()
        if (!isStationActive) return
        isStationActive = false
        if (_playing && !isContinuousMix) {
            val isReady = player.exoPlayer.playbackState == Player.STATE_READY
            if (isReady || lastStoppedTimeMs == 0L) {
                lastStoppedTimeMs = System.currentTimeMillis()
                stoppedWindowIndex = player.exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
                stoppedPositionMs = player.exoPlayer.currentPosition.coerceAtLeast(0L)
            }
        }
        _playing = false
        prefetchGeneration++ // Cancel any active background prefetches immediately
        if (activeListener != null) {
            player.exoPlayer.removeListener(activeListener!!)
            activeListener = null
        }
        player.stopInternal()
    }

    override fun release() {
        activeChunkPrefetchJob?.cancel()
        activeChunkPrefetchJob = null
        stop()
        stationScope.cancel()
    }

    private fun areSegmentFilesValid(uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return false
        for (u in uris) {
            val p = u.path ?: return false
            val f = File(p)
            if (!f.exists() || f.length() <= 0L) return false
        }
        return true
    }

    override fun play() {
        val currentGen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: play() called (gen=$currentGen)")
        isStationActive = true
        _playing = true
        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

        activeChunkPrefetchJob?.cancel()
        activeChunkPrefetchJob = null
        queuedChunkStartBlocks.clear()

        val isContinuousMix = isContinuousMixStation()
        val canResumeMix = isContinuousMix && savedMixAbsMs > 0L && continuousMixItem != null && lastStoppedTimeMs > 0L
        val canResumeTrack = director.currentPhase != DirectorPhase.BREAK_BLOCK && lastStoppedTimeMs > 0L && areSegmentFilesValid(currentSegmentUris)
        if (!canResumeMix && !canResumeTrack) {
            setStationOnlyMetadata()
        }

        activeDecodeJob?.cancel()
        activeDecodeJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            if (!_playing || playGeneration.get() != currentGen) return@launch
            ensureStationIndexed()
            if (canResumeMix) {
                val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
                resumeContinuousMix(elapsedMs, currentGen)
            } else if (canResumeTrack) {
                val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
                resumeFromRealTime(elapsedMs, currentGen)
            } else {
                setStationOnlyMetadata()
                pickNextSegment()
                if (currentSegmentUris.isNotEmpty()) {
                    if (!_playing || playGeneration.get() != currentGen) return@launch
                    val lastIdx = currentSegmentUris.size - 1
                    val mainDur = getFileDurationMs(currentSegmentUris[lastIdx])
                    val startOffset = if (lastSegment == RadioSegmentType.Song && currentSegmentUris.size == 1 && mainDur > 45_000L) {
                        rng.nextInt(25_000).toLong()
                    } else {
                        0L
                    }
                    startPlayback(currentSegmentUris, 0, startOffset, currentGen)
                }
            }
        }
    }

    private fun resumeContinuousMix(elapsedMs: Long, expectedGen: Int) {
        val item = continuousMixItem ?: return
        val totalSamples = item.archive.getEntryTotalSamples(item.trackName)
        val sRate = item.archive.getEntrySampleRate(item.trackName).toLong().coerceAtLeast(1L)
        val totalMixDurationMs = if (totalSamples > 0L) (totalSamples * 1000L) / sRate else 0L

        val targetMixMs = if (totalMixDurationMs > 0L) {
            (savedMixAbsMs + elapsedMs) % totalMixDurationMs
        } else {
            savedMixAbsMs + elapsedMs
        }
        val targetSample = (targetMixMs * sRate) / 1000L

        val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
        val chunkBlocks = MIX_CHUNK_BLOCKS

        val rawBlock = item.archive.getBlockForSample(item.trackName, targetSample)
        val targetStartBlock = ((rawBlock / chunkBlocks) * chunkBlocks).coerceIn(0, (totalBlocks - chunkBlocks).coerceAtLeast(0))

        if (!queuedChunkStartBlocks.add(targetStartBlock)) return

        val chunkStartSample = item.archive.getChunkStartSample(item.trackName, targetStartBlock)
        val seekWithinChunkMs = ((targetSample - chunkStartSample).coerceAtLeast(0L) * 1000L) / sRate

        val mainUri = item.archive.materializeBlockChunkUri(item.trackName, targetStartBlock, chunkBlocks) ?: return
        if (!_playing || playGeneration.get() != expectedGen) return

        val chunkMediaId = buildChunkMediaId(item.trackName, targetStartBlock)
        chunkStartSampleByMediaId[chunkMediaId] = chunkStartSample
        chunkMediaIdByUri[mainUri] = chunkMediaId
        chunkFileNameByMediaId[chunkMediaId] = mainUri.toString()

        pinAudioUri(mainUri)
        currentSongItem = item
        lastSegment = RadioSegmentType.Song
        currentSegmentUris = listOf(mainUri)
        currentMultiChunkTotalBlocks = totalBlocks
        currentMultiChunkNextBlock = targetStartBlock + chunkBlocks
        currentMultiChunksPlayedInRow = 1
        introItemCount = 0

        val markers = item.archive.readTrackMarkers(item.trackName)
        if (markers.isNotEmpty()) {
            activeMixMarkers = markers
            activeMixChunkStartSample = chunkStartSample
            activeMixSampleRate = sRate.toInt()
            lastDisplayedMixTrackId = -1
        } else {
            stopMixPoller(clearMarkers = true)
        }

        Rpf7Archive.pinFile(mainUri.toString())

        startPlayback(listOf(mainUri), 0, seekWithinChunkMs, expectedGen)
    }

    private fun resumeFromRealTime(rawElapsedMs: Long, expectedGen: Int) {
        var remainingMs = rawElapsedMs
        for (u in currentSegmentUris) {
            getFileDurationMs(u)
        }
        var winIdx = stoppedWindowIndex.coerceIn(0, (currentSegmentUris.size - 1).coerceAtLeast(0))
        var posMs = stoppedPositionMs.coerceAtLeast(0L)

        val isContinuousMix = isContinuousMixStation() || (currentSongItem != null && Rpf7MetadataRepository.getTrackMeta(currentSongItem!!.trackName).isContinuousMix)
        if (isContinuousMix && currentSongItem != null && activeMixMarkers.isEmpty()) {
            val markers = currentSongItem!!.archive.readTrackMarkers(currentSongItem!!.trackName)
            if (markers.isNotEmpty()) {
                activeMixMarkers = markers
            }
        }

        var safety = 0
        while (safety < 6 && currentSegmentUris.isNotEmpty()) {
            safety++
            for (i in winIdx until currentSegmentUris.size) {
                val uri = currentSegmentUris[i]
                val durMs = getFileDurationMs(uri)
                val timeLeftMs = (durMs - posMs).coerceAtLeast(0L)
                if (remainingMs < timeLeftMs && areSegmentFilesValid(currentSegmentUris.subList(i, currentSegmentUris.size))) {
                    if (!_playing || playGeneration.get() != expectedGen) return
                    startPlayback(currentSegmentUris, i, posMs + remainingMs, expectedGen)
                    return
                } else {
                    remainingMs -= timeLeftMs
                    posMs = 0L
                }
            }
            if (!advanceMultiChunkOrPickNext()) break
            winIdx = 0
            posMs = 0L
            for (u in currentSegmentUris) {
                getFileDurationMs(u)
            }
        }

        if (currentSegmentUris.isNotEmpty()) {
            if (!_playing || playGeneration.get() != expectedGen) return
            startPlayback(currentSegmentUris, 0, 0L, expectedGen)
        }
    }

    private fun advanceMultiChunkOrPickNext(): Boolean {
        val item = currentSongItem
        if (lastSegment == RadioSegmentType.Song && item != null &&
            currentMultiChunkNextBlock in 1 until currentMultiChunkTotalBlocks
        ) {
            val isContinuousMix = isContinuousMixStation() || Rpf7MetadataRepository.getTrackMeta(item.trackName).isContinuousMix
            val maxChunksBeforeBreak = if (isContinuousMix) 6 else 100
            val chunkBlocks = if (isContinuousMix) MIX_CHUNK_BLOCKS else item.archive.getChunkBlocksFor(item.trackName)
            if (currentMultiChunksPlayedInRow < maxChunksBeforeBreak) {
                val nextStart = currentMultiChunkNextBlock
                if (queuedChunkStartBlocks.add(nextStart)) {
                    val nextUri = item.archive.materializeBlockChunkUri(
                        item.trackName,
                        nextStart,
                        chunkBlocks
                    )
                    if (nextUri != null) {
                        val chunkMediaId = buildChunkMediaId(item.trackName, nextStart)
                        val nextSample = item.archive.getChunkStartSample(item.trackName, nextStart)
                        chunkStartSampleByMediaId[chunkMediaId] = nextSample
                        chunkMediaIdByUri[nextUri] = chunkMediaId
                        chunkFileNameByMediaId[chunkMediaId] = nextUri.toString()
                        pinAudioUri(nextUri)
                        currentMultiChunkNextBlock += chunkBlocks
                        currentMultiChunksPlayedInRow += 1
                        currentSegmentUris = listOf(nextUri)
                        return true
                    }
                }
            }
        }
        pickNextSegment()
        return currentSegmentUris.isNotEmpty()
    }

    private fun playNext() {
        val currentGen = playGeneration.get()
        if (!_playing) return
        activeOverlayJob?.cancel()
        activeOverlayJob = null
        player.resetMusicDuckGain()
        activeDecodeJob?.cancel()
        activeDecodeJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            if (!_playing || playGeneration.get() != currentGen) return@launch
            if (advanceMultiChunkOrPickNext()) {
                if (!_playing || playGeneration.get() != currentGen) return@launch
                startPlayback(currentSegmentUris, 0, 0L, currentGen)
            }
        }
    }

    private fun pickNextSegment() {
        currentMultiChunkNextBlock = 0
        currentMultiChunkTotalBlocks = 0
        currentMultiChunksPlayedInRow = 0
        hasPrefetchedNextSegment = false
        activeChunkPrefetchJob?.cancel()
        activeChunkPrefetchJob = null
        queuedChunkStartBlocks.clear()
        chunkFileNameByMediaId.values.forEach { Rpf7Archive.unpinFile(it) }
        chunkFileNameByMediaId.clear()
        chunkStartSampleByMediaId.clear()
        chunkMediaIdByUri.clear()

        val isContinuousMix = isContinuousMixStation() || (currentSongItem != null && Rpf7MetadataRepository.getTrackMeta(currentSongItem!!.trackName).isContinuousMix)
        if (isContinuousMix && allSongItems.isNotEmpty()) {
            setStationOnlyMetadata()
            prepareContinuousMixSegment()
            return
        }

        val prefetched = prefetchedItem
        prefetchedItem = null
        if (prefetched != null && areSegmentFilesValid(prefetched.uris)) {
            currentSegmentUris = prefetched.uris
            for (u in currentSegmentUris) pinAudioUri(u)
            currentSongItem = prefetched.songItem
            lastSegment = prefetched.segmentType
            pendingBreakClips.clear()
            prefetched.pendingBreakClips?.let { pendingBreakClips.addAll(it) }
            plannedOverlayCue = prefetched.plannedOverlayCue
            plannedPreRollCue = prefetched.plannedPreRollCue
            introItemCount = if (prefetched.plannedOverlayCue != null || prefetched.plannedPreRollCue != null) 0 else if (currentSegmentUris.size > 1 && lastSegment == RadioSegmentType.Song) 1 else 0
            if (prefetched.songItem != null) {
                val totalBlocks = prefetched.songItem.archive.getEntryBlockCount(prefetched.songItem.trackName)
                val chunkBlocks = prefetched.songItem.archive.getChunkBlocksFor(prefetched.songItem.trackName)
                val chunkThreshold = prefetched.songItem.archive.getChunkThresholdFor(prefetched.songItem.trackName)
                currentMultiChunkTotalBlocks = totalBlocks
                currentMultiChunkNextBlock = if (totalBlocks > chunkThreshold) chunkBlocks else totalBlocks
                currentMultiChunksPlayedInRow = 1
                if (totalBlocks > chunkThreshold) {
                    val sOff = prefetched.songItem.archive.getChunkStartSample(prefetched.songItem.trackName, 0)
                    val chunkMediaId = buildChunkMediaId(prefetched.songItem.trackName, 0)
                    chunkStartSampleByMediaId[chunkMediaId] = sOff
                    chunkMediaIdByUri[currentSegmentUris.last()] = chunkMediaId
                    chunkFileNameByMediaId[chunkMediaId] = currentSegmentUris.last().toString()
                    Rpf7Archive.pinFile(currentSegmentUris.last().toString())
                    queuedChunkStartBlocks.add(0)
                }
            }
            return
        }

        val event = director.pickNextEvent { song ->
            if (song.introKeys.isNotEmpty()) {
                val intro = song.introKeys[rng.nextInt(song.introKeys.size)]
                Rpf7ClipRef(song.archive, intro)
            } else null
        }
        setStationOnlyMetadata()
        when (event) {
            is NextBroadcastEvent.SongSweepItem -> {
                val item = event.song
                val uris = ArrayList<Uri>()
                val markers = item.archive.readDjMarkers(item.trackName)

                var djIntroUri: Uri? = null
                var djDurMs = 0L
                if (event.djIntro != null) {
                    val u = materializeClip(event.djIntro.trackKey)
                    if (u != null) {
                        pinAudioUri(u)
                        djIntroUri = u
                        djDurMs = readAudioDurationMs(context, u)
                    }
                }

                val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
                val chunkBlocks = item.archive.getChunkBlocksFor(item.trackName)
                val chunkThreshold = item.archive.getChunkThresholdFor(item.trackName)
                val mainUri = if (totalBlocks > chunkThreshold) {
                    item.archive.materializeBlockChunkUri(item.trackName, 0, chunkBlocks)
                } else {
                    item.archive.materializeAudioUri(item.trackName)
                }
                if (mainUri != null) {
                    pinAudioUri(mainUri)
                }

                val fitsInIntro = djIntroUri != null && markers != null && markers.hasIntroWindow &&
                        djDurMs > 0L && djDurMs <= markers.introDurationMs
                val canPreRoll = djIntroUri != null && markers != null && markers.hasIntroWindow &&
                        djDurMs > markers.introDurationMs && markers.introEndMs >= 2000L && markers.effectiveIntroStartMs <= 3000L

                when {
                    fitsInIntro -> {
                        mainUri?.let { uris.add(it) }
                        val djStartMs = maxOf(markers!!.effectiveIntroStartMs, markers.introEndMs - djDurMs - 250L)
                        plannedOverlayCue = PlannedOverlayCue(djIntroUri!!, djStartMs, markers.introEndMs)
                        introItemCount = 0
                        GTRadioLogger.logVerbose("DJ-OVERLAY") {
                            "${item.trackName}: fits-in-intro [${markers.effectiveIntroStartMs}..${markers.introEndMs}ms], djDur=${djDurMs}ms, fireAt=${djStartMs}ms"
                        }
                    }
                    canPreRoll -> {
                        mainUri?.let { uris.add(it) }
                        val preRollDelayMs = (djDurMs - markers!!.introEndMs + 250L).coerceIn(500L, (djDurMs - 500L).coerceAtLeast(500L))
                        plannedPreRollCue = PlannedPreRollCue(djIntroUri!!, preRollDelayMs, markers.introEndMs)
                        introItemCount = 0
                        GTRadioLogger.logVerbose("DJ-OVERLAY") {
                            "${item.trackName}: pre-roll djDur=${djDurMs}ms, introEnd=${markers.introEndMs}ms, delay=${preRollDelayMs}ms"
                        }
                    }
                    else -> {
                        if (djIntroUri != null) {
                            uris.add(djIntroUri)
                        }
                        if (mainUri != null) {
                            uris.add(mainUri)
                        }
                        introItemCount = if (uris.size > 1) 1 else 0
                    }
                }

                if (totalBlocks > chunkThreshold && mainUri != null) {
                    val sOff = item.archive.getChunkStartSample(item.trackName, 0)
                    val chunkMediaId = buildChunkMediaId(item.trackName, 0)
                    chunkStartSampleByMediaId[chunkMediaId] = sOff
                    chunkMediaIdByUri[mainUri] = chunkMediaId
                    chunkFileNameByMediaId[chunkMediaId] = mainUri.toString()
                    Rpf7Archive.pinFile(mainUri.toString())
                    queuedChunkStartBlocks.add(0)
                }
                currentSegmentUris = uris
                currentSongItem = item
                lastSegment = RadioSegmentType.Song
                currentMultiChunkTotalBlocks = totalBlocks
                currentMultiChunkNextBlock = if (totalBlocks > chunkThreshold) chunkBlocks else totalBlocks
                currentMultiChunksPlayedInRow = 1
                pendingBreakClips.clear()
            }
            is NextBroadcastEvent.BreakBlockItem -> {
                setStationOnlyMetadata()
                val clips = ArrayList(event.clips)
                val upfrontClips = ArrayList<DirectorBreakClip<Rpf7ClipRef>>()
                if (clips.isNotEmpty() && clips[0].segmentType in listOf(RadioSegmentType.Announcer, RadioSegmentType.DJChatter)) {
                    upfrontClips.add(clips.removeAt(0))
                }
                if (clips.isNotEmpty()) {
                    upfrontClips.add(clips.removeAt(0))
                }
                val uris = ArrayList<Uri>()
                for (c in upfrontClips) {
                    val u = c.clip.archive.materializeAudioUri(c.clip.trackKey)
                    if (u != null) {
                        uris.add(u)
                        pinAudioUri(u)
                    }
                }
                currentSegmentUris = uris
                currentSongItem = null
                lastSegment = upfrontClips.firstOrNull()?.segmentType ?: RadioSegmentType.Commercial
                pendingBreakClips.clear()
                pendingBreakClips.addAll(clips)
                introItemCount = 0
            }
            is NextBroadcastEvent.MidSweepInterludeItem -> {
                setStationOnlyMetadata()
                val ref = event.clip
                val u = ref.archive.materializeAudioUri(ref.trackKey)
                if (u != null) {
                    pinAudioUri(u)
                    currentSegmentUris = listOf(u)
                }
                currentSongItem = null
                lastSegment = event.segmentType
                pendingBreakClips.clear()
                introItemCount = 0
            }
        }
    }

    private fun prepareContinuousMixSegment() {
        val item = allSongItems[rng.nextInt(allSongItems.size)]
        val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
        val chunkBlocks = MIX_CHUNK_BLOCKS
        val chunkThreshold = item.archive.getChunkThresholdFor(item.trackName)
        val startBlk = if (totalBlocks > chunkBlocks * 2) {
            (rng.nextInt(totalBlocks / chunkBlocks) * chunkBlocks).coerceAtMost(totalBlocks - chunkBlocks)
        } else {
            0
        }
        if (!queuedChunkStartBlocks.add(startBlk)) return
        val mainUri = item.archive.materializeBlockChunkUri(item.trackName, startBlk, chunkBlocks) ?: return
        pinAudioUri(mainUri)
        currentMultiChunkTotalBlocks = totalBlocks
        currentMultiChunkNextBlock = startBlk + chunkBlocks
        currentMultiChunksPlayedInRow = 1

        val chunkMediaId = buildChunkMediaId(item.trackName, startBlk)
        val sOff = item.archive.getChunkStartSample(item.trackName, startBlk)
        chunkStartSampleByMediaId[chunkMediaId] = sOff
        chunkMediaIdByUri[mainUri] = chunkMediaId
        chunkFileNameByMediaId[chunkMediaId] = mainUri.toString()

        val markers = item.archive.readTrackMarkers(item.trackName)
        if (markers.isNotEmpty()) {
            activeMixMarkers = markers
            activeMixChunkStartSample = sOff
            activeMixSampleRate = item.archive.getEntrySampleRate(item.trackName)
            lastDisplayedMixTrackId = -1
        } else {
            stopMixPoller(clearMarkers = true)
        }

        introItemCount = 0
        Rpf7Archive.pinFile(mainUri.toString())

        currentSongItem = item
        lastSegment = RadioSegmentType.Song
        currentSegmentUris = listOf(mainUri)
        pendingBreakClips.clear()
        savedMixAbsMs = 0L
        continuousMixItem = item
    }

    private fun startLookAheadMonitor(expectedGen: Int, totalDurationMs: Long) {
        lookAheadJob?.cancel()
        isPrefetching = false
        val songItem = currentSongItem
        val markers = songItem?.let { it.archive.readDjMarkers(it.trackName) }

        lookAheadJob = stationScope.launch {
            var outroOverlapHandled = false
            while (isActive && _playing && playGeneration.get() == expectedGen) {
                delay(250L)
                val pos = player.getCurrentTrackPosition().coerceAtLeast(0L)
                val remainingMs = totalDurationMs - pos

                val exo = player.exoPlayer
                val curWinIdx = exo.currentMediaItemIndex
                val isFinalWindow = curWinIdx >= exo.mediaItemCount - 1
                val isFinalChunk = currentMultiChunkTotalBlocks <= 0 || currentMultiChunkNextBlock >= currentMultiChunkTotalBlocks

                val hasValidOutro = markers != null && markers.hasOutroWindow && markers.outroStartMs > (markers.introEndMs.coerceAtLeast(0L) + 15_000L)

                // 1. Trigger background prefetch ONLY on final chunk window of the song, guarded by hasPrefetchedNextSegment
                val outroPreloadTrigger = hasValidOutro && pos >= (markers!!.outroStartMs - 15_000L)
                if (isFinalWindow && isFinalChunk && !hasPrefetchedNextSegment && !isPrefetching && (remainingMs in 1..25_000L || outroPreloadTrigger)) {
                    hasPrefetchedNextSegment = true
                    isPrefetching = true
                    stationScope.launch(StationDispatchers.ioDispatcher) {
                        if (!_playing || playGeneration.get() != expectedGen) return@launch
                        prefetchNextEvent(expectedGen)
                    }
                }

                // 2. Outro overlap (strictly only on the final chunk of the song! Music ducks 0.50f, restores when DJ finishes; song plays naturally to completion)
                if (isFinalWindow && isFinalChunk && hasValidOutro && markers != null) {
                    if (!outroOverlapHandled && pos >= markers.outroStartMs) {
                        val pItem = prefetchedItem
                        if (pItem != null && pItem.uris.isNotEmpty()) {
                            val isVoiceLeadIn = pItem.segmentType in listOf(
                                RadioSegmentType.Announcer, RadioSegmentType.DJChatter,
                                RadioSegmentType.NewsReport, RadioSegmentType.WeatherReport
                            ) || pItem.event is NextBroadcastEvent.MidSweepInterludeItem ||
                               (pItem.event is NextBroadcastEvent.BreakBlockItem && pItem.uris.isNotEmpty())

                            if (isVoiceLeadIn) {
                                outroOverlapHandled = true
                                val voiceUri = pItem.uris.first()
                                GTRadioLogger.logVerbose("DJ-OVERLAY") {
                                    "${songItem?.trackName}: outro overlap at ${pos}ms -> voice clip '$voiceUri' (${pItem.segmentType})"
                                }
                                if (pItem.uris.size > 1) {
                                    prefetchedItem = pItem.copy(uris = pItem.uris.drop(1))
                                } else {
                                    prefetchedItem = null
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    player.djVoiceOverlayPlayer?.playOverlay(voiceUri, expectedGen.toLong(), player.isMutedForTuningStatic)
                                    player.setMusicDuckGain(0.50f, 300L, stationScope)
                                }

                                val djDur = readAudioDurationMs(context, voiceUri).coerceAtLeast(1000L)
                                val startWait = System.currentTimeMillis()

                                stationScope.launch {
                                    while (isActive && _playing && playGeneration.get() == expectedGen) {
                                        val elapsed = System.currentTimeMillis() - startWait
                                        val djFinished = elapsed >= djDur || player.djVoiceOverlayPlayer?.isPlaying == false

                                        if (djFinished) {
                                            // DJ clip finished, restore duck gain and let song finish naturally to STATE_ENDED
                                            withContext(Dispatchers.Main.immediate) {
                                                player.setMusicDuckGain(1.0f, 500L, stationScope)
                                            }
                                            break
                                        }
                                        delay(100L)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun prefetchNextEvent(expectedGen: Int) {
        if (!_playing || playGeneration.get() != expectedGen) return
        val event = director.pickNextEvent { song ->
            if (song.introKeys.isNotEmpty()) {
                val intro = song.introKeys[rng.nextInt(song.introKeys.size)]
                Rpf7ClipRef(song.archive, intro)
            } else null
        }
        when (event) {
            is NextBroadcastEvent.SongSweepItem -> {
                val item = event.song
                val uris = ArrayList<Uri>()
                val pinned = ArrayList<String>()
                val markers = item.archive.readDjMarkers(item.trackName)

                var djIntroUri: Uri? = null
                var djDurMs = 0L
                if (event.djIntro != null) {
                    val u = materializeClip(event.djIntro.trackKey)
                    if (u != null) {
                        val p = u.toString()
                        pinned.add(p)
                        Rpf7Archive.pinFile(p)
                        djIntroUri = u
                        djDurMs = readAudioDurationMs(context, u)
                    }
                }
                val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
                val chunkThreshold = item.archive.getChunkThresholdFor(item.trackName)
                val chunkBlocks = item.archive.getChunkBlocksFor(item.trackName)
                val mainUri = if (totalBlocks > chunkThreshold) {
                    item.archive.materializeBlockChunkUri(item.trackName, 0, chunkBlocks)
                } else {
                    item.archive.materializeAudioUri(item.trackName)
                }
                if (mainUri != null) {
                    val p = mainUri.toString()
                    pinned.add(p)
                    Rpf7Archive.pinFile(p)
                }

                val fitsInIntro = djIntroUri != null && markers != null && markers.hasIntroWindow &&
                        djDurMs > 0L && djDurMs <= markers.introDurationMs
                val canPreRoll = djIntroUri != null && markers != null && markers.hasIntroWindow &&
                        djDurMs > markers.introDurationMs && markers.introEndMs >= 2000L && markers.effectiveIntroStartMs <= 3000L

                var pOverlayCue: PlannedOverlayCue? = null
                var pPreRollCue: PlannedPreRollCue? = null

                when {
                    fitsInIntro -> {
                        mainUri?.let { uris.add(it) }
                        val djStartMs = maxOf(markers!!.effectiveIntroStartMs, markers.introEndMs - djDurMs - 250L)
                        pOverlayCue = PlannedOverlayCue(djIntroUri!!, djStartMs, markers.introEndMs)
                    }
                    canPreRoll -> {
                        mainUri?.let { uris.add(it) }
                        val preRollDelayMs = (djDurMs - markers!!.introEndMs + 250L).coerceIn(500L, (djDurMs - 500L).coerceAtLeast(500L))
                        pPreRollCue = PlannedPreRollCue(djIntroUri!!, preRollDelayMs, markers.introEndMs)
                    }
                    else -> {
                        if (djIntroUri != null) uris.add(djIntroUri)
                        if (mainUri != null) uris.add(mainUri)
                    }
                }

                if (uris.isNotEmpty() && _playing && playGeneration.get() == expectedGen) {
                    prefetchedItem = PrefetchedBroadcastItem(
                        uris = uris,
                        pinnedFiles = pinned,
                        event = event,
                        songItem = item,
                        segmentType = RadioSegmentType.Song,
                        plannedOverlayCue = pOverlayCue,
                        plannedPreRollCue = pPreRollCue
                    )
                    GTRadioLogger.logVerbose("DIRECTOR") {
                        "Rpf7[$stationTitle]: Look-ahead prefetched next song '${item.trackName}' (intro=${event.djIntro != null})"
                    }
                } else {
                    pinned.forEach { Rpf7Archive.unpinFile(it) }
                }
            }
            is NextBroadcastEvent.BreakBlockItem -> {
                val clips = ArrayList(event.clips)
                val upfrontClips = ArrayList<DirectorBreakClip<Rpf7ClipRef>>()
                if (clips.isNotEmpty() && clips[0].segmentType in listOf(RadioSegmentType.Announcer, RadioSegmentType.DJChatter)) {
                    upfrontClips.add(clips.removeAt(0))
                }
                if (clips.isNotEmpty()) {
                    upfrontClips.add(clips.removeAt(0))
                }
                val uris = ArrayList<Uri>()
                val pinned = ArrayList<String>()
                for (c in upfrontClips) {
                    val u = c.clip.archive.materializeAudioUri(c.clip.trackKey)
                    if (u != null) {
                        uris.add(u)
                        val p = u.toString()
                        pinned.add(p)
                        Rpf7Archive.pinFile(p)
                    }
                }
                if (uris.isNotEmpty() && _playing && playGeneration.get() == expectedGen) {
                    prefetchedItem = PrefetchedBroadcastItem(
                        uris = uris,
                        pinnedFiles = pinned,
                        event = event,
                        songItem = null,
                        segmentType = upfrontClips.first().segmentType,
                        pendingBreakClips = clips
                    )
                    GTRadioLogger.logVerbose("DIRECTOR") {
                        "Rpf7[$stationTitle]: Look-ahead prefetched break upfront (${upfrontClips.size} clips, ${clips.size} queued)"
                    }
                } else {
                    pinned.forEach { Rpf7Archive.unpinFile(it) }
                }
            }
            is NextBroadcastEvent.MidSweepInterludeItem -> {
                val ref = event.clip
                val u = ref.archive.materializeAudioUri(ref.trackKey)
                if (u != null && _playing && playGeneration.get() == expectedGen) {
                    val p = u.toString()
                    Rpf7Archive.pinFile(p)
                    prefetchedItem = PrefetchedBroadcastItem(
                        uris = listOf(u),
                        pinnedFiles = listOf(p),
                        event = event,
                        songItem = null,
                        segmentType = event.segmentType
                    )
                    GTRadioLogger.logVerbose("DIRECTOR") {
                        "Rpf7[$stationTitle]: Look-ahead prefetched interlude '${ref.trackKey}' (${event.segmentType})"
                    }
                }
            }
        }
    }

    private fun prefetchAndQueueNextChunk(expectedGen: Int = playGeneration.get()) {
        val item = currentSongItem ?: return
        val totalBlocks = currentMultiChunkTotalBlocks
        val nextBlock = currentMultiChunkNextBlock
        val isContinuousMix = isContinuousMixStation() || Rpf7MetadataRepository.getTrackMeta(item.trackName).isContinuousMix
        val maxChunks = if (isContinuousMix) 6 else 100
        val chunkBlocks = if (isContinuousMix) MIX_CHUNK_BLOCKS else item.archive.getChunkBlocksFor(item.trackName)

        if (currentMultiChunksPlayedInRow >= maxChunks || nextBlock >= totalBlocks) return
        if (!queuedChunkStartBlocks.add(nextBlock)) return

        currentMultiChunkNextBlock = nextBlock + chunkBlocks
        currentMultiChunksPlayedInRow += 1

        activeChunkPrefetchJob?.cancel()
        activeChunkPrefetchJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            if (!_playing || playGeneration.get() != expectedGen) return@launch
            val nextUri = item.archive.materializeBlockChunkUri(item.trackName, nextBlock, chunkBlocks) ?: return@launch
            if (!_playing || playGeneration.get() != expectedGen) return@launch
            val nextStartSample = item.archive.getChunkStartSample(item.trackName, nextBlock)
            val chunkMediaId = buildChunkMediaId(item.trackName, nextBlock)
            chunkStartSampleByMediaId[chunkMediaId] = nextStartSample
            chunkMediaIdByUri[nextUri] = chunkMediaId
            chunkFileNameByMediaId[chunkMediaId] = nextUri.toString()
            Rpf7Archive.pinFile(nextUri.toString())

            val mediaItem = MediaItem.Builder().setUri(nextUri).setMediaId(chunkMediaId).build()
            withContext(Dispatchers.Main.immediate) {
                if (_playing && playGeneration.get() == expectedGen) {
                    player.exoPlayer.addMediaItem(mediaItem)
                }
            }
        }
    }

    private fun startPlayback(uris: List<Uri>, startWindowIndex: Int, startPositionMs: Long, expectedGen: Int = playGeneration.get()) {
        val totalDur = if (uris.isNotEmpty()) getFileDurationMs(uris.last()) else 0L
        val preRoll = plannedPreRollCue
        plannedPreRollCue = null
        val overlayCue = plannedOverlayCue
        plannedOverlayCue = null

        if (preRoll != null && startWindowIndex == 0 && startPositionMs == 0L) {
            // Case 2: Pre-roll
            player.djVoiceOverlayPlayer?.playOverlay(preRoll.djUri, expectedGen.toLong(), player.isMutedForTuningStatic) {
                player.setMusicDuckGain(1.0f, 400L, stationScope)
                unpinAudioUri(preRoll.djUri)
            }
            activeOverlayJob?.cancel()
            activeOverlayJob = stationScope.launch {
                var elapsed = 0L
                while (isActive && _playing && playGeneration.get() == expectedGen && elapsed < preRoll.preRollDelayMs) {
                    delay(50L)
                    if (player.isPlaying || player.djVoiceOverlayPlayer?.isPlaying == true) {
                        elapsed += 50L
                    }
                }
                if (!_playing || playGeneration.get() != expectedGen) return@launch
                withContext(Dispatchers.Main.immediate) {
                    player.setMusicDuckGain(0.50f, 0L, stationScope)
                    executeStartPlayback(uris, startWindowIndex, startPositionMs, expectedGen, totalDur)
                }
            }
        } else {
            executeStartPlayback(uris, startWindowIndex, startPositionMs, expectedGen, totalDur)
            if (overlayCue != null && startWindowIndex == 0 && startPositionMs == 0L) {
                // Case 1: Fits in intro
                activeOverlayJob?.cancel()
                activeOverlayJob = stationScope.launch {
                    var overlayFired = false
                    while (isActive && _playing && playGeneration.get() == expectedGen) {
                        delay(50L)
                        val pos = player.getCurrentTrackPosition().coerceAtLeast(0L)
                        if (!overlayFired && pos >= overlayCue.djStartMs) {
                            overlayFired = true
                            withContext(Dispatchers.Main.immediate) {
                                player.setMusicDuckGain(0.50f, 300L, stationScope)
                                player.djVoiceOverlayPlayer?.playOverlay(overlayCue.djUri, expectedGen.toLong(), player.isMutedForTuningStatic) {
                                    player.setMusicDuckGain(1.0f, 400L, stationScope)
                                    unpinAudioUri(overlayCue.djUri)
                                }
                            }
                        }
                        if (overlayFired && pos >= overlayCue.introEndMs) {
                            withContext(Dispatchers.Main.immediate) {
                                player.setMusicDuckGain(1.0f, 400L, stationScope)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun executeStartPlayback(uris: List<Uri>, startWindowIndex: Int, startPositionMs: Long, expectedGen: Int = playGeneration.get(), totalDur: Long = 0L) {
        mainHandler.post {
            if (!_playing || playGeneration.get() != expectedGen) {
                GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: startPlayback aborted: generation mismatch or not playing (gen=$expectedGen, cur=${playGeneration.get()})")
                return@post
            }

            refreshCurrentSegmentMetadata()

            if (activeListener != null) {
                player.exoPlayer.removeListener(activeListener!!)
                activeListener = null
            }

            player.exoPlayer.clearMediaItems()
            for (u in uris) {
                val chunkMediaId = chunkMediaIdByUri[u]
                val mediaItem = if (chunkMediaId != null) {
                    MediaItem.Builder().setUri(u).setMediaId(chunkMediaId).build()
                } else {
                    MediaItem.fromUri(u)
                }
                player.exoPlayer.addMediaItem(mediaItem)
            }

            if (!_playing || playGeneration.get() != expectedGen) return@post

            if (startWindowIndex > 0 || startPositionMs > 0L) {
                player.exoPlayer.seekTo(startWindowIndex, startPositionMs)
            }
            updateMetadataForWindowIndex(startWindowIndex, expectedGen)

            activeListener = object : Player.Listener {
                private var alreadyHandled = false

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && activeMixMarkers.isNotEmpty()) {
                        val mediaId = player.exoPlayer.currentMediaItem?.mediaId
                        if (mediaId != null && chunkStartSampleByMediaId.containsKey(mediaId)) {
                            updateMixTrackMetadata()
                        }
                    }
                    if (!alreadyHandled && state == Player.STATE_ENDED) {
                        alreadyHandled = true
                        player.exoPlayer.removeListener(this)
                        if (_playing && playGeneration.get() == expectedGen) {
                            if (pendingBreakClips.isEmpty() && director.currentPhase == DirectorPhase.BREAK_BLOCK) {
                                director.onBreakCompleted()
                            }
                            playNext()
                        }
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val currentMediaId = mediaItem?.mediaId ?: player.exoPlayer.currentMediaItem?.mediaId
                    if (currentMediaId != null && currentMediaId.startsWith("rpf7_chunk_")) {
                        chunkStartSampleByMediaId[currentMediaId]?.let { sOff ->
                            activeMixChunkStartSample = sOff
                        }
                        val curBlock = currentMediaId.substringAfterLast('_').toIntOrNull()
                        if (curBlock != null) {
                            val oldEntries = chunkFileNameByMediaId.entries.filter { (mId, _) ->
                                val blk = mId.substringAfterLast('_').toIntOrNull()
                                blk != null && blk < curBlock
                            }
                            for ((mId, uriStr) in oldEntries) {
                                chunkFileNameByMediaId.remove(mId)
                                chunkStartSampleByMediaId.remove(mId)
                                Rpf7Archive.unpinFile(uriStr)
                            }
                        }
                    }
                    val curIdx = player.exoPlayer.currentMediaItemIndex
                    updateMetadataForWindowIndex(curIdx, expectedGen)

                    // Staged Break Decoding: while Clip #1 plays, decode Clip #2; while Clip #2 plays, decode remaining clips
                    if (pendingBreakClips.isNotEmpty()) {
                        val nextClip = pendingBreakClips.removeAt(0)
                        stationScope.launch(StationDispatchers.ioDispatcher) {
                            if (!_playing || playGeneration.get() != expectedGen) return@launch
                            val uri = nextClip.clip.archive.materializeAudioUri(nextClip.clip.trackKey) ?: return@launch
                            pinAudioUri(uri)
                            withContext(Dispatchers.Main.immediate) {
                                if (_playing && playGeneration.get() == expectedGen) {
                                    player.exoPlayer.addMediaItem(MediaItem.fromUri(uri))
                                    GTRadioLogger.logVerbose("DIRECTOR") {
                                        "Rpf7[$stationTitle]: Staged queued break clip '${nextClip.clip.trackKey}' (${nextClip.label})"
                                    }
                                }
                            }
                        }
                    }

                    if (currentSongItem != null && currentMultiChunkTotalBlocks > 0 && isContinuousMixStation()) {
                        prefetchAndQueueNextChunk(expectedGen)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: ExoPlayer error on track: ${error.message}", error)
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
            if (!_playing || playGeneration.get() != expectedGen) return@post
            player.prepare()
            player.playInternal()

            if (activeMixMarkers.isNotEmpty()) {
                startMixPoller(expectedGen)
            } else {
                stopMixPoller(clearMarkers = false)
            }

            if (currentMultiChunkTotalBlocks > 0 && currentMultiChunkNextBlock < currentMultiChunkTotalBlocks && isContinuousMixStation()) {
                prefetchAndQueueNextChunk(expectedGen)
            }

            // Start look-ahead prefetch monitor for discrete songs
            if (!isContinuousMixStation() && currentSongItem != null) {
                startLookAheadMonitor(expectedGen, totalDur)
            }
        }
    }

    private fun startMixPoller(expectedGen: Int = playGeneration.get()) {
        if (activePollerJob?.isActive == true) return
        activePollerJob = stationScope.launch {
            while (isActive && _playing && playGeneration.get() == expectedGen && activeMixMarkers.isNotEmpty()) {
                val exo = player.exoPlayer
                val mediaId = exo.currentMediaItem?.mediaId
                if (exo.playbackState == Player.STATE_READY && mediaId != null && chunkStartSampleByMediaId.containsKey(mediaId)) {
                    updateMixTrackMetadata()
                }
                delay(1000L)
            }
        }
    }

    private fun stopMixPoller(clearMarkers: Boolean = false) {
        activePollerJob?.cancel()
        activePollerJob = null
        if (clearMarkers) {
            activeMixMarkers = emptyList()
            activeMixChunkStartSample = 0L
            lastDisplayedMixTrackId = -1
        }
    }

    private fun updateMixTrackMetadata() {
        if (!_playing || activeMixMarkers.isEmpty()) return
        val exo = player.exoPlayer
        if (exo.playbackState != Player.STATE_READY) return
        val mediaId = exo.currentMediaItem?.mediaId ?: return
        val baseSample = chunkStartSampleByMediaId[mediaId] ?: return
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }
        val posMs = exo.currentPosition.coerceAtLeast(0L).let {
            if (exo.duration > 0) it.coerceAtMost(exo.duration) else it
        }
        val currentSample = baseSample + (posMs * activeMixSampleRate) / 1000L

        var resolvedTrackId = -1
        for (m in activeMixMarkers) {
            if (m.sampleOffset <= currentSample) {
                resolvedTrackId = m.trackId
            } else {
                break
            }
        }

        if (resolvedTrackId == lastDisplayedMixTrackId) return
        lastDisplayedMixTrackId = resolvedTrackId

        GTRadioLogger.logVerbose("TRACKID") {
            "Rpf7RadioStation[$stationTitle]: TRACKID transition -> $resolvedTrackId at sample $currentSample (${currentSample / activeMixSampleRate.toDouble()}s)"
        }

        val meta = if (resolvedTrackId > 0) Rpf7MetadataRepository.getTrackByTrackId(resolvedTrackId) else null
        if (meta != null) {
            val displayTitle = meta.title
            val displayArtist = meta.artist
            var art: Bitmap? = stationLogo
            if (isAlbumArtEnabled()) {
                val cached = Rpf7MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle)
                if (cached != null) {
                    art = cached
                } else {
                    val expectedGen = playGeneration.get()
                    val currentTid = resolvedTrackId
                    Rpf7MetadataRepository.fetchEraAccurateArtAsync(context, displayArtist, displayTitle, maxYear = 2021) {
                        mainHandler.post {
                            if (_playing && playGeneration.get() == expectedGen && lastDisplayedMixTrackId == currentTid) {
                                val newArt = Rpf7MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle) ?: stationLogo
                                val alb = Rpf7MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
                                val sub = if (alb.isNotBlank()) "$displayArtist • $alb" else "$displayArtist • $stationTitle"
                                _currentMetadata = buildStationMetadata(
                                    trackTitle = displayTitle,
                                    trackSubtitle = sub,
                                    art = newArt,
                                    isSongOrShow = true,
                                    cleanArtist = displayArtist,
                                    cleanAlbum = alb
                                )
                                onMetadataChanged?.invoke(_currentMetadata)
                            }
                        }
                    }
                }
            }

            val eraAlbum = Rpf7MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
            val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
            _currentMetadata = buildStationMetadata(
                trackTitle = displayTitle,
                trackSubtitle = subtitle,
                art = art,
                isSongOrShow = true,
                cleanArtist = displayArtist,
                cleanAlbum = eraAlbum
            )
            onMetadataChanged?.invoke(_currentMetadata)
        } else if (lastDisplayedMixTrackId <= 0) {
            // First track marker hasn't been hit yet; show initial mix title cleanly without losing poller
            val trackMeta = currentSongItem?.let { Rpf7MetadataRepository.getTrackMeta(it.trackName, stationTitle) }
            val dTitle = trackMeta?.title ?: stationTitle
            val dArtist = trackMeta?.artist ?: ""
            val sub = if (dArtist.isNotBlank() && dArtist != stationTitle) "$dArtist • $stationTitle" else stationTitle
            _currentMetadata = buildStationMetadata(dTitle, sub, stationLogo, isSongOrShow = true, cleanArtist = dArtist)
            onMetadataChanged?.invoke(_currentMetadata)
        }
    }

    private fun isContinuousMixStation(): Boolean {
        val rpf = stationArchive.rpfName.lowercase(Locale.ROOT)
        val key = stationKey.lowercase(Locale.ROOT)
        val currentTrackMix = currentSongItem?.let { Rpf7MetadataRepository.getTrackMeta(it.trackName).isContinuousMix } ?: false
        return isContinuousMixStation ||
            currentTrackMix ||
            Rpf7MetadataRepository.isContinuousMix(stationArchive.rpfName) ||
            rpf.contains("radio_07") || rpf.contains("radio_08") || rpf.contains("radio_13") || rpf.contains("radio_14") ||
            rpf.contains("mexican") ||
            key.contains("radio_07") || key.contains("radio_08") || key.contains("radio_13") || key.contains("radio_14") ||
            key.contains("mexican")
    }

    private fun isMixStation(): Boolean {
        return isContinuousMixStation()
    }

    private fun isTalk(): Boolean {
        val rpf = stationArchive.rpfName.lowercase(Locale.ROOT)
        val key = stationKey.lowercase(Locale.ROOT)
        return isTalkRadio ||
            rpf.contains("radio_05") || rpf.contains("radio_11") || rpf.contains("talk") ||
            key.contains("radio_05") || key.contains("radio_11") || key.contains("talk")
    }

    private fun getDiscreteSongItems(): List<Rpf7SongItem> {
        ensureStationIndexed()
        if (isTalk() || isMixStation()) return emptyList()
        return allSongItems.filter { item ->
            val stem = item.trackName.lowercase(Locale.ROOT).removeSuffix(".awc")
            stem !in setOf("time", "to", "intro", "general") &&
                !stem.startsWith("id_") &&
                !stem.startsWith("mono_solo_") &&
                !stem.contains("mix")
        }
    }

    override fun supportsTracklist(): Boolean {
        ensureStationIndexed()
        if (isTalk() || isMixStation()) return false
        return getDiscreteSongItems().isNotEmpty()
    }

    override fun getCleanTracks(): List<TracklistTrack> {
        val items = getDiscreteSongItems()
        return items.map { item ->
            val meta = Rpf7MetadataRepository.getTrackMeta(item.trackName, stationTitle)
            val title = meta.title
            val artist = meta.artist
            TracklistTrack(
                id = item.trackName,
                title = title,
                artist = artist,
                stationName = stationTitle,
                maxYear = 2021
            )
        }.distinctBy { it.id }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    override fun playCleanTrack(
        trackId: String,
        player: GTRadioPlayer,
        onTrackFinished: () -> Unit,
        onMetadataReady: (title: String, artist: String, album: String, maxYear: Int) -> Unit
    ) {
        val items = getDiscreteSongItems()
        val targetItem = items.find { it.trackName.equals(trackId, ignoreCase = true) } ?: return
        val token = cleanPlayGeneration.incrementAndGet()
        val meta = Rpf7MetadataRepository.getTrackMeta(targetItem.trackName, stationTitle)
        val title = meta.title
        val artist = meta.artist

        activeCleanJob?.cancel()
        activeCleanJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                val album = Rpf7MetadataRepository.getCachedAlbumName(context, artist, title) ?: stationTitle
                withContext(Dispatchers.Main.immediate) {
                    if (token == cleanPlayGeneration.get()) {
                        onMetadataReady(title, artist, album, 2021)
                    }
                }
                val uri = targetItem.archive.materializeFullAudioUri(targetItem.trackName)
                if (token != cleanPlayGeneration.get()) return@launch
                if (uri == null) {
                    GTRadioLogger.log("Rpf7RadioStation: Failed to materialize clean track audio for '${targetItem.trackName}'")
                    withContext(Dispatchers.Main.immediate) { onTrackFinished() }
                    return@launch
                }
                val dur = readAudioDurationMs(context, uri)
                withContext(Dispatchers.Main.immediate) {
                    if (token != cleanPlayGeneration.get()) return@withContext
                    player.playCleanQueue(listOf(uri), listOf(dur)) {
                        if (token == cleanPlayGeneration.get()) {
                            onTrackFinished()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    GTRadioLogger.log("Rpf7RadioStation: Error materializing clean track", e)
                }
                withContext(Dispatchers.Main.immediate) { onTrackFinished() }
            }
        }
    }

    override fun stopCleanTrackPlayback() {
        cleanPlayGeneration.incrementAndGet()
        activeCleanJob?.cancel()
        activeCleanJob = null
        player.stopCleanQueue()
    }

    companion object {
        private const val MIX_CHUNK_BLOCKS = 4
        private val STATION_ID_REGEX = Regex("^id_\\d+.*")
        private val RPF7_INTRO_ALIASES = mapOf(
            "heart_beat" to "heartbeat",
            "tell_to_my_heart" to "tell_it_to_my_heart"
        )
    }
}
