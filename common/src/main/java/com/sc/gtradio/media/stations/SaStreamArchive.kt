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
    private var pcXorKey: ByteArray = intArrayOf(0xEA, 0x3A, 0xC4, 0xA1, 0x9A, 0xA8, 0x14, 0xF3, 0x48, 0xB0, 0xD7, 0x23, 0x9D, 0xE8, 0xFF, 0xF1).map { it.toByte() }.toByteArray()

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
        if (fileSize < 8024L) return

        // Auto-derive 16-byte XOR key from Track 1 boundary (8000..8021)
        val probe = ByteBuffer.allocate(8032)
        ch.read(probe, 0L)
        val p = probe.array()
        val derived = ByteArray(16)
        // Bytes 8000..8003 decrypt to 01 00 FF FF (little-endian 0xFFFF0001)
        derived[0] = (p[8000].toInt() xor 0x01).toByte()
        derived[1] = (p[8001].toInt() xor 0x00).toByte()
        derived[2] = (p[8002].toInt() xor 0xFF).toByte()
        derived[3] = (p[8003].toInt() xor 0xFF).toByte()
        // Bytes 8008..8021 decrypt to "OggS" + 0x00, 0x02 + 8 zero bytes
        val oggHeader = byteArrayOf(
            0x4F, 0x67, 0x67, 0x53, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        for (i in 8 until 16) {
            derived[i] = (p[8000 + i].toInt() xor oggHeader[i - 8].toInt()).toByte()
        }
        // Bytes 64..79 are unused header slots filled with repeating padding byte (0xCD or 0x00)
        val padByte = p[64].toInt() xor derived[0].toInt()
        for (i in 4..7) {
            derived[i] = (p[64 + i].toInt() xor padByte).toByte()
        }
        pcXorKey = derived

        var offset = 0L
        var trackNum = 1
        val hdrBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (offset + 8008L < fileSize) {
            hdrBuf.clear()
            ch.read(hdrBuf, offset + 8000L)
            val raw8 = hdrBuf.array()
            val dec8 = ByteArray(8)
            for (i in 0 until 8) {
                val kIdx = ((offset + 8000L + i) % 16L).toInt()
                dec8[i] = (raw8[i].toInt() xor pcXorKey[kIdx].toInt()).toByte()
            }
            val bb = ByteBuffer.wrap(dec8).order(ByteOrder.LITTLE_ENDIAN)
            val magic = bb.int
            val oggLen = bb.int.toLong() and 0xFFFFFFFFL
            if (oggLen <= 0L || offset + 8008L + oggLen > fileSize) {
                break
            }
            slices[trackNum] = SaTrackSlice(trackNum, offset + 8008L, oggLen, 0)
            offset += 8008L + oggLen
            trackNum++
        }
    }

    fun materializeTrackUri(trackNum: Int): Uri? {
        ensureIndexed()
        val slice = slices[trackNum] ?: return null
        val ext = if (containerType == SaContainerType.MOBILE_OSW) "mp3" else "ogg"
        val cacheDir = File(context.cacheDir, "sa_stream_tracks").apply { mkdirs() }
        val outFile = File(cacheDir, "${streamCode}_${trackNum}.$ext")
        if (outFile.exists() && outFile.length() > 128L) {
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
            GTRadioLogger.log("SaStreamArchive[$streamCode]: Indexed ${slices.size} tracks ($containerType)")
        } catch (e: Exception) {
            GTRadioLogger.log("SaStreamArchive[$streamCode]: Indexing failed", e)
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
