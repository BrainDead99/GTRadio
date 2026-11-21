package com.sc.gtradio.media

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A custom ForwardingPlayer container that more accurately represents certain attributes about our faux live-stream behavior
 */
@UnstableApi
class GTRadioPlayer(val exoPlayer: ExoPlayer) : ForwardingPlayer(exoPlayer) {

    enum class RadioState {
        NONE,
        STOPPED,
        PAUSED,
        PLAYING
    }

    var radioPlaybackState: RadioState = RadioState.NONE
    var isTracklistMode: Boolean = false
    private var cleanTrackListener: Player.Listener? = null
    private var cleanSegmentDurationsMs: List<Long> = emptyList()
    var onCleanQueuePrepared: ((totalDurationMs: Long) -> Unit)? = null

    var nextEnabled = false
    var previousEnabled = false
    var hasActiveQueueOrStation: Boolean = false

    var onTransportSkipNext: (() -> Unit)? = null
    var onTransportSkipPrevious: (() -> Unit)? = null
    var onTransportPlay: (() -> Unit)? = null
    var onTransportPause: (() -> Unit)? = null
    var onTransportStop: (() -> Unit)? = null

    private var isDispatchingTransport = false
    private val playerListeners = CopyOnWriteArraySet<Player.Listener>()
    private val wrappedListeners = HashMap<Player.Listener, Player.Listener>()

    var activeSessionMetadata: MediaMetadata = MediaMetadata.EMPTY
        private set

