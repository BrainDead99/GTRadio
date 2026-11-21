import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * A custom ForwardingPlayer container that more accurately represents certain attributes about our faux live-stream behavior
 */
@UnstableApi
class GTRadioPlayer(val exoPlayer: ExoPlayer) : ForwardingPlayer(exoPlayer) {

    var radioPlaybackState: Int = PlaybackStateCompat.STATE_NONE
    var isTracklistMode: Boolean = false
    private var cleanTrackListener: Player.Listener? = null
    private var cleanSegmentDurationsMs: List<Long> = emptyList()
    var onCleanQueuePrepared: ((totalDurationMs: Long) -> Unit)? = null

    var nextEnabled = false
    var previousEnabled = false

    fun isExoPlayerReady(): Boolean = exoPlayer.playbackState == Player.STATE_READY && exoPlayer.playWhenReady

    fun setStreamMuted(muted: Boolean) {
        exoPlayer.volume = if (muted) 0f else 1f
    }

    fun resetForStationSwitch() {
        stopCleanQueue()
        exoPlayer.stop()
    }

    //region Customized Functionality
    override fun getPlaybackState(): Int {
        if (radioPlaybackState == PlaybackStateCompat.STATE_PLAYING) {
            return Player.STATE_READY
        } else if (radioPlaybackState == PlaybackStateCompat.STATE_STOPPED) {
            return Player.STATE_IDLE
        }
        return super.getPlaybackState()
    }

    override fun isPlaying(): Boolean {
        if (radioPlaybackState == PlaybackStateCompat.STATE_PLAYING) {
            return true
        }
        return super.isPlaying()
    }

    override fun play() {
        radioPlaybackState = PlaybackStateCompat.STATE_PLAYING
        super.play()
    }

    override fun pause() {
        radioPlaybackState = PlaybackStateCompat.STATE_PAUSED
        super.pause()
    }

    override fun stop() {
        radioPlaybackState = PlaybackStateCompat.STATE_STOPPED
        super.stop()
    }

    override fun hasPreviousMediaItem(): Boolean = previousEnabled

    override fun hasNextMediaItem(): Boolean = nextEnabled

    fun hasPrevious(): Boolean = previousEnabled

    fun hasNext(): Boolean = nextEnabled

    override fun getDuration(): Long {
        if (isTracklistMode) {
            return getDurationMs()
        }
        // For a faux live-stream, no items have a known duration
        return -1L
    }

