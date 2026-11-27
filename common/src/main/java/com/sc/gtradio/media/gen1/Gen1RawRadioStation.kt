package com.sc.gtradio.media.gen1
import com.sc.gtradio.media.core.*


import com.sc.gtradio.media.GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.artwork.AlbumArtDownloader
import com.sc.gtradio.media.artwork.AlbumHintRepository
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class Gen1RawRadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaItem,
    private val fileName: String,
    private val sourceUri: Uri,
    private val isXorAdf: Boolean,
    private val stationInfo: Gen1StationInfo?,
    private val player: GTRadioPlayer,
    private val context: Context,
    override var adsEnabled: Boolean = true,
    override var weatherChatterEnabled: Boolean = false,
    override var newsReportsEnabled: Boolean = false
) : RadioStation {

    override var onMetadataChanged: ((MediaMetadata) -> Unit)? = null
    override var stationName: String = (mediaItem.mediaMetadata.title ?: stationInfo?.displayName ?: fileName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.mediaMetadata.subtitle ?: if (isXorAdf) "GTA Vice City" else "GTA III").toString()
    @Volatile
    private var cachedStationLogo: Bitmap? = null
    private val gameCode: Int = if (isXorAdf) 1 else 2
    private val rng = Random()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activePrepareJob: Job? = null
    private var seamlessPrepareJob: Job? = null
    private var activePollerJob: Job? = null
    private var activeMetadataJob: Job? = null
    private val playGeneration = AtomicInteger(0)

    override val supportsSeamlessPrepare: Boolean get() = true

    private var _playing = false
    private var activeCueKey: String = "__INIT__"
    private var currentSegmentType: RadioSegmentType = RadioSegmentType.None
    private var _currentMetadata: MediaMetadata = buildStationOnlyMetadata(getCachedStationLogoOnly(), RadioSegmentType.None)
    override val metadata: MediaMetadata
        get() = _currentMetadata

    private var cachedDurationMs: Long = -1L

    init {
        stationScope.launch(StationDispatchers.ioDispatcher) {
            Gen1MetadataRepository.ensureLoaded(context)
            AlbumHintRepository.ensureLoaded(context)
            ensureStationLogoLoadedOnIo()
        }
    }

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

    private fun isMetadataEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("show_song_metadata_enabled", true)
    }

    private fun isAlbumArtEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return if (prefs.contains("download_album_art_enabled")) {
            prefs.getBoolean("download_album_art_enabled", true)
        } else {
            prefs.getBoolean("fetch_album_art_enabled", true)
        }
    }

    private fun notifyMetadataChanged(meta: MediaMetadata) {
        _currentMetadata = meta
        onMetadataChanged?.invoke(meta)
    }

    private fun updateDynamicMetadataFromPosition() {
        if (!isMetadataEnabled()) {
            if (activeCueKey != "__DISABLED__") {
                activeCueKey = "__DISABLED__"
                currentSegmentType = RadioSegmentType.None
                val expectedGen = playGeneration.get()
                activeMetadataJob?.cancel()
                activeMetadataJob = stationScope.launch(StationDispatchers.ioDispatcher) {
                    val logo = ensureStationLogoLoadedOnIo()
                    val disabledMeta = buildStationOnlyMetadata(logo, RadioSegmentType.None)
                    withContext(Dispatchers.Main.immediate) {
                        if (playGeneration.get() == expectedGen && activeCueKey == "__DISABLED__") {
                            notifyMetadataChanged(disabledMeta)
                        }
                    }
                }
            }
            return
        }

        val posMs = player.getCurrentTrackPosition()
        if (!Gen1MetadataRepository.isLoaded() || cachedDurationMs <= 0L) {
            val expectedGen = playGeneration.get()
            stationScope.launch(StationDispatchers.ioDispatcher) {
                Gen1MetadataRepository.ensureLoaded(context)
                estimateDurationMs()
                withContext(Dispatchers.Main.immediate) {
                    if (_playing && playGeneration.get() == expectedGen) {
                        updateDynamicMetadataFromPosition()
                    }
                }
            }
            return
        }

        val durMs = cachedDurationMs.coerceAtLeast(1000L)
        val effectiveMs = if (posMs >= 0L) (posMs % durMs) else (System.currentTimeMillis() % durMs)
        val posSec = (effectiveMs / 1000L).toInt()

        val cue = Gen1MetadataRepository.findCueAt(fileName, gameCode, posSec)
        val newKey = if (cue != null) {
            "${cue.cueType.name}|${cue.startSec}|${cue.endSec}|${cue.title}|${cue.artist}"
        } else {
            "__HOST__"
        }

        if (newKey != activeCueKey) {
            activeCueKey = newKey
            val expectedGen = playGeneration.get()
            val expectedKey = newKey
            val albumArtEnabled = isAlbumArtEnabled()
            GTRadioLogger.logVerbose("GEN1") {
                if (cue != null) {
                    "Cue change [type=${cue.cueType}, pos=${effectiveMs}ms, window=${cue.startSec}s..${cue.endSec}s]: '${cue.title}' by '${cue.artist}'"
                } else {
                    "Cue change [pos=${effectiveMs}ms]: Station Host / DJ chatter window"
                }
            }

            activeMetadataJob?.cancel()
            activeMetadataJob = stationScope.launch(StationDispatchers.ioDispatcher) {
                if (!_playing || playGeneration.get() != expectedGen || activeCueKey != expectedKey) return@launch
                val logo = ensureStationLogoLoadedOnIo()

                if (cue == null) {
                    val segType = if (!isXorAdf && stationInfo != null && stationInfo.hostName.isNotBlank()) {
                        RadioSegmentType.DJChatter
                    } else {
                        RadioSegmentType.None
                    }
                    val built = if (segType == RadioSegmentType.DJChatter && stationInfo != null) {
                        buildSegmentMetadata(
                            title = stationTitle,
                            subtitle = "Hosted by ${stationInfo.hostName} • ${stationInfo.genre}",
                            artist = "DJ: ${stationInfo.hostName}",
                            album = stationInfo.genre,
                            art = logo,
                            isSong = true,
                            segmentType = segType,
                            cueType = null
                        )
                    } else {
                        buildStationOnlyMetadata(logo, segType)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (_playing && playGeneration.get() == expectedGen && activeCueKey == expectedKey) {
                            currentSegmentType = segType
                            notifyMetadataChanged(built)
                        }
                    }
                    return@launch
                }

                when (cue.cueType) {
                    Gen1CueType.SONG -> {
                        val segType = RadioSegmentType.Song
                        AlbumHintRepository.ensureLoaded(context)
                        val hint = AlbumHintRepository.findHint(context, cue.artist, cue.title)
                        val officialAlbum = hint?.officialAlbum?.ifBlank { null }
                            ?: Gen1MetadataRepository.getCachedAlbumName(context, cue.artist, cue.title)?.ifBlank { null }
                            ?: cue.album
                        val noArt = hint?.status == "NO_ART"
                        val cachedArt = if (albumArtEnabled && !noArt) {
                            Gen1MetadataRepository.getCachedAlbumArt(cue.artist, cue.title)
                        } else null
                        val subtitle = if (officialAlbum.isNotBlank()) {
                            "${cue.artist} • $officialAlbum"
                        } else {
                            "${cue.artist} • $stationTitle"
                        }
                        val initialMeta = buildSegmentMetadata(
                            title = cue.title,
                            subtitle = subtitle,
                            artist = cue.artist,
                            album = officialAlbum,
                            art = cachedArt ?: logo,
                            isSong = true,
                            segmentType = segType,
                            cueType = cue.cueType
                        )
                        withContext(Dispatchers.Main.immediate) {
                            if (_playing && playGeneration.get() == expectedGen && activeCueKey == expectedKey) {
                                currentSegmentType = segType
                                notifyMetadataChanged(initialMeta)
                            }
                        }

                        if (cachedArt == null && albumArtEnabled && !noArt) {
                            val stationMax = stationInfo?.maxYear ?: if (isXorAdf) 1986 else 2001
                            val maxYear = if (cue.year in 1950..2025) maxOf(stationMax, cue.year) else stationMax
                            Gen1MetadataRepository.fetchAlbumArtAsync(cue.artist, cue.title, maxYear) { fetchedBmp ->
                                if (_playing && playGeneration.get() == expectedGen && activeCueKey == expectedKey && fetchedBmp != null) {
                                    stationScope.launch(StationDispatchers.ioDispatcher) {
                                        if (!_playing || playGeneration.get() != expectedGen || activeCueKey != expectedKey) return@launch
                                        val resolvedAlbum = hint?.officialAlbum?.ifBlank { null }
                                            ?: Gen1MetadataRepository.getCachedAlbumName(context, cue.artist, cue.title)?.ifBlank { null }
                                            ?: officialAlbum
                                        val resolvedSubtitle = if (resolvedAlbum.isNotBlank()) {
                                            "${cue.artist} • $resolvedAlbum"
                                        } else {
                                            "${cue.artist} • $stationTitle"
                                        }
                                        val updatedMeta = buildSegmentMetadata(
                                            title = cue.title,
                                            subtitle = resolvedSubtitle,
                                            artist = cue.artist,
                                            album = resolvedAlbum,
                                            art = fetchedBmp,
                                            isSong = true,
                                            segmentType = segType,
                                            cueType = cue.cueType
                                        )
                                        withContext(Dispatchers.Main.immediate) {
                                            if (_playing && playGeneration.get() == expectedGen && activeCueKey == expectedKey) {
                                                notifyMetadataChanged(updatedMeta)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Gen1CueType.TALK -> {
                        val segType = cue.cueType.toSegmentType()
                        val displayArtist = cue.artist.ifBlank { stationTitle }
                        val subtitle = if (displayArtist != stationTitle) {
                            "$displayArtist • $stationTitle"
                        } else {
                            stationTitle
                        }
                        val talkMeta = buildSegmentMetadata(
                            title = cue.title,
                            subtitle = subtitle,
                            artist = displayArtist,
                            album = "",
                            art = logo,
                            isSong = true,
                            segmentType = segType,
                            cueType = cue.cueType
                        )
                        withContext(Dispatchers.Main.immediate) {
                            if (_playing && playGeneration.get() == expectedGen && activeCueKey == expectedKey) {
                                currentSegmentType = segType
                                notifyMetadataChanged(talkMeta)
                            }
                        }
                    }

                    Gen1CueType.AD, Gen1CueType.DJ, Gen1CueType.ID -> {
                        val segType = cue.cueType.toSegmentType()
                        val nonSongMeta = buildStationOnlyMetadata(logo, segType, cue.cueType)
                        withContext(Dispatchers.Main.immediate) {
                            if (_playing && playGeneration.get() == expectedGen && activeCueKey == expectedKey) {
                                currentSegmentType = segType
                                notifyMetadataChanged(nonSongMeta)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildStationOnlyMetadata(
        art: Bitmap?,
        segmentType: RadioSegmentType,
        cueType: Gen1CueType? = null
    ): MediaMetadata {
        return buildSegmentMetadata(
            title = stationTitle,
            subtitle = "",
            artist = "",
            album = "",
            art = art,
            isSong = false,
            segmentType = segmentType,
            cueType = cueType
        )
    }

    private fun buildSegmentMetadata(
        title: String,
        subtitle: String,
        artist: String,
        album: String,
        art: Bitmap?,
        isSong: Boolean,
        segmentType: RadioSegmentType,
        cueType: Gen1CueType?
    ): MediaMetadata {
        val extras = Bundle().apply {
            putString("segment_type", segmentType.name)
            putInt("segment_type_id", segmentType.value)
            if (cueType != null) {
                putString("cue_type", cueType.name)
            }
        }
        return buildStationMediaMetadata(
            mediaId = mediaItem.mediaId,
            title = title,
            subtitle = subtitle,
            artist = artist.ifBlank { subtitle },
            albumArtist = album,
            stationTitle = stationTitle,
            groupTitle = groupTitle,
            art = art,
            isSong = isSong,
            durationMs = -1L,
            extras = extras
        )
    }

    private fun estimateDurationMs(): Long {
        if (cachedDurationMs > 0L) return cachedDurationMs
        var durMs = 1_800_000L
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                val fileSize = pfd.statSize
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (!isXorAdf) {
                        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                        ch.read(hdr, 0L)
                        val byteRate = hdr.getInt(28)
                        if (byteRate > 0 && fileSize > 44L) {
                            durMs = ((fileSize - 44L) * 1000L) / byteRate.toLong()
                        }
                    } else {
                        val hdr = ByteBuffer.allocate(16)
                        ch.read(hdr, 0L)
                        val b1 = (hdr.get(1).toInt() xor 0x22) and 0xFF
                        val b2 = (hdr.get(2).toInt() xor 0x22) and 0xFF
                        val bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
                        val brIdx = (b2 shr 4) and 0x0F
                        val kbps = if ((b1 and 0xE0) == 0xE0 && bitrates[brIdx] > 0) bitrates[brIdx] else 128
                        if (fileSize > 0L) {
                            durMs = (fileSize * 8L) / kbps.toLong()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        cachedDurationMs = durMs
        return durMs
    }

    private var isStationActive = false
    private var lastStoppedTimeMs: Long = 0L
    private var stoppedPositionMs: Long = 0L

    private data class PreparedGen1Playback(
        val mediaSource: ProgressiveMediaSource,
        val liveOffsetMs: Long
    )

    private fun buildMediaSource(): ProgressiveMediaSource {
        val dataSourceFactory = AdfXorDataSource.Factory(
            context = context,
            xorMask = if (isXorAdf) 0x22 else 0x00
        )
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
        if (isXorAdf) {
            mediaItemBuilder.setMimeType(MimeTypes.AUDIO_MPEG)
        } else {
            mediaItemBuilder.setMimeType(MimeTypes.AUDIO_WAV)
        }

        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(mediaItemBuilder.build())
    }

    private fun computeLiveOffsetMs(durMs: Long): Long {
        return if (durMs > 0L) {
            if (lastStoppedTimeMs > 0L) {
                val elapsedMs = (System.currentTimeMillis() - lastStoppedTimeMs).coerceAtLeast(0L)
                (stoppedPositionMs + elapsedMs) % durMs
            } else {
                (System.currentTimeMillis() % durMs).coerceIn(0L, (durMs - 5_000L).coerceAtLeast(0L))
            }
        } else {
            0L
        }
    }

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
        val prepToken = playGeneration.incrementAndGet()
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            try {
                Gen1MetadataRepository.ensureLoaded(context)
                AlbumHintRepository.ensureLoaded(context)
                ensureStationLogoLoadedOnIo()
                val mediaSource = buildMediaSource()
                val durMs = estimateDurationMs()
                if (prepToken != playGeneration.get() || !isActive) return@launch
                val liveOffsetMs = computeLiveOffsetMs(durMs)
                val prepared = PreparedGen1Playback(mediaSource, liveOffsetMs)
                withContext(Dispatchers.Main.immediate) {
                    if (prepToken != playGeneration.get()) return@withContext
                    seamlessPrepareJob = null
                    onReady {
                        if (prepToken != playGeneration.get()) return@onReady
                        val commitToken = playGeneration.incrementAndGet()
                        GTRadioLogger.log("Gen1RawRadioStation[$stationTitle]: seamless commit() (file=$fileName, isXorAdf=$isXorAdf, token=$commitToken)")
                        isStationActive = true
                        _playing = true
                        activeCueKey = "__INIT__"
                        currentSegmentType = RadioSegmentType.None
                        activePrepareJob?.cancel()
                        activePrepareJob = null
                        activePollerJob?.cancel()
                        activePollerJob = null
                        activeMetadataJob?.cancel()
                        activeMetadataJob = null

                        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
                        player.repeatMode = Player.REPEAT_MODE_ONE
                        player.playWhenReady = false
                        player.setMediaSource(prepared.mediaSource)
                        if (prepared.liveOffsetMs > 0L) {
                            player.exoPlayer.seekTo(0, prepared.liveOffsetMs)
                        }
                        player.prepare()
                        player.playInternal()
                        updateDynamicMetadataFromPosition()
                        startPoller(commitToken)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                GTRadioLogger.e("Gen1RawRadioStation[$stationTitle]: seamless prepare error", t)
                withContext(Dispatchers.Main.immediate) {
                    if (prepToken == playGeneration.get()) {
                        seamlessPrepareJob = null
                        onFailed()
                    }
                }
            }
        }
    }

    override fun stop() {
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
        if (!isStationActive) {
            playGeneration.incrementAndGet()
            return
        }
        isStationActive = false
        _playing = false
        currentSegmentType = RadioSegmentType.None
        playGeneration.incrementAndGet()
        activePrepareJob?.cancel()
        activePrepareJob = null
        activePollerJob?.cancel()
        activePollerJob = null
        activeMetadataJob?.cancel()
        activeMetadataJob = null
        lastStoppedTimeMs = System.currentTimeMillis()
        stoppedPositionMs = player.exoPlayer.currentPosition.coerceAtLeast(0L)
        player.stopInternal()
    }

    override fun release() {
        stop()
        stationScope.cancel()
    }

    override fun play() {
        seamlessPrepareJob?.cancel()
        seamlessPrepareJob = null
        val token = playGeneration.incrementAndGet()
        GTRadioLogger.log("Gen1RawRadioStation[$stationTitle]: play() called (file=$fileName, isXorAdf=$isXorAdf, token=$token)")
        isStationActive = true
        _playing = true
        activeCueKey = "__INIT__"
        currentSegmentType = RadioSegmentType.None
        activePrepareJob?.cancel()
        activePrepareJob = null
        activePollerJob?.cancel()
        activePollerJob = null
        activeMetadataJob?.cancel()
        activeMetadataJob = null

        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.playWhenReady = false

        val mediaSource = buildMediaSource()

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playInternal()

        activePrepareJob = stationScope.launch(StationDispatchers.ioDispatcher) {
            Gen1MetadataRepository.ensureLoaded(context)
            AlbumHintRepository.ensureLoaded(context)
            ensureStationLogoLoadedOnIo()
            val durMs = estimateDurationMs()
            if (token != playGeneration.get() || !isActive || !_playing) return@launch
            val liveOffsetMs = computeLiveOffsetMs(durMs)

            withContext(Dispatchers.Main.immediate) {
                if (token == playGeneration.get() && _playing) {
                    if (liveOffsetMs > 0L) {
                        player.exoPlayer.seekTo(0, liveOffsetMs)
                    }
                    updateDynamicMetadataFromPosition()
                    startPoller(token)
                }
            }
        }
    }

    private fun startPoller(token: Int) {
        activePollerJob?.cancel()
        activePollerJob = stationScope.launch {
            while (isActive && _playing && token == playGeneration.get()) {
                delay(1000L)
                if (token == playGeneration.get() && _playing) {
                    updateDynamicMetadataFromPosition()
                }
            }
        }
    }
}
