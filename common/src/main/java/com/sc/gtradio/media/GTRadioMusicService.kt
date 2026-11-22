package com.sc.gtradio.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.stations.*
import kotlinx.coroutines.*
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicLong

enum class PlaybackMode {
    RADIO,
    TRACKLIST
}

const val NOW_PLAYING_CHANNEL_ID = "com.sc.gtradio.media.NOW_PLAYING"
const val NOW_PLAYING_NOTIFICATION_ID = 0xb369
const val ACTION_PLAY_MEDIA_ID = "com.sc.gtradio.ACTION_PLAY_MEDIA_ID"
const val ACTION_SEEK_TO = "com.sc.gtradio.ACTION_SEEK_TO"
const val ACTION_RELOAD_LIBRARY = "com.sc.gtradio.ACTION_RELOAD_LIBRARY"

@UnstableApi
open class GTRadioMusicService : MediaLibraryService() {
    private var stationGroups: ArrayList<StationGroup> = ArrayList()
    private var fullStationList: ArrayList<MediaItem> = ArrayList()
    private val stationCache: MutableMap<String, RadioStation> = mutableMapOf()
    private val sharedPrefListener = GTRadioOnSharedPrefChange()

    private var playbackMode: PlaybackMode = PlaybackMode.RADIO
    private var activeTracklistStation: TracklistCapableStation? = null
    private var activeTracklistStationId: String? = null
    private var activeTracklistTracks: List<TracklistTrack> = emptyList()
    private var activeTracklistIndex: Int = -1
    private var shuffleEnabled: Boolean = false
    private var repeatMode: Int = TracklistContract.REPEAT_MODE_NONE
    private val tracklistPlayedIndices = mutableListOf<Int>()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var groupsLoaded = false
    private var cachedUri: String? = null
    @Volatile private var cacheHydrated = false
    private val tracklistRequestGen = AtomicLong(0)
    private val playbackPrefs by lazy {
        applicationContext.getSharedPreferences("playback_state_prefs", Context.MODE_PRIVATE)
    }

    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val gtrAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()
    private val playerListener = PlayerEventListener()

    val radioAudioProcessor by lazy { RadioAudioProcessor(applicationContext) }

    private val exoPlayer: ExoPlayer by lazy {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(radioAudioProcessor))
                    .build()
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_500,
                /* maxBufferMs = */ 10_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(gtrAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .experimentalSetDynamicSchedulingEnabled(true)
            .build()
    }

    private val radioPlayer: GTRadioPlayer by lazy {
        GTRadioPlayer(exoPlayer).apply {
            addListener(playerListener)
            onCleanQueuePrepared = { totalDur ->
                if (playbackMode == PlaybackMode.TRACKLIST) {
                    val currentTrack = activeTracklistTracks.getOrNull(activeTracklistIndex)
                    if (currentTrack != null) {
                        val art = AlbumArtDownloader.getCachedAlbumArt(applicationContext, currentTrack.artist, currentTrack.title)
                        publishTracklistMetadata(currentTrack, activeTracklistIndex, art, totalDur)
                    }
                    mediaLibrarySession.setSessionExtras(buildSessionExtras())
                }
            }
        }
    }

    private var activeStation: RadioStation? = null
    private var activeStationGroupId: String? = null
    private var activePlaybackStationGroup: StationGroup? = null
    @Volatile private var stationSwitchToken = 0L
    @Volatile private var playerReadyForCurrentSwitch = false

