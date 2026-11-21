package com.sc.gtradio.media.stations

import com.sc.gtradio.media.GTRadioPlayer
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
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import java.util.Random
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
    override var adsEnabled: Boolean,
    override var weatherChatterEnabled: Boolean = false,
    override var newsReportsEnabled: Boolean
) : RadioStation, TracklistCapableStation {

    override var onMetadataChanged: ((MediaMetadata) -> Unit)? = null
    override var stationName: String = (mediaItem.mediaMetadata.title ?: stationArchive.rpfName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.mediaMetadata.subtitle ?: "GTA V").toString()
    private val stationLogo: Bitmap? = mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.loadBitmap(context, it, 512) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rng = Random()

    private var _currentMetadata: MediaMetadata = buildStationMetadata(stationTitle, "", stationLogo)
    override val metadata: MediaMetadata
        get() = _currentMetadata

    private var initialized = false
    private var isTalkRadio = false
    private var isContinuousMixStation = false

    private val idQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)
    private val soloQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)
    private val generalDjQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)
    private val morningDjList = ArrayList<Rpf7ClipRef>()
    private val afternoonDjList = ArrayList<Rpf7ClipRef>()
    private val eveningDjList = ArrayList<Rpf7ClipRef>()
    private val nightDjList = ArrayList<Rpf7ClipRef>()
    private val timeOfDayDjQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)

    private val toAdQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)
    private val toNewsQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)
    private val toWeatherQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)

    private val advertQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)
    private val newsQueue = RandomizedDualHeadQueue<Rpf7ClipRef>(emptyList(), rng)
    private val songQueue = RandomizedDualHeadQueue<Rpf7SongItem>(emptyList(), rng)
    private val allSongItems = ArrayList<Rpf7SongItem>()
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val cleanPlayExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var songsSinceLastBreak = 0
    private var consecutiveSongs = 0
    private var interludeChainCount = 0
    private var activeHourBucket = -1
    private var activeListener: Player.Listener? = null

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: Rpf7SongItem? = null
    private var currentMultiChunkNextBlock: Int = 0
    private var currentMultiChunkTotalBlocks: Int = 0
    private var currentMultiChunksPlayedInRow: Int = 0

    private var lastStoppedTimeMs: Long = 0L
    private var stoppedWindowIndex: Int = 0
    private var stoppedPositionMs: Long = 0L
    private val durationCache = HashMap<Uri, Long>()

    private var activeMixMarkers: List<Rpf7TrackMarker> = emptyList()
    private var activeMixChunkStartSample: Long = 0L
    private var activeMixSampleRate: Int = 48000
    private var lastDisplayedMixTrackId: Int = -1
    private var mixPollerRunnable: Runnable? = null

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

        val sInfo = Rpf7MetadataRepository.getStationInfo(stationKey)
            ?: Rpf7MetadataRepository.getStationInfo(stationArchive.rpfName)
        isTalkRadio = sInfo?.isTalk ?: (stationKey.contains("talk") || stationArchive.rpfName.contains("talk"))
        isContinuousMixStation = sInfo?.isContinuousMix ?: false

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

        // Build intro song hash lookup table
        val introHashMap = HashMap<Int, String>()
        for (entry in stationArchive.getFileEntries()) {
            val stem = entry.name.removeSuffix(".awc").lowercase(Locale.ROOT)
            if (stem in setOf("time", "to", "intro", "general") || stem.startsWith("id_") || stem.startsWith("mono_solo_")) continue
            for (num in 1..8) {
                val pad = String.format(Locale.ROOT, "%02d", num)
                introHashMap[Rpf7Crypto.jenkHashString("${stem}_$pad") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("intro_${stem}_$pad") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("${stem}_$num") and 0x1FFFFFFF] = stem
                introHashMap[Rpf7Crypto.jenkHashString("intro_${stem}_$num") and 0x1FFFFFFF] = stem
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

        // 3. Classify top-level MultiChannel entries
        for (entry in stationArchive.getFileEntries()) {
            val nameLower = entry.name.lowercase(Locale.ROOT)
            val stem = nameLower.removeSuffix(".awc")
            if (stem in setOf("time", "to", "intro", "general")) continue

            val ref = Rpf7ClipRef(stationArchive, entry.name)
            when {
                stem.startsWith("id_") -> ids.add(ref)
                stem.startsWith("mono_solo_") -> solos.add(ref)
                else -> {
                    val intros = introMap[stem] ?: emptyList()
                    songItems.add(Rpf7SongItem(stationArchive, entry.name, intros))
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
        allSongItems.clear()
        allSongItems.addAll(songItems)
        rebuildTimeOfDayQueue()

        // 4. Index Adverts and News
        val allAdverts = ArrayList<Rpf7ClipRef>()
        advertsArchive?.getFileEntries()?.forEach {
            allAdverts.add(Rpf7ClipRef(advertsArchive, it.name))
        }
        advertQueue.reset(allAdverts)

        val allNews = ArrayList<Rpf7ClipRef>()
        newsArchive?.getFileEntries()?.forEach {
            allNews.add(Rpf7ClipRef(newsArchive, it.name))
        }
        newsQueue.reset(allNews)

        GTRadioLogger.log(
            "Rpf7RadioStation[$stationTitle]: Indexed ${songItems.size} songs/shows, " +
                    "${ids.size} IDs, ${solos.size} solos, ${generals.size} general DJ clips, " +
                    "${morningDjList.size + afternoonDjList.size + eveningDjList.size + nightDjList.size} TOD clips, " +
                    "${allAdverts.size} adverts, ${allNews.size} news"
        )
    }

    private fun getHourBucket(): Int {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 4..11 -> 0
            hour in 12..16 -> 1
            hour in 17..22 -> 2
            else -> 3
        }
    }

    private fun rebuildTimeOfDayQueue() {
        val b = getHourBucket()
        activeHourBucket = b
        val list = when (b) {
            0 -> morningDjList
            1 -> afternoonDjList.ifEmpty { morningDjList }
            2 -> eveningDjList.ifEmpty { afternoonDjList.ifEmpty { morningDjList } }
            else -> nightDjList.ifEmpty { eveningDjList.ifEmpty { morningDjList } }
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
        val isContinuousMix = isContinuousMixStation || meta.isContinuousMix

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
            val mainIdx = currentSegmentUris.size - 1
            if (winIdx == mainIdx) {
                applySongOrShowMetadata(currentSongItem!!)
                if (activeMixMarkers.isNotEmpty()) {
                    startMixPoller(expectedGen)
                }
            } else {
                stopMixPoller()
                setStationOnlyMetadata()
            }
        } else {
            stopMixPoller()
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
        if (!isStationActive) return
        isStationActive = false
        stopMixPoller()
        if (_playing) {
            lastStoppedTimeMs = System.currentTimeMillis()
            stoppedWindowIndex = player.exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
            stoppedPositionMs = player.exoPlayer.currentPosition.coerceAtLeast(0L)
        }
        _playing = false
        prefetchGeneration++ // Cancel any active background prefetches immediately
        if (activeListener != null) {
            player.removeListener(activeListener!!)
            activeListener = null
        }
        player.stopInternal()
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

        decodeExecutor.submit {
            if (!_playing || playGeneration.get() != currentGen) return@submit
            ensureStationIndexed()
            if (lastStoppedTimeMs > 0L && areSegmentFilesValid(currentSegmentUris)) {
                val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
                resumeFromRealTime(elapsedMs, currentGen)
            } else {
                pickNextSegment()
                if (currentSegmentUris.isNotEmpty()) {
                    if (!_playing || playGeneration.get() != currentGen) return@submit
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

    private fun resumeFromRealTime(rawElapsedMs: Long, expectedGen: Int) {
        var remainingMs = rawElapsedMs
        for (u in currentSegmentUris) {
            getFileDurationMs(u)
        }
        var winIdx = stoppedWindowIndex.coerceIn(0, (currentSegmentUris.size - 1).coerceAtLeast(0))
        var posMs = stoppedPositionMs.coerceAtLeast(0L)

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
            val isContinuousMix = isContinuousMixStation || Rpf7MetadataRepository.getTrackMeta(item.trackName).isContinuousMix
            val maxChunksBeforeBreak = if (isContinuousMix) 6 else 100
            val chunkBlocks = item.archive.getChunkBlocksFor(item.trackName)
            if (currentMultiChunksPlayedInRow < maxChunksBeforeBreak) {
                val nextStart = currentMultiChunkNextBlock
                val nextUri = item.archive.materializeBlockChunkUri(
                    item.trackName,
                    nextStart,
                    chunkBlocks
                )
                if (nextUri != null) {
                    if (activeMixMarkers.isNotEmpty()) {
                        activeMixChunkStartSample = item.archive.getChunkStartSample(item.trackName, nextStart)
                    }
                    currentMultiChunkNextBlock += chunkBlocks
                    currentMultiChunksPlayedInRow += 1
                    currentSegmentUris = listOf(nextUri)

                    // Background prefetch subsequent chunk strictly inside decodeExecutor guarded by playGeneration
                    if (currentMultiChunkNextBlock < currentMultiChunkTotalBlocks) {
                        val gen = playGeneration.get()
                        decodeExecutor.submit {
                            if (!_playing || playGeneration.get() != gen) return@submit
                            item.archive.prefetchBlockChunk(
                                item.trackName,
                                currentMultiChunkNextBlock,
                                chunkBlocks
                            ) { !_playing || playGeneration.get() != gen }
                        }
                    }
                    return true
                }
            }
        }
        pickNextSegment()
        return currentSegmentUris.isNotEmpty()
    }

    private fun playNext() {
        val currentGen = playGeneration.get()
        if (!_playing) return
        decodeExecutor.submit {
            if (!_playing || playGeneration.get() != currentGen) return@submit
            if (advanceMultiChunkOrPickNext()) {
                if (!_playing || playGeneration.get() != currentGen) return@submit
                startPlayback(currentSegmentUris, 0, 0L, currentGen)
            }
        }
    }

    private fun hasAnyDjChatter(): Boolean {
        return generalDjQueue.isNotEmpty() || timeOfDayDjQueue.isNotEmpty()
    }

    private fun hasAnyAnnouncerOrSolo(): Boolean {
        return idQueue.isNotEmpty() || soloQueue.isNotEmpty()
    }

    private fun pickNextSegment() {
        currentMultiChunkNextBlock = 0
        currentMultiChunkTotalBlocks = 0
        currentMultiChunksPlayedInRow = 0

        var attempts = 0
        while (attempts < 12) {
            attempts++
            when (lastSegment) {
                RadioSegmentType.None -> {
                    if (prepareSongSegment(requireDjIntro = false)) return
                    lastSegment = RadioSegmentType.Song
                }
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
                    if ((isTalkRadio && songsSinceLastBreak >= 1) || ((adsEnabled || newsReportsEnabled) && songsSinceLastBreak >= adThreshold)) {
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
        stopMixPoller()
        lastSegment = RadioSegmentType.Announcer
        val useId = if (idQueue.isNotEmpty() && soloQueue.isNotEmpty()) {
            isTalkRadio || rng.nextBoolean()
        } else {
            idQueue.isNotEmpty()
        }
        val ref = if (useId && idQueue.isNotEmpty()) idQueue.next()
        else if (soloQueue.isNotEmpty()) soloQueue.next()
        else return false

        val uri = ref.archive.materializeAudioUri(ref.trackKey) ?: return false
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongItem = null
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareDjOrWeatherSegment(): Boolean {
        stopMixPoller()
        lastSegment = RadioSegmentType.DJChatter
        if (getHourBucket() != activeHourBucket) rebuildTimeOfDayQueue()

        val useTod = timeOfDayDjQueue.isNotEmpty() && (generalDjQueue.isEmpty() || rng.nextInt(100) < 35)
        val ref = if (useTod) timeOfDayDjQueue.next()
        else if (generalDjQueue.isNotEmpty()) generalDjQueue.next()
        else return false

        val uri = ref.archive.materializeAudioUri(ref.trackKey) ?: return false
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongItem = null
        currentSegmentUris = listOf(uri)
        return true
    }

    private fun prepareAdvertOrNewsSegment(): Boolean {
        stopMixPoller()
        songsSinceLastBreak = 0
        val canAd = adsEnabled && advertQueue.isNotEmpty()
        val canNews = newsReportsEnabled && newsQueue.isNotEmpty()
        if (!canAd && !canNews) return false

        val playAd = if (canAd && canNews) (rng.nextInt(10) < 7) else canAd
        if (playAd) {
            lastSegment = RadioSegmentType.Commercial
            val adRef = advertQueue.next()
            val adUri = adRef.archive.materializeAudioUri(adRef.trackKey) ?: return false
            val list = ArrayList<Uri>(2)
            if (toAdQueue.isNotEmpty() && rng.nextBoolean()) {
                val toRef = toAdQueue.next()
                toRef.archive.materializeAudioUri(toRef.trackKey)?.let { list.add(it) }
            }
            list.add(adUri)
            consecutiveSongs = 0
            interludeChainCount += 1
            currentSongItem = null
            currentSegmentUris = list
            return true
        } else {
            lastSegment = RadioSegmentType.NewsReport
            val newsRef = newsQueue.next()
            val newsUri = newsRef.archive.materializeAudioUri(newsRef.trackKey) ?: return false
            val list = ArrayList<Uri>(2)
            if (toNewsQueue.isNotEmpty() && rng.nextBoolean()) {
                val toRef = toNewsQueue.next()
                toRef.archive.materializeAudioUri(toRef.trackKey)?.let { list.add(it) }
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
        val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
        val chunkBlocks = item.archive.getChunkBlocksFor(item.trackName)
        val chunkThreshold = item.archive.getChunkThresholdFor(item.trackName)
        GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: Selected track '${item.trackName}' (blocks=$totalBlocks, chunkBlocks=$chunkBlocks, intros=${item.introKeys.size})")

        val mainUri: Uri
        if (totalBlocks > chunkThreshold) {
            val isContinuousMix = isContinuousMixStation || Rpf7MetadataRepository.getTrackMeta(item.trackName).isContinuousMix
            val startBlk = if (isContinuousMix && totalBlocks > chunkBlocks * 2) {
                (rng.nextInt(totalBlocks / chunkBlocks) * chunkBlocks).coerceAtMost(totalBlocks - chunkBlocks)
            } else {
                0
            }
            mainUri = item.archive.materializeBlockChunkUri(item.trackName, startBlk, chunkBlocks) ?: return false
            currentMultiChunkTotalBlocks = totalBlocks
            currentMultiChunkNextBlock = startBlk + chunkBlocks
            currentMultiChunksPlayedInRow = 1

            val markers = item.archive.readTrackMarkers(item.trackName)
            val distinctMusicIds = markers.map { it.trackId }.filter { it > 1 }.distinct()
            val useMixPoller = distinctMusicIds.size >= 2 || isContinuousMixStation || Rpf7MetadataRepository.isContinuousMix(stationArchive.rpfName)
            if (useMixPoller && markers.isNotEmpty()) {
                activeMixMarkers = markers
                activeMixChunkStartSample = item.archive.getChunkStartSample(item.trackName, startBlk)
                activeMixSampleRate = item.archive.getEntrySampleRate(item.trackName)
                lastDisplayedMixTrackId = -1
            } else {
                stopMixPoller()
            }

            // Prefetch next chunk strictly inside decodeExecutor guarded by playGeneration
            val gen = playGeneration.get()
            decodeExecutor.submit {
                if (!_playing || playGeneration.get() != gen) return@submit
                item.archive.prefetchBlockChunk(
                    item.trackName,
                    currentMultiChunkNextBlock,
                    chunkBlocks
                ) { !_playing || playGeneration.get() != gen }
            }
        } else {
            mainUri = item.archive.materializeAudioUri(item.trackName) ?: return false
            currentMultiChunkTotalBlocks = totalBlocks
            currentMultiChunkNextBlock = totalBlocks
            currentMultiChunksPlayedInRow = 1

            val markers = item.archive.readTrackMarkers(item.trackName)
            val distinctMusicIds = markers.map { it.trackId }.filter { it > 1 }.distinct()
            val useMixPoller = distinctMusicIds.size >= 2 || isContinuousMixStation || Rpf7MetadataRepository.isContinuousMix(stationArchive.rpfName)
            if (useMixPoller && markers.isNotEmpty()) {
                activeMixMarkers = markers
                activeMixChunkStartSample = 0L
                activeMixSampleRate = item.archive.getEntrySampleRate(item.trackName)
                lastDisplayedMixTrackId = -1
            } else {
                stopMixPoller()
            }
        }

        val list = ArrayList<Uri>(2)
        if (item.introKeys.isNotEmpty() && (requireDjIntro || rng.nextInt(100) < 60)) {
            val chosenIntro = item.introKeys[rng.nextInt(item.introKeys.size)]
            materializeClip(chosenIntro)?.let { list.add(it) }
        }
        list.add(mainUri)

        currentSongItem = item
        currentSegmentUris = list
        return true
    }

    private fun startPlayback(uris: List<Uri>, startWindowIndex: Int, startPositionMs: Long, expectedGen: Int = playGeneration.get()) {
        mainHandler.post {
            if (!_playing || playGeneration.get() != expectedGen) {
                GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: startPlayback aborted: generation mismatch or not playing (gen=$expectedGen, cur=${playGeneration.get()})")
                return@post
            }

            refreshCurrentSegmentMetadata()

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

            if (!_playing || playGeneration.get() != expectedGen) return@post

            player.setMediaSource(concatSource)
            if (startWindowIndex > 0 || startPositionMs > 0L) {
                player.exoPlayer.seekTo(startWindowIndex, startPositionMs)
            }
            updateMetadataForWindowIndex(startWindowIndex, expectedGen)

            activeListener = object : Player.Listener {
                private var alreadyHandled = false

                override fun onPlaybackStateChanged(state: Int) {
                    if (!alreadyHandled && state == Player.STATE_ENDED) {
                        alreadyHandled = true
                        player.removeListener(this)
                        if (_playing && playGeneration.get() == expectedGen) {
                            playNext()
                        }
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val curIdx = player.exoPlayer.currentMediaItemIndex
                    updateMetadataForWindowIndex(curIdx, expectedGen)
                }

                override fun onPlayerError(error: PlaybackException) {
                    GTRadioLogger.log("Rpf7RadioStation[$stationTitle]: ExoPlayer error on track: ${error.message}", error)
                    if (!alreadyHandled && _playing && playGeneration.get() == expectedGen) {
                        alreadyHandled = true
                        player.removeListener(this)
                        mainHandler.post {
                            if (_playing && playGeneration.get() == expectedGen) {
                                playNext()
                            }
                        }
                    }
                }
            }
            player.addListener(activeListener!!)
            if (!_playing || playGeneration.get() != expectedGen) return@post
            player.prepare()
            player.playInternal()

            if (activeMixMarkers.isNotEmpty()) {
                startMixPoller(expectedGen)
            } else {
                stopMixPoller()
            }
        }
    }

    private fun startMixPoller(expectedGen: Int = playGeneration.get()) {
        mixPollerRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (!_playing || playGeneration.get() != expectedGen || activeMixMarkers.isEmpty()) {
                    return
                }
                updateMixTrackMetadata()
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mixPollerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopMixPoller() {
        mixPollerRunnable?.let {
            mainHandler.removeCallbacks(it)
            mixPollerRunnable = null
        }
        activeMixMarkers = emptyList()
        activeMixChunkStartSample = 0L
        lastDisplayedMixTrackId = -1
    }

    private fun updateMixTrackMetadata() {
        if (!_playing || activeMixMarkers.isEmpty()) return
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }
        val currentTrackPosMs = player.getCurrentTrackPosition().coerceAtLeast(0L)
        val currentSample = activeMixChunkStartSample + (currentTrackPosMs * activeMixSampleRate / 1000L)

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
        } else {
            setStationOnlyMetadata()
        }
    }

    private fun isMixStation(): Boolean {
        val rpf = stationArchive.rpfName.lowercase(Locale.ROOT)
        val key = stationKey.lowercase(Locale.ROOT)
        return isContinuousMixStation ||
            rpf.contains("radio_07") || rpf.contains("radio_08") || rpf.contains("radio_13") || rpf.contains("radio_14") ||
            key.contains("radio_07") || key.contains("radio_08") || key.contains("radio_13") || key.contains("radio_14")
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

        cleanPlayExecutor.submit {
            try {
                val album = Rpf7MetadataRepository.getCachedAlbumName(context, artist, title) ?: stationTitle
                mainHandler.post {
                    if (token == cleanPlayGeneration.get()) {
                        onMetadataReady(title, artist, album, 2021)
                    }
                }
                val uri = targetItem.archive.materializeFullAudioUri(targetItem.trackName)
                if (token != cleanPlayGeneration.get()) return@submit
                if (uri == null) {
                    GTRadioLogger.log("Rpf7RadioStation: Failed to materialize clean track audio for '${targetItem.trackName}'")
                    mainHandler.post { onTrackFinished() }
                    return@submit
                }
                val dur = readAudioDurationMs(context, uri)
                mainHandler.post {
                    if (token != cleanPlayGeneration.get()) return@post
                    player.playCleanQueue(listOf(uri), listOf(dur)) {
                        if (token == cleanPlayGeneration.get()) {
                            onTrackFinished()
                        }
                    }
                }
            } catch (e: Exception) {
                GTRadioLogger.log("Rpf7RadioStation: Error materializing clean track", e)
                mainHandler.post { onTrackFinished() }
            }
        }
    }

    override fun stopCleanTrackPlayback() {
        cleanPlayGeneration.incrementAndGet()
        player.stopCleanQueue()
    }
}
