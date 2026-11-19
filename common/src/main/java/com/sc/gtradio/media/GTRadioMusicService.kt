package com.sc.gtradio.media

import GTRadioNotificationManager
import GTRadioPlayer
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.preference.PreferenceManager
import android.support.v4.media.MediaBrowserCompat.MediaItem
import androidx.media.MediaBrowserServiceCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SimpleExoPlayer
import androidx.media3.ui.PlayerNotificationManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.stations.*
import java.util.ArrayList
import java.util.concurrent.Executors

@UnstableApi open class GTRadioMusicService : MediaBrowserServiceCompat() {
    private var stationGroups: ArrayList<StationGroup> = ArrayList()
    private var fullStationList: ArrayList<MediaItem> = ArrayList()
    private val stationCache: MutableMap<String, RadioStation> = mutableMapOf()
    private val sharedPrefListener = GTRadioOnSharedPrefChange()

    private val scanExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var groupsLoaded = false
    private var cachedUri: String? = null
    private val pendingRootResults = ArrayList<Result<MutableList<MediaItem>>>()

    //Setup a listener for settings changes
    private inner class GTRadioOnSharedPrefChange : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (prefs != null) {
                when(key) {
                    "dynamic_theme_uri", "game_key_folder_uri", "gtav_key_folder_uri", "game_keys_updated", "gtav_keys_updated", resources.getString(R.string.radio_folders_uri_key) -> {
                        GTRadioLogger.log("GTRadioMusicService: Library URI or Game keys changed, invalidating cache...")
                        this@GTRadioMusicService.activeStation?.stop()
                        this@GTRadioMusicService.activeStation = null
                        this@GTRadioMusicService.stationCache.clear()
                        synchronized(this@GTRadioMusicService.pendingRootResults) {
                            this@GTRadioMusicService.groupsLoaded = false
                            this@GTRadioMusicService.cachedUri = null
                            this@GTRadioMusicService.stationGroups = ArrayList()
                            this@GTRadioMusicService.fullStationList = ArrayList()
                        }
                        DynamicThemeManager.invalidateCache()
                        this@GTRadioMusicService.scanExecutor.submit {
                            Handler(Looper.getMainLooper()).post {
                                this@GTRadioMusicService.notifyChildrenChanged("root")
                            }
                        }
                    }
                    resources.getString(R.string.ads_enabled_preference_key)  -> {
                        //Update ads enabled
                        if (this@GTRadioMusicService.activeStation != null) {
                            val newValue = prefs.getBoolean(key, false)
                            this@GTRadioMusicService.activeStation?.adsEnabled = newValue
                        }
                    }
                    resources.getString(R.string.weather_chatter_enabled_preference_key) -> {
                        //Update weather chatter enabled
                        if (this@GTRadioMusicService.activeStation != null) {
                            val newValue = prefs.getBoolean(key, false)
                            this@GTRadioMusicService.activeStation?.weatherChatterEnabled = newValue
                        }
                    }
                    resources.getString(R.string.news_reports_preference_key) -> {
                        //Update news reports enabled
                        if (this@GTRadioMusicService.activeStation != null) {
                            val newValue = prefs.getBoolean(key, false)
                            this@GTRadioMusicService.activeStation?.newsReportsEnabled = newValue
                        }
                    }
                }
            }
        }
    }

    private lateinit var session: MediaSessionCompat

    private var isForegroundService = false

    private lateinit var notificationManager: GTRadioNotificationManager
    private val gtrAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()
    private val playerListener = PlayerEventListener()


    //Exoplayer will handle audio focus
    private val exoPlayer: ExoPlayer by lazy {
        SimpleExoPlayer.Builder(this).build().apply {
            setAudioAttributes(gtrAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
    }

    //Our own custom player will wrap ExoPlayer in order to provide more accurate playback status as well as other fields for our faux-stream use case
    private val radioPlayer: GTRadioPlayer by lazy {
        GTRadioPlayer(exoPlayer).apply {
            addListener(playerListener)
        }
    }

    private var activeStation: RadioStation? = null
    private var activeStationGroupId: String? = null
    @Volatile private var stationSwitchToken = 0L
    @Volatile private var playerReadyForCurrentSwitch = false

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onCommand(command: String?, extras: Bundle?, cb: ResultReceiver?) {
            super.onCommand(command, extras, cb)
            when (command) {
                "setActiveStationGroupId" -> {
                    activeStationGroupId = extras?.getString("stationGroupId")
                }
                "setStreamMuted" -> {
                    val muted = extras?.getBoolean("muted", false) ?: false
                    radioPlayer.setStreamMuted(muted)
                }
            }
        }

        private fun configureLoopingNavigationActions(group: StationGroup, stationIndex: Int): Long {
            val canCycle = group.stationList.size > 1
            radioPlayer.nextEnabled = canCycle
            radioPlayer.previousEnabled = canCycle
            var actions = group.getPlaybackActionsForStation(stationIndex)
            if (canCycle) {
                actions = actions or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            }
            return actions
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

            activeStation = station
            activeStationGroupId = group.mediaItem.mediaId

            val playbackActions = configureLoopingNavigationActions(group, stationIndex)
            val noisePack = DynamicThemeManager.findNoisePackForSeries(applicationContext, group.groupName)
            val hasJson = noisePack?.hasThemeJson ?: false
            val soundCount = noisePack?.noiseSoundUris?.size ?: 0
            val shouldPlayNoise = noisePack != null && soundCount > 0 && hasJson

            GTRadioLogger.log("[TUNE] Trigger: $trigger -> station '${station.stationName}' (index=$stationIndex) in series '${group.groupName}' | pack: matched=${noisePack != null}, hasJson=$hasJson, sounds=$soundCount")

            // Stop/clear old ExoPlayer playback so stale STATE_READY cannot cause false-positive ready
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
                                session.setMetadata(station.metadata)
                                val playingState = PlaybackStateCompat.Builder()
                                    .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1F)
                                    .setActions(playbackActions)
                                    .build()
                                session.setPlaybackState(playingState)
                                notificationManager.showNotificationForPlayer(radioPlayer)
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

            val playbackState = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1F)
                .setActions(playbackActions)
                .build()
            session.setPlaybackState(playbackState)
            session.setMetadata(station.metadata)
            notificationManager.showNotificationForPlayer(radioPlayer)
        }

        override fun onPlay() {
            if (activeStation == null) {
                val group = (activeStationGroupId?.let { getStationGroup(it) })
                    ?: (fullStationList.firstOrNull()?.mediaId?.let { getStationGroupContainingMedia(it) })
                    ?: return
                val firstStation = group.stationList.firstOrNull() ?: return
                val st = getRadioStation(group, firstStation.mediaId!!) ?: return
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

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId.isNullOrBlank()) {
                return
            }

            val group = getStationGroupContainingMedia(mediaId) ?: return
            if (activeStation?.mediaId != mediaId) {
                activeStation?.stop()
            }
            val st = getRadioStation(group, mediaId) ?: return
            val stationIndex = group.getStationIndex(st.mediaId)
            startStationWithTuningNoise(group, st, stationIndex, trigger = "in_app")
        }

        override fun onPause() {
            val st = activeStation ?: return
            val group = (activeStationGroupId?.let { getStationGroup(it) })
                ?: getStationGroup(st.stationGroupId)
                ?: getStationGroupContainingMedia(st.mediaId)
                ?: return
            val stationIndex = group.getStationIndex(st.mediaId)
            val playbackActions = configureLoopingNavigationActions(group, stationIndex)

            DynamicThemeManager.stopTuningNoise("pause")
            radioPlayer.setStreamMuted(false)
            st.stop()
            val playbackState = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1F)
                .setActions(playbackActions)
                .build()
            session.setPlaybackState(playbackState)
        }

        override fun onStop() {
            val st = activeStation ?: return
            val group = (activeStationGroupId?.let { getStationGroup(it) })
                ?: getStationGroup(st.stationGroupId)
                ?: getStationGroupContainingMedia(st.mediaId)
                ?: return
            val stationIndex = group.getStationIndex(st.mediaId)
            val playbackActions = configureLoopingNavigationActions(group, stationIndex)

            DynamicThemeManager.stopTuningNoise("stop")
            radioPlayer.setStreamMuted(false)
            st.stop()
            val playbackState = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1F)
                .setActions(playbackActions)
                .build()
            session.setPlaybackState(playbackState)
        }

        override fun onSkipToNext() {
            if (activeStation == null) {
                val group = (activeStationGroupId?.let { getStationGroup(it) })
                    ?: (fullStationList.firstOrNull()?.mediaId?.let { getStationGroupContainingMedia(it) })
                    ?: return
                val firstStation = group.stationList.firstOrNull() ?: return
                val st = getRadioStation(group, firstStation.mediaId!!) ?: return
                startStationWithTuningNoise(group, st, 0, trigger = "skip_next")
                return
            }

            val currentSt = activeStation ?: return
            val group = (activeStationGroupId?.let { getStationGroup(it) })
                ?: getStationGroup(currentSt.stationGroupId)
                ?: getStationGroupContainingMedia(currentSt.mediaId)
                ?: return

            if (group.stationList.isEmpty()) return
            val stationIndex = group.getStationIndex(currentSt.mediaId)
            val nextStationIndex = if (stationIndex + 1 >= group.stationList.size) 0 else (stationIndex + 1)

            currentSt.stop()
            val nextSt = getRadioStation(group, group.stationList[nextStationIndex].mediaId!!) ?: return
            startStationWithTuningNoise(group, nextSt, nextStationIndex, trigger = "skip_next")
        }

        override fun onSkipToPrevious() {
            if (activeStation == null) {
                val group = (activeStationGroupId?.let { getStationGroup(it) })
                    ?: (fullStationList.firstOrNull()?.mediaId?.let { getStationGroupContainingMedia(it) })
                    ?: return
                val firstStation = group.stationList.firstOrNull() ?: return
                val st = getRadioStation(group, firstStation.mediaId!!) ?: return
                startStationWithTuningNoise(group, st, 0, trigger = "skip_prev")
                return
            }

            val currentSt = activeStation ?: return
            val group = (activeStationGroupId?.let { getStationGroup(it) })
                ?: getStationGroup(currentSt.stationGroupId)
                ?: getStationGroupContainingMedia(currentSt.mediaId)
                ?: return

            if (group.stationList.isEmpty()) return
            val stationIndex = group.getStationIndex(currentSt.mediaId)
            val prevStationIndex = if (stationIndex - 1 < 0) group.stationList.lastIndex else (stationIndex - 1)

            currentSt.stop()
            val prevSt = getRadioStation(group, group.stationList[prevStationIndex].mediaId!!) ?: return
            startStationWithTuningNoise(group, prevSt, prevStationIndex, trigger = "skip_prev")
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (fullStationList.isNullOrEmpty()) {
                return
            }

            if (query.isNullOrEmpty()) {
                //Play the current station or the first station
                this.onPlay()
                return
            }

            //TODO: Implement some sort of actual search algorithm rather than a .contains()
            val station = fullStationList.find { x -> x.isPlayable && x.description.description?.contains(query) == true }
            if (station != null) {
                this.onPlayFromMediaId(station.mediaId, null)
            }
        }

    }

    override fun onCreate() {
        super.onCreate()

        //Subscribe to any SharedPref changes for things like the base folder, ads, and weather chatter
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        sharedPref.registerOnSharedPreferenceChangeListener(sharedPrefListener)

        // Build a PendingIntent that can be used to launch the UI.
        val sessionActivityPendingIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_MUTABLE)
            }

        // Create a new MediaSession.
        session = MediaSessionCompat(this, "MusicService")
            .apply {
                setSessionActivity(sessionActivityPendingIntent)
                val playbackState = PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1F)
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
                    .build()
                setPlaybackState(playbackState)
                isActive = true
            }
        sessionToken = session.sessionToken
        session.setCallback(callback)
        /**
         * The notification manager will use our player and media session to decide when to post
         * notifications. When notifications are posted or removed our listener will be called, this
         * allows us to promote the service to foreground (required so that we're not killed if
         * the main UI is not visible).
         */
        notificationManager = GTRadioNotificationManager(
            this,
            session.sessionToken,
            PlayerNotificationListener()
        )
        notificationManager.showNotificationForPlayer(radioPlayer)
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

    private fun getFullStationList(): ArrayList<MediaItem> {
        return ArrayList(stationGroups.map { x -> x.stationList }.flatten())
    }

    override fun onDestroy() {
        //Free up our session
        session.run {
            isActive = false
            release()
        }

        // Free ExoPlayer resources.
        radioPlayer.removeListener(playerListener)
        radioPlayer.release()
        scanExecutor.shutdown()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val currentUri = sharedPref?.getString(getString(R.string.radio_folders_uri_key), "") ?: ""

        if (parentId.isBlank() || parentId == "root") {
            var needScan = false
            synchronized(pendingRootResults) {
                if (groupsLoaded && cachedUri == currentUri) {
                    val groups = ArrayList(stationGroups.map { x -> x.mediaItem })
                    GTRadioLogger.log("GTRadioMusicService.onLoadChildren(root): cached hit, sending ${groups.size} groups (0ms)")
                    result.sendResult(groups)
                    return
                }

                result.detach()
                pendingRootResults.add(result)
                if (pendingRootResults.size == 1) {
                    needScan = true
                } else {
                    GTRadioLogger.log("GTRadioMusicService.onLoadChildren(root): Scan already in progress, coalescing request (#${pendingRootResults.size})")
                }
            }

            if (needScan) {
                scanExecutor.submit {
                    GTRadioLogger.log("GTRadioMusicService: Starting background station group scan on scanExecutor")
                    val groups = getStationGroups()
                    val fullList = ArrayList(groups.map { x -> x.stationList }.flatten())
                    val groupMediaItems = ArrayList(groups.map { x -> x.mediaItem })

                    val callbacks: List<Result<MutableList<MediaItem>>>
                    synchronized(pendingRootResults) {
                        stationGroups = groups
                        fullStationList = fullList
                        cachedUri = currentUri
                        groupsLoaded = true
                        callbacks = ArrayList(pendingRootResults)
                        pendingRootResults.clear()
                    }

                    GTRadioLogger.log("GTRadioMusicService: Background scan complete, delivering to ${callbacks.size} pending client(s)")
                    for (cb in callbacks) {
                        try {
                            cb.sendResult(ArrayList(groupMediaItems))
                        } catch (e: Exception) {
                            GTRadioLogger.log("GTRadioMusicService.onLoadChildren: Client disconnected or failed to sendResult", e)
                        }
                    }
                }
            }
            return
        }

        if (groupsLoaded && cachedUri == currentUri) {
            val group = stationGroups.find { x -> x.mediaItem.mediaId == parentId }
            val stations = group?.stationList ?: ArrayList()
            result.sendResult(stations)
        } else {
            result.detach()
            scanExecutor.submit {
                if (!groupsLoaded || cachedUri != currentUri) {
                    val groups = getStationGroups()
                    val fullList = ArrayList(groups.map { x -> x.stationList }.flatten())
                    synchronized(pendingRootResults) {
                        stationGroups = groups
                        fullStationList = fullList
                        cachedUri = currentUri
                        groupsLoaded = true
                    }
                }
                val group = stationGroups.find { x -> x.mediaItem.mediaId == parentId }
                val stations = group?.stationList ?: ArrayList()
                try {
                    result.sendResult(stations)
                } catch (e: Exception) {
                    GTRadioLogger.log("GTRadioMusicService.onLoadChildren($parentId): Failed to sendResult", e)
                }
            }
        }
    }

    private fun getRadioStation(group: StationGroup, mediaId: String): RadioStation? {
        //Get the mediaId of the first station if none is provided
        if (mediaId.isBlank()) {
            return null
        }

        if (!fullStationList.any { x -> x.mediaId == mediaId }) {
            //Someone is asking us to play something that doesn't exist
            return null
        }

        if (mediaId.isBlank()) {
            //Really lost here, nothing has been found for this id
            return null
        }

        //Let's do a lookup and see if this is cached already
        if (stationCache.containsKey(mediaId)) {
            return stationCache[mediaId]
        }

        //Didn't have the station cached yet, so lets build it and cache it
        val station = buildRadioStation(group, mediaId)
        if (station != null) {
            station.onMetadataChanged = { updatedMeta ->
                if (activeStation?.mediaId == station.mediaId) {
                    session.setMetadata(updatedMeta)
                    notificationManager.showNotificationForPlayer(radioPlayer)
                }
            }
            stationCache[station.mediaItem.mediaId!!] = station
        }
        return station
    }

    private fun buildRadioStation(group: StationGroup, mediaId: String): RadioStation? {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val adsEnabled = sharedPref?.getBoolean(getString(R.string.ads_enabled_preference_key), false) ?: false
        val weatherChatterEnabled = sharedPref?.getBoolean(getString(R.string.weather_chatter_enabled_preference_key), false) ?: false
        val newsReportsEnabled  = sharedPref?.getBoolean(getString(R.string.news_reports_preference_key), false) ?: false

        val stationMedia = group.stationList.find { x -> x.mediaId == mediaId } ?: return null
        if (group.isGen1RawGroup) {
            com.sc.gtradio.common.GTRadioLogger.log("GTRadioMusicService: Creating Gen1 Raw station for ${stationMedia.description.title}")
            return group.createGen1RawStation(
                group.mediaItem.mediaId!!,
                stationMedia.mediaId!!,
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
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        applicationContext,
                        "GTA IV/EFLC key not found. Select a folder containing GTAIV.exe, EFLC.exe, or a key .dat file in Settings -> Game Key Directory.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return null
            }
            com.sc.gtradio.common.GTRadioLogger.log("GTRadioMusicService: Creating GTA IV RPF station for ${stationMedia.description.title}")
            return group.createRpf3Station(
                group.mediaItem.mediaId!!,
                stationMedia.mediaId!!,
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
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        applicationContext,
                        "GTA V keys not found. Select a folder with GTA5.exe or key files in Settings -> Game Key Directory.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return null
            }
            com.sc.gtradio.common.GTRadioLogger.log("GTRadioMusicService: Creating GTA V RPF station for ${stationMedia.description.title}")
            return group.createRpf7Station(
                group.mediaItem.mediaId!!,
                stationMedia.mediaId!!,
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
                group.mediaItem.mediaId!!,
                stationMedia.mediaId!!,
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
                return Gen1RadioStation(group.mediaItem.mediaId!!, stationMedia.mediaId!!, stationMedia, stationDoc,  radioPlayer, applicationContext)
            }
            2 -> {
                val advertsDoc = folderContents.find { x -> x.name?.uppercase()?.contains("ADVERTS") == true } ?: return null
                return Gen2RadioStation(group.mediaItem.mediaId!!, stationMedia.mediaId!!, stationMedia, stationDoc, advertsDoc, radioPlayer, applicationContext, adsEnabled, weatherChatterEnabled)
            }
            3 -> {
                val advertsDoc = folderContents.find { x -> x.name?.uppercase()?.contains("ADVERTS") == true } ?: return null
                val newsDoc = folderContents.find { x -> x.name?.uppercase()?.contains("NEWS") == true } ?: return null
                val weatherDoc = folderContents.find { x -> x.name?.uppercase()?.contains("WEATHER") == true } ?: return null
                return Gen3RadioStation(group.mediaItem.mediaId!!, stationMedia.mediaId!!, stationMedia, stationDoc, advertsDoc, newsDoc, weatherDoc, radioPlayer, applicationContext, adsEnabled, weatherChatterEnabled, newsReportsEnabled)
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

    /**
     * Listen for notification events.
     */
    private inner class PlayerNotificationListener : PlayerNotificationManager.NotificationListener {
        override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
            if (ongoing && !isForegroundService) {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, this@GTRadioMusicService.javaClass)
                )

                startForeground(notificationId, notification)
                isForegroundService = true
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            stopForeground(true)
            isForegroundService = false
            stopSelf()
        }
    }

    /**
     * Listen for events from ExoPlayer.
     */
    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && exoPlayer.playWhenReady) {
                playerReadyForCurrentSwitch = true
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && exoPlayer.playbackState == Player.STATE_READY) {
                playerReadyForCurrentSwitch = true
            }
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playWhenReady && playbackState == Player.STATE_READY) {
                playerReadyForCurrentSwitch = true
            }
            notificationManager.updatePlayerButtons(radioPlayer)
            when (val actualPlaybackState = radioPlayer.playbackState) {
                Player.STATE_IDLE,
                Player.STATE_BUFFERING,
                Player.STATE_READY -> {
                    notificationManager.showNotificationForPlayer(radioPlayer)
                    if (actualPlaybackState == Player.STATE_READY) {
                        if (!playWhenReady) {
                            // If playback is paused we remove the foreground state which allows the
                            // notification to be dismissed. An alternative would be to provide a
                            // "close" button in the notification which stops playback and clears
                            // the notification.
                            stopForeground(false)
                            isForegroundService = false
                        }
                    }
                }
                else -> {
                    notificationManager.hideNotification()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            var message = R.string.generic_error
            when (error.errorCode) {
                // If the data from MediaSource object could not be loaded the Exoplayer raises
                // a type_source error.
                // An error message is printed to UI via Toast message to inform the user.
                ExoPlaybackException.TYPE_SOURCE -> {
                    message = R.string.error_media_not_found
                    Log.e("", "TYPE_SOURCE: " + error.message)
                }
                // If the error occurs in a render component, Exoplayer raises a type_remote error.
                ExoPlaybackException.TYPE_RENDERER -> {
                    Log.e("", "TYPE_RENDERER: " + error.message)
                }
                // If occurs an unexpected RuntimeException Exoplayer raises a type_unexpected error.
                ExoPlaybackException.TYPE_UNEXPECTED -> {
                    Log.e("", "TYPE_UNEXPECTED: " + error.message)
                }

                // If the error occurs in a remote component, Exoplayer raises a type_remote error.
                ExoPlaybackException.TYPE_REMOTE -> {
                    Log.e("", "TYPE_REMOTE: " + error.message)
                }
            }
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

}