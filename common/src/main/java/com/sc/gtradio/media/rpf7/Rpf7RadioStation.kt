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
import kotlinx.coroutines.CancellationException
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
    private val stationConfig = Rpf7StationConfig.resolve(stationArchive.rpfName, stationKey)
    private val isTalkRadio: Boolean get() = stationConfig.isTalkRadio

    private val director by lazy {
        Rpf7RadioDirector<Rpf7SongItem, Rpf7ClipRef>(
            context = context,
            stationName = stationTitle,
            config = stationConfig,
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
            if (stationConfig.advertPool != Rpf7AdvertPool.NONE) director.adsEnabled = value
        }
    override var weatherChatterEnabled: Boolean = weatherChatterEnabled
        set(value) {
            field = value
            director.weatherChatterEnabled = false
        }
    override var newsReportsEnabled: Boolean = newsReportsEnabled
        set(value) {
            field = value
            if (stationConfig.allowsNews) director.newsReportsEnabled = value
        }

    private val morningDjList = ArrayList<Rpf7ClipRef>()
    private val eveningDjList = ArrayList<Rpf7ClipRef>()

    private val allSongItems = ArrayList<Rpf7SongItem>()
    private val stationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeDecodeJob: Job? = null
    private var seamlessPrepareJob: Job? = null
    private var activeCleanJob: Job? = null
    private var activePollerJob: Job? = null
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    override val supportsSeamlessPrepare: Boolean get() = true

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var activeListener: Player.Listener? = null

    private data class ChunkPlaybackMeta(
        val item: Rpf7SongItem,
        val startBlock: Int,
        val loopEpoch: Int,
        val startSample: Long,
        val uri: Uri
    )

    private data class NextChunkTarget(
        val item: Rpf7SongItem,
        val startBlock: Int,
        val totalBlocks: Int,
        val loopEpoch: Int,
        val chunkBlocks: Int
    )

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: Rpf7SongItem? = null
    private var nextChunkItem: Rpf7SongItem? = null
    private var nextChunkStartBlock: Int = 0
    private var nextChunkTotalBlocks: Int = 0
    private var nextChunkLoopEpoch: Int = 0
    private var currentMultiChunksPlayedInRow: Int = 0
    private var hasStartedContinuousMix: Boolean = false
    private var introItemCount = 0
    private var hasPrefetchedNextSegment: Boolean = false
    private val chunkMetaByMediaId = ConcurrentHashMap<String, ChunkPlaybackMeta>()
    private val chunkMediaIdByUri = ConcurrentHashMap<Uri, String>()
    private val queuedChunkKeys: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private var activeChunkPrefetchJob: Job? = null

    private fun buildChunkKey(entryName: String, loopEpoch: Int, startBlock: Int): String =
        "${entryName.lowercase(Locale.ROOT)}_${loopEpoch}_$startBlock"

    private fun buildChunkMediaId(entryName: String, loopEpoch: Int, startBlock: Int): String =
        "rpf7_chunk_${entryName}_${loopEpoch}_$startBlock"

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
        val event: Gen3BroadcastEvent<Rpf7SongItem, Rpf7ClipRef>,
        val songItem: Rpf7SongItem?,
        val segmentType: RadioSegmentType,
        val pendingBreakClips: List<StandaloneBreakClip<Rpf7ClipRef>>? = null,
        val plannedOverlayCue: PlannedOverlayCue? = null,
        val plannedPreRollCue: PlannedPreRollCue? = null
    )

    private var prefetchedItem: PrefetchedBroadcastItem? = null
    private var lookAheadJob: Job? = null
    private var activeOverlayJob: Job? = null
    private var plannedOverlayCue: PlannedOverlayCue? = null
    private var plannedPreRollCue: PlannedPreRollCue? = null
    private var isPrefetching = false
    private val activePinnedCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val pendingBreakClips = ArrayList<StandaloneBreakClip<Rpf7ClipRef>>()
    private var activeBreakStagingJob: Job? = null
    @Volatile private var isOutroOverlaySpeaking: Boolean = false
    @Volatile private var enoentRetryCount: Int = 0
    @Volatile private var seamlessPrepareActive: Boolean = false
    private var seamlessPreparePrevHasStartedMix: Boolean? = null
    private var seamlessPreparePrevSavedMixAbsMs: Long? = null

    private data class PreparedPlayback(
        val uris: List<Uri>,
        val startWindowIndex: Int,
        val startPositionMs: Long,
        val totalDurMs: Long,
        val preRoll: PlannedPreRollCue?,
        val overlayCue: PlannedOverlayCue?
    )

    private fun findEnoentChunkMeta(error: Throwable?, currentMediaId: String?): Pair<ChunkPlaybackMeta, Boolean>? {
        var cur: Throwable? = error
        var depth = 0
        val msgs = StringBuilder()
        while (cur != null && depth < 10) {
            cur.message?.let { msgs.append(it).append('\n') }
            cur = cur.cause
            depth++
        }
        val combined = msgs.toString()
        val matchedByUri = chunkMetaByMediaId.values.firstOrNull { meta ->
            val fname = meta.uri.lastPathSegment
            !fname.isNullOrBlank() && combined.contains(fname)
        }
        val currentMeta = currentMediaId?.let { chunkMetaByMediaId[it] }
        return when {
            matchedByUri != null -> Pair(matchedByUri, matchedByUri == currentMeta)
            currentMeta != null -> Pair(currentMeta, true)
            else -> null
        }
    }

    private fun isEnoentError(error: Throwable?): Boolean {
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur is java.io.FileNotFoundException) return true
            if (cur.message?.contains("ENOENT", ignoreCase = true) == true) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun pinAudioUri(uri: Uri) {
        val uriStr = uri.toString()
        activePinnedCounts.compute(uriStr) { _, existing ->
            (existing ?: AtomicInteger(0)).apply { incrementAndGet() }
        }
        Rpf7Archive.pinFile(uriStr)
    }

    private fun unpinAudioUri(uri: Uri) {
        val uriStr = uri.toString()
        var decremented = false
        activePinnedCounts.computeIfPresent(uriStr) { _, counter ->
            decremented = true
            if (counter.decrementAndGet() <= 0) null else counter
        }
        if (decremented) {
            Rpf7Archive.unpinFile(uriStr)
        }
    }

    private fun unpinAllActive() {
        val snapshot = activePinnedCounts.entries.toList()
        activePinnedCounts.clear()
        for ((u, counter) in snapshot) {
            repeat(counter.get().coerceAtLeast(0)) {
                Rpf7Archive.unpinFile(u)
            }
        }
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

        stationArchive.ensureIndexed()
        advertsArchive?.ensureIndexed()
        newsArchive?.ensureIndexed()

        val ids = ArrayList<Rpf7ClipRef>()
        val solos = ArrayList<Rpf7ClipRef>()
        val generals = ArrayList<Rpf7ClipRef>()
        val toAds = ArrayList<Rpf7ClipRef>()
        val toNews = ArrayList<Rpf7ClipRef>()
        val introMap = HashMap<String, MutableList<String>>()
        val songItems = ArrayList<Rpf7SongItem>()

        // 1. Build lookup tables for 29-bit Jenkins hashes in voice banks
        val timeHashMap = HashMap<Int, String>()
        for (prefix in listOf("morning", "evening")) {
            for (num in 1..25) {
                val pad = String.format(Locale.ROOT, "%02d", num)
                timeHashMap[Rpf7Crypto.jenkHashString("${prefix}_$pad") and 0x1FFFFFFF] = prefix
                timeHashMap[Rpf7Crypto.jenkHashString("${prefix}_$num") and 0x1FFFFFFF] = prefix
            }
        }

        val toHashMap = HashMap<Int, String>()
        for (prefix in listOf("to_ad", "to_news")) {
            for (num in 1..25) {
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
                        "evening" -> eveningDjList.add(ref)
                    }
                }
                "to" -> {
                    when (toHashMap[h29]) {
                        "to_ad" -> toAds.add(ref)
                        "to_news" -> toNews.add(ref)
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
        director.songQueue.reset(songItems)
        director.morningDjList = morningDjList
        director.eveningDjList = eveningDjList
        allSongItems.clear()
        allSongItems.addAll(songItems)
        director.rebuildTimeOfDayQueueIfNeeded()

        // 4. Index Adverts and News with exact pool routing (excluding TV-only commercial stems)
        val allAdverts = ArrayList<Rpf7ClipRef>()
        if (stationConfig.advertPool != Rpf7AdvertPool.NONE) {
            advertsArchive?.getFileEntries()?.forEach { entry ->
                val stem = entry.name.lowercase(Locale.ROOT).removeSuffix(".awc")
                if (Rpf7RadioDirector.isTvAdvertStem(stem)) return@forEach
                val isEligible = when (stationConfig.advertPool) {
                    Rpf7AdvertPool.COUNTRY -> stem in COUNTRY_AD_STEMS
                    Rpf7AdvertPool.GENERAL -> stem !in COUNTRY_ONLY_AD_STEMS
                    Rpf7AdvertPool.NONE -> false
                }
                if (isEligible) {
                    allAdverts.add(Rpf7ClipRef(advertsArchive, entry.name))
                }
            }
        }
        director.resetAdverts(allAdverts) { it.trackKey }

        val allNews = ArrayList<Rpf7ClipRef>()
        if (stationConfig.allowsNews) {
            newsArchive?.getFileEntries()?.forEach {
                allNews.add(Rpf7ClipRef(newsArchive, it.name))
            }
        }
        director.newsQueue.reset(allNews)

        GTRadioLogger.log(
            "Rpf7RadioStation[$stationTitle]: Indexed ${songItems.size} songs/shows, " +
                    "${ids.size} IDs, ${solos.size} solos, ${generals.size} general DJ clips, " +
                    "${morningDjList.size + eveningDjList.size} TOD clips, " +
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

    private fun notifyMetadataChanged(metadata: MediaMetadata) {
        _currentMetadata = metadata
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onMetadataChanged?.invoke(metadata)
        } else {
            mainHandler.post {
                onMetadataChanged?.invoke(metadata)
            }
        }
    }

    private fun setStationOnlyMetadata() {
        notifyMetadataChanged(buildStationMetadata(stationTitle, "", stationLogo))
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
            notifyMetadataChanged(
                buildStationMetadata(
                    trackTitle = displayTitle,
                    trackSubtitle = subtitle,
                    art = stationLogo,
                    isSongOrShow = true,
                    cleanArtist = displayArtist,
                    cleanAlbum = ""
                )
            )
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
                            notifyMetadataChanged(
                                buildStationMetadata(
                                    trackTitle = displayTitle,
                                    trackSubtitle = sub,
                                    art = newArt,
                                    isSongOrShow = true,
                                    cleanArtist = displayArtist,
                                    cleanAlbum = alb
                                )
                            )
                        }
                    }
                }
            }
        }

        val eraAlbum = Rpf7MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
        val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
        notifyMetadataChanged(
            buildStationMetadata(
                trackTitle = displayTitle,
                trackSubtitle = subtitle,
                art = art,
                isSongOrShow = true,
                cleanArtist = displayArtist,
                cleanAlbum = eraAlbum
            )
        )
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateMetadataForWindowIndex(player.exoPlayer.currentMediaItemIndex)
        } else {
            mainHandler.post {
                updateMetadataForWindowIndex(player.exoPlayer.currentMediaItemIndex)
            }
        }
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

    override fun cancelSeamlessPrepare() {
        playGeneration.incrementAndGet()
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
        if (seamlessPrepareActive) {
            seamlessPrepareActive = false
            seamlessPreparePrevHasStartedMix?.let { hasStartedContinuousMix = it }
            seamlessPreparePrevHasStartedMix = null
            seamlessPreparePrevSavedMixAbsMs?.let { savedMixAbsMs = it }
            seamlessPreparePrevSavedMixAbsMs = null
            plannedOverlayCue = null
            plannedPreRollCue = null
            clearChunkTracking(cancelPrefetch = false)
            unpinAllActive()
        }
    }

    override fun prepareForSeamlessTakeOver(
        onReady: (commit: () -> Unit) -> Unit,
        onFailed: () -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { prepareForSeamlessTakeOver(onReady, onFailed) }
            return
        }
        cancelSeamlessPrepare()
        val currentGen = playGeneration.incrementAndGet()
        seamlessPreparePrevHasStartedMix = hasStartedContinuousMix
        seamlessPreparePrevSavedMixAbsMs = savedMixAbsMs
        seamlessPrepareActive = true
        queuedChunkKeys.clear()

        seamlessPrepareJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                val isValid = { playGeneration.get() == currentGen && isActive }
                if (!isValid()) return@launch
                ensureStationIndexed()
                if (!isValid()) return@launch
                val prepared = resolvePlaybackForStart(emitMetadata = false, isValid = isValid)
                if (!isValid()) return@launch
                if (prepared == null || prepared.uris.isEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        if (playGeneration.get() == currentGen) {
                            cancelSeamlessPrepare()
                            onFailed()
                        }
                    }
                    return@launch
                }
                withContext(Dispatchers.Main.immediate) {
                    if (playGeneration.get() != currentGen) return@withContext
                    seamlessPrepareJob = null
                    onReady {
                        if (playGeneration.get() != currentGen) return@onReady
                        val commitGen = playGeneration.incrementAndGet()
                        GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: seamless commit() (gen=$commitGen)")
                        seamlessPrepareActive = false
                        seamlessPreparePrevHasStartedMix = null
                        seamlessPreparePrevSavedMixAbsMs = null
                        enoentRetryCount = 0
                        isStationActive = true
                        _playing = true
                        activeChunkPrefetchJob?.cancel()
                        activeChunkPrefetchJob = null
                        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
                        player.repeatMode = Player.REPEAT_MODE_OFF
                        player.playWhenReady = false
                        if (prepared.preRoll != null && prepared.startWindowIndex == 0 && prepared.startPositionMs == 0L) {
                            setStationOnlyMetadata()
                        }
                        launchPreparedPlayback(prepared, commitGen)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: seamless prepare error", e)
                withContext(Dispatchers.Main.immediate) {
                    if (playGeneration.get() == currentGen) {
                        cancelSeamlessPrepare()
                        onFailed()
                    }
                }
            }
        }
    }

    override fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stop() }
            return
        }
        val gen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: stop() called (gen=$gen)")
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
        if (seamlessPrepareActive) {
            seamlessPrepareActive = false
            seamlessPreparePrevHasStartedMix?.let { hasStartedContinuousMix = it }
            seamlessPreparePrevHasStartedMix = null
            seamlessPreparePrevSavedMixAbsMs?.let { savedMixAbsMs = it }
            seamlessPreparePrevSavedMixAbsMs = null
        }
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
        stopMixPoller(clearMarkers = true)
        lookAheadJob?.cancel()
        lookAheadJob = null
        isPrefetching = false
        prefetchedItem?.pinnedFiles?.forEach { Rpf7Archive.unpinFile(it) }
        prefetchedItem = null
        pendingBreakClips.clear()

        val isContinuousMix = isContinuousMixStation()
        if (isContinuousMix && currentSongItem != null) {
            val exo = player.exoPlayer
            if (exo.playbackState == Player.STATE_READY) {
                val mediaId = exo.currentMediaItem?.mediaId
                val chunkMeta = if (mediaId != null) chunkMetaByMediaId[mediaId] else null
                if (chunkMeta != null) {
                    val rawPosMs = exo.currentPosition.coerceAtLeast(0L)
                    val curPosMs = if (exo.duration > 0) rawPosMs.coerceAtMost(exo.duration) else rawPosMs
                    val sRate = chunkMeta.item.archive.getEntrySampleRate(chunkMeta.item.trackName).toLong().coerceAtLeast(1L)
                    val currentSample = chunkMeta.startSample + (curPosMs * sRate / 1000L)
                    savedMixAbsMs = (currentSample * 1000L) / sRate
                    continuousMixItem = chunkMeta.item
                    lastStoppedTimeMs = System.currentTimeMillis()
                }
            }
        }

        clearChunkTracking(cancelPrefetch = false)
        unpinAllActive()
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release() }
            return
        }
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { play() }
            return
        }
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
        seamlessPrepareActive = false
        seamlessPreparePrevHasStartedMix = null
        seamlessPreparePrevSavedMixAbsMs = null
        enoentRetryCount = 0
        val currentGen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: play() called (gen=$currentGen)")
        isStationActive = true
        _playing = true
        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

        activeChunkPrefetchJob?.cancel()
        activeChunkPrefetchJob = null
        queuedChunkKeys.clear()

        val isContinuousMix = isContinuousMixStation()
        val canResumeMix = isContinuousMix && savedMixAbsMs > 0L && continuousMixItem != null && lastStoppedTimeMs > 0L
        val canResumeTrack = !isContinuousMix && director.currentPhase != Gen3DirectorPhase.BREAK_BLOCK && lastStoppedTimeMs > 0L && areSegmentFilesValid(currentSegmentUris)
        if (!canResumeMix && !canResumeTrack) {
            setStationOnlyMetadata()
        }

        activeDecodeJob?.cancel()
        activeDecodeJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                val isValid = { _playing && playGeneration.get() == currentGen }
                if (!isValid()) return@launch
                ensureStationIndexed()
                if (!isValid()) return@launch
                val prepared = resolvePlaybackForStart(emitMetadata = true, isValid = isValid)
                if (prepared != null && isValid()) {
                    launchPreparedPlayback(prepared, currentGen)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: activeDecodeJob error", e)
            }
        }
    }

    private suspend fun resolvePlaybackForStart(
        emitMetadata: Boolean,
        isValid: () -> Boolean
    ): PreparedPlayback? {
        val isContinuousMix = isContinuousMixStation()
        val canResumeMix = isContinuousMix && savedMixAbsMs > 0L && continuousMixItem != null && lastStoppedTimeMs > 0L
        val canResumeTrack = !isContinuousMix && director.currentPhase != Gen3DirectorPhase.BREAK_BLOCK && lastStoppedTimeMs > 0L && areSegmentFilesValid(currentSegmentUris)
        if (canResumeMix) {
            val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
            return resolveContinuousMixPrepared(elapsedMs, isValid)
        }
        if (canResumeTrack) {
            val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
            return resolveResumeFromRealTimePrepared(elapsedMs, emitMetadata, isValid)
        }
        if (emitMetadata) {
            withContext(Dispatchers.Main.immediate) {
                if (isValid()) {
                    setStationOnlyMetadata()
                }
            }
        }
        pickNextSegment(emitMetadata)
        if (currentSegmentUris.isEmpty() || !isValid()) return null
        val lastIdx = currentSegmentUris.size - 1
        val mainDur = getFileDurationMs(currentSegmentUris[lastIdx])
        val startOffset = if (lastSegment == RadioSegmentType.Song && currentSegmentUris.size == 1 && mainDur > 45_000L) {
            rng.nextInt(25_000).toLong()
        } else {
            0L
        }
        val totalDur = currentSegmentUris.sumOf { getFileDurationMs(it) }
        val preRoll = plannedPreRollCue
        plannedPreRollCue = null
        val overlay = plannedOverlayCue
        plannedOverlayCue = null
        return PreparedPlayback(currentSegmentUris, 0, startOffset, totalDur, preRoll, overlay)
    }

    private fun isMixItem(item: Rpf7SongItem?): Boolean {
        val trackMix = item?.let { Rpf7MetadataRepository.getTrackMeta(it.trackName).isContinuousMix } ?: false
        return isContinuousMixStation() || trackMix
    }

    private fun getSortedContinuousMixParts(): List<Rpf7SongItem> {
        val mixTagged = allSongItems.filter { Rpf7MetadataRepository.getTrackMeta(it.trackName).isContinuousMix }
        val pool = if (mixTagged.isNotEmpty()) mixTagged else allSongItems
        return pool.sortedBy { it.trackName.lowercase(Locale.ROOT) }
    }

    private fun isValidWavOrAudioFile(uri: Uri?): Boolean {
        val path = uri?.path ?: return false
        val f = File(path)
        return f.exists() && f.length() > 44L
    }

    private fun materializeValidChunkUri(item: Rpf7SongItem, startBlock: Int, chunkBlocks: Int): Uri? {
        val first = item.archive.materializeBlockChunkUri(item.trackName, startBlock, chunkBlocks)
        if (isValidWavOrAudioFile(first)) return first
        // Retry exact same startBlock if cache file was evicted or incomplete; never skip ahead.
        val retry = item.archive.materializeBlockChunkUri(item.trackName, startBlock, chunkBlocks)
        return if (isValidWavOrAudioFile(retry)) retry else null
    }

    private fun registerChunkMetadata(item: Rpf7SongItem, startBlock: Int, loopEpoch: Int, uri: Uri): String {
        val chunkMediaId = buildChunkMediaId(item.trackName, loopEpoch, startBlock)
        val sOff = item.archive.getChunkStartSample(item.trackName, startBlock)
        chunkMetaByMediaId[chunkMediaId] = ChunkPlaybackMeta(item, startBlock, loopEpoch, sOff, uri)
        chunkMediaIdByUri[uri] = chunkMediaId
        return chunkMediaId
    }

    private fun clearChunkTracking(cancelPrefetch: Boolean = true) {
        if (cancelPrefetch) {
            activeChunkPrefetchJob?.cancel()
            activeChunkPrefetchJob = null
        }
        queuedChunkKeys.clear()
        for (meta in chunkMetaByMediaId.values) {
            unpinAudioUri(meta.uri)
        }
        chunkMetaByMediaId.clear()
        chunkMediaIdByUri.clear()
    }

    private fun unpinIfUnusedByPlayer(uri: Uri) {
        val uriStr = uri.toString()
        val exo = player.exoPlayer
        val curIdx = exo.currentMediaItemIndex.coerceAtLeast(0)
        for (i in curIdx until exo.mediaItemCount) {
            if (exo.getMediaItemAt(i).localConfiguration?.uri?.toString() == uriStr) {
                return
            }
        }
        unpinAudioUri(uri)
    }

    private fun resetChunkCursor() {
        nextChunkItem = null
        nextChunkStartBlock = 0
        nextChunkTotalBlocks = 0
        nextChunkLoopEpoch = 0
        currentMultiChunksPlayedInRow = 0
    }

    private fun resolveNextChunkTarget(): NextChunkTarget? {
        val item = nextChunkItem ?: currentSongItem ?: return null
        val totalBlocks = nextChunkTotalBlocks.takeIf { it > 0 } ?: item.archive.getEntryBlockCount(item.trackName)
        val isMix = isMixItem(item)
        if (nextChunkStartBlock < totalBlocks) {
            val chunkBlocks = if (isMix) MIX_CHUNK_BLOCKS else item.archive.getChunkBlocksFor(item.trackName)
            return NextChunkTarget(item, nextChunkStartBlock, totalBlocks, nextChunkLoopEpoch, chunkBlocks)
        }
        if (isMix) {
            val parts = getSortedContinuousMixParts()
            val nextItem = if (parts.size > 1) {
                val curIdx = parts.indexOfFirst { it.trackName.equals(item.trackName, ignoreCase = true) }
                parts[if (curIdx >= 0) (curIdx + 1) % parts.size else 0]
            } else {
                item
            }
            val nextTotal = nextItem.archive.getEntryBlockCount(nextItem.trackName)
            return NextChunkTarget(nextItem, 0, nextTotal, nextChunkLoopEpoch + 1, MIX_CHUNK_BLOCKS)
        }
        return null
    }

    private fun resolveContinuousMixPrepared(
        elapsedMs: Long,
        isValid: () -> Boolean
    ): PreparedPlayback? {
        var item = continuousMixItem ?: return null
        var absMs = savedMixAbsMs + elapsedMs
        val parts = getSortedContinuousMixParts()
        if (parts.size > 1) {
            var guard = 0
            while (guard < parts.size) {
                val pSamples = item.archive.getEntryTotalSamples(item.trackName)
                val pRate = item.archive.getEntrySampleRate(item.trackName).toLong().coerceAtLeast(1L)
                val pDurMs = if (pSamples > 0L) (pSamples * 1000L) / pRate else 0L
                if (pDurMs <= 0L || absMs < pDurMs) break
                absMs -= pDurMs
                val idx = parts.indexOfFirst { it.trackName.equals(item.trackName, ignoreCase = true) }
                item = parts[if (idx >= 0) (idx + 1) % parts.size else 0]
                guard++
            }
        }

        val totalSamples = item.archive.getEntryTotalSamples(item.trackName)
        val sRate = item.archive.getEntrySampleRate(item.trackName).toLong().coerceAtLeast(1L)
        val totalMixDurationMs = if (totalSamples > 0L) (totalSamples * 1000L) / sRate else 0L

        val targetMixMs = if (totalMixDurationMs > 0L) absMs % totalMixDurationMs else absMs
        val targetSample = (targetMixMs * sRate) / 1000L

        val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
        val chunkBlocks = MIX_CHUNK_BLOCKS

        val rawBlock = item.archive.getBlockForSample(item.trackName, targetSample)
        val maxAlignedBlock = ((totalBlocks - 1) / chunkBlocks).coerceAtLeast(0) * chunkBlocks
        val targetStartBlock = ((rawBlock / chunkBlocks) * chunkBlocks).coerceIn(0, maxAlignedBlock)

        val chunkKey = buildChunkKey(item.trackName, 0, targetStartBlock)
        if (!queuedChunkKeys.add(chunkKey)) return null

        val chunkStartSample = item.archive.getChunkStartSample(item.trackName, targetStartBlock)
        val seekWithinChunkMs = ((targetSample - chunkStartSample).coerceAtLeast(0L) * 1000L) / sRate

        val mainUri = materializeValidChunkUri(item, targetStartBlock, chunkBlocks) ?: return null
        if (!isValid()) return null

        pinAudioUri(mainUri)
        registerChunkMetadata(item, targetStartBlock, 0, mainUri)
        hasStartedContinuousMix = true
        currentSongItem = item
        lastSegment = RadioSegmentType.Song
        currentSegmentUris = listOf(mainUri)
        nextChunkItem = item
        nextChunkTotalBlocks = totalBlocks
        nextChunkLoopEpoch = 0
        nextChunkStartBlock = targetStartBlock + chunkBlocks
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

        val totalDur = getFileDurationMs(mainUri)
        return PreparedPlayback(listOf(mainUri), 0, seekWithinChunkMs, totalDur, null, null)
    }

    private fun resolveResumeFromRealTimePrepared(
        rawElapsedMs: Long,
        emitMetadata: Boolean,
        isValid: () -> Boolean
    ): PreparedPlayback? {
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
                    if (!isValid()) return null
                    for (u in currentSegmentUris.subList(i, currentSegmentUris.size)) pinAudioUri(u)
                    val totalDur = currentSegmentUris.sumOf { getFileDurationMs(it) }
                    val preRoll = plannedPreRollCue
                    plannedPreRollCue = null
                    val overlay = plannedOverlayCue
                    plannedOverlayCue = null
                    return PreparedPlayback(currentSegmentUris, i, posMs + remainingMs, totalDur, preRoll, overlay)
                } else {
                    remainingMs -= timeLeftMs
                    posMs = 0L
                }
            }
            if (!advanceMultiChunkOrPickNext(emitMetadata)) break
            winIdx = 0
            posMs = 0L
            for (u in currentSegmentUris) {
                getFileDurationMs(u)
            }
        }

        if (currentSegmentUris.isNotEmpty() && isValid()) {
            val totalDur = currentSegmentUris.sumOf { getFileDurationMs(it) }
            val preRoll = plannedPreRollCue
            plannedPreRollCue = null
            val overlay = plannedOverlayCue
            plannedOverlayCue = null
            return PreparedPlayback(currentSegmentUris, 0, 0L, totalDur, preRoll, overlay)
        }
        return null
    }

    private fun advanceMultiChunkOrPickNext(emitMetadata: Boolean = true): Boolean {
        if (lastSegment == RadioSegmentType.Song && currentSongItem != null) {
            val target = resolveNextChunkTarget()
            if (target != null) {
                val chunkKey = buildChunkKey(target.item.trackName, target.loopEpoch, target.startBlock)
                val nextUri = materializeValidChunkUri(target.item, target.startBlock, target.chunkBlocks)
                if (nextUri != null && queuedChunkKeys.add(chunkKey)) {
                    pinAudioUri(nextUri)
                    registerChunkMetadata(target.item, target.startBlock, target.loopEpoch, nextUri)
                    if (target.item != currentSongItem) {
                        currentSongItem = target.item
                        if (isMixItem(target.item)) {
                            val markers = target.item.archive.readTrackMarkers(target.item.trackName)
                            if (markers.isNotEmpty()) {
                                activeMixMarkers = markers
                                activeMixSampleRate = target.item.archive.getEntrySampleRate(target.item.trackName)
                                lastDisplayedMixTrackId = -1
                            } else {
                                stopMixPoller(clearMarkers = true)
                            }
                        }
                    }
                    activeMixChunkStartSample = chunkMetaByMediaId[chunkMediaIdByUri[nextUri]]?.startSample ?: 0L
                    nextChunkItem = target.item
                    nextChunkTotalBlocks = target.totalBlocks
                    nextChunkLoopEpoch = target.loopEpoch
                    nextChunkStartBlock = target.startBlock + target.chunkBlocks
                    currentMultiChunksPlayedInRow += 1
                    currentSegmentUris = listOf(nextUri)
                    introItemCount = 0
                    return true
                }
            }
        }
        pickNextSegment(emitMetadata)
        return currentSegmentUris.isNotEmpty()
    }

    private fun playNext() {
        val currentGen = playGeneration.get()
        if (!_playing) return
        activeOverlayJob?.cancel()
        activeOverlayJob = null
        activeChunkPrefetchJob?.cancel()
        activeChunkPrefetchJob = null
        player.resetMusicDuckGain()
        activeDecodeJob?.cancel()
        activeDecodeJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                if (!_playing || playGeneration.get() != currentGen) return@launch
                if (advanceMultiChunkOrPickNext(emitMetadata = true)) {
                    if (!_playing || playGeneration.get() != currentGen) return@launch
                    startPlayback(currentSegmentUris, 0, 0L, currentGen)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: playNext decode error", e)
            }
        }
    }

    private fun pickNextSegment(emitMetadata: Boolean = true) {
        resetChunkCursor()
        hasPrefetchedNextSegment = false
        clearChunkTracking(cancelPrefetch = true)
        for (u in currentSegmentUris) {
            unpinAudioUri(u)
        }

        val isContinuousMix = isContinuousMixStation() || (currentSongItem != null && Rpf7MetadataRepository.getTrackMeta(currentSongItem!!.trackName).isContinuousMix)
        if (isContinuousMix && allSongItems.isNotEmpty()) {
            if (emitMetadata) setStationOnlyMetadata()
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
                nextChunkItem = prefetched.songItem
                nextChunkTotalBlocks = totalBlocks
                nextChunkLoopEpoch = 0
                nextChunkStartBlock = if (totalBlocks > chunkThreshold) chunkBlocks else totalBlocks
                currentMultiChunksPlayedInRow = 1
                if (totalBlocks > chunkThreshold) {
                    val lastUri = currentSegmentUris.lastOrNull()
                    if (lastUri != null) {
                        pinAudioUri(lastUri)
                        registerChunkMetadata(prefetched.songItem, 0, 0, lastUri)
                    }
                    queuedChunkKeys.add(buildChunkKey(prefetched.songItem.trackName, 0, 0))
                }
            }
            return
        }

        val event = director.pickNextEvent(
            pickIntro = { song ->
                if (song.introKeys.isNotEmpty()) {
                    val intro = song.introKeys[rng.nextInt(song.introKeys.size)]
                    Rpf7ClipRef(song.archive, intro)
                } else null
            },
            pickFallbackIntro = {
                val clip = if (director.timeOfDayDjQueue.isNotEmpty()) {
                    director.timeOfDayDjQueue.next()
                } else if (director.generalDjQueue.isNotEmpty()) {
                    director.generalDjQueue.next()
                } else null
                clip
            }
        )
        if (emitMetadata) setStationOnlyMetadata()
        when (event) {
            is Gen3BroadcastEvent.SongEvent -> {
                val item = event.song
                val uris = ArrayList<Uri>()
                val markers = item.archive.readDjMarkers(item.trackName)

                var djIntroUri: Uri? = null
                var djDurMs = 0L
                if (event.songIntroOverlay != null) {
                    val u = materializeClip(event.songIntroOverlay.trackKey)
                    if (u != null) {
                        val dur = readAudioDurationMs(context, u)
                        val isIntroEligible = if (event.isFallbackIntroOverlay) {
                            markers != null && markers.hasIntroWindow && markers.introDurationMs >= 3000L && dur in 1..markers.introDurationMs
                        } else {
                            true
                        }
                        if (isIntroEligible) {
                            pinAudioUri(u)
                            djIntroUri = u
                            djDurMs = dur
                        }
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
                val canPreRoll = !event.isFallbackIntroOverlay && djIntroUri != null && markers != null && markers.hasIntroWindow &&
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
                        if (!event.isFallbackIntroOverlay && djIntroUri != null) {
                            uris.add(djIntroUri)
                        }
                        if (mainUri != null) {
                            uris.add(mainUri)
                        }
                        introItemCount = if (uris.size > 1) 1 else 0
                    }
                }

                if (totalBlocks > chunkThreshold && mainUri != null) {
                    registerChunkMetadata(item, 0, 0, mainUri)
                    queuedChunkKeys.add(buildChunkKey(item.trackName, 0, 0))
                }
                currentSegmentUris = uris
                currentSongItem = item
                lastSegment = RadioSegmentType.Song
                nextChunkItem = item
                nextChunkTotalBlocks = totalBlocks
                nextChunkLoopEpoch = 0
                nextChunkStartBlock = if (totalBlocks > chunkThreshold) chunkBlocks else totalBlocks
                currentMultiChunksPlayedInRow = 1
                pendingBreakClips.clear()
            }
            is Gen3BroadcastEvent.BreakBlockEvent -> {
                if (emitMetadata) setStationOnlyMetadata()
                val standalone = ArrayList(event.standaloneClips)
                if (standalone.isNotEmpty()) {
                    val firstClip = standalone.removeAt(0)
                    val u = firstClip.clip.archive.materializeAudioUri(firstClip.clip.trackKey)
                    if (u != null) {
                        pinAudioUri(u)
                        currentSegmentUris = listOf(u)
                    } else {
                        currentSegmentUris = emptyList()
                    }
                    lastSegment = firstClip.segmentType
                    pendingBreakClips.clear()
                    pendingBreakClips.addAll(standalone)
                } else {
                    currentSegmentUris = emptyList()
                    lastSegment = RadioSegmentType.Commercial
                    pendingBreakClips.clear()
                }
                currentSongItem = null
                introItemCount = 0
            }
            is Gen3BroadcastEvent.InterludeEvent -> {
                if (emitMetadata) setStationOnlyMetadata()
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
        val parts = getSortedContinuousMixParts()
        if (parts.isEmpty()) return
        val item: Rpf7SongItem
        val startBlk: Int
        val chunkBlocks = MIX_CHUNK_BLOCKS
        if (!hasStartedContinuousMix) {
            item = parts[rng.nextInt(parts.size)]
            val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
            val alignedBuckets = ((totalBlocks - 1) / chunkBlocks).coerceAtLeast(0)
            startBlk = if (totalBlocks > chunkBlocks * 2 && alignedBuckets > 0) {
                rng.nextInt(alignedBuckets + 1) * chunkBlocks
            } else {
                0
            }
            hasStartedContinuousMix = true
        } else {
            val prev = currentSongItem
            item = if (parts.size > 1 && prev != null) {
                val idx = parts.indexOfFirst { it.trackName.equals(prev.trackName, ignoreCase = true) }
                parts[if (idx >= 0) (idx + 1) % parts.size else 0]
            } else {
                prev ?: parts[0]
            }
            startBlk = 0
        }

        val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
        val loopEpoch = 0
        val chunkKey = buildChunkKey(item.trackName, loopEpoch, startBlk)
        if (!queuedChunkKeys.add(chunkKey)) return
        val mainUri = materializeValidChunkUri(item, startBlk, chunkBlocks) ?: return
        pinAudioUri(mainUri)
        nextChunkItem = item
        nextChunkTotalBlocks = totalBlocks
        nextChunkLoopEpoch = loopEpoch
        nextChunkStartBlock = startBlk + chunkBlocks
        currentMultiChunksPlayedInRow = 1

        registerChunkMetadata(item, startBlk, loopEpoch, mainUri)
        val sOff = item.archive.getChunkStartSample(item.trackName, startBlk)

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
                val liveTotalDurMs = player.getDurationMs().takeIf { it > 0L } ?: totalDurationMs
                val remainingMs = liveTotalDurMs - pos

                val exo = player.exoPlayer
                val curWinIdx = exo.currentMediaItemIndex
                val isFinalWindow = curWinIdx >= exo.mediaItemCount - 1
                val isFinalChunk = nextChunkTotalBlocks <= 0 || (nextChunkStartBlock >= nextChunkTotalBlocks && !isMixItem(songItem))

                val hasValidOutro = markers != null && markers.hasOutroWindow && markers.outroStartMs > (markers.introEndMs.coerceAtLeast(0L) + 15_000L)

                // 1. Trigger background prefetch ONLY on final chunk window of the song, guarded by hasPrefetchedNextSegment
                val outroPreloadTrigger = hasValidOutro && pos >= (markers!!.outroStartMs - 15_000L)
                if (isFinalWindow && isFinalChunk && !hasPrefetchedNextSegment && !isPrefetching && (remainingMs in 1..25_000L || outroPreloadTrigger)) {
                    hasPrefetchedNextSegment = true
                    isPrefetching = true
                    stationScope.launch(StationDispatchers.ioDispatcher) {
                        try {
                            if (!_playing || playGeneration.get() != expectedGen) return@launch
                            prefetchNextEvent(expectedGen)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Throwable) {
                            GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: prefetchNextEvent error", e)
                        }
                    }
                }

                // 2. Outro overlap (strictly only on the final chunk of the song! Music ducks 0.60f, restores when DJ finishes; song plays naturally to completion)
                if (isFinalWindow && isFinalChunk && hasValidOutro && markers != null) {
                    if (!outroOverlapHandled && pos >= markers.outroStartMs) {
                        val pItem = prefetchedItem
                        if (pItem != null) {
                            when (val ev = pItem.event) {
                                is Gen3BroadcastEvent.BreakBlockEvent -> {
                                    val leadIn = ev.outroDryLeadIn
                                    if (leadIn != null) {
                                        outroOverlapHandled = true
                                        val voiceUri = leadIn.archive.materializeAudioUri(leadIn.trackKey)
                                        if (voiceUri != null) {
                                            pinAudioUri(voiceUri)
                                            isOutroOverlaySpeaking = true
                                            GTRadioLogger.logVerbose("DJ-OVERLAY") {
                                                "${songItem?.trackName}: outro overlap dry lead-in at ${pos}ms -> '$voiceUri' (${ev.outroLeadInRole})"
                                            }
                                            withContext(Dispatchers.Main.immediate) {
                                                player.djVoiceOverlayPlayer?.playOverlay(voiceUri, expectedGen.toLong(), player.isMutedForTuningStatic) {
                                                    isOutroOverlaySpeaking = false
                                                    unpinAudioUri(voiceUri)
                                                    player.setMusicDuckGain(1.0f, 500L, stationScope)
                                                }
                                                player.setMusicDuckGain(0.60f, 300L, stationScope)
                                            }
                                        }
                                    }
                                }
                                is Gen3BroadcastEvent.InterludeEvent -> {
                                    if (ev.isDryOverlayEligible) {
                                        val voiceUri = ev.clip.archive.materializeAudioUri(ev.clip.trackKey)
                                        val djDur = if (voiceUri != null) readAudioDurationMs(context, voiceUri) else 0L
                                        if (voiceUri != null && djDur in 1..12_000L) {
                                            outroOverlapHandled = true
                                            pinAudioUri(voiceUri)
                                            prefetchedItem = null // Consumed as overlay, don't replay in System 2
                                            isOutroOverlaySpeaking = true
                                            GTRadioLogger.logVerbose("DJ-OVERLAY") {
                                                "${songItem?.trackName}: outro overlap chatter at ${pos}ms -> '$voiceUri' (${ev.role})"
                                            }
                                            withContext(Dispatchers.Main.immediate) {
                                                player.djVoiceOverlayPlayer?.playOverlay(voiceUri, expectedGen.toLong(), player.isMutedForTuningStatic) {
                                                    isOutroOverlaySpeaking = false
                                                    unpinAudioUri(voiceUri)
                                                    player.setMusicDuckGain(1.0f, 500L, stationScope)
                                                }
                                                player.setMusicDuckGain(0.60f, 300L, stationScope)
                                            }
                                        }
                                    }
                                }
                                is Gen3BroadcastEvent.SongEvent -> {
                                    // Songs never overlay preceding songs at outro
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
        val event = director.pickNextEvent(
            pickIntro = { song ->
                if (song.introKeys.isNotEmpty()) {
                    val intro = song.introKeys[rng.nextInt(song.introKeys.size)]
                    Rpf7ClipRef(song.archive, intro)
                } else null
            },
            pickFallbackIntro = {
                val clip = if (director.timeOfDayDjQueue.isNotEmpty()) {
                    director.timeOfDayDjQueue.next()
                } else if (director.generalDjQueue.isNotEmpty()) {
                    director.generalDjQueue.next()
                } else null
                clip
            }
        )
        when (event) {
            is Gen3BroadcastEvent.SongEvent -> {
                val item = event.song
                val uris = ArrayList<Uri>()
                val pinned = ArrayList<String>()
                val markers = item.archive.readDjMarkers(item.trackName)

                var djIntroUri: Uri? = null
                var djDurMs = 0L
                if (event.songIntroOverlay != null) {
                    val u = materializeClip(event.songIntroOverlay.trackKey)
                    if (u != null) {
                        val dur = readAudioDurationMs(context, u)
                        val isIntroEligible = if (event.isFallbackIntroOverlay) {
                            markers != null && markers.hasIntroWindow && markers.introDurationMs >= 3000L && dur in 1..markers.introDurationMs
                        } else {
                            true
                        }
                        if (isIntroEligible) {
                            val p = u.toString()
                            pinned.add(p)
                            Rpf7Archive.pinFile(p)
                            djIntroUri = u
                            djDurMs = dur
                        }
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
                val canPreRoll = !event.isFallbackIntroOverlay && djIntroUri != null && markers != null && markers.hasIntroWindow &&
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
                        if (!event.isFallbackIntroOverlay && djIntroUri != null) uris.add(djIntroUri)
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
                        "Rpf7[$stationTitle]: Look-ahead prefetched next song '${item.trackName}' (intro=${event.songIntroOverlay != null}, fallback=${event.isFallbackIntroOverlay})"
                    }
                } else {
                    pinned.forEach { Rpf7Archive.unpinFile(it) }
                }
            }
            is Gen3BroadcastEvent.BreakBlockEvent -> {
                val standalone = ArrayList(event.standaloneClips)
                if (standalone.isNotEmpty()) {
                    val firstClip = standalone.first()
                    val u = firstClip.clip.archive.materializeAudioUri(firstClip.clip.trackKey)
                    if (u != null && _playing && playGeneration.get() == expectedGen) {
                        val p = u.toString()
                        val pinned = arrayListOf(p)
                        Rpf7Archive.pinFile(p)
                        val queuedClips = standalone.drop(1)
                        prefetchedItem = PrefetchedBroadcastItem(
                            uris = listOf(u),
                            pinnedFiles = pinned,
                            event = event,
                            songItem = null,
                            segmentType = firstClip.segmentType,
                            pendingBreakClips = queuedClips
                        )
                        GTRadioLogger.logVerbose("DIRECTOR") {
                            "Rpf7[$stationTitle]: Look-ahead prefetched break upfront '${firstClip.clip.trackKey}' (${queuedClips.size} queued)"
                        }
                    }
                }
            }
            is Gen3BroadcastEvent.InterludeEvent -> {
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { prefetchAndQueueNextChunk(expectedGen) }
            return
        }
        if (!_playing || playGeneration.get() != expectedGen) return
        if (lastSegment != RadioSegmentType.Song || currentSongItem == null) return

        val exo = player.exoPlayer
        val curIdx = exo.currentMediaItemIndex.coerceAtLeast(0)
        val aheadCount = (exo.mediaItemCount - 1) - curIdx
        if (aheadCount >= 1) return
        if (activeChunkPrefetchJob?.isActive == true) return

        val target = resolveNextChunkTarget() ?: return
        val chunkKey = buildChunkKey(target.item.trackName, target.loopEpoch, target.startBlock)
        if (queuedChunkKeys.contains(chunkKey)) return

        activeChunkPrefetchJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                if (!_playing || playGeneration.get() != expectedGen) return@launch
                val nextUri = materializeValidChunkUri(target.item, target.startBlock, target.chunkBlocks) ?: return@launch
                if (!_playing || playGeneration.get() != expectedGen) return@launch

                // Pin BEFORE addMediaItem() so LRU eviction never deletes queued chunks
                pinAudioUri(nextUri)

                withContext(Dispatchers.Main.immediate) {
                    if (!_playing || playGeneration.get() != expectedGen ||
                        lastSegment != RadioSegmentType.Song || currentSongItem == null
                    ) {
                        unpinIfUnusedByPlayer(nextUri)
                        return@withContext
                    }
                    if (!queuedChunkKeys.add(chunkKey)) {
                        unpinIfUnusedByPlayer(nextUri)
                        return@withContext
                    }

                    val chunkMediaId = registerChunkMetadata(target.item, target.startBlock, target.loopEpoch, nextUri)
                    val mediaItem = MediaItem.Builder().setUri(nextUri).setMediaId(chunkMediaId).build()
                    player.exoPlayer.addMediaItem(mediaItem)

                    if (!isMixItem(target.item)) {
                        currentSegmentUris = currentSegmentUris + nextUri
                    }

                    nextChunkItem = target.item
                    nextChunkTotalBlocks = target.totalBlocks
                    nextChunkLoopEpoch = target.loopEpoch
                    nextChunkStartBlock = target.startBlock + target.chunkBlocks
                    currentMultiChunksPlayedInRow += 1

                    val remainingAhead = (player.exoPlayer.mediaItemCount - 1) - player.exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
                    if (remainingAhead < 1) {
                        mainHandler.post {
                            if (_playing && playGeneration.get() == expectedGen) {
                                prefetchAndQueueNextChunk(expectedGen)
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: prefetchAndQueueNextChunk error", e)
            }
        }
    }

    private fun launchPreparedPlayback(prepared: PreparedPlayback, expectedGen: Int) {
        val uris = prepared.uris
        val startWindowIndex = prepared.startWindowIndex
        val startPositionMs = prepared.startPositionMs
        val totalDur = prepared.totalDurMs
        val preRoll = prepared.preRoll
        val overlayCue = prepared.overlayCue

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
                    player.setMusicDuckGain(0.60f, 0L, stationScope)
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
                                player.setMusicDuckGain(0.60f, 300L, stationScope)
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

    private fun startPlayback(uris: List<Uri>, startWindowIndex: Int, startPositionMs: Long, expectedGen: Int = playGeneration.get()) {
        val totalDur = if (uris.isNotEmpty()) uris.sumOf { getFileDurationMs(it) } else 0L
        val preRoll = plannedPreRollCue
        plannedPreRollCue = null
        val overlayCue = plannedOverlayCue
        plannedOverlayCue = null
        launchPreparedPlayback(
            PreparedPlayback(uris, startWindowIndex, startPositionMs, totalDur, preRoll, overlayCue),
            expectedGen
        )
    }

    private fun executeStartPlayback(uris: List<Uri>, startWindowIndex: Int, startPositionMs: Long, expectedGen: Int = playGeneration.get(), totalDur: Long = 0L) {
        val commitBlock = {
            if (!_playing || playGeneration.get() != expectedGen) {
                GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: startPlayback aborted: generation mismatch or not playing (gen=$expectedGen, cur=${playGeneration.get()})")
            } else {
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

                if (_playing && playGeneration.get() == expectedGen) {
                    if (startWindowIndex > 0 || startPositionMs > 0L) {
                        player.exoPlayer.seekTo(startWindowIndex, startPositionMs)
                    }
                    updateMetadataForWindowIndex(startWindowIndex, expectedGen)

                    activeListener = object : Player.Listener {
                        private var alreadyHandled = false

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                enoentRetryCount = 0
                                if (activeMixMarkers.isNotEmpty()) {
                                    val mediaId = player.exoPlayer.currentMediaItem?.mediaId
                                    if (mediaId != null && chunkMetaByMediaId.containsKey(mediaId)) {
                                        updateMixTrackMetadata()
                                    }
                                }
                            }
                            if (!alreadyHandled && state == Player.STATE_ENDED) {
                                alreadyHandled = true
                                player.exoPlayer.removeListener(this)
                                if (_playing && playGeneration.get() == expectedGen) {
                                    if (pendingBreakClips.isNotEmpty() || activeBreakStagingJob?.isActive == true) {
                                        stageAndPlayRemainingBreakClip(expectedGen)
                                    } else {
                                        if (director.currentPhase == Gen3DirectorPhase.BREAK_BLOCK) {
                                            director.onBreakCompleted()
                                        }
                                        onSongOrSegmentEnded(expectedGen)
                                    }
                                }
                            }
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            val exo = player.exoPlayer
                            val currentMediaId = mediaItem?.mediaId ?: exo.currentMediaItem?.mediaId
                            if (currentMediaId != null) {
                                val meta = chunkMetaByMediaId[currentMediaId]
                                if (meta != null) {
                                    if (meta.item != currentSongItem) {
                                        currentSongItem = meta.item
                                        if (isMixItem(meta.item)) {
                                            val markers = meta.item.archive.readTrackMarkers(meta.item.trackName)
                                            if (markers.isNotEmpty()) {
                                                activeMixMarkers = markers
                                                activeMixSampleRate = meta.item.archive.getEntrySampleRate(meta.item.trackName)
                                                lastDisplayedMixTrackId = -1
                                            } else {
                                                stopMixPoller(clearMarkers = true)
                                            }
                                        }
                                    }
                                    activeMixChunkStartSample = meta.startSample
                                }
                            }

                            // Unpin ONLY chunks that ExoPlayer has transitioned past and are no longer active/queued
                            val curIdx = exo.currentMediaItemIndex.coerceAtLeast(0)
                            val activeMediaIds = HashSet<String>()
                            val activeUriStrings = HashSet<String>()
                            for (i in curIdx until exo.mediaItemCount) {
                                val mi = exo.getMediaItemAt(i)
                                if (mi.mediaId.isNotEmpty()) activeMediaIds.add(mi.mediaId)
                                mi.localConfiguration?.uri?.toString()?.let { activeUriStrings.add(it) }
                            }
                            val pastEntries = chunkMetaByMediaId.entries.filter { (mId, _) -> mId !in activeMediaIds }
                            for ((mId, meta) in pastEntries) {
                                chunkMetaByMediaId.remove(mId)
                                queuedChunkKeys.remove(buildChunkKey(meta.item.trackName, meta.loopEpoch, meta.startBlock))
                                if (meta.uri.toString() !in activeUriStrings) {
                                    unpinAudioUri(meta.uri)
                                }
                            }

                            updateMetadataForWindowIndex(curIdx, expectedGen)

                            // Staged Break Decoding: while current clip plays, stage next queued break clip
                            stageNextPendingBreakClip(expectedGen)

                            if (lastSegment == RadioSegmentType.Song && currentSongItem != null && nextChunkTotalBlocks > 0) {
                                prefetchAndQueueNextChunk(expectedGen)
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: ExoPlayer error on track: ${error.message}", error)
                            if (!alreadyHandled && _playing && playGeneration.get() == expectedGen) {
                                alreadyHandled = true
                                player.exoPlayer.removeListener(this)
                                val failingMediaId = player.exoPlayer.currentMediaItem?.mediaId
                                val resolvedEnoent = if (isEnoentError(error)) findEnoentChunkMeta(error, failingMediaId) else null
                                if (resolvedEnoent != null && enoentRetryCount < 1) {
                                    val (failingMeta, isCurrentChunk) = resolvedEnoent
                                    enoentRetryCount++
                                    val failingPosMs = if (isCurrentChunk) player.exoPlayer.currentPosition.coerceAtLeast(0L) else 0L
                                    GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: ENOENT on chunk '${failingMeta.item.trackName}' blk=${failingMeta.startBlock}; re-materializing exact chunk (retry=$enoentRetryCount)")
                                    activeChunkPrefetchJob?.cancel()
                                    activeChunkPrefetchJob = null
                                    stationScope.launch(StationDispatchers.ioDispatcher) {
                                        try {
                                            if (!_playing || playGeneration.get() != expectedGen) return@launch
                                            val chunkBlocks = if (isMixItem(failingMeta.item)) MIX_CHUNK_BLOCKS else failingMeta.item.archive.getChunkBlocksFor(failingMeta.item.trackName)
                                            val repairedUri = materializeValidChunkUri(failingMeta.item, failingMeta.startBlock, chunkBlocks)
                                            if (repairedUri != null && _playing && playGeneration.get() == expectedGen) {
                                                val dur = getFileDurationMs(repairedUri)
                                                withContext(Dispatchers.Main.immediate) {
                                                    if (_playing && playGeneration.get() == expectedGen) {
                                                        clearChunkTracking(cancelPrefetch = true)
                                                        pinAudioUri(repairedUri)
                                                        registerChunkMetadata(failingMeta.item, failingMeta.startBlock, failingMeta.loopEpoch, repairedUri)
                                                        queuedChunkKeys.add(buildChunkKey(failingMeta.item.trackName, failingMeta.loopEpoch, failingMeta.startBlock))
                                                        val totalBlocks = failingMeta.item.archive.getEntryBlockCount(failingMeta.item.trackName)
                                                        nextChunkItem = failingMeta.item
                                                        nextChunkTotalBlocks = totalBlocks
                                                        nextChunkLoopEpoch = failingMeta.loopEpoch
                                                        nextChunkStartBlock = failingMeta.startBlock + chunkBlocks
                                                        currentSegmentUris = listOf(repairedUri)
                                                        executeStartPlayback(listOf(repairedUri), 0, failingPosMs, expectedGen, dur)
                                                    }
                                                }
                                            } else {
                                                withContext(Dispatchers.Main.immediate) {
                                                    if (_playing && playGeneration.get() == expectedGen) playNext()
                                                }
                                            }
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (t: Throwable) {
                                            GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: ENOENT chunk recovery failed", t)
                                            withContext(Dispatchers.Main.immediate) {
                                                if (_playing && playGeneration.get() == expectedGen) playNext()
                                            }
                                        }
                                    }
                                    return
                                }
                                mainHandler.post {
                                    if (_playing && playGeneration.get() == expectedGen) {
                                        playNext()
                                    }
                                }
                            }
                        }
                    }
                    player.exoPlayer.addListener(activeListener!!)
                    if (_playing && playGeneration.get() == expectedGen) {
                        player.prepare()
                        player.playInternal()

                        if (pendingBreakClips.isNotEmpty()) {
                            stageNextPendingBreakClip(expectedGen)
                        }

                        if (activeMixMarkers.isNotEmpty()) {
                            startMixPoller(expectedGen)
                        } else {
                            stopMixPoller(clearMarkers = false)
                        }

                        if (lastSegment == RadioSegmentType.Song && currentSongItem != null && nextChunkTotalBlocks > 0) {
                            prefetchAndQueueNextChunk(expectedGen)
                        }

                        // Start look-ahead prefetch monitor for discrete songs
                        if (!isContinuousMixStation() && currentSongItem != null) {
                            startLookAheadMonitor(expectedGen, totalDur)
                        }
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            commitBlock()
        } else {
            mainHandler.post(commitBlock)
        }
    }

    private fun onSongOrSegmentEnded(expectedGen: Int) {
        if (!_playing || playGeneration.get() != expectedGen) return
        if (isOutroOverlaySpeaking) {
            stationScope.launch {
                var elapsed = 0L
                while (isActive && _playing && isOutroOverlaySpeaking && elapsed < 6000L) {
                    delay(50L)
                    elapsed += 50L
                }
                withContext(Dispatchers.Main.immediate) {
                    if (_playing && playGeneration.get() == expectedGen) {
                        player.resetMusicDuckGain()
                        playNext()
                    }
                }
            }
        } else {
            player.resetMusicDuckGain()
            playNext()
        }
    }

    private fun stageNextPendingBreakClip(expectedGen: Int = playGeneration.get()) {
        if (pendingBreakClips.isEmpty() || !_playing || playGeneration.get() != expectedGen) return
        val nextClip = pendingBreakClips.removeAt(0)
        activeBreakStagingJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                if (!_playing || playGeneration.get() != expectedGen) return@launch
                val uri = nextClip.clip.archive.materializeAudioUri(nextClip.clip.trackKey) ?: return@launch
                pinAudioUri(uri)
                withContext(Dispatchers.Main.immediate) {
                    if (_playing && playGeneration.get() == expectedGen) {
                        player.exoPlayer.addMediaItem(MediaItem.fromUri(uri))
                        GTRadioLogger.logVerbose("DIRECTOR") {
                            "Rpf7[$stationTitle]: Staged queued break clip '${nextClip.clip.trackKey}' (${nextClip.role})"
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: staged break clip decode error", e)
            }
        }
    }

    private fun stageAndPlayRemainingBreakClip(expectedGen: Int) {
        stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                activeBreakStagingJob?.join()
                if (!_playing || playGeneration.get() != expectedGen) return@launch
                if (pendingBreakClips.isEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        if (_playing && playGeneration.get() == expectedGen) {
                            if (director.currentPhase == Gen3DirectorPhase.BREAK_BLOCK) {
                                director.onBreakCompleted()
                            }
                            playNext()
                        }
                    }
                    return@launch
                }
                val nextClip = pendingBreakClips.removeAt(0)
                val uri = nextClip.clip.archive.materializeAudioUri(nextClip.clip.trackKey)
                if (uri != null) {
                    pinAudioUri(uri)
                    withContext(Dispatchers.Main.immediate) {
                        if (_playing && playGeneration.get() == expectedGen) {
                            currentSegmentUris = listOf(uri)
                            lastSegment = nextClip.segmentType
                            executeStartPlayback(listOf(uri), 0, 0L, expectedGen, getFileDurationMs(uri))
                        }
                    }
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        if (_playing && playGeneration.get() == expectedGen) {
                            stageAndPlayRemainingBreakClip(expectedGen)
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: stageAndPlayRemainingBreakClip error", e)
                withContext(Dispatchers.Main.immediate) {
                    if (_playing && playGeneration.get() == expectedGen) {
                        playNext()
                    }
                }
            }
        }
    }

    private fun startMixPoller(expectedGen: Int = playGeneration.get()) {
        if (activePollerJob?.isActive == true) return
        activePollerJob = stationScope.launch {
            while (isActive && _playing && playGeneration.get() == expectedGen && activeMixMarkers.isNotEmpty()) {
                val exo = player.exoPlayer
                val mediaId = exo.currentMediaItem?.mediaId
                if (exo.playbackState == Player.STATE_READY && mediaId != null && chunkMetaByMediaId.containsKey(mediaId)) {
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
        if (!_playing) return
        val exo = player.exoPlayer
        if (exo.playbackState != Player.STATE_READY) return
        val mediaId = exo.currentMediaItem?.mediaId ?: return
        val chunkMeta = chunkMetaByMediaId[mediaId] ?: return
        if (chunkMeta.item != currentSongItem || activeMixMarkers.isEmpty()) {
            val markers = chunkMeta.item.archive.readTrackMarkers(chunkMeta.item.trackName)
            if (markers.isNotEmpty()) {
                currentSongItem = chunkMeta.item
                activeMixMarkers = markers
                activeMixSampleRate = chunkMeta.item.archive.getEntrySampleRate(chunkMeta.item.trackName)
            }
        }
        if (activeMixMarkers.isEmpty()) return
        val baseSample = chunkMeta.startSample
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
                                notifyMetadataChanged(
                                    buildStationMetadata(
                                        trackTitle = displayTitle,
                                        trackSubtitle = sub,
                                        art = newArt,
                                        isSongOrShow = true,
                                        cleanArtist = displayArtist,
                                        cleanAlbum = alb
                                    )
                                )
                            }
                        }
                    }
                }
            }

            val eraAlbum = Rpf7MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
            val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
            notifyMetadataChanged(
                buildStationMetadata(
                    trackTitle = displayTitle,
                    trackSubtitle = subtitle,
                    art = art,
                    isSongOrShow = true,
                    cleanArtist = displayArtist,
                    cleanAlbum = eraAlbum
                )
            )
        } else if (lastDisplayedMixTrackId <= 0) {
            // First track marker hasn't been hit yet; show initial mix title cleanly without losing poller
            val trackMeta = currentSongItem?.let { Rpf7MetadataRepository.getTrackMeta(it.trackName, stationTitle) }
            val dTitle = trackMeta?.title ?: stationTitle
            val dArtist = trackMeta?.artist ?: ""
            val sub = if (dArtist.isNotBlank() && dArtist != stationTitle) "$dArtist • $stationTitle" else stationTitle
            notifyMetadataChanged(buildStationMetadata(dTitle, sub, stationLogo, isSongOrShow = true, cleanArtist = dArtist))
        }
    }

    private fun isContinuousMixStation(): Boolean {
        val currentTrackMix = currentSongItem?.let { Rpf7MetadataRepository.getTrackMeta(it.trackName).isContinuousMix } ?: false
        return stationConfig.isContinuousMix ||
            currentTrackMix ||
            Rpf7MetadataRepository.isContinuousMix(stationArchive.rpfName)
    }

    private fun isMixStation(): Boolean {
        return isContinuousMixStation()
    }

    private fun isTalk(): Boolean {
        return stationConfig.isTalkRadio
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
        val COUNTRY_ONLY_AD_STEMS = setOf(
            "mono_ad032_larrys_rv_sales",
            "mono_ad038_floyds_scrap_metal",
            "mono_ad039_buddys_trucking",
            "mono_ad046_dons_country_store",
            "mono_ad053_blaine_county_bank"
        )
        val COUNTRY_AD_STEMS = setOf(
            "mono_ad006_desert_tourism",
            "mono_ad007_sa_water_power",
            "mono_ad008_up_n_atom",
            "mono_ad012_donate_your_car",
            "mono_ad013_race_to_pluto",
            "mono_ad015_prop208_jungle",
            "mono_ad016_pisswasser",
            "mono_ad018_lottery_starving_children",
            "mono_ad019_lottery_really_addicted",
            "mono_ad020_whiz_wirless",
            "mono_ad022_bail_bonds",
            "mono_ad024_proposition_14",
            "mono_ad025_pic_officer",
            "mono_ad026_pic_franchise",
            "mono_ad029_gastro_band",
            "mono_ad030_economic_recovery_group",
            "mono_ad031_rehab_island",
            "mono_ad032_larrys_rv_sales",
            "mono_ad033_night_lights",
            "mono_ad035_electrotoke",
            "mono_ad036_toilet_cleaner",
            "mono_ad038_floyds_scrap_metal",
            "mono_ad039_buddys_trucking",
            "mono_ad040_fleeca_interest_fees",
            "mono_ad041_fleeca_bedroom",
            "mono_ad042_shark",
            "mono_ad043_smoked_dreams",
            "mono_ad045_preservex",
            "mono_ad046_dons_country_store",
            "mono_ad047_proposition_45",
            "mono_ad053_blaine_county_bank",
            "mono_ad055_hitting_kids_works_wonders",
            "mono_ad064_serious_cougar",
            "mono_ad069_hitting_kids_pot",
            "mono_ad081_mma",
            "mono_ad084_chains_intmacy_throb",
            "mono_ad085_chains_intmacy_diamonds",
            "mono_ad093_sex_addiction_victim",
            "mono_ad094_pontius",
            "mono_ad095_sue_murry",
            "mono_ad096_jock_cranley1",
            "mono_ad097_jock_cranley2",
            "mono_ad_ng_009_vislib",
            "mono_ad_ng_008_tcse2",
            "mono_ad_ng_007_tcse",
            "mono_ad_ng_006_phachi",
            "mono_ad_ng_005_mysta",
            "mono_ad_ng_004_macrac2",
            "mono_ad_ng_003_macrac",
            "mono_ad_ng_002_extpal",
            "mono_ad_ng_001_extpal"
        )
    }
}
