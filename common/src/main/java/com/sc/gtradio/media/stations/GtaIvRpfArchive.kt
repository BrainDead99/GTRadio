package com.sc.gtradio.media.stations

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
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class GtaIvRpfEntry(
    val name: String,
    val offset: Long,
    val size: Long,
    val isWaveBank: Boolean
)

data class GtaIvBankSubTrack(
    val parentEntryName: String,
    val subName: String,
    val dataOffsetInRpf: Long,
    val numSamples16Bit: Int,
    val sampleRate: Int,
    val isCompressed: Boolean
)

class GtaIvRpfArchive(
    private val context: Context,
    val rpfName: String,
    val sourceUri: Uri
) {
    private val entries = LinkedHashMap<String, GtaIvRpfEntry>()
    private val bankSubTracks = LinkedHashMap<String, GtaIvBankSubTrack>()
    private var indexed = false

    companion object {
        private val RPF3_AES_KEY = ByteArray(32) // Runtime key extraction added in GtaIvKeyManager

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

    @Synchronized
    fun ensureIndexed() {
        if (indexed) return
        indexed = true
        GtaIvMetadataRepository.ensureLoaded(context)
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    indexRpf(ch)
                }
            }
            GTRadioLogger.log("GtaIvRpfArchive[$rpfName]: Indexed ${entries.size} entries and ${bankSubTracks.size} bank sub-tracks")
        } catch (e: Exception) {
            GTRadioLogger.log("GtaIvRpfArchive[$rpfName]: Indexing failed", e)
        }
    }

    fun getFileEntries(): Collection<GtaIvRpfEntry> {
        ensureIndexed()
        return entries.values
    }

    fun getBankSubTracks(): Collection<GtaIvBankSubTrack> {
        ensureIndexed()
        return bankSubTracks.values
    }

    fun hasEntry(name: String): Boolean {
        ensureIndexed()
        val u = name.uppercase()
        return entries.containsKey(u) || bankSubTracks.containsKey(u)
    }

    private fun indexRpf(ch: FileChannel) {
        if (ch.size() < 2048L) return
        val hdrBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        readFully(ch, hdrBuf, 0L)
        hdrBuf.flip()

        val magicBytes = ByteArray(4)
        hdrBuf.get(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)
        if (magic != "RPF3") return

        val tocSize = hdrBuf.int
        val entryCount = hdrBuf.int
        hdrBuf.int // unknown
        val encryptedFlag = hdrBuf.int

        if (tocSize <= 0 || tocSize > 262144 || entryCount <= 0) return
        val paddedTocSize = (tocSize + 15) and -16
        val tocBuf = ByteBuffer.allocate(paddedTocSize)
        readFully(ch, tocBuf, 0x800L)
        var tocBytes = tocBuf.array()

        if (encryptedFlag != 0) {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(RPF3_AES_KEY, "AES"))
            for (round in 0 until 16) {
                tocBytes = cipher.doFinal(tocBytes)
            }
        }

        val tocOrder = ByteBuffer.wrap(tocBytes).order(ByteOrder.LITTLE_ENDIAN)
        val probe4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

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

            val entryName = GtaIvMetadataRepository.resolveHash(context, nameHash).uppercase()
            if (entryName == "NULLTRACK") continue

            probe4.clear()
            readFully(ch, probe4, fileOffset)
            val firstInt = probe4.getInt(0)
            val isBank = (firstInt == 28)

            val entry = GtaIvRpfEntry(entryName, fileOffset, fileSize, isBank)
            entries[entryName] = entry

            if (isBank) {
                indexSingleCBank(ch, entry)
            }
        }
    }

    private fun indexSingleCBank(ch: FileChannel, bankEntry: GtaIvRpfEntry) {
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
            val numSamples16Bit = wiBuf.getInt(16)
            val sampleRate = wiBuf.getShort(24).toInt() and 0xFFFF
            val isCompressed = if (wihSize > 32 && readLen >= 60) (wiBuf.getInt(56) > 0) else true

            val resolvedSubName = GtaIvMetadataRepository.resolveHash(context, if (wihHash != 0) wihHash else wiHash).uppercase()
            val subKey = if (resolvedSubName.startsWith("0X")) "${bankEntry.name}_$i" else resolvedSubName

            val absDataOffset = bankEntry.offset + headerSize.toLong() + sampleDataRelOffset
            if (numSamples16Bit > 0 && sampleRate in 8000..48000 && absDataOffset < bankEntry.offset + bankEntry.size) {
                bankSubTracks[subKey] = GtaIvBankSubTrack(
                    parentEntryName = bankEntry.name,
                    subName = subKey,
                    dataOffsetInRpf = absDataOffset,
                    numSamples16Bit = numSamples16Bit,
                    sampleRate = sampleRate,
                    isCompressed = isCompressed
                )
            }
        }
    }

    fun materializeAudioUri(trackKey: String): Uri? {
        ensureIndexed()
        val cleanKey = trackKey.uppercase()
        val cacheDir = File(context.cacheDir, "gta4_rpf_tracks").apply { mkdirs() }
        val safeFileName = "${rpfName.removeSuffix(".rpf")}_${cleanKey.replace(Regex("[^A-Z0-9_]"), "_")}.wav"
        val outFile = File(cacheDir, safeFileName)
        if (outFile.exists() && outFile.length() > 44L) {
            return Uri.fromFile(outFile)
        }

        // Keep cache folder bounded so phone storage never fills up
        try {
            val cached = cacheDir.listFiles()
            if (cached != null && cached.size > 40) {
                cached.sortBy { it.lastModified() }
                for (i in 0 until 15) cached[i].delete()
            }
        } catch (_: Exception) {}

        val subTrack = bankSubTracks[cleanKey]
        val fileEntry = entries[cleanKey]
        if (subTrack == null && fileEntry == null) return null

        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    if (subTrack != null) {
                        decodeBankSubTrackToWav(ch, subTrack, outFile)
                    } else if (fileEntry != null) {
                        if (fileEntry.isWaveBank) {
                            val firstSub = bankSubTracks.values.firstOrNull { it.parentEntryName == fileEntry.name }
                            if (firstSub != null) decodeBankSubTrackToWav(ch, firstSub, outFile)
                        } else {
                            decodeMultiCToWav(ch, fileEntry, outFile)
                        }
                    }
                }
            }
            if (outFile.exists() && outFile.length() > 44L) {
                return Uri.fromFile(outFile)
            }
        } catch (e: Exception) {
            GTRadioLogger.log("GtaIvRpfArchive[$rpfName]: Failed decoding $cleanKey", e)
            outFile.delete()
        }
        return null
    }

    private fun decodeBankSubTrackToWav(ch: FileChannel, sub: GtaIvBankSubTrack, outFile: File) {
        val totalSamples = sub.numSamples16Bit
        val pcmBytesLen = totalSamples * 2
        val byteCountToRead = if (sub.isCompressed) (totalSamples + 1) / 2 else pcmBytesLen

        val inBuf = ByteArray(byteCountToRead)
        readFully(ch, ByteBuffer.wrap(inBuf), sub.dataOffsetInRpf)

        BufferedOutputStream(FileOutputStream(outFile), 65536).use { out ->
            writeWavHeader(out, pcmBytesLen, sub.sampleRate, 1)
            if (!sub.isCompressed) {
                out.write(inBuf)
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

    private fun decodeMultiCToWav(ch: FileChannel, entry: GtaIvRpfEntry, outFile: File) {
        val hdrBuf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        if (readFully(ch, hdrBuf, entry.offset) < 128) return

        val numBlocks = hdrBuf.getInt(8)
        val blockSize = hdrBuf.getInt(12)
        val chTableOffset = hdrBuf.getLong(16).toInt()
        val numChannels = hdrBuf.getInt(36)
        val headerSize = hdrBuf.getInt(44)

        if (numBlocks <= 0 || blockSize <= 2048 || numChannels <= 0 || headerSize <= 0) return

        val detailOffset = chTableOffset + (numChannels * 16)
        val totalSamplesPerCh = if (detailOffset + 28 <= 512) hdrBuf.getInt(detailOffset + 16) else 0
        var sampleRate = if (detailOffset + 28 <= 512) (hdrBuf.getShort(detailOffset + 24).toInt() and 0xFFFF) else 32000
        if (sampleRate !in 8000..48000) sampleRate = 32000

        val outChannels = if (numChannels >= 2) 2 else 1
        // Pre-scan blocks to compute exact total samples if header value is 0
        val blockHeaderBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        var computedTotalSamples = 0
        for (b in 0 until numBlocks) {
            val blockStart = entry.offset + headerSize.toLong() + (b.toLong() * blockSize.toLong())
            if (blockStart + 2048L > entry.offset + entry.size) break
            blockHeaderBuf.clear()
            readFully(ch, blockHeaderBuf, blockStart)
            val tblOff = blockHeaderBuf.getLong(0).toInt().coerceIn(16, 64)
            val samplesInBlk = blockHeaderBuf.getInt(tblOff + 12)
            if (samplesInBlk > 0) computedTotalSamples += samplesInBlk
        }

        val finalSamplesPerCh = if (computedTotalSamples > 0) computedTotalSamples else totalSamplesPerCh
        if (finalSamplesPerCh <= 0) return

        val totalPcmBytes = finalSamplesPerCh * outChannels * 2
        val leftState = AdpcmState()
        val rightState = AdpcmState()

        val blockBytes = ByteArray(blockSize)
        val pcmOutBuf = ByteArray(16384)
        var pcmOutPos = 0

        BufferedOutputStream(FileOutputStream(outFile), 65536).use { out ->
            writeWavHeader(out, totalPcmBytes, sampleRate, outChannels)

            for (b in 0 until numBlocks) {
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

                var decodedInBlk = 0
                for (i in 0 until numBytesPerCh) {
                    val bL = blockBytes[ch0ByteOff + i].toInt() and 0xFF
                    val bR = if (outChannels == 2) (blockBytes[ch1ByteOff + i].toInt() and 0xFF) else 0

                    // Sample 1 (low nibble)
                    if (decodedInBlk < ch0Samples) {
                        val sL1 = decodeNibble(bL and 0x0F, leftState).toInt()
                        pcmOutBuf[pcmOutPos++] = (sL1 and 0xFF).toByte()
                        pcmOutBuf[pcmOutPos++] = ((sL1 shr 8) and 0xFF).toByte()
                        if (outChannels == 2) {
                            val sR1 = decodeNibble(bR and 0x0F, rightState).toInt()
                            pcmOutBuf[pcmOutPos++] = (sR1 and 0xFF).toByte()
                            pcmOutBuf[pcmOutPos++] = ((sR1 shr 8) and 0xFF).toByte()
                        }
                        decodedInBlk++
                    }

                    // Sample 2 (high nibble)
                    if (decodedInBlk < ch0Samples) {
                        val sL2 = decodeNibble((bL shr 4) and 0x0F, leftState).toInt()
                        pcmOutBuf[pcmOutPos++] = (sL2 and 0xFF).toByte()
                        pcmOutBuf[pcmOutPos++] = ((sL2 shr 8) and 0xFF).toByte()
                        if (outChannels == 2) {
                            val sR2 = decodeNibble((bR shr 4) and 0x0F, rightState).toInt()
                            pcmOutBuf[pcmOutPos++] = (sR2 and 0xFF).toByte()
                            pcmOutBuf[pcmOutPos++] = ((sR2 shr 8) and 0xFF).toByte()
                        }
                        decodedInBlk++
                    }

                    if (pcmOutPos >= pcmOutBuf.size - 8) {
                        out.write(pcmOutBuf, 0, pcmOutPos)
                        pcmOutPos = 0
                    }
                }
            }
            if (pcmOutPos > 0) {
                out.write(pcmOutBuf, 0, pcmOutPos)
            }
        }
    }

    private fun writeWavHeader(out: BufferedOutputStream, pcmDataBytes: Int, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val blockAlign = (channels * 2).toShort()
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray(Charsets.US_ASCII))
        hdr.putInt(36 + pcmDataBytes)
        hdr.put("WAVE".toByteArray(Charsets.US_ASCII))
        hdr.put("fmt ".toByteArray(Charsets.US_ASCII))
        hdr.putInt(16)
        hdr.putShort(1.toShort()) // PCM
        hdr.putShort(channels.toShort())
        hdr.putInt(sampleRate)
        hdr.putInt(byteRate)
        hdr.putShort(blockAlign)
        hdr.putShort(16.toShort()) // 16-bit
        hdr.put("data".toByteArray(Charsets.US_ASCII))
        hdr.putInt(pcmDataBytes)
        out.write(hdr.array())
    }
}
