package com.sc.gtradio.media.stations

import GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.GTRadioLogger
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

@UnstableApi
class Gen1RawRadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaBrowserCompat.MediaItem,
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

    override var onMetadataChanged: ((MediaMetadataCompat) -> Unit)? = null
    override var stationName: String = (mediaItem.description.title ?: stationInfo?.displayName ?: fileName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.description.subtitle ?: if (isXorAdf) "GTA Vice City" else "GTA III").toString()
    private val stationLogo: Bitmap? = mediaItem.description.iconBitmap
    private val gameCode: Int = if (isXorAdf) 1 else 2
    private val rng = Random()

    private var _playing = false
    private var activeCueKey: String = "__INIT__"
    private var _currentMetadata: MediaMetadataCompat = buildMetadata(null, null)
    override val metadata: MediaMetadataCompat
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

    private fun buildMetadata(cue: Gen1TrackCue?, customArt: Bitmap?): MediaMetadataCompat {
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

        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaItem.mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, cleanArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, cleanAlbum)
            .putString(MediaMetadataCompat.METADATA_KEY_WRITER, stationTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "$stationTitle • $groupTitle")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, if (isSong) 1L else 0L)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artToUse)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artToUse)
            .build()
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
        player.stop()
    }

    override fun play() {
        GTRadioLogger.log("Gen1RawRadioStation[$stationTitle]: play() called (file=$fileName, isXorAdf=$isXorAdf)")
        _playing = true
        activeCueKey = "__INIT__"
        mainHandler.removeCallbacks(metadataTickRunnable)

        player.radioPlaybackState = PlaybackStateCompat.STATE_PLAYING
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
        player.play()

        updateDynamicMetadataFromPosition()
        mainHandler.postDelayed(metadataTickRunnable, 1000L)
    }
}
