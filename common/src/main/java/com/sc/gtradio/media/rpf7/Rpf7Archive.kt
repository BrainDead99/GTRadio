package com.sc.gtradio.media.rpf7
import com.sc.gtradio.media.rpf3.Rpf3Archive
import com.sc.gtradio.media.core.DjMarkerWindow


import android.content.Context
import android.net.Uri
import com.sc.gtradio.common.GTRadioLogger
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

/**
 * Precomputed block geometry and virtual WAV layout for zero-cache streaming of an RPF7 MultiChannel entry.
 */
data class Rpf7StreamIndex(
    val entryName: String,
    val numBlocks: Int,
    val blockSize: Int,
    val channels: Int,
    val sampleRate: Int,
    val codec: Int,
    val totalSamples: Long,
    val dataChunkOffset: Long,
    val dataChunkSize: Long,
    val seekTable: LongArray,
    val blockSamples: LongArray,
    val blockPcmBytes: LongArray,
    val blockStartWavByte: LongArray,
    val totalPcmBytes: Long,
    val totalVirtualWavBytes: Long,
    val durationMs: Long
)

/**
 * Thread-safe registry mapping active [Rpf7Archive.archiveStreamId] keys to their archives
 * so [Rpf7StreamingDataSource] can resolve `gtradio-rpf7://` URIs without Context allocations.
 */
object Rpf7StreamRegistry {
    private val archives = ConcurrentHashMap<String, Rpf7Archive>()

    fun register(archiveId: String, archive: Rpf7Archive) {
        archives[archiveId] = archive
    }

    fun get(archiveId: String): Rpf7Archive? = archives[archiveId]
}

/**
 * Metadata record for a continuous-mix sample-accurate trackid marker inside an AWC chunk 0xBD.
 */
data class Rpf7TrackMarker(
    val sampleOffset: Long,
    val trackId: Int
)

/**
 * Metadata record for a top-level .awc binary entry within an RPF7 archive.
 */
data class Rpf7Entry(
    val name: String,
    val offset: Long,
    val size: Long,
    val isEncrypted: Boolean,
    val isCompressed: Boolean,
    val isMultiChannel: Boolean,
    val numBlocks: Int,
    val sampleRate: Int,
    val channels: Int,
    val totalSamples: Int,
    val dataChunkOffset: Long = 0L,
    val dataChunkSize: Long = 0L,
    val blockSize: Int = 0,
    val isMultiChannelEncrypt: Boolean = false,
    val isWholeFileEncrypted: Boolean = false,
    val codec: Int = 4
)

/**
 * Metadata record for an individual sub-stream inside a single-channel voice bank (e.g. general.awc).
 */
data class Rpf7BankSubTrack(
    val parentEntryName: String,
    val subIndex: Int,
    val streamId: Int,
    val subKey: String,
    val dataOffsetInRpf: Long,
    val dataSize: Int,
    val numSamples: Int,
    val sampleRate: Int,
    val codec: Int,
    val isEncrypted: Boolean
)

/**
 * Reader and on-demand audio decoder for GTA V Legacy PC RPF7 archives containing AWC audio.
 * Supports:
 * - Lazy indexing via FileChannel through SAF contentResolver
 * - RPF7 TOC NG decryption with automatic key index resolution
 * - Fast header parsing of multi-channel streams and single-channel voice banks
 * - 4-bit IMA ADPCM and 16-bit PCM decoding with RSXXTEA block decryption
 * - Chunked block streaming for low latency on mobile (CHUNK_BLOCKS = 4)
 * - Single-threaded background prefetching
 * - Safe 100 MB LRU cache eviction in context.cacheDir/rpf7_tracks
 */