    fun getDurationMs(): Long {
        if (cleanSegmentDurationsMs.isNotEmpty() && cleanSegmentDurationsMs.none { it <= 0L }) {
            return cleanSegmentDurationsMs.sum()
        }
        val timeline = exoPlayer.currentTimeline
        if (timeline.isEmpty) {
            val d = exoPlayer.duration
            return if (d > 0L && d != C.TIME_UNSET) d else 0L
        }
        var totalMs = 0L
        val window = Timeline.Window()
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, window)
            val d = window.durationMs
            if (d > 0L && d != C.TIME_UNSET) {
                totalMs += d
            }
        }
        if (totalMs > 0L) return totalMs
        val d = exoPlayer.duration
        return if (d > 0L && d != C.TIME_UNSET) d else 0L
    }

    fun getCurrentTrackDuration(): Long {
        return exoPlayer.duration
    }

    override fun getCurrentPosition(): Long {
        if (isTracklistMode) {
            return getCurrentTrackPosition()
        }
        // For a faux live-stream, we never really know the official "position" we are in
        return -1L
    }

    fun getCurrentTrackPosition(): Long {
        val curWin = exoPlayer.currentMediaItemIndex
        val curPos = exoPlayer.currentPosition.coerceAtLeast(0L)
        if (cleanSegmentDurationsMs.isNotEmpty() && curWin >= 0) {
            val validWin = curWin.coerceAtMost(cleanSegmentDurationsMs.size)
            val sumPrefix = cleanSegmentDurationsMs.take(validWin).sum()
            return sumPrefix + curPos
        }
        val timeline = exoPlayer.currentTimeline
        if (timeline.isEmpty || curWin <= 0) {
            return curPos
        }
        var cumulativeMs = 0L
        val window = Timeline.Window()
        for (i in 0 until curWin.coerceAtMost(timeline.windowCount)) {
            timeline.getWindow(i, window)
            val d = window.durationMs
            if (d > 0L && d != C.TIME_UNSET) {
                cumulativeMs += d
            }
        }
        return cumulativeMs + curPos
    }

    fun seekToMs(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        if (cleanSegmentDurationsMs.isNotEmpty()) {
            var accumulated = 0L
            for (i in cleanSegmentDurationsMs.indices) {
                val d = cleanSegmentDurationsMs[i].coerceAtLeast(0L)
                if (target < accumulated + d || i == cleanSegmentDurationsMs.lastIndex) {
                    val offsetInWindow = (target - accumulated).coerceAtLeast(0L)
                    exoPlayer.seekTo(i, offsetInWindow)
                    return
                }
                accumulated += d
            }
            exoPlayer.seekTo(cleanSegmentDurationsMs.lastIndex, 0L)
            return
        }
        val timeline = exoPlayer.currentTimeline
        if (timeline.isEmpty || timeline.windowCount <= 1) {
            exoPlayer.seekTo(target)
            return
        }
        var accumulated = 0L
        val window = Timeline.Window()
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, window)
            val d = if (window.durationMs > 0L && window.durationMs != C.TIME_UNSET) window.durationMs else 0L
            if (target < accumulated + d || i == timeline.windowCount - 1) {
                val offsetInWindow = (target - accumulated).coerceAtLeast(0L)
                exoPlayer.seekTo(i, offsetInWindow)
                return
            }
            accumulated += d
        }
        exoPlayer.seekTo(timeline.windowCount - 1, 0L)
    }

    fun playCleanQueue(uris: List<Uri>, segmentDurationsMs: List<Long> = emptyList(), onEnded: () -> Unit) {
        stopCleanQueue()
        cleanSegmentDurationsMs = segmentDurationsMs
        isTracklistMode = true
        radioPlaybackState = PlaybackStateCompat.STATE_PLAYING
        nextEnabled = true
        previousEnabled = true
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        setStreamMuted(false)
        exoPlayer.clearMediaItems()
        for (uri in uris) {
            exoPlayer.addMediaItem(MediaItem.fromUri(uri))
        }

        val totalDur = getDurationMs()
        if (totalDur > 0L) {
            onCleanQueuePrepared?.invoke(totalDur)
        }

        cleanTrackListener = object : Player.Listener {
            private var handled = false
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !handled) {
                    handled = true
                    exoPlayer.removeListener(this)
                    cleanTrackListener = null
                    onEnded()
                }
            }
        }
        exoPlayer.addListener(cleanTrackListener!!)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun stopCleanQueue() {
        cleanTrackListener?.let {
            exoPlayer.removeListener(it)
            cleanTrackListener = null
        }
        cleanSegmentDurationsMs = emptyList()
        isTracklistMode = false
        radioPlaybackState = PlaybackStateCompat.STATE_STOPPED
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_PLAY_PAUSE -> true
            Player.COMMAND_STOP -> isPlaying
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> hasNext()
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> hasPrevious()
            else -> super.isCommandAvailable(command)
        }
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()
        if (hasNext()) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (hasPrevious()) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return builder.build()
    }

    override fun getCurrentWindowIndex(): Int = super.getCurrentWindowIndex()

    // ExoPlayer-specific forwarding helpers
    fun setMediaSource(mediaSource: MediaSource) {
        exoPlayer.setMediaSource(mediaSource)
    }

    fun setMediaSource(mediaSource: MediaSource, startPositionMs: Long) {
        exoPlayer.setMediaSource(mediaSource, startPositionMs)
    }

    fun setMediaSource(mediaSource: MediaSource, resetPosition: Boolean) {
        exoPlayer.setMediaSource(mediaSource, resetPosition)
    }

    fun setMediaSources(mediaSources: List<MediaSource>) {
        exoPlayer.setMediaSources(mediaSources)
    }

    fun setMediaSources(mediaSources: List<MediaSource>, resetPosition: Boolean) {
        exoPlayer.setMediaSources(mediaSources, resetPosition)
    }

    fun setMediaSources(mediaSources: List<MediaSource>, startWindowIndex: Int, startPositionMs: Long) {
        exoPlayer.setMediaSources(mediaSources, startWindowIndex, startPositionMs)
    }

    fun addMediaSource(mediaSource: MediaSource) {
        exoPlayer.addMediaSource(mediaSource)
    }

    fun addMediaSource(index: Int, mediaSource: MediaSource) {
        exoPlayer.addMediaSource(index, mediaSource)
    }

    fun addMediaSources(mediaSources: List<MediaSource>) {
        exoPlayer.addMediaSources(mediaSources)
    }

    fun addMediaSources(index: Int, mediaSources: List<MediaSource>) {
        exoPlayer.addMediaSources(index, mediaSources)
    }

    fun setShuffleOrder(shuffleOrder: ShuffleOrder) {
        exoPlayer.setShuffleOrder(shuffleOrder)
    }

    fun prepare(mediaSource: MediaSource) {
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    fun prepare(mediaSource: MediaSource, resetPosition: Boolean, resetState: Boolean) {
        exoPlayer.setMediaSource(mediaSource, resetPosition)
        exoPlayer.prepare()
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        super.setAudioAttributes(audioAttributes, handleAudioFocus)
    }

    fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean) {
        exoPlayer.setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
    }

    fun setSeekParameters(seekParameters: SeekParameters?) {
        exoPlayer.setSeekParameters(seekParameters)
    }

    fun getSeekParameters(): SeekParameters = exoPlayer.seekParameters
}