    private inner class GTRadioOnSharedPrefChange : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (prefs != null) {
                when (key) {
                    "dynamic_theme_uri", "game_key_folder_uri", "gtav_key_folder_uri", "game_keys_updated", "gtav_keys_updated", resources.getString(R.string.radio_folders_uri_key) -> {
                        GTRadioLogger.log("GTRadioMusicService: Library URI or Game keys changed, invalidating cache...")
                        reloadLibrary()
                    }
                    resources.getString(R.string.ads_enabled_preference_key) -> {
                        if (this@GTRadioMusicService.activeStation != null) {
                            val newValue = prefs.getBoolean(key, false)
                            this@GTRadioMusicService.activeStation?.adsEnabled = newValue
                        }
                    }
                    resources.getString(R.string.weather_chatter_enabled_preference_key) -> {
                        if (this@GTRadioMusicService.activeStation != null) {
                            val newValue = prefs.getBoolean(key, false)
                            this@GTRadioMusicService.activeStation?.weatherChatterEnabled = newValue
                        }
                    }
                    "loudness_normalization_enabled", "car_stereo_eq_enabled" -> {
                        radioAudioProcessor.updateSettings(prefs)
                    }
                    resources.getString(R.string.news_reports_preference_key) -> {
                        if (this@GTRadioMusicService.activeStation != null) {
                            val newValue = prefs.getBoolean(key, false)
                            this@GTRadioMusicService.activeStation?.newsReportsEnabled = newValue
                        }
                    }
                }
            }
        }
    }

    private fun reloadLibrary() {
        radioPlayer.hasActiveQueueOrStation = false
        radioPlayer.notifyAvailableCommandsChanged()
        if (playbackMode == PlaybackMode.TRACKLIST) {
            activeTracklistStation?.stopCleanTrackPlayback()
            activeTracklistStation = null
            activeTracklistStationId = null
            activeTracklistTracks = emptyList()
            activeTracklistIndex = -1
            radioPlayer.stopCleanQueue()
            radioPlayer.isTracklistMode = false
            playbackMode = PlaybackMode.RADIO
        }
        activeStation?.stop()
        activeStation = null
        stationCache.clear()
        synchronized(this) {
            groupsLoaded = false
            cachedUri = null
            stationGroups = ArrayList()
            fullStationList = ArrayList()
        }
        DynamicThemeManager.invalidateCache()
        LibraryIndexCache.invalidate(applicationContext)
        cacheHydrated = false
        serviceScope.launch(Dispatchers.IO) {
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val currentUri = sharedPref?.getString(getString(R.string.radio_folders_uri_key), "") ?: ""
            ensureGroupsLoadedSync(currentUri)
            withContext(Dispatchers.Main.immediate) {
                mediaLibrarySession.notifyChildrenChanged("root", stationGroups.size, null)
                mediaLibrarySession.notifyChildrenChanged(TracklistContract.ROOT_ID, stationGroups.size, null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        GTRadioLogger.init(applicationContext)
        AlbumArtDownloader.init(applicationContext)
        serviceScope.launch(Dispatchers.IO) {
            AlbumHintRepository.ensureLoaded(applicationContext)
            val currentUri = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                ?.getString(getString(R.string.radio_folders_uri_key), "") ?: ""
            if (currentUri.isNotBlank()) {
                val cached = LibraryIndexCache.loadCache(applicationContext, currentUri)
                if (cached != null) {
                    DynamicThemeManager.refreshIfNeeded(applicationContext, cached.themeDirUri)
                }
            }
        }

        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        sharedPref.registerOnSharedPreferenceChangeListener(sharedPrefListener)

        val sessionIntent = packageManager?.getLaunchIntentForPackage(packageName)
        val sessionActivityPendingIntent = sessionIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        // Wire native transport callbacks from GTRadioPlayer
        radioPlayer.onTransportPlay = {
            handlePlay()
        }
        radioPlayer.onTransportPause = {
            handlePause()
        }
        radioPlayer.onTransportStop = {
            handleStop()
        }
        radioPlayer.onTransportSkipNext = {
            handleSkipNext()
        }
        radioPlayer.onTransportSkipPrevious = {
            handleSkipPrevious()
        }

        val sessionBuilder = MediaLibrarySession.Builder(this, radioPlayer, LibraryCallback())
        if (sessionActivityPendingIntent != null) {
            sessionBuilder.setSessionActivity(sessionActivityPendingIntent)
        }
        mediaLibrarySession = sessionBuilder.build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOW_PLAYING_CHANNEL_ID)
                .setChannelName(R.string.notification_channel)
                .setNotificationId(NOW_PLAYING_NOTIFICATION_ID)
                .build()
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_PLAY_MEDIA_ID, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SEEK_TO, Bundle.EMPTY))
                .add(SessionCommand(TracklistContract.ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(TracklistContract.ACTION_CYCLE_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_RELOAD_LIBRARY, Bundle.EMPTY))
                .add(SessionCommand("setActiveStationGroupId", Bundle.EMPTY))
                .add(SessionCommand("setStreamMuted", Bundle.EMPTY))
                .build()
            val playerCommands = session.player.availableCommands.buildUpon()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_STOP)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_GET_TIMELINE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_PLAY_MEDIA_ID -> {
                    val mediaId = args.getString("media_id") ?: ""
                    if (mediaId.isNotBlank()) {
                        playMediaId(mediaId, args)
                    }
                }
                ACTION_SEEK_TO -> {
                    val posMs = args.getLong("position_ms", -1L)
                    if (posMs >= 0L) {
                        radioPlayer.seekToMs(posMs)
                    }
                }
                "setActiveStationGroupId" -> {
                    activeStationGroupId = args.getString("stationGroupId")
                }
                "setStreamMuted" -> {
                    val muted = args.getBoolean("muted", false)
                    radioPlayer.setStreamMuted(muted)
                }
                TracklistContract.ACTION_TOGGLE_SHUFFLE -> {
                    shuffleEnabled = !shuffleEnabled
                    tracklistPlayedIndices.clear()
                    if (activeTracklistIndex >= 0) tracklistPlayedIndices.add(activeTracklistIndex)
                    session.setSessionExtras(buildSessionExtras())
                }
                TracklistContract.ACTION_CYCLE_REPEAT -> {
                    repeatMode = when (repeatMode) {
                        TracklistContract.REPEAT_MODE_NONE -> TracklistContract.REPEAT_MODE_ALL
                        TracklistContract.REPEAT_MODE_ALL -> TracklistContract.REPEAT_MODE_ONE
                        else -> TracklistContract.REPEAT_MODE_NONE
                    }
                    session.setSessionExtras(buildSessionExtras())
                }
                ACTION_RELOAD_LIBRARY -> {
                    reloadLibrary()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Root")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val currentUri = sharedPref?.getString(getString(R.string.radio_folders_uri_key), "") ?: ""

            serviceScope.launch(Dispatchers.IO) {
                try {
                    ensureGroupsLoadedSync(currentUri)
                    val items: List<MediaItem> = when {
                        parentId == TracklistContract.ROOT_ID -> buildTracklistRootItems()
                        parentId.startsWith(TracklistContract.GROUP_PREFIX) -> {
                            val rawGroupId = parentId.removePrefix(TracklistContract.GROUP_PREFIX)
                            buildTracklistGroupStations(rawGroupId)
                        }
                        parentId.startsWith(TracklistContract.STATION_PREFIX) -> {
                            val rawStationId = parentId.removePrefix(TracklistContract.STATION_PREFIX)
                            buildTracklistStationSongs(rawStationId)
                        }
                        parentId.isBlank() || parentId == "root" -> {
                            stationGroups.map { it.mediaItem }
                        }
                        else -> {
                            val group = stationGroups.find { it.mediaItem.mediaId == parentId }
                            group?.stationList ?: emptyList()
                        }
                    }
                    future.set(LibraryResult.ofItemList(items, params))
                } catch (e: Exception) {
                    GTRadioLogger.log("GTRadioMusicService: onGetChildren failed for $parentId", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = fullStationList.find { it.mediaId == mediaId }
                ?: stationGroups.find { it.mediaItem.mediaId == mediaId }?.mediaItem
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val target = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            if (target != null && target.mediaId.isNotBlank()) {
                playMediaId(target.mediaId, Bundle.EMPTY)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems,
                    startIndex,
                    startPositionMs
                )
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val currentUri = sharedPref?.getString(getString(R.string.radio_folders_uri_key), "") ?: ""

            serviceScope.launch(Dispatchers.IO) {
                try {
                    ensureGroupsLoadedSync(currentUri)

                    val lastMode = playbackPrefs.getString("last_playback_mode", "RADIO") ?: "RADIO"
                    val lastMediaId = playbackPrefs.getString("last_media_id", "") ?: ""
                    val lastGroupId = playbackPrefs.getString("last_group_id", "") ?: ""

                    if (lastMediaId.isNotBlank()) {
                        withContext(Dispatchers.Main.immediate) {
                            playMediaId(lastMediaId, Bundle().apply {
                                putString("stationGroupId", lastGroupId)
                            })
                        }
                    }

                    val resolvedItem = fullStationList.find { it.mediaId == lastMediaId }
                        ?: fullStationList.firstOrNull()
                    if (resolvedItem != null) {
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(resolvedItem),
                                0,
                                0L
                            )
                        )
                    } else {
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                emptyList(),
                                0,
                                0L
                            )
                        )
                    }
                    GTRadioLogger.log("GTRadioMusicService: onPlaybackResumption resolved mode=$lastMode, mediaId=$lastMediaId")
                } catch (e: Exception) {
                    GTRadioLogger.log("GTRadioMusicService: onPlaybackResumption failed", e)
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                }
            }
            return future
        }
    }

    private fun persistPlaybackState() {
        val editor = playbackPrefs.edit()
        editor.putString("last_playback_mode", playbackMode.name)
        val mediaIdToSave = when (playbackMode) {
            PlaybackMode.TRACKLIST -> {
                val track = activeTracklistTracks.getOrNull(activeTracklistIndex)
                if (track != null && activeTracklistStationId != null) {
                    TracklistContract.SONG_PREFIX + activeTracklistStationId + "::" + track.id
                } else ""
            }
            PlaybackMode.RADIO -> activeStation?.mediaId ?: ""
        }
        editor.putString("last_media_id", mediaIdToSave)
        editor.putString("last_group_id", activeStationGroupId ?: "")
        editor.putString("last_station_id", activeStation?.mediaId ?: activeTracklistStationId ?: "")
        editor.apply()
    }

    private fun buildSessionExtras(): Bundle {
        return Bundle().apply {
            putString(TracklistContract.EXTRA_PLAYBACK_MODE, playbackMode.name)
            putBoolean(TracklistContract.EXTRA_SHUFFLE_ENABLED, shuffleEnabled)
            putInt(TracklistContract.EXTRA_REPEAT_MODE, repeatMode)
            putString(TracklistContract.EXTRA_STATION_GROUP_ID, activeStationGroupId ?: "")
            if (playbackMode == PlaybackMode.TRACKLIST) {
                val currentTrack = activeTracklistTracks.getOrNull(activeTracklistIndex)
                putString(TracklistContract.EXTRA_STATION_ID, activeTracklistStationId ?: "")
                putString(TracklistContract.EXTRA_TRACK_ID, currentTrack?.id ?: "")
                putString(TracklistContract.EXTRA_TRACK_TITLE, currentTrack?.title ?: "")
                putString(TracklistContract.EXTRA_TRACK_ARTIST, currentTrack?.artist ?: "")
                putString(TracklistContract.EXTRA_TRACK_ALBUM, currentTrack?.stationName ?: "")
                putInt(TracklistContract.EXTRA_TRACK_INDEX, activeTracklistIndex)
                putInt(TracklistContract.EXTRA_TRACK_COUNT, activeTracklistTracks.size)
                val dur = radioPlayer.getDurationMs()
                if (dur > 0L) {
                    putLong(TracklistContract.EXTRA_DURATION_MS, dur)
                } else {
                    putLong(TracklistContract.EXTRA_DURATION_MS, 0L)
                }
            } else {
                putString(TracklistContract.EXTRA_STATION_ID, activeStation?.mediaId ?: "")
            }
        }
    }

    private fun handlePlay() {
        if (playbackMode == PlaybackMode.TRACKLIST) {
            if (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.playbackState == Player.STATE_BUFFERING) {
                radioPlayer.playInternal()
                mediaLibrarySession.setSessionExtras(buildSessionExtras())
            } else if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED) {
                if (activeTracklistIndex in activeTracklistTracks.indices) {
                    playTracklistTrack(activeTracklistIndex)
                } else if (activeTracklistTracks.isNotEmpty()) {
                    playTracklistTrack(0)
                }
            } else if (activeTracklistIndex in activeTracklistTracks.indices) {
                playTracklistTrack(activeTracklistIndex)
            }
            return
        }

        if (activeStation == null) {
            val group = (activeStationGroupId?.let { getStationGroup(it) })
                ?: (fullStationList.firstOrNull()?.mediaId?.let { getStationGroupContainingMedia(it) })
                ?: return
            val firstStation = group.stationList.firstOrNull() ?: return
            val st = getRadioStation(group, firstStation.mediaId) ?: return
            startStationWithTuningNoise(group, st, 0, trigger = "play")
            return
        }

        val currentSt = activeStation ?: return
        val group = (activeStationGroupId?.let { getStationGroup(it) })
            ?: getStationGroup(currentSt.stationGroupId)
            ?: getStationGroupContainingMedia(currentSt.mediaId)
            ?: return
        val stationIndex = group.getStationIndex(currentSt.mediaId)
        startStationWithTuningNoise(group, currentSt, stationIndex, trigger = "play")
    }

    fun playMediaId(mediaId: String, extras: Bundle?) {
        if (mediaId.isBlank()) return

        radioPlayer.setTransitionLoading(true)

        if (mediaId.startsWith(TracklistContract.SONG_PREFIX)) {
            val payload = mediaId.removePrefix(TracklistContract.SONG_PREFIX)
            val stationId = payload.substringBefore("::")
            val trackId = payload.substringAfter("::")

            val group = getStationGroupContainingMedia(stationId)
            val station = group?.let { getRadioStation(it, stationId) } as? TracklistCapableStation
            val tracks = station?.getCleanTracks() ?: emptyList()
            val targetTrack = tracks.find { it.id.equals(trackId, ignoreCase = true) } ?: tracks.firstOrNull()
            if (targetTrack != null) {
                val cachedArt = AlbumArtDownloader.getMemoryCachedAlbumArt(targetTrack.artist, targetTrack.title)
                    ?: AlbumArtDownloader.getCachedAlbumArt(applicationContext, targetTrack.artist, targetTrack.title)
                val previewMeta = buildStationMediaMetadata(
                    mediaId = mediaId,
                    title = targetTrack.title,
                    subtitle = "${targetTrack.artist} • ${targetTrack.stationName}",
                    artist = targetTrack.artist,
                    albumArtist = targetTrack.stationName,
                    stationTitle = targetTrack.stationName,
                    groupTitle = targetTrack.stationName,
                    art = cachedArt,
                    isSong = true,
                    durationMs = -1L
                )
                radioPlayer.updateSessionMetadata(previewMeta)
            }

            val departingStation = activeStation
            activeStation = null
            departingStation?.stop()

            DynamicThemeManager.stopTuningNoise("tracklist_start")
            radioPlayer.setStreamMuted(false)
            radioPlayer.isTracklistMode = true
            radioPlayer.hasActiveQueueOrStation = true
            playbackMode = PlaybackMode.TRACKLIST
            radioPlayer.notifyAvailableCommandsChanged()

            if (station == null || group == null) return
            activeTracklistStation = station
            activeTracklistStationId = stationId
            activeTracklistTracks = tracks
            val trackIndex = activeTracklistTracks.indexOfFirst { it.id.equals(trackId, ignoreCase = true) }
            val targetIndex = if (trackIndex >= 0) trackIndex else 0
            playTracklistTrack(targetIndex)
            return
        }

        val targetItem = fullStationList.find { it.mediaId == mediaId }
        if (targetItem != null) {
            val targetArtUri = targetItem.mediaMetadata.artworkUri
            val targetArt = DynamicThemeManager.getCachedBitmap(targetArtUri, 512)
            val previewMeta = buildStationMediaMetadata(
                mediaId = targetItem.mediaId,
                title = targetItem.mediaMetadata.title?.toString() ?: "",
                subtitle = targetItem.mediaMetadata.subtitle?.toString() ?: "",
                artist = targetItem.mediaMetadata.artist?.toString() ?: "",
                albumArtist = "",
                stationTitle = targetItem.mediaMetadata.title?.toString() ?: "",
                groupTitle = targetItem.mediaMetadata.albumTitle?.toString() ?: "",
                art = targetArt,
                isSong = false,
                durationMs = -1L
            )
            radioPlayer.updateSessionMetadata(previewMeta)

            if (targetArt == null && targetArtUri != null) {
                val targetMediaId = targetItem.mediaId
                val token = stationSwitchToken
                serviceScope.launch(Dispatchers.IO) {
                    val loaded = DynamicThemeManager.loadBitmap(applicationContext, targetArtUri, 512)
                    if (loaded != null) {
                        withContext(Dispatchers.Main.immediate) {
                            if (token == stationSwitchToken && activeStation?.mediaId == targetMediaId) {
                                val updatedMeta = buildStationMediaMetadata(
                                    mediaId = targetMediaId,
                                    title = targetItem.mediaMetadata.title?.toString() ?: "",
                                    subtitle = targetItem.mediaMetadata.subtitle?.toString() ?: "",
                                    artist = targetItem.mediaMetadata.artist?.toString() ?: "",
                                    albumArtist = "",
                                    stationTitle = targetItem.mediaMetadata.title?.toString() ?: "",
                                    groupTitle = targetItem.mediaMetadata.albumTitle?.toString() ?: "",
                                    art = loaded,
                                    isSong = false,
                                    durationMs = -1L
                                )
                                radioPlayer.updateSessionMetadata(updatedMeta)
                            }
                        }
                    }
                }
            }
        }

        if (playbackMode == PlaybackMode.TRACKLIST) {
            activeTracklistStation?.stopCleanTrackPlayback()
            activeTracklistStation = null
            activeTracklistStationId = null
            activeTracklistTracks = emptyList()
            activeTracklistIndex = -1
            radioPlayer.stopCleanQueue()
            radioPlayer.isTracklistMode = false
            playbackMode = PlaybackMode.RADIO
        }

        val group = getStationGroupContainingMedia(mediaId) ?: return
        val st = getRadioStation(group, mediaId) ?: return
        val stationIndex = group.getStationIndex(st.mediaId)
        startStationWithTuningNoise(group, st, stationIndex, trigger = "in_app")
    }

    private fun handlePause() {
        if (playbackMode == PlaybackMode.TRACKLIST) {
            radioPlayer.pauseInternal()
            mediaLibrarySession.setSessionExtras(buildSessionExtras())
            return
        }

        val st = activeStation ?: return
        DynamicThemeManager.stopTuningNoise("pause")
        radioPlayer.setStreamMuted(false)
        st.stop()
        radioPlayer.pauseInternal()
    }

    @Volatile private var isHandlingStop = false

    private fun handleStop() {
        if (isHandlingStop) return
        isHandlingStop = true
        try {
            radioPlayer.setTransitionLoading(false)
            radioPlayer.hasActiveQueueOrStation = false
            radioPlayer.notifyAvailableCommandsChanged()
            if (playbackMode == PlaybackMode.TRACKLIST) {
                activeTracklistStation?.stopCleanTrackPlayback()
                activeTracklistStation = null
                activeTracklistStationId = null
                activeTracklistTracks = emptyList()
                activeTracklistIndex = -1
                radioPlayer.stopCleanQueue()
                mediaLibrarySession.setSessionExtras(buildSessionExtras())
                return
            }

            val stationToStop = activeStation
            activeStation = null
            DynamicThemeManager.stopTuningNoise("stop")
            radioPlayer.setStreamMuted(false)
            stationToStop?.stop()
            radioPlayer.stopInternal()
        } finally {
            isHandlingStop = false
        }
    }

    private fun handleSkipNext() {
        if (playbackMode == PlaybackMode.TRACKLIST) {
            val nextIdx = getNextTracklistIndex(loopAll = (repeatMode == TracklistContract.REPEAT_MODE_ALL))
            if (nextIdx != null) {
                playTracklistTrack(nextIdx)
            }
            return
        }

        val currentSt = activeStation
        val group = activePlaybackStationGroup
            ?: (currentSt?.stationGroupId?.let { getStationGroup(it) })
            ?: (currentSt?.let { getStationGroupContainingMedia(it.mediaId) })
            ?: (activeStationGroupId?.let { getStationGroup(it) })
            ?: (fullStationList.firstOrNull()?.mediaId?.let { getStationGroupContainingMedia(it) })
            ?: return

        if (group.stationList.isEmpty()) return
        val stationIndex = if (currentSt != null) group.getStationIndex(currentSt.mediaId) else -1
        val nextStationIndex = if (stationIndex >= 0) {
            if (stationIndex + 1 >= group.stationList.size) 0 else (stationIndex + 1)
        } else {
            0
        }

        val nextItem = group.stationList[nextStationIndex]
        radioPlayer.setTransitionLoading(true)
        val targetArtUri = nextItem.mediaMetadata.artworkUri
        val targetArt = DynamicThemeManager.getCachedBitmap(targetArtUri, 512)
        val previewMeta = buildStationMediaMetadata(
            mediaId = nextItem.mediaId,
            title = nextItem.mediaMetadata.title?.toString() ?: "",
            subtitle = nextItem.mediaMetadata.subtitle?.toString() ?: "",
            artist = nextItem.mediaMetadata.artist?.toString() ?: "",
            albumArtist = "",
            stationTitle = nextItem.mediaMetadata.title?.toString() ?: "",
            groupTitle = nextItem.mediaMetadata.albumTitle?.toString() ?: "",
            art = targetArt,
            isSong = false,
            durationMs = -1L
        )
        radioPlayer.updateSessionMetadata(previewMeta)

        if (targetArt == null && targetArtUri != null) {
            val currentStationId = nextItem.mediaId
            val token = stationSwitchToken
            serviceScope.launch(Dispatchers.IO) {
                val loaded = DynamicThemeManager.loadBitmap(applicationContext, targetArtUri, 512)
                if (loaded != null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (token == stationSwitchToken && activeStation?.mediaId == currentStationId) {
                            val updatedMeta = buildStationMediaMetadata(
                                mediaId = currentStationId,
                                title = nextItem.mediaMetadata.title?.toString() ?: "",
                                subtitle = nextItem.mediaMetadata.subtitle?.toString() ?: "",
                                artist = nextItem.mediaMetadata.artist?.toString() ?: "",
                                albumArtist = "",
                                stationTitle = nextItem.mediaMetadata.title?.toString() ?: "",
                                groupTitle = nextItem.mediaMetadata.albumTitle?.toString() ?: "",
                                art = loaded,
                                isSong = false,
                                durationMs = -1L
                            )
                            radioPlayer.updateSessionMetadata(updatedMeta)
                        }
                    }
                }
            }
        }

        val nextSt = getRadioStation(group, nextItem.mediaId) ?: return
        startStationWithTuningNoise(group, nextSt, nextStationIndex, trigger = "skip_next")
    }

    private fun handleSkipPrevious() {
        if (playbackMode == PlaybackMode.TRACKLIST) {
            if (radioPlayer.getCurrentTrackPosition() > 3000L) {
                radioPlayer.seekToMs(0L)
                return
            }
            if (shuffleEnabled) {
                if (tracklistPlayedIndices.size > 1) {
                    tracklistPlayedIndices.removeAt(tracklistPlayedIndices.lastIndex)
                    val prevIdx = tracklistPlayedIndices.removeAt(tracklistPlayedIndices.lastIndex)
                    playTracklistTrack(prevIdx)
                    return
                }
            }
            val prevIdx = if (activeTracklistIndex - 1 >= 0) {
                activeTracklistIndex - 1
            } else if (repeatMode == TracklistContract.REPEAT_MODE_ALL) {
                activeTracklistTracks.lastIndex
            } else {
                0
            }
            playTracklistTrack(prevIdx)
            return
        }

        val currentSt = activeStation
        val group = activePlaybackStationGroup
            ?: (currentSt?.stationGroupId?.let { getStationGroup(it) })
            ?: (currentSt?.let { getStationGroupContainingMedia(it.mediaId) })
            ?: (activeStationGroupId?.let { getStationGroup(it) })
            ?: (fullStationList.firstOrNull()?.mediaId?.let { getStationGroupContainingMedia(it) })
            ?: return

        if (group.stationList.isEmpty()) return
        val stationIndex = if (currentSt != null) group.getStationIndex(currentSt.mediaId) else -1
        val prevStationIndex = if (stationIndex >= 0) {
            if (stationIndex - 1 < 0) group.stationList.lastIndex else (stationIndex - 1)
        } else {
            0
        }

        val prevItem = group.stationList[prevStationIndex]
        radioPlayer.setTransitionLoading(true)
        val targetArtUri = prevItem.mediaMetadata.artworkUri
        val targetArt = DynamicThemeManager.getCachedBitmap(targetArtUri, 512)
        val previewMeta = buildStationMediaMetadata(
            mediaId = prevItem.mediaId,
            title = prevItem.mediaMetadata.title?.toString() ?: "",
            subtitle = prevItem.mediaMetadata.subtitle?.toString() ?: "",
            artist = prevItem.mediaMetadata.artist?.toString() ?: "",
            albumArtist = "",
            stationTitle = prevItem.mediaMetadata.title?.toString() ?: "",
            groupTitle = prevItem.mediaMetadata.albumTitle?.toString() ?: "",
            art = targetArt,
            isSong = false,
            durationMs = -1L
        )
        radioPlayer.updateSessionMetadata(previewMeta)

        if (targetArt == null && targetArtUri != null) {
            val currentStationId = prevItem.mediaId
            val token = stationSwitchToken
            serviceScope.launch(Dispatchers.IO) {
                val loaded = DynamicThemeManager.loadBitmap(applicationContext, targetArtUri, 512)
                if (loaded != null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (token == stationSwitchToken && activeStation?.mediaId == currentStationId) {
                            val updatedMeta = buildStationMediaMetadata(
                                mediaId = currentStationId,
                                title = prevItem.mediaMetadata.title?.toString() ?: "",
                                subtitle = prevItem.mediaMetadata.subtitle?.toString() ?: "",
                                artist = prevItem.mediaMetadata.artist?.toString() ?: "",
                                albumArtist = "",
                                stationTitle = prevItem.mediaMetadata.title?.toString() ?: "",
                                groupTitle = prevItem.mediaMetadata.albumTitle?.toString() ?: "",
                                art = loaded,
                                isSong = false,
                                durationMs = -1L
                            )
                            radioPlayer.updateSessionMetadata(updatedMeta)
                        }
                    }
                }
            }
        }

        val prevSt = getRadioStation(group, prevItem.mediaId) ?: return
        startStationWithTuningNoise(group, prevSt, prevStationIndex, trigger = "skip_prev")
    }

    private fun startStationWithTuningNoise(
        group: StationGroup,
        station: RadioStation,
        stationIndex: Int,
        trigger: String = "in_app"
    ) {
        val switchToken = ++stationSwitchToken
        val switchStartTime = SystemClock.uptimeMillis()
        playerReadyForCurrentSwitch = false

        radioPlayer.setTransitionLoading(true)
        val departingStation = activeStation
        if (departingStation != null && departingStation !== station) {
            departingStation.stop()
        }

        activeStation = station
        activeStationGroupId = group.mediaItem.mediaId
        activePlaybackStationGroup = group

        val canCycle = group.stationList.size > 1
        radioPlayer.hasActiveQueueOrStation = true
        radioPlayer.updateNavigationCommands(canCycle, canCycle)

        val noisePack = DynamicThemeManager.findNoisePackForSeries(applicationContext, group.groupName)
        val hasJson = noisePack?.hasThemeJson ?: false
        val soundCount = noisePack?.noiseSoundUris?.size ?: 0
        val shouldPlayNoise = noisePack != null && soundCount > 0 && hasJson

        GTRadioLogger.log("[TUNE] Trigger: $trigger -> station '${station.stationName}' (index=$stationIndex) in series '${group.groupName}' | pack: matched=${noisePack != null}, hasJson=$hasJson, sounds=$soundCount")

        radioPlayer.resetForStationSwitch()

        if (shouldPlayNoise) {
            radioPlayer.setStreamMuted(true)
            station.play()

            DynamicThemeManager.playTuningNoiseUntilReady(
                applicationContext,
                noisePack!!,
                isRadioReady = {
                    playerReadyForCurrentSwitch && radioPlayer.isExoPlayerReady()
                },
                onComplete = {
                    if (switchToken == stationSwitchToken) {
                        val elapsedMs = SystemClock.uptimeMillis() - switchStartTime
                        GTRadioLogger.log("[TUNE] Noise finished ($trigger) for '${station.stationName}' in ${elapsedMs}ms, unmuting stream")
                        radioPlayer.setStreamMuted(false)
                        if (activeStation?.mediaId == station.mediaId) {
                            radioPlayer.updateSessionMetadata(station.metadata)
                            radioPlayer.playInternal()
                        }
                    }
                }
            )
        } else {
            DynamicThemeManager.stopTuningNoise("no_noise_pack")
            radioPlayer.setStreamMuted(false)
            station.play()
            val elapsedMs = SystemClock.uptimeMillis() - switchStartTime
            GTRadioLogger.log("[TUNE] Play ($trigger) for '${station.stationName}' in ${elapsedMs}ms (no noise pack)")
        }

        radioPlayer.updateSessionMetadata(station.metadata)
        radioPlayer.playInternal()
        mediaLibrarySession.setSessionExtras(buildSessionExtras())
        persistPlaybackState()
    }

    private fun playTracklistTrack(index: Int) {
        if (index !in activeTracklistTracks.indices) return
        val station = activeTracklistStation ?: return
        val track = activeTracklistTracks[index]
        val reqGen = tracklistRequestGen.incrementAndGet()

        // Stop in-flight decode on previous station and any radio station
        activeTracklistStation?.stopCleanTrackPlayback()
        activeTracklistStation?.stop()
        activeStation?.stop()
        radioPlayer.stopCleanQueue()
        radioPlayer.isTracklistMode = true

        radioPlayer.setTransitionLoading(true)

        val cachedArt = AlbumArtDownloader.getMemoryCachedAlbumArt(track.artist, track.title)
            ?: AlbumArtDownloader.getCachedAlbumArt(applicationContext, track.artist, track.title)
        publishTracklistMetadata(track, index, cachedArt, 0L)

        radioPlayer.stopInternal()

        activeTracklistIndex = index
        if (index !in tracklistPlayedIndices) {
            tracklistPlayedIndices.add(index)
        }

        val canNav = activeTracklistTracks.size > 1
        radioPlayer.hasActiveQueueOrStation = true
        radioPlayer.updateNavigationCommands(canNav, canNav)

        val expectedIndex = index
        val expectedTrackId = track.id
        if (cachedArt == null) {
            serviceScope.launch(Dispatchers.IO) {
                val ok = AlbumArtDownloader.fetchEraAccurateArtSync(
                    context = applicationContext,
                    artist = track.artist,
                    songTitle = track.title,
                    preferredYear = track.maxYear,
                    maxYear = track.maxYear
                )
                if (ok) {
                    val newArt = AlbumArtDownloader.getCachedAlbumArt(applicationContext, track.artist, track.title)
                    if (newArt != null) {
                        withContext(Dispatchers.Main.immediate) {
                            if (playbackMode == PlaybackMode.TRACKLIST &&
                                tracklistRequestGen.get() == reqGen &&
                                activeTracklistIndex == expectedIndex &&
                                activeTracklistTracks.getOrNull(expectedIndex)?.id == expectedTrackId
                            ) {
                                val dur = radioPlayer.getDurationMs()
                                publishTracklistMetadata(track, expectedIndex, newArt, dur)
                            }
                        }
                    }
                }
            }
        }

        if (tracklistRequestGen.get() != reqGen) return

        station.playCleanTrack(
            track.id,
            radioPlayer,
            onTrackFinished = {
                serviceScope.launch(Dispatchers.Main.immediate) {
                    if (tracklistRequestGen.get() == reqGen) {
                        onTracklistSongFinished()
                    }
                }
            },
            onMetadataReady = { _, _, _, _ -> }
        )

        persistPlaybackState()
    }

    private fun publishTracklistMetadata(
        track: TracklistTrack,
        index: Int,
        art: Bitmap?,
        durationMs: Long
    ) {
        val songMediaId = TracklistContract.SONG_PREFIX + (activeTracklistStationId ?: "") + "::" + track.id
        val meta = buildStationMediaMetadata(
            mediaId = songMediaId,
            title = track.title,
            subtitle = "${track.artist} • ${track.stationName}",
            artist = track.artist,
            albumArtist = track.stationName,
            stationTitle = track.stationName,
            groupTitle = track.stationName,
            art = art,
            isSong = true,
            durationMs = durationMs
        )
        radioPlayer.updateSessionMetadata(meta)
        mediaLibrarySession.setSessionExtras(buildSessionExtras())
    }

    private fun onTracklistSongFinished() {
        if (playbackMode != PlaybackMode.TRACKLIST) return
        if (activeTracklistTracks.isEmpty()) return

        when (repeatMode) {
            TracklistContract.REPEAT_MODE_ONE -> {
                playTracklistTrack(activeTracklistIndex)
            }
            TracklistContract.REPEAT_MODE_ALL -> {
                val nextIdx = getNextTracklistIndex(loopAll = true)
                if (nextIdx != null) {
                    playTracklistTrack(nextIdx)
                } else {
                    radioPlayer.stopCleanQueue()
                }
            }
            else -> { // REPEAT_MODE_NONE
                val nextIdx = getNextTracklistIndex(loopAll = false)
                if (nextIdx != null) {
                    playTracklistTrack(nextIdx)
                } else {
                    radioPlayer.stopCleanQueue()
                }
            }
        }
    }

    private fun getNextTracklistIndex(loopAll: Boolean): Int? {
        if (activeTracklistTracks.isEmpty()) return null
        if (shuffleEnabled) {
            val unplayed = activeTracklistTracks.indices.filter { it !in tracklistPlayedIndices }
            if (unplayed.isNotEmpty()) {
                return unplayed.random()
            }
            if (loopAll) {
                tracklistPlayedIndices.clear()
                if (activeTracklistIndex >= 0) tracklistPlayedIndices.add(activeTracklistIndex)
                val eligible = activeTracklistTracks.indices.filter { it != activeTracklistIndex }.ifEmpty { activeTracklistTracks.indices.toList() }
                return eligible.random()
            }
            return null
        } else {
            val next = activeTracklistIndex + 1
            return if (next < activeTracklistTracks.size) {
                next
            } else if (loopAll) {
                0
            } else {
                null
            }
        }
    }

    private fun getStationGroups(): ArrayList<StationGroup> {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val radioPath = sharedPref?.getString(getString(R.string.radio_folders_uri_key), "") ?: return ArrayList()
        if (radioPath.isBlank()) { return ArrayList() }
        val dir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(radioPath))
        stationCache.clear()
        val scanStart = System.currentTimeMillis()
        val groups = StationGroup.scanRootDirectory(dir, applicationContext)
        val scanElapsed = System.currentTimeMillis() - scanStart
        GTRadioLogger.logVerbose("SCAN") {
            "getStationGroups: Scanned ${groups.size} group(s) in ${scanElapsed}ms"
        }
        return groups
    }

    private fun ensureGroupsLoadedSync(currentUri: String) {
        if (!groupsLoaded || cachedUri != currentUri) {
            if (!cacheHydrated && currentUri.isNotBlank()) {
                val cacheStart = System.currentTimeMillis()
                val cached = LibraryIndexCache.loadCache(applicationContext, currentUri)
                if (cached != null) {
                    val hydratedGroups = LibraryIndexCache.hydrateGroups(cached, applicationContext)
                    val flatList = ArrayList(hydratedGroups.map { it.stationList }.flatten())
                    val cacheElapsed = System.currentTimeMillis() - cacheStart
                    synchronized(this) {
                        stationGroups = hydratedGroups
                        fullStationList = flatList
                        cachedUri = currentUri
                        groupsLoaded = true
                        cacheHydrated = true
                    }
                    GTRadioLogger.log("[SCAN] LibraryIndexCache HIT: hydrated ${hydratedGroups.size} group(s) in ${cacheElapsed}ms")

                    // Quiet background SAF validation pass
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            DynamicThemeManager.refreshIfNeeded(applicationContext, cached.themeDirUri)
                            AlbumHintRepository.ensureLoaded(applicationContext)
                            val freshGroups = getStationGroups()
                            val freshList = ArrayList(freshGroups.map { x -> x.stationList }.flatten())
                            synchronized(this@GTRadioMusicService) {
                                stationGroups = freshGroups
                                fullStationList = freshList
                            }
                            LibraryIndexCache.saveCache(applicationContext, currentUri, freshGroups)
                            GTRadioLogger.log("[SCAN] LibraryIndexCache: Background SAF validation complete, ${freshGroups.size} group(s)")
                            withContext(Dispatchers.Main.immediate) {
                                mediaLibrarySession.notifyChildrenChanged("root", freshGroups.size, null)
                                mediaLibrarySession.notifyChildrenChanged(TracklistContract.ROOT_ID, freshGroups.size, null)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            GTRadioLogger.log("[SCAN] LibraryIndexCache: Background validation pass failed", e)
                        }
                    }
                    return
                } else {
                    val cacheElapsed = System.currentTimeMillis() - cacheStart
                    GTRadioLogger.log("[SCAN] LibraryIndexCache MISS: cache check took ${cacheElapsed}ms, running full SAF scan")
                }
            }

            val scanStart = System.currentTimeMillis()
            AlbumHintRepository.ensureLoaded(applicationContext)
            val groups = getStationGroups()
            val fullList = ArrayList(groups.map { x -> x.stationList }.flatten())
            val scanElapsed = System.currentTimeMillis() - scanStart
            synchronized(this) {
                stationGroups = groups
                fullStationList = fullList
                cachedUri = currentUri
                groupsLoaded = true
                cacheHydrated = true
            }
            GTRadioLogger.log("[SCAN] LibraryIndexCache MISS: full SAF scan in ${scanElapsed}ms")
            if (currentUri.isNotBlank()) {
                LibraryIndexCache.saveCache(applicationContext, currentUri, groups)
                serviceScope.launch(Dispatchers.IO) {
                    val themeUri = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                        .getString(applicationContext.getString(R.string.dynamic_theme_uri_key), null)
                    DynamicThemeManager.refreshIfNeeded(applicationContext, themeUri)
                }
            }
        }
    }

    private fun buildTracklistRootItems(): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        for (group in stationGroups) {
            var hasTracklistStation = false
            for (stMedia in group.stationList) {
                val stId = stMedia.mediaId
                val st = getRadioStation(group, stId)
                if (st is TracklistCapableStation && st.supportsTracklist()) {
                    hasTracklistStation = true
                    break
                }
            }
            if (hasTracklistStation) {
                val desc = MediaMetadata.Builder()
                    .setTitle(group.mediaItem.mediaMetadata.title)
                    .setSubtitle("Tracklists")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
                items.add(
                    MediaItem.Builder()
                        .setMediaId(TracklistContract.GROUP_PREFIX + group.mediaItem.mediaId)
                        .setMediaMetadata(desc)
                        .build()
                )
            }
        }
        return items
    }

    private fun buildTracklistGroupStations(rawGroupId: String): List<MediaItem> {
        val group = stationGroups.find { it.mediaItem.mediaId == rawGroupId } ?: return emptyList()
        val items = ArrayList<MediaItem>()
        for (stMedia in group.stationList) {
            val stId = stMedia.mediaId
            val st = getRadioStation(group, stId)
            if (st is TracklistCapableStation && st.supportsTracklist()) {
                val trackCount = st.getCleanTracks().size
                val extras = Bundle().apply {
                    putString(TracklistContract.EXTRA_STATION_ID, stId)
                    putString(TracklistContract.EXTRA_STATION_NAME, st.stationName)
                    putInt(TracklistContract.EXTRA_STATION_TRACK_COUNT, trackCount)
                    putInt(TracklistContract.EXTRA_TRACK_COUNT, trackCount)
                }
                val meta = MediaMetadata.Builder()
                    .setTitle(st.stationName)
                    .setSubtitle("$trackCount tracks")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(extras)
                    .build()
                items.add(
                    MediaItem.Builder()
                        .setMediaId(TracklistContract.STATION_PREFIX + stId)
                        .setMediaMetadata(meta)
                        .build()
                )
            }
        }
        return items
    }

    private fun buildTracklistStationSongs(rawStationId: String): List<MediaItem> {
        val group = getStationGroupContainingMedia(rawStationId) ?: return emptyList()
        val st = getRadioStation(group, rawStationId) as? TracklistCapableStation ?: return emptyList()
        if (!st.supportsTracklist()) return emptyList()

        val tracks = st.getCleanTracks()
        val items = ArrayList<MediaItem>(tracks.size)
        for ((idx, track) in tracks.withIndex()) {
            val extras = Bundle().apply {
                putString(TracklistContract.EXTRA_STATION_ID, rawStationId)
                putString(TracklistContract.EXTRA_TRACK_ID, track.id)
                putString(TracklistContract.EXTRA_TRACK_TITLE, track.title)
                putString(TracklistContract.EXTRA_TRACK_ARTIST, track.artist)
                putString(TracklistContract.EXTRA_TRACK_ALBUM, track.stationName)
                putInt(TracklistContract.EXTRA_TRACK_MAX_YEAR, track.maxYear)
                putInt(TracklistContract.EXTRA_TRACK_INDEX, idx)
                putInt(TracklistContract.EXTRA_TRACK_COUNT, tracks.size)
                putInt(TracklistContract.EXTRA_STATION_TRACK_COUNT, tracks.size)
            }
            val meta = MediaMetadata.Builder()
                .setTitle(track.title)
                .setSubtitle(track.artist)
                .setArtist(track.artist)
                .setAlbumTitle(track.stationName)
                .setDescription(track.stationName)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)
                .build()
            items.add(
                MediaItem.Builder()
                    .setMediaId(TracklistContract.SONG_PREFIX + rawStationId + "::" + track.id)
                    .setMediaMetadata(meta)
                    .build()
            )
        }
        return items
    }

    private fun getRadioStation(group: StationGroup, mediaId: String): RadioStation? {
        if (mediaId.isBlank()) return null

        if (!fullStationList.any { x -> x.mediaId == mediaId }) {
            return null
        }

        if (stationCache.containsKey(mediaId)) {
            return stationCache[mediaId]
        }

        val station = buildRadioStation(group, mediaId)
        if (station != null) {
            station.onMetadataChanged = { updatedMeta ->
                if (activeStation?.mediaId == station.mediaId) {
                    radioPlayer.updateSessionMetadata(updatedMeta)
                }
            }
            stationCache[station.mediaItem.mediaId] = station
        }
        return station
    }

    private fun buildRadioStation(group: StationGroup, mediaId: String): RadioStation? {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val adsEnabled = sharedPref?.getBoolean(getString(R.string.ads_enabled_preference_key), false) ?: false
        val weatherChatterEnabled = sharedPref?.getBoolean(getString(R.string.weather_chatter_enabled_preference_key), false) ?: false
        val newsReportsEnabled = sharedPref?.getBoolean(getString(R.string.news_reports_preference_key), false) ?: false

        val stationMedia = group.stationList.find { x -> x.mediaId == mediaId } ?: return null
        if (group.isGen1RawGroup) {
            GTRadioLogger.log("GTRadioMusicService: Creating Gen1 Raw station for ${stationMedia.mediaMetadata.title}")
            return group.createGen1RawStation(
                group.mediaItem.mediaId,
                stationMedia.mediaId,
                stationMedia,
                radioPlayer,
                applicationContext,
                adsEnabled,
                weatherChatterEnabled,
                newsReportsEnabled
            )
        }
        if (group.isRpf3Group) {
            if (!Rpf3KeyManager.isUnlocked(this)) {
                Rpf3KeyManager.ensureKeyLoaded(this)
            }
            if (!Rpf3KeyManager.isUnlocked(this)) {
                serviceScope.launch(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        applicationContext,
                        "GTA IV/EFLC key not found. Select a folder containing GTAIV.exe, EFLC.exe, or a key .dat file in Settings -> Game Key Directory.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return null
            }
            GTRadioLogger.log("GTRadioMusicService: Creating GTA IV RPF station for ${stationMedia.mediaMetadata.title}")
            return group.createRpf3Station(
                group.mediaItem.mediaId,
                stationMedia.mediaId,
                stationMedia,
                radioPlayer,
                applicationContext,
                adsEnabled,
                weatherChatterEnabled,
                newsReportsEnabled
            )
        }
        if (group.isRpf7Group) {
            if (!Rpf7KeyManager.isUnlocked(this)) {
                Rpf7KeyManager.ensureKeysLoaded(this)
            }
            if (!Rpf7KeyManager.isUnlocked(this)) {
                serviceScope.launch(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        applicationContext,
                        "GTA V keys not found. Select a folder with GTA5.exe or key files in Settings -> Game Key Directory.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return null
            }
            GTRadioLogger.log("GTRadioMusicService: Creating GTA V RPF station for ${stationMedia.mediaMetadata.title}")
            return group.createRpf7Station(
                group.mediaItem.mediaId,
                stationMedia.mediaId,
                stationMedia,
                radioPlayer,
                applicationContext,
                adsEnabled,
                weatherChatterEnabled,
                newsReportsEnabled
            )
        }
        if (group.isGen2RawGroup && mediaId.contains("#SA_RAW#")) {
            val code = mediaId.substringAfter("#SA_RAW#")
            val stationArch = group.getOrCreateArchive(code) ?: return null
            val advertsArch = group.getOrCreateArchive("ADVERTS")
            val stMeta = Gen2MetadataRepository.getByStreamCode(applicationContext, code) ?: return null
            return Gen2RawRadioStation(
                group.mediaItem.mediaId,
                stationMedia.mediaId,
                stationMedia,
                stationArch,
                advertsArch,
                stMeta,
                radioPlayer,
                applicationContext,
                adsEnabled,
                weatherChatterEnabled
            )
        }
        val folderContents = group.folderDoc.listFiles()
        val stationDoc = folderContents.find { x -> x.isDirectory && x.uri.toString() == stationMedia.mediaId } ?: return null

        when (group.generation) {
            1 -> {
                return Gen1RadioStation(group.mediaItem.mediaId, stationMedia.mediaId, stationMedia, stationDoc, radioPlayer, applicationContext)
            }
            2 -> {
                val advertsDoc = folderContents.find { x -> x.name?.uppercase()?.contains("ADVERTS") == true } ?: return null
                return Gen2RadioStation(group.mediaItem.mediaId, stationMedia.mediaId, stationMedia, stationDoc, advertsDoc, radioPlayer, applicationContext, adsEnabled, weatherChatterEnabled)
            }
            3 -> {
                val advertsDoc = folderContents.find { x -> x.name?.uppercase()?.contains("ADVERTS") == true } ?: return null
                val newsDoc = folderContents.find { x -> x.name?.uppercase()?.contains("NEWS") == true } ?: return null
                val weatherDoc = folderContents.find { x -> x.name?.uppercase()?.contains("WEATHER") == true } ?: return null
                return Gen3RadioStation(group.mediaItem.mediaId, stationMedia.mediaId, stationMedia, stationDoc, advertsDoc, newsDoc, weatherDoc, radioPlayer, applicationContext, adsEnabled, weatherChatterEnabled, newsReportsEnabled)
            }
            else -> {
                return null
            }
        }
    }

    private fun getStationGroupContainingMedia(mediaId: String): StationGroup? {
        return stationGroups.find { x -> x.stationList.any { y -> y.mediaId == mediaId } }
    }

    private fun getStationGroup(stationGroupId: String): StationGroup? {
        return stationGroups.find { x -> x.mediaItem.mediaId == stationGroupId }
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && exoPlayer.playWhenReady) {
                playerReadyForCurrentSwitch = true
            }
            if (playbackMode == PlaybackMode.TRACKLIST && playbackState == Player.STATE_READY) {
                val currentTrack = activeTracklistTracks.getOrNull(activeTracklistIndex)
                if (currentTrack != null) {
                    val dur = radioPlayer.getDurationMs()
                    val art = AlbumArtDownloader.getCachedAlbumArt(applicationContext, currentTrack.artist, currentTrack.title)
                    publishTracklistMetadata(currentTrack, activeTracklistIndex, art, dur)
                }
                mediaLibrarySession.setSessionExtras(buildSessionExtras())
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && exoPlayer.playbackState == Player.STATE_READY) {
                playerReadyForCurrentSwitch = true
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            radioPlayer.setTransitionLoading(false)
            var message = R.string.generic_error
            when (error.errorCode) {
                ExoPlaybackException.TYPE_SOURCE -> {
                    message = R.string.error_media_not_found
                    Log.e("GTRadioMusicService", "TYPE_SOURCE: " + error.message)
                }
                ExoPlaybackException.TYPE_RENDERER -> {
                    Log.e("GTRadioMusicService", "TYPE_RENDERER: " + error.message)
                }
                ExoPlaybackException.TYPE_UNEXPECTED -> {
                    Log.e("GTRadioMusicService", "TYPE_UNEXPECTED: " + error.message)
                }
                ExoPlaybackException.TYPE_REMOTE -> {
                    Log.e("GTRadioMusicService", "TYPE_REMOTE: " + error.message)
                }
            }
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (playbackMode == PlaybackMode.TRACKLIST) {
            activeTracklistStation?.stopCleanTrackPlayback()
            radioPlayer.stopCleanQueue()
            radioPlayer.isTracklistMode = false
        }
        for (st in stationCache.values) {
            st.release()
        }
        stationCache.clear()
        mediaLibrarySession.run {
            release()
        }
        radioPlayer.removeListener(playerListener)
        radioPlayer.release()
        super.onDestroy()
    }
}
