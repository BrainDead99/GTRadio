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
import java.util.*

data class Rpf3SongItem(
    val archive: Rpf3Archive,
    val trackName: String,
    val introNames: List<String> = emptyList()
)

data class Rpf3ClipRef(
    val archive: Rpf3Archive,
    val trackKey: String
)

@UnstableApi class Rpf3RadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaItem,
    private val stationKey: String,
    private val stationArchive: Rpf3Archive,
    private val extraTracksArchive: Rpf3Archive?,
    private val ep1SfxArchive: Rpf3Archive?,
    private val ep2SfxArchive: Rpf3Archive?,
    private val advertsArchive: Rpf3Archive?,
    private val newsArchive: Rpf3Archive?,
    private val weatherArchive: Rpf3Archive?,
    private val player: GTRadioPlayer,
    private val context: Context,
    override var adsEnabled: Boolean,
    override var weatherChatterEnabled: Boolean,
    override var newsReportsEnabled: Boolean
) : RadioStation, TracklistCapableStation {

    override var onMetadataChanged: ((MediaMetadata) -> Unit)? = null
    override var stationName: String = (mediaItem.mediaMetadata.title ?: stationArchive.rpfName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.mediaMetadata.subtitle ?: "GTA IV").toString()
    private val stationLogo: Bitmap? = mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.loadBitmap(context, it, 512) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rng = Random()

    private var _currentMetadata: MediaMetadata = buildStationMetadata(stationTitle, "", stationLogo)
    override val metadata: MediaMetadata
        get() = _currentMetadata

    private var initialized = false
    private var isTalkRadio = false

    private val idQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)
    private val soloQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)
    private val generalDjQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)
    private val morningDjList = ArrayList<Rpf3ClipRef>()
    private val afternoonDjList = ArrayList<Rpf3ClipRef>()
    private val eveningDjList = ArrayList<Rpf3ClipRef>()
    private val nightDjList = ArrayList<Rpf3ClipRef>()
    private val timeOfDayDjQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)

    private val toAdQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)
    private val toNewsQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)
    private val toWeatherQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)

    private val advertQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)
    private val newsQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)
    private val weatherQueue = RandomizedDualHeadQueue<Rpf3ClipRef>(emptyList(), rng)
    private val songQueue = RandomizedDualHeadQueue<Rpf3SongItem>(emptyList(), rng)
    private val allSongItems = ArrayList<Rpf3SongItem>()
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val cleanPlayExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val playGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var songsSinceLastBreak = 0
    private var consecutiveSongs = 0
    private var interludeChainCount = 0
    private var activeHourBucket = -1
    private var activeListener: Player.Listener? = null

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: Rpf3SongItem? = null
    private var currentMultiChunkNextBlock: Int = 0
    private var currentMultiChunkTotalBlocks: Int = 0
    private var currentMultiChunksPlayedInRow: Int = 0

    private var lastStoppedTimeMs: Long = 0L
    private var stoppedWindowIndex: Int = 0
    private var stoppedPositionMs: Long = 0L
    private val durationCache = HashMap<Uri, Long>()

    private var activeMixMarkers: List<Rpf3TrackMarker> = emptyList()
    private var activeMixChunkStartMs: Long = 0L
    private var lastDisplayedMixTrackId: Int = -1
    private var mixPollerRunnable: Runnable? = null

    private fun materializeClip(keyOrScoped: String): Uri? {
        stationArchive.materializeAudioUri(keyOrScoped)?.let { return it }
        ep1SfxArchive?.materializeAudioUri(keyOrScoped)?.let { return it }
        ep2SfxArchive?.materializeAudioUri(keyOrScoped)?.let { return it }
        extraTracksArchive?.materializeAudioUri(keyOrScoped)?.let { return it }
        return null
    }

    @Synchronized
    private fun ensureStationIndexed() {
        if (initialized) return
        initialized = true

        val keyLower = stationKey.lowercase()
        isTalkRadio = keyLower.contains("wktt") || keyLower.contains("plr") || keyLower.contains("lazlow")

        val ids = ArrayList<Rpf3ClipRef>()
        val solos = ArrayList<Rpf3ClipRef>()
        val generals = ArrayList<Rpf3ClipRef>()
        val toAds = ArrayList<Rpf3ClipRef>()
        val toNews = ArrayList<Rpf3ClipRef>()
        val toWeather = ArrayList<Rpf3ClipRef>()
        val introMap = HashMap<String, MutableList<String>>()
        val songItems = ArrayList<Rpf3SongItem>()

        val isDedicatedStationRpf = !stationArchive.rpfName.uppercase().startsWith("EP")
        if (isDedicatedStationRpf) {
            for (sub in stationArchive.getBankSubTracks()) {
                val pName = sub.parentEntryName.uppercase()
                val sName = sub.subName.uppercase()
                val ref = Rpf3ClipRef(stationArchive, "$pName#$sName")
                when {
                    sName.startsWith("MORNING_") -> morningDjList.add(ref)
                    sName.startsWith("AFTERNOON_") -> afternoonDjList.add(ref)
                    sName.startsWith("EVENING_") -> eveningDjList.add(ref)
                    sName.startsWith("NIGHT_") -> nightDjList.add(ref)
                    sName.startsWith("TO_AD") -> toAds.add(ref)
                    sName.startsWith("TO_NEWS") -> toNews.add(ref)
                    sName.startsWith("TO_WEATHER") -> toWeather.add(ref)
                    pName == "GENERAL" || sName.startsWith("GENERAL") -> generals.add(ref)
                    pName == "INTRO" -> {
                        val baseSong = sName.replace(Regex("_\\d+$"), "")
                        introMap.getOrPut(baseSong) { ArrayList() }.add("$pName#$sName")
                    }
                }
            }

            for (entry in stationArchive.getFileEntries()) {
                if (entry.isWaveBank) continue
                val eName = entry.name.uppercase()
                if (eName in setOf("TIME", "GENERAL", "INTRO", "TO", "NULLTRACK")) continue
                val ref = Rpf3ClipRef(stationArchive, eName)
                when {
                    eName.startsWith("ID_") -> ids.add(ref)
                    eName.startsWith("SOLO_") -> solos.add(ref)
                    eName.startsWith("MORNING_") -> morningDjList.add(ref)
                    eName.startsWith("AFTERNOON_") -> afternoonDjList.add(ref)
                    eName.startsWith("EVENING_") -> eveningDjList.add(ref)
                    eName.startsWith("NIGHT_") -> nightDjList.add(ref)
                    eName.startsWith("TO_AD") -> toAds.add(ref)
                    eName.startsWith("TO_NEWS") -> toNews.add(ref)
                    eName.startsWith("TO_WEATHER") -> toWeather.add(ref)
                    else -> {
                        val intros = introMap[eName] ?: emptyList()
                        songItems.add(Rpf3SongItem(stationArchive, eName, intros))
                    }
                }
            }
        }

        // Helper to index DLC DJ banks and Solo clips from EP1_SFX.rpf or EP2_SFX.rpf
        fun indexDlcDjAssets(dlcArch: Rpf3Archive?, djBankPrefix: String, soloPrefix: String) {
            if (dlcArch == null) return
            for (sub in dlcArch.getBankSubTracks()) {
                val pName = sub.parentEntryName.uppercase()
                if (!pName.startsWith(djBankPrefix)) continue
                val sName = sub.subName.uppercase()
                val scopedKey = "$pName#$sName"
                val ref = Rpf3ClipRef(dlcArch, scopedKey)
                when {
                    pName.endsWith("_TIME") -> {
                        when {
                            sName.startsWith("MORNING_") -> morningDjList.add(ref)
                            sName.startsWith("AFTERNOON_") -> afternoonDjList.add(ref)
                            sName.startsWith("EVENING_") -> eveningDjList.add(ref)
                            sName.startsWith("NIGHT_") -> nightDjList.add(ref)
                        }
                    }
                    pName.endsWith("_TO") -> {
                        when {
                            sName.startsWith("TO_AD") -> toAds.add(ref)
                            sName.startsWith("TO_NEWS") -> toNews.add(ref)
                            sName.startsWith("TO_WEATHER") -> toWeather.add(ref)
                        }
                    }
                    pName.endsWith("_GENERAL") -> generals.add(ref)
                    pName.endsWith("_INTRO") -> {
                        val baseSong = sName.replace(Regex("_\\d+$"), "")
                        introMap.getOrPut(baseSong) { ArrayList() }.add(scopedKey)
                    }
                }
            }
            for (entry in dlcArch.getFileEntries()) {
                if (!entry.isWaveBank && entry.name.uppercase().startsWith(soloPrefix)) {
                    solos.add(Rpf3ClipRef(dlcArch, entry.name.uppercase()))
                }
            }
        }

        when {
            keyLower.contains("liberty_rock") -> indexDlcDjAssets(ep1SfxArchive, "DJ_LIBERTY_ROCK_", "LIBERTY_ROCK_SOLO_")
            keyLower.contains("dance_rock") -> indexDlcDjAssets(ep1SfxArchive, "DJ_DANCE_ROCK_", "DANCE_ROCK_SOLO_")
            keyLower.contains("k109") -> indexDlcDjAssets(ep2SfxArchive, "DJ_K109_THE_STUDIO_", "K109_THE_STUDIO_SOLO_")
        }

        if (extraTracksArchive != null) {
            val extraSet = when {
                keyLower.contains("beat_95") -> Rpf3MetadataRepository.beatExtraTracks
                keyLower.contains("bobby_konders") -> Rpf3MetadataRepository.massiveBExtraTracks
                keyLower.contains("dance_mix") -> Rpf3MetadataRepository.electroChocExtraTracks
                keyLower.contains("ramjam") -> Rpf3MetadataRepository.ramJamExtraTracks
                else -> emptySet()
            }
            for (extraName in extraSet) {
                if (extraTracksArchive.hasEntry(extraName)) {
                    songItems.add(Rpf3SongItem(extraTracksArchive, extraName, introMap[extraName] ?: emptyList()))
                }
            }
        }

        if (ep1SfxArchive != null) {
            val ep1Set = when {
                keyLower.contains("liberty_rock") -> Rpf3MetadataRepository.eflcLibertyRockEp1Tracks
                keyLower.contains("dance_rock") -> Rpf3MetadataRepository.eflcRadioBrokerEp1Tracks
                keyLower.contains("hardcore") || keyLower == "eflc_lchc" -> Rpf3MetadataRepository.eflcLchcEp1Tracks
                keyLower.contains("beat_95") || keyLower == "eflc_beat" -> Rpf3MetadataRepository.eflcBeatEp1Tracks
                keyLower.contains("wktt") -> Rpf3MetadataRepository.eflcWkttEp1Tracks
                else -> emptySet()
            }
            for (tName in ep1Set) {
                if (ep1SfxArchive.hasEntry(tName)) {
                    songItems.add(Rpf3SongItem(ep1SfxArchive, tName, introMap[tName] ?: emptyList()))
                }
            }
        }

        if (ep2SfxArchive != null) {
            val ep2Set = when {
                keyLower.contains("k109") -> Rpf3MetadataRepository.eflcK109Ep2Tracks
                keyLower.contains("dance_mix") || keyLower == "eflc_electro_choc" -> Rpf3MetadataRepository.eflcElectroChocEp2Tracks
                keyLower.contains("vladivostok") || keyLower == "eflc_vladivostok" -> Rpf3MetadataRepository.eflcVladivostokEp2Tracks
                keyLower.contains("san_juan") || keyLower == "eflc_san_juan" -> Rpf3MetadataRepository.eflcSanJuanEp2Tracks
                keyLower.contains("wktt") -> Rpf3MetadataRepository.eflcWkttEp2Tracks
                keyLower.contains("lazlow") -> Rpf3MetadataRepository.eflcIntegrityEp2Tracks
                else -> emptySet()
            }
            for (tName in ep2Set) {
                if (ep2SfxArchive.hasEntry(tName)) {
                    songItems.add(Rpf3SongItem(ep2SfxArchive, tName, introMap[tName] ?: emptyList()))
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

        val allAdverts = ArrayList<Rpf3ClipRef>()
        advertsArchive?.getFileEntries()?.filter { !it.isWaveBank }?.forEach {
            allAdverts.add(Rpf3ClipRef(advertsArchive, it.name))
        }
        ep1SfxArchive?.getFileEntries()?.filter { !it.isWaveBank && it.name.uppercase().startsWith("AD0") }?.forEach {
            allAdverts.add(Rpf3ClipRef(ep1SfxArchive, it.name))
        }
        ep2SfxArchive?.getFileEntries()?.filter { !it.isWaveBank && it.name.uppercase().startsWith("AD1") }?.forEach {
            allAdverts.add(Rpf3ClipRef(ep2SfxArchive, it.name))
        }
        advertQueue.reset(allAdverts)

        val allNews = ArrayList<Rpf3ClipRef>()
        newsArchive?.getFileEntries()?.filter { !it.isWaveBank }?.forEach {
            allNews.add(Rpf3ClipRef(newsArchive, it.name))
        }
        ep1SfxArchive?.getFileEntries()?.filter { !it.isWaveBank && it.name.uppercase().startsWith("NEWS_") }?.forEach {
            allNews.add(Rpf3ClipRef(ep1SfxArchive, it.name))
        }
        ep2SfxArchive?.getFileEntries()?.filter { !it.isWaveBank && it.name.uppercase().startsWith("NEWS_MONO_") }?.forEach {
            allNews.add(Rpf3ClipRef(ep2SfxArchive, it.name))
        }
        newsQueue.reset(allNews)

        weatherArchive?.let { arch ->
            weatherQueue.reset(arch.getFileEntries().filter { !it.isWaveBank }.map { Rpf3ClipRef(arch, it.name) })
        }
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

    private fun applySongOrShowMetadata(item: Rpf3SongItem) {
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }

        if (activeMixMarkers.isNotEmpty()) {
            updateMixTrackMetadata()
            return
        }

        val meta = Rpf3MetadataRepository.getTrackMeta(context, item.trackName, stationTitle)
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

        val maxYear = 2009
        var art: Bitmap? = stationLogo
        if (isAlbumArtEnabled()) {
            val cached = Gen2MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle)
            if (cached != null) {
                art = cached
            } else {
                Gen2MetadataRepository.fetchEraAccurateArtAsync(context, displayArtist, displayTitle, maxYear) {
                    mainHandler.post {
                        if (_playing && lastSegment == RadioSegmentType.Song && currentSongItem == item) {
                            val newArt = Gen2MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle) ?: stationLogo
                            val alb = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
                            val sub = if (alb.isNotBlank()) "$displayArtist • $alb" else "$displayArtist • $stationTitle"
                            _currentMetadata = buildStationMetadata(displayTitle, sub, newArt, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = alb)
                            onMetadataChanged?.invoke(_currentMetadata)
                        }
                    }
                }
            }
        }

        val eraAlbum = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
        val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
        _currentMetadata = buildStationMetadata(displayTitle, subtitle, art, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = eraAlbum)
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
                if (f.exists() && f.length() > 44L) {
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
        } catch (_: Exception) {}
        durationCache[uri] = dur
        return dur
    }

    override fun stop() {
        val gen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: stop() called (gen=$gen)")
        if (!isStationActive) return
        isStationActive = false
        stopMixPoller()
        if (_playing) {
            lastStoppedTimeMs = System.currentTimeMillis()
            stoppedWindowIndex = player.exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
            stoppedPositionMs = player.exoPlayer.currentPosition.coerceAtLeast(0L)
        }
        _playing = false
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
            if (!f.exists() || f.length() <= 44L) return false
        }
        return true
    }

    override fun play() {
        val currentGen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: play() called (gen=$currentGen)")
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
            val isContinuousMix = Rpf3MetadataRepository.getTrackMeta(context, item.trackName, stationTitle)?.isTalkShow == true && !isTalkRadio
            val maxChunksBeforeBreak = if (isContinuousMix) 3 else 100
            if (currentMultiChunksPlayedInRow < maxChunksBeforeBreak) {
                val nextStart = currentMultiChunkNextBlock
                val nextUri = item.archive.materializeAudioChunkUri(
                    item.trackName,
                    nextStart,
                    Rpf3Archive.CHUNK_BLOCKS
                )
                if (nextUri != null) {
                    if (activeMixMarkers.isNotEmpty()) {
                        activeMixChunkStartMs = item.archive.getChunkStartMs(item.trackName, nextStart)
                    }
                    currentMultiChunkNextBlock += Rpf3Archive.CHUNK_BLOCKS
                    currentMultiChunksPlayedInRow += 1
                    currentSegmentUris = listOf(nextUri)
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
        if (weatherChatterEnabled && weatherArchive != null && weatherQueue.isNotEmpty() && rng.nextInt(100) < 22) {
            return prepareWeatherSegment()
        }
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

    private fun prepareWeatherSegment(): Boolean {
        stopMixPoller()
        lastSegment = RadioSegmentType.WeatherReport
        if (!weatherChatterEnabled || weatherQueue.isEmpty()) return false
        val wRef = weatherQueue.next()
        val wUri = wRef.archive.materializeAudioUri(wRef.trackKey) ?: return false

        val list = ArrayList<Uri>(2)
        if (toWeatherQueue.isNotEmpty() && rng.nextBoolean()) {
            val toRef = toWeatherQueue.next()
            toRef.archive.materializeAudioUri(toRef.trackKey)?.let { list.add(it) }
        }
        list.add(wUri)
        consecutiveSongs = 0
        interludeChainCount += 1
        currentSongItem = null
        currentSegmentUris = list
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
        GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: Selected track '${item.trackName}' (blocks=$totalBlocks, intros=${item.introNames.size})")

        val mainUri: Uri
        if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) {
            val isContinuousMix = Rpf3MetadataRepository.getTrackMeta(context, item.trackName, stationTitle)?.isTalkShow == true && !isTalkRadio
            val startBlk = if (isContinuousMix && totalBlocks > Rpf3Archive.CHUNK_BLOCKS * 2) {
                (rng.nextInt(totalBlocks / Rpf3Archive.CHUNK_BLOCKS) * Rpf3Archive.CHUNK_BLOCKS).coerceAtMost(totalBlocks - Rpf3Archive.CHUNK_BLOCKS)
            } else {
                0
            }
            mainUri = item.archive.materializeAudioChunkUri(item.trackName, startBlk, Rpf3Archive.CHUNK_BLOCKS) ?: return false
            currentMultiChunkTotalBlocks = totalBlocks
            currentMultiChunkNextBlock = startBlk + Rpf3Archive.CHUNK_BLOCKS
            currentMultiChunksPlayedInRow = 1

            val markers = item.archive.readTrackMarkers(item.trackName)
            val distinctMusicIds = markers.map { it.trackId }.filter { it > 1 && it != 153 }.distinct()
            val useMixPoller = distinctMusicIds.size >= 2 || isContinuousMixStation()
            if (useMixPoller && markers.isNotEmpty()) {
                activeMixMarkers = markers
                activeMixChunkStartMs = item.archive.getChunkStartMs(item.trackName, startBlk)
                lastDisplayedMixTrackId = -1
            } else {
                stopMixPoller()
            }
        } else {
            mainUri = item.archive.materializeAudioUri(item.trackName) ?: return false
            currentMultiChunkTotalBlocks = totalBlocks
            currentMultiChunkNextBlock = totalBlocks
            currentMultiChunksPlayedInRow = 1

            val markers = item.archive.readTrackMarkers(item.trackName)
            val distinctMusicIds = markers.map { it.trackId }.filter { it > 1 && it != 153 }.distinct()
            val useMixPoller = distinctMusicIds.size >= 2 || isContinuousMixStation()
            if (useMixPoller && markers.isNotEmpty()) {
                activeMixMarkers = markers
                activeMixChunkStartMs = 0L
                lastDisplayedMixTrackId = -1
            } else {
                stopMixPoller()
            }
        }

        val list = ArrayList<Uri>(2)
        if (item.introNames.isNotEmpty() && (requireDjIntro || rng.nextInt(100) < 60)) {
            val chosenIntro = item.introNames[rng.nextInt(item.introNames.size)]
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
                GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: startPlayback aborted: generation mismatch or not playing (gen=$expectedGen, cur=${playGeneration.get()})")
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
                    GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: ExoPlayer error on track: ${error.message}", error)
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
        activeMixChunkStartMs = 0L
        lastDisplayedMixTrackId = -1
    }

    private fun updateMixTrackMetadata() {
        if (!_playing || activeMixMarkers.isEmpty()) return
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }
        val currentTrackPosMs = player.getCurrentTrackPosition().coerceAtLeast(0L)
        val currentMs = activeMixChunkStartMs + currentTrackPosMs

        var resolvedTrackId = -1
        for (m in activeMixMarkers) {
            if (m.timeOffsetMs <= currentMs) {
                resolvedTrackId = m.trackId
            } else {
                break
            }
        }

        if (resolvedTrackId == lastDisplayedMixTrackId) return
        lastDisplayedMixTrackId = resolvedTrackId

        GTRadioLogger.logVerbose("TRACKID") {
            "Rpf3RadioStation[$stationTitle]: TRACKID transition -> $resolvedTrackId at ${currentMs}ms"
        }

        val meta = if (resolvedTrackId > 0) Rpf3MetadataRepository.getTrackByTrackId(resolvedTrackId) else null
        if (meta != null) {
            val displayTitle = meta.title
            val displayArtist = meta.artist
            val maxYear = 2009
            var art: Bitmap? = stationLogo
            if (isAlbumArtEnabled()) {
                val cached = Gen2MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle)
                if (cached != null) {
                    art = cached
                } else {
                    val currentTid = resolvedTrackId
                    Gen2MetadataRepository.fetchEraAccurateArtAsync(context, displayArtist, displayTitle, maxYear) {
                        mainHandler.post {
                            if (_playing && lastDisplayedMixTrackId == currentTid) {
                                val newArt = Gen2MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle) ?: stationLogo
                                val alb = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
                                val sub = if (alb.isNotBlank()) "$displayArtist • $alb" else "$displayArtist • $stationTitle"
                                _currentMetadata = buildStationMetadata(displayTitle, sub, newArt, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = alb)
                                onMetadataChanged?.invoke(_currentMetadata)
                            }
                        }
                    }
                }
            }
            val eraAlbum = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
            val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
            _currentMetadata = buildStationMetadata(displayTitle, subtitle, art, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = eraAlbum)
            onMetadataChanged?.invoke(_currentMetadata)
        } else {
            setStationOnlyMetadata()
        }
    }

    private fun isContinuousMixStation(): Boolean {
        val archName = stationArchive.rpfName.lowercase(Locale.ROOT)
        val key = stationKey.lowercase(Locale.ROOT)
        if (archName.contains("radio_ny_classics") || archName.contains("ny_classics") ||
            archName.contains("radio_dance_mix") || archName.contains("dance_mix") ||
            archName.contains("radio_bobby_konders") || archName.contains("bobby_konders") ||
            archName.contains("radio_beat_95") || archName.contains("beat_95") ||
            archName.contains("radio_ramjamfm") || archName.contains("ramjam") ||
            key.contains("radio_ny_classics") || key.contains("ny_classics") ||
            key.contains("radio_dance_mix") || key.contains("dance_mix") ||
            key.contains("radio_bobby_konders") || key.contains("bobby_konders") ||
            key.contains("radio_beat_95") || key.contains("beat_95") ||
            key.contains("radio_ramjamfm") || key.contains("ramjam")
        ) {
            return true
        }
        for (entry in stationArchive.getFileEntries()) {
            val upper = entry.name.uppercase(Locale.ROOT)
            if (upper.contains("MIX") ||
                upper.contains("LCHC_32K_PH") ||
                upper.contains("EUROBEAT_MIX") ||
                upper.contains("CROOKERS_MIX")
            ) {
                return true
            }
        }
        return false
    }

    private fun getDiscreteSongItems(): List<Rpf3SongItem> {
        ensureStationIndexed()
        if (isTalkRadio || isContinuousMixStation()) return emptyList()
        return allSongItems.filter { item ->
            val upper = item.trackName.uppercase(Locale.ROOT)
            !upper.contains("MIX") &&
                !upper.contains("LCHC_32K_PH") &&
                !upper.contains("EUROBEAT_MIX") &&
                !upper.contains("CROOKERS_MIX") &&
                Rpf3MetadataRepository.getTrackMeta(context, item.trackName, stationTitle)?.isTalkShow != true
        }
    }

    override fun supportsTracklist(): Boolean {
        ensureStationIndexed()
        if (isTalkRadio || isContinuousMixStation()) return false
        return getDiscreteSongItems().isNotEmpty()
    }

    override fun getCleanTracks(): List<TracklistTrack> {
        val items = getDiscreteSongItems()
        return items.map { item ->
            val meta = Rpf3MetadataRepository.getTrackMeta(context, item.trackName, stationTitle)
            val title = meta?.title ?: item.trackName
            val artist = meta?.artist ?: stationTitle
            TracklistTrack(
                id = item.trackName,
                title = title,
                artist = artist,
                stationName = stationTitle,
                maxYear = 2009
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
        val meta = Rpf3MetadataRepository.getTrackMeta(context, targetItem.trackName, stationTitle)
        val title = meta?.title ?: targetItem.trackName
        val artist = meta?.artist ?: stationTitle

        cleanPlayExecutor.submit {
            try {
                val album = Gen2MetadataRepository.getCachedAlbumName(context, artist, title) ?: stationTitle
                mainHandler.post {
                    if (token == cleanPlayGeneration.get()) {
                        onMetadataReady(title, artist, album, 2009)
                    }
                }
                val uri = targetItem.archive.materializeFullAudioUri(targetItem.trackName)
                if (token != cleanPlayGeneration.get()) return@submit
                if (uri == null) {
                    GTRadioLogger.log("Rpf3RadioStation: Failed to materialize clean track audio for '${targetItem.trackName}'")
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
                GTRadioLogger.log("Rpf3RadioStation: Error materializing clean track", e)
                mainHandler.post { onTrackFinished() }
            }
        }
    }

    override fun stopCleanTrackPlayback() {
        cleanPlayGeneration.incrementAndGet()
        player.stopCleanQueue()
    }
}
