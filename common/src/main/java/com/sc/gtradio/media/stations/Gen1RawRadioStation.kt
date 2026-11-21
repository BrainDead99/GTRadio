package com.sc.gtradio.media.stations

import com.sc.gtradio.media.GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

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
    private val stationLogo: Bitmap? = mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.loadBitmap(context, it, 512) }
    private val gameCode: Int = if (isXorAdf) 1 else 2
    private val rng = Random()

    private var _playing = false
    private var activeCueKey: String = "__INIT__"
    private var _currentMetadata: MediaMetadata = buildMetadata(null, null)
    override val metadata: MediaMetadata
        get() = _currentMetadata

    private var cachedDurationMs: Long = -1L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val metadataTickRunnable = object : Runnable {
        override fun run() {
            if (!_playing) return
            updateDynamicMetadataFromPosition()
            mainHandler.postDelayed(this, 1000L)
        }
    }

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

        val durMs = estimateDurationMs().coerceAtLeast(1000L)
        val posMs = player.getCurrentTrackPosition()
        val effectiveMs = if (posMs >= 0L) (posMs % durMs) else (System.currentTimeMillis() % durMs)
        val posSec = (effectiveMs / 1000L).toInt()

        val cue = Gen1MetadataRepository.findCueAt(fileName, gameCode, posSec)
        val newKey = if (cue != null) "${cue.title}|${cue.artist}" else "__HOST__"

        if (newKey != activeCueKey) {
            activeCueKey = newKey
            GTRadioLogger.logVerbose("GEN1") {
                if (cue != null) {
                    "Cue change [pos=${effectiveMs}ms, window=${cue.startSec}s..${cue.endSec}s]: '${cue.title}' by '${cue.artist}'"
                } else {
                    "Cue change [pos=${effectiveMs}ms]: Station Host / DJ chatter window"
                }
            }
            val cachedArt = if (cue != null && isAlbumArtEnabled()) {
                Gen1MetadataRepository.getCachedAlbumArt(cue.artist, cue.title)
            } else null

            _currentMetadata = buildMetadata(cue, cachedArt)
            onMetadataChanged?.invoke(_currentMetadata)

            if (cue != null && cachedArt == null && isAlbumArtEnabled()) {
                val maxYear = stationInfo?.maxYear ?: if (isXorAdf) 1986 else 2001
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

    override fun stop() {
        _playing = false
        mainHandler.removeCallbacks(metadataTickRunnable)
        player.stopInternal()
    }

    override fun play() {
        GTRadioLogger.log("Gen1RawRadioStation[$stationTitle]: play() called (file=$fileName, isXorAdf=$isXorAdf)")
        _playing = true
        activeCueKey = "__INIT__"
        mainHandler.removeCallbacks(metadataTickRunnable)

        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.playWhenReady = false

        val durMs = estimateDurationMs()
        val liveOffsetMs = if (durMs > 60_000L) {
            (System.currentTimeMillis() % durMs).coerceIn(0L, durMs - 5_000L)
        } else {
            rng.nextInt(30_000).toLong()
        }

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

        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(mediaItemBuilder.build())

        player.setMediaSource(mediaSource)
        if (liveOffsetMs > 0L) {
            player.seekTo(0, liveOffsetMs)
        }
        player.prepare()
        player.playInternal()

        updateDynamicMetadataFromPosition()
        mainHandler.postDelayed(metadataTickRunnable, 1000L)
    }
}
