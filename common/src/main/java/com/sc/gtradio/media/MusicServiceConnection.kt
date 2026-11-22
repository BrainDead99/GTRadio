package com.sc.gtradio.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.sc.gtradio.common.GTRadioLogger
import java.util.Collections
import java.util.HashMap
import java.util.HashSet

data class GTRadioPlaybackState(
    val isPlaying: Boolean = false,
    val isSkipToNextEnabled: Boolean = true,
    val isSkipToPreviousEnabled: Boolean = true,
    val position: Long = 0L,
    val lastPositionUpdateTime: Long = SystemClock.elapsedRealtime(),
    val playbackSpeed: Float = 1.0f,
    val extras: Bundle? = null
)

val EMPTY_PLAYBACK_STATE = GTRadioPlaybackState()
val NOTHING_PLAYING: MediaMetadata = MediaMetadata.EMPTY

inline val GTRadioPlaybackState.currentPlayBackPosition: Long
    get() = if (isPlaying) {
        val timeDelta = SystemClock.elapsedRealtime() - lastPositionUpdateTime
        (position + (timeDelta * playbackSpeed)).toLong()
    } else {
        position
    }

abstract class SubscriptionCallback {
    open fun onChildrenLoaded(parentId: String, children: List<MediaItem>) {}
    open fun onError(parentId: String) {}
}

@UnstableApi
class MusicServiceConnection(private val context: Context, serviceComponent: ComponentName) {
    val isConnected = MutableLiveData<Boolean>().apply { postValue(false) }
    val playbackState = MutableLiveData<GTRadioPlaybackState>().apply { postValue(EMPTY_PLAYBACK_STATE) }
    val nowPlaying = MutableLiveData<MediaMetadata>().apply { postValue(NOTHING_PLAYING) }
    val isPlaying = MutableLiveData<Boolean>().apply { postValue(false) }
    val sessionExtras = MutableLiveData<Bundle?>().apply { postValue(null) }

    val transportControls = TransportControls()

    private var mediaBrowser: MediaBrowser? = null
    private val subscriptions = HashMap<String, MutableSet<SubscriptionCallback>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentExtras: Bundle? = null
    private var lastPostedPlaying: Boolean? = null
    private var lastPostedState: GTRadioPlaybackState? = null
    private var lastPostedMetadata: MediaMetadata? = null

