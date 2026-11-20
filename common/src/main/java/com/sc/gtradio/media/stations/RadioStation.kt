package com.sc.gtradio.media.stations

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat

interface RadioStation {
    val mediaId: String
    val mediaItem: MediaBrowserCompat.MediaItem
    val stationGroupId: String

    val metadata: MediaMetadataCompat
    var onMetadataChanged: ((MediaMetadataCompat) -> Unit)?

    var stationName: String
    var adsEnabled: Boolean
    var weatherChatterEnabled: Boolean
    var newsReportsEnabled: Boolean

    fun play()
    fun stop()
}

fun readAudioDurationMs(context: android.content.Context, uri: android.net.Uri): Long {
    var dur = 0L
    val retriever = android.media.MediaMetadataRetriever()
    try {
        val p = uri.path
        if (p != null && java.io.File(p).exists()) {
            retriever.setDataSource(p)
        } else {
            retriever.setDataSource(context, uri)
        }
        val dStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
        dur = dStr?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
    } finally {
        try { retriever.release() } catch (_: Exception) {}
    }
    return dur
}
