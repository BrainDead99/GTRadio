package com.sc.gtradio.media.rpf7
import com.sc.gtradio.media.core.*


import com.sc.gtradio.media.*
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.io.FileInputStream
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
            if (stationConfig.advertPool != Rpf7AdvertPool.NONE || stationConfig.useStationLocalAdvertsOnly) {
                director.adsEnabled = value
            }
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
    private val nightDjList = ArrayList<Rpf7ClipRef>()

    private val allSongItems = ArrayList<Rpf7SongItem>()
    private val stationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeDecodeJob: Job? = null
    private var seamlessPrepareJob: Job? = null
    private var activeCleanJob: Job? = null
    private var activePollerJob: Job? = null
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    override val supportsSeamlessPrepare: Boolean get() = true
    override val supportsNeighborPrewarm: Boolean get() = true

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var activeListener: Player.Listener? = null

    private data class PreparedPlayback(
        val uris: List<Uri>,
        val startWindowIndex: Int,
        val startPositionMs: Long,
        val totalDurMs: Long,
        val preRoll: PlannedPreRollCue?,
        val overlayCue: PlannedOverlayCue?
    )

    private data class PrewarmedTakeOver(
        val createdAtElapsedMs: Long,
        val uris: List<Uri>,
        val windowDurationsMs: List<Long>,
        val baseWindowIndex: Int,
        val basePositionMs: Long,
        val totalDurMs: Long,
        val songItem: Rpf7SongItem?,
        val segmentType: RadioSegmentType,
        val introItemCount: Int,
        val pendingBreakClips: List<StandaloneBreakClip<Rpf7ClipRef>>,
        val preRoll: PlannedPreRollCue?,
        val overlayCue: PlannedOverlayCue?,
        val pinnedFiles: List<String>,
        val isContinuousMix: Boolean,
        val markContinuousMixStarted: Boolean,
        val mixMarkers: List<Rpf7TrackMarker>,
        val mixSampleRate: Int,
        val consumedPreviousSegment: Boolean,
        val usedPrefetchedItem: Boolean
    )

    private val prewarmGeneration = AtomicInteger(0)
    private val prewarmLock = Any()
    @Volatile private var prewarmedTakeOver: PrewarmedTakeOver? = null
    @Volatile private var pendingPrewarmPinnedFiles: List<String> = emptyList()

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: Rpf7SongItem? = null
    private var hasStartedContinuousMix: Boolean = false
    private var pendingMixStartOffsetMs: Long = 0L
    private var introItemCount = 0
    private var hasPrefetchedNextSegment: Boolean = false

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
    @Volatile private var seamlessPrepareActive: Boolean = false
    private var seamlessPreparePrevHasStartedMix: Boolean? = null
    private var seamlessPreparePrevSavedMixAbsMs: Long? = null

    private fun pinAudioUri(uri: Uri) {
        if (uri.scheme == Rpf7StreamingDataSource.SCHEME) return
        val uriStr = uri.toString()
        activePinnedCounts.compute(uriStr) { _, existing ->
            (existing ?: AtomicInteger(0)).apply { incrementAndGet() }
        }
        Rpf7Archive.pinFile(uriStr)
    }

    private fun unpinAudioUri(uri: Uri) {
        if (uri.scheme == Rpf7StreamingDataSource.SCHEME) return
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
    private val durationCache = ConcurrentHashMap<Uri, Long>()

    private var activeMixMarkers: List<Rpf7TrackMarker> = emptyList()
    private var activeMixSampleRate: Int = 48000
    private var lastDisplayedMixTrackId: Int = -1
    private var lastPolledMixPosMs: Long = -1L
    private var mixPollerRunnable: Runnable? = null
    private var savedMixAbsMs: Long = 0L
    private var continuousMixItem: Rpf7SongItem? = null

    @Volatile private var prefetchGeneration = 0
    private val playGeneration = AtomicInteger(0)

    private fun resolveStreamIndex(uri: Uri?): Rpf7StreamIndex? {
        if (uri == null || uri.scheme != Rpf7StreamingDataSource.SCHEME) return null
        val host = uri.host ?: return null
        val rawPath = uri.encodedPath?.removePrefix("/")?.removeSuffix(".wav") ?: return null
        val entryName = Uri.decode(rawPath)
        val archive = Rpf7StreamRegistry.get(host) ?: return null
        return archive.getOrBuildStreamIndex(entryName)
    }

    private fun resolvePlaybackUri(archive: Rpf7Archive, trackKey: String): Uri? {
        val clean = trackKey.trim()
        if (archive.isMultiChannelEntry(clean)) {
            val idx = archive.getOrBuildStreamIndex(clean) ?: return null
            val uri = archive.buildStreamUri(idx.entryName)
            durationCache[uri] = idx.durationMs
            return uri
        }
        return archive.materializeAudioUri(clean)
    }

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

        morningDjList.clear()
        eveningDjList.clear()
        nightDjList.clear()

        val ids = ArrayList<Rpf7ClipRef>()
        val solos = ArrayList<Rpf7ClipRef>()
        val generals = ArrayList<Rpf7ClipRef>()
        val toAds = ArrayList<Rpf7ClipRef>()
        val toNews = ArrayList<Rpf7ClipRef>()
        val localAdvertEntries = ArrayList<Rpf7ClipRef>()
        val introMap = HashMap<String, MutableList<String>>()
        val songItems = ArrayList<Rpf7SongItem>()

        // 1. Build lookup tables for 29-bit Jenkins hashes in voice banks
        val timeHashMap = HashMap<Int, String>()
        for (prefix in listOf("morning", "evening", "night")) {
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
            if (isNonSongStem(stem)) continue
            val lookupStem = RPF7_INTRO_ALIASES[stem] ?: stem
            for (num in 1..8) {
                val pad = String.format(Locale.ROOT, "%02d", num)
                introHashMap[Rpf7Crypto.jenkHashString("${lookupStem}_$pad") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("intro_${lookupStem}_$pad") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("${lookupStem}_$num") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("intro_${lookupStem}_$num") and 0x1FFFFFFF] = stem
            }
        }

        // 2. Classify voice bank sub-tracks (time, to, intro, general, including DLC/expansion prefixed banks)
        for (sub in stationArchive.getBankSubTracks()) {
            val parent = sub.parentEntryName.lowercase(Locale.ROOT).removeSuffix(".awc")
            val h29 = sub.streamId and 0x1FFFFFFF
            val ref = Rpf7ClipRef(stationArchive, sub.subKey)

            when {
                parent == "time" || parent.endsWith("_time") -> {
                    when (timeHashMap[h29]) {
                        "morning" -> morningDjList.add(ref)
                        "evening" -> eveningDjList.add(ref)
                        "night" -> nightDjList.add(ref)
                    }
                }
                parent == "to" || parent.endsWith("_to") -> {
                    when (toHashMap[h29]) {
                        "to_ad" -> toAds.add(ref)
                        "to_news" -> toNews.add(ref)
                    }
                }
                parent == "intro" || parent.endsWith("_intro") -> {
                    val matchedSong = introHashMap[h29]
                    if (matchedSong != null) {
                        introMap.getOrPut(matchedSong) { ArrayList() }.add(sub.subKey)
                    }
                }
                parent == "general" || parent.endsWith("_general") -> generals.add(ref)
            }
        }

        // 3. Classify top-level MultiChannel entries (guarding id_rather_be_with_you against ID false positive)
        for (entry in stationArchive.getFileEntries()) {
            val nameLower = entry.name.lowercase(Locale.ROOT)
            val stem = nameLower.removeSuffix(".awc")
            if (isVoiceBankStem(stem) || isIgnoredSilenceStem(stem)) continue

            val ref = Rpf7ClipRef(stationArchive, entry.name)
            when {
                isStationIdStem(stem) -> ids.add(ref)
                isDjSoloStem(stem) -> solos.add(ref)
                isLocalAdvertStem(stem) -> localAdvertEntries.add(ref)
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
        director.nightDjList = nightDjList
        allSongItems.clear()
        allSongItems.addAll(songItems)
        director.rebuildTimeOfDayQueueIfNeeded()

        // 4. Index Adverts and News with exact pool routing (excluding TV-only commercial stems)
        val allAdverts = ArrayList<Rpf7ClipRef>()
        if (stationConfig.useStationLocalAdvertsOnly) {
            allAdverts.addAll(localAdvertEntries)
        } else if (stationConfig.advertPool != Rpf7AdvertPool.NONE) {
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
                    "${morningDjList.size + eveningDjList.size + nightDjList.size} TOD clips, " +
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

    private fun resolveDiscreteTrackMeta(item: Rpf7SongItem): Rpf7TrackMeta {
        val markers = item.archive.readTrackMarkers(item.trackName)
        for (m in markers) {
            if (m.trackId > 1 && m.trackId != 1687) {
                Rpf7MetadataRepository.getTrackById(m.trackId, context)?.let { return it }
            }
        }
        return Rpf7MetadataRepository.getTrackMeta(item.trackName, stationTitle, context)
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

        val meta = resolveDiscreteTrackMeta(item)
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
        if (uri.scheme == Rpf7StreamingDataSource.SCHEME) {
            val streamIdx = resolveStreamIndex(uri)
            if (streamIdx != null && streamIdx.durationMs > 0L) {
                durationCache[uri] = streamIdx.durationMs
                return streamIdx.durationMs
            }
        }
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

    override fun discardNeighborPrewarm() {
        prewarmGeneration.incrementAndGet()
        val stale = synchronized(prewarmLock) {
            val old = prewarmedTakeOver
            prewarmedTakeOver = null
            old
        }
        stale?.pinnedFiles?.forEach { Rpf7Archive.unpinFile(it) }
    }

    private fun clearPendingPrewarmPins() {
        val pins = pendingPrewarmPinnedFiles
        pendingPrewarmPinnedFiles = emptyList()
        for (p in pins) {
            Rpf7Archive.unpinFile(p)
        }
    }

    private fun collectAndPinNonStreamingFiles(
        uris: List<Uri>,
        extraUris: List<Uri> = emptyList()
    ): List<String> {
        val pinned = ArrayList<String>()
        for (u in uris + extraUris) {
            if (u.scheme != Rpf7StreamingDataSource.SCHEME) {
                val p = u.toString()
                Rpf7Archive.pinFile(p)
                pinned.add(p)
            }
        }
        return pinned
    }

    private fun advancePrewarmedPlayback(
        prewarmed: PrewarmedTakeOver,
        extraElapsedMs: Long
    ): PreparedPlayback? {
        var rem = extraElapsedMs.coerceAtLeast(0L)
        var win = prewarmed.baseWindowIndex.coerceIn(0, (prewarmed.uris.size - 1).coerceAtLeast(0))
        var pos = prewarmed.basePositionMs.coerceAtLeast(0L)
        var landed = false
        while (win < prewarmed.uris.size) {
            val dur = prewarmed.windowDurationsMs.getOrElse(win) { 0L }
            if (dur <= 0L) {
                if (win == prewarmed.uris.lastIndex) {
                    landed = true
                    break
                }
                win++
                pos = 0L
                continue
            }
            val left = (dur - pos).coerceAtLeast(0L)
            if (rem < left) {
                pos += rem
                landed = true
                break
            }
            rem -= left
            win++
            pos = 0L
        }
        if (!landed) return null
        val effectivePreRoll = if (prewarmed.preRoll != null && win == 0 && pos == 0L) prewarmed.preRoll else null
        val effectiveOverlay = if (prewarmed.overlayCue != null && win == 0 && pos <= prewarmed.overlayCue.djStartMs) {
            prewarmed.overlayCue
        } else {
            null
        }
        return PreparedPlayback(
            uris = prewarmed.uris,
            startWindowIndex = win,
            startPositionMs = pos,
            totalDurMs = prewarmed.totalDurMs,
            preRoll = effectivePreRoll,
            overlayCue = effectiveOverlay
        )
    }

    private fun takeValidPrewarmedTakeOver(): Pair<PrewarmedTakeOver, PreparedPlayback>? {
        prewarmGeneration.incrementAndGet()
        val prewarmed = synchronized(prewarmLock) {
            val p = prewarmedTakeOver
            prewarmedTakeOver = null
            p
        } ?: return null

        val extraMs = (SystemClock.elapsedRealtime() - prewarmed.createdAtElapsedMs).coerceAtLeast(0L)
        val advanced = advancePrewarmedPlayback(prewarmed, extraMs)
        if (advanced == null || !areSegmentFilesValid(prewarmed.uris)) {
            prewarmed.pinnedFiles.forEach { Rpf7Archive.unpinFile(it) }
            return null
        }
        return prewarmed to advanced
    }

    private fun applyPrewarmedStateForCommit(prewarmed: PrewarmedTakeOver, prepared: PreparedPlayback) {
        if (prewarmed.consumedPreviousSegment) {
            for (u in currentSegmentUris) {
                unpinAudioUri(u)
            }
        }
        if (prewarmed.usedPrefetchedItem) {
            prefetchedItem = null
        }
        for (i in prepared.startWindowIndex until prepared.uris.size) {
            pinAudioUri(prepared.uris[i])
        }
        prepared.preRoll?.let { pinAudioUri(it.djUri) }
        prepared.overlayCue?.let { pinAudioUri(it.djUri) }

        currentSegmentUris = prewarmed.uris
        currentSongItem = prewarmed.songItem
        lastSegment = prewarmed.segmentType
        introItemCount = prewarmed.introItemCount
        pendingBreakClips.clear()
        pendingBreakClips.addAll(prewarmed.pendingBreakClips)
        plannedOverlayCue = null
        plannedPreRollCue = null

        if (prewarmed.isContinuousMix) {
            if (prewarmed.markContinuousMixStarted) {
                hasStartedContinuousMix = true
            }
            pendingMixStartOffsetMs = 0L
            savedMixAbsMs = 0L
            continuousMixItem = prewarmed.songItem
        }
        if (prewarmed.mixMarkers.isNotEmpty()) {
            activeMixMarkers = prewarmed.mixMarkers
            activeMixSampleRate = prewarmed.mixSampleRate
            lastDisplayedMixTrackId = -1
            lastPolledMixPosMs = -1L
        } else {
            stopMixPoller(clearMarkers = true)
        }
    }

    override suspend fun prewarmNeighborForFastSwitch() {
        if (isStationActive || _playing || seamlessPrepareActive) return
        val myGen = prewarmGeneration.get()

        suspend fun isStillValid(): Boolean {
            return currentCoroutineContext().isActive &&
                prewarmGeneration.get() == myGen &&
                !isStationActive &&
                !_playing &&
                !seamlessPrepareActive
        }

        val existing = synchronized(prewarmLock) { prewarmedTakeOver }
        if (existing != null) {
            val extraMs = (SystemClock.elapsedRealtime() - existing.createdAtElapsedMs).coerceAtLeast(0L)
            val advanced = advancePrewarmedPlayback(existing, extraMs)
            if (advanced != null && areSegmentFilesValid(existing.uris)) {
                prewarmSingleTargetBlock(
                    uris = advanced.uris,
                    windowIndex = advanced.startWindowIndex,
                    positionMs = advanced.startPositionMs,
                    isStillValid = ::isStillValid
                )
                return
            }
            discardNeighborPrewarm()
        }

        if (!isStillValid()) return
        ensureStationIndexed()
        if (!isStillValid()) return

        val snapshot = resolvePrewarmedTakeOver { !isStationActive && !_playing && !seamlessPrepareActive && prewarmGeneration.get() == myGen }
            ?: return
        if (!isStillValid()) {
            snapshot.pinnedFiles.forEach { Rpf7Archive.unpinFile(it) }
            return
        }

        var replacedOld: PrewarmedTakeOver? = null
        val stored = synchronized(prewarmLock) {
            if (prewarmGeneration.get() != myGen || isStationActive || _playing || seamlessPrepareActive) {
                false
            } else {
                replacedOld = prewarmedTakeOver
                prewarmedTakeOver = snapshot
                true
            }
        }
        replacedOld?.pinnedFiles?.forEach { Rpf7Archive.unpinFile(it) }
        if (!stored) {
            snapshot.pinnedFiles.forEach { Rpf7Archive.unpinFile(it) }
            return
        }

        prewarmSingleTargetBlock(
            uris = snapshot.uris,
            windowIndex = snapshot.baseWindowIndex,
            positionMs = snapshot.basePositionMs,
            isStillValid = ::isStillValid
        )
    }

    private suspend fun prewarmSingleTargetBlock(
        uris: List<Uri>,
        windowIndex: Int,
        positionMs: Long,
        isStillValid: suspend () -> Boolean
    ) {
        val winUri = uris.getOrNull(windowIndex)
        val nextUri = uris.getOrNull(windowIndex + 1)
        val (targetUri, targetSeekMs) = when {
            winUri?.scheme == Rpf7StreamingDataSource.SCHEME -> winUri to positionMs
            nextUri?.scheme == Rpf7StreamingDataSource.SCHEME -> nextUri to 0L
            else -> return
        }
        val host = targetUri.host ?: return
        val rawPath = targetUri.encodedPath?.removePrefix("/")?.removeSuffix(".wav") ?: return
        val entryName = Uri.decode(rawPath)
        val archive = Rpf7StreamRegistry.get(host) ?: return
        val index = archive.getOrBuildStreamIndex(entryName) ?: return

        val bytesPerFrame = index.channels.toLong() * 2L
        val samplePos = (targetSeekMs.coerceAtLeast(0L) * index.sampleRate.toLong()) / 1000L
        val targetWavByte = (44L + samplePos * bytesPerFrame)
            .coerceIn(44L, (index.totalVirtualWavBytes - 1L).coerceAtLeast(44L))
        val targetBlockIdx = Rpf7StreamingDataSource.findBlockForWavByte(index, targetWavByte)
        val cacheKey = Gen3StreamBlockCache.buildKey(
            Rpf7StreamingDataSource.SCHEME,
            archive.archiveStreamId,
            index.entryName,
            targetBlockIdx
        )
        if (Gen3StreamBlockCache.get(cacheKey) != null) return
        if (!isStillValid()) return

        val prevWarmup = if (index.codec == 7 && targetBlockIdx > 0) {
            val prevKey = Gen3StreamBlockCache.buildKey(
                Rpf7StreamingDataSource.SCHEME,
                archive.archiveStreamId,
                index.entryName,
                targetBlockIdx - 1
            )
            Gen3StreamBlockCache.get(prevKey)?.tailWarmupByChannel
        } else {
            null
        }
        val outTail = if (index.codec == 7) arrayOfNulls<ByteArray?>(index.channels) else null
        var decodedPcm: ByteArray? = null
        archive.openStreamPfd(index.entryName)?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                if (isStillValid()) {
                    decodedPcm = archive.decodeSingleBlockToPcmBytes(
                        index = index,
                        blockIdx = targetBlockIdx,
                        prevWarmupByChannel = prevWarmup,
                        outTailWarmupByChannel = outTail,
                        openChannel = ch
                    )
                }
            }
        }
        val pcm = decodedPcm ?: return
        if (pcm.isNotEmpty() && isStillValid()) {
            Gen3StreamBlockCache.put(cacheKey, CachedStreamBlock(pcm, outTail))
            GTRadioLogger.logVerbose("PREWARM") {
                "Rpf7[$stationTitle]: Pre-warmed blk=$targetBlockIdx of '${index.entryName}' (${pcm.size / 1024} KB)"
            }
        }
    }

    private fun resolvePrewarmedTakeOver(isValid: () -> Boolean): PrewarmedTakeOver? {
        val isContinuousMix = isContinuousMixStation()
        val canResumeMix = isContinuousMix && savedMixAbsMs > 0L && continuousMixItem != null && lastStoppedTimeMs > 0L
        if (canResumeMix) {
            var item = continuousMixItem ?: return null
            val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
            var absMs = savedMixAbsMs + elapsedMs
            val parts = getSortedContinuousMixParts()
            if (parts.size > 1) {
                val totalCycleMs = parts.sumOf { it.archive.getOrBuildStreamIndex(it.trackName)?.durationMs ?: 0L }
                if (totalCycleMs > 0L) {
                    absMs %= totalCycleMs
                }
                var guard = 0
                while (guard < parts.size) {
                    val pDurMs = item.archive.getOrBuildStreamIndex(item.trackName)?.durationMs ?: 0L
                    if (pDurMs <= 0L || absMs < pDurMs) break
                    absMs -= pDurMs
                    val idx = parts.indexOfFirst { it.trackName.equals(item.trackName, ignoreCase = true) }
                    item = parts[if (idx >= 0) (idx + 1) % parts.size else 0]
                    guard++
                }
            }
            val streamIdx = item.archive.getOrBuildStreamIndex(item.trackName) ?: return null
            val totalMixDur = streamIdx.durationMs
            val targetMixMs = if (totalMixDur > 0L) absMs % totalMixDur else 0L
            val mainUri = item.archive.buildStreamUri(streamIdx.entryName)
            durationCache[mainUri] = totalMixDur
            if (!isValid()) return null
            val markers = item.archive.readTrackMarkers(streamIdx.entryName)
            return PrewarmedTakeOver(
                createdAtElapsedMs = SystemClock.elapsedRealtime(),
                uris = listOf(mainUri),
                windowDurationsMs = listOf(totalMixDur),
                baseWindowIndex = 0,
                basePositionMs = targetMixMs,
                totalDurMs = totalMixDur,
                songItem = item,
                segmentType = RadioSegmentType.Song,
                introItemCount = 0,
                pendingBreakClips = emptyList(),
                preRoll = null,
                overlayCue = null,
                pinnedFiles = emptyList(),
                isContinuousMix = true,
                markContinuousMixStarted = true,
                mixMarkers = markers,
                mixSampleRate = streamIdx.sampleRate.coerceAtLeast(1),
                consumedPreviousSegment = true,
                usedPrefetchedItem = false
            )
        }

        val prevUris = currentSegmentUris.toList()
        val canResumeTrack = !isContinuousMix &&
            director.currentPhase != Gen3DirectorPhase.BREAK_BLOCK &&
            lastStoppedTimeMs > 0L &&
            areSegmentFilesValid(prevUris)
        if (canResumeTrack) {
            var remainingMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
            val prevDurs = prevUris.map { getFileDurationMs(it) }
            val winIdx = stoppedWindowIndex.coerceIn(0, (prevUris.size - 1).coerceAtLeast(0))
            var posMs = stoppedPositionMs.coerceAtLeast(0L)

            val prevMix = currentSongItem?.let { Rpf7MetadataRepository.getTrackMeta(it.trackName).isContinuousMix } == true
            val markers = if ((isContinuousMix || prevMix) && currentSongItem != null) {
                activeMixMarkers.ifEmpty { currentSongItem!!.archive.readTrackMarkers(currentSongItem!!.trackName) }
            } else {
                activeMixMarkers
            }

            for (i in winIdx until prevUris.size) {
                val durMs = prevDurs[i]
                val timeLeftMs = (durMs - posMs).coerceAtLeast(0L)
                if (remainingMs < timeLeftMs && areSegmentFilesValid(prevUris.subList(i, prevUris.size))) {
                    if (!isValid()) return null
                    val pins = collectAndPinNonStreamingFiles(
                        prevUris,
                        listOfNotNull(plannedPreRollCue?.djUri, plannedOverlayCue?.djUri)
                    )
                    return PrewarmedTakeOver(
                        createdAtElapsedMs = SystemClock.elapsedRealtime(),
                        uris = prevUris,
                        windowDurationsMs = prevDurs,
                        baseWindowIndex = i,
                        basePositionMs = posMs + remainingMs,
                        totalDurMs = prevDurs.sum(),
                        songItem = currentSongItem,
                        segmentType = lastSegment,
                        introItemCount = introItemCount,
                        pendingBreakClips = pendingBreakClips.toList(),
                        preRoll = plannedPreRollCue,
                        overlayCue = plannedOverlayCue,
                        pinnedFiles = pins,
                        isContinuousMix = false,
                        markContinuousMixStarted = false,
                        mixMarkers = markers,
                        mixSampleRate = activeMixSampleRate,
                        consumedPreviousSegment = false,
                        usedPrefetchedItem = false
                    )
                } else {
                    remainingMs -= timeLeftMs
                    posMs = 0L
                }
            }

            var checkedPrefetched = false
            var safety = 0
            while (safety < 6 && isValid()) {
                safety++
                val forceLand = (safety >= 6)

                if (!checkedPrefetched) {
                    checkedPrefetched = true
                    val prefetched = prefetchedItem
                    if (prefetched != null && areSegmentFilesValid(prefetched.uris)) {
                        val durs = prefetched.uris.map { getFileDurationMs(it) }
                        val introCount = if (prefetched.plannedOverlayCue != null || prefetched.plannedPreRollCue != null) {
                            0
                        } else if (prefetched.uris.size > 1 && prefetched.segmentType == RadioSegmentType.Song) {
                            1
                        } else {
                            0
                        }
                        for (i in prefetched.uris.indices) {
                            val durMs = durs[i]
                            if (remainingMs < durMs) {
                                if (!isValid()) return null
                                val pins = collectAndPinNonStreamingFiles(
                                    prefetched.uris,
                                    listOfNotNull(prefetched.plannedPreRollCue?.djUri, prefetched.plannedOverlayCue?.djUri)
                                )
                                return PrewarmedTakeOver(
                                    createdAtElapsedMs = SystemClock.elapsedRealtime(),
                                    uris = prefetched.uris,
                                    windowDurationsMs = durs,
                                    baseWindowIndex = i,
                                    basePositionMs = remainingMs,
                                    totalDurMs = durs.sum(),
                                    songItem = prefetched.songItem,
                                    segmentType = prefetched.segmentType,
                                    introItemCount = introCount,
                                    pendingBreakClips = prefetched.pendingBreakClips ?: emptyList(),
                                    preRoll = prefetched.plannedPreRollCue,
                                    overlayCue = prefetched.plannedOverlayCue,
                                    pinnedFiles = pins,
                                    isContinuousMix = false,
                                    markContinuousMixStarted = false,
                                    mixMarkers = emptyList(),
                                    mixSampleRate = 48000,
                                    consumedPreviousSegment = true,
                                    usedPrefetchedItem = true
                                )
                            }
                            remainingMs -= durMs
                        }
                        if (forceLand) {
                            val pins = collectAndPinNonStreamingFiles(
                                prefetched.uris,
                                listOfNotNull(prefetched.plannedPreRollCue?.djUri, prefetched.plannedOverlayCue?.djUri)
                            )
                            return PrewarmedTakeOver(
                                createdAtElapsedMs = SystemClock.elapsedRealtime(),
                                uris = prefetched.uris,
                                windowDurationsMs = durs,
                                baseWindowIndex = 0,
                                basePositionMs = 0L,
                                totalDurMs = durs.sum(),
                                songItem = prefetched.songItem,
                                segmentType = prefetched.segmentType,
                                introItemCount = introCount,
                                pendingBreakClips = prefetched.pendingBreakClips ?: emptyList(),
                                preRoll = prefetched.plannedPreRollCue,
                                overlayCue = prefetched.plannedOverlayCue,
                                pinnedFiles = pins,
                                isContinuousMix = false,
                                markContinuousMixStarted = false,
                                mixMarkers = emptyList(),
                                mixSampleRate = 48000,
                                consumedPreviousSegment = true,
                                usedPrefetchedItem = true
                            )
                        }
                        continue
                    }
                }

                if (isContinuousMix && allSongItems.isNotEmpty()) {
                    return buildColdContinuousMixPrewarmSnapshot(isValid)
                }
                if (director.songQueue.isEmpty()) return null

                val event = director.pickNextEvent(
                    pickIntro = { song ->
                        if (song.introKeys.isNotEmpty()) {
                            Rpf7ClipRef(song.archive, song.introKeys[rng.nextInt(song.introKeys.size)])
                        } else null
                    },
                    pickFallbackIntro = {
                        if (director.timeOfDayDjQueue.isNotEmpty()) {
                            director.timeOfDayDjQueue.next()
                        } else if (director.generalDjQueue.isNotEmpty()) {
                            director.generalDjQueue.next()
                        } else null
                    }
                )
                val resolved = resolveEventForPrewarm(
                    event = event,
                    remainingMs = remainingMs,
                    forceLand = forceLand,
                    isColdStart = false,
                    isValid = isValid
                )
                if (resolved.snapshot != null) {
                    return resolved.snapshot
                }
                remainingMs = resolved.remainingMs
            }
            return null
        }

        if (isContinuousMix && allSongItems.isNotEmpty()) {
            return buildColdContinuousMixPrewarmSnapshot(isValid)
        }
        if (director.songQueue.isEmpty()) return null

        val event = director.pickNextEvent(
            pickIntro = { song ->
                if (song.introKeys.isNotEmpty()) {
                    Rpf7ClipRef(song.archive, song.introKeys[rng.nextInt(song.introKeys.size)])
                } else null
            },
            pickFallbackIntro = {
                if (director.timeOfDayDjQueue.isNotEmpty()) {
                    director.timeOfDayDjQueue.next()
                } else if (director.generalDjQueue.isNotEmpty()) {
                    director.generalDjQueue.next()
                } else null
            }
        )
        return resolveEventForPrewarm(
            event = event,
            remainingMs = 0L,
            forceLand = true,
            isColdStart = true,
            isValid = isValid
        ).snapshot
    }

    private fun buildColdContinuousMixPrewarmSnapshot(isValid: () -> Boolean): PrewarmedTakeOver? {
        val parts = getSortedContinuousMixParts()
        if (parts.isEmpty()) return null
        val item = parts[rng.nextInt(parts.size)]
        val streamIdx = item.archive.getOrBuildStreamIndex(item.trackName) ?: return null
        val mainUri = item.archive.buildStreamUri(streamIdx.entryName)
        val dur = streamIdx.durationMs
        durationCache[mainUri] = dur
        val startOff = if (dur > 30_000L) {
            rng.nextInt((dur - 30_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toLong()
        } else {
            0L
        }
        val markers = item.archive.readTrackMarkers(streamIdx.entryName)
        if (!isValid()) return null
        return PrewarmedTakeOver(
            createdAtElapsedMs = SystemClock.elapsedRealtime(),
            uris = listOf(mainUri),
            windowDurationsMs = listOf(dur),
            baseWindowIndex = 0,
            basePositionMs = startOff,
            totalDurMs = dur,
            songItem = item,
            segmentType = RadioSegmentType.Song,
            introItemCount = 0,
            pendingBreakClips = emptyList(),
            preRoll = null,
            overlayCue = null,
            pinnedFiles = emptyList(),
            isContinuousMix = true,
            markContinuousMixStarted = true,
            mixMarkers = markers,
            mixSampleRate = streamIdx.sampleRate.coerceAtLeast(1),
            consumedPreviousSegment = true,
            usedPrefetchedItem = false
        )
    }

    private data class EventPrewarmResult(
        val snapshot: PrewarmedTakeOver?,
        val remainingMs: Long
    )

    private fun resolveEventForPrewarm(
        event: Gen3BroadcastEvent<Rpf7SongItem, Rpf7ClipRef>,
        remainingMs: Long,
        forceLand: Boolean,
        isColdStart: Boolean,
        isValid: () -> Boolean
    ): EventPrewarmResult {
        var rem = remainingMs
        when (event) {
            is Gen3BroadcastEvent.SongEvent -> {
                val item = event.song
                val mainUri = resolvePlaybackUri(item.archive, item.trackName)
                val mainDurMs = if (mainUri != null) getFileDurationMs(mainUri) else 0L
                if (!isColdStart && !forceLand && mainDurMs > 0L && rem >= mainDurMs) {
                    rem -= mainDurMs
                    return EventPrewarmResult(null, rem)
                }

                val markers = item.archive.readDjMarkers(item.trackName)
                var landedSeekMs = if (isColdStart) 0L else if (forceLand && rem >= mainDurMs) 0L else rem.coerceAtLeast(0L)
                val introEstDur = if (!isColdStart && !event.isFallbackIntroOverlay && event.songIntroOverlay != null) {
                    event.songIntroOverlay.archive.getEntryDurationMs(event.songIntroOverlay.trackKey) ?: 0L
                } else 0L
                val pastIntroEnd = !isColdStart && markers != null && markers.hasIntroWindow && landedSeekMs >= markers.introEndMs
                val pastStandaloneIntro = !isColdStart && (markers == null || !markers.hasIntroWindow) && introEstDur > 0L && landedSeekMs >= introEstDur
                val skipIntroDecode = pastIntroEnd || pastStandaloneIntro
                if (pastStandaloneIntro) {
                    landedSeekMs = (landedSeekMs - introEstDur).coerceAtLeast(0L)
                }

                val built = buildSongSegmentMedia(
                    event = event,
                    mainUri = mainUri,
                    markers = markers,
                    skipIntroDecode = skipIntroDecode,
                    pinToActive = false
                )
                if (built.uris.isEmpty() || !isValid()) {
                    built.pinnedFiles.forEach { Rpf7Archive.unpinFile(it) }
                    return EventPrewarmResult(null, rem)
                }

                val durs = built.uris.map { getFileDurationMs(it) }
                val totalDur = durs.sum()
                val (winIdx, seekPos) = if (isColdStart) {
                    val coldOffset = if (!isMixItem(item) && built.uris.size == 1 && mainDurMs > 45_000L && built.overlayCue == null && built.preRollCue == null) {
                        rng.nextInt(25_000).toLong()
                    } else {
                        0L
                    }
                    0 to coldOffset
                } else {
                    val landed = resolveLandedWindowPlayback(built.uris, landedSeekMs, built.preRollCue, built.overlayCue)
                    landed.startWindowIndex to landed.startPositionMs
                }

                val snapMarkers = if (isMixItem(item)) item.archive.readTrackMarkers(item.trackName) else emptyList()
                val snapSr = if (snapMarkers.isNotEmpty()) item.archive.getEntrySampleRate(item.trackName).coerceAtLeast(1) else 48000
                return EventPrewarmResult(
                    snapshot = PrewarmedTakeOver(
                        createdAtElapsedMs = SystemClock.elapsedRealtime(),
                        uris = built.uris,
                        windowDurationsMs = durs,
                        baseWindowIndex = winIdx,
                        basePositionMs = seekPos,
                        totalDurMs = totalDur,
                        songItem = item,
                        segmentType = RadioSegmentType.Song,
                        introItemCount = built.introCount,
                        pendingBreakClips = emptyList(),
                        preRoll = built.preRollCue,
                        overlayCue = built.overlayCue,
                        pinnedFiles = built.pinnedFiles,
                        isContinuousMix = isMixItem(item),
                        markContinuousMixStarted = false,
                        mixMarkers = snapMarkers,
                        mixSampleRate = snapSr,
                        consumedPreviousSegment = true,
                        usedPrefetchedItem = false
                    ),
                    remainingMs = rem
                )
            }
            is Gen3BroadcastEvent.BreakBlockEvent -> {
                val standalone = ArrayList(event.standaloneClips)
                if (standalone.isEmpty()) return EventPrewarmResult(null, rem)
                if (!isColdStart && !forceLand) {
                    var landedIdx = -1
                    for (idx in standalone.indices) {
                        val clipRef = standalone[idx].clip
                        val clipDurMs = clipRef.archive.getEntryDurationMs(clipRef.trackKey) ?: 0L
                        if (clipDurMs > 0L && rem >= clipDurMs) {
                            rem -= clipDurMs
                        } else {
                            landedIdx = idx
                            break
                        }
                    }
                    if (landedIdx < 0) return EventPrewarmResult(null, rem)

                    val landedClip = standalone[landedIdx]
                    val u = resolvePlaybackUri(landedClip.clip.archive, landedClip.clip.trackKey)
                    if (u != null && isValid()) {
                        val durMs = getFileDurationMs(u)
                        val seekMs = rem.coerceIn(0L, (durMs - 1L).coerceAtLeast(0L))
                        val pins = collectAndPinNonStreamingFiles(listOf(u))
                        val remainingClips = if (landedIdx + 1 < standalone.size) {
                            standalone.subList(landedIdx + 1, standalone.size).toList()
                        } else emptyList()
                        return EventPrewarmResult(
                            snapshot = PrewarmedTakeOver(
                                createdAtElapsedMs = SystemClock.elapsedRealtime(),
                                uris = listOf(u),
                                windowDurationsMs = listOf(durMs),
                                baseWindowIndex = 0,
                                basePositionMs = seekMs,
                                totalDurMs = durMs,
                                songItem = null,
                                segmentType = landedClip.segmentType,
                                introItemCount = 0,
                                pendingBreakClips = remainingClips,
                                preRoll = null,
                                overlayCue = null,
                                pinnedFiles = pins,
                                isContinuousMix = false,
                                markContinuousMixStarted = false,
                                mixMarkers = emptyList(),
                                mixSampleRate = 48000,
                                consumedPreviousSegment = true,
                                usedPrefetchedItem = false
                            ),
                            remainingMs = rem
                        )
                    }
                } else {
                    val firstClip = standalone.removeAt(0)
                    val u = resolvePlaybackUri(firstClip.clip.archive, firstClip.clip.trackKey)
                    if (u != null && isValid()) {
                        val durMs = getFileDurationMs(u)
                        val pins = collectAndPinNonStreamingFiles(listOf(u))
                        return EventPrewarmResult(
                            snapshot = PrewarmedTakeOver(
                                createdAtElapsedMs = SystemClock.elapsedRealtime(),
                                uris = listOf(u),
                                windowDurationsMs = listOf(durMs),
                                baseWindowIndex = 0,
                                basePositionMs = 0L,
                                totalDurMs = durMs,
                                songItem = null,
                                segmentType = firstClip.segmentType,
                                introItemCount = 0,
                                pendingBreakClips = standalone,
                                preRoll = null,
                                overlayCue = null,
                                pinnedFiles = pins,
                                isContinuousMix = false,
                                markContinuousMixStarted = false,
                                mixMarkers = emptyList(),
                                mixSampleRate = 48000,
                                consumedPreviousSegment = true,
                                usedPrefetchedItem = false
                            ),
                            remainingMs = rem
                        )
                    }
                }
            }
            is Gen3BroadcastEvent.InterludeEvent -> {
                val ref = event.clip
                val clipDurMs = ref.archive.getEntryDurationMs(ref.trackKey) ?: 0L
                if (!isColdStart && !forceLand && clipDurMs > 0L && rem >= clipDurMs) {
                    rem -= clipDurMs
                    return EventPrewarmResult(null, rem)
                }
                val u = resolvePlaybackUri(ref.archive, ref.trackKey)
                if (u != null && isValid()) {
                    val durMs = getFileDurationMs(u)
                    val seekMs = if (isColdStart || (forceLand && rem >= durMs)) 0L else rem.coerceIn(0L, (durMs - 1L).coerceAtLeast(0L))
                    val pins = collectAndPinNonStreamingFiles(listOf(u))
                    return EventPrewarmResult(
                        snapshot = PrewarmedTakeOver(
                            createdAtElapsedMs = SystemClock.elapsedRealtime(),
                            uris = listOf(u),
                            windowDurationsMs = listOf(durMs),
                            baseWindowIndex = 0,
                            basePositionMs = seekMs,
                            totalDurMs = durMs,
                            songItem = null,
                            segmentType = event.segmentType,
                            introItemCount = 0,
                            pendingBreakClips = emptyList(),
                            preRoll = null,
                            overlayCue = null,
                            pinnedFiles = pins,
                            isContinuousMix = false,
                            markContinuousMixStarted = false,
                            mixMarkers = emptyList(),
                            mixSampleRate = 48000,
                            consumedPreviousSegment = true,
                            usedPrefetchedItem = false
                        ),
                        remainingMs = rem
                    )
                }
            }
        }
        return EventPrewarmResult(null, rem)
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
            clearPendingPrewarmPins()
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

        val takenPrewarm = takeValidPrewarmedTakeOver()
        if (takenPrewarm != null) {
            val (prewarmed, prepared) = takenPrewarm
            pendingPrewarmPinnedFiles = prewarmed.pinnedFiles
            seamlessPrepareActive = true
            GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: prewarm HIT (win=${prepared.startWindowIndex}, pos=${prepared.startPositionMs}ms)")
            onReady {
                if (playGeneration.get() != currentGen) return@onReady
                val commitGen = playGeneration.incrementAndGet()
                GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: seamless prewarm commit() (gen=$commitGen)")
                val stalePins = pendingPrewarmPinnedFiles
                pendingPrewarmPinnedFiles = emptyList()
                seamlessPrepareActive = false
                seamlessPreparePrevHasStartedMix = null
                seamlessPreparePrevSavedMixAbsMs = null
                applyPrewarmedStateForCommit(prewarmed, prepared)
                for (p in stalePins) {
                    Rpf7Archive.unpinFile(p)
                }
                isStationActive = true
                _playing = true
                player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.playWhenReady = false
                if (prepared.preRoll != null && prepared.startWindowIndex == 0 && prepared.startPositionMs == 0L) {
                    setStationOnlyMetadata()
                }
                launchPreparedPlayback(prepared, commitGen)
            }
            return
        }

        seamlessPreparePrevHasStartedMix = hasStartedContinuousMix
        seamlessPreparePrevSavedMixAbsMs = savedMixAbsMs
        seamlessPrepareActive = true

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
                        isStationActive = true
                        _playing = true
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
        discardNeighborPrewarm()
        clearPendingPrewarmPins()
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
                val rawPosMs = exo.currentPosition.coerceAtLeast(0L)
                val curPosMs = if (exo.duration > 0) rawPosMs.coerceAtMost(exo.duration) else rawPosMs
                savedMixAbsMs = curPosMs
                continuousMixItem = currentSongItem
                lastStoppedTimeMs = System.currentTimeMillis()
            }
        }

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
        stop()
        stationScope.cancel()
    }

    private fun areSegmentFilesValid(uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return false
        for (u in uris) {
            if (u.scheme == Rpf7StreamingDataSource.SCHEME) {
                if (resolveStreamIndex(u) == null) return false
            } else {
                val p = u.path ?: return false
                val f = File(p)
                if (!f.exists() || f.length() <= 0L) return false
            }
        }
        return true
    }

    override fun play() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { play() }
            return
        }
        discardNeighborPrewarm()
        clearPendingPrewarmPins()
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
        seamlessPrepareActive = false
        seamlessPreparePrevHasStartedMix = null
        seamlessPreparePrevSavedMixAbsMs = null
        val currentGen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: play() called (gen=$currentGen)")
        isStationActive = true
        _playing = true
        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

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
        val mixOffset = pendingMixStartOffsetMs
        pendingMixStartOffsetMs = 0L
        val startOffset = when {
            isMixItem(currentSongItem) -> mixOffset.coerceIn(0L, (mainDur - 1L).coerceAtLeast(0L))
            lastSegment == RadioSegmentType.Song && currentSegmentUris.size == 1 && mainDur > 45_000L ->
                rng.nextInt(25_000).toLong()
            else -> 0L
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
        val isMediaPlayer = stationKey.contains("radio_36_audioplayer", ignoreCase = true) ||
            stationArchive.rpfName.contains("radio_36_audioplayer", ignoreCase = true)
        val pool = if (isMediaPlayer) {
            allSongItems
        } else {
            val mixTagged = allSongItems.filter { Rpf7MetadataRepository.getTrackMeta(it.trackName).isContinuousMix }
            if (mixTagged.isNotEmpty()) mixTagged else allSongItems
        }
        return pool.sortedWith(MIX_PART_COMPARATOR)
    }

    private fun resolveContinuousMixPrepared(
        elapsedMs: Long,
        isValid: () -> Boolean
    ): PreparedPlayback? {
        var item = continuousMixItem ?: return null
        var absMs = savedMixAbsMs + elapsedMs
        val parts = getSortedContinuousMixParts()
        if (parts.size > 1) {
            val totalCycleMs = parts.sumOf { it.archive.getOrBuildStreamIndex(it.trackName)?.durationMs ?: 0L }
            if (totalCycleMs > 0L) {
                absMs %= totalCycleMs
            }
            var guard = 0
            while (guard < parts.size) {
                val pIdx = item.archive.getOrBuildStreamIndex(item.trackName)
                val pDurMs = pIdx?.durationMs ?: 0L
                if (pDurMs <= 0L || absMs < pDurMs) break
                absMs -= pDurMs
                val idx = parts.indexOfFirst { it.trackName.equals(item.trackName, ignoreCase = true) }
                item = parts[if (idx >= 0) (idx + 1) % parts.size else 0]
                guard++
            }
        }

        val streamIdx = item.archive.getOrBuildStreamIndex(item.trackName) ?: return null
        val totalMixDurationMs = streamIdx.durationMs
        val targetMixMs = if (totalMixDurationMs > 0L) absMs % totalMixDurationMs else 0L
        val mainUri = item.archive.buildStreamUri(streamIdx.entryName)
        durationCache[mainUri] = totalMixDurationMs
        if (!isValid()) return null

        hasStartedContinuousMix = true
        pendingMixStartOffsetMs = 0L
        currentSongItem = item
        lastSegment = RadioSegmentType.Song
        currentSegmentUris = listOf(mainUri)
        introItemCount = 0

        val markers = item.archive.readTrackMarkers(streamIdx.entryName)
        if (markers.isNotEmpty()) {
            activeMixMarkers = markers
            activeMixSampleRate = streamIdx.sampleRate.coerceAtLeast(1)
            lastDisplayedMixTrackId = -1
            lastPolledMixPosMs = -1L
        } else {
            stopMixPoller(clearMarkers = true)
        }

        return PreparedPlayback(listOf(mainUri), 0, targetMixMs, totalMixDurationMs, null, null)
    }

    private data class BuiltSongMedia(
        val uris: List<Uri>,
        val pinnedFiles: List<String>,
        val overlayCue: PlannedOverlayCue?,
        val preRollCue: PlannedPreRollCue?,
        val introCount: Int
    )

    private fun buildSongSegmentMedia(
        event: Gen3BroadcastEvent.SongEvent<Rpf7SongItem, Rpf7ClipRef>,
        mainUri: Uri?,
        markers: DjMarkerWindow?,
        skipIntroDecode: Boolean,
        pinToActive: Boolean
    ): BuiltSongMedia {
        val uris = ArrayList<Uri>()
        val pinned = ArrayList<String>()

        var djIntroUri: Uri? = null
        var djDurMs = 0L
        val fallbackIneligible = event.isFallbackIntroOverlay &&
            (markers == null || !markers.hasIntroWindow || markers.introDurationMs < 3000L)
        if (!skipIntroDecode && !fallbackIneligible && event.songIntroOverlay != null) {
            val u = materializeClip(event.songIntroOverlay.trackKey)
            if (u != null) {
                val dur = getFileDurationMs(u)
                val isIntroEligible = if (event.isFallbackIntroOverlay) {
                    markers != null && markers.hasIntroWindow && markers.introDurationMs >= 3000L && dur in 1..markers.introDurationMs
                } else {
                    true
                }
                if (isIntroEligible) {
                    if (pinToActive) {
                        pinAudioUri(u)
                    } else if (u.scheme != Rpf7StreamingDataSource.SCHEME) {
                        val p = u.toString()
                        pinned.add(p)
                        Rpf7Archive.pinFile(p)
                    }
                    djIntroUri = u
                    djDurMs = dur
                }
            }
        }

        if (mainUri != null) {
            if (pinToActive) {
                pinAudioUri(mainUri)
            } else if (mainUri.scheme != Rpf7StreamingDataSource.SCHEME) {
                val p = mainUri.toString()
                pinned.add(p)
                Rpf7Archive.pinFile(p)
            }
        }

        val fitsInIntro = djIntroUri != null && markers != null && markers.hasIntroWindow &&
            djDurMs > 0L && djDurMs <= markers.introDurationMs
        val canPreRoll = !event.isFallbackIntroOverlay && djIntroUri != null && markers != null && markers.hasIntroWindow &&
            djDurMs > markers.introDurationMs && markers.introEndMs >= 2000L && markers.effectiveIntroStartMs <= 3000L

        var overlayCue: PlannedOverlayCue? = null
        var preRollCue: PlannedPreRollCue? = null
        var introCount = 0

        when {
            fitsInIntro -> {
                mainUri?.let { uris.add(it) }
                val djStartMs = maxOf(markers!!.effectiveIntroStartMs, markers.introEndMs - djDurMs - 250L)
                overlayCue = PlannedOverlayCue(djIntroUri!!, djStartMs, markers.introEndMs)
                GTRadioLogger.logVerbose("DJ-OVERLAY") {
                    "${event.song.trackName}: fits-in-intro [${markers.effectiveIntroStartMs}..${markers.introEndMs}ms], djDur=${djDurMs}ms, fireAt=${djStartMs}ms"
                }
            }
            canPreRoll -> {
                mainUri?.let { uris.add(it) }
                val preRollDelayMs = (djDurMs - markers!!.introEndMs + 250L).coerceIn(500L, (djDurMs - 500L).coerceAtLeast(500L))
                preRollCue = PlannedPreRollCue(djIntroUri!!, preRollDelayMs, markers.introEndMs)
                GTRadioLogger.logVerbose("DJ-OVERLAY") {
                    "${event.song.trackName}: pre-roll djDur=${djDurMs}ms, introEnd=${markers.introEndMs}ms, delay=${preRollDelayMs}ms"
                }
            }
            else -> {
                if (!event.isFallbackIntroOverlay && djIntroUri != null) {
                    uris.add(djIntroUri)
                }
                if (mainUri != null) {
                    uris.add(mainUri)
                }
                introCount = if (uris.size > 1) 1 else 0
            }
        }

        return BuiltSongMedia(uris, pinned, overlayCue, preRollCue, introCount)
    }

    private fun resolveLandedWindowPlayback(
        uris: List<Uri>,
        landedSeekMs: Long,
        preRoll: PlannedPreRollCue?,
        overlay: PlannedOverlayCue?
    ): PreparedPlayback {
        val totalDur = uris.sumOf { getFileDurationMs(it) }
        var rem = landedSeekMs.coerceAtLeast(0L)
        for (i in uris.indices) {
            val dur = getFileDurationMs(uris[i])
            if (rem < dur || i == uris.lastIndex) {
                val clampedSeek = rem.coerceIn(0L, (dur - 1L).coerceAtLeast(0L))
                return PreparedPlayback(uris, i, clampedSeek, totalDur, preRoll, overlay)
            }
            rem -= dur
        }
        return PreparedPlayback(uris, 0, 0L, totalDur, preRoll, overlay)
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
        val winIdx = stoppedWindowIndex.coerceIn(0, (currentSegmentUris.size - 1).coerceAtLeast(0))
        var posMs = stoppedPositionMs.coerceAtLeast(0L)

        val isContinuousMix = isContinuousMixStation() || (currentSongItem != null && Rpf7MetadataRepository.getTrackMeta(currentSongItem!!.trackName).isContinuousMix)
        if (isContinuousMix && currentSongItem != null && activeMixMarkers.isEmpty()) {
            val markers = currentSongItem!!.archive.readTrackMarkers(currentSongItem!!.trackName)
            if (markers.isNotEmpty()) {
                activeMixMarkers = markers
            }
        }

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

        for (u in currentSegmentUris) {
            unpinAudioUri(u)
        }
        currentSegmentUris = emptyList()
        if (emitMetadata) {
            setStationOnlyMetadata()
        }

        var safety = 0
        while (safety < 6 && isValid()) {
            safety++
            val forceLand = (safety >= 6)

            val prefetched = prefetchedItem
            if (prefetched != null) {
                prefetchedItem = null
                if (areSegmentFilesValid(prefetched.uris)) {
                    currentSegmentUris = prefetched.uris
                    currentSongItem = prefetched.songItem
                    lastSegment = prefetched.segmentType
                    pendingBreakClips.clear()
                    prefetched.pendingBreakClips?.let { pendingBreakClips.addAll(it) }
                    plannedOverlayCue = prefetched.plannedOverlayCue
                    plannedPreRollCue = prefetched.plannedPreRollCue
                    introItemCount = if (prefetched.plannedOverlayCue != null || prefetched.plannedPreRollCue != null) 0 else if (currentSegmentUris.size > 1 && lastSegment == RadioSegmentType.Song) 1 else 0

                    for (i in currentSegmentUris.indices) {
                        val durMs = getFileDurationMs(currentSegmentUris[i])
                        if (remainingMs < durMs) {
                            if (!isValid()) return null
                            for (u in currentSegmentUris.subList(i, currentSegmentUris.size)) pinAudioUri(u)
                            val totalDur = currentSegmentUris.sumOf { getFileDurationMs(it) }
                            val preRoll = plannedPreRollCue
                            plannedPreRollCue = null
                            val overlay = plannedOverlayCue
                            plannedOverlayCue = null
                            return PreparedPlayback(currentSegmentUris, i, remainingMs, totalDur, preRoll, overlay)
                        }
                        remainingMs -= durMs
                    }
                    for (u in currentSegmentUris) unpinAudioUri(u)
                    if (forceLand) {
                        for (u in currentSegmentUris) pinAudioUri(u)
                        val totalDur = currentSegmentUris.sumOf { getFileDurationMs(it) }
                        val preRoll = plannedPreRollCue
                        plannedPreRollCue = null
                        val overlay = plannedOverlayCue
                        plannedOverlayCue = null
                        return PreparedPlayback(currentSegmentUris, 0, 0L, totalDur, preRoll, overlay)
                    }
                    continue
                }
            }

            if (isContinuousMix && allSongItems.isNotEmpty()) {
                prepareContinuousMixSegment()
                if (currentSegmentUris.isNotEmpty() && isValid()) {
                    val startOff = pendingMixStartOffsetMs
                    pendingMixStartOffsetMs = 0L
                    return PreparedPlayback(currentSegmentUris, 0, startOff, getFileDurationMs(currentSegmentUris[0]), null, null)
                }
                break
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

            when (event) {
                is Gen3BroadcastEvent.SongEvent -> {
                    val item = event.song
                    val mainUri = resolvePlaybackUri(item.archive, item.trackName)
                    val mainDurMs = if (mainUri != null) getFileDurationMs(mainUri) else 0L
                    if (!forceLand && mainDurMs > 0L && remainingMs >= mainDurMs) {
                        remainingMs -= mainDurMs
                        GTRadioLogger.logVerbose("CATCHUP") {
                            "Rpf7[$stationTitle]: skipped elapsed '${item.trackName}' (${mainDurMs}ms) without decoding intro; rem=${remainingMs}ms"
                        }
                        continue
                    }

                    var landedSeekMs = if (forceLand && remainingMs >= mainDurMs) 0L else remainingMs.coerceAtLeast(0L)
                    val markers = item.archive.readDjMarkers(item.trackName)
                    val introEstDur = if (!event.isFallbackIntroOverlay && event.songIntroOverlay != null) {
                        event.songIntroOverlay.archive.getEntryDurationMs(event.songIntroOverlay.trackKey) ?: 0L
                    } else 0L
                    val pastIntroEnd = markers != null && markers.hasIntroWindow && landedSeekMs >= markers.introEndMs
                    val pastStandaloneIntro = (markers == null || !markers.hasIntroWindow) && introEstDur > 0L && landedSeekMs >= introEstDur
                    val skipIntroDecode = pastIntroEnd || pastStandaloneIntro
                    if (pastStandaloneIntro) {
                        landedSeekMs = (landedSeekMs - introEstDur).coerceAtLeast(0L)
                    }

                    val built = buildSongSegmentMedia(
                        event = event,
                        mainUri = mainUri,
                        markers = markers,
                        skipIntroDecode = skipIntroDecode,
                        pinToActive = true
                    )
                    if (built.uris.isEmpty() || !isValid()) return null

                    currentSegmentUris = built.uris
                    currentSongItem = item
                    lastSegment = RadioSegmentType.Song
                    introItemCount = built.introCount
                    pendingBreakClips.clear()
                    plannedOverlayCue = null
                    plannedPreRollCue = null
                    return resolveLandedWindowPlayback(built.uris, landedSeekMs, built.preRollCue, built.overlayCue)
                }
                is Gen3BroadcastEvent.BreakBlockEvent -> {
                    val standalone = ArrayList(event.standaloneClips)
                    if (standalone.isEmpty()) continue
                    if (!forceLand) {
                        var landedIdx = -1
                        for (idx in standalone.indices) {
                            val clipRef = standalone[idx].clip
                            val clipDurMs = clipRef.archive.getEntryDurationMs(clipRef.trackKey) ?: 0L
                            if (clipDurMs > 0L && remainingMs >= clipDurMs) {
                                remainingMs -= clipDurMs
                            } else {
                                landedIdx = idx
                                break
                            }
                        }
                        if (landedIdx < 0) continue

                        val landedClip = standalone[landedIdx]
                        val u = resolvePlaybackUri(landedClip.clip.archive, landedClip.clip.trackKey)
                        if (u != null && isValid()) {
                            pinAudioUri(u)
                            currentSegmentUris = listOf(u)
                            currentSongItem = null
                            lastSegment = landedClip.segmentType
                            introItemCount = 0
                            pendingBreakClips.clear()
                            if (landedIdx + 1 < standalone.size) {
                                pendingBreakClips.addAll(standalone.subList(landedIdx + 1, standalone.size))
                            }
                            plannedOverlayCue = null
                            plannedPreRollCue = null
                            val durMs = getFileDurationMs(u)
                            val seekMs = remainingMs.coerceIn(0L, (durMs - 1L).coerceAtLeast(0L))
                            return PreparedPlayback(listOf(u), 0, seekMs, durMs, null, null)
                        }
                    } else {
                        val firstClip = standalone.removeAt(0)
                        val u = resolvePlaybackUri(firstClip.clip.archive, firstClip.clip.trackKey)
                        if (u != null && isValid()) {
                            pinAudioUri(u)
                            currentSegmentUris = listOf(u)
                            currentSongItem = null
                            lastSegment = firstClip.segmentType
                            introItemCount = 0
                            pendingBreakClips.clear()
                            pendingBreakClips.addAll(standalone)
                            plannedOverlayCue = null
                            plannedPreRollCue = null
                            return PreparedPlayback(listOf(u), 0, 0L, getFileDurationMs(u), null, null)
                        }
                    }
                }
                is Gen3BroadcastEvent.InterludeEvent -> {
                    val ref = event.clip
                    val clipDurMs = ref.archive.getEntryDurationMs(ref.trackKey) ?: 0L
                    if (!forceLand && clipDurMs > 0L && remainingMs >= clipDurMs) {
                        remainingMs -= clipDurMs
                        continue
                    }
                    val u = resolvePlaybackUri(ref.archive, ref.trackKey)
                    if (u != null && isValid()) {
                        pinAudioUri(u)
                        currentSegmentUris = listOf(u)
                        currentSongItem = null
                        lastSegment = event.segmentType
                        introItemCount = 0
                        pendingBreakClips.clear()
                        plannedOverlayCue = null
                        plannedPreRollCue = null
                        val durMs = getFileDurationMs(u)
                        val seekMs = if (forceLand && remainingMs >= durMs) 0L else remainingMs.coerceIn(0L, (durMs - 1L).coerceAtLeast(0L))
                        return PreparedPlayback(listOf(u), 0, seekMs, durMs, null, null)
                    }
                }
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
        pickNextSegment(emitMetadata)
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
            try {
                if (!_playing || playGeneration.get() != currentGen) return@launch
                if (advanceMultiChunkOrPickNext(emitMetadata = true)) {
                    if (!_playing || playGeneration.get() != currentGen) return@launch
                    val startPosMs = pendingMixStartOffsetMs
                    pendingMixStartOffsetMs = 0L
                    startPlayback(currentSegmentUris, 0, startPosMs, currentGen)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                GTRadioLogger.e("Rpf7RadioStation[$stationTitle]: playNext decode error", e)
            }
        }
    }

    private fun pickNextSegment(emitMetadata: Boolean = true) {
        hasPrefetchedNextSegment = false
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
                val markers = item.archive.readDjMarkers(item.trackName)
                val mainUri = resolvePlaybackUri(item.archive, item.trackName)
                val built = buildSongSegmentMedia(
                    event = event,
                    mainUri = mainUri,
                    markers = markers,
                    skipIntroDecode = false,
                    pinToActive = true
                )

                plannedOverlayCue = built.overlayCue
                plannedPreRollCue = built.preRollCue
                introItemCount = built.introCount
                currentSegmentUris = built.uris
                currentSongItem = item
                lastSegment = RadioSegmentType.Song
                pendingBreakClips.clear()
            }
            is Gen3BroadcastEvent.BreakBlockEvent -> {
                if (emitMetadata) setStationOnlyMetadata()
                val standalone = ArrayList(event.standaloneClips)
                if (standalone.isNotEmpty()) {
                    val firstClip = standalone.removeAt(0)
                    val u = resolvePlaybackUri(firstClip.clip.archive, firstClip.clip.trackKey)
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
                val u = resolvePlaybackUri(ref.archive, ref.trackKey)
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
        val coldStart = !hasStartedContinuousMix
        if (coldStart) {
            item = parts[rng.nextInt(parts.size)]
            hasStartedContinuousMix = true
        } else {
            val prev = currentSongItem
            item = if (parts.size > 1 && prev != null) {
                val idx = parts.indexOfFirst { it.trackName.equals(prev.trackName, ignoreCase = true) }
                parts[if (idx >= 0) (idx + 1) % parts.size else 0]
            } else {
                prev ?: parts[0]
            }
        }

        val streamIdx = item.archive.getOrBuildStreamIndex(item.trackName) ?: return
        val mainUri = item.archive.buildStreamUri(streamIdx.entryName)
        durationCache[mainUri] = streamIdx.durationMs
        pendingMixStartOffsetMs = if (coldStart && streamIdx.durationMs > 30_000L) {
            rng.nextInt((streamIdx.durationMs - 30_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toLong()
        } else {
            0L
        }

        val markers = item.archive.readTrackMarkers(streamIdx.entryName)
        if (markers.isNotEmpty()) {
            activeMixMarkers = markers
            activeMixSampleRate = streamIdx.sampleRate.coerceAtLeast(1)
            lastDisplayedMixTrackId = -1
            lastPolledMixPosMs = -1L
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
                val isFinalChunk = !isMixItem(songItem)

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
                val markers = item.archive.readDjMarkers(item.trackName)
                val mainUri = resolvePlaybackUri(item.archive, item.trackName)
                val built = buildSongSegmentMedia(
                    event = event,
                    mainUri = mainUri,
                    markers = markers,
                    skipIntroDecode = false,
                    pinToActive = false
                )

                if (built.uris.isNotEmpty() && _playing && playGeneration.get() == expectedGen) {
                    prefetchedItem = PrefetchedBroadcastItem(
                        uris = built.uris,
                        pinnedFiles = built.pinnedFiles,
                        event = event,
                        songItem = item,
                        segmentType = RadioSegmentType.Song,
                        plannedOverlayCue = built.overlayCue,
                        plannedPreRollCue = built.preRollCue
                    )
                    GTRadioLogger.logVerbose("DIRECTOR") {
                        "Rpf7[$stationTitle]: Look-ahead prefetched next song '${item.trackName}' (intro=${event.songIntroOverlay != null}, fallback=${event.isFallbackIntroOverlay})"
                    }
                } else {
                    built.pinnedFiles.forEach { Rpf7Archive.unpinFile(it) }
                }
            }
            is Gen3BroadcastEvent.BreakBlockEvent -> {
                val standalone = ArrayList(event.standaloneClips)
                if (standalone.isNotEmpty()) {
                    val firstClip = standalone.first()
                    val u = resolvePlaybackUri(firstClip.clip.archive, firstClip.clip.trackKey)
                    if (u != null && _playing && playGeneration.get() == expectedGen) {
                        val pinned = if (u.scheme != Rpf7StreamingDataSource.SCHEME) {
                            val p = u.toString()
                            Rpf7Archive.pinFile(p)
                            arrayListOf(p)
                        } else {
                            emptyList()
                        }
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
                val u = resolvePlaybackUri(ref.archive, ref.trackKey)
                if (u != null && _playing && playGeneration.get() == expectedGen) {
                    val pinned = if (u.scheme != Rpf7StreamingDataSource.SCHEME) {
                        val p = u.toString()
                        Rpf7Archive.pinFile(p)
                        listOf(p)
                    } else {
                        emptyList()
                    }
                    prefetchedItem = PrefetchedBroadcastItem(
                        uris = listOf(u),
                        pinnedFiles = pinned,
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
            if (overlayCue != null && startWindowIndex == 0 && startPositionMs <= overlayCue.djStartMs) {
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
                val isSinglePartMix = isMixItem(currentSongItem) && getSortedContinuousMixParts().size <= 1
                player.repeatMode = if (isSinglePartMix) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                for (u in uris) {
                    player.exoPlayer.addMediaItem(MediaItem.fromUri(u))
                }

                if (_playing && playGeneration.get() == expectedGen) {
                    if (startWindowIndex > 0 || startPositionMs > 0L) {
                        player.exoPlayer.seekTo(startWindowIndex, startPositionMs)
                    }
                    updateMetadataForWindowIndex(startWindowIndex, expectedGen)

                    activeListener = object : Player.Listener {
                        private var alreadyHandled = false

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && activeMixMarkers.isNotEmpty()) {
                                updateMixTrackMetadata()
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
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && activeMixMarkers.isNotEmpty()) {
                                lastDisplayedMixTrackId = -1
                                lastPolledMixPosMs = 0L
                                updateMixTrackMetadata()
                            }
                            val curIdx = exo.currentMediaItemIndex.coerceAtLeast(0)
                            updateMetadataForWindowIndex(curIdx, expectedGen)

                            // Staged Break Decoding: while current clip plays, stage next queued break clip
                            stageNextPendingBreakClip(expectedGen)
                        }

                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int
                        ) {
                            if (!_playing || playGeneration.get() != expectedGen) return
                            if (activeMixMarkers.isNotEmpty() &&
                                (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                                 reason == Player.DISCONTINUITY_REASON_SEEK)
                            ) {
                                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                                    newPosition.positionMs < oldPosition.positionMs
                                ) {
                                    lastDisplayedMixTrackId = -1
                                    lastPolledMixPosMs = newPosition.positionMs
                                }
                                updateMixTrackMetadata()
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

                        // Start look-ahead prefetch monitor for discrete songs & talk shows
                        if (!isMixItem(currentSongItem) && currentSongItem != null) {
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
                val uri = resolvePlaybackUri(nextClip.clip.archive, nextClip.clip.trackKey) ?: return@launch
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
                val uri = resolvePlaybackUri(nextClip.clip.archive, nextClip.clip.trackKey)
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
                if (exo.playbackState == Player.STATE_READY) {
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
            lastDisplayedMixTrackId = -1
            lastPolledMixPosMs = -1L
        }
    }

    private fun updateMixTrackMetadata() {
        if (!_playing) return
        val exo = player.exoPlayer
        if (exo.playbackState != Player.STATE_READY) return
        val item = currentSongItem ?: return
        if (activeMixMarkers.isEmpty()) {
            val markers = item.archive.readTrackMarkers(item.trackName)
            if (markers.isNotEmpty()) {
                activeMixMarkers = markers
                activeMixSampleRate = item.archive.getEntrySampleRate(item.trackName).coerceAtLeast(1)
            }
        }
        if (activeMixMarkers.isEmpty()) return
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }
        val posMs = exo.currentPosition.coerceAtLeast(0L).let {
            if (exo.duration > 0) it.coerceAtMost(exo.duration) else it
        }
        if (lastPolledMixPosMs > 10_000L && posMs < 2_000L) {
            lastDisplayedMixTrackId = -1
        }
        lastPolledMixPosMs = posMs
        val currentSample = (posMs * activeMixSampleRate.toLong()) / 1000L

        var resolvedTrackId = -1
        for (m in activeMixMarkers) {
            if (m.sampleOffset <= currentSample) {
                resolvedTrackId = m.trackId
            } else {
                break
            }
        }

        if (resolvedTrackId == lastDisplayedMixTrackId) return

        val meta = if (resolvedTrackId > 1 && resolvedTrackId != 1687) {
            Rpf7MetadataRepository.getTrackByTrackId(resolvedTrackId, context)
        } else {
            null
        }

        if (meta != null) {
            lastDisplayedMixTrackId = resolvedTrackId
            GTRadioLogger.logVerbose("TRACKID") {
                "Rpf7RadioStation[$stationTitle]: TRACKID transition -> $resolvedTrackId at sample $currentSample (${currentSample / activeMixSampleRate.toDouble()}s)"
            }
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
        } else if (lastDisplayedMixTrackId != -1) {
            GTRadioLogger.logVerbose("TRACKID") {
                "Rpf7RadioStation[$stationTitle]: TRACKID break reset (tid=$resolvedTrackId) at sample $currentSample (${currentSample / activeMixSampleRate.toDouble()}s)"
            }
            lastDisplayedMixTrackId = -1
            setStationOnlyMetadata()
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
            !isNonSongStem(stem) &&
                !Rpf7MetadataRepository.getTrackMeta(item.trackName).isContinuousMix
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
            val meta = resolveDiscreteTrackMeta(item)
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
        val meta = resolveDiscreteTrackMeta(targetItem)
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
                val uri = resolvePlaybackUri(targetItem.archive, targetItem.trackName)
                if (token != cleanPlayGeneration.get()) return@launch
                if (uri == null) {
                    GTRadioLogger.log("Rpf7RadioStation: Failed to build clean track stream for '${targetItem.trackName}'")
                    withContext(Dispatchers.Main.immediate) { onTrackFinished() }
                    return@launch
                }
                val dur = getFileDurationMs(uri)
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
        private val STATION_ID_REGEX = Regex("^id_\\d+.*")

        private fun isVoiceBankStem(stem: String): Boolean =
            stem == "time" || stem.endsWith("_time") ||
                stem == "to" || stem.endsWith("_to") ||
                stem == "intro" || stem.endsWith("_intro") ||
                stem == "general" || stem.endsWith("_general")

        private fun isStationIdStem(stem: String): Boolean =
            STATION_ID_REGEX.matches(stem) ||
                (stem.startsWith("id_") && stem != "id_rather_be_with_you") ||
                stem.startsWith("generic_id_") ||
                stem.startsWith("takeover_id_") ||
                stem.startsWith("moto_ident_") ||
                stem.startsWith("dlc_sec_id_")

        private fun isDjSoloStem(stem: String): Boolean =
            stem.startsWith("mono_solo_") ||
                stem.startsWith("djsolo_") ||
                stem.startsWith("custom_djsolo_") ||
                stem.startsWith("takeover_djsolo_") ||
                stem.startsWith("motomami_dj_solo_") ||
                stem.startsWith("dj_mono_solo_")

        private fun isLocalAdvertStem(stem: String): Boolean =
            stem.startsWith("kult_ad")

        private fun isIgnoredSilenceStem(stem: String): Boolean =
            stem == "tuner_ap_nomix_silence"

        private fun isNonSongStem(stem: String): Boolean =
            isVoiceBankStem(stem) ||
                isStationIdStem(stem) ||
                isDjSoloStem(stem) ||
                isLocalAdvertStem(stem) ||
                isIgnoredSilenceStem(stem)

        private fun mixSortTier(stem: String): Int = when {
            stem.contains("island_update") -> 1
            stem.startsWith("tuner_ap_mix") -> 0
            stem.startsWith("ap_dre_") || stem.startsWith("nez_") || stem.startsWith("schoolboy_") -> 1
            else -> 0
        }

        private fun compareNaturalNames(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ia = i
                    while (ia < a.length && a[ia].isDigit()) ia++
                    var jb = j
                    while (jb < b.length && b[jb].isDigit()) jb++
                    val numA = a.substring(i, ia).trimStart('0')
                    val numB = b.substring(j, jb).trimStart('0')
                    if (numA.length != numB.length) return numA.length.compareTo(numB.length)
                    val cmp = numA.compareTo(numB)
                    if (cmp != 0) return cmp
                    i = ia
                    j = jb
                } else {
                    if (ca != cb) return ca.compareTo(cb)
                    i++
                    j++
                }
            }
            return a.length.compareTo(b.length)
        }

        private val MIX_PART_COMPARATOR = Comparator<Rpf7SongItem> { a, b ->
            val sa = a.trackName.lowercase(Locale.ROOT).removeSuffix(".awc")
            val sb = b.trackName.lowercase(Locale.ROOT).removeSuffix(".awc")
            val tierCmp = mixSortTier(sa).compareTo(mixSortTier(sb))
            if (tierCmp != 0) tierCmp else compareNaturalNames(sa, sb)
        }

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
