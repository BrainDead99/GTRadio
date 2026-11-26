package com.sc.gtradio.media.rpf3

import android.content.Context
import android.net.Uri
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.core.DjMarkerWindow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Precomputed block geometry and virtual WAV layout for zero-cache streaming of an RPF3 MultiC entry.
 */
data class Rpf3StreamIndex(
    val entryName: String,
    val entryOffset: Long,
    val entrySize: Long,
    val headerSize: Int,
    val numBlocks: Int,
    val blockSize: Int,
    val outChannels: Int,
    val sampleRate: Int,
    val isCompressed: Boolean,
    val chTableOffset: Int,
    val numChannels: Int,
    val headerBytes: ByteArray,
    val blockStartSamples: LongArray,
    val blockSamples: LongArray,
    val blockPcmBytes: LongArray,
    val blockStartWavByte: LongArray,
    val validBlockIndices: IntArray,
    val totalSamples: Long,
    val totalPcmBytes: Long,
    val totalVirtualWavBytes: Long,
    val durationMs: Long
)

/**
 * Thread-safe registry mapping active [Rpf3Archive.archiveStreamId] keys to their archives
 * so [Rpf3StreamingDataSource] can resolve `gtradio-rpf3://` URIs without Context allocations.
 */
object Rpf3StreamRegistry {
    private val archives = ConcurrentHashMap<String, Rpf3Archive>()

    fun register(archiveId: String, archive: Rpf3Archive) {
        archives[archiveId] = archive
    }

    fun get(archiveId: String): Rpf3Archive? = archives[archiveId]

    fun resolveStreamIndex(uri: Uri?): Rpf3StreamIndex? {
        if (uri == null || uri.scheme != Rpf3StreamingDataSource.SCHEME) return null
        val archiveId = uri.host ?: return null
        val rawPath = uri.encodedPath?.removePrefix("/")?.removeSuffix(".wav") ?: return null
        val entryName = Uri.decode(rawPath)
        val archive = get(archiveId) ?: return null
        return archive.getOrBuildStreamIndex(entryName)
    }

    fun hasValidEntry(uri: Uri?): Boolean = resolveStreamIndex(uri) != null
}

/**
 * Metadata record for a continuous-mix trackid marker inside an IVAUD marker table.
 */
data class Rpf3TrackMarker(
    val timeOffsetMs: Long,
    val trackId: Int
)

data class Rpf3Entry(
    val name: String,
    val offset: Long,
    val size: Long,
    val isWaveBank: Boolean,
    val numBlocks: Int = 1
)

data class Rpf3BankSubTrack(
    val parentEntryName: String,
    val subName: String,
    val dataOffsetInRpf: Long,
    val numSamplesInBytes: Int,
    val numSamples16Bit: Int,
    val sampleRate: Int,
    val isCompressed: Boolean
)