class Rpf7Archive(
    private val context: Context,
    val rpfName: String,
    val sourceUri: Uri
) {

    private val entries = LinkedHashMap<String, Rpf7Entry>()
    private val bankSubTracks = LinkedHashMap<String, Rpf7BankSubTrack>()
    private val trackMarkersCache = ConcurrentHashMap<String, List<Rpf7TrackMarker>>()
    private val djMarkerCache = ConcurrentHashMap<String, DjMarkerWindow>()
    private val seekTableCache = ConcurrentHashMap<String, LongArray>()
    private val streamIndexCache = ConcurrentHashMap<String, Rpf7StreamIndex>()
    val archiveStreamId: String = "rpf7_${nextArchiveId.incrementAndGet()}_" +
        rpfName.removeSuffix(".rpf").replace(Regex("[^A-Za-z0-9_]"), "_").lowercase(Locale.ROOT)
    @Volatile private var indexed = false
    val isIndexed: Boolean get() = indexed

    init {
        Rpf7StreamRegistry.register(archiveStreamId, this)
        Rpf7StreamingDataSource.ensureRegistered()
    }

    companion object {
        private val nextArchiveId = AtomicLong(0L)
        const val CHUNK_BLOCKS = 4
        const val CHUNK_THRESHOLD_BLOCKS = 16
        private val NO_DJ_MARKERS = DjMarkerWindow()

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
            header[20] = 1; header[21] = 0 // 1 = PCM
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
            header[34] = 16; header[35] = 0 // 16-bit
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (pcmDataBytes and 0xFFL).toByte()
            header[41] = ((pcmDataBytes ushr 8) and 0xFFL).toByte()
            header[42] = ((pcmDataBytes ushr 16) and 0xFFL).toByte()
            header[43] = ((pcmDataBytes ushr 24) and 0xFFL).toByte()
            return header
        }

        private const val MAX_CACHE_BYTES = 150L * 1024L * 1024L // 150 MB cap
        private const val TARGET_CACHE_BYTES = 100L * 1024L * 1024L // Evict down to 100 MB

        private val pinnedFileCounts = ConcurrentHashMap<String, AtomicInteger>()

        fun pinFile(fileNameOrUri: String?) {
            val name = fileNameOrUri?.substringAfterLast('/') ?: return
            if (name.isEmpty()) return
            pinnedFileCounts.compute(name) { _, existing ->
                (existing ?: AtomicInteger(0)).apply { incrementAndGet() }
            }
        }

        fun unpinFile(fileNameOrUri: String?) {
            val name = fileNameOrUri?.substringAfterLast('/') ?: return
            if (name.isEmpty()) return
            pinnedFileCounts.computeIfPresent(name) { _, counter ->
                if (counter.decrementAndGet() <= 0) null else counter
            }
        }

        fun clearPinnedFiles() {
            pinnedFileCounts.clear()
        }

        fun isPinned(fileName: String?): Boolean =
            if (fileName != null) (pinnedFileCounts[fileName]?.get() ?: 0) > 0 else false

        private val prefetchExecutor = Executors.newSingleThreadExecutor()
        private val decodeLock = Any()

        private val IMA_INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )

        private val IMA_STEP_TABLE = intArrayOf(
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

        private fun clip(value: Int, min: Int, max: Int): Int {
            if (value < min) return min
            if (value > max) return max
            return value
        }

        /**
         * Decodes GTA V 4-bit IMA ADPCM data.
         * In GTA V, each 2048-byte sub-block starts with a 4-byte header:
         * byte 0: stepIndex, byte 1: unused, bytes 2..3: initial predictor (16-bit signed).
         * followed by 2044 bytes of ADPCM nibbles (low nibble first).
         */
        fun decodeAdpcm(
            data: ByteArray,
            offset: Int,
            length: Int,
            sampleCount: Int,
            outPcm: ShortArray,
            outOffset: Int
        ): Int {
            var predictor = 0
            var stepIndex = 0
            var readingOffset = offset
            val endOffset = offset + length
            var writingOffset = outOffset
            var samplesLeft = sampleCount
            var bytesInBlock = 0

            while (readingOffset < endOffset && samplesLeft > 0) {
                if (bytesInBlock == 0) {
                    if (readingOffset + 4 > endOffset) break
                    stepIndex = clip(data[readingOffset].toInt() and 0xFF, 0, 88)
                    val p0 = data[readingOffset + 2].toInt() and 0xFF
                    val p1 = data[readingOffset + 3].toInt()
                    predictor = (p0 or (p1 shl 8)).toShort().toInt()
                    bytesInBlock = 2044
                    readingOffset += 4
                } else {
                    val b = data[readingOffset].toInt() and 0xFF

                    // Low nibble
                    val nibble0 = b and 0x0F
                    var step = IMA_STEP_TABLE[stepIndex]
                    var diff = ((((nibble0 and 7) shl 1) + 1) * step) shr 3
                    if ((nibble0 and 8) != 0) diff = -diff
                    predictor += diff
                    stepIndex = clip(stepIndex + IMA_INDEX_TABLE[nibble0], 0, 88)
                    if (writingOffset < outPcm.size) {
                        outPcm[writingOffset++] = clip(predictor, -32768, 32767).toShort()
                    }
                    samplesLeft--

                    // High nibble
                    if (samplesLeft > 0) {
                        val nibble1 = (b shr 4) and 0x0F
                        step = IMA_STEP_TABLE[stepIndex]
                        diff = ((((nibble1 and 7) shl 1) + 1) * step) shr 3
                        if ((nibble1 and 8) != 0) diff = -diff
                        predictor += diff
                        stepIndex = clip(stepIndex + IMA_INDEX_TABLE[nibble1], 0, 88)
                        if (writingOffset < outPcm.size) {
                            outPcm[writingOffset++] = clip(predictor, -32768, 32767).toShort()
                        }
                        samplesLeft--
                    }

                    bytesInBlock--
                    readingOffset++
                }
            }
            return writingOffset - outOffset
        }
    }

    // ------------------------------------------------------------------------
    // Indexing
    // ------------------------------------------------------------------------

    @Synchronized
    fun ensureIndexed() {
        if (indexed) return

        val keys = Rpf7KeyManager.getKeys(context)
        if (keys == null) {
            GTRadioLogger.log("Rpf7Archive[$rpfName]: Keys not unlocked. Indexing skipped.")
            return
        }

        try {
            entries.clear()
            bankSubTracks.clear()
            var ok = false
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    ok = indexRpf(channel, keys)
                }
            }
            if (ok) {
                indexed = true
                GTRadioLogger.log("Rpf7Archive[$rpfName]: Indexed ${entries.size} entries and ${bankSubTracks.size} bank sub-tracks")
            } else {
                entries.clear()
                bankSubTracks.clear()
                GTRadioLogger.log("Rpf7Archive[$rpfName]: Indexing incomplete; deferred for retry")
            }
        } catch (e: Exception) {
            entries.clear()
            bankSubTracks.clear()
            GTRadioLogger.log("Rpf7Archive[$rpfName]: Indexing failed", e)
        }
    }

    private fun indexRpf(channel: FileChannel, keys: Rpf7KeySet): Boolean {
        val fileSize = channel.size()
        if (fileSize < 16) return false

        val hdrBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        channel.read(hdrBuf)
        hdrBuf.flip()

        val magic = hdrBuf.int
        if (magic != 0x52504637) { // "RPF7"
            GTRadioLogger.log("Rpf7Archive[$rpfName]: Invalid RPF7 magic: 0x${Integer.toHexString(magic)}")
            return false
        }

        val entryCount = hdrBuf.int
        val namesLength = hdrBuf.int
        val enc = hdrBuf.int

        if (entryCount <= 0 || namesLength <= 0) return false

        val entriesEnc = ByteArray(entryCount * 16)
        val namesEnc = ByteArray(namesLength)

        val bufEntries = ByteBuffer.wrap(entriesEnc)
        channel.position(16)
        channel.read(bufEntries)

        val bufNames = ByteBuffer.wrap(namesEnc)
        channel.position(16L + entryCount * 16L)
        channel.read(bufNames)

        // Resolve valid NG key index
        val keyIndex = Rpf7Crypto.findValidRpf7TocKeyIndex(entriesEnc, rpfName, fileSize, keys)
        val decEntries = Rpf7Crypto.decryptNGWithKeyIndex(entriesEnc, keyIndex, keys)
        val decNames = Rpf7Crypto.decryptNGWithKeyIndex(namesEnc, keyIndex, keys)

        for (i in 0 until entryCount) {
            val base = i * 16
            val y = readIntLe(decEntries, base)
            val x = readIntLe(decEntries, base + 4)

            if (x == 0x7FFFFF00) {
                // Directory entry
                continue
            }

            if ((x and 0x80000000.toInt()) == 0) {
                // Binary file entry
                val bufQ = readLongLe(decEntries, base)
                val nameOff = (bufQ and 0xFFFFL).toInt()
                val fSize = ((bufQ ushr 16) and 0xFFFFFFL)
                val fOffset = (((bufQ ushr 40) and 0xFFFFFFL) * 512L)

                val uncompressed = readIntLe(decEntries, base + 8).toLong() and 0xFFFFFFFFL
                val encType = readIntLe(decEntries, base + 12)

                val isEncrypted = (encType == 1)
                val isCompressed = (fSize > 0)
                val actualSize = if (isCompressed) fSize else uncompressed

                // Resolve filename
                var nEnd = nameOff
                while (nEnd < decNames.size && decNames[nEnd] != 0.toByte()) {
                    nEnd++
                }
                val name = String(decNames, nameOff, nEnd - nameOff, Charsets.ISO_8859_1)

                if (name.lowercase(Locale.ROOT).endsWith(".awc")) {
                    try {
                        indexAwcEntry(channel, name, fOffset, actualSize, isEncrypted, isCompressed, keys)
                    } catch (e: Exception) {
                        GTRadioLogger.log("Rpf7Archive[$rpfName]: Failed indexing AWC $name", e)
                    }
                }
            }
        }
        return true
    }

    private fun indexAwcEntry(
        channel: FileChannel,
        entryName: String,
        rpfOffset: Long,
        rpfSize: Long,
        isEncrypted: Boolean,
        isCompressed: Boolean,
        keys: Rpf7KeySet
    ) {
        // Read header prefix (16384 bytes covers header, stream infos, and chunk table)
        val hdrLen = Math.min(rpfSize, 16384L).toInt()
        val rawHdr = ByteArray(hdrLen)
        val buf = ByteBuffer.wrap(rawHdr)
        channel.position(rpfOffset)
        channel.read(buf)

        // Decrypt AWC whole-file encryption if needed
        var wholeFileEncrypted = false
        val magicLe = readIntLe(rawHdr, 0)
        if (magicLe != 0x54414441 && magicLe != 0x41444154) { // Not "ADAT"
            Rpf7Crypto.decryptRsxxtea(rawHdr, 0, rawHdr.size, keys.awcKey)
            val checkMagic = readIntLe(rawHdr, 0)
            if (checkMagic == 0x54414441 || checkMagic == 0x41444154) {
                wholeFileEncrypted = true
            } else {
                return
            }
        }

        val version = readShortLe(rawHdr, 4).toInt() and 0xFFFF
        val flags = readShortLe(rawHdr, 6).toInt() and 0xFFFF
        val streamCount = readIntLe(rawHdr, 8)
        val dataOffset = readIntLe(rawHdr, 12)

        val chunkIndicesFlag = (flags and 1) != 0
        val singleChannelEncrypt = (flags and 2) != 0
        val isMultiChannel = (flags and 4) != 0
        val multiChannelEncrypt = (flags and 8) != 0

        var pos = 16
        if (chunkIndicesFlag) {
            pos += streamCount * 2
        }

        data class TempStreamInfo(val id: Int, val chunkCount: Int)
        data class TempChunkInfo(val type: Int, val size: Int, val offset: Int)

        val streams = ArrayList<TempStreamInfo>()
        for (s in 0 until streamCount) {
            if (pos + 4 > rawHdr.size) break
            val rawVal = readIntLe(rawHdr, pos)
            pos += 4
            val chunks = (rawVal ushr 29) and 0x7
            val id = rawVal and 0x1FFFFFFF
            streams.add(TempStreamInfo(id, chunks))
        }

        val allChunksByStream = ArrayList<List<TempChunkInfo>>()
        for (s in streams) {
            val cList = ArrayList<TempChunkInfo>()
            for (c in 0 until s.chunkCount) {
                if (pos + 8 > rawHdr.size) break
                val rawChunk = readLongLe(rawHdr, pos)
                pos += 8
                val cType = ((rawChunk ushr 56) and 0xFFL).toInt()
                val cSize = ((rawChunk ushr 28) and 0x0FFFFFFFL).toInt()
                val cOffset = (rawChunk and 0x0FFFFFFFL).toInt()
                cList.add(TempChunkInfo(cType, cSize, cOffset))
            }
            allChunksByStream.add(cList)
        }

        if (isMultiChannel) {
            // Find StreamFormatChunk (0x48) and DataChunk (0x55)
            var blkCount = 1
            var blkSize = 524288
            var chCount = 2
            var chSampleRate = 48000
            var chSamples = 0
            var chCodec = 4
            var dataChunkOff = dataOffset.toLong()
            var dataChunkSize = rpfSize - dataOffset

            for (sIdx in 0 until streams.size) {
                val chunks = allChunksByStream.getOrNull(sIdx) ?: continue
                for (chunk in chunks) {
                    if (chunk.type == 0x48) { // StreamFormat chunk
                        val readLen = Math.min(chunk.size, 128)
                        val sfBuf = if (wholeFileEncrypted && chunk.offset + readLen <= rawHdr.size) {
                            ByteBuffer.wrap(rawHdr, chunk.offset, readLen).order(ByteOrder.LITTLE_ENDIAN)
                        } else {
                            val b = ByteBuffer.allocate(readLen).order(ByteOrder.LITTLE_ENDIAN)
                            channel.position(rpfOffset + chunk.offset)
                            channel.read(b)
                            b.flip()
                            b
                        }
                        if (sfBuf.remaining() >= 12) {
                            blkCount = sfBuf.int
                            blkSize = sfBuf.int
                            chCount = sfBuf.int
                            if (sfBuf.remaining() >= 16) {
                                sfBuf.int // id
                                chSamples = sfBuf.int
                                sfBuf.short // headroom
                                chSampleRate = sfBuf.short.toInt() and 0xFFFF
                                chCodec = sfBuf.get().toInt() and 0xFF
                            }
                        }
                    } else if (chunk.type == 0x55) { // Data chunk
                        dataChunkOff = chunk.offset.toLong()
                        dataChunkSize = chunk.size.toLong()
                    }
                }
            }

            val entry = Rpf7Entry(
                name = entryName,
                offset = rpfOffset,
                size = rpfSize,
                isEncrypted = isEncrypted,
                isCompressed = isCompressed,
                isMultiChannel = true,
                numBlocks = blkCount,
                sampleRate = chSampleRate,
                channels = chCount,
                totalSamples = chSamples,
                dataChunkOffset = dataChunkOff,
                dataChunkSize = dataChunkSize,
                blockSize = blkSize,
                isMultiChannelEncrypt = multiChannelEncrypt,
                isWholeFileEncrypted = wholeFileEncrypted,
                codec = chCodec
            )
            entries[entryName] = entry
        } else {
            // Single-channel voice bank (e.g. general.awc, intro.awc, to.awc)
            var bankCodec = 4
            val baseName = entryName.removeSuffix(".awc")
            for (sIdx in 0 until streams.size) {
                val sInfo = streams[sIdx]
                val chunks = allChunksByStream.getOrNull(sIdx) ?: continue
                val fmtChunk = chunks.firstOrNull { it.type == 0xFA }
                val datChunk = chunks.firstOrNull { it.type == 0x55 }

                if (fmtChunk != null && datChunk != null) {
                    val fmtBuf = ByteBuffer.allocate(Math.min(fmtChunk.size, 32)).order(ByteOrder.LITTLE_ENDIAN)
                    channel.position(rpfOffset + fmtChunk.offset)
                    channel.read(fmtBuf)
                    fmtBuf.flip()

                    if (fmtBuf.remaining() >= 20) {
                        val numSamples = fmtBuf.int
                        fmtBuf.int // loopPoint
                        val sRate = fmtBuf.short.toInt() and 0xFFFF
                        fmtBuf.short // headroom
                        fmtBuf.short // loopBegin
                        fmtBuf.short // loopEnd
                        fmtBuf.short // playEnd
                        fmtBuf.get() // playBegin
                        val codec = fmtBuf.get().toInt() and 0xFF
                        bankCodec = codec

                        val subKey = "${baseName}_sub$sIdx"
                        val hexKey = "${baseName}_0x${Integer.toHexString(sInfo.id).padStart(8, '0')}"

                        val subTrack = Rpf7BankSubTrack(
                            parentEntryName = entryName,
                            subIndex = sIdx,
                            streamId = sInfo.id,
                            subKey = subKey,
                            dataOffsetInRpf = rpfOffset + datChunk.offset,
                            dataSize = datChunk.size,
                            numSamples = numSamples,
                            sampleRate = sRate,
                            codec = codec,
                            isEncrypted = singleChannelEncrypt
                        )
                        bankSubTracks[subKey] = subTrack
                        bankSubTracks[hexKey] = subTrack
                    }
                }
            }

            val parentEntry = Rpf7Entry(
                name = entryName,
                offset = rpfOffset,
                size = rpfSize,
                isEncrypted = isEncrypted,
                isCompressed = isCompressed,
                isMultiChannel = false,
                numBlocks = 1,
                sampleRate = 48000,
                channels = 1,
                totalSamples = 0,
                isWholeFileEncrypted = wholeFileEncrypted,
                codec = bankCodec
            )
            entries[entryName] = parentEntry
        }
    }

    // ------------------------------------------------------------------------
    // Public Accessors
    // ------------------------------------------------------------------------

    fun getFileEntries(): Collection<Rpf7Entry> {
        ensureIndexed()
        return entries.values
    }

    fun getBankSubTracks(): Collection<Rpf7BankSubTrack> {
        ensureIndexed()
        return bankSubTracks.values
    }

    fun getBankSubTracksFor(parentName: String): List<Rpf7BankSubTrack> {
        ensureIndexed()
        val cleanParent = if (parentName.endsWith(".awc")) parentName else "$parentName.awc"
        return bankSubTracks.values.filter { it.parentEntryName.equals(cleanParent, ignoreCase = true) }
    }

    fun getEntry(name: String): Rpf7Entry? {
        ensureIndexed()
        val cleanName = if (name.endsWith(".awc")) name else "$name.awc"
        return entries[cleanName] ?: entries[name]
    }

    fun getEntryBlockCount(name: String): Int {
        return getEntry(name)?.numBlocks ?: 1
    }

    fun hasEntry(name: String): Boolean {
        ensureIndexed()
        val cleanName = if (name.endsWith(".awc")) name else "$name.awc"
        return entries.containsKey(cleanName) || entries.containsKey(name) || bankSubTracks.containsKey(name)
    }

    fun isEnhancedMp3Archive(): Boolean {
        ensureIndexed()
        return entries.values.any { it.codec == 7 } || bankSubTracks.values.any { it.codec == 7 }
    }

    fun getChunkBlocksFor(entryName: String): Int {
        val entry = getEntry(entryName)
        return if (entry?.codec == 7) 4 else CHUNK_BLOCKS
    }

    fun getChunkThresholdFor(entryName: String): Int {
        val entry = getEntry(entryName)
        return if (entry?.codec == 7) 16 else CHUNK_THRESHOLD_BLOCKS
    }

    fun getEntryTotalSamples(entryName: String): Long {
        ensureIndexed()
        val cleanName = if (entryName.endsWith(".awc")) entryName else "$entryName.awc"
        val entry = getEntry(cleanName) ?: return 0L
        return entry.totalSamples.toLong()
    }

    fun getBlockForSample(entryName: String, sampleOffset: Long): Int {
        if (sampleOffset <= 0L) return 0
        ensureIndexed()
        val cleanName = if (entryName.endsWith(".awc")) entryName else "$entryName.awc"
        if (!seekTableCache.containsKey(cleanName)) {
            readTrackMarkers(cleanName)
        }
        val seekTbl = seekTableCache[cleanName]
        val entry = getEntry(cleanName)
        val numBlocks = entry?.numBlocks ?: 1
        if (seekTbl != null && seekTbl.isNotEmpty()) {
            var blk = 0
            for (i in 0 until minOf(seekTbl.size, numBlocks)) {
                if (seekTbl[i] <= sampleOffset) {
                    blk = i
                } else {
                    break
                }
            }
            return blk
        }
        if (entry != null && entry.totalSamples > 0 && numBlocks > 0) {
            return ((sampleOffset * numBlocks.toLong()) / entry.totalSamples.toLong()).toInt().coerceIn(0, numBlocks - 1)
        }
        return 0
    }

    fun getEntrySampleRate(entryName: String): Int {
        return getEntry(entryName)?.sampleRate ?: 48000
    }

    fun getChunkStartSample(entryName: String, startBlock: Int): Long {
        if (startBlock <= 0) return 0L
        ensureIndexed()
        val cleanName = if (entryName.endsWith(".awc")) entryName else "$entryName.awc"
        if (!seekTableCache.containsKey(cleanName)) {
            readTrackMarkers(cleanName)
        }
        val seekTbl = seekTableCache[cleanName]
        if (seekTbl != null && startBlock in seekTbl.indices) {
            return seekTbl[startBlock]
        }
        val entry = getEntry(cleanName) ?: return 0L
        if (entry.numBlocks > 0 && entry.totalSamples > 0) {
            return (startBlock.toLong() * entry.totalSamples.toLong()) / entry.numBlocks.toLong()
        }
        return 0L
    }

    fun readTrackMarkers(awcEntryName: String): List<Rpf7TrackMarker> {
        ensureIndexed()
        val cleanName = if (awcEntryName.endsWith(".awc")) awcEntryName else "$awcEntryName.awc"
        trackMarkersCache[cleanName]?.let { return it }

        val entry = getEntry(cleanName) ?: return emptyList()
        val markers = ArrayList<Rpf7TrackMarker>()

        try {
            val keys = Rpf7KeyManager.getKeys(context)
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val hdrLen = Math.min(entry.size, 65536L).toInt()
                    val rawHdr = ByteArray(hdrLen)
                    val buf = ByteBuffer.wrap(rawHdr)
                    channel.position(entry.offset)
                    channel.read(buf)

                    if (entry.isWholeFileEncrypted && keys != null) {
                        Rpf7Crypto.decryptRsxxtea(rawHdr, 0, rawHdr.size, keys.awcKey)
                    }

                    val magicLe = readIntLe(rawHdr, 0)
                    if (magicLe != 0x54414441 && magicLe != 0x41444154) {
                        return emptyList()
                    }

                    val flags = readShortLe(rawHdr, 6).toInt() and 0xFFFF
                    val streamCount = readIntLe(rawHdr, 8)
                    val chunkIndicesFlag = (flags and 1) != 0

                    var pos = 16
                    if (chunkIndicesFlag) {
                        pos += streamCount * 2
                    }

                    val streamChunkCounts = IntArray(streamCount)
                    for (s in 0 until streamCount) {
                        if (pos + 4 > rawHdr.size) break
                        val rawVal = readIntLe(rawHdr, pos)
                        pos += 4
                        streamChunkCounts[s] = (rawVal ushr 29) and 0x7
                    }

                    for (s in 0 until streamCount) {
                        val chunkCount = streamChunkCounts[s]
                        for (c in 0 until chunkCount) {
                            if (pos + 8 > rawHdr.size) break
                            val rawChunk = readLongLe(rawHdr, pos)
                            pos += 8
                            val cType = ((rawChunk ushr 56) and 0xFFL).toInt()
                            val cSize = ((rawChunk ushr 28) and 0x0FFFFFFFL).toInt()
                            val cOffset = (rawChunk and 0x0FFFFFFFL).toInt()

                            if (cType == 0xA3 && !seekTableCache.containsKey(cleanName) && cSize >= 4) {
                                val sBuf = ByteBuffer.allocate(cSize).order(ByteOrder.LITTLE_ENDIAN)
                                channel.position(entry.offset + cOffset)
                                if (channel.read(sBuf) >= cSize) {
                                    sBuf.flip()
                                    val count = cSize / 4
                                    val tbl = LongArray(count)
                                    for (i in 0 until count) {
                                        tbl[i] = sBuf.int.toLong() and 0xFFFFFFFFL
                                    }
                                    seekTableCache[cleanName] = tbl
                                }
                            } else if (cType == 0xBD && cSize >= 16) {
                                val mBuf = ByteBuffer.allocate(cSize).order(ByteOrder.LITTLE_ENDIAN)
                                channel.position(entry.offset + cOffset)
                                if (channel.read(mBuf) >= cSize) {
                                    mBuf.flip()
                                    val numRecs = cSize / 16
                                    var djIntroStart = -1L
                                    var djIntroEnd = -1L
                                    var djOutroStart = -1L
                                    var djOutroEnd = -1L
                                    val sampleRate = getEntrySampleRate(cleanName).toLong().coerceAtLeast(1L)
                                    for (r in 0 until numRecs) {
                                        val nameHash = mBuf.int
                                        val rawVal = mBuf.int
                                        val sampleOffUint = mBuf.int
                                        mBuf.int // padding

                                        if (nameHash == 0xA6D93246.toInt()) {
                                            val tid = java.lang.Float.intBitsToFloat(rawVal).toInt()
                                            val sOff = sampleOffUint.toLong() and 0xFFFFFFFFL
                                            markers.add(Rpf7TrackMarker(sOff, tid))
                                        } else if (nameHash == 0x08DBA0F8.toInt()) {
                                            val sOff = sampleOffUint.toLong() and 0xFFFFFFFFL
                                            val timeMs = (sOff * 1000L) / sampleRate
                                            when (rawVal) {
                                                0x449687BA.toInt() -> djIntroStart = timeMs
                                                0x2CC6E05E.toInt() -> djIntroEnd = timeMs
                                                0x7DCE9FA0.toInt() -> djOutroStart = timeMs
                                                0x71D8DCAD.toInt() -> djOutroEnd = timeMs
                                            }
                                        }
                                    }
                                    if (djIntroStart >= 0L || djIntroEnd > 0L || djOutroStart > 0L || djOutroEnd > 0L) {
                                        djMarkerCache[cleanName] = DjMarkerWindow(djIntroStart, djIntroEnd, djOutroStart, djOutroEnd)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf7Archive[$rpfName]: Error reading track markers for $awcEntryName", e)
        }

        markers.sortBy { it.sampleOffset }
        val deduped = ArrayList<Rpf7TrackMarker>()
        for (m in markers) {
            if (deduped.isEmpty() || deduped.last().trackId != m.trackId || deduped.last().sampleOffset != m.sampleOffset) {
                deduped.add(m)
            }
        }
        trackMarkersCache[cleanName] = deduped
        return deduped
    }

    fun readDjMarkers(awcEntryName: String): DjMarkerWindow? {
        val cleanName = awcEntryName.lowercase()
        djMarkerCache[cleanName]?.let { return if (it === NO_DJ_MARKERS) null else it }
        readTrackMarkers(cleanName)
        val cached = djMarkerCache[cleanName] ?: NO_DJ_MARKERS
        djMarkerCache[cleanName] = cached
        return if (cached === NO_DJ_MARKERS) null else cached
    }

    // ------------------------------------------------------------------------
    // Zero-Cache Streaming Index & Single-Block Decoder
    // ------------------------------------------------------------------------

    fun isMultiChannelEntry(trackKey: String): Boolean {
        return getEntry(trackKey.trim())?.isMultiChannel == true
    }

    fun getEntryDurationMs(trackKey: String): Long? {
        ensureIndexed()
        val cleanKey = trackKey.trim()
        val entry = getEntry(cleanKey)
        if (entry != null && entry.isMultiChannel) {
            return getOrBuildStreamIndex(entry.name)?.durationMs
        }
        val sub = bankSubTracks[cleanKey]
        if (sub != null && sub.sampleRate > 0 && sub.numSamples > 0) {
            return (sub.numSamples.toLong() * 1000L) / sub.sampleRate.toLong()
        }
        return null
    }

    fun buildStreamUri(entryName: String): Uri {
        Rpf7StreamRegistry.register(archiveStreamId, this)
        Rpf7StreamingDataSource.ensureRegistered()
        val entry = getEntry(entryName.trim())
        val targetName = entry?.name ?: entryName.trim().let {
            if (it.endsWith(".awc", ignoreCase = true)) it else "$it.awc"
        }
        return Uri.parse("${Rpf7StreamingDataSource.SCHEME}://$archiveStreamId/${Uri.encode(targetName)}.wav")
    }

    fun openStreamPfd(): android.os.ParcelFileDescriptor? {
        return try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")
        } catch (_: Exception) {
            null
        }
    }

    fun getOrBuildStreamIndex(entryName: String): Rpf7StreamIndex? {
        ensureIndexed()
        val entry = getEntry(entryName.trim()) ?: return null
        if (!entry.isMultiChannel || entry.dataChunkSize <= 0L) return null

        streamIndexCache[entry.name]?.let { return it }

        val numBlocks = entry.numBlocks
        val blockSize = entry.blockSize
        val channels = entry.channels
        val sampleRate = entry.sampleRate
        val totalSamples = entry.totalSamples.toLong() and 0xFFFFFFFFL
        if (channels !in 1..2 || sampleRate <= 0 || numBlocks <= 0 || blockSize <= 0 || totalSamples <= 0L) {
            return null
        }

        if (!seekTableCache.containsKey(entry.name)) {
            readTrackMarkers(entry.name)
        }
        val rawSeek = seekTableCache[entry.name]
        val seekTable = if (rawSeek != null && rawSeek.size == numBlocks && isMonotonicSeekTable(rawSeek, totalSamples)) {
            rawSeek.copyOf()
        } else {
            LongArray(numBlocks) { b -> (b.toLong() * totalSamples) / numBlocks.toLong() }
        }

        val bytesPerFrame = channels.toLong() * 2L
        val blockSamples = LongArray(numBlocks)
        val blockPcmBytes = LongArray(numBlocks)
        val blockStartWavByte = LongArray(numBlocks)
        for (b in 0 until numBlocks) {
            val endSample = if (b + 1 < numBlocks) seekTable[b + 1] else totalSamples
            val samples = (endSample - seekTable[b]).coerceAtLeast(0L)
            blockSamples[b] = samples
            blockPcmBytes[b] = samples * bytesPerFrame
            blockStartWavByte[b] = 44L + seekTable[b] * bytesPerFrame
        }

        val totalPcmBytes = totalSamples * bytesPerFrame
        val totalVirtualWavBytes = 44L + totalPcmBytes
        val durationMs = (totalSamples * 1000L) / sampleRate.toLong()

        val index = Rpf7StreamIndex(
            entryName = entry.name,
            numBlocks = numBlocks,
            blockSize = blockSize,
            channels = channels,
            sampleRate = sampleRate,
            codec = entry.codec,
            totalSamples = totalSamples,
            dataChunkOffset = entry.dataChunkOffset,
            dataChunkSize = entry.dataChunkSize,
            seekTable = seekTable,
            blockSamples = blockSamples,
            blockPcmBytes = blockPcmBytes,
            blockStartWavByte = blockStartWavByte,
            totalPcmBytes = totalPcmBytes,
            totalVirtualWavBytes = totalVirtualWavBytes,
            durationMs = durationMs
        )
        return streamIndexCache.putIfAbsent(entry.name, index) ?: index
    }

    private fun isMonotonicSeekTable(tbl: LongArray, totalSamples: Long): Boolean {
        if (tbl.isEmpty() || tbl[0] != 0L) return false
        for (i in 1 until tbl.size) {
            if (tbl[i] < tbl[i - 1]) return false
        }
        return tbl[tbl.size - 1] < totalSamples
    }

    /**
     * Decodes a single MultiChannel block [blockIdx] into raw interleaved 16-bit PCM bytes whose
     * exact length matches `index.blockPcmBytes[blockIdx]`.
     */
    fun decodeSingleBlockToPcmBytes(
        index: Rpf7StreamIndex,
        blockIdx: Int,
        prevWarmupByChannel: Array<ByteArray?>? = null,
        outTailWarmupByChannel: Array<ByteArray?>? = null,
        openChannel: FileChannel? = null
    ): ByteArray {
        if (blockIdx !in 0 until index.numBlocks) return ByteArray(0)
        val expectedBytes = index.blockPcmBytes[blockIdx].toInt().coerceAtLeast(0)
        if (expectedBytes == 0) return ByteArray(0)

        val entry = getEntry(index.entryName) ?: return ByteArray(expectedBytes)
        val keys = Rpf7KeyManager.getKeys(context) ?: return ByteArray(expectedBytes)

        fun decodeWithChannel(ch: FileChannel): ByteArray {
            val blkSize = index.blockSize
            val baseDataOffset = entry.offset + index.dataChunkOffset
            val dataEndPos = baseDataOffset + index.dataChunkSize
            val chCount = index.channels

            val blockOffset = baseDataOffset + blockIdx.toLong() * blkSize.toLong()
            val actualBlockSize = Math.min(blkSize.toLong(), dataEndPos - blockOffset).toInt()
            if (actualBlockSize <= 0) return ByteArray(expectedBytes)

            val blockBytes = ByteArray(actualBlockSize)
            val bytesRead = readFullyAt(ch, blockOffset, blockBytes, actualBlockSize)
            if (bytesRead <= 0) return ByteArray(expectedBytes)

            if (entry.isMultiChannelEncrypt && !entry.isWholeFileEncrypted) {
                Rpf7Crypto.decryptRsxxtea(blockBytes, 0, bytesRead and -4, keys.awcKey)
            }

            if (index.codec == 7) {
                val warmup = prevWarmupByChannel ?: if (blockIdx > 0) {
                    extractCodec7WarmupFromBlock(ch, entry, baseDataOffset, dataEndPos, blkSize, chCount, blockIdx - 1, keys)
                } else {
                    arrayOfNulls<ByteArray?>(chCount)
                }
                return decodeCodec7BlockBytes(
                    blockBytes = blockBytes,
                    actualBlockSize = bytesRead,
                    chCount = chCount,
                    prevWarmup = warmup,
                    outTailWarmup = outTailWarmupByChannel,
                    expectedBytes = expectedBytes
                )
            }

            // Codec 4 (IMA ADPCM) or other non-MP3 multi-channel stream
            var rpos = 0
            val blockCounts = IntArray(chCount)
            val sampleCounts = IntArray(chCount)
            for (c in 0 until chCount) {
                if (rpos + 24 <= bytesRead) {
                    blockCounts[c] = readIntLe(blockBytes, rpos + 4)
                    sampleCounts[c] = readIntLe(blockBytes, rpos + 12)
                }
                rpos += 24
            }
            for (c in 0 until chCount) {
                rpos += blockCounts[c] * 4
            }
            val pad = (2048 - (rpos % 2048)) % 2048
            rpos += pad

            val bcnt0 = (blockCounts[0] * 2048).coerceIn(0, (bytesRead - rpos).coerceAtLeast(0))
            val sCount0 = sampleCounts[0]
            val rightOff = rpos + bcnt0
            val bcnt1 = if (chCount > 1) {
                (blockCounts[1] * 2048).coerceIn(0, (bytesRead - rightOff).coerceAtLeast(0))
            } else 0
            val sCount1 = if (chCount > 1) sampleCounts[1] else 0

            val decoded = com.sc.gtradio.media.core.Gen3NativeTurbo.nativeDecodeImaAdpcmToPcm16(
                blockBytes,
                rpos.coerceAtMost(bytesRead),
                bcnt0,
                sCount0,
                if (chCount > 1) blockBytes else null,
                rightOff.coerceAtMost(bytesRead),
                bcnt1,
                sCount1
            )
            return normalizeBlockPcmBytes(decoded, expectedBytes)
        }

        return if (openChannel != null) {
            decodeWithChannel(openChannel)
        } else {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    decodeWithChannel(ch)
                }
            } ?: ByteArray(expectedBytes)
        }
    }

    private fun extractCodec7WarmupFromBlock(
        channel: FileChannel,
        entry: Rpf7Entry,
        baseDataOffset: Long,
        dataEndPos: Long,
        blkSize: Int,
        chCount: Int,
        prevBlockIdx: Int,
        keys: Rpf7KeySet
    ): Array<ByteArray?> {
        val prevWarmup = Array<ByteArray?>(chCount) { null }
        if (prevBlockIdx < 0) return prevWarmup
        try {
            val prevBlockOffset = baseDataOffset + prevBlockIdx.toLong() * blkSize.toLong()
            val actualPrevSize = Math.min(blkSize.toLong(), dataEndPos - prevBlockOffset).toInt()
            if (actualPrevSize <= 2048) return prevWarmup

            val prevBlockBytes = ByteArray(actualPrevSize)
            val bytesRead = readFullyAt(channel, prevBlockOffset, prevBlockBytes, actualPrevSize)
            if (bytesRead <= 2048) return prevWarmup

            if (entry.isMultiChannelEncrypt && !entry.isWholeFileEncrypted) {
                Rpf7Crypto.decryptRsxxtea(prevBlockBytes, 0, bytesRead and -4, keys.awcKey)
            }
            val prevByteLengths = IntArray(chCount)
            var dPos = 0
            for (c in 0 until chCount) {
                if (dPos + 24 <= bytesRead) {
                    prevByteLengths[c] = readIntLe(prevBlockBytes, dPos + 20)
                }
                dPos += 24
            }
            var cPos = 2048
            for (c in 0 until chCount) {
                val chLen = prevByteLengths[c]
                val chOff = cPos
                cPos = (cPos + chLen + 15) and -16
                val safeLen = Math.max(0, Math.min(chLen, bytesRead - chOff))
                if (safeLen > 0 && chOff < bytesRead) {
                    val warmupSize = Math.min(2048, safeLen)
                    val warmupBytes = ByteArray(warmupSize)
                    System.arraycopy(prevBlockBytes, chOff + safeLen - warmupSize, warmupBytes, 0, warmupSize)
                    prevWarmup[c] = warmupBytes
                }
            }
        } catch (e: Exception) {
            GTRadioLogger.logVerbose("DECODE") { "Failed warmup read on blk $prevBlockIdx: ${e.message}" }
        }
        return prevWarmup
    }

    private fun decodeCodec7BlockBytes(
        blockBytes: ByteArray,
        actualBlockSize: Int,
        chCount: Int,
        prevWarmup: Array<ByteArray?>,
        outTailWarmup: Array<ByteArray?>?,
        expectedBytes: Int
    ): ByteArray {
        val samplesToSkip = IntArray(chCount)
        val sampleCounts = IntArray(chCount)
        val byteLengths = IntArray(chCount)

        var descPos = 0
        for (c in 0 until chCount) {
            if (descPos + 24 <= actualBlockSize) {
                samplesToSkip[c] = readIntLe(blockBytes, descPos + 8)
                sampleCounts[c] = readIntLe(blockBytes, descPos + 12)
                byteLengths[c] = readIntLe(blockBytes, descPos + 20)
            }
            descPos += 24
        }

        var curPos = 2048
        val chOffsets = IntArray(chCount)
        val chSafeLens = IntArray(chCount)
        for (c in 0 until chCount) {
            val chByteLen = byteLengths[c]
            chOffsets[c] = curPos
            curPos = (curPos + chByteLen + 15) and -16
            chSafeLens[c] = Math.max(0, Math.min(chByteLen, actualBlockSize - chOffsets[c]))
        }

        val leftPayload = ByteArray(chSafeLens[0])
        if (chSafeLens[0] > 0 && chOffsets[0] < actualBlockSize) {
            System.arraycopy(blockBytes, chOffsets[0], leftPayload, 0, chSafeLens[0])
        }
        val rightPayload = if (chCount >= 2 && chSafeLens[1] > 0 && chOffsets[1] < actualBlockSize) {
            ByteArray(chSafeLens[1]).also { rBuf ->
                System.arraycopy(blockBytes, chOffsets[1], rBuf, 0, chSafeLens[1])
            }
        } else null

        if (outTailWarmup != null) {
            for (c in 0 until minOf(chCount, outTailWarmup.size)) {
                val pBuf = if (c == 0) leftPayload else rightPayload
                val sLen = pBuf?.size ?: 0
                if (pBuf != null && sLen > 0) {
                    val warmupSize = Math.min(2048, sLen)
                    val tail = ByteArray(warmupSize)
                    System.arraycopy(pBuf, sLen - warmupSize, tail, 0, warmupSize)
                    outTailWarmup[c] = tail
                } else {
                    outTailWarmup[c] = null
                }
            }
        }

        // AWC Codec 7 sampleCount includes leading samplesToSkip; native decoder takes net output samples
        val netLeft = (sampleCounts[0] - samplesToSkip[0]).coerceAtLeast(0)
        val netRight = if (chCount >= 2) (sampleCounts[1] - samplesToSkip[1]).coerceAtLeast(0) else 0

        val blockPcm = NativeMp3Decoder.nativeDecodeDualMonoMp3BlockToPcmBytes(
            leftPayload,
            prevWarmup.getOrNull(0),
            samplesToSkip[0],
            netLeft,
            rightPayload,
            if (chCount >= 2) prevWarmup.getOrNull(1) else null,
            if (chCount >= 2) samplesToSkip[1] else 0,
            netRight
        )
        return normalizeBlockPcmBytes(blockPcm, expectedBytes)
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

    private fun readFullyAt(channel: FileChannel, position: Long, dst: ByteArray, length: Int): Int {
        val buf = ByteBuffer.wrap(dst, 0, length)
        var total = 0
        while (buf.hasRemaining()) {
            val n = channel.read(buf, position + total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    // ------------------------------------------------------------------------
    // Audio Materialization (Decoding to 16-bit PCM WAV)
    // ------------------------------------------------------------------------

    /**
     * Main entry point to materialize audio for [trackKey].
     * For large MultiChannel tracks (> CHUNK_THRESHOLD_BLOCKS), decodes the first chunk (0..CHUNK_BLOCKS)
     * and triggers prefetch of the next chunk.
     * For small MultiChannel tracks, decodes all blocks into a full WAV.
     * For single-channel bank clips, decodes the clip into a mono WAV.
     */
    fun materializeAudioUri(trackKey: String): Uri? {
        ensureIndexed()
        val cleanKey = trackKey.trim()

        // 1. Check if it's a MultiChannel entry
        val entry = getEntry(cleanKey)
        if (entry != null && entry.isMultiChannel) {
            val threshold = getChunkThresholdFor(entry.name)
            val chunkBlocks = getChunkBlocksFor(entry.name)
            return if (entry.numBlocks > threshold) {
                materializeBlockChunkUri(entry.name, 0, chunkBlocks)
            } else {
                materializeBlockChunkUri(entry.name, 0, entry.numBlocks)
            }
        }

        // 2. Check if it's a Bank sub-track
        val subTrack = bankSubTracks[cleanKey]
        if (subTrack != null) {
            return materializeBankSubTrackUri(subTrack)
        }

        GTRadioLogger.log("Rpf7Archive[$rpfName]: Track key not found: $trackKey")
        return null
    }

    fun materializeFullAudioUri(trackKey: String): Uri? {
        ensureIndexed()
        val cleanKey = trackKey.trim()
        val entry = getEntry(cleanKey)
        if (entry != null && entry.isMultiChannel) {
            return materializeBlockChunkUri(entry.name, 0, entry.numBlocks)
        }
        val subTrack = bankSubTracks[cleanKey]
        if (subTrack != null) {
            return materializeBankSubTrackUri(subTrack)
        }
        return null
    }

    /**
     * Decodes [blockCount] blocks starting from [startBlock] of [entryName] to a stereo 16-bit WAV.
     * Automatically triggers prefetch of the subsequent chunk.
     */
    fun materializeBlockChunkUri(
        entryName: String,
        startBlock: Int,
        blockCount: Int = getChunkBlocksFor(entryName)
    ): Uri? {
        ensureIndexed()
        val entry = getEntry(entryName) ?: return null
        val actualStart = Math.max(0, startBlock)
        val actualBlocks = minOf(blockCount, (entry.numBlocks - actualStart).coerceAtLeast(0))
        if (actualBlocks <= 0) return null

        val uri = materializeBlockChunkInternal(entry, actualStart, actualBlocks)
        if (uri != null && uri.scheme == "file") {
            uri.path?.let { path ->
                runCatching { File(path).setLastModified(System.currentTimeMillis()) }
            }
        }

        // Background prefetch next chunk if more blocks remain
        if (actualStart + actualBlocks < entry.numBlocks) {
            val nextBlocks = minOf(getChunkBlocksFor(entry.name), entry.numBlocks - (actualStart + actualBlocks))
            if (nextBlocks > 0) {
                prefetchBlockChunk(entry.name, actualStart + actualBlocks, nextBlocks)
            }
        }

        return uri
    }

    /**
     * Alias for [materializeBlockChunkUri] for parity with Rpf3Archive.
     */
    fun materializeAudioChunkUri(
        entryName: String,
        startBlock: Int,
        blockCount: Int = getChunkBlocksFor(entryName)
    ): Uri? = materializeBlockChunkUri(entryName, startBlock, blockCount)

    /**
     * Asynchronously decodes the next chunk in the background so transitions are instantaneous.
     */
    fun prefetchBlockChunk(
        entryName: String,
        startBlock: Int,
        blockCount: Int = getChunkBlocksFor(entryName),
        isCancelled: (() -> Boolean)? = null
    ) {
        val entry = getEntry(entryName) ?: return
        if (startBlock >= entry.numBlocks) return
        val actualStart = Math.max(0, startBlock)
        val actualBlocks = minOf(blockCount, (entry.numBlocks - actualStart).coerceAtLeast(0))
        if (actualBlocks <= 0) return
        prefetchExecutor.execute {
            if (isCancelled?.invoke() == true) return@execute
            try {
                materializeBlockChunkInternal(entry, actualStart, actualBlocks)
            } catch (_: Exception) {}
        }
    }

    private fun materializeBlockChunkInternal(
        entry: Rpf7Entry,
        startBlock: Int,
        blockCount: Int
    ): Uri? {
        val totalBlocks = entry.numBlocks
        val actualStart = Math.max(0, startBlock)
        val actualBlocks = minOf(blockCount, (totalBlocks - actualStart).coerceAtLeast(0))
        if (actualBlocks <= 0) return null

        val cacheDir = File(context.cacheDir, "rpf7_tracks")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val uriHash = Integer.toHexString(sourceUri.toString().hashCode())
        val isFullTrack = (actualStart == 0 && actualBlocks >= totalBlocks)
        val chunkSuffix = if (isFullTrack) "" else "_blk${actualStart}_${actualBlocks}"
        val safeBase = entry.name.removeSuffix(".awc").replace(Regex("[^A-Za-z0-9_]"), "_")
        val safeFileName = "${rpfName.removeSuffix(".rpf")}_${uriHash}_${safeBase}$chunkSuffix.wav"
        val outFile = File(cacheDir, safeFileName)

        // Cache hit
        if (outFile.exists() && outFile.length() > 44) {
            outFile.setLastModified(System.currentTimeMillis())
            GTRadioLogger.logVerbose("DECODE") { "RPF7 cache HIT: $safeFileName" }
            return Uri.fromFile(outFile)
        }

        val decodeStart = System.currentTimeMillis()
        synchronized(decodeLock) {
            if (outFile.exists() && outFile.length() > 44) {
                outFile.setLastModified(System.currentTimeMillis())
                GTRadioLogger.logVerbose("DECODE") { "RPF7 cache HIT: $safeFileName" }
                return Uri.fromFile(outFile)
            }

            cleanCacheIfNeeded(cacheDir, safeFileName)

            val tempFile = File(cacheDir, "${safeFileName}_${Thread.currentThread().id}.tmp")
            try {
                val keys = Rpf7KeyManager.getKeys(context)
                    ?: throw IllegalStateException("Keys not unlocked")

                context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        if (entry.codec == 7) {
                            decodeEnhancedMp3MultiChannelBlocks(channel, entry, actualStart, actualBlocks, keys, tempFile)
                        } else {
                            decodeMultiChannelBlocks(channel, entry, actualStart, actualBlocks, keys, tempFile)
                        }
                    }
                }

                if (tempFile.exists() && tempFile.length() > 44) {
                    if (outFile.exists()) outFile.delete()
                    if (tempFile.renameTo(outFile)) {
                        val decodeMs = System.currentTimeMillis() - decodeStart
                        val codecName = when (entry.codec) {
                            7 -> "MP3"
                            4 -> "ADPCM"
                            else -> "PCM"
                        }
                        GTRadioLogger.logVerbose("DECODE") {
                            "RPF7 cache MISS: decoded $safeFileName in ${decodeMs}ms ($codecName, ${outFile.length() / 1024} KB)"
                        }
                        return Uri.fromFile(outFile)
                    }
                }
            } catch (e: Exception) {
                GTRadioLogger.log("Rpf7Archive[$rpfName]: Failed decoding ${entry.name} blk[$startBlock..${startBlock+blockCount}]", e)
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
        return null
    }

    private fun decodeEnhancedMp3MultiChannelBlocks(
        channel: FileChannel,
        entry: Rpf7Entry,
        startBlock: Int,
        blockCount: Int,
        keys: Rpf7KeySet,
        tempFile: File
    ) {
        val blkSize = entry.blockSize
        val baseDataOffset = entry.offset + entry.dataChunkOffset
        val chCount = Math.max(1, entry.channels)
        val dataEndPos = baseDataOffset + entry.dataChunkSize

        val blockPcmList = ArrayList<ByteArray>(blockCount)
        var totalPcmBytes = 0
        var detectedSampleRate: Int? = null
        val prevChannelWarmup = Array<ByteArray?>(chCount) { null }

        // If resuming or chunking mid-stream, extract warmup bytes from preceding block to prime bit reservoir
        if (startBlock > 0) {
            try {
                val prevBlockOffset = baseDataOffset + (startBlock - 1).toLong() * blkSize.toLong()
                val actualPrevSize = Math.min(blkSize.toLong(), dataEndPos - prevBlockOffset).toInt()
                if (actualPrevSize > 2048) {
                    val prevBlockBytes = ByteArray(actualPrevSize)
                    val prevBuf = ByteBuffer.wrap(prevBlockBytes)
                    channel.position(prevBlockOffset)
                    if (channel.read(prevBuf) > 0) {
                        if (entry.isMultiChannelEncrypt && !entry.isWholeFileEncrypted) {
                            Rpf7Crypto.decryptRsxxtea(prevBlockBytes, 0, actualPrevSize and -4, keys.awcKey)
                        }
                        val prevByteLengths = IntArray(chCount)
                        var dPos = 0
                        for (c in 0 until chCount) {
                            if (dPos + 24 <= actualPrevSize) {
                                prevByteLengths[c] = readIntLe(prevBlockBytes, dPos + 20)
                            }
                            dPos += 24
                        }
                        var cPos = 2048
                        for (c in 0 until chCount) {
                            val chLen = prevByteLengths[c]
                            val chOff = cPos
                            cPos = (cPos + chLen + 15) and -16
                            val safeLen = Math.max(0, Math.min(chLen, actualPrevSize - chOff))
                            if (safeLen > 0 && chOff < actualPrevSize) {
                                val warmupSize = Math.min(2048, safeLen)
                                val warmupBytes = ByteArray(warmupSize)
                                System.arraycopy(prevBlockBytes, chOff + safeLen - warmupSize, warmupBytes, 0, warmupSize)
                                prevChannelWarmup[c] = warmupBytes
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                GTRadioLogger.logVerbose("DECODE") { "Failed to extract warmup bytes from block ${startBlock - 1}: ${e.message}" }
            }
        }

        for (b in 0 until blockCount) {
            val curBlockIdx = startBlock + b
            if (curBlockIdx >= entry.numBlocks) break

            val blockOffset = baseDataOffset + curBlockIdx.toLong() * blkSize.toLong()
            val actualBlockSize = Math.min(blkSize.toLong(), dataEndPos - blockOffset).toInt()
            if (actualBlockSize <= 0) break

            val blockBytes = ByteArray(actualBlockSize)
            val buf = ByteBuffer.wrap(blockBytes)
            channel.position(blockOffset)
            val bytesRead = channel.read(buf)
            if (bytesRead <= 0) break

            // Decrypt block payload if encrypted
            if (entry.isMultiChannelEncrypt && !entry.isWholeFileEncrypted) {
                Rpf7Crypto.decryptRsxxtea(blockBytes, 0, actualBlockSize and -4, keys.awcKey)
            }

            // Parse 2048-byte Codec-7 block header
            // For each channel: 6 int32s (24 bytes): startBlock, blockCount, samplesToSkip, sampleCount, frameCount, byteLength
            val startBlocks = IntArray(chCount)
            val blockCounts = IntArray(chCount)
            val samplesToSkip = IntArray(chCount)
            val sampleCounts = IntArray(chCount)
            val frameCounts = IntArray(chCount)
            val byteLengths = IntArray(chCount)

            var descPos = 0
            for (c in 0 until chCount) {
                if (descPos + 24 <= actualBlockSize) {
                    startBlocks[c] = readIntLe(blockBytes, descPos)
                    blockCounts[c] = readIntLe(blockBytes, descPos + 4)
                    samplesToSkip[c] = readIntLe(blockBytes, descPos + 8)
                    sampleCounts[c] = readIntLe(blockBytes, descPos + 12)
                    frameCounts[c] = readIntLe(blockBytes, descPos + 16)
                    byteLengths[c] = readIntLe(blockBytes, descPos + 20)
                }
                descPos += 24
            }

            // Channel payloads start after header (2048 bytes)
            var curPos = 2048
            val chOffsets = IntArray(chCount)
            val chSafeLens = IntArray(chCount)
            for (c in 0 until chCount) {
                val chByteLen = byteLengths[c]
                chOffsets[c] = curPos
                curPos = (curPos + chByteLen + 15) and -16
                chSafeLens[c] = Math.max(0, Math.min(chByteLen, actualBlockSize - chOffsets[c]))
            }

            val leftPayload = ByteArray(chSafeLens[0])
            if (chSafeLens[0] > 0 && chOffsets[0] < actualBlockSize) {
                System.arraycopy(blockBytes, chOffsets[0], leftPayload, 0, chSafeLens[0])
            }
            if (detectedSampleRate == null && leftPayload.size >= 4) {
                detectedSampleRate = detectMp3SampleRate(leftPayload)
            }
            val rightPayload = if (chCount >= 2 && chSafeLens[1] > 0 && chOffsets[1] < actualBlockSize) {
                val rBuf = ByteArray(chSafeLens[1])
                System.arraycopy(blockBytes, chOffsets[1], rBuf, 0, chSafeLens[1])
                rBuf
            } else null

            // AWC Codec 7 sampleCount includes leading samplesToSkip; native decoder takes net output samples
            val netLeftCount = (sampleCounts[0] - samplesToSkip[0]).coerceAtLeast(0)
            val netRightCount = if (chCount >= 2) (sampleCounts[1] - samplesToSkip[1]).coerceAtLeast(0) else 0

            val blockPcm = NativeMp3Decoder.nativeDecodeDualMonoMp3BlockToPcmBytes(
                leftPayload,
                prevChannelWarmup[0],
                samplesToSkip[0],
                netLeftCount,
                rightPayload,
                if (chCount >= 2) prevChannelWarmup[1] else null,
                if (chCount >= 2) samplesToSkip[1] else 0,
                netRightCount
            )

            // Save trailing bytes for the next block warmup
            for (c in 0 until chCount) {
                val sLen = chSafeLens[c]
                if (sLen > 0) {
                    val warmupSize = Math.min(2048, sLen)
                    val warmupBytes = ByteArray(warmupSize)
                    val pBuf = if (c == 0) leftPayload else rightPayload
                    if (pBuf != null && pBuf.size >= sLen) {
                        System.arraycopy(pBuf, sLen - warmupSize, warmupBytes, 0, warmupSize)
                        prevChannelWarmup[c] = warmupBytes
                    }
                }
            }

            if (blockPcm.isNotEmpty()) {
                blockPcmList.add(blockPcm)
                totalPcmBytes += blockPcm.size
            }
        }

        val wavSampleRate = detectedSampleRate ?: entry.sampleRate.takeIf { it > 0 } ?: 48000
        if (detectedSampleRate != null && detectedSampleRate != entry.sampleRate) {
            GTRadioLogger.logVerbose("DECODE") {
                "RPF7 MP3 sample rate for ${entry.name}: header=${entry.sampleRate}Hz -> actual=${detectedSampleRate}Hz"
            }
        }

        // Write final WAV file with 256 KB buffer and single write per block
        BufferedOutputStream(FileOutputStream(tempFile), 262144).use { out ->
            writeWavHeader(out, totalPcmBytes, wavSampleRate, chCount)
            for (blockPcm in blockPcmList) {
                out.write(blockPcm)
            }
        }
    }

    /**
     * Parses MPEG Layer III frame sync headers to detect the native decoded sample rate
     * matching minimp3's info.hz so mono/stereo WAV headers never play at a mismatched pitch.
     */
    private fun detectMp3SampleRate(mp3Bytes: ByteArray): Int? {
        val mpeg1Bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val mpeg2Bitrates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        val scanLimit = minOf(mp3Bytes.size - 4, 2048)
        for (i in 0..scanLimit) {
            val b0 = mp3Bytes[i].toInt() and 0xFF
            val b1 = mp3Bytes[i + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) continue

            val versionBits = (b1 ushr 3) and 0x03
            val layerBits = (b1 ushr 1) and 0x03
            if (versionBits == 1 || layerBits != 1) continue

            val b2 = mp3Bytes[i + 2].toInt() and 0xFF
            val bitrateIdx = (b2 ushr 4) and 0x0F
            val srIdx = (b2 ushr 2) and 0x03
            val padding = (b2 ushr 1) and 0x01
            if (bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) continue

            val hz = when (versionBits) {
                3 -> intArrayOf(44100, 48000, 32000)[srIdx]
                2 -> intArrayOf(22050, 24000, 16000)[srIdx]
                else -> intArrayOf(11025, 12000, 8000)[srIdx]
            }
            val kbps = if (versionBits == 3) mpeg1Bitrates[bitrateIdx] else mpeg2Bitrates[bitrateIdx]
            if (kbps <= 0) continue

            val frameSize = (if (versionBits == 3) 144 else 72) * (kbps * 1000) / hz + padding
            if (frameSize < 24) continue

            val nextPos = i + frameSize
            if (nextPos + 4 <= mp3Bytes.size) {
                val n0 = mp3Bytes[nextPos].toInt() and 0xFF
                val n1 = mp3Bytes[nextPos + 1].toInt() and 0xFF
                val n2 = mp3Bytes[nextPos + 2].toInt() and 0xFF
                val nVer = (n1 ushr 3) and 0x03
                val nLayer = (n1 ushr 1) and 0x03
                val nSrIdx = (n2 ushr 2) and 0x03
                if (n0 == 0xFF && (n1 and 0xE0) == 0xE0 && nVer == versionBits && nLayer == 1 && nSrIdx == srIdx) {
                    return hz
                }
            } else if (i == 0) {
                return hz
            }
        }
        return null
    }

    private fun decodeMp3ChannelToPcm(mp3Bytes: ByteArray, sampleRate: Int, warmupBytes: ByteArray? = null): ShortArray {
        if (mp3Bytes.isEmpty()) return ShortArray(0)
        return try {
            NativeMp3Decoder.nativeDecodeMonoMp3WithWarmup(mp3Bytes, warmupBytes)
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf7Archive[$rpfName]: Native MP3 decoding failed", e)
            ShortArray(0)
        }
    }

    private fun decodeMultiChannelBlocks(
        channel: FileChannel,
        entry: Rpf7Entry,
        startBlock: Int,
        blockCount: Int,
        keys: Rpf7KeySet,
        tempFile: File
    ) {
        val blkSize = entry.blockSize
        val baseDataOffset = entry.offset + entry.dataChunkOffset
        val chCount = Math.max(1, entry.channels)
        val dataEndPos = baseDataOffset + entry.dataChunkSize

        // Temporary decode buffers (each byte of ADPCM decompresses to 2 PCM samples)
        val rawBlock = ByteArray(blkSize)
        val blockPcmList = ArrayList<ByteArray>(blockCount)
        var totalPcmBytes = 0

        for (b in 0 until blockCount) {
            val curBlockIdx = startBlock + b
            if (curBlockIdx >= entry.numBlocks) break

            val blockOffset = baseDataOffset + curBlockIdx.toLong() * blkSize.toLong()
            val actualBlockSize = Math.min(blkSize.toLong(), dataEndPos - blockOffset).toInt()
            if (actualBlockSize <= 0) break
            val bytesRead = readFullyAt(channel, blockOffset, rawBlock, actualBlockSize)
            if (bytesRead <= 0) break

            // RSXXTEA decrypt block payload if encrypted
            if (entry.isMultiChannelEncrypt && !entry.isWholeFileEncrypted) {
                Rpf7Crypto.decryptRsxxtea(rawBlock, 0, bytesRead and -4, keys.awcKey)
            }

            // Parse block channel headers
            var rpos = 0
            val startBlks = IntArray(chCount)
            val blockCounts = IntArray(chCount)
            val sampleCounts = IntArray(chCount)

            for (c in 0 until chCount) {
                startBlks[c] = readIntLe(rawBlock, rpos)
                blockCounts[c] = readIntLe(rawBlock, rpos + 4)
                sampleCounts[c] = readIntLe(rawBlock, rpos + 12)
                rpos += 24
            }

            for (c in 0 until chCount) {
                rpos += blockCounts[c] * 4
            }

            val pad = (2048 - (rpos % 2048)) % 2048
            rpos += pad

            val bcnt0 = blockCounts[0] * 2048
            val sCount0 = sampleCounts[0]
            val bcnt1 = if (chCount > 1) blockCounts[1] * 2048 else 0
            val sCount1 = if (chCount > 1) sampleCounts[1] else 0

            val blockPcm = com.sc.gtradio.media.core.Gen3NativeTurbo.nativeDecodeImaAdpcmToPcm16(
                rawBlock,
                rpos,
                bcnt0,
                sCount0,
                if (chCount > 1) rawBlock else null,
                rpos + bcnt0,
                bcnt1,
                sCount1
            )
            if (blockPcm.isNotEmpty()) {
                blockPcmList.add(blockPcm)
                totalPcmBytes += blockPcm.size
            }
        }

        // Write final WAV file with 256 KB buffer and single write per block
        BufferedOutputStream(FileOutputStream(tempFile), 262144).use { out ->
            writeWavHeader(out, totalPcmBytes, entry.sampleRate.takeIf { it > 0 } ?: 48000, chCount)
            for (blockPcm in blockPcmList) {
                out.write(blockPcm)
            }
        }
    }

    private fun materializeBankSubTrackUri(subTrack: Rpf7BankSubTrack): Uri? {
        val cacheDir = File(context.cacheDir, "rpf7_tracks")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val uriHash = Integer.toHexString(sourceUri.toString().hashCode())
        val safeParent = subTrack.parentEntryName.removeSuffix(".awc").replace(Regex("[^A-Za-z0-9_]"), "_")
        val isMp3 = (subTrack.codec == 7)
        val ext = if (isMp3) "mp3" else "wav"
        val safeFileName = "${rpfName.removeSuffix(".rpf")}_${uriHash}_${safeParent}_0x${Integer.toHexString(subTrack.streamId).padStart(8, '0')}.$ext"
        val outFile = File(cacheDir, safeFileName)

        if (outFile.exists() && outFile.length() > 0) {
            outFile.setLastModified(System.currentTimeMillis())
            GTRadioLogger.logVerbose("DECODE") { "RPF7 cache HIT: $safeFileName" }
            return Uri.fromFile(outFile)
        }

        val decodeStart = System.currentTimeMillis()
        synchronized(decodeLock) {
            if (outFile.exists() && outFile.length() > 0) {
                outFile.setLastModified(System.currentTimeMillis())
                GTRadioLogger.logVerbose("DECODE") { "RPF7 cache HIT: $safeFileName" }
                return Uri.fromFile(outFile)
            }

            cleanCacheIfNeeded(cacheDir, safeFileName)

            val tempFile = File(cacheDir, "${safeFileName}_${Thread.currentThread().id}.tmp")
            try {
                val keys = Rpf7KeyManager.getKeys(context)
                    ?: throw IllegalStateException("Keys not unlocked")

                val clipRaw = ByteArray(subTrack.dataSize)
                context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        val buf = ByteBuffer.wrap(clipRaw)
                        channel.position(subTrack.dataOffsetInRpf)
                        channel.read(buf)
                    }
                }

                // Decrypt SingleChannelEncrypt payload
                if (subTrack.isEncrypted) {
                    Rpf7Crypto.decryptRsxxtea(clipRaw, 0, clipRaw.size and -4, keys.awcKey)
                }

                if (isMp3) {
                    FileOutputStream(tempFile).use { out ->
                        out.write(clipRaw)
                    }
                } else {
                    val pcmBytes: ByteArray = if (subTrack.codec == 0 || subTrack.codec == 1) {
                        clipRaw
                    } else {
                        com.sc.gtradio.media.core.Gen3NativeTurbo.nativeDecodeImaAdpcmToPcm16(
                            clipRaw, 0, clipRaw.size, subTrack.numSamples, null, 0, 0, 0
                        )
                    }

                    BufferedOutputStream(FileOutputStream(tempFile), 262144).use { out ->
                        val bankRate = subTrack.sampleRate.takeIf { it > 0 } ?: 48000
                        writeWavHeader(out, pcmBytes.size, bankRate, 1)
                        out.write(pcmBytes)
                    }
                }

                if (tempFile.exists() && tempFile.length() > 0) {
                    if (outFile.exists()) outFile.delete()
                    if (tempFile.renameTo(outFile)) {
                        val decodeMs = System.currentTimeMillis() - decodeStart
                        val codecName = if (isMp3) "MP3" else if (subTrack.codec == 4) "ADPCM" else "PCM"
                        GTRadioLogger.logVerbose("DECODE") {
                            "RPF7 cache MISS: decoded $safeFileName in ${decodeMs}ms ($codecName, ${outFile.length() / 1024} KB)"
                        }
                        return Uri.fromFile(outFile)
                    }
                }
            } catch (e: Exception) {
                GTRadioLogger.log("Rpf7Archive[$rpfName]: Failed decoding bank sub-track ${subTrack.subKey}", e)
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    // WAV Header & Cache Management
    // ------------------------------------------------------------------------

    private fun writeWavHeader(
        out: java.io.OutputStream,
        pcmDataBytes: Int,
        sampleRate: Int,
        channels: Int
    ) {
        out.write(buildWavHeaderBytes(pcmDataBytes.toLong() and 0xFFFFFFFFL, sampleRate, channels))
    }

    private fun cleanCacheIfNeeded(cacheDir: File, currentSafeFileName: String) {
        val allFiles = ArrayList<File>()
        cacheDir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
        if (allFiles.isEmpty()) return
        var totalBytes = 0L
        for (f in allFiles) totalBytes += f.length()

        if (totalBytes > MAX_CACHE_BYTES) {
            val now = System.currentTimeMillis()
            val sorted = allFiles.sortedBy { it.lastModified() }
            var evictedFiles = 0
            var evictedBytes = 0L
            for (f in sorted) {
                if (totalBytes <= TARGET_CACHE_BYTES) break
                val ageMs = now - f.lastModified()
                // Never delete active file, pinned playing/queued files, or files modified within the last 15 seconds
                if (f.name != currentSafeFileName && !isPinned(f.name) && ageMs > 15_000L) {
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
                    "Cache eviction (RPF7): pruned $evictedFiles file(s), ${evictedBytes / 1024} KB freed (limit ${MAX_CACHE_BYTES / 1024 / 1024}MB)"
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Binary Parsing Helpers
    // ------------------------------------------------------------------------

    private fun readIntLe(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLe(b: ByteArray, off: Int): Short {
        return ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    private fun readLongLe(b: ByteArray, off: Int): Long {
        var res = 0L
        for (i in 0 until 8) {
            res = res or ((b[off + i].toLong() and 0xFFL) shl (i * 8))
        }
        return res
    }
}