    fun updateSessionMetadata(metadata: MediaMetadata) {
        activeSessionMetadata = metadata
        val currentItem = currentMediaItem
        for (l in playerListeners) {
            l.onMediaMetadataChanged(metadata)
            l.onPlaylistMetadataChanged(metadata)
            if (currentItem != null) {
                l.onMediaItemTransition(currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
        }
    }

    fun updateNavigationCommands(canNext: Boolean, canPrev: Boolean) {
        nextEnabled = canNext
        previousEnabled = canPrev
        notifyAvailableCommandsChanged()
    }

    fun notifyAvailableCommandsChanged() {
        val cmds = availableCommands
        for (l in playerListeners) {
            l.onAvailableCommandsChanged(cmds)
        }
    }

    fun notifyPlaybackStateChanged() {
        val state = playbackState
        val playing = isPlaying
        for (l in playerListeners) {
            l.onPlaybackStateChanged(state)
            l.onIsPlayingChanged(playing)
        }
    }

    private var isTransitionLoading: Boolean = false

    fun isTransitionLoading(): Boolean = isTransitionLoading

    fun setTransitionLoading(loading: Boolean) {
        if (isTransitionLoading == loading) return
        val oldState = playbackState
        val oldPwr = playWhenReady
        isTransitionLoading = loading
        val newState = playbackState
        val newPwr = playWhenReady
        if (oldState != newState) {
            for (l in playerListeners) {
                l.onPlaybackStateChanged(newState)
            }
        }
        if (oldPwr != newPwr) {
            for (l in playerListeners) {
                l.onPlayWhenReadyChanged(newPwr, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
        }
    }

    override fun addListener(listener: Player.Listener) {
        playerListeners.add(listener)
        synchronized(wrappedListeners) {
            val wrapped = object : Player.Listener by listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    listener.onAvailableCommandsChanged(this@GTRadioPlayer.availableCommands)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val effectiveState = if (isTransitionLoading && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
                        Player.STATE_BUFFERING
                    } else {
                        this@GTRadioPlayer.playbackState
                    }
                    listener.onPlaybackStateChanged(effectiveState)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    val effectivePwr = if (isTransitionLoading) true else this@GTRadioPlayer.playWhenReady
                    listener.onPlayWhenReadyChanged(effectivePwr, reason)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    listener.onEvents(this@GTRadioPlayer, events)
                }
            }
            wrappedListeners[listener] = wrapped
            super.addListener(wrapped)
        }
    }

    override fun removeListener(listener: Player.Listener) {
        playerListeners.remove(listener)
        val wrapped = synchronized(wrappedListeners) {
            wrappedListeners.remove(listener)
        }
        if (wrapped != null) {
            super.removeListener(wrapped)
        } else {
            super.removeListener(listener)
        }
    }

    fun isExoPlayerReady(): Boolean = exoPlayer.playbackState == Player.STATE_READY && exoPlayer.playWhenReady

    fun setStreamMuted(muted: Boolean) {
        exoPlayer.volume = if (muted) 0f else 1f
    }

    fun resetForStationSwitch() {
        stopCleanQueue()
        stopInternal()
    }

    //region Customized Functionality
    override fun getPlaybackState(): Int {
        if (isTransitionLoading) {
            val superState = super.getPlaybackState()
            if (superState == Player.STATE_IDLE || superState == Player.STATE_ENDED || radioPlaybackState == RadioState.STOPPED || radioPlaybackState == RadioState.NONE) {
                return Player.STATE_BUFFERING
            }
        }
        if (radioPlaybackState == RadioState.PLAYING) {
            return if (isTransitionLoading) Player.STATE_BUFFERING else Player.STATE_READY
        } else if (radioPlaybackState == RadioState.STOPPED) {
            return if (isTransitionLoading) Player.STATE_BUFFERING else Player.STATE_IDLE
        }
        return if (isTransitionLoading) Player.STATE_BUFFERING else super.getPlaybackState()
    }

    override fun getPlayWhenReady(): Boolean {
        if (isTransitionLoading) {
            return true
        }
        return super.getPlayWhenReady()
    }

    override fun isPlaying(): Boolean {
        if (isTransitionLoading) {
            return false
        }
        if (radioPlaybackState == RadioState.PLAYING) {
            return true
        }
        return super.isPlaying()
    }

    fun playInternal() {
        radioPlaybackState = RadioState.PLAYING
        super.play()
        setTransitionLoading(false)
        notifyPlaybackStateChanged()
    }

    fun pauseInternal() {
        radioPlaybackState = RadioState.PAUSED
        super.pause()
        notifyPlaybackStateChanged()
    }

    fun stopInternal() {
        radioPlaybackState = RadioState.STOPPED
        super.stop()
        notifyPlaybackStateChanged()
    }

    override fun play() {
        if (isDispatchingTransport) {
            playInternal()
            return
        }
        val cb = onTransportPlay
        if (cb != null) {
            isDispatchingTransport = true
            try {
                cb.invoke()
            } finally {
                isDispatchingTransport = false
            }
        } else {
            playInternal()
        }
    }

    override fun pause() {
        if (isDispatchingTransport) {
            pauseInternal()
            return
        }
        val cb = onTransportPause
        if (cb != null) {
            isDispatchingTransport = true
            try {
                cb.invoke()
            } finally {
                isDispatchingTransport = false
            }
        } else {
            pauseInternal()
        }
    }

    override fun stop() {
        if (isDispatchingTransport) {
            stopInternal()
            return
        }
        val cb = onTransportStop
        if (cb != null) {
            isDispatchingTransport = true
            try {
                cb.invoke()
            } finally {
                isDispatchingTransport = false
            }
        } else {
            stopInternal()
        }
    }

    override fun hasPreviousMediaItem(): Boolean = hasActiveQueueOrStation || previousEnabled

    override fun hasNextMediaItem(): Boolean = hasActiveQueueOrStation || nextEnabled

    fun hasPrevious(): Boolean = hasActiveQueueOrStation || previousEnabled

    fun hasNext(): Boolean = hasActiveQueueOrStation || nextEnabled

    override fun seekToNext() {
        if (isDispatchingTransport) {
            super.seekToNext()
            return
        }
        val cb = onTransportSkipNext
        if (cb != null) {
            isDispatchingTransport = true
            try {
                cb.invoke()
            } finally {
                isDispatchingTransport = false
            }
        } else {
            super.seekToNext()
        }
    }

    override fun seekToNextMediaItem() {
        seekToNext()
    }

    override fun seekToPrevious() {
        if (isDispatchingTransport) {
            super.seekToPrevious()
            return
        }
        val cb = onTransportSkipPrevious
        if (cb != null) {
            isDispatchingTransport = true
            try {
                cb.invoke()
            } finally {
                isDispatchingTransport = false
            }
        } else {
            super.seekToPrevious()
        }
    }

    override fun seekToPreviousMediaItem() {
        seekToPrevious()
    }

    override fun seekTo(positionMs: Long) {
        if (isTracklistMode) {
            seekToMs(positionMs)
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (isTracklistMode) {
            seekToMs(positionMs)
        }
    }

    private class SyntheticTransitionTimeline(private val mediaItem: MediaItem) : Timeline() {
        override fun getWindowCount(): Int = 1
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            window.set(
                Window.SINGLE_WINDOW_UID,
                mediaItem,
                null,
                C.TIME_UNSET,
                C.TIME_UNSET,
                C.TIME_UNSET,
                false,
                true,
                null,
                0L,
                C.TIME_UNSET,
                0,
                0,
                0L
            )
            return window
        }

        override fun getPeriodCount(): Int = 1
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            period.set(
                if (setIds) 0 else null,
                if (setIds) Window.SINGLE_WINDOW_UID else null,
                0,
                C.TIME_UNSET,
                0L
            )
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            return if (uid == Window.SINGLE_WINDOW_UID) 0 else C.INDEX_UNSET
        }

        override fun getUidOfPeriod(periodIndex: Int): Any = Window.SINGLE_WINDOW_UID
    }

    private class RadioForwardingTimeline(private val timeline: Timeline) : Timeline() {
        override fun getWindowCount(): Int = timeline.windowCount
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            timeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
            window.isSeekable = false
            window.isDynamic = true
            window.durationUs = C.TIME_UNSET
            window.presentationStartTimeMs = C.TIME_UNSET
            window.windowStartTimeMs = 0L
            return window
        }

        override fun getPeriodCount(): Int = timeline.periodCount
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            return timeline.getPeriod(periodIndex, period, setIds)
        }

