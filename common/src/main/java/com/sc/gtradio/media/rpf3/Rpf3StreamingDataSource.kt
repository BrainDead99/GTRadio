package com.sc.gtradio.media.rpf3

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.sc.gtradio.media.core.Gen3RoutingDataSourceFactory
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Zero-disk-cache Media3 [BaseDataSource] serving an RPF3 MultiC stream as a
 * single continuous virtual 16-bit PCM WAV (`44 + totalPcmBytes`), decoding only the requested
 * block on demand into a tiny 2-block in-memory LRU cache.
 */
@UnstableApi
class Rpf3StreamingDataSource : BaseDataSource(/* isNetwork = */ false) {

    private var currentUri: Uri? = null
    private var activeArchive: Rpf3Archive? = null
    private var streamIndex: Rpf3StreamIndex? = null
    private var headerBytes: ByteArray? = null

    private var openPfd: ParcelFileDescriptor? = null
    private var openFis: FileInputStream? = null
    private var fileChannel: FileChannel? = null

    private var currentPos: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    private val blockCache = object : LinkedHashMap<Int, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean {
            return size > MAX_BLOCK_CACHE_SIZE
        }
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val archiveId = uri.host
            ?: throw IOException("Missing archive host in RPF3 stream URI: $uri")
        val rawPath = uri.encodedPath?.removePrefix("/")?.removeSuffix(".wav")
            ?: throw IOException("Missing entry path in RPF3 stream URI: $uri")
        val entryName = Uri.decode(rawPath)

        val archive = Rpf3StreamRegistry.get(archiveId)
            ?: throw IOException("Unknown RPF3 stream archive: $archiveId")
        val index = archive.getOrBuildStreamIndex(entryName)
            ?: throw IOException("Failed building RPF3 stream index for: $entryName")

        val totalLength = index.totalVirtualWavBytes
        val startPos = dataSpec.position
        if (startPos < 0L || startPos > totalLength) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        transferInitializing(dataSpec)

        val pfd = archive.openStreamPfd()
            ?: throw IOException("Failed opening SAF file descriptor for RPF3 stream: $uri")
        val fis = try {
            FileInputStream(pfd.fileDescriptor)
        } catch (t: Throwable) {
            try {
                pfd.close()
            } catch (_: Throwable) {
            }
            throw IOException("Failed opening stream channel for RPF3 stream: $uri", t)
        }
        val ch = fis.channel

        currentUri = uri
        activeArchive = archive
        streamIndex = index
        headerBytes = Rpf3Archive.buildWavHeaderBytes(
            pcmDataBytes = index.totalPcmBytes,
            sampleRate = index.sampleRate,
            channels = index.outChannels
        )
        openPfd = pfd
        openFis = fis
        fileChannel = ch
        currentPos = startPos
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, totalLength - startPos).coerceAtLeast(0L)
        } else {
            totalLength - startPos
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT

        val index = streamIndex ?: return C.RESULT_END_OF_INPUT
        val hdr = headerBytes ?: return C.RESULT_END_OF_INPUT

        val maxToRead = minOf(length.toLong(), bytesRemaining).toInt()
        var totalCopied = 0

        while (totalCopied < maxToRead) {
            if (currentPos < WAV_HEADER_SIZE) {
                val hdrOff = currentPos.toInt()
                val copyLen = minOf(maxToRead - totalCopied, WAV_HEADER_SIZE.toInt() - hdrOff)
                System.arraycopy(hdr, hdrOff, buffer, offset + totalCopied, copyLen)
                currentPos += copyLen
                bytesRemaining -= copyLen
                totalCopied += copyLen
            } else {
                if (currentPos >= index.totalVirtualWavBytes) break
                val validIdx = findBlockForWavByte(index, currentPos)
                val pcmBytes = getOrDecodeBlock(index, validIdx)
                val blockStart = index.blockStartWavByte[validIdx]
                val blockOffset = (currentPos - blockStart).toInt()
                val availableInBlock = pcmBytes.size - blockOffset
                if (availableInBlock <= 0) break

                val copyLen = minOf(maxToRead - totalCopied, availableInBlock)
                System.arraycopy(pcmBytes, blockOffset, buffer, offset + totalCopied, copyLen)
                currentPos += copyLen
                bytesRemaining -= copyLen
                totalCopied += copyLen
            }
        }

        if (totalCopied > 0) {
            bytesTransferred(totalCopied)
            return totalCopied
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = currentUri

    @Throws(IOException::class)
    override fun close() {
        currentUri = null
        activeArchive = null
        streamIndex = null
        headerBytes = null
        blockCache.clear()
        currentPos = 0L
        bytesRemaining = 0L

        var firstError: IOException? = null
        try {
            fileChannel?.close()
        } catch (e: IOException) {
            firstError = e
        } finally {
            fileChannel = null
        }
        try {
            openFis?.close()
        } catch (e: IOException) {
            if (firstError == null) firstError = e
        } finally {
            openFis = null
        }
        try {
            openPfd?.close()
        } catch (e: IOException) {
            if (firstError == null) firstError = e
        } finally {
            openPfd = null
        }

        if (opened) {
            opened = false
            transferEnded()
        }
        if (firstError != null) {
            throw firstError
        }
    }

    private fun findBlockForWavByte(index: Rpf3StreamIndex, wavBytePos: Long): Int {
        val starts = index.blockStartWavByte
        var lo = 0
        var hi = starts.size - 1
        var res = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= wavBytePos) {
                res = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        while (res + 1 < starts.size && wavBytePos >= starts[res] + index.blockPcmBytes[res]) {
            res++
        }
        return res
    }

    private fun getOrDecodeBlock(index: Rpf3StreamIndex, validIdx: Int): ByteArray {
        blockCache[validIdx]?.let { return it }
        val archive = activeArchive
            ?: throw IOException("RPF3 archive unavailable while decoding block $validIdx")
        val ch = fileChannel
            ?: throw IOException("RPF3 channel unavailable while decoding block $validIdx")
        val pcm = archive.decodeSingleBlockToPcmBytes(index, validIdx, ch)
        blockCache[validIdx] = pcm
        return pcm
    }

    object Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = Rpf3StreamingDataSource()
    }

    companion object {
        const val SCHEME = "gtradio-rpf3"
        private const val WAV_HEADER_SIZE = 44L
        private const val MAX_BLOCK_CACHE_SIZE = 2

        fun ensureRegistered() {
            Gen3RoutingDataSourceFactory.registerScheme(SCHEME, Factory)
        }
    }
}
