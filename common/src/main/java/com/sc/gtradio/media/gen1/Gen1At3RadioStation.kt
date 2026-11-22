package com.sc.gtradio.media.gen1
import com.sc.gtradio.media.core.*


import com.sc.gtradio.media.GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger

@UnstableApi
class Gen1At3RadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaItem,
    private val archive: At3AudioArchive,
    private val stationInfo: Gen1StationInfo?,
    private val isVcs: Boolean,
    private val player: GTRadioPlayer,
    private val context: Context,
    override var adsEnabled: Boolean = true,
    override var weatherChatterEnabled: Boolean = false,
    override var newsReportsEnabled: Boolean = false
) : RadioStation {

    override var onMetadataChanged: ((MediaMetadata) -> Unit)? = null
    override var stationName: String = (mediaItem.mediaMetadata.title ?: stationInfo?.displayName ?: archive.fileName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.mediaMetadata.subtitle ?: if (isVcs) "GTA: Vice City Stories" else "GTA: Liberty City Stories").toString()
    private val stationLogo: Bitmap? by lazy { mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.getCachedBitmap(it, 512) ?: DynamicThemeManager.loadBitmap(context, it, 512) } }
    private val gameCode: Int = if (isVcs) 4 else 3

    init {
        Gen1MetadataRepository.ensureLoaded(context)
    }

    private val stationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activePollerJob: Job? = null

    private var _playing = false
    private var currentChunkStartFrame = 0
    private var nextChunkStartFrame = 0
    private var activeListener: Player.Listener? = null
    private var activeCueKey: String = "__INIT__"
    private var _currentMetadata: MediaMetadata = buildMetadata(null, null)
    override val metadata: MediaMetadata
        get() = _currentMetadata

    private fun isMetadataEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("show_song_metadata_enabled", true)
    }

    private fun isAlbumArtEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("fetch_album_art_enabled", true)
    }

    private fun updateDynamicMetadataFromPosition() {
        if (!isMetadataEnabled()) {
            if (activeCueKey != "__DISABLED__") {
                activeCueKey = "__DISABLED__"
                _currentMetadata = buildMetadata(null, null)
                onMetadataChanged?.invoke(_currentMetadata)
            }
            return
        }

        val samplesPerFrame = if (archive.isAtrac3Plus) 2048L else 1024L
        val chunkStartMs = (currentChunkStartFrame.toLong() * samplesPerFrame * 1000L) / archive.sampleRate.toLong().coerceAtLeast(1L)
        val trackPosMs = player.getCurrentTrackPosition().coerceAtLeast(0L)
        val durMs = archive.durationMs.coerceAtLeast(1000L)
        val posSec = (((chunkStartMs + trackPosMs) % durMs) / 1000L).toInt()

        val cue = Gen1MetadataRepository.findCueAt(archive.fileName, gameCode, posSec)
        val newKey = if (cue != null) "${cue.title}|${cue.artist}" else "__HOST__"

        if (newKey != activeCueKey) {
            activeCueKey = newKey
            val totalPosMs = (chunkStartMs + trackPosMs) % durMs
            GTRadioLogger.logVerbose("GEN1") {
                if (cue != null) {
                    "Cue change [pos=${totalPosMs}ms, window=${cue.startSec}s..${cue.endSec}s]: '${cue.title}' by '${cue.artist}'"
                } else {
                    "Cue change [pos=${totalPosMs}ms]: Station Host / DJ chatter window"
                }
            }
            val cachedArt = if (cue != null && isAlbumArtEnabled()) {
                Gen1MetadataRepository.getCachedAlbumArt(cue.artist, cue.title)
            } else null

            _currentMetadata = buildMetadata(cue, cachedArt)
            onMetadataChanged?.invoke(_currentMetadata)

            if (cue != null && cachedArt == null && isAlbumArtEnabled()) {
                val maxYear = stationInfo?.maxYear ?: if (isVcs) 1984 else 1998
                val expectedKey = newKey
                Gen1MetadataRepository.fetchAlbumArtAsync(cue.artist, cue.title, maxYear) { fetchedBmp ->
                    if (_playing && activeCueKey == expectedKey && fetchedBmp != null) {
                        _currentMetadata = buildMetadata(cue, fetchedBmp)
                        onMetadataChanged?.invoke(_currentMetadata)
                    }
                }
            }
        }
    }

    private fun buildMetadata(cue: Gen1TrackCue?, customArt: Bitmap?): MediaMetadata {
        val showMeta = isMetadataEnabled()
        val displayTitle: String
        val displaySubtitle: String
        val cleanArtist: String
        val cleanAlbum: String
        val isSong: Boolean

        if (showMeta && cue != null) {
            displayTitle = cue.title
            displaySubtitle = "${cue.artist} • $stationTitle"
            cleanArtist = cue.artist
            cleanAlbum = stationTitle
            isSong = true
        } else if (showMeta && stationInfo != null && stationInfo.hostName.isNotBlank()) {
            displayTitle = stationTitle
            displaySubtitle = "Hosted by ${stationInfo.hostName} • ${stationInfo.genre}"
            cleanArtist = "DJ: ${stationInfo.hostName}"
            cleanAlbum = stationInfo.genre
            isSong = true
        } else {
            displayTitle = stationTitle
            displaySubtitle = groupTitle
            cleanArtist = groupTitle
            cleanAlbum = groupTitle
            isSong = false
        }

        val artToUse = customArt ?: stationLogo

        return buildStationMediaMetadata(
            mediaId = mediaItem.mediaId,
            title = displayTitle,
            subtitle = displaySubtitle,
            artist = cleanArtist,
            albumArtist = cleanAlbum,
            stationTitle = stationTitle,
            groupTitle = groupTitle,
            art = artToUse,
            isSong = isSong,
            durationMs = -1L
        )
    }

    private var isStationActive = false

    override fun stop() {
        if (!isStationActive) return
        isStationActive = false
        _playing = false
        activePollerJob?.cancel()
        activePollerJob = null
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
        GTRadioLogger.log("Gen1At3RadioStation[$stationTitle]: play() called (file=${archive.fileName})")
        isStationActive = true
        archive.ensureHeaderParsed()
        _playing = true
        activeCueKey = "__INIT__"
        activePollerJob?.cancel()
        activePollerJob = null

        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

        val durMs = archive.durationMs
        val liveMs = System.currentTimeMillis() % durMs
        val samplesPerFrame = if (archive.isAtrac3Plus) 2048L else 1024L
        val liveFrame = ((liveMs * archive.sampleRate.toLong()) / (samplesPerFrame * 1000L)).toInt()
            .coerceIn(0, (archive.totalFrames - 1).coerceAtLeast(0))

        playChunkAtFrame(liveFrame)
        activePollerJob = stationScope.launch {
            while (isActive && _playing) {
                delay(1000L)
                if (_playing) {
                    updateDynamicMetadataFromPosition()
                }
            }
        }
    }

    private fun playChunkAtFrame(startFrame: Int) {
        if (!_playing) return
        currentChunkStartFrame = startFrame
        val uri = archive.materializeChunkUri(startFrame, At3AudioArchive.CHUNK_FRAMES) ?: return
        val afterChunk = startFrame + At3AudioArchive.CHUNK_FRAMES
        nextChunkStartFrame = if (afterChunk < archive.totalFrames) afterChunk else 0
        startPlayback(uri)
        updateDynamicMetadataFromPosition()
    }

    private fun startPlayback(uri: Uri) {
        if (activeListener != null) {
            player.exoPlayer.removeListener(activeListener!!)
            activeListener = null
        }

        val factory = ProgressiveMediaSource.Factory(
            DefaultDataSourceFactory(context, Util.getUserAgent(context, context.packageName))
        )
        val source = factory.createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(source)

        activeListener = object : Player.Listener {
            private var handled = false
            override fun onPlaybackStateChanged(state: Int) {
                if (!handled && state == Player.STATE_ENDED) {
                    handled = true
                    player.exoPlayer.removeListener(this)
                    playChunkAtFrame(nextChunkStartFrame)
                }
            }
        }
        player.exoPlayer.addListener(activeListener!!)
        player.prepare()
        player.playInternal()
    }
}