class Rpf3Archive(
    private val context: Context,
    val rpfName: String,
    val sourceUri: Uri
) {
    private val entries = LinkedHashMap<String, Rpf3Entry>()
    private val bankSubTracks = LinkedHashMap<String, Rpf3BankSubTrack>()
    private val chunkStates = ConcurrentHashMap<String, IntArray>()
    private val trackMarkersCache = ConcurrentHashMap<String, List<Rpf3TrackMarker>>()
    private val djMarkerCache = ConcurrentHashMap<String, DjMarkerWindow>()
    private val streamIndexCache = ConcurrentHashMap<String, Rpf3StreamIndex>()
    val archiveStreamId: String = "rpf3_${nextArchiveId.incrementAndGet()}_" +
        rpfName.removeSuffix(".rpf").replace(Regex("[^A-Za-z0-9_]"), "_").lowercase(Locale.ROOT)
    @Volatile private var indexed = false
    val isIndexed: Boolean get() = indexed

    init {
        Rpf3StreamRegistry.register(archiveStreamId, this)
        Rpf3StreamingDataSource.ensureRegistered()
    }

    companion object {
        private val nextArchiveId = AtomicLong(0L)
        const val CHUNK_BLOCKS = 6
        const val CHUNK_THRESHOLD_BLOCKS = 18

        fun buildWavHeaderBytes(
            pcmDataBytes: Long,
            sampleRate: Int,
            channels: Int
        ): ByteArray {
            val totalDataLen = pcmDataBytes + 36L
            val byteRate = sampleRate.toLong() * channels.toLong() * 2L
            val blockAlign = channels * 2

            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xFFL).toByte()
            header[5] = ((totalDataLen ushr 8) and 0xFFL).toByte()
            header[6] = ((totalDataLen ushr 16) and 0xFFL).toByte()
            header[7] = ((totalDataLen ushr 24) and 0xFFL).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0
            header[22] = channels.toByte(); header[23] = 0
            header[24] = (sampleRate and 0xFF).toByte()
            header[25] = ((sampleRate ushr 8) and 0xFF).toByte()
            header[26] = ((sampleRate ushr 16) and 0xFF).toByte()
            header[27] = ((sampleRate ushr 24) and 0xFF).toByte()
            header[28] = (byteRate and 0xFFL).toByte()
            header[29] = ((byteRate ushr 8) and 0xFFL).toByte()
            header[30] = ((byteRate ushr 16) and 0xFFL).toByte()
            header[31] = ((byteRate ushr 24) and 0xFFL).toByte()
            header[32] = blockAlign.toByte(); header[33] = 0
            header[34] = 16; header[35] = 0
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (pcmDataBytes and 0xFFL).toByte()
            header[41] = ((pcmDataBytes ushr 8) and 0xFFL).toByte()
            header[42] = ((pcmDataBytes ushr 16) and 0xFFL).toByte()
            header[43] = ((pcmDataBytes ushr 24) and 0xFFL).toByte()
            return header
        }
        private val prefetchExecutor = Executors.newSingleThreadExecutor()
        private val decodeLock = Any()
        @Volatile private var oldCachePurged = false
        private val NO_DJ_MARKERS = DjMarkerWindow()

        private val pinnedFileCounts = ConcurrentHashMap<String, AtomicInteger>()

        fun pinFile(fileNameOrUri: String) {
            val name = fileNameOrUri.substringAfterLast('/')
            if (name.isEmpty()) return
            pinnedFileCounts.compute(name) { _, existing ->
                (existing ?: AtomicInteger(0)).apply { incrementAndGet() }
            }
        }

        fun unpinFile(fileNameOrUri: String) {
            val name = fileNameOrUri.substringAfterLast('/')
            if (name.isEmpty()) return
            pinnedFileCounts.computeIfPresent(name) { _, counter ->
                if (counter.decrementAndGet() <= 0) null else counter
            }
        }

        fun clearPinnedFiles() {
            pinnedFileCounts.clear()
        }

        fun isPinned(fileName: String): Boolean =
            (pinnedFileCounts[fileName]?.get() ?: 0) > 0

        private val INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )

        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
    }

    private class AdpcmState(var valprev: Int = 0, var index: Int = 0)

    private fun decodeNibble(nibble: Int, state: AdpcmState): Short {
        var idx = state.index
        val step = STEP_TABLE[idx]
        var valpred = state.valprev
        val delta = nibble and 0x0F

        idx += INDEX_TABLE[delta]
        if (idx < 0) idx = 0 else if (idx > 88) idx = 88

        val sign = (delta and 8) != 0
        val mag = delta and 7

        var vpdiff = step shr 3
        if ((mag and 4) != 0) vpdiff += step
        if ((mag and 2) != 0) vpdiff += step shr 1
        if ((mag and 1) != 0) vpdiff += step shr 2

        if (sign) valpred -= vpdiff else valpred += vpdiff
        if (valpred > 32767) valpred = 32767 else if (valpred < -32768) valpred = -32768

        state.valprev = valpred
        state.index = idx
        return valpred.toShort()
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

    private fun getCacheDir(): File {
        if (!oldCachePurged) {
            oldCachePurged = true
            try {
                File(context.cacheDir, "gta4_rpf_tracks").deleteRecursively()
                File(context.cacheDir, "gta4_rpf_tracks_v2").deleteRecursively()
                File(context.cacheDir, "gta4_rpf_tracks_v3").deleteRecursively()
            } catch (_: Exception) {}
        }
        return File(context.cacheDir, "rpf3_tracks").apply { mkdirs() }
    }

    @Synchronized
    fun ensureIndexed() {
        if (indexed) return
        Rpf3MetadataRepository.ensureLoaded(context)
        try {
            entries.clear()
            bankSubTracks.clear()
            var ok = false
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    ok = indexRpf(ch)
                }
            }
            if (ok) {
                indexed = true
                GTRadioLogger.log("Rpf3Archive[$rpfName]: Indexed ${entries.size} entries and ${bankSubTracks.size} bank sub-tracks")
            } else {
                entries.clear()
                bankSubTracks.clear()
                GTRadioLogger.log("Rpf3Archive[$rpfName]: Indexing incomplete; deferred for retry")
            }
        } catch (e: Exception) {
            entries.clear()
            bankSubTracks.clear()
            GTRadioLogger.log("Rpf3Archive[$rpfName]: Indexing failed", e)
        }
    }

    fun getFileEntries(): Collection<Rpf3Entry> {
        ensureIndexed()
        return entries.values
    }

    fun getBankSubTracks(): Collection<Rpf3BankSubTrack> {
        ensureIndexed()
        return bankSubTracks.values
    }

    fun hasEntry(name: String): Boolean {
        ensureIndexed()
        val u = name.uppercase()
        return entries.containsKey(u) || bankSubTracks.containsKey(u)
    }

    fun getEntryBlockCount(name: String): Int {
        ensureIndexed()
        return entries[name.uppercase()]?.numBlocks ?: 1
    }

    private fun indexRpf(ch: FileChannel): Boolean {
        if (ch.size() < 2048L) return false
        val hdrBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        readFully(ch, hdrBuf, 0L)
        hdrBuf.flip()

        val magicBytes = ByteArray(4)
        hdrBuf.get(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)
        if (magic != "RPF3") return false

        val tocSize = hdrBuf.int
        val entryCount = hdrBuf.int
        hdrBuf.int // unknown
        val encryptedFlag = hdrBuf.int

        if (tocSize <= 0 || tocSize > 262144 || entryCount <= 0) return false
        val paddedTocSize = (tocSize + 15) and -16
        val tocBuf = ByteBuffer.allocate(paddedTocSize)
        readFully(ch, tocBuf, 0x800L)
        var tocBytes = tocBuf.array()

        if (encryptedFlag != 0) {
            val key = Rpf3KeyManager.getKey(context)
            if (key == null) {
                GTRadioLogger.log("Rpf3Archive[$rpfName]: RPF3 key is not loaded/unlocked; cannot decrypt TOC")
                return false
            }
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            for (round in 0 until 16) {
                tocBytes = cipher.doFinal(tocBytes)
            }
        }

        val tocOrder = ByteBuffer.wrap(tocBytes).order(ByteOrder.LITTLE_ENDIAN)
        val probe16 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until entryCount) {
            val pos = i * 16
            if (pos + 16 > tocBytes.size) break
            val nameHash = tocOrder.getInt(pos)
            val data1 = tocOrder.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val rawData2 = tocOrder.getInt(pos + 8)
            val isDir = (rawData2 and -0x80000000) != 0
            if (isDir) continue

            val fileOffset = (rawData2 and 0x7FFFFFFF).toLong()
            val fileSize = data1
            if (fileSize < 32L || fileOffset + fileSize > ch.size()) continue

            val entryName = Rpf3MetadataRepository.resolveHash(context, nameHash).uppercase()
            if (entryName == "NULLTRACK") continue

            probe16.clear()
            readFully(ch, probe16, fileOffset)
            val firstInt = probe16.getInt(0)
            val secondInt = probe16.getInt(4)
            val isBank = (firstInt == 28 && secondInt == 0)
            val blocks = if (isBank) 1 else probe16.getInt(8).coerceAtLeast(1)

            val entry = Rpf3Entry(entryName, fileOffset, fileSize, isBank, blocks)
            entries[entryName] = entry

            if (isBank) {
                indexSingleCBank(ch, entry)
            }
        }
        return true
    }

    private fun indexSingleCBank(ch: FileChannel, bankEntry: Rpf3Entry) {
        val hdr28 = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        if (readFully(ch, hdr28, bankEntry.offset) < 28) return
        val offsetWaveInfo = hdr28.getLong(0)
        val numBlocks = hdr28.getInt(16)
        val headerSize = hdr28.getInt(24)

        if (numBlocks <= 0 || numBlocks > 512 || offsetWaveInfo <= 0 || offsetWaveInfo > headerSize) return

        val wihTableSize = numBlocks * 16
        val wihBuf = ByteBuffer.allocate(wihTableSize).order(ByteOrder.LITTLE_ENDIAN)
        if (readFully(ch, wihBuf, bankEntry.offset + offsetWaveInfo) < wihTableSize) return

        val baseAfterWih = bankEntry.offset + offsetWaveInfo + wihTableSize.toLong()
        val wiBuf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numBlocks) {
            val wihOffset = wihBuf.getLong(i * 16)
            val wihHash = wihBuf.getInt(i * 16 + 8)
            val wihSize = wihBuf.getInt(i * 16 + 12)

            wiBuf.clear()
            val readLen = minOf(64, wihSize.coerceAtLeast(32))
            wiBuf.limit(readLen)
            if (readFully(ch, wiBuf, baseAfterWih + wihOffset) < 28) continue

            val sampleDataRelOffset = wiBuf.getLong(0)
            val wiHash = wiBuf.getInt(8)
            val numSamplesInBytes = wiBuf.getInt(12)
            val numSamples16Bit = wiBuf.getInt(16)
            var sampleRate = wiBuf.getShort(24).toInt() and 0xFFFF
            if (sampleRate !in 8000..48000) sampleRate = 32000

            // WInumStates is at byte offset 52 of WaveInfo (not 56), and 4-bit ADPCM uses ~0.5 bytes/sample
            val numStates = if (wihSize > 32 && readLen >= 56) wiBuf.getInt(52) else 1
            val isCompressed = (numStates > 0) || (numSamplesInBytes in 1 until numSamples16Bit)

            val resolvedSubName = Rpf3MetadataRepository.resolveHash(context, if (wihHash != 0) wihHash else wiHash).uppercase()
            val subKey = if (resolvedSubName.startsWith("0X")) "${bankEntry.name}_$i" else resolvedSubName

            val absDataOffset = bankEntry.offset + headerSize.toLong() + sampleDataRelOffset
            if (numSamples16Bit > 0 && absDataOffset < bankEntry.offset + bankEntry.size) {
                val subObj = Rpf3BankSubTrack(
                    parentEntryName = bankEntry.name,
                    subName = subKey,
                    dataOffsetInRpf = absDataOffset,
                    numSamplesInBytes = numSamplesInBytes,
                    numSamples16Bit = numSamples16Bit,
                    sampleRate = sampleRate,
                    isCompressed = isCompressed
                )
                bankSubTracks["${bankEntry.name.uppercase()}#$subKey"] = subObj
                if (!bankEntry.name.uppercase().startsWith("DJ_")) {
                    bankSubTracks[subKey] = subObj
                }
            }
        }
    }

    fun readTrackMarkers(entryName: String): List<Rpf3TrackMarker> {
        ensureIndexed()
        val cleanKey = entryName.uppercase()
        trackMarkersCache[cleanKey]?.let { return it }

        val entry = entries[cleanKey] ?: return emptyList()
        if (entry.isWaveBank) return emptyList()

        val markers = ArrayList<Rpf3TrackMarker>()
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val probeBuf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
                    if (readFully(ch, probeBuf, entry.offset) >= 32) {
                        val markerTableOffset = probeBuf.getInt(28).toLong() and 0xFFFFFFFFL
                        if (markerTableOffset > 0L && markerTableOffset + 24L <= entry.size) {
                            val mHdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                            val bytesRead = readFully(ch, mHdr, entry.offset + markerTableOffset)
                            if (bytesRead == 24) {
                                val tagAt16 = mHdr.getInt(16)
                                val tagAt0 = mHdr.getInt(0)
                                val expectedTag = 0x7DEEF8B7.toInt()
                                val (markerChunkTag, markerDataSize, recOffsetRel) = when {
                                    tagAt16 == expectedTag -> Triple(tagAt16, mHdr.getInt(20), 24L)
                                    tagAt0 == expectedTag -> Triple(tagAt0, mHdr.getInt(4), 8L)
                                    else -> Triple(0, 0, 0L)
                                }
                                if (markerChunkTag == expectedTag &&
                                    markerDataSize > 0 &&
                                    markerDataSize <= 131072 &&
                                    markerTableOffset + 24L + markerDataSize <= entry.size &&
                                    markerTableOffset + recOffsetRel + markerDataSize <= entry.size
                                ) {
                                    val recBuf = ByteBuffer.allocate(markerDataSize).order(ByteOrder.LITTLE_ENDIAN)
                                    val markerBytesRead = readFully(ch, recBuf, entry.offset + markerTableOffset + recOffsetRel)
                                    if (markerBytesRead >= 16) {
                                        val actualBuf = ByteBuffer.wrap(recBuf.array(), 0, markerBytesRead).order(ByteOrder.LITTLE_ENDIAN)
                                        var djIntroStart = -1L
                                        var djIntroEnd = -1L
                                        var djOutroStart = -1L
                                        var djOutroEnd = -1L
                                        while (actualBuf.remaining() >= 16) {
                                            val nameHash = actualBuf.int
                                            val rawVal = actualBuf.int
                                            actualBuf.int // pad
                                            val timeOffsetUint = actualBuf.int
                                            val tOff = timeOffsetUint.toLong() and 0xFFFFFFFFL

                                            if (nameHash == 0xA6D93246.toInt()) {
                                                val tid = java.lang.Float.intBitsToFloat(rawVal).toInt()
                                                markers.add(Rpf3TrackMarker(tOff, tid))
                                            } else if (nameHash == 0x08DBA0F8.toInt()) {
                                                when (rawVal) {
                                                    0x449687BA.toInt() -> djIntroStart = tOff
                                                    0x2CC6E05E.toInt() -> djIntroEnd = tOff
                                                    0x7DCE9FA0.toInt() -> djOutroStart = tOff
                                                    0x71D8DCAD.toInt() -> djOutroEnd = tOff
                                                }
                                            }
                                        }
                                        if (djIntroStart >= 0L || djIntroEnd > 0L || djOutroStart > 0L || djOutroEnd > 0L) {
                                            djMarkerCache[cleanKey] = DjMarkerWindow(djIntroStart, djIntroEnd, djOutroStart, djOutroEnd)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: java.nio.BufferUnderflowException) {
            GTRadioLogger.log("Rpf3Archive[$rpfName]: BufferUnderflowException reading track markers for $entryName", e)
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf3Archive[$rpfName]: Error reading track markers for $entryName", e)
        }

        markers.sortBy { it.timeOffsetMs }
        val deduped = ArrayList<Rpf3TrackMarker>()
        for (m in markers) {
            if (deduped.isEmpty() || deduped.last().trackId != m.trackId || deduped.last().timeOffsetMs != m.timeOffsetMs) {
                deduped.add(m)
            }
        }
        trackMarkersCache[cleanKey] = deduped
        return deduped
    }

    fun readDjMarkers(entryName: String): DjMarkerWindow? {
        val cleanKey = entryName.uppercase()
        djMarkerCache[cleanKey]?.let { return if (it === NO_DJ_MARKERS) null else it }
        readTrackMarkers(cleanKey)
        val cached = djMarkerCache[cleanKey] ?: NO_DJ_MARKERS
        djMarkerCache[cleanKey] = cached
        return if (cached === NO_DJ_MARKERS) null else cached
    }

    fun getChunkStartMs(entryName: String, startBlock: Int): Long {
        if (startBlock <= 0) return 0L
        ensureIndexed()
        val cleanKey = entryName.uppercase()
        val entry = entries[cleanKey] ?: return 0L
        if (entry.isWaveBank || startBlock >= entry.numBlocks) return 0L

        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val probeBuf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
                    if (readFully(ch, probeBuf, entry.offset) < 64) return 0L
                    val blockSize = probeBuf.getInt(12)
                    val rawChOff20 = probeBuf.getInt(20)
                    val rawChOff16 = probeBuf.getInt(16)
                    val chTableOffset = if (rawChOff20 in 16..256) rawChOff20 else if (rawChOff16 in 16..256) rawChOff16 else 48
                    val numChannels = probeBuf.getInt(36)
                    val headerSize = probeBuf.getInt(44)

                    if (blockSize <= 2048 || numChannels <= 0 || headerSize <= 0) return 0L
                    val hdrReadSize = headerSize.coerceIn(128, 524288)
                    val hdrBuf = ByteBuffer.allocate(hdrReadSize).order(ByteOrder.LITTLE_ENDIAN)
                    readFully(ch, hdrBuf, entry.offset)
                    val detailOffset = chTableOffset + (numChannels * 16)
                    var sampleRate = if (detailOffset + 28 <= hdrReadSize) (hdrBuf.getShort(detailOffset + 24).toInt() and 0xFFFF) else 32000
                    if (sampleRate !in 16000..48000) sampleRate = 32000

                    val outChannels = if (numChannels >= 2) 2 else 1
                    val blockStart = entry.offset + headerSize.toLong() + (startBlock.toLong() * blockSize.toLong())
                    val blockHeaderBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
                    if (readFully(ch, blockHeaderBuf, blockStart) >= 64) {
                        val tblOff = blockHeaderBuf.getLong(0).toInt().coerceIn(16, 64)
                        val sampleTblOff = tblOff + outChannels * 16
                        if (sampleTblOff + 4 <= 128) {
                            val startSample = blockHeaderBuf.getInt(sampleTblOff).coerceAtLeast(0)
                            if (startSample > 0) {
                                return (startSample.toLong() * 1000L) / sampleRate.toLong()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf3Archive[$rpfName]: Error getting chunk start ms for $entryName block $startBlock", e)
        }
        return 0L
    }

    fun isMultiChannelEntry(trackKey: String): Boolean {
        ensureIndexed()
        val cleanKey = trackKey.trim().uppercase(Locale.ROOT)
        val entry = entries[cleanKey] ?: return false
        return !entry.isWaveBank
    }

    fun buildStreamUri(entryName: String): Uri {
        Rpf3StreamRegistry.register(archiveStreamId, this)
        Rpf3StreamingDataSource.ensureRegistered()
        ensureIndexed()
        val cleanKey = entryName.trim().uppercase(Locale.ROOT)
        val targetName = entries[cleanKey]?.name ?: cleanKey
        return Uri.parse("${Rpf3StreamingDataSource.SCHEME}://$archiveStreamId/${Uri.encode(targetName)}.wav")
    }

    fun openStreamPfd(): android.os.ParcelFileDescriptor? {
        return try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")
        } catch (_: Exception) {
            null
        }
    }

    fun getEntryDurationMs(trackKey: String): Long? {
        ensureIndexed()
        val cleanKey = trackKey.trim().uppercase(Locale.ROOT)
        val sub = bankSubTracks[cleanKey]
        if (sub != null && sub.sampleRate > 0 && sub.numSamples16Bit > 0) {
            return (sub.numSamples16Bit.toLong() * 1000L) / sub.sampleRate.toLong()
        }
        val fileEntry = entries[cleanKey] ?: return null
        if (fileEntry.isWaveBank) {
            val firstSub = bankSubTracks.values.firstOrNull { it.parentEntryName == fileEntry.name } ?: return null
            if (firstSub.sampleRate > 0 && firstSub.numSamples16Bit > 0) {
                return (firstSub.numSamples16Bit.toLong() * 1000L) / firstSub.sampleRate.toLong()
            }
            return null
        }
        return getOrBuildStreamIndex(fileEntry.name)?.durationMs
    }

    fun getOrBuildStreamIndex(entryName: String): Rpf3StreamIndex? {
        ensureIndexed()
        val cleanKey = entryName.trim().uppercase(Locale.ROOT)
        val entry = entries[cleanKey] ?: return null
        if (entry.isWaveBank) return null

        streamIndexCache[entry.name]?.let { return it }

        try {
            var built: Rpf3StreamIndex? = null
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val probeBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
                    if (readFully(ch, probeBuf, entry.offset) < 64) return null

                    val rawNumBlocks = probeBuf.getInt(8)
                    val blockSize = probeBuf.getInt(12)
                    val rawChOff20 = probeBuf.getInt(20)
                    val rawChOff16 = probeBuf.getInt(16)
                    val chTableOffset = if (rawChOff20 in 16..256) rawChOff20 else if (rawChOff16 in 16..256) rawChOff16 else 48
                    val numChannels = probeBuf.getInt(36)
                    val headerSize = probeBuf.getInt(44)

                    if (rawNumBlocks <= 0 || blockSize <= 2048 || numChannels <= 0 || headerSize <= 0) return null

                    val hdrReadSize = minOf(headerSize.toLong(), entry.size).toInt().coerceIn(128, 524288)
                    val headerBytes = ByteArray(hdrReadSize)
                    val hdrGot = readFully(ch, ByteBuffer.wrap(headerBytes), entry.offset)
                    if (hdrGot < 128) return null
                    val hdrBuf = ByteBuffer.wrap(headerBytes, 0, hdrGot).order(ByteOrder.LITTLE_ENDIAN)

                    val detailOffset = chTableOffset + (numChannels * 16)
                    var sampleRate = if (detailOffset + 28 <= hdrGot) {
                        hdrBuf.getShort(detailOffset + 24).toInt() and 0xFFFF
                    } else 32000
                    if (sampleRate !in 16000..48000) sampleRate = 32000
                    val outChannels = if (numChannels >= 2) 2 else 1

                    val wihPos0 = chTableOffset
                    val wihOffset0 = if (wihPos0 + 16 <= hdrGot) hdrBuf.getLong(wihPos0).toInt() else 0
                    val wihSize0 = if (wihPos0 + 16 <= hdrGot) hdrBuf.getInt(wihPos0 + 12) else 0
                    val wiStart0 = detailOffset + wihOffset0
                    val numStates0 = if (wihSize0 > 32 && wiStart0 >= 0 && wiStart0 + 56 <= hdrGot) {
                        hdrBuf.getInt(wiStart0 + 52)
                    } else 0
                    val isCompressed = (wihSize0 > 32 && numStates0 > 0)

                    val validIndicesList = ArrayList<Int>(rawNumBlocks)
                    val startSamplesList = ArrayList<Long>(rawNumBlocks)
                    val blockSamplesList = ArrayList<Long>(rawNumBlocks)
                    val blockPcmBytesList = ArrayList<Long>(rawNumBlocks)
                    val blockStartWavByteList = ArrayList<Long>(rawNumBlocks)

                    val bytesPerFrame = outChannels.toLong() * 2L
                    var cumulativeSamples = 0L
                    val blockHeaderBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
                    val entryEnd = entry.offset + entry.size

                    for (b in 0 until rawNumBlocks) {
                        val blockStart = entry.offset + headerSize.toLong() + (b.toLong() * blockSize.toLong())
                        if (blockStart + 2048L > entryEnd) break
                        blockHeaderBuf.clear()
                        val got = readFully(ch, blockHeaderBuf, blockStart)
                        if (got < 64) break

                        val tblOff = blockHeaderBuf.getLong(0).toInt().coerceIn(16, 64)
                        if (tblOff + (outChannels * 16) > got) break

                        val ch0Samples = blockHeaderBuf.getInt(tblOff + 12)
                        val ch1Samples = if (outChannels == 2) blockHeaderBuf.getInt(tblOff + 28) else ch0Samples
                        val samplesInBlk = if (outChannels == 2) {
                            minOf(ch0Samples, ch1Samples).coerceAtLeast(0)
                        } else {
                            ch0Samples.coerceAtLeast(0)
                        }
                        if (samplesInBlk <= 0) continue

                        val sampleTblOff = tblOff + outChannels * 16
                        val rawFirstSample = if (sampleTblOff + 4 <= got) {
                            blockHeaderBuf.getInt(sampleTblOff).coerceAtLeast(0).toLong()
                        } else 0L
                        val blockStartSample = if (rawFirstSample > 0L || b == 0) rawFirstSample else cumulativeSamples

                        val pcmBytes = samplesInBlk.toLong() * bytesPerFrame
                        validIndicesList.add(b)
                        startSamplesList.add(blockStartSample)
                        blockSamplesList.add(samplesInBlk.toLong())
                        blockPcmBytesList.add(pcmBytes)
                        blockStartWavByteList.add(44L + cumulativeSamples * bytesPerFrame)
                        cumulativeSamples += samplesInBlk.toLong()
                    }

                    if (cumulativeSamples <= 0L || validIndicesList.isEmpty()) return null

                    val totalSamples = cumulativeSamples
                    val totalPcmBytes = totalSamples * bytesPerFrame
                    val totalVirtualWavBytes = 44L + totalPcmBytes
                    val durationMs = (totalSamples * 1000L) / sampleRate.toLong()

                    built = Rpf3StreamIndex(
                        entryName = entry.name,
                        entryOffset = entry.offset,
                        entrySize = entry.size,
                        headerSize = headerSize,
                        numBlocks = validIndicesList.size,
                        blockSize = blockSize,
                        outChannels = outChannels,
                        sampleRate = sampleRate,
                        isCompressed = isCompressed,
                        chTableOffset = chTableOffset,
                        numChannels = numChannels,
                        headerBytes = headerBytes,
                        blockStartSamples = startSamplesList.toLongArray(),
                        blockSamples = blockSamplesList.toLongArray(),
                        blockPcmBytes = blockPcmBytesList.toLongArray(),
                        blockStartWavByte = blockStartWavByteList.toLongArray(),
                        validBlockIndices = validIndicesList.toIntArray(),
                        totalSamples = totalSamples,
                        totalPcmBytes = totalPcmBytes,
                        totalVirtualWavBytes = totalVirtualWavBytes,
                        durationMs = durationMs
                    )
                }
            }
            val result = built ?: return null
            return streamIndexCache.putIfAbsent(entry.name, result) ?: result
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf3Archive[$rpfName]: Failed building stream index for $entryName", e)
            return null
        }
    }

    /**
     * Decodes a single MultiC block [validIdx] into raw interleaved 16-bit PCM bytes whose
     * exact length matches `index.blockPcmBytes[validIdx]`.
     */
    fun decodeSingleBlockToPcmBytes(
        index: Rpf3StreamIndex,
        validIdx: Int,
        channel: FileChannel
    ): ByteArray {
        if (validIdx !in 0 until index.numBlocks) return ByteArray(0)
        val expectedBytes = index.blockPcmBytes[validIdx].toInt().coerceAtLeast(0)
        if (expectedBytes == 0) return ByteArray(0)

        val rawBlockIdx = index.validBlockIndices[validIdx]
        val blockStart = index.entryOffset + index.headerSize.toLong() + (rawBlockIdx.toLong() * index.blockSize.toLong())
        val entryEnd = index.entryOffset + index.entrySize
        val actualBlockSize = minOf(index.blockSize.toLong(), entryEnd - blockStart).toInt()
        if (actualBlockSize <= 2048) return ByteArray(expectedBytes)

        val blockBytes = ByteArray(actualBlockSize)
        val bytesRead = readFully(channel, ByteBuffer.wrap(blockBytes), blockStart)
        if (bytesRead <= 2048) return ByteArray(expectedBytes)

        val bb = ByteBuffer.wrap(blockBytes, 0, minOf(128, bytesRead)).order(ByteOrder.LITTLE_ENDIAN)
        val tblOff = bb.getLong(0).toInt().coerceIn(16, 64)
        val outChannels = index.outChannels
        if (tblOff + outChannels * 16 > bb.limit()) return ByteArray(expectedBytes)

        val ch0StartChunk = bb.getInt(tblOff)
        val ch0ByteOff = 2048 + (ch0StartChunk * 2048)
        val ch1StartChunk = if (outChannels == 2) bb.getInt(tblOff + 16) else 0
        val ch1ByteOff = 2048 + (ch1StartChunk * 2048)

        val samples = index.blockSamples[validIdx].toInt().coerceAtLeast(0)
        if (samples <= 0) return ByteArray(expectedBytes)

        if (ch0ByteOff < 2048 || ch0ByteOff >= bytesRead) return ByteArray(expectedBytes)
        if (outChannels == 2 && (ch1ByteOff < 2048 || ch1ByteOff >= bytesRead)) return ByteArray(expectedBytes)

        if (!index.isCompressed) {
            val out = ByteArray(expectedBytes)
            val maxLeftSamples = ((bytesRead - ch0ByteOff) / 2).coerceAtLeast(0)
            val maxRightSamples = if (outChannels == 2) ((bytesRead - ch1ByteOff) / 2).coerceAtLeast(0) else maxLeftSamples
            val frames = minOf(samples, maxLeftSamples, maxRightSamples, expectedBytes / (outChannels * 2))
            if (frames > 0) {
                if (outChannels == 1) {
                    System.arraycopy(blockBytes, ch0ByteOff, out, 0, frames * 2)
                } else {
                    var outPos = 0
                    for (i in 0 until frames) {
                        val lOff = ch0ByteOff + i * 2
                        val rOff = ch1ByteOff + i * 2
                        out[outPos++] = blockBytes[lOff]
                        out[outPos++] = blockBytes[lOff + 1]
                        out[outPos++] = blockBytes[rOff]
                        out[outPos++] = blockBytes[rOff + 1]
                    }
                }
            }
            return out
        }

        val hdrBuf = ByteBuffer.wrap(index.headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val firstSample = index.blockStartSamples[validIdx]
        val startChunkIdx = (firstSample / 4096L).toInt()
        val leftState = readHeaderAdpcmState(hdrBuf, index.chTableOffset, index.numChannels, 0, startChunkIdx) ?: AdpcmState()
        val rightState = if (outChannels == 2) {
            readHeaderAdpcmState(hdrBuf, index.chTableOffset, index.numChannels, 1, startChunkIdx) ?: AdpcmState()
        } else {
            AdpcmState()
        }
        val stateArray = intArrayOf(
            leftState.valprev, leftState.index, rightState.valprev, rightState.index
        )
        val numBytesPerCh = (samples + 1) / 2
        val availCh0 = (bytesRead - ch0ByteOff).coerceAtLeast(0)
        val availCh1 = if (outChannels == 2) (bytesRead - ch1ByteOff).coerceAtLeast(0) else availCh0
        val safeBytesPerCh = minOf(numBytesPerCh, availCh0, availCh1)
        val safeSamples = minOf(samples, safeBytesPerCh * 2)
        val decoded = if (safeBytesPerCh > 0 && safeSamples > 0) {
            com.sc.gtradio.media.core.Gen3NativeTurbo.nativeDecodeRpf3ImaAdpcmToPcm16(
                blockBytes, ch0ByteOff, ch1ByteOff, safeBytesPerCh, safeSamples, outChannels, stateArray
            )
        } else {
            ByteArray(0)
        }
        return normalizeBlockPcmBytes(decoded, expectedBytes)
    }

    private fun normalizeBlockPcmBytes(pcm: ByteArray, expectedBytes: Int): ByteArray {
        if (pcm.size == expectedBytes) return pcm
        return ByteArray(expectedBytes).also { out ->
            val copyLen = minOf(pcm.size, expectedBytes)
            if (copyLen > 0) {
                System.arraycopy(pcm, 0, out, 0, copyLen)
            }
        }
    }

    fun materializeAudioUri(trackKey: String): Uri? {
        ensureIndexed()
        val cleanKey = trackKey.uppercase()
        val fileEntry = entries[cleanKey]
        if (fileEntry != null && !fileEntry.isWaveBank && fileEntry.numBlocks > CHUNK_THRESHOLD_BLOCKS) {
            return materializeAudioChunkUri(cleanKey, 0, CHUNK_BLOCKS)
        }
        return materializeInternal(cleanKey, 0, Int.MAX_VALUE)
    }

    fun materializeFullAudioUri(trackKey: String): Uri? {
        ensureIndexed()
        val cleanKey = trackKey.uppercase()
        return materializeInternal(cleanKey, 0, Int.MAX_VALUE)
    }

    fun materializeAudioChunkUri(trackKey: String, startBlock: Int, blockCount: Int = CHUNK_BLOCKS): Uri? {
        ensureIndexed()
        val cleanKey = trackKey.uppercase()
        val uri = materializeInternal(cleanKey, startBlock, blockCount)
        val fileEntry = entries[cleanKey]
        if (fileEntry != null && !fileEntry.isWaveBank) {
            val nextStart = startBlock + blockCount
            if (nextStart < fileEntry.numBlocks) {
                prefetchExecutor.submit {
                    try {
                        materializeInternal(cleanKey, nextStart, blockCount)
                    } catch (_: Throwable) {}
                }
            }
        }
        return uri
    }

    private fun materializeInternal(cleanKey: String, startBlock: Int, blockCount: Int): Uri? = synchronized(decodeLock) {
        val cacheDir = getCacheDir()
        val chunkSuffix = if (blockCount < Int.MAX_VALUE) "_blk${startBlock}_${blockCount}" else ""
        val safeFileName = "${rpfName.removeSuffix(".rpf")}_${cleanKey.replace(Regex("[^A-Z0-9_]"), "_")}$chunkSuffix.wav"
        val outFile = File(cacheDir, safeFileName)
        val now = System.currentTimeMillis()
        if (outFile.exists() && outFile.length() > 44L) {
            outFile.setLastModified(now)
            GTRadioLogger.logVerbose("DECODE") { "RPF3 cache HIT: $safeFileName" }
            return Uri.fromFile(outFile)
        }

        val decodeStart = System.currentTimeMillis()

        // Keep decoded audio cache under 120 MB (total app storage ~140-160 MB) and never delete active/recent files
        try {
            val maxCacheBytes = 120L * 1024L * 1024L
            val allCached = ArrayList<File>()
            cacheDir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allCached.addAll(it) }

            var totalBytes = 0L
            for (f in allCached) totalBytes += f.length()

            if (totalBytes > maxCacheBytes) {
                var evictedFiles = 0
                var evictedBytes = 0L
                allCached.sortBy { it.lastModified() }
                val targetBytes = 80L * 1024L * 1024L
                for (f in allCached) {
                    if (totalBytes <= targetBytes) break
                    val ageMs = now - f.lastModified()
                    if (f.name != safeFileName && !isPinned(f.name) && ageMs > 25_000L) {
                        val len = f.length()
                        if (f.delete()) {
                            totalBytes -= len
                            evictedBytes += len
                            evictedFiles++
                        }
                    }
                }
                if (evictedFiles > 0) {
                    GTRadioLogger.logVerbose("DECODE") {
                        "Cache eviction (RPF3): pruned $evictedFiles file(s), ${evictedBytes / 1024} KB freed"
                    }
                }
            }
        } catch (_: Exception) {}

        val subTrack = bankSubTracks[cleanKey]
        val fileEntry = entries[cleanKey]
        if (subTrack == null && fileEntry == null) return null

        val tempFile = File(cacheDir, "${safeFileName}_${Thread.currentThread().id}.tmp")
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (subTrack != null) {
                        decodeBankSubTrackToWav(ch, subTrack, tempFile)
                    } else if (fileEntry != null) {
                        if (fileEntry.isWaveBank) {
                            val firstSub = bankSubTracks.values.firstOrNull { it.parentEntryName == fileEntry.name }
                            if (firstSub != null) decodeBankSubTrackToWav(ch, firstSub, tempFile)
                        } else {
                            decodeMultiCToWav(ch, fileEntry, tempFile, startBlock, blockCount)
                        }
                    }
                }
            }
            if (tempFile.exists() && tempFile.length() > 44L) {
                tempFile.renameTo(outFile)
                val decodeMs = System.currentTimeMillis() - decodeStart
                GTRadioLogger.logVerbose("DECODE") {
                    "RPF3 cache MISS: decoded $safeFileName in ${decodeMs}ms (WAV, ${outFile.length() / 1024} KB)"
                }
                return Uri.fromFile(outFile)
            }
        } catch (t: Throwable) {
            GTRadioLogger.log("Rpf3Archive[$rpfName]: Failed decoding $cleanKey (start=$startBlock)", Exception(t))
            tempFile.delete()
            outFile.delete()
        }
        return null
    }

    private fun decodeBankSubTrackToWav(ch: FileChannel, sub: Rpf3BankSubTrack, outFile: File) {
        val totalSamples = sub.numSamples16Bit
        val pcmBytesLen = totalSamples * 2
        val byteCountToRead = if (sub.isCompressed) (totalSamples + 1) / 2 else pcmBytesLen

        val inBuf = ByteArray(byteCountToRead)
        readFully(ch, ByteBuffer.wrap(inBuf), sub.dataOffsetInRpf)

        BufferedOutputStream(FileOutputStream(outFile), 262144).use { out ->
            writeWavHeader(out, pcmBytesLen, sub.sampleRate, 1)
            if (!sub.isCompressed) {
                out.write(inBuf)
            } else {
                val stateArr = intArrayOf(0, 0, 0, 0)
                val pcmBytes = com.sc.gtradio.media.core.Gen3NativeTurbo.nativeDecodeRpf3ImaAdpcmToPcm16(
                    inBuf, 0, 0, inBuf.size, totalSamples, 1, stateArr
                )
                if (pcmBytes.isNotEmpty()) {
                    out.write(pcmBytes)
                } else {
                    val state = AdpcmState()
                    val outChunk = ByteArray(8192)
                    var outPos = 0
                    var samplesDecoded = 0
                    for (b in inBuf) {
                        val v = b.toInt() and 0xFF
                        if (samplesDecoded < totalSamples) {
                            val s1 = decodeNibble(v and 0x0F, state).toInt()
                            outChunk[outPos++] = (s1 and 0xFF).toByte()
                            outChunk[outPos++] = ((s1 shr 8) and 0xFF).toByte()
                            samplesDecoded++
                        }
                        if (samplesDecoded < totalSamples) {
                            val s2 = decodeNibble((v shr 4) and 0x0F, state).toInt()
                            outChunk[outPos++] = (s2 and 0xFF).toByte()
                            outChunk[outPos++] = ((s2 shr 8) and 0xFF).toByte()
                            samplesDecoded++
                        }
                        if (outPos >= outChunk.size - 4) {
                            out.write(outChunk, 0, outPos)
                            outPos = 0
                        }
                    }
                    if (outPos > 0) out.write(outChunk, 0, outPos)
                }
            }
        }
    }

    private fun readHeaderAdpcmState(
        fullHdr: ByteBuffer,
        chTableOffset: Int,
        numChannels: Int,
        channelIdx: Int,
        chunkIndex: Int
    ): AdpcmState? {
        if (chunkIndex <= 0 || channelIdx >= numChannels) return AdpcmState(0, 0)
        val wihPos = chTableOffset + channelIdx * 16
        if (wihPos + 16 > fullHdr.limit()) return null
        val wihOffset = fullHdr.getLong(wihPos).toInt()
        val wihSize = fullHdr.getInt(wihPos + 12)
        val baseAfterWih = chTableOffset + numChannels * 16
        val wiStart = baseAfterWih + wihOffset
        if (wiStart < 0 || wihSize <= 56 || wiStart + 56 > fullHdr.limit()) return null

        val offsetToStates = fullHdr.getLong(wiStart + 32).toInt().let { if (it in 32..128) it else 56 }
        val numStates = fullHdr.getInt(wiStart + 52)
        if (numStates <= 0) return null

        val stateIdx = (chunkIndex - 1).coerceIn(0, numStates - 1)
        val statePos = wiStart + offsetToStates + stateIdx * 3
        if (statePos + 3 > fullHdr.limit()) return null

        val valprev = fullHdr.getShort(statePos).toInt()
        val index = (fullHdr.get(statePos + 2).toInt() and 0xFF).coerceIn(0, 88)
        return AdpcmState(valprev, index)
    }

    private fun decodeMultiCToWav(
        ch: FileChannel,
        entry: Rpf3Entry,
        outFile: File,
        startBlock: Int = 0,
        maxBlocks: Int = Int.MAX_VALUE
    ) {
        val probeBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        if (readFully(ch, probeBuf, entry.offset) < 64) return

        val numBlocks = probeBuf.getInt(8)
        val blockSize = probeBuf.getInt(12)
        val rawChOff20 = probeBuf.getInt(20)
        val rawChOff16 = probeBuf.getInt(16)
        val chTableOffset = if (rawChOff20 in 16..256) rawChOff20 else if (rawChOff16 in 16..256) rawChOff16 else 48
        val numChannels = probeBuf.getInt(36)
        val headerSize = probeBuf.getInt(44)

        if (numBlocks <= 0 || blockSize <= 2048 || numChannels <= 0 || headerSize <= 0) return

        val hdrReadSize = headerSize.coerceIn(128, 524288)
        val hdrBuf = ByteBuffer.allocate(hdrReadSize).order(ByteOrder.LITTLE_ENDIAN)
        if (readFully(ch, hdrBuf, entry.offset) < 128) return

        val detailOffset = chTableOffset + (numChannels * 16)
        var sampleRate = if (detailOffset + 28 <= hdrReadSize) (hdrBuf.getShort(detailOffset + 24).toInt() and 0xFFFF) else 32000
        if (sampleRate !in 16000..48000) sampleRate = 32000

        val firstBlock = startBlock.coerceIn(0, numBlocks - 1)
        val endBlockExclusive = if (maxBlocks == Int.MAX_VALUE) numBlocks else minOf(numBlocks, firstBlock + maxBlocks)
        if (firstBlock >= endBlockExclusive) return

        val outChannels = if (numChannels >= 2) 2 else 1
        val blockHeaderBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        var computedTotalSamples = 0
        var firstBlockStartSample = 0
        for (b in firstBlock until endBlockExclusive) {
            val blockStart = entry.offset + headerSize.toLong() + (b.toLong() * blockSize.toLong())
            if (blockStart + 2048L > entry.offset + entry.size) break
            blockHeaderBuf.clear()
            readFully(ch, blockHeaderBuf, blockStart)
            val tblOff = blockHeaderBuf.getLong(0).toInt().coerceIn(16, 64)
            val samplesInBlk = blockHeaderBuf.getInt(tblOff + 12)
            if (b == firstBlock) {
                val sampleTblOff = tblOff + outChannels * 16
                if (sampleTblOff + 4 <= 128) {
                    firstBlockStartSample = blockHeaderBuf.getInt(sampleTblOff).coerceAtLeast(0)
                }
            }
            if (samplesInBlk > 0) computedTotalSamples += samplesInBlk
        }

        if (computedTotalSamples <= 0) return

        val totalPcmBytes = computedTotalSamples * outChannels * 2
        val prevState = chunkStates["${entry.name}_$firstBlock"]
        val startChunkIdx = if (firstBlockStartSample > 0) (firstBlockStartSample / 4096) else (firstBlock * (if (outChannels == 2) 123 else 247))
        val leftState = if (prevState != null) {
            AdpcmState(prevState[0], prevState[1])
        } else {
            readHeaderAdpcmState(hdrBuf, chTableOffset, numChannels, 0, startChunkIdx) ?: AdpcmState()
        }
        val rightState = if (prevState != null) {
            AdpcmState(prevState[2], prevState[3])
        } else {
            readHeaderAdpcmState(hdrBuf, chTableOffset, numChannels, 1, startChunkIdx) ?: AdpcmState()
        }

        val blockBytes = ByteArray(blockSize)
        val stateArray = intArrayOf(
            leftState.valprev, leftState.index, rightState.valprev, rightState.index
        )

        BufferedOutputStream(FileOutputStream(outFile), 262144).use { out ->
            writeWavHeader(out, totalPcmBytes, sampleRate, outChannels)

            for (b in firstBlock until endBlockExclusive) {
                val blockStart = entry.offset + headerSize.toLong() + (b.toLong() * blockSize.toLong())
                if (blockStart + blockSize.toLong() > entry.offset + entry.size) break

                readFully(ch, ByteBuffer.wrap(blockBytes), blockStart)
                val bb = ByteBuffer.wrap(blockBytes, 0, 128).order(ByteOrder.LITTLE_ENDIAN)
                val tblOff = bb.getLong(0).toInt().coerceIn(16, 64)

                val ch0StartChunk = bb.getInt(tblOff)
                val ch0Samples = bb.getInt(tblOff + 12)
                if (ch0Samples <= 0) continue

                val ch0ByteOff = 2048 + (ch0StartChunk * 2048)
                val ch1StartChunk = if (outChannels == 2) bb.getInt(tblOff + 16) else 0
                val ch1ByteOff = 2048 + (ch1StartChunk * 2048)

                val numBytesPerCh = (ch0Samples + 1) / 2
                if (ch0ByteOff + numBytesPerCh > blockSize) continue
                if (outChannels == 2 && ch1ByteOff + numBytesPerCh > blockSize) continue

                val blockPcm = com.sc.gtradio.media.core.Gen3NativeTurbo.nativeDecodeRpf3ImaAdpcmToPcm16(
                    blockBytes, ch0ByteOff, ch1ByteOff, numBytesPerCh, ch0Samples, outChannels, stateArray
                )
                if (blockPcm.isNotEmpty()) {
                    out.write(blockPcm)
                }
            }
        }
        leftState.valprev = stateArray[0]
        leftState.index = stateArray[1]
        rightState.valprev = stateArray[2]
        rightState.index = stateArray[3]

        if (endBlockExclusive < numBlocks) {
            chunkStates["${entry.name}_$endBlockExclusive"] = intArrayOf(
                leftState.valprev, leftState.index, rightState.valprev, rightState.index
            )
        }
    }

    private fun writeWavHeader(out: BufferedOutputStream, pcmDataBytes: Int, sampleRate: Int, channels: Int) {
        out.write(buildWavHeaderBytes(pcmDataBytes.toLong() and 0xFFFFFFFFL, sampleRate, channels))
    }
}
