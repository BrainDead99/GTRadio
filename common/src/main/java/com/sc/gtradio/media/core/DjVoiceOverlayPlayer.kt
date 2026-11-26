package com.sc.gtradio.media.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.sc.gtradio.common.GTRadioLogger

/**
 * Single pooled lightweight secondary ExoPlayer for dual-channel DJ voice overlays.
 *
 * CRITICAL AUDIO FOCUS RULE:
 * Configured with handleAudioFocus = false so overlaying voice clips (DJ intros, station IDs,
 * news, adverts, weather reports) NEVER steals Android audio focus from the primary music ExoPlayer.
 */
@UnstableApi
class DjVoiceOverlayPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var currentSessionToken: Long = -1L
    private var activeCallback: (() -> Unit)? = null
    private var activeListener: Player.Listener? = null
    private var isMuted: Boolean = false
    private var fadeGain: Float = 1.0f
    private var activeUri: Uri? = null

    val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    val currentPosition: Long
        get() = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L

    val duration: Long
        get() = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L

    val currentUri: Uri?
        get() = activeUri

    private fun getOrCreatePlayer(): ExoPlayer {
        val existing = exoPlayer
        if (existing != null) return existing

        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(context)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .build()

        exoPlayer = player
        return player
    }

    /**
     * Plays a voice overlay URI immediately on the pooled ExoPlayer.
     * Stops any previous clip and guarantees onFinished is called exactly once when playback ends or fails.
     */
    fun playOverlay(uri: Uri, token: Long, isMuted: Boolean = false, onFinished: (() -> Unit)? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { playOverlay(uri, token, isMuted, onFinished) }
            return
        }

        stopOverlayInternal()

        currentSessionToken = token
        activeCallback = onFinished
        activeUri = uri
        this.isMuted = isMuted

        val player = getOrCreatePlayer()
        val effectiveVolume = if (this.isMuted) 0f else fadeGain.coerceIn(0f, 1f)
        player.volume = effectiveVolume

        var callbackFired = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (!callbackFired && currentSessionToken == token) {
                        callbackFired = true
                        mainHandler.post {
                            if (currentSessionToken == token) {
                                val cb = activeCallback
                                activeCallback = null
                                cb?.invoke()
                            }
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                GTRadioLogger.log("DjVoiceOverlayPlayer: Error playing overlay $uri: ${error.message}", error)
                if (!callbackFired && currentSessionToken == token) {
                    callbackFired = true
                    mainHandler.post {
                        if (currentSessionToken == token) {
                            val cb = activeCallback
                            activeCallback = null
                            cb?.invoke()
                        }
                    }
                }
            }
        }

        activeListener = listener
        player.addListener(listener)

        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        GTRadioLogger.logVerbose("DJ-OVERLAY") {
            "Playing overlay: $uri (token=$token, muted=$isMuted)"
        }
    }

    /**
     * Immediately mutes or unmutes the voice overlay in lockstep with tuning-static mute state.
     */
    fun setMuted(muted: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setMuted(muted) }
            return
        }
        isMuted = muted
        exoPlayer?.volume = if (muted) 0f else fadeGain.coerceIn(0f, 1f)
    }

    /**
     * Applies the smooth station-switch fade multiplier without interfering with mute state.
     */
    fun setFadeGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setFadeGain(clamped) }
            return
        }
        fadeGain = clamped
        exoPlayer?.volume = if (isMuted) 0f else fadeGain
    }

    /**
     * Pauses the active voice overlay in lockstep with music player pause.
     */
    fun pause() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { pause() }
            return
        }
        exoPlayer?.pause()
    }

    /**
     * Resumes the active voice overlay in lockstep with music player resume.
     */
    fun resume() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { resume() }
            return
        }
        if (exoPlayer?.playbackState != Player.STATE_ENDED && exoPlayer?.playbackState != Player.STATE_IDLE) {
            exoPlayer?.play()
        }
    }

    /**
     * Immediately stops and clears any active DJ voice clip.
     * Prevents leaked audio on station switches, stop, or Tracklist mode entry.
     */
    fun stopOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopOverlay() }
            return
        }
        stopOverlayInternal()
    }

    private fun stopOverlayInternal() {
        currentSessionToken = -1L
        activeCallback = null
        activeUri = null
        activeListener?.let {
            exoPlayer?.removeListener(it)
            activeListener = null
        }
        exoPlayer?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
    }

    /**
     * Releases the pooled ExoPlayer cleanly on service destroy.
     */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release() }
            return
        }
        stopOverlayInternal()
        exoPlayer?.release()
        exoPlayer = null
    }
}
