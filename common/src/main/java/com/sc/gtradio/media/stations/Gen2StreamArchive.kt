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

enum class Gen2ContainerType {
    MOBILE_OSW,
    PC_STREAM
}

data class Gen2TrackSlice(
    val trackNumber: Int,
    val dataOffset: Long,
    val dataLength: Long,
    val compressionMethod: Int = 0
)

class Gen2StreamArchive(
    private val context: Context,
    val streamCode: String,
    val sourceUri: Uri,
    val containerType: Gen2ContainerType
) {
    private val slices = HashMap<Int, Gen2TrackSlice>()
    private var indexed = false
    private var pcXorKey: ByteArray = intArrayOf(0xEA, 0x3A, 0xC4, 0xA1, 0x9A, 0xA8, 0x14, 0xF3, 0x48, 0xB0, 0xD7, 0x23, 0x9D, 0xE8, 0xFF, 0xF1).map { it.toByte() }.toByteArray()

    @Synchronized
    fun ensureIndexed() {
        if (indexed) return
        indexed = true
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (containerType == Gen2ContainerType.MOBILE_OSW) {
                        indexOswZip(ch)
                    } else {
                        indexPcStream(ch)
                    }
                }
            }
            GTRadioLogger.log("Gen2StreamArchive[$streamCode]: Indexed ${slices.size} tracks ($containerType)")
        } catch (e: Exception) {
            GTRadioLogger.log("Gen2StreamArchive[$streamCode]: Indexing failed", e)
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

    private fun indexOswZip(ch: FileChannel) {
        val size = ch.size()
        if (size < 22) return
        val tailSize = minOf(size, 65557L).toInt()
        val tailBuf = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN)
        readFully(ch, tailBuf, size - tailSize)
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
        readFully(ch, cdBuf, cdOffset)
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
                slices[trackNum] = Gen2TrackSlice(trackNum, localHeaderOffset, compSize, method)
            }
            pos += 46 + nameLen + extraLen + commentLen
        }
    }

    private fun indexPcStream(ch: FileChannel) {
        val fileSize = ch.size()
        if (fileSize < 8080L) return

        // Derive 16-byte XOR key from the four 16-byte-aligned blocks at 8000..8063
        // (7 of the 8 length/rate slots are 0xCDCDCDCD, so 3 of the 4 16-byte blocks are pure 0xCD)
        val probe64 = ByteBuffer.allocate(64)
        if (readFully(ch, probe64, 8000L) == 64) {
            val raw64 = probe64.array()
            val b0 = raw64.copyOfRange(0, 16)
            val b1 = raw64.copyOfRange(16, 32)
            val b2 = raw64.copyOfRange(32, 48)
            val padBlock = if (b0.contentEquals(b1) || b0.contentEquals(b2)) b0 else b1
            val derived = ByteArray(16)
            for (i in 0 until 16) {
                derived[i] = (padBlock[i].toInt() xor 0xCD).toByte()
            }
            pcXorKey = derived
        }

        var offset = 0L
        var trackNum = 1
        val hdrBuf = ByteBuffer.allocate(68)

        while (offset + 8068L < fileSize) {
            hdrBuf.clear()
            val readCount = readFully(ch, hdrBuf, offset + 8000L)
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
                if (lenInt != -842150451 && rateInt != -842150451 && lenInt > 0) {
                    val cand = lenInt.toLong() and 0xFFFFFFFFL
                    if (cand > 0L && offset + 8068L + cand <= fileSize) {
                        oggLen = cand
                        break
                    }
                }
            }

            if (oggLen <= 0L) {
                GTRadioLogger.log("Gen2StreamArchive[$streamCode]: Stopped at offset $offset (invalid oggLen=$oggLen)")
                break
            }

            slices[trackNum] = Gen2TrackSlice(trackNum, offset + 8068L, oggLen, 0)
            offset += 8068L + oggLen
            trackNum++
        }
    }

    fun materializeTrackUri(trackNum: Int): Uri? {
        ensureIndexed()
        val slice = slices[trackNum] ?: return null
        val ext = if (containerType == Gen2ContainerType.MOBILE_OSW) "mp3" else "ogg"
        val cacheDir = File(context.cacheDir, "gen2_stream_tracks").apply { mkdirs() }
        val outFile = File(cacheDir, "${streamCode}_${trackNum}.$ext")
        if (outFile.exists() && outFile.length() == slice.dataLength) {
            GTRadioLogger.logVerbose("DECODE") { "Gen2 cache HIT: ${outFile.name}" }
            return Uri.fromFile(outFile)
        }

        val decodeStart = System.currentTimeMillis()

        // Keep cache folder tidy so extracted segments never bloat phone storage
        try {
            val allCached = ArrayList<File>()
            cacheDir.listFiles()?.let { allCached.addAll(it) }
            File(context.cacheDir, "at3_tracks").listFiles()?.let { allCached.addAll(it) }
            File(context.cacheDir, "rpf3_tracks").listFiles()?.let { allCached.addAll(it) }
            File(context.cacheDir, "rpf7_tracks").listFiles()?.let { allCached.addAll(it) }
            File(context.cacheDir, "gta4_rpf_tracks_v3").listFiles()?.let { allCached.addAll(it) }
            File(context.cacheDir, "gta5_rpf_tracks").listFiles()?.let { allCached.addAll(it) }
            File(context.cacheDir, "sa_stream_tracks").listFiles()?.let { allCached.addAll(it) }
            File(context.cacheDir, "sa_raw_tracks").listFiles()?.let { allCached.addAll(it) }
            if (allCached.size > 60) {
                allCached.sortBy { it.lastModified() }
                var evictedFiles = 0
                var evictedBytes = 0L
                for (i in 0 until 20) {
                    val len = allCached[i].length()
                    if (allCached[i].delete()) {
                        evictedFiles++
                        evictedBytes += len
                    }
                }
                if (evictedFiles > 0) {
                    GTRadioLogger.logVerbose("DECODE") {
                        "Cache eviction (Gen2): pruned $evictedFiles file(s), ${evictedBytes / 1024} KB freed (file count > 60)"
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (containerType == Gen2ContainerType.MOBILE_OSW) {
                        extractOswSlice(ch, slice, outFile)
                    } else {
                        extractPcSlice(ch, slice, outFile)
                    }
                }
            }
            if (outFile.exists() && outFile.length() > 0L) {
                val decodeMs = System.currentTimeMillis() - decodeStart
                val format = if (containerType == Gen2ContainerType.MOBILE_OSW) "MP3" else "OGG"
                GTRadioLogger.logVerbose("DECODE") {
                    "Gen2 cache MISS: extracted ${outFile.name} in ${decodeMs}ms ($format, ${outFile.length() / 1024} KB)"
                }
                return Uri.fromFile(outFile)
            }
        } catch (e: Exception) {
            GTRadioLogger.log("Gen2StreamArchive[$streamCode]: Failed extracting track $trackNum", e)
        }
        return null
    }

    private fun extractOswSlice(ch: FileChannel, slice: Gen2TrackSlice, outFile: File) {
        val lfh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        readFully(ch, lfh, slice.dataOffset)
        val nameLen = lfh.getShort(26).toInt() and 0xFFFF
        val extraLen = lfh.getShort(28).toInt() and 0xFFFF
        val payloadOffset = slice.dataOffset + 30L + nameLen + extraLen

        val compBytes = ByteArray(slice.dataLength.toInt())
        readFully(ch, ByteBuffer.wrap(compBytes), payloadOffset)

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

    private fun extractPcSlice(ch: FileChannel, slice: Gen2TrackSlice, outFile: File) {
        val raw = ByteArray(slice.dataLength.toInt())
        readFully(ch, ByteBuffer.wrap(raw), slice.dataOffset)
        val baseMod = (slice.dataOffset % 16L).toInt()
        for (i in raw.indices) {
            val k = pcXorKey[(baseMod + i) and 15]
            raw[i] = (raw[i].toInt() xor k.toInt()).toByte()
        }
        FileOutputStream(outFile).use { it.write(raw) }
    }
}
