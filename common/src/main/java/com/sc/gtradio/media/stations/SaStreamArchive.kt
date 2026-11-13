package com.sc.gtradio.media.stations

import android.content.Context
import android.net.Uri
import com.sc.gtradio.common.GTRadioLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

enum class SaContainerType {
    MOBILE_OSW,
    PC_STREAM
}

data class SaTrackSlice(
    val trackNumber: Int,
    val dataOffset: Long,
    val dataLength: Long,
    val compressionMethod: Int = 0
)

class SaStreamArchive(
    private val context: Context,
    val streamCode: String,
    val sourceUri: Uri,
    val containerType: SaContainerType
) {
    private val slices = HashMap<Int, SaTrackSlice>()
    private var indexed = false
    private var pcXorKey: ByteArray = ByteArray(16)

    @Synchronized
    fun ensureIndexed() {
        if (indexed) return
        indexed = true
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (containerType == SaContainerType.MOBILE_OSW) {
                        indexOswZip(ch)
                    } else {
                        indexPcStream(ch)
                    }
                }
            }
            GTRadioLogger.log("SaStreamArchive[$streamCode]: Indexed ${slices.size} tracks ($containerType)")
        } catch (e: Exception) {
            GTRadioLogger.log("SaStreamArchive[$streamCode]: Indexing failed", e)
        }
    }

    fun hasTrack(trackNum: Int): Boolean {
        ensureIndexed()
        return slices.containsKey(trackNum)
    }

    fun getTrackNumbers(): Set<Int> {
        ensureIndexed()
        return slices.keys
    }

    private fun indexOswZip(ch: FileChannel) {
        val size = ch.size()
        if (size < 22) return
        val tailSize = minOf(size, 65557L).toInt()
        val tailBuf = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN)
        ch.read(tailBuf, size - tailSize)
        val arr = tailBuf.array()

        var eocdPos = -1
        for (i in tailSize - 22 downTo 0) {
            if (arr[i] == 0x50.toByte() && arr[i + 1] == 0x4b.toByte() &&
                arr[i + 2] == 0x05.toByte() && arr[i + 3] == 0x06.toByte()
            ) {
                eocdPos = i
                break
            }
        }
        if (eocdPos < 0) return

        val cdEntries = ((arr[eocdPos + 10].toInt() and 0xFF) or ((arr[eocdPos + 11].toInt() and 0xFF) shl 8))
        val cdSize = tailBuf.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = tailBuf.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL

        val cdBuf = ByteBuffer.allocate(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        ch.read(cdBuf, cdOffset)
        cdBuf.flip()

        val numRegex = Regex("(\\d+)")
        var pos = 0
        for (entryIdx in 0 until cdEntries) {
            if (pos + 46 > cdBuf.limit()) break
            val sig = cdBuf.getInt(pos)
            if (sig != 0x02014b50) break

            val method = cdBuf.getShort(pos + 10).toInt() and 0xFFFF
            val compSize = cdBuf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
            val nameLen = cdBuf.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = cdBuf.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = cdBuf.getShort(pos + 32).toInt() and 0xFFFF
            val localHeaderOffset = cdBuf.getInt(pos + 42).toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(nameLen)
            cdBuf.position(pos + 46)
            cdBuf.get(nameBytes)
            val entryName = String(nameBytes, Charsets.UTF_8)

            val m = numRegex.find(entryName)
            val trackNum = m?.groupValues?.get(1)?.toIntOrNull()
            if (trackNum != null && compSize > 0) {
                slices[trackNum] = SaTrackSlice(trackNum, localHeaderOffset, compSize, method)
            }
            pos += 46 + nameLen + extraLen + commentLen
        }
    }

    private fun indexPcStream(ch: FileChannel) {
        val fileSize = ch.size()
        if (fileSize < 8080L) return

        // Bytes 7984..7999 (16-byte aligned) are always 0xCD padding at the end of the 8000-byte beat table
        val keyProbe = ByteBuffer.allocate(16)
        ch.read(keyProbe, 7984L)
        val kp = keyProbe.array()
        val derived = ByteArray(16)
        for (i in 0 until 16) {
            derived[i] = (kp[i].toInt() xor 0xCD).toByte()
        }
        pcXorKey = derived

        var offset = 0L
        var trackNum = 1
        val hdrBuf = ByteBuffer.allocate(68)

        while (offset + 8068L < fileSize) {
            hdrBuf.clear()
            val readCount = ch.read(hdrBuf, offset + 8000L)
            if (readCount < 68) break

            val raw68 = hdrBuf.array()
            val dec68 = ByteArray(68)
            for (i in 0 until 68) {
                val kIdx = ((offset + 8000L + i) % 16L).toInt()
                dec68[i] = (raw68[i].toInt() xor pcXorKey[kIdx].toInt()).toByte()
            }

            val bb = ByteBuffer.wrap(dec68).order(ByteOrder.LITTLE_ENDIAN)
            var oggLen = -1L
            for (slot in 0 until 8) {
                val lenInt = bb.getInt(slot * 8)
                val rateInt = bb.getInt(slot * 8 + 4)
                if (lenInt != -842150451 && rateInt != -842150451 && lenInt > 0) { // -842150451 is 0xCDCDCDCD
                    val cand = lenInt.toLong() and 0xFFFFFFFFL
                    if (cand > 0L && offset + 8068L + cand <= fileSize) {
                        oggLen = cand
                        break
                    }
                }
            }

            if (oggLen <= 0L) {
                GTRadioLogger.log("SaStreamArchive[$streamCode]: Stopped at offset $offset (invalid oggLen=$oggLen)")
                break
            }

            slices[trackNum] = SaTrackSlice(trackNum, offset + 8068L, oggLen, 0)
            offset += 8068L + oggLen
            trackNum++
        }
    }

    fun materializeTrackUri(trackNum: Int): Uri? {
        ensureIndexed()
        val slice = slices[trackNum] ?: return null
        val ext = if (containerType == SaContainerType.MOBILE_OSW) "mp3" else "ogg"
        val cacheDir = File(context.cacheDir, "sa_stream_tracks").apply { mkdirs() }
        val outFile = File(cacheDir, "${streamCode}_${trackNum}.$ext")
        if (outFile.exists() && outFile.length() == slice.dataLength) {
            return Uri.fromFile(outFile)
        }

        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (containerType == SaContainerType.MOBILE_OSW) {
                        extractOswSlice(ch, slice, outFile)
                    } else {
                        extractPcSlice(ch, slice, outFile)
                    }
                }
            }
            if (outFile.exists() && outFile.length() > 0L) {
                return Uri.fromFile(outFile)
            }
        } catch (e: Exception) {
            GTRadioLogger.log("SaStreamArchive[$streamCode]: Failed extracting track $trackNum", e)
        }
        return null
    }

    private fun extractOswSlice(ch: FileChannel, slice: SaTrackSlice, outFile: File) {
        val lfh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        ch.read(lfh, slice.dataOffset)
        val nameLen = lfh.getShort(26).toInt() and 0xFFFF
        val extraLen = lfh.getShort(28).toInt() and 0xFFFF
        val payloadOffset = slice.dataOffset + 30L + nameLen + extraLen

        val compBytes = ByteArray(slice.dataLength.toInt())
        ch.read(ByteBuffer.wrap(compBytes), payloadOffset)

        FileOutputStream(outFile).use { out ->
            if (slice.compressionMethod == 0) {
                out.write(compBytes)
            } else {
                InflaterInputStream(compBytes.inputStream(), Inflater(true)).use { inf ->
                    inf.copyTo(out)
                }
            }
        }
    }

    private fun extractPcSlice(ch: FileChannel, slice: SaTrackSlice, outFile: File) {
        val raw = ByteArray(slice.dataLength.toInt())
        ch.read(ByteBuffer.wrap(raw), slice.dataOffset)
        val baseMod = (slice.dataOffset % 16L).toInt()
        for (i in raw.indices) {
            val k = pcXorKey[(baseMod + i) and 15]
            raw[i] = (raw[i].toInt() xor k.toInt()).toByte()
        }
        FileOutputStream(outFile).use { it.write(raw) }
    }
}