    init {
        val sessionToken = SessionToken(context, serviceComponent)
        val browserFuture = MediaBrowser.Builder(context, sessionToken)
            .setListener(BrowserListener())
            .buildAsync()

        browserFuture.addListener({
            try {
                val browser = browserFuture.get()
                mediaBrowser = browser
                browser.addListener(PlayerListener())
                val extras = browser.sessionExtras
                currentExtras = extras
                sessionExtras.postValue(extras)
                isConnected.postValue(true)
                GTRadioLogger.log("MusicServiceConnection: MediaBrowser connected successfully")

                // Resubscribe any pending subscriptions
                synchronized(subscriptions) {
                    for (parentId in subscriptions.keys) {
                        browser.subscribe(parentId, null)
                        fetchChildren(parentId)
                    }
                }
                updatePlaybackState()
            } catch (e: Exception) {
                GTRadioLogger.log("MusicServiceConnection: MediaBrowser connection failed", e)
                isConnected.postValue(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun subscribe(parentId: String, callback: SubscriptionCallback) {
        synchronized(subscriptions) {
            subscriptions.getOrPut(parentId) { HashSet() }.add(callback)
        }
        val browser = mediaBrowser
        if (browser != null && browser.isConnected) {
            browser.subscribe(parentId, null)
            fetchChildren(parentId)
        }
    }

    fun unsubscribe(parentId: String, callback: SubscriptionCallback) {
        synchronized(subscriptions) {
            val set = subscriptions[parentId]
            if (set != null) {
                set.remove(callback)
                if (set.isEmpty()) {
                    subscriptions.remove(parentId)
                    mediaBrowser?.unsubscribe(parentId)
                }
            }
        }
    }

    private fun fetchChildren(parentId: String) {
        val browser = mediaBrowser ?: return
        val callbacks: List<SubscriptionCallback> = synchronized(subscriptions) {
            subscriptions[parentId]?.toList() ?: emptyList()
        }
        if (callbacks.isEmpty()) return

        val future = browser.getChildren(parentId, 0, Int.MAX_VALUE, null)
        future.addListener({
            try {
                val res = future.get()
                if (res != null && res.resultCode == LibraryResult.RESULT_SUCCESS) {
                    val children = res.value ?: emptyList()
                    callbacks.forEach { it.onChildrenLoaded(parentId, children) }
                } else {
                    callbacks.forEach { it.onError(parentId) }
                }
            } catch (e: Exception) {
                GTRadioLogger.log("MusicServiceConnection: fetchChildren failed for $parentId", e)
                callbacks.forEach { it.onError(parentId) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun sendCommand(command: String, parameters: Bundle?): Boolean =
        sendCommand(command, parameters, null)

    fun sendCommand(command: String, parameters: Bundle?, resultCallback: ((Int, Bundle?) -> Unit)?): Boolean {
        val browser = mediaBrowser ?: return false
        val sessionCommand = SessionCommand(command, Bundle.EMPTY)
        val future = browser.sendCustomCommand(sessionCommand, parameters ?: Bundle.EMPTY)
        future.addListener({
            val res = try { future.get() } catch (e: Exception) { null }
            resultCallback?.invoke(res?.resultCode ?: -1, res?.extras)
        }, ContextCompat.getMainExecutor(context))
        return true
    }

    private fun bundleEquals(a: Bundle?, b: Bundle?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.size() != b.size()) return false
        for (key in a.keySet()) {
            if (!b.containsKey(key)) return false
            if (a.get(key) != b.get(key)) return false
        }
        return true
    }

    private fun metadataEquals(a: MediaMetadata?, b: MediaMetadata?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.title != b.title || a.subtitle != b.subtitle || a.albumTitle != b.albumTitle ||
            a.writer != b.writer || a.trackNumber != b.trackNumber) return false
        val aArt = a.artworkData
        val bArt = b.artworkData
        if ((aArt == null) != (bArt == null)) return false
        if (aArt != null && bArt != null && aArt.size != bArt.size) return false
        return bundleEquals(a.extras, b.extras)
    }

    private fun updatePlaybackState() {
        val browser = mediaBrowser ?: return
        val playing = browser.isPlaying
        val canNext = browser.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) ||
            browser.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val canPrev = browser.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS) ||
            browser.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        val pos = browser.currentPosition.coerceAtLeast(0L)
        val speed = browser.playbackParameters.speed
        val meta = browser.mediaMetadata

        if (lastPostedPlaying != playing) {
            lastPostedPlaying = playing
            isPlaying.postValue(playing)
        }

        val lastState = lastPostedState
        val isSeek = lastState != null && Math.abs(pos - lastState.currentPlayBackPosition) > 1000L
        val controlsChanged = lastState == null ||
            lastState.isPlaying != playing ||
            lastState.isSkipToNextEnabled != canNext ||
            lastState.isSkipToPreviousEnabled != canPrev ||
            lastState.playbackSpeed != speed ||
            !bundleEquals(lastState.extras, currentExtras)

        if (controlsChanged || isSeek) {
            val state = GTRadioPlaybackState(
                isPlaying = playing,
                isSkipToNextEnabled = canNext,
                isSkipToPreviousEnabled = canPrev,
                position = pos,
                lastPositionUpdateTime = SystemClock.elapsedRealtime(),
                playbackSpeed = speed,
                extras = currentExtras
            )
            lastPostedState = state
            playbackState.postValue(state)
        }

        if (lastPostedMetadata == null || !metadataEquals(lastPostedMetadata, meta)) {
            lastPostedMetadata = meta
            nowPlaying.postValue(meta)
        }
    }

    inner class TransportControls {
        fun play() {
            mediaBrowser?.play()
        }

        fun pause() {
            mediaBrowser?.pause()
        }

        fun stop() {
            mediaBrowser?.stop()
        }

        fun skipToNext() {
            mediaBrowser?.seekToNext()
        }

        fun skipToPrevious() {
            mediaBrowser?.seekToPrevious()
        }

        fun seekTo(positionMs: Long) {
            this@MusicServiceConnection.seekTo(positionMs)
        }

        fun playFromMediaId(mediaId: String, extras: Bundle? = null) {
            sendCommand(
                ACTION_PLAY_MEDIA_ID,
                Bundle().apply {
                    putString("media_id", mediaId)
                    extras?.let { putAll(it) }
                }
            )
        }

        fun sendCustomAction(action: String, extras: Bundle? = null) {
            sendCommand(action, extras ?: Bundle.EMPTY)
        }
    }

    fun durationMs(): Long {
        val b = mediaBrowser
        val browserDur = b?.duration ?: 0L
        if (browserDur > 0L && browserDur != androidx.media3.common.C.TIME_UNSET) {
            return browserDur
        }
        val sessionDur = sessionExtras.value?.getLong(com.sc.gtradio.media.stations.TracklistContract.EXTRA_DURATION_MS, 0L)
            ?: currentExtras?.getLong(com.sc.gtradio.media.stations.TracklistContract.EXTRA_DURATION_MS, 0L)
            ?: 0L
        if (sessionDur > 0L) return sessionDur

        val metaDur = nowPlaying.value?.extras?.getLong("duration_ms", 0L) ?: 0L
        if (metaDur > 0L) return metaDur

        val metaTracklistDur = nowPlaying.value?.extras?.getLong(com.sc.gtradio.media.stations.TracklistContract.EXTRA_DURATION_MS, 0L) ?: 0L
        if (metaTracklistDur > 0L) return metaTracklistDur

        return 0L
    }

    fun currentPositionMs(): Long {
        val b = mediaBrowser
        val pos = b?.currentPosition ?: -1L
        if (pos >= 0L) return pos
        return playbackState.value?.currentPlayBackPosition ?: 0L
    }

    fun seekTo(positionMs: Long) {
        GTRadioLogger.logVerbose("SEEK") { "MusicServiceConnection.seekTo: positionMs=$positionMs" }
        val b = mediaBrowser
        if (b != null) {
            b.seekTo(positionMs)
            if (!b.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                sendCommand(ACTION_SEEK_TO, Bundle().apply { putLong("position_ms", positionMs) })
            }
        }
    }

    private inner class BrowserListener : MediaBrowser.Listener {
        override fun onChildrenChanged(
            browser: MediaBrowser,
            parentId: String,
            itemCount: Int,
            params: MediaLibraryService.LibraryParams?
        ) {
            fetchChildren(parentId)
        }

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            if (!bundleEquals(currentExtras, extras)) {
                currentExtras = extras
                sessionExtras.postValue(extras)
                updatePlaybackState()
            }
        }

        override fun onDisconnected(controller: MediaController) {
            isConnected.postValue(false)
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            updatePlaybackState()
        }

        override fun onPlaybackStateChanged(state: Int) {
            updatePlaybackState()
        }

        override fun onMediaMetadataChanged(metadata: MediaMetadata) {
            if (lastPostedMetadata == null || !metadataEquals(lastPostedMetadata, metadata)) {
                lastPostedMetadata = metadata
                nowPlaying.postValue(metadata)
            }
            updatePlaybackState()
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            updatePlaybackState()
        }
    }

    companion object {
        @Volatile
        private var instance: MusicServiceConnection? = null

        fun getInstance(context: Context, serviceComponent: ComponentName) =
            instance ?: synchronized(this) {
                instance ?: MusicServiceConnection(context, serviceComponent)
                    .also { instance = it }
            }
    }
}

val MediaMetadata.mediaId: String?
    get() = extras?.getString("media_id")

val MediaMetadata.durationMs: Long
    get() {
        val b = extras ?: return -1L
        return b.getLong("duration_ms", -1L)
    }

private val metadataArtCache = android.util.LruCache<Int, Bitmap>(16)

val MediaMetadata.iconBitmap: Bitmap?
    get() {
        val bytes = artworkData ?: return null
        if (bytes.isEmpty()) return null
        val hash = bytes.contentHashCode()
        synchronized(metadataArtCache) {
            metadataArtCache.get(hash)?.let { if (!it.isRecycled) return it }
        }
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        if (bmp != null) {
            synchronized(metadataArtCache) {
                metadataArtCache.put(hash, bmp)
            }
        }
        return bmp
    }

val MediaMetadata.isSong: Boolean
    get() = (trackNumber == 1) || (extras?.getBoolean("is_song") == true)

val MediaMetadata.stationName: String?
    get() = writer?.toString() ?: extras?.getString("station_name")

val MediaMetadata.groupName: String?
    get() = albumTitle?.toString() ?: extras?.getString("group_name")
