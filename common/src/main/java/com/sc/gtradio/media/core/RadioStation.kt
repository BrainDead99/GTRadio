package com.sc.gtradio.media.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.ByteArrayOutputStream
import java.io.File

interface RadioStation {
    val mediaId: String
    val mediaItem: MediaItem
    val stationGroupId: String

    val metadata: MediaMetadata
    var onMetadataChanged: ((MediaMetadata) -> Unit)?

    var stationName: String
    var adsEnabled: Boolean
    var weatherChatterEnabled: Boolean
    var newsReportsEnabled: Boolean

    fun play()
    fun stop()
    fun release() {
        stop()
    }

    val supportsSeamlessPrepare: Boolean get() = false
    fun prepareForSeamlessTakeOver(
        onReady: (commit: () -> Unit) -> Unit,
        onFailed: () -> Unit = {}
    ) {
        onReady { play() }
    }
    fun cancelSeamlessPrepare() {}
}

object StationDispatchers {
    val ioDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(4)
    val prewarmDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)
}

private var neutralRadioPlaceholderJpegBytes: ByteArray? = null

fun getNeutralRadioPlaceholderJpeg(): ByteArray {
    neutralRadioPlaceholderJpegBytes?.let { return it }
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.parseColor("#222226"))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88888E")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    // Radio chassis
    val radioRect = RectF(16f, 32f, 80f, 76f)
    canvas.drawRoundRect(radioRect, 6f, 6f, paint)
    // Speaker circle
    canvas.drawCircle(36f, 54f, 13f, paint)
    // Antenna
    canvas.drawLine(24f, 32f, 64f, 16f, paint)
    // Knobs / buttons
    paint.style = Paint.Style.FILL
    canvas.drawCircle(64f, 46f, 4f, paint)
    canvas.drawCircle(64f, 62f, 4f, paint)

    val baos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
    val bytes = baos.toByteArray()
    bmp.recycle()
    neutralRadioPlaceholderJpegBytes = bytes
    return bytes
}

fun compressArtworkToJpeg(bitmap: Bitmap?, maxDim: Int = 300, quality: Int = 85): ByteArray? {
    if (bitmap == null || bitmap.isRecycled) return null
    return try {
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        var bytes = baos.toByteArray()
        if (bytes.size > 40 * 1024 && quality > 60) {
            baos.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos)
            bytes = baos.toByteArray()
        }
        if (scaled != bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }
        bytes
    } catch (_: Exception) {
        null
    }
}

fun buildStationMediaMetadata(
    mediaId: String,
    title: String,
    subtitle: String,
    artist: String,
    albumArtist: String,
    stationTitle: String,
    groupTitle: String,
    art: Bitmap? = null,
    isSong: Boolean = false,
    durationMs: Long = -1L,
    extras: Bundle? = null
): MediaMetadata {
    val b = MediaMetadata.Builder()
        .setTitle(title)
        .setDisplayTitle(title)
        .setSubtitle(subtitle)
        .setArtist(artist.ifBlank { subtitle })
        .setAlbumArtist(albumArtist)
        .setWriter(stationTitle)
        .setAlbumTitle(groupTitle)
        .setDescription("$stationTitle • $groupTitle")
        .setTrackNumber(if (isSong) 1 else 0)
        .setIsPlayable(true)
        .setIsBrowsable(false)

    val artBytes = compressArtworkToJpeg(art) ?: getNeutralRadioPlaceholderJpeg()
    b.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)

    val finalExtras = Bundle(extras ?: Bundle())
    finalExtras.putString("media_id", mediaId)
    finalExtras.putString("station_name", stationTitle)
    finalExtras.putString("group_name", groupTitle)
    finalExtras.putBoolean("is_song", isSong)
    if (durationMs > 0L) {
        finalExtras.putLong("duration_ms", durationMs)
    } else {
        finalExtras.remove("duration_ms")
    }
    b.setExtras(finalExtras)

    return b.build()
}

fun readAudioDurationMs(context: Context, uri: Uri): Long {
    var dur = 0L
    val retriever = android.media.MediaMetadataRetriever()
    try {
        val p = uri.path
        if (p != null && File(p).exists()) {
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
