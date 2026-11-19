package com.sc.gtradio.media.stations

import android.content.Context
import android.net.Uri
import com.sc.gtradio.common.GTRadioLogger
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

class At3AudioArchive(
    private val context: Context,
    val fileName: String,
    val sourceUri: Uri
) {
    var parsed = false
        private set
    var isAtrac3Plus = true
        private set
    var channels = 2
        private set
    var sampleRate = 44100
        private set
    var byteRate = 12058
        private set
    var blockAlign = 560
        private set
    var dataOffset = 96L
        private set
    var dataSize = 0L
        private set
    var totalFrames = 0
        private set
    var durationMs = 1_800_000L
        private set
    private var extraData: ByteArray? = null

    companion object {
        const val CHUNK_FRAMES = 1300 // ~60.4 seconds per chunk at 44.1 kHz
        private val prefetchExecutor = Executors.newSingleThreadExecutor()
        private val decodeLock = Any()

        init {
            try {
                System.loadLibrary("gtradio_at3")
            } catch (t: Throwable) {
                GTRadioLogger.log("At3AudioArchive: Failed to load libgtradio_at3.so", Exception(t))
            }
        }

        @JvmStatic
        external fun nativeDecodeAt3ToWav(
            inData: ByteArray,
            inLen: Int,
            channels: Int,
            sampleRate: Int,
            blockAlign: Int,
            isAtrac3Plus: Boolean,
            extraData: ByteArray?,
            skipFrames: Int,
            outWavPath: String
        ): Boolean
    }

    private fun readFully(ch: FileChannel, dst: ByteBuffer, position: Long): Int {
        var total = 0
        var pos = position
        while (dst.hasRemaining()) {
            val n = ch.read(dst, pos)
            if (n <= 0) break
            total += n
            pos += n
        }
        return total
    }

    @Synchronized
    fun ensureHeaderParsed() {
        if (parsed) return
        parsed = true
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                val fileSize = pfd.statSize
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
                    val read = readFully(ch, buf, 0L)
                    if (read < 44) return

                    var pos = 12
                    while (pos + 8 <= read) {
                        val cid0 = buf.get(pos).toInt().toChar()
                        val cid1 = buf.get(pos + 1).toInt().toChar()
                        val cid2 = buf.get(pos + 2).toInt().toChar()
                        val cid3 = buf.get(pos + 3).toInt().toChar()
                        val chunkId = "$cid0$cid1$cid2$cid3"
                        val chunkSize = buf.getInt(pos + 4)

                        if (chunkId == "fmt " && pos + 8 + 16 <= read) {
                            val fmtTag = buf.getShort(pos + 8).toInt() and 0xFFFF
                            channels = (buf.getShort(pos + 10).toInt() and 0xFFFF).coerceIn(1, 2)
                            sampleRate = buf.getInt(pos + 12).coerceIn(8000, 48000)
                            byteRate = buf.getInt(pos + 16).coerceAtLeast(1000)
                            blockAlign = (buf.getShort(pos + 20).toInt() and 0xFFFF).coerceAtLeast(64)
                            isAtrac3Plus = (fmtTag == 0xFFFE)
                            val extraLen = (chunkSize - 16).coerceIn(0, 64)
                            if (extraLen > 0 && pos + 24 + extraLen <= read) {
                                val ex = ByteArray(extraLen)
                                for (k in 0 until extraLen) ex[k] = buf.get(pos + 24 + k)
                                extraData = ex
                            }
                        } else if (chunkId == "data") {
                            dataOffset = (pos + 8).toLong()
                            dataSize = if (chunkSize > 0 && dataOffset + chunkSize <= fileSize) {
                                chunkSize.toLong()
                            } else {
                                (fileSize - dataOffset).coerceAtLeast(0L)
                            }
                            break
                        }
                        pos += 8 + chunkSize + (chunkSize and 1)
                    }

                    if (dataSize <= 0L && fileSize > 164L) {
                        dataOffset = 164L
                        dataSize = fileSize - 164L
                    }
                    totalFrames = (dataSize / blockAlign.toLong()).toInt().coerceAtLeast(1)
                    val samplesPerFrame = if (isAtrac3Plus) 2048L else 1024L
                    durationMs = ((totalFrames.toLong() * samplesPerFrame * 1000L) / sampleRate.toLong()).coerceAtLeast(10_000L)
                }
            }
            GTRadioLogger.log("At3AudioArchive[$fileName]: ch=$channels, sr=$sampleRate, align=$blockAlign, dataOff=$dataOffset, frames=$totalFrames, dur=${durationMs / 1000}s")
        } catch (e: Exception) {
            GTRadioLogger.log("At3AudioArchive[$fileName]: Failed to parse header", e)
        }
    }

    fun materializeChunkUri(startFrame: Int, frameCount: Int = CHUNK_FRAMES): Uri? {
        ensureHeaderParsed()
        val safeStart = startFrame.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        val uri = materializeInternal(safeStart, frameCount)

        val nextStart = if (safeStart + frameCount < totalFrames) (safeStart + frameCount) else 0
        prefetchExecutor.submit {
            try {
                materializeInternal(nextStart, frameCount)
            } catch (_: Throwable) {}
        }
        return uri
    }

    private fun materializeInternal(startFrame: Int, frameCount: Int): Uri? = synchronized(decodeLock) {
        val cacheDir = File(context.cacheDir, "at3_tracks").apply { mkdirs() }
        val cleanBase = fileName.uppercase().removeSuffix(".AT3").replace(Regex("[^A-Z0-9_]"), "_")
        val safeFileName = "at3_${cleanBase}_f${startFrame}_${frameCount}.wav"
        val outFile = File(cacheDir, safeFileName)
        val now = System.currentTimeMillis()
        if (outFile.exists() && outFile.length() > 44L) {
            outFile.setLastModified(now)
            return Uri.fromFile(outFile)
        }

        // Enforce shared 95 MB audio cache cap across AT3, GTA IV RPF, and GTA SA caches
        try {
            val maxCacheBytes = 95L * 1024L * 1024L
            val allCached = ArrayList<File>()
            cacheDir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allCached.addAll(it) }
            File(context.cacheDir, "gta4_rpf_tracks_v2").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allCached.addAll(it) }
            File(context.cacheDir, "gen2_stream_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allCached.addAll(it) }
            File(context.cacheDir, "sa_stream_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allCached.addAll(it) }
            File(context.cacheDir, "sa_raw_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allCached.addAll(it) }

            var totalBytes = 0L
            for (f in allCached) totalBytes += f.length()

            if (totalBytes > maxCacheBytes) {
                allCached.sortBy { it.lastModified() }
                val targetBytes = 65L * 1024L * 1024L
                for (f in allCached) {
                    if (totalBytes <= targetBytes) break
                    val ageMs = now - f.lastModified()
                    if (f.name != safeFileName && ageMs > 25_000L) {
                        val len = f.length()
                        if (f.delete()) totalBytes -= len
                    }
                }
            }
        } catch (_: Exception) {}

        val preRollFrames = if (startFrame >= 2) 2 else 0
        val actualFirstFrame = startFrame - preRollFrames
        val framesToRead = minOf(frameCount + preRollFrames, totalFrames - actualFirstFrame)
        if (framesToRead <= preRollFrames) return null

        val bytesToRead = framesToRead * blockAlign
        val byteOffset = dataOffset + (actualFirstFrame.toLong() * blockAlign.toLong())
        val inBuf = ByteArray(bytesToRead)

        val tempFile = File(cacheDir, "${safeFileName}_${Thread.currentThread().id}.tmp")
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val n = readFully(ch, ByteBuffer.wrap(inBuf), byteOffset)
                    if (n < blockAlign) return null
                }
            }

            val ok = nativeDecodeAt3ToWav(
                inData = inBuf,
                inLen = bytesToRead,
                channels = channels,
                sampleRate = sampleRate,
                blockAlign = blockAlign,
                isAtrac3Plus = isAtrac3Plus,
                extraData = extraData,
                skipFrames = preRollFrames,
                outWavPath = tempFile.absolutePath
            )

            if (ok && tempFile.exists() && tempFile.length() > 44L) {
                tempFile.renameTo(outFile)
                return Uri.fromFile(outFile)
            }
        } catch (t: Throwable) {
            GTRadioLogger.log("At3AudioArchive[$fileName]: Native decode failed at frame $startFrame", Exception(t))
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
        return null
    }
}
