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

@UnstableApi open class GTRadioMusicService : MediaBrowserServiceCompat() {
    private var stationGroups: ArrayList<StationGroup> = ArrayList()
    private var fullStationList: ArrayList<MediaItem> = ArrayList()
    private val stationCache: MutableMap<String, RadioStation> = mutableMapOf()
    private val sharedPrefListener = GTRadioOnSharedPrefChange()

    //Setup a listener for settings changes
    private inner class GTRadioOnSharedPrefChange : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (prefs != null) {
                when(key) {
                    "dynamic_theme_uri", "game_key_folder_uri", "gtav_key_folder_uri", "game_keys_updated", "gtav_keys_updated", resources.getString(R.string.radio_folders_uri_key) -> {
                        GTRadioLogger.log("GTRadioMusicService: Library URI or Game keys changed, rescanning...")
                        this@GTRadioMusicService.activeStation?.stop()
                        this@GTRadioMusicService.activeStation = null
                        this@GTRadioMusicService.stationCache.clear()
                        this@GTRadioMusicService.stationGroups = this@GTRadioMusicService.getStationGroups()
                        this@GTRadioMusicService.fullStationList = this@GTRadioMusicService.getFullStationList()
                        this@GTRadioMusicService.notifyChildrenChanged("root")
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

        private fun startStationWithGlobalTuningNoise(group: StationGroup, station: RadioStation, stationIndex: Int) {
            val playbackActions = configureLoopingNavigationActions(group, stationIndex)
            val noisePack = DynamicThemeManager.findNoisePackForSeries(applicationContext, group.groupName)
            val shouldPlayNoise = noisePack != null &&
                noisePack.noiseSoundUris.isNotEmpty() &&
                DynamicThemeManager.isDynamicThemeEnabled(applicationContext)

            if (shouldPlayNoise) {
                radioPlayer.setStreamMuted(true)
                station.play()
                DynamicThemeManager.playTuningNoiseUntilReady(
                    applicationContext,
                    noisePack!!,
                    isRadioReady = { radioPlayer.isExoPlayerReady() },
                    onComplete = {
                        radioPlayer.setStreamMuted(false)
                        if (activeStation?.mediaId == station.mediaId) {
                            session.setMetadata(station.metadata)
                            notificationManager.showNotificationForPlayer(radioPlayer)
                        }
                    }
                )
            } else {
                DynamicThemeManager.stopTuningNoise()
                radioPlayer.setStreamMuted(false)
                station.play()
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
            if (activeStation == null && activeStationGroupId != null) {
                val group = getStationGroup(activeStationGroupId!!) ?: return
                val firstStation = group.stationList.firstOrNull() ?: return
                activeStation = getRadioStation(group, firstStation.mediaId!!)
            } else if (activeStation == null) {
                val mediaId = fullStationList.firstOrNull()?.mediaId ?: return
                val group = getStationGroupContainingMedia(mediaId) ?: return
                activeStation = getRadioStation(group, mediaId)
            }

            val st = activeStation ?: return
            val group = getStationGroup(st.stationGroupId) ?: return
            val stationIndex = group.getStationIndex(st.mediaId)
            startStationWithGlobalTuningNoise(group, st, stationIndex)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId.isNullOrBlank()) {
                return
            }

            val group = getStationGroupContainingMedia(mediaId) ?: return
            if (activeStation?.mediaId != mediaId) { activeStation?.stop() }
            activeStation = getRadioStation(group, mediaId)
            val st = activeStation ?: return

            activeStationGroupId = group.mediaItem.mediaId
            val stationIndex = group.getStationIndex(st.mediaId)
            startStationWithGlobalTuningNoise(group, st, stationIndex)
        }

        override fun onPause() {
            val st = activeStation ?: return
            val group = getStationGroup(st.stationGroupId) ?: return
            val stationIndex = group.getStationIndex(st.mediaId)
            val playbackActions = configureLoopingNavigationActions(group, stationIndex)

            DynamicThemeManager.stopTuningNoise()
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
            val group = getStationGroup(st.stationGroupId) ?: return
            val stationIndex = group.getStationIndex(st.mediaId)
            val playbackActions = configureLoopingNavigationActions(group, stationIndex)

            DynamicThemeManager.stopTuningNoise()
            radioPlayer.setStreamMuted(false)
            st.stop()
            val playbackState = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1F)
                .setActions(playbackActions)
                .build()
            session.setPlaybackState(playbackState)
        }

        override fun onSkipToNext() {
            if (activeStation == null && activeStationGroupId == null) {
                return
            } else if (activeStation == null && activeStationGroupId != null) {
                val group = getStationGroup(activeStationGroupId!!) ?: return
                val firstStation = group.stationList.firstOrNull() ?: return
                activeStation = getRadioStation(group, firstStation.mediaId!!)
                val st = activeStation ?: return
                startStationWithGlobalTuningNoise(group, st, 0)
                return
            }

            val currentSt = activeStation ?: return
            val group = getStationGroup(currentSt.stationGroupId) ?: return
            if (group.stationList.isEmpty()) return
            val stationIndex = group.getStationIndex(currentSt.mediaId)
            val nextStationIndex = if (stationIndex + 1 >= group.stationList.size) 0 else (stationIndex + 1)

            currentSt.stop()
            activeStation = getRadioStation(group, group.stationList[nextStationIndex].mediaId!!)
            val nextSt = activeStation ?: return
            startStationWithGlobalTuningNoise(group, nextSt, nextStationIndex)
        }

        override fun onSkipToPrevious() {
            if (activeStation == null && activeStationGroupId == null) {
                return
            } else if (activeStation == null && activeStationGroupId != null) {
                val group = getStationGroup(activeStationGroupId!!) ?: return
                val firstStation = group.stationList.firstOrNull() ?: return
                activeStation = getRadioStation(group, firstStation.mediaId!!)
                val st = activeStation ?: return
                startStationWithGlobalTuningNoise(group, st, 0)
                return
            }

            val currentSt = activeStation ?: return
            val group = getStationGroup(currentSt.stationGroupId) ?: return
            if (group.stationList.isEmpty()) return
            val stationIndex = group.getStationIndex(currentSt.mediaId)
            val prevStationIndex = if (stationIndex - 1 < 0) group.stationList.lastIndex else (stationIndex - 1)

            currentSt.stop()
            activeStation = getRadioStation(group, group.stationList[prevStationIndex].mediaId!!)
            val prevSt = activeStation ?: return
            startStationWithGlobalTuningNoise(group, prevSt, prevStationIndex)
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

        stationGroups = getStationGroups()
        fullStationList = getFullStationList()
    }

    private fun getStationGroups(): ArrayList<StationGroup> {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val radioPath = sharedPref?.getString(getString(R.string.radio_folders_uri_key), "") ?: return ArrayList()
        if (radioPath.isBlank()) { return ArrayList() }
        val dir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(radioPath))
        stationCache.clear()
        return StationGroup.scanRootDirectory(dir, applicationContext)
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
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId.isBlank() || parentId == "root") {
            if (stationGroups.isEmpty()) {
                stationGroups = getStationGroups()
                fullStationList = getFullStationList()
            }
            val groups = ArrayList(stationGroups.map { x -> x.mediaItem })
            GTRadioLogger.log("GTRadioMusicService.onLoadChildren(root): sending ${groups.size} groups")
            result.sendResult(groups)
            return
        }
        val group = stationGroups.find { x -> x.mediaItem.mediaId == parentId }
        val stations = group?.stationList ?: ArrayList()
        result.sendResult(stations)
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
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
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