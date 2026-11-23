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
    @Volatile private var indexed = false

    companion object {
        const val CHUNK_BLOCKS = 4
        const val CHUNK_THRESHOLD_BLOCKS = 16
        private val NO_DJ_MARKERS = DjMarkerWindow()

        private const val MAX_CACHE_BYTES = 150L * 1024L * 1024L // 150 MB cap
        private const val TARGET_CACHE_BYTES = 100L * 1024L * 1024L // Evict down to 100 MB

        private val pinnedFileNames = java.util.Collections.synchronizedSet(HashSet<String>())

        fun pinFile(fileNameOrUri: String) {
            val name = fileNameOrUri.substringAfterLast('/')
            pinnedFileNames.add(name)
        }

        fun unpinFile(fileNameOrUri: String) {
            val name = fileNameOrUri.substringAfterLast('/')
            pinnedFileNames.remove(name)
        }

        fun clearPinnedFiles() {
            pinnedFileNames.clear()
        }

        fun isPinned(fileName: String): Boolean = pinnedFileNames.contains(fileName)

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
        indexed = true

        val keys = Rpf7KeyManager.getKeys(context)
        if (keys == null) {
            GTRadioLogger.log("Rpf7Archive[$rpfName]: GTA V keys not unlocked. Indexing skipped.")
            return
        }

        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    indexRpf(channel, keys)
                }
            }
            GTRadioLogger.log("Rpf7Archive[$rpfName]: Indexed ${entries.size} entries and ${bankSubTracks.size} bank sub-tracks")
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf7Archive[$rpfName]: Indexing failed", e)
        }
    }

    private fun indexRpf(channel: FileChannel, keys: Rpf7KeySet) {
        val fileSize = channel.size()
        if (fileSize < 16) return

        val hdrBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        channel.read(hdrBuf)
        hdrBuf.flip()

        val magic = hdrBuf.int
        if (magic != 0x52504637) { // "RPF7"
            GTRadioLogger.log("Rpf7Archive[$rpfName]: Invalid RPF7 magic: 0x${Integer.toHexString(magic)}")
            return
        }

        val entryCount = hdrBuf.int
        val namesLength = hdrBuf.int
        val enc = hdrBuf.int

        if (entryCount <= 0 || namesLength <= 0) return

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
                        val sfBuf = ByteBuffer.allocate(Math.min(chunk.size, 128)).order(ByteOrder.LITTLE_ENDIAN)
                        channel.position(rpfOffset + chunk.offset)
                        channel.read(sfBuf)
                        sfBuf.flip()
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
        val actualCount = Math.min(blockCount, entry.numBlocks - actualStart)
        if (actualCount <= 0) return null

        val uri = materializeBlockChunkInternal(entry, actualStart, actualCount)

        // Background prefetch next chunk if more blocks remain
        if (actualStart + actualCount < entry.numBlocks) {
            prefetchBlockChunk(entry.name, actualStart + actualCount, getChunkBlocksFor(entry.name))
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
        prefetchExecutor.execute {
            if (isCancelled?.invoke() == true) return@execute
            try {
                materializeBlockChunkInternal(entry, startBlock, blockCount)
            } catch (_: Exception) {}
        }
    }

    private fun materializeBlockChunkInternal(
        entry: Rpf7Entry,
        startBlock: Int,
        blockCount: Int
    ): Uri? {
        val cacheDir = File(context.cacheDir, "rpf7_tracks")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val uriHash = Integer.toHexString(sourceUri.toString().hashCode())
        val isFullTrack = (startBlock == 0 && blockCount >= entry.numBlocks)
        val chunkSuffix = if (isFullTrack) "" else "_blk${startBlock}_${blockCount}"
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
                            decodeEnhancedMp3MultiChannelBlocks(channel, entry, startBlock, blockCount, keys, tempFile)
                        } else {
                            decodeMultiChannelBlocks(channel, entry, startBlock, blockCount, keys, tempFile)
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

        val pcmDataBaos = java.io.ByteArrayOutputStream(blockCount * blkSize * 4)
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
            val channelPcm = ArrayList<ShortArray>(chCount)

            for (c in 0 until chCount) {
                val chByteLen = byteLengths[c]
                val chOffset = curPos
                // 16-byte alignment for next channel
                curPos = (curPos + chByteLen + 15) and -16

                val safeLen = Math.max(0, Math.min(chByteLen, actualBlockSize - chOffset))
                val mp3Payload = ByteArray(safeLen)
                if (safeLen > 0 && chOffset < actualBlockSize) {
                    System.arraycopy(blockBytes, chOffset, mp3Payload, 0, safeLen)
                }

                val decoded = decodeMp3ChannelToPcm(mp3Payload, entry.sampleRate, prevChannelWarmup[c])

                // Save trailing bytes for the next block warmup
                if (safeLen > 0) {
                    val warmupSize = Math.min(2048, safeLen)
                    val warmupBytes = ByteArray(warmupSize)
                    System.arraycopy(mp3Payload, safeLen - warmupSize, warmupBytes, 0, warmupSize)
                    prevChannelWarmup[c] = warmupBytes
                }

                val trimStart = samplesToSkip[c].coerceIn(0, decoded.size)
                val targetEnd = trimStart + sampleCounts[c]
                val trimEnd = if (sampleCounts[c] > 0) targetEnd.coerceAtMost(decoded.size) else decoded.size
                val trimmed = if (trimStart < trimEnd) {
                    decoded.copyOfRange(trimStart, trimEnd)
                } else {
                    decoded
                }
                channelPcm.add(trimmed)
            }

            // Interleave PCM frames (Left, Right for stereo, or Mono)
            if (chCount >= 2) {
                val left = channelPcm[0]
                val right = channelPcm[1]
                val n = Math.min(left.size, right.size)
                for (f in 0 until n) {
                    val l = left[f].toInt()
                    pcmDataBaos.write(l and 0xFF)
                    pcmDataBaos.write((l ushr 8) and 0xFF)
                    val r = right[f].toInt()
                    pcmDataBaos.write(r and 0xFF)
                    pcmDataBaos.write((r ushr 8) and 0xFF)
                }
            } else if (channelPcm.isNotEmpty()) {
                val mono = channelPcm[0]
                for (f in 0 until mono.size) {
                    val m = mono[f].toInt()
                    pcmDataBaos.write(m and 0xFF)
                    pcmDataBaos.write((m ushr 8) and 0xFF)
                }
            }
        }

        // Write final WAV file
        val pcmBytes = pcmDataBaos.toByteArray()
        BufferedOutputStream(FileOutputStream(tempFile)).use { out ->
            writeWavHeader(out, pcmBytes.size, entry.sampleRate, chCount)
            out.write(pcmBytes)
        }
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

        // Temporary decode buffers (each byte of ADPCM decompresses to 2 PCM samples)
        val rawBlock = ByteArray(blkSize)
        val maxSamplesPerChannel = Math.max((blkSize / 2048 + 2) * 4088, blkSize * 2 + 8192)
        val chLeftPcm = ShortArray(maxSamplesPerChannel)
        val chRightPcm = ShortArray(maxSamplesPerChannel)

        var totalFramesDecoded = 0
        val pcmDataBaos = java.io.ByteArrayOutputStream(blockCount * blkSize * 4)

        for (b in 0 until blockCount) {
            val curBlockIdx = startBlock + b
            if (curBlockIdx >= entry.numBlocks) break

            val blockOffset = baseDataOffset + curBlockIdx.toLong() * blkSize.toLong()
            val buf = ByteBuffer.wrap(rawBlock)
            channel.position(blockOffset)
            val bytesRead = channel.read(buf)
            if (bytesRead <= 0) break

            // RSXXTEA decrypt block payload if encrypted
            if (entry.isMultiChannelEncrypt && !entry.isWholeFileEncrypted) {
                Rpf7Crypto.decryptRsxxtea(rawBlock, 0, bytesRead, keys.awcKey)
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

            // Decode each channel
            var ch0Samples = 0
            var ch1Samples = 0

            for (c in 0 until chCount) {
                val bcnt = blockCounts[c] * 2048
                val sCount = sampleCounts[c]

                if (c == 0) {
                    ch0Samples = decodeAdpcm(rawBlock, rpos, bcnt, sCount, chLeftPcm, 0)
                } else if (c == 1) {
                    ch1Samples = decodeAdpcm(rawBlock, rpos, bcnt, sCount, chRightPcm, 0)
                }
                rpos += bcnt
            }

            // Interleave PCM frames (Left, Right)
            val frames = if (chCount > 1) Math.min(ch0Samples, ch1Samples) else ch0Samples
            for (f in 0 until frames) {
                val l = chLeftPcm[f].toInt()
                pcmDataBaos.write(l and 0xFF)
                pcmDataBaos.write((l ushr 8) and 0xFF)

                if (chCount > 1) {
                    val r = chRightPcm[f].toInt()
                    pcmDataBaos.write(r and 0xFF)
                    pcmDataBaos.write((r ushr 8) and 0xFF)
                }
            }
            totalFramesDecoded += frames
        }

        // Write final WAV file
        val pcmBytes = pcmDataBaos.toByteArray()
        BufferedOutputStream(FileOutputStream(tempFile)).use { out ->
            writeWavHeader(out, pcmBytes.size, entry.sampleRate, chCount)
            out.write(pcmBytes)
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
                        val outPcm = ShortArray(subTrack.numSamples + 2048)
                        val decodedCount = decodeAdpcm(clipRaw, 0, clipRaw.size, subTrack.numSamples, outPcm, 0)
                        val out = ByteArray(decodedCount * 2)
                        for (i in 0 until decodedCount) {
                            val s = outPcm[i].toInt()
                            out[i * 2] = (s and 0xFF).toByte()
                            out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
                        }
                        out
                    }

                    BufferedOutputStream(FileOutputStream(tempFile)).use { out ->
                        writeWavHeader(out, pcmBytes.size, subTrack.sampleRate, 1)
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
        val totalDataLen = pcmDataBytes + 36
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xFF).toByte()
        header[5] = ((totalDataLen ushr 8) and 0xFF).toByte()
        header[6] = ((totalDataLen ushr 16) and 0xFF).toByte()
        header[7] = ((totalDataLen ushr 24) and 0xFF).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0 // 1 = PCM
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = ((sampleRate ushr 8) and 0xFF).toByte()
        header[26] = ((sampleRate ushr 16) and 0xFF).toByte()
        header[27] = ((sampleRate ushr 24) and 0xFF).toByte()
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = ((byteRate ushr 8) and 0xFF).toByte()
        header[30] = ((byteRate ushr 16) and 0xFF).toByte()
        header[31] = ((byteRate ushr 24) and 0xFF).toByte()
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = 16; header[35] = 0 // 16-bit
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmDataBytes and 0xFF).toByte()
        header[41] = ((pcmDataBytes ushr 8) and 0xFF).toByte()
        header[42] = ((pcmDataBytes ushr 16) and 0xFF).toByte()
        header[43] = ((pcmDataBytes ushr 24) and 0xFF).toByte()

        out.write(header)
    }

    private fun cleanCacheIfNeeded(cacheDir: File, currentSafeFileName: String) {
        val allFiles = ArrayList<File>()
        cacheDir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
        File(context.cacheDir, "at3_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
        File(context.cacheDir, "gen2_stream_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
        File(context.cacheDir, "rpf3_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
        File(context.cacheDir, "gta5_rpf_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
        File(context.cacheDir, "gta4_rpf_tracks_v3").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
        File(context.cacheDir, "sa_stream_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
        File(context.cacheDir, "sa_raw_tracks").listFiles()?.filter { !it.name.endsWith(".tmp") }?.let { allFiles.addAll(it) }
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
