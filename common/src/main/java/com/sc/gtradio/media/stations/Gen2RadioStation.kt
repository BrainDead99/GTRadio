package com.sc.gtradio.media.stations

import GTRadioPlayer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.sc.gtradio.common.FileUtils.listSimpleFiles
import java.util.*

@UnstableApi class Gen2RadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaBrowserCompat.MediaItem,
    baseStationFolderDoc: DocumentFile,
    advertsFolderDoc: DocumentFile,
    private val player: GTRadioPlayer,
    private val context: Context,
    override var adsEnabled: Boolean,
    private var _weatherChatterEnabled: Boolean
) : RadioStation {

    override var newsReportsEnabled: Boolean = false
    override var onMetadataChanged: ((MediaMetadataCompat) -> Unit)? = null

    override var weatherChatterEnabled: Boolean
        get() = _weatherChatterEnabled
        set(enabled) {
            if (enabled != _weatherChatterEnabled) {
                _weatherChatterEnabled = enabled
                randomizeDJFiles()
            }
        }

    private val stationTitle: String = (mediaItem.description.title ?: "").toString()
    private val groupTitle: String = (mediaItem.description.subtitle ?: "").toString()
    private val stationLogo: Bitmap? = mediaItem.description.iconBitmap

    private var _currentMetadata: MediaMetadataCompat = buildStationMetadata(
        trackTitle = stationTitle,
        trackArtist = groupTitle,
        art = stationLogo
    )

    override val metadata: MediaMetadataCompat
        get() = _currentMetadata

    override var stationName: String = ""

    private var advertFiles: Array<Uri> = emptyArray()
    private var announcerFiles: Array<Uri> = emptyArray()
    private var djMorningFiles: Array<Uri> = emptyArray()
    private var djEveningFiles: Array<Uri> = emptyArray()
    private var djNightFiles: Array<Uri> = emptyArray()
    private var djOtherFiles: Array<Uri> = emptyArray()
    private var djWeatherFogFiles: Array<Uri> = emptyArray()
    private var djWeatherRainFiles: Array<Uri> = emptyArray()
    private var djWeatherSunFiles: Array<Uri> = emptyArray()
    private var djWeatherStormFiles: Array<Uri> = emptyArray()
    private var songs: Array<Song> = emptyArray()
    private var currentDJFiles: Array<Uri> = emptyArray()

    private var advertIterator: Iterator<Uri>? = null
    private var announcerIterator: Iterator<Uri>? = null
    private var djIterator: Iterator<Uri>? = null
    private var songIterator: Iterator<Song>? = null

    private var _playing = false
    private var lastSegment = RadioSegmentType.None
    private var songsSinceLastBreak = 0
    private var activeListener: Player.Listener? = null
    private val rng = Random()
    private val suffixRegex = Regex("\\s*\\((?:Mid|Intro(?:\\s*DJ\\s*#?\\d+)?|Outro(?:\\s*DJ\\s*#?\\d+)?)\\)\\s*$", RegexOption.IGNORE_CASE)

    init {
        stationName = baseStationFolderDoc.name ?: ""
        val subDirs = baseStationFolderDoc.listFiles().filter { x -> x.isDirectory }
        for (subDir in subDirs) {
            when (subDir.name?.uppercase()) {
                "ANNOUNCER", "IDS" -> setupAnnouncerFiles(subDir)
                "DJ CHATTER" -> setupDJFiles(subDir)
                "SONGS" -> setupSongs(subDir)
            }
        }
        setupAdvertFiles(advertsFolderDoc)
        randomizeAll()
    }

    private fun buildStationMetadata(trackTitle: String, trackArtist: String, art: Bitmap?): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaItem.mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, trackTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, trackArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, trackArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, stationTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, groupTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "$stationTitle • $groupTitle")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            .build()
    }

    private fun updateMetadataFromUri(uri: Uri, fallbackTitle: String, isAd: Boolean = false) {
        var title = fallbackTitle
        var artist = if (isAd) "Advertisement • $stationTitle" else stationTitle
        var art: Bitmap? = if (isAd) null else stationLogo

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (!rawTitle.isNullOrBlank()) {
                title = suffixRegex.replace(rawTitle, "").trim()
            }
            if (!rawArtist.isNullOrBlank()) {
                artist = "$rawArtist • $stationTitle"
            }
            val picBytes = retriever.embeddedPicture
            if (picBytes != null && picBytes.isNotEmpty()) {
                art = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size) ?: art
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        _currentMetadata = buildStationMetadata(title, artist, art)
        onMetadataChanged?.invoke(_currentMetadata)
    }

    private fun setupAnnouncerFiles(folder: DocumentFile) {
        announcerFiles = folder.listFiles().map { x -> x.uri }.toTypedArray()
    }

    private fun setupAdvertFiles(folder: DocumentFile) {
        advertFiles = folder.listFiles().map { x -> x.uri }.toTypedArray()
    }

    private fun setupDJFiles(folder: DocumentFile) {
        val djFolders = folder.listFiles()
        djMorningFiles = (djFolders.firstOrNull { x -> x.name?.uppercase() == "MORNING" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
        djEveningFiles = (djFolders.firstOrNull { x -> x.name?.uppercase() == "EVENING" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
        djNightFiles = (djFolders.firstOrNull { x -> x.name?.uppercase() == "NIGHT" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
        djOtherFiles = (djFolders.firstOrNull { x -> x.name?.uppercase() == "OTHER" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()

        val weatherFolder = folder.listFiles().firstOrNull { x -> x.name?.uppercase() == "WEATHER" }
        val weatherFolders = weatherFolder?.listFiles()
        if (weatherFolder != null && weatherFolders?.isNotEmpty() == true) {
            djWeatherFogFiles = (weatherFolders.firstOrNull { x -> x.name?.uppercase() == "FOG" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
            djWeatherRainFiles = (weatherFolders.firstOrNull { x -> x.name?.uppercase() == "RAIN" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
            djWeatherSunFiles = (weatherFolders.firstOrNull { x -> x.name?.uppercase() == "SUN" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
            djWeatherStormFiles = (weatherFolders.firstOrNull { x -> x.name?.uppercase() == "STORM" }?.listFiles() ?: emptyArray()).map { x -> x.uri }.toTypedArray()
        }
    }

    private fun setupSongs(folder: DocumentFile) {
        songs = folder.listFiles().map { x -> Song(x) }.toTypedArray()
    }

    private fun randomizeAll() {
        randomizeAnnouncerFiles()
        randomizeAdvertFiles()
        randomizeDJFiles()
        randomizeSongFolders()
    }

    private fun randomizeAnnouncerFiles() {
        announcerFiles.shuffle()
        announcerIterator = announcerFiles.iterator()
    }

    private fun randomizeAdvertFiles() {
        advertFiles.shuffle()
        advertIterator = advertFiles.iterator()
    }

    private fun randomizeDJFiles() {
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        var arrToConcat: Array<Uri> = emptyArray()
        if (currentHour in 3..11) {
            djMorningFiles.shuffle()
            arrToConcat = djMorningFiles
        } else if (currentHour in 17..22) {
            djEveningFiles.shuffle()
            arrToConcat = djEveningFiles
        } else if (currentHour < 3 || currentHour >= 23) {
            djNightFiles.shuffle()
            arrToConcat = djNightFiles
        }
        djOtherFiles.shuffle()
        currentDJFiles = djOtherFiles.plus(arrToConcat)

        if (weatherChatterEnabled) {
            djWeatherFogFiles.shuffle()
            djWeatherRainFiles.shuffle()
            djWeatherStormFiles.shuffle()
            djWeatherSunFiles.shuffle()
            currentDJFiles = currentDJFiles.plus(djWeatherFogFiles).plus(djWeatherStormFiles).plus(djWeatherRainFiles).plus(djWeatherSunFiles)
        }
        currentDJFiles.shuffle()
        djIterator = currentDJFiles.iterator()
    }

    private fun randomizeSongFolders() {
        songs.shuffle()
        songIterator = songs.iterator()
    }

    private fun getNextAdvertFile(): Uri {
        if (!advertIterator!!.hasNext()) randomizeAdvertFiles()
        return advertIterator!!.next()
    }

    private fun getNextAnnouncerFile(): Uri {
        if (!announcerIterator!!.hasNext()) randomizeAnnouncerFiles()
        return announcerIterator!!.next()
    }

    private fun getNextDjFile(): Uri {
        if (!djIterator!!.hasNext()) randomizeDJFiles()
        return djIterator!!.next()
    }

    private fun getNextSongFolder(): Song {
        if (!songIterator!!.hasNext()) randomizeSongFolders()
        return songIterator!!.next()
    }

    override fun stop() {
        _playing = false
        player.stop()
    }

    override fun play() {
        _playing = true
        player.radioPlaybackState = PlaybackStateCompat.STATE_PLAYING
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false
        playNext()
    }

    private fun playNext() {
        if (activeListener != null) {
            player.removeListener(activeListener!!)
        }
        if (_playing) {
            when (lastSegment) {
                RadioSegmentType.None -> {
                    lastSegment = RadioSegmentType.fromInt(rng.nextInt(5))
                    playNext()
                }
                RadioSegmentType.Commercial -> {
                    if (rng.nextInt(11) < 6 || announcerFiles.isEmpty()) playNextSong(true) else playNextAnnouncerSpot()
                }
                RadioSegmentType.Announcer -> {
                    if (rng.nextInt(11) < 3 || currentDJFiles.isEmpty()) playNextSong(true) else playNextDjChatter()
                }
                RadioSegmentType.DJChatter -> {
                    if (songsSinceLastBreak == 0) {
                        playNextSong()
                    } else {
                        val numberOfSongsBeforeBreak = rng.nextInt(3) + 3
                        if (adsEnabled && numberOfSongsBeforeBreak <= songsSinceLastBreak) playNextAdvert() else playNextSong()
                    }
                }
                RadioSegmentType.Song -> {
                    val numberOfSongsBeforeBreak = rng.nextInt(3) + 3
                    if (adsEnabled && numberOfSongsBeforeBreak <= songsSinceLastBreak) {
                        playNextAdvert()
                    } else {
                        val nextThing = if (currentDJFiles.isEmpty() && announcerFiles.isEmpty()) 0
                        else if (currentDJFiles.isEmpty()) rng.nextInt(2)
                        else if (announcerFiles.isEmpty()) (if (rng.nextInt(2) == 0) 0 else 2)
                        else rng.nextInt(3)
                        when (nextThing) {
                            0 -> playNextSong()
                            1 -> playNextAnnouncerSpot()
                            else -> playNextDjChatter()
                        }
                    }
                }
                else -> {
                    lastSegment = RadioSegmentType.fromInt(rng.nextInt(5) + 1)
                    playNext()
                }
            }
        }
    }

    private fun playNextAnnouncerSpot() {
        lastSegment = RadioSegmentType.Announcer
        if (announcerFiles.isNotEmpty()) {
            val nextAnn = getNextAnnouncerFile()
            updateMetadataFromUri(nextAnn, "$stationTitle (Station ID)")
            playFile(nextAnn)
        } else {
            playNext()
        }
    }

    private fun playNextDjChatter() {
        lastSegment = RadioSegmentType.DJChatter
        if (currentDJFiles.isNotEmpty()) {
            val nextDj = getNextDjFile()
            updateMetadataFromUri(nextDj, "$stationTitle (DJ Chatter)")
            playFile(nextDj)
        } else {
            playNext()
        }
    }

    private fun playNextAdvert() {
        lastSegment = RadioSegmentType.Commercial
        songsSinceLastBreak = 0
        if (adsEnabled && advertFiles.isNotEmpty()) {
            val nextAd = getNextAdvertFile()
            updateMetadataFromUri(nextAd, "Commercial Break", isAd = true)
            playFile(nextAd)
        } else {
            playNext()
        }
    }

    private fun playNextSong(requireDjIntro: Boolean = false, requireDjOutro: Boolean = false) {
        songsSinceLastBreak += 1
        lastSegment = RadioSegmentType.Song
        if (songs.isNotEmpty()) {
            val nextSong = getNextSongFolder()
            playSong(nextSong, requireDjIntro, requireDjOutro)
        } else {
            playNext()
        }
    }

    private fun playSong(song: Song, requireDjIntro: Boolean, requireDjOutro: Boolean) {
        if (song.mainSongUri == null) {
            playNext()
            return
        }

        updateMetadataFromUri(song.mainSongUri, song.folderName)

        val introToUse = if (requireDjIntro && song.djIntroUris.isNotEmpty()) {
            song.djIntroUris[rng.nextInt(song.djIntroUris.size)]
        } else if (rng.nextInt(11) < 6 && song.djIntroUris.isNotEmpty()) {
            song.djIntroUris[rng.nextInt(song.djIntroUris.size)]
        } else {
            song.mainIntroUri
        }

        val outroToUse = if (requireDjOutro && song.djOutroUris.isNotEmpty()) {
            song.djOutroUris[rng.nextInt(song.djOutroUris.size)]
        } else if (rng.nextInt(11) < 6 && song.djOutroUris.isNotEmpty()) {
            song.djOutroUris[rng.nextInt(song.djOutroUris.size)]
        } else {
            song.mainOutroUri
        }

        val factory = ProgressiveMediaSource.Factory(DefaultDataSourceFactory(context, Util.getUserAgent(context, context.packageName)))
        val concatSource = ConcatenatingMediaSource(true)
        if (introToUse != null) concatSource.addMediaSource(factory.createMediaSource(MediaItem.fromUri(introToUse)))
        concatSource.addMediaSource(factory.createMediaSource(MediaItem.fromUri(song.mainSongUri)))
        if (outroToUse != null) concatSource.addMediaSource(factory.createMediaSource(MediaItem.fromUri(outroToUse)))

        player.setMediaSource(concatSource)

        activeListener = object : Player.Listener {
            private var alreadyHandled = false
            override fun onPlaybackStateChanged(state: Int) {
                if (!alreadyHandled && state == Player.STATE_ENDED) {
                    alreadyHandled = true
                    player.removeListener(activeListener!!)
                    playNext()
                }
            }
        }
        player.addListener(activeListener!!)
        player.prepare()
        player.play()
    }

    private fun playFile(file: Uri) {
        val src = ProgressiveMediaSource.Factory(DefaultDataSourceFactory(context, Util.getUserAgent(context, context.packageName))).createMediaSource(MediaItem.fromUri(file))
        player.setMediaSource(src)

        activeListener = object : Player.Listener {
            private var alreadyHandled = false
            override fun onPlaybackStateChanged(state: Int) {
                if (!alreadyHandled && state == Player.STATE_ENDED) {
                    alreadyHandled = true
                    player.removeListener(activeListener!!)
                    playNext()
                }
            }
        }
        player.addListener(activeListener!!)
        player.prepare()
        player.play()
    }

    private inner class Song(songFolderDoc: DocumentFile?) {
        val folderName: String = songFolderDoc?.name ?: stationTitle
        val songFiles = songFolderDoc?.listSimpleFiles(context.applicationContext)

        val mainSongUri: Uri? = (songFiles ?: emptyArray()).find { x -> !x.name.uppercase().contains("(INTRO") && !x.name.uppercase().contains("(OUTRO") }?.uri
        val mainIntroUri: Uri? = (songFiles ?: emptyArray()).find { x -> x.name.uppercase().contains("(INTRO)") }?.uri
        val mainOutroUri: Uri? = (songFiles ?: emptyArray()).find { x -> x.name.uppercase().contains("(OUTRO)") }?.uri
        val djIntroUris: Array<Uri> = (songFiles ?: emptyArray()).filter { x -> x.name.uppercase().contains("(INTRO DJ") }.map { x -> x.uri }.toTypedArray()
        val djOutroUris: Array<Uri> = (songFiles ?: emptyArray()).filter { x -> x.name.uppercase().contains("(OUTRO DJ") }.map { x -> x.uri }.toTypedArray()
    }
}
