package com.sc.gtradio.media.stations

import GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.GTRadioLogger

@UnstableApi
class Gen1At3RadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaBrowserCompat.MediaItem,
    private val archive: At3AudioArchive,
    private val stationInfo: Gen1StationInfo?,
    private val isVcs: Boolean,
    private val player: GTRadioPlayer,
    private val context: Context,
    override var adsEnabled: Boolean = true,
    override var weatherChatterEnabled: Boolean = false,
    override var newsReportsEnabled: Boolean = false
) : RadioStation {

    override var onMetadataChanged: ((MediaMetadataCompat) -> Unit)? = null
    override var stationName: String = (mediaItem.description.title ?: stationInfo?.displayName ?: archive.fileName).toString()

    private val stationTitle: String = stationName
    private val groupTitle: String = (mediaItem.description.subtitle ?: if (isVcs) "GTA: Vice City Stories" else "GTA: Liberty City Stories").toString()
    private val stationLogo: Bitmap? = mediaItem.description.iconBitmap

    private var _playing = false
    private var nextChunkStartFrame = 0
    private var activeListener: Player.Listener? = null
    private var _currentMetadata: MediaMetadataCompat = buildMetadata()
    override val metadata: MediaMetadataCompat
        get() = _currentMetadata

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

    override fun stop() {
        _playing = false
        if (activeListener != null) {
            player.removeListener(activeListener!!)
            activeListener = null
        }
        player.stop()
    }

    override fun play() {
        GTRadioLogger.log("Gen1At3RadioStation[$stationTitle]: play() called (file=${archive.fileName})")
        archive.ensureHeaderParsed()
        _playing = true
        _currentMetadata = buildMetadata()
        onMetadataChanged?.invoke(_currentMetadata)

        player.radioPlaybackState = PlaybackStateCompat.STATE_PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

        val durMs = archive.durationMs
        val liveMs = System.currentTimeMillis() % durMs
        val samplesPerFrame = if (archive.isAtrac3Plus) 2048L else 1024L
        val liveFrame = ((liveMs * archive.sampleRate.toLong()) / (samplesPerFrame * 1000L)).toInt()
            .coerceIn(0, (archive.totalFrames - 1).coerceAtLeast(0))

        playChunkAtFrame(liveFrame)
    }

    private fun playChunkAtFrame(startFrame: Int) {
        if (!_playing) return
        val uri = archive.materializeChunkUri(startFrame, At3AudioArchive.CHUNK_FRAMES) ?: return
        val afterChunk = startFrame + At3AudioArchive.CHUNK_FRAMES
        nextChunkStartFrame = if (afterChunk < archive.totalFrames) afterChunk else 0
        startPlayback(uri)
    }

    private fun startPlayback(uri: Uri) {
        if (activeListener != null) {
            player.removeListener(activeListener!!)
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
                    player.removeListener(this)
                    playChunkAtFrame(nextChunkStartFrame)
                }
            }
        }
        player.addListener(activeListener!!)
        player.prepare()
        player.play()
    }
}