        override fun getIndexOfPeriod(uid: Any): Int = timeline.getIndexOfPeriod(uid)
        override fun getUidOfPeriod(periodIndex: Int): Any = timeline.getUidOfPeriod(periodIndex)
    }

    override fun getCurrentTimeline(): Timeline {
        val superTimeline = super.getCurrentTimeline()
        if (isTracklistMode) {
            return superTimeline
        }
        if (isTransitionLoading && super.getMediaItemCount() == 0) {
            val item = getCurrentMediaItem() ?: MediaItem.Builder().setMediaId("gt_radio_active").setMediaMetadata(activeSessionMetadata).build()
            return SyntheticTransitionTimeline(item)
        }
        if (superTimeline.isEmpty) {
            return superTimeline
        }
        return RadioForwardingTimeline(superTimeline)
    }

    override fun getMediaItemCount(): Int {
        val superCount = super.getMediaItemCount()
        if (superCount == 0 && isTransitionLoading) {
            return 1
        }
        return superCount
    }

    override fun getCurrentMediaItem(): MediaItem? {
        val superItem = super.getCurrentMediaItem()
        if (superItem != null) {
            if (activeSessionMetadata != MediaMetadata.EMPTY || hasActiveQueueOrStation) {
                return superItem.buildUpon()
                    .setMediaMetadata(activeSessionMetadata)
                    .build()
            }
            return superItem
        }
        if (isTransitionLoading || activeSessionMetadata != MediaMetadata.EMPTY || hasActiveQueueOrStation) {
            return MediaItem.Builder()
                .setMediaId("gt_radio_active")
                .setMediaMetadata(activeSessionMetadata)
                .build()
        }
        return null
    }

    override fun getMediaMetadata(): MediaMetadata = activeSessionMetadata

    override fun getPlaylistMetadata(): MediaMetadata = activeSessionMetadata

    override fun isCurrentMediaItemSeekable(): Boolean = isTracklistMode

    override fun isCurrentMediaItemLive(): Boolean = !isTracklistMode

    override fun getDuration(): Long {
        if (isTracklistMode) {
            return getDurationMs().takeIf { it > 0L } ?: super.getDuration()
        }
        return C.TIME_UNSET
    }

    override fun getContentDuration(): Long {
        if (isTracklistMode) {
            return getDurationMs().takeIf { it > 0L } ?: super.getContentDuration()
        }
        return C.TIME_UNSET
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
        return 0L
    }

    override fun getContentPosition(): Long {
        if (isTracklistMode) {
            return getCurrentTrackPosition()
        }
        return 0L
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
        notifyAvailableCommandsChanged()
        radioPlaybackState = RadioState.PLAYING
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
        playInternal()
        notifyPlaybackStateChanged()
    }

    fun stopCleanQueue() {
        cleanTrackListener?.let {
            exoPlayer.removeListener(it)
            cleanTrackListener = null
        }
        cleanSegmentDurationsMs = emptyList()
        isTracklistMode = false
        notifyAvailableCommandsChanged()
        stopInternal()
        exoPlayer.clearMediaItems()
        notifyPlaybackStateChanged()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return getAvailableCommands().contains(command)
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_TIMELINE)

        if (hasActiveQueueOrStation || hasNext() || hasPrevious()) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        if (isTracklistMode) {
            builder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            builder.add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            builder.add(Player.COMMAND_SEEK_BACK)
            builder.add(Player.COMMAND_SEEK_FORWARD)
        } else {
            builder.remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            builder.remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            builder.remove(Player.COMMAND_SEEK_BACK)
            builder.remove(Player.COMMAND_SEEK_FORWARD)
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
