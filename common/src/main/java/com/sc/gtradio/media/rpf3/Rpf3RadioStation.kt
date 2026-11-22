package com.sc.gtradio.media.rpf3
import com.sc.gtradio.media.core.*
import com.sc.gtradio.media.gen2.Gen2MetadataRepository


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
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import kotlinx.coroutines.*
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
    adsEnabled: Boolean,
    weatherChatterEnabled: Boolean,
    newsReportsEnabled: Boolean
) : RadioStation, TracklistCapableStation {

    override var onMetadataChanged: ((MediaMetadata) -> Unit)? = null
    override var stationName: String = (mediaItem.mediaMetadata.title ?: stationArchive.rpfName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.mediaMetadata.subtitle ?: "GTA IV").toString()
    private val stationLogo: Bitmap? by lazy { mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.getCachedBitmap(it, 512) ?: DynamicThemeManager.loadBitmap(context, it, 512) } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rng = Random()

    private var _currentMetadata: MediaMetadata = buildStationMetadata(stationTitle, "", stationLogo)
    override val metadata: MediaMetadata
        get() = _currentMetadata

    private var initialized = false
    private var isTalkRadio = false

    private val director by lazy {
        Gen3RadioDirector<Rpf3SongItem, Rpf3ClipRef>(
            context = context,
            era = SeriesEra.GEN3_RPF3,
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
            director.adsEnabled = value
        }
    override var weatherChatterEnabled: Boolean = weatherChatterEnabled
        set(value) {
            field = value
            director.weatherChatterEnabled = value
        }
    override var newsReportsEnabled: Boolean = newsReportsEnabled
        set(value) {
            field = value
            director.newsReportsEnabled = value
        }

    private val morningDjList = ArrayList<Rpf3ClipRef>()
    private val afternoonDjList = ArrayList<Rpf3ClipRef>()
    private val eveningDjList = ArrayList<Rpf3ClipRef>()
    private val nightDjList = ArrayList<Rpf3ClipRef>()

    private val allSongItems = ArrayList<Rpf3SongItem>()
    private val stationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeDecodeJob: Job? = null
    private var activeCleanJob: Job? = null
    private var activePollerJob: Job? = null
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val playGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var activeListener: Player.Listener? = null

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: Rpf3SongItem? = null
    private var currentMultiChunkNextBlock: Int = 0
    private var currentMultiChunkTotalBlocks: Int = 0
    private var currentMultiChunksPlayedInRow: Int = 0

    private data class PrefetchedBroadcastItem(
        val uris: List<Uri>,
        val pinnedFiles: List<String>,
        val event: NextBroadcastEvent<Rpf3SongItem, Rpf3ClipRef>,
        val songItem: Rpf3SongItem?,
        val segmentType: RadioSegmentType,
        val pendingBreakClips: List<DirectorBreakClip<Rpf3ClipRef>>? = null
    )

    private var prefetchedItem: PrefetchedBroadcastItem? = null
    private var lookAheadJob: Job? = null
    private var isPrefetching = false
    private val activePinnedUris = java.util.Collections.synchronizedSet(HashSet<String>())
    private val pendingBreakClips = ArrayList<DirectorBreakClip<Rpf3ClipRef>>()

    private fun pinAudioUri(uri: Uri) {
        val uriStr = uri.toString()
        activePinnedUris.add(uriStr)
        Rpf3Archive.pinFile(uriStr)
    }

    private fun unpinAllActive() {
        for (u in activePinnedUris) {
            Rpf3Archive.unpinFile(u)
        }
        activePinnedUris.clear()
        Rpf3Archive.clearPinnedFiles()
    }

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

        director.idQueue.reset(ids)
        director.soloQueue.reset(solos)
        director.generalDjQueue.reset(generals)
        director.toAdQueue.reset(toAds)
        director.toNewsQueue.reset(toNews)
        director.toWeatherQueue.reset(toWeather)
        director.songQueue.reset(songItems)
        director.morningDjList = morningDjList
        director.afternoonDjList = afternoonDjList
        director.eveningDjList = eveningDjList
        director.nightDjList = nightDjList
        allSongItems.clear()
        allSongItems.addAll(songItems)
        director.rebuildTimeOfDayQueueIfNeeded()

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
        director.advertQueue.reset(allAdverts)

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
        director.newsQueue.reset(allNews)

        weatherArchive?.let { arch ->
            val allWeather = arch.getFileEntries().filter { !it.isWaveBank }.map { Rpf3ClipRef(arch, it.name) }
            director.weatherQueue.reset(allWeather)
            val suns = ArrayList<Rpf3ClipRef>()
            val clouds = ArrayList<Rpf3ClipRef>()
            val fogs = ArrayList<Rpf3ClipRef>()
            val rains = ArrayList<Rpf3ClipRef>()
            val winds = ArrayList<Rpf3ClipRef>()
            for (w in allWeather) {
                val u = w.trackKey.uppercase()
                when {
                    u.startsWith("SUN_") -> suns.add(w)
                    u.startsWith("CLOUD_") -> clouds.add(w)
                    u.startsWith("FOG_") -> fogs.add(w)
                    u.startsWith("RAIN_") -> rains.add(w)
                    u.startsWith("WIND_") -> winds.add(w)
                }
            }
            director.weatherSunQueue.reset(suns)
            director.weatherCloudQueue.reset(clouds)
            director.weatherFogQueue.reset(fogs)
            director.weatherRainQueue.reset(rains)
            director.weatherWindQueue.reset(winds)
        }
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
            if (winIdx in currentSegmentUris.indices) {
                applySongOrShowMetadata(currentSongItem!!)
                if (winIdx == mainIdx && activeMixMarkers.isNotEmpty()) {
                    startMixPoller(expectedGen)
                } else {
                    stopMixPoller()
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
        activeDecodeJob?.cancel()
        activeDecodeJob = null
        activeCleanJob?.cancel()
        activeCleanJob = null
        stopMixPoller()
        lookAheadJob?.cancel()
        lookAheadJob = null
        isPrefetching = false
        prefetchedItem?.pinnedFiles?.forEach { Rpf3Archive.unpinFile(it) }
        prefetchedItem = null
        unpinAllActive()
        pendingBreakClips.clear()
        if (!isStationActive) return
        isStationActive = false
        if (_playing) {
            val isReady = player.exoPlayer.playbackState == Player.STATE_READY
            if (isReady || lastStoppedTimeMs == 0L) {
                lastStoppedTimeMs = System.currentTimeMillis()
                stoppedWindowIndex = player.exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
                stoppedPositionMs = player.exoPlayer.currentPosition.coerceAtLeast(0L)
            }
        }
        _playing = false
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

        activeDecodeJob?.cancel()
        activeDecodeJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            if (!_playing || playGeneration.get() != currentGen) return@launch
            ensureStationIndexed()
            if (lastStoppedTimeMs > 0L && areSegmentFilesValid(currentSegmentUris)) {
                val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
                resumeFromRealTime(elapsedMs, currentGen)
            } else {
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

    private fun resumeFromRealTime(rawElapsedMs: Long, expectedGen: Int) {
        var remainingMs = rawElapsedMs
        for (u in currentSegmentUris) {
            getFileDurationMs(u)
        }
        var winIdx = stoppedWindowIndex.coerceIn(0, (currentSegmentUris.size - 1).coerceAtLeast(0))
        var posMs = stoppedPositionMs.coerceAtLeast(0L)

        if (isContinuousMixStation() && currentSongItem != null && activeMixMarkers.isEmpty()) {
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
            val isContinuousMix = isContinuousMixStation() || (Rpf3MetadataRepository.getTrackMeta(context, item.trackName, stationTitle)?.isTalkShow == true && !isTalkRadio)
            val maxChunksBeforeBreak = if (isContinuousMix) 6 else 100
            if (currentMultiChunksPlayedInRow < maxChunksBeforeBreak) {
                val nextStart = currentMultiChunkNextBlock
                val nextUri = item.archive.materializeAudioChunkUri(
                    item.trackName,
                    nextStart,
                    Rpf3Archive.CHUNK_BLOCKS
                )
                if (nextUri != null) {
                    pinAudioUri(nextUri)
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

        if (isContinuousMixStation()) {
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
            if (prefetched.songItem != null) {
                val totalBlocks = prefetched.songItem.archive.getEntryBlockCount(prefetched.songItem.trackName)
                currentMultiChunkTotalBlocks = totalBlocks
                currentMultiChunkNextBlock = if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) Rpf3Archive.CHUNK_BLOCKS else totalBlocks
                currentMultiChunksPlayedInRow = 1
            }
            return
        }

        val event = director.pickNextEvent { song ->
            if (song.introNames.isNotEmpty()) {
                val intro = song.introNames[rng.nextInt(song.introNames.size)]
                Rpf3ClipRef(song.archive, intro)
            } else null
        }
        when (event) {
            is NextBroadcastEvent.SongSweepItem -> {
                val item = event.song
                val uris = ArrayList<Uri>()
                if (event.djIntro != null) {
                    materializeClip(event.djIntro.trackKey)?.let { u ->
                        uris.add(u)
                        pinAudioUri(u)
                    }
                }
                val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
                val mainUri = if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) {
                    item.archive.materializeAudioChunkUri(item.trackName, 0, Rpf3Archive.CHUNK_BLOCKS)
                } else {
                    item.archive.materializeAudioUri(item.trackName)
                }
                if (mainUri != null) {
                    uris.add(mainUri)
                    pinAudioUri(mainUri)
                }
                currentSegmentUris = uris
                currentSongItem = item
                lastSegment = RadioSegmentType.Song
                currentMultiChunkTotalBlocks = totalBlocks
                currentMultiChunkNextBlock = if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) Rpf3Archive.CHUNK_BLOCKS else totalBlocks
                currentMultiChunksPlayedInRow = 1
                pendingBreakClips.clear()
            }
            is NextBroadcastEvent.BreakBlockItem -> {
                val clips = ArrayList(event.clips)
                val upfrontClips = ArrayList<DirectorBreakClip<Rpf3ClipRef>>()
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
            }
            is NextBroadcastEvent.MidSweepInterludeItem -> {
                val ref = event.clip
                val u = ref.archive.materializeAudioUri(ref.trackKey)
                if (u != null) {
                    pinAudioUri(u)
                    currentSegmentUris = listOf(u)
                }
                currentSongItem = null
                lastSegment = event.segmentType
                pendingBreakClips.clear()
            }
        }
    }

    private fun prepareContinuousMixSegment() {
        if (allSongItems.isEmpty()) return
        val item = allSongItems[rng.nextInt(allSongItems.size)]
        val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
        val startBlk = if (totalBlocks > Rpf3Archive.CHUNK_BLOCKS * 2) {
            (rng.nextInt(totalBlocks / Rpf3Archive.CHUNK_BLOCKS) * Rpf3Archive.CHUNK_BLOCKS).coerceAtMost(totalBlocks - Rpf3Archive.CHUNK_BLOCKS)
        } else {
            0
        }
        val mainUri = item.archive.materializeAudioChunkUri(item.trackName, startBlk, Rpf3Archive.CHUNK_BLOCKS) ?: return
        pinAudioUri(mainUri)
        currentMultiChunkTotalBlocks = totalBlocks
        currentMultiChunkNextBlock = startBlk + Rpf3Archive.CHUNK_BLOCKS
        currentMultiChunksPlayedInRow = 1

        val markers = item.archive.readTrackMarkers(item.trackName)
        if (markers.isNotEmpty()) {
            activeMixMarkers = markers
            activeMixChunkStartMs = item.archive.getChunkStartMs(item.trackName, startBlk)
            lastDisplayedMixTrackId = -1
        } else {
            stopMixPoller()
        }

        currentSongItem = item
        lastSegment = RadioSegmentType.Song
        currentSegmentUris = listOf(mainUri)
        pendingBreakClips.clear()
    }

    private fun startLookAheadMonitor(expectedGen: Int, totalDurationMs: Long) {
        lookAheadJob?.cancel()
        isPrefetching = false
        prefetchedItem = null
        if (totalDurationMs <= 25_000L) {
            lookAheadJob = stationScope.launch(StationDispatchers.ioDispatcher) {
                delay(2_000L)
                if (!_playing || playGeneration.get() != expectedGen || isPrefetching) return@launch
                prefetchNextEvent(expectedGen)
            }
            return
        }
        lookAheadJob = stationScope.launch {
            while (isActive && _playing && playGeneration.get() == expectedGen && !isPrefetching) {
                delay(1000L)
                val pos = player.getCurrentTrackPosition().coerceAtLeast(0L)
                val remainingMs = totalDurationMs - pos
                if (remainingMs in 1..25_000L) {
                    isPrefetching = true
                    stationScope.launch(StationDispatchers.ioDispatcher) {
                        if (!_playing || playGeneration.get() != expectedGen) return@launch
                        prefetchNextEvent(expectedGen)
                    }
                    break
                }
            }
        }
    }

    private fun prefetchNextEvent(expectedGen: Int) {
        if (!_playing || playGeneration.get() != expectedGen) return
        val event = director.pickNextEvent { song ->
            if (song.introNames.isNotEmpty()) {
                val intro = song.introNames[rng.nextInt(song.introNames.size)]
                Rpf3ClipRef(song.archive, intro)
            } else null
        }
        when (event) {
            is NextBroadcastEvent.SongSweepItem -> {
                val item = event.song
                val uris = ArrayList<Uri>()
                val pinned = ArrayList<String>()
                if (event.djIntro != null) {
                    materializeClip(event.djIntro.trackKey)?.let { u ->
                        uris.add(u)
                        val p = u.toString()
                        pinned.add(p)
                        Rpf3Archive.pinFile(p)
                    }
                }
                val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
                val mainUri = if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) {
                    item.archive.materializeAudioChunkUri(item.trackName, 0, Rpf3Archive.CHUNK_BLOCKS)
                } else {
                    item.archive.materializeAudioUri(item.trackName)
                }
                if (mainUri != null) {
                    uris.add(mainUri)
                    val p = mainUri.toString()
                    pinned.add(p)
                    Rpf3Archive.pinFile(p)
                }
                if (uris.isNotEmpty() && _playing && playGeneration.get() == expectedGen) {
                    prefetchedItem = PrefetchedBroadcastItem(
                        uris = uris,
                        pinnedFiles = pinned,
                        event = event,
                        songItem = item,
                        segmentType = RadioSegmentType.Song
                    )
                    GTRadioLogger.logVerbose("DIRECTOR") {
                        "[DIRECTOR] Rpf3[$stationTitle]: Look-ahead prefetched next song '${item.trackName}' (intro=${event.djIntro != null})"
                    }
                } else {
                    pinned.forEach { Rpf3Archive.unpinFile(it) }
                }
            }
            is NextBroadcastEvent.BreakBlockItem -> {
                val clips = ArrayList(event.clips)
                val upfrontClips = ArrayList<DirectorBreakClip<Rpf3ClipRef>>()
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
                        Rpf3Archive.pinFile(p)
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
                        "[DIRECTOR] Rpf3[$stationTitle]: Look-ahead prefetched break upfront (${upfrontClips.size} clips, ${clips.size} queued)"
                    }
                } else {
                    pinned.forEach { Rpf3Archive.unpinFile(it) }
                }
            }
            is NextBroadcastEvent.MidSweepInterludeItem -> {
                val ref = event.clip
                val u = ref.archive.materializeAudioUri(ref.trackKey)
                if (u != null && _playing && playGeneration.get() == expectedGen) {
                    val p = u.toString()
                    Rpf3Archive.pinFile(p)
                    prefetchedItem = PrefetchedBroadcastItem(
                        uris = listOf(u),
                        pinnedFiles = listOf(p),
                        event = event,
                        songItem = null,
                        segmentType = event.segmentType
                    )
                    GTRadioLogger.logVerbose("DIRECTOR") {
                        "[DIRECTOR] Rpf3[$stationTitle]: Look-ahead prefetched interlude '${ref.trackKey}' (${event.segmentType})"
                    }
                }
            }
        }
    }

    private fun startPlayback(uris: List<Uri>, startWindowIndex: Int, startPositionMs: Long, expectedGen: Int = playGeneration.get()) {
        mainHandler.post {
            if (!_playing || playGeneration.get() != expectedGen) {
                GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: startPlayback aborted: generation mismatch or not playing (gen=$expectedGen, cur=${playGeneration.get()})")
                return@post
            }

            refreshCurrentSegmentMetadata()

            if (activeListener != null) {
                player.exoPlayer.removeListener(activeListener!!)
                activeListener = null
            }

            player.exoPlayer.clearMediaItems()
            for (u in uris) {
                player.exoPlayer.addMediaItem(MediaItem.fromUri(u))
            }
            if (startWindowIndex > 0 || startPositionMs > 0L) {
                player.exoPlayer.seekTo(startWindowIndex, startPositionMs)
            }
            updateMetadataForWindowIndex(startWindowIndex, expectedGen)

            activeListener = object : Player.Listener {
                private var alreadyHandled = false
                override fun onPlaybackStateChanged(state: Int) {
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
                    val curIdx = player.exoPlayer.currentMediaItemIndex
                    updateMetadataForWindowIndex(curIdx, expectedGen)

                    // Staged Break Decoding: while Clip #1 plays, decode Clip #2; while Clip #2 plays, decode remaining
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
                                        "[DIRECTOR] Rpf3[$stationTitle]: Staged queued break clip '${nextClip.clip.trackKey}' (${nextClip.label})"
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: ExoPlayer error on track: ${error.message}", error)
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
                stopMixPoller()
            }

            // Start look-ahead prefetch monitor for discrete songs
            if (!isContinuousMixStation() && currentSongItem != null) {
                val totalDur = if (uris.isNotEmpty()) getFileDurationMs(uris.last()) else 0L
                startLookAheadMonitor(expectedGen, totalDur)
            }
        }
    }

    private fun startMixPoller(expectedGen: Int = playGeneration.get()) {
        stopMixPoller()
        activePollerJob = stationScope.launch {
            while (isActive && _playing && playGeneration.get() == expectedGen && activeMixMarkers.isNotEmpty()) {
                updateMixTrackMetadata()
                delay(1000L)
            }
        }
    }

    private fun stopMixPoller() {
        activePollerJob?.cancel()
        activePollerJob = null
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

        activeCleanJob?.cancel()
        activeCleanJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                val album = Gen2MetadataRepository.getCachedAlbumName(context, artist, title) ?: stationTitle
                withContext(Dispatchers.Main.immediate) {
                    if (token == cleanPlayGeneration.get()) {
                        onMetadataReady(title, artist, album, 2009)
                    }
                }
                val uri = targetItem.archive.materializeFullAudioUri(targetItem.trackName)
                if (token != cleanPlayGeneration.get()) return@launch
                if (uri == null) {
                    GTRadioLogger.log("Rpf3RadioStation: Failed to materialize clean track audio for '${targetItem.trackName}'")
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
                    GTRadioLogger.log("Rpf3RadioStation: Error materializing clean track", e)
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
}
