package com.sc.gtradio.media.rpf3
import com.sc.gtradio.media.core.*
import com.sc.gtradio.media.gen2.Gen2MetadataRepository


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
import java.io.FileInputStream
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
    @Volatile private var cachedStationLogo: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rng = Random()

    private fun getCachedStationLogoOnly(): Bitmap? {
        cachedStationLogo?.let { return it }
        val bmp = mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.getCachedBitmap(it, 512) }
        if (bmp != null) cachedStationLogo = bmp
        return bmp
    }

    private fun ensureStationLogoLoadedOnIo(): Bitmap? {
        getCachedStationLogoOnly()?.let { return it }
        val bmp = mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.loadBitmap(context, it, 512) }
        if (bmp != null) cachedStationLogo = bmp
        return bmp
    }

    private var _currentMetadata: MediaMetadata = buildStationMetadata(stationTitle, "", getCachedStationLogoOnly())
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
    private var activeMixMarkerLoadJob: Job? = null
    private var activeMetadataJob: Job? = null
    private val cleanPlayGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val playGeneration = java.util.concurrent.atomic.AtomicInteger(0)

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
        val songItem: Rpf3SongItem?,
        val segmentType: RadioSegmentType,
        val introItemCount: Int,
        val pendingBreakClips: List<StandaloneBreakClip<Rpf3ClipRef>>,
        val preRoll: PlannedPreRollCue?,
        val overlayCue: PlannedOverlayCue?,
        val pinnedFiles: List<String>,
        val isContinuousMix: Boolean,
        val markContinuousMixStarted: Boolean,
        val mixMarkers: List<Rpf3TrackMarker>,
        val consumedPreviousSegment: Boolean,
        val usedPrefetchedItem: Boolean
    )

    private val prewarmGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val prewarmLock = Any()
    @Volatile private var prewarmedTakeOver: PrewarmedTakeOver? = null
    @Volatile private var pendingPrewarmPinnedFiles: List<String> = emptyList()

    private var currentSegmentUris: List<Uri> = emptyList()
    private var currentSongItem: Rpf3SongItem? = null
    private var introItemCount: Int = 0
    private var hasStartedContinuousMix: Boolean = false
    private var pendingMixStartOffsetMs: Long = 0L
    private var savedMixAbsMs: Long = 0L
    private var continuousMixItem: Rpf3SongItem? = null

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

    private fun pinAudioUri(uri: Uri) {
        if (uri.scheme == Rpf3StreamingDataSource.SCHEME) return
        val uriStr = uri.toString()
        activePinnedCounts.compute(uriStr) { _, existing ->
            (existing ?: java.util.concurrent.atomic.AtomicInteger(0)).apply { incrementAndGet() }
        }
        Rpf3Archive.pinFile(uriStr)
    }

    private fun unpinAudioUri(uri: Uri) {
        if (uri.scheme == Rpf3StreamingDataSource.SCHEME) return
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
    private val durationCache = ConcurrentHashMap<Uri, Long>()

    private var activeMixMarkers: List<Rpf3TrackMarker> = emptyList()
    private var lastDisplayedMixTrackId: Int = -1
    private var lastPolledMixPosMs: Long = -1L
    private var mixPollerRunnable: Runnable? = null

    private fun resolveStreamIndex(uri: Uri?): Rpf3StreamIndex? {
        return Rpf3StreamRegistry.resolveStreamIndex(uri)
    }

    private fun resolvePlaybackUri(archive: Rpf3Archive, trackKey: String): Uri? {
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
        ep1SfxArchive?.materializeAudioUri(keyOrScoped)?.let { return it }
        ep2SfxArchive?.materializeAudioUri(keyOrScoped)?.let { return it }
        extraTracksArchive?.materializeAudioUri(keyOrScoped)?.let { return it }
        return null
    }

    @Synchronized
    private fun ensureStationIndexed() {
        if (initialized) return
        initialized = true
        Rpf3MetadataRepository.ensureLoaded(context)
        ensureStationLogoLoadedOnIo()

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
        notifyMetadataChanged(buildStationMetadata(stationTitle, "", getCachedStationLogoOnly()))
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

        val expectedGen = playGeneration.get()
        val albumArtEnabled = isAlbumArtEnabled()
        activeMetadataJob?.cancel()
        activeMetadataJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            if (!_playing || playGeneration.get() != expectedGen || lastSegment != RadioSegmentType.Song || currentSongItem != item) {
                return@launch
            }
            val logo = ensureStationLogoLoadedOnIo()
            val meta = Rpf3MetadataRepository.getTrackMeta(context, item.trackName, stationTitle)
            val displayTitle = meta?.title ?: item.trackName.replace('_', ' ')
            val displayArtist = meta?.artist ?: stationTitle
            val isTalk = isTalkRadio || (meta?.isTalkShow == true)

            if (isTalk) {
                val subtitle = if (displayArtist.isNotBlank() && displayArtist != stationTitle) "$displayArtist • $stationTitle" else stationTitle
                val m = buildStationMetadata(
                    trackTitle = displayTitle,
                    trackSubtitle = subtitle,
                    art = logo,
                    isSongOrShow = true,
                    cleanArtist = displayArtist,
                    cleanAlbum = ""
                )
                withContext(Dispatchers.Main.immediate) {
                    if (_playing && playGeneration.get() == expectedGen && lastSegment == RadioSegmentType.Song && currentSongItem == item) {
                        notifyMetadataChanged(m)
                    }
                }
                return@launch
            }

            val maxYear = 2009
            val cached = if (albumArtEnabled) {
                Gen2MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle)
            } else null
            val eraAlbum = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
            val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
            val built = buildStationMetadata(displayTitle, subtitle, cached ?: logo, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = eraAlbum)
            withContext(Dispatchers.Main.immediate) {
                if (_playing && playGeneration.get() == expectedGen && lastSegment == RadioSegmentType.Song && currentSongItem == item) {
                    notifyMetadataChanged(built)
                }
            }

            if (albumArtEnabled && cached == null && _playing && playGeneration.get() == expectedGen && lastSegment == RadioSegmentType.Song && currentSongItem == item) {
                Gen2MetadataRepository.fetchEraAccurateArtAsync(context, displayArtist, displayTitle, maxYear) {
                    val newArt = Gen2MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle) ?: logo
                    val alb = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
                    val sub = if (alb.isNotBlank()) "$displayArtist • $alb" else "$displayArtist • $stationTitle"
                    val updated = buildStationMetadata(displayTitle, sub, newArt, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = alb)
                    mainHandler.post {
                        if (_playing && playGeneration.get() == expectedGen && lastSegment == RadioSegmentType.Song && currentSongItem == item) {
                            notifyMetadataChanged(updated)
                        }
                    }
                }
            }
        }
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
        if (uri.scheme == Rpf3StreamingDataSource.SCHEME) {
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

    private fun readAudioDurationMs(uri: Uri): Long = getFileDurationMs(uri)

    override fun discardNeighborPrewarm() {
        prewarmGeneration.incrementAndGet()
        val stale = synchronized(prewarmLock) {
            val old = prewarmedTakeOver
            prewarmedTakeOver = null
            old
        }
        stale?.pinnedFiles?.forEach { Rpf3Archive.unpinFile(it) }
    }

    private fun clearPendingPrewarmPins() {
        val pins = pendingPrewarmPinnedFiles
        pendingPrewarmPinnedFiles = emptyList()
        for (p in pins) {
            Rpf3Archive.unpinFile(p)
        }
    }

    private fun collectAndPinNonStreamingFiles(
        uris: List<Uri>,
        extraUris: List<Uri> = emptyList()
    ): List<String> {
        val pinned = ArrayList<String>()
        for (u in uris + extraUris) {
            if (u.scheme != Rpf3StreamingDataSource.SCHEME) {
                val p = u.toString()
                Rpf3Archive.pinFile(p)
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
            prewarmed.pinnedFiles.forEach { Rpf3Archive.unpinFile(it) }
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
            snapshot.pinnedFiles.forEach { Rpf3Archive.unpinFile(it) }
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
        replacedOld?.pinnedFiles?.forEach { Rpf3Archive.unpinFile(it) }
        if (!stored) {
            snapshot.pinnedFiles.forEach { Rpf3Archive.unpinFile(it) }
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
            winUri?.scheme == Rpf3StreamingDataSource.SCHEME -> winUri to positionMs
            nextUri?.scheme == Rpf3StreamingDataSource.SCHEME -> nextUri to 0L
            else -> return
        }
        val host = targetUri.host ?: return
        val rawPath = targetUri.encodedPath?.removePrefix("/")?.removeSuffix(".wav") ?: return
        val entryName = Uri.decode(rawPath)
        val archive = Rpf3StreamRegistry.get(host) ?: return
        val index = archive.getOrBuildStreamIndex(entryName) ?: return

        val bytesPerFrame = index.outChannels.toLong() * 2L
        val samplePos = (targetSeekMs.coerceAtLeast(0L) * index.sampleRate.toLong()) / 1000L
        val targetWavByte = (44L + samplePos * bytesPerFrame)
            .coerceIn(44L, (index.totalVirtualWavBytes - 1L).coerceAtLeast(44L))
        val targetBlockIdx = Rpf3StreamingDataSource.findBlockForWavByte(index, targetWavByte)
        val cacheKey = Gen3StreamBlockCache.buildKey(
            Rpf3StreamingDataSource.SCHEME,
            archive.archiveStreamId,
            index.entryName,
            targetBlockIdx
        )
        if (Gen3StreamBlockCache.get(cacheKey) != null) return
        if (!isStillValid()) return

        var decodedPcm: ByteArray? = null
        archive.openStreamPfd()?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                if (isStillValid()) {
                    decodedPcm = archive.decodeSingleBlockToPcmBytes(index, targetBlockIdx, ch)
                }
            }
        }
        val pcm = decodedPcm ?: return
        if (pcm.isNotEmpty() && isStillValid()) {
            Gen3StreamBlockCache.put(cacheKey, CachedStreamBlock(pcm))
            GTRadioLogger.logVerbose("PREWARM") {
                "Rpf3[$stationTitle]: Pre-warmed blk=$targetBlockIdx of '${index.entryName}' (${pcm.size / 1024} KB)"
            }
        }
    }

    private fun resolvePrewarmedTakeOver(isValid: () -> Boolean): PrewarmedTakeOver? {
        val isMix = isContinuousMixStation()
        val canResumeMix = isMix && savedMixAbsMs > 0L && continuousMixItem != null && lastStoppedTimeMs > 0L
        if (canResumeMix) {
            var item = continuousMixItem ?: return null
            val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
            var absMs = savedMixAbsMs + elapsedMs
            val parts = getMixSeriesPartsFor(item)
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
                consumedPreviousSegment = true,
                usedPrefetchedItem = false
            )
        }

        val prevUris = currentSegmentUris.toList()
        val canResumeTrack = !isMix &&
            director.currentPhase != Gen3DirectorPhase.BREAK_BLOCK &&
            lastStoppedTimeMs > 0L &&
            areSegmentFilesValid(prevUris)
        if (canResumeTrack) {
            var remainingMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
            val prevDurs = prevUris.map { getFileDurationMs(it) }
            val winIdx = stoppedWindowIndex.coerceIn(0, (prevUris.size - 1).coerceAtLeast(0))
            var posMs = stoppedPositionMs.coerceAtLeast(0L)

            val markers = if (isMix && currentSongItem != null) {
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
                                consumedPreviousSegment = true,
                                usedPrefetchedItem = true
                            )
                        }
                        continue
                    }
                }

                if (isMix) {
                    return buildColdContinuousMixPrewarmSnapshot(isValid)
                }
                if (director.songQueue.isEmpty()) return null

                val event = director.pickNextEvent { song ->
                    if (song.introNames.isNotEmpty()) {
                        Rpf3ClipRef(song.archive, song.introNames[rng.nextInt(song.introNames.size)])
                    } else null
                }
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

        if (isMix) {
            return buildColdContinuousMixPrewarmSnapshot(isValid)
        }
        if (director.songQueue.isEmpty()) return null

        val event = director.pickNextEvent { song ->
            if (song.introNames.isNotEmpty()) {
                Rpf3ClipRef(song.archive, song.introNames[rng.nextInt(song.introNames.size)])
            } else null
        }
        return resolveEventForPrewarm(
            event = event,
            remainingMs = 0L,
            forceLand = true,
            isColdStart = true,
            isValid = isValid
        ).snapshot
    }

    private fun buildColdContinuousMixPrewarmSnapshot(isValid: () -> Boolean): PrewarmedTakeOver? {
        val mixEntries = getAllMixEntries()
        if (mixEntries.isEmpty()) return null
        val item = mixEntries[rng.nextInt(mixEntries.size)]
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
            consumedPreviousSegment = true,
            usedPrefetchedItem = false
        )
    }

    private data class EventPrewarmResult(
        val snapshot: PrewarmedTakeOver?,
        val remainingMs: Long
    )

    private fun resolveEventForPrewarm(
        event: Gen3BroadcastEvent<Rpf3SongItem, Rpf3ClipRef>,
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
                val introEstDur = if (!isColdStart && event.songIntroOverlay != null) {
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
                    built.pinnedFiles.forEach { Rpf3Archive.unpinFile(it) }
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
            pendingMixStartOffsetMs = 0L
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
            GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: prewarm HIT (win=${prepared.startWindowIndex}, pos=${prepared.startPositionMs}ms)")
            onReady {
                if (playGeneration.get() != currentGen) return@onReady
                val commitGen = playGeneration.incrementAndGet()
                GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: seamless prewarm commit() (gen=$commitGen)")
                val stalePins = pendingPrewarmPinnedFiles
                pendingPrewarmPinnedFiles = emptyList()
                seamlessPrepareActive = false
                seamlessPreparePrevHasStartedMix = null
                seamlessPreparePrevSavedMixAbsMs = null
                applyPrewarmedStateForCommit(prewarmed, prepared)
                for (p in stalePins) {
                    Rpf3Archive.unpinFile(p)
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
                        GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: seamless commit() (gen=$commitGen)")
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
        pendingMixStartOffsetMs = 0L
        activeDecodeJob?.cancel()
        activeDecodeJob = null
        activeCleanJob?.cancel()
        activeCleanJob = null
        activeOverlayJob?.cancel()
        activeOverlayJob = null
        activeMixMarkerLoadJob?.cancel()
        activeMixMarkerLoadJob = null
        activeMetadataJob?.cancel()
        activeMetadataJob = null
        plannedOverlayCue = null
        plannedPreRollCue = null
        player.djVoiceOverlayPlayer?.stopOverlay()
        player.resetMusicDuckGain()
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
        stop()
        stationScope.cancel()
    }

    private fun isValidWavOrAudioFile(uri: Uri?): Boolean {
        if (uri == null) return false
        if (uri.scheme == Rpf3StreamingDataSource.SCHEME) {
            return Rpf3StreamRegistry.hasValidEntry(uri)
        }
        val p = uri.path ?: return false
        val f = File(p)
        return f.exists() && f.length() > 44L
    }

    private fun areSegmentFilesValid(uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return false
        for (u in uris) {
            if (!isValidWavOrAudioFile(u)) return false
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
        GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: play() called (gen=$currentGen)")
        isStationActive = true
        _playing = true
        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

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

    private data class BuiltSongMedia(
        val uris: List<Uri>,
        val pinnedFiles: List<String>,
        val overlayCue: PlannedOverlayCue?,
        val preRollCue: PlannedPreRollCue?,
        val introCount: Int
    )

    private fun buildSongSegmentMedia(
        event: Gen3BroadcastEvent.SongEvent<Rpf3SongItem, Rpf3ClipRef>,
        mainUri: Uri?,
        markers: DjMarkerWindow?,
        skipIntroDecode: Boolean,
        pinToActive: Boolean
    ): BuiltSongMedia {
        val uris = ArrayList<Uri>()
        val pinned = ArrayList<String>()

        var djIntroUri: Uri? = null
        var djDurMs = 0L
        if (!skipIntroDecode && event.songIntroOverlay != null) {
            val u = materializeClip(event.songIntroOverlay.trackKey)
            if (u != null) {
                if (pinToActive) {
                    pinAudioUri(u)
                } else if (u.scheme != Rpf3StreamingDataSource.SCHEME) {
                    val p = u.toString()
                    pinned.add(p)
                    Rpf3Archive.pinFile(p)
                }
                djIntroUri = u
                djDurMs = getFileDurationMs(u)
            }
        }

        if (mainUri != null) {
            if (pinToActive) {
                pinAudioUri(mainUri)
            } else if (mainUri.scheme != Rpf3StreamingDataSource.SCHEME) {
                val p = mainUri.toString()
                pinned.add(p)
                Rpf3Archive.pinFile(p)
            }
        }

        val fitsInIntro = djIntroUri != null && markers != null && markers.hasIntroWindow &&
            djDurMs > 0L && djDurMs <= markers.introDurationMs
        val canPreRoll = djIntroUri != null && markers != null && markers.hasIntroWindow &&
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
                if (djIntroUri != null) uris.add(djIntroUri)
                if (mainUri != null) uris.add(mainUri)
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

        if (isContinuousMixStation() && currentSongItem != null && activeMixMarkers.isEmpty()) {
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

            if (isContinuousMixStation()) {
                prepareContinuousMixSegment()
                if (currentSegmentUris.isNotEmpty() && isValid()) {
                    val startOff = pendingMixStartOffsetMs
                    pendingMixStartOffsetMs = 0L
                    return PreparedPlayback(currentSegmentUris, 0, startOff, getFileDurationMs(currentSegmentUris[0]), null, null)
                }
                break
            }

            val event = director.pickNextEvent { song ->
                if (song.introNames.isNotEmpty()) {
                    val intro = song.introNames[rng.nextInt(song.introNames.size)]
                    Rpf3ClipRef(song.archive, intro)
                } else null
            }

            when (event) {
                is Gen3BroadcastEvent.SongEvent -> {
                    val item = event.song
                    val mainUri = resolvePlaybackUri(item.archive, item.trackName)
                    val mainDurMs = if (mainUri != null) getFileDurationMs(mainUri) else 0L
                    if (!forceLand && mainDurMs > 0L && remainingMs >= mainDurMs) {
                        remainingMs -= mainDurMs
                        GTRadioLogger.logVerbose("CATCHUP") {
                            "Rpf3[$stationTitle]: skipped elapsed '${item.trackName}' (${mainDurMs}ms) without decoding intro; rem=${remainingMs}ms"
                        }
                        continue
                    }

                    var landedSeekMs = if (forceLand && remainingMs >= mainDurMs) 0L else remainingMs.coerceAtLeast(0L)
                    val markers = item.archive.readDjMarkers(item.trackName)
                    val introEstDur = if (event.songIntroOverlay != null) {
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

    private fun resolveContinuousMixPrepared(
        elapsedMs: Long,
        isValid: () -> Boolean
    ): PreparedPlayback? {
        var item = continuousMixItem ?: return null
        var absMs = savedMixAbsMs + elapsedMs
        val parts = getMixSeriesPartsFor(item)
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
            lastDisplayedMixTrackId = -1
            lastPolledMixPosMs = -1L
        } else {
            stopMixPoller(clearMarkers = true)
        }

        return PreparedPlayback(listOf(mainUri), 0, targetMixMs, totalMixDurationMs, null, null)
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
                GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: playNext decode error", e)
            }
        }
    }

    private fun pickNextSegment(emitMetadata: Boolean = true) {
        hasPrefetchedNextSegment = false
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
                val uris = ArrayList<Uri>()
                val clips = ArrayList(event.standaloneClips)
                if (clips.isNotEmpty()) {
                    val first = clips.removeAt(0)
                    val u = resolvePlaybackUri(first.clip.archive, first.clip.trackKey)
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
                introItemCount = 0
                pendingBreakClips.clear()
                pendingBreakClips.addAll(clips)
            }
            is Gen3BroadcastEvent.InterludeEvent -> {
                val ref = event.clip
                val u = resolvePlaybackUri(ref.archive, ref.trackKey)
                if (u != null) {
                    pinAudioUri(u)
                    currentSegmentUris = listOf(u)
                } else {
                    currentSegmentUris = emptyList()
                }
                currentSongItem = null
                introItemCount = 0
                lastSegment = event.segmentType
                pendingBreakClips.clear()
            }
        }
    }

    private fun prepareContinuousMixSegment() {
        val mixEntries = getAllMixEntries()
        if (mixEntries.isEmpty()) return
        val item: Rpf3SongItem
        val coldStart = !hasStartedContinuousMix
        if (coldStart) {
            item = mixEntries[rng.nextInt(mixEntries.size)]
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

        lookAheadJob = stationScope.launch {
            val markers = withContext(StationDispatchers.ioDispatcher) {
                songItem?.let { it.archive.readDjMarkers(it.trackName) }
            }
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
                                        val djDur = if (voiceUri != null) getFileDurationMs(voiceUri) else 0L
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
                        "Rpf3[$stationTitle]: Look-ahead prefetched next song '${item.trackName}' (intro=${event.songIntroOverlay != null})"
                    }
                } else {
                    built.pinnedFiles.forEach { Rpf3Archive.unpinFile(it) }
                }
            }
            is Gen3BroadcastEvent.BreakBlockEvent -> {
                val clips = ArrayList(event.standaloneClips)
                val uris = ArrayList<Uri>()
                val pinned = ArrayList<String>()
                if (clips.isNotEmpty()) {
                    val first = clips.removeAt(0)
                    val u = resolvePlaybackUri(first.clip.archive, first.clip.trackKey)
                    if (u != null) {
                        uris.add(u)
                        if (u.scheme != Rpf3StreamingDataSource.SCHEME) {
                            val p = u.toString()
                            pinned.add(p)
                            Rpf3Archive.pinFile(p)
                        }
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
                val u = resolvePlaybackUri(ref.archive, ref.trackKey)
                if (u != null && _playing && playGeneration.get() == expectedGen) {
                    val pinned = if (u.scheme != Rpf3StreamingDataSource.SCHEME) {
                        val p = u.toString()
                        Rpf3Archive.pinFile(p)
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
                        "Rpf3[$stationTitle]: Look-ahead prefetched interlude '${ref.trackKey}' (${event.role}, dryEligible=${event.isDryOverlayEligible})"
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
                GTRadioLogger.log("Rpf3RadioStation[$stationTitle]: startPlayback aborted: generation mismatch or not playing (gen=$expectedGen, cur=${playGeneration.get()})")
            } else {
                if (activeListener != null) {
                    player.exoPlayer.removeListener(activeListener!!)
                    activeListener = null
                }

                player.exoPlayer.clearMediaItems()
                val isSinglePartMix = isMixItem(currentSongItem) && getMixSeriesPartsFor(currentSongItem!!).size <= 1
                player.repeatMode = if (isSinglePartMix) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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
                val uri = resolvePlaybackUri(nextClip.clip.archive, nextClip.clip.trackKey) ?: return@launch
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
                GTRadioLogger.e("Rpf3RadioStation[$stationTitle]: stageAndPlayRemainingBreakClip error", e)
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
            if (activeMixMarkerLoadJob?.isActive != true) {
                val expectedGen = playGeneration.get()
                activeMixMarkerLoadJob = stationScope.launch(StationDispatchers.ioDispatcher) {
                    val markers = item.archive.readTrackMarkers(item.trackName)
                    withContext(Dispatchers.Main.immediate) {
                        if (_playing && playGeneration.get() == expectedGen && currentSongItem == item && markers.isNotEmpty()) {
                            activeMixMarkers = markers
                            updateMixTrackMetadata()
                        }
                    }
                }
            }
            return
        }
        if (!isMetadataEnabled()) {
            setStationOnlyMetadata()
            return
        }
        val currentMs = exo.currentPosition.coerceAtLeast(0L).let {
            if (exo.duration > 0) it.coerceAtMost(exo.duration) else it
        }
        if (lastPolledMixPosMs > 10_000L && currentMs < 2_000L) {
            lastDisplayedMixTrackId = -1
        }
        lastPolledMixPosMs = currentMs

        var resolvedTrackId = -1
        for (m in activeMixMarkers) {
            if (m.timeOffsetMs <= currentMs) {
                resolvedTrackId = m.trackId
            } else {
                break
            }
        }

        if (resolvedTrackId == lastDisplayedMixTrackId) return

        val meta = if (resolvedTrackId > 0) Rpf3MetadataRepository.getTrackByTrackIdIfLoaded(resolvedTrackId) else null
        if (meta != null) {
            lastDisplayedMixTrackId = resolvedTrackId
            GTRadioLogger.logVerbose("TRACKID") {
                "Rpf3RadioStation[$stationTitle]: TRACKID transition -> $resolvedTrackId at ${currentMs}ms"
            }
            val displayTitle = meta.title
            val displayArtist = meta.artist
            val maxYear = 2009
            val expectedGen = playGeneration.get()
            val currentTid = resolvedTrackId
            val albumArtEnabled = isAlbumArtEnabled()
            activeMetadataJob?.cancel()
            activeMetadataJob = stationScope.launch(StationDispatchers.ioDispatcher) {
                val logo = ensureStationLogoLoadedOnIo()
                val cachedArt = if (albumArtEnabled) {
                    Gen2MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle)
                } else null
                val eraAlbum = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
                val subtitle = if (eraAlbum.isNotBlank()) "$displayArtist • $eraAlbum" else "$displayArtist • $stationTitle"
                val builtMeta = buildStationMetadata(displayTitle, subtitle, cachedArt ?: logo, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = eraAlbum)
                withContext(Dispatchers.Main.immediate) {
                    if (_playing && playGeneration.get() == expectedGen && lastDisplayedMixTrackId == currentTid) {
                        notifyMetadataChanged(builtMeta)
                    }
                }
                if (albumArtEnabled && cachedArt == null && _playing && playGeneration.get() == expectedGen && lastDisplayedMixTrackId == currentTid) {
                    Gen2MetadataRepository.fetchEraAccurateArtAsync(context, displayArtist, displayTitle, maxYear) {
                        val newArt = Gen2MetadataRepository.getCachedAlbumArt(context, displayArtist, displayTitle) ?: logo
                        val alb = Gen2MetadataRepository.getCachedAlbumName(context, displayArtist, displayTitle) ?: ""
                        val sub = if (alb.isNotBlank()) "$displayArtist • $alb" else "$displayArtist • $stationTitle"
                        val updatedMeta = buildStationMetadata(displayTitle, sub, newArt, isSongOrShow = true, cleanArtist = displayArtist, cleanAlbum = alb)
                        mainHandler.post {
                            if (_playing && playGeneration.get() == expectedGen && lastDisplayedMixTrackId == currentTid) {
                                notifyMetadataChanged(updatedMeta)
                            }
                        }
                    }
                }
            }
        } else if (resolvedTrackId > 0 && !Rpf3MetadataRepository.isLoaded()) {
            val expectedGen = playGeneration.get()
            activeMetadataJob?.cancel()
            activeMetadataJob = stationScope.launch(StationDispatchers.ioDispatcher) {
                Rpf3MetadataRepository.ensureLoaded(context)
                withContext(Dispatchers.Main.immediate) {
                    if (_playing && playGeneration.get() == expectedGen) {
                        updateMixTrackMetadata()
                    }
                }
            }
        } else {
            if (lastDisplayedMixTrackId != resolvedTrackId) {
                lastDisplayedMixTrackId = resolvedTrackId
                GTRadioLogger.logVerbose("TRACKID") {
                    "Rpf3RadioStation[$stationTitle]: TRACKID transition -> $resolvedTrackId at ${currentMs}ms"
                }
                setStationOnlyMetadata()
            }
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
                val uri = resolvePlaybackUri(targetItem.archive, targetItem.trackName)
                if (token != cleanPlayGeneration.get()) return@launch
                if (uri == null) {
                    GTRadioLogger.log("Rpf3RadioStation: Failed to build clean track stream for '${targetItem.trackName}'")
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
