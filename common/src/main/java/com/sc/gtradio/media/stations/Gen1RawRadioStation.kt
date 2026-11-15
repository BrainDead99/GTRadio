package com.sc.gtradio.media.stations

import GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
    private val isVcAdf: Boolean,
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
    private val groupTitle: String = (mediaItem.description.subtitle ?: if (isVcAdf) "GTA Vice City" else "GTA III").toString()
    private val stationLogo: Bitmap? = mediaItem.description.iconBitmap
    private val rng = Random()

    private var _playing = false
    private var _currentMetadata: MediaMetadataCompat = buildMetadata()
    override val metadata: MediaMetadataCompat
        get() = _currentMetadata

    private var cachedDurationMs: Long = -1L

    private fun isMetadataEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("show_song_metadata_enabled", true)
    }

    private fun buildMetadata(): MediaMetadataCompat {
        val showMeta = isMetadataEnabled() && stationInfo != null
        val hostSub = if (showMeta && stationInfo!!.hostName.isNotBlank()) {
            "Hosted by ${stationInfo.hostName} • ${stationInfo.genre}"
        } else {
            groupTitle
        }
        val cleanArtist = if (showMeta) "DJ: ${stationInfo!!.hostName}" else ""
        val cleanAlbum = if (showMeta) stationInfo!!.genre else ""

        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaItem.mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stationTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stationTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, hostSub)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, cleanArtist.ifBlank { hostSub })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, cleanAlbum)
            .putString(MediaMetadataCompat.METADATA_KEY_WRITER, stationTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "$stationTitle • $groupTitle")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, if (showMeta) 1L else 0L)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, stationLogo)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, stationLogo)
            .build()
    }

    private fun estimateDurationMs(): Long {
        if (cachedDurationMs > 0L) return cachedDurationMs
        var durMs = 1_800_000L // default 30m fallback
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                val fileSize = pfd.statSize
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (!isVcAdf) {
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
        player.stop()
    }

    override fun play() {
        GTRadioLogger.log("Gen1RawRadioStation[$stationTitle]: play() called (file=$fileName, isVcAdf=$isVcAdf)")
        _playing = true
        _currentMetadata = buildMetadata()
        onMetadataChanged?.invoke(_currentMetadata)

        player.radioPlaybackState = PlaybackStateCompat.STATE_PLAYING
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.playWhenReady = false

        val durMs = estimateDurationMs()
        val liveOffsetMs = if (durMs > 60_000L) {
            (System.currentTimeMillis() % durMs).coerceIn(0L, durMs - 5_000L)
        } else {
            rng.nextInt(30_000).toLong()
        }

        val dataSourceFactory = VcAdfDataSource.Factory(
            context = context,
            xorMask = if (isVcAdf) 0x22 else 0x00
        )
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
        if (isVcAdf) {
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
    }
}
