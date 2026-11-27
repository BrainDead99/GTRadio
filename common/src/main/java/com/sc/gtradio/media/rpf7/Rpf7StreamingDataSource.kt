package com.sc.gtradio.media.rpf7

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.sc.gtradio.media.core.CachedStreamBlock
import com.sc.gtradio.media.core.Gen3RoutingDataSourceFactory
import com.sc.gtradio.media.core.Gen3StreamBlockCache
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Zero-disk-cache Media3 [BaseDataSource] serving an RPF7 MultiChannel `.awc` stream as a
 * single continuous virtual 16-bit PCM WAV (`44 + totalPcmBytes`), decoding only the requested
 * block on demand into the shared bounded [Gen3StreamBlockCache] and opening SAF PFDs lazily
 * only on decoded-block cache misses.
 */
@UnstableApi
class Rpf7StreamingDataSource : BaseDataSource(/* isNetwork = */ false) {

    private var currentUri: Uri? = null
    private var activeArchive: Rpf7Archive? = null
    private var streamIndex: Rpf7StreamIndex? = null
    private var headerBytes: ByteArray? = null

    private var openPfd: ParcelFileDescriptor? = null
    private var openFis: FileInputStream? = null
    private var fileChannel: FileChannel? = null

    private var currentPos: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        closeChannelQuietly()
        val uri = dataSpec.uri
        val archiveId = uri.host
            ?: throw IOException("Missing archive host in RPF7 stream URI: $uri")
        val rawPath = uri.encodedPath?.removePrefix("/")?.removeSuffix(".wav")
            ?: throw IOException("Missing entry path in RPF7 stream URI: $uri")
        val entryName = Uri.decode(rawPath)

        val archive = Rpf7StreamRegistry.get(archiveId)
            ?: throw IOException("Unknown RPF7 stream archive: $archiveId")
        val index = archive.getOrBuildStreamIndex(entryName)
            ?: throw IOException("Failed building RPF7 stream index for: $entryName")

        val totalLength = index.totalVirtualWavBytes
        val startPos = dataSpec.position
        if (startPos < 0L || startPos > totalLength) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        transferInitializing(dataSpec)

        currentUri = uri
        activeArchive = archive
        streamIndex = index
        headerBytes = Rpf7Archive.buildWavHeaderBytes(
            pcmDataBytes = index.totalPcmBytes,
            sampleRate = index.sampleRate,
            channels = index.channels
        )
        openPfd = null
        openFis = null
        fileChannel = null
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
                if (currentPos >= WAV_HEADER_SIZE && totalCopied > 0) {
                    break
                }
            } else {
                if (currentPos >= index.totalVirtualWavBytes) break
                val blockIdx = findBlockForWavByte(index, currentPos)
                val cached = getOrDecodeBlock(index, blockIdx)
                val blockStart = index.blockStartWavByte[blockIdx]
                val blockOffset = (currentPos - blockStart).toInt()
                val availableInBlock = cached.pcmBytes.size - blockOffset
                if (availableInBlock <= 0) break

                val copyLen = minOf(maxToRead - totalCopied, availableInBlock)
                System.arraycopy(cached.pcmBytes, blockOffset, buffer, offset + totalCopied, copyLen)
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
        currentPos = 0L
        bytesRemaining = 0L

        val firstError = closeChannelQuietly()

        if (opened) {
            opened = false
            transferEnded()
        }
        if (firstError != null) {
            throw firstError
        }
    }

    private fun closeChannelQuietly(): IOException? {
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
        return firstError
    }

    @Throws(IOException::class)
    private fun ensureChannelOpen(archive: Rpf7Archive): FileChannel {
        fileChannel?.let { return it }
        val pfd = archive.openStreamPfd()
            ?: throw IOException("Failed opening SAF file descriptor for RPF7 stream: $currentUri")
        val fis = try {
            FileInputStream(pfd.fileDescriptor)
        } catch (t: Throwable) {
            try {
                pfd.close()
            } catch (_: Throwable) {
            }
            throw IOException("Failed opening stream channel for RPF7 stream: $currentUri", t)
        }
        openPfd = pfd
        openFis = fis
        return fis.channel.also { fileChannel = it }
    }

    private fun getOrDecodeBlock(index: Rpf7StreamIndex, blockIdx: Int): CachedStreamBlock {
        val archive = activeArchive
            ?: throw IOException("RPF7 archive unavailable while decoding block $blockIdx")
        val key = Gen3StreamBlockCache.buildKey(SCHEME, archive.archiveStreamId, index.entryName, blockIdx)
        Gen3StreamBlockCache.get(key)?.let { return it }

        val ch = ensureChannelOpen(archive)
        val prevWarmup = if (index.codec == 7 && blockIdx > 0) {
            val prevKey = Gen3StreamBlockCache.buildKey(SCHEME, archive.archiveStreamId, index.entryName, blockIdx - 1)
            Gen3StreamBlockCache.get(prevKey)?.tailWarmupByChannel
        } else {
            null
        }
        val outTail = if (index.codec == 7) arrayOfNulls<ByteArray?>(index.channels) else null
        val pcm = archive.decodeSingleBlockToPcmBytes(
            index = index,
            blockIdx = blockIdx,
            prevWarmupByChannel = prevWarmup,
            outTailWarmupByChannel = outTail,
            openChannel = ch
        )
        val decoded = CachedStreamBlock(pcm, outTail)
        Gen3StreamBlockCache.put(key, decoded)
        return decoded
    }

    object Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = Rpf7StreamingDataSource()
    }

    companion object {
        const val SCHEME = "gtradio-rpf7"
        private const val WAV_HEADER_SIZE = 44L

        fun findBlockForWavByte(index: Rpf7StreamIndex, wavBytePos: Long): Int {
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

        fun ensureRegistered() {
            Gen3RoutingDataSourceFactory.registerScheme(SCHEME, Factory)
        }
    }
}
