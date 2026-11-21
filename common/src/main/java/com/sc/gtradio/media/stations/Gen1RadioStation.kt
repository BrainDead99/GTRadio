package com.sc.gtradio.media.stations

import com.sc.gtradio.media.GTRadioPlayer
import android.content.Context
import android.icu.util.Calendar
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.util.*
import kotlin.math.abs

@UnstableApi class Gen1RadioStation(
    override val stationGroupId: String,
    override val mediaId: String,
    override val mediaItem: MediaItem,
    baseStationDoc: DocumentFile,
    private val player: GTRadioPlayer,
    context: Context) : RadioStation {

    override val metadata: MediaMetadata = buildStationMediaMetadata(
        mediaId = mediaId,
        title = mediaItem.mediaMetadata.title?.toString() ?: "",
        subtitle = mediaItem.mediaMetadata.subtitle?.toString() ?: "",
        artist = mediaItem.mediaMetadata.subtitle?.toString() ?: "",
        albumArtist = mediaItem.mediaMetadata.subtitle?.toString() ?: "",
        stationTitle = mediaItem.mediaMetadata.title?.toString() ?: "",
        groupTitle = mediaItem.mediaMetadata.subtitle?.toString() ?: "",
        art = null,
        isSong = true
    )

    override var onMetadataChanged: ((MediaMetadata) -> Unit)? = null
    override var stationName: String = ""

    //These fields are irrelevant to these stations
    override var adsEnabled: Boolean = true
    override var weatherChatterEnabled: Boolean = false
    override var newsReportsEnabled: Boolean = true

    private var _playing = false

    //Properties used to calculate elapsed time and current location in the track
    private var durationSec: Int = 0
    private var lastStoppedDurationSec: Int = 0
    private var lastStoppedTime: Calendar? = null
    private var activeListener: Player.Listener? = null

    private val mediaSource: MediaSource?

    init {
        stationName = getStationName(baseStationDoc)
        val files = baseStationDoc.listFiles()
        val radioFileUri = files.firstOrNull { x -> x.name?.uppercase()?.contains("LOGO") == false }?.uri
        mediaSource = if (radioFileUri != null) {
            val media = MediaItem.fromUri(radioFileUri)
            ProgressiveMediaSource.Factory(DefaultDataSourceFactory(context, Util.getUserAgent(context, context.packageName))).createMediaSource(media)
        } else {
            null
        }
    }

    private fun getStationName(stationFolder: DocumentFile): String {
        return stationFolder.name ?: ""
    }

    private var isStationActive = false

    override fun stop() {
        if (!isStationActive) return
        isStationActive = false
        if (mediaSource == null) {
            return
        }

        _playing = false
        lastStoppedDurationSec = (player.exoPlayer.currentPosition.coerceAtLeast(0L) / 1000).toInt()
        lastStoppedTime = Calendar.getInstance()
        player.stopInternal()
    }

    override fun play() {
        if (mediaSource == null) {
            return
        }

        isStationActive = true
        _playing = true
        player.radioPlaybackState = GTRadioPlayer.RadioState.PLAYING

        /* Have to listen to some timeline changes just to get track duration.
         * MediaMetadataRetriever is dated and throws exceptions when using Uri, and using MetadataRetriever is also an async operation where
         *      we can save time by just letting the player prepare the source like it has to anyway (instead of us making MetadataRetriever do it also).
         *      Also note, the GTRadioPlayer.currentTrackDuration field only gets filled once the timeline is built which is async and not immediately following .prepare
         */
        if (durationSec == 0) {
            activeListener = object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                        if (durationSec != 0) {
                            player.removeListener(activeListener!!)
                            return
                        }
                        val stuff = timeline.getPeriod(0, Timeline.Period())
                        if (stuff.durationMs > 0) {
                            durationSec = (stuff.durationMs / 1000).toInt()
                            player.removeListener(activeListener!!)
                            seekAndPlayFile()
                        }
                    }
                }
            }
            player.addListener(activeListener!!)
        }

        // Prepare the player.
        player.setMediaSource(mediaSource)
        player.playWhenReady = false
        player.prepare()
        if (durationSec != 0) {
            //Already know the duration so just play, otherwise we must wait for the duration to be calculated in the timeline
            seekAndPlayFile()
        }

    }

    private fun seekAndPlayFile() {
        val seekPos = getSeekPosition()
        if (seekPos > 0L) {
            player.exoPlayer.seekTo(seekPos)
        }
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.playInternal()
    }

    private fun getSeekPosition(): Long {
        val rng = Random()
        if (lastStoppedTime == null) {
            //Pick a random starting point
            return (rng.nextInt(durationSec) * 1000).toLong()
        } else {
            //Calculate position
            val now = Calendar.getInstance()
            val elapsedTimeSec = ((now.timeInMillis - lastStoppedTime!!.timeInMillis) / 1000).toInt()
            val divisions = (elapsedTimeSec.toFloat() / durationSec)
            val multiplier = abs(divisions - divisions.toInt())
            val initialStartPositionSec = (durationSec * multiplier) + lastStoppedDurationSec
            val finalPosition = if (initialStartPositionSec > durationSec) {
                initialStartPositionSec - durationSec
            } else {
                initialStartPositionSec
            }
            return (finalPosition * 1000).toLong()
        }


    }

}