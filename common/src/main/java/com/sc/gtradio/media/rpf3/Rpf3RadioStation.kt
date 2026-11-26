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
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class Rpf3SongItem(
    val archive: Rpf3Archive,
    val trackName: String,
    val introNames: List<String> = emptyList()
)

data class Rpf3ClipRef(
    val archive: Rpf3Archive,
    val trackKey: String
)

private val RPF3_INTRO_ALIASES = mapOf(
    "HEAVENANDHELL" to "HEAVENHELL"
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
    private val isTalkRadio: Boolean = run {
        val k = stationKey.lowercase(Locale.ROOT)
        val a = stationArchive.rpfName.lowercase(Locale.ROOT)
        k.contains("wktt") || k.contains("plr") || k.contains("lazlow") ||
            a.contains("wktt") || a.contains("plr") || a.contains("lazlow")
    }

    private val isContinuousMix: Boolean get() = isContinuousMixStation()
    private val isAmbient: Boolean get() = isAmbientJourneyStation()
    private val isNoCommercial: Boolean get() = isTalkRadio || isContinuousMix || isAmbient

    private val maxAds: Int get() {
        val isVlad = isVladivostokStation()
        val isSanJ = isSanJuanStation()
        return if (isVlad || isSanJ) 1 else 2
    }

    private val director by lazy {
        Rpf3RadioDirector<Rpf3SongItem, Rpf3ClipRef>(
            context = context,
            stationName = stationTitle,
            isTalkRadio = isTalkRadio,
            isContinuousMix = isContinuousMix,
            isNoCommercialStation = isNoCommercial,
            maxAdsPerBreak = maxAds,
            rng = rng
        ).also { d ->
            d.adsEnabled = if (isNoCommercial) false else this.adsEnabled
            d.weatherChatterEnabled = if (isNoCommercial) false else this.weatherChatterEnabled
            d.newsReportsEnabled = if (isNoCommercial) false else this.newsReportsEnabled
        }
    }

    override var adsEnabled: Boolean = adsEnabled
        set(value) {
            field = value
            if (!isNoCommercial) {
                director.adsEnabled = value
            }
        }
    override var weatherChatterEnabled: Boolean = weatherChatterEnabled
        set(value) {
            field = value
            if (!isNoCommercial) {
                director.weatherChatterEnabled = value
            }
        }
    override var newsReportsEnabled: Boolean = newsReportsEnabled
        set(value) {
            field = value
            if (!isNoCommercial) {
                director.newsReportsEnabled = value
            }
        }

    private val morningDjList = ArrayList<Rpf3ClipRef>()
    private val afternoonDjList = ArrayList<Rpf3ClipRef>()
    private val eveningDjList = ArrayList<Rpf3ClipRef>()
    private val nightDjList = ArrayList<Rpf3ClipRef>()

    private val allSongItems = ArrayList<Rpf3SongItem>()
    private val stationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeDecodeJob: Job? = null
    private var seamlessPrepareJob: Job? = null
    private var activeCleanJob: Job? = null
    private var activePollerJob: Job? = null
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val playGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    override val supportsSeamlessPrepare: Boolean get() = true

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var activeListener: Player.Listener? = null

    private data class ChunkPlaybackMeta(
        val item: Rpf3SongItem,
        val startBlock: Int,
        val loopEpoch: Int,
        val startMs: Long,
        val uri: Uri
    )

    private data class NextChunkTarget(
        val item: Rpf3SongItem,
        val startBlock: Int,
        val totalBlocks: Int,
        val loopEpoch: Int,
        val chunkBlocks: Int
    )

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: Rpf3SongItem? = null
    private var nextChunkItem: Rpf3SongItem? = null
    private var nextChunkStartBlock: Int = 0
    private var nextChunkTotalBlocks: Int = 0
    private var nextChunkLoopEpoch: Int = 0
    private var currentMultiChunksPlayedInRow: Int = 0
    private var introItemCount: Int = 0
    private var hasStartedContinuousMix: Boolean = false
    private var savedMixAbsMs: Long = 0L
    private var continuousMixItem: Rpf3SongItem? = null
    private val chunkMetaByMediaId = ConcurrentHashMap<String, ChunkPlaybackMeta>()
    private val chunkMediaIdByUri = ConcurrentHashMap<Uri, String>()
    private val queuedChunkKeys: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private var activeChunkPrefetchJob: Job? = null

    private fun buildChunkKey(entryName: String, loopEpoch: Int, startBlock: Int): String =
        "${entryName.uppercase(Locale.ROOT)}_${loopEpoch}_$startBlock"

    private fun buildChunkMediaId(entryName: String, loopEpoch: Int, startBlock: Int): String =
        "rpf3_chunk_${entryName}_${loopEpoch}_$startBlock"

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
        val event: Gen3BroadcastEvent<Rpf3SongItem, Rpf3ClipRef>,
        val songItem: Rpf3SongItem?,
        val segmentType: RadioSegmentType,
        val pendingBreakClips: List<StandaloneBreakClip<Rpf3ClipRef>>? = null,
        val plannedOverlayCue: PlannedOverlayCue? = null,
        val plannedPreRollCue: PlannedPreRollCue? = null
    )

    private var prefetchedItem: PrefetchedBroadcastItem? = null
    private var lookAheadJob: Job? = null
    private var activeOverlayJob: Job? = null
    private var plannedOverlayCue: PlannedOverlayCue? = null
    private var plannedPreRollCue: PlannedPreRollCue? = null
    private var isPrefetching = false
    private var hasPrefetchedNextSegment = false
    private val activePinnedCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val pendingBreakClips = ArrayList<StandaloneBreakClip<Rpf3ClipRef>>()
    private var activeBreakStagingJob: Job? = null
    @Volatile
    private var isOutroOverlaySpeaking: Boolean = false
    @Volatile
    private var seamlessPrepareActive: Boolean = false
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

    private fun pinAudioUri(uri: Uri) {
        val uriStr = uri.toString()
        activePinnedCounts.compute(uriStr) { _, existing ->
            (existing ?: java.util.concurrent.atomic.AtomicInteger(0)).apply { incrementAndGet() }
        }
        Rpf3Archive.pinFile(uriStr)
    }

    private fun unpinAudioUri(uri: Uri) {
        val uriStr = uri.toString()
        var decremented = false
        activePinnedCounts.computeIfPresent(uriStr) { _, counter ->
            decremented = true
            if (counter.decrementAndGet() <= 0) null else counter
        }
        if (decremented) {
            Rpf3Archive.unpinFile(uriStr)
        }
    }

    private fun unpinAllActive() {
        val snapshot = activePinnedCounts.entries.toList()
        activePinnedCounts.clear()
        for ((u, counter) in snapshot) {
            repeat(counter.get().coerceAtLeast(0)) {
                Rpf3Archive.unpinFile(u)
            }
        }
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

        val keyLower = stationKey.lowercase(Locale.ROOT)

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
                        val lookupKey = RPF3_INTRO_ALIASES[eName] ?: eName
                        val intros = introMap[lookupKey] ?: emptyList()
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
                    val lookupKey = RPF3_INTRO_ALIASES[extraName] ?: extraName
                    songItems.add(Rpf3SongItem(extraTracksArchive, extraName, introMap[lookupKey] ?: emptyList()))
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
                    val lookupKey = RPF3_INTRO_ALIASES[tName] ?: tName
                    songItems.add(Rpf3SongItem(ep1SfxArchive, tName, introMap[lookupKey] ?: emptyList()))
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
                    val lookupKey = RPF3_INTRO_ALIASES[tName] ?: tName
                    songItems.add(Rpf3SongItem(ep2SfxArchive, tName, introMap[lookupKey] ?: emptyList()))
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

        // Authentic Game Config: continuous mixes, ambient journey, and talk stations disable external breaks
        val isContinuous = isContinuousMixStation()
        val isAmbient = isAmbientJourneyStation()
        if (isContinuous || isAmbient || isTalkRadio) {
            director.adsEnabled = false
            director.newsReportsEnabled = false
            director.weatherChatterEnabled = false
        }

        val isVlad = isVladivostokStation()
        val isSanJ = isSanJuanStation()
        if (isVlad || isSanJ) {
            director.maxAdsPerBreak = 1
        }

        val allAdverts = ArrayList<Rpf3ClipRef>()
        fun shouldIncludeAd(adName: String): Boolean {
            val u = adName.uppercase(Locale.ROOT)
            return when {
                isVlad -> u.contains("RUSSIAN")
                isSanJ -> u.contains("SPANISH")
                else -> !u.contains("RUSSIAN") && !u.contains("SPANISH")
            }
        }

        advertsArchive?.getFileEntries()?.filter { !it.isWaveBank && shouldIncludeAd(it.name) }?.forEach {
            allAdverts.add(Rpf3ClipRef(advertsArchive, it.name))
        }
        ep1SfxArchive?.getFileEntries()?.filter { !it.isWaveBank && it.name.uppercase(Locale.ROOT).startsWith("AD0") && shouldIncludeAd(it.name) }?.forEach {
            allAdverts.add(Rpf3ClipRef(ep1SfxArchive, it.name))
        }
        ep2SfxArchive?.getFileEntries()?.filter { !it.isWaveBank && it.name.uppercase(Locale.ROOT).startsWith("AD1") && shouldIncludeAd(it.name) }?.forEach {
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
            val m = buildStationMetadata(
                trackTitle = displayTitle,
                trackSubtitle = subtitle,
                art = stationLogo,
                isSongOrShow = true,
                cleanArtist = displayArtist,
                cleanAlbum = ""
            )
            notifyMetadataChanged(m)
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
                            notifyMetadataChanged(buildStationMetadata(displayTitle, sub, newArt, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = alb))
                        }
                    }
                }
            }
        }

        val eraAlbum = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
        val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
        notifyMetadataChanged(buildStationMetadata(displayTitle, subtitle, art, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = eraAlbum))
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
                        GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: seamless commit() (gen=$commitGen)")
                        seamlessPrepareActive = false
                        seamlessPreparePrevHasStartedMix = null
                        seamlessPreparePrevSavedMixAbsMs = null
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
                GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: seamless prepare error", e)
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
        GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: stop() called (gen=$gen)")
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
        prefetchedItem?.pinnedFiles?.forEach { Rpf3Archive.unpinFile(it) }
        prefetchedItem = null
        pendingBreakClips.clear()
        activeBreakStagingJob?.cancel()
        activeBreakStagingJob = null
        isOutroOverlaySpeaking = false

        val isMix = isContinuousMixStation()
        if (isMix && currentSongItem != null) {
            val exo = player.exoPlayer
            if (exo.playbackState == Player.STATE_READY) {
                val mediaId = exo.currentMediaItem?.mediaId
                val chunkMeta = if (mediaId != null) chunkMetaByMediaId[mediaId] else null
                if (chunkMeta != null) {
                    val rawPosMs = exo.currentPosition.coerceAtLeast(0L)
                    val curPosMs = if (exo.duration > 0) rawPosMs.coerceAtMost(exo.duration) else rawPosMs
                    savedMixAbsMs = chunkMeta.startMs + curPosMs
                    continuousMixItem = chunkMeta.item
                    lastStoppedTimeMs = System.currentTimeMillis()
                }
            }
        }

        clearChunkTracking(cancelPrefetch = false)
        unpinAllActive()
        if (!isStationActive) return
        isStationActive = false
        if (_playing && !isMix) {
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
            if (!f.exists() || f.length() <= 44L) return false
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
        val currentGen = playGeneration.incrementAndGet()
        GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: play() called (gen=$currentGen)")
        isStationActive = true
        _playing = true
        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

        activeChunkPrefetchJob?.cancel()
        activeChunkPrefetchJob = null
        queuedChunkKeys.clear()

        val isMix = isContinuousMixStation()
        val canResumeMix = isMix && savedMixAbsMs > 0L && continuousMixItem != null && lastStoppedTimeMs > 0L
        val canResumeTrack = !isMix && director.currentPhase != Gen3DirectorPhase.BREAK_BLOCK && lastStoppedTimeMs > 0L && areSegmentFilesValid(currentSegmentUris)
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
                GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: activeDecodeJob error", e)
            }
        }
    }

    private suspend fun resolvePlaybackForStart(
        emitMetadata: Boolean,
        isValid: () -> Boolean
    ): PreparedPlayback? {
        val isMix = isContinuousMixStation()
        val canResumeMix = isMix && savedMixAbsMs > 0L && continuousMixItem != null && lastStoppedTimeMs > 0L
        val canResumeTrack = !isMix && director.currentPhase != Gen3DirectorPhase.BREAK_BLOCK && lastStoppedTimeMs > 0L && areSegmentFilesValid(currentSegmentUris)
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

    private fun isMixEntryName(trackName: String): Boolean {
        if (isTalkRadio) return false
        val u = trackName.uppercase(Locale.ROOT)
        val meta = Rpf3MetadataRepository.getTrackMeta(context, u, stationTitle)
        return meta?.isTalkShow == true ||
            u.contains("MIX") ||
            u == "BEAT" ||
            u == "FK" ||
            u.contains("LCHC_32K_PH") ||
            u.contains("EUROBEAT_MIX") ||
            u.contains("CROOKERS_MIX")
    }

    private fun isMixItem(item: Rpf3SongItem?): Boolean {
        return item != null && isContinuousMixStation() && isMixEntryName(item.trackName)
    }

    private fun getAllMixEntries(): List<Rpf3SongItem> {
        val mixItems = allSongItems.filter { isMixEntryName(it.trackName) }
        return (if (mixItems.isNotEmpty()) mixItems else allSongItems).sortedBy { it.trackName.uppercase(Locale.ROOT) }
    }

    private fun getMixSeriesPartsFor(item: Rpf3SongItem): List<Rpf3SongItem> {
        val allMixes = getAllMixEntries()
        val u = item.trackName.uppercase(Locale.ROOT)
        val partMatch = Regex("^(.+?)(?:_P\\d+|_PART_?\\d+)$").find(u)
        if (partMatch != null) {
            val prefix = partMatch.groupValues[1]
            val series = allMixes.filter {
                val cu = it.trackName.uppercase(Locale.ROOT)
                cu.startsWith("${prefix}_P") || cu.startsWith("${prefix}_PART")
            }.sortedBy { it.trackName.uppercase(Locale.ROOT) }
            if (series.isNotEmpty()) return series
        }
        return listOf(item)
    }

    private fun isValidWavFile(uri: Uri?): Boolean {
        val p = uri?.path ?: return false
        val f = File(p)
        return f.exists() && f.length() > 44L
    }

    private fun materializeValidChunkUri(item: Rpf3SongItem, startBlock: Int, chunkBlocks: Int): Uri? {
        val first = item.archive.materializeAudioChunkUri(item.trackName, startBlock, chunkBlocks)
        if (isValidWavFile(first)) return first
        // Retry exact same startBlock if cache file was evicted or incomplete; never skip ahead.
        val retry = item.archive.materializeAudioChunkUri(item.trackName, startBlock, chunkBlocks)
        return if (isValidWavFile(retry)) retry else null
    }

    private fun registerChunkMetadata(item: Rpf3SongItem, startBlock: Int, loopEpoch: Int, uri: Uri): String {
        val chunkMediaId = buildChunkMediaId(item.trackName, loopEpoch, startBlock)
        val startMs = item.archive.getChunkStartMs(item.trackName, startBlock)
        chunkMetaByMediaId[chunkMediaId] = ChunkPlaybackMeta(item, startBlock, loopEpoch, startMs, uri)
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
        val chunkBlocks = Rpf3Archive.CHUNK_BLOCKS
        if (nextChunkStartBlock < totalBlocks) {
            return NextChunkTarget(item, nextChunkStartBlock, totalBlocks, nextChunkLoopEpoch, chunkBlocks)
        }
        if (isMixItem(item)) {
            val parts = getMixSeriesPartsFor(item)
            val nextItem = if (parts.size > 1) {
                val curIdx = parts.indexOfFirst { it.trackName.equals(item.trackName, ignoreCase = true) }
                parts[if (curIdx >= 0) (curIdx + 1) % parts.size else 0]
            } else {
                item
            }
            val nextTotal = nextItem.archive.getEntryBlockCount(nextItem.trackName)
            return NextChunkTarget(nextItem, 0, nextTotal, nextChunkLoopEpoch + 1, chunkBlocks)
        }
        return null
    }

    private fun estimateEntryDurationMs(item: Rpf3SongItem, totalBlocks: Int): Long {
        if (totalBlocks <= 0) return 0L
        val chunkBlocks = Rpf3Archive.CHUNK_BLOCKS
        val lastAligned = ((totalBlocks - 1) / chunkBlocks).coerceAtLeast(0) * chunkBlocks
        if (lastAligned <= 0) return 0L
        val lastStartMs = item.archive.getChunkStartMs(item.trackName, lastAligned)
        if (lastStartMs <= 0L) return 0L
        val avgBlockMs = lastStartMs.toDouble() / lastAligned.toDouble()
        val remainingBlocks = (totalBlocks - lastAligned).coerceAtLeast(1)
        return lastStartMs + (remainingBlocks * avgBlockMs).toLong()
    }

    private fun resolveContinuousMixPrepared(
        elapsedMs: Long,
        isValid: () -> Boolean
    ): PreparedPlayback? {
        var item = continuousMixItem ?: return null
        var absMs = savedMixAbsMs + elapsedMs
        val parts = getMixSeriesPartsFor(item)
        if (parts.size > 1) {
            var guard = 0
            while (guard < parts.size) {
                val pTotalBlocks = item.archive.getEntryBlockCount(item.trackName)
                val pDurMs = estimateEntryDurationMs(item, pTotalBlocks)
                if (pDurMs <= 0L || absMs < pDurMs) break
                absMs -= pDurMs
                val idx = parts.indexOfFirst { it.trackName.equals(item.trackName, ignoreCase = true) }
                item = parts[if (idx >= 0) (idx + 1) % parts.size else 0]
                guard++
            }
        }

        val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
        val chunkBlocks = Rpf3Archive.CHUNK_BLOCKS
        val estTotalMs = estimateEntryDurationMs(item, totalBlocks)
        val targetMixMs = if (estTotalMs > 0L) absMs % estTotalMs else absMs

        var targetStartBlock = 0
        var chunkStartMs = 0L
        var blk = chunkBlocks
        while (blk < totalBlocks) {
            val ms = item.archive.getChunkStartMs(item.trackName, blk)
            if (ms <= 0L || ms > targetMixMs) break
            targetStartBlock = blk
            chunkStartMs = ms
            blk += chunkBlocks
        }

        val chunkKey = buildChunkKey(item.trackName, 0, targetStartBlock)
        if (!queuedChunkKeys.add(chunkKey)) return null

        val mainUri = materializeValidChunkUri(item, targetStartBlock, chunkBlocks) ?: return null
        if (!isValid()) return null

        val fileDur = getFileDurationMs(mainUri)
        val seekWithinChunkMs = (targetMixMs - chunkStartMs).coerceIn(0L, (fileDur - 500L).coerceAtLeast(0L))

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
            activeMixChunkStartMs = chunkStartMs
            lastDisplayedMixTrackId = -1
        } else {
            stopMixPoller(clearMarkers = true)
        }

        return PreparedPlayback(listOf(mainUri), 0, seekWithinChunkMs, fileDur, null, null)
    }

    private fun advanceMultiChunkOrPickNext(emitMetadata: Boolean = true): Boolean {
        if (lastSegment == RadioSegmentType.Song && currentSongItem != null) {
            val target = resolveNextChunkTarget()
            if (target != null) {
                val chunkKey = buildChunkKey(target.item.trackName, target.loopEpoch, target.startBlock)
                val nextUri = materializeValidChunkUri(target.item, target.startBlock, target.chunkBlocks)
                if (nextUri != null && queuedChunkKeys.add(chunkKey)) {
                    pinAudioUri(nextUri)
                    val mId = registerChunkMetadata(target.item, target.startBlock, target.loopEpoch, nextUri)
                    if (target.item != currentSongItem) {
                        currentSongItem = target.item
                        if (isMixItem(target.item)) {
                            val markers = target.item.archive.readTrackMarkers(target.item.trackName)
                            if (markers.isNotEmpty()) {
                                activeMixMarkers = markers
                                lastDisplayedMixTrackId = -1
                            } else {
                                stopMixPoller(clearMarkers = true)
                            }
                        }
                    }
                    activeMixChunkStartMs = chunkMetaByMediaId[mId]?.startMs ?: 0L
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
                GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: playNext decode error", e)
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
            plannedOverlayCue = prefetched.plannedOverlayCue
            plannedPreRollCue = prefetched.plannedPreRollCue
            introItemCount = if (prefetched.plannedOverlayCue != null || prefetched.plannedPreRollCue != null) 0 else if (currentSegmentUris.size > 1 && lastSegment == RadioSegmentType.Song) 1 else 0
            if (prefetched.songItem != null) {
                val totalBlocks = prefetched.songItem.archive.getEntryBlockCount(prefetched.songItem.trackName)
                nextChunkItem = prefetched.songItem
                nextChunkTotalBlocks = totalBlocks
                nextChunkLoopEpoch = 0
                nextChunkStartBlock = if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) Rpf3Archive.CHUNK_BLOCKS else totalBlocks
                currentMultiChunksPlayedInRow = 1
                if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) {
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

        val event = director.pickNextEvent { song ->
            if (song.introNames.isNotEmpty()) {
                val intro = song.introNames[rng.nextInt(song.introNames.size)]
                Rpf3ClipRef(song.archive, intro)
            } else null
        }
        if (emitMetadata) {
            setStationOnlyMetadata()
        }
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
                        pinAudioUri(u)
                        djIntroUri = u
                        djDurMs = readAudioDurationMs(context, u)
                    }
                }

                val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
                val mainUri = if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) {
                    item.archive.materializeAudioChunkUri(item.trackName, 0, Rpf3Archive.CHUNK_BLOCKS)
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

                if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS && mainUri != null) {
                    registerChunkMetadata(item, 0, 0, mainUri)
                    queuedChunkKeys.add(buildChunkKey(item.trackName, 0, 0))
                }
                currentSegmentUris = uris
                currentSongItem = item
                lastSegment = RadioSegmentType.Song
                nextChunkItem = item
                nextChunkTotalBlocks = totalBlocks
                nextChunkLoopEpoch = 0
                nextChunkStartBlock = if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) Rpf3Archive.CHUNK_BLOCKS else totalBlocks
                currentMultiChunksPlayedInRow = 1
                pendingBreakClips.clear()
            }
            is Gen3BroadcastEvent.BreakBlockEvent -> {
                val uris = ArrayList<Uri>()
                val clips = ArrayList(event.standaloneClips)
                if (clips.isNotEmpty()) {
                    val first = clips.removeAt(0)
                    val u = first.clip.archive.materializeAudioUri(first.clip.trackKey)
                    if (u != null) {
                        uris.add(u)
                        pinAudioUri(u)
                    }
                    lastSegment = first.segmentType
                } else {
                    lastSegment = RadioSegmentType.Commercial
                }
                currentSegmentUris = uris
                currentSongItem = null
                pendingBreakClips.clear()
                pendingBreakClips.addAll(clips)
            }
            is Gen3BroadcastEvent.InterludeEvent -> {
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
        val mixEntries = getAllMixEntries()
        if (mixEntries.isEmpty()) return
        val chunkBlocks = Rpf3Archive.CHUNK_BLOCKS
        val item: Rpf3SongItem
        val startBlk: Int
        if (!hasStartedContinuousMix) {
            item = mixEntries[rng.nextInt(mixEntries.size)]
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
            val series = if (prev != null) getMixSeriesPartsFor(prev) else mixEntries
            item = if (series.size > 1 && prev != null) {
                val idx = series.indexOfFirst { it.trackName.equals(prev.trackName, ignoreCase = true) }
                series[if (idx >= 0) (idx + 1) % series.size else 0]
            } else {
                prev ?: series[0]
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
        val startMs = item.archive.getChunkStartMs(item.trackName, startBlk)

        val markers = item.archive.readTrackMarkers(item.trackName)
        if (markers.isNotEmpty()) {
            activeMixMarkers = markers
            activeMixChunkStartMs = startMs
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
                            GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: prefetchNextEvent error", e)
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
        val event = director.pickNextEvent { song ->
            if (song.introNames.isNotEmpty()) {
                val intro = song.introNames[rng.nextInt(song.introNames.size)]
                Rpf3ClipRef(song.archive, intro)
            } else null
        }
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
                        val p = u.toString()
                        pinned.add(p)
                        Rpf3Archive.pinFile(p)
                        djIntroUri = u
                        djDurMs = readAudioDurationMs(context, u)
                    }
                }
                val totalBlocks = item.archive.getEntryBlockCount(item.trackName)
                val mainUri = if (totalBlocks > Rpf3Archive.CHUNK_THRESHOLD_BLOCKS) {
                    item.archive.materializeAudioChunkUri(item.trackName, 0, Rpf3Archive.CHUNK_BLOCKS)
                } else {
                    item.archive.materializeAudioUri(item.trackName)
                }
                if (mainUri != null) {
                    val p = mainUri.toString()
                    pinned.add(p)
                    Rpf3Archive.pinFile(p)
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
                        "Rpf3[$stationTitle]: Look-ahead prefetched next song '${item.trackName}' (intro=${event.songIntroOverlay != null})"
                    }
                } else {
                    pinned.forEach { Rpf3Archive.unpinFile(it) }
                }
            }
            is Gen3BroadcastEvent.BreakBlockEvent -> {
                val clips = ArrayList(event.standaloneClips)
                val uris = ArrayList<Uri>()
                val pinned = ArrayList<String>()
                if (clips.isNotEmpty()) {
                    val first = clips.removeAt(0)
                    val u = first.clip.archive.materializeAudioUri(first.clip.trackKey)
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
                        segmentType = event.standaloneClips.first().segmentType,
                        pendingBreakClips = clips
                    )
                    GTRadioLogger.logVerbose("DIRECTOR") {
                        "Rpf3[$stationTitle]: Look-ahead prefetched break upfront (${uris.size} clips, ${clips.size} queued, dryLeadIn=${event.outroDryLeadIn != null})"
                    }
                } else {
                    pinned.forEach { Rpf3Archive.unpinFile(it) }
                }
            }
            is Gen3BroadcastEvent.InterludeEvent -> {
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
                        "Rpf3[$stationTitle]: Look-ahead prefetched interlude '${ref.trackKey}' (${event.role}, dryEligible=${event.isDryOverlayEligible})"
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

                // Pin BEFORE addMediaItem() so LRU eviction cannot delete queued chunks
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
                GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: prefetchAndQueueNextChunk error", e)
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
                GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: startPlayback aborted: generation mismatch or not playing (gen=$expectedGen, cur=${playGeneration.get()})")
            } else {
                if (activeListener != null) {
                    player.exoPlayer.removeListener(activeListener!!)
                    activeListener = null
                }

                player.exoPlayer.clearMediaItems()
                for (u in uris) {
                    val chunkMediaId = chunkMediaIdByUri[u]
                    val item = if (chunkMediaId != null) {
                        MediaItem.Builder().setUri(u).setMediaId(chunkMediaId).build()
                    } else {
                        MediaItem.fromUri(u)
                    }
                    player.exoPlayer.addMediaItem(item)
                }
                if (startWindowIndex > 0 || startPositionMs > 0L) {
                    player.exoPlayer.seekTo(startWindowIndex, startPositionMs)
                }
                updateMetadataForWindowIndex(startWindowIndex, expectedGen)

                activeListener = object : Player.Listener {
                    private var alreadyHandled = false
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && activeMixMarkers.isNotEmpty()) {
                            val mediaId = player.exoPlayer.currentMediaItem?.mediaId
                            if (mediaId != null && chunkMetaByMediaId.containsKey(mediaId)) {
                                updateMixTrackMetadata()
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
                                            lastDisplayedMixTrackId = -1
                                        } else {
                                            stopMixPoller(clearMarkers = true)
                                        }
                                    }
                                }
                                activeMixChunkStartMs = meta.startMs
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
                            "Rpf3[$stationTitle]: Staged queued break clip '${nextClip.clip.trackKey}' (${nextClip.role})"
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: staged break clip decode error", e)
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
                GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: stageAndPlayRemainingBreakClip error", e)
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
            activeMixChunkStartMs = 0L
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
            }
        }
        if (activeMixMarkers.isEmpty()) return
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }
        val currentTrackPosMs = exo.currentPosition.coerceAtLeast(0L).let {
            if (exo.duration > 0) it.coerceAtMost(exo.duration) else it
        }
        val currentMs = chunkMeta.startMs + currentTrackPosMs

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
                                notifyMetadataChanged(buildStationMetadata(displayTitle, sub, newArt, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = alb))
                            }
                        }
                    }
                }
            }
            val eraAlbum = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
            val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
            notifyMetadataChanged(buildStationMetadata(displayTitle, subtitle, art, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = eraAlbum))
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

    private fun isAmbientJourneyStation(): Boolean {
        val archName = stationArchive.rpfName.lowercase(Locale.ROOT)
        val key = stationKey.lowercase(Locale.ROOT)
        return archName.contains("classical_ambient") || key.contains("classical_ambient")
    }

    private fun isVladivostokStation(): Boolean {
        val archName = stationArchive.rpfName.lowercase(Locale.ROOT)
        val key = stationKey.lowercase(Locale.ROOT)
        return archName.contains("vladivostok") || key.contains("vladivostok")
    }

    private fun isSanJuanStation(): Boolean {
        val archName = stationArchive.rpfName.lowercase(Locale.ROOT)
        val key = stationKey.lowercase(Locale.ROOT)
        return archName.contains("san_juan") || key.contains("san_juan")
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
